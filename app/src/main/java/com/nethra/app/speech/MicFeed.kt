package com.nethra.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * NETHRA's own microphone capture, shared with the speech recogniser.
 *
 * Android gives the microphone to one app at a time. While NETHRA records video,
 * Google's recogniser (a different app) is refused the mic — "Speech Recognition
 * and Synthesis from Google cannot record now…" — so voice commands and the
 * teleprompter went deaf. Instead, NETHRA captures the mic itself (same app as
 * the camera recorder, so both may capture) and streams the audio to the
 * recogniser through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE, Android 13+).
 *
 * It also runs [VoiceActivity] on every frame so the teleprompter knows when
 * the creator is talking, even between recogniser results.
 *
 * 16 kHz mono PCM-16, 20 ms frames. Threads: one reader, one pipe writer.
 */
class MicFeed(private val onSpeechFrame: (nowMs: Long, wpm: Float?) -> Unit) {

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var writer: Thread? = null
    @Volatile private var running = false

    private val vad = VoiceActivity()
    private val rate = SpeechRate(FRAME_MS)

    /** Syllables per word for the language being spoken — see [SpeechRate.syllablesPerWord]. */
    var syllablesPerWord: Float
        get() = rate.syllablesPerWord
        set(v) { rate.syllablesPerWord = v }

    /**
     * NETHRA ends each recogniser session itself: after this much silence following
     * speech, the pipe is closed (end-of-stream) so the recogniser returns its final
     * result. Some recognisers never endpoint on their own when fed a stream, which
     * left "Nethra" alone without a final result.
     */
    @Volatile var endSilenceMs: Long = 900
    @Volatile private var eofPending = false
    @Volatile private var sessionStartMs = 0L
    @Volatile private var sessionLastSpeechMs = 0L
    /** Speech heard (ms) in the current session — lets the listener spot a recogniser that hears nothing. */
    @Volatile var sessionSpeechMs = 0L
        private set
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
    private val lock = Any()
    private var sink: OutputStream? = null
    private var sinkFd: ParcelFileDescriptor? = null
    private var sourceFd: ParcelFileDescriptor? = null

    /** Last ~[PREROLL_MS] of audio, replayed at the start of each recogniser session so no word is lost in the gap. */
    private val ring = ArrayDeque<ByteArray>()

    /** Last time a frame had any signal at all (an all-zero stream means Android silenced this capture). */
    @Volatile var lastSignalMs: Long = 0
        private set

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // Callers only start listening once RECORD_AUDIO is granted.
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 25))
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed: ${e.javaClass.simpleName}"); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return false }
        record = rec
        running = true
        lastSignalMs = SystemClock.elapsedRealtime()
        reader = Thread(::readLoop, "nethra-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        writer = Thread(::writeLoop, "nethra-mic-pipe").apply { start() }
        return true
    }

    fun stop() {
        running = false
        endSession()
        reader?.join(300); writer?.join(300)
        reader = null; writer = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        synchronized(ring) { ring.clear() }
    }

    /**
     * Opens a fresh pipe for one recogniser session and returns its read end
     * (to put in the recognition intent). Pre-roll audio is queued first.
     */
    fun newSession(): ParcelFileDescriptor {
        endSession()
        val (read, write) = ParcelFileDescriptor.createPipe()
        synchronized(lock) {
            sourceFd = read
            sinkFd = write
            sink = ParcelFileDescriptor.AutoCloseOutputStream(write)
            queue.clear()
            eofPending = false
            sessionSpeechMs = 0
            sessionStartMs = SystemClock.elapsedRealtime()
            sessionLastSpeechMs = 0
            synchronized(ring) { ring.forEach { queue.offer(it) } }
        }
        return read
    }

    /** Stop feeding the current session once queued audio is written (end-of-stream → final result). */
    fun finishInput() { if (sink != null) eofPending = true }

    /** Ends the current session: the recogniser sees end-of-stream. */
    fun endSession() {
        synchronized(lock) {
            runCatching { sink?.close() }
            runCatching { sourceFd?.close() }
            sink = null; sinkFd = null; sourceFd = null
            queue.clear()
        }
    }

    private fun readLoop() {
        val rec = record ?: return
        val pcm = ShortArray(FRAME_SAMPLES)
        var lastNotify = 0L
        while (running) {
            val n = rec.read(pcm, 0, pcm.size)
            if (n <= 0) { if (n < 0) SystemClock.sleep(20); continue }
            val now = SystemClock.elapsedRealtime()
            if (pcm.any { it.toInt() != 0 }) lastSignalMs = now
            val speech = vad.feed(pcm, n, now)
            val speaking = vad.isSpeaking(now)
            rate.feed(vad.levelDb, speaking)
            if (speech && now - lastNotify > 100) { lastNotify = now; onSpeechFrame(now, rate.wpm) }
            if (sink != null && !eofPending) {
                if (speech) { sessionSpeechMs += FRAME_MS; sessionLastSpeechMs = now }
                val endOfUtterance = sessionSpeechMs >= 200 && now - sessionLastSpeechMs > endSilenceMs
                if (endOfUtterance || now - sessionStartMs > MAX_SESSION_MS) eofPending = true
            }

            val bytes = ByteArray(n * 2)
            for (i in 0 until n) { bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            synchronized(ring) {
                ring.addLast(bytes)
                while (ring.size > PREROLL_MS / FRAME_MS) ring.removeFirst()
            }
            // Never block the mic thread: if the recogniser isn't reading, drop the oldest audio.
            if (sink != null && !eofPending && !queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
        }
    }

    private fun writeLoop() {
        while (running) {
            val frame = queue.poll(60, TimeUnit.MILLISECONDS)
            if (frame == null) {
                // Everything queued is written: deliver end-of-stream so the recogniser finalises.
                if (eofPending) synchronized(lock) { runCatching { sink?.close() }; sink = null }
                continue
            }
            val out = synchronized(lock) { sink } ?: continue
            try {
                out.write(frame)
            } catch (e: IOException) {
                // The recogniser closed its end (session over). Wait for the next session.
                synchronized(lock) { if (sink === out) { runCatching { out.close() }; sink = null } }
            }
        }
    }

    companion object {
        private const val TAG = "MicFeed"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val PREROLL_MS = 400
        /** ~3 s of audio buffered for a slow recogniser before the oldest is dropped. */
        private const val QUEUE_FRAMES = 150
        /** A single recogniser session never runs longer than this (then it's finalised and restarted). */
        private const val MAX_SESSION_MS = 25_000L
    }
}
