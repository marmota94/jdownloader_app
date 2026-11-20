package org.jdownloader.remote

import java.util.Locale
import org.jdownloader.remote.presentation.formatByteSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeTest {
    @Test fun scalesLargeFoldersAndProgressIndependently() {
        assertEquals("100 GiB", formatByteSize(100L shl 30, Locale.US))
        assertEquals("1.5 TiB", formatByteSize(3L shl 39, Locale.US))
        assertEquals("768 KiB", formatByteSize(768L shl 10, Locale.US))
    }

    @Test fun roundsAcrossUnitBoundaryAndUsesLocale() {
        assertEquals("1 MiB", formatByteSize((1L shl 20) - 1, Locale.US))
        assertEquals("1,5 GiB", formatByteSize(3L shl 29, Locale.GERMANY))
        assertEquals("0 B", formatByteSize(-1, Locale.US))
    }
}
