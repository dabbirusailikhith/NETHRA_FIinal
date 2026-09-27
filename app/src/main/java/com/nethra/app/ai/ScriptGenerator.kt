package com.nethra.app.ai

import com.nethra.app.config.AiConfig
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.nethra.app.config.PrompterScript
import com.nethra.app.config.ScriptLanguage
import java.time.LocalDate

data class GeneratedScript(
    val title: String,
    /** The script exactly as the creator reads it (Hinglish/Tinglish, native script or English). */
    val body: String,
    val sources: List<Citation>,
    val notes: String,
    val targetWords: Int,
    val researched: Boolean,
    /** True when the model hit its output limit — the script may be cut short. */
    val possiblyIncomplete: Boolean,
    val model: String,
    val language: ScriptLanguage = ScriptLanguage.ENGLISH
) {
    val wordCount: Int get() = ScriptText.countWords(body)
    val estimatedSeconds: Int get() = (wordCount * 60.0 / language.wordsPerMinute).toInt()
    /** Noticeably shorter than requested — shown to the creator, never hidden. */
    val isShort: Boolean get() = wordCount < targetWords * 0.8
}

/**
 * Writes spoken scripts with the configured cloud model. There is no local
 * fallback by design: a failure surfaces as a [NethraException].
 */
class ScriptGenerator(private val client: OpenRouterClient) {

    suspend fun generate(
        brief: ScriptBrief,
        language: ScriptLanguage = ScriptLanguage.ENGLISH,
        script: PrompterScript = PrompterScript.MIXED
    ): GeneratedScript {
        if (brief.isEmpty) throw NethraException(ErrorKind.BAD_RESPONSE, "Tell NETHRA what the script is about first.")
        val messages = JSONArray()
            .put(OpenRouterClient.systemText(SYSTEM_PROMPT))
            .put(OpenRouterClient.userText(userPrompt(brief, language, script)))
        val maxTokens = (brief.targetWords * 4 + 3000).coerceAtLeast(6000)

        var researched = AiConfig.SCRIPT_WEB_RESEARCH
        val result = try {
            client.chat(
                model = AiConfig.SCRIPT_MODEL,
                messages = messages,
                maxTokens = maxTokens,
                temperature = 0.7,
                plugins = if (researched) webPlugin() else null
            )
        } catch (e: NethraException) {
            // A plugin-specific rejection should not block the creator; retry once
            // without research and say so in the result. Other errors propagate.
            if (researched && e.kind == ErrorKind.BAD_RESPONSE) {
                researched = false
                client.chat(AiConfig.SCRIPT_MODEL, messages, maxTokens, 0.7, null)
            } else throw e
        }
        // Regex-heavy parsing of a long reply stays off the main thread too.
        return withContext(Dispatchers.Default) { ScriptResponseParser.parse(
            raw = result.text,
            citations = result.citations,
            targetWords = brief.targetWords,
            researched = researched,
            truncated = result.finishReason == "length",
            model = AiConfig.SCRIPT_MODEL,
            language = language
        ) }
    }

    private fun webPlugin() = JSONArray().put(
        JSONObject()
            .put("id", "web")
            .put("max_results", AiConfig.SCRIPT_WEB_MAX_RESULTS)
            .put(
                "search_prompt",
                "A web search was conducted on ${LocalDate.now()}. Use these results only to check and update " +
                    "factual or time-sensitive claims. Never put URLs, citations or source names inside the spoken " +
                    "script itself; list the pages you relied on in the SOURCES section."
            )
    )

    private fun userPrompt(b: ScriptBrief, language: ScriptLanguage, script: PrompterScript): String = buildString {
        appendLine("Write a complete teleprompter script.")
        languageRule(language, script)?.let { appendLine(); appendLine(it) }
        appendLine()
        appendLine("Creator's spoken brief (verbatim, may contain speech-recognition errors):")
        appendLine("\"${b.spokenBrief.ifBlank { b.topic }}\"")
        appendLine()
        appendLine("Brief as understood (the creator may have edited these):")
        appendLine("- Topic: ${b.topic.ifBlank { "(infer from the spoken brief)" }}")
        appendLine("- Target audience: ${b.audience.ifBlank { "(not specified — general audience for the topic)" }}")
        appendLine("- Platform: ${b.platform.ifBlank { "(not specified — short-form vertical video)" }}")
        appendLine("- Tone: ${b.tone.ifBlank { "(not specified — natural, confident, conversational)" }}")
        val dur = if (b.durationSeconds == null) "${b.effectiveSeconds} seconds (default; creator did not specify)"
        else "${b.effectiveSeconds} seconds"
        appendLine("- Duration: $dur")
        appendLine("- Must include: ${b.mustInclude.ifBlank { "(nothing specific)" }}")
        appendLine("- Must NOT include: ${b.exclude.ifBlank { "(nothing specific)" }}")
        appendLine("- Call to action: ${b.callToAction.ifBlank { "(choose one that fits, or none if it would feel forced)" }}")
        appendLine()
        appendLine(
            "Length: about ${language.wordsForSeconds(b.effectiveSeconds)} spoken words (±10%), which is " +
                "${b.effectiveSeconds} seconds at ${language.wordsPerMinute} words per minute in ${language.promptName}. " +
                "Write the whole script — do not stop early, do not summarise, and do not pad."
        )
    }

