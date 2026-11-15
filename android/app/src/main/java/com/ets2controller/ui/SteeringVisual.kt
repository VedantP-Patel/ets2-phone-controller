package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.CockpitBorder
import com.ets2controller.ui.theme.CockpitPanel
import com.ets2controller.ui.theme.getThemeAccent
import kotlin.math.abs

@Composable
fun SteeringVisual(
    steeringProvider: () -> Float,
    rawAngleProvider: () -> Float,
    clusterTheme: String,
    onCalibrateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = getThemeAccent(clusterTheme)
    val angleDeg = rawAngleProvider()
    val normSteer = steeringProvider()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCalibrateClick() },
        contentAlignment = Alignment.Center
    ) {
        // Static outer steering arc gauge (shows normalized wheel lock left/right)
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val radius = (size.minDimension / 2f).coerceAtLeast(36f)
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcRadius = radius + 6f

            // Background sweep track (-135° to +135° around top)
            drawArc(
                color = Color(0xFF1E2430),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )

            // Active steering sweep from top center (270°)
            val sweep = normSteer * 120f
            if (abs(sweep) > 0.5f) {
                drawArc(
                    color = accent,
                    startAngle = 270f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
            }

            // Top zero-point tick mark
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(center.x, center.y - arcRadius - 5f),
                end = Offset(center.x, center.y - arcRadius + 5f),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        // Hardware-accelerated rotating 3-spoke truck steering wheel
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .graphicsLayer {
                    rotationZ = angleDeg
                }
        ) {
            val radius = (size.minDimension / 2f).coerceAtLeast(30f)
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer dark leather wheel rim
            drawCircle(
                color = Color(0xFF141820),
                radius = radius,
                center = center,
                style = Stroke(width = 22f)
            )
            // Inner rim bevel highlight
            drawCircle(
                color = Color(0xFF2D3545),
                radius = radius,
                center = center,
                style = Stroke(width = 16f)
            )

            // Left & Right ergonomic thumb grip bolsters (9 & 3 o'clock)
            drawArc(
                color = Color(0xFF3E485C),
                startAngle = 155f,
                sweepAngle = 50f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 24f, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFF3E485C),
                startAngle = -25f,
                sweepAngle = 50f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 24f, cap = StrokeCap.Round)
            )

            // Metallic brushed aluminum spokes (Left, Right, Bottom Twin-Spoke)
            val spokeBrush = Brush.linearGradient(
                colors = listOf(Color(0xFF4B5569), Color(0xFF2A303D))
            )
            // Left spoke
            drawLine(
                brush = spokeBrush,
                start = Offset(center.x - radius + 10f, center.y + 4f),
                end = Offset(center.x - 16f, center.y + 2f),
                strokeWidth = 18f,
                cap = StrokeCap.Round
            )
            // Right spoke
            drawLine(
                brush = spokeBrush,
                start = Offset(center.x + radius - 10f, center.y + 4f),
                end = Offset(center.x + 16f, center.y + 2f),
                strokeWidth = 18f,
                cap = StrokeCap.Round
            )
            // Bottom twin spokes
            drawLine(
                brush = spokeBrush,
                start = Offset(center.x - 10f, center.y + 14f),
                end = Offset(center.x - 14f, center.y + radius - 10f),
                strokeWidth = 12f,
                cap = StrokeCap.Round
            )
            drawLine(
                brush = spokeBrush,
                start = Offset(center.x + 10f, center.y + 14f),
                end = Offset(center.x + 14f, center.y + radius - 10f),
                strokeWidth = 12f,
                cap = StrokeCap.Round
            )

            // Top center alignment stripe (high-contrast accent)
            drawLine(
                color = accent,
                start = Offset(center.x, center.y - radius - 11f),
                end = Offset(center.x, center.y - radius + 11f),
                strokeWidth = 8f,
                cap = StrokeCap.Round
            )

            // Center airbag / horn pad hub
            drawCircle(
                color = Color(0xFF181D26),
                radius = 30f,
                center = center
            )
            drawCircle(
                color = accent.copy(alpha = 0.65f),
                radius = 30f,
                center = center,
                style = Stroke(width = 2.5f)
            )
        }

        // Center Hub Digital Readout + Calibrate Trigger
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CockpitPanel.copy(alpha = 0.85f))
                .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${if (angleDeg >= 0f) "+" else ""}${"%.1f".format(angleDeg)}°",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "CENTER",
                color = accent,
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}