package com.nethra.app.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionBuilderTest {

    @Test fun parsesTimedJsonLeniently() {
        val raw = """```json
            {"segments":[{"start":0.5,"end":2.0,"text":"Hello everyone [inaudible] and welcome"},
                         {"start":"0:02.4","end":"0:04","text":"Today we talk solar"},
                         {"start":5,"text":"no end time here"}, {"text":"no start"}]}```"""
        val s = CaptionBuilder.parseSegments(raw, offsetMs = 10_000, clipMs = 60_000)
        assertEquals(3, s.size)
        assertEquals(TimedSegment(10_500, 12_000, "Hello everyone and welcome"), s[0])
        assertEquals(12_400, s[1].startMs); assertEquals(14_000, s[1].endMs)
        assertTrue(s[2].endMs > s[2].startMs)
    }

    @Test fun parsesBareArray() {
        val s = CaptionBuilder.parseSegments("""[{"s":1,"e":2,"t":"hi there"}]""", 0, 10_000)
        assertEquals(listOf(TimedSegment(1000, 2000, "hi there")), s)
    }

    @Test fun snapsToRealSpeech() {
        // Speech from 1.00 s to 2.40 s; the model said 0.8–2.1 s.
        val speech = BooleanArray(200) { it in 50 until 120 }
        val out = CaptionBuilder.snapToSpeech(listOf(TimedSegment(800, 2100, "a b c")), speech)
        assertEquals(1000, out[0].startMs)
        assertEquals(2400, out[0].endMs)
    }

    @Test fun keepsOrderAndMinimumLength() {
        val out = CaptionBuilder.snapToSpeech(
            listOf(TimedSegment(1000, 1100, "a"), TimedSegment(1050, 3000, "b")), BooleanArray(0)
        )
        assertTrue(out[0].endMs - out[0].startMs >= CaptionBuilder.MIN_CARD_MS)
        assertTrue(out[1].startMs >= out[0].endMs)
    }

    @Test fun cardsAreShortAndBreakAtPunctuation() {
        val seg = TimedSegment(0, 6000, "Hello everyone, today we are talking about solar panels on rooftops.")
        val cards = CaptionBuilder.cards(listOf(seg))
        assertEquals("Hello everyone,", cards[0].text)
        assertTrue(cards.all { it.words.size <= CaptionBuilder.MAX_WORDS })
        assertTrue(cards.all { it.text.length <= CaptionBuilder.MAX_CHARS + 12 })
        assertEquals(seg.text, cards.joinToString(" ") { it.text })
        // Word timings are continuous and inside the segment.
        val words = cards.flatMap { it.words }
        assertEquals(0L, words.first().startMs)
        assertTrue(words.last().endMs in 5990..6000)
        words.zipWithNext().forEach { (a, b) -> assertTrue(b.startMs >= a.startMs) }
    }

    @Test fun lookupFindsCardAndActiveWord() {
        val cards = CaptionBuilder.cards(listOf(TimedSegment(1000, 2000, "one two"), TimedSegment(5000, 6000, "three four")))
        assertNull(CaptionBuilder.at(cards, 500))
        assertEquals(0 to 0, CaptionBuilder.at(cards, 1100))
        assertEquals(0 to 1, CaptionBuilder.at(cards, 1900))
        assertEquals(0 to 1, CaptionBuilder.at(cards, 2500))          // held briefly after the card ends
        assertNull(CaptionBuilder.at(cards, 3500))                   // then gone
        assertEquals(1 to 0, CaptionBuilder.at(cards, 5100))
    }

    @Test fun fallbackSpreadsWordsOverSpeech() {
        val speech = BooleanArray(300) { it in 25 until 100 || it in 200 until 250 }   // 1.5 s + 1.0 s of speech
        val segs = CaptionBuilder.fromText("a b c d e f g h i j", speech, 0, 6000)
        assertEquals(2, segs.size)
        assertEquals(6, segs[0].text.split(' ').size)
        assertTrue(segs[1].startMs >= 4000)
    }

    @Test fun dropsSpokenCommands() {
        val segs = listOf(TimedSegment(0, 1000, "Welcome back"), TimedSegment(1000, 2000, "Nethra, pause"), TimedSegment(3000, 4000, "Nethra"))
        assertEquals(listOf("Welcome back"), CaptionBuilder.dropCommands(segs).map { it.text })
    }

    @Test fun srtFormat() {
        val srt = CaptionBuilder.toSrt(CaptionBuilder.cards(listOf(TimedSegment(1234, 3000, "Hi there"))))
        assertTrue(srt.startsWith("1\n00:00:01,234 --> 00:00:03,000\nHi there"))
    }
}
