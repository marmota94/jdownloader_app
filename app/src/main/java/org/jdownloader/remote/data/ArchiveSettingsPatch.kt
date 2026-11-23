package org.jdownloader.remote.data

import kotlinx.serialization.json.*

internal fun archiveSettingsPatch(current: JsonObject, autoExtract: Boolean?, extractPath: String, password: String): JsonObject {
    val previousAuto = (current["autoExtract"] as? JsonPrimitive)?.booleanOrNull
    val previousPath = (current["extractPath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    // Null and empty paths mean unchanged in JDownloader's extraction API.
    require(autoExtract != null || previousAuto == null)
    require(extractPath.isNotBlank() || previousPath.isBlank())
    require('\u0000' !in extractPath)
    return buildJsonObject {
        if (autoExtract != previousAuto) put("autoExtract", JsonPrimitive(autoExtract))
        if (extractPath != previousPath && extractPath.isNotBlank()) put("extractPath", extractPath)
        if (password.isNotBlank()) {
            val existing = (current["passwords"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            put("passwords", JsonArray((existing + password).distinct().map { JsonPrimitive(it) }))
        }
    }
}
