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

/*
 * ChatGPT-style flat canvas palette.
 *
 * Dark: one pure flat background (#191919) with no structural borders; cards
 * float on it as slightly lighter planes (#212121) and interactive surfaces
 * (the input capsule, code blocks) sit one step higher (#2F2F2F). Assistant
 * prose is muted off-white (#ECECF1); user messages are pure white. Buttons
 * invert (white fill, near-black content) exactly like the official app.
 */

// Shared canvas tokens.
val ChatCanvas = Color(0xFF191919)
val ChatSurface = Color(0xFF212121)
val ChatCapsule = Color(0xFF2F2F2F)
val ChatText = Color(0xFFECECF1)
val ChatTextMuted = Color(0xFFACACB7)
val OpenAiGreen = Color(0xFF10A37F)

private val Positive = Color(0xFF68C287)
private val Negative = Color(0xFFEF6C6C)
private val Warning = Color(0xFFE0AF68)

val DiffAdded = Positive
val DiffRemoved = Negative
val StatusWarning = Warning

private val DarkScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = ChatCapsule,
    onPrimaryContainer = Color.White,
    secondary = OpenAiGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A3831),
    onSecondaryContainer = Color(0xFF9BD9C4),
    tertiary = Warning,
    background = ChatCanvas,
    onBackground = ChatText,
    surface = ChatCanvas,
    onSurface = ChatText,
    surfaceVariant = ChatSurface,
    onSurfaceVariant = ChatTextMuted,
    surfaceContainer = ChatSurface,
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainerHigh = ChatCapsule,
    surfaceContainerHighest = Color(0xFF3A3A3A),
    outline = Color(0xFF2F2F2F),
    outlineVariant = Color(0xFF242424),
    error = Negative,
    onError = Color(0xFF0D0D0D),
    errorContainer = Color(0xFF3B1D1D),
    onErrorContainer = Color(0xFFFF9B9B),
    inverseSurface = Color(0xFFECECF1),
    inverseOnSurface = Color(0xFF191919),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0D0D0D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEC),
    onPrimaryContainer = Color(0xFF0D0D0D),
    secondary = OpenAiGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F2E8),
    onSecondaryContainer = Color(0xFF0B3B2C),
    tertiary = Color(0xFF9A6700),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0D0D0D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFF8F8F8F),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFFE5E5E5),
    outlineVariant = Color(0xFFECECEC),
    error = Color(0xFFEF4146),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF93201F),
    inverseSurface = Color(0xFF0D0D0D),
    inverseOnSurface = Color(0xFFF4F4F4),
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
