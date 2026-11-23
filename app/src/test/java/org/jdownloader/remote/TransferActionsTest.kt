package org.jdownloader.remote

import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.presentation.*
import org.junit.Assert.*
import org.junit.Test

class TransferActionsTest {
    private val rows = (1L..3L).map { Transfer(it, "File $it", 0, 100, "", packageId = 10) }

    @Test fun movingUpUsesThePredecessorOfThePreviousRow() {
        assertNull(transferMoveParams(rows[0], true, rows, -1))
        assertEquals("[[2],0]", JsonArray(transferMoveParams(rows[1], true, rows, -1)!!.toList()).toString())
        assertEquals("[[3],1]", JsonArray(transferMoveParams(rows[2], true, rows, -1)!!.toList()).toString())
    }

    @Test fun movingLinksDownKeepsTheirPackageAndStopsAtTheLastRow() {
        assertEquals("[[1],2,10]", JsonArray(transferMoveParams(rows[0], false, rows, 1)!!.toList()).toString())
        assertNull(transferMoveParams(rows[2], false, rows, 1))
        assertNull(transferMoveParams(rows[0].copy(packageId = 0), false, rows, 1))
        assertNull(transferMoveParams(rows[0].copy(id = 99), false, rows, 1))
    }

    @Test fun selectingAFolderAddsEachFileOnceAndTogglingAgainClearsIt() {
        val selected = toggleTransferSelection(setOf(1L), rows.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), selected)
        assertEquals(emptySet<Long>(), toggleTransferSelection(selected, rows.map { it.id }))
        assertEquals(setOf(2L, 3L), toggleTransferSelection(selected, listOf(1L)))
        assertEquals(selected, toggleTransferSelection(selected, emptyList()))
    }

    @Test fun downloadSearchMatchesPackageNameFileNameHostAndStatusInQueueOrder() {
        val folder = rows[0].copy(id = 10, name = "Example package")
        val data = TransferListData(listOf(folder), listOf(
            rows[1].copy(host = "host.example.org"),
            rows[0].copy(status = "Waiting for account"),
            rows[2].copy(packageId = 0, name = "Unattached file")
        ))
        assertSame(data, matchingDownloads(data, " ", DownloadFilter.ALL))
        assertEquals(listOf(2L, 1L), matchingDownloads(data, " EXAMPLE PACKAGE ", DownloadFilter.ALL).links.map { it.id })
        assertEquals(listOf(2L), matchingDownloads(data, "HOST.EXAMPLE", DownloadFilter.ALL).links.map { it.id })
        assertEquals(listOf(1L), matchingDownloads(data, "account", DownloadFilter.ALL).links.map { it.id })
        assertEquals(TransferListData(emptyList(), listOf(data.links.last())), matchingDownloads(data, "unattached", DownloadFilter.ALL))
    }

    @Test fun statusFilterAppliesToMatchingPackageChildrenAndKeepsDisabledSeparate() {
        val folder = rows[0].copy(id = 10, name = "Example")
        val data = TransferListData(listOf(folder), listOf(
            rows[0].copy(enabled = false, finished = true),
            rows[1].copy(running = true),
            rows[2].copy(enabled = false, running = true)
        ))
        assertEquals(listOf(2L), matchingDownloads(data, "example", DownloadFilter.ACTIVE).links.map { it.id })
        assertEquals(listOf(1L), matchingDownloads(data, "example", DownloadFilter.FINISHED).links.map { it.id })
        assertEquals(listOf(1L, 3L), matchingDownloads(data, "example", DownloadFilter.DISABLED).links.map { it.id })
        assertEquals(TransferListData(emptyList(), emptyList()), matchingDownloads(data, "no match", DownloadFilter.DISABLED))
    }

    @Test fun countsCompletionByServerFlagRatherThanBytesAndRetainsStatus() {
        val links = Json.parseToJsonElement("""[
            {"uuid":1,"packageUUID":10,"name":"Complete","bytesLoaded":100,"bytesTotal":100,"finished":true,"enabled":true,"host":"example.org","speed":0,"eta":0},
            {"uuid":2,"packageUUID":10,"name":"Extracting","bytesLoaded":100,"bytesTotal":100,"finished":false,"enabled":false,"status":"Extracting","speed":2048,"eta":60,"running":true},
            {"uuid":3,"name":"Unknown"}
        ]""").transfers()
        assertEquals(3, links.size)
        assertEquals(1, links.count { it.finished == true })
        assertEquals("example.org", links[0].host)
        assertEquals(false, links[1].enabled)
        assertEquals("Extracting", links[1].status)
        assertEquals(2048L, links[1].speed)
        assertEquals(60L, links[1].eta)
        assertTrue(links[1].running)
        assertNull(links[2].enabled)
        assertNull(links[2].finished)
        assertEquals(-1L, links[2].eta)
    }

    @Test fun faviconRequestsAcceptOnlyHostNames() {
        assertEquals("https://www.google.com/s2/favicons?domain=example.org&sz=32", hostIconUrl("example.org"))
        for (host in listOf("", "https://example.org", "example.org/path", "example.org?x=y", "user@example.org", "example.org:443", "127.0.0.1")) {
            assertNull(hostIconUrl(host))
        }
    }
}
