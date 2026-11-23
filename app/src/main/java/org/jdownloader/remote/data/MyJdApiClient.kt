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
import kotlinx.coroutines.flow.asStateFlow
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
    private val recoveringSession = MutableStateFlow(false)
    val reconnecting = recoveringSession.asStateFlow()
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
            recoveringSession.value = true
            try {
                recoverSession(s)
            } finally {
                recoveringSession.value = false
            }
            block(current())
        }
    }
    private suspend fun recoverSession(s: Session) {
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
    suspend fun rpc(path: String, params: List<JsonElement>): JsonElement =
        callDevice(path, params, targetId = null)

    suspend fun queryPackages(deviceId: String, query: JsonObject): JsonElement =
        callDevice("/downloadsV2/queryPackages", listOf(query), targetId = deviceId)

    suspend fun deviceOverview(deviceId: String): DeviceOverview =
        callDevice("/toolbar/getStatus", emptyList(), targetId = deviceId).deviceOverview()

    private suspend fun setting(key: String) = rpc("/config/get", listOf(
        JsonPrimitive("org.jdownloader.settings.GeneralSettings"), JsonNull, JsonPrimitive(key)))

    private suspend fun setSetting(key: String, value: JsonElement) {
        val saved = rpc("/config/set", listOf(JsonPrimitive("org.jdownloader.settings.GeneralSettings"), JsonNull, JsonPrimitive(key), value))
        if (saved.jsonPrimitive.booleanOrNull != true) throw ApiException("REQUEST_FAILED")
    }

    suspend fun serverLimits(): ServerLimits {
        val enabled = setting("DownloadSpeedLimitEnabled").jsonPrimitive.boolean
        val speed = setting("DownloadSpeedLimit").jsonPrimitive.int
        val downloads = setting("MaxSimultaneDownloads").jsonPrimitive.int
        val chunks = setting("MaxChunksPerFile").jsonPrimitive.int
        val perHost = setting("MaxSimultaneDownloadsPerHost").jsonPrimitive.int
        return ServerLimits(if (enabled) speed else 0, downloads, chunks, perHost)
    }

    suspend fun saveServerLimits(value: ServerLimits) {
        if (value.speedLimit > 0) setSetting("DownloadSpeedLimit", JsonPrimitive(value.speedLimit))
        setSetting("MaxSimultaneDownloads", JsonPrimitive(value.maxDownloads))
        setSetting("MaxChunksPerFile", JsonPrimitive(value.maxChunks))
        setSetting("MaxSimultaneDownloadsPerHost", JsonPrimitive(value.maxPerHost))
        setSetting("DownloadSpeedLimitEnabled", JsonPrimitive(value.speedLimit > 0))
    }

    suspend fun checkServerUpdates(): Boolean {
        rpc("/update/runUpdateCheck", emptyList())
        return rpc("/update/isUpdateAvailable", emptyList()).jsonPrimitive.boolean
    }

    suspend fun advancedSettings(search: String): List<AdvancedSetting> {
        require(search.trim().length in 2..100)
        val query = buildJsonObject {
            put("pattern", "(?i).*" + Regex.escape(search.trim()) + ".*")
            put("values", true); put("description", true); put("enumInfo", true); put("includeExtensions", true)
        }
        return rpc("/config/query", listOf(query)).jsonArray.map { entry ->
            val obj = entry.jsonObject
            AdvancedSetting(entry.text("interfaceName"), obj["storage"] ?: JsonNull, entry.text("key"), entry.text("abstractType"),
                obj["value"] ?: JsonNull, entry.text("docs"), (obj["enumOptions"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                })
        }.sortedBy { it.key }
    }

    suspend fun saveAdvancedSetting(setting: AdvancedSetting, value: JsonElement): JsonElement {
        require(setting.interfaceName.isNotBlank() && setting.key.isNotBlank())
        val saved = rpc("/config/set", listOf(JsonPrimitive(setting.interfaceName), setting.storage, JsonPrimitive(setting.key), value))
        if (saved.jsonPrimitive.booleanOrNull != true) throw ApiException("REQUEST_FAILED")
        return rpc("/config/get", listOf(JsonPrimitive(setting.interfaceName), setting.storage, JsonPrimitive(setting.key)))
    }

    suspend fun addArchivePassword(linkIds: List<Long>, packageIds: List<Long>, password: String) {
        require(password.isNotBlank() && (linkIds.isNotEmpty() || packageIds.isNotEmpty()))
        val archives = rpc("/extraction/getArchiveInfo", listOf(JsonArray(linkIds.map { JsonPrimitive(it) }), JsonArray(packageIds.map { JsonPrimitive(it) })))
            .jsonArray.map { it.text("archiveId") }.filter { it.isNotBlank() }.distinct()
        if (archives.isEmpty()) throw ApiException("ARCHIVE_NOT_FOUND")
        val settings = rpc("/extraction/getArchiveSettings", listOf(JsonArray(archives.map { JsonPrimitive(it) }))).jsonArray
        for (id in archives) {
            val current = settings.firstOrNull { it.text("archiveId") == id } ?: throw ApiException("ARCHIVE_NOT_FOUND")
            val passwords = (current.jsonObject["passwords"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            val saved = rpc("/extraction/setArchiveSettings", listOf(JsonPrimitive(id), buildJsonObject {
                put("passwords", JsonArray((passwords + password).distinct().map { JsonPrimitive(it) }))
            }))
            if (saved.jsonPrimitive.booleanOrNull != true) throw ApiException("REQUEST_FAILED")
        }
    }

    suspend fun archiveInfo(linkIds: List<Long>, packageIds: List<Long>): List<ArchiveInfo> {
        require(linkIds.isNotEmpty() || packageIds.isNotEmpty())
        val archives = rpc("/extraction/getArchiveInfo", listOf(JsonArray(linkIds.map { JsonPrimitive(it) }), JsonArray(packageIds.map { JsonPrimitive(it) })))
            .jsonArray.filter { it.text("archiveId").isNotBlank() }.distinctBy { it.text("archiveId") }
        if (archives.isEmpty()) return emptyList()
        val settings = rpc("/extraction/getArchiveSettings", listOf(JsonArray(archives.map { JsonPrimitive(it.text("archiveId")) })))
            .jsonArray.associateBy { it.text("archiveId") }
        return archives.map { archive ->
            val id = archive.text("archiveId")
            ArchiveInfo(id, archive.text("archiveName"), archive.text("controllerId").toLongOrNull() ?: -1,
                archive.text("controllerStatus"), (archive.jsonObject["states"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },
                settings[id]?.jsonObject ?: throw ApiException("ARCHIVE_NOT_FOUND"))
        }
    }

    suspend fun saveArchiveOptions(id: String, autoExtract: Boolean?, extractPath: String, password: String) {
        require(id.isNotBlank())
        val current = rpc("/extraction/getArchiveSettings", listOf(JsonArray(listOf(JsonPrimitive(id)))))
            .jsonArray.firstOrNull { it.text("archiveId") == id }?.jsonObject ?: throw ApiException("ARCHIVE_NOT_FOUND")
        val patch = archiveSettingsPatch(current, autoExtract, extractPath, password)
        if (patch.isEmpty()) return
        if (rpc("/extraction/setArchiveSettings", listOf(JsonPrimitive(id), patch)).jsonPrimitive.booleanOrNull != true) throw ApiException("REQUEST_FAILED")
    }

    suspend fun startExtraction(linkIds: List<Long>, packageIds: List<Long>) {
        require(linkIds.isNotEmpty() || packageIds.isNotEmpty())
        val result = rpc("/extraction/startExtractionNow", listOf(JsonArray(linkIds.map { JsonPrimitive(it) }), JsonArray(packageIds.map { JsonPrimitive(it) }))).jsonObject
        if (result.values.none { (it as? JsonPrimitive)?.booleanOrNull == true }) throw ApiException("EXTRACTION_NOT_READY")
    }

    suspend fun cancelExtraction(controllerId: Long) {
        require(controllerId >= 0)
        if (rpc("/extraction/cancelExtraction", listOf(JsonPrimitive(controllerId))).jsonPrimitive.booleanOrNull != true) throw ApiException("REQUEST_FAILED")
    }

    private suspend fun callDevice(path: String, params: List<JsonElement>, targetId: String?): JsonElement = mutex.withLock { authenticated { s ->
        val selected = device
        val d = if (targetId != null && selected?.id != targetId) Device(targetId, "")
            else selected ?: throw ApiException("DEVICE_REQUIRED")
        val host = direct.takeIf { d.id == selected?.id }
        if (host == null) deviceCall(s, d, path, params, base) else try {
            deviceCall(s, d, path, params, host)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            direct = null; connection.value = Connection.CLOUD
            // A decrypted API rejection is definitive, not an ambiguous delivery.
            // In particular TOKEN_INVALID must reach authenticated() to reconnect.
            if (e is ApiException) throw e
            // Mutations can have reached the LAN device before a timeout: never replay them.
            val readOnly = path in setOf("/toolbar/getStatus", "/update/isUpdateAvailable", "/config/query", "/extraction/getArchiveInfo", "/extraction/getArchiveSettings") || path.substringAfterLast('/') in setOf("queryPackages", "queryLinks", "getCurrentState", "getSpeedInBps", "list", "get", "ping")
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
