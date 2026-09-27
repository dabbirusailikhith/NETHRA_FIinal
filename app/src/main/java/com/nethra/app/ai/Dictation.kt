package com.nethra.app.ai

import android.util.Base64
import com.nethra.app.config.AiConfig
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import com.nethra.app.media.AudioChunks
import com.nethra.app.speech.MicFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transcribes a short spoken instruction (a command or a script brief) with the
 * cloud transcription model — far more accurate than the phone's recogniser for
 * accents, fast speech, mixed languages and the name "Nethra".
 */
class Dictation(private val client: OpenRouterClient) {

    suspend fun transcribe(pcm: ByteArray): String {
        val b64 = withContext(Dispatchers.Default) {
            val wav = AudioChunks.wavHeader(pcm.size, MicFeed.SAMPLE_RATE) + pcm
            Base64.encodeToString(wav, Base64.NO_WRAP)
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PROMPT))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", b64).put("format", "wav")))
        val result = client.chat(
            model = AiConfig.TRANSCRIPTION_MODEL,
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            maxTokens = 1000, temperature = 0.0, retries = 1
        )
        val text = Transcriber.stripWrapper(result.text).trim().trim('"')
        if (text.isBlank() || text.equals(Transcriber.NO_SPEECH, ignoreCase = true)) {
            throw NethraException(ErrorKind.NO_SPEECH, "I didn't catch that. Say it again a little closer to the phone.")
        }
        return text
    }

    companion object {
        val PROMPT = """
            Transcribe this short voice instruction exactly as spoken.
            Context: the speaker is talking to NETHRA (pronounced "Neh-thra"), a video-recording app. They are either giving a
            command (start/stop/pause/resume recording) or describing a video script they want written: topic, audience,
            platform (YouTube, Instagram Reels, Shorts), tone, duration, what to include or leave out, call to action.
            - Keep the spoken language(s); do not translate. Spell "Nethra" as Nethra.
            - Drop filler sounds (um, uh). Do not summarise or add anything.
            - If there is no speech, reply with exactly ${Transcriber.NO_SPEECH}
            Reply with the transcript only.
        """.trimIndent()
    }
}
