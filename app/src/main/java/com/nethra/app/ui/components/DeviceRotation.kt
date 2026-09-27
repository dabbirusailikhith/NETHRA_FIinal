package com.nethra.app.ui.components

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The activity is locked to portrait so the camera preview never re-lays out.
 * Instead, icons and labels counter-rotate to stay readable, like the stock camera.
 */
class DeviceRotation(
    /** Physical orientation snapped to 0/90/180/270 (OrientationEventListener convention: 90 = left edge up). */
    val degrees: Int,
    /** Rotation to apply to UI elements so they read upright (animated, may exceed ±180 for the shortest turn). */
    val uiRotation: Float
) {
    /** Matching Surface.ROTATION_* for CameraX target rotation. */
    val surfaceRotation: Int
        get() = when (degrees) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }

    val isLandscape: Boolean get() = degrees == 90 || degrees == 270
}

@Composable
fun rememberDeviceRotation(): DeviceRotation {
    val context = LocalContext.current
    var snapped by remember { mutableIntStateOf(0) }
    // Accumulate the target so 270 -> 0 turns 90° rather than spinning back 270°.
    var target by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Hysteresis: only switch once the phone is clearly in the next quadrant.
                val next = when {
                    isNear(orientation, 0) -> 0
                    isNear(orientation, 90) -> 90
                    isNear(orientation, 180) -> 180
                    isNear(orientation, 270) -> 270
                    else -> return
                }
                if (next == snapped) return
                snapped = next
                val desired = -next.toFloat()
                var delta = (desired - target) % 360f
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                target += delta
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    val animated by animateFloatAsState(target, tween(250), label = "uiRotation")
    return DeviceRotation(snapped, animated)
}

private fun isNear(orientation: Int, center: Int): Boolean {
    val d = Math.floorMod(orientation - center + 180, 360) - 180
    return kotlin.math.abs(d) <= 35
}
