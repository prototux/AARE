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
        requests.request(order)
        assertTrue(sent.isEmpty())
        requests.start()
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L), sent)
    }

    @Test
    fun oneRequestAtATimeInOrderUntilAnswered() {
        requests.start()
        // Asked for out of order: they still go out driving status first.
        requests.request(listOf(SensorType.SENSOR_ACCELEROMETER_DATA, SensorType.SENSOR_NIGHT_MODE, SensorType.SENSOR_DRIVING_STATUS_DATA))
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L), sent)
        requests.answered()
        assertEquals(SensorType.SENSOR_NIGHT_MODE to 0L, sent[1])
        requests.answered()
        assertEquals(SensorType.SENSOR_ACCELEROMETER_DATA to 500L, sent[2])
        requests.answered()
        assertEquals(3, sent.size)
    }

    @Test
    fun aSilentCarDoesNotHoldTheQueue() {
        requests.start()
        requests.request(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_SPEED))
        assertEquals(1, sent.size)
        timeouts[0]() // the driving status request's timeout
        assertEquals(SensorType.SENSOR_SPEED to 0L, sent[1])
        // A timeout of a request that was answered meanwhile changes nothing.
        requests.answered()
        timeouts[1]()
        assertEquals(2, sent.size)
    }

    @Test
    fun aSensorIsAskedForOnceAndNeverStopped() {
        requests.start()
        requests.request(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_SPEED))
        requests.answered(); requests.answered()
        requests.request(listOf(SensorType.SENSOR_SPEED))
        requests.answered()
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA to 0L, SensorType.SENSOR_SPEED to 0L), sent)
        assertTrue(sent.none { it.second < 0 })
    }

    @Test
    fun sensorsTheCarDoesNotOfferAreNeverAskedFor() {
        requests.start()
        requests.request(listOf(SensorType.SENSOR_TOLL_CARD))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anAnswerWithNothingPendingIsIgnored() {
        requests.start()
        requests.answered()
        requests.request(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_SPEED))
        assertEquals(1, sent.size)
    }

    @Test
    fun stopForgetsTheSession() {
        requests.start()
        requests.request(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA))
        requests.answered()
        requests.stop()
        requests.request(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA))
        requests.start()
        assertEquals(2, sent.size)
    }
}
