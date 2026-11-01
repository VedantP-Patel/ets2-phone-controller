package com.ets2controller.ui.theme

import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0F1115)
val CockpitPanel = Color(0xFF181B20)
val CockpitBorder = Color(0xFF282C34)

// Authentic Truck Brand Cluster Themes
val ScaniaAmber = Color(0xFFFFA726)       // Scania / MAN Amber Glow
val VolvoIceBlue = Color(0xFF00E5FF)      // Volvo Scandinavian Cyan
val MercedesGreen = Color(0xFF00E676)     // Mercedes-Benz Classic Emerald
val DafWhite = Color(0xFFF5F5F5)          // DAF High-Contrast Monochrome

val GaugeAccent = ScaniaAmber
val GasPedalGreen = Color(0xFF4CAF50)     // Acceleration
val BrakePedalRed = Color(0xFFE53935)     // Brake
val TextMuted = Color(0xFF8A909D)
val TextBright = Color(0xFFEEEEEE)
val ConnectedGreen = Color(0xFF00E676)
val DisconnectedRed = Color(0xFFFF5252)

fun getThemeAccent(themeName: String): Color {
    return when (themeName) {
        "VOLVO_CYAN" -> VolvoIceBlue
        "MERCEDES_GREEN" -> MercedesGreen
        "DAF_WHITE" -> DafWhite
        else -> ScaniaAmber
    }
}
