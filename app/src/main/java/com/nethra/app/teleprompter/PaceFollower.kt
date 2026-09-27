package com.nethra.app.teleprompter

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** How the teleprompter scrolls. */
enum class ScrollMode {
    /** Follows the recognised words and glides at the speaker's measured pace. */
    ADAPTIVE,
    /** Constant speed at the chosen words-per-minute (no speech needed). */
    FIXED_WPM
}

/**
 * Turns bursty speech-recognition updates into a smooth, continuous scroll
 * position that moves at the speaker's own pace. Pure — unit tested.
 *
 * Recognisers report words in bursts (every 0.3–1 s, sometimes late). Jumping
 * the script on every burst looks jerky, and waiting for the next burst makes
 * it lag. So this works like a phase-locked loop:
 *
 *  - [onRecognised] anchors the true position whenever the tracker moves;
 *  - the speaker's pace (WPM) is measured from how fast those anchors advance;
 *  - between anchors, [tick] glides forward at that pace, a few words ahead at most,
 *    and eases back onto each new anchor instead of snapping;
 *  - when the speaker goes quiet (no voice activity), the glide stops.
 *
 * In [ScrollMode.FIXED_WPM] it simply advances at the chosen WPM.
 *
 * Positions are in script words (fractional for smooth scrolling).
 */
