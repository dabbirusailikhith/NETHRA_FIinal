package com.nethra.app.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun rememberPreviewView(): PreviewView {
    val context = LocalContext.current
    return remember {
        PreviewView(context).apply {
            // TextureView-backed so Compose overlays, clipping and rotation compose correctly.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
}

/**
 * Portrait 9:16 camera frame. The camera use cases are bound at 16:9 so the
 * preview shows exactly what is recorded/analysed, and overlays drawn in
 * [overlay] share the same coordinate space as the image.
 */
@Composable
fun CameraFrame(
    previewView: PreviewView,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(9f / 16f, matchHeightConstraintsFirst = false)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            overlay()
        }
    }
}