    companion object {
        /** The language instruction for the writer, or null for English. Public for tests. */
        fun languageRule(language: ScriptLanguage, script: PrompterScript): String? {
            if (!language.hasScriptChoice) return null
            val lang = language.promptName
            return when (script) {
                PrompterScript.MIXED ->
                    "Write the script in ${language.mixedName}: spoken $lang typed in English (Latin) letters, the way " +
                        "$lang speakers write in chat and in YouTube comments — for example " +
                        (if (language == ScriptLanguage.TELUGU) "\"Nenu ee roju meeku oka simple tip cheppali.\" "
                        else "\"Aaj hum baat karenge ek simple tip ke baare mein.\" ") +
                        "Keep English words that $lang speakers really say in English (brand names, tech terms, " +
                        "everyday English words) spelled normally. Use common, simple spellings. Do NOT use " +
                        "${if (language == ScriptLanguage.TELUGU) "Telugu" else "Devanagari"} script anywhere."
                PrompterScript.NATIVE ->
                    "Write the script in $lang, in its own script. Keep English words the creator would really say " +
                        "(brand names, technical terms) in English letters."
            }
        }

        val SYSTEM_PROMPT = """
            You are NETHRA's scriptwriter for solo video creators. You write the exact words the creator will say to camera while reading a teleprompter.

            Writing rules:
            - Natural spoken language: short sentences, contractions, one idea per sentence, easy to say in one breath.
            - Open with a strong hook in the first sentence. Deliver real, specific value. Close with the call to action if one fits.
            - Match the platform, audience and tone. Respect "must include" and "must NOT include" exactly.
            - Hit the requested word count. Never cut the script short and never pad it with filler.
            - No stage directions, camera notes, headings, bullet points, emojis, hashtags, markdown, brackets or URLs in the script. Only words to be spoken.

            Accuracy rules:
            - Do not invent facts, statistics, prices, dates, quotes, names or studies.
            - If web search results are provided, use them for current or factual claims.
            - If you cannot verify a claim, leave it out or phrase it honestly as uncertain. Say so in NOTES.

            Reply in exactly this format and nothing else:
            TITLE: <a short working title>
            SCRIPT:
            <the full script as plain paragraphs, in the language and letters you were asked for>
            SOURCES:
            - <page title> — <url>   (one per line; write "- none" if you used no sources)
            NOTES:
            <one short line about anything you could not verify, or "none">
        """.trimIndent()
    }
}

/** Parses the TITLE/SCRIPT/SOURCES/NOTES reply. Pure — unit tested. */
object ScriptResponseParser {

    private val SECTION = Regex("""(?im)^\s*\**\s*(TITLE|SCRIPT|SOURCES|NOTES)\s*\**\s*:\s*""")

    fun parse(
        raw: String,
        citations: List<Citation>,
        targetWords: Int,
        researched: Boolean,
        truncated: Boolean,
        model: String,
        language: ScriptLanguage = ScriptLanguage.ENGLISH
    ): GeneratedScript {
        val sections = mutableMapOf<String, String>()
        val matches = SECTION.findAll(raw).toList()
        if (matches.isEmpty()) {
            sections["SCRIPT"] = raw
        } else {
            matches.forEachIndexed { i, m ->
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
                sections.putIfAbsent(m.groupValues[1].uppercase(), raw.substring(m.range.last + 1, end).trim())
            }
        }
        val body = ScriptText.cleanSpoken(sections["SCRIPT"].orEmpty())
        if (ScriptText.countWords(body) < 5) {
            throw NethraException(ErrorKind.BAD_RESPONSE, "The model did not return a usable script. Try again.")
        }

        val sources = citations.toMutableList()
        sections["SOURCES"].orEmpty().lines().forEach { line ->
            val url = Regex("""https?://\S+""").find(line)?.value?.trimEnd(')', '.', ',', ']')
            if (url != null && sources.none { it.url == url }) {
                val title = line.substringBefore(url).trim().trimStart('-', '*', ' ').trimEnd('—', '-', ':', ' ', '(', '[')
                sources += Citation(url, title.ifBlank { url })
            }
        }
        val notes = sections["NOTES"].orEmpty().trim().let { if (it.equals("none", true) || it.equals("- none", true)) "" else it }

        return GeneratedScript(
            title = sections["TITLE"].orEmpty().lineSequence().firstOrNull().orEmpty().trim().trim('"', '*'),
            body = body,
            sources = sources,
            notes = notes,
            targetWords = targetWords,
            researched = researched,
            possiblyIncomplete = truncated,
            language = language,
            model = model
        )
    }
}

object ScriptText {
    fun countWords(text: String): Int = text.split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }

    /** Strips markdown, links and stage directions the model was asked not to write. */
    fun cleanSpoken(text: String): String = text
        .replace(Regex("""\[([^\]]+)]\((https?://[^)]+)\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""(?m)^\s*[\[(][^\])]{0,60}[\])]\s*$"""), "")
        .replace(Regex("""\*\*|__|(?m)^#+\s*|(?m)^>\s*|`"""), "")
        .replace(Regex("""(?m)^\s*[-*•]\s+"""), "")
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
