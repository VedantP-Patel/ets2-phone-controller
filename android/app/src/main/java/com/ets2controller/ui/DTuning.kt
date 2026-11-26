package com.ets2controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.ui.theme.*
import kotlin.math.pow
import kotlin.math.roundToInt

private val TABS = listOf("Steering" to "wheel", "Controls & safety" to "lock", "Feedback" to "pulse", "Cockpit" to "gear", "Connection" to "wifi")

/** Tuning screens: responsive, cutout-safe, scrollable across all 5 tabs, live theme preview. */
@Composable
fun DTuningScreen(
    settings: ControllerSettings,
    onSave: (ControllerSettings) -> Unit,
    onDismiss: () -> Unit,
    isConnected: Boolean = false,
    connectedIp: String = "",
    packetsPerSec: Int = 0,
    savedManualIp: String = "",
    onSetManualIp: (String) -> Unit = {}
) {
    var s by remember { mutableStateOf(settings) }
    var tab by remember { mutableStateOf(0) }
    val accent = getThemeAccent(s.clusterTheme)
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val leftPad = maxOf(8.dp, safe.calculateLeftPadding(layoutDir))
    val rightPad = maxOf(8.dp, safe.calculateRightPadding(layoutDir))
    val topPad = maxOf(6.dp, safe.calculateTopPadding())
    val botPad = maxOf(6.dp, safe.calculateBottomPadding())

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = leftPad, end = rightPad, top = topPad, bottom = botPad)
    ) {
        val sidebarW = (maxWidth * 0.21f).coerceIn(132.dp, 174.dp)

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(sidebarW).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Tuning", color = TextBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                TABS.forEachIndexed { i, (name, icon) ->
                    val on = i == tab
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) accent else CockpitTile)
                            .border(1.dp, if (on) accent else CockpitBorder, RoundedCornerShape(12.dp))
                            .clickable { tab = i }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DIcon(icon, if (on) DarkBackground else TextBright, Modifier.size(15.dp))
                        Text(name.uppercase(), color = if (on) DarkBackground else TextBright, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(CockpitBorder))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    when (tab) {
                        0 -> SteeringTab(s, accent) { s = it }
                        1 -> ControlsTab(s, accent) { s = it }
                        2 -> Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Toggle("Haptic feedback", "Short ticks on button presses", s.hapticFeedbackEnabled, accent) { s = s.copy(hapticFeedbackEnabled = it) }
                            Toggle("Force feedback rumble", "Vibrates on rumble strips, curbs, and collisions", s.forceFeedbackEnabled, accent) { s = s.copy(forceFeedbackEnabled = it) }
                            Toggle("Cockpit audio", "Relay tick, air hiss, shifter thunks, and air horn", s.soundEffectsEnabled, accent) { s = s.copy(soundEffectsEnabled = it) }
                            Toggle("Steering detents", "A notch every 15 degrees of steering", s.steeringHapticTension, accent) { s = s.copy(steeringHapticTension = it) }
                        }
                        3 -> Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Label("SPEED UNIT")
                            Seg(listOf("km/h", "mph"), if (s.speedUnitMph) 1 else 0, accent) { s = s.copy(speedUnitMph = it == 1) }
                            Label("COCKPIT THEME")
                            val themes = listOf("SCANIA_AMBER", "VOLVO_CYAN", "MERCEDES_GREEN", "DAF_WHITE")
                            Seg(listOf("Scania", "Volvo", "Mercedes", "DAF"), themes.indexOf(s.clusterTheme).coerceAtLeast(0), accent) { s = s.copy(clusterTheme = themes[it]) }
                            Toggle("Auto-cancel turn signals", "Cancels when the wheel straightens", s.autoCancelBlinkers, accent) { s = s.copy(autoCancelBlinkers = it) }
                        }
                        else -> Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)
                        ) {
                            DConnectionContent(isConnected, connectedIp, packetsPerSec, savedManualIp, onSetManualIp, accent, onDone = {})
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(CockpitBorder))
                Row(
                    modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cancel", color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Save & apply", color = DarkBackground, fontSize = 14.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(accent)
                            .clickable { onSave(s); onDismiss() }.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun RowScope.SteeringTab(s: ControllerSettings, accent: Color, onChange: (ControllerSettings) -> Unit) {
    val sens = (45f / s.steeringMaxAngleDeg).coerceIn(0.5f, 2.0f)
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Label("WHEEL RANGE")
        val ranges = listOf(540, 720, 900, 1080)
        Seg(ranges.map { "$it" }, ranges.indexOf(s.wheelDegreesRange.toInt()).coerceAtLeast(0), accent) { onChange(s.copy(wheelDegreesRange = ranges[it].toFloat())) }
        Label("RESPONSE CURVE \u00B7 THEME NOW, BLUE AT 120 KM/H")
        CurveChart(s.steeringCurvePower, s.speedSensitivity, accent)
        Toggle("1:1 wheel mode", "Uses full selected wheel range instead of wrist angle", s.wheelMode1to1, accent) { onChange(s.copy(wheelMode1to1 = it)) }
        Toggle("Invert steering", "Flips left and right", s.invertSteering, accent) { onChange(s.copy(invertSteering = it)) }
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Tune in this order: sensitivity, deadzone, smoothing.", color = TextMuted, fontSize = 10.sp)
        TuneSlider("1  Sensitivity", "%.1f\u00D7".format(sens), sens, 0.5f, 2.0f, accent) { onChange(s.copy(steeringMaxAngleDeg = 45f / it)) }
        TuneSlider("2  Deadzone", "%.1f\u00B0".format(s.steeringDeadzoneDeg), s.steeringDeadzoneDeg, 0f, 10f, accent) { onChange(s.copy(steeringDeadzoneDeg = it)) }
        TuneSlider("3  Smoothing", "%.1f".format(s.filterMinCutoff), s.filterMinCutoff, 0.4f, 2.5f, accent) { onChange(s.copy(filterMinCutoff = it)) }
        TuneSlider("Curve (gamma)", "%.2f".format(s.steeringCurvePower), s.steeringCurvePower, 1.0f, 2.2f, accent) { onChange(s.copy(steeringCurvePower = it)) }
        TuneSlider("Speed sensitivity", "${(s.speedSensitivity * 100).roundToInt()}%", s.speedSensitivity, 0f, 1f, accent) { onChange(s.copy(speedSensitivity = it)) }
        TuneSlider("Caster self-center", "${(s.casterSpringStrength * 100).roundToInt()}%", s.casterSpringStrength, 0f, 1f, accent) { onChange(s.copy(casterSpringStrength = it)) }
    }
}

