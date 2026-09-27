package com.nethra.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMetadataTest {

    @Test fun titleIsCleanedTrimmedAndLimited() {
        val f = YouTubeMetadata.fields("  Why <cold> showers   work  ", "", emptyList(), emptyList(), "clip.mp4")
        assertEquals("Why ‹cold› showers work", f.title)
        val long = YouTubeMetadata.fields("x".repeat(150), "", emptyList(), emptyList(), "")
        assertEquals(100, long.title.length)
        assertEquals("clip", YouTubeMetadata.fields("  ", "", emptyList(), emptyList(), "clip").title)
        assertEquals("NETHRA video", YouTubeMetadata.fields("", "", emptyList(), emptyList(), "").title)
    }

    @Test fun hashtagsAreAppendedToTheDescription() {
        val f = YouTubeMetadata.fields("T", "Line one.\nLine two.", emptyList(), listOf("#solar", "#energy", "nohash"), "")
        assertEquals("Line one.\nLine two.\n\n#solar #energy", f.description)
        assertEquals("#a", YouTubeMetadata.fields("T", "", emptyList(), listOf("#a"), "").description)
    }

    @Test fun descriptionFitsFiveThousandUtf8Bytes() {
        val hindi = "नमस्ते ".repeat(1000)   // 3 bytes per char → well over 5000 bytes
        val f = YouTubeMetadata.fields("T", hindi, emptyList(), emptyList(), "")
        assertTrue(f.description.toByteArray(Charsets.UTF_8).size <= 5000)
        assertTrue(f.description.startsWith("नमस्ते"))
        assertFalse(f.description.contains('�'))
    }

    @Test fun tagsRespectYouTubesCountingRules() {
        assertEquals(13, YouTubeMetadata.tagCost("solar panel")) // 11 + 2 quotes
        assertEquals(5, YouTubeMetadata.tagCost("solar"))
        val many = (1..200).map { "solar panel tip $it" }
        val fitted = YouTubeMetadata.fitTags(many)
        assertTrue(YouTubeMetadata.tagsLength(fitted) <= 500)
        assertTrue(fitted.size > 10)
        // Duplicates, '#', commas and quotes are cleaned.
        assertEquals(listOf("solar", "rooftop panels", "diy"), YouTubeMetadata.fitTags(listOf("#solar", "Solar", "rooftop, panels", "\"diy\"")))
    }

    @Test fun requestBodyShape() {
        val f = YouTubeMetadata.fields("Title", "Desc", listOf("a", "b c"), listOf("#x"), "")
        val json = YouTubeMetadata.requestBody(f, YouTubePrivacy.UNLISTED)
        val snippet = json.getJSONObject("snippet")
        assertEquals("Title", snippet.getString("title"))
        assertEquals("Desc\n\n#x", snippet.getString("description"))
        assertEquals(2, snippet.getJSONArray("tags").length())
        assertEquals("22", snippet.getString("categoryId"))
        val status = json.getJSONObject("status")
        assertEquals("unlisted", status.getString("privacyStatus"))
        assertFalse(status.getBoolean("selfDeclaredMadeForKids"))
    }
}

class YouTubeErrorsTest {
    @Test fun knownReasonsGetActionableText() {
        assertTrue(YouTubeErrors.explain(403, "quotaExceeded").contains("quota"))
        assertTrue(YouTubeErrors.explain(401, "youtubeSignupRequired").contains("channel"))
        assertTrue(YouTubeErrors.explain(403, "accessNotConfigured").contains("YouTube Data API v3"))
        assertEquals("YouTube upload failed (500). boom", YouTubeErrors.explain(500, "", "boom"))
    }
}
