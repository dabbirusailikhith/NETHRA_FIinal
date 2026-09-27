package com.nethra.app.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.nethra.app.ai.local.LocalGemma
import com.nethra.app.config.AiConfig
import com.nethra.app.config.ApiKeyProvider
import com.nethra.app.core.NetworkMonitor
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import com.nethra.app.core.userMessage
import com.nethra.app.media.AudioChunks
import com.nethra.app.media.AudioExtractor
import com.nethra.app.media.FrameRange
import com.nethra.app.media.PcmAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TranscriptChunk(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String = "",
    val noSpeech: Boolean = false,
    val error: String? = null
) {
    val done: Boolean get() = error == null && (text.isNotBlank() || noSpeech)
}

data class Transcript(
    val chunks: List<TranscriptChunk>,
    /** Transcribed on the phone by Gemma (offline) rather than by the cloud model. */
    val onDevice: Boolean = false
) {
    val failedChunks: List<TranscriptChunk> get() = chunks.filter { it.error != null }
    val isComplete: Boolean get() = chunks.isNotEmpty() && chunks.all { it.done }
    val hasSpeech: Boolean get() = chunks.any { it.text.isNotBlank() }

    /** Chunk texts joined in order; failed chunks are marked, never silently dropped. */
    val fullText: String
        get() = chunks.joinToString("\n\n") { c ->
            when {
                c.error != null -> "[Part ${c.index + 1} (${fmt(c.startMs)}–${fmt(c.endMs)}) not transcribed: ${c.error}]"
                c.noSpeech -> ""
                else -> c.text
            }
        }.replace(Regex("\n{3,}"), "\n\n").trim()

    companion object {
        fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    }
}

sealed interface TranscribeProgress {
    data class Extracting(val fraction: Float) : TranscribeProgress
    data class Transcribing(val chunk: Int, val of: Int, val onDevice: Boolean = false) : TranscribeProgress
}

/**
 * Picked video → extracted 16 kHz mono audio → quiet-point chunks → transcription per chunk.
 *
 * Online (with a key) each chunk goes to the cloud model. Offline — or with no key — the
 * installed Gemma model transcribes on the phone instead, in ~24 s parts (its audio limit
 * is about 30 s). Nothing leaves the phone in that case. The decoded audio stays cached for the session so
 * failed chunks can be retried without decoding the video again. The source
 * video is only ever read.
 */
