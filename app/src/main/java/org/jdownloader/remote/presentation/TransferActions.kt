package org.jdownloader.remote.presentation

import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.Transfer
import org.jdownloader.remote.domain.TransferListData

internal enum class DownloadFilter { ALL, ACTIVE, FINISHED, DISABLED }

internal fun matchingDownloads(transfers: TransferListData, query: String, filter: DownloadFilter): TransferListData {
    val term = query.trim()
    if (term.isEmpty() && filter == DownloadFilter.ALL) return transfers
    val matchingPackages = transfers.packages.filter { it.name.contains(term, ignoreCase = true) }.mapTo(hashSetOf()) { it.id }
    val links = transfers.links.filter { link ->
        val statusMatches = when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> link.enabled != false && (link.running || link.speed > 0)
            DownloadFilter.FINISHED -> link.finished == true
            DownloadFilter.DISABLED -> link.enabled == false
        }
        statusMatches && (term.isEmpty() || link.packageId in matchingPackages ||
            link.name.contains(term, ignoreCase = true) || link.host.contains(term, ignoreCase = true) ||
            link.status.contains(term, ignoreCase = true))
    }
    val parentIds = links.mapTo(hashSetOf()) { it.packageId }
    val parentsWithLinks = transfers.links.mapTo(hashSetOf()) { it.packageId }
    val packages = transfers.packages.filter { it.id in parentIds ||
        (filter == DownloadFilter.ALL && it.id in matchingPackages && it.id !in parentsWithLinks) }
    return TransferListData(packages, links)
}

internal fun toggleTransferSelection(selected: Set<Long>, targets: List<Long>): Set<Long> =
    if (targets.all { it in selected }) selected - targets.toSet() else selected + targets

internal fun transferMoveParams(row: Transfer, folder: Boolean, siblings: List<Transfer>, direction: Int): Array<JsonElement>? {
    require(direction == -1 || direction == 1)
    val index = siblings.indexOfFirst { it.id == row.id }
    if (index < 0 || index + direction !in siblings.indices || (!folder && row.packageId <= 0)) return null
    val after = if (direction < 0) siblings.getOrNull(index - 2)?.id ?: 0 else siblings[index + 1].id
    val ids = JsonArray(listOf(JsonPrimitive(row.id)))
    return if (folder) arrayOf(ids, JsonPrimitive(after))
        else arrayOf(ids, JsonPrimitive(after), JsonPrimitive(row.packageId))
}
