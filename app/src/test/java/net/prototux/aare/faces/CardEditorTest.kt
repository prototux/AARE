package net.prototux.aare.faces

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card editor (tools/card-editor) knows the same format as the app, and its page was built from its sources. */
class CardEditorTest {
    private val dir = File("../tools/card-editor")
    private val template = File(dir, "template.html").readText()
    private val page = File(dir, "index.html").readText()

    private fun json(name: String) = JSONObject(page.lineSequence().first { it.startsWith("const $name = ") }.removePrefix("const $name = ").removeSuffix(";").replace("<\\/", "</"))

    /** The quoted words of the list or object [name] in the editor's code. */
    private fun list(name: String): List<String> =
        Regex("""const $name = \[(.*?)];""").find(template)!!.groupValues[1].let { Regex("'([^']*)'").findAll(it).map { m -> m.groupValues[1] }.toList() }

    private fun keys(name: String): List<String> =
        Regex("""const $name = \{(.*?)};""").find(template)!!.groupValues[1].let { Regex("""(\w+):""").findAll(it).map { m -> m.groupValues[1] }.toList() }

    @Test
    fun thePageIsBuiltFromItsSources() {
        val rebuild = "run tools/card-editor/build.py"
        val code = template.substringAfter("const LANGS")
        assertTrue(rebuild, page.endsWith(code))
        assertTrue(rebuild, page.contains(template.substringAfter("/*FONT*/").substringBefore("/*DESIGNS*/")))
        val designs = json("DESIGNS")
        assertEquals(rebuild, FaceStore.BUNDLED.toSet(), designs.keys().asSequence().toSet())
        for (id in FaceStore.BUNDLED) assertEquals("$id: $rebuild", JSONObject(File("src/main/assets/faces/$id.json").readText()).toString(), designs.getJSONObject(id).toString())
        val rows = json("WORDS").getJSONObject("rows")
        for (line in File(dir, "words.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val key = line.substringBefore(" = ")
            if (key != "help") assertEquals("$key: $rebuild", line.substringAfter(" = ").split(" | ").map { it.trim() }, rows.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) })
        }
    }

    @Test
    fun itKnowsTheSameFormat() {
        assertEquals(FaceValue.entries.map { it.id }, keys("VALUES"))
        assertEquals(FaceCondition.entries.map { it.id }, list("CONDITIONS"))
        assertEquals(FaceToken.entries.map { it.id }, list("TOKENS").filter { ':' !in it })
        assertEquals(FaceLayer.Font.entries.map { it.name.lowercase() }, list("FONTS"))
        assertEquals(ICONS, json("ICONS").keys().asSequence().toSet())
        assertEquals(FaceColor.NAMES, Regex("""night: \{(.*?)}""").find(template)!!.groupValues[1].split(',').map { it.substringBefore(':') }.toSet())
    }
}