class PaceFollower(
    initialWpm: Float = DEFAULT_WPM,
    /** How far the glide may run ahead of the last recognised word. */
    private val maxLeadWords: Float = 4f,
    /** Voice quieter than this for [silenceMs] stops the glide. */
    private val silenceMs: Long = 1_200,
    /** Time constant for easing onto the anchor, ms. Smaller = snappier. */
    private val easeMs: Float = 280f,
    /** Window used to measure pace. */
    private val paceWindowMs: Long = 8_000,
    /**
     * If the recogniser hasn't placed the speaker for this long while they are
     * clearly talking (voice activity), glide on at the measured pace instead of
     * stopping — the recogniser can lag or drop out, especially while recording.
     */
    private val deadReckonAfterMs: Long = 2_000
) {
    var mode: ScrollMode = ScrollMode.ADAPTIVE
    /** User-chosen WPM: the speed in FIXED mode and the starting guess in ADAPTIVE mode. */
    var targetWpm: Float = initialWpm.coerceIn(MIN_WPM, MAX_WPM)
        set(v) { field = v.coerceIn(MIN_WPM, MAX_WPM) }
    var paused: Boolean = false

    /** Current measured speaking pace (adaptive), words per minute. */
    var measuredWpm: Float = targetWpm
        private set

    /** Smooth display position in words. */
    var displayPosition: Float = 0f
        private set

    private var anchorPos = 0
    private var anchorMs = 0L
    private var lastRecognisedMs = Long.MIN_VALUE / 2
    private var lastTickMs: Long? = null
    private var lastVoiceMs = Long.MIN_VALUE / 2
    private var wordCount = Int.MAX_VALUE
    private val samples = ArrayDeque<Pair<Long, Int>>()

    /** The pace actually used for scrolling right now. */
    val effectiveWpm: Float get() = if (mode == ScrollMode.FIXED_WPM) targetWpm else measuredWpm

    fun setWordCount(n: Int) { wordCount = max(0, n) }

    /** The script tracker moved to [position] (next word to say). */
    fun onRecognised(position: Int, nowMs: Long) {
        lastVoiceMs = nowMs
        lastRecognisedMs = nowMs
        if (position < anchorPos - 2 || position > anchorPos + 60) {
            // A manual jump or re-read: restart pace measurement from here.
            samples.clear()
            jumpTo(position, nowMs)
            return
        }
        if (position > anchorPos) {
            samples.addLast(nowMs to position)
            while (samples.size > 2 && nowMs - samples.first().first > paceWindowMs) samples.removeFirst()
            updatePace()
        }
        anchorPos = position
        anchorMs = nowMs
    }

    /** The recogniser heard sound / a partial result arrived: the speaker is talking. */
    fun onVoiceActivity(nowMs: Long) { lastVoiceMs = nowMs }

    /**
     * Speaking rate measured from the voice itself ([com.nethra.app.speech.SpeechRate]).
     * Used while recognised words aren't arriving, so the glide follows how fast the
     * person actually talks instead of a preset speed.
     */
    fun onVoicePace(wpm: Float, nowMs: Long) {
        if (nowMs - lastRecognisedMs <= deadReckonAfterMs && samples.size >= 2) return  // words are better evidence
        measuredWpm = (measuredWpm + (wpm - measuredWpm) * 0.25f).coerceIn(MIN_WPM, MAX_WPM)
    }

    /** Manual navigation (drag, previous/next line). Snaps without animation. */
    fun jumpTo(position: Int, nowMs: Long) {
        anchorPos = position
        anchorMs = nowMs
        lastRecognisedMs = nowMs
        displayPosition = position.toFloat()
    }

    fun reset(nowMs: Long = 0) {
        samples.clear()
        measuredWpm = targetWpm
        jumpTo(0, nowMs)
        lastTickMs = null
    }

    /** True while gliding on voice activity alone (the recogniser isn't placing words). */
    var deadReckoning: Boolean = false
        private set

    val isSpeaking: Boolean get() = lastTickMs?.let { it - lastVoiceMs < silenceMs } ?: false

    /** Advance the animation clock; call every frame. @return the display position. */
    fun tick(nowMs: Long): Float {
        val prev = lastTickMs ?: nowMs
        lastTickMs = nowMs
        val dt = (nowMs - prev).coerceIn(0, 100).toFloat()
        if (paused || dt == 0f) return displayPosition

        if (mode == ScrollMode.FIXED_WPM) {
            displayPosition = min(wordCount.toFloat(), displayPosition + targetWpm / 60_000f * dt)
            anchorPos = displayPosition.toInt()
            return displayPosition
        }

        val speaking = nowMs - lastVoiceMs < silenceMs
        // Without words to anchor to, stop sooner after the voice stops (0.5 s, not [silenceMs]).
        val voiceNow = nowMs - lastVoiceMs < DEAD_RECKON_SILENCE_MS
        if (voiceNow && nowMs - lastRecognisedMs > deadReckonAfterMs) {
            // Dead reckoning: voice is heard but no words are being placed. Keep moving at the
            // speaker's pace; the next recognised word re-anchors (easing back if we ran ahead).
            displayPosition = min(wordCount.toFloat(), displayPosition + measuredWpm / 60_000f * dt)
            anchorPos = displayPosition.toInt()
            anchorMs = nowMs
            deadReckoning = true
            return displayPosition
        }
        deadReckoning = false
        val sinceAnchor = (nowMs - anchorMs).coerceAtLeast(0)
        // Glide ahead only while words are being recognised; otherwise dead reckoning (above) decides.
        val recent = nowMs - lastRecognisedMs <= deadReckonAfterMs
        val lead = if (speaking && recent) min(maxLeadWords, measuredWpm / 60_000f * sinceAnchor) else 0f
        val target = min(wordCount.toFloat(), anchorPos + lead)
        // Exponential ease toward the target: never overshoots, frame-rate independent.
        val k = 1f - exp(-dt / easeMs)
        val next = displayPosition + (target - displayPosition) * k
        // The glide never moves backwards by itself; only jumps (manual / re-read) do.
        displayPosition = if (next < displayPosition && displayPosition - target < 1.5f) displayPosition else next
        return displayPosition
    }

    private fun updatePace() {
        if (samples.size < 2) return
        val (t0, p0) = samples.first()
        val (t1, p1) = samples.last()
        val span = t1 - t0
        if (span < MIN_PACE_SPAN_MS) return
        val instant = (p1 - p0) * 60_000f / span
        // Smooth, and trust the measurement more as the window fills.
        val alpha = 0.35f
        measuredWpm = (measuredWpm + (instant - measuredWpm) * alpha).coerceIn(MIN_WPM, MAX_WPM)
    }

    companion object {
        const val DEFAULT_WPM = 150f
        const val MIN_WPM = 60f
        const val MAX_WPM = 260f
        const val MIN_PACE_SPAN_MS = 1_500L
        const val DEAD_RECKON_SILENCE_MS = 500L
    }
}
