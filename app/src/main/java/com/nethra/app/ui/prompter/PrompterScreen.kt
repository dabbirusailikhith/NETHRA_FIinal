package com.nethra.app.ui.prompter

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nethra.app.media.CleanStatus
import com.nethra.app.teleprompter.ScrollMode
import com.nethra.app.ui.components.CameraFrame
import com.nethra.app.ui.components.CameraMode
import com.nethra.app.ui.components.CameraSetup
import com.nethra.app.ui.components.CaptionStatusCard
import com.nethra.app.ui.components.DeviceRotation
import com.nethra.app.ui.components.ImmersiveCameraWindow
import com.nethra.app.ui.components.IosCameraScaffold
import com.nethra.app.ui.components.IosCountdown
import com.nethra.app.ui.components.IosModeStrip
import com.nethra.app.ui.components.IosRecordTimer
import com.nethra.app.ui.components.IosRoundButton
import com.nethra.app.ui.components.IosShutterButton
import com.nethra.app.ui.components.IosShutterRow
import com.nethra.app.ui.components.IosSideButton
import com.nethra.app.ui.components.IosTopRow
import com.nethra.app.ui.components.PermissionExplainer
import com.nethra.app.ui.components.PermissionsState
import com.nethra.app.ui.components.ProgressLine
import com.nethra.app.ui.components.findActivity
import com.nethra.app.ui.components.openAppSettings
import com.nethra.app.ui.components.rememberDeviceRotation
import com.nethra.app.ui.components.rememberPermissions
import com.nethra.app.ui.components.rememberPreviewView
import com.nethra.app.ui.nethraViewModel
import com.nethra.app.ui.theme.NethraColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PrompterScreen(onExit: () -> Unit, onSwitchMode: (CameraMode) -> Unit = {}) {
    val perms = rememberPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    if (!perms.isGranted(Manifest.permission.CAMERA)) {
        LaunchedEffect(Unit) { if (!perms.askedOnce) perms.request() }
        BackHandler { onExit() }
        PermissionExplainer(
            title = "Camera and microphone",
            reason = "The teleprompter shows you in the front camera and records video with sound. " +
                "The microphone also listens for \"Nethra\" so you can ask for a script and control " +
                "recording hands-free. Recordings are saved only to Movies/NETHRA on this phone.",
            state = perms, required = Manifest.permission.CAMERA, onBack = onExit
        )
        return
    }
    PrompterCamera(perms, onExit, onSwitchMode)
}

