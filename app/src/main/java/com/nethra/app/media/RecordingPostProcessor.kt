package com.nethra.app.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nethra.app.captions.CaptionMaker
import com.nethra.app.captions.CaptionStatus
import com.nethra.app.core.AppPrefs
import com.nethra.app.core.NetworkMonitor
import com.nethra.app.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface CleanStatus {
    data object Idle : CleanStatus
    data class Working(val stage: String, val progress: Float?) : CleanStatus
    /** Export succeeded — only now may the app say the commands were removed. */
    data class Done(
        val cleanUri: Uri,
        val cleanName: String,
        val commandsRemoved: Int,
        val removedMs: Long,
        val boundariesRefined: Boolean
    ) : CleanStatus
    /** The original is untouched and still saved; [message] says why no clean copy exists. */
    data class Failed(val message: String) : CleanStatus
}

/**
 * Everything that happens to a recording after Stop, in the application scope so it
 * keeps going if the creator leaves the screen. The original is never modified.
 *
 *  1. Clean copy — spoken commands cut out (teleprompter takes with "Nethra pause/stop").
 *  2. Captioned copy — word-by-word captions burned in, made from the clean copy when
 *     there is one (when "Auto captions" is on and there's internet).
 *
 * Recordings are processed one at a time (each is a full re-encode).
 */
class RecordingPostProcessor(
    private val context: Context,
    private val store: MediaStoreVideos,
    private val captions: CaptionMaker,
    private val prefs: AppPrefs,
    private val network: NetworkMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Mutex()

    private val _status = MutableStateFlow<CleanStatus>(CleanStatus.Idle)
    val status: StateFlow<CleanStatus> = _status

    private val _captionStatus = MutableStateFlow<CaptionStatus>(CaptionStatus.Idle)
    val captionStatus: StateFlow<CaptionStatus> = _captionStatus

    fun clear() {
        if (_status.value !is CleanStatus.Working) _status.value = CleanStatus.Idle
        if (_captionStatus.value !is CaptionStatus.Working) _captionStatus.value = CaptionStatus.Idle
    }

    /** Called when a recording has been saved. */
    fun afterRecording(original: Uri, baseName: String, marks: List<CommandMark>, recordedMs: Long) {
        val wantCaptions = prefs.autoCaptions.value
        if (marks.isEmpty() && !wantCaptions) return
        if (wantCaptions) _captionStatus.value = CaptionStatus.Working("Captions: waiting…", null)
        scope.launch {
            queue.withLock {
                val cleanUri = if (marks.isNotEmpty()) cleanCopy(original, baseName, marks, recordedMs) else null
                if (wantCaptions) captionCopy(cleanUri ?: original, "${baseName}_captions")
            }
        }
    }

    /** Captions for any saved video (e.g. from the Transcript screen). */
    fun captionOnly(source: Uri, baseName: String) {
        _captionStatus.value = CaptionStatus.Working("Captions: waiting…", null)
        scope.launch { queue.withLock { captionCopy(source, "${baseName}_captions") } }
    }

    private suspend fun captionCopy(source: Uri, outBase: String) {
        if (!captions.canRun) {
            _captionStatus.value = CaptionStatus.Failed("Captions need internet, or an on-device Gemma model to caption offline. Your video is saved in Movies/NETHRA.")
            return
        }
        try {
            _captionStatus.value = captions.make(
                source, outBase, prefs.language.value, prefs.captionScript.value
            ) { _captionStatus.value = it }
        } catch (e: CancellationException) {
            _captionStatus.value = CaptionStatus.Failed("Captions were cancelled. Your video is saved in Movies/NETHRA.")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Captions failed: ${e.javaClass.simpleName}: ${e.message}")
            _captionStatus.value = CaptionStatus.Failed(
                "Couldn't add captions (${e.userMessage().removePrefix("Something went wrong: ")}). Your video is saved unchanged in Movies/NETHRA."
            )
        }
    }

    /** @return the saved clean copy, or null if none was made. */
    private suspend fun cleanCopy(original: Uri, baseName: String, marks: List<CommandMark>, recordedMs: Long): Uri? {
        val pcm = File(context.cacheDir, "clean_${System.nanoTime()}.pcm")
        val out = File(context.cacheDir, "${baseName}_clean.mp4")
        try {
            _status.value = CleanStatus.Working("Finding command boundaries…", null)
            val duration = store.durationMs(original) ?: recordedMs
            val envelope = try {
                AudioExtractor(context).extract(original, pcm) { f ->
                    _status.value = CleanStatus.Working("Finding command boundaries…", f)
                }.frameRms
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Audio analysis failed; using recogniser timing only (${e.javaClass.simpleName})")
                null
            } finally {
                pcm.delete()
            }

            val plan = CommandCutPlanner.plan(marks, duration, envelope)
            if (plan.keeps.isEmpty()) {
                _status.value = CleanStatus.Failed("The recording was almost entirely commands, so no clean copy was made. The original is saved in Movies/NETHRA.")
                return null
            }

            _status.value = CleanStatus.Working("Exporting clean copy…", 0f)
            VideoCleaner(context).export(original, plan.keeps, out) { p ->
                _status.value = CleanStatus.Working("Exporting clean copy…", p)
            }
            val outDuration = store.durationMs(out)
            if (!out.exists() || out.length() == 0L || outDuration == null || outDuration <= 0) {
                throw IllegalStateException("the exported file is empty")
            }
            _status.value = CleanStatus.Working("Saving to Movies/NETHRA…", null)
            val name = "${baseName}_clean.mp4"
            val uri = store.saveCopy(out, name)
            _status.value = CleanStatus.Done(uri, name, marks.size, plan.removedMs, plan.boundariesRefined)
            return uri
        } catch (e: CancellationException) {
            _status.value = CleanStatus.Failed("Clean export was cancelled. The original is saved in Movies/NETHRA.")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Clean export failed: ${e.javaClass.simpleName}")
            _status.value = CleanStatus.Failed(
                "Couldn't export the clean copy (${e.userMessage().removePrefix("Something went wrong: ")}). " +
                    "The original recording is saved unchanged in Movies/NETHRA, spoken commands included."
            )
            return null
        } finally {
            out.delete()
        }
    }

    companion object { private const val TAG = "PostProcessor" }
}
