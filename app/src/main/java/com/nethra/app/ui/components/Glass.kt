package com.nethra.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethra.app.ui.theme.NethraColors

/**
 * iOS "material" look without a real backdrop blur (Compose can't blur what's
 * behind a view cheaply): a dark translucent fill like UIBlurEffect(.systemThinMaterialDark)
 * and a hairline border.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(14.dp), strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (strong) NethraColors.GlassFillStrong else NethraColors.GlassFill)
    .background(Brush.verticalGradient(listOf(Color(0x0FFFFFFF), Color.Transparent)))
    .border(0.5.dp, NethraColors.GlassBorder, shape)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .glass(strong = strong)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

/** Round glass control with a label underneath. [rotation] keeps icon and label upright as the phone turns. */
@Composable
fun GlassControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rotation: Float = 0f,
    tint: Color = Color.White,
    fill: Color? = null,
    size: Dp = 60.dp
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "alpha")
    Column(
        modifier.alpha(alpha).widthIn(min = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(size)
                .glass(CircleShape, strong = true)
                .then(if (fill != null) Modifier.background(fill.copy(alpha = 0.85f), CircleShape) else Modifier)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.45f).rotate(rotation))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .rotate(rotation)
                .background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, rotation: Float = 0f, enabled: Boolean = true, tint: Color = Color.White) {
    Box(
        Modifier
            .size(44.dp)
            .glass(CircleShape, strong = true)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp).rotate(rotation))
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f, dot: Boolean = true) {
    Row(
        modifier.rotate(rotation).glass(RoundedCornerShape(50), strong = true).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (dot) Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, color = Color.White, fontSize = 13.sp, maxLines = 2)
    }
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, accent: Boolean = false) {
    Row(
        modifier
            .glass(RoundedCornerShape(14.dp), strong = true)
            .then(if (accent) Modifier.background(NethraColors.Accent.copy(alpha = 0.28f)) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
fun ProgressLine(label: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
