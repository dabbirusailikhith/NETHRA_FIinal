package com.nethra.app.config

/**
 * THE place to change which AI models NETHRA uses.
 *
 * There is deliberately no model-picker screen: edit a constant here, rebuild,
 * reinstall. Model IDs are OpenRouter IDs (https://openrouter.ai/models).
 */
object AiConfig {

    const val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"

    // ---------------------------------------------------------------- Scripts
    /**
     * Writes teleprompter scripts. CLOUD ONLY: if this call cannot be made the
     * app shows an error and keeps the brief — it never falls back to Gemma.
     */
    const val SCRIPT_MODEL = "anthropic/claude-sonnet-5"

    /**
     * Attach OpenRouter's web-search plugin to script requests so current or
     * factual claims can be checked. OpenRouter bills search results on top of
     * model tokens. Set to false to write from model knowledge only.
     */
    const val SCRIPT_WEB_RESEARCH = true
    const val SCRIPT_WEB_MAX_RESULTS = 5

    /** Spoken pace used to turn a requested duration into a word target. */
    const val SPOKEN_WORDS_PER_MINUTE = 150

    /** Length used when the brief does not say how long the video should be. */
    const val DEFAULT_SCRIPT_SECONDS = 60

    // ---------------------------------------------------------- Transcription
    /** Transcribes extracted video audio. Must accept audio input on OpenRouter. */
    const val TRANSCRIPTION_MODEL = "google/gemini-3.8-flash"

    /** Long videos are split into chunks of about this length (cut at quiet points). */
    const val TRANSCRIPTION_CHUNK_SECONDS = 120

    // ------------------------------------------------------------ Publish kit
    /** Writes the YouTube / Instagram publishing kit from the transcript. */
    const val PUBLISH_KIT_MODEL = "google/gemini-3.8-flash"

    // -------------------------------------------------- Optional local Gemma
    /**
     * Local Gemma (LiteRT-LM `.litertlm` file) is optional. When the phone is offline
     * (or has no cloud key) it transcribes audio for the Transcript screen and the
     * auto-captions, and writes the publish kit. It is never used for scripts.
     * Audio needs a Gemma build with the audio encoder (Gemma 3n / Gemma 4 E2B or E4B).
     */
    const val LOCAL_MODEL_EXTENSION = ".litertlm"
    const val LOCAL_MODEL_DIR_NAME = "model"
    const val LOCAL_PREFER_GPU = true
    const val LOCAL_MAX_TOKENS = 8192

    /** Gemma hears at most ~30 s of audio per message, so offline audio is cut into parts of about this length. */
    const val LOCAL_AUDIO_CHUNK_SECONDS = 24

    /** Gemma's context is far smaller than the cloud model's; longer transcripts are truncated for it. */
    const val LOCAL_TRANSCRIPT_CHAR_LIMIT = 12_000

    /** Sent as OpenRouter's optional X-Title attribution header. */
    const val APP_TITLE = "NETHRA"
}
