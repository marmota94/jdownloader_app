package org.jdownloader.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.jdownloader.remote.data.*
import org.junit.Assert.*
import org.junit.Test

class LegacyLinkCollectorTest {
    @Test fun queryPackagesUsesLegacySizeAndInfoMapSchema() = runTest {
        val client = LegacyLinkCollectorClient { path, args ->
            assertEquals("/linkcollector/queryPackages", path)
            val query = args.single().jsonObject
            assertEquals(JsonPrimitive(20), query["startAt"])
            assertEquals(JsonPrimitive(10), query["maxResults"])
            assertEquals(JsonArray(listOf(JsonPrimitive(123L))), query["packageUUIDs"])
            assertEquals(JsonPrimitive(true), query["size"])
            assertEquals(JsonPrimitive(true), query["saveTo"])
            assertFalse(query.containsKey("bytesTotal"))
            Json.parseToJsonElement("[{\"uuid\":123,\"name\":\"Package\",\"infoMap\":{\"size\":2048,\"childCount\":2}}]")
        }
        val item = client.queryPackages(LegacyPackageQuery(startAt = 20, maxResults = 10, packageIds = listOf(123))).single()
        assertEquals(123L, item.id); assertEquals("Package", item.name)
        assertEquals(2048L, item.info.getValue("size").jsonPrimitive.long)
    }

    @Test fun queryLinksRequestsPackageUuidSelectionAndLegacyFields() = runTest {
        val client = LegacyLinkCollectorClient { path, args ->
            assertEquals("/linkcollector/queryLinks", path)
            val query = args.single().jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(7L), JsonPrimitive(8L))), query["packageUUIDs"])
            assertEquals(JsonPrimitive(true), query["size"])
            assertEquals(JsonPrimitive(true), query["host"])
            assertEquals(JsonPrimitive(true), query["availability"])
            assertEquals(JsonPrimitive(true), query["url"])
            JsonArray(emptyList())
        }
        assertTrue(client.queryLinks(LegacyLinkQuery(packageIds = listOf(7, 8))).isEmpty())
    }

    @Test fun addLinksUsesFourOrFiveOrderedArguments() = runTest {
        var count = 0
        val client = LegacyLinkCollectorClient { path, args ->
            assertEquals("/linkcollector/addLinks", path)
            val expected = mutableListOf("https://example.org/a", "Package", "extract", "download")
            if (count++ == 1) expected += "/downloads"
            assertEquals(expected.map { JsonPrimitive(it) }, args)
            JsonPrimitive(true)
        }
        assertEquals(true, client.addLinks("https://example.org/a", "Package", "extract", "download"))
        assertEquals(true, client.addLinks("https://example.org/a", "Package", "extract", "download", "/downloads"))
        assertEquals(2, count)
    }

    @Test fun selectionCommandsUseRuntimeLinkIdsThenPackageIds() = runTest {
        val paths = mutableListOf<String>()
        val client = LegacyLinkCollectorClient { path, args ->
            paths += path
            assertEquals(listOf(JsonArray(listOf(JsonPrimitive(11L))), JsonArray(listOf(JsonPrimitive(22L)))), args)
            JsonPrimitive(true)
        }
        client.startDownloads(listOf(11), listOf(22))
        client.removeLinks(listOf(11), listOf(22))
        assertEquals(listOf("/linkcollector/startDownloads", "/linkcollector/removeLinks"), paths)
    }

    @Test fun nullableLegacyBooleanIsPreserved() = runTest {
        val client = LegacyLinkCollectorClient { _, _ -> JsonNull }
        assertNull(client.addLinks("https://example.org"))
    }

    @Test fun emptyMutationSelectionIsRejectedBeforeSending() = runTest {
        val client = LegacyLinkCollectorClient { _, _ -> fail("must not send"); JsonNull }
        try { client.removeLinks(emptyList()); fail("must require a selection") } catch (_: IllegalArgumentException) {}
        try { client.startDownloads(emptyList()); fail("must require a selection") } catch (_: IllegalArgumentException) {}
    }
}