@Composable
private fun PrompterCamera(perms: PermissionsState, onExit: () -> Unit, onSwitchMode: (CameraMode) -> Unit) {
    ImmersiveCameraWindow()
    val vm = nethraViewModel { app, c -> PrompterViewModel(app, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clean by vm.cleanStatus.collectAsStateWithLifecycle()
    val captionStatus by vm.captionStatus.collectAsStateWithLifecycle()
    val captureVersion by vm.captureVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = rememberPreviewView()
    val rotation = rememberDeviceRotation()
    val provider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val micGranted = perms.isGranted(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(micGranted) { vm.setMicGranted(micGranted) }
    LaunchedEffect(rotation.degrees) { vm.lastRotation = rotation.surfaceRotation }

    val preview = remember { CameraSetup.preview(previewView) }
    val bound = remember { mutableStateOf<List<UseCase>>(emptyList()) }
    LaunchedEffect(captureVersion) {
        try {
            val selector = if (vm.ui.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.value = CameraSetup.bind(context, owner, selector, preview, vm.videoCapture)
            bound.value = listOf(preview, vm.videoCapture)
            vm.onCameraError(null)
        } catch (e: IllegalStateException) {
            vm.onCameraError(e.message)
        }
    }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.onForeground()
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onForeground()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.onBackground()
            // Unbind only our own use cases: the next mode may already have bound its camera.
            provider.value?.unbind(*bound.value.toTypedArray())
        }
    }

    // Leaving waits for a running take to be saved, then goes where it was asked to.
    val pendingExit = remember { mutableStateOf<(() -> Unit)?>(null) }
    val exitNow by vm.exitNow.collectAsStateWithLifecycle()
    LaunchedEffect(exitNow) { if (exitNow) { vm.consumeExit(); (pendingExit.value ?: onExit)() } }
    val leaveTo: (() -> Unit) -> Unit = { target ->
        if (vm.requestExit()) { target() } else { pendingExit.value = target }
    }
    val tryExit = { leaveTo(onExit) }
    val switchTo: (CameraMode) -> Unit = { m -> leaveTo { onSwitchMode(m) } }
    BackHandler {
        when {
            ui.briefOpen -> vm.openBrief(false)
            ui.settingsOpen -> vm.toggleSettings()
            else -> switchTo(CameraMode.CAMERA)
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp
    val r = rotation.uiRotation
    val recordingLike = ui.rec == RecState.RECORDING || ui.rec == RecState.PAUSED || ui.rec == RecState.STARTING

    IosCameraScaffold(
        onSwipe = { d -> if (!ui.isRecordingActive && !ui.briefOpen) CameraMode.PROMPTER.step(d)?.let(switchTo) },
        // The preview holds the movable script panel, so modes swipe on the bars only.
        swipeOnViewfinder = false,
        topBar = {
            IosTopRow(
                left = { IosRoundButton(Icons.Filled.Apps, if (ui.exitRequested) "Saving…" else "Home", tryExit, rotation = r, enabled = !ui.exitRequested && !ui.isRecordingActive) },
                center = {
                    if (ui.isRecordingActive) IosRecordTimer(ui.recordedMs, ui.rec == RecState.PAUSED, r)
                    else VoiceChip(ui, perms, r)
                },
                right = {
                    IosRoundButton(Icons.Filled.Edit, "Script brief", { vm.openBrief(!ui.briefOpen) }, rotation = r, enabled = !ui.isRecordingActive, active = ui.briefOpen)
                    IosRoundButton(Icons.Filled.Tune, "Teleprompter settings", vm::toggleSettings, rotation = r, enabled = !ui.isRecordingActive, active = ui.settingsOpen)
                }
            )
        },
        viewfinder = {
            CameraFrame(previewView) {
                FloatingScriptPanel(ui, vm, rotation)
                IosCountdown(ui.countdown)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (ui.deafWhileRecording) {
                        Notice(
                            "Voice commands aren't hearing you while recording — this phone may be giving the mic only to " +
                                "the camera. The buttons still work.", NethraColors.Warn
                        )
                    }
                    ui.cameraError?.let { Notice(it, NethraColors.Bad) }
                    ui.recordMessage?.let { Notice(it, if (ui.savedName != null) NethraColors.Good else NethraColors.Warn, vm::dismissRecordMessage) }
                    CleanCard(clean, vm::dismissClean)
                    CaptionStatusCard(captionStatus, vm::dismissClean)
                    when {
                        ui.dictation != null -> DictationCaption(ui, vm::finishDictationNow, vm::cancelDictation)
                        ui.briefCapture != null -> BriefCaption(ui.briefCapture!!, vm::finishBriefNow)
                        else -> Caption(ui)
                    }
                }
            }
            val sheetMax = (screenH * 0.62f).dp
            AnimatedVisibility(
                ui.briefOpen, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
            ) {
                BriefSheet(ui, vm, Modifier.padding(8.dp).imePadding().heightIn(max = sheetMax))
            }
            AnimatedVisibility(
                ui.settingsOpen, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
            ) {
                SettingsSheet(ui, vm, Modifier.padding(8.dp).heightIn(max = sheetMax))
            }
        },
        bottomBar = {
            IosModeStrip(CameraMode.PROMPTER, switchTo, visible = !ui.isRecordingActive && ui.countdown == null)
            Spacer(Modifier.height(6.dp))
            IosShutterRow(
                left = {
                    if (ui.isRecordingActive) {
                        val paused = ui.rec == RecState.PAUSED
                        IosSideButton(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause",
                            { if (paused) vm.resume() else vm.pause() }, r,
                            enabled = ui.rec == RecState.RECORDING || paused
                        )
                    } else {
                        IosSideButton(Icons.Filled.Replay, "Restart", vm::restartScript, r, enabled = ui.script != null)
                    }
                },
                shutter = {
                    IosShutterButton(
                        recording = recordingLike || ui.countdown != null,
                        onClick = { vm.onShutter(rotation.surfaceRotation) },
                        enabled = ui.cameraError == null,
                        busy = ui.rec == RecState.STOPPING
                    )
                },
                right = {
                    // iOS puts the camera flip button here.
                    IosSideButton(
                        Icons.Filled.Cameraswitch, if (ui.front) "Rear" else "Front", vm::flipCamera, r,
                        enabled = !ui.isRecordingActive && ui.countdown == null
                    )
                }
            )
        }
    )
}

// ------------------------------------------------------------------ top bar

@Composable
private fun VoiceChip(ui: PrompterUi, perms: PermissionsState, rotation: Float) {
    val context = LocalContext.current
    val on = ui.micGranted && ui.voice != VoiceMode.OFF
    val awaiting = ui.voice == VoiceMode.AWAITING
    Row(
        Modifier.rotate(rotation).clip(RoundedCornerShape(50))
            .background(if (awaiting) NethraColors.CameraYellow.copy(alpha = 0.25f) else Color(0x33FFFFFF))
            .clickable(enabled = !ui.micGranted) {
                val activity = context.findActivity()
                val blocked = perms.askedOnce &&
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
                if (blocked) context.openAppSettings() else perms.request()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (on) Icons.Filled.Mic else Icons.Filled.MicOff, null,
            tint = if (on) NethraColors.CameraYellow else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                !ui.micGranted -> "TAP TO ALLOW MIC"
                awaiting -> "YES?"
                on -> "NETHRA"
                else -> "VOICE…"
            },
            color = if (on) NethraColors.CameraYellow else Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
        )
    }
}

/** Cloud dictation after "Nethra": a live level meter, ✓ to finish now, ✕ to cancel. */
@Composable
private fun DictationCaption(ui: PrompterUi, onDone: () -> Unit, onCancel: () -> Unit) {
    val listening = ui.dictation == DictationPhase.LISTENING
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xE61C1C1E))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (listening) "Listening — say your brief or a command, then pause" else "Understanding what you said…",
                color = NethraColors.CameraYellow, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            if (listening) {
                // Level meter: shows the mic is hearing you.
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(ui.dictationLevel.coerceIn(0.03f, 1f)).height(6.dp).background(NethraColors.Good))
                }
            } else {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (listening) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(NethraColors.Good).clickable(onClick = onDone),
                contentAlignment = Alignment.Center
            ) { Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
        }
        IosRoundButton(Icons.Filled.Close, "Cancel", onCancel, size = 36.dp)
    }
}

