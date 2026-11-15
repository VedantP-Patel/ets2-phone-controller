package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun CockpitVectorIcon(
    name: String,
    tint: Color,
    modifier: Modifier = Modifier,
    wiperAngleDeg: Float = 0f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val s = minOf(w, h)
        val stroke = Stroke(width = (s * 0.085f).coerceAtLeast(1.8.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (name) {
            "ARROW_LEFT" -> {
                drawLine(tint, Offset(w * 0.22f, cy), Offset(w * 0.78f, cy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.44f, h * 0.28f), Offset(w * 0.22f, cy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.44f, h * 0.72f), Offset(w * 0.22f, cy), stroke.width, StrokeCap.Round)
            }
            "ARROW_RIGHT" -> {
                drawLine(tint, Offset(w * 0.22f, cy), Offset(w * 0.78f, cy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.56f, h * 0.28f), Offset(w * 0.78f, cy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.56f, h * 0.72f), Offset(w * 0.78f, cy), stroke.width, StrokeCap.Round)
            }
            "CHEVRON_UP" -> {
                drawLine(tint, Offset(w * 0.28f, h * 0.62f), Offset(cx, h * 0.36f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.62f), Offset(cx, h * 0.36f), stroke.width, StrokeCap.Round)
            }
            "CHEVRON_DOWN" -> {
                drawLine(tint, Offset(w * 0.28f, h * 0.38f), Offset(cx, h * 0.64f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.38f), Offset(cx, h * 0.64f), stroke.width, StrokeCap.Round)
            }
            "HORN" -> {
                val cone = Path().apply {
                    moveTo(w * 0.20f, h * 0.40f)
                    lineTo(w * 0.34f, h * 0.40f)
                    lineTo(w * 0.54f, h * 0.24f)
                    lineTo(w * 0.54f, h * 0.76f)
                    lineTo(w * 0.34f, h * 0.60f)
                    lineTo(w * 0.20f, h * 0.60f)
                    close()
                }
                drawPath(cone, tint, style = stroke)
                drawArc(tint, -40f, 80f, false, Offset(w * 0.50f, h * 0.30f), Size(w * 0.24f, h * 0.40f), style = stroke)
                drawArc(tint, -45f, 90f, false, Offset(w * 0.54f, h * 0.18f), Size(w * 0.34f, h * 0.64f), style = stroke)
            }
            "LIGHTS_LOW" -> {
                // D-shaped lamp housing on the right, 3 downward-slanted beams on the left
                drawArc(tint, -90f, 180f, false, Offset(w * 0.36f, h * 0.24f), Size(w * 0.42f, h * 0.52f), style = stroke)
                drawLine(tint, Offset(w * 0.57f, h * 0.24f), Offset(w * 0.57f, h * 0.76f), stroke.width, StrokeCap.Round)
                for (i in 0..2) {
                    val y = h * (0.28f + i * 0.18f)
                    drawLine(tint, Offset(w * 0.22f, y + h * 0.09f), Offset(w * 0.45f, y), stroke.width * 0.85f, StrokeCap.Round)
                }
            }
            "HIGH_BEAM" -> {
                // D-shaped lamp housing on the right, 3 horizontal beams on the left
                drawArc(tint, -90f, 180f, false, Offset(w * 0.36f, h * 0.24f), Size(w * 0.42f, h * 0.52f), style = stroke)
                drawLine(tint, Offset(w * 0.57f, h * 0.24f), Offset(w * 0.57f, h * 0.76f), stroke.width, StrokeCap.Round)
                for (i in 0..2) {
                    val y = h * (0.32f + i * 0.18f)
                    drawLine(tint, Offset(w * 0.20f, y), Offset(w * 0.45f, y), stroke.width * 0.85f, StrokeCap.Round)
                }
            }
            "BRAKE_PEDAL" -> {
                drawRoundRect(tint, Offset(w * 0.22f, h * 0.28f), Size(w * 0.56f, h * 0.44f), CornerRadius(s * 0.12f), style = stroke)
                drawLine(tint, Offset(w * 0.36f, cy), Offset(w * 0.64f, cy), stroke.width, StrokeCap.Round)
            }
            "GAS_PEDAL" -> {
                drawRoundRect(tint, Offset(w * 0.32f, h * 0.16f), Size(w * 0.36f, h * 0.68f), CornerRadius(s * 0.10f), style = stroke)
                for (i in 1..3) {
                    val y = h * (0.16f + i * 0.17f)
                    drawLine(tint, Offset(w * 0.38f, y), Offset(w * 0.62f, y), stroke.width * 0.75f, StrokeCap.Round)
                }
            }
            "CRUISE" -> {
                drawArc(tint, 145f, 250f, false, Offset(w * 0.20f, h * 0.22f), Size(w * 0.60f, h * 0.60f), style = stroke)
                drawLine(tint, Offset(cx, cy + h * 0.04f), Offset(w * 0.66f, h * 0.34f), stroke.width, StrokeCap.Round)
            }
            "WIPERS" -> {
                drawArc(tint, 205f, 130f, false, Offset(w * 0.14f, h * 0.28f), Size(w * 0.72f, h * 0.72f), style = stroke)
                rotate(degrees = wiperAngleDeg, pivot = Offset(cx, h * 0.74f)) {
                    drawLine(tint, Offset(cx, h * 0.74f), Offset(w * 0.34f, h * 0.26f), stroke.width, StrokeCap.Round)
                }
            }
            "CAMERA" -> {
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.30f), Size(w * 0.64f, h * 0.44f), CornerRadius(s * 0.10f), style = stroke)
                drawCircle(tint, s * 0.13f, Offset(cx, h * 0.52f), style = stroke)
                drawLine(tint, Offset(w * 0.36f, h * 0.24f), Offset(w * 0.64f, h * 0.24f), stroke.width, StrokeCap.Round)
            }
            "HAZARD" -> {
                val tri = Path().apply {
                    moveTo(cx, h * 0.20f)
                    lineTo(w * 0.82f, h * 0.78f)
                    lineTo(w * 0.18f, h * 0.78f)
                    close()
                }
                drawPath(tri, tint, style = stroke)
                val inner = Path().apply {
                    moveTo(cx, h * 0.38f)
                    lineTo(w * 0.66f, h * 0.68f)
                    lineTo(w * 0.34f, h * 0.68f)
                    close()
                }
                drawPath(inner, tint, style = Stroke(width = stroke.width * 0.75f, join = StrokeJoin.Round))
            }
            "PARK" -> {
                drawCircle(tint, s * 0.34f, Offset(cx, cy), style = stroke)
                drawLine(tint, Offset(w * 0.43f, h * 0.34f), Offset(w * 0.43f, h * 0.66f), stroke.width, StrokeCap.Round)
                drawArc(tint, -90f, 180f, false, Offset(w * 0.38f, h * 0.34f), Size(w * 0.22f, h * 0.18f), style = stroke)
            }
            "ENGINE" -> {
                drawRoundRect(tint, Offset(w * 0.26f, h * 0.34f), Size(w * 0.48f, h * 0.36f), CornerRadius(s * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.62f, h * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(cx, h * 0.24f), Offset(cx, h * 0.34f), stroke.width)
                drawLine(tint, Offset(w * 0.16f, h * 0.44f), Offset(w * 0.16f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.84f, h * 0.42f), Offset(w * 0.84f, h * 0.64f), stroke.width, StrokeCap.Round)
            }
            "TRAILER" -> {
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.26f), Size(w * 0.46f, h * 0.34f), CornerRadius(s * 0.06f), style = stroke)
                drawLine(tint, Offset(w * 0.64f, h * 0.52f), Offset(w * 0.84f, h * 0.52f), stroke.width, StrokeCap.Round)
                drawCircle(tint, s * 0.07f, Offset(w * 0.32f, h * 0.68f), style = stroke)
                drawCircle(tint, s * 0.07f, Offset(w * 0.50f, h * 0.68f), style = stroke)
            }
            "DIFF_LOCK" -> {
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.46f), Size(w * 0.44f, h * 0.34f), CornerRadius(s * 0.08f), style = stroke)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.35f, h * 0.22f), Size(w * 0.30f, h * 0.44f), style = stroke)
            }
            "GEAR" -> {
                drawCircle(tint, s * 0.22f, Offset(cx, cy), style = stroke)
                for (i in 0 until 8) {
                    rotate(degrees = i * 45f, pivot = Offset(cx, cy)) {
                        drawLine(tint, Offset(cx, h * 0.16f), Offset(cx, h * 0.25f), stroke.width, StrokeCap.Round)
                    }
                }
            }
            "PHONE" -> {
                rotate(-18f, pivot = Offset(cx, cy)) {
                    drawRoundRect(tint, Offset(w * 0.30f, h * 0.18f), Size(w * 0.40f, h * 0.64f), CornerRadius(s * 0.09f), style = stroke)
                }
            }
            "WHEEL" -> {
                drawCircle(tint, s * 0.35f, Offset(cx, cy), style = stroke)
                drawCircle(tint, s * 0.08f, Offset(cx, cy), style = stroke)
                drawLine(tint, Offset(w * 0.17f, cy), Offset(w * 0.83f, cy), stroke.width * 0.85f)
                drawLine(tint, Offset(cx, cy), Offset(cx, h * 0.83f), stroke.width * 0.85f)
            }
            "LANE" -> {
                drawLine(tint, Offset(w * 0.30f, h * 0.22f), Offset(w * 0.20f, h * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.70f, h * 0.22f), Offset(w * 0.80f, h * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(cx, h * 0.26f), Offset(cx, h * 0.42f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(cx, h * 0.56f), Offset(cx, h * 0.74f), stroke.width, StrokeCap.Round)
            }
            "LINK" -> {
                rotate(-45f, pivot = Offset(cx, cy)) {
                    drawRoundRect(tint, Offset(w * 0.22f, h * 0.38f), Size(w * 0.32f, h * 0.24f), CornerRadius(s * 0.12f), style = stroke)
                    drawRoundRect(tint, Offset(w * 0.46f, h * 0.38f), Size(w * 0.32f, h * 0.24f), CornerRadius(s * 0.12f), style = stroke)
                }
            }
            "AXLE" -> {
                drawLine(tint, Offset(cx, h * 0.24f), Offset(cx, h * 0.66f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.38f), Offset(cx, h * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.38f), Offset(cx, h * 0.24f), stroke.width, StrokeCap.Round)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.34f, h * 0.64f), Size(w * 0.32f, h * 0.24f), style = stroke)
            }
            "SUSP" -> {
                drawLine(tint, Offset(w * 0.28f, h * 0.40f), Offset(cx, h * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.40f), Offset(cx, h * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.60f), Offset(cx, h * 0.76f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.60f), Offset(cx, h * 0.76f), stroke.width, StrokeCap.Round)
            }
            "FLASH" -> {
                val bolt = Path().apply {
                    moveTo(w * 0.56f, h * 0.18f)
                    lineTo(w * 0.30f, h * 0.52f)
                    lineTo(w * 0.50f, h * 0.52f)
                    lineTo(w * 0.44f, h * 0.82f)
                    lineTo(w * 0.70f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.48f)
                    close()
                }
                drawPath(bolt, tint, style = stroke)
            }
            "WINDOW" -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.24f), Size(w * 0.52f, h * 0.52f), CornerRadius(s * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.24f, h * 0.46f), Offset(w * 0.76f, h * 0.46f), stroke.width * 0.85f)
            }
            "BEACON" -> {
                drawRoundRect(tint, Offset(w * 0.32f, h * 0.44f), Size(w * 0.36f, h * 0.26f), CornerRadius(s * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.22f, h * 0.74f), Offset(w * 0.78f, h * 0.74f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(cx, h * 0.20f), Offset(cx, h * 0.32f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.28f), Offset(w * 0.32f, h * 0.36f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.28f), Offset(w * 0.68f, h * 0.36f), stroke.width, StrokeCap.Round)
            }
            "EYE" -> {
                val eye = Path().apply {
                    moveTo(w * 0.16f, cy)
                    quadraticTo(cx, h * 0.22f, w * 0.84f, cy)
                    quadraticTo(cx, h * 0.78f, w * 0.16f, cy)
                }
                drawPath(eye, tint, style = stroke)
                drawCircle(tint, s * 0.10f, Offset(cx, cy), style = stroke)
            }
            "MAP" -> {
                val mapPath = Path().apply {
                    moveTo(w * 0.18f, h * 0.28f)
                    lineTo(w * 0.40f, h * 0.22f)
                    lineTo(w * 0.60f, h * 0.30f)
                    lineTo(w * 0.82f, h * 0.22f)
                    lineTo(w * 0.82f, h * 0.72f)
                    lineTo(w * 0.60f, h * 0.80f)
                    lineTo(w * 0.40f, h * 0.72f)
                    lineTo(w * 0.18f, h * 0.78f)
                    close()
                }
                drawPath(mapPath, tint, style = stroke)
            }
            "ROUTE" -> {
                drawCircle(tint, s * 0.08f, Offset(w * 0.32f, h * 0.28f), style = stroke)
                drawCircle(tint, s * 0.08f, Offset(w * 0.68f, h * 0.72f), style = stroke)
                val curve = Path().apply {
                    moveTo(w * 0.40f, h * 0.28f)
                    cubicTo(w * 0.76f, h * 0.28f, w * 0.24f, h * 0.72f, w * 0.60f, h * 0.72f)
                }
                drawPath(curve, tint, style = stroke)
            }
            "JOB" -> {
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.20f), Size(w * 0.44f, h * 0.60f), CornerRadius(s * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.38f, h * 0.38f), Offset(w * 0.62f, h * 0.38f), stroke.width * 0.8f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.50f), Offset(w * 0.62f, h * 0.50f), stroke.width * 0.8f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.62f), Offset(w * 0.54f, h * 0.62f), stroke.width * 0.8f, StrokeCap.Round)
            }
            "SAVE" -> {
                drawRoundRect(tint, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), CornerRadius(s * 0.08f), style = stroke)
                drawRect(tint, Offset(w * 0.34f, h * 0.22f), Size(w * 0.28f, h * 0.20f), style = stroke)
                drawRect(tint, Offset(w * 0.32f, h * 0.54f), Size(w * 0.36f, h * 0.24f), style = stroke)
            }
            "PAUSE" -> {
                drawLine(tint, Offset(w * 0.38f, h * 0.26f), Offset(w * 0.38f, h * 0.74f), stroke.width * 1.15f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, h * 0.26f), Offset(w * 0.62f, h * 0.74f), stroke.width * 1.15f, StrokeCap.Round)
            }
            "CAR_RADAR" -> {
                val cab = Path().apply {
                    moveTo(w * 0.28f, h * 0.30f)
                    lineTo(w * 0.72f, h * 0.30f)
                    lineTo(w * 0.78f, h * 0.66f)
                    lineTo(w * 0.22f, h * 0.66f)
                    close()
                }
                drawPath(cab, tint, style = stroke)
                drawCircle(tint, s * 0.045f, Offset(w * 0.35f, h * 0.54f))
                drawCircle(tint, s * 0.045f, Offset(w * 0.65f, h * 0.54f))
            }
            "MIRROR" -> {
                drawRoundRect(tint, Offset(w * 0.34f, h * 0.22f), Size(w * 0.32f, h * 0.56f), CornerRadius(s * 0.08f), style = stroke)
            }
            "AIR_PRESSURE" -> {
                drawLine(tint, Offset(w * 0.16f, h * 0.34f), Offset(w * 0.68f, h * 0.34f), stroke.width * 0.85f, StrokeCap.Round)
                drawArc(tint, -90f, 180f, false, Offset(w * 0.56f, h * 0.22f), Size(w * 0.22f, h * 0.24f), style = Stroke(stroke.width * 0.85f, cap = StrokeCap.Round))
                drawLine(tint, Offset(w * 0.16f, h * 0.52f), Offset(w * 0.80f, h * 0.52f), stroke.width * 0.85f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.70f), Offset(w * 0.64f, h * 0.70f), stroke.width * 0.85f, StrokeCap.Round)
            }
            "FUEL_PUMP" -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.22f), Size(w * 0.40f, h * 0.58f), CornerRadius(s * 0.06f), style = stroke)
                drawRect(tint, Offset(w * 0.32f, h * 0.30f), Size(w * 0.24f, h * 0.18f), style = Stroke(stroke.width * 0.75f))
                val hose = Path().apply {
                    moveTo(w * 0.64f, h * 0.34f)
                    lineTo(w * 0.76f, h * 0.42f)
                    lineTo(w * 0.76f, h * 0.68f)
                    lineTo(w * 0.68f, h * 0.68f)
                }
                drawPath(hose, tint, style = Stroke(stroke.width * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "DROPLET" -> {
                val drop = Path().apply {
                    moveTo(cx, h * 0.18f)
                    cubicTo(w * 0.76f, h * 0.48f, w * 0.78f, h * 0.80f, cx, h * 0.80f)
                    cubicTo(w * 0.22f, h * 0.80f, w * 0.24f, h * 0.48f, cx, h * 0.18f)
                    close()
                }
                drawPath(drop, tint, style = stroke)
            }
            "THERMOMETER" -> {
                drawRoundRect(tint, Offset(w * 0.41f, h * 0.18f), Size(w * 0.18f, h * 0.46f), CornerRadius(s * 0.09f), style = stroke)
                drawCircle(tint, s * 0.16f, Offset(cx, h * 0.70f), style = stroke)
            }
            "RETARDER_COIL" -> {
                val wave = Path().apply {
                    moveTo(w * 0.14f, cy)
                    lineTo(w * 0.28f, cy)
                    lineTo(w * 0.38f, h * 0.28f)
                    lineTo(w * 0.50f, h * 0.72f)
                    lineTo(w * 0.62f, h * 0.28f)
                    lineTo(w * 0.72f, cy)
                    lineTo(w * 0.86f, cy)
                }
                drawPath(wave, tint, style = Stroke(stroke.width * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> {
                drawRoundRect(tint, Offset(w * 0.26f, h * 0.26f), Size(w * 0.48f, h * 0.48f), CornerRadius(s * 0.10f), style = stroke)
            }
        }
    }
}