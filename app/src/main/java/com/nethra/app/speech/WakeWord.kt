package com.nethra.app.speech

import com.nethra.app.core.Text

enum class VoiceCommand { START_RECORDING, PAUSE, RESUME, STOP }

sealed interface Utterance {
    /** No wake word — ignored unless NETHRA is waiting for a follow-up. */
    data class Plain(val text: String) : Utterance
    /** "Nethra" alone (or followed by something that isn't a command). [rest] may be a brief. */
    data class Wake(val rest: String) : Utterance
    data class Command(val command: VoiceCommand) : Utterance
    /** "Nethra, write a script about ..." — [brief] is everything after the wake word. */
    data class ScriptRequest(val brief: String) : Utterance
}

/**
 * Parses recogniser text for the wake word "Nethra" and the few commands NETHRA
 * understands. Recognisers spell unfamiliar names many ways ("netra", "nitra",
 * "net ra"...), so matching is fuzzy. Pure — unit tested.
 */
object WakeWord {

    private const val WAKE = "nethra"
    private val KNOWN_SPELLINGS = setOf(
        "nethra", "netra", "nitra", "nithra", "nethera", "neethra", "neetra", "nethraa", "netraa",
        "natra", "nathra", "nehtra", "netrah", "nethrah", "nedra", "nettra", "neytra", "naitra",
        "netro", "nethro", "nether", "nehra", "neitra", "nayathra", "nethrā",
        "नेत्रा", "नेथरा", "நேத்ரா", "నేత్ర", "ನೇತ್ರ"
    )

    /** Index range [first, last] of the tokens that make up the wake word, or null. */
    fun findWake(tokens: List<String>): IntRange? {
        for (i in tokens.indices) {
            if (isWakeToken(tokens[i])) return i..i
            if (i + 1 < tokens.size && isWakeToken(tokens[i] + tokens[i + 1]) && tokens[i].length <= 4) return i..(i + 1)
        }
        return null
    }

    fun isWakeToken(token: String): Boolean {
        // "Nethra's start recording" — recognisers like to add a possessive.
        val t = token.lowercase().trim { !it.isLetterOrDigit() }.removeSuffix("'s").removeSuffix("s'")
        if (t in KNOWN_SPELLINGS) return true
        if (t.length !in 4..8 || !t.startsWith("n")) return false
        return Text.levenshtein(t, WAKE) <= 2 && Text.levenshtein(t, "netra") <= 2
    }

    fun parse(text: String): Utterance {
        val tokens = Text.tokens(text)
        val wake = findWake(tokens) ?: return Utterance.Plain(text.trim())
        val rest = tokens.drop(wake.last + 1)
        commandIn(rest)?.let { return Utterance.Command(it) }
        val restText = restOfOriginal(text, rest.size)
        return if (isScriptRequest(rest)) Utterance.ScriptRequest(restText) else Utterance.Wake(restText)
    }

    /**
     * Recognises a command at the start of [tokens] (the words after the wake
     * word, or a follow-up utterance after "Nethra" alone). Only the first few
     * words count, so a sentence that merely contains "stop" is not a command.
     */
    fun commandIn(tokens: List<String>): VoiceCommand? {
        // "please stop", "ok pause": skip polite/filler words before the command.
        val head = tokens.map { it.lowercase() }.dropWhile { it in FILLERS }.take(4).map(::normaliseCommandWord)
        if (head.isEmpty()) return null
        val first = head[0]
        val joined = head.joinToString(" ")
        return when {
            first in PAUSE_WORDS -> VoiceCommand.PAUSE
            first in RESUME_WORDS || joined.startsWith("carry on") || joined.startsWith("go on") -> VoiceCommand.RESUME
            first in STOP_WORDS || first == "end" && head.getOrNull(1)?.startsWith("record") == true ||
                joined.startsWith("finish recording") || joined.startsWith("cut") -> VoiceCommand.STOP
            first in NATIVE_START_WORDS ||
            first in setOf("record", "recording", "action", "roll") ||
                (first in START_WORDS && head.getOrNull(1)?.startsWith("record") == true) ||
                (first in setOf("start", "begin", "starts", "started") &&
                    (head.size == 1 || head[1].startsWith("record") || head[1] in setOf("the", "a", "video", "now"))) ->
                VoiceCommand.START_RECORDING
            else -> null
        }
    }

