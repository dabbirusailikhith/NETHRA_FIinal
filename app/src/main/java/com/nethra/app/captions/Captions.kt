package com.nethra.app.captions

import com.nethra.app.speech.Utterance
import com.nethra.app.speech.WakeWord
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** A phrase with approximate timing (ms from the start of the video). */
data class TimedSegment(val startMs: Long, val endMs: Long, val text: String)

data class CaptionWord(val text: String, val startMs: Long, val endMs: Long)

/** One on-screen caption: 1–4 words shown together; the word being spoken is highlighted. */
data class CaptionCard(val words: List<CaptionWord>) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * Turns a timed transcript into caption cards. Pure — unit tested.
 *
 *  1. [parseSegments] reads the model's timed JSON (lenient about format);
 *  2. [snapToSpeech] moves each phrase onto the real speech in the audio envelope
 *     (model timestamps are only approximate);
 *  3. [cards] splits phrases into short cards with per-word timing.
 */
object CaptionBuilder {
    const val MAX_WORDS = 4
    const val MAX_CHARS = 26
    const val MIN_CARD_MS = 450L
    /** How far a boundary may move to land on speech. */
    const val SNAP_MS = 450L

    // ------------------------------------------------------------------ parsing

    /**
     * Reads `{"segments":[{"start":1.2,"end":3.4,"text":"…"}]}` (or a bare array; times in
     * seconds as numbers or "m:ss.s" strings) and offsets it by [offsetMs]. Bad rows are skipped.
     */
    fun parseSegments(raw: String, offsetMs: Long, clipMs: Long): List<TimedSegment> {
        val arr = jsonArray(raw) ?: return emptyList()
        val out = mutableListOf<TimedSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = cleanText(o.optString("text", o.optString("t")))
            if (text.isBlank()) continue
            val s = seconds(o.opt("start") ?: o.opt("s")) ?: continue
            val e = seconds(o.opt("end") ?: o.opt("e")) ?: (s + 0.4 * text.split(' ').size)
            val sMs = (s * 1000).toLong().coerceIn(0, clipMs)
            val eMs = (e * 1000).toLong().coerceIn(sMs + 200, max(clipMs, sMs + 200))
            out += TimedSegment(offsetMs + sMs, offsetMs + eMs, text)
        }
        return out.sortedBy { it.startMs }
    }

    private fun jsonArray(raw: String): JSONArray? {
        val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // {"segments":[…]} — but only if it really is that object (a bare array also contains '{').
        if (body.trimStart().startsWith("{")) {
            runCatching { JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1)).optJSONArray("segments") }
                .getOrNull()?.let { return it }
        }
        return runCatching { JSONArray(body.substring(body.indexOf('['), body.lastIndexOf(']') + 1)) }.getOrNull()
    }

    private fun seconds(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> {
            val p = v.trim().split(':')
            runCatching { p.fold(0.0) { acc, part -> acc * 60 + part.toDouble() } }.getOrNull()
        }
        else -> null
    }

    /** Removes [inaudible]-style markers and extra spaces. */
    fun cleanText(t: String) = t.replace(Regex("""\[[^\]]*]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Drops spoken app commands ("Nethra, pause") — they aren't part of the content. */
    fun dropCommands(segs: List<TimedSegment>): List<TimedSegment> = segs.filterNot {
        val u = WakeWord.parse(it.text)
        u is Utterance.Command || (u is Utterance.Wake && u.rest.isBlank())
    }

    // ------------------------------------------------------------------ timing

    /** Frames (20 ms) that contain speech, from a linear RMS envelope, with an adaptive threshold. */
    fun speechMask(frameRms: FloatArray): BooleanArray {
        if (frameRms.isEmpty()) return BooleanArray(0)
        val sorted = frameRms.sorted()
        val floor = sorted[(sorted.size * 0.15).toInt().coerceAtMost(sorted.size - 1)]
        val threshold = max(floor * 3f, 150f)
        return BooleanArray(frameRms.size) { frameRms[it] > threshold }
    }

    /**
     * Moves each segment's start to the first speech within ±[SNAP_MS] and its end to the
     * last speech within ±[SNAP_MS]; keeps segments in order, non-overlapping, ≥ [MIN_CARD_MS].
     */
    fun snapToSpeech(segs: List<TimedSegment>, speech: BooleanArray, frameMs: Int = 20): List<TimedSegment> {
        if (speech.isEmpty()) return fixOrder(segs)
        val w = (SNAP_MS / frameMs).toInt()
        fun frame(ms: Long) = (ms / frameMs).toInt().coerceIn(0, speech.size - 1)
        val snapped = segs.map { s ->
            val a = frame(s.startMs); val b = frame(s.endMs)
            val start = (max(0, a - w)..min(speech.size - 1, a + w)).firstOrNull { speech[it] }?.let { it.toLong() * frameMs } ?: s.startMs
            val end = (min(speech.size - 1, b + w) downTo max(0, b - w)).firstOrNull { speech[it] }?.let { (it + 1).toLong() * frameMs } ?: s.endMs
            if (end > start) s.copy(startMs = start, endMs = end) else s
        }
        return fixOrder(snapped)
    }

    private fun fixOrder(segs: List<TimedSegment>): List<TimedSegment> {
        val out = mutableListOf<TimedSegment>()
        for (s in segs.sortedBy { it.startMs }) {
            val prevEnd = out.lastOrNull()?.endMs ?: 0L
            val start = max(s.startMs, prevEnd)
            val end = max(s.endMs, start + MIN_CARD_MS)
            out += s.copy(startMs = start, endMs = end)
        }
        return out
    }

    /**
     * Fallback when the model gives text without usable timestamps: spread the words over
     * the speech regions of [startMs, endMs] in proportion to how long each region is.
     */
    fun fromText(text: String, speech: BooleanArray, startMs: Long, endMs: Long, frameMs: Int = 20): List<TimedSegment> {
        val words = cleanText(text).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val a = (startMs / frameMs).toInt().coerceAtLeast(0)
        val b = (endMs / frameMs).toInt().coerceAtMost(speech.size)
        val regions = mutableListOf<LongRange>()
        var i = a
        while (i < b) {
            if (!speech.getOrElse(i) { false }) { i++; continue }
            var j = i
            while (j < b && (speech.getOrElse(j) { false } || (j + 10 < b && (j until j + 10).any { speech.getOrElse(it) { false } }))) j++
            regions += (i.toLong() * frameMs)..(j.toLong() * frameMs)
            i = j + 1
        }
        if (regions.isEmpty()) regions += startMs..endMs
        val total = regions.sumOf { it.last - it.first }.coerceAtLeast(1)
        val out = mutableListOf<TimedSegment>()
        var w = 0
        regions.forEachIndexed { idx, r ->
            val share = if (idx == regions.lastIndex) words.size - w
            else ((r.last - r.first).toDouble() / total * words.size).toInt().coerceAtMost(words.size - w)
            if (share > 0) { out += TimedSegment(r.first, r.last, words.subList(w, w + share).joinToString(" ")); w += share }
        }
        return out
    }

    // ------------------------------------------------------------------ cards

    /** Splits segments into cards of ≤ [MAX_WORDS] words / [MAX_CHARS] chars, breaking at punctuation. */
    fun cards(segs: List<TimedSegment>): List<CaptionCard> {
        val cards = mutableListOf<CaptionCard>()
        for (s in segs) {
            val words = timedWords(s)
            var cur = mutableListOf<CaptionWord>()
            for (w in words) {
                val chars = cur.sumOf { it.text.length + 1 } + w.text.length
                if (cur.isNotEmpty() && (cur.size >= MAX_WORDS || chars > MAX_CHARS)) { cards += CaptionCard(cur); cur = mutableListOf() }
                cur += w
                if (w.text.last() in ".?!,;:" && cur.size >= 2) { cards += CaptionCard(cur); cur = mutableListOf() }
            }
            if (cur.isNotEmpty()) cards += CaptionCard(cur)
        }
        return cards
    }

    /** Word timings inside a segment, proportional to word length (a stand-in for syllables). */
    fun timedWords(s: TimedSegment): List<CaptionWord> {
        val words = s.text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val weights = words.map { it.count(Char::isLetterOrDigit).coerceAtLeast(1) + 2 }
        val total = weights.sum().toDouble()
        val span = (s.endMs - s.startMs).coerceAtLeast(1)
        var t = s.startMs.toDouble()
        return words.mapIndexed { i, w ->
            val d = span * weights[i] / total
            val cw = CaptionWord(w, t.toLong(), (t + d).toLong())
            t += d
            cw
        }
    }

    /** The card on screen at [timeMs] and the index of the word being spoken, or null. */
    fun at(cards: List<CaptionCard>, timeMs: Long): Pair<Int, Int>? {
        var lo = 0; var hi = cards.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cards[mid]
            // A card stays up until the next one starts (max 700 ms) so text doesn't flicker off between words.
            val holdEnd = min(c.endMs + 700, cards.getOrNull(mid + 1)?.startMs ?: Long.MAX_VALUE)
            when {
                timeMs < c.startMs -> hi = mid - 1
                timeMs >= max(c.endMs, holdEnd) -> lo = mid + 1
                else -> {
                    val word = c.words.indexOfLast { it.startMs <= timeMs }.coerceAtLeast(0)
                    return mid to word
                }
            }
        }
        return null
    }

    /** SubRip subtitles for YouTube uploads. */
    fun toSrt(cards: List<CaptionCard>): String = buildString {
        cards.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
}
