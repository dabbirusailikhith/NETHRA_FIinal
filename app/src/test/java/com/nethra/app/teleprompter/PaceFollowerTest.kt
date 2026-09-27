package com.nethra.app.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceFollowerTest {

    /** Simulates a speaker at [wpm] with the recogniser reporting in bursts every [burstMs]. */
    private fun speak(p: PaceFollower, wpm: Float, seconds: Int, burstMs: Long = 600, startMs: Long = 0, startWord: Int = 0): Long {
        var t = startMs
        val end = startMs + seconds * 1000L
        var nextBurst = startMs + burstMs
        while (t < end) {
            p.onVoiceActivity(t)
            if (t >= nextBurst) {
                val heard = startWord + ((t - startMs) * wpm / 60_000f).toInt()
                p.onRecognised(heard, t)
                nextBurst += burstMs
            }
            p.tick(t)
            t += 16
        }
        return t
    }

    @Test fun measuresSlowAndFastSpeakers() {
        for (wpm in listOf(100f, 150f, 200f)) {
            val p = PaceFollower(initialWpm = 150f).apply { setWordCount(10_000) }
            speak(p, wpm, seconds = 20)
            println("speaker $wpm wpm → measured ${p.measuredWpm}")
            assertEquals(wpm, p.measuredWpm, wpm * 0.1f)
        }
    }

    @Test fun glidesSmoothlyBetweenBursts() {
        val p = PaceFollower(initialWpm = 150f).apply { setWordCount(10_000) }
        var t = speak(p, 150f, seconds = 10)
        // Between bursts, the display keeps moving forward in small steps (no jumps).
        var prev = p.displayPosition
        var maxStep = 0f
        repeat(60) {
            t += 16
            p.onVoiceActivity(t)
            if (it % 37 == 0) p.onRecognised((t * 150f / 60_000f).toInt(), t)
            val now = p.tick(t)
            assertTrue("never goes backwards", now >= prev - 1e-4f)
            maxStep = maxOf(maxStep, now - prev)
            prev = now
        }
        assertTrue("max per-frame step $maxStep words", maxStep < 0.6f)
    }

    @Test fun staysCloseToTheSpeaker() {
        val p = PaceFollower(initialWpm = 120f).apply { setWordCount(10_000) }
        val t = speak(p, 170f, seconds = 15)
        val truth = t * 170f / 60_000f
        println("display ${p.displayPosition} vs truth $truth")
        assertEquals(truth, p.displayPosition, 4f)
    }

    @Test fun stopsWhenTheSpeakerStops() {
        val p = PaceFollower().apply { setWordCount(10_000) }
        var t = speak(p, 150f, seconds = 8)
        val atStop = p.displayPosition
        repeat(200) { t += 16; p.tick(t) }        // 3.2 s of silence
        val later = p.displayPosition
        assertTrue("drifted ${later - atStop} words while silent", later - atStop < 4.1f)
        val settled = p.displayPosition
        repeat(200) { t += 16; p.tick(t) }
        assertEquals(settled, p.displayPosition, 0.05f)
    }

    @Test fun fixedWpmModeIgnoresSpeech() {
        val p = PaceFollower().apply { setWordCount(10_000); mode = ScrollMode.FIXED_WPM; targetWpm = 120f }
        var t = 0L
        repeat(60 * 60) { p.tick(t); t += 16 }   // ~57.6 s
        assertEquals(120f * 57.6f / 60f, p.displayPosition, 1f)
        assertEquals(120f, p.effectiveWpm, 0f)
    }

    @Test fun pauseAndManualJump() {
        val p = PaceFollower().apply { setWordCount(10_000); mode = ScrollMode.FIXED_WPM; targetWpm = 200f }
        var t = 0L
        repeat(100) { p.tick(t); t += 16 }
        p.paused = true
        val frozen = p.displayPosition
        repeat(100) { p.tick(t); t += 16 }
        assertEquals(frozen, p.displayPosition, 0f)
        p.jumpTo(42, t)
        assertEquals(42f, p.displayPosition, 0f)
    }

    @Test fun wpmIsClamped() {
        val p = PaceFollower()
        p.targetWpm = 1000f
        assertEquals(PaceFollower.MAX_WPM, p.targetWpm, 0f)
        p.targetWpm = 5f
        assertEquals(PaceFollower.MIN_WPM, p.targetWpm, 0f)
    }

    @Test fun neverScrollsPastTheEnd() {
        val p = PaceFollower().apply { setWordCount(20); mode = ScrollMode.FIXED_WPM; targetWpm = 260f }
        var t = 0L
        repeat(2000) { p.tick(t); t += 16 }
        assertEquals(20f, p.displayPosition, 0f)
    }
}

class PaceFollowerDeadReckoningTest {
    @Test fun keepsGlidingOnVoiceActivityWhenTheRecogniserIsSilent() {
        val p = PaceFollower(initialWpm = 150f).apply { setWordCount(10_000) }
        var t = 0L
        // 6 s of talking that the recogniser never transcribes (e.g. mic blocked while recording).
        repeat(375) { p.onVoiceActivity(t); p.tick(t); t += 16 }
        // Up to 4 words of normal lead in the first 2 s, then ~150 wpm for ~4 s ⇒ ~14 words.
        assertTrue("moved ${p.displayPosition}", p.displayPosition in 11f..17f)
        assertTrue(p.deadReckoning)
        // Silence: stops.
        val stop = p.displayPosition
        repeat(200) { p.tick(t); t += 16 }
        assertTrue("drift ${p.displayPosition - stop}", p.displayPosition - stop < 1.5f)
    }

    @Test fun recognitionReanchorsAfterDeadReckoning() {
        val p = PaceFollower(initialWpm = 150f).apply { setWordCount(10_000) }
        var t = 0L
        repeat(375) { p.onVoiceActivity(t); p.tick(t); t += 16 }
        p.onRecognised(14, t)             // the recogniser catches up: speaker is at word 14
        repeat(120) { p.onVoiceActivity(t); p.tick(t); t += 16 }
        assertTrue("display ${p.displayPosition}", p.displayPosition in 13f..20f)
        assertTrue(!p.deadReckoning)
    }
}

class PaceFollowerVoicePaceTest {
    @Test fun voicePaceDrivesTheGlideWhenWordsArentRecognised() {
        val p = PaceFollower(initialWpm = 150f).apply { setWordCount(10_000) }
        var t = 0L
        repeat(30) { p.onVoicePace(100f, t); t += 100 }
        assertEquals(100f, p.measuredWpm, 3f)
        val start = p.displayPosition
        repeat(625) { p.onVoiceActivity(t); p.tick(t); t += 16 }     // 10 s of talking
        // ≈ 4 words normal lead + 8 s × 100 wpm ⇒ ~17 words, not the 150-wpm ~24.
        val moved = p.displayPosition - start
        assertTrue("moved $moved", moved in 12f..19f)
    }
}