    /**
     * Commands that are unambiguous even without the wake word — the recogniser often
     * mishears the unusual name "Nethra" but gets "stop recording" right. Only a short
     * utterance of the form "<verb> recording" (or "<verb> the recording/video") counts,
     * so ordinary speech containing "stop" or "start" never triggers anything.
     */
    fun bareCommand(text: String): VoiceCommand? {
        val tokens = Text.tokens(text).map { it.lowercase() }.dropWhile { it in FILLERS }
        if (tokens.size !in 2..4) return null
        if (tokens.none { normaliseCommandWord(it).startsWith("record") || it == "video" }) return null
        val cmd = commandIn(tokens) ?: return null
        // The verb must be the first word (after fillers), and "record" must follow it.
        return if (tokens.size <= 4 && normaliseCommandWord(tokens[1]).let { it.startsWith("record") || it in setOf("the", "video") }) cmd else null
    }

    /** Maps near-misses of command words onto the word itself ("resumed" → "resume", "recordin" → "recording"). */
    fun normaliseCommandWord(w: String): String {
        if (w.length < 4) return w
        for (c in COMMAND_WORDS) if (w == c || (Text.levenshtein(w, c) <= 1 && w[0] == c[0])) return c
        return w
    }

    /**
     * Stricter, low-latency check for *partial* recogniser results, so a command
     * can act before the recogniser decides the utterance is over (≈1 s sooner).
     * Only returns a command when the words so far can't be the start of
     * something else: "Nethra start" alone might become "Nethra start writing…",
     * so START needs a "record…" word here; the final result still catches the rest.
     */
    fun fastCommand(partial: String): VoiceCommand? {
        val tokens = Text.tokens(partial)
        val wake = findWake(tokens) ?: return null
        val rest = tokens.drop(wake.last + 1)
        if (rest.isEmpty() || rest.size > 3) return null
        val cmd = commandIn(rest) ?: return null
        if (cmd == VoiceCommand.START_RECORDING && rest.none { it.startsWith("record") } && rest[0] != "action") return null
        return cmd
    }

    private val FILLERS = setOf("ok", "okay", "please", "hey", "now", "so", "and", "um", "uh", "hmm")
    private val COMMAND_WORDS = listOf("pause", "resume", "continue", "stop", "start", "record", "recording", "finish", "begin")
    // Hindi (Devanagari) and Telugu command words, for creators who say the command in their own
    // language. Commands in English keep working either way. Worth a native speaker's review.
    private val PAUSE_WORDS = setOf(
        "pause", "pose", "paws", "pos", "pours", "paused", "pausing", "hold",
        "रुको", "रुक", "ठहरो", "ఆపు", "ఆగు"
    )
    private val RESUME_WORDS = setOf(
        "resume", "resumed", "resuming", "presume", "rezoom", "continue", "unpause",
        "जारी", "चालू", "కొనసాగు", "కొనసాగించు"
    )
    private val STOP_WORDS = setOf(
        "stop", "stopped", "stopping", "stops",
        "बंद", "खत्म", "ముగించు", "బంద్"
    )
    /** "Start recording" in Hindi and Telugu — these stand alone, unlike the English "start". */
    private val NATIVE_START_WORDS = setOf("शुरू", "रिकॉर्ड", "మొదలు", "ప్రారంభించు", "మొదలుపెట్టు")
    /** Mis-hearings of "start" that only count when followed by "record…". */
    private val START_WORDS = setOf("star", "stat", "stuart", "startup", "tart")

    private fun isScriptRequest(rest: List<String>): Boolean {
        val head = rest.take(6).map { it.lowercase() }
        val verbs = setOf("write", "make", "create", "generate", "draft", "prepare", "give")
        return head.any { it in verbs } && head.any { it.startsWith("script") }
    }

    /** Returns the tail of the original text that corresponds to the last [count] tokens (keeps punctuation/case). */
    private fun restOfOriginal(text: String, count: Int): String {
        if (count == 0) return ""
        val words = text.trim().split(Regex("""\s+"""))
        // tokens() drops punctuation-only words; walk back from the end counting real words.
        var seen = 0
        var idx = words.size
        while (idx > 0 && seen < count) {
            idx--
            if (words[idx].any(Char::isLetterOrDigit)) seen++
        }
        return words.drop(idx).joinToString(" ").trim().trimStart(',', '.', ':', ';', '-', ' ')
    }
}
