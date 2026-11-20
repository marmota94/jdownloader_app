package org.jdownloader.remote.presentation.theme
import androidx.compose.ui.graphics.Color
import org.jdownloader.remote.domain.AccentColor

fun AccentColor.color(dark: Boolean): Color = when (this) {
    AccentColor.TEAL -> if (dark) Color(0xFF80D5D0) else Color(0xFF006A68)
    AccentColor.BLUE -> if (dark) Color(0xFFA9C7FF) else Color(0xFF2259A2)
    AccentColor.PURPLE -> if (dark) Color(0xFFD6BBFF) else Color(0xFF674398)
    AccentColor.GREEN -> if (dark) Color(0xFF98D79F) else Color(0xFF2E6738)
    AccentColor.ORANGE -> if (dark) Color(0xFFFFB783) else Color(0xFF984800)
    AccentColor.PINK -> if (dark) Color(0xFFFFB0CF) else Color(0xFF9D3A67)
}
