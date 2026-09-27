package com.nethra.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.camera.video.MediaStoreOutputOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves videos to the shared Movies/NETHRA folder (no storage permission needed on Android 10+). */
class MediaStoreVideos(private val context: Context) {

    fun newBaseName(): String = "NETHRA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Where CameraX writes the original recording. */
    fun recordingOutput(baseName: String): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
        }
        return MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /** Copies a finished export next to the original. The temp file is left for the caller to delete. */
    suspend fun saveCopy(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the new video")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IllegalStateException("Couldn't open the new video for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Saves a small text file (e.g. .srt subtitles) to Documents/NETHRA. */
    suspend fun saveText(text: String, displayName: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NETHRA")
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IllegalStateException("MediaStore refused the subtitle file")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Couldn't write the subtitle file")
        uri
    }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun durationMs(uri: Uri): Long? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    fun durationMs(file: File): Long? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    companion object {
        const val RELATIVE_DIR = "Movies/NETHRA"
    }
}
