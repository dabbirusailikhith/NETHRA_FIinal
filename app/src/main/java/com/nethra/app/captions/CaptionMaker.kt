package com.nethra.app.captions

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.nethra.app.ai.OpenRouterClient
import com.nethra.app.ai.Transcriber
import com.nethra.app.ai.local.LocalGemma
import com.nethra.app.core.NetworkMonitor
import com.nethra.app.config.AiConfig
import com.nethra.app.config.ApiKeyProvider
import com.nethra.app.config.CaptionScript
import com.nethra.app.config.ScriptLanguage
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import com.nethra.app.media.AudioChunks
import com.nethra.app.media.AudioExtractor
import com.nethra.app.media.MediaStoreVideos
import com.nethra.app.media.PcmAudio
import com.nethra.app.media.TransformerRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed interface CaptionStatus {
    data object Idle : CaptionStatus
    data class Working(val stage: String, val progress: Float?) : CaptionStatus
    data class Done(val uri: Uri, val name: String, val cards: Int, val srtSaved: Boolean) : CaptionStatus
    /** The video itself is always saved; [message] says why no captioned copy exists. */
    data class Failed(val message: String) : CaptionStatus
}

/**
 * Makes a captioned copy of a recording, entirely from inside NETHRA:
 *  1. extract the audio on the phone;
 *  2. transcribe it in ~40 s parts with timestamps (cloud model, spoken language kept) — or,
 *     offline, in ~24 s parts with on-device Gemma, whose words are then spread over the
 *     real speech found in the waveform (Gemma gives no reliable timestamps);
 *  3. snap timings to the real speech and build short word-by-word cards ([CaptionBuilder]);
 *  4. burn them into a new MP4 with Media3 ([CaptionOverlay]) and save it beside the original,
 *     plus an .srt subtitle file (Documents/NETHRA) for YouTube.
 * The original recording is never modified.
 */
