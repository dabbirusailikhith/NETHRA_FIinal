package com.nethra.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCutPlannerTest {

    @Test fun noMarksKeepsEverything() {
        val p = CommandCutPlanner.plan(emptyList(), 10_000, null)
        assertTrue(p.cuts.isEmpty())
        assertEquals(listOf(Span(0, 10_000)), p.keeps)
    }

    @Test fun withoutAudioFallsBackToAFixedMargin() {
        val p = CommandCutPlanner.plan(listOf(CommandMark(5_000, 6_000, "pause")), 10_000, null)
        assertEquals(listOf(Span(4_700, 6_000)), p.cuts)
        assertEquals(listOf(Span(0, 4_700), Span(6_000, 10_000)), p.keeps)
        assertEquals(1_300, p.removedMs)
        assertFalse(p.boundariesRefined)
    }

    @Test fun cutStartMovesToThePauseBeforeTheWakeWord() {
        // 10 s at 20 ms frames: silence for the first second, speech, then a 600 ms pause at 4.0–4.6 s.
        val rms = FloatArray(500) { 3000f }
        for (i in 0 until 50) rms[i] = 50f
        for (i in 200 until 230) rms[i] = 50f
        val p = CommandCutPlanner.plan(listOf(CommandMark(4_700, 6_000, "pause")), 10_000, rms, 20)
        assertTrue(p.boundariesRefined)
        // Keeps most of the pause, leaving a 150 ms margin (7 frames) before speech resumes at 4.6 s.
        assertEquals(listOf(Span(4_460, 6_000)), p.cuts)
        assertEquals(listOf(Span(0, 4_460), Span(6_000, 10_000)), p.keeps)
    }

    @Test fun marksAreClampedAndSorted() {
        val p = CommandCutPlanner.plan(
            listOf(CommandMark(9_000, 12_000, "stop"), CommandMark(2_000, 3_000, "pause")), 10_000, null
        )
        assertEquals(listOf(Span(1_700, 3_000), Span(8_700, 10_000)), p.cuts)
        assertEquals(listOf(Span(0, 1_700), Span(3_000, 8_700)), p.keeps)
    }

    @Test fun mergeJoinsNearbyCuts() {
        assertEquals(listOf(Span(1_000, 3_000)), CommandCutPlanner.merge(listOf(Span(2_050, 3_000), Span(1_000, 2_000))))
        assertEquals(2, CommandCutPlanner.merge(listOf(Span(1_000, 2_000), Span(2_200, 3_000))).size)
        assertTrue(CommandCutPlanner.merge(listOf(Span(5, 5))).isEmpty())
    }

    @Test fun keepsDropSlivers() {
        assertTrue(CommandCutPlanner.keepsFrom(listOf(Span(50, 1_000)), 1_040).isEmpty())
    }

    @Test fun findPauseBefore() {
        assertNull(CommandCutPlanner.findPauseBefore(BooleanArray(500), 4_700, 20))
        val quiet = BooleanArray(500)
        for (i in 150..160) quiet[i] = true   // short pause, before the command
        for (i in 240..270) quiet[i] = true   // runs past the estimated command start: ignored
        assertEquals(3_080L, CommandCutPlanner.findPauseBefore(quiet, 4_700, 20))
    }

    @Test fun recordingClockSkipsPausedTime() {
        val c = RecordingClock()
        c.onStart(1_000)
        assertEquals(500, c.positionAt(1_500))
        c.onPause(2_000)
        assertEquals(1_000, c.positionAt(2_500))
        c.onResume(3_000)
        assertEquals(1_500, c.positionAt(3_500))
        assertEquals(1_000, c.positionAt(2_900))
        c.onStop(4_000)
        assertEquals(2_000, c.positionAt(9_999))
    }
}
