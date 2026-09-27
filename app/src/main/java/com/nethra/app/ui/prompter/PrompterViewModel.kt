package com.nethra.app.ui.prompter

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethra.app.AppContainer
import com.nethra.app.ai.BriefParser
import com.nethra.app.ai.GeneratedScript
import com.nethra.app.ai.ScriptBrief
import com.nethra.app.ai.ScriptText
import com.nethra.app.core.userMessage
import com.nethra.app.captions.CaptionStatus
import com.nethra.app.media.CleanStatus
import com.nethra.app.media.CommandMark
import com.nethra.app.media.RecordingClock
import com.nethra.app.persist.DraftStore
import com.nethra.app.speech.ChimeMuter
import com.nethra.app.speech.HeardTiming
import com.nethra.app.speech.Speaker
import com.nethra.app.speech.VoiceClip
import com.nethra.app.config.ApiKeyProvider
import com.nethra.app.config.CaptionScript
import com.nethra.app.config.PrompterScript
import com.nethra.app.config.ScriptLanguage
import com.nethra.app.core.Text
import com.nethra.app.core.Translit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.nethra.app.speech.SpeechCallbacks
import com.nethra.app.speech.SpeechListener
import com.nethra.app.speech.Utterance
import com.nethra.app.speech.VoiceCommand
import com.nethra.app.speech.WakeWord
import com.nethra.app.teleprompter.PaceFollower
import com.nethra.app.teleprompter.ScriptTracker
import com.nethra.app.teleprompter.ScrollMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VoiceMode {
    /** Not listening (no mic permission, app in background, or paused by the recording mode). */
    OFF,
    /** Listening for "Nethra …". */
    LISTENING,
    /** Heard "Nethra" alone; the next utterance is a brief or a command. */
    AWAITING
}

enum class RecState { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

enum class DictationPhase { LISTENING, TRANSCRIBING }

/** Where the floating script panel sits, as fractions (0..1) of the camera preview. */
data class PanelRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun clamp() = PanelRect(
        left.coerceIn(0f, 1f - width.coerceIn(MIN, 1f)), top.coerceIn(0f, 1f - height.coerceIn(MIN, 1f)),
        width.coerceIn(MIN, 1f), height.coerceIn(MIN, 1f)
    )
    fun encode() = "$left,$top,$width,$height"

    companion object {
        const val MIN = 0.18f
        val DEFAULT_PORTRAIT = PanelRect(0.03f, 0.02f, 0.94f, 0.36f)
        /** Landscape default: a band along the edge that is physically on top, full length. */
        fun defaultLandscape(degrees: Int) =
            if (degrees == 270) PanelRect(0.58f, 0.02f, 0.40f, 0.96f) else PanelRect(0.02f, 0.02f, 0.40f, 0.96f)
        fun decode(s: String?): PanelRect? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 }?.let { PanelRect(it[0], it[1], it[2], it[3]).clamp() }
    }
}

data class PrompterUi(
    val micGranted: Boolean = false,
    val voice: VoiceMode = VoiceMode.OFF,
    val heard: String = "",
    val voiceProblem: String? = null,
    val brief: ScriptBrief = ScriptBrief(),
    val briefOpen: Boolean = false,
    val generating: Boolean = false,
    val scriptError: String? = null,
    val script: GeneratedScript? = null,
    val lines: List<String> = emptyList(),
    val currentLine: Int = 0,
    val rec: RecState = RecState.IDLE,
    val recordedMs: Long = 0,
    val spokenCommands: Int = 0,
    val savedName: String? = null,
    val recordMessage: String? = null,
    val cameraError: String? = null,
    /** Which lens the teleprompter uses: front (selfie, default) or rear. */
    val front: Boolean = true,
    val voiceWhileRecording: Boolean = true,
    val silenceChimes: Boolean = true,
    /** ADAPTIVE follows the speaker's pace; FIXED_WPM scrolls at [wpm] while recording. */
    val scrollMode: ScrollMode = ScrollMode.ADAPTIVE,
    /** Fixed speed, and the starting guess for the adaptive pace. */
    val wpm: Int = PaceFollower.DEFAULT_WPM.toInt(),
    /** The speaker's measured pace (adaptive mode). */
    val liveWpm: Int = PaceFollower.DEFAULT_WPM.toInt(),
    /** NETHRA answers voice commands out loud ("Paused.", "Recording in three…"). */
    val voiceReplies: Boolean = true,
    /** 3-2-1 before a voice-started recording. */
    val countdown: Int? = null,
    /** While dictating a script brief: everything heard so far (null when not dictating). */
    val briefCapture: String? = null,
    /** Cloud dictation after "Nethra": listening / transcribing (null when idle). */
    val dictation: DictationPhase? = null,
    /** Microphone level 0..1 while dictating, for the meter. */
    val dictationLevel: Float = 0f,
    /** Script text size (sp). */
    val textSize: Int = 24,
    /** Stream NETHRA's own mic audio to the recogniser (works during recording on Android 13+). */
    val useOwnMic: Boolean = true,
    /** Which listening path is active, for the settings screen. */
    val voiceEngine: String = "",
    /** The language the creator speaks; picks the recogniser, script, pace and captions. */
    val language: ScriptLanguage = ScriptLanguage.ENGLISH,
    /** Floating script panel placement (fractions of the preview) for portrait and landscape. */
    val panelPortrait: PanelRect = PanelRect.DEFAULT_PORTRAIT,
    val panelLandscape: PanelRect? = null,
    val settingsOpen: Boolean = false,
    /** Recording with voice on, but the recogniser has heard nothing for a while. */
    val deafWhileRecording: Boolean = false,
    val exitRequested: Boolean = false
) {
    val isRecordingActive get() = rec != RecState.IDLE
}

