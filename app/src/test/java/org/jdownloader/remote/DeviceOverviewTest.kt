package org.jdownloader.remote

import kotlinx.serialization.json.Json
import org.jdownloader.remote.domain.deviceOverview
import org.junit.Assert.*
import org.junit.Test

class DeviceOverviewTest {
    @Test fun parsesSummaryWithoutFetchingDownloadLists() {
        val summary = Json.parseToJsonElement("""{"state":"Running","running":true,"pause":false,"speed":2048,"download_current":3000000000,"download_complete":6000000000}""").deviceOverview()
        assertEquals("RUNNING", summary.state)
        assertEquals(2048L, summary.speed)
        assertEquals(3000000000L, summary.loaded)
        assertEquals(6000000000L, summary.total)
        assertEquals(0.5f, summary.progress!!, 0.0001f)
    }

    @Test fun pauseTakesPrecedenceAndUnknownSizesHaveNoPercentage() {
        val summary = Json.parseToJsonElement("""{"state":"Running","running":true,"pause":true,"speed":-1,"download_current":-1,"download_complete":-1}""").deviceOverview()
        assertEquals("PAUSE", summary.state)
        assertEquals(0L, summary.speed)
        assertEquals(0L, summary.loaded)
        assertNull(summary.progress)
    }

    @Test fun clampsProgressAndAcceptsOlderSummaryWithoutState() {
        val summary = Json.parseToJsonElement("""{"running":false,"speed":0,"download_current":200,"download_complete":100}""").deviceOverview()
        assertEquals("STOPPED", summary.state)
        assertEquals(1f, summary.progress!!, 0f)
    }

    @Test(expected = NoSuchElementException::class) fun missingSizesAreNotReportedAsZeroProgress() {
        Json.parseToJsonElement("""{"speed":0}""").deviceOverview()
    }
}