/** The brief being dictated, live, with ✓ to finish early. */
@Composable
private fun BriefCaption(text: String, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xE61C1C1E))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Listening to your brief — pause or say “done” when finished", color = NethraColors.CameraYellow, fontSize = 12.sp)
            Text(text.ifBlank { "…" }, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(NethraColors.Good).clickable(onClick = onDone),
            contentAlignment = Alignment.Center
        ) { Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Live caption of what the recogniser heard, plus the voice status — like iOS Live Captions. */
@Composable
private fun Caption(ui: PrompterUi) {
    val status = when {
        !ui.micGranted -> null
        ui.isRecordingActive && !ui.voiceWhileRecording -> "Voice paused while recording — use the buttons"
        ui.isRecordingActive && ui.voice == VoiceMode.OFF -> "Voice commands pause during takes on this phone — use the buttons"
        ui.voice == VoiceMode.AWAITING -> "Yes? Say your brief or a command"
        ui.voiceProblem != null && ui.voice == VoiceMode.OFF -> ui.voiceProblem
        else -> null
    }
    val heard = ui.heard.takeIf { it.isNotBlank() && ui.voice != VoiceMode.OFF }
    if (status == null && heard == null) return
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        status?.let { Text(it, color = NethraColors.CameraYellow, fontSize = 12.sp, textAlign = TextAlign.Center) }
        heard?.let {
            Text(
                "“${it.takeLast(70)}”", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Live words-per-minute chip on the script panel. Tap for scrolling settings. */
@Composable
private fun PaceChip(ui: PrompterUi, rotation: Float, onClick: () -> Unit) {
    val fixed = ui.scrollMode == ScrollMode.FIXED_WPM
    Column(
        Modifier.rotate(rotation).clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "${if (fixed) ui.wpm else ui.liveWpm}",
            color = if (fixed) Color.White else NethraColors.CameraYellow, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
        Text(if (fixed) "FIXED" else "WPM", color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 0.5.sp)
    }
}

// ------------------------------------------------------------------ script panel

/**
 * The script as a floating panel over the preview: drag ✥ to move it, drag ◢ to
 * resize it. Portrait and landscape keep separate layouts, and in landscape the
 * text is turned to read upright for the way the phone is held (the activity
 * itself stays portrait so the camera never restarts).
 */
@Composable
private fun FloatingScriptPanel(ui: PrompterUi, vm: PrompterViewModel, rotation: DeviceRotation) {
    val landscape = rotation.isLandscape
    val saved = if (landscape) ui.panelLandscape ?: PanelRect.defaultLandscape(rotation.degrees) else ui.panelPortrait
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        // Live rect while dragging; saved to preferences when the drag ends.
        var rect by remember(landscape, saved) { mutableStateOf(saved) }
        val wPx = rect.width * fullW
        val hPx = rect.height * fullH
        Box(
            Modifier
                .offset { IntOffset((rect.left * fullW).roundToInt(), (rect.top * fullH).roundToInt()) }
                .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
        ) {
            // Content is laid out for the reader's orientation, then turned to match the phone.
            val innerW = if (landscape) hPx else wPx
            val innerH = if (landscape) wPx else hPx
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(with(density) { innerW.toDp() }, with(density) { innerH.toDp() })
                    .graphicsLayer { rotationZ = rotation.uiRotation }
            ) {
                ScriptPanel(ui, vm, Modifier.fillMaxSize(), 0f)
            }
            PanelHandle(Icons.Filled.OpenWith, "Move script", Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp),
                onDrag = { dx, dy -> rect = rect.copy(left = rect.left + dx / fullW, top = rect.top + dy / fullH).clamp() },
                onEnd = { vm.setPanel(landscape, rect) })
            PanelHandle(Icons.Filled.OpenInFull, "Resize script", Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp),
                onDrag = { dx, dy ->
                    rect = rect.copy(
                        width = (rect.width + dx / fullW).coerceIn(PanelRect.MIN, 1f - rect.left),
                        height = (rect.height + dy / fullH).coerceIn(PanelRect.MIN, 1f - rect.top)
                    )
                },
                onEnd = { vm.setPanel(landscape, rect) })
        }
    }
}

