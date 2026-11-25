package net.prototux.aare.faces

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A card file: its text, and [spec] read from it, or the [error] that keeps it from showing. */
class Face(val id: String, val json: String) {
    val spec: FaceSpec?
    val error: String?

    init {
        var message: String? = null
        spec = try { FaceSpec.parse(json) } catch (e: FaceException) { message = e.message; null }
        error = message
    }

    val name: String get() = spec?.name ?: id
}

/**
 * The card designs: the bundled clocks (assets/faces), and the user's own cards, one file each in
 * the app's private files. Nothing is shared between phones by AARE: files are imported by
 * hand.
 */
object FaceStore {
    private const val TAG = "AareFaces"
    private const val DIR = "faces"
    const val MAX_FACES = 50
    const val DEFAULT_CLOCK = "digital"
    /** The bundled designs, in the order shown. */
    val BUNDLED = listOf("digital", "stacked", "weather", "classic", "minimal", "rings", "elegant", "segments", "agenda")

    private val _own = MutableStateFlow<List<Face>>(emptyList())
    /** The user's cards, oldest first. */
    val own: StateFlow<List<Face>> = _own
    @Volatile private var loaded = false
    @Volatile private var bundledCache: List<Face>? = null

    /** The bundled designs. Reads the assets the first time: call off the main thread then. */
    fun bundled(context: Context): List<Face> = bundledCache ?: BUNDLED.mapNotNull { id ->
        runCatching { Face(id, context.assets.open("$DIR/$id.json").bufferedReader().use { it.readText() }) }
            .onFailure { Log.w(TAG, "bundled $id: ${it.message}") }.getOrNull()
    }.also { bundledCache = it }

    /** The bundled design [id], or the default one (an id from a later version). */
    fun clock(context: Context, id: String): FaceSpec? =
        bundled(context).let { all -> all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == DEFAULT_CLOCK } }?.spec

    /** Reads the user's cards, once. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) { loadNow(context) }

    @Synchronized
    private fun loadNow(context: Context) {
        if (loaded) return
        _own.value = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching { Face(f.name.removeSuffix(".json"), f.readText()) }.onFailure { Log.w(TAG, "${f.name}: ${it.message}") }.getOrNull()
        }
        loaded = true
    }

    /**
     * Adds the card in [json], in place of the one of the same name if there is one (a corrected
     * file keeps its place on the dashboard). Throws [FaceException] if it isn't a card or there are
     * too many.
     */
    suspend fun import(context: Context, json: String): Face = withContext(Dispatchers.IO) { importNow(context, json.removePrefix("\uFEFF")) }

    @Synchronized
    private fun importNow(context: Context, json: String): Face {
        loadNow(context)
        val old = FaceSpec.parse(json).name.let { name -> _own.value.firstOrNull { it.spec?.name == name } }
        if (old == null && _own.value.size >= MAX_FACES) throw FaceException("there are already $MAX_FACES cards")
        val face = Face(old?.id ?: newId(), json)
        val file = File(dir(context), "${face.id}.json")
        // To a temporary file first: a full disk doesn't leave half a card.
        val tmp = File(dir(context), "${face.id}.tmp")
        try {
            tmp.writeText(json)
            check(tmp.renameTo(file)) { "can't write ${file.name}" }
        } finally {
            tmp.delete()
        }
        _own.value = if (old == null) _own.value + face else _own.value.map { if (it === old) face else it }
        return face
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) { deleteNow(context, id) }

    @Synchronized
    private fun deleteNow(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
        _own.value = _own.value.filterNot { it.id == id }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** Letters and digits only (it is a file name, and part of the dashboard's comma-separated order), in order of creation. */
    private fun newId() = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(4)
}
