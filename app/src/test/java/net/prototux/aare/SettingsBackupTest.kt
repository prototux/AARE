package net.prototux.aare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    @Test
    fun keepsTheUsersSettingsAndTrustedCars() {
        assertTrue(SettingsBackup.keeps("settings", "car_theme"))
        assertTrue(SettingsBackup.keeps("settings", "rail_apps"))
        assertTrue(SettingsBackup.keeps("head_unit_pins", "usb:Android|Android Auto|"))
    }

    @Test
    fun leavesOutWhatBelongsToThisPhone() {
        assertFalse(SettingsBackup.keeps("settings", "instance_id"))
        assertFalse(SettingsBackup.keeps("settings", "requested_permissions"))
        assertFalse(SettingsBackup.keeps("settings", "cert_index"))
        assertFalse(SettingsBackup.keeps("settings", "update_latest"))
        assertFalse(SettingsBackup.keeps("settings", "phone_bt_address"))
        assertFalse(SettingsBackup.keeps("settings", "root_audio"))
        assertFalse(SettingsBackup.keeps("settings", "cert_update_last_check_ms"))
    }
}
