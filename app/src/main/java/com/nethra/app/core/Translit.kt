package com.nethra.app.core

/**
 * Turns Devanagari (Hindi) and Telugu text into Latin letters, and turns any Latin spelling
 * into a loose "sound key" so different romanisations of the same word compare equal.
 * Pure — unit tested.
 *
 * Why: the speech recogniser returns Hindi in Devanagari and Telugu in Telugu script, but a
 * Hinglish / Tinglish teleprompter script is written in English letters — and people spell
 * those words many ways ("karenge", "karege", "kareinge"). Matching on the sound key of both
 * sides makes all of them line up.
 */
object Translit {

    // ---------------------------------------------------------------- Devanagari
    private val DEV_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऍ' to "e", 'ऑ' to "o"
    )
    private val DEV_CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f", 'य़' to "y"
    )
    private val DEV_MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o"
    )
    private const val DEV_VIRAMA = '्'
    private const val DEV_NUKTA = '\u093C'
    private val NUKTA_FORMS = mapOf('क' to "q", 'ज' to "z", 'फ' to "f", 'ड' to "r", 'ढ' to "rh", 'ग' to "g", 'ख' to "kh")
    private val DEV_NASAL = setOf('ं', 'ँ')
    private const val DEV_VISARGA = 'ः'

    // ---------------------------------------------------------------- Telugu
    private val TEL_VOWELS = mapOf(
        'అ' to "a", 'ఆ' to "aa", 'ఇ' to "i", 'ఈ' to "ee", 'ఉ' to "u", 'ఊ' to "oo", 'ఋ' to "ru",
        'ఎ' to "e", 'ఏ' to "e", 'ఐ' to "ai", 'ఒ' to "o", 'ఓ' to "o", 'ఔ' to "au"
    )
    private val TEL_CONS = mapOf(
        'క' to "k", 'ఖ' to "kh", 'గ' to "g", 'ఘ' to "gh", 'ఙ' to "n",
        'చ' to "ch", 'ఛ' to "chh", 'జ' to "j", 'ఝ' to "jh", 'ఞ' to "n",
        'ట' to "t", 'ఠ' to "th", 'డ' to "d", 'ఢ' to "dh", 'ణ' to "n",
        'త' to "t", 'థ' to "th", 'ద' to "d", 'ధ' to "dh", 'న' to "n",
        'ప' to "p", 'ఫ' to "ph", 'బ' to "b", 'భ' to "bh", 'మ' to "m",
        'య' to "y", 'ర' to "r", 'ఱ' to "r", 'ల' to "l", 'ళ' to "l", 'వ' to "v",
        'శ' to "sh", 'ష' to "sh", 'స' to "s", 'హ' to "h"
    )
    // Tinglish spells the long ē / ō as plain "e" / "o" (నేను = nenu), so they map that way.
    private val TEL_MATRAS = mapOf(
        'ా' to "aa", 'ి' to "i", 'ీ' to "ee", 'ు' to "u", 'ూ' to "oo", 'ృ' to "ru",
        'ె' to "e", 'ే' to "e", 'ై' to "ai", 'ొ' to "o", 'ో' to "o", 'ౌ' to "au"
    )
    private const val TEL_VIRAMA = '్'
    private val TEL_NASAL = setOf('ం', 'ఁ')
    private const val TEL_VISARGA = 'ః'
    private val TEL_LABIALS = setOf('ప', 'ఫ', 'బ', 'భ', 'మ')

    /** Romanises Devanagari and Telugu; everything else (Latin, digits, spaces) passes through. */
    fun toLatin(text: String): String {
        val out = StringBuilder(text.length * 2)
        var pendingA = false   // a consonant was written and still carries its inherent "a"
        fun flush() { if (pendingA) { out.append('a'); pendingA = false } }
        for ((i, c) in text.withIndex()) {
            val cons = DEV_CONS[c] ?: TEL_CONS[c]
            val matra = DEV_MATRAS[c] ?: TEL_MATRAS[c]
            val vowel = DEV_VOWELS[c] ?: TEL_VOWELS[c]
            when {
                cons != null -> { flush(); out.append(cons); pendingA = true }
                matra != null -> { pendingA = false; out.append(matra) }
                c == DEV_VIRAMA || c == TEL_VIRAMA -> pendingA = false
                // Decomposed nukta (ज + ़ — Unicode keeps these decomposed): adjust the consonant just written.
                c == DEV_NUKTA -> {
                    val prev = text.getOrNull(i - 1)
                    val with = NUKTA_FORMS[prev]
                    val base = DEV_CONS[prev]
                    if (with != null && base != null && out.endsWith(base)) out.setLength(out.length - base.length).also { out.append(with) }
                }
                c in DEV_NASAL -> { flush(); out.append('n') }
                // Telugu writes a word-final "m" (మనం = manam) and "m" before p/b/m with the anusvara.
                c in TEL_NASAL -> {
                    flush()
                    val next = text.getOrNull(i + 1)
                    out.append(if (next == null || next !in TEL_CONS || next in TEL_LABIALS) 'm' else 'n')
                }
                c == DEV_VISARGA || c == TEL_VISARGA -> { flush(); out.append('h') }
                vowel != null -> { flush(); out.append(vowel) }
                c in '०'..'९' -> { flush(); out.append('0' + (c - '०')) }
                c in '౦'..'౯' -> { flush(); out.append('0' + (c - '౦')) }
                c == '।' || c == '॥' -> { flush(); out.append('.') }
                else -> { flush(); out.append(c) }
            }
        }
        flush()
        return out.toString()
    }

    /**
     * A loose sound key for one Latin word: long vowels shortened, aspirate "h"s dropped,
     * doubled letters collapsed and the short "a" removed (Hindi and Telugu speakers drop or
     * blur it constantly, and romanisers write it inconsistently). "karenge", "karege" and
     * "kareinge" come out close enough for fuzzy matching; "aaj" and "aj" come out equal.
     */
    fun soundKey(latinWord: String): String {
        var w = latinWord.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return latinWord.lowercase()
        w = w.replace("chh", "ch").replace("sh", "s").replace("ph", "f")
            .replace(Regex("([kgcjtdpbrl])h"), "$1")
            .replace("aa", "a").replace("ee", "i").replace("ii", "i")
            .replace("oo", "u").replace("uu", "u").replace("ei", "e")
            .replace('w', 'v').replace('z', 'j').replace('q', 'k')
        w = w.replace(Regex("(.)\\1+"), "$1")
        // A nasalised final vowel is written with or without its "n": nahin/nahi, hain/hai, mein/mei.
        if (w.length > 3) w = w.replace(Regex("([ie])n$"), "$1")
        // Drop the short "a" except at the start of the word.
        val key = w.first() + w.substring(1).replace("a", "")
        return key.ifEmpty { w }
    }

    /** What the teleprompter matches on for Hindi/Telugu: romanise, then take the sound key. */
    fun matchKey(token: String): String = soundKey(toLatin(token))
}
