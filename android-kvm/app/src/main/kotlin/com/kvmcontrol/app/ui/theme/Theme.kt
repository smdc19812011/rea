package com.kvmcontrol.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val KvmColorScheme = darkColorScheme(
    primary = KvmGreen,
    onPrimary = KvmSurface,
    primaryContainer = KvmGreenDark,
    secondary = KvmBlue,
    onSecondary = KvmSurface,
    secondaryContainer = KvmBlueDark,
    surface = KvmSurface,
    surfaceVariant = KvmSurfaceVariant,
    onSurface = KvmOnSurface,
    error = KvmError,
)

@Composable
fun AndroidKvmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KvmColorScheme,
        content = content,
    )
}