class Transcriber(
    private val context: Context,
    private val client: OpenRouterClient,
    private val gemma: LocalGemma,
    private val network: NetworkMonitor
) {
    private var cachedUri: Uri? = null
    private var cachedLocal = false
    private var cachedAudio: PcmAudio? = null
    private var cachedRanges: List<FrameRange> = emptyList()

    suspend fun transcribe(uri: Uri, onProgress: (TranscribeProgress) -> Unit): Transcript {
        // Decide cloud vs phone before the slow decode, and fail fast if neither can run.
        val local = chooseLocal()
        val audio = prepare(uri, local) { onProgress(TranscribeProgress.Extracting(it)) }
        val empty = cachedRanges.mapIndexed { i, r -> TranscriptChunk(i, r.startMs(), r.endMs()) }
        return transcribeChunks(Transcript(empty, local), audio, empty.map { it.index }, local, onProgress)
    }

    /** Re-sends only the chunks that failed last time (a full redo if cloud/phone changed since). */
    suspend fun retryFailed(uri: Uri, previous: Transcript, onProgress: (TranscribeProgress) -> Unit): Transcript {
        val local = chooseLocal()
        if (local != previous.onDevice) return transcribe(uri, onProgress)
        val audio = prepare(uri, local) { onProgress(TranscribeProgress.Extracting(it)) }
        if (cachedRanges.size != previous.chunks.size) return transcribe(uri, onProgress)
        return transcribeChunks(previous, audio, previous.failedChunks.map { it.index }, local, onProgress)
    }

    /** True → transcribe on the phone with Gemma; false → cloud. Throws when neither is possible. */
    private fun chooseLocal(): Boolean {
        val cloudReady = ApiKeyProvider.hasOpenRouterKey && network.isOnlineNow()
        if (cloudReady) return false
        if (gemma.isInstalled) return true
        throw if (!ApiKeyProvider.hasOpenRouterKey) NethraException(
            ErrorKind.MISSING_KEY,
            "Transcription needs the cloud model (no OpenRouter key in this build) or an on-device Gemma model. Add either one and try again."
        ) else NethraException(
            ErrorKind.OFFLINE,
            "You're offline. Connect to the internet, or add an on-device Gemma model (Gemma 3n / Gemma 4 E4B) to transcribe without internet."
        )
    }

    fun release() {
        cachedAudio?.file?.delete()
        cachedAudio = null; cachedUri = null; cachedRanges = emptyList()
    }

    private suspend fun prepare(uri: Uri, local: Boolean, onFraction: (Float) -> Unit): PcmAudio {
        cachedAudio?.let {
            if (uri == cachedUri && it.file.exists()) {
                if (local != cachedLocal) { planRanges(it, local); cachedLocal = local }
                return it
            }
        }
        release()
        val out = File(context.cacheDir, "transcribe_${System.currentTimeMillis()}.pcm")
        val audio = AudioExtractor(context).extract(uri, out, onFraction)
        if (audio.frameRms.isEmpty()) {
            out.delete()
            throw NethraException(ErrorKind.NO_AUDIO, "The video's audio track is empty.")
        }
        planRanges(audio, local)
        cachedAudio = audio; cachedUri = uri; cachedLocal = local
        return audio
    }

    /** Cloud parts are ~2 min; Gemma hears at most ~30 s at a time. */
    private fun planRanges(audio: PcmAudio, local: Boolean) {
        val framesPerSec = 1000 / PcmAudio.FRAME_MS
        cachedRanges = if (local) AudioChunks.plan(
            audio.frameRms,
            targetFrames = AiConfig.LOCAL_AUDIO_CHUNK_SECONDS * framesPerSec,
            searchFrames = 6 * framesPerSec
        ) else AudioChunks.plan(
            audio.frameRms,
            targetFrames = AiConfig.TRANSCRIPTION_CHUNK_SECONDS * framesPerSec,
            searchFrames = 20 * framesPerSec
        )
    }

    private suspend fun transcribeChunks(
        start: Transcript,
        audio: PcmAudio,
        indices: List<Int>,
        local: Boolean,
        onProgress: (TranscribeProgress) -> Unit
    ): Transcript {
        val chunks = start.chunks.toMutableList()
        indices.forEachIndexed { n, idx ->
            onProgress(TranscribeProgress.Transcribing(n + 1, indices.size, local))
            val range = cachedRanges[idx]
            chunks[idx] = if (AudioChunks.peak(audio.frameRms, range) < SILENCE_PEAK) {
                chunks[idx].copy(text = "", noSpeech = true, error = null)
            } else try {
                val previousTail = chunks.getOrNull(idx - 1)?.text?.takeLast(300).orEmpty()
                val text = if (local) transcribeOnPhone(audio, range, previousTail)
                else transcribeOne(audio, range, idx, chunks.size, previousTail)
                if (text.trim().equals(NO_SPEECH, ignoreCase = true)) chunks[idx].copy(text = "", noSpeech = true, error = null)
                else chunks[idx].copy(text = text.trim(), noSpeech = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NethraException) {
                // Account/key problems will fail every chunk the same way — stop and report once.
                if (e.kind in setOf(ErrorKind.MISSING_KEY, ErrorKind.AUTH, ErrorKind.CREDITS, ErrorKind.MODEL_NOT_FOUND, ErrorKind.OFFLINE)) throw e
                // A model that can't load or can't hear audio fails every part the same way.
                if (local && e.kind == ErrorKind.LOCAL_MODEL && idx == indices.first()) throw e
                chunks[idx].copy(error = e.userMessage())
            } catch (e: Exception) {
                chunks[idx].copy(error = e.userMessage())
            }
        }
        val result = Transcript(chunks, local)
        if (result.isComplete && !result.hasSpeech) {
            throw NethraException(ErrorKind.NO_SPEECH, "No speech was found in this video's audio.")
        }
        return result
    }

    private suspend fun transcribeOne(audio: PcmAudio, range: FrameRange, idx: Int, total: Int, previousTail: String): String {
        val b64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(AudioChunks.wavBytes(audio, range), Base64.NO_WRAP)
        }
        val instructions = buildString {
            append(PROMPT)
            if (total > 1) append("\n\nThis is part ${idx + 1} of $total of a longer recording.")
            if (previousTail.isNotBlank()) {
                append(" The previous part ended with: \"…$previousTail\". Use that only as context; do not repeat it.")
            }
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", instructions))
            .put(
                JSONObject().put("type", "input_audio").put(
                    "input_audio", JSONObject().put("data", b64).put("format", "wav")
                )
            )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val result = client.chat(
            model = AiConfig.TRANSCRIPTION_MODEL,
            messages = messages,
            maxTokens = 8000,
            temperature = 0.0,
            retries = 2
        )
        if (result.text.isBlank()) throw NethraException(ErrorKind.BAD_RESPONSE, "The transcription model returned nothing for this part.")
        if (result.finishReason == "length") {
            throw NethraException(ErrorKind.BAD_RESPONSE, "The transcription of this part was cut off by the model's output limit.")
        }
        return stripWrapper(result.text)
    }

    /** One ≤30 s part through on-device Gemma. */
    private suspend fun transcribeOnPhone(audio: PcmAudio, range: FrameRange, previousTail: String): String {
        val wav = withContext(Dispatchers.IO) { AudioChunks.wavBytes(audio, range) }
        val instruction = buildString {
            append(PROMPT)
            if (previousTail.isNotBlank()) append("\n\nContext only (do not repeat): the audio before this ended with \"…${previousTail.takeLast(150)}\".")
        }
        val text = stripWrapper(gemma.transcribeAudio(wav, instruction))
        return text.ifBlank { NO_SPEECH }
    }

    private fun FrameRange.startMs() = start.toLong() * PcmAudio.FRAME_MS
    private fun FrameRange.endMs() = end.toLong() * PcmAudio.FRAME_MS

    companion object {
        const val NO_SPEECH = "[NO SPEECH]"
        /** RMS below this (out of 32767) across a whole chunk is treated as silence. */
        private const val SILENCE_PEAK = 40f

        val PROMPT = """
            Transcribe this audio exactly as spoken.
            - Keep the original language(s). Do not translate. If speakers mix languages, keep each word in the language it was spoken in, in its native script.
            - Keep the speaker's own wording, including repetitions and informal grammar. Do not summarise, paraphrase, correct, shorten or add anything.
            - Use normal punctuation and paragraph breaks. No timestamps, speaker labels, headings or commentary.
            - Mark unclear words as [inaudible].
            - If there is no speech at all, reply with exactly $NO_SPEECH
            Reply with the transcript only.
        """.trimIndent()

        /** Removes code fences or a "Transcript:" label a model sometimes adds. */
        fun stripWrapper(text: String): String = text.trim()
            .removePrefix("```text").removePrefix("```").removeSuffix("```").trim()
            .replace(Regex("""^(?i)(transcript(ion)?\s*:)\s*"""), "")
            .trim()
    }
}
