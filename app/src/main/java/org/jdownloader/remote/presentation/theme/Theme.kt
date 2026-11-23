package org.jdownloader.remote.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import org.jdownloader.remote.domain.*

@Composable fun RemoteTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; else -> true }
    val context = LocalContext.current
    var colors = if (settings.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val accent = settings.accent.color(dark)
        val contrast = if (dark) Color.Black else Color.White
        val container = lerp(contrast, accent, if (dark) 0.25f else 0.16f)
        val onContainer = if (dark) Color.White else Color.Black
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = accent, onPrimary = contrast, surfaceTint = accent,
            primaryContainer = container, onPrimaryContainer = onContainer,
            secondary = accent, onSecondary = contrast,
            secondaryContainer = container, onSecondaryContainer = onContainer,
            inversePrimary = settings.accent.color(!dark)
        )
    }
    // Keep elevated surfaces from the dark scheme so overlays and cards remain distinct.
    if (settings.theme == ThemeMode.AMOLED) colors = colors.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black
    )
    MaterialTheme(colorScheme = colors, content = content)
}
