package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListingModeTest {
    @Test
    fun standaloneSelectionOverridesHiddenState() {
        assertEquals(
            AppListingMode.STANDALONE,
            appListingMode("com.example.app", hidden = setOf("com.example.app"), standalone = setOf("com.example.app")),
        )
    }

    @Test
    fun standaloneSelectionReplacesNativeEntry() {
        assertEquals(
            AppListingMode.STANDALONE,
            appListingMode("com.example.app", hidden = emptySet(), standalone = setOf("com.example.app")),
        )
    }

    @Test
    fun unselectedPackagesKeepTheirNativeVisibility() {
        assertEquals(
            AppListingMode.HIDDEN,
            appListingMode("com.example.hidden", hidden = setOf("com.example.hidden"), standalone = emptySet()),
        )
        assertEquals(
            AppListingMode.NATIVE,
            appListingMode("com.example.visible", hidden = emptySet(), standalone = emptySet()),
        )
    }
}
