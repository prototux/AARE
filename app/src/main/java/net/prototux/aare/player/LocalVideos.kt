package net.prototux.aare.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat

/** A video on the phone (MediaStore). */
data class LocalVideo(val id: Long, val uri: Uri, val title: String, val durationMs: Long, val folderId: String, val folder: String, val addedSec: Long)

/** A folder of videos on the phone, newest first. */
data class VideoFolder(val id: String, val name: String, val count: Int, val newestSec: Long)

/** The phone's videos, through MediaStore (needs [permission]). Call off the main thread. */
object LocalVideos {
    /** The permission this Android version needs to list the phone's videos. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Every video, newest first. */
    fun all(context: Context): List<LocalVideo> {
        if (!granted(context)) return emptyList()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val out = ArrayList<LocalVideo>()
        runCatching {
            context.contentResolver.query(collection, cols, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(cols[0]); val name = c.getColumnIndexOrThrow(cols[1]); val title = c.getColumnIndexOrThrow(cols[2])
                val dur = c.getColumnIndexOrThrow(cols[3]); val bucket = c.getColumnIndexOrThrow(cols[4]); val bucketName = c.getColumnIndexOrThrow(cols[5])
                val added = c.getColumnIndexOrThrow(cols[6])
                while (c.moveToNext()) {
                    val vid = c.getLong(id)
                    val display = c.getString(name)?.substringBeforeLast('.')
                    out += LocalVideo(
                        id = vid,
                        uri = ContentUris.withAppendedId(collection, vid),
                        title = display?.takeIf { it.isNotBlank() } ?: c.getString(title) ?: "Video $vid",
                        durationMs = c.getLong(dur),
                        folderId = c.getString(bucket) ?: "",
                        folder = c.getString(bucketName) ?: "",
                        addedSec = c.getLong(added),
                    )
                }
            }
        }
        return out
    }

    /** Videos grouped by folder, the folder with the newest video first. */
    fun folders(videos: List<LocalVideo>): List<VideoFolder> =
        videos.groupBy { it.folderId }.map { (id, v) -> VideoFolder(id, v.first().folder, v.size, v.maxOf { it.addedSec }) }
            .sortedByDescending { it.newestSec }

    private val thumbs = LruCache<Long, Bitmap>(64)

    /** A small frame of the video (Android 10+), cached; null if there's none. */
    fun thumbnail(context: Context, video: LocalVideo, width: Int, height: Int): Bitmap? {
        thumbs.get(video.id)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.contentResolver.loadThumbnail(video.uri, Size(width, height), null) }.getOrNull()
            ?.also { thumbs.put(video.id, it) }
    }
}
