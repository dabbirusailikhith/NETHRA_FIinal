package com.nethra.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefParserTest {

    @Test fun fullSpokenBrief() {
        val spoken = "write a script about solar panels for beginners, two minutes, in a friendly tone, for YouTube Shorts, " +
            "mention the 30% tax credit, don't mention brand names, end with subscribe for more"
        val b = BriefParser.parse(spoken)
        assertEquals("solar panels", b.topic)
        assertEquals("beginners", b.audience)
        assertEquals("YouTube Shorts", b.platform)
        assertEquals("friendly", b.tone)
        assertEquals(120, b.durationSeconds)
        assertEquals("the 30% tax credit", b.mustInclude)
        assertEquals("brand names", b.exclude)
        assertEquals("subscribe for more", b.callToAction)
        assertEquals(spoken, b.spokenBrief)
        assertEquals(300, b.targetWords)
    }

    @Test fun durations() {
        assertEquals(90, BriefParser.findDurationSeconds("about 90 seconds long"))
        assertEquals(90, BriefParser.findDurationSeconds("a minute and a half"))
        assertEquals(30, BriefParser.findDurationSeconds("half a minute"))
        assertEquals(300, BriefParser.findDurationSeconds("5 min"))
        assertEquals(45, BriefParser.findDurationSeconds("a 45-second reel"))
        assertEquals(60, BriefParser.findDurationSeconds("one minute"))
        assertNull(BriefParser.findDurationSeconds("3 hours"))
        assertNull(BriefParser.findDurationSeconds("no length given"))
    }

    @Test fun defaultsToOneMinute() {
        val b = BriefParser.parse("make a video about coffee")
        assertEquals("coffee", b.topic)
        assertNull(b.durationSeconds)
        assertEquals(60, b.effectiveSeconds)
        assertEquals(150, b.targetWords)
    }

    @Test fun platformsAndTargets() {
        assertEquals("Instagram Reels", BriefParser.findPlatform("an instagram reel"))
        assertEquals("YouTube", BriefParser.findPlatform("for my youtube channel"))
        assertEquals("", BriefParser.findPlatform("for my blog"))
        assertEquals(20, ScriptBrief.wordsForSeconds(5))
        assertTrue(ScriptBrief().isEmpty)
    }
}
