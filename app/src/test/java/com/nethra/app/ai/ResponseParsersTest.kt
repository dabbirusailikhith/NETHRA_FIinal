package com.nethra.app.ai

import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponseParsersTest {

    private val raw = listOf(
        "TITLE: Solar in 60 seconds",
        "SCRIPT:",
        "**Solar panels** turn sunlight into electricity.",
        "[Pause]",
        "They work even on cloudy days, just less well.",
        "SOURCES:",
        "- Energy.gov — https://www.energy.gov/solar",
        "- Already cited https://a.example/x",
        "NOTES: none"
    ).joinToString("\n")

    @Test fun scriptSections() {
        val s = ScriptResponseParser.parse(raw, listOf(Citation("https://a.example/x", "A")), 150, true, false, "m")
        assertEquals("Solar in 60 seconds", s.title)
        assertTrue(s.body.startsWith("Solar panels turn sunlight"))
        assertFalse(s.body.contains("**"))
        assertFalse(s.body.contains("[Pause]"))
        assertEquals(
            listOf(Citation("https://a.example/x", "A"), Citation("https://www.energy.gov/solar", "Energy.gov")),
            s.sources
        )
        assertEquals("", s.notes)
        assertEquals(15, s.wordCount)
        assertTrue(s.isShort)
        assertTrue(s.researched)
        assertFalse(s.possiblyIncomplete)
    }

    @Test fun unsectionedReplyIsTheScript() {
        val s = ScriptResponseParser.parse("Just a plain script with enough words in it.", emptyList(), 10, false, true, "m")
        assertEquals("Just a plain script with enough words in it.", s.body)
        assertTrue(s.possiblyIncomplete)
    }

    @Test fun tooShortIsAnError() {
        try {
            ScriptResponseParser.parse("TITLE: x\nSCRIPT: hi", emptyList(), 150, false, false, "m")
            fail("expected an exception")
        } catch (e: NethraException) {
            assertEquals(ErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    @Test fun publishKit() {
        val json = "{\"youtube\":{\"title\":\"T\",\"description\":\"D\",\"tags\":[\"solar\",\"#Solar\",\"energy, tips\"]," +
            "\"hashtags\":\"#solar #energy #solar\"},\"instagram\":{\"caption\":\"C\",\"hashtags\":[\"solar\",\"#green-energy\",\"#2024\"]}}"
        val kit = PublishKitParser.parse("Here you go:\n```json\n$json\n```", "model-x")
        assertEquals("T", kit.youtube.title)
        assertEquals("D", kit.youtube.description)
        assertEquals(listOf("solar", "energy tips"), kit.youtube.tags)
        assertEquals(listOf("#solar", "#energy"), kit.youtube.hashtags)
        assertEquals("C", kit.instagram.caption)
        assertEquals(listOf("#solar", "#greenenergy"), kit.instagram.hashtags)
        assertEquals("model-x", kit.source)
    }

    @Test fun publishKitErrors() {
        for (bad in listOf("no json here", "{not json}", "{\"youtube\":{},\"instagram\":{}}")) {
            try {
                PublishKitParser.parse(bad, "m")
                fail("expected an exception for $bad")
            } catch (e: NethraException) {
                assertEquals(ErrorKind.BAD_RESPONSE, e.kind)
            }
        }
    }

    @Test fun tagsStayWithinYouTubeLimit() {
        val tags = PublishKitParser.normaliseTags((0 until 60).map { "tag-%06d".format(it) })
        assertTrue(tags.size < 60)
        assertTrue(tags.sumOf { it.length } + tags.size - 1 <= PublishKit.YT_TAGS_MAX_CHARS)
    }
}
