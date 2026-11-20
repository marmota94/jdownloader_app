package org.jdownloader.remote.data

import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64
import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*

@Singleton class RemoteRepository @Inject constructor(val api: MyJdApiClient) : DownloaderRepository {
    override suspend fun login(email: String, password: String, rememberSession: Boolean) =
        api.login(email, password, rememberSession)
    override suspend fun devices() = api.devices()
    override suspend fun select(device: Device) = api.select(device)
    override suspend fun rpc(path: String, vararg params: JsonElement) = api.rpc(path, params.toList())
    override suspend fun snapshot(collector: Boolean): Snapshot {
        val ns = if (collector) "/linkgrabberv2" else "/downloadsV2"
        val query = buildJsonObject { put("startAt", 0); put("maxResults", -1); put("bytesLoaded", true); put("bytesTotal", true); put("status", true); put("speed", true) }
        val packages = rpc("$ns/queryPackages", query).transfers()
        val links = rpc("$ns/queryLinks", query).transfers()
        val speed = rpc("/downloadcontroller/getSpeedInBps").jsonPrimitive.longOrNull ?: 0
        val state = rpc("/downloadcontroller/getCurrentState").jsonPrimitive.content
        val captchas = (rpc("/captcha/list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Snapshot(packages, links, speed, state, captchas)
    }
    override suspend fun addLinks(links: String, password: String, name: String) {
        rpc("/linkgrabberv2/addLinks", buildJsonObject { put("links", links); put("extractPassword", password); put("downloadPassword", password); put("packageName", name); put("autostart", false) })
    }
    override suspend fun uploadContainer(type: String, bytes: ByteArray) {
        require(type in setOf("dlc", "ccf", "rsdf"))
        rpc("/linkgrabberv2/addContainer", JsonPrimitive(type), JsonPrimitive("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)))
    }
    override suspend fun logout() = api.logout()
}
