package com.nethra.app.config

/**
 * The language the creator speaks, and everything that changes with it.
 *
 * Hindi and Telugu creators rarely read their own script on a prompter: they read
 * **Hinglish / Tinglish** — the language typed in English letters, English words kept as
 * they are ("Aaj hum baat karenge camera settings ke baare mein"). So that is the default
 * teleprompter script ([PrompterScript.MIXED]); the recogniser still answers in Devanagari
 * or Telugu, and [com.nethra.app.core.Translit] matches the two by sound.
 *
 * [syllablesPerWord] and [wordsPerMinute] are starting values, not measured
 * ones: Telugu is agglutinative and packs far more syllables into a word than
 * English, so a shared constant made the prompter race. Tune them on real
 * recordings — they are the only numbers here that affect scroll speed.
 */
enum class ScriptLanguage(
    val code: String,
    /** Shown in the settings picker, in the language's own script. */
    val label: String,
    /** Locale handed to the speech recogniser. */
    val localeTag: String,
    /** How the language is named to the writing and transcription models. */
    val promptName: String,
    /** True when the language has its own non-Latin script (so script and caption choices apply). */
    val needsRomanisation: Boolean,
    /** The code-mixed, English-letters form creators type in chat: "Hinglish", "Tinglish". */
    val mixedName: String,
    /** Average syllables per spoken word — converts a measured syllable rate into words per minute. */
    val syllablesPerWord: Float,
    /** Comfortable speaking pace, used to turn a requested duration into a word target. */
    val wordsPerMinute: Int
) {
    ENGLISH("en", "English", "en-IN", "English", false, "English", 1.5f, 150),
    HINDI("hi", "हिन्दी", "hi-IN", "Hindi", true, "Hinglish", 2.1f, 120),
    TELUGU("te", "తెలుగు", "te-IN", "Telugu", true, "Tinglish", 2.9f, 105);

    /** Whether a separate script/caption choice exists for this language. */
    val hasScriptChoice: Boolean get() = needsRomanisation

    /** Words for a spoken duration at this language's pace. */
    fun wordsForSeconds(seconds: Int): Int =
        (seconds * wordsPerMinute / 60.0).toInt().coerceAtLeast(20)

    companion object {
        fun of(code: String?): ScriptLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/** How the teleprompter script is written for Hindi / Telugu. */
enum class PrompterScript(val code: String) {
    /** Hinglish / Tinglish: the language in English letters, English words kept (default). */
    MIXED("mixed"),
    /** The language's own script: Devanagari or Telugu. */
    NATIVE("native");

    fun label(l: ScriptLanguage): String = when (this) {
        MIXED -> l.mixedName
        NATIVE -> l.label
    }

    companion object {
        fun of(code: String?): PrompterScript = entries.firstOrNull { it.code == code } ?: MIXED
    }
}

/** What the burned-in captions say when the creator speaks Hindi / Telugu. */
enum class CaptionScript(val code: String) {
    /** Exactly as spoken, in the language's own script (English words stay in English). */
    ORIGINAL("original"),
    /** Translated into natural English, timed to the original speech. */
    TRANSLATED("translated"),
    /** As spoken, in Hinglish / Tinglish (English letters). */
    MIXED("mixed");

    fun label(l: ScriptLanguage): String = when (this) {
        ORIGINAL -> l.label
        TRANSLATED -> "English"
        MIXED -> l.mixedName
    }

    companion object {
        fun of(code: String?): CaptionScript = entries.firstOrNull { it.code == code } ?: MIXED
    }
}
