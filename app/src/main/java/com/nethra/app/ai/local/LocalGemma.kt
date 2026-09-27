package com.nethra.app.ai.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.nethra.app.config.AiConfig
import com.nethra.app.core.ErrorKind
import com.nethra.app.core.NethraException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds the optional Gemma `.litertlm` file. Nothing is bundled in the APK.
 *
 * Search order:
 *  1. /sdcard/Android/data/com.nethra.app/files/model/  (adb push or in-app import)
 *  2. /data/local/tmp/nethra/                            (adb push, developer builds)
 */
class ModelLocator(private val context: Context) {

    val appModelDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, AiConfig.LOCAL_MODEL_DIR_NAME).apply { mkdirs() }

    private val searchDirs: List<File>
        get() = listOf(appModelDir, File("/data/local/tmp/nethra"))

    fun find(): File? = searchDirs.asSequence()
        .mapNotNull { dir ->
            runCatching {
                dir.listFiles { f -> f.isFile && f.canRead() && f.name.endsWith(AiConfig.LOCAL_MODEL_EXTENSION, true) }
                    ?.maxByOrNull { it.length() }
            }.getOrNull()
        }
        .firstOrNull()

    /** Copies a picked `.litertlm` file into [appModelDir] (a multi-GB copy — runs on IO). */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "gemma${AiConfig.LOCAL_MODEL_EXTENSION}"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (!name.endsWith(AiConfig.LOCAL_MODEL_EXTENSION, true)) {
            throw NethraException(ErrorKind.LOCAL_MODEL, "That file isn't a LiteRT-LM model. Pick a file ending in ${AiConfig.LOCAL_MODEL_EXTENSION}.")
        }
        val target = File(appModelDir, name)
        val partial = File(appModelDir, "$name.part")
        try {
            resolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        copied += r
                        if (size > 0) onProgress((copied.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            } ?: throw NethraException(ErrorKind.LOCAL_MODEL, "Couldn't open the selected model file.")
            // Only one model is used; remove older ones so they don't waste storage.
            appModelDir.listFiles { f -> f.name.endsWith(AiConfig.LOCAL_MODEL_EXTENSION, true) && f != target }?.forEach { it.delete() }
            if (!partial.renameTo(target)) throw NethraException(ErrorKind.LOCAL_MODEL, "Couldn't save the model file.")
            target
        } catch (e: Throwable) {
            partial.delete()
            if (e is NethraException || e is CancellationException) throw e
            throw NethraException(ErrorKind.LOCAL_MODEL, "Model import failed: ${e.message ?: e.javaClass.simpleName}. Check free storage.", e)
        }
    }
}

sealed interface LocalModelState {
    data object NotInstalled : LocalModelState
    data class Available(val file: File) : LocalModelState
    data object Loading : LocalModelState
    data class Ready(val file: File, val backend: String) : LocalModelState
    data class Failed(val message: String) : LocalModelState
}

/**
 * Optional on-device Gemma via LiteRT-LM. Loaded lazily on first use (loading
 * takes seconds and a few GB of RAM) and closed when the app is trimmed.
 * Used when the cloud can't be reached: publish-kit text, and — with a Gemma build that has
 * the audio encoder — offline transcription for the Transcript screen and captions.
 */
