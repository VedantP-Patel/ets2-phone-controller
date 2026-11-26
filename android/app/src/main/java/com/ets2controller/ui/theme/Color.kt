package com.ets2controller.ui.theme

import androidx.compose.ui.graphics.Color

// Design v2 palette (matches the design canvas exactly)
val DarkBackground = Color(0xFF0B0E13)
val CockpitPanel = Color(0xFF151A22)
val CockpitTile = Color(0xFF151A22)
val CockpitDeep = Color(0xFF0F131A)
val CockpitBorder = Color(0xFF2A323E)
val CockpitBorderStrong = Color(0xFF3A4452)

val ScaniaAmber = Color(0xFFFFB020)
val VolvoIceBlue = Color(0xFF38BDF8)
val MercedesGreen = Color(0xFF4ADE80)
val DafWhite = Color(0xFFF5F5F5)

val GaugeAccent = ScaniaAmber
val GasPedalGreen = Color(0xFF4ADE80)
val BrakePedalRed = Color(0xFFFF5A4F)
val SoftRed = Color(0xFFFF8A7A)
val CruiseSkyBlue = Color(0xFF38BDF8)
val TextMuted = Color(0xFF98A2B3)
val TextBright = Color(0xFFE8ECF2)
val ConnectedGreen = Color(0xFF4ADE80)
val DisconnectedRed = Color(0xFFFF5A4F)

fun getThemeAccent(themeName: String): Color {
    return when (themeName) {
        "VOLVO_CYAN" -> VolvoIceBlue
        "MERCEDES_GREEN" -> MercedesGreen
        "DAF_WHITE" -> DafWhite
        else -> ScaniaAmber
    }
}