class CaptionMaker(
    private val context: Context,
    private val client: OpenRouterClient,
    private val store: MediaStoreVideos,
    private val gemma: LocalGemma,
    private val network: NetworkMonitor
) {
    /** Captions can be made right now — in the cloud, or offline with an on-device Gemma. */
    val canRun: Boolean get() = (ApiKeyProvider.hasOpenRouterKey && network.isOnlineNow()) || gemma.isInstalled

    suspend fun make(
        source: Uri,
        outBaseName: String,
        language: ScriptLanguage = ScriptLanguage.ENGLISH,
        captionScript: CaptionScript = CaptionScript.MIXED,
        onStatus: (CaptionStatus) -> Unit
    ): CaptionStatus.Done {
        val local = !(ApiKeyProvider.hasOpenRouterKey && network.isOnlineNow())
        if (local && !gemma.isInstalled) throw NethraException(
            if (ApiKeyProvider.hasOpenRouterKey) ErrorKind.OFFLINE else ErrorKind.MISSING_KEY,
            "Captions need internet, or an on-device Gemma model to work offline."
        )
        val pcmFile = File(context.cacheDir, "captions_${System.nanoTime()}.pcm")
        val out = File(context.cacheDir, "${outBaseName}.mp4")
        try {
            onStatus(CaptionStatus.Working("Captions: reading the audio…", null))
            val audio = AudioExtractor(context).extract(source, pcmFile) { f -> onStatus(CaptionStatus.Working("Captions: reading the audio…", f)) }
            if (audio.frameRms.isEmpty()) throw NethraException(ErrorKind.NO_AUDIO, "The video has no sound to caption.")

            val fps = 1000 / PcmAudio.FRAME_MS
            val ranges = if (local) AudioChunks.plan(audio.frameRms, targetFrames = AiConfig.LOCAL_AUDIO_CHUNK_SECONDS * fps, searchFrames = 6 * fps)
            else AudioChunks.plan(audio.frameRms, targetFrames = CHUNK_SECONDS * fps, searchFrames = 8 * fps)
            val speech = CaptionBuilder.speechMask(audio.frameRms)
            val segments = mutableListOf<TimedSegment>()
            ranges.forEachIndexed { i, r ->
                val where = if (local) " on the phone" else ""
                onStatus(CaptionStatus.Working("Captions: transcribing$where, part ${i + 1} of ${ranges.size}…", i.toFloat() / ranges.size))
                if (AudioChunks.peak(audio.frameRms, r) < SILENCE_PEAK) return@forEachIndexed
                val startMs = r.start.toLong() * PcmAudio.FRAME_MS
                val endMs = r.end.toLong() * PcmAudio.FRAME_MS
                if (local) {
                    val text = Transcriber.stripWrapper(gemma.transcribeAudio(AudioChunks.wavBytes(audio, r), localPrompt(language, captionScript)))
                    if (text.isNotBlank() && !text.equals(Transcriber.NO_SPEECH, ignoreCase = true)) {
                        segments += CaptionBuilder.fromText(text, speech, startMs, endMs)
                    }
                    return@forEachIndexed
                }
                val raw = transcribeTimed(AudioChunks.wavBytes(audio, r), language, captionScript)
                var segs = CaptionBuilder.parseSegments(raw, startMs, endMs - startMs)
                if (segs.isEmpty() && !raw.trimStart().startsWith("{") && !raw.trimStart().startsWith("[")) {
                    // The model answered with plain text: spread it over the speech in this part.
                    segs = CaptionBuilder.fromText(Transcriber.stripWrapper(raw), speech, startMs, endMs)
                }
                segments += segs
            }
            val cards = CaptionBuilder.cards(CaptionBuilder.snapToSpeech(CaptionBuilder.dropCommands(segments), speech))
            if (cards.isEmpty()) throw NethraException(ErrorKind.NO_SPEECH, "No speech was found to caption.")

            val (w, h) = displaySize(source) ?: (1080 to 1920)
            onStatus(CaptionStatus.Working("Captions: adding them to the video…", 0f))
            val effects = Effects(
                ImmutableList.of<AudioProcessor>(),
                ImmutableList.of<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(CaptionOverlay(cards, w, h))))
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setEffects(effects).build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(item)).build()).build()
            TransformerRunner.export(context, composition, out) { p -> onStatus(CaptionStatus.Working("Captions: adding them to the video…", p)) }
            if (!out.exists() || out.length() == 0L) throw IllegalStateException("the captioned file is empty")

            onStatus(CaptionStatus.Working("Captions: saving to Movies/NETHRA…", null))
            val name = "$outBaseName.mp4"
            val uri = store.saveCopy(out, name)
            val srtSaved = runCatching { store.saveText(CaptionBuilder.toSrt(cards), "$outBaseName.srt", "application/x-subrip") }.isSuccess
            return CaptionStatus.Done(uri, name, cards.size, srtSaved)
        } finally {
            pcmFile.delete()
            out.delete()
        }
    }

    private suspend fun transcribeTimed(wav: ByteArray, language: ScriptLanguage, mode: CaptionScript): String {
        val b64 = withContext(Dispatchers.Default) { Base64.encodeToString(wav, Base64.NO_WRAP) }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt(language, mode)))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", b64).put("format", "wav")))
        val result = client.chat(
            model = AiConfig.TRANSCRIPTION_MODEL,
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            maxTokens = 6000, temperature = 0.0, retries = 2
        )
        return result.text
    }

    /** Width/height as displayed (rotation applied): the frame size the overlay is drawn on. */
    private fun displaySize(uri: Uri): Pair<Int, Int>? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 == 0) w to h else h to w
        }
    }.onFailure { Log.w(TAG, "No video size: ${it.javaClass.simpleName}") }.getOrNull()

    companion object {
        /** The caption prompt, told which language to expect and how to write it. Public for tests. */
        fun prompt(language: ScriptLanguage, mode: CaptionScript): String = PROMPT + "\n" + languageRules(language, mode)

        /** Offline (Gemma): plain words only — timing comes from the waveform, not the model. */
        fun localPrompt(language: ScriptLanguage, mode: CaptionScript): String =
            """
            Transcribe this audio for on-screen video captions.
            Reply with ONLY the words, as plain text on one line. No timestamps, labels or commentary.
            - Normal punctuation. Leave out filler sounds (um, uh).
            - If there is no speech, reply exactly ${Transcriber.NO_SPEECH}
            """.trimIndent() + "\n" + languageRules(language, mode)

        private fun languageRules(language: ScriptLanguage, mode: CaptionScript): String = buildString {
            if (!language.hasScriptChoice) {
                append("- Exact words as spoken. Do not translate, summarise or correct.")
                return@buildString
            }
            val lang = language.promptName
            append("- The speaker talks in $lang, often mixing in English words.\n")
            append(
                when (mode) {
                    CaptionScript.ORIGINAL ->
                        "- Exact words as spoken, $lang in its own script, English words in English letters. Do not translate, summarise or correct."
                    CaptionScript.MIXED ->
                        "- Exact words as spoken, written in ${language.mixedName}: $lang in English (Latin) letters the way " +
                            "$lang speakers type in chat, English words spelled normally. No ${if (language == ScriptLanguage.TELUGU) "Telugu" else "Devanagari"} script. Do not translate, summarise or correct."
                    CaptionScript.TRANSLATED ->
                        "- Translate each segment into short, natural English, as a subtitle would. Keep each segment's " +
                            "start and end at the time the ORIGINAL words were spoken, so the English appears in sync with the speech. " +
                            "Keep names and English words as they are."
                }
            )
        }

        private const val TAG = "CaptionMaker"
        /** Shorter parts give the model tighter timestamps. */
        private const val CHUNK_SECONDS = 40
        private const val SILENCE_PEAK = 40f

        val PROMPT = """
            Transcribe this audio for on-screen video captions, with timestamps.
            Reply with ONLY this JSON, no markdown:
            {"segments":[{"start":0.0,"end":1.8,"text":"..."}]}
            - start/end: seconds from the start of THIS audio clip, accurate to 0.1 s, when the phrase is actually spoken.
            - Each segment is a short phrase of 2 to 7 words that ends at a natural pause.
            - Normal punctuation. No speaker labels. Leave out filler sounds (um, uh).
            - If there is no speech, reply {"segments":[]}
        """.trimIndent()
    }
}

