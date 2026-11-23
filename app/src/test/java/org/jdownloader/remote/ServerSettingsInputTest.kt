package org.jdownloader.remote

import kotlinx.serialization.json.*
import org.jdownloader.remote.domain.*
import org.jdownloader.remote.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ServerSettingsInputTest {
    @Test fun speedConversionPreservesServerBytesAndAcceptsDecimalComma() {
        for (bytes in listOf(0, 1, 100000, Int.MAX_VALUE)) {
            assertEquals(ServerLimits(bytes, 3, 2, 4), parseServerLimits(speedLimitText(bytes), "3", "2", "4"))
        }
        assertEquals(ServerLimits(1536, 20, 20, 40), parseServerLimits("1,5", "20", "20", "40"))
        assertEquals(102, parseServerLimits("0.1", "1")!!.speedLimit)
    }

    @Test fun invalidOrOverflowingLimitsAreRejected() {
        for (speed in listOf("", "-1", "NaN", "Infinity", "1e9", "2097152", "0.00000001", "1.2.3")) assertNull(parseServerLimits(speed, "3"))
        assertNull(parseServerLimits("0", "0"))
        assertNull(parseServerLimits("0", "21"))
        assertNull(parseServerLimits("0", "3", "21", "1"))
        assertNull(parseServerLimits("0", "3", "1", "41"))
    }

    private fun entry(type: String, value: JsonElement, options: List<String> = emptyList()) =
        AdvancedSetting("org.example.Settings", JsonNull, "Key", type, value, "", options)

    @Test fun advancedEditorPreservesTypesAndRejectsInvalidNumbers() {
        assertEquals(JsonPrimitive(false), parseAdvancedValue(entry("BOOLEAN", JsonPrimitive(true)), "false"))
        assertNull(parseAdvancedValue(entry("BOOLEAN", JsonPrimitive(true)), "1"))
        assertEquals(JsonPrimitive("a\"b\nc"), parseAdvancedValue(entry("STRING", JsonPrimitive("old")), "a\"b\nc"))
        assertEquals(JsonNull, parseAdvancedValue(entry("STRING", JsonNull), "null"))
        assertEquals(JsonPrimitive(20), parseAdvancedValue(entry("INT", JsonPrimitive(1)), "20"))
        assertNull(parseAdvancedValue(entry("INT", JsonPrimitive(1)), "1.5"))
        assertNull(parseAdvancedValue(entry("INT", JsonPrimitive(1)), "2147483648"))
        assertNull(parseAdvancedValue(entry("DOUBLE", JsonPrimitive(1.0)), "1e999"))
    }

    @Test fun advancedEditorSupportsListsAndEnumsWithoutChangingTheirShape() {
        val list = entry("STRING_LIST", JsonArray(listOf(JsonPrimitive("old"))))
        assertEquals(Json.parseToJsonElement("""["new","other"]"""), parseAdvancedValue(list, """["new","other"]"""))
        assertNull(parseAdvancedValue(list, "{}"))
        val enum = entry("ENUM", JsonPrimitive("A"), listOf("A", "B"))
        assertEquals(JsonPrimitive("B"), parseAdvancedValue(enum, "B"))
        assertNull(parseAdvancedValue(enum, "C"))
        assertEquals(JsonPrimitive("B"), parseAdvancedValue(enum.copy(value = JsonNull), "B"))
        assertTrue(enum.copy(key = "ProxyPassword").secret)
    }
}
