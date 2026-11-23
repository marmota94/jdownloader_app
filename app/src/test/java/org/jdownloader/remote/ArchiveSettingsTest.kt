package org.jdownloader.remote

import kotlinx.serialization.json.*
import org.jdownloader.remote.data.archiveSettingsPatch
import org.jdownloader.remote.domain.ArchiveInfo
import org.junit.Assert.*
import org.junit.Test

class ArchiveSettingsTest {
    private val settings = Json.parseToJsonElement("""{
        "autoExtract":false,"extractPath":"/old","passwords":["existing"],
        "finalPassword":"known","removeFilesAfterExtraction":true,"removeDownloadLinksAfterExtraction":false
    }""").jsonObject

    @Test fun patchOnlyChangesRequestedExtractionFieldsAndAppendsPasswords() {
        val patch = archiveSettingsPatch(settings, true, "/new", "additional")
        assertEquals(Json.parseToJsonElement("""{"autoExtract":true,"extractPath":"/new","passwords":["existing","additional"]}"""), patch)
        assertFalse(patch.containsKey("finalPassword"))
        assertFalse(patch.containsKey("removeFilesAfterExtraction"))
        assertFalse(patch.containsKey("removeDownloadLinksAfterExtraction"))
        assertTrue(archiveSettingsPatch(settings, false, "/old", "").isEmpty())
    }

    @Test fun defaultsRemainUnchangedAndPasswordsAreNotDuplicated() {
        val current = Json.parseToJsonElement("""{"autoExtract":null,"extractPath":"","passwords":["existing"]}""").jsonObject
        assertTrue(archiveSettingsPatch(current, null, "", "").isEmpty())
        assertEquals(Json.parseToJsonElement("""{"passwords":["existing"]}"""), archiveSettingsPatch(current, null, "", "existing"))
    }

    @Test fun unsupportedClearingAndInvalidPathsAreRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) { archiveSettingsPatch(settings, null, "/old", "") }
        assertThrows(IllegalArgumentException::class.java) { archiveSettingsPatch(settings, false, "", "") }
        assertThrows(IllegalArgumentException::class.java) { archiveSettingsPatch(settings, false, "bad\u0000path", "") }
    }

    @Test fun readinessRequiresEveryPartToBeComplete() {
        val info = ArchiveInfo("id", "Archive", -1, "NA", mapOf("part1" to "COMPLETE", "part2" to "COMPLETE"), settings)
        assertTrue(info.ready)
        assertEquals(false, info.autoExtract)
        assertEquals("/old", info.extractPath)
        assertFalse(info.copy(parts = emptyMap()).ready)
        assertFalse(info.copy(parts = mapOf("part1" to "MISSING")).ready)
        assertFalse(info.copy(parts = mapOf("part1" to "INCOMPLETE")).ready)
    }
}
