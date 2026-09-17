package io.clarionchain.keel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Blue = Color(0xFF3B82F6)
private val Danger = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = White,
    background = Black,
    onBackground = White,
    surface = Color(0xFF101010),
    onSurface = White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF9E9E9E),
    error = Danger,
    onError = Black,
)

@Composable
fun KeelTheme(content: @Composable () -> Unit) {
    // Always dark: black background, white text, blue accent.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
