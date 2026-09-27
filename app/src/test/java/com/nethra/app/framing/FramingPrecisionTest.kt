package com.nethra.app.framing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Precision tests for the framing coach. Run with:
 *   .\gradlew.bat testDebugUnitTest --tests "com.nethra.app.framing.*"
 * The evaluation report is printed to the test output
 * (app\build\reports\tests\testDebugUnitTest\index.html → Standard output).
 */
class FramingPrecisionTest {

    private val box = NormRect.DEFAULT // 0.2, 0.12, 0.8, 0.9  (w 0.6, h 0.78)

    /** A subject box the same size as the target, shifted by (dx, dy) and scaled by [s]. */
    private fun subject(dx: Float = 0f, dy: Float = 0f, s: Float = 0.92f): NormRect {
        val w = box.width * s; val h = box.height * s
        val cx = box.centerX + dx; val cy = box.centerY + dy
        return NormRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    @Test fun metricsForAPerfectFitAreIdeal() {
        val m = FramingMath.measure(box, box, 0.9f)
        assertEquals(0f, m.dx, 1e-5f); assertEquals(0f, m.dy, 1e-5f)
        assertEquals(1f, m.scale, 1e-5f)
        assertEquals(1f, m.iou, 1e-5f)
        assertEquals(1f, m.coverage, 1e-5f)
        assertEquals(100f, m.score, 1e-3f)
    }

    @Test fun iouAndCoverage() {
        val a = NormRect(0f, 0f, 0.5f, 0.5f)
        val b = NormRect(0.25f, 0f, 0.75f, 0.5f)
        assertEquals(1f / 3f, a.iou(b), 1e-5f)
        assertEquals(0f, a.iou(NormRect(0.6f, 0.6f, 0.9f, 0.9f)), 1e-6f)
        val m = FramingMath.measure(b, a, 1f)
        assertEquals(0.5f, m.coverage, 1e-5f)
    }

    @Test fun relativeErrorIsOneAtTheToleranceEdge() {
        val tolX = FramingTolerance.STANDARD.centerX * box.width
        val m = FramingMath.measure(subject(dx = tolX), box, 0.9f)
        assertEquals(1f, m.dxRel, 1e-4f)
        assertEquals(Guidance.GOOD, FramingCoach.evaluate(Detection(subject(dx = tolX * 0.9f), 0.9f, true, 0), box))
        assertEquals(Guidance.MOVE_RIGHT, FramingCoach.evaluate(Detection(subject(dx = tolX * 1.1f), 0.9f, true, 0), box))
    }

    @Test fun scoreFallsMonotonicallyWithError() {
        var prev = 101f
        for (i in 0..10) {
            val sc = FramingMath.measure(subject(dx = i * 0.02f), box, 0.9f).score
            assertTrue("score should fall: $prev -> $sc at step $i", sc <= prev)
            prev = sc
        }
        prev = 101f
        for (i in 0..8) {
            val sc = FramingMath.measure(subject(s = 1f + i * 0.08f), box, 0.9f).score
            assertTrue(sc <= prev); prev = sc
        }
    }

    @Test fun presetsAreOrderedByStrictness() {
        val nudge = Detection(subject(dx = 0.05f), 0.9f, true, 0)   // 0.083 target widths off
        assertEquals(Guidance.GOOD, FramingCoach.evaluate(nudge, box, FramingTolerance.RELAXED, false))
        assertEquals(Guidance.GOOD, FramingCoach.evaluate(nudge, box, FramingTolerance.STANDARD, false))
        assertEquals(Guidance.MOVE_RIGHT, FramingCoach.evaluate(nudge, box, FramingTolerance.PRECISE, false))
    }

    @Test fun hysteresisKeepsGoodAtTheBoundary() {
        val tolX = FramingTolerance.STANDARD.centerX * box.width
        val justOutside = Detection(subject(dx = tolX * 1.2f), 0.9f, true, 0)
        assertEquals(Guidance.MOVE_RIGHT, FramingCoach.evaluate(justOutside, box, FramingTolerance.STANDARD, wasGood = false))
        assertEquals(Guidance.GOOD, FramingCoach.evaluate(justOutside, box, FramingTolerance.STANDARD, wasGood = true))
    }

    /** Labelled synthetic grid: every rule, several magnitudes, noise-free. Must be 100%. */
    @Test fun labelledGridAccuracy() {
        val cases = buildList {
            add(subject() to Guidance.GOOD)
            add(subject(dx = 0.03f) to Guidance.GOOD)
            add(subject(dy = -0.04f) to Guidance.GOOD)
            for (d in listOf(0.1f, 0.15f, 0.2f)) {
                add(subject(dx = -d) to Guidance.MOVE_LEFT)
                add(subject(dx = d) to Guidance.MOVE_RIGHT)
            }
            for (d in listOf(0.12f, 0.16f)) {
                add(subject(dy = -d, s = 0.8f) to Guidance.CAMERA_UP)
                add(subject(dy = d, s = 0.8f) to Guidance.CAMERA_DOWN)
            }
            for (s in listOf(0.4f, 0.55f, 0.7f)) add(subject(s = s) to Guidance.COME_CLOSER)
            for (s in listOf(1.2f, 1.3f)) add(subject(s = s).clamp() to Guidance.STEP_BACK)
        }
        var ok = 0
        for ((r, want) in cases) {
            val got = FramingCoach.evaluate(Detection(r, 0.9f, true, 0), box)
            if (got == want) ok++ else println("MISS: $r expected $want got $got")
        }
        println("Labelled grid: $ok / ${cases.size}")
        assertEquals(cases.size, ok)
    }

    /**
     * Simulates a person walking into the box with detector jitter (σ = 1.2 % of
     * the frame), then standing still. Checks the whole pipeline: smoothing,
     * stability, hysteresis and how much it talks.
     */
    @Test fun noisyWalkInEvaluation() {
        val rnd = Random(42)
        val eval = FramingEvaluator(FramingCoach())
        val fps = 15
        val frameMs = 1000L / fps
        var t = 0L
        fun noisy(r: NormRect) = NormRect(
            r.left + rnd.nextGaussian() * 0.012f, r.top + rnd.nextGaussian() * 0.012f,
            r.right + rnd.nextGaussian() * 0.012f, r.bottom + rnd.nextGaussian() * 0.012f
        )
        // 3 s: walking in from the right, 0.3 → 0.0 offset.
        for (i in 0 until 3 * fps) {
            val dx = 0.3f * (1f - i / (3f * fps))
            val want = if (dx > 0.09f) Guidance.MOVE_RIGHT else null   // near the edge: don't grade
            eval.add(Detection(noisy(subject(dx = dx)), 0.85f, true, t), box, t, want)
            t += frameMs
        }
        // 10 s: standing in the box.
        for (i in 0 until 10 * fps) {
            eval.add(Detection(noisy(subject()), 0.85f, true, t), box, t, if (i > fps) Guidance.GOOD else null)
            t += frameMs
        }
        val r = eval.report()
        println(r)
        assertTrue("accuracy ${r.accuracy}", r.accuracy >= 0.95f)
        assertTrue("steady: ${r.flipsPerMinute} flips/min", r.flipsPerMinute <= 12f)
        assertTrue("quiet: ${r.utterancesPerMinute}/min", r.utterancesPerMinute <= 12f)
        assertTrue("score ${r.meanScore}", r.meanScore >= 80f)
        assertNotNull(r.timeToGoodMs)
    }

    @Test fun smoothingAndHysteresisReduceFlips() {
        fun run(smoothing: Float, hysteresis: Float): Float {
            val rnd = Random(7)
            val tolX = FramingTolerance.STANDARD.centerX * box.width
            val tol = FramingTolerance.STANDARD.copy(hysteresis = hysteresis)
            val eval = FramingEvaluator(FramingCoach(smoothing = smoothing, tolerance = tol))
            var t = 0L
            repeat(600) {
                // Standing right at the tolerance edge with jitter: the worst case.
                val r = subject(dx = tolX * 0.95f)
                val j = rnd.nextGaussian() * 0.03f
                eval.add(Detection(NormRect(r.left + j, r.top, r.right + j, r.bottom), 0.9f, true, t), box, t)
                t += 66
            }
            return eval.report().flipsPerMinute
        }
        val raw = run(0f, 1f)
        val smooth = run(0.45f, 1f)
        val full = run(0.45f, FramingTolerance.STANDARD.hysteresis)
        println("flips/min at the tolerance edge: raw=$raw smoothed=$smooth smoothed+hysteresis=$full")
        // At the edge smoothing alone can't decide (the mean *is* on the edge); hysteresis does.
        assertTrue("full pipeline should flip ≥5× less than raw", full * 5 <= raw)
    }

    @Test fun spokenPhrasesScaleWithDistance() {
        val v = GuidanceVoice()
        fun say(dx: Float) = v.phrase(
            if (dx < 0) Guidance.MOVE_LEFT else Guidance.MOVE_RIGHT,
            FramingMath.measure(subject(dx = dx), box, 0.9f), box, 0
        )
        assertEquals("Just a tiny bit to your right.", say(0.08f))
        assertEquals("A small step to your left.", say(-0.15f))
        assertEquals("One step to your right.", say(0.3f))
        assertEquals("Two steps to your left.", say(-0.5f))
        assertTrue(v.phrase(Guidance.GOOD, null, box, 0, improvedFrom = Guidance.MOVE_LEFT).startsWith("That's it"))
        assertEquals(Guidance.NO_PERSON.spoken, v.phrase(Guidance.NO_PERSON, null, box, 0))
        assertTrue(v.phrase(Guidance.NO_PERSON, null, box, 1) != Guidance.NO_PERSON.spoken)
    }

    private fun Random.nextGaussian(): Float {
        // Box–Muller
        val u1 = nextDouble().coerceAtLeast(1e-9); val u2 = nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
}
