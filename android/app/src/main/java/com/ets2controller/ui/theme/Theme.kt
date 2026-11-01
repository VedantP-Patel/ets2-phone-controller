package com.ets2controller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GaugeAccent,
    secondary = GasPedalGreen,
    tertiary = BrakePedalRed,
    background = DarkBackground,
    surface = CockpitPanel
)

@Composable
fun ETS2ControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

