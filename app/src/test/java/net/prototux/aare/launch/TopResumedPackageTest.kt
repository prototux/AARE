package net.prototux.aare.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopResumedPackageTest {
    @Test
    fun thePhoneScreensTopAppIsFound() {
        val dump = """
            Display #12 (activities from top to bottom):
                topResumedActivity=ActivityRecord{1 u0 com.anthropic.claude/.MainActivity t5}
            Display #0 (activities from top to bottom):
              * Task{2 #3 type=standard A=10261:com.google.android.apps.maps}
                topResumedActivity=ActivityRecord{160 u0 com.google.android.apps.maps/com.google.android.maps.MapsActivity t3}
        """.trimIndent()
        assertEquals("com.google.android.apps.maps", topResumedPackage(dump))
    }

    @Test
    fun nothingResumedGivesNull() {
        assertNull(topResumedPackage("Display #0 (activities from top to bottom):\n  mFocusedApp=null"))
        assertNull(topResumedPackage(""))
    }
}
