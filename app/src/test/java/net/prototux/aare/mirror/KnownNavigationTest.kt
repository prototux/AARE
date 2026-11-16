package net.prototux.aare.mirror

import org.junit.Assert.assertTrue
import org.junit.Test

class KnownNavigationTest {
    /** A typo in a package name would silently keep that app off the car while driving. */
    @Test
    fun everyEntryIsAPackageName() {
        val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        KNOWN_NAVIGATION.forEach { assertTrue("not a package name: \"$it\"", packageName.matches(it)) }
    }

    /** #150: the Chinese navigation apps. */
    @Test
    fun amapBaiduAndTencentAreKnown() {
        assertTrue(KNOWN_NAVIGATION.containsAll(setOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map")))
    }
}
