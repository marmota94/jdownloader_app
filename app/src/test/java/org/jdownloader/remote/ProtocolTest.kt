package org.jdownloader.remote

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.jdownloader.remote.data.*
import org.jdownloader.remote.domain.*
import org.junit.Assert.*
import org.junit.Test

private class MemorySessions : SessionStorage {
    var value: Session? = null
    override suspend fun loadSession() = value
    override suspend fun saveSession(value: Session?) { this.value = value }
    override suspend fun saveDevice(id: String) {}
}
class ProtocolTest {
    private val seeded = Session("00112233445566778899aabbccddeeff", "regain", "11".repeat(32), "22".repeat(32))
    private fun seededApi(handler: suspend MockRequestHandleScope.(HttpRequestData, JsonObject) -> HttpResponseData): MyJdApiClient {
        val store = MemorySessions().apply { value = seeded }
        val api = MyJdApiClient(store)
        api.http.close()
        api.http = HttpClient(MockEngine { request ->
            val token = MyJdCrypto.token(MyJdCrypto.unhex(seeded.deviceSecret), seeded.session)
            val payload = Json.parseToJsonElement(MyJdCrypto.decrypt(token, (request.body as TextContent).text)).jsonObject
            handler(request, payload)
        }) { install(HttpTimeout) }
        return api
    }
    private fun MockRequestHandleScope.encrypted(payload: JsonObject, data: JsonElement): HttpResponseData {
        val token = MyJdCrypto.token(MyJdCrypto.unhex(seeded.deviceSecret), seeded.session)
        return respond(MyJdCrypto.encrypt(token, buildJsonObject { put("rid", payload.getValue("rid")); put("data", data) }.toString()))
    }
    @Test fun backgroundPackageQueryWorksWithoutAnInMemoryDevice() = runTest {
        val api = seededApi { request, payload ->
            assertEquals("/t_${seeded.session}_saved-device/downloadsV2/queryPackages", request.url.encodedPath)
            val query = Json.parseToJsonElement(payload.getValue("params").jsonArray.single().jsonPrimitive.content)
            assertTrue(query.jsonObject.getValue("finished").jsonPrimitive.boolean)
            encrypted(payload, Json.parseToJsonElement("""[{"uuid":42,"name":"Folder","finished":true}]"""))
        }
        try {
            val packages = api.queryPackages("saved-device", buildJsonObject { put("finished", true) }).transfers()
            assertEquals(true, packages.single().finished)
        } finally { api.http.close() }
    }
    @Test fun signedConnectAndReconnectRotateCorrectKey() = runTest {
        val storage = MemorySessions(); val api = MyJdApiClient(storage)
        val loginKey = MyJdCrypto.secret("test@example.org", "secret", "server")
        val firstToken = "00112233445566778899aabbccddeeff"
        val secondToken = "ffeeddccbbaa99887766554433221100"
        var call = 0
        api.http.close()
        api.http = HttpClient(MockEngine { request ->
            val unsigned = request.url.encodedPath + "?" + request.url.encodedQuery.substringBefore("&signature=")
            val key = if (call == 0) loginKey else MyJdCrypto.token(loginKey, firstToken)
            val signingKey = if (call >= 3) MyJdCrypto.token(key, secondToken) else key
            assertEquals(MyJdCrypto.sign(signingKey, unsigned), request.url.parameters["signature"])
            val rid = request.url.parameters["rid"]!!.toLong()
            when (call++) {
                0 -> { assertEquals("/my/connect", request.url.encodedPath); respond(MyJdCrypto.encrypt(key, "{\"rid\":$rid,\"sessiontoken\":\"$firstToken\",\"regaintoken\":\"regain\"}")) }
                1 -> respond("{\"src\":\"MYJD\",\"type\":\"TOKEN_INVALID\"}", HttpStatusCode.Forbidden)
                2 -> { assertEquals("/my/reconnect", request.url.encodedPath); respond(MyJdCrypto.encrypt(key, "{\"rid\":$rid,\"sessiontoken\":\"$secondToken\",\"regaintoken\":\"next\"}")) }
                else -> {
                    assertEquals(MyJdCrypto.sign(MyJdCrypto.token(key, secondToken), unsigned), request.url.parameters["signature"])
                    respond(MyJdCrypto.encrypt(MyJdCrypto.token(key, secondToken), "{\"rid\":$rid,\"list\":[]}"))
                }
            }
        })
        try {
            api.login("TEST@example.org", "secret")
            api.devices()
            assertEquals(secondToken, storage.value!!.session)
            assertEquals(MyJdCrypto.hex(MyJdCrypto.token(MyJdCrypto.token(loginKey, firstToken), secondToken)), storage.value!!.serverKey)
        } finally { api.http.close() }
    }
    @Test fun rejectMismatchedResponseId() = runTest {
        val api = MyJdApiClient(MemorySessions()); api.http.close()
        api.http = HttpClient(MockEngine { respond(MyJdCrypto.encrypt(MyJdCrypto.secret("x", "y", "server"), "{\"rid\":1,\"sessiontoken\":\"00\",\"regaintoken\":\"r\"}")) })
        try { api.login("x", "y"); fail("must reject mismatched rid") } catch (e: ApiException) { assertEquals("RID_MISMATCH", e.kind) } finally { api.http.close() }
    }
    @Test fun rejectedReconnectUsesRememberedKeyAfterRestart() = runTest {
        val email = "test@example.org"
        val key = MyJdCrypto.secret(email, "secret", "server")
        val recovery = SignInRecovery(email, MyJdCrypto.hex(key), System.currentTimeMillis() + 60_000)
        val storage = MemorySessions().apply { value = seeded.copy(recovery = recovery) }
        val token = "ffeeddccbbaa99887766554433221100"
        val paths = mutableListOf<String>()
        val api = MyJdApiClient(storage)
        api.http.close()
        api.http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val rid = request.url.parameters["rid"]!!.toLong()
            when (paths.size) {
                1, 2 -> respond("""{"src":"MYJD","type":"TOKEN_INVALID"}""", HttpStatusCode.Forbidden)
                3 -> {
                    assertEquals(email, request.url.parameters["email"])
                    val unsigned = path + "?" + request.url.encodedQuery.substringBefore("&signature=")
                    assertEquals(MyJdCrypto.sign(key, unsigned), request.url.parameters["signature"])
                    respond(MyJdCrypto.encrypt(key, """{"rid":$rid,"sessiontoken":"$token","regaintoken":"next"}"""))
                }
                else -> respond(MyJdCrypto.encrypt(MyJdCrypto.token(key, token), """{"rid":$rid,"list":[]}"""))
            }
        })
        try {
            assertEquals(emptyList<Device>(), api.devices())
            assertEquals(listOf("/my/listdevices", "/my/reconnect", "/my/connect", "/my/listdevices"), paths)
            assertEquals(token, storage.value!!.session)
            assertEquals(recovery, storage.value!!.recovery)
            assertEquals(seeded.deviceSecret, storage.value!!.deviceSecret)
        } finally { api.http.close() }
    }

    @Test fun expiredRememberedKeyCannotStartNewSession() = runTest {
        val storage = MemorySessions().apply {
            value = seeded.copy(recovery = SignInRecovery("test@example.org", "11".repeat(32), 1))
        }
        val paths = mutableListOf<String>()
        val api = MyJdApiClient(storage)
        api.http.close()
        api.http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(storage.value?.recovery)
            respond("""{"src":"MYJD","type":"TOKEN_INVALID"}""", HttpStatusCode.Forbidden)
        })
        try {
            try {
                api.devices()
                fail("Expired recovery should require sign-in")
            } catch (e: ApiException) {
                assertEquals("LOGIN_REQUIRED", e.kind)
            }
            assertEquals(listOf("/my/listdevices", "/my/reconnect"), paths)
            assertNull(storage.value)
        } finally { api.http.close() }
    }
    @Test fun rpcSerializesEveryArgumentAsJsonAndUsesEncryptedDeviceRoute() = runTest {
        var checked = false
        val api = seededApi { request, payload ->
            if (payload.text("url") == "/device/getDirectConnectionInfos") encrypted(payload, buildJsonObject { put("infos", JsonArray(emptyList())) })
            else {
                assertEquals("/t_${seeded.session}_device%20one/downloadsV2/setPriority", request.url.encodedPath)
                assertEquals("application/aesjson-jd; charset=utf-8", request.body.contentType.toString())
                val params = payload.getValue("params").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("\"HIGH\"", "[12,13]", "[]", "true", "null", "{\"name\":\"ä\"}"), params)
                checked = true
                encrypted(payload, JsonPrimitive(true))
            }
        }
        try {
            api.select(Device("device one", "Computer"))
            api.rpc("/downloadsV2/setPriority", listOf(JsonPrimitive("HIGH"), JsonArray(listOf(JsonPrimitive(12), JsonPrimitive(13))), JsonArray(emptyList()), JsonPrimitive(true), JsonNull, buildJsonObject { put("name", "ä") }))
            assertTrue(checked)
        } finally { api.http.close() }
    }
    @Test fun failedLanReadFallsBackToCloud() = runTest {
        var lanRequests = 0; var cloudReads = 0
        val api = seededApi { request, payload ->
            when (payload.text("url")) {
                "/device/getDirectConnectionInfos" -> encrypted(payload, buildJsonObject { put("infos", JsonArray(listOf(buildJsonObject { put("ip", "192.168.1.2"); put("port", 3128) }))) })
                "/device/ping" -> encrypted(payload, JsonPrimitive(true))
                else -> if (request.url.host == "192.168.1.2") { lanRequests++; throw java.io.IOException("connection lost") }
                else { cloudReads++; encrypted(payload, JsonPrimitive(42)) }
            }
        }
        try {
            api.select(Device("id", "Computer")); assertEquals(Connection.LAN, api.connection.value)
            assertEquals(JsonPrimitive(42), api.rpc("/downloadcontroller/getSpeedInBps", emptyList()))
            assertEquals(Connection.CLOUD, api.connection.value)
            assertEquals(1, lanRequests); assertEquals(1, cloudReads)
        } finally { api.http.close() }
    }
    @Test fun ambiguousLanMutationIsNotReplayed() = runTest {
        var mutations = 0
        val api = seededApi { _, payload ->
            when (payload.text("url")) {
                "/device/getDirectConnectionInfos" -> encrypted(payload, buildJsonObject { put("infos", JsonArray(listOf(buildJsonObject { put("ip", "192.168.1.2"); put("port", 3128) }))) })
                "/device/ping" -> encrypted(payload, JsonPrimitive(true))
                else -> { mutations++; throw java.io.IOException("response lost after request sent") }
            }
        }
        try {
            api.select(Device("id", "Computer"))
            assertEquals(Connection.LAN, api.connection.value)
            try { api.rpc("/linkgrabberv2/addLinks", listOf(buildJsonObject { put("links", "https://example.org") })); fail("must report uncertain delivery") }
            catch (e: ApiException) { assertEquals("DELIVERY_UNCERTAIN", e.kind) }
            assertEquals(1, mutations); assertEquals(Connection.CLOUD, api.connection.value)
        } finally { api.http.close() }
    }
    @Test fun rejectedLanMutationKeepsDefinitiveApiError() = runTest {
        val api = seededApi { _, payload ->
            when (payload.text("url")) {
                "/device/getDirectConnectionInfos" -> encrypted(payload, buildJsonObject { put("infos", JsonArray(listOf(buildJsonObject { put("ip", "192.168.1.2"); put("port", 3128) }))) })
                "/device/ping" -> encrypted(payload, JsonPrimitive(true))
                else -> respond("{\"src\":\"DEVICE\",\"type\":\"BAD_PARAMETERS\"}", HttpStatusCode.BadRequest)
            }
        }
        try {
            api.select(Device("id", "Computer"))
            assertEquals(Connection.LAN, api.connection.value)
            try { api.rpc("/downloadsV2/setPriority", emptyList()); fail("must preserve error") }
            catch (e: ApiException) { assertEquals("BAD_PARAMETERS", e.kind) }
        } finally { api.http.close() }
    }
}
