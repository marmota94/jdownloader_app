package org.jdownloader.remote.data

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class ApiException(val kind: String, val source: String = "") : Exception("$source $kind".trim())
class RequestIds { private val value = AtomicLong(System.currentTimeMillis()); fun next(): Long = value.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) } }

@Singleton class MyJdApiClient @Inject constructor(private val store: SessionStorage) {
    internal var http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 25000; connectTimeoutMillis = 5000 }; followRedirects = false }
    private val mutex = Mutex()
    private val ids = RequestIds()
    private var session: Session? = null
    private var loaded = false
    private var device: Device? = null
    private var direct: String? = null
    val connection = MutableStateFlow(Connection.CLOUD)
    private val base = "https://api.jdownloader.org"
    private val appKey = org.jdownloader.remote.BuildConfig.MYJD_APP_KEY
    private suspend fun current(): Session {
        if (!loaded) { session = store.loadSession(); loaded = true }
        val current = session ?: throw ApiException("LOGIN_REQUIRED")
        if (current.recovery != null && current.recovery.expiresAt <= System.currentTimeMillis()) {
            val expired = current.copy(recovery = null)
            persist(expired)
            return expired
        }
        return current
    }
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private suspend fun decode(response: HttpResponse, key: ByteArray, rid: Long): JsonObject {
        val raw = response.bodyAsText()
        if (response.status.value == 200 && raw.trimStart().startsWith("{")) throw ApiException("UNENCRYPTED_RESPONSE")
        val obj = if (raw.trimStart().startsWith("{")) Json.parseToJsonElement(raw).jsonObject
            else Json.parseToJsonElement(MyJdCrypto.decrypt(key, raw)).jsonObject
        if (response.status.value != 200 || "type" in obj) throw ApiException(obj.text("type").ifBlank { "HTTP_${response.status.value}" }, obj.text("src"))
        if (obj["rid"]?.jsonPrimitive?.longOrNull != rid) throw ApiException("RID_MISMATCH")
        return obj
    }
    private suspend fun server(path: String, params: List<Pair<String, String>>, key: ByteArray): JsonObject {
        val rid = store.reserveRequestId(ids.next())
        val query = path + "?" + (params + ("rid" to rid.toString())).joinToString("&") { encode(it.first) + "=" + encode(it.second) }
        return decode(http.get(base + query + "&signature=" + MyJdCrypto.sign(key, query)), key, rid)
    }
    suspend fun login(email: String, password: String, rememberSession: Boolean = false) = mutex.withLock {
        val normalized = email.trim().lowercase(java.util.Locale.ROOT)
        val login = MyJdCrypto.secret(normalized, password, "server")
        val secret = MyJdCrypto.secret(normalized, password, "device")
        val recovery = if (rememberSession) SignInRecovery(
            normalized, MyJdCrypto.hex(login), System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(30)
        ) else null
        persist(connect(normalized, login, MyJdCrypto.hex(secret), recovery))
        device = null
    }
    private suspend fun connect(email: String, loginKey: ByteArray, deviceSecret: String, recovery: SignInRecovery?): Session {
        val response = server("/my/connect", listOf("email" to email, "appkey" to appKey), loginKey)
        val token = response.text("sessiontoken")
        return Session(token, response.text("regaintoken"), MyJdCrypto.hex(MyJdCrypto.token(loginKey, token)), deviceSecret, recovery)
    }
    private suspend fun persist(next: Session?) = withContext(NonCancellable) {
        store.saveSession(next)
        session = next
        loaded = true
        direct = null
        connection.value = Connection.CLOUD
    }
    private suspend fun reconnect(old: Session) {
        val key = MyJdCrypto.unhex(old.serverKey)
        val r = server("/my/reconnect", listOf("sessiontoken" to old.session, "regaintoken" to old.regain, "appkey" to appKey), key)
        val token = r.text("sessiontoken")
        val next = old.copy(session = token, regain = r.text("regaintoken"), serverKey = MyJdCrypto.hex(MyJdCrypto.token(key, token)))
        persist(next)
    }
    private suspend fun <T> authenticated(block: suspend (Session) -> T): T {
        val s = current()
        return try { block(s) } catch (e: ApiException) {
            if (e.kind != "TOKEN_INVALID" && !(e.kind == "SESSION" && e.source == "DEVICE")) throw e
            try {
                reconnect(s)
            } catch (rejected: ApiException) {
                if (rejected.kind !in setOf("TOKEN_INVALID", "AUTH_FAILED")) throw rejected
                val recovery = s.recovery?.takeIf { it.expiresAt > System.currentTimeMillis() }
                if (recovery == null) {
                    persist(null)
                    throw ApiException("LOGIN_REQUIRED")
                }
                try {
                    persist(connect(recovery.email, MyJdCrypto.unhex(recovery.loginKey), s.deviceSecret, recovery))
                } catch (failed: ApiException) {
                    if (failed.kind != "AUTH_FAILED") throw failed
                    persist(null)
                    throw ApiException("LOGIN_REQUIRED")
                }
            }
            block(current())
        }
    }
    suspend fun devices(): List<Device> = mutex.withLock { authenticated { s ->
        val result = server("/my/listdevices", listOf("sessiontoken" to s.session), MyJdCrypto.unhex(s.serverKey))
        (result["list"] as? JsonArray).orEmpty().map { Device(it.text("id"), it.text("name"), it.text("type")) }
    } }
    private suspend fun deviceCall(s: Session, d: Device, path: String, params: List<JsonElement>, host: String): JsonElement {
        val rid = store.reserveRequestId(ids.next())
        val body = buildJsonObject {
            put("apiVer", 1); put("url", path); put("rid", rid)
            // Each argument is a JSON document stored in a string, including quoted
            // JSON strings. This matches the official Java client's String[] params.
            put("params", JsonArray(params.map { JsonPrimitive(it.toString()) }))
        }
        val token = MyJdCrypto.token(MyJdCrypto.unhex(s.deviceSecret), s.session)
        val response = http.post("$host/t_${s.session}_${encode(d.id)}$path") {
            contentType(ContentType.parse("application/aesjson-jd; charset=utf-8")); setBody(MyJdCrypto.encrypt(token, body.toString()))
            if (host != base) timeout { requestTimeoutMillis = 2500; connectTimeoutMillis = 1200 }
        }
        return decode(response, token, rid)["data"] ?: JsonNull
    }
    suspend fun select(d: Device) = mutex.withLock {
        device = d; direct = null; connection.value = Connection.CLOUD; store.saveDevice(d.id)
        try { authenticated { s ->
            val info = deviceCall(s, d, "/device/getDirectConnectionInfos", emptyList(), base)
            val candidates = (info as? JsonObject)?.get("infos") as? JsonArray ?: JsonArray(emptyList())
            for (candidate in candidates) {
                val ip = candidate.text("ip"); val port = candidate.number("port")
                // Only literal private IPv4 addresses supplied by the authenticated device.
                val octets = ip.split('.')
                if (octets.size != 4 || octets.any { !it.matches(Regex("[0-9]{1,3}")) || (it.toIntOrNull() ?: -1) !in 0..255 } || port !in 1..65535) continue
                val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
                if (!address.isSiteLocalAddress) continue
                val host = "http://$ip:$port"
                try { if (deviceCall(s, d, "/device/ping", emptyList(), host).jsonPrimitive.boolean) { direct = host; connection.value = Connection.LAN; break } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Next authenticated LAN candidate. */ }
            }
        } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is ApiException && e.kind in setOf("TOKEN_INVALID", "AUTH_FAILED", "LOGIN_REQUIRED")) throw e
            direct = null; connection.value = Connection.CLOUD
        }
    }
    suspend fun rpc(path: String, params: List<JsonElement>): JsonElement = mutex.withLock { authenticated { s ->
        val d = device ?: throw ApiException("DEVICE_REQUIRED")
        val host = direct
        if (host == null) deviceCall(s, d, path, params, base) else try {
            deviceCall(s, d, path, params, host)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            direct = null; connection.value = Connection.CLOUD
            // A decrypted API rejection is definitive, not an ambiguous delivery.
            // In particular TOKEN_INVALID must reach authenticated() to reconnect.
            if (e is ApiException) throw e
            // Mutations can have reached the LAN device before a timeout: never replay them.
            val readOnly = path.substringAfterLast('/') in setOf("queryPackages", "queryLinks", "getCurrentState", "getSpeedInBps", "list", "get", "ping")
            val neverConnected = e is java.net.ConnectException || e is io.ktor.client.network.sockets.ConnectTimeoutException
            if (!readOnly && !neverConnected) throw ApiException("DELIVERY_UNCERTAIN")
            deviceCall(s, d, path, params, base)
        }
    } }
    suspend fun logout(): Unit = mutex.withLock {
        try { session?.let { server("/my/disconnect", listOf("sessiontoken" to it.session), MyJdCrypto.unhex(it.serverKey)) } }
        finally { persist(null); device = null }
    }
}
