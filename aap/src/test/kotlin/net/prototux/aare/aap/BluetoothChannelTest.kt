package net.prototux.aare.aap

import aap_protobuf.service.bluetooth.message.BluetoothPairingMethodOuterClass.BluetoothPairingMethod
import net.prototux.aare.aap.channels.BluetoothChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Bluetooth service channel: the pairing method the phone asks for. */
class BluetoothChannelTest {
    @Test
    fun pinWinsWhenTheCarOffersBoth() {
        // The last of [numeric comparison, PIN] the car offers is kept, whatever its order.
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothChannel.pairingMethod(listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothChannel.pairingMethod(listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON)))
    }

    @Test
    fun onlyPinOrNumericComparisonIsAskedFor() {
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothChannel.pairingMethod(listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB, BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_UNAVAILABLE, BluetoothChannel.pairingMethod(listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB, BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_UNAVAILABLE, BluetoothChannel.pairingMethod(emptyList()))
    }
}
