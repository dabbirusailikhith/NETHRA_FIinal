package com.nethra.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioChunksTest {

    @Test fun shortAudioIsOneChunk() {
        assertTrue(AudioChunks.plan(FloatArray(0), 300, 50).isEmpty())
        assertEquals(listOf(FrameRange(0, 100)), AudioChunks.plan(FloatArray(100) { 1f }, 100, 20))
    }

    @Test fun cutsLandInQuietStretchesAndCoverEverything() {
        val rms = FloatArray(1_000) { 100f }
        for (i in 280 until 300) rms[i] = 0f
        val chunks = AudioChunks.plan(rms, 300, 50)
        assertEquals(0, chunks.first().start)
        assertEquals(287, chunks.first().end)
        assertEquals(1_000, chunks.last().end)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        chunks.forEach { assertTrue(it.end - it.start <= 300 + 25) }
    }

    @Test fun wavHeader() {
        val h = ByteBuffer.wrap(AudioChunks.wavHeader(32_000, 16_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals(36 + 32_000, h.getInt(4))
        assertEquals(16_000, h.getInt(24))
        assertEquals(32_000, h.getInt(28))
        assertEquals(32_000, h.getInt(40))
    }
}
