package com.nethra.app.media

import kotlin.math.max
import kotlin.math.min

/** A span of the recording, in milliseconds from its start. */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

/**
 * A spoken command heard during recording, already mapped onto the recording
 * timeline. [roughStartMs] is an estimate of when "Nethra" began; [endMs] is
 * where recording paused or stopped because of the command.
 */
data class CommandMark(val roughStartMs: Long, val endMs: Long, val label: String)

data class CutPlan(
    val cuts: List<Span>,
    val keeps: List<Span>,
    /** True when the audio could be analysed and every cut start was moved to a pause in speech. */
    val boundariesRefined: Boolean
) {
    val removedMs: Long get() = cuts.sumOf { it.durationMs }
}

/**
 * Plans which parts of a recording to keep so spoken commands are removed.
 * Pure — unit tested.
 *
 * Recogniser timing only brackets a command roughly, so each cut start is moved
 * to the nearest pause before the wake word, found in the recording's own
 * loudness envelope, leaving a small safety margin of silence.
 */
object CommandCutPlanner {
    const val MARGIN_MS = 150L
    const val MIN_KEEP_MS = 100L
    /** How far before the rough start to look for the pause that precedes "Nethra". */
    const val SEARCH_BACK_MS = 1800L
    const val SEARCH_FORWARD_MS = 250L
    const val MIN_GAP_MS = 120L
    /** Pauses at least this long are treated as phrase boundaries and preferred. */
    const val PHRASE_GAP_MS = 250L
    /** Used when no pause can be found: cut this much earlier than the rough start. */
    const val FALLBACK_EXTRA_MS = 300L

    fun plan(
        marks: List<CommandMark>,
        durationMs: Long,
        frameRms: FloatArray?,
        frameMs: Int = PcmAudio.FRAME_MS
    ): CutPlan {
        if (marks.isEmpty() || durationMs <= 0) return CutPlan(emptyList(), listOf(Span(0, durationMs.coerceAtLeast(0))), true)
        val quiet = frameRms?.takeIf { it.isNotEmpty() }?.let { quietMask(it) }
        var allRefined = quiet != null
        val cuts = marks.sortedBy { it.endMs }.map { m ->
            val end = m.endMs.coerceIn(0, durationMs)
            val rough = m.roughStartMs.coerceIn(0, end)
            val start = quiet?.let { findPauseBefore(it, rough, frameMs) }
                ?: run { allRefined = false; (rough - FALLBACK_EXTRA_MS).coerceAtLeast(0) }
            Span(min(start, end), end)
        }
        val merged = merge(cuts)
        return CutPlan(merged, keepsFrom(merged, durationMs), allRefined)
    }

    /** Complement of [cuts] inside [0, durationMs], dropping slivers under [MIN_KEEP_MS]. */
    fun keepsFrom(cuts: List<Span>, durationMs: Long): List<Span> {
        val keeps = mutableListOf<Span>()
        var cursor = 0L
        for (c in cuts) {
            if (c.startMs - cursor >= MIN_KEEP_MS) keeps += Span(cursor, c.startMs)
            cursor = max(cursor, c.endMs)
        }
        if (durationMs - cursor >= MIN_KEEP_MS) keeps += Span(cursor, durationMs)
        return keeps
    }

    fun merge(spans: List<Span>): List<Span> {
        val sorted = spans.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        val out = mutableListOf<Span>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs + MIN_KEEP_MS) out[out.size - 1] = Span(last.startMs, max(last.endMs, s.endMs))
            else out += s
        }
        return out
    }

    /** Frames quieter than an adaptive threshold (a multiple of the recording's noise floor). */
    fun quietMask(rms: FloatArray): BooleanArray {
        val sorted = rms.sortedArray()
        val floor = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.size - 1)]
        val threshold = max(floor * 2.5f, floor + 120f)
        return BooleanArray(rms.size) { rms[it] < threshold }
    }

    /**
     * Finds the pause that precedes the wake word: among quiet runs whose end
     * (the next speech onset) is at or before [roughMs] + [SEARCH_FORWARD_MS], take
     * the latest phrase-length pause (≥ [PHRASE_GAP_MS]), else the latest pause of
     * at least [MIN_GAP_MS]. Returns a cut start that keeps most of that pause,
     * leaving [MARGIN_MS] of silence before the command. Null if none is found.
     */
    fun findPauseBefore(quiet: BooleanArray, roughMs: Long, frameMs: Int): Long? {
        val minGap = (MIN_GAP_MS / frameMs).toInt().coerceAtLeast(1)
        val phraseGap = (PHRASE_GAP_MS / frameMs).toInt().coerceAtLeast(minGap)
        val margin = (MARGIN_MS / frameMs).toInt()
        val latestOnset = ((roughMs + SEARCH_FORWARD_MS) / frameMs).toInt().coerceAtMost(quiet.size)
        val earliest = ((roughMs - SEARCH_BACK_MS) / frameMs).toInt().coerceAtLeast(0)
        if (latestOnset <= earliest) return null

        var bestPhrase: IntRange? = null
        var bestAny: IntRange? = null
        var i = earliest
        while (i < latestOnset) {
            if (!quiet[i]) { i++; continue }
            val start = i
            while (i < quiet.size && quiet[i]) i++
            val onset = i                                   // first loud frame after the pause
            if (onset > latestOnset) break                  // pause runs past the command start estimate
            val len = onset - start
            if (len >= minGap) bestAny = start until onset
            if (len >= phraseGap) bestPhrase = start until onset
        }
        val gap = bestPhrase ?: bestAny ?: return null
        val cutFrame = max(gap.first, gap.last + 1 - margin)
        return cutFrame.toLong() * frameMs
    }
}

/**
 * Maps [android.os.SystemClock.elapsedRealtime] moments onto the recording
 * timeline, skipping paused stretches. Fed from CameraX recording events.
 */
class RecordingClock {
    private var accumulatedMs = 0L
    private var activeSince: Long? = null

    fun onStart(nowMs: Long) { accumulatedMs = 0; activeSince = nowMs }
    fun onPause(nowMs: Long) { activeSince?.let { accumulatedMs += nowMs - it }; activeSince = null }
    fun onResume(nowMs: Long) { if (activeSince == null) activeSince = nowMs }
    fun onStop(nowMs: Long) = onPause(nowMs)

    /**
     * Recording position at wall-clock [wallMs]. Exact within the current active
     * stretch; earlier moments clamp to where it began, and moments while paused
     * map to the pause point.
     */
    fun positionAt(wallMs: Long): Long {
        val since = activeSince ?: return accumulatedMs
        return accumulatedMs + (wallMs - since).coerceAtLeast(0)
    }
}
