package com.nethra.app.ui.framing

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nethra.app.framing.Guidance
import com.nethra.app.framing.NormRect
import com.nethra.app.ui.camera.CameraNotice
import com.nethra.app.ui.camera.RecorderVoiceChip
import com.nethra.app.ui.components.CameraFrame
import com.nethra.app.ui.components.CameraMode
import com.nethra.app.ui.components.CameraSetup
import com.nethra.app.ui.components.CaptionStatusCard
import com.nethra.app.ui.components.CaptionsButton
import com.nethra.app.ui.components.ImmersiveCameraWindow
import com.nethra.app.ui.components.IosBadge
import com.nethra.app.ui.components.IosCameraScaffold
import com.nethra.app.ui.components.IosCountdown
import com.nethra.app.ui.components.IosModeStrip
import com.nethra.app.ui.components.IosRecordTimer
import com.nethra.app.ui.components.IosRoundButton
import com.nethra.app.ui.components.IosSection
import com.nethra.app.ui.components.IosSegmented
import com.nethra.app.ui.components.IosSheet
import com.nethra.app.ui.components.IosShutterButton
import com.nethra.app.ui.components.IosShutterRow
import com.nethra.app.ui.components.IosSideButton
import com.nethra.app.ui.components.IosToggleRow
import com.nethra.app.ui.components.IosTopRow
import com.nethra.app.ui.components.PermissionExplainer
import com.nethra.app.ui.components.drawFocusBox
import com.nethra.app.ui.components.rememberDeviceRotation
import com.nethra.app.ui.components.rememberPermissions
import com.nethra.app.ui.components.rememberPreviewView
import com.nethra.app.ui.nethraViewModel
import com.nethra.app.ui.prompter.RecState
import com.nethra.app.ui.theme.NethraColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun FramingScreen(onExit: () -> Unit, onSwitchMode: (CameraMode) -> Unit = {}) {
    val perms = rememberPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    if (!perms.isGranted(Manifest.permission.CAMERA)) {
        LaunchedEffect(Unit) { if (!perms.askedOnce) perms.request() }
        PermissionExplainer(
            title = "Camera access",
            reason = "The framing coach needs the rear camera to see where the person is standing. " +
                "Frames are analysed on the phone and never uploaded. The microphone (optional) lets you say " +
                "“Nethra, start recording”, “pause”, “resume” and “stop”.",
            state = perms, required = Manifest.permission.CAMERA, onBack = onExit
        )
        return
    }
    FramingCamera(perms.isGranted(Manifest.permission.RECORD_AUDIO), onExit, onSwitchMode)
}

