package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icon path data copied 1:1 from the design canvas (24x24 stroke icons). */
private val ICON_PATHS: Map<String, String> = mapOf(
    "left" to "M10 6 4 12l6 6M4 12h16",
    "right" to "M14 6l6 6-6 6M20 12H4",
    "horn" to "M3 10v4h4l6 4V6L7 10H3zM16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11",
    "flash" to "M13 3 6 13h5l-1 8 7-10h-5z",
    "low" to "M8 7a5 5 0 0 0 0 10h2V7zM14 8l6 3M14 12l6 3M14 16l6 3",
    "high" to "M8 7a5 5 0 0 0 0 10h2V7zM14 8h7M14 12h7M14 16h7",
    "wiper" to "M3 19a10 10 0 0 1 18 0M12 19 7 8",
    "hazard" to "M12 4 21 19H3zM12 9l4.5 8h-9z",
    "park" to "M12 3a9 9 0 1 0 .01 0M10 16V8h3a2.5 2.5 0 0 1 0 5h-3",
    "engine" to "M4 10h3l1-2h6l1 2h3v6h-3l-1 2H9l-1-2H4zM2 12v2M22 12v2",
    "trailer" to "M2 7h14v9H2zM16 13h6M6 19a1.5 1.5 0 1 0 .01 0M12 19a1.5 1.5 0 1 0 .01 0",
    "lock" to "M7 11V8a5 5 0 0 1 10 0v3M5 11h14v9H5z",
    "beacon" to "M6 18a6 6 0 0 1 12 0zM12 3v3M4.5 6l2 2M19.5 6l-2 2M4 21h16",
    "cam" to "M3 8h4l2-3h6l2 3h4v11H3zM12 11a3.5 3.5 0 1 0 .01 0",
    "prev" to "M19 5 9 12l10 7zM6 5v14",
    "next" to "M5 5l10 7-10 7zM18 5v14",
    "gauge" to "M4 17a9 9 0 1 1 16 0M12 17l4-6",
    "plus" to "M12 5v14M5 12h14",
    "minus" to "M5 12h14",
    "pulse" to "M3 12h3l2-5 3 10 3-10 2 5h5",
    "up" to "M6 15l6-6 6 6",
    "down" to "M6 9l6 6 6-6",
    "gas" to "M8 4h8l1 14H7zM9.5 8h5M9.5 11h5M9.5 14h5",
    "brake" to "M4 7h16v10H4zM8 11h8",
    "gear" to "M12 9a3 3 0 1 0 .01 0M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1",
    "wheel" to "M12 3a9 9 0 1 0 .01 0M12 12l-8-2M12 12l8-2M12 12v9",
    "tilt" to "M8 3h8v18H8zM11 18h2",
    "more" to "M5 12h.01M12 12h.01M19 12h.01",
    "fuel" to "M5 20V5a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v15M4 20h10M13 9h2l3 3v6a1.5 1.5 0 0 0 3 0V9l-3-3",
    "air" to "M4 12h10a3 3 0 1 0-3-3M4 16h13a3 3 0 1 1-3 3M4 8h6",
    "drop" to "M12 3S5 11 5 15a7 7 0 0 0 14 0C19 11 12 3 12 3z",
    "temp" to "M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z",
    "exh" to "M3 10h10v4H3zM13 12h8M17 9l3 3-3 3",
    "updown" to "M8 9l4-4 4 4M8 15l4 4 4-4",
    "lane" to "M7 4 5 20M17 4l2 16M12 6v3M12 12v3M12 18v2",
    "window" to "M4 5h16v14H4zM4 10h16",
    "eye" to "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 .01 0",
    "mirror" to "M7 4h10v14H7zM12 18v3",
    "map" to "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2zM9 4v14M15 6v14",
    "route" to "M6 19a2 2 0 1 0 .01 0M18 5a2 2 0 1 0 .01 0M8 19h6a4 4 0 0 0 0-8h-4a4 4 0 0 1 0-8h6",
    "job" to "M9 4h6v3H9zM6 6h12v15H6zM9 12h6M9 16h6",
    "save" to "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6",
    "pause" to "M8 5v14M16 5v14",
    "reset" to "M5 12a7 7 0 1 1 2 5M5 18v-5h5",
    "car" to "M4 15l2-6h12l2 6v4H4zM7 15h.01M17 15h.01",
    "link" to "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1",
    "axle" to "M12 20a3 3 0 1 0 .01 0M12 5v9M9 8l3-3 3 3",
    "d" to "M8 5v14h4a7 7 0 0 0 0-14zM8 5",
    "r" to "M8 19V5h5a3.5 3.5 0 0 1 0 7H8M13 12l4 7",
    "n" to "M7 19V5l10 14V5",
    "slide" to "M4 12h16M8 8l-4 4 4 4M16 8l4 4-4 4",
    "data" to "M4 20V10M10 20V4M16 20v-7M22 20H2",
    "power" to "M12 3v8M7 6a8 8 0 1 0 10 0",
    "sun" to "M12 8a4 4 0 1 0 .01 0M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    "center" to "M12 3v4M12 17v4M3 12h4M17 12h4M12 9a3 3 0 1 0 .01 0",
    "clock" to "M12 3a9 9 0 1 0 .01 0M12 7v5l3 2",
    "check" to "M5 12l5 5 9-10",
    "x" to "M6 6l12 12M18 6L6 18",
    "wifi" to "M2 9a15 15 0 0 1 20 0M5 13a10 10 0 0 1 14 0M8.5 16.5a5 5 0 0 1 7 0M12 20h.01",
    "box" to "M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8",
    "coin" to "M12 3a9 9 0 1 0 .01 0M9 10h5M9 14h5M12 7v10",
)

@androidx.compose.runtime.Composable
fun DIcon(
    name: String,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
    stroke: Float = 1.9f
) {
    val path = remember(name) {
        PathParser().parsePathString(ICON_PATHS[name] ?: ICON_PATHS.getValue("gear")).toPath()
    }
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Shared 0.25..1 breathing value that blinking and pulsing states read. */
val LocalBlink = compositionLocalOf<State<Float>> { mutableStateOf(1f) }

/** buzz(durationMs, amplitude 1..255). Provided by the cockpit screen. */
val LocalBuzz = compositionLocalOf<(Long, Int) -> Unit> { { _, _ -> } }
