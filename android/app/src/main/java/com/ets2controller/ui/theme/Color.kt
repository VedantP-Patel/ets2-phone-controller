package com.ets2controller.ui.theme

import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0D1015)
val CockpitPanel = Color(0xFF141820)
val CockpitTile = Color(0xFF191E28)
val CockpitBorder = Color(0xFF282E3A)

// Authentic Truck Brand Cluster Themes
val ScaniaAmber = Color(0xFFFFAA00)
val VolvoIceBlue = Color(0xFF38BDF8)
val MercedesGreen = Color(0xFF28C76F)
val DafWhite = Color(0xFFF5F5F5)

val GaugeAccent = ScaniaAmber
val GasPedalGreen = Color(0xFF28C76F)
val BrakePedalRed = Color(0xFFFF5252)
val CruiseSkyBlue = Color(0xFF38BDF8)
val TextMuted = Color(0xFF8A93A5)
val TextBright = Color(0xFFF1F5F9)
val ConnectedGreen = Color(0xFF28C76F)
val DisconnectedRed = Color(0xFFFF5252)

fun getThemeAccent(themeName: String): Color {
    return when (themeName) {
        "VOLVO_CYAN" -> VolvoIceBlue
        "MERCEDES_GREEN" -> MercedesGreen
        "DAF_WHITE" -> DafWhite
        else -> ScaniaAmber
    }
}