@Composable
private fun FramingCamera(micGranted: Boolean, onExit: () -> Unit, onSwitchMode: (CameraMode) -> Unit) {
    ImmersiveCameraWindow()
    val vm = nethraViewModel { app, c -> FramingViewModel(app, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val rec by vm.recorder.state.collectAsStateWithLifecycle()
    val captionStatus by vm.recorder.captionStatus.collectAsStateWithLifecycle()
    val autoCaptions by vm.recorder.autoCaptions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = rememberPreviewView()
    val rotation = rememberDeviceRotation()
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val bound = remember { mutableStateOf<List<UseCase>>(emptyList()) }

    LaunchedEffect(rotation.degrees) {
        vm.setDeviceDegrees(rotation.degrees)
        vm.recorder.targetRotation = rotation.surfaceRotation
    }
    LaunchedEffect(micGranted) { vm.recorder.setMicGranted(micGranted) }

    LaunchedEffect(Unit) {
        val preview = CameraSetup.preview(previewView)
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(CameraSetup.analysisSelector())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(vm.analysisExecutor, vm.detector)
        try {
            // Preview + analysis + video on the rear camera; fall back to no recording if the
            // phone can't run all three at once.
            providerHolder.value = try {
                CameraSetup.bind(context, owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, vm.recorder.videoCapture)
                    .also { vm.recorder.setCanRecord(true); bound.value = listOf(preview, analysis, vm.recorder.videoCapture) }
            } catch (e: IllegalStateException) {
                vm.recorder.setCanRecord(false)
                CameraSetup.bind(context, owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    .also { bound.value = listOf(preview, analysis) }
            }
            vm.onCameraError(null)
            vm.setActive(true)
        } catch (e: IllegalStateException) {
            vm.onCameraError(e.message)
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.recorder.onForeground()
                Lifecycle.Event.ON_STOP -> vm.recorder.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.recorder.onForeground()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.recorder.onBackground()
            vm.setActive(false)
            // Unbind only our own use cases: the next mode may already have bound its camera.
            providerHolder.value?.unbind(*bound.value.toTypedArray())
        }
    }

    val leave: () -> Unit = { vm.recorder.exitThen(onExit) }
    val switchTo: (CameraMode) -> Unit = { m -> vm.recorder.exitThen { onSwitchMode(m) } }
    BackHandler { if (ui.settingsOpen) vm.toggleSettings() else switchTo(CameraMode.CAMERA) }

    val r = rotation.uiRotation

    IosCameraScaffold(
        // The viewfinder's own drags draw the target box, so modes swipe on the bars only.
        onSwipe = { d -> if (!rec.isRecordingActive) CameraMode.FRAMING.step(d)?.let(switchTo) },
        swipeOnViewfinder = false,
        topBar = {
            IosTopRow(
                left = { IosRoundButton(Icons.Filled.Apps, "Home", leave, rotation = r, enabled = !rec.isRecordingActive) },
                center = {
                    if (rec.isRecordingActive) IosRecordTimer(rec.recordedMs, rec.rec == RecState.PAUSED, r)
                    else RecorderVoiceChip(rec, r)
                },
                right = {
                    CaptionsButton(autoCaptions, vm.recorder::toggleAutoCaptions, r)
                    IosRoundButton(
                        if (ui.voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (ui.voiceOn) "Spoken guidance on" else "Spoken guidance off", vm::toggleVoice,
                        rotation = r, active = ui.voiceOn
                    )
                    IosRoundButton(Icons.Filled.Tune, "Framing settings", vm::toggleSettings, rotation = r, active = ui.settingsOpen)
                }
            )
        },
        viewfinder = {
            CameraFrame(previewView) {
                val locked = rec.isRecordingActive && ui.lockWhileRecording
                FramingOverlay(ui, onTargetChange = vm::setTarget, locked = locked)
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (locked) IosBadge("Framing locked", Color.White, rotation = r) else GuidanceBadge(ui, r)
                }
                if (ui.showHud && !locked) PrecisionHud(ui, Modifier.align(Alignment.TopStart).padding(8.dp))
                IosCountdown(rec.countdown)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ui.cameraError?.let { CameraNotice(it, NethraColors.Bad) }
                    if (vm.ttsUnavailable) CameraNotice("Text-to-speech isn't available — guidance is on screen only.", NethraColors.Warn)
                    rec.voiceProblem?.let { if (!rec.listening) CameraNotice(it, NethraColors.Warn) }
                    rec.recordMessage?.let { CameraNotice(it, if (rec.savedName != null) NethraColors.Good else NethraColors.Warn, vm.recorder::dismissMessage) }
                    CaptionStatusCard(captionStatus, vm.recorder::dismissCaptions)
                    if (!rec.isRecordingActive && rec.recordMessage == null && !ui.settingsOpen) {
                        Text(
                            "Drag to draw the box · drag inside to move · drag a corner to resize",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.rotate(r)
                        )
                    }
                }
            }
            AnimatedVisibility(
                ui.settingsOpen, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
            ) {
                FramingSettings(ui, vm)
            }
        },
        bottomBar = {
            IosModeStrip(CameraMode.FRAMING, switchTo, visible = !rec.isRecordingActive && rec.countdown == null)
            Spacer(Modifier.height(6.dp))
            IosShutterRow(
                left = {
                    if (rec.isRecordingActive) {
                        val paused = rec.rec == RecState.PAUSED
                        IosSideButton(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause",
                            { if (paused) vm.recorder.resume() else vm.recorder.pause() }, r,
                            enabled = rec.rec == RecState.RECORDING || paused
                        )
                    } else {
                        IosSideButton(Icons.Filled.CropFree, "Reset box", vm::resetBox, r)
                    }
                },
                shutter = {
                    IosShutterButton(
                        recording = rec.shutterIsStop,
                        onClick = vm.recorder::onShutter,
                        enabled = rec.canRecord && ui.cameraError == null,
                        busy = rec.rec == RecState.STOPPING
                    )
                },
                right = { ScoreDial(ui, r) }
            )
        }
    )
}

// ------------------------------------------------------------------ chrome pieces

@Composable
private fun GuidanceBadge(ui: FramingUi, rotation: Float) {
    val g = ui.coach.guidance
    val pct = (ui.confidence * 100).toInt()
    val (text, color) = when {
        !ui.detectorRunning -> "Starting camera" to Color.White
        g == Guidance.NO_PERSON -> "No person detected" to Color.White
        g == Guidance.LOW_CONFIDENCE -> "Not confident · $pct%" to NethraColors.Warn
        g == Guidance.GOOD -> "In frame · hold still" to NethraColors.Good
        else -> g.label to NethraColors.CameraYellow
    }
    IosBadge(text, color, rotation = rotation)
}

/** Round precision score in the shutter row's right slot (like the iOS zoom dial). Tap-free, just a readout. */
@Composable
private fun ScoreDial(ui: FramingUi, rotation: Float) {
    val m = ui.coach.metrics
    val score = m?.score?.toInt()
    val color = when {
        score == null -> Color.White.copy(alpha = 0.5f)
        ui.coach.fits -> NethraColors.Good
        score >= 70 -> NethraColors.CameraYellow
        else -> Color.White
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.rotate(rotation)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text(score?.toString() ?: "–", color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Precision", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
    }
}

/** Live precision numbers — the same ones the unit tests assert on. */
@Composable
private fun PrecisionHud(ui: FramingUi, modifier: Modifier) {
    val m = ui.coach.metrics
    val rep = ui.report
    fun f(v: Float) = (if (v >= 0) "+" else "") + "%.3f".format(v)
    val lines = buildList {
        add("guide  ${ui.coach.guidance.name}")
        if (m != null) {
            add("score  %.1f".format(m.score))
            add("IoU    %.3f   cov %.2f".format(m.iou, m.coverage))
            add("dx ${f(m.dx)}  (${"%.2f".format(m.dxRel)}×tol)")
            add("dy ${f(m.dy)}  (${"%.2f".format(m.dyRel)}×tol)")
            add("scale  %.3f".format(m.scale) + if (m.bottomCut) "  (width)" else "")
        }
        add("conf   %d%%   fps %.1f".format((ui.confidence * 100).toInt(), ui.fps))
        if (rep != null) {
            add("in-box %.0f%%  flips/min %.1f".format(rep.goodRatio * 100, rep.flipsPerMinute))
            add("mean   %.1f   IoU %.2f".format(rep.meanScore, rep.meanIou))
        }
        add("mode   ${ui.strictness.label}")
    }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xA6000000)).padding(8.dp)) {
        lines.forEach { Text(it, color = Color.White, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp) }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun FramingSettings(ui: FramingUi, vm: FramingViewModel) {
    val clipboard = LocalClipboardManager.current
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    IosSheet("Framing", vm::toggleSettings, Modifier.padding(8.dp).heightIn(max = maxH)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val tol = ui.strictness.tolerance
            IosSection(
                "Precision",
                footer = "Centre within ±%d%% of the box, size %d–%d%% of the box, confidence ≥ %d%%."
                    .format((tol.centerX * 100).toInt(), (tol.tooSmall * 100).toInt(), (tol.tooBig * 100).toInt(), (tol.minConfidence * 100).toInt())
            ) {
                Spacer(Modifier.height(6.dp))
                IosSegmented(Strictness.entries.map { it.label }, ui.strictness.ordinal, { vm.setStrictness(Strictness.entries[it]) })
                Spacer(Modifier.height(4.dp))
                IosToggleRow("Show precision numbers", ui.showHud, vm::setShowHud)
            }
            val rep = ui.report
            IosSection("This session", footer = "Also logged every second: adb logcat -s NethraFraming") {
                if (rep == null) {
                    Text("Collecting…", color = NethraColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    StatRow("Mean precision", "%.1f / 100".format(rep.meanScore))
                    StatRow("Mean IoU", "%.3f".format(rep.meanIou))
                    StatRow("Time in frame", "%.0f%%".format(rep.goodRatio * 100))
                    StatRow("Guidance changes", "%.1f / min".format(rep.flipsPerMinute))
                    StatRow("Spoken prompts", "%.1f / min".format(rep.utterancesPerMinute))
                    StatRow("Time to first ‘in frame’", rep.timeToGoodMs?.let { "%.1f s".format(it / 1000f) } ?: "—")
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinkButton("Copy report") { clipboard.setText(AnnotatedString(vm.reportText())) }
                    LinkButton("Reset stats", vm::resetStats)
                }
            }
            IosSection(
                "Voice",
                footer = "Say “Nethra, start recording” (3-2-1 countdown), “Nethra, pause”, “Nethra, resume” or “Nethra, stop”."
            ) {
                IosToggleRow("Spoken guidance & replies", ui.voiceOn, { if (it != ui.voiceOn) vm.toggleVoice() })
                IosToggleRow(
                    "Lock framing while recording", ui.lockWhileRecording, vm::setLockWhileRecording,
                    subtitle = "Once recording starts, no more position prompts — spoken or on screen — and detection " +
                        "pauses until the take ends. Spoken prompts never play during a take either way."
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = NethraColors.TextDim, fontSize = 15.sp)
    }
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = NethraColors.Accent, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp)
    )
}

// ------------------------------------------------------------------ overlay (box drawing)

private enum class DragMode { NONE, MOVE, DRAW, TL, TR, BL, BR }

@Composable
private fun FramingOverlay(ui: FramingUi, onTargetChange: (NormRect) -> Unit, locked: Boolean = false) {
    val target by rememberUpdatedState(ui.target)
    val handlePx = with(LocalDensity.current) { 32.dp.toPx() }

    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var mode = DragMode.NONE
            var anchor = Offset.Zero
            var current = Offset.Zero
            // Work on a local copy: several drag events can arrive before the next recomposition.
            var working = target
            detectDragGestures(
                onDragStart = { p ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    working = target
                    val t = working
                    val corners = mapOf(
                        DragMode.TL to Offset(t.left * w, t.top * h), DragMode.TR to Offset(t.right * w, t.top * h),
                        DragMode.BL to Offset(t.left * w, t.bottom * h), DragMode.BR to Offset(t.right * w, t.bottom * h)
                    )
                    val corner = corners.entries.minByOrNull { (it.value - p).getDistance() }
                    mode = when {
                        corner != null && (corner.value - p).getDistance() <= handlePx -> corner.key
                        p.x in t.left * w..t.right * w && p.y in t.top * h..t.bottom * h -> DragMode.MOVE
                        else -> DragMode.DRAW
                    }
                    anchor = p; current = p
                },
                onDragEnd = { mode = DragMode.NONE },
                onDragCancel = { mode = DragMode.NONE },
                onDrag = { change, delta ->
                    change.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    current += delta
                    val dx = delta.x / w; val dy = delta.y / h
                    val t = working
                    val next = when (mode) {
                        DragMode.MOVE -> {
                            val mx = dx.coerceIn(-t.left, 1f - t.right)
                            val my = dy.coerceIn(-t.top, 1f - t.bottom)
                            NormRect(t.left + mx, t.top + my, t.right + mx, t.bottom + my)
                        }
                        DragMode.DRAW -> NormRect(
                            min(anchor.x, current.x) / w, min(anchor.y, current.y) / h,
                            max(anchor.x, current.x) / w, max(anchor.y, current.y) / h
                        ).clamp().let { r -> if (r.width < MIN_SIZE || r.height < MIN_SIZE) null else r }
                        DragMode.TL -> t.copy(left = (t.left + dx).coerceIn(0f, t.right - MIN_SIZE), top = (t.top + dy).coerceIn(0f, t.bottom - MIN_SIZE))
                        DragMode.TR -> t.copy(right = (t.right + dx).coerceIn(t.left + MIN_SIZE, 1f), top = (t.top + dy).coerceIn(0f, t.bottom - MIN_SIZE))
                        DragMode.BL -> t.copy(left = (t.left + dx).coerceIn(0f, t.right - MIN_SIZE), bottom = (t.bottom + dy).coerceIn(t.top + MIN_SIZE, 1f))
                        DragMode.BR -> t.copy(right = (t.right + dx).coerceIn(t.left + MIN_SIZE, 1f), bottom = (t.bottom + dy).coerceIn(t.top + MIN_SIZE, 1f))
                        DragMode.NONE -> null
                    }
                    if (next != null) { working = next; onTargetChange(next) }
                }
            )
        }
    ) {
        val t = ui.target
        val fits = ui.coach.fits && !locked
        if (locked) {
            // Take in progress: just a faint frame guide, no prompts.
            drawFocusBox(
                t.left * size.width, t.top * size.height, t.right * size.width, t.bottom * size.height,
                Color.White.copy(alpha = 0.35f), stroke = 2.dp.toPx(), bracket = 18.dp.toPx(), tick = 7.dp.toPx()
            )
            return@Canvas
        }
        // iOS focus-square style target: yellow, green once the person fits.
        drawFocusBox(
            t.left * size.width, t.top * size.height, t.right * size.width, t.bottom * size.height,
            if (fits) NethraColors.Good else NethraColors.CameraYellow,
            stroke = 3.dp.toPx(), bracket = 18.dp.toPx(), tick = 7.dp.toPx()
        )
        ui.subject?.let { s ->
            val confident = ui.coach.guidance.isConfident
            val color = if (fits) NethraColors.Good else Color.White
            // Dashed when the detector isn't sure: it's a guess, not a measurement.
            drawRectN(s, color.copy(alpha = 0.75f), 1.5.dp.toPx(), if (confident) null else PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        }
    }
}

private const val MIN_SIZE = 0.08f

private fun DrawScope.drawRectN(r: NormRect, color: Color, stroke: Float, effect: PathEffect?) {
    val tl = Offset(r.left * size.width, r.top * size.height)
    val sz = Size(abs(r.width) * size.width, abs(r.height) * size.height)
    drawRoundRect(color, tl, sz, CornerRadius(12f, 12f), style = Stroke(width = stroke, pathEffect = effect))
}
