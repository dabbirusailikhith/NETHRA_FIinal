package com.nethra.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * NETHRA's voice, via Android text-to-speech.
 *
 * - Picks the best installed voice for the language (on-device, highest quality,
 *   so it works offline and doesn't sound robotic).
 * - Ducks music/other audio while speaking (transient audio focus).
 * - Won't cut itself off to repeat the same kind of prompt: [say] with
 *   [Priority.NORMAL] waits for the current sentence; [Priority.URGENT] interrupts.
 * - Tracks when it was last talking so the speech recogniser can ignore
 *   NETHRA's own voice (see [isEchoWindow]).
 */
class Speaker(context: Context) {

    enum class Priority { NORMAL, URGENT }

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .build()

    @Volatile private var ready = false
    @Volatile private var failed = false
    @Volatile private var talking = false
    @Volatile private var lastEndMs = 0L
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val main = Handler(Looper.getMainLooper())
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            configure()
            pending?.let { (text, done) -> pending = null; main.post { say(text, Priority.URGENT, done) } }
        } else {
            failed = true
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    val isReady: Boolean get() = ready
    val isUnavailable: Boolean get() = failed
    val isSpeaking: Boolean get() = talking || (ready && tts.isSpeaking)

    /** True while NETHRA is talking and for a short tail after, when the mic may still hear it. */
    fun isEchoWindow(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        isSpeaking || nowMs - lastEndMs < ECHO_TAIL_MS

    private fun configure() {
        tts.setAudioAttributes(attrs)
        val wanted = Locale.getDefault()
        val locale = if (tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) wanted else Locale.US
        tts.language = locale
        pickBestVoice(locale)?.let { runCatching { tts.voice = it } }
        // Slightly slower than default with a touch of warmth: clearer from a few metres away.
        tts.setSpeechRate(0.98f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { talking = true }
            override fun onDone(utteranceId: String?) { finished(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finished(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finished(utteranceId) }
        })
    }

    private fun finished(utteranceId: String? = null) {
        talking = false
        lastEndMs = SystemClock.elapsedRealtime()
        runCatching { audio.abandonAudioFocusRequest(focus) }
        utteranceId?.let { id -> doneCallbacks.remove(id)?.let { cb -> main.post(cb) } }
    }

    /** Best on-device voice for [locale]: same language & country, highest quality, lowest latency. */
    private fun pickBestVoice(locale: Locale): Voice? = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(
                compareBy<Voice>(
                    { it.isNetworkConnectionRequired },          // offline first
                    { if (it.locale.country == locale.country) 0 else 1 },
                    { -it.quality },
                    { it.latency }
                )
            )
            .firstOrNull()
    }.getOrNull()

    /**
     * Speaks [text]. [onDone] runs on the main thread when it has finished (or
     * straight away if speech is unavailable), e.g. to start recording only after
     * a spoken countdown so NETHRA's voice isn't in the video.
     */
    fun say(text: String, priority: Priority = Priority.URGENT, onDone: (() -> Unit)? = null) {
        if (failed) { onDone?.let { main.post(it) }; return }
        if (!ready) { pending = text to onDone; return }
        // Don't cut a sentence off for routine guidance; the next frame will re-evaluate anyway.
        if (priority == Priority.NORMAL && isSpeaking) { onDone?.let { main.post(it) }; return }
        runCatching { audio.requestAudioFocus(focus) }
        talking = true
        val id = "nethra-${System.nanoTime()}"
        if (onDone != null) doneCallbacks[id] = onDone
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }, id)
        if (result != TextToSpeech.SUCCESS) finished(id)
    }

    fun stop() {
        if (ready) tts.stop()
        // Interrupted utterances still owe their callbacks (a countdown must not hang).
        doneCallbacks.keys.toList().forEach { finished(it) }
        finished()
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        runCatching { audio.abandonAudioFocusRequest(focus) }
        ready = false
    }

    companion object {
        private const val TAG = "Speaker"
        const val ECHO_TAIL_MS = 600L
    }
}

/**
 * Silences the start/stop chime some speech recognisers play on every session
 * (NETHRA restarts sessions continuously). The notification, system and media
 * playback volumes are muted while the teleprompter is open — microphone
 * recording is unaffected. Streams the user had already muted are left alone.
 * Always call [restore].
 */
class ChimeMuter(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val streams = intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)
    private val muted = mutableSetOf<Int>()

    fun mute() {
        for (s in streams) {
            if (s in muted) continue
            // Don't touch streams the user already muted; unmuting later would surprise them.
            if (audio.isStreamMute(s)) continue
            runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) }.onSuccess { muted += s }
        }
    }

    fun restore() {
        for (s in muted) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        muted.clear()
    }
}
