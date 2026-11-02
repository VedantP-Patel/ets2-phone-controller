package com.ets2controller.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.TruckTelemetry
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import kotlin.math.roundToInt

@Composable
fun DashboardGauges(
    telemetry: TruckTelemetry,
    useMph: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    var activePage by remember { mutableIntStateOf(0) } // 0 = Driving Instruments, 1 = Route & Diagnostics

    val displaySpeed = if (useMph) (telemetry.speedKmh * 0.621371f) else telemetry.speedKmh
    val speedUnit = if (useMph) "MPH" else "KM/H"
    val displaySpeedLimit = if (useMph && telemetry.speedLimitKmh > 0) (telemetry.speedLimitKmh * 0.621371f).roundToInt() else telemetry.speedLimitKmh

    val displayCruise = if (useMph && telemetry.cruiseSpeedKmh > 0) (telemetry.cruiseSpeedKmh * 0.621371f).roundToInt() else telemetry.cruiseSpeedKmh
    val isDiffLocked = (telemetry.auxStateFlags and (1 shl 0)) != 0
    val isLiftAxle = (telemetry.auxStateFlags and (1 shl 1)) != 0
    val isTrailerConnected = (telemetry.auxStateFlags and (1 shl 2)) != 0
    val isHighBeam = (telemetry.auxStateFlags and (1 shl 3)) != 0

    val isOverspeeding = telemetry.speedLimitKmh > 0 && (telemetry.speedKmh > (telemetry.speedLimitKmh + 2.0f))

    // Blinker flash animation
    val infiniteTransition = rememberInfiniteTransition(label = "blinker_flash")
    val blinkerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinker_alpha"
    )

    // Overspeed pulse animation
    val overspeedBorderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overspeed_pulse"
    )

    val leftBlinkerActive = telemetry.isLeftBlinker || telemetry.isHazard
    val rightBlinkerActive = telemetry.isRightBlinker || telemetry.isHazard

    val safeMaxRpm = if (telemetry.maxRpm > 500f) telemetry.maxRpm else 2500f
    val rpmRatio = (telemetry.engineRpm / safeMaxRpm).coerceIn(0.0f, 1.0f)
    val animatedRpmRatio by animateFloatAsState(
        targetValue = rpmRatio,
        animationSpec = tween(durationMillis = 60),
        label = "rpm_anim"
    )

    val airBar = if (telemetry.airPressureBar > 30f) telemetry.airPressureBar / 14.5038f else telemetry.airPressureBar

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CockpitPanel)
            .border(1.5.dp, if (isOverspeeding) Color(0xFFFF3D00).copy(alpha = overspeedBorderAlpha) else CockpitBorder, RoundedCornerShape(12.dp))
            .clickable { activePage = (activePage + 1) % 2 } // Tap anywhere to toggle between Instruments & Route
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = activePage,
            transitionSpec = {
                fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
            },
            label = "hud_page_transition"
        ) { page ->
            if (page == 0) {
                // PAGE 0: PRIMARY DRIVING GAUGES
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Turn Signal Arrow
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (leftBlinkerActive) Color(0xFF003810) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        TurnArrowCanvas(
                            isLeft = true,
                            color = if (leftBlinkerActive) Color(0xFF00E676).copy(alpha = blinkerAlpha) else Color(0xFF2E3830),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Center Primary Cluster
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // European Speed Limit Sign (if available)
                        if (displaySpeedLimit > 0) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(
                                        3.dp,
                                        if (isOverspeeding) Color(0xFFFF1744).copy(alpha = overspeedBorderAlpha) else Color(0xFFD50000),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$displaySpeedLimit",
                                    color = Color.Black,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }

                        // Gear Badge: Heavy Truck 12+2 Opticruise (C1, C2, A1..A12 / M1..M12, R1..R2, N)
                        val totalFwdGears = telemetry.forwardGears.let { if (it > 0) it else 14 }
                        val isAuto = telemetry.isAutomatic
                        val gear = telemetry.gear

                        val gearText = when {
                            gear == 0 -> "N"
                            gear == -1 -> "R1"
                            gear == -2 -> "R2"
                            gear < 0 -> "R${-gear}"
                            totalFwdGears > 12 -> {
                                // Heavy truck with crawler gears (e.g. Scania Opticruise 12+2 = 14 gears)
                                when (gear) {
                                    1 -> "C1"
                                    2 -> "C2"
                                    else -> {
                                        val roadGear = gear - 2
                                        if (isAuto) "A$roadGear" else "M$roadGear"
                                    }
                                }
                            }
                            else -> {
                                // Standard 6-12 speed road transmission
                                if (isAuto) "A$gear" else "M$gear"
                            }
                        }
                        val gearColor = when {
                            gear == 0 -> Color(0xFFFFA726)
                            gear < 0 -> Color(0xFFFF3D00)
                            gearText.startsWith("C") -> Color(0xFFFF9100) // Distinct Crawler orange-amber
                            else -> accentColor
                        }
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .defaultMinSize(minWidth = 36.dp)
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF101317))
                                .border(1.5.dp, gearColor, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = gearText,
                                color = gearColor,
                                fontSize = if (gearText.length > 2) 14.sp else 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Digital Speed Readout
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${displaySpeed.roundToInt()}",
                                color = if (isOverspeeding) Color(0xFFFF5252) else Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = speedUnit,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }

                        // RPM & Air Readout
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${telemetry.engineRpm.roundToInt()} RPM",
                                color = when {
                                    telemetry.engineRpm > 2100f -> Color(0xFFFF5252)
                                    telemetry.engineRpm > 1600f -> Color(0xFFFFA726)
                                    else -> Color.White
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (telemetry.isParkBrake) {
                                    Text("(P)", color = Color(0xFFFF1744), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                if (telemetry.isCruiseControl) {
                                    Text("CC $displayCruise", color = Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                if (isHighBeam) {
                                    Text("HI", color = Color(0xFF2979FF), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                if (isDiffLocked) {
                                    Text("DIFF", color = Color(0xFFFF9100), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                if (isLiftAxle) {
                                    Text("LIFT", color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                Text(
                                    text = "⛽${telemetry.fuelPercent}% | ${String.format("%.1f", airBar)}b",
                                    color = if (telemetry.isLowAir || telemetry.fuelPercent < 15) Color(0xFFFF5252) else Color.Gray,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Right Turn Signal Arrow
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (rightBlinkerActive) Color(0xFF003810) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        TurnArrowCanvas(
                            isLeft = false,
                            color = if (rightBlinkerActive) Color(0xFF00E676).copy(alpha = blinkerAlpha) else Color(0xFF2E3830),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                // PAGE 1: ROUTE ADVISOR & VEHICLE DIAGNOSTICS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Navigation GPS
                    Column(horizontalAlignment = Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("NAV", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (telemetry.routeDistanceKm > 0) "${telemetry.routeDistanceKm} km" else "NO ROUTE",
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val etaHours = telemetry.routeTimeMin / 60
                        val etaMinutes = telemetry.routeTimeMin % 60
                        Text(
                            text = if (telemetry.routeTimeMin > 0) "ETA ${etaHours}h ${etaMinutes}m" else "Free Roam",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    }

                    // Cruise Control Status
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (telemetry.isCruiseControl) "CRUISE ACTIVE" else "CRUISE STANDBY",
                            color = if (telemetry.isCruiseControl) Color(0xFF00E676) else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (telemetry.isCruiseControl) "$displayCruise $speedUnit" else "Swipe / 2x Gas",
                            color = if (telemetry.isCruiseControl) Color.White else Color.DarkGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    // Truck Diagnostics & Health
                    Column(horizontalAlignment = Alignment.End) {
                        val damageColor = when {
                            telemetry.truckDamagePct >= 10 -> Color(0xFFFF5252)
                            telemetry.truckDamagePct > 0 -> Color(0xFFFFA726)
                            else -> Color(0xFF00E676)
                        }
                        Text(
                            text = "DMG ${telemetry.truckDamagePct}%",
                            color = damageColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "FUEL ${telemetry.fuelPercent}%",
                            color = if (telemetry.fuelPercent < 15) Color(0xFFFF5252) else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // RPM Tachometer Bar: Authentic Diesel Banding
        // Diesel Eco Range (<= 1500 RPM): Pure Crisp Emerald Green
        // Power Band (1500 - 2100 RPM): High RPM Amber / Orange
        // Redline / Overspeed (> 2100 RPM): Danger Red
        val currentRpm = telemetry.engineRpm
        val rpmBarEndColor = when {
            currentRpm > 2100f -> Color(0xFFFF3D00)
            currentRpm > 1500f -> Color(0xFFFFA726)
            else -> Color(0xFF00E676)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF101317))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedRpmRatio)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF00E676),
                                rpmBarEndColor
                            )
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Page Indicator Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { idx ->
                Box(
                    modifier = Modifier
                        .size(if (activePage == idx) 5.dp else 4.dp)
                        .clip(CircleShape)
                        .background(if (activePage == idx) accentColor else Color.Gray.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
fun TurnArrowCanvas(isLeft: Boolean, color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (isLeft) {
                moveTo(w * 0.85f, h * 0.35f)
                lineTo(w * 0.48f, h * 0.35f)
                lineTo(w * 0.48f, h * 0.12f)
                lineTo(w * 0.10f, h * 0.50f)
                lineTo(w * 0.48f, h * 0.88f)
                lineTo(w * 0.48f, h * 0.65f)
                lineTo(w * 0.85f, h * 0.65f)
                close()
            } else {
                moveTo(w * 0.15f, h * 0.35f)
                lineTo(w * 0.52f, h * 0.35f)
                lineTo(w * 0.52f, h * 0.12f)
                lineTo(w * 0.90f, h * 0.50f)
                lineTo(w * 0.52f, h * 0.88f)
                lineTo(w * 0.52f, h * 0.65f)
                lineTo(w * 0.15f, h * 0.65f)
                close()
            }
        }
        drawPath(path, color = color)
    }
}
