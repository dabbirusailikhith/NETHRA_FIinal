package com.nethra.app.teleprompter

import com.nethra.app.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTrackerTest {

    private val script = "Hello everyone and welcome back. Today we talk about solar panels on rooftops. " +
        "They save money every single month."

    @Test fun splitsIntoLines() {
        val t = ScriptTracker(script)
        assertEquals(3, t.lines.size)
        assertEquals(19, t.wordCount)
        assertEquals(0, t.currentLine)
    }

    @Test fun followsSpeechForward() {
        val t = ScriptTracker(script)
        assertTrue(t.onHeard("hello everyone and welcome"))
        assertEquals(4, t.position)
        assertTrue(t.onHeard("today we talk about solar"))
        assertEquals(10, t.position)
        assertEquals(1, t.currentLine)
    }

    @Test fun toleratesRecogniserMisspellings() {
        val t = ScriptTracker(script)
        t.jumpToLine(1)
        assertTrue(t.onHeard("they save mony every"))
        assertEquals(17, t.position)
        assertEquals(2, t.currentLine)
    }

    @Test fun ignoresUnrelatedSpeechAndWeakBackwardMatches() {
        val t = ScriptTracker(script)
        assertFalse(t.onHeard("pizza is great"))
        assertEquals(0, t.position)
        t.onHeard("today we talk about solar")
        val before = t.position
        assertFalse(t.onHeard("hello"))
        assertEquals(before, t.position)
    }

    @Test fun manualCorrection() {
        val t = ScriptTracker(script)
        t.jumpToLine(1)
        assertEquals(5, t.position)
        t.nudgeLines(1)
        assertEquals(13, t.position)
        t.nudgeLines(5)
        assertEquals(2, t.currentLine)
        t.nudgeLines(-10)
        assertEquals(0, t.position)
        t.advanceWords(100)
        assertTrue(t.isFinished)
        t.reset()
        assertEquals(0, t.position)
    }

    @Test fun fuzzyWordEquality() {
        assertTrue(ScriptTracker.same("panel", "panels"))
        assertTrue(ScriptTracker.same("money", "mony"))
        assertFalse(ScriptTracker.same("cat", "cut"))
    }

    @Test fun tokensKeepApostrophesAndDropPunctuation() {
        assertEquals(listOf("it's", "nethra's", "day"), Text.tokens("It’s Nethra's day!"))
        assertEquals("cafe", Text.fold("café"))
    }

    @Test fun emptyScript() {
        val t = ScriptTracker("   ")
        assertEquals(0, t.lines.size)
        assertFalse(t.onHeard("anything at all"))
        t.jumpToLine(3)
        assertEquals(0, t.currentLine)
    }
}

class ScriptTrackerScrollTest {
    private val script = "Hello everyone and welcome back. Today we talk about solar panels on rooftops. " +
        "They save money every single month."

    @Test fun lineAtMapsWordPositionsToLineFractions() {
        val t = ScriptTracker(script)          // lines: 5, 8, 6 words
        assertEquals(0 to 0f, t.lineAt(0f))
        val (l, f) = t.lineAt(2.5f)
        assertEquals(0, l); assertEquals(0.5f, f, 1e-5f)
        assertEquals(1, t.lineAt(5f).first)
        assertEquals(2, t.lineAt(18.9f).first)
        assertEquals(8, t.wordsInLine(1))
        t.setPosition(13)
        assertEquals(2, t.currentLine)
    }
}
