package net.prototux.aare.aap

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.aap.channels.SensorRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRequestsTest {
    private val sent = ArrayList<Pair<SensorType, Long>>()
    private val timeouts = ArrayList<() -> Unit>()
    private val order = listOf(
        SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_NIGHT_MODE,
        SensorType.SENSOR_SPEED, SensorType.SENSOR_ACCELEROMETER_DATA,
    )
    private val requests = SensorRequests(
        order,
        periodOf = { if (it == SensorType.SENSOR_ACCELEROMETER_DATA) 500L else 0L },
        send = { type, period -> sent += type to period },
        schedule = { timeouts += it },
    )

    @Test
    fun nothingGoesOutBeforeTheChannelOpens() {
        assertTrue(sent.isEmpty())
        requests.start()
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L), sent)
    }

    @Test
    fun oneRequestAtATimeInOrderUntilAnswered() {
        requests.start()
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L), sent)
        requests.answered()
        assertEquals(SensorType.SENSOR_NIGHT_MODE to 0L, sent[1])
        requests.answered()
        assertEquals(SensorType.SENSOR_SPEED to 0L, sent[2])
        requests.answered()
        assertEquals(SensorType.SENSOR_ACCELEROMETER_DATA to 500L, sent[3])
        requests.answered()
        assertEquals(4, sent.size)
    }

    @Test
    fun aSilentCarDoesNotHoldTheQueue() {
        requests.start()
        assertEquals(1, sent.size)
        timeouts[0]() // the driving status request's timeout
        assertEquals(SensorType.SENSOR_NIGHT_MODE to 0L, sent[1])
        // A timeout of a request that was answered meanwhile changes nothing.
        requests.answered()
        timeouts[1]()
        assertEquals(3, sent.size)
    }

    @Test
    fun aSensorIsAskedForOnceAndNeverStopped() {
        requests.start()
        requests.start()
        repeat(6) { requests.answered() }
        assertEquals(order.map { it to (if (it == SensorType.SENSOR_ACCELEROMETER_DATA) 500L else 0L) }, sent)
        assertTrue(sent.none { it.second < 0 })
    }

    @Test
    fun anAnswerWithNothingPendingIsIgnored() {
        requests.answered()
        requests.start()
        assertEquals(1, sent.size)
    }

    @Test
    fun stopForgetsTheSession() {
        requests.start()
        requests.answered()
        requests.stop()
        timeouts[1]() // the night mode request's timeout, after the stop
        assertEquals(2, sent.size)
        requests.start()
        assertEquals(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L, sent[2])
    }
}
