package org.jdownloader.remote.domain

import kotlinx.serialization.json.*

data class ArchiveInfo(val id: String, val name: String, val controllerId: Long, val status: String,
    val parts: Map<String, String>, val settings: JsonObject) {
    val ready: Boolean get() = parts.isNotEmpty() && parts.values.all { it == "COMPLETE" }
    val autoExtract: Boolean? get() = (settings["autoExtract"] as? JsonPrimitive)?.booleanOrNull
    val extractPath: String get() = (settings["extractPath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
