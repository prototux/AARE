package net.prototux.aare.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HelperNameTest {
    @Test
    fun aPackageBecomesAPlainProcessSuffix() {
        assertEquals("video_org_wisso_newpipematerial", AppLauncher.helperName("video-org.wisso.newpipematerial"))
    }

    @Test
    fun helpersForDifferentAppsGetDifferentNames() {
        assertNotEquals(AppLauncher.helperName("launch-com.a"), AppLauncher.helperName("launch-com.b"))
    }
}
