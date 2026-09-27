package com.nethra.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records one spoken utterance (a command or a script brief) with NETHRA's own
 * microphone, for accurate cloud transcription. Ends by itself when the speaker
 * pauses ([endSilenceMs] after speech), or on [finishNow]. 16 kHz mono PCM-16.
 *
 * The speech recogniser must be stopped first (only one of them can hold the mic).
 */
class VoiceClip {
    private val stop = AtomicBoolean(false)

    /** Ends the recording now (the ✓ button). */
    fun finishNow() = stop.set(true)

    /**
     * @return little-endian PCM bytes, or null if nobody spoke within [startTimeoutMs].
     * @param onLevel 0..1 loudness for a level meter (called ~10×/s).
     */
    @SuppressLint("MissingPermission") // Only called with RECORD_AUDIO granted.
    suspend fun record(
        startTimeoutMs: Long = 7_000,
        endSilenceMs: Long = 1_800,
        maxMs: Long = 60_000,
        onLevel: (Float) -> Unit = {}
    ): ByteArray? = withContext(Dispatchers.IO) {
        val rate = MicFeed.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION: tuned for speech-to-text (no heavy processing that smears words).
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MicFeed.FRAME_BYTES * 25))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val vad = VoiceActivity(marginDb = 8f, hangoverMs = 250)
        val out = ByteArrayOutputStream(rate * 2 * 10)
        val frame = ShortArray(MicFeed.FRAME_SAMPLES)
        val bytes = ByteArray(MicFeed.FRAME_BYTES)
        // Keep ~300 ms before speech starts so the first word isn't clipped.
        val preroll = ArrayDeque<ByteArray>()
        var heardSpeech = false
        var lastSpeech = 0L
        var lastLevel = 0L
        val started = SystemClock.elapsedRealtime()
        stop.set(false)
        try {
            rec.startRecording()
            while (true) {
                ensureActive()
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                val speech = vad.feed(frame, n, now)
                for (i in 0 until n) { bytes[2 * i] = (frame[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte() }
                if (now - lastLevel > 100) { lastLevel = now; onLevel(((vad.levelDb + 60f) / 50f).coerceIn(0f, 1f)) }
                if (!heardSpeech) {
                    preroll.addLast(bytes.copyOf(n * 2)); if (preroll.size > 15) preroll.removeFirst()
                    if (speech) { heardSpeech = true; lastSpeech = now; preroll.forEach { out.write(it) }; preroll.clear() }
                    else if (now - started > startTimeoutMs || stop.get()) return@withContext null
                } else {
                    out.write(bytes, 0, n * 2)
                    if (speech) lastSpeech = now
                    if (stop.get() || now - lastSpeech > endSilenceMs || now - started > maxMs) break
                }
            }
            out.toByteArray()
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
