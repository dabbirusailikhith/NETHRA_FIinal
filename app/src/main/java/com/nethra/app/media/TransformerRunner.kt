package com.nethra.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.transformer.Composition
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
 * Runs a Media3 [Transformer] export as a cancellable suspend call. Transformer is
 * driven from the main looper; decoding/encoding happens on its own threads.
 */
object TransformerRunner {
    suspend fun export(context: Context, composition: Composition, out: File, onProgress: (Float) -> Unit) {
        out.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        handler.postDelayed(this, 300)
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
