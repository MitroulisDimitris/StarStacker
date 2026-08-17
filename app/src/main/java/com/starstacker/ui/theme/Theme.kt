package com.starstacker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Night-adapted palette, ported from astro-app-ui-prototype.html.
 *
 * Dark adaptation takes ~25 minutes and one white screen ends it, so there is no hue axis to
 * work with. State is carried by brightness, weight and shape, and full intensity ([Hot]) is
 * rationed to exactly one element per screen.
 */
object Night {
    val Void = Color(0xFF070402)
    val Surface = Color(0xFF120A06)
    val Surface2 = Color(0xFF1B0F08)
    val Line = Color(0xFF3A1C0E)
    val LineSoft = Color(0xFF25120A)

    /** One element per screen. Never two. */
    val Hot = Color(0xFFFF5A2B)
    val Red = Color(0xFFD93F14)
    val Mid = Color(0xFFA63410)
    val Dim = Color(0xFF6E2411)
    val Ghost = Color(0xFF451707)

    val Txt = Color(0xFFF0906E)
    val Txt2 = Color(0xFFB05E3E)
    val Txt3 = Color(0xFF7A3E28)

    val Warn = Color(0xFFFFA53D)
}

/**
 * The prototype uses Space Grotesk + IBM Plex Mono. Bundling them is plan T-0.2; until the
 * font files land, the monospace family carries the numeric role, which is the part that
 * actually matters for reading values in the dark.
 */
private val UiFamily = FontFamily.SansSerif
val NumFamily: FontFamily = FontFamily.Monospace

private val NightTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, letterSpacing = (-0.5).sp, color = Night.Txt,
    ),
    titleMedium = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, color = Night.Txt,
    ),
    bodyMedium = TextStyle(
        fontFamily = UiFamily, fontSize = 13.sp, color = Night.Txt2, lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = UiFamily, fontSize = 11.5.sp, color = Night.Txt3, lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = NumFamily, fontSize = 9.5.sp,
        letterSpacing = 2.2.sp, color = Night.Dim,
    ),
)

private val NightColors = darkColorScheme(
    primary = Night.Hot,
    onPrimary = Night.Void,
    secondary = Night.Red,
    background = Night.Void,
    onBackground = Night.Txt,
    surface = Night.Surface,
    onSurface = Night.Txt,
    surfaceVariant = Night.Surface2,
    onSurfaceVariant = Night.Txt2,
    outline = Night.Line,
    error = Night.Warn,
)

@Composable
fun StarStackerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Always dark. A light theme would be a bug, not a preference.
    MaterialTheme(
        colorScheme = NightColors,
        typography = NightTypography,
        content = content,
    )
}
