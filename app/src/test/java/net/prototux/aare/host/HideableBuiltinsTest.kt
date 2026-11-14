package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F13: which built-in apps the user may hide from the car's list, and which must never be. */
class HideableBuiltinsTest {
    @Test
    fun phoneAndNotificationsCanBeHidden() {
        assertTrue(CarRuntime.PHONE_ID in CarRuntime.HIDEABLE_BUILTIN_IDS)
        assertTrue(CarRuntime.NOTIFICATIONS_ID in CarRuntime.HIDEABLE_BUILTIN_IDS)
    }

    @Test
    fun settingsCanNeverBeHidden() {
        // Hiding it would lock the user out of the car-side settings from the car itself.
        assertFalse(CarRuntime.SETTINGS_ID in CarRuntime.HIDEABLE_BUILTIN_IDS)
    }
}
