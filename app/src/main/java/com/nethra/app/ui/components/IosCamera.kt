package com.nethra.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethra.app.ui.theme.NethraColors
import kotlin.math.abs

/*
 * Building blocks that mirror the native iOS Camera app:
 *  - black chrome bars above and below the viewfinder,
 *  - a mode strip (FRAMING · PROMPTER) with the selected mode in yellow,
 *  - a white-ringed shutter whose red disc morphs into a rounded square while recording,
 *  - a red time-code pill while recording, and small round chrome buttons.
 */

/**
 * NETHRA's modes in carousel order, like the iOS Camera strip. The app opens on
 * CAMERA; swipe left for TELEPROMPTER, then AI FRAMING, then TRANSCRIPT.
 */
enum class CameraMode(val label: String) {
    CAMERA("CAMERA"),
    PROMPTER("TELEPROMPTER"),
    FRAMING("AI FRAMING"),
    TRANSCRIPT("TRANSCRIPT");

    /** Neighbour in the carousel: +1 = next (swipe left), -1 = previous (swipe right). */
    fun step(delta: Int): CameraMode? = entries.getOrNull(ordinal + delta)
}

/** Tabular digits so the timer doesn't jitter as numbers change. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * Full-screen camera layout: top chrome, viewfinder, bottom chrome.
 * The viewfinder keeps its own aspect ratio inside the space left between the bars.
 */
@Composable
fun IosCameraScaffold(
    topBar: @Composable BoxScope.() -> Unit,
    bottomBar: @Composable ColumnScope.() -> Unit,
    /** Horizontal swipe between modes (+1 = swipe left / next). Null disables swiping. */
    onSwipe: ((Int) -> Unit)? = null,
    /** Also swipe on the viewfinder (off where the viewfinder has its own drag gestures). */
    swipeOnViewfinder: Boolean = true,
    viewfinder: @Composable BoxScope.() -> Unit
) {
    // pointerInput must not restart on every recomposition (camera screens recompose per frame).
    val latest by rememberUpdatedState(onSwipe)
    val swipe = if (onSwipe != null) Modifier.modeSwipe { latest?.invoke(it) } else Modifier
    Column(Modifier.fillMaxSize().background(NethraColors.Chrome)) {
        Box(
            Modifier.fillMaxWidth().background(NethraColors.Chrome).statusBarsPadding()
                .height(52.dp).padding(horizontal = 14.dp),
            content = topBar
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().then(if (swipeOnViewfinder) swipe else Modifier),
            contentAlignment = Alignment.Center, content = viewfinder
        )
        Column(
            Modifier.fillMaxWidth().background(NethraColors.Chrome).then(swipe).navigationBarsPadding()
                .padding(top = 8.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = bottomBar
        )
    }
}

/** Detects a deliberate horizontal swipe (≥ 56 dp) and reports its direction once per gesture. */
fun Modifier.modeSwipe(onSwipe: (Int) -> Unit): Modifier = pointerInput(Unit) {
    val threshold = 56.dp.toPx()
    var total = 0f
    var fired = false
    detectHorizontalDragGestures(
        onDragStart = { total = 0f; fired = false },
        onDragEnd = { total = 0f },
        onDragCancel = { total = 0f },
        onHorizontalDrag = { change, dx ->
            total += dx
            if (!fired && abs(total) > threshold) {
                fired = true
                change.consume()
                onSwipe(if (total < 0) +1 else -1)
            }
        }
    )
}

/** Top chrome row with a truly centred middle slot. */
@Composable
fun BoxScope.IosTopRow(
    left: @Composable RowScope.() -> Unit,
    center: @Composable () -> Unit,
    right: @Composable RowScope.() -> Unit
) {
    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = left)
    Box(Modifier.align(Alignment.Center)) { center() }
    Row(
        Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp), content = right
    )
}

/** Bottom row: [left] · shutter · [right], each side a fixed-width slot so the shutter stays centred. */
@Composable
fun IosShutterRow(
    left: @Composable BoxScope.() -> Unit,
    shutter: @Composable () -> Unit,
    right: @Composable BoxScope.() -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart, content = left)
        shutter()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd, content = right)
    }
}

/**
 * iOS-style mode carousel: every mode in one row, the selected one in yellow and
 * slid to the centre. Tap a mode, or swipe (see [modeSwipe]) to move one step.
 */
@Composable
fun IosModeStrip(selected: CameraMode, onSelect: (CameraMode) -> Unit, visible: Boolean = true) {
    val centers = remember { mutableStateMapOf<CameraMode, Float>() }
    var boxWidth by remember { mutableIntStateOf(0) }
    val target = centers[selected]?.let { boxWidth / 2f - it } ?: 0f
    val ready = centers.size == CameraMode.entries.size && boxWidth > 0
    val shift by animateFloatAsState(target, if (ready) spring<Float>(dampingRatio = 0.85f, stiffness = 380f) else snap<Float>(), label = "modeShift")
    Box(
        Modifier.fillMaxWidth().height(34.dp).clipToBounds().onSizeChanged { boxWidth = it.width },
        contentAlignment = Alignment.CenterStart
    ) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = shift; alpha = if (ready) 1f else 0f },
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                CameraMode.entries.forEach { m ->
                    val sel = m == selected
                    Text(
                        m.label,
                        color = if (sel) NethraColors.CameraYellow else Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1,
                        modifier = Modifier
                            .onPlaced { centers[m] = it.positionInParent().x + it.size.width / 2f }
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !sel, role = Role.Tab, onClickLabel = "Switch to ${m.label.lowercase()}") { onSelect(m) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * The iOS video shutter: a white ring around a red disc. While recording the
 * disc shrinks into a rounded square (the "stop" affordance).
 */
@Composable
fun IosShutterButton(
    recording: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    label: String = if (recording) "Stop recording" else "Start recording"
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "inner")
    val corner by animateDpAsState(if (recording) 7.dp else 31.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "corner")
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")
    Box(
        Modifier
            .size(78.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled && !busy, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.scale(scale).size(inner).clip(RoundedCornerShape(corner))
                .background(if (busy) NethraColors.Record.copy(alpha = 0.5f) else NethraColors.Record)
        )
    }
}

/** Small round chrome button (flash / live-photo style). [active] shows the icon in camera yellow. */
@Composable
fun IosRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    rotation: Float = 0f,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = Color.White,
    background: Color = Color(0x33FFFFFF)
) {
    Box(
        Modifier.size(size).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (active) NethraColors.CameraYellow else tint,
            modifier = Modifier.size(size * 0.5f).rotate(rotation)
        )
    }
}

