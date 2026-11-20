package org.jdownloader.remote

import org.jdownloader.remote.data.newCompletionIds
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageCompletionTest {
    @Test fun firstCheckDoesNotNotifyForAlreadyCompletedPackages() {
        assertEquals(emptySet<String>(), newCompletionIds(null, setOf("1", "2")))
    }

    @Test fun laterChecksNotifyOnlyNewCompletions() {
        assertEquals(setOf("2"), newCompletionIds(setOf("1"), setOf("1", "2")))
        assertEquals(emptySet<String>(), newCompletionIds(setOf("1", "2"), setOf("1", "2")))
        assertEquals(setOf("1"), newCompletionIds(emptySet(), setOf("1")))
    }
}
