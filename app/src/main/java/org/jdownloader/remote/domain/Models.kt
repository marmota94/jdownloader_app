package org.jdownloader.remote.domain

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

@Serializable data class Device(val id: String, val name: String, val type: String = "")
@Serializable data class Session(
    val session: String,
    val regain: String,
    val serverKey: String,
    val deviceSecret: String,
    val recovery: SignInRecovery? = null
)
@Serializable data class SignInRecovery(val email: String, val loginKey: String, val expiresAt: Long)
enum class Connection { CLOUD, LAN }
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class AccentColor { TEAL, BLUE, PURPLE, GREEN, ORANGE, PINK, RED, AMBER, LIME, CYAN, INDIGO, LAVENDER, MAGENTA, SLATE }
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamic: Boolean = true, val locale: String = "",
    val cnl: Boolean = false, val accent: AccentColor = AccentColor.TEAL,
    val completionAlerts: Boolean = false
)
data class Transfer(
    val id: Long, val name: String, val loaded: Long, val total: Long, val status: String,
    val packageId: Long = 0, val finished: Boolean? = null,
    val enabled: Boolean? = null, val host: String = "", val speed: Long = 0,
    val eta: Long = -1, val running: Boolean = false
)
data class TransferListData(val packages: List<Transfer>, val links: List<Transfer>)
data class DownloadStatus(val speed: Long, val state: String)
data class ServerLimits(val speedLimit: Int, val maxDownloads: Int, val maxChunks: Int = 1, val maxPerHost: Int = 1) {
    init { require(speedLimit >= 0 && maxDownloads in 1..20 && maxChunks in 1..20 && maxPerHost in 1..40) }
}
data class AdvancedSetting(val interfaceName: String, val storage: JsonElement, val key: String,
    val type: String, val value: JsonElement, val description: String, val options: List<String>) {
    val secret: Boolean get() = listOf("password", "secret", "token", "credential").any { key.contains(it, ignoreCase = true) }
}
data class DeviceOverview(val state: String, val speed: Long, val loaded: Long, val total: Long) {
    val progress: Float? get() = if (total > 0) (loaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
}
fun JsonElement.deviceOverview(): DeviceOverview {
    val status = jsonObject
    val state = when {
        status["pause"]?.jsonPrimitive?.booleanOrNull == true -> "PAUSE"
        text("state").isNotBlank() -> text("state").uppercase(java.util.Locale.ROOT)
        status["running"]?.jsonPrimitive?.booleanOrNull == true -> "RUNNING"
        status["running"]?.jsonPrimitive?.booleanOrNull == false -> "STOPPED"
        else -> "UNKNOWN"
    }
    return DeviceOverview(state, status.getValue("speed").jsonPrimitive.long.coerceAtLeast(0),
        status.getValue("download_current").jsonPrimitive.long.coerceAtLeast(0),
        status.getValue("download_complete").jsonPrimitive.long.coerceAtLeast(0))
}
fun JsonElement.text(key: String): String = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonElement.number(key: String): Long = text(key).toLongOrNull() ?: 0
fun JsonElement.transfers(): List<Transfer> = (this as? JsonArray).orEmpty().map {
    Transfer(it.number("uuid"), it.text("name"), it.number("bytesLoaded"), it.number("bytesTotal"),
        it.text("status").ifBlank { it.text("availability") }, it.number("packageUUID"),
        it.text("finished").toBooleanStrictOrNull(), it.text("enabled").toBooleanStrictOrNull(),
        it.text("host"), it.number("speed"), it.text("eta").toLongOrNull() ?: -1,
        it.text("running").toBooleanStrictOrNull() ?: false)
}
interface DownloaderRepository {
    val reconnecting: StateFlow<Boolean>
    suspend fun login(email: String, password: String, rememberSession: Boolean)
    suspend fun devices(): List<Device>
    suspend fun deviceOverview(deviceId: String): DeviceOverview
    suspend fun serverLimits(): ServerLimits
    suspend fun saveServerLimits(value: ServerLimits)
    suspend fun checkServerUpdates(): Boolean
    suspend fun advancedSettings(search: String): List<AdvancedSetting>
    suspend fun saveAdvancedSetting(setting: AdvancedSetting, value: JsonElement): JsonElement
    suspend fun addArchivePassword(linkIds: List<Long>, packageIds: List<Long>, password: String)
    suspend fun archiveInfo(linkIds: List<Long>, packageIds: List<Long>): List<ArchiveInfo>
    suspend fun saveArchiveOptions(id: String, autoExtract: Boolean?, extractPath: String, password: String)
    suspend fun startExtraction(linkIds: List<Long>, packageIds: List<Long>)
    suspend fun cancelExtraction(controllerId: Long)
    suspend fun select(device: Device)
    suspend fun transfers(collector: Boolean): TransferListData
    suspend fun downloadStatus(): DownloadStatus
    suspend fun checkPackageCompletion()
    suspend fun captchas(): List<JsonObject>
    suspend fun rpc(path: String, vararg params: JsonElement): JsonElement
    suspend fun addLinks(links: String, password: String = "", name: String = "", archivePassword: String = password)
    suspend fun uploadContainer(type: String, bytes: ByteArray)
    suspend fun logout()
}