/** A labelled round button for the bottom bar's side slots (e.g. Pause / Resume while recording). */
@Composable
fun IosSideButton(icon: ImageVector, label: String, onClick: () -> Unit, rotation: Float = 0f, enabled: Boolean = true, active: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IosRoundButton(icon, label, onClick, size = 48.dp, rotation = rotation, enabled = enabled, active = active)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.85f else 0.35f), fontSize = 11.sp, modifier = Modifier.rotate(rotation))
    }
}

/** Red time-code pill shown while recording ("00:01:23"); dims when paused. */
@Composable
fun IosRecordTimer(ms: Long, paused: Boolean, rotation: Float = 0f) {
    val s = ms / 1000
    val text = "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    // Not rotated with the phone: the pill is wide and would be clipped by the 52 dp top bar.
    Row(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (paused) Color(0x99000000) else NethraColors.Record)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (paused) {
            Box(Modifier.size(7.dp).background(NethraColors.Record, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            (if (paused) "PAUSED  " else "") + text, color = Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, style = Tabular
        )
    }
}

/** Yellow-on-dark label like iOS's "PORTRAIT" / "AE/AF LOCK" badges. */
@Composable
fun IosBadge(text: String, color: Color = NethraColors.CameraYellow, modifier: Modifier = Modifier, rotation: Float = 0f) {
    Text(
        text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.rotate(rotation).clip(RoundedCornerShape(5.dp)).background(Color(0x99000000))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/** Big centred countdown ("3", "2", "1") shown before a voice-started recording. */
@Composable
fun BoxScope.IosCountdown(value: Int?) {
    AnimatedVisibility(
        value != null, modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(120)) + scaleIn(initialScale = 1.4f), exit = fadeOut(tween(150))
    ) {
        Text(
            (value ?: 0).toString(), color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light,
            style = Tabular
        )
    }
}

/** iOS-style grouped sheet with a grabber and a title row. */
@Composable
fun IosSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NethraColors.Sheet)
            .border(0.5.dp, NethraColors.GlassBorder, RoundedCornerShape(16.dp))
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(Color(0x66EBEBF5)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Done", color = NethraColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

/** iOS inset-grouped section: rounded dark card with a small caps header. */
@Composable
fun IosSection(header: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (header != null) {
            Text(header.uppercase(), color = NethraColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NethraColors.Fill)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            content = content
        )
        if (footer != null) {
            Text(footer, color = NethraColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
        }
    }
}

@Composable
fun IosToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = NethraColors.TextDim, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NethraColors.Good, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Color(0xFF39393D), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** iOS segmented control. */
@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(9.dp))
            .background(Color(0x3D767680)).padding(2.dp)
    ) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) Color(0xFF636366) else Color.Transparent)
                    .clickable(enabled = enabled && !sel, role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(o, color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun IosSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = NethraColors.Accent, inactiveTrackColor = Color(0xFF39393D),
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
        )
    )
}

/**
 * iOS focus-square style box: thin outline, small ticks at the middle of each
 * side, and short L-brackets at the corners (the resize handles).
 */
fun DrawScope.drawFocusBox(
    left: Float, top: Float, right: Float, bottom: Float,
    color: Color, stroke: Float, bracket: Float, tick: Float
) {
    val thin = stroke * 0.55f
    // Outline
    drawLine(color, Offset(left, top), Offset(right, top), thin)
    drawLine(color, Offset(left, bottom), Offset(right, bottom), thin)
    drawLine(color, Offset(left, top), Offset(left, bottom), thin)
    drawLine(color, Offset(right, top), Offset(right, bottom), thin)
    // Mid-side ticks (pointing inward), like the iOS focus square
    val cx = (left + right) / 2; val cy = (top + bottom) / 2
    drawLine(color, Offset(cx, top), Offset(cx, top + tick), thin)
    drawLine(color, Offset(cx, bottom), Offset(cx, bottom - tick), thin)
    drawLine(color, Offset(left, cy), Offset(left + tick, cy), thin)
    drawLine(color, Offset(right, cy), Offset(right - tick, cy), thin)
    // Corner brackets
    val b = bracket
    fun l(a: Offset, c: Offset) = drawLine(color, a, c, stroke, cap = StrokeCap.Round)
    l(Offset(left, top), Offset(left + b, top)); l(Offset(left, top), Offset(left, top + b))
    l(Offset(right, top), Offset(right - b, top)); l(Offset(right, top), Offset(right, top + b))
    l(Offset(left, bottom), Offset(left + b, bottom)); l(Offset(left, bottom), Offset(left, bottom - b))
    l(Offset(right, bottom), Offset(right - b, bottom)); l(Offset(right, bottom), Offset(right, bottom - b))
}
