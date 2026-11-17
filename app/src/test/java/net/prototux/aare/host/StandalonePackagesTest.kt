package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class StandalonePackagesTest {
    @Test
    fun theAllowListCountsOnlyWhenEnabled() {
        assertEquals(listOf("org.newpipe"), standalonePackages(true, listOf("org.newpipe"), emptySet()))
        assertEquals(emptyList<String>(), standalonePackages(false, listOf("org.newpipe"), emptySet()))
    }

    @Test
    fun navigationAppsWithoutACarAppAreAddedOnce() {
        assertEquals(listOf("com.google.android.apps.maps"),
            standalonePackages(false, emptyList(), setOf("com.waze"), listOf("com.waze", "com.google.android.apps.maps")))
        assertEquals(listOf("com.google.android.apps.maps"),
            standalonePackages(true, listOf("com.google.android.apps.maps"), emptySet(), listOf("com.google.android.apps.maps")))
    }
}
