package com.ets2controller.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*

private data class DockTile(
    val label: String,
    val icon: String,
    val bit: Int,
    val guarded: Boolean = false,
    val momentary: Boolean = false,
    val isOn: (TruckTelemetryV2) -> Boolean = { false }
)

/**
 * Quick dock: bottom drawer of eight frequently used controls over a dimmed cockpit.
 * Reuses DTile so Engine preserves hold-to-confirm protection and haptic feedback.
 */
@Composable
fun QuickDockOverlay(
    accentColor: Color,
    telemetry: TruckTelemetryV2,
    holdMs: Long = 400L,
    slideOffCancel: Boolean = true,
    onTrigger: (Int) -> Unit,
    onHoldChange: (Int, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val tiles = remember {
        listOf(
            DockTile("ENGINE", "engine", 1, guarded = true) { it.isEngineRunning },
            DockTile("LIGHTS", "low", 2) { it.isLowBeams },
            DockTile("BEACON", "beacon", 22) { it.isBeacons },
            DockTile("CABIN LT", "sun", 58),
            DockTile("WINDOW L", "window", 54, momentary = true),
            DockTile("WINDOW R", "window", 55, momentary = true),
            DockTile("QUICK SAVE", "save", 32),
            DockTile("PHOTO", "cam", 59)
        )
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val backdropAlpha by animateFloatAsState(
        targetValue = if (visibleState.targetState) 0.62f else 0f,
        animationSpec = tween(200),
        label = "dockBackdrop"
    )
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val hPad = maxOf(12.dp, safe.calculateLeftPadding(layoutDir), safe.calculateRightPadding(layoutDir))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoRows = maxWidth < 660.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backdropAlpha))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically { it } + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = hPad, end = hPad, bottom = maxOf(12.dp, safe.calculateBottomPadding()))
                    .clip(RoundedCornerShape(16.dp))
                    .background(CockpitPanel)
                    .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (total > 60f) onDismiss()
                                total = 0f
                            },
                            onVerticalDrag = { _, dy -> total += dy }
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("QUICK DOCK \u00B7 SWIPE DOWN TO CLOSE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Box(modifier = Modifier.size(width = 44.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(CockpitBorderStrong))
                    Spacer(modifier = Modifier.width(44.dp))
                }
                val rows = if (twoRows) listOf(tiles.take(4), tiles.drop(4)) else listOf(tiles)
                rows.forEach { rowTiles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowTiles.forEach { tile ->
                            val on = tile.isOn(telemetry)
                            DTile(
                                label = tile.label,
                                icon = tile.icon,
                                modifier = Modifier.weight(1f).height(60.dp),
                                tint = if (on) accentColor else TextBright,
                                ts = if (on) TS.LATCHED else TS.NONE,
                                guarded = tile.guarded,
                                holdMs = holdMs,
                                slideOffCancel = slideOffCancel,
                                onPressChanged = if (tile.momentary) { held -> onHoldChange(tile.bit, held) } else null,
                                onTap = { if (!tile.momentary) onTrigger(tile.bit) }
                            )
                        }
                    }
                }
            }
        }
    }
}
