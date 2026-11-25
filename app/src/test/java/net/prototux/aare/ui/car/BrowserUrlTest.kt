package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlTest {
    @Test fun detectsPlainHttpUrls() {
        assertTrue(isPlainHttpUrl("http://example.com"))
        assertTrue(isPlainHttpUrl("HTTP://192.168.1.1:8080/a"))
        assertTrue(isPlainHttpUrl("  http://localhost "))

        assertFalse(isPlainHttpUrl("https://example.com"))
        assertFalse(isPlainHttpUrl("HTTPS://example.com"))
        assertFalse(isPlainHttpUrl("about:blank"))
        assertFalse(isPlainHttpUrl("data:text/html,hi"))
        assertFalse(isPlainHttpUrl(null))
        assertFalse(isPlainHttpUrl(""))
    }

    @Test fun addsHttpsSchemeWhenMissing() {
        assertEquals("https://example.com", toUrl("example.com"))
        assertEquals("https://example.com/path", toUrl("  https://example.com/path "))
        assertEquals("http://example.com", toUrl("http://example.com"))
        assertEquals("HTTP://192.168.1.1", toUrl("HTTP://192.168.1.1"))
    }
}
