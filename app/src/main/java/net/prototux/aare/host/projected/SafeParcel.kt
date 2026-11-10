package net.prototux.aare.host.projected

import android.os.Parcel
import android.os.Parcelable

/**
 * Writer for Google Play Services' "SafeParcelable" wire format, the encoding its car-projection
 * objects (DrawingSpec, CarInfo, CarUiInfo) use. These classes aren't in the Android SDK, so the
 * host writes their parcels by hand.
 *
 * A SafeParcelable is a header followed by tagged fields. The header is the magic `0x4245` (20293)
 * in the high-tagged form, then a 32-bit total object size that is back-patched once the body is
 * written. Each field starts with a 32-bit tag whose low 16 bits are the field id:
 *  - an inline 4-byte value (int, boolean) tags the id with [INLINE] and is followed by the value;
 *  - a variable-length value (String, Parcelable, array) tags the id with [SIZED], then a 32-bit
 *    field size, then the value; the size is back-patched the same way as the header.
 *
 * All sizes are "bytes after the size word", i.e. `end - (start + 8)`.
 */
internal object SafeParcel {

    private const val OBJECT_MAGIC = 20293
    /** High bits marking a tag as a sized (length-prefixed) field or the object header. */
    const val SIZED = 0xffff0000.toInt()
    /** High bits marking a tag as an inline 4-byte field. */
    const val INLINE = 0x00040000

    /** Writes the object header and returns the start position to pass back to [end]. */
    fun begin(p: Parcel): Int {
        val start = p.dataPosition()
        p.writeInt(OBJECT_MAGIC or SIZED)
        p.writeInt(0) // total size, back-patched in end()
        return start
    }

    /** Back-patches the object's total size. */
    fun end(p: Parcel, start: Int) = patchSize(p, start)

    fun intField(p: Parcel, id: Int, value: Int) {
        p.writeInt(id or INLINE)
        p.writeInt(value)
    }

    fun booleanField(p: Parcel, id: Int, value: Boolean) = intField(p, id, if (value) 1 else 0)

    fun stringField(p: Parcel, id: Int, value: String?) = sizedField(p, id) { p.writeString(value) }

    fun parcelableField(p: Parcel, id: Int, value: Parcelable, flags: Int = 0) =
        sizedField(p, id) { value.writeToParcel(p, flags) }

    /** Writes a length-prefixed field: the tag, a placeholder size, [body], then the patched size. */
    private inline fun sizedField(p: Parcel, id: Int, body: () -> Unit) {
        val start = p.dataPosition()
        p.writeInt(id or SIZED)
        p.writeInt(0)
        body()
        patchSize(p, start)
    }

    /** Rewrites the size word at `start + 4` to the bytes written since, then restores the position. */
    private fun patchSize(p: Parcel, start: Int) {
        val end = p.dataPosition()
        p.setDataPosition(start + 4)
        p.writeInt(end - (start + 8))
        p.setDataPosition(end)
    }
}