@Composable
private fun PanelHandle(icon: ImageVector, label: String, modifier: Modifier, onDrag: (Float, Float) -> Unit, onEnd: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier.size(34.dp).clip(CircleShape).background(Color(0xB3000000))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { end() }, onDragCancel = { end() },
                    onDrag = { change, delta -> change.consume(); drag(delta.x, delta.y) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = NethraColors.CameraYellow, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ScriptPanel(ui: PrompterUi, vm: PrompterViewModel, modifier: Modifier, rotation: Float) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x8C000000)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                ui.generating -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Writing your script…", color = Color.White, fontSize = 15.sp)
                    Text(ui.brief.topic.ifBlank { ui.brief.spokenBrief }.take(80), color = NethraColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                ui.script == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("No script yet", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Say “Nethra, write a script about …”, or tap ✎ to type a brief. " +
                            "Then say “Nethra, start recording”.",
                        color = NethraColors.TextDim, fontSize = 15.sp, lineHeight = 20.sp
                    )
                }
                else -> ScriptLines(ui, vm)
            }
        }
        if (ui.script != null && !ui.generating) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                PaceChip(ui, rotation) { if (!ui.isRecordingActive) vm.toggleSettings() }
                IosRoundButton(Icons.Filled.KeyboardArrowUp, "Previous line", { vm.nudge(-1) }, size = 34.dp, rotation = rotation)
                IosRoundButton(Icons.Filled.KeyboardArrowDown, "Next line", { vm.nudge(1) }, size = 34.dp, rotation = rotation)
            }
        }
    }
}

