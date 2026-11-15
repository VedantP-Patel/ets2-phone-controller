package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import kotlin.math.roundToInt

@Composable
fun DashboardGauges(
    telemetry: TruckTelemetryV2,
    useMph: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val displaySpeed = if (useMph) (telemetry.speedKmh * 0.621371f).roundToInt() else telemetry.speedKmh.roundToInt()
    val displayLimit = if (useMph) (telemetry.speedLimitKmh * 0.621371f).roundToInt() else telemetry.speedLimitKmh
    val speedUnit = if (useMph) "MPH" else "KM/H"
    val overSpeed = displayLimit > 0 && displaySpeed > displayLimit + 3

    val gearText = when {
        telemetry.gear < 0 -> "R${-telemetry.gear}"
        telemetry.gear == 0 -> "N"
        else -> "D${telemetry.gear}"
    }

    val rpmRatio = (telemetry.engineRpm / telemetry.maxRpm.coerceAtLeast(1500f)).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CockpitPanel)
            .border(
                width = if (telemetry.isCollisionWarning) 2.dp else 1.dp,
                color = if (telemetry.isCollisionWarning) Color(0xFFFF1744) else CockpitBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Automotive Telltale Warning Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "◀",
                color = if (telemetry.isLeftBlinker || telemetry.isHazard) Color(0xFF00E676) else Color(0xFF2A313D),
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TelltaleBadge("LOW", telemetry.isLowBeams, Color(0xFF00E676))
                TelltaleBadge("HI", telemetry.isHighBeams, Color(0xFF2979FF))
                TelltaleBadge("BCN", telemetry.isBeacons, Color(0xFFFF9100))
                TelltaleBadge("(P)", telemetry.isParkBrake, Color(0xFFFF1744))
                TelltaleBadge("RET", telemetry.isRetarderActive, Color(0xFFFF9100))
                TelltaleBadge("EB", telemetry.isEngineBrakeActive, Color(0xFFFFAB00))
                TelltaleBadge("CC", telemetry.isCruiseActive, Color(0xFF00E5FF))
                TelltaleBadge("LKA", telemetry.isLkaActive, Color(0xFF00E676))
            }

            Text(
                text = "▶",
                color = if (telemetry.isRightBlinker || telemetry.isHazard) Color(0xFF00E676) else Color(0xFF2A313D),
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Row 2: Speedometer + Gear Ladder + Segmented RPM Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Speed + EU Speed Limit Sign
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = displaySpeed.toString().padStart(2, '0'),
                    color = if (overSpeed) Color(0xFFFF5252) else Color.White,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
                Column {
                    Text(speedUnit, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    if (telemetry.isCruiseActive && telemetry.cruiseSpeedKmh > 0) {
                        Text("CC ${telemetry.cruiseSpeedKmh}", color = Color(0xFF00E5FF), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (displayLimit > 0) {
                    Box(
                        modifier = Modifier
                            .size(23.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, Color(0xFFD50000), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$displayLimit", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            // Center: Active Gear Box + Retarder Level Steps
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.dp, accentColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = gearText,
                        color = accentColor,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black
                    )
                }
                if (telemetry.retarderLevel > 0) {
                    Text("RET LVL ${telemetry.retarderLevel}", color = Color(0xFFFF9100), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Right: Segmented RPM Tachometer Bar + Numeric RPM
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(108.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RPM", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${telemetry.engineRpm.roundToInt()}",
                        color = if (rpmRatio > 0.85f) Color(0xFFFF5252) else Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                // 14-segment LED RPM bar
                Row(
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (seg in 0 until 14) {
                        val thresh = (seg + 1) / 14f
                        val lit = rpmRatio >= (seg / 14f) && telemetry.engineRpm > 50f
                        val segColor = when {
                            !lit -> Color(0xFF1D222D)
                            thresh > 0.85f -> Color(0xFFFF1744)
                            thresh in 0.42f..0.68f -> Color(0xFF00E676)
                            else -> accentColor
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(segColor)
                        )
                    }
                }
            }
        }

        // Row 3: Visual Air Pressure Bar, Fuel Bar & GPS Route Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniGaugeBar(
                label = "AIR ${"%.1f".format(telemetry.airPressureBar)}b",
                fraction = (telemetry.airPressureBar / 10f).coerceIn(0f, 1f),
                barColor = if (telemetry.isLowAir) Color(0xFFFF1744) else Color(0xFF29B6F6),
                modifier = Modifier.weight(1f)
            )
            MiniGaugeBar(
                label = "FUEL ${telemetry.fuelPercent}%",
                fraction = (telemetry.fuelPercent / 100f).coerceIn(0f, 1f),
                barColor = if (telemetry.fuelPercent < 15) Color(0xFFFF9100) else Color(0xFF00E676),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (telemetry.routeDistanceKm > 0) "NAV ${telemetry.routeDistanceKm}km • ${telemetry.routeTimeMin}m"
                       else "SWIPE PAD TO LOOK",
                color = Color(0xFF8E9BAE),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TelltaleBadge(label: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) activeColor.copy(alpha = 0.22f) else Color(0xFF131720))
            .border(1.dp, if (active) activeColor else Color(0xFF232A38), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = if (active) activeColor else Color(0xFF475166),
            fontSize = 7.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun MiniGaugeBar(label: String, fraction: Float, barColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Color.LightGray, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1A202C))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
    }
}