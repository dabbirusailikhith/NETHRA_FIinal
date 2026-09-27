package com.nethra.app.speech

/**
 * Estimates speaking rate from the loudness envelope alone — no speech
 * recognition needed. Pure — unit tested.
 *
 * Each syllable is a loudness peak (the vowel). Peaks are counted while the
 * speaker is talking; syllables per second of *speech time* are converted to
 * words per minute (≈ [SYLLABLES_PER_WORD] syllables per word). Pauses don't
 * lower the estimate, so it reflects how fast the person talks, not how often.
 */
class SpeechRate(
    private val frameMs: Int = 20,
    /** A peak must rise this much above the dip before it to count as a new syllable. */
    private val riseDb: Float = 4f,
    /** Syllables can't come faster than this. */
    private val minGapMs: Int = 100,
    /** Seconds of speech kept in the estimate. */
    private val windowSpeechMs: Int = 8_000
) {
    /**
     * Average syllables per spoken word for the language being spoken. English is about 1.5;
     * Hindi is nearer 2.1 and Telugu nearer 2.9, so leaving this at the English value made the
     * teleprompter think an Indian-language speaker was racing. Set from the chosen language.
     */
    var syllablesPerWord: Float = SYLLABLES_PER_WORD
    private var dip = Float.MAX_VALUE
    private var rising = false
    private var peakCandidate = -200f
    private var sinceLastPeakMs = Int.MAX_VALUE / 2
    private val peaks = ArrayDeque<Int>()     // speech-time stamps of syllable peaks
    private var speechTimeMs = 0

    /** Latest estimate, or null until there's enough speech to judge (≥ 2 s). */
    var wpm: Float? = null
        private set

    /**
     * @param levelDb frame loudness (dBFS), @param speaking whether VAD calls this frame speech.
     */
    fun feed(levelDb: Float, speaking: Boolean) {
        sinceLastPeakMs += frameMs
        if (!speaking) { dip = Float.MAX_VALUE; rising = false; return }
        speechTimeMs += frameMs
        if (levelDb < dip) dip = levelDb
        if (levelDb > peakCandidate || !rising) {
            if (levelDb - dip >= riseDb) { rising = true; peakCandidate = levelDb }
        }
        // Falling ≥ riseDb/2 from the candidate confirms the peak.
        if (rising && peakCandidate - levelDb >= riseDb / 2 && sinceLastPeakMs >= minGapMs) {
            peaks.addLast(speechTimeMs)
            sinceLastPeakMs = 0
            rising = false
            dip = levelDb
            peakCandidate = -200f
        }
        while (peaks.isNotEmpty() && speechTimeMs - peaks.first() > windowSpeechMs) peaks.removeFirst()
        val span = minOf(speechTimeMs, windowSpeechMs)
        if (span >= 2_000 && peaks.size >= 4) {
            val sylPerSec = peaks.size * 1000f / span
            wpm = (sylPerSec * 60f / syllablesPerWord.coerceAtLeast(0.5f)).coerceIn(60f, 260f)
        }
    }

    companion object {
        /** English default; other languages set [SpeechRate.syllablesPerWord] from their own value. */
        const val SYLLABLES_PER_WORD = 1.5f
    }
}
