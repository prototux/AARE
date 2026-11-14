package net.prototux.aare.ui.car

import net.prototux.aare.host.templates.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageInputTest {
    @Test
    fun typedTextBecomesOneSafeStringLiteral() {
        assertEquals("\"abc\"", PageInput.jsString("abc"))
        assertEquals("\"\\\"x\\\" \\\\ y\"", PageInput.jsString("\"x\" \\ y"))
        assertEquals("\"a\\nb\\rc\"", PageInput.jsString("a\nb\rc"))
        assertEquals("\"\\u2028\\u2029\"", PageInput.jsString("\u2028\u2029"))
        assertEquals("\"\\u003c/script>\"", PageInput.jsString("</script>"))
        assertEquals("\"\\u0007\"", PageInput.jsString("\u0007"))
        assertEquals("\"éß字 😀\"", PageInput.jsString("éß字 😀"))
    }

    @Test
    fun insertTypesAtTheCaret() {
        assertEquals("document.execCommand('insertText',false,\"it's\")", PageInput.insert("it's"))
    }

    @Test
    fun fieldKindPicksTheKeyboard() {
        assertEquals(KeyboardMode.Text, PageInput.keyboardFor("text"))
        assertEquals(KeyboardMode.Email, PageInput.keyboardFor("email"))
        assertEquals(KeyboardMode.Number, PageInput.keyboardFor("number"))
        assertEquals(KeyboardMode.Phone, PageInput.keyboardFor("tel"))
        assertNull(PageInput.keyboardFor(""))
    }

    /** evaluateJavascript hands back the result JSON-encoded, or "null". */
    @Test
    fun evaluateResultIsUnquoted() {
        assertEquals(KeyboardMode.Text, PageInput.keyboardFor(PageInput.unquote("\"text\"")))
        assertNull(PageInput.keyboardFor(PageInput.unquote("null")))
        assertNull(PageInput.keyboardFor(PageInput.unquote(null)))
    }
}
