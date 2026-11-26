package com.ets2controller.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*

private data class DockTile(
    val label: String,
    val icon: String,
    val bit: Int,
    val isOn: (TruckTelemetryV2) -> Boolean = { false }
)

/**
 * Quick dock: a bottom sheet of eight frequently used buttons over a dimmed cockpit.
 * Every bit index here already exists in the PC plugin's 60-button map.
 */
@Composable
fun QuickDockOverlay(
    accentColor: Color,
    telemetry: TruckTelemetryV2,
    onTrigger: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val tiles = remember {
        listOf(
            DockTile("ENGINE", "engine", 1) { it.isEngineRunning },
            DockTile("LIGHTS", "low", 2) { it.isLowBeams },
            DockTile("BEACON", "beacon", 22) { it.isBeacons },
            DockTile("AUX LIGHT", "low", 58),
            DockTile("WINDOW L", "window", 54),
            DockTile("WINDOW R", "window", 55),
            DockTile("QUICK SAVE", "save", 32),
            DockTile("PHOTO", "cam", 59)
        )
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
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
                    .padding(horizontal = 12.dp, vertical = 12.dp)
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("QUICK DOCK  \u00B7  SWIPE DOWN TO CLOSE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Box(modifier = Modifier.size(width = 44.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(CockpitBorder))
                    Spacer(modifier = Modifier.width(44.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tiles.forEach { tile ->
                        val on = tile.isOn(telemetry)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) accentColor.copy(alpha = 0.16f) else CockpitTile)
                                .border(1.dp, if (on) accentColor else CockpitBorder, RoundedCornerShape(12.dp))
                                .clickable { onTrigger(tile.bit) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            DIcon(
                                name = tile.icon,
                                tint = if (on) accentColor else TextBright,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tile.label,
                                color = if (on) accentColor else TextBright,
                                fontSize = 8.5.sp,
                                lineHeight = 9.5.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}
