package net.prototux.aare.ui.car

import net.prototux.aare.host.templates.KeyboardMode

/**
 * Typing into a web page's text fields from the [net.prototux.aare.host.templates.CarKeyboard]
 * (#148). The phone's IME can't show on the car display, so the browser asks the page, after each
 * tap, which kind of field has the focus, and types into it with small scripts. No JavaScript
 * interface is added to the page: a page can't call into AARE.
 */
internal object PageInput {
    /**
     * Evaluates to the focused field's kind: "text", "email", "number", "tel", or "" when no
     * editable field has the focus. A field inside a frame from another site isn't reachable.
     */
    const val FOCUSED_FIELD = """(function(){var e=document.activeElement;
while(e&&e.tagName==='IFRAME'){try{e=e.contentDocument.activeElement}catch(x){return ''}}
if(!e||e.disabled||e.readOnly)return '';
if(e.isContentEditable||e.tagName==='TEXTAREA')return 'text';
if(e.tagName!=='INPUT')return '';
var t=(e.getAttribute('type')||'text').toLowerCase();
if(t==='email'||t==='number'||t==='tel')return t;
return ['text','search','url','password'].indexOf(t)>=0?'text':'';})()"""

    /** Deletes the character before the caret, as the backspace key does. */
    const val BACKSPACE = "document.execCommand('delete',false)"

    /**
     * Enter: submits the field's form (DuckDuckGo's and Wikipedia's search boxes are in one), or
     * sends the page an Enter key for fields without a form.
     */
    const val SUBMIT = """(function(){var e=document.activeElement;
while(e&&e.tagName==='IFRAME'){try{e=e.contentDocument.activeElement}catch(x){return}}
if(!e)return;
if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();return}
['keydown','keypress','keyup'].forEach(function(n){e.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))});})()"""

    /** Leaves the field, so the keyboard doesn't come back on the next tap elsewhere. */
    const val BLUR = "document.activeElement&&document.activeElement.blur&&document.activeElement.blur()"

    /** Brings the focused field back into view once the keyboard has taken its place. */
    const val SHOW_FIELD = "document.activeElement&&document.activeElement.scrollIntoView({block:'nearest'})"

    /** Inserts [text] at the caret; the page gets its usual input events. */
    fun insert(text: String) = "document.execCommand('insertText',false,${jsString(text)})"

    /** The car keyboard for a field kind from [FOCUSED_FIELD], or null when nothing is editable. */
    fun keyboardFor(kind: String): KeyboardMode? = when (kind) {
        "text" -> KeyboardMode.Text
        "email" -> KeyboardMode.Email
        "number" -> KeyboardMode.Number
        "tel" -> KeyboardMode.Phone
        else -> null
    }

    /** What evaluateJavascript hands back for a string result: the value JSON-quoted. */
    fun unquote(result: String?): String = result?.removeSurrounding("\"").orEmpty()

    /** [s] as a JavaScript string literal. */
    fun jsString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            '<' -> append("\\u003c") // never closes a script element, whatever the text
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
