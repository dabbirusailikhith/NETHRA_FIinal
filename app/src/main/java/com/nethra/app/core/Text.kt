package com.nethra.app.core

import java.text.Normalizer

/** Small text helpers shared by wake-word matching and teleprompter tracking. */
object Text {

    private val COMBINING = Regex("""\p{Mn}+""")

    /** Lower-case word tokens with punctuation removed. Keeps letters of any script and digits. */
    fun tokens(text: String): List<String> = text.lowercase()
        .replace('’', '\'')
        .split(Regex("""[^\p{L}\p{N}\p{M}']+"""))
        .map { it.trim('\'') }
        .filter { it.isNotEmpty() }

    /**
     * Folds Latin accents ("café" → "cafe") so recogniser spelling differences don't break matching.
     *
     * Only Latin text is folded. In Devanagari, Telugu and other Indic scripts the combining
     * marks ARE the vowels, so stripping them would merge different words into one and wreck
     * teleprompter matching; anything outside Latin is left exactly as it came in.
     */
    fun fold(word: String): String {
        if (word.any { it.code > LATIN_MAX }) return word
        return COMBINING.replace(Normalizer.normalize(word, Normalizer.Form.NFD), "")
    }

    /** End of Latin Extended-B: past here a script carries meaning in its marks. */
    private const val LATIN_MAX = 0x024F

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
