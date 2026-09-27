package com.nethra.app.share

import org.json.JSONArray
import org.json.JSONObject

/** Who can see the uploaded video. */
enum class YouTubePrivacy(val apiValue: String, val label: String) {
    PRIVATE("private", "Private"),
    UNLISTED("unlisted", "Unlisted"),
    PUBLIC("public", "Public")
}

/**
 * Turns the publishing kit into the `snippet` + `status` body of a YouTube
 * Data API `videos.insert` call, applying YouTube's own limits so the upload
 * isn't rejected. Pure — unit tested.
 *
 * Limits (YouTube Data API, videos resource):
 *  - title: required, ≤ 100 characters, no `<` or `>`;
 *  - description: ≤ 5000 bytes (UTF-8), no `<` or `>`;
 *  - tags: ≤ 500 characters in total, counting the commas between tags and
 *    the quotes YouTube adds around a tag that contains a space.
 */
object YouTubeMetadata {
    const val TITLE_MAX_CHARS = 100
    const val DESCRIPTION_MAX_BYTES = 5000
    const val TAGS_MAX_CHARS = 500
    /** "People & Blogs" — YouTube's default category for creator uploads. */
    const val DEFAULT_CATEGORY = "22"

    data class Fields(val title: String, val description: String, val tags: List<String>)

    /**
     * @param hashtags appended to the end of the description (YouTube shows the
     *   first three above the title), as the YouTube app does.
     */
    fun fields(title: String, description: String, tags: List<String>, hashtags: List<String>, fallbackTitle: String): Fields {
        val t = clean(title).replace(Regex("""\s+"""), " ").trim().ifBlank { clean(fallbackTitle).trim() }.ifBlank { "NETHRA video" }
        val body = buildString {
            append(clean(description).trim())
            val tagsLine = hashtags.map { clean(it).trim() }.filter { it.startsWith("#") && it.length > 1 }.joinToString(" ")
            if (tagsLine.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append(tagsLine) }
        }
        return Fields(truncateChars(t, TITLE_MAX_CHARS), truncateBytes(body, DESCRIPTION_MAX_BYTES), fitTags(tags))
    }

    /** The JSON body for `videos.insert?part=snippet,status`. */
    fun requestBody(f: Fields, privacy: YouTubePrivacy, madeForKids: Boolean = false): JSONObject = JSONObject()
        .put(
            "snippet", JSONObject()
                .put("title", f.title)
                .put("description", f.description)
                .put("tags", JSONArray(f.tags))
                .put("categoryId", DEFAULT_CATEGORY)
        )
        .put(
            "status", JSONObject()
                .put("privacyStatus", privacy.apiValue)
                .put("selfDeclaredMadeForKids", madeForKids)
                .put("embeddable", true)
        )

    /** Keeps tags in order until YouTube's 500-character budget is used up. */
    fun fitTags(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        var used = 0
        for (r in raw) {
            val tag = clean(r).replace(",", " ").replace("\"", "").replace(Regex("""\s+"""), " ").trim().trimStart('#')
            if (tag.isEmpty() || !seen.add(tag.lowercase())) continue
            val cost = tagCost(tag) + if (out.isEmpty()) 0 else 1
            if (used + cost > TAGS_MAX_CHARS) continue   // a shorter tag later may still fit
            out += tag; used += cost
        }
        return out
    }

    /** Characters YouTube counts for one tag (quotes added when it contains a space). */
    fun tagCost(tag: String) = tag.length + if (tag.contains(' ')) 2 else 0

    fun tagsLength(tags: List<String>) = tags.sumOf { tagCost(it) } + (tags.size - 1).coerceAtLeast(0)

    /** YouTube rejects angle brackets in titles and descriptions; use look-alike quotes. */
    fun clean(s: String) = s.replace('<', '‹').replace('>', '›')

    private fun truncateChars(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max)
        return s.substring(0, end).trimEnd()
    }

    /** Cuts to at most [maxBytes] UTF-8 bytes without splitting a character. */
    fun truncateBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp); bytes += len; i += Character.charCount(cp)
        }
        return sb.toString().trimEnd()
    }

    fun studioUrl(videoId: String) = "https://studio.youtube.com/video/$videoId/edit"
    fun watchUrl(videoId: String) = "https://youtu.be/$videoId"
}

object YouTubeErrors {
    /** Creator-readable text for YouTube API errors. Pure — unit tested. */
    fun explain(code: Int, reason: String, apiMessage: String = ""): String = when (reason) {
        "quotaExceeded", "rateLimitExceeded" ->
            "Your Google Cloud project has used today's YouTube API quota. Uploads work again after it resets (midnight Pacific time)."
        "uploadLimitExceeded" -> "This YouTube channel has hit its upload limit for now. Try again later."
        "youtubeSignupRequired" -> "This Google account doesn't have a YouTube channel yet. Create one in the YouTube app, then upload again."
        "accessNotConfigured" -> "The YouTube Data API v3 isn't enabled in your Google Cloud project (SETUP_GUIDE.md §12)."
        "insufficientPermissions", "forbidden" -> "YouTube refused the upload for this account ($reason). Sign in with the channel's owner account."
        "invalidTitle" -> "YouTube rejected the title. Edit it and try again."
        "invalidDescription" -> "YouTube rejected the description. Edit it and try again."
        "invalidTags" -> "YouTube rejected the tags. Remove unusual characters and try again."
        "invalidCategoryId" -> "YouTube rejected the video category."
        else -> "YouTube upload failed ($code${if (reason.isNotBlank()) " $reason" else ""}). ${apiMessage.take(160)}".trim()
    }
}