/**
 * The script scrolls continuously: every frame the pace follower's fractional
 * word position is mapped to (line, progress through line) and the list is
 * scrolled to that pixel offset — so it glides at the reader's own pace instead
 * of jumping line by line.
 */
@Composable
private fun ScriptLines(ui: PrompterUi, vm: PrompterViewModel) {
    val list = rememberLazyListState()
    val userDragging = remember { mutableStateOf(false) }

    LaunchedEffect(ui.lines) {
        if (ui.lines.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val now = withFrameMillis { SystemClock.elapsedRealtime() }
            val pos = vm.tickScroll(now)
            if (userDragging.value) continue
            val (line, frac) = vm.lineAt(pos)
            val index = line.coerceIn(0, ui.lines.lastIndex)
            val h = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            val offset = (frac * h).toInt()
            if (list.firstVisibleItemIndex != index || abs(list.firstVisibleItemScrollOffset - offset) >= 1) {
                list.scrollToItem(index, offset)
            }
        }
    }
    // Manual correction: when a hand scroll settles, the top line becomes the current line.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> userDragging.value = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // Wait for any fling to settle before reading the position.
                    snapshotFlow { list.isScrollInProgress }.first { !it }
                    val info = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    var line = list.firstVisibleItemIndex
                    if (info != null && list.firstVisibleItemScrollOffset > info.size / 2) line++
                    vm.jumpToLine(line)
                    userDragging.value = false
                }
            }
        }
    }

    LazyColumn(state = list, contentPadding = PaddingValues(top = 44.dp, bottom = 200.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(ui.lines) { i, line ->
            val current = i == ui.currentLine
            Row(
                Modifier.fillMaxWidth().clickable { vm.jumpToLine(i) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Reading marker, like a teleprompter's arrow.
                Box(
                    Modifier.padding(top = 6.dp).width(3.dp).height((ui.textSize * 0.95f).dp).clip(RoundedCornerShape(2.dp))
                        .background(if (current) NethraColors.CameraYellow else Color.Transparent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    line,
                    color = when {
                        current -> Color.White
                        i < ui.currentLine -> Color.White.copy(alpha = 0.32f)
                        else -> Color.White.copy(alpha = 0.72f)
                    },
                    fontSize = ui.textSize.sp, lineHeight = (ui.textSize * 1.33f).sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ------------------------------------------------------------------ status

@Composable
private fun Notice(text: String, color: Color, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xE61C1C1E))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onDismiss != null) IosRoundButton(Icons.Filled.Close, "Dismiss", onDismiss, size = 28.dp, background = Color.Transparent)
    }
}

@Composable
private fun CleanCard(status: CleanStatus, onDismiss: () -> Unit) {
    when (status) {
        CleanStatus.Idle -> Unit
        is CleanStatus.Working -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xE61C1C1E)).padding(12.dp)) {
            ProgressLine("Clean copy (spoken commands removed): ${status.stage}", status.progress)
        }
        is CleanStatus.Done -> Notice(
            "Clean copy saved as ${status.cleanName} beside the original — removed ${status.commandsRemoved} spoken " +
                "command${if (status.commandsRemoved == 1) "" else "s"} (%.1f s).".format(status.removedMs / 1000f) +
                if (status.boundariesRefined) "" else " Cut points were estimated from speech timing only; check the edit.",
            NethraColors.Good, onDismiss
        )
        is CleanStatus.Failed -> Notice(status.message, NethraColors.Warn, onDismiss)
    }
}
