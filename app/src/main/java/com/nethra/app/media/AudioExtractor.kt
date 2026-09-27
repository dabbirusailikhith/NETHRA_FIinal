package com.nethra.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Decoded audio as 16 kHz mono 16-bit little-endian PCM on disk, plus a
 * loudness envelope ([frameRms], one value per [FRAME_MS]) used to find quiet
 * points for chunking and phrase boundaries for command cuts.
 */
class PcmAudio(
    val file: File,
    val frameRms: FloatArray,
    val durationMs: Long
) {
    val sampleRate get() = SAMPLE_RATE
    val totalSamples: Long get() = file.length() / 2

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
    }
}

/**
 * Decodes the first audio track of any Android-supported video/audio file with
 * MediaExtractor + MediaCodec, down-mixes to mono and resamples to 16 kHz.
 * Runs on [Dispatchers.IO]; the source is only read, never modified.
 */
class AudioExtractor(private val context: Context) {

    suspend fun extract(uri: Uri, outFile: File, onProgress: (Float) -> Unit = {}): PcmAudio =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                extractor.release()
                throw NethraException(ErrorKind.UNSUPPORTED_FILE, "This file can't be read as a video. Pick an MP4, MOV, MKV or WebM file.", e)
            }

            var codec: MediaCodec? = null
            try {
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw NethraException(ErrorKind.NO_AUDIO, "This video has no audio track, so there is nothing to transcribe.")

                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

                val dec = try {
                    MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
                } catch (e: Exception) {
                    throw NethraException(ErrorKind.UNSUPPORTED_FILE, "This phone can't decode the video's audio format ($mime).", e)
                }
                codec = dec

                var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floatPcm = false
                var resampler = LinearResampler(inRate, PcmAudio.SAMPLE_RATE)
                val envelope = EnvelopeBuilder(PcmAudio.SAMPLES_PER_FRAME)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var mono = FloatArray(4096)
                var written = 0L

                BufferedOutputStream(FileOutputStream(outFile), 1 shl 16).use { out ->
                    val emit: (Float) -> Unit = { s ->
                        val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                        envelope.add(v)
                        written++
                    }
                    while (true) {
                        coroutineContext.ensureActive()
                        if (!inputDone) {
                            val inIdx = dec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val buf = dec.getInputBuffer(inIdx)!!
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = dec.outputFormat
                                inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                                resampler = LinearResampler(inRate, PcmAudio.SAMPLE_RATE)
                            }
                            outIdx >= 0 -> {
                                val buf = dec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                val bytesPerSample = if (floatPcm) 4 else 2
                                val frames = info.size / (bytesPerSample * channels)
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (i in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) {
                                        sum += if (floatPcm) buf.float else buf.short / 32768f
                                    }
                                    mono[i] = sum / channels
                                }
                                resampler.process(mono, frames, emit)
                                dec.releaseOutputBuffer(outIdx, false)
                                if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            }
                        }
                    }
                }
                onProgress(1f)
                PcmAudio(outFile, envelope.build(), written * 1000 / PcmAudio.SAMPLE_RATE)
            } catch (e: NethraException) {
                outFile.delete(); throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                outFile.delete(); throw e
            } catch (e: Exception) {
                outFile.delete()
                throw NethraException(ErrorKind.MEDIA, "Couldn't extract the audio: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }
}

/** Streaming linear-interpolation resampler. Good enough for speech. */
internal class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var t = 0.0          // read position; -1 refers to the last sample of the previous buffer
    private var prev = 0f

    fun process(input: FloatArray, n: Int, out: (Float) -> Unit) {
        if (n == 0) return
        while (t < n - 1) {
            val i = kotlin.math.floor(t).toInt()
            val frac = (t - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = input[i + 1]
            out(a + (b - a) * frac)
            t += step
        }
        t -= n
        prev = input[n - 1]
    }
}

/** Builds the per-frame RMS loudness envelope while samples stream past. */
internal class EnvelopeBuilder(private val samplesPerFrame: Int) {
    private val values = ArrayList<Float>(4096)
    private var acc = 0.0
    private var count = 0

    fun add(sample: Int) {
        acc += sample.toDouble() * sample
        if (++count == samplesPerFrame) {
            values += sqrt(acc / count).toFloat()
            acc = 0.0; count = 0
        }
    }

    fun build(): FloatArray = values.toFloatArray()
}
