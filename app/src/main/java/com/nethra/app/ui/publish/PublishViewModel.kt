package com.nethra.app.ui.publish

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethra.app.AppContainer
import com.nethra.app.ai.PublishKit
import com.nethra.app.ai.TranscribeProgress
import com.nethra.app.ai.Transcript
import com.nethra.app.ai.local.LocalModelState
import com.nethra.app.core.NethraException
import com.nethra.app.core.userMessage
import com.nethra.app.persist.DraftStore
import com.nethra.app.share.Platform
import com.nethra.app.share.PlatformHandoff
import com.nethra.app.share.YouTubeAuth
import com.nethra.app.share.YouTubeMetadata
import com.nethra.app.share.YouTubePrivacy
import com.nethra.app.share.YouTubeUploadException
import com.nethra.app.share.YouTubeUploadResult
import com.nethra.app.share.YouTubeUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PublishUi(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val videoDurationMs: Long? = null,
    val pickError: String? = null,

    val transcribing: Boolean = false,
    val progress: TranscribeProgress? = null,
    /** Chunk-level result of this session (null after restoring a draft). */
    val transcript: Transcript? = null,
    val transcriptText: String = "",
    val transcriptComplete: Boolean = false,
    val transcribeError: String? = null,

    val kit: PublishKit? = null,
    val kitGenerating: Boolean = false,
    val kitError: String? = null,
    /** The cloud kit failed for a reason on-device Gemma could work around. */
    val offerLocal: Boolean = false,
    val importProgress: Float? = null,
    val importError: String? = null,

    val message: String? = null,

    // ---- direct YouTube upload (title, description and tags filled in by the API)
    val ytPrivacy: YouTubePrivacy = YouTubePrivacy.PRIVATE,
    val ytUploading: Boolean = false,
    val ytSent: Long = 0,
    val ytTotal: Long = 0,
    val ytResult: YouTubeUploadResult? = null,
    val ytError: String? = null,
    /** Google's consent screen that the UI must launch (first upload, or after access was revoked). */
    val ytConsent: PendingIntent? = null
) {
    val busy get() = transcribing || kitGenerating || importProgress != null || ytUploading
    val ytProgress: Float? get() = if (ytTotal > 0) ytSent.toFloat() / ytTotal else null
    val failedParts get() = transcript?.failedChunks?.size ?: 0
}

/**
 * Picked video → transcript → publishing kit → handoff. The picked video is
 * only ever read; nothing is uploaded except the extracted audio (to the
 * transcription model) and the transcript text (to the kit model).
 */
