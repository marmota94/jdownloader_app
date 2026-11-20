package org.jdownloader.remote.domain

import kotlinx.serialization.Serializable
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
data class Settings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamic: Boolean = true, val locale: String = "", val cnl: Boolean = false)
data class Transfer(val id: Long, val name: String, val loaded: Long, val total: Long, val status: String, val packageId: Long = 0)
data class Snapshot(val packages: List<Transfer>, val links: List<Transfer>, val speed: Long, val state: String, val captchas: List<JsonObject>)
fun JsonElement.text(key: String): String = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonElement.number(key: String): Long = text(key).toLongOrNull() ?: 0
fun JsonElement.transfers(): List<Transfer> = (this as? JsonArray).orEmpty().map {
    Transfer(it.number("uuid"), it.text("name"), it.number("bytesLoaded"), it.number("bytesTotal"), it.text("status").ifBlank { it.text("availability") }, it.number("packageUUID"))
}
interface DownloaderRepository {
    suspend fun login(email: String, password: String, rememberSession: Boolean)
    suspend fun devices(): List<Device>
    suspend fun select(device: Device)
    suspend fun snapshot(collector: Boolean): Snapshot
    suspend fun rpc(path: String, vararg params: JsonElement): JsonElement
    suspend fun addLinks(links: String, password: String = "", name: String = "")
    suspend fun uploadContainer(type: String, bytes: ByteArray)
    suspend fun logout()
}
