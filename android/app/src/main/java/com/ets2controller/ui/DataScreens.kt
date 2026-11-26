package com.ets2controller.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class DataTab { TRIP, TRUCK }

private val AmberWarn = Color(0xFFFFB020)

/**
 * Full-screen data pages. Everything here is drawn from fields the current
 * 42-byte telemetry packet already carries. Gauges the packet does not carry
 * (oil, water, battery) show a dash until the plugin sends them.
 */
@Composable
fun DataOverlay(
    telemetry: TruckTelemetryV2,
    brakeTempC: Float,
    speedUnitMph: Boolean,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(DataTab.TRIP) }
    val isAuto = (telemetry.safetyFlags and (1 shl 5)) != 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataTabButton("TRIP", "route", tab == DataTab.TRIP, accentColor) { tab = DataTab.TRIP }
                DataTabButton("TRUCK", "gear", tab == DataTab.TRUCK, accentColor) { tab = DataTab.TRUCK }
            }
            Text(
                text = if (isAuto) "GEARBOX: AUTOMATIC \u00B7 CLUTCH HIDDEN" else "GEARBOX: MANUAL \u00B7 CLUTCH SHOWN",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "\u2715 Close",
                color = BrakePedalRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        when (tab) {
            DataTab.TRIP -> TripPage(telemetry, speedUnitMph, accentColor, Modifier.weight(1f))
            DataTab.TRUCK -> TruckPage(telemetry, brakeTempC, accentColor, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DataTabButton(label: String, icon: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else CockpitPanel)
            .border(1.dp, if (selected) accent else CockpitBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DIcon(icon, tint = if (selected) Color(0xFF0D1015) else TextBright, modifier = Modifier.size(16.dp))
        Text(label, color = if (selected) Color(0xFF0D1015) else TextBright, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DataCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitTile)
            .border(1.dp, CockpitBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, color: Color = TextBright) {
    Column {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(unit, color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun TripPage(t: TruckTelemetryV2, mph: Boolean, accent: Color, modifier: Modifier) {
    val k = if (mph) 0.621371f else 1f
    val unit = if (mph) "mph" else "km/h"
    val dist = if (mph) (t.routeDistanceKm * 0.621371f).roundToInt() else t.routeDistanceKm
    val distUnit = if (mph) "mi" else "km"
    val eta = if (t.routeTimeMin > 0) "%d:%02d".format(t.routeTimeMin / 60, t.routeTimeMin % 60) else "\u2014"
    val speed = (t.speedKmh * k).roundToInt()
    val limit = if (t.speedLimitKmh > 0) (t.speedLimitKmh * k).roundToInt().toString() else "\u2014"
    val cruise = if (t.cruiseSpeedKmh > 0) (t.cruiseSpeedKmh * k).roundToInt().toString() else "OFF"
    val over = t.speedLimitKmh > 0 && speed > (t.speedLimitKmh * k).roundToInt() + 2

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DataCard("ROUTE", Modifier.weight(1f).fillMaxHeight()) {
            BigStat("DISTANCE LEFT", if (t.routeDistanceKm > 0) dist.toString() else "\u2014", distUnit)
            BigStat("ETA", eta, "h:min")
            Text(
                text = "Route data comes from the in-game navigation.",
                color = TextMuted, fontSize = 9.sp
            )
        }
        DataCard("DRIVING", Modifier.weight(1f).fillMaxHeight()) {
            BigStat("SPEED", speed.toString(), unit, if (over) BrakePedalRed else TextBright)
            BigStat("LIMIT", limit, unit)
            BigStat("CRUISE", cruise, if (cruise == "OFF") "" else unit, if (t.isCruiseActive) CruiseSkyBlue else TextBright)
        }
        DataCard("TANKS AND DAMAGE", Modifier.weight(1.3f).fillMaxHeight()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WaveTank("FUEL", t.fuelPercent, ConnectedGreen, 15)
                WaveTank("ADBLUE", t.adbluePercent, VolvoIceBlue, 15)
            }
            DamageBar("TRUCK DAMAGE", t.truckDamagePct)
        }
    }
}

@Composable
private fun TruckPage(t: TruckTelemetryV2, brakeTempC: Float, accent: Color, modifier: Modifier) {
    val airFrac = (t.airPressureBar / 10f).coerceIn(0f, 1f)
    val airZones = listOf(
        Triple(0f, 0.2f, BrakePedalRed),
        Triple(0.2f, 0.45f, AmberWarn),
        Triple(0.45f, 1f, ConnectedGreen)
    )
    val brakeZones = listOf(
        Triple(0f, 0.555f, ConnectedGreen),
        Triple(0.555f, 0.778f, AmberWarn),
        Triple(0.778f, 1f, BrakePedalRed)
    )
    val none = listOf(Triple(0f, 1f, CockpitBorder))

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcGauge("AIR PRESSURE", "%.1f bar".format(t.airPressureBar), airFrac, airZones, t.isLowAir || t.airPressureBar < 4.5f, Modifier.weight(1f).fillMaxHeight())
                    ArcGauge("BRAKE TEMP (EST.)", "%.0f\u00B0C".format(brakeTempC), (brakeTempC / 450f).coerceIn(0f, 1f), brakeZones, brakeTempC > 350f, Modifier.weight(1f).fillMaxHeight())
                    ArcGauge("CRUISE SET", if (t.cruiseSpeedKmh > 0) "${t.cruiseSpeedKmh} km/h" else "OFF", (t.cruiseSpeedKmh / 130f).coerceIn(0f, 1f), none, false, Modifier.weight(1f).fillMaxHeight())
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcGauge("OIL TEMP (NO DATA)", "\u2014", null, none, false, Modifier.weight(1f).fillMaxHeight())
                    ArcGauge("WATER TEMP (NO DATA)", "\u2014", null, none, false, Modifier.weight(1f).fillMaxHeight())
                    ArcGauge("BATTERY (NO DATA)", "\u2014", null, none, false, Modifier.weight(1f).fillMaxHeight())
                }
            }
            DataCard("TANKS AND DAMAGE", Modifier.weight(2f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    WaveTank("FUEL", t.fuelPercent, ConnectedGreen, 15)
                    WaveTank("ADBLUE", t.adbluePercent, VolvoIceBlue, 15)
                }
                DamageBar("TRUCK DAMAGE", t.truckDamagePct)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Lamp("PARK LT", "low", (t.safetyFlags and (1 shl 6)) != 0, TextBright, Modifier.weight(1f))
            Lamp("LOW", "low", t.isLowBeams, ConnectedGreen, Modifier.weight(1f))
            Lamp("HIGH", "high", t.isHighBeams, VolvoIceBlue, Modifier.weight(1f))
            Lamp("BEACON", "beacon", t.isBeacons, AmberWarn, Modifier.weight(1f))
            Lamp("WIPERS", "wiper", t.isWipersActive, TextBright, Modifier.weight(1f))
            Lamp("PARK BRK", "park", t.isParkBrake, BrakePedalRed, Modifier.weight(1f))
            Lamp("DIFF", "lock", t.isDiffLock, AmberWarn, Modifier.weight(1f))
            Lamp("TRAILER", "trailer", (t.safetyFlags and 1) != 0, ConnectedGreen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ArcGauge(
    label: String,
    valueText: String,
    fraction: Float?,
    zones: List<Triple<Float, Float, Color>>,
    warn: Boolean,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(fraction ?: 0f, tween(700, easing = FastOutSlowInEasing), label = "gaugeNeedle")
    val blink = rememberInfiniteTransition(label = "gaugeWarn")
    val blinkAlpha by blink.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "gaugeWarnAlpha"
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CockpitTile)
            .border(1.dp, if (warn) BrakePedalRed else CockpitBorder, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.size(width = 104.dp, height = 58.dp)) {
            val stroke = 7.dp.toPx()
            val r = size.width / 2f - stroke
            val c = Offset(size.width / 2f, size.height - 6.dp.toPx())
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            drawArc(color = CockpitBorder, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            zones.forEach { (a, b, col) ->
                drawArc(color = col, startAngle = 180f + 180f * a, sweepAngle = 180f * (b - a), useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
            if (fraction != null) {
                val ang = Math.toRadians((180f + 180f * animated).toDouble())
                val len = r - 4.dp.toPx()
                val tip = Offset(c.x + len * cos(ang).toFloat(), c.y + len * sin(ang).toFloat())
                drawLine(TextBright, c, tip, 3.dp.toPx(), StrokeCap.Round)
                drawCircle(TextBright, 4.dp.toPx(), c)
            }
        }
        Text(
            text = valueText,
            color = if (warn) BrakePedalRed.copy(alpha = blinkAlpha) else TextBright,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
        Text(label, color = TextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
    }
}

@Composable
private fun WaveTank(label: String, percent: Int, color: Color, warnPercent: Int) {
    val t = rememberInfiniteTransition(label = "tankWave")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "tankPhase"
    )
    val low = percent <= warnPercent
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CockpitPanel)
                .border(2.dp, if (low) BrakePedalRed else CockpitBorder, RoundedCornerShape(10.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val level = size.height * (1f - percent.coerceIn(0, 100) / 100f)
                val amp = 3.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, level)
                    var x = 0f
                    while (x <= size.width) {
                        lineTo(x, level + amp * sin(phase + x / 12.dp.toPx()))
                        x += 2f
                    }
                    lineTo(size.width, level)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.9f))
                val warnY = size.height * (1f - warnPercent / 100f)
                drawLine(AmberWarn, Offset(0f, warnY), Offset(size.width, warnY), 2.dp.toPx())
            }
        }
        Column {
            Text("$percent%", color = if (low) BrakePedalRed else TextBright, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun DamageBar(label: String, percent: Int) {
    val col = when {
        percent >= 50 -> BrakePedalRed
        percent >= 15 -> AmberWarn
        else -> ConnectedGreen
    }
    val grow by animateFloatAsState(percent.coerceIn(0, 100) / 100f, tween(900, easing = FastOutSlowInEasing), label = "damageGrow")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
            Text("$percent%", color = col, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CockpitBorder)) {
            Box(modifier = Modifier.fillMaxWidth(grow).fillMaxHeight().background(col))
        }
        Text("Amber at 15%, red at 50%. Only total truck damage is sent today.", color = TextMuted, fontSize = 8.sp)
    }
}

@Composable
private fun Lamp(label: String, icon: String, on: Boolean, onColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) onColor.copy(alpha = 0.16f) else CockpitTile)
            .border(1.dp, if (on) onColor else CockpitBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        DIcon(icon, tint = if (on) onColor else TextMuted, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = if (on) onColor else TextMuted, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
