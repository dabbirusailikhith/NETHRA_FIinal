package com.nethra.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class SpeechRateTest {
    /** Loudness of speech with [sylPerSec] syllables a second: a pulsing envelope (±9 dB). */
    private fun envelopeDb(tMs: Int, sylPerSec: Float) =
        (-24.0 + 9.0 * sin(2 * PI * sylPerSec * tMs / 1000.0)).toFloat()

    private fun run(sylPerSec: Float, seconds: Int, pauseEveryMs: Int = 0): SpeechRate {
        val r = SpeechRate()
        var t = 0
        while (t < seconds * 1000) {
            val pause = pauseEveryMs > 0 && (t / pauseEveryMs) % 2 == 1
            r.feed(if (pause) -60f else envelopeDb(t, sylPerSec), !pause)
            t += 20
        }
        return r
    }

    @Test fun slowNormalFast() {
        // 2.5, 3.75, 5 syllables/s ≈ 100, 150, 200 wpm at 1.5 syllables/word.
        for ((syl, wpm) in listOf(2.5f to 100f, 3.75f to 150f, 5f to 200f)) {
            val est = run(syl, 12).wpm
            assertNotNull(est)
            println("syl/s $syl → ${est} wpm")
            assertEquals(wpm, est!!, wpm * 0.12f)
        }
    }

    @Test fun pausesDontSlowTheEstimate() {
        val est = run(3.75f, 20, pauseEveryMs = 1500).wpm!!
        assertEquals(150f, est, 20f)
    }

    @Test fun noEstimateWithoutEnoughSpeech() {
        assertNull(run(4f, 1).wpm)
    }
}

class SpeechRateLanguageTest {
    /** Same voice, same syllable rate — a Telugu speaker is producing far fewer words. */
    @Test fun syllableRateConvertsPerLanguage() {
        fun measure(syllablesPerWord: Float): Float {
            val r = SpeechRate().apply { this.syllablesPerWord = syllablesPerWord }
            var t = 0
            while (t < 12_000) {
                r.feed((-24.0 + 9.0 * kotlin.math.sin(2 * Math.PI * 3.75 * t / 1000.0)).toFloat(), true)
                t += 20
            }
            return r.wpm!!
        }
        val english = measure(1.5f)
        val telugu = measure(2.9f)
        println("3.75 syl/s -> English ${english} wpm, Telugu ${telugu} wpm")
        assertEquals(150f, english, 15f)
        assertEquals(78f, telugu, 12f)
        assertTrue(telugu < english)
    }
}
