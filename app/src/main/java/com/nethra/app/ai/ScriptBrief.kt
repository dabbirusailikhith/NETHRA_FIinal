package com.nethra.app.ai

import com.nethra.app.config.AiConfig

/** Everything the creator told NETHRA about the script they want. */
data class ScriptBrief(
    val topic: String = "",
    val audience: String = "",
    val platform: String = "",
    val tone: String = "",
    /** Null means "not specified" → [AiConfig.DEFAULT_SCRIPT_SECONDS]. */
    val durationSeconds: Int? = null,
    val mustInclude: String = "",
    val exclude: String = "",
    val callToAction: String = "",
    /** The creator's words exactly as recognised, sent to the model verbatim. */
    val spokenBrief: String = ""
) {
    val effectiveSeconds: Int get() = durationSeconds ?: AiConfig.DEFAULT_SCRIPT_SECONDS
    val targetWords: Int get() = wordsForSeconds(effectiveSeconds)
    val isEmpty: Boolean get() = topic.isBlank() && spokenBrief.isBlank()

    companion object {
        fun wordsForSeconds(seconds: Int): Int =
            (seconds * AiConfig.SPOKEN_WORDS_PER_MINUTE / 60.0).toInt().coerceAtLeast(20)
    }
}

/**
 * Pulls structured fields out of a spoken brief. It only needs to be roughly
 * right: the verbatim brief is also sent to the script model, and the creator
 * can edit every field before generating.
 */
object BriefParser {

    private val NUMBER_WORDS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0, "forty-five" to 45.0,
        "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0, "half" to 0.5
    )

    private val LEAD_IN = Regex(
        """^(?:please\s+)?(?:can you\s+|could you\s+)?(?:write|make|create|generate|draft)\s+(?:me\s+)?(?:a\s+|an\s+)?(?:new\s+)?(?:video\s+)?(?:script|video)\s*(?:about|on|for|regarding)?\s*""",
        RegexOption.IGNORE_CASE
    )

    // Clause starters that end the topic phrase.
    private val CLAUSE_BREAK = Regex(
        """\s*(?:[,.;]|\bfor (?:an? |the )?(?:audience|people|viewers|beginners|students|kids|parents|developers|creators|youtube|instagram|reels|shorts|tiktok)|\bfor\s+\d|\btargeting\b|\baimed at\b|\bin an? \w+(?: and \w+)? tone\b|\bwith an? \w+(?: and \w+)? tone\b|\btone\b|\b(?:about |around |roughly |approximately )?\d+(?:\.\d+)?\s*(?:-|\s)?(?:minutes?|mins?|seconds?|secs?)\b|\b(?:one|two|three|four|five|half an?|a|an)\s+(?:minutes?|min)\b|\bmention\b|\binclude\b|\bdon'?t\b|\bdo not\b|\bavoid\b|\bwithout\b|\bcall to action\b|\bend with\b|\bask (?:them|viewers|people|the audience)\b|\bon (?:youtube|instagram|tiktok)\b)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(spoken: String): ScriptBrief {
        val text = spoken.trim()
        val lower = text.lowercase()
        val body = LEAD_IN.replace(text, "").trim()
        val topic = CLAUSE_BREAK.find(body)?.let { body.substring(0, it.range.first) } ?: body
        return ScriptBrief(
            topic = topic.trim().trim(',', '.', ' ').ifBlank { body.take(120) },
            audience = findAudience(lower),
            platform = findPlatform(lower),
            tone = findTone(lower),
            durationSeconds = findDurationSeconds(lower),
            mustInclude = capture(lower, """\b(?:mention|include|cover|talk about)\s+(.+?)(?=[,.;]|\s+(?:and )?(?:don'?t|do not|avoid|end with|call to action)\b|$)"""),
            exclude = capture(lower, """\b(?:don'?t|do not|avoid|never)\s+(?:mention|include|talk about|say|use)?\s*(.+?)(?=[,.;]|\s+(?:and )?(?:end with|call to action|mention|include)\b|$)"""),
            callToAction = capture(lower, """\b(?:call to action(?: is| should be)?|cta(?: is)?|end with|ask (?:them|viewers|people|the audience) to)\s*:?\s*(.+?)(?=[,.;]|$)"""),
            spokenBrief = text
        )
    }

    fun findDurationSeconds(lower: String): Int? {
        Regex("""a minute and a half|one and a half minutes?|1\.5 minutes?""").find(lower)?.let { return 90 }
        Regex("""half (?:a|an) minute""").find(lower)?.let { return 30 }
        Regex("""(\d+(?:\.\d+)?|[a-z-]+)\s*(?:-|\s)?(minutes?|mins?|seconds?|secs?)\b""").findAll(lower).forEach { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@forEach
            val unit = m.groupValues[2]
            val secs = if (unit.startsWith("min")) n * 60 else n
            if (secs in 10.0..1800.0) return secs.toInt()
        }
        return null
    }

    fun findPlatform(lower: String): String = when {
        "shorts" in lower || "youtube short" in lower -> "YouTube Shorts"
        "reel" in lower -> "Instagram Reels"
        "instagram" in lower -> "Instagram"
        "tiktok" in lower || "tik tok" in lower -> "TikTok"
        "youtube" in lower -> "YouTube"
        "linkedin" in lower -> "LinkedIn"
        else -> ""
    }

    private val TONES = listOf(
        "funny", "humorous", "casual", "professional", "energetic", "inspiring", "inspirational",
        "serious", "friendly", "calm", "dramatic", "motivational", "educational", "conversational",
        "witty", "emotional", "formal", "playful", "confident", "warm"
    )

    fun findTone(lower: String): String {
        Regex("""\b(?:in an?|with an?|keep it|make it)\s+([a-z]+(?:\s+and\s+[a-z]+)?)\s+(?:tone|voice|style|vibe)""").find(lower)?.let {
            return it.groupValues[1]
        }
        Regex("""\btone\s+(?:is|should be|:)?\s*([a-z]+(?:\s+and\s+[a-z]+)?)""").find(lower)?.let {
            return it.groupValues[1]
        }
        return TONES.filter { Regex("""\b$it\b""").containsMatchIn(lower) }.take(2).joinToString(" and ")
    }

    fun findAudience(lower: String): String = capture(
        lower,
        """\b(?:for|targeting|aimed at|audience is|audience of|target audience is)\s+((?:an? |the )?(?:[a-z-]+\s+){0,4}?(?:beginners|students|kids|children|parents|developers|creators|founders|people|viewers|professionals|teens|teenagers|women|men|moms|dads|fans|gamers|engineers|marketers|entrepreneurs|designers|audience|users|learners|travellers|travelers))\b"""
    )

    private fun capture(lower: String, pattern: String): String =
        Regex(pattern).find(lower)?.groupValues?.get(1)?.trim()?.trim(',', '.') ?: ""
}
