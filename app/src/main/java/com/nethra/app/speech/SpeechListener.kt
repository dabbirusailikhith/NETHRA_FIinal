package com.nethra.app.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Timing of one recognised utterance, in [SystemClock.elapsedRealtime] milliseconds. */
data class HeardTiming(
    /** When the recogniser detected speech starting (earliest bound of the phrase). */
    val speechStartMs: Long?,
    /** When a partial result first contained the wake word (a later, tighter bound). */
    val wakeSeenMs: Long?,
    /** When the recogniser detected the end of speech. */
    val speechEndMs: Long?,
    /** When the final result arrived. */
    val finalMs: Long
)

interface SpeechCallbacks {
    fun onListening(active: Boolean) {}
    fun onPartial(text: String) {}
    /** Microphone loudness from the recogniser (dB, roughly -2..10). Used to tell when the speaker is talking. */
    fun onLevel(rmsDb: Float) {}
    /**
     * NETHRA's own voice-activity detector heard speech (called ~10×/s while talking). Main thread.
     * [wpm] is the speaking rate measured from the voice's rhythm (null until ~2 s of speech).
     */
    fun onSpeechActivity(nowMs: Long, wpm: Float?) {}
    fun onFinal(text: String, timing: HeardTiming)
    /** A problem the creator should see (permission, no recogniser, repeated failures). */
    fun onProblem(message: String, fatal: Boolean) {}
}

/**
 * Continuous listening built from back-to-back [SpeechRecognizer] sessions.
 * Android has no always-on wake-word API for apps, so each session is restarted
 * as soon as it ends. Must be used from the main thread.
 *
 * On Android 13+ NETHRA captures the microphone itself ([MicFeed]) and streams
 * it to the recogniser, so recognition keeps working while the camera records
 * (Android refuses the mic to Google's recogniser during another app's
 * recording). Order tried: on-device recogniser + our audio → default
 * recogniser + our audio → default recogniser with its own mic (older phones).
 */
