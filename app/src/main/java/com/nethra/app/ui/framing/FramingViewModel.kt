package com.nethra.app.ui.framing

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethra.app.AppContainer
import com.nethra.app.framing.CoachState
import com.nethra.app.framing.Detection
import com.nethra.app.framing.FramingCoach
import com.nethra.app.framing.FramingEvaluator
import com.nethra.app.framing.FramingReport
import com.nethra.app.framing.FramingTolerance
import com.nethra.app.framing.NormRect
import com.nethra.app.framing.PersonDetector
import com.nethra.app.framing.uprightFor
import com.nethra.app.speech.Speaker
import com.nethra.app.ui.camera.HandsFreeRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Strictness(val label: String, val tolerance: FramingTolerance) {
    RELAXED("Relaxed", FramingTolerance.RELAXED),
    STANDARD("Standard", FramingTolerance.STANDARD),
    PRECISE("Precise", FramingTolerance.PRECISE)
}

data class FramingUi(
    val target: NormRect = NormRect.DEFAULT,
    val coach: CoachState = CoachState(),
    /** Latest detected person, in screen coordinates (for drawing). */
    val subject: NormRect? = null,
    val confidence: Float = 0f,
    val faceFound: Boolean = false,
    val voiceOn: Boolean = true,
    val lastSpoken: String? = null,
    val cameraError: String? = null,
    val detectorRunning: Boolean = false,
    // --- precision / evaluation
    val strictness: Strictness = Strictness.STANDARD,
    val showHud: Boolean = false,
    val fps: Float = 0f,
    val report: FramingReport? = null,
    /**
     * Once recording starts, stop all position prompts (spoken and on screen) and pause
     * detection until the take ends. On by default: the shot is set, don't nag mid-take.
     */
    val lockWhileRecording: Boolean = true,
    val settingsOpen: Boolean = false
)

/**
 * Framing coach on the rear camera, with hands-free recording ([recorder]).
 *
 * Precision: every frame's [com.nethra.app.framing.FramingMetrics] is available
 * in [FramingUi.coach], a running [FramingEvaluator] report is kept for the HUD,
 * and a one-line summary is logged each second under the tag [LOG_TAG] so a real
 * session can be evaluated with `adb logcat -s NethraFraming`.
 */
class FramingViewModel(app: Application, c: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(FramingUi())
    val ui: StateFlow<FramingUi> = _ui

    /** Single thread: ML Kit runs here, never on the main thread. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "nethra-framing") }

    private val speaker = Speaker(app)
    val recorder = HandsFreeRecorder(app, c, viewModelScope, speaker, LOG_TAG)
    private val coach = FramingCoach()
    private val evaluator = FramingEvaluator()
    private val lock = Any()
    @Volatile private var deviceDegrees = 0
    @Volatile private var active = false
    private var lastLogMs = 0L
    private val frameTimes = ArrayDeque<Long>()

    val detector = PersonDetector(::onDetection)

    init {
        // Lock framing for the take: no prompts, no detection, until recording ends.
        viewModelScope.launch {
            recorder.state.map { it.isRecordingActive }.distinctUntilChanged().collect { recording ->
                detector.paused = recording && _ui.value.lockWhileRecording
                if (recording) speaker.stop()
                if (!recording) synchronized(lock) { coach.reset() }
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun setActive(on: Boolean) {
        active = on
        if (!on) speaker.stop()
        _ui.update { it.copy(detectorRunning = on) }
    }

    fun setDeviceDegrees(deg: Int) {
        if (deg == deviceDegrees) return
        deviceDegrees = deg
        synchronized(lock) { coach.reset() }
    }

    fun setTarget(rect: NormRect) = _ui.update { it.copy(target = rect) }

    fun resetBox() {
        synchronized(lock) { coach.reset(); evaluator.reset() }
        _ui.update { it.copy(target = NormRect.DEFAULT, coach = CoachState(), lastSpoken = null, report = null) }
    }

    fun toggleVoice() {
        val on = !_ui.value.voiceOn
        if (!on) speaker.stop()
        recorder.setVoiceReplies(on)
        _ui.update { it.copy(voiceOn = on) }
    }

    fun toggleSettings() = _ui.update { it.copy(settingsOpen = !it.settingsOpen) }

    fun setStrictness(s: Strictness) {
        synchronized(lock) { coach.tolerance = s.tolerance; coach.reset(); evaluator.reset() }
        _ui.update { it.copy(strictness = s, report = null) }
    }

    fun setShowHud(on: Boolean) = _ui.update { it.copy(showHud = on) }

    fun setLockWhileRecording(on: Boolean) {
        _ui.update { it.copy(lockWhileRecording = on) }
        detector.paused = on && recorder.state.value.isRecordingActive
    }

    fun resetStats() {
        synchronized(lock) { evaluator.reset() }
        _ui.update { it.copy(report = null) }
    }

    /** Text for "Copy report": the evaluation summary plus the current frame's numbers. */
    fun reportText(): String {
        val s = _ui.value
        val r = synchronized(lock) { evaluator.report() }
        return buildString {
            appendLine("NETHRA framing — strictness ${s.strictness.label}, target ${s.target}")
            s.coach.metrics?.let { appendLine("Now: " + it.toLogLine(s.coach.guidance)) }
            append(r.toString())
        }
    }

    fun onCameraError(message: String?) = _ui.update { it.copy(cameraError = message) }

    /** Called on the analysis thread for every analysed frame. */
    private fun onDetection(d: Detection) {
        if (!active) return
        val deg = deviceDegrees
        val target = _ui.value.target
        val now = SystemClock.elapsedRealtime()
        val (state, say, report) = synchronized(lock) {
            // Judge in the scene's upright frame so directions stay right when the phone is sideways.
            val upright = d.copy(subject = d.subject?.uprightFor(deg))
            val say = coach.update(upright, target.uprightFor(deg), now)
            evaluator.observe(coach.state, say, now)
            Triple(coach.state, say, if (now - lastLogMs >= 1000) evaluator.report() else null)
        }
        frameTimes.addLast(now)
        while (frameTimes.size > 1 && now - frameTimes.first() > 2000) frameTimes.removeFirst()
        val fps = if (frameTimes.size > 1) (frameTimes.size - 1) * 1000f / (now - frameTimes.first()).coerceAtLeast(1) else 0f

        if (report != null) {
            lastLogMs = now
            state.metrics?.let { Log.i(LOG_TAG, it.toLogLine(state.guidance) + " fps=%.1f".format(fps)) }
                ?: Log.i(LOG_TAG, "g=${state.guidance.name} fps=%.1f".format(fps))
        }
        _ui.update {
            it.copy(
                coach = state, subject = d.subject, confidence = d.confidence, faceFound = d.faceFound,
                lastSpoken = say ?: it.lastSpoken, fps = fps, report = report ?: it.report
            )
        }
        val s = _ui.value
        val r = recorder.state.value
        // Never speak position prompts during a take (they'd be in the video's audio).
        val quietForTake = r.isRecordingActive
        if (say != null && s.voiceOn && !quietForTake && r.countdown == null && !speaker.isUnavailable) {
            speaker.say(say, Speaker.Priority.NORMAL)
        }
    }

    val ttsUnavailable: Boolean get() = speaker.isUnavailable

    override fun onCleared() {
        active = false
        recorder.release()
        analysisExecutor.shutdown()
        detector.close()
        speaker.shutdown()
    }

    companion object {
        const val LOG_TAG = "NethraFraming"
    }
}