/**
 * Teleprompter state machine: wake word → brief → cloud script → recording
 * with spoken pause/resume/stop → optional clean export of the spoken commands.
 *
 * All speech and CameraX callbacks arrive on the main thread; the only heavy
 * work (script generation, clean export) runs in coroutines off the main thread.
 */
class PrompterViewModel(private val app: Application, private val c: AppContainer) : ViewModel(), SpeechCallbacks {

    private val _ui = MutableStateFlow(PrompterUi())
    val ui: StateFlow<PrompterUi> = _ui
    val cleanStatus: StateFlow<CleanStatus> = c.postProcessor.status
    val captionStatus: StateFlow<CaptionStatus> = c.postProcessor.captionStatus
    val autoCaptions: StateFlow<Boolean> = c.prefs.autoCaptions
    fun setAutoCaptions(on: Boolean) = c.prefs.setAutoCaptions(on)

    private val listener = SpeechListener(app, this)
    private val muter = ChimeMuter(app)
    private val speaker = Speaker(app)
    private var tracker: ScriptTracker? = null
    private val prefs = app.getSharedPreferences("nethra_prompter", android.content.Context.MODE_PRIVATE)

    /** Smooth, pace-following scroll position (in script words). Driven by the screen every frame. */
    val pace = PaceFollower()
    private var countdownJob: Job? = null
    private var lastCmd: VoiceCommand? = null
    private var lastCmdAt = 0L

    private var recorderUsesMic = true
    /** Rebuilt when the audio source changes; the screen rebinds when [captureVersion] changes. */
    var videoCapture: VideoCapture<Recorder> = buildCapture(true)
        private set
    private val _captureVersion = MutableStateFlow(0)
    val captureVersion: StateFlow<Int> = _captureVersion

    private var recording: Recording? = null
    private var recordingBase: String? = null
    private var clock = RecordingClock()
    private val marks = mutableListOf<CommandMark>()
    private var foreground = false
    private var awaitJob: Job? = null
    private var tickerJob: Job? = null
    private var saveJob: Job? = null
    private var lastHeardAt = 0L

