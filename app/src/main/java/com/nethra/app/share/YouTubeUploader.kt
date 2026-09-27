package com.nethra.app.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class YouTubeUploadResult(val videoId: String, val privacy: String) {
    val watchUrl get() = YouTubeMetadata.watchUrl(videoId)
    val studioUrl get() = YouTubeMetadata.studioUrl(videoId)
}

/** An upload problem with a message the creator can act on. [authExpired] = get a new token and retry. */
class YouTubeUploadException(message: String, val authExpired: Boolean = false, val retryable: Boolean = false) : Exception(message)

/**
 * Uploads a video with the YouTube Data API's resumable protocol:
 *  1. POST the metadata (title, description, tags, privacy) → an upload session URL;
 *  2. PUT the file in 8 MB chunks with Content-Range, reporting progress;
 *  3. on a dropped connection or 5xx, ask the session where it got to and continue.
 *
 * The original video file is only read. Cancel by cancelling the coroutine.
 */
class YouTubeUploader(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** The upload session, kept so a retry after a token refresh continues instead of restarting. */
    private var session: String? = null
    private var sessionFor: Uri? = null

    fun resetSession() { session = null; sessionFor = null }

    suspend fun upload(
        video: Uri,
        body: JSONObject,
        accessToken: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): YouTubeUploadResult = withContext(Dispatchers.IO) {
        val total = sizeOf(video) ?: throw YouTubeUploadException("Couldn't read the video's size. Pick the video again.")
        val mime = context.contentResolver.getType(video) ?: "video/*"
        if (sessionFor != video) resetSession()
        val url = session ?: startSession(body, accessToken, total, mime).also { session = it; sessionFor = video }

        var offset = queryOffset(url, accessToken, total)?.let { if (it < 0) return@withContext finish(it, url, accessToken, total) else it } ?: 0L
        var failures = 0
        try {
        while (offset < total) {
            coroutineContext.ensureActive()
            val len = minOf(CHUNK, total - offset)
            val bytes = readRange(video, offset, len)
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Range", "bytes $offset-${offset + len - 1}/$total")
                .put(bytes.toRequestBody(mime.toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 308 -> { offset = nextOffset(r); failures = 0; onProgress(offset, total) }
                        r.code == 200 || r.code == 201 -> { onProgress(total, total); return@withContext parseDone(r) }
                        r.code == 401 -> throw YouTubeUploadException("Sign-in expired.", authExpired = true)
                        r.code == 404 || r.code == 410 -> { resetSession(); throw YouTubeUploadException("YouTube dropped the upload session. Tap Upload to start again.", retryable = true) }
                        r.code >= 500 -> throw IOException("YouTube server error ${r.code}")
                        else -> throw apiError(r)
                    }
                }
            } catch (e: IOException) {
                if (++failures > MAX_RETRIES) throw YouTubeUploadException("The connection keeps dropping (${e.message}). Tap Upload to continue where it stopped.", retryable = true)
                delay(1000L shl (failures - 1).coerceAtMost(5))
                offset = queryOffset(url, accessToken, total)?.let { if (it < 0) return@withContext finish(it, url, accessToken, total) else it } ?: offset
            }
        }
        } finally {
            closeStream()
        }
        finish(-1, url, accessToken, total)
    }

    private fun startSession(body: JSONObject, token: String, total: Long, mime: String): String {
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Length", total.toString())
            .header("X-Upload-Content-Type", mime)
            .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (r.code == 401) throw YouTubeUploadException("Sign-in expired.", authExpired = true)
                if (!r.isSuccessful) throw apiError(r)
                return r.header("Location") ?: throw YouTubeUploadException("YouTube didn't start an upload session. Try again.", retryable = true)
            }
        } catch (e: IOException) {
            throw YouTubeUploadException("Couldn't reach YouTube (${e.message}). Check the connection and try again.", retryable = true)
        }
    }

    /** @return bytes already received, -1 if the upload is already complete, or null if unknown. */
    private fun queryOffset(url: String, token: String, total: Long): Long? = try {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            when (r.code) {
                308 -> nextOffset(r)
                200, 201 -> -1L
                401 -> throw YouTubeUploadException("Sign-in expired.", authExpired = true)
                else -> null
            }
        }
    } catch (e: IOException) { null }

    private fun finish(@Suppress("UNUSED_PARAMETER") marker: Long, url: String, token: String, total: Long): YouTubeUploadResult {
        // Ask once more for the final resource (the last chunk's response may have been lost).
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 200 || r.code == 201) return parseDone(r)
            throw YouTubeUploadException("The upload finished but YouTube didn't confirm it. Check YouTube Studio before uploading again.")
        }
    }

    private fun nextOffset(r: Response): Long =
        r.header("Range")?.substringAfter("-")?.toLongOrNull()?.plus(1) ?: 0L

    private fun parseDone(r: Response): YouTubeUploadResult {
        val json = JSONObject(r.body?.string().orEmpty())
        resetSession()
        return YouTubeUploadResult(
            videoId = json.optString("id").ifBlank { throw YouTubeUploadException("YouTube accepted the video but didn't return its id. Check YouTube Studio.") },
            privacy = json.optJSONObject("status")?.optString("privacyStatus").orEmpty()
        )
    }

    private fun apiError(r: Response): YouTubeUploadException {
        val raw = r.body?.string().orEmpty()
        val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val msg = err?.optString("message").orEmpty()
        Log.w(TAG, "Upload error ${r.code} $reason")
        return YouTubeUploadException(YouTubeErrors.explain(r.code, reason, msg))
    }

    private fun sizeOf(uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0).takeIf { it > 0 }
        }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l > 0 } }
    }

    // One stream is read front to back across chunks; it is only reopened (and skipped
    // forward) after a resume, so a long video isn't re-read from the start per chunk.
    private var stream: InputStream? = null
    private var streamPos = 0L

    private fun closeStream() { runCatching { stream?.close() }; stream = null; streamPos = 0 }

    private fun readRange(uri: Uri, offset: Long, len: Long): ByteArray {
        if (stream == null || streamPos != offset) {
            closeStream()
            val s = context.contentResolver.openInputStream(uri) ?: throw YouTubeUploadException("Couldn't open the video. Pick it again.")
            var skipped = 0L
            while (skipped < offset) {
                val n = s.skip(offset - skipped)
                if (n > 0) { skipped += n; continue }
                if (s.read() < 0) { s.close(); throw YouTubeUploadException("The video file got shorter while uploading.") }
                skipped++
            }
            stream = s; streamPos = offset
        }
        val s = stream!!
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) {
            val n = s.read(buf, read, buf.size - read)
            if (n < 0) throw YouTubeUploadException("The video file got shorter while uploading.")
            read += n
        }
        streamPos += read
        return buf
    }

    companion object {
        private const val TAG = "YouTubeUploader"
        /** 8 MiB — a multiple of 256 KiB, as the resumable protocol requires. */
        const val CHUNK = 8L * 1024 * 1024
        private const val MAX_RETRIES = 6
    }
}