@Composable
private fun RowScope.ControlsTab(s: ControllerSettings, accent: Color, onChange: (ControllerSettings) -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Label("CONTROL SIZE")
        val sizes = listOf(0.95f, 1.0f, 1.2f)
        Seg(listOf("Compact", "Standard", "Large"), sizes.indexOfFirst { it == s.controlScale }.let { if (it < 0) 1 else it }, accent) { onChange(s.copy(controlScale = sizes[it])) }
        Label("HAND LAYOUT")
        Seg(listOf("Right-handed", "Left-handed mirror"), if (s.leftHanded) 1 else 0, accent) { onChange(s.copy(leftHanded = it == 1)) }
        Toggle("Ergonomic thumb layout", "Places blinkers, shift & BRAKE in thumb reach", s.ergonomicLayout, accent) { onChange(s.copy(ergonomicLayout = it)) }
        Toggle("Relative drag pedals", "Slide up from anywhere on GAS/BRAKE", s.relativePedalDrag, accent) { onChange(s.copy(relativePedalDrag = it)) }
        Toggle("Brake wins over gas", "Brake overrides throttle if both touched", s.brakeWins, accent) { onChange(s.copy(brakeWins = it)) }
        Toggle("Cancel tap on slide-off", "Drag off a button before lifting to cancel", s.slideOffCancel, accent) { onChange(s.copy(slideOffCancel = it)) }
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TuneSlider("Hold-to-confirm time", "%.1f s".format(s.holdConfirmMs / 1000f), s.holdConfirmMs.toFloat(), 200f, 800f, accent) { onChange(s.copy(holdConfirmMs = (it / 50f).roundToInt() * 50)) }
        TuneSlider("Haptic strength", "${(s.hapticStrength * 100).roundToInt()}%", s.hapticStrength, 0.1f, 1f, accent) { onChange(s.copy(hapticStrength = it)) }
        TuneSlider("Edge guard width", "${s.edgeGuardDp} dp", s.edgeGuardDp.toFloat(), 8f, 32f, accent) { onChange(s.copy(edgeGuardDp = it.roundToInt())) }
        Toggle("Hazard needs a hold", "Turn off to make hazard instant", s.hazardNeedsHold, accent) { onChange(s.copy(hazardNeedsHold = it)) }
        Toggle("Speed-sensitive deadzone", "Smaller deadzone at low speed", s.speedSensitiveDeadzone, accent) { onChange(s.copy(speedSensitiveDeadzone = it)) }
        Toggle("Reduce motion", "Disables decorative pulse & scale transitions", s.reduceMotion, accent) { onChange(s.copy(reduceMotion = it)) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
}

@Composable
private fun Seg(options: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) accent else CockpitDeep)
                    .border(1.dp, if (on) accent else CockpitBorder, RoundedCornerShape(10.dp))
                    .clickable { onSelect(i) },
                Alignment.Center
            ) { Text(o, color = if (on) DarkBackground else TextBright, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
        }
    }
}

@Composable
private fun TuneSlider(label: String, valueText: String, value: Float, min: Float, max: Float, accent: Color, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(valueText, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = CockpitBorder),
            modifier = Modifier.height(34.dp)
        )
    }
}

@Composable
private fun Toggle(label: String, sub: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = TextBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = TextMuted, fontSize = 10.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.4f))
        )
    }
}

/** Response curve: input on the bottom, output on the left. Dashed line is linear. */
@Composable
private fun CurveChart(gamma: Float, speedSens: Float, accent: Color) {
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(CockpitDeep)
            .border(1.dp, CockpitBorder, RoundedCornerShape(10.dp))
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - pad * 2f
        val h = size.height - pad * 2f
        fun pt(x: Float, y: Float) = Offset(pad + w * x, pad + h * (1f - y))
        drawLine(CockpitBorderStrong, pt(0f, 0f), pt(1f, 1f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        val fast = 1f - speedSens * 0.45f
        for (pass in 0..1) {
            var prev = pt(0f, 0f)
            for (i in 1..40) {
                val x = i / 40f
                val y = x.pow(gamma) * (if (pass == 0) fast else 1f)
                val p = pt(x, y)
                drawLine(if (pass == 0) VolvoIceBlue else accent, prev, p, if (pass == 0) 2.dp.toPx() else 3.dp.toPx(), StrokeCap.Round)
                prev = p
            }
        }
    }
}
