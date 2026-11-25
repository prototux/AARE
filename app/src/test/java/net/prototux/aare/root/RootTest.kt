package net.prototux.aare.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Looking for su without running it. */
class RootTest {
    @Test fun findsSuOnThePath() {
        assertTrue(Root.found("/apex/bin:/opt/bin") { it == "/opt/bin/su" })
    }

    @Test fun findsSuWhereRootManagersPutIt() {
        // Magisk on Android 16 shows it in /product/bin; its own copy stays in /debug_ramdisk.
        assertTrue(Root.found("") { it == "/product/bin/su" })
        assertTrue(Root.found(null) { it == "/debug_ramdisk/su" })
    }

    @Test fun nothingFoundOnAStockPhone() {
        assertFalse(Root.found("/system/bin:/vendor/bin") { false })
    }
}
