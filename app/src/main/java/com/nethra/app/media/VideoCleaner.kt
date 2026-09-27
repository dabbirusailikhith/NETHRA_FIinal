package com.nethra.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exports the kept spans of a video, in order, into one new MP4 with Media3
 * Transformer. The source is only read. Transformer is driven from the main
 * looper but does all decoding/encoding on its own background threads.
 */
class VideoCleaner(private val context: Context) {

    suspend fun export(source: Uri, keeps: List<Span>, out: File, onProgress: (Float) -> Unit) {
        require(keeps.isNotEmpty()) { "Nothing to export" }
        out.delete()
        val items = keeps.map { span ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(span.startMs)
                            .setEndPositionMs(span.endMs)
                            .build()
                    )
                    .build()
            ).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        handler.postDelayed(this, 250)
                    }
                }
                transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            onProgress(1f)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            out.delete()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        runCatching { transformer.cancel() }
                        out.delete()
                    }
                }
                transformer.start(composition, out.absolutePath)
                handler.post(poll)
            }
        }
    }
}
