package dev.opencode.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.opencode.mobile.core.settings.ThemeMode

// Tuned for long reading sessions on an OLED phone: near-black surfaces, one
// blue accent for actions, green/red reserved for diff and tool status.
private val Ink = Color(0xFF0C0E13)
private val Surface1 = Color(0xFF12151C)
private val Surface2 = Color(0xFF181C25)
private val Line = Color(0xFF262B38)
private val TextHigh = Color(0xFFE7EAF2)
private val TextLow = Color(0xFF98A1B6)
private val Accent = Color(0xFF7AA2F7)
private val Positive = Color(0xFF9ECE6A)
private val Negative = Color(0xFFF7768E)
private val Warning = Color(0xFFE0AF68)

val DiffAdded = Positive
val DiffRemoved = Negative
val StatusWarning = Warning

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B0D12),
    primaryContainer = Color(0xFF1E2A45),
    onPrimaryContainer = Accent,
    secondary = Positive,
    onSecondary = Color(0xFF0B0D12),
    tertiary = Warning,
    background = Ink,
    onBackground = TextHigh,
    surface = Surface1,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextLow,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Color(0xFF1E222C),
    outline = Line,
    outlineVariant = Color(0xFF1E222C),
    error = Negative,
    onError = Color(0xFF0B0D12),
    errorContainer = Color(0xFF3A1D25),
    onErrorContainer = Negative,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E5AAC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FB),
    onPrimaryContainer = Color(0xFF14213C),
    secondary = Color(0xFF3F7A2E),
    background = Color(0xFFFAFBFD),
    onBackground = Color(0xFF15181F),
    surface = Color.White,
    onSurface = Color(0xFF15181F),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF525A6B),
    outline = Color(0xFFD5D9E2),
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

/** Touch targets below this are hard to hit one-handed. */
val MinTouchTarget = 48.dp

@Composable
fun OpenCodeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
