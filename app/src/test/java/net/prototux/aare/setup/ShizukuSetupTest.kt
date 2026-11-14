package net.prototux.aare.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import net.prototux.aare.setup.ShizukuSetup.AndroidAutoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSetupTest {
    @Test
    fun theSetupAsksOnlyForPermissionsTheReleaseHas() {
        val oreo = SetupPermissions.forSdk(Build.VERSION_CODES.O)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALENDAR), oreo)
        val twelve = SetupPermissions.forSdk(Build.VERSION_CODES.S)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in twelve && Manifest.permission.BLUETOOTH_SCAN in twelve)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in twelve)
        val thirteen = SetupPermissions.forSdk(Build.VERSION_CODES.TIRAMISU)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in thirteen && Manifest.permission.NEARBY_WIFI_DEVICES in thirteen)
        assertFalse(Manifest.permission.ACCESS_LOCAL_NETWORK in thirteen)
        assertTrue(Manifest.permission.ACCESS_LOCAL_NETWORK in SetupPermissions.forSdk(Build.VERSION_CODES.CINNAMON_BUN))
    }

    @Test
    fun onlyWhatIsntGrantedYetIsGranted() {
        val asked = SetupPermissions.forSdk(Build.VERSION_CODES.TIRAMISU)
        val granted = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)
        val missing = ShizukuSetup.toGrant(asked) { it in granted }
        assertEquals(asked - granted, missing)
        assertEquals(emptyList<String>(), ShizukuSetup.toGrant(asked) { true })
    }

    @Test
    fun theHelperGrantsNothingElse() {
        assertTrue(ShizukuSetup.mayGrant(Manifest.permission.RECORD_AUDIO, Build.VERSION_CODES.O))
        assertFalse(ShizukuSetup.mayGrant(Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.S))
        assertFalse(ShizukuSetup.mayGrant("android.permission.WRITE_SECURE_SETTINGS", Build.VERSION_CODES.CINNAMON_BUN))
        assertFalse(ShizukuSetup.mayGrant(Manifest.permission.READ_SMS, Build.VERSION_CODES.CINNAMON_BUN))
    }

    @Test
    fun theCommandsAreExactlyThese() {
        assertEquals(listOf("pm", "grant", "--user", "0", "net.prototux.aare", "android.permission.RECORD_AUDIO"),
            ShizukuSetup.grant("net.prototux.aare", Manifest.permission.RECORD_AUDIO, 0))
        assertEquals(listOf("cmd", "notification", "allow_listener", "net.prototux.aare/net.prototux.aare.notify.CarNotificationListener", "10"),
            ShizukuSetup.allowListener("net.prototux.aare/net.prototux.aare.notify.CarNotificationListener", 10))
        assertEquals(listOf("pm", "disable-user", "--user", "0", "com.google.android.projection.gearhead"), ShizukuSetup.disableAndroidAuto(0))
        assertEquals(listOf("pm", "enable", "--user", "0", "com.google.android.projection.gearhead"), ShizukuSetup.enableAndroidAuto(0))
    }

    @Test
    fun androidAutoIsNeverUninstalledNorCleared() {
        val all = listOf(ShizukuSetup.disableAndroidAuto(0), ShizukuSetup.enableAndroidAuto(0)).flatten()
        assertFalse(all.any { it == "uninstall" || it == "clear" || it.startsWith("--") && it != "--user" })
    }

    @Test
    fun theUserIsTheCallers() {
        assertEquals(0, ShizukuSetup.userId(10_123))
        assertEquals(10, ShizukuSetup.userId(1_010_123))
        assertEquals(0, ShizukuSetup.userId(2000))
    }

    @Test
    fun commandResults() {
        assertTrue(ShizukuSetup.succeeded(0, ""))
        assertTrue(ShizukuSetup.succeeded(0, "Package com.google.android.projection.gearhead new state: disabled-user"))
        assertFalse(ShizukuSetup.succeeded(255, ""))
        assertFalse(ShizukuSetup.succeeded(0, "Exception occurred while executing 'grant':\njava.lang.SecurityException: …"))
        assertFalse(ShizukuSetup.succeeded(0, "Error: unknown command"))
        assertFalse(ShizukuSetup.succeeded(0, "Invalid listener - must be a ComponentName"))
    }

    @Test
    fun androidAutosState() {
        assertEquals(AndroidAutoState.NOT_INSTALLED, ShizukuSetup.androidAutoState(null))
        assertEquals(AndroidAutoState.ENABLED, ShizukuSetup.androidAutoState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertEquals(AndroidAutoState.ENABLED, ShizukuSetup.androidAutoState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertEquals(AndroidAutoState.DISABLED, ShizukuSetup.androidAutoState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertEquals(AndroidAutoState.DISABLED, ShizukuSetup.androidAutoState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertEquals(AndroidAutoState.DISABLED, ShizukuSetup.androidAutoState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
    }
}
