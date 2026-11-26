package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay

private data class MoreItem(val label: String, val icon: String, val bit: Int, val guarded: Boolean = false, val momentary: Boolean = false)

private val MORE_GROUPS: List<Pair<String, List<MoreItem>>> = listOf(
    "DRIVE ASSIST" to listOf(MoreItem("ACC gap", "acc_gap", 46), MoreItem("AEB brake", "brake", 48), MoreItem("Lane assist", "lane", 47), MoreItem("Drive", "d", 56), MoreItem("Reverse", "r", 57)),
    "BRAKES" to listOf(MoreItem("Park", "park", 6, true), MoreItem("Eng brake", "exh", 7), MoreItem("Retarder up", "up", 24), MoreItem("Retarder dn", "down", 25), MoreItem("Trailer brake", "trailer", 45, momentary = true)),
    "TRAILER AND AXLES" to listOf(MoreItem("Couple", "link", 20, true), MoreItem("Trailer axle", "axle_trailer", 44), MoreItem("Truck axle", "axle_truck", 19), MoreItem("Diff lock", "lock", 18), MoreItem("Susp reset", "reset", 53)),
    "SUSPENSION" to listOf(MoreItem("Front up", "susp_f_up", 49, momentary = true), MoreItem("Front down", "susp_f_dn", 50, momentary = true), MoreItem("Rear up", "susp_r_up", 51, momentary = true), MoreItem("Rear down", "susp_r_dn", 52, momentary = true), MoreItem("Neutral", "n", 17)),
    "ELECTRICS AND LIGHTS" to listOf(MoreItem("Engine", "engine", 1, true), MoreItem("Lights", "low", 2), MoreItem("High beam", "high", 21), MoreItem("Flash", "flash", 35, momentary = true), MoreItem("Cabin lt", "sun", 58)),
    "CABIN" to listOf(MoreItem("Window L", "window", 54, momentary = true), MoreItem("Window R", "window", 55, momentary = true), MoreItem("Beacon", "beacon", 22), MoreItem("Wipers", "wiper", 12), MoreItem("Air horn", "horn", 23, momentary = true)),
    "VIEWS" to listOf(MoreItem("Look L", "look_left", 38), MoreItem("Look R", "look_right", 39), MoreItem("Map", "map", 42), MoreItem("Dash info", "gauge", 29), MoreItem("Service", "clock", 41)),
    "CAMERAS" to listOf(MoreItem("Interior", "cam_interior", 13), MoreItem("Chase", "cam_chase", 34), MoreItem("Top", "cam_top", 37), MoreItem("Window", "cam_window", 33), MoreItem("Mirrors", "mirror", 40)),
    "GAME" to listOf(MoreItem("Route", "route", 8), MoreItem("Job info", "job", 31), MoreItem("Photo", "cam", 59), MoreItem("Quick save", "save", 32), MoreItem("Pause", "pause", 43), MoreItem("Activate", "check", 30))
)

