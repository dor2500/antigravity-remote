package com.antigravity.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgPrimary = Color(0xFF0D1117)
val BgSecondary = Color(0xFF161B22)
val BgTertiary = Color(0xFF21262D)
val BorderSubtle = Color(0xFF30363D)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)
val AccentBlue = Color(0xFF58A6FF)
val AccentPurple = Color(0xFFA371F7)
val StatusGreen = Color(0xFF3FB950)
val StatusAmber = Color(0xFFD29922)
val StatusRed = Color(0xFFF85149)

private val AntigravityDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF051324),
    primaryContainer = Color(0xFF1F3A5F),
    onPrimaryContainer = TextPrimary,
    secondary = AccentPurple,
    onSecondary = Color(0xFF1A0B2E),
    secondaryContainer = Color(0xFF2E1E4A),
    onSecondaryContainer = TextPrimary,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = BgSecondary,
    onSurface = TextPrimary,
    surfaceVariant = BgTertiary,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = StatusRed,
    onError = Color.White,
)

/**
 * Every text style picks its direction from its own content, so English replies, code and
 * paths read left-to-right and Hebrew reads right-to-left, whatever the app language is.
 */
private val ContentDirectionTypography: Typography = Typography().run {
    fun TextStyle.content() = copy(textDirection = TextDirection.Content)
    copy(
        displayLarge = displayLarge.content(), displayMedium = displayMedium.content(), displaySmall = displaySmall.content(),
        headlineLarge = headlineLarge.content(), headlineMedium = headlineMedium.content(), headlineSmall = headlineSmall.content(),
        titleLarge = titleLarge.content(), titleMedium = titleMedium.content(), titleSmall = titleSmall.content(),
        bodyLarge = bodyLarge.content(), bodyMedium = bodyMedium.content(), bodySmall = bodySmall.content(),
        labelLarge = labelLarge.content(), labelMedium = labelMedium.content(), labelSmall = labelSmall.content()
    )
}

@Composable
fun AntigravityRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AntigravityDarkColorScheme,
        typography = ContentDirectionTypography,
        content = content
    )
}
