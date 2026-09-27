package com.nethra.app.ui.publish

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nethra.app.ai.PublishKit
import com.nethra.app.ai.PublishKitParser
import com.nethra.app.ai.TranscribeProgress
import com.nethra.app.ai.Transcript
import com.nethra.app.ai.local.LocalModelState
import com.nethra.app.config.AiConfig
import com.nethra.app.share.Platform
import com.nethra.app.share.YouTubePrivacy
import com.nethra.app.ui.components.IosSegmented
import com.nethra.app.ui.components.GlassButton
import com.nethra.app.ui.components.GlassCard
import com.nethra.app.ui.components.GlassIconButton
import com.nethra.app.ui.components.ProgressLine
import com.nethra.app.ui.components.StatusPill
import com.nethra.app.ui.nethraViewModel
import com.nethra.app.ui.theme.NethraColors

@Composable
fun PublishScreen(onExit: () -> Unit) {
    val vm = nethraViewModel { app, c -> PublishViewModel(app, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val gemma by vm.gemmaState.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()

    // System file picker: no storage permission needed, access is granted per file.
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onVideoPicked(it) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importModel(it) }
    // Google's one-time "Allow NETHRA to upload videos to YouTube" consent screen.
    val youTubeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.onYouTubeConsent(res.data) else vm.onYouTubeConsentCancelled()
    }
    LaunchedEffect(ui.ytConsent) {
        ui.ytConsent?.let { youTubeConsent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }

    Box(
        Modifier.fillMaxSize().background(NethraColors.Background)
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onExit)
                Spacer(Modifier.width(12.dp))
                Text("Transcript & publish kit", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            if (!online) StatusPill(
                if (gemma is LocalModelState.NotInstalled) "Offline — add an on-device Gemma model (Gemma 3n / Gemma 4 E4B) to transcribe without internet."
                else "Offline — transcripts and the kit will run on the phone with Gemma. Slower, and nothing leaves the phone.",
                NethraColors.Warn
            )

            // ---------------------------------------------------------- video
            GlassCard(Modifier.fillMaxWidth()) {
                Title("1 · Video")
                if (ui.videoUri == null) {
                    Body("Pick a video from this phone. It is only read — NETHRA never changes or re-saves it.")
                } else {
                    Text(ui.videoName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    ui.videoDurationMs?.let { Body("Length ${Transcript.fmt(it)}") }
                }
                ui.pickError?.let { Error(it) }
                Spacer(Modifier.height(10.dp))
                GlassButton(
                    if (ui.videoUri == null) "Pick video" else "Pick another video",
                    { pickVideo.launch(arrayOf("video/*")) }, enabled = !ui.busy, icon = Icons.Filled.FileOpen
                )
            }

            // ---------------------------------------------------------- transcript
            if (ui.videoUri != null || ui.transcriptText.isNotBlank()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Title("2 · Transcript")
                    Body(
                        if (online) "The audio is extracted on the phone, split into ~${AiConfig.TRANSCRIPTION_CHUNK_SECONDS / 60}-minute parts " +
                            "at pauses, and transcribed word for word in the spoken language by ${AiConfig.TRANSCRIPTION_MODEL}. Not summarised."
                        else "Offline: the audio is transcribed on the phone by Gemma in ~${AiConfig.LOCAL_AUDIO_CHUNK_SECONDS} s parts. " +
                            "Nothing leaves the phone. Needs a Gemma build with audio (Gemma 3n E2B / E4B)."
                    )
                    Spacer(Modifier.height(10.dp))
                    if (ui.transcribing) {
                        ProgressLine(progressLabel(ui.progress), progressValue(ui.progress))
                        Spacer(Modifier.height(8.dp))
                        GlassButton("Cancel", vm::cancel)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(
                                if (ui.transcriptText.isBlank()) "Transcribe" else "Transcribe again", vm::transcribe,
                                enabled = ui.videoUri != null && !ui.busy, icon = Icons.Filled.Subtitles, accent = ui.transcriptText.isBlank()
                            )
                            if (ui.failedParts > 0) {
                                GlassButton("Retry ${ui.failedParts} failed", vm::retryFailed, enabled = !ui.busy, icon = Icons.Filled.Refresh)
                            }
                        }
                    }
                    ui.transcribeError?.let { Error(it) }
                    if (ui.transcriptText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        if (!ui.transcriptComplete) Warn("This transcript is incomplete — missing parts are marked in [brackets].")
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                .background(Color(0x33000000), MaterialTheme.shapes.medium)
                                .verticalScroll(rememberScrollState()).padding(10.dp)
                        ) {
                            SelectionContainer { Text(ui.transcriptText, color = Color.White, fontSize = 14.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton("Copy transcript", { vm.copy("Transcript", ui.transcriptText) }, icon = Icons.Filled.ContentCopy)
                            Body("${ui.transcriptText.length} chars")
                        }
                    }
                }
            }

            // ---------------------------------------------------------- kit
            if (ui.transcriptText.isNotBlank()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Title("3 · Publishing kit")
                    Body("Written from the transcript by ${AiConfig.PUBLISH_KIT_MODEL}. Edit anything before sharing.")
                    Spacer(Modifier.height(10.dp))
                    if (ui.kitGenerating) {
                        ProgressLine(if (gemma is LocalModelState.Loading) "Loading Gemma on the phone…" else "Writing titles, captions and tags…", null)
                    } else {
                        GlassButton(
                            if (ui.kit == null) "Create kit" else "Create again", vm::generateKit,
                            enabled = !ui.busy, icon = Icons.Filled.AutoAwesome, accent = ui.kit == null
                        )
                    }
                    ui.kitError?.let { Error(it) }
                    if (ui.offerLocal && !ui.kitGenerating) LocalOffer(ui, gemma, vm) { pickModel.launch(arrayOf("*/*")) }
                }
                ui.kit?.let { KitEditor(it, vm) }
            }

            ui.message?.let { m ->
                GlassCard(Modifier.fillMaxWidth(), strong = true, onClick = vm::dismissMessage) {
                    Text(m, color = Color.White, fontSize = 14.sp)
                    Body("Tap to dismiss")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LocalOffer(ui: PublishUi, gemma: LocalModelState, vm: PublishViewModel, onImport: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text("Use on-device Gemma instead?", color = Color.White, fontWeight = FontWeight.SemiBold)
    val long = ui.transcriptText.length > AiConfig.LOCAL_TRANSCRIPT_CHAR_LIMIT
    Body(
        "Runs on the phone without internet. It's smaller than the cloud model, so check the result." +
            if (long) " This transcript is long; Gemma only reads the first ${AiConfig.LOCAL_TRANSCRIPT_CHAR_LIMIT} characters." else ""
    )
    Spacer(Modifier.height(8.dp))
    when (gemma) {
        LocalModelState.NotInstalled -> {
            Body("No Gemma model is installed. Import a .litertlm file (see model/README.md), or push one with adb.")
            ui.importProgress?.let { ProgressLine("Copying model…", it) }
                ?: GlassButton("Import Gemma model", onImport, enabled = !ui.busy, icon = Icons.Filled.FileOpen)
        }
        is LocalModelState.Failed -> {
            Error(gemma.message)
            GlassButton("Try Gemma again", vm::generateKitLocally, enabled = !ui.busy, icon = Icons.Filled.Memory)
        }
        else -> GlassButton("Create kit with Gemma", vm::generateKitLocally, enabled = !ui.busy, icon = Icons.Filled.Memory)
    }
    ui.importError?.let { Error(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitEditor(kit: PublishKit, vm: PublishViewModel) {
    val yt = kit.youtube
    val ig = kit.instagram
    GlassCard(Modifier.fillMaxWidth()) {
        Title("YouTube")
        if (kit.source.isNotBlank()) Body("Written by ${kit.source}")
        KitField("Title", yt.title, PublishKit.YT_TITLE_MAX, singleLine = true) { vm.updateKit(kit.copy(youtube = yt.copy(title = it))) }
        KitField("Description", yt.description, PublishKit.YT_DESCRIPTION_MAX) { vm.updateKit(kit.copy(youtube = yt.copy(description = it))) }
        val tagText = yt.tags.joinToString(", ")
        ListField("Tags (comma separated)", yt.tags, ", ", PublishKit.YT_TAGS_MAX_CHARS, ::parseTags) {
            vm.updateKit(kit.copy(youtube = yt.copy(tags = it)))
        }
        ListField("Hashtags", yt.hashtags, " ", null, ::parseHashtags) {
            vm.updateKit(kit.copy(youtube = yt.copy(hashtags = it)))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Copy title", { vm.copy("YouTube title", yt.title) }, icon = Icons.Filled.ContentCopy)
            GlassButton("Copy description", { vm.copy("YouTube description", yt.description) }, icon = Icons.Filled.ContentCopy)
            GlassButton("Copy tags", { vm.copy("YouTube tags", tagText) }, icon = Icons.Filled.ContentCopy)
            GlassButton("Copy all", { vm.copy("YouTube text", kit.youtubeClipboard()) }, icon = Icons.Filled.ContentCopy)
        }
        Spacer(Modifier.height(12.dp))
        YouTubeUpload(yt.tags.size, vm)
        Spacer(Modifier.height(12.dp))
        HandoffButton(Platform.YOUTUBE, vm, primary = false)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Title("Instagram")
        KitField("Caption", ig.caption, PublishKit.IG_CAPTION_MAX) { vm.updateKit(kit.copy(instagram = ig.copy(caption = it))) }
        ListField("Hashtags (${ig.hashtags.size}/${PublishKit.IG_HASHTAGS_MAX})", ig.hashtags, " ", null, ::parseHashtags) {
            vm.updateKit(kit.copy(instagram = ig.copy(hashtags = it)))
        }
        if (ig.hashtags.size > PublishKit.IG_HASHTAGS_MAX) Warn("Instagram allows at most ${PublishKit.IG_HASHTAGS_MAX} hashtags.")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Copy caption", { vm.copy("Instagram caption", ig.caption) }, icon = Icons.Filled.ContentCopy)
            GlassButton("Copy hashtags", { vm.copy("Instagram hashtags", ig.hashtags.joinToString(" ")) }, icon = Icons.Filled.ContentCopy)
            GlassButton("Copy all", { vm.copy("Instagram text", kit.instagramClipboard()) }, icon = Icons.Filled.ContentCopy)
        }
        Spacer(Modifier.height(8.dp))
        HandoffButton(Platform.INSTAGRAM, vm)
    }
}

@Composable
private fun HandoffButton(p: Platform, vm: PublishViewModel, primary: Boolean = true) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    GlassButton(
        if (primary) "Hand off to ${p.label}" else "Open in the ${p.label} app instead", { vm.handOff(p) },
        enabled = ui.videoUri != null && !ui.ytUploading, icon = Icons.Filled.Share, accent = primary
    )
    Spacer(Modifier.height(4.dp))
    Body(
        (if (p == Platform.YOUTUBE)
            "The YouTube app doesn't accept a title, description or tags from other apps, so here the text is copied " +
                "to your clipboard and you paste it (use the Copy buttons above for one field at a time)."
        else
            "Handoff, not upload: the text is copied to your clipboard and the video opens in the ${p.label} app, " +
                "where you paste and post it yourself.") +
            if (!vm.isInstalled(p)) " ${p.label} isn't installed, so the share sheet opens instead." else ""
    )
}

/**
 * Direct upload through the YouTube Data API: title, description (with hashtags)
 * and tags are filled in on YouTube automatically.
 */
@Composable
private fun YouTubeUpload(tagCount: Int, vm: PublishViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    Text("Upload to YouTube", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Body("Title, description, hashtags and $tagCount tags are filled in on YouTube for you. You sign in with Google once.")
    Spacer(Modifier.height(8.dp))
    IosSegmented(
        YouTubePrivacy.entries.map { it.label }, ui.ytPrivacy.ordinal,
        { vm.setYouTubePrivacy(YouTubePrivacy.entries[it]) }, enabled = !ui.ytUploading
    )
    Spacer(Modifier.height(8.dp))
    when {
        ui.ytUploading -> {
            val mb = { b: Long -> "%.1f".format(b / 1_048_576f) }
            ProgressLine(
                ui.ytProgress?.let { "Uploading… ${(it * 100).toInt()}% (${mb(ui.ytSent)} of ${mb(ui.ytTotal)} MB)" } ?: "Signing in and starting the upload…",
                ui.ytProgress
            )
            Spacer(Modifier.height(8.dp))
            GlassButton("Pause upload", vm::cancelYouTubeUpload)
        }
        else -> GlassButton(
            if (ui.ytError != null && ui.ytTotal > 0 && ui.ytResult == null) "Continue upload" else "Upload to YouTube",
            vm::uploadToYouTube, enabled = ui.videoUri != null && !ui.busy, icon = Icons.Filled.CloudUpload, accent = true
        )
    }
    ui.ytError?.let { Error(it) }
    ui.ytResult?.let { r ->
        Spacer(Modifier.height(8.dp))
        Text("Uploaded ✓  ${r.watchUrl}", color = NethraColors.Good, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Open in YouTube Studio", { runCatching { uri.openUri(r.studioUrl) } }, icon = Icons.AutoMirrored.Filled.OpenInNew)
            GlassButton("Watch", { runCatching { uri.openUri(r.watchUrl) } })
        }
    }
    Spacer(Modifier.height(4.dp))
    Body(
        "Until Google audits your YouTube API project, YouTube keeps API uploads Private — switch them to Public " +
            "in YouTube Studio. The original file on the phone is only read."
    )
}

@Composable
private fun KitField(label: String, value: String, max: Int?, singleLine: Boolean = false, onChange: (String) -> Unit) {
    val over = max != null && value.length > max
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        label = { Text(label, fontSize = 12.sp) }, singleLine = singleLine,
        supportingText = if (max != null) {
            { Text("${value.length} / $max", color = if (over) NethraColors.Bad else NethraColors.TextDim, fontSize = 11.sp) }
        } else null,
        isError = over,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = NethraColors.Accent, unfocusedBorderColor = NethraColors.GlassBorder,
            focusedLabelColor = NethraColors.Accent, unfocusedLabelColor = NethraColors.TextDim,
            cursorColor = NethraColors.Accent
        )
    )
}

/** Edits a list as text; keeps the typed text (trailing spaces, half-typed tags) while the list follows it. */
@Composable
private fun ListField(label: String, items: List<String>, sep: String, max: Int?, parse: (String) -> List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(items.joinToString(sep)) }
    LaunchedEffect(items) { if (parse(text) != items) text = items.joinToString(sep) }
    KitField(label, text, max) { text = it; onChange(parse(it)) }
}

private fun parseTags(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
private fun parseHashtags(v: String) = PublishKitParser.normaliseHashtags(listOf(v))

private fun progressLabel(p: TranscribeProgress?): String = when (p) {
    is TranscribeProgress.Extracting -> "Extracting audio on the phone… ${(p.fraction * 100).toInt()}%"
    is TranscribeProgress.Transcribing ->
        if (p.onDevice) "Transcribing offline with Gemma on the phone — part ${p.chunk} of ${p.of}…"
        else "Transcribing part ${p.chunk} of ${p.of}…"
    null -> "Working…"
}

private fun progressValue(p: TranscribeProgress?): Float? = when (p) {
    is TranscribeProgress.Extracting -> p.fraction
    is TranscribeProgress.Transcribing -> (p.chunk - 1f) / p.of
    null -> null
}

@Composable private fun Title(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = Color.White)
@Composable private fun Body(t: String) = Text(t, color = NethraColors.TextDim, fontSize = 13.sp)
@Composable private fun Error(t: String) = Text(t, color = NethraColors.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
@Composable private fun Warn(t: String) = Text(t, color = NethraColors.Warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
