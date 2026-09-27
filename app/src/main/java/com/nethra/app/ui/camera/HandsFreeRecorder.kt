package com.nethra.app.ui.camera

import android.annotation.SuppressLint
import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.nethra.app.AppContainer
import com.nethra.app.speech.HeardTiming
import com.nethra.app.speech.Speaker
import com.nethra.app.speech.SpeechCallbacks
import com.nethra.app.speech.SpeechListener
import com.nethra.app.speech.Utterance
import com.nethra.app.speech.VoiceCommand
import com.nethra.app.speech.WakeWord
import com.nethra.app.ui.prompter.RecState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecorderUi(
    val canRecord: Boolean = false,
    val micGranted: Boolean = false,
    val listening: Boolean = false,
    val heard: String = "",
    val voiceProblem: String? = null,
    val rec: RecState = RecState.IDLE,
    val recordedMs: Long = 0,
    /** 3-2-1 before a voice-started recording. */
    val countdown: Int? = null,
    val recordMessage: String? = null,
    val savedName: String? = null,
    /** NETHRA answers commands out loud ("Paused.", the countdown). */
    val voiceReplies: Boolean = true
) {
    val isRecordingActive get() = rec != RecState.IDLE
    /** Recording, paused or about to start: the shutter shows "stop". */
    val shutterIsStop get() = rec == RecState.RECORDING || rec == RecState.PAUSED || rec == RecState.STARTING || countdown != null
}

/**
 * Hands-free video recording shared by the CAMERA and AI FRAMING modes:
 * CameraX recording to Movies/NETHRA plus the voice commands
 * "Nethra, start recording" (with a spoken 3-2-1), "Nethra, pause",
 * "Nethra, resume" and "Nethra, stop". Buttons call the same functions.
 *
 * Replies are spoken only while the camera isn't capturing (before start,
 * after pause, before resume, after stop) so NETHRA's voice stays out of takes.
 *
 * Main thread only. Owned by a ViewModel, which passes its scope.
 */
