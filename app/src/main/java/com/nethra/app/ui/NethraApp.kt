package com.nethra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nethra.app.ui.camera.CameraScreen
import com.nethra.app.ui.components.CameraMode
import com.nethra.app.ui.components.IosModeStrip
import com.nethra.app.ui.components.modeSwipe
import com.nethra.app.ui.framing.FramingScreen
import com.nethra.app.ui.home.HomeScreen
import com.nethra.app.ui.prompter.PrompterScreen
import com.nethra.app.ui.publish.PublishScreen
import com.nethra.app.ui.theme.NethraColors

enum class Screen { HOME, CAMERA, FRAMING, PROMPTER, PUBLISH }

private fun CameraMode.screen() = when (this) {
    CameraMode.CAMERA -> Screen.CAMERA
    CameraMode.PROMPTER -> Screen.PROMPTER
    CameraMode.FRAMING -> Screen.FRAMING
    CameraMode.TRANSCRIPT -> Screen.PUBLISH
}

/**
 * Like the iOS Camera app, NETHRA opens straight into the camera. The modes form
 * one carousel — CAMERA · TELEPROMPTER · AI FRAMING · TRANSCRIPT — switched by
 * swiping or tapping the mode strip. The home screen (status, model, key) is one
 * tap away from the top-left button.
 *
 * Camera screens are swapped without a cross-fade so the old screen releases the
 * camera before the new one binds it.
 */
@Composable
fun NethraApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    val goHome = { screen = Screen.HOME }
    val goCamera = { screen = Screen.CAMERA }
    val switchMode: (CameraMode) -> Unit = { screen = it.screen() }

    Box(Modifier.fillMaxSize().background(NethraColors.Background)) {
        when (screen) {
            Screen.HOME -> HomeScreen(onOpen = { screen = it })
            Screen.CAMERA -> CameraScreen(onHome = goHome, onSwitchMode = switchMode)
            Screen.FRAMING -> FramingScreen(onExit = goHome, onSwitchMode = switchMode)
            Screen.PROMPTER -> PrompterScreen(onExit = goHome, onSwitchMode = switchMode)
            Screen.PUBLISH -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { PublishScreen(onExit = goCamera) }
                // Keep the carousel on the transcript page so it feels like one camera app.
                Box(
                    Modifier.fillMaxWidth().background(NethraColors.Chrome)
                        .modeSwipe { d -> CameraMode.TRANSCRIPT.step(d)?.let(switchMode) }
                        .navigationBarsPadding().padding(vertical = 8.dp)
                ) {
                    IosModeStrip(CameraMode.TRANSCRIPT, switchMode)
                }
            }
        }
    }
    // Camera modes handle Back themselves (a take must be saved first); CAMERA lets Back leave the app.
    BackHandler(enabled = screen == Screen.HOME || screen == Screen.PUBLISH) { goCamera() }
}
