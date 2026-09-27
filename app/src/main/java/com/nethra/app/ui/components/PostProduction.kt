package com.nethra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethra.app.captions.CaptionStatus
import com.nethra.app.ui.theme.NethraColors

/** Progress / result of the automatic captioned copy. Tap a finished card to dismiss it. */
@Composable
fun CaptionStatusCard(status: CaptionStatus, onDismiss: () -> Unit) {
    val (text, color, progress) = when (status) {
        CaptionStatus.Idle -> return
        is CaptionStatus.Working -> Triple(status.stage, NethraColors.CameraYellow, status.progress ?: -1f)
        is CaptionStatus.Done -> Triple(
            "Captioned copy saved as ${status.name} (${status.cards} captions)" +
                if (status.srtSaved) ", subtitles in Documents/NETHRA." else ".",
            NethraColors.Good, null
        )
        is CaptionStatus.Failed -> Triple(status.message, NethraColors.Warn, null)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xE61C1C1E))
            .then(if (status !is CaptionStatus.Working) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontSize = 13.sp)
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NethraColors.CameraYellow)
            else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = NethraColors.CameraYellow)
        }
    }
}

/** "CC" top-bar button: yellow when automatic captions are on. */
@Composable
fun CaptionsButton(on: Boolean, onToggle: () -> Unit, rotation: Float) {
    IosRoundButton(
        if (on) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
        if (on) "Auto captions on" else "Auto captions off", onToggle, rotation = rotation, active = on
    )
}
