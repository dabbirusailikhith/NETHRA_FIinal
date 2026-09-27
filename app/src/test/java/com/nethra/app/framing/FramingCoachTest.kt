package com.nethra.app.framing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramingCoachTest {

    private val box = NormRect.DEFAULT // 0.2, 0.12, 0.8, 0.9

    private fun det(r: NormRect?, conf: Float = 0.9f) = Detection(r, conf, faceFound = true, timestampMs = 0)
    private fun eval(r: NormRect?, conf: Float = 0.9f) = FramingCoach.evaluate(det(r, conf), box)

    @Test fun noPersonAndLowConfidence() {
        assertEquals(Guidance.NO_PERSON, eval(null))
        assertEquals(Guidance.LOW_CONFIDENCE, eval(NormRect(0.25f, 0.15f, 0.75f, 0.87f), conf = 0.3f))
    }

    @Test fun inTheBox() {
        assertEquals(Guidance.GOOD, eval(NormRect(0.25f, 0.15f, 0.75f, 0.87f)))
    }

    @Test fun distance() {
        assertEquals(Guidance.COME_CLOSER, eval(NormRect(0.45f, 0.4f, 0.55f, 0.6f)))
        assertEquals(Guidance.STEP_BACK, eval(NormRect(0.05f, 0.02f, 0.95f, 0.97f)))
        assertEquals(Guidance.STEP_BACK, eval(NormRect(0f, 0.1f, 1f, 0.9f)))
    }

    @Test fun sidewaysIsFromTheSubjectsPointOfView() {
        assertEquals(Guidance.MOVE_LEFT, eval(NormRect(0.05f, 0.15f, 0.55f, 0.87f)))
        assertEquals(Guidance.MOVE_LEFT, eval(NormRect(0f, 0.15f, 0.4f, 0.87f)))
        assertEquals(Guidance.MOVE_RIGHT, eval(NormRect(0.45f, 0.15f, 0.95f, 0.87f)))
    }

    @Test fun height() {
        assertEquals(Guidance.CAMERA_UP, eval(NormRect(0.25f, 0.02f, 0.75f, 0.74f)))
        assertEquals(Guidance.CAMERA_DOWN, eval(NormRect(0.25f, 0.26f, 0.75f, 0.98f)))
    }

    @Test fun speaksOnlyOnceStableAndDoesNotRepeatGood() {
        val coach = FramingCoach(stableFrames = 3)
        val good = det(NormRect(0.25f, 0.15f, 0.75f, 0.87f))
        assertNull(coach.update(good, box, 10_000))
        assertNull(coach.update(good, box, 10_100))
        assertEquals(Guidance.NO_PERSON, coach.state.guidance)
        assertEquals(Guidance.GOOD.spoken, coach.update(good, box, 10_200))
        assertEquals(Guidance.GOOD, coach.state.guidance)
        assertNull(coach.update(good, box, 30_000))

        val nobody = det(null)
        assertNull(coach.update(nobody, box, 31_000))
        assertNull(coach.update(nobody, box, 31_100))
        assertEquals(Guidance.NO_PERSON.spoken, coach.update(nobody, box, 31_200))
    }

    @Test fun uprightMapping() {
        val r = NormRect(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(r, r.uprightFor(0))
        assertRect(NormRect(0.7f, 0.6f, 0.9f, 0.8f), r.uprightFor(180))
        assertRect(r, r.uprightFor(90).uprightFor(270))
        val sideways = r.uprightFor(90)
        assertEquals(r.height, sideways.width, 1e-5f)
        assertEquals(r.width, sideways.height, 1e-5f)
    }

    private fun assertRect(e: NormRect, a: NormRect) {
        assertEquals(e.left, a.left, 1e-5f); assertEquals(e.top, a.top, 1e-5f)
        assertEquals(e.right, a.right, 1e-5f); assertEquals(e.bottom, a.bottom, 1e-5f)
    }
}