/** The More panel: scrollable two-column groups with cutout-safe margins and guarded hold support. */
@Composable
fun DMorePanel(
    holdMs: Long = 400L,
    slideOffCancel: Boolean = true,
    onTrigger: (Int) -> Unit,
    onHoldChange: (Int, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val leftPad = maxOf(16.dp, safe.calculateLeftPadding(layoutDir))
    val rightPad = maxOf(16.dp, safe.calculateRightPadding(layoutDir))
    val topPad = maxOf(10.dp, safe.calculateTopPadding())
    val botPad = maxOf(10.dp, safe.calculateBottomPadding())

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = leftPad, end = rightPad, top = maxOf(6.dp, topPad), bottom = maxOf(6.dp, botPad)),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("More", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("TAP OR SCROLL \u00B7 HOLD RINGED BUTTONS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
            Text(
                "\u2715 Close", color = BrakePedalRed, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDismiss() }.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(MORE_GROUPS.take(5), MORE_GROUPS.drop(5)).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    col.forEach { (title, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items.forEach { it0 ->
                                    DTile(
                                        it0.label, it0.icon, Modifier.weight(1f).height(38.dp),
                                        guarded = it0.guarded, holdMs = holdMs, slideOffCancel = slideOffCancel,
                                        iconSize = 19.dp,
                                        onPressChanged = if (it0.momentary) { held -> onHoldChange(it0.bit, held) } else null,
                                        onTap = { if (!it0.momentary) onTrigger(it0.bit) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Connection content: found PC, manual IP, checklist. Used by both dialog and Tuning -> Connection. */
@Composable
fun DConnectionContent(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    accent: Color,
    onDone: () -> Unit,
    showPinnedActions: Boolean = true
) {
    var ip by remember { mutableStateOf(connectedIp.ifBlank { savedManualIp }) }
    var statusNotice by remember { mutableStateOf("") }

    LaunchedEffect(savedManualIp, connectedIp) {
        if (ip.isBlank()) {
            ip = connectedIp.ifBlank { savedManualIp }
        }
    }
    LaunchedEffect(statusNotice) {
        if (statusNotice.isNotEmpty()) {
            delay(1800L)
            statusNotice = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CONNECT TO PC", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitDeep)
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DIcon("wifi", if (isConnected) ConnectedGreen else TextMuted, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isConnected) "ETS2 PC Connected" else "Searching\u2026", color = TextBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isConnected) "$connectedIp \u00B7 $packetsPerSec pps" else "Listening for ETS2 beacon (UDP 5006) or USB ADB (127.0.0.1)",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            label = { Text("PC IPv4 address (127.0.0.1 for USB ADB)") }, modifier = Modifier.fillMaxWidth()
        )
        Text("CHECKLIST", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        CheckLine(true, "Same Wi-Fi, USB Tethering (RNDIS), or ADB USB (127.0.0.1)")
        CheckLine(isConnected, "PC plugin found")
        CheckLine(isConnected && packetsPerSec > 0, "Streaming controls ($packetsPerSec pps)")

        if (statusNotice.isNotEmpty()) {
            Text(statusNotice, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        if (showPinnedActions) {
            ConnectionActionRow(
                accent = accent,
                onAutoScan = {
                    onSetManualIp("")
                    ip = ""
                    statusNotice = "Auto-discovery active"
                    onDone()
                },
                onConnect = {
                    onSetManualIp(ip)
                    statusNotice = if (ip.isBlank()) "Auto-discovery active" else "Connecting to ${ip.trim()}\u2026"
                    onDone()
                }
            )
        }
    }
}

@Composable
private fun ConnectionActionRow(
    accent: Color,
    onAutoScan: () -> Unit,
    onConnect: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "AUTO SCAN", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAutoScan() }.padding(horizontal = 14.dp, vertical = 12.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "CONNECT", color = DarkBackground, fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(accent)
                .clickable { onConnect() }.padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun DConnectDialog(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    accent: Color,
    onDismiss: () -> Unit
) {
    var ip by remember { mutableStateOf(connectedIp.ifBlank { savedManualIp }) }
    LaunchedEffect(savedManualIp, connectedIp) {
        if (ip.isBlank()) ip = connectedIp.ifBlank { savedManualIp }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))
            .imePadding()
            .clickable { onDismiss() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val maxDialogH = (maxHeight * 0.94f).coerceAtLeast(240.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = maxDialogH)
                .clip(RoundedCornerShape(14.dp))
                .background(CockpitPanel)
                .border(1.dp, CockpitBorderStrong, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("CONNECT TO PC", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(
                        "\u2715", color = TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDismiss() }.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitDeep)
                        .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DIcon("wifi", if (isConnected) ConnectedGreen else TextMuted, Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (isConnected) "ETS2 PC Connected" else "Searching\u2026", color = TextBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (isConnected) "$connectedIp \u00B7 $packetsPerSec pps" else "Auto-discovery running (UDP 5006)",
                            color = TextMuted, fontSize = 11.sp
                        )
                    }
                }
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it }, singleLine = true,
                    label = { Text("PC IPv4 address (127.0.0.1 for USB ADB)") }, modifier = Modifier.fillMaxWidth()
                )
                CheckLine(true, "Same Wi-Fi, USB Tethering, or ADB USB (127.0.0.1)")
                CheckLine(isConnected, "PC server found")
                CheckLine(isConnected && packetsPerSec > 0, "Receiving data ($packetsPerSec pps)")
            }
            ConnectionActionRow(
                accent = accent,
                onAutoScan = { onSetManualIp(""); ip = ""; onDismiss() },
                onConnect = { onSetManualIp(ip); onDismiss() }
            )
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))
                .background(if (ok) ConnectedGreen.copy(alpha = 0.18f) else Color.Transparent)
                .border(if (ok) 0.dp else 2.dp, CockpitBorder, RoundedCornerShape(11.dp)),
            Alignment.Center
        ) { if (ok) DIcon("check", ConnectedGreen, Modifier.size(14.dp), 2.4f) }
        Text(text, color = TextBright, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
