package net.prototux.aare.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ElmStreamTest {
    @Test
    fun onlyClearAdapterNames() {
        listOf("OBDII", "OBD2", "V-LINK", "vLink", "Vgate iCar Pro", "OBDLink MX+", "ELM327 v1.5", "KONNWEI KW902", "Veepeak").forEach {
            assertTrue(it, Elm327.isAdapterName(it))
        }
        listOf("Helmet", "Scanner", "iCar headset", "Elm Street speaker", "My car", "Galaxy Buds").forEach {
            assertFalse(it, Elm327.isAdapterName(it))
        }
    }

    @Test
    fun answerUpToThePrompt() {
        val adapter = PipedOutputStream()
        val written = ByteArrayOutputStream()
        val stream = ElmStream(PipedInputStream(adapter), written)
        Thread { Thread.sleep(30); adapter.write("410C1F40\r\r>".toByteArray()); adapter.flush() }.start()
        assertEquals("410C1F40\r\r", stream.send("010C", 2_000))
        assertEquals("010C\r", written.toString())
    }

    @Test
    fun silenceEndsAtTheLimit() {
        val adapter = PipedOutputStream()
        val stream = ElmStream(PipedInputStream(adapter), ByteArrayOutputStream())
        val start = System.currentTimeMillis()
        assertEquals("41" + ObdLink.NO_ANSWER, run {
            adapter.write("41".toByteArray()); adapter.flush()
            stream.send("010C", 200)
        })
        assertTrue(System.currentTimeMillis() - start in 190..1_500)
    }

    @Test
    fun aLateAnswerIsNotTheNextOne() {
        val adapter = PipedOutputStream()
        val stream = ElmStream(PipedInputStream(adapter), ByteArrayOutputStream())
        stream.send("010D", 50) // no answer in time
        adapter.write("410D32\r>".toByteArray()); adapter.flush()
        Thread.sleep(50)
        Thread { Thread.sleep(30); adapter.write("410C1F40\r>".toByteArray()); adapter.flush() }.start()
        assertEquals("410C1F40\r", stream.send("010C", 2_000))
    }

    @Test
    fun aClosedLinkIsAnError() {
        val adapter = PipedOutputStream()
        val stream = ElmStream(PipedInputStream(adapter), ByteArrayOutputStream())
        adapter.close()
        Thread.sleep(50)
        assertThrows(IOException::class.java) { stream.send("010C", 2_000) }
    }
}