class HandsFreeRecorder(
    private val app: Application,
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val logTag: String = "NethraRecorder"
) : SpeechCallbacks {

    private val _state = MutableStateFlow(RecorderUi())
    val state: StateFlow<RecorderUi> = _state

    private val listener = SpeechListener(app, this)
    private var foreground = false
    private var recording: Recording? = null
    private var countdownJob: Job? = null
    private var lastCmd: VoiceCommand? = null
    private var lastCmdAt = 0L

    /** Surface rotation used as the video orientation (set by the screen). */
    var targetRotation: Int = 0

    /** Bind this with the preview. It can be re-bound to the other lens between takes. */
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            // MIC (not CAMCORDER) so the speech recogniser can keep hearing "Nethra, stop".
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .build()
    )

    // ------------------------------------------------------------ lifecycle

    fun setCanRecord(can: Boolean) = _state.update { it.copy(canRecord = can) }

    fun setMicGranted(granted: Boolean) {
        if (_state.value.micGranted == granted) return
        _state.update { it.copy(micGranted = granted) }
        refreshListening()
    }

    fun setVoiceReplies(on: Boolean) {
        if (!on) speaker.stop()
        _state.update { it.copy(voiceReplies = on) }
    }

    fun onForeground() { foreground = true; refreshListening() }

    fun onBackground() {
        foreground = false
        cancelCountdown()
        refreshListening()
    }

    private fun refreshListening() {
        val s = _state.value
        // Older phones: the recogniser uses its own mic, which Android refuses during our recording
        // (and shows a warning each time it tries). Pause listening for the take there.
        val blocked = s.isRecordingActive && !listener.canListenWhileRecording
        val want = foreground && s.micGranted && !blocked
        if (want && !listener.isRunning) listener.start()
        if (!want && listener.isRunning) listener.stop()
        if (!want) _state.update { it.copy(listening = false) }
    }

    fun dismissMessage() = _state.update { it.copy(recordMessage = null) }

    val captionStatus = c.postProcessor.captionStatus
    val autoCaptions = c.prefs.autoCaptions
    fun toggleAutoCaptions() = c.prefs.setAutoCaptions(!c.prefs.autoCaptions.value)
    fun dismissCaptions() = c.postProcessor.clear()

    fun release() {
        cancelCountdown()
        listener.stop()
        runCatching { recording?.stop() }
    }

    // ------------------------------------------------------------ voice

    override fun onListening(active: Boolean) = _state.update { it.copy(listening = listener.isRunning) }

    override fun onProblem(message: String, fatal: Boolean) = _state.update { it.copy(voiceProblem = message) }

    override fun onPartial(text: String) {
        _state.update { it.copy(heard = text) }
        // Act on "Nethra stop / pause / resume" as soon as the words are there (~1 s sooner).
        WakeWord.fastCommand(text)?.let { onCommand(it) }
    }

    override fun onFinal(text: String, timing: HeardTiming) {
        _state.update { it.copy(heard = text) }
        val u = WakeWord.parse(text)
        if (u is Utterance.Command) onCommand(u.command)
        else if (u is Utterance.Plain) WakeWord.bareCommand(text)?.let(::onCommand)
    }

    /** A partial and its final result usually carry the same command: act once. */
    private fun onCommand(cmd: VoiceCommand) {
        val now = SystemClock.elapsedRealtime()
        if (cmd == lastCmd && now - lastCmdAt < COMMAND_DEDUPE_MS) return
        lastCmd = cmd; lastCmdAt = now
        val s = _state.value
        when (cmd) {
            VoiceCommand.START_RECORDING -> if (s.rec == RecState.IDLE && s.countdown == null) startWithCountdown()
            VoiceCommand.PAUSE -> if (s.rec == RecState.RECORDING) { pause(); reply("Paused.") }
            // Say it first, then resume, so NETHRA's voice isn't in the take.
            VoiceCommand.RESUME -> if (s.rec == RecState.PAUSED) reply("Resuming.") { resume() }
            VoiceCommand.STOP -> when {
                s.countdown != null -> { cancelCountdown(); reply("Cancelled.") }
                s.rec == RecState.RECORDING || s.rec == RecState.PAUSED || s.rec == RecState.STARTING -> stop()
                else -> Unit
            }
        }
    }

    private fun reply(text: String, then: (() -> Unit)? = null) {
        if (_state.value.voiceReplies) speaker.say(text, Speaker.Priority.URGENT, then) else then?.invoke()
    }

    // ------------------------------------------------------------ recording

    /** Voice start: "Recording in three, two, one" so the speaker can settle, then record. */
    fun startWithCountdown() {
        if (_state.value.rec != RecState.IDLE || !_state.value.canRecord) return
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (n in 3 downTo 1) {
                _state.update { it.copy(countdown = n) }
                reply(when (n) { 3 -> "Recording in three."; 2 -> "Two."; else -> "One." })
                delay(if (n == 3) 1300 else 1000)
            }
            _state.update { it.copy(countdown = null) }
            speaker.stop()
            startRecording()
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel(); countdownJob = null
        if (_state.value.countdown != null) _state.update { it.copy(countdown = null) }
    }

    /** Shutter button: iOS starts immediately; a tap during the countdown cancels it; while recording it stops. */
    fun onShutter() {
        val s = _state.value
        when {
            s.countdown != null -> cancelCountdown()
            s.rec == RecState.IDLE -> startRecording()
            else -> stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val s = _state.value
        if (s.rec != RecState.IDLE) return
        if (!s.canRecord) { _state.update { it.copy(recordMessage = "Recording isn't available with this camera setup.") }; return }
        val base = c.videos.newBaseName()
        videoCapture.targetRotation = targetRotation
        try {
            var pending = videoCapture.output.prepareRecording(app, c.videos.recordingOutput(base))
            if (s.micGranted) pending = pending.withAudioEnabled()
            _state.update {
                it.copy(
                    rec = RecState.STARTING, recordedMs = 0, savedName = null,
                    recordMessage = if (s.micGranted) null else "Recording without sound — microphone permission is off."
                )
            }
            recording = pending.start(ContextCompat.getMainExecutor(app)) { e -> onRecordEvent(e, base) }
        } catch (e: Exception) {
            Log.w(logTag, "Recording failed to start: ${e.javaClass.simpleName}")
            recording = null
            _state.update { it.copy(rec = RecState.IDLE, recordMessage = "Couldn't start recording: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun pause() { if (_state.value.rec == RecState.RECORDING) recording?.pause() }

    fun resume() { if (_state.value.rec == RecState.PAUSED) recording?.resume() }

    fun stop() {
        val r = recording ?: return
        _state.update { it.copy(rec = RecState.STOPPING) }
        r.stop()
    }

    private fun onRecordEvent(e: VideoRecordEvent, base: String) {
        when (e) {
            is VideoRecordEvent.Start -> { listener.setRecording(true); _state.update { it.copy(rec = RecState.RECORDING) }; refreshListening() }
            is VideoRecordEvent.Pause -> _state.update { it.copy(rec = RecState.PAUSED) }
            is VideoRecordEvent.Resume -> _state.update { it.copy(rec = RecState.RECORDING) }
            is VideoRecordEvent.Status -> {
                val ms = e.recordingStats.recordedDurationNanos / 1_000_000
                if (_state.value.rec != RecState.STOPPING) _state.update { it.copy(recordedMs = ms) }
            }
            is VideoRecordEvent.Finalize -> {
                runCatching { recording?.close() }
                recording = null
                val uri = e.outputResults.outputUri
                val kept = uri != Uri.EMPTY && e.error in KEEPS_FILE
                val name = if (kept) c.videos.displayName(uri) ?: "$base.mp4" else null
                val msg = when {
                    name != null && e.error == VideoRecordEvent.Finalize.ERROR_NONE -> "Saved $name to Movies/NETHRA."
                    name != null -> "Recording stopped early. What was recorded is saved as $name."
                    e.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "Nothing was recorded — it stopped before any video arrived."
                    else -> "Recording failed (${e.cause?.message ?: "error ${e.error}"})."
                }
                listener.setRecording(false)
                _state.update { it.copy(rec = RecState.IDLE, savedName = name, recordMessage = msg) }
                val recordedMs = e.recordingStats.recordedDurationNanos / 1_000_000
                // Captioned copy (when Auto captions is on), made in the background.
                if (name != null && recordedMs > 0) c.postProcessor.afterRecording(uri, base, emptyList(), recordedMs)
                refreshListening()
                if (name != null) reply("Saved.")
                pendingExit?.let { pendingExit = null; it() }
            }
        }
    }

    private var pendingExit: (() -> Unit)? = null

    /**
     * Leaves safely: cancels a countdown and, if a take is running, stops it and
     * calls [then] once the file is saved. @return true if [then] ran immediately.
     */
    fun exitThen(then: () -> Unit): Boolean {
        cancelCountdown()
        if (!_state.value.isRecordingActive || recording == null) {
            if (recording == null && _state.value.isRecordingActive) _state.update { it.copy(rec = RecState.IDLE) }
            then(); return true
        }
        pendingExit = then
        stop()
        return false
    }

    companion object {
        private const val COMMAND_DEDUPE_MS = 3000L
        private val KEEPS_FILE = setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        )
    }
}
