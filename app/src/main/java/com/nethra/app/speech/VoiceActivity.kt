package com.nethra.app.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice-activity detector with an adaptive noise floor. Pure — unit tested.
 *
 * Feed it 16-bit PCM frames (~20 ms). A frame counts as speech when it is
 * [marginDb] louder than the running noise floor; a short [hangoverMs] keeps
 * "speaking" true across the tiny gaps between words.
 */
class VoiceActivity(
    private val marginDb: Float = 9f,
    private val hangoverMs: Long = 350,
    /** Anything quieter than this is never speech (digital silence, a muted mic). */
    private val absoluteMinDb: Float = -60f
) {
    var noiseFloorDb: Float = -50f
        private set
    var levelDb: Float = -90f
        private set
    private var lastSpeechMs = Long.MIN_VALUE / 2
    private var initialised = false

    fun isSpeaking(nowMs: Long): Boolean = nowMs - lastSpeechMs < hangoverMs

    /** @return true if this frame is speech. */
    fun feed(pcm: ShortArray, count: Int, nowMs: Long): Boolean {
        if (count <= 0) return false
        levelDb = dbfs(pcm, count)
        if (!initialised) { noiseFloorDb = max(levelDb, -70f); initialised = true }
        val speech = levelDb > absoluteMinDb && levelDb > noiseFloorDb + marginDb
        // The floor falls quickly to quieter frames and rises slowly (so speech doesn't become "noise").
        noiseFloorDb = if (levelDb < noiseFloorDb) noiseFloorDb + (levelDb - noiseFloorDb) * 0.2f
        else if (!speech) noiseFloorDb + (levelDb - noiseFloorDb) * 0.02f
        else noiseFloorDb + (levelDb - noiseFloorDb) * 0.002f
        if (speech) lastSpeechMs = nowMs
        return speech
    }

    companion object {
        /** RMS level in dB relative to full scale (0 dBFS = loudest). */
        fun dbfs(pcm: ShortArray, count: Int): Float {
            var sum = 0.0
            for (i in 0 until count) { val v = pcm[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / count)
            return if (rms <= 1e-9) -120f else (20 * log10(rms)).toFloat()
        }
    }
}
