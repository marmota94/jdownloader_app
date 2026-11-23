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
    AccentColor.RED -> if (dark) Color(0xFFFFAFA8) else Color(0xFFAD292C)
    AccentColor.AMBER -> if (dark) Color(0xFFFFD26B) else Color(0xFF785600)
    AccentColor.LIME -> if (dark) Color(0xFFC3E96B) else Color(0xFF526600)
    AccentColor.CYAN -> if (dark) Color(0xFF72DBFA) else Color(0xFF00657E)
    AccentColor.INDIGO -> if (dark) Color(0xFFB8BFFF) else Color(0xFF3F4BB0)
    AccentColor.LAVENDER -> if (dark) Color(0xFFE5B7F5) else Color(0xFF79468D)
    AccentColor.MAGENTA -> if (dark) Color(0xFFFFAAEE) else Color(0xFF97267E)
    AccentColor.SLATE -> if (dark) Color(0xFFBDC9D5) else Color(0xFF4D5B69)
}
