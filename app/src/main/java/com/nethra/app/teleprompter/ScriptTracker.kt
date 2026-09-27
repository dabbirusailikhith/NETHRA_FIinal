package com.nethra.app.teleprompter

import com.nethra.app.core.Text

/**
 * Follows the creator through the script from recognised speech.
 *
 * Matching is tolerant: the last few heard words are aligned (LCS) against a
 * window just around the current position, with fuzzy word equality, and the
 * position only moves when at least two words agree. It prefers moving forward
 * and never jumps far backwards on its own. Pure — unit tested.
 *
 * [script] is exactly what the creator reads. For Hindi and Telugu that is usually Hinglish /
 * Tinglish in English letters, while the recogniser answers in Devanagari or Telugu script —
 * so both sides go through [keyOf] before comparing. English uses [Text.fold]; Hindi and
 * Telugu use [com.nethra.app.core.Translit.matchKey], which romanises what was heard and
 * reduces both spellings to a sound key ("करेंगे" and "karege" meet in the middle). The
 * same key works when the script itself is in native letters.
 */
class ScriptTracker(script: String, private val keyOf: (String) -> String = Text::fold) {

    /** Display segments (sentences / short paragraphs), in order. */
    val lines: List<String>
    private val words: List<String>
    private val wordLine: IntArray
    private val lineFirstWord: IntArray

    /** Index of the next script word the creator is expected to say. */
    var position: Int = 0
        private set

    init {
        val segs = splitLines(script)
        lines = segs
        val w = mutableListOf<String>()
        val wl = mutableListOf<Int>()
        val first = IntArray(segs.size)
        segs.forEachIndexed { li, s ->
            first[li] = w.size
            Text.tokens(s).forEach { t -> w += keyOf(t); wl += li }
        }
        words = w
        wordLine = wl.toIntArray()
        lineFirstWord = first
    }

    val wordCount: Int get() = words.size
    val isFinished: Boolean get() = position >= words.size

    /** Line containing the next word to say (the last line once finished). */
    val currentLine: Int
        get() = if (words.isEmpty()) 0 else wordLine[position.coerceAtMost(words.size - 1)]

    /** Progress through the current line, 0..1, for a subtle highlight. */
    val progress: Float get() = if (words.isEmpty()) 0f else position.toFloat() / words.size

    /**
     * Feed recogniser text (partial or final). Only the tail is used.
     * @return true if the position changed.
     */
    fun onHeard(text: String): Boolean {
        if (words.isEmpty()) return false
        val heard = Text.tokens(text).map(keyOf).takeLast(HEARD_TAIL)
        if (heard.isEmpty()) return false

        val lo = (position - BACK_WINDOW).coerceAtLeast(0)
        val hi = (position + FORWARD_WINDOW).coerceAtMost(words.size - 1)
        var bestEnd = -1
        var bestScore = 0
        for (end in lo..hi) {
            // The newest heard word must match the candidate end word.
            if (!same(heard.last(), words[end])) continue
            val start = (end - heard.size - 2).coerceAtLeast(0)
            val score = lcs(heard, start, end)
            val better = score > bestScore ||
                (score == bestScore && bestEnd >= 0 && preferred(end, bestEnd))
            if (better) { bestScore = score; bestEnd = end }
        }
        val needed = if (heard.size == 1) 1 else MIN_MATCHES
        if (bestEnd < 0 || bestScore < needed) return false
        // A single matched word may only nudge forward a little, never backwards.
        if (bestScore < MIN_MATCHES && (bestEnd < position || bestEnd > position + 3 || words[bestEnd].length < 4)) return false
        // Moving backwards needs strong evidence (the creator re-reading a line).
        if (bestEnd + 1 < position && bestScore < 3) return false
        val newPos = bestEnd + 1
        if (newPos == position) return false
        position = newPos
        return true
    }

    /** Manual correction: jump so [line] is the current line. */
    fun jumpToLine(line: Int) {
        if (lines.isEmpty()) return
        position = lineFirstWord[line.coerceIn(0, lines.size - 1)]
    }

    fun nudgeLines(delta: Int) = jumpToLine(currentLine + delta)

    /** Pace-based fallback (auto-scroll) when speech can't be heard. */
    fun advanceWords(n: Int) { position = (position + n).coerceIn(0, words.size) }

    fun reset() { position = 0 }

    /** Sets the next word to say (used when fixed-WPM scrolling drives the position). */
    fun setPosition(word: Int) { position = word.coerceIn(0, words.size) }

    /** Number of words in [line]. */
    fun wordsInLine(line: Int): Int {
        if (lines.isEmpty()) return 0
        val l = line.coerceIn(0, lines.size - 1)
        val end = if (l + 1 < lines.size) lineFirstWord[l + 1] else words.size
        return end - lineFirstWord[l]
    }

    /**
     * Maps a fractional word position (from [PaceFollower]) to the line it falls in
     * and how far through that line it is (0..1), for smooth pixel scrolling.
     */
    fun lineAt(wordPosition: Float): Pair<Int, Float> {
        if (lines.isEmpty() || words.isEmpty()) return 0 to 0f
        val p = wordPosition.coerceIn(0f, words.size.toFloat())
        val idx = p.toInt().coerceAtMost(words.size - 1)
        val line = wordLine[idx]
        val n = wordsInLine(line).coerceAtLeast(1)
        val frac = ((p - lineFirstWord[line]) / n).coerceIn(0f, 1f)
        return line to frac
    }

    /** Of two equal-scoring ends, prefer the one at/after the current position and closest to it. */
    private fun preferred(a: Int, b: Int): Boolean {
        fun cost(e: Int) = if (e + 1 >= position) (e + 1 - position) else (position - e) * 3
        return cost(a) < cost(b)
    }

    private fun lcs(heard: List<String>, start: Int, end: Int): Int {
        val seg = end - start + 1
        val dp = Array(heard.size + 1) { IntArray(seg + 1) }
        for (i in 1..heard.size) for (j in 1..seg) {
            dp[i][j] = if (same(heard[i - 1], words[start + j - 1])) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[heard.size][seg]
    }

    companion object {
        const val HEARD_TAIL = 6

        private fun splitLines(text: String): List<String> =
            text.split(Regex("""(?<=[.!?…])\s+|\n+"""))
                .map { it.trim() }
                .filter { it.any(Char::isLetterOrDigit) }
        const val BACK_WINDOW = 5
        const val FORWARD_WINDOW = 40
        const val MIN_MATCHES = 2

        fun same(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length >= 4 && b.length >= 4 && Text.levenshtein(a, b) <= 1) return true
            // Recognisers often drop or add plural/possessive endings.
            return a.length >= 5 && b.length >= 5 && (a.startsWith(b) || b.startsWith(a))
        }
    }
}
