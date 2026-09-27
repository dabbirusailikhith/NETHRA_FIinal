package com.nethra.app.ui.camera

import android.Manifest
import android.content.Intent
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nethra.app.ui.components.CameraFrame
import com.nethra.app.ui.components.CameraMode
import com.nethra.app.ui.components.CameraSetup
import com.nethra.app.ui.components.CaptionStatusCard
import com.nethra.app.ui.components.CaptionsButton
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
import com.nethra.app.ui.components.rememberDeviceRotation
import com.nethra.app.ui.components.rememberPermissions
import com.nethra.app.ui.components.rememberPreviewView
import com.nethra.app.ui.nethraViewModel
import com.nethra.app.ui.prompter.RecState
import com.nethra.app.ui.theme.NethraColors

/**
 * The plain CAMERA mode — the middle of NETHRA's iOS-style carousel. Records
 * video with either lens; "Nethra, start recording / pause / resume / stop" work here too.
 */
@Composable
fun CameraScreen(onHome: () -> Unit, onSwitchMode: (CameraMode) -> Unit) {
    val perms = rememberPermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    if (!perms.isGranted(Manifest.permission.CAMERA)) {
        LaunchedEffect(Unit) { if (!perms.askedOnce) perms.request() }
        PermissionExplainer(
            title = "Camera and microphone",
            reason = "NETHRA records video with the camera. The microphone records sound and listens for " +
                "“Nethra, start recording”, “pause”, “resume” and “stop”. Videos are saved only to Movies/NETHRA.",
            state = perms, required = Manifest.permission.CAMERA, onBack = onHome
        )
        return
    }
    CameraMain(perms.isGranted(Manifest.permission.RECORD_AUDIO), onHome, onSwitchMode)
}

@Composable
private fun CameraMain(micGranted: Boolean, onHome: () -> Unit, onSwitchMode: (CameraMode) -> Unit) {
    ImmersiveCameraWindow()
    val vm = nethraViewModel { app, c -> CameraViewModel(app, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val rec by vm.recorder.state.collectAsStateWithLifecycle()
    val captionStatus by vm.recorder.captionStatus.collectAsStateWithLifecycle()
    val autoCaptions by vm.recorder.autoCaptions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = rememberPreviewView()
    val preview = remember { CameraSetup.preview(previewView) }
    val rotation = rememberDeviceRotation()
    val provider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val bound = remember { mutableStateOf<List<UseCase>>(emptyList()) }
    val r = rotation.uiRotation

    LaunchedEffect(micGranted) { vm.recorder.setMicGranted(micGranted) }
    LaunchedEffect(rotation.degrees) { vm.recorder.targetRotation = rotation.surfaceRotation }
    LaunchedEffect(ui.front) {
        val selector = if (ui.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.value = CameraSetup.bind(context, owner, selector, preview, vm.recorder.videoCapture)
            bound.value = listOf(preview, vm.recorder.videoCapture)
            vm.recorder.setCanRecord(true)
            vm.onCameraError(null)
        } catch (e: IllegalStateException) {
            vm.recorder.setCanRecord(false)
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
            // Unbind only our own use cases: the next mode may already have bound its camera.
            provider.value?.unbind(*bound.value.toTypedArray())
        }
    }

    val switchTo: (CameraMode) -> Unit = { m -> vm.recorder.exitThen { onSwitchMode(m) } }

    IosCameraScaffold(
        onSwipe = { d -> if (!rec.isRecordingActive) CameraMode.CAMERA.step(d)?.let(switchTo) },
        topBar = {
            IosTopRow(
                left = { IosRoundButton(Icons.Filled.Apps, "Home", { vm.recorder.exitThen(onHome) }, rotation = r, enabled = !rec.isRecordingActive) },
                center = {
                    if (rec.isRecordingActive) IosRecordTimer(rec.recordedMs, rec.rec == RecState.PAUSED, r)
                    else RecorderVoiceChip(rec, r)
                },
                right = {
                    CaptionsButton(autoCaptions, vm.recorder::toggleAutoCaptions, r)
                    IosRoundButton(
                        if (rec.voiceReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (rec.voiceReplies) "Spoken replies on" else "Spoken replies off",
                        { vm.recorder.setVoiceReplies(!rec.voiceReplies) }, rotation = r, active = rec.voiceReplies
                    )
                }
            )
        },
        viewfinder = {
            CameraFrame(previewView) {
                IosCountdown(rec.countdown)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ui.cameraError?.let { CameraNotice(it, NethraColors.Bad) }
                    rec.recordMessage?.let { CameraNotice(it, if (rec.savedName != null) NethraColors.Good else NethraColors.Warn, vm.recorder::dismissMessage) }
                    CaptionStatusCard(captionStatus, vm.recorder::dismissCaptions)
                    if (!rec.isRecordingActive && rec.countdown == null && rec.listening && rec.recordMessage == null) {
                        Text(
                            "Say “Nethra, start recording”", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.rotate(r)
                                .clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            IosModeStrip(CameraMode.CAMERA, switchTo, visible = !rec.isRecordingActive && rec.countdown == null)
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
                        IosSideButton(Icons.Filled.VideoLibrary, "Videos", { openVideos(context) }, r)
                    }
                },
                shutter = {
                    IosShutterButton(
                        recording = rec.shutterIsStop, onClick = vm.recorder::onShutter,
                        enabled = rec.canRecord && ui.cameraError == null, busy = rec.rec == RecState.STOPPING
                    )
                },
                right = {
                    IosSideButton(Icons.Filled.Cameraswitch, "Flip", vm::flip, r, enabled = !rec.isRecordingActive && rec.countdown == null)
                }
            )
        }
    )
}

private fun openVideos(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Small "NETHRA" mic chip for the top bar: yellow while listening for the wake word. */
@Composable
fun RecorderVoiceChip(rec: RecorderUi, rotation: Float) {
    val on = rec.micGranted && rec.listening
    Row(
        Modifier.rotate(rotation).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (on) Icons.Filled.Mic else Icons.Filled.MicOff, null,
            tint = if (on) NethraColors.CameraYellow else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (on) "NETHRA" else if (!rec.micGranted) "MIC OFF" else "VOICE…",
            color = if (on) NethraColors.CameraYellow else Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
        )
    }
}

/** Dark rounded notice with a coloured dot; tap to dismiss when [onDismiss] is set. */
@Composable
fun CameraNotice(text: String, color: Color, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCC1C1C1E))
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}
