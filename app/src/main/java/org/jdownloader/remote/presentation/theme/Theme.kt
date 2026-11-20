package org.jdownloader.remote.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.jdownloader.remote.domain.*

@Composable fun RemoteTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; else -> true }
    val context = LocalContext.current
    var colors = if (settings.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Mint) else lightColorScheme(primary = Ocean)
    if (settings.theme == ThemeMode.AMOLED) colors = colors.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceBright = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black, surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black, surfaceContainerHighest = Color.Black
    )
    MaterialTheme(colorScheme = colors, content = content)
}
