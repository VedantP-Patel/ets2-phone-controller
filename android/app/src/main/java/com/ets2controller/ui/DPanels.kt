package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*

private data class MoreItem(val label: String, val icon: String, val bit: Int, val guarded: Boolean = false)

private val MORE_GROUPS: List<Pair<String, List<MoreItem>>> = listOf(
    "DRIVE ASSIST" to listOf(MoreItem("ACC gap", "car", 46), MoreItem("ACC mode", "gauge", 48), MoreItem("Lane assist", "lane", 47), MoreItem("Drive", "d", 56), MoreItem("Reverse", "r", 57)),
    "BRAKES" to listOf(MoreItem("Park", "park", 6, true), MoreItem("Eng brake", "exh", 7), MoreItem("Retarder up", "up", 24), MoreItem("Retarder down", "down", 25), MoreItem("Trailer brake", "trailer", 45)),
    "TRAILER AND AXLES" to listOf(MoreItem("Couple", "link", 20, true), MoreItem("Trailer axle", "axle", 44), MoreItem("Truck axle", "axle", 19), MoreItem("Diff lock", "lock", 18), MoreItem("Susp reset", "reset", 53)),
    "SUSPENSION" to listOf(MoreItem("Front up", "updown", 49), MoreItem("Front down", "updown", 50), MoreItem("Rear up", "updown", 51), MoreItem("Rear down", "updown", 52), MoreItem("Neutral", "n", 17)),
    "ELECTRICS AND LIGHTS" to listOf(MoreItem("Engine", "engine", 1, true), MoreItem("Lights", "low", 2), MoreItem("High beam", "high", 21), MoreItem("Flash", "flash", 35), MoreItem("Aux light", "sun", 58)),
    "CABIN" to listOf(MoreItem("Window L", "window", 54), MoreItem("Window R", "window", 55), MoreItem("Beacon", "beacon", 22), MoreItem("Wipers", "wiper", 12), MoreItem("Air horn", "horn", 23)),
    "VIEWS" to listOf(MoreItem("Look L", "eye", 38), MoreItem("Look R", "eye", 39), MoreItem("Map", "map", 42), MoreItem("Dash info", "gauge", 29), MoreItem("Rest stop", "clock", 41)),
    "CAMERAS" to listOf(MoreItem("Interior", "cam", 13), MoreItem("Chase", "cam", 34), MoreItem("Top", "cam", 37), MoreItem("Window", "cam", 33), MoreItem("Mirrors", "mirror", 40)),
    "GAME" to listOf(MoreItem("Route", "route", 8), MoreItem("Job info", "job", 31), MoreItem("Photo", "cam", 59), MoreItem("Quick save", "save", 32), MoreItem("Pause", "pause", 43), MoreItem("Activate", "check", 30))
)

/** The More panel: eight groups of five, same grouping as the design. Park, Engine and Couple need a hold. */
@Composable
fun DMorePanel(onTrigger: (Int) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("More", color = TextBright, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("TAP TO SEND \u00B7 TAP THE HANDLE AT THE BOTTOM FOR THE QUICK DOCK", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
            Text(
                "\u2715 Close", color = BrakePedalRed, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDismiss() }.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            listOf(MORE_GROUPS.take(5), MORE_GROUPS.drop(5)).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    col.forEach { (title, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.forEach { it0 ->
                                    DTile(
                                        it0.label, it0.icon, Modifier.weight(1f).height(52.dp), guarded = it0.guarded,
                                        onTap = { onTrigger(it0.bit) }
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

/** Connection content: found PC, manual IP, checklist. Used by the dialog and the Connection tab. */
@Composable
fun DConnectionContent(
    isConnected: Boolean,
    connectedIp: String,
    packetsPerSec: Int,
    savedManualIp: String,
    onSetManualIp: (String) -> Unit,
    accent: Color,
    onDone: () -> Unit
) {
    var ip by remember { mutableStateOf(connectedIp.ifBlank { savedManualIp }) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CONNECT TO PC", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text("FOUND ON THIS WI-FI", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CockpitDeep)
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DIcon("wifi", if (isConnected) ConnectedGreen else TextMuted, Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isConnected) "ETS2 PC" else "Searching\u2026", color = TextBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(if (isConnected) connectedIp else "Auto-discovery is running", color = TextMuted, fontSize = 11.sp)
            }
        }
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            label = { Text("Or enter the PC IPv4 address") }, modifier = Modifier.fillMaxWidth()
        )
        Text("CHECKLIST", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        CheckLine(true, "Phone is on the same network or USB tethering")
        CheckLine(isConnected, "PC server found")
        CheckLine(isConnected && packetsPerSec > 0, "Receiving data ($packetsPerSec packets per second)")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AUTO SCAN", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onSetManualIp(""); ip = ""; onDone() }.padding(12.dp)
            )
            Text(
                "CONNECT", color = DarkBackground, fontSize = 12.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(accent)
                    .clickable { onSetManualIp(ip); onDone() }.padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
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
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.width(460.dp).clip(RoundedCornerShape(14.dp)).background(CockpitPanel)
                .border(1.dp, CockpitBorderStrong, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}.padding(18.dp)
        ) {
            DConnectionContent(isConnected, connectedIp, packetsPerSec, savedManualIp, onSetManualIp, accent, onDismiss)
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
