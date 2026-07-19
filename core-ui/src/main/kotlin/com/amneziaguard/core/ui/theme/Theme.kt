package com.amneziaguard.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmneziaDarkColors = darkColorScheme(
    primary = AccentOrange,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = AccentOrangeContainer,
    onPrimaryContainer = AccentOrange,
    secondary = TextSecondary,
    onSecondary = NavyBackground,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = NavyOutline,
    error = ErrorRed,
    onError = Color(0xFF200000),
)

@Composable
fun AmneziaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmneziaDarkColors,
        content = content,
    )
}