class LocalGemma(private val context: Context, private val locator: ModelLocator) {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedFile: File? = null
    /** The loaded engine accepts audio (its model has an audio encoder and the audio backend started). */
    @Volatile private var audioReady = false
    /** A model file already found to have no audio encoder — refused at once next time, no reload. */
    @Volatile private var noAudioFile: File? = null

    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.NotInstalled)
    val state: StateFlow<LocalModelState> = _state

    fun refresh() {
        if (_state.value is LocalModelState.Ready || _state.value is LocalModelState.Loading) return
        _state.value = locator.find()?.let { LocalModelState.Available(it) } ?: LocalModelState.NotInstalled
    }

    val isInstalled: Boolean get() = locator.find() != null

    suspend fun generate(systemPrompt: String, userPrompt: String, temperature: Double = 0.6): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val eng = ensureEngine()
                try {
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0)
                    )
                    eng.createConversation(config).use { conversation ->
                        val reply = conversation.sendMessage(userPrompt)
                        reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                    }.ifBlank { throw NethraException(ErrorKind.LOCAL_MODEL, "Gemma returned an empty answer.") }
                } catch (e: NethraException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Gemma inference failed: ${e.javaClass.simpleName}")
                    throw NethraException(ErrorKind.LOCAL_MODEL, "On-device Gemma failed: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        }

    /**
     * Transcribes one short WAV clip (16 kHz mono, ≤ ~30 s) on the phone.
     * @throws NethraException when no model is installed or the model can't hear audio.
     */
    suspend fun transcribeAudio(wav: ByteArray, instruction: String): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                locator.find()?.let { if (it == noAudioFile) throw noAudio(it) }
                val eng = ensureEngine()
                if (!audioReady) throw noAudio(loadedFile)
                try {
                    val config = ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)
                    )
                    eng.createConversation(config).use { conversation ->
                        val reply = conversation.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text(instruction)))
                        reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The engine starts fine with an audio backend even when the file has no audio
                    // encoder; it only fails here ("TF_LITE_AUDIO_ENCODER_HW not found in the model").
                    if (e.message.orEmpty().contains("AUDIO_ENCODER", ignoreCase = true)) {
                        noAudioFile = loadedFile
                        throw noAudio(loadedFile)
                    }
                    Log.w(TAG, "Gemma audio failed: ${e.javaClass.simpleName}")
                    throw NethraException(ErrorKind.LOCAL_MODEL, "On-device transcription failed: ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        }

    private fun noAudio(file: File?) = NethraException(
        ErrorKind.LOCAL_MODEL,
        "The installed model (${file?.name ?: "Gemma"}) has no audio encoder, so it can write text but can't hear. " +
            "For offline transcripts and captions, import gemma-3n-E2B-it-int4.litertlm or gemma-3n-E4B-it-int4.litertlm " +
            "(Hugging Face, litert-community). It replaces this model and still writes the publish kit."
    )

    private fun ensureEngine(): Engine {
        val file = locator.find() ?: run {
            _state.value = LocalModelState.NotInstalled
            throw NethraException(ErrorKind.LOCAL_MODEL, "No Gemma model installed. See model/README.md for how to add one.")
        }
        engine?.let { if (file == loadedFile) return it }
        closeEngine()
        _state.value = LocalModelState.Loading

        val backends = buildList {
            if (AiConfig.LOCAL_PREFER_GPU) add("GPU" to { Backend.GPU() })
            add("CPU" to { Backend.CPU() })
        }
        var lastError: Throwable? = null
        // Try with the audio encoder first (audio always runs on CPU); a text-only model
        // refuses that, so each backend is retried without audio.
        for ((name, backend) in backends) for (withAudio in listOf(true, false)) {
            try {
                val eng = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backend(),
                        audioBackend = if (withAudio) Backend.CPU() else null,
                        maxNumTokens = AiConfig.LOCAL_MAX_TOKENS,
                        cacheDir = context.cacheDir.absolutePath
                    )
                )
                eng.initialize()
                engine = eng; loadedFile = file; audioReady = withAudio
                _state.value = LocalModelState.Ready(file, if (withAudio) "$name + audio" else name)
                Log.i(TAG, "Gemma loaded on $name (audio=$withAudio)")
                return eng
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Gemma failed to load on $name (audio=$withAudio): ${e.javaClass.simpleName}")
            }
        }
        val msg = "Couldn't load ${file.name}: ${lastError?.message ?: "unknown error"}. " +
            "Use a Gemma .litertlm build made for Android, and close other apps to free memory."
        _state.value = LocalModelState.Failed(msg)
        throw NethraException(ErrorKind.LOCAL_MODEL, msg, lastError)
    }

    /** Frees the multi-GB engine; it reloads on next use. Skipped while a generation is running. */
    fun release() {
        if (!mutex.tryLock()) return
        try { closeEngine() } finally { mutex.unlock() }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null; loadedFile = null; audioReady = false
        if (_state.value is LocalModelState.Ready) refreshAfterClose()
    }

    private fun refreshAfterClose() {
        _state.value = locator.find()?.let { LocalModelState.Available(it) } ?: LocalModelState.NotInstalled
    }

    companion object { private const val TAG = "LocalGemma" }
}
