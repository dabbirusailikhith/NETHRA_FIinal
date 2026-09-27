package com.nethra.app.ai

import com.nethra.app.ai.local.LocalGemma
import com.nethra.app.config.AiConfig
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeKit(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val hashtags: List<String> = emptyList()
)

data class InstagramKit(
    val caption: String = "",
    val hashtags: List<String> = emptyList()
)

data class PublishKit(
    val youtube: YouTubeKit = YouTubeKit(),
    val instagram: InstagramKit = InstagramKit(),
    /** Which model wrote it, e.g. "google/gemini-3.8-flash" or "Gemma (on-device)". */
    val source: String = ""
) {
    /** Text copied to the clipboard before handing off to YouTube. */
    fun youtubeClipboard(): String = buildString {
        appendLine(youtube.title); appendLine()
        appendLine(youtube.description)
        if (youtube.hashtags.isNotEmpty()) { appendLine(); appendLine(youtube.hashtags.joinToString(" ")) }
        if (youtube.tags.isNotEmpty()) { appendLine(); append("Tags: "); append(youtube.tags.joinToString(", ")) }
    }.trim()

    fun instagramClipboard(): String = buildString {
        append(instagram.caption)
        if (instagram.hashtags.isNotEmpty()) { append("\n\n"); append(instagram.hashtags.joinToString(" ")) }
    }.trim()

    companion object {
        const val YT_TITLE_MAX = 100
        const val YT_DESCRIPTION_MAX = 5000
        const val YT_TAGS_MAX_CHARS = 500
        const val IG_CAPTION_MAX = 2200
        const val IG_HASHTAGS_MAX = 30
    }
}

/** Writes the YouTube / Instagram kit from a transcript. */
class PublishKitGenerator(
    private val client: OpenRouterClient,
    private val gemma: LocalGemma
) {
    /** Cloud model (the default). Throws [NethraException] — callers may then offer [generateLocal]. */
    suspend fun generateCloud(transcript: String): PublishKit {
        requireTranscript(transcript)
        val messages = JSONArray()
            .put(OpenRouterClient.systemText(SYSTEM_PROMPT))
            .put(OpenRouterClient.userText(userPrompt(transcript)))
        val result = client.chat(AiConfig.PUBLISH_KIT_MODEL, messages, maxTokens = 4000, temperature = 0.6)
        return PublishKitParser.parse(result.text, AiConfig.PUBLISH_KIT_MODEL)
    }

    /** Optional on-device Gemma. Long transcripts are truncated to fit Gemma's context — the UI says so. */
    suspend fun generateLocal(transcript: String): PublishKit {
        requireTranscript(transcript)
        val clipped = transcript.take(AiConfig.LOCAL_TRANSCRIPT_CHAR_LIMIT)
        val raw = gemma.generate(SYSTEM_PROMPT, userPrompt(clipped))
        return PublishKitParser.parse(raw, "Gemma (on-device)")
    }

    private fun requireTranscript(t: String) {
        if (t.isBlank()) throw NethraException(ErrorKind.NO_SPEECH, "There's no transcript to build a publishing kit from.")
    }

    private fun userPrompt(transcript: String) = buildString {
        appendLine("Create a publishing kit for this video from its transcript.")
        appendLine("Write the kit in the same language as the transcript.")
        appendLine()
        appendLine("TRANSCRIPT START")
        appendLine(transcript)
        append("TRANSCRIPT END")
    }

    companion object {
        val SYSTEM_PROMPT = """
            You write publishing text for a creator's video, based only on its transcript.
            - Do not invent facts, claims, links, names, prices or dates that are not in the transcript.
            - YouTube title: specific and compelling, at most 100 characters, no clickbait that the video doesn't deliver.
            - YouTube description: 2 to 4 short paragraphs summarising what the viewer gets, at most 1500 characters. No links unless they are in the transcript.
            - YouTube tags: 10 to 15 search phrases, no # symbol, total under 450 characters.
            - YouTube hashtags: 3 to 5, each starting with #.
            - Instagram caption: a hook first line, 2 to 4 short lines, a call to action if the video has one, at most 1200 characters, no hashtags inside it.
            - Instagram hashtags: 8 to 15, each starting with #, relevant and not spammy.
            Reply with ONLY this JSON object, no markdown fences:
            {"youtube":{"title":"","description":"","tags":[""],"hashtags":["#"]},"instagram":{"caption":"","hashtags":["#"]}}
        """.trimIndent()
    }
}

/** Lenient parser for the kit JSON. Pure — unit tested. */
object PublishKitParser {

    fun parse(raw: String, source: String): PublishKit {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw NethraException(ErrorKind.BAD_RESPONSE, "The model didn't return a publishing kit. Try again.")
        val json = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw NethraException(ErrorKind.BAD_RESPONSE, "The publishing kit came back malformed. Try again.", e)
        }
        val yt = json.optJSONObject("youtube") ?: JSONObject()
        val ig = json.optJSONObject("instagram") ?: JSONObject()
        val kit = PublishKit(
            youtube = YouTubeKit(
                title = yt.optString("title").trim().take(PublishKit.YT_TITLE_MAX),
                description = yt.optString("description").trim().take(PublishKit.YT_DESCRIPTION_MAX),
                tags = normaliseTags(list(yt, "tags")),
                hashtags = normaliseHashtags(list(yt, "hashtags")).take(15)
            ),
            instagram = InstagramKit(
                caption = ig.optString("caption").trim().take(PublishKit.IG_CAPTION_MAX),
                hashtags = normaliseHashtags(list(ig, "hashtags")).take(PublishKit.IG_HASHTAGS_MAX)
            ),
            source = source
        )
        if (kit.youtube.title.isBlank() && kit.instagram.caption.isBlank()) {
            throw NethraException(ErrorKind.BAD_RESPONSE, "The publishing kit came back empty. Try again.")
        }
        return kit
    }

    private fun list(obj: JSONObject, key: String): List<String> = when (val v = obj.opt(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf(String::isNotBlank) }
        is String -> v.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    /** Tags: no '#', trimmed, de-duplicated, total within YouTube's 500-character limit. */
    fun normaliseTags(raw: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for (t in raw) {
            val tag = t.trim().trimStart('#').replace(Regex("""[<>,]"""), "").replace(Regex("""\s+"""), " ").trim()
            if (tag.isEmpty() || out.containsKey(tag.lowercase())) continue
            val cost = tag.length + (if (out.isEmpty()) 0 else 1)
            if (total + cost > PublishKit.YT_TAGS_MAX_CHARS) break
            out[tag.lowercase()] = tag; total += cost
        }
        return out.values.toList()
    }

    /** Hashtags: single token starting with '#', no punctuation, de-duplicated. */
    fun normaliseHashtags(raw: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        for (h in raw.flatMap { it.split(Regex("""\s+""")) }) {
            val body = h.trim().trimStart('#').filter { it.isLetterOrDigit() || it == '_' }
            if (body.isEmpty() || body.all(Char::isDigit)) continue
            out.putIfAbsent(body.lowercase(), "#$body")
        }
        return out.values.toList()
    }
}
