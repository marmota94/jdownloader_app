package org.jdownloader.remote.data

import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*

@Singleton class RemoteRepository @Inject constructor(
    private val api: MyJdApiClient,
    private val settings: SettingsRepository,
    private val completionMonitor: PackageCompletionMonitor
) : DownloaderRepository {
    override val reconnecting = api.reconnecting
    override suspend fun login(email: String, password: String, rememberSession: Boolean) =
        api.login(email, password, rememberSession)
    override suspend fun devices() = api.devices()
    override suspend fun deviceOverview(deviceId: String) = api.deviceOverview(deviceId)
    override suspend fun serverLimits() = api.serverLimits()
    override suspend fun saveServerLimits(value: ServerLimits) = api.saveServerLimits(value)
    override suspend fun checkServerUpdates() = api.checkServerUpdates()
    override suspend fun advancedSettings(search: String) = api.advancedSettings(search)
    override suspend fun saveAdvancedSetting(setting: AdvancedSetting, value: JsonElement) = api.saveAdvancedSetting(setting, value)
    override suspend fun addArchivePassword(linkIds: List<Long>, packageIds: List<Long>, password: String) = api.addArchivePassword(linkIds, packageIds, password)
    override suspend fun archiveInfo(linkIds: List<Long>, packageIds: List<Long>) = api.archiveInfo(linkIds, packageIds)
    override suspend fun saveArchiveOptions(id: String, autoExtract: Boolean?, extractPath: String, password: String) = api.saveArchiveOptions(id, autoExtract, extractPath, password)
    override suspend fun startExtraction(linkIds: List<Long>, packageIds: List<Long>) = api.startExtraction(linkIds, packageIds)
    override suspend fun cancelExtraction(controllerId: Long) = api.cancelExtraction(controllerId)
    override suspend fun select(device: Device) = api.select(device)
    override suspend fun rpc(path: String, vararg params: JsonElement) = api.rpc(path, params.toList())
    override suspend fun transfers(collector: Boolean): TransferListData {
        val ns = if (collector) "/linkgrabberv2" else "/downloadsV2"
        val query = buildJsonObject {
            put("startAt", 0); put("maxResults", -1); put("bytesLoaded", true); put("bytesTotal", true)
            put("status", true); put("speed", true); put("enabled", true); put("finished", true)
            put("running", true); put("eta", true); put("host", true)
        }
        val packages = if (collector) rpc("$ns/queryPackages", query).transfers()
            else downloadPackages(query)
        val links = rpc("$ns/queryLinks", query).transfers()
        return TransferListData(packages, links)
    }
    private suspend fun downloadPackages(query: JsonObject): List<Transfer> {
        val deviceId = settings.deviceId()
        if (deviceId == null || !settings.settings.first().completionAlerts) {
            return rpc("/downloadsV2/queryPackages", query).transfers()
        }
        return observedPackages(deviceId, query)
    }
    override suspend fun checkPackageCompletion() {
        if (!settings.settings.first().completionAlerts) return
        val deviceId = settings.deviceId() ?: return
        observedPackages(deviceId, buildJsonObject { put("startAt", 0); put("maxResults", -1) })
    }
    private suspend fun observedPackages(deviceId: String, query: JsonObject): List<Transfer> {
        val completionQuery = JsonObject(query + ("finished" to JsonPrimitive(true)))
        return completionMonitor.check(deviceId) {
            api.queryPackages(deviceId, completionQuery).jsonArray.transfers()
        }
    }
    override suspend fun downloadStatus(): DownloadStatus {
        val speed = rpc("/downloadcontroller/getSpeedInBps").jsonPrimitive.longOrNull ?: 0
        val state = rpc("/downloadcontroller/getCurrentState").jsonPrimitive.content
        return DownloadStatus(speed, state)
    }
    override suspend fun captchas(): List<JsonObject> =
        (rpc("/captcha/list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    override suspend fun addLinks(links: String, password: String, name: String, archivePassword: String) {
        rpc("/linkgrabberv2/addLinks", buildJsonObject { put("links", links); put("extractPassword", archivePassword); put("downloadPassword", password); put("packageName", name); put("autostart", false) })
    }
    override suspend fun uploadContainer(type: String, bytes: ByteArray) {
        require(type in setOf("dlc", "ccf", "rsdf"))
        rpc("/linkgrabberv2/addContainer", JsonPrimitive(type), JsonPrimitive("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)))
    }
    override suspend fun logout() = api.logout()
}