    init {
        val savedMode = runCatching { ScrollMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(ScrollMode.ADAPTIVE)
        val savedWpm = prefs.getInt(KEY_WPM, PaceFollower.DEFAULT_WPM.toInt())
        val replies = prefs.getBoolean(KEY_REPLIES, true)
        pace.mode = savedMode
        pace.targetWpm = savedWpm.toFloat()
        val ownMic = prefs.getBoolean(KEY_OWN_MIC, true)
        listener.setUseOwnMic(ownMic)
        val lang = c.prefs.language.value
        listener.setLanguage(lang.localeTag, lang.syllablesPerWord)
        _ui.update {
            it.copy(
                scrollMode = savedMode, wpm = pace.targetWpm.toInt(), liveWpm = pace.targetWpm.toInt(), voiceReplies = replies,
                language = lang,
                textSize = prefs.getInt(KEY_TEXT, 24), useOwnMic = ownMic, voiceEngine = listener.modeLabel,
                panelPortrait = PanelRect.decode(prefs.getString(KEY_PANEL_P, null)) ?: PanelRect.DEFAULT_PORTRAIT,
                panelLandscape = PanelRect.decode(prefs.getString(KEY_PANEL_L, null))
            )
        }
        viewModelScope.launch {
            c.drafts.loadPrompter()?.let { d ->
                if (_ui.value.script != null || !_ui.value.brief.isEmpty) return@let
                _ui.update { it.copy(brief = d.brief) }
                d.script?.let { applyScript(it, d.scrollLine) }
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun setMicGranted(granted: Boolean) {
        if (_ui.value.micGranted == granted) return
        _ui.update { it.copy(micGranted = granted) }
        refreshListening()
    }

    fun onForeground() {
        foreground = true
        refreshListening()
    }

    fun onBackground() {
        foreground = false
        cancelCountdown()
        speaker.stop()
        refreshListening()
        // CameraX stops the camera with the lifecycle; the recording finalises as
        // "source inactive" and whatever was captured is kept.
    }

    fun onCameraError(message: String?) = _ui.update { it.copy(cameraError = message) }

    private fun shouldListen(): Boolean {
        val s = _ui.value
        if (!foreground || !s.micGranted) return false
        // Cloud dictation holds the mic itself.
        if (s.dictation != null) return false
        if (s.isRecordingActive && !s.voiceWhileRecording) return false
        // Older phones: the recogniser uses its own mic, which Android refuses during our recording.
        if (s.isRecordingActive && !listener.canListenWhileRecording) return false
        return true
    }

    private fun refreshListening() {
        if (shouldListen()) {
            if (_ui.value.silenceChimes) muter.mute()
            if (!listener.isRunning) listener.start()
        } else {
            if (listener.isRunning) listener.stop()
            muter.restore()
            _ui.update { it.copy(voice = VoiceMode.OFF) }
        }
    }

    // ------------------------------------------------------------ speech callbacks

    override fun onListening(active: Boolean) {
        _ui.update {
            val mode = when {
                !listener.isRunning -> VoiceMode.OFF
                it.voice == VoiceMode.AWAITING -> VoiceMode.AWAITING
                else -> VoiceMode.LISTENING
            }
            it.copy(voice = mode, voiceProblem = if (listener.isRunning) null else it.voiceProblem)
        }
    }

    override fun onProblem(message: String, fatal: Boolean) {
        _ui.update { it.copy(voiceProblem = message, voice = if (fatal) VoiceMode.OFF else it.voice) }
    }

    override fun onPartial(text: String) {
        markHeard()
        pace.onVoiceActivity(SystemClock.elapsedRealtime())
        _ui.update { it.copy(heard = text) }
        if (capturing) {
            // Still talking: don't finish the brief yet; show the words live.
            captureJob?.cancel()
            if (WakeWord.fastCommand(text) == VoiceCommand.STOP) { cancelCapture(); return }
            _ui.update { it.copy(briefCapture = (capture.toString() + " " + stripWake(text)).trim()) }
            return
        }
        // "Nethra, write a script…" is starting: wait for longer pauses from now on so the
        // recogniser doesn't cut the brief at the first breath.
        if (!_ui.value.isRecordingActive && WakeWord.parse(text) is Utterance.ScriptRequest) listener.setLongPause(true)
        // "Nethra stop / pause / resume" acts on the partial result, about a second sooner,
        // so less of the command ends up in the take.
        WakeWord.fastCommand(text)?.let { cmd -> endAwait(); onCommand(cmd, listener.partialTiming()); return }
        val tokens = Text.tokens(text)
        if (WakeWord.findWake(tokens) == null) follow(text)
    }

    override fun onSpeechActivity(nowMs: Long, wpm: Float?) {
        markHeard()
        // Only a take moves the script: before recording it stays put, whatever is said.
        if (_ui.value.rec != RecState.RECORDING) return
        // NETHRA's own voice-activity detector keeps the script gliding between (or without)
        // recogniser results, at the pace measured from the voice's own rhythm.
        pace.onVoiceActivity(nowMs)
        wpm?.let { pace.onVoicePace(it, nowMs) }
    }

    override fun onLevel(rmsDb: Float) {
        if (_ui.value.rec != RecState.RECORDING) return
        // Classic mode: the recogniser's level meter tells us the creator is talking.
        if (rmsDb > VOICE_RMS_DB) pace.onVoiceActivity(SystemClock.elapsedRealtime())
    }

    override fun onFinal(text: String, timing: HeardTiming) {
        markHeard()
        _ui.update { it.copy(heard = text) }
        val s = _ui.value
        val u = WakeWord.parse(text)
        if (capturing) { continueCapture(text, u, timing); return }
        // "stop recording", "pause recording"… work even when "Nethra" itself was misheard.
        if (u is Utterance.Plain) WakeWord.bareCommand(text)?.let { endAwait(); onCommand(it, timing); return }
        val awaiting = s.voice == VoiceMode.AWAITING
        when {
            u is Utterance.Command -> { endAwait(); onCommand(u.command, timing) }
            u is Utterance.ScriptRequest -> {
                endAwait()
                when {
                    s.isRecordingActive -> Unit
                    // "Nethra, write a script" with no topic yet: ask, and take the brief by cloud dictation.
                    topicWords(u.brief) < 2 && cloudDictationReady() -> startDictation("What's it about?")
                    else -> startCapture(u.brief)
                }
            }
            u is Utterance.Wake && u.rest.isBlank() -> if (cloudDictationReady()) startDictation("Yes?") else beginAwait()
            u is Utterance.Wake -> {
                endAwait()
                // "Nethra, a 30 second reel about …" — anything substantial after the name is a brief.
                if (!s.isRecordingActive && ScriptText.countWords(u.rest) >= 3) startCapture(u.rest)
            }
            u is Utterance.Plain && awaiting -> {
                endAwait()
                val cmd = WakeWord.commandIn(Text.tokens(text))
                when {
                    cmd != null -> onCommand(cmd, timing)
                    !s.isRecordingActive -> startCapture(text)
                    else -> follow(text)
                }
            }
            else -> follow(text)
        }
    }

    // ------------------------------------------------------------ cloud dictation
    //
    // After a bare "Nethra", NETHRA answers "Yes?", records what follows with its own mic
    // (ending at the pause), and transcribes it with the cloud model — much clearer than
    // the phone recogniser for briefs. The result is run as a command or used as the brief.

    private var dictationJob: Job? = null
    private var clip: VoiceClip? = null

    private fun cloudDictationReady(): Boolean {
        val s = _ui.value
        return s.micGranted && !s.isRecordingActive && s.dictation == null &&
            ApiKeyProvider.hasOpenRouterKey && c.network.isOnlineNow()
    }

    private fun startDictation(prompt: String) {
        endAwait()
        dictationJob?.cancel()
        _ui.update { it.copy(dictation = DictationPhase.LISTENING, dictationLevel = 0f, briefOpen = false, settingsOpen = false, scriptError = null) }
        refreshListening()   // stops the recogniser: the clip needs the mic
        dictationJob = viewModelScope.launch {
            try {
                // Speak the prompt first, so NETHRA's own voice isn't in the clip.
                suspendCancellableCoroutine<Unit> { cont -> reply(prompt) { if (cont.isActive) cont.resume(Unit) } }
                val vc = VoiceClip().also { clip = it }
                val pcm = vc.record(onLevel = { lvl -> _ui.update { it.copy(dictationLevel = lvl) } })
                if (pcm == null) {
                    _ui.update { it.copy(recordMessage = "I didn't hear anything. Say “Nethra” and try again.") }
                    return@launch
                }
                _ui.update { it.copy(dictation = DictationPhase.TRANSCRIBING) }
                val text = c.dictation.transcribe(pcm)
                _ui.update { it.copy(heard = text) }
                handleDictated(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(briefOpen = true, scriptError = e.userMessage() + " You can also type the brief here.") }
            } finally {
                clip = null
                _ui.update { it.copy(dictation = null, dictationLevel = 0f) }
                refreshListening()
            }
        }
    }

    /** ✓ on the dictation caption: stop listening now and transcribe. */
    fun finishDictationNow() { clip?.finishNow() }

    /** ✕ on the dictation caption. */
    fun cancelDictation() { dictationJob?.cancel() }

    private fun handleDictated(text: String) {
        val tokens = Text.tokens(text)
        val wake = WakeWord.findWake(tokens)
        val rest = if (wake != null && wake.first == 0) tokens.drop(wake.last + 1) else tokens
        val cmd = WakeWord.commandIn(rest)?.takeIf { rest.size <= 4 } ?: WakeWord.bareCommand(text)
        when {
            cmd != null -> onCommand(cmd, HeardTiming(null, null, null, SystemClock.elapsedRealtime()))
            ScriptText.countWords(text) >= 2 -> spokenBrief(stripWake(text))
            else -> _ui.update { it.copy(briefOpen = true, scriptError = "I only caught “$text”. Say “Nethra” and try again, or type the brief here.") }
        }
    }

    /** Words in a spoken script request that describe the topic (not "write a script about"). */
    private fun topicWords(brief: String): Int =
        Text.tokens(brief).count { it !in REQUEST_WORDS }

    // ------------------------------------------------------------ brief dictation
    //
    // Recognisers end an utterance at the first short pause, so a brief spoken naturally
    // ("Nethra, write a script about… um… for students, one minute") arrived in pieces and
    // only the first piece was used. Dictation keeps listening across pauses and joins the
    // pieces; it ends after CAPTURE_GAP_MS of silence or when the creator says "done".

    private var capturing = false
    private val capture = StringBuilder()
    private var captureJob: Job? = null

    private fun startCapture(first: String) {
        capturing = true
        capture.clear().append(first.trim())
        listener.setLongPause(true)
        _ui.update { it.copy(briefCapture = capture.toString(), briefOpen = false, settingsOpen = false) }
        scheduleCaptureEnd()
    }

    private fun continueCapture(text: String, u: Utterance, timing: HeardTiming) {
        when {
            u is Utterance.Command -> { cancelCapture(); onCommand(u.command, timing); return }
            isDoneWord(text) -> { finishCapture(); return }
            u is Utterance.ScriptRequest -> capture.clear().append(u.brief)   // started over
            else -> { if (capture.isNotEmpty()) capture.append(' '); capture.append(stripWake(text)) }
        }
        _ui.update { it.copy(briefCapture = capture.toString().trim()) }
        scheduleCaptureEnd()
    }

    private fun scheduleCaptureEnd() {
        captureJob?.cancel()
        captureJob = viewModelScope.launch { delay(CAPTURE_GAP_MS); finishCapture() }
    }

    private fun finishCapture() {
        if (!capturing) return
        capturing = false
        captureJob?.cancel()
        listener.setLongPause(false)
        val text = capture.toString().trim().removeSuffix(".").trim()
        _ui.update { it.copy(briefCapture = null) }
        if (ScriptText.countWords(text) >= 2) spokenBrief(text)
        else _ui.update { it.copy(briefOpen = true, scriptError = "I only caught “$text”. Say the brief again, or type it here.") }
    }

    private fun cancelCapture() {
        capturing = false
        captureJob?.cancel()
        listener.setLongPause(false)
        _ui.update { it.copy(briefCapture = null) }
    }

    /** Finishes the dictated brief now (the ✓ on the caption). */
    fun finishBriefNow() = finishCapture()

    private fun isDoneWord(text: String): Boolean {
        val t = Text.tokens(text).filterNot { WakeWord.isWakeToken(it) }.joinToString(" ")
        return t in DONE_PHRASES
    }

    private fun stripWake(text: String): String {
        val tokens = Text.tokens(text)
        val wake = WakeWord.findWake(tokens) ?: return text.trim()
        return if (wake.first == 0) text.trim().split(Regex("""\s+""")).drop(wake.last + 1).joinToString(" ").trimStart(',', ' ') else text.trim()
    }

    private fun markHeard() {
        lastHeardAt = SystemClock.elapsedRealtime()
        if (_ui.value.deafWhileRecording) _ui.update { it.copy(deafWhileRecording = false) }
    }

    private fun beginAwait() {
        listener.setLongPause(true)
        _ui.update { it.copy(voice = VoiceMode.AWAITING) }
        awaitJob?.cancel()
        awaitJob = viewModelScope.launch {
            delay(AWAIT_TIMEOUT_MS)
            endAwait()
        }
    }

    private fun endAwait() {
        awaitJob?.cancel(); awaitJob = null
        listener.setLongPause(false)
        if (_ui.value.voice == VoiceMode.AWAITING) {
            _ui.update { it.copy(voice = if (listener.isRunning) VoiceMode.LISTENING else VoiceMode.OFF) }
        }
    }

    /** Teleprompter follows the creator's speech; [pace] turns the jumps into a smooth glide. */
    private fun follow(text: String) {
        val t = tracker ?: return
        if (_ui.value.scrollMode == ScrollMode.FIXED_WPM) return
        // Rehearsing or chatting before the take doesn't move the script.
        if (_ui.value.rec != RecState.RECORDING) return
        if (t.onHeard(text)) pace.onRecognised(t.position, SystemClock.elapsedRealtime())
    }

    /**
     * Called by the screen once per frame. Advances the smooth scroll and returns the
     * position in script words (fractional). Also keeps [PrompterUi.currentLine] and the
     * live WPM readout up to date (only when they change, to avoid recomposing every frame).
     */
    fun tickScroll(nowMs: Long): Float {
        val t = tracker ?: return 0f
        val s = _ui.value
        // Both modes scroll only during a take (not before recording, not while paused).
        pace.paused = s.rec != RecState.RECORDING
        val pos = pace.tick(nowMs)
        if (s.scrollMode == ScrollMode.FIXED_WPM) t.setPosition(pos.toInt())
        val line = t.lineAt(pos).first
        val live = pace.effectiveWpm.toInt()
        if (line != s.currentLine || kotlin.math.abs(live - s.liveWpm) >= 3) {
            _ui.update { it.copy(currentLine = line, liveWpm = live) }
        }
        return pos
    }

    /** Line index and 0..1 progress through it for a word position (for pixel scrolling). */
    fun lineAt(pos: Float): Pair<Int, Float> = tracker?.lineAt(pos) ?: (0 to 0f)

    // ------------------------------------------------------------ commands

    private fun onCommand(cmd: VoiceCommand, timing: HeardTiming) {
        // A partial and its final result usually carry the same command: act once.
        val now = SystemClock.elapsedRealtime()
        if (cmd == lastCmd && now - lastCmdAt < COMMAND_DEDUPE_MS) return
        lastCmd = cmd; lastCmdAt = now
        val state = _ui.value.rec
        when (cmd) {
            VoiceCommand.START_RECORDING -> if (state == RecState.IDLE && _ui.value.countdown == null) startWithCountdown()
            // Say it first, then resume, so NETHRA's voice isn't in the take.
            VoiceCommand.RESUME -> if (state == RecState.PAUSED) reply("Resuming.") { resume() }
            VoiceCommand.PAUSE -> if (state == RecState.RECORDING) {
                markSpokenCommand(timing, "pause"); pause(); reply("Paused.")
            }
            VoiceCommand.STOP -> when {
                _ui.value.countdown != null -> { cancelCountdown(); reply("Cancelled.") }
                state == RecState.RECORDING -> { markSpokenCommand(timing, "stop"); stop() }
                // Spoken while paused: not in the video, nothing to cut.
                state == RecState.PAUSED || state == RecState.STARTING -> stop()
                else -> Unit
            }
        }
    }

    /**
     * Speaks a short reply. The chime muter silences media volume while listening,
     * which would also silence NETHRA, so it is lifted just for the reply.
     */
    private fun reply(text: String, then: (() -> Unit)? = null) {
        if (!_ui.value.voiceReplies || speaker.isUnavailable) { then?.invoke(); return }
        muter.restore()
        speaker.say(text, Speaker.Priority.URGENT) {
            if (_ui.value.silenceChimes && listener.isRunning) muter.mute()
            then?.invoke()
        }
    }

    /** Voice start: "Recording in three, two, one" so the creator can settle, then record. */
    fun startWithCountdown() {
        if (_ui.value.rec != RecState.IDLE) return
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _ui.update { it.copy(briefOpen = false, settingsOpen = false) }
            for (n in 3 downTo 1) {
                _ui.update { it.copy(countdown = n) }
                reply(when (n) { 3 -> "Recording in three."; 2 -> "Two."; else -> "One." })
                delay(if (n == 3) 1300 else 1000)
            }
            speaker.stop()
            _ui.update { it.copy(countdown = null) }
            startRecording(lastRotation)
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel(); countdownJob = null
        if (_ui.value.countdown != null) _ui.update { it.copy(countdown = null) }
    }

    /** Shutter button: starts at once (like iOS); during a countdown it cancels; while recording it stops. */
    fun onShutter(surfaceRotation: Int) {
        val s = _ui.value
        when {
            s.countdown != null -> cancelCountdown()
            s.rec == RecState.IDLE -> startRecording(surfaceRotation)
            else -> stop()
        }
    }

    /**
     * Records where "Nethra pause/stop" sits on the recording timeline. Only these two
     * are ever inside the video ("start" and "resume" are said while not recording).
     * The start is a deliberately early estimate; the planner refines it to the
     * silence before the phrase using the recorded audio.
     */
    private fun markSpokenCommand(t: HeardTiming, label: String) {
        val now = SystemClock.elapsedRealtime()
        val end = clock.positionAt(now)
        val estimates = listOfNotNull(t.wakeSeenMs?.minus(WAKE_LAG_MS), t.speechEndMs?.minus(COMMAND_SPEECH_MS))
        var roughWall = estimates.minOrNull() ?: (t.finalMs - FALLBACK_COMMAND_MS)
        t.speechStartMs?.let { roughWall = maxOf(roughWall, it - SPEECH_START_SLACK_MS) }
        val start = clock.positionAt(roughWall).coerceAtMost(end)
        marks += CommandMark(start, end, label)
        _ui.update { it.copy(spokenCommands = marks.size) }
    }

    // ------------------------------------------------------------ recording

    /** Surface rotation of the phone, updated by the screen, used as the video's orientation. */
    var lastRotation: Int = 0

    private fun buildCapture(useMic: Boolean): VideoCapture<Recorder> {
        recorderUsesMic = useMic
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            // MIC is not a "privacy-sensitive" source, which gives the speech recogniser a
            // chance to keep hearing while CameraX records. CAMCORDER (the default) can
            // silence other capture on many phones. See SETUP_GUIDE.md §9.
            .setAudioSource(if (useMic) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.CAMCORDER)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(surfaceRotation: Int) {
        val s = _ui.value
        if (s.rec != RecState.IDLE) return
        if (s.cameraError != null) { _ui.update { it.copy(recordMessage = "Can't record: ${s.cameraError}") }; return }
        c.postProcessor.clear()
        val base = c.videos.newBaseName()
        marks.clear()
        clock = RecordingClock()
        recordingBase = base
        videoCapture.targetRotation = surfaceRotation
        try {
            var pending = videoCapture.output.prepareRecording(app, c.videos.recordingOutput(base))
            if (s.micGranted) pending = pending.withAudioEnabled()
            _ui.update {
                it.copy(
                    rec = RecState.STARTING, recordedMs = 0, spokenCommands = 0, savedName = null,
                    recordMessage = if (s.micGranted) null else "Recording without sound — microphone permission is off.",
                    briefOpen = false, settingsOpen = false
                )
            }
            recording = pending.start(ContextCompat.getMainExecutor(app)) { e -> onRecordEvent(e, base) }
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed to start: ${e.javaClass.simpleName}")
            recording = null
            _ui.update { it.copy(rec = RecState.IDLE, recordMessage = "Couldn't start recording: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun pause() {
        if (_ui.value.rec != RecState.RECORDING) return
        clock.onPause(SystemClock.elapsedRealtime())
        recording?.pause()
    }

    fun resume() {
        if (_ui.value.rec != RecState.PAUSED) return
        recording?.resume()
    }

    fun stop() {
        val r = recording ?: return
        if (_ui.value.rec == RecState.RECORDING) clock.onStop(SystemClock.elapsedRealtime())
        _ui.update { it.copy(rec = RecState.STOPPING) }
        r.stop()
    }

    private fun onRecordEvent(e: VideoRecordEvent, base: String) {
        val now = SystemClock.elapsedRealtime()
        when (e) {
            is VideoRecordEvent.Start -> {
                clock.onStart(now)
                listener.setRecording(true)
                lastHeardAt = now
                // Start the take's scroll from where the script is now (fresh timing).
                tracker?.let { pace.jumpTo(it.position, now) }
                _ui.update { it.copy(rec = RecState.RECORDING) }
                refreshListening()
                startTicker()
            }
            is VideoRecordEvent.Pause -> _ui.update { it.copy(rec = RecState.PAUSED) }
            is VideoRecordEvent.Resume -> {
                clock.onResume(now)
                lastHeardAt = now
                _ui.update { it.copy(rec = RecState.RECORDING) }
            }
            is VideoRecordEvent.Status -> {
                val ms = e.recordingStats.recordedDurationNanos / 1_000_000
                if (_ui.value.rec != RecState.STOPPING) _ui.update { it.copy(recordedMs = ms) }
            }
            is VideoRecordEvent.Finalize -> onFinalized(e, base)
        }
    }

    private fun onFinalized(e: VideoRecordEvent.Finalize, base: String) {
        listener.setRecording(false)
        clock.onStop(SystemClock.elapsedRealtime())
        tickerJob?.cancel()
        runCatching { recording?.close() }
        recording = null
        val uri = e.outputResults.outputUri
        val recordedMs = e.recordingStats.recordedDurationNanos / 1_000_000
        val err = e.error
        val fileKept = uri != Uri.EMPTY && err in KEEPS_FILE
        val name = if (fileKept) c.videos.displayName(uri) ?: "$base.mp4" else null

        val message = when (err) {
            VideoRecordEvent.Finalize.ERROR_NONE -> "Saved $name to Movies/NETHRA."
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                "Recording stopped because the camera closed (app left or another app took the camera). " +
                    (if (fileKept) "What was recorded is saved as $name." else "Nothing could be saved.")
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Storage is full, so recording stopped. What fit is saved as $name."
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "Recording hit the size/length limit. Saved $name."
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "Nothing was recorded — it was stopped before any video arrived."
            else -> "Recording failed (${e.cause?.message ?: "error $err"}). " +
                (if (uri != Uri.EMPTY) "The file in Movies/NETHRA may be incomplete." else "No file was saved.")
        }
        _ui.update {
            it.copy(rec = RecState.IDLE, recordedMs = recordedMs, savedName = name, recordMessage = message, deafWhileRecording = false)
        }
        refreshListening()

        // Clean copy when a spoken command is inside the file; captioned copy when Auto captions is on.
        if (fileKept && recordedMs > 0) {
            c.postProcessor.afterRecording(uri, base, marks.toList(), recordedMs)
        }
        marks.clear()
        recordingBase = null
        if (_ui.value.exitRequested) {
            _ui.update { it.copy(exitRequested = false) }
            exitNow.value = true
        }
    }

    /** Set when an Exit that waited for the recording to be saved may now leave the screen. */
    val exitNow = MutableStateFlow(false)

    /** @return true if the screen may leave now; false if it must wait for the recording to be saved. */
    fun requestExit(): Boolean {
        cancelCountdown()
        if (!_ui.value.isRecordingActive) return true
        if (recording == null) { _ui.update { it.copy(rec = RecState.IDLE) }; return true }
        _ui.update { it.copy(exitRequested = true) }
        stop()
        return false
    }

    fun consumeExit() { exitNow.value = false }

    /** The "can't hear you while recording" hint. (Scrolling is driven per frame by [tickScroll].) */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val s = _ui.value
                if (s.rec != RecState.RECORDING) continue
                val deaf = s.voiceWhileRecording && s.micGranted && listener.isRunning &&
                    SystemClock.elapsedRealtime() - lastHeardAt > DEAF_HINT_MS
                if (deaf != s.deafWhileRecording) _ui.update { it.copy(deafWhileRecording = deaf) }
            }
        }
    }

    // ------------------------------------------------------------ script

    fun openBrief(open: Boolean) = _ui.update { it.copy(briefOpen = open, settingsOpen = false) }

    fun updateBrief(b: ScriptBrief) {
        _ui.update { it.copy(brief = b) }
        saveDraftSoon()
    }

    private fun spokenBrief(text: String) {
        val brief = BriefParser.parse(text)
        _ui.update { it.copy(brief = brief, briefOpen = true, scriptError = null) }
        saveDraftSoon()
        generate()
    }

    fun generate() {
        val s = _ui.value
        if (s.generating || s.isRecordingActive) return
        if (s.brief.isEmpty) {
            _ui.update { it.copy(briefOpen = true, scriptError = "Add a topic first — or say \"Nethra, write a script about …\".") }
            return
        }
        _ui.update { it.copy(generating = true, scriptError = null) }
        viewModelScope.launch {
            try {
                val script = c.scripts.generate(_ui.value.brief, _ui.value.language, c.prefs.prompterScript.value)
                applyScript(script, 0)
                _ui.update { it.copy(briefOpen = script.isShort || script.possiblyIncomplete) }
                saveDraftSoon()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cloud only by design: no local fallback. The brief stays as typed/spoken.
                _ui.update { it.copy(scriptError = e.userMessage() + " Your brief is kept — try again when ready.", briefOpen = true) }
            } finally {
                _ui.update { it.copy(generating = false) }
            }
        }
    }

    private fun applyScript(script: GeneratedScript, line: Int) {
        // Hindi/Telugu: the recogniser answers in Devanagari/Telugu while a Hinglish/Tinglish script
        // is in English letters, so both sides are matched by sound (Translit.matchKey).
        val key: (String) -> String = if (script.language.hasScriptChoice) Translit::matchKey else com.nethra.app.core.Text::fold
        val t = ScriptTracker(script.body, key)
        if (line > 0) t.jumpToLine(line)
        tracker = t
        pace.setWordCount(t.wordCount)
        pace.reset(SystemClock.elapsedRealtime())
        pace.jumpTo(t.position, SystemClock.elapsedRealtime())
        _ui.update { it.copy(script = script, lines = t.lines, currentLine = t.currentLine) }
    }

    fun jumpToLine(line: Int) {
        val t = tracker ?: return
        t.jumpToLine(line)
        pace.jumpTo(t.position, SystemClock.elapsedRealtime())
        _ui.update { it.copy(currentLine = t.currentLine) }
        saveDraftSoon()
    }

    fun nudge(delta: Int) {
        val t = tracker ?: return
        t.nudgeLines(delta)
        pace.jumpTo(t.position, SystemClock.elapsedRealtime())
        _ui.update { it.copy(currentLine = t.currentLine) }
        saveDraftSoon()
    }

    fun restartScript() = jumpToLine(0)

    private fun saveDraftSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val s = _ui.value
            c.drafts.savePrompter(DraftStore.PrompterDraft(s.brief, s.script, s.currentLine))
        }
    }

    // ------------------------------------------------------------ settings

    fun toggleSettings() = _ui.update { it.copy(settingsOpen = !it.settingsOpen, briefOpen = false, voiceEngine = listener.modeLabel) }

    fun setVoiceWhileRecording(on: Boolean) {
        if (_ui.value.isRecordingActive) return
        _ui.update { it.copy(voiceWhileRecording = on) }
        if (on != recorderUsesMic) {
            videoCapture = buildCapture(on)
            _captureVersion.value++
        }
    }

    fun setSilenceChimes(on: Boolean) {
        _ui.update { it.copy(silenceChimes = on) }
        if (on && listener.isRunning) muter.mute() else muter.restore()
    }

    fun setScrollMode(mode: ScrollMode) {
        pace.mode = mode
        // Resume from where the eye is, whichever mode takes over.
        tracker?.let { t -> t.setPosition(pace.displayPosition.toInt()); pace.jumpTo(t.position, SystemClock.elapsedRealtime()) }
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _ui.update { it.copy(scrollMode = mode, liveWpm = pace.effectiveWpm.toInt()) }
    }

    fun setWpm(wpm: Int) {
        pace.targetWpm = wpm.toFloat()
        prefs.edit().putInt(KEY_WPM, pace.targetWpm.toInt()).apply()
        _ui.update { it.copy(wpm = pace.targetWpm.toInt(), liveWpm = pace.effectiveWpm.toInt()) }
    }

    /** Switch front/rear camera between takes. The screen rebinds when [captureVersion] changes. */
    fun flipCamera() {
        val s = _ui.value
        if (s.isRecordingActive || s.countdown != null) return
        _ui.update { it.copy(front = !it.front) }
        _captureVersion.value++
    }

    fun setTextSize(sp: Int) {
        val v = sp.coerceIn(16, 44)
        prefs.edit().putInt(KEY_TEXT, v).apply()
        _ui.update { it.copy(textSize = v) }
    }

    fun setUseOwnMic(on: Boolean) {
        if (_ui.value.isRecordingActive) return
        prefs.edit().putBoolean(KEY_OWN_MIC, on).apply()
        listener.setUseOwnMic(on)
        _ui.update { it.copy(useOwnMic = on, voiceEngine = listener.modeLabel) }
    }

    /** Live listener state for the Voice test panel in settings. */
    fun voiceDiagnostics(): String = listener.diagnostics()

    /** Refreshes the voice-engine label (it can change when NETHRA falls back automatically). */
    fun refreshVoiceEngine() = _ui.update { it.copy(voiceEngine = listener.modeLabel) }

    fun setPanel(landscape: Boolean, rect: PanelRect) {
        val r = rect.clamp()
        if (landscape) {
            prefs.edit().putString(KEY_PANEL_L, r.encode()).apply()
            _ui.update { it.copy(panelLandscape = r) }
        } else {
            prefs.edit().putString(KEY_PANEL_P, r.encode()).apply()
            _ui.update { it.copy(panelPortrait = r) }
        }
    }

    fun resetPanel() {
        prefs.edit().remove(KEY_PANEL_L).remove(KEY_PANEL_P).apply()
        _ui.update { it.copy(panelPortrait = PanelRect.DEFAULT_PORTRAIT, panelLandscape = null) }
    }

    /**
     * Switches the spoken language. The recogniser's locale, the syllable rate behind the pace
     * estimate, the script's word target and the caption language all follow from this one choice.
     */
    fun setLanguage(l: ScriptLanguage) {
        if (_ui.value.isRecordingActive || _ui.value.language == l) return
        c.prefs.setLanguage(l)
        listener.setLanguage(l.localeTag, l.syllablesPerWord)
        setWpm(l.wordsPerMinute)
        _ui.update { it.copy(language = l, voiceEngine = listener.modeLabel) }
    }

    val prompterScript: StateFlow<PrompterScript> = c.prefs.prompterScript
    fun setPrompterScript(s: PrompterScript) = c.prefs.setPrompterScript(s)
    val captionScript: StateFlow<CaptionScript> = c.prefs.captionScript
    fun setCaptionScript(s: CaptionScript) = c.prefs.setCaptionScript(s)

    fun setVoiceReplies(on: Boolean) {
        if (!on) speaker.stop()
        prefs.edit().putBoolean(KEY_REPLIES, on).apply()
        _ui.update { it.copy(voiceReplies = on) }
    }

    fun dismissRecordMessage() = _ui.update { it.copy(recordMessage = null) }

    fun dismissClean() = c.postProcessor.clear()

    override fun onCleared() {
        captureJob?.cancel()
        dictationJob?.cancel()
        cancelCountdown()
        listener.stop()
        speaker.shutdown()
        muter.restore()
        runCatching { recording?.stop() }
    }

    companion object {
        private const val TAG = "Prompter"
        private const val AWAIT_TIMEOUT_MS = 8000L
        private const val TICK_MS = 400L
        private const val DEAF_HINT_MS = 15_000L
        /** Partials usually show "Nethra" this long after it was said. */
        private const val WAKE_LAG_MS = 600L
        /** Typical length of "Nethra, pause" / "Nethra, stop". */
        private const val COMMAND_SPEECH_MS = 1100L
        private const val FALLBACK_COMMAND_MS = 2200L
        private const val SPEECH_START_SLACK_MS = 300L
        private const val COMMAND_DEDUPE_MS = 3000L
        /** Silence that ends a dictated brief. */
        private const val CAPTURE_GAP_MS = 2600L
        private val REQUEST_WORDS = setOf("write", "make", "create", "generate", "draft", "prepare", "give", "me", "a", "an", "the",
            "script", "scripts", "new", "about", "on", "for", "please", "can", "you", "i", "want", "need")
        private val DONE_PHRASES = setOf("done", "that's it", "thats it", "that's all", "thats all", "go ahead", "write it", "go", "finish", "okay done", "ok done")
        /** Recogniser level (dB) above which the creator counts as talking. */
        private const val VOICE_RMS_DB = 4f
        private const val KEY_MODE = "scroll_mode"
        private const val KEY_WPM = "wpm"
        private const val KEY_REPLIES = "voice_replies"
        private const val KEY_TEXT = "text_size"
        private const val KEY_OWN_MIC = "own_mic"
        private const val KEY_PANEL_P = "panel_portrait"
        private const val KEY_PANEL_L = "panel_landscape"

        private val KEEPS_FILE = setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        )
    }
}
