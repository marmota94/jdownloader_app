package org.jdownloader.remote.data

import javax.inject.Inject
import kotlinx.serialization.json.*

/** Compatibility with the upstream deprecated /linkcollector namespace.
 * New UI operations use /linkgrabberv2, whose query and response schemas differ.
 */
class LegacyLinkCollectorClient internal constructor(
    private val call: suspend (String, List<JsonElement>) -> JsonElement
) {
    @Inject constructor(api: MyJdApiClient) : this({ path, params -> api.rpc(path, params) })

    suspend fun queryPackages(query: LegacyPackageQuery = LegacyPackageQuery()): List<LegacyCollectedItem> =
        items(call("/linkcollector/queryPackages", listOf(query.toJson())))

    suspend fun queryLinks(query: LegacyLinkQuery = LegacyLinkQuery()): List<LegacyCollectedItem> =
        items(call("/linkcollector/queryLinks", listOf(query.toJson())))

    suspend fun addLinks(
        links: String,
        packageName: String = "",
        extractPassword: String = "",
        downloadPassword: String = "",
        destinationFolder: String? = null
    ): Boolean? {
        require(links.isNotBlank())
        val params = listOf(links, packageName, extractPassword, downloadPassword).map { JsonPrimitive(it) }.toMutableList<JsonElement>()
        if (destinationFolder != null) params += JsonPrimitive(destinationFolder)
        return outcome(call("/linkcollector/addLinks", params))
    }

    /** Moves the selection to the download list; global controller state is separate. */
    suspend fun startDownloads(linkIds: List<Long>, packageIds: List<Long> = emptyList()): Boolean? {
        require(linkIds.isNotEmpty() || packageIds.isNotEmpty())
        return outcome(call("/linkcollector/startDownloads", listOf(ids(linkIds), ids(packageIds))))
    }

    /**
     * Upstream LinkCollectorAPIImpl consumes linkIds before packageIds. Its interface's
     * APIParameterNames annotation reverses their names, so generated docs are misleading.
     * https://github.com/mirror/jdownloader/blob/master/src/org/jdownloader/api/linkcollector/LinkCollectorAPIImpl.java
     */
    suspend fun removeLinks(linkIds: List<Long>, packageIds: List<Long> = emptyList()): Boolean? {
        require(linkIds.isNotEmpty() || packageIds.isNotEmpty())
        return outcome(call("/linkcollector/removeLinks", listOf(ids(linkIds), ids(packageIds))))
    }

    private fun outcome(value: JsonElement): Boolean? {
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.booleanOrNull ?: throw ApiException("INVALID_LEGACY_RESPONSE")
    }

    private fun items(value: JsonElement): List<LegacyCollectedItem> =
        (value as? JsonArray ?: throw ApiException("INVALID_LEGACY_RESPONSE")).map { entry ->
            val obj = entry as? JsonObject ?: throw ApiException("INVALID_LEGACY_RESPONSE")
            val id = (obj["uuid"] as? JsonPrimitive)?.longOrNull ?: throw ApiException("INVALID_LEGACY_RESPONSE")
            val name = (obj["name"] as? JsonPrimitive)?.content ?: throw ApiException("INVALID_LEGACY_RESPONSE")
            LegacyCollectedItem(id, name, obj["infoMap"] as? JsonObject ?: buildJsonObject {})
        }
}

data class LegacyCollectedItem(val id: Long, val name: String, val info: JsonObject)

private fun ids(values: List<Long>) = JsonArray(values.map { JsonPrimitive(it) })
private fun validatePage(startAt: Int, maxResults: Int) { require(startAt >= 0 && maxResults >= -1) }

data class LegacyPackageQuery(
    val startAt: Int = 0, val maxResults: Int = -1,
    val packageIds: List<Long> = emptyList(),
    val size: Boolean = true, val saveTo: Boolean = true,
    val childCount: Boolean = true, val hosts: Boolean = true,
    val availability: Boolean = true
) {
    internal fun toJson(): JsonObject {
        validatePage(startAt, maxResults)
        return buildJsonObject {
            put("startAt", startAt); put("maxResults", maxResults)
            put("packageUUIDs", ids(packageIds)); put("size", size); put("saveTo", saveTo)
            put("childCount", childCount); put("hosts", hosts); put("availability", availability)
        }
    }
}

data class LegacyLinkQuery(
    val startAt: Int = 0, val maxResults: Int = -1,
    val packageIds: List<Long> = emptyList(),
    val size: Boolean = true, val host: Boolean = true,
    val availability: Boolean = true, val url: Boolean = true, val enabled: Boolean = true
) {
    internal fun toJson(): JsonObject {
        validatePage(startAt, maxResults)
        return buildJsonObject {
            put("startAt", startAt); put("maxResults", maxResults)
            put("packageUUIDs", ids(packageIds)); put("size", size); put("host", host)
            put("availability", availability); put("url", url); put("enabled", enabled)
        }
    }
}
