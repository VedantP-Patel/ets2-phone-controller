package com.ets2controller.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import com.ets2controller.ui.theme.GaugeAccent
import kotlin.math.abs

@Composable
fun SteeringVisual(
    steeringValue: Float, // -1.0 to 1.0
    rawAngleDeg: Float,
    clusterTheme: String = "SCANIA_AMBER",
    onCalibrateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCentered = abs(rawAngleDeg) <= 1.5f
    val centerGreen = Color(0xFF00E676)
    val themeAccent = com.ets2controller.ui.theme.getThemeAccent(clusterTheme)

    // Direct frame-synchronized visual wheel rotation (eliminates tween interruption stutter)
    val displayAngle = rawAngleDeg

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Steering wheel graphic (Tap or double-tap to calibrate center)
        Box(
            modifier = Modifier
                .size(148.dp)
                .graphicsLayer { rotationZ = displayAngle }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onCalibrateClick() },
                        onDoubleTap = { onCalibrateClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2.0f - 8.dp.toPx()
                val center = Offset(size.width / 2.0f, size.height / 2.0f)

                // Outer steering wheel rim
                drawCircle(
                    color = Color(0xFF2C323D),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                )

                // Center hub
                drawCircle(
                    color = CockpitPanel,
                    radius = radius * 0.42f,
                    center = center
                )
                drawCircle(
                    color = if (isCentered) centerGreen else CockpitBorder,
                    radius = radius * 0.42f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Left spoke
                drawLine(
                    color = Color(0xFF383F4C),
                    start = Offset(center.x - radius * 0.42f, center.y),
                    end = Offset(center.x - radius, center.y),
                    strokeWidth = 12.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Right spoke
                drawLine(
                    color = Color(0xFF383F4C),
                    start = Offset(center.x + radius * 0.42f, center.y),
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = 12.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Bottom spoke
                drawLine(
                    color = Color(0xFF383F4C),
                    start = Offset(center.x, center.y + radius * 0.42f),
                    end = Offset(center.x, center.y + radius),
                    strokeWidth = 10.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // 12 o'clock alignment stripe (racing marker - glows green when centered)
                drawLine(
                    color = if (isCentered) centerGreen else GaugeAccent,
                    start = Offset(center.x, center.y - radius + 8.dp.toPx()),
                    end = Offset(center.x, center.y - radius - 8.dp.toPx()),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Authentic Heavy Commercial Truck Center Steering Crest
            BrandCenterHub(
                clusterTheme = clusterTheme,
                isCentered = isCentered,
                centerColor = centerGreen,
                accentColor = themeAccent
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // High-Visibility Calibrate Center Button with Integrated Live Angle Readout
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CockpitPanel)
                .border(
                    width = 1.5.dp,
                    color = if (isCentered) centerGreen else GaugeAccent.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable { onCalibrateClick() }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🎯",
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isCentered) "CENTERED" else (if (rawAngleDeg > 0f) "▶ " else "◀ ") + "${String.format("%.1f", abs(rawAngleDeg))}°",
                color = if (isCentered) centerGreen else GaugeAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "CALIBRATE",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun BrandCenterHub(
    clusterTheme: String,
    isCentered: Boolean,
    centerColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF14161B))
            .border(1.5.dp, if (isCentered) centerColor else accentColor.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when (clusterTheme) {
            "SCANIA_AMBER" -> {
                // Scania Crowned Griffin Crest
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Canvas(modifier = Modifier.size(24.dp, 16.dp)) {
                        val w = size.width
                        val h = size.height
                        // 3-point Scania crown
                        val crown = Path().apply {
                            moveTo(w * 0.20f, h * 0.85f)
                            lineTo(w * 0.15f, h * 0.35f)
                            lineTo(w * 0.35f, h * 0.60f)
                            lineTo(w * 0.50f, h * 0.15f)
                            lineTo(w * 0.65f, h * 0.60f)
                            lineTo(w * 0.85f, h * 0.35f)
                            lineTo(w * 0.80f, h * 0.85f)
                            close()
                        }
                        drawPath(crown, color = Color(0xFFFFC107))
                    }
                    Text(
                        text = "SCANIA",
                        color = if (isCentered) centerColor else Color(0xFFFFB300),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            "VOLVO_CYAN" -> {
                // Volvo Scandinavian Iron Mark
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
                        val w = size.width
                        val h = size.height
                        val r = w * 0.40f
                        val c = Offset(w * 0.5f, h * 0.5f)

                        // Iron circle
                        drawCircle(
                            color = Color(0xFFCFD8DC),
                            radius = r,
                            center = c,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 45° diagonal arrow pointing to upper-right (North-East)
                        val arrowTip = Offset(w * 0.88f, h * 0.12f)
                        drawLine(
                            color = Color(0xFFCFD8DC),
                            start = c,
                            end = arrowTip,
                            strokeWidth = 2.2.dp.toPx()
                        )
                        val head = Path().apply {
                            moveTo(arrowTip.x, arrowTip.y)
                            lineTo(arrowTip.x - 7.dp.toPx(), arrowTip.y)
                            lineTo(arrowTip.x, arrowTip.y)
                            lineTo(arrowTip.x, arrowTip.y + 7.dp.toPx())
                        }
                        drawPath(head, color = Color(0xFFCFD8DC), style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    // Blue center banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .height(13.dp)
                            .background(Color(0xFF0D47A1))
                            .border(0.5.dp, Color(0xFF90CAF9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VOLVO",
                            color = Color.White,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
            "MERCEDES_GREEN" -> {
                // Mercedes Three-Pointed Star
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
                        val w = size.width
                        val h = size.height
                        val c = Offset(w * 0.5f, h * 0.5f)
                        val r = w * 0.42f

                        // Outer chrome ring
                        drawCircle(
                            color = Color(0xFFECEFF1),
                            radius = r,
                            center = c,
                            style = Stroke(width = 1.6.dp.toPx())
                        )

                        // Star Point 1 (Top: 0°, pointing up)
                        val p1 = Offset(c.x, c.y - r)
                        // Star Point 2 (120°, pointing down-right)
                        val p2 = Offset(c.x + r * 0.866f, c.y + r * 0.5f)
                        // Star Point 3 (240°, pointing down-left)
                        val p3 = Offset(c.x - r * 0.866f, c.y + r * 0.5f)

                        // Draw 3-point star lines
                        drawLine(Color(0xFFECEFF1), c, p1, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color(0xFFECEFF1), c, p2, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color(0xFFECEFF1), c, p3, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    Text(
                        text = "ACTROS",
                        color = if (isCentered) centerColor else Color(0xFF00E676),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                    )
                }
            }
            "DAF_WHITE" -> {
                // DAF Bold Twin-Bar Emblem
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Canvas(modifier = Modifier.size(34.dp, 3.dp)) {
                        drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.5.dp.toPx())
                    }
                    Text(
                        text = "DAF",
                        color = if (isCentered) centerColor else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Canvas(modifier = Modifier.size(34.dp, 3.dp)) {
                        drawLine(Color(0xFFE53935), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
            else -> {
                Text(
                    text = "ETS2",
                    color = if (isCentered) centerColor else accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