class SpeechListener(private val context: Context, private val callbacks: SpeechCallbacks) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var sessionActive = false
    private var preferOffline = false
    private var languageTag: String? = null
    private var longPause = false
    private var consecutiveErrors = 0

    private val mic = MicFeed { now, wpm -> main.post { if (wanted) callbacks.onSpeechActivity(now, wpm) } }
    /** Sessions in a row where our VAD heard speech but the recogniser returned nothing. */
    private var deafSessions = 0
    private var ownMicAllowed = true
    private var recording = false
    /**
     * Shared-mic recogniser for recordings: 0 = on-device, 1 = default Google recogniser.
     * [sharedBroken] once neither accepts NETHRA's audio on this phone.
     */
    private var sharedStage = 0
    private var sharedBroken = Build.VERSION.SDK_INT < 33
    private var stageFailures = 0
    private var stageWorked = false

    /**
     * Classic path (recogniser opens the mic itself) whenever the camera isn't recording —
     * the reliable way for wake-word listening. Only during a recording, when Android refuses
     * the mic to the recogniser, NETHRA captures the mic and streams it to the recogniser.
     */
    private val shared: Boolean get() = recording && ownMicAllowed && !sharedBroken

    /**
     * True when recognition can keep going during a take (shared mic available). When false,
     * listening during a recording only triggers Android's "cannot record now" warning —
     * callers pause listening for the take instead.
     */
    val canListenWhileRecording: Boolean get() = ownMicAllowed && !sharedBroken

    /** Which listening path is in use, for the settings screen. */
    val modeLabel: String get() = when {
        !shared -> "Google recogniser · phone mic" + if (!canListenWhileRecording) " (voice pauses during takes)" else " (NETHRA mic during takes)"
        sharedStage == 0 -> "On-device recogniser · NETHRA mic (recording)"
        else -> "Google recogniser · NETHRA mic (recording)"
    }

    // ---- live diagnostics for the Voice test panel and logcat (tag SpeechListener)
    @Volatile var lastEvent: String = "idle"; private set
    @Volatile var lastHeard: String = ""; private set
    @Volatile var lastError: String = ""; private set
    @Volatile var level: Float = -2f; private set
    private fun event(e: String) { lastEvent = e; Log.i(TAG, e) }

    /** Lets the creator turn the shared-mic path off if it misbehaves on their phone. */
    fun setUseOwnMic(on: Boolean) {
        if (ownMicAllowed == on) return
        ownMicAllowed = on
        restartIfRunning()
    }

    /** Tell the listener a recording started/stopped: it switches listening path accordingly. */
    fun setRecording(active: Boolean) {
        if (recording == active) return
        recording = active
        restartIfRunning()
    }

    private fun restartIfRunning() {
        if (!wanted) return
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        if (shared) { if (!mic.isRunning && !mic.start()) { sharedBroken = true; event("NETHRA mic unavailable") } } else mic.stop()
        startSession(150)
    }

    private var offlineSince = 0L
    private var sendLocale = true
    private var speechStart: Long? = null
    private var wakeSeen: Long? = null
    private var speechEnd: Long? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)
    val isRunning: Boolean get() = wanted

    fun start() {
        if (wanted) return
        if (!isAvailable) {
            callbacks.onProblem("No speech recogniser on this phone. Install or enable Google's speech services (Speech Recognition & Synthesis) to use voice commands. Buttons still work.", true)
            return
        }
        wanted = true
        consecutiveErrors = 0
        if (shared && !mic.isRunning && !mic.start()) { sharedBroken = true; event("NETHRA mic unavailable") }
        event("start (${modeLabel})")
        startSession(0)
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        mic.stop()
        callbacks.onListening(false)
    }

    /** When true, the recogniser waits for a longer pause before finishing (used while dictating a brief). */
    fun setLongPause(enabled: Boolean) {
        if (longPause == enabled) return
        longPause = enabled
        mic.endSilenceMs = if (enabled) 2500 else 900
    }

    fun setPreferOffline(offline: Boolean) { preferOffline = offline }

    /**
     * Timing of the utterance in progress, for acting on a *partial* result
     * (e.g. "Nethra stop" before the recogniser has finished the session).
     */
    fun partialTiming(): HeardTiming = HeardTiming(speechStart, wakeSeen, null, SystemClock.elapsedRealtime())

    /**
     * Sets the recogniser's language and the syllable rate used to measure speaking pace.
     * [tag] is a BCP-47 locale such as "hi-IN"; null lets the recogniser pick its own default.
     */
    fun setLanguage(tag: String?, syllablesPerWord: Float = SpeechRate.SYLLABLES_PER_WORD) {
        languageTag = tag
        sendLocale = true
        mic.syllablesPerWord = syllablesPerWord
    }

    private fun startSession(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        if (recording && !shared) {
            // The recogniser can't get the mic during our recording on this phone; trying only
            // makes Android show its "cannot record now" warning. Resume after the take.
            event("paused during recording")
            return
        }
        main.postDelayed({ if (wanted) beginSession() }, delayMs)
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (shared && sharedStage == 0 && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else {
            if (shared && sharedStage == 0) sharedStage = 1
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /** The shared-mic recogniser failed repeatedly before ever working: fall back one step. */
    private fun demote(reason: String) {
        if (!shared) return
        Log.w(TAG, "Shared-mic recogniser $sharedStage failed ($reason); falling back")
        if (sharedStage == 0) sharedStage = 1 else {
            sharedBroken = true
            mic.stop()
            callbacks.onProblem("On this phone voice commands can't run during a take. They work before and after; the buttons always work.", false)
        }
        stageFailures = 0
        deafSessions = 0
        stageWorked = false
        destroyRecognizer()
        event("fallback: $modeLabel")
    }

    /**
     * Forces the recogniser to deliver its final result now. Used when partial words
     * stopped arriving (some recognisers, especially fed a stream, never decide the
     * speaker has finished, which left "Nethra" alone without a result).
     */
    private val finalizeNow = Runnable {
        if (!sessionActive) return@Runnable
        event("finalising after pause")
        if (shared) mic.finishInput()
        runCatching { recognizer?.stopListening() }
    }

    private fun beginSession() {
        val rec = recognizer ?: try {
            createRecognizer().also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        } catch (e: Exception) {
            callbacks.onProblem("Speech recogniser failed to start (${e.javaClass.simpleName}).", false)
            startSession(3000)
            return
        }
        speechStart = null; wakeSeen = null; speechEnd = null
        if (preferOffline && SystemClock.elapsedRealtime() - offlineSince > OFFLINE_RETRY_MS) preferOffline = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            val silence = if (longPause) 2500L else 900L
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            if (longPause) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
            // Always ask in the phone's own language/accent (e.g. en-IN), which recognises names better.
            if (languageTag == null && sendLocale) putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            if (Build.VERSION.SDK_INT >= 33) {
                // Nudge the recogniser toward NETHRA's own words (supported by newer recognisers; ignored otherwise).
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIASING)
            }
            if (shared && Build.VERSION.SDK_INT >= 33) {
                // Feed our own microphone audio instead of letting the recogniser open the mic.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, mic.newSession())
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeed.SAMPLE_RATE)
            }
        }
        try {
            rec.startListening(intent)
            sessionActive = true
            callbacks.onListening(true)
            event("listening")
            if (shared && !stageWorked) {
                // A recogniser that silently ignores our audio never calls back: catch that too.
                main.postDelayed({ if (wanted && sessionActive && !stageWorked && mic.sessionSpeechMs >= 1500) { demote("no response"); startSession(200) } }, 12_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.javaClass.simpleName}")
            destroyRecognizer()
            startSession(1500)
        }
    }

    private fun destroyRecognizer() {
        sessionActive = false
        mic.endSession()
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { event("ready") }
        // Not proof the recogniser understands our audio (it may still return nothing), so stageWorked waits for words.
        override fun onBeginningOfSpeech() { speechStart = SystemClock.elapsedRealtime() }
        override fun onRmsChanged(rmsdB: Float) { level = rmsdB; callbacks.onLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { speechEnd = SystemClock.elapsedRealtime() }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            stageWorked = true
            lastHeard = text
            Log.i(TAG, "partial: $text")
            // If no newer partial arrives, force the final result (see finalizeNow).
            main.removeCallbacks(finalizeNow)
            main.postDelayed(finalizeNow, if (longPause) 2600L else 1200L)
            if (wakeSeen == null && WakeWord.findWake(com.nethra.app.core.Text.tokens(text)) != null) {
                wakeSeen = SystemClock.elapsedRealtime()
            }
            callbacks.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            main.removeCallbacks(finalizeNow)
            sessionActive = false
            val heardSpeech = mic.sessionSpeechMs
            mic.endSession()
            consecutiveErrors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // Prefer an alternative that contains the wake word — recognisers often put
            // the unfamiliar name only in the 2nd or 3rd hypothesis.
            // Prefer an alternative that is a command, then one with the wake word, then the top guess.
            val best = list.firstOrNull { WakeWord.parse(it) is Utterance.Command || WakeWord.bareCommand(it) != null }
                ?: list.firstOrNull { WakeWord.findWake(com.nethra.app.core.Text.tokens(it)) != null }
                ?: list.firstOrNull()
            event("result: ${best ?: "(nothing)"}")
            if (!best.isNullOrBlank()) { stageWorked = true; deafSessions = 0; lastHeard = best }
            else if (shared && !stageWorked && heardSpeech >= 800 && ++deafSessions >= 2) {
                // Our mic heard clear speech twice but the recogniser returned nothing: it isn't getting our audio.
                demote("empty results"); startSession(200); return
            }
            if (!best.isNullOrBlank()) {
                callbacks.onFinal(best, HeardTiming(speechStart, wakeSeen, speechEnd, SystemClock.elapsedRealtime()))
            }
            if (wanted) startSession(50)
        }

        override fun onError(error: Int) {
            main.removeCallbacks(finalizeNow)
            sessionActive = false
            lastError = "error $error (${errorName(error)})"
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) event(lastError)
            val heardSpeech = mic.sessionSpeechMs
            mic.endSession()
            if (!wanted) return
            if (shared && !stageWorked && heardSpeech >= 800 &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && ++deafSessions >= 2
            ) { demote("hears nothing"); startSession(200); return }
            // With our own audio, a recogniser that can't take it fails at once with a
            // client/audio/language error. Fall back after a few such failures.
            if (shared && !stageWorked && error in STAGE_ERRORS && ++stageFailures >= 3) {
                demote("error $error"); startSession(300); return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    startSession(50)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wanted = false
                    callbacks.onListening(false)
                    callbacks.onProblem("Microphone permission is needed for voice commands. Buttons still work.", true)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    destroyRecognizer()
                    retryWithBackoff()
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                ERROR_SERVER_DISCONNECTED -> {
                    // Try the on-device recogniser for a while, then go back online (it's more accurate).
                    preferOffline = true
                    offlineSince = SystemClock.elapsedRealtime()
                    retryWithBackoff()
                }
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                    languageTag = null
                    sendLocale = false   // let the recogniser pick its own default language
                    retryWithBackoff()
                }
                else -> {
                    destroyRecognizer()
                    retryWithBackoff()
                }
            }
        }
    }

    private fun retryWithBackoff() {
        consecutiveErrors++
        if (consecutiveErrors == 5) {
            callbacks.onProblem("Voice commands keep failing on this phone. NETHRA will keep trying; the buttons always work.", false)
        }
        startSession((250L * consecutiveErrors).coerceAtMost(4000L))
    }

    /** One line for the Voice test panel. */
    fun diagnostics(): String = buildString {
        append(modeLabel)
        append("\nState: ").append(if (wanted) lastEvent else "off")
        append("\nHeard: ").append(lastHeard.ifBlank { "—" })
        if (lastError.isNotBlank()) append("\nLast error: ").append(lastError)
    }

    companion object {
        private const val TAG = "SpeechListener"

        fun errorName(e: Int) = when (e) {
            1 -> "network timeout"; 2 -> "network"; 3 -> "audio"; 4 -> "server"; 5 -> "client"
            6 -> "no speech"; 7 -> "no match"; 8 -> "busy"; 9 -> "no mic permission"; 10 -> "too many requests"
            11 -> "server disconnected"; 12 -> "language not supported"; 13 -> "language unavailable"
            else -> "code $e"
        }
        // API 31 constants referenced by value so older SDK stubs are not needed.
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val OFFLINE_RETRY_MS = 60_000L
        private val BIASING = arrayListOf(
            "Nethra", "write a script", "script about", "start recording", "stop", "pause", "resume",
            "minute", "seconds", "tone", "audience", "YouTube", "Instagram", "reel", "shorts", "call to action", "done"
        )
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private val STAGE_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER
        )
    }
}
