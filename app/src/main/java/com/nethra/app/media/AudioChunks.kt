package com.nethra.app.media

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A slice of [PcmAudio], in envelope frames (start inclusive, end exclusive). */
data class FrameRange(val start: Int, val end: Int)

object AudioChunks {

    /**
     * Splits an envelope into chunks of about [targetFrames], moving each cut to
     * the quietest stretch within [searchFrames] before the target so words are
     * not sliced in half. Pure — unit tested.
     */
    fun plan(frameRms: FloatArray, targetFrames: Int, searchFrames: Int, quietWindow: Int = 15): List<FrameRange> {
        val n = frameRms.size
        if (n == 0) return emptyList()
        if (n <= targetFrames + searchFrames / 2) return listOf(FrameRange(0, n))
        val out = mutableListOf<FrameRange>()
        var start = 0
        while (n - start > targetFrames + searchFrames / 2) {
            val ideal = start + targetFrames
            val lo = (ideal - searchFrames).coerceAtLeast(start + targetFrames / 2)
            var bestAt = ideal
            var bestEnergy = Double.MAX_VALUE
            var i = lo
            while (i <= ideal) {
                var e = 0.0
                for (k in i until (i + quietWindow).coerceAtMost(n)) e += frameRms[k]
                if (e < bestEnergy) { bestEnergy = e; bestAt = i + quietWindow / 2 }
                i++
            }
            val cut = bestAt.coerceIn(start + 1, n - 1)
            out += FrameRange(start, cut)
            start = cut
        }
        out += FrameRange(start, n)
        return out
    }

    /** Loudest frame in a range — used to skip chunks that are pure digital silence. */
    fun peak(frameRms: FloatArray, range: FrameRange): Float {
        var p = 0f
        for (i in range.start until range.end.coerceAtMost(frameRms.size)) if (frameRms[i] > p) p = frameRms[i]
        return p
    }

    /** Reads one chunk of the PCM file and wraps it as a 16 kHz mono 16-bit WAV. */
    fun wavBytes(audio: PcmAudio, range: FrameRange): ByteArray {
        val startByte = range.start.toLong() * PcmAudio.SAMPLES_PER_FRAME * 2
        val endByte = (range.end.toLong() * PcmAudio.SAMPLES_PER_FRAME * 2).coerceAtMost(audio.file.length())
        val dataLen = (endByte - startByte).coerceAtLeast(0).toInt()
        val out = ByteArrayOutputStream(44 + dataLen)
        out.write(wavHeader(dataLen, PcmAudio.SAMPLE_RATE))
        RandomAccessFile(audio.file, "r").use { raf ->
            raf.seek(startByte)
            val buf = ByteArray(1 shl 16)
            var left = dataLen
            while (left > 0) {
                val r = raf.read(buf, 0, minOf(buf.size, left))
                if (r <= 0) break
                out.write(buf, 0, r); left -= r
            }
        }
        return out.toByteArray()
    }

    fun wavHeader(dataLen: Int, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataLen)
        }.array()
    }
}
