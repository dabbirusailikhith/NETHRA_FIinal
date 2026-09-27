package com.nethra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** iOS system colours (dark appearance), so NETHRA looks like the native Camera app. */
object NethraColors {
    val Background = Color(0xFF000000)
    /** systemBlue */
    val Accent = Color(0xFF0A84FF)
    val AccentSoft = Color(0xFF64D2FF)
    /** systemGreen */
    val Good = Color(0xFF30D158)
    /** systemOrange */
    val Warn = Color(0xFFFF9F0A)
    /** systemRed */
    val Bad = Color(0xFFFF453A)
    /** The Camera app's record red. */
    val Record = Color(0xFFFF3B30)
    /** The Camera app's yellow: selected mode, focus box, active labels. */
    val CameraYellow = Color(0xFFFFD60A)
    /** Camera chrome: the black bars above and below the preview. */
    val Chrome = Color(0xFF000000)
    val ChromeTranslucent = Color(0x8C000000)
    /** secondarySystemBackground / sheet */
    val Sheet = Color(0xF21C1C1E)
    val Fill = Color(0xFF2C2C2E)
    val GlassFill = Color(0x59000000)
    val GlassFillStrong = Color(0xB81C1C1E)
    val GlassBorder = Color(0x1FFFFFFF)
    val TextDim = Color(0x99EBEBF5)
    val Separator = Color(0x38545458)
}

private val scheme = darkColorScheme(
    primary = NethraColors.Accent,
    onPrimary = Color.White,
    secondary = NethraColors.AccentSoft,
    background = NethraColors.Background,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    onBackground = Color.White,
    error = NethraColors.Bad
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun NethraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
