package app.switchboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F3A3D), onPrimary = Color(0xFFF7F5F0),
    secondary = Color(0xFFB7851A), onSecondary = Color.White,
    background = Color(0xFFF7F5F0), onBackground = Color(0xFF1B1D1C),
    surface = Color(0xFFF7F5F0), onSurface = Color(0xFF1B1D1C),
    surfaceVariant = Color(0xFFE8E4DA), onSurfaceVariant = Color(0xFF4F5552),
    surfaceContainer = Color(0xFFEFECE4), surfaceContainerHigh = Color(0xFFE8E4DA),
    outline = Color(0xFF8A8F8B), error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC9C4), onPrimary = Color(0xFF0E2224),
    secondary = Color(0xFFF2C14E), onSecondary = Color(0xFF2B1F00),
    background = Color(0xFF121615), onBackground = Color(0xFFE4E6E3),
    surface = Color(0xFF121615), onSurface = Color(0xFFE4E6E3),
    surfaceVariant = Color(0xFF26302E), onSurfaceVariant = Color(0xFFB9C0BC),
    surfaceContainer = Color(0xFF1A201F), surfaceContainerHigh = Color(0xFF222A28),
    outline = Color(0xFF7B837F), error = Color(0xFFF2B8B5),
)

@Composable
fun SwitchboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
