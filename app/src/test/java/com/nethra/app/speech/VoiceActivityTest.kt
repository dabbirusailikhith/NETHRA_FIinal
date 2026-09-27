package com.nethra.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VoiceActivityTest {
    private val rnd = Random(3)
    private fun noise(amp: Double) = ShortArray(320) { (rnd.nextDouble(-1.0, 1.0) * amp * 32767).toInt().toShort() }
    private fun tone(amp: Double) = ShortArray(320) { (sin(2 * PI * 200 * it / 16000.0) * amp * 32767).toInt().toShort() }

    @Test fun dbfsOfKnownSignals() {
        assertEquals(-120f, VoiceActivity.dbfs(ShortArray(320), 320), 0f)
        // Full-scale sine ≈ -3 dBFS
        assertEquals(-3f, VoiceActivity.dbfs(tone(1.0), 320), 0.3f)
    }

    @Test fun detectsSpeechAboveAdaptiveNoiseFloor() {
        val vad = VoiceActivity()
        var t = 0L
        repeat(100) { assertFalse(vad.feed(noise(0.003), 320, t)); t += 20 }   // quiet room
        assertTrue(vad.feed(tone(0.2), 320, t)); t += 20                       // talking
        assertTrue(vad.isSpeaking(t))
        t += 400
        assertFalse(vad.isSpeaking(t))                                          // hangover over
    }

    @Test fun loudSteadyNoiseBecomesTheFloor() {
        val vad = VoiceActivity()
        var t = 0L
        var speechFrames = 0
        repeat(500) { if (vad.feed(noise(0.05), 320, t)) speechFrames++; t += 20 }  // fan / traffic
        assertTrue("noise counted as speech $speechFrames times", speechFrames < 10)
        assertTrue(vad.feed(tone(0.5), 320, t))                                   // voice over the noise
    }
}
