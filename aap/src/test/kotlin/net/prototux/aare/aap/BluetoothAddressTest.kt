package net.prototux.aare.aap

import net.prototux.aare.aap.channels.BluetoothChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothAddressTest {
    @Test
    fun sendsTheAddressAsAndroidWritesIt() {
        assertEquals("0A:1B:2C:3D:4E:5F", BluetoothChannel.canonicalAddress("0a:1b:2c:3d:4e:5f"))
        assertEquals("0A:1B:2C:3D:4E:5F", BluetoothChannel.canonicalAddress(" 0A:1b:2C:3d:4E:5f "))
        assertEquals("0A:1B:2C:3D:4E:5F", BluetoothChannel.canonicalAddress("0A:1B:2C:3D:4E:5F"))
    }
}