class PublishViewModel(private val app: Application, private val c: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(PublishUi())
    val ui: StateFlow<PublishUi> = _ui
    val gemmaState: StateFlow<LocalModelState> = c.gemma.state
    val online: StateFlow<Boolean> = c.network.online

    private var work: Job? = null
    private var uploadJob: Job? = null
    private val uploader = YouTubeUploader(app)
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val d = c.drafts.loadPublish() ?: return@launch
            if (_ui.value.videoUri != null) return@launch
            val uri = d.videoUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            // Only restore the video if Android still lets us read it.
            val readable = uri != null && app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            _ui.update {
                it.copy(
                    videoUri = if (readable) uri else null,
                    videoName = if (readable) d.videoName else "",
                    transcriptText = d.transcript,
                    transcriptComplete = d.transcriptComplete,
                    kit = d.kit,
                    message = if (!readable && uri != null) "Your last transcript was restored, but pick the video again to share it." else null
                )
            }
        }
        c.gemma.refresh()
    }

    // ------------------------------------------------------------ video

    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        if (_ui.value.busy) return
        val type = app.contentResolver.getType(uri)
        if (type != null && !type.startsWith("video/")) {
            _ui.update { it.copy(pickError = "That file is $type, not a video. Pick an MP4, MOV, WebM or 3GP video.") }
            return
        }
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        c.transcriber.release()
        uploader.resetSession()
        _ui.value = PublishUi(videoUri = uri, videoName = "Selected video", ytPrivacy = _ui.value.ytPrivacy)
        viewModelScope.launch {
            val (name, duration) = withContext(Dispatchers.IO) { c.videos.displayName(uri) to c.videos.durationMs(uri) }
            _ui.update { if (it.videoUri == uri) it.copy(videoName = name ?: it.videoName, videoDurationMs = duration) else it }
            saveDraftSoon()
        }
    }

    // ------------------------------------------------------------ transcript

    fun transcribe() = runTranscription(retry = false)

    fun retryFailed() = runTranscription(retry = true)

    private fun runTranscription(retry: Boolean) {
        val s = _ui.value
        val uri = s.videoUri ?: return
        if (s.busy) return
        val previous = s.transcript
        _ui.update { it.copy(transcribing = true, progress = TranscribeProgress.Extracting(0f), transcribeError = null) }
        work = viewModelScope.launch {
            try {
                val onProgress: (TranscribeProgress) -> Unit = { p -> _ui.update { it.copy(progress = p) } }
                val t = if (retry && previous != null) c.transcriber.retryFailed(uri, previous, onProgress)
                else c.transcriber.transcribe(uri, onProgress)
                _ui.update {
                    it.copy(
                        transcript = t, transcriptText = t.fullText, transcriptComplete = t.isComplete,
                        transcribeError = if (t.isComplete) null
                        else "${t.failedChunks.size} of ${t.chunks.size} parts couldn't be transcribed — they're marked in the text. Retry them when you're ready."
                    )
                }
                saveDraftSoon()
            } catch (e: CancellationException) {
                _ui.update { it.copy(transcribeError = "Transcription cancelled.") }
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(transcribeError = e.userMessage()) }
            } finally {
                _ui.update { it.copy(transcribing = false, progress = null) }
            }
        }
    }

    fun cancel() { work?.cancel() }

    // ------------------------------------------------------------ kit

    fun generateKit() {
        val s = _ui.value
        if (s.busy) return
        _ui.update { it.copy(kitGenerating = true, kitError = null, offerLocal = false) }
        work = viewModelScope.launch {
            try {
                val kit = c.publishKits.generateCloud(_ui.value.transcriptText)
                _ui.update { it.copy(kit = kit) }
                saveDraftSoon()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cloudDown = (e as? NethraException)?.isCloudUnavailable == true
                if (cloudDown) c.gemma.refresh()
                _ui.update { it.copy(kitError = e.userMessage(), offerLocal = cloudDown) }
            } finally {
                _ui.update { it.copy(kitGenerating = false) }
            }
        }
    }

    fun generateKitLocally() {
        val s = _ui.value
        if (s.busy) return
        _ui.update { it.copy(kitGenerating = true, kitError = null) }
        work = viewModelScope.launch {
            try {
                val kit = c.publishKits.generateLocal(_ui.value.transcriptText)
                _ui.update { it.copy(kit = kit, offerLocal = false) }
                saveDraftSoon()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(kitError = e.userMessage()) }
            } finally {
                _ui.update { it.copy(kitGenerating = false) }
            }
        }
    }

    fun importModel(uri: Uri?) {
        if (uri == null || _ui.value.busy) return
        _ui.update { it.copy(importProgress = 0f, importError = null) }
        work = viewModelScope.launch {
            try {
                c.modelLocator.import(uri) { p -> _ui.update { it.copy(importProgress = p) } }
                c.gemma.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(importError = e.userMessage()) }
            } finally {
                _ui.update { it.copy(importProgress = null) }
            }
        }
    }

    fun updateKit(kit: PublishKit) {
        // Edited text must go into a new upload session (metadata is sent when a session starts).
        if (!_ui.value.ytUploading) uploader.resetSession()
        _ui.update { it.copy(kit = kit) }
        saveDraftSoon()
    }

    // ------------------------------------------------------------ share

    fun copy(label: String, text: String) {
        PlatformHandoff.copy(app, label, text)
        _ui.update { it.copy(message = "$label copied.") }
    }

    fun handOff(platform: Platform) {
        val s = _ui.value
        val uri = s.videoUri ?: run { _ui.update { it.copy(message = "Pick the video again first.") }; return }
        val kit = s.kit ?: return
        val text = if (platform == Platform.YOUTUBE) kit.youtubeClipboard() else kit.instagramClipboard()
        val msg = try {
            PlatformHandoff.handOff(app, platform, uri, text, title = if (platform == Platform.YOUTUBE) kit.youtube.title else null)
        } catch (e: Exception) {
            "Couldn't open ${platform.label}: ${e.message ?: e.javaClass.simpleName}. The text is still on your clipboard."
        }
        _ui.update { it.copy(message = msg) }
    }

    fun isInstalled(p: Platform) = PlatformHandoff.isInstalled(app, p)

    // ------------------------------------------------------------ YouTube upload

    fun setYouTubePrivacy(p: YouTubePrivacy) {
        if (_ui.value.ytUploading) return
        // Privacy is part of the upload session's metadata: a change starts a fresh session.
        uploader.resetSession()
        _ui.update { it.copy(ytPrivacy = p) }
    }

    /**
     * Uploads the picked video to the signed-in channel with the kit's title,
     * description (+ hashtags) and tags. Calling it again after a failure or
     * Cancel continues the same upload where it stopped.
     */
    fun uploadToYouTube() {
        val s = _ui.value
        if (s.videoUri == null) { _ui.update { it.copy(ytError = "Pick the video again first.") }; return }
        if (s.kit == null || s.busy) return
        _ui.update { it.copy(ytUploading = true, ytError = null, ytResult = null) }
        uploadJob = viewModelScope.launch { authorizeThenUpload() }
    }

    private suspend fun authorizeThenUpload() {
        when (val o = YouTubeAuth.authorize(app)) {
            is YouTubeAuth.Outcome.Token -> upload(o.accessToken)
            is YouTubeAuth.Outcome.NeedsConsent -> _ui.update { it.copy(ytConsent = o.intent, ytUploading = false) }
            is YouTubeAuth.Outcome.Failed -> _ui.update { it.copy(ytError = o.message, ytUploading = false) }
        }
    }

    /** The screen launched [PublishUi.ytConsent]; this is its result. */
    fun onYouTubeConsent(data: Intent?) {
        _ui.update { it.copy(ytConsent = null) }
        when (val o = YouTubeAuth.tokenFromConsent(app, data)) {
            is YouTubeAuth.Outcome.Token -> {
                _ui.update { it.copy(ytUploading = true, ytError = null) }
                uploadJob = viewModelScope.launch { upload(o.accessToken) }
            }
            is YouTubeAuth.Outcome.Failed -> _ui.update { it.copy(ytError = o.message) }
            is YouTubeAuth.Outcome.NeedsConsent -> _ui.update { it.copy(ytError = "YouTube access wasn't granted.") }
        }
    }

    fun onYouTubeConsentCancelled() = _ui.update { it.copy(ytConsent = null, ytError = "YouTube sign-in was cancelled.") }

    fun cancelYouTubeUpload() { uploadJob?.cancel() }

    private suspend fun upload(firstToken: String) {
        val s = _ui.value
        val uri = s.videoUri ?: return
        val kit = s.kit ?: return
        val fields = YouTubeMetadata.fields(
            kit.youtube.title, kit.youtube.description, kit.youtube.tags, kit.youtube.hashtags,
            fallbackTitle = s.videoName.substringBeforeLast('.')
        )
        val body = YouTubeMetadata.requestBody(fields, s.ytPrivacy)
        var token = firstToken
        var refreshed = false
        try {
            while (true) {
                try {
                    val r = uploader.upload(uri, body, token) { sent, total -> _ui.update { it.copy(ytSent = sent, ytTotal = total) } }
                    val kept = r.privacy.isNotBlank() && r.privacy != s.ytPrivacy.apiValue
                    _ui.update {
                        it.copy(
                            ytResult = r,
                            message = "Uploaded to YouTube with its title, description and ${fields.tags.size} tags." +
                                if (kept) " YouTube set it to ${r.privacy} — change it in YouTube Studio." else ""
                        )
                    }
                    return
                } catch (e: YouTubeUploadException) {
                    if (!e.authExpired || refreshed) throw e
                    // The token expired mid-upload: get a new one and continue the same session.
                    refreshed = true
                    YouTubeAuth.clearToken(app, token)
                    when (val o = YouTubeAuth.authorize(app)) {
                        is YouTubeAuth.Outcome.Token -> token = o.accessToken
                        is YouTubeAuth.Outcome.NeedsConsent -> { _ui.update { it.copy(ytConsent = o.intent) }; return }
                        is YouTubeAuth.Outcome.Failed -> throw YouTubeUploadException(o.message)
                    }
                }
            }
        } catch (e: CancellationException) {
            _ui.update { it.copy(ytError = "Upload paused. Tap Upload to continue where it stopped.") }
            throw e
        } catch (e: YouTubeUploadException) {
            _ui.update { it.copy(ytError = e.message) }
        } catch (e: Exception) {
            _ui.update { it.copy(ytError = "YouTube upload failed: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _ui.update { it.copy(ytUploading = false) }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null, pickError = null) }

    private fun saveDraftSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val s = _ui.value
            c.drafts.savePublish(
                DraftStore.PublishDraft(s.videoUri?.toString().orEmpty(), s.videoName, s.transcriptText, s.transcriptComplete, s.kit)
            )
        }
    }

    override fun onCleared() {
        work?.cancel()
        uploadJob?.cancel()
        c.transcriber.release()
    }
}
