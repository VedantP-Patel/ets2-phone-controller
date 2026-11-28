package com.ets2controller.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.config.DEFAULT_QUICK_DOCK_BITS
import com.ets2controller.config.normalizeQuickDockBits
import com.ets2controller.config.placeInQuickDockSlot
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.delay

internal data class ControlAction(
    val label: String,
    val icon: String,
    val bit: Int,
    val guarded: Boolean = false,
    val momentary: Boolean = false
)

internal val CONTROL_GROUPS: List<Pair<String, List<ControlAction>>> = listOf(
    "DRIVE ASSIST" to listOf(ControlAction("ACC gap", "acc_gap", 46), ControlAction("AEB brake", "brake", 48), ControlAction("Lane assist", "lane", 47), ControlAction("Drive", "d", 56), ControlAction("Reverse", "r", 57)),
    "BRAKES" to listOf(ControlAction("Park", "park", 6, true), ControlAction("Eng brake", "exh", 7), ControlAction("Retarder up", "up", 24), ControlAction("Retarder dn", "down", 25), ControlAction("Trailer brake", "trailer", 45, momentary = true)),
    "TRAILER AND AXLES" to listOf(ControlAction("Couple", "link", 20, true), ControlAction("Trailer axle", "axle_trailer", 44), ControlAction("Truck axle", "axle_truck", 19), ControlAction("Diff lock", "lock", 18), ControlAction("Susp reset", "reset", 53)),
    "SUSPENSION" to listOf(ControlAction("Front up", "susp_f_up", 49, momentary = true), ControlAction("Front down", "susp_f_dn", 50, momentary = true), ControlAction("Rear up", "susp_r_up", 51, momentary = true), ControlAction("Rear down", "susp_r_dn", 52, momentary = true), ControlAction("Neutral", "n", 17)),
    "ELECTRICS AND LIGHTS" to listOf(ControlAction("Engine", "engine", 1, true), ControlAction("Lights", "low", 2), ControlAction("High beam", "high", 21), ControlAction("Flash", "flash", 35, momentary = true), ControlAction("Cabin lt", "sun", 58)),
    "CABIN" to listOf(ControlAction("Window L", "window", 54, momentary = true), ControlAction("Window R", "window", 55, momentary = true), ControlAction("Beacon", "beacon", 22), ControlAction("Wipers", "wiper", 12), ControlAction("Air horn", "horn", 23, momentary = true)),
    "VIEWS" to listOf(ControlAction("Look L", "look_left", 38), ControlAction("Look R", "look_right", 39), ControlAction("Map", "map", 42), ControlAction("Dash info", "gauge", 29), ControlAction("Service", "clock", 41)),
    "CAMERAS" to listOf(ControlAction("Interior", "cam_interior", 13), ControlAction("Chase", "cam_chase", 34), ControlAction("Top", "cam_top", 37), ControlAction("Window", "cam_window", 33), ControlAction("Mirrors", "mirror", 40)),
    "GAME" to listOf(ControlAction("Route", "route", 8), ControlAction("Job info", "job", 31), ControlAction("Photo", "cam", 59), ControlAction("Quick save", "save", 32), ControlAction("Pause", "pause", 43), ControlAction("Activate", "check", 30))
)

private val CONTROL_BY_BIT: Map<Int, ControlAction> =
    CONTROL_GROUPS.asSequence().flatMap { it.second.asSequence() }.associateBy { it.bit }

internal fun findControlByBit(bit: Int): ControlAction? = CONTROL_BY_BIT[bit]

internal fun isControlActive(bit: Int, t: TruckTelemetryV2): Boolean = when (bit) {
    1 -> t.isEngineRunning
    2 -> t.isLowBeams
    6 -> t.isParkBrake
    7 -> t.isEngineBrakeActive
    12 -> t.isWipersActive
    18 -> t.isDiffLock
    19 -> t.isTruckLiftAxle
    20 -> t.isTrailerConnected
    21 -> t.isHighBeams
    22 -> t.isBeacons
    44 -> t.isTrailerLiftAxle
    47 -> t.isLkaActive
    else -> false
}

/** The More panel: scrollable two-column groups with cutout-safe margins, guarded hold support, and Pin-to-Dock mode. */
@Composable
fun DMorePanel(
    holdMs: Long = 400L,
    slideOffCancel: Boolean = true,
    quickDockBits: List<Int> = DEFAULT_QUICK_DOCK_BITS,
    onUpdateQuickDock: (List<Int>) -> Unit = {},
    onTrigger: (Int) -> Unit,
    onHoldChange: (Int, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val accent = LocalAccent.current
    val reduceMotion = LocalReduceMotion.current
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val leftPad = maxOf(16.dp, safe.calculateLeftPadding(layoutDir))
    val rightPad = maxOf(16.dp, safe.calculateRightPadding(layoutDir))
    val topPad = maxOf(10.dp, safe.calculateTopPadding())
    val botPad = maxOf(10.dp, safe.calculateBottomPadding())

    val dockSlots = remember(quickDockBits) { normalizeQuickDockBits(quickDockBits) }
    var pinMode by remember { mutableStateOf(false) }
    var selectedBit by remember { mutableStateOf<Int?>(null) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = leftPad, end = rightPad, top = maxOf(6.dp, topPad), bottom = maxOf(6.dp, botPad)),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("More", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val hint = when {
                !pinMode -> "TAP OR SCROLL \u00B7 HOLD RINGED BUTTONS"
                selectedBit != null -> "TAP SLOT #1\u2013#8 FOR ${(findControlByBit(selectedBit!!)?.label ?: "").uppercase()}"
                selectedSlot != null -> "TAP ANY ACTION TO PIN IN SLOT #${selectedSlot!! + 1}"
                else -> "TAP AN ACTION OR A DOCK SLOT #1\u2013#8"
            }
            Text(hint, color = if (pinMode) accent else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pinMode) "Done Pinning" else "Pin to Dock",
                    color = if (pinMode) DarkBackground else accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (pinMode) accent else CockpitPanel)
                        .border(1.dp, if (pinMode) accent else accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable {
                            pinMode = !pinMode
                            selectedBit = null
                            selectedSlot = null
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    "\u2715 Close", color = BrakePedalRed, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDismiss() }.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(CONTROL_GROUPS.take(5), CONTROL_GROUPS.drop(5)).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    col.forEach { (title, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items.forEach { it0 ->
                                    val dockIdx = dockSlots.indexOf(it0.bit)
                                    val isPicked = pinMode && selectedBit == it0.bit
                                    Box(Modifier.weight(1f).height(38.dp)) {
                                        if (pinMode) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .semantics { contentDescription = "Pin ${it0.label}" }
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(
                                                        when {
                                                            isPicked -> accent.copy(alpha = 0.22f)
                                                            dockIdx >= 0 -> CockpitPanel
                                                            else -> CockpitTile
                                                        }
                                                    )
                                                    .border(
                                                        width = if (isPicked) 1.5.dp else 1.dp,
                                                        color = when {
                                                            isPicked -> accent
                                                            dockIdx >= 0 -> accent.copy(alpha = 0.5f)
                                                            else -> CockpitBorder
                                                        },
                                                        shape = RoundedCornerShape(9.dp)
                                                    )
                                                    .clickable {
                                                        val targetSlot = selectedSlot
                                                        if (targetSlot != null) {
                                                            onUpdateQuickDock(placeInQuickDockSlot(dockSlots, targetSlot, it0.bit))
                                                            selectedSlot = null
                                                            selectedBit = null
                                                        } else {
                                                            selectedBit = if (selectedBit == it0.bit) null else it0.bit
                                                        }
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                DIcon(it0.icon, if (isPicked || dockIdx >= 0) accent else TextBright, Modifier.size(16.dp))
                                                Text(
                                                    it0.label.uppercase(),
                                                    color = if (isPicked) accent else TextBright,
                                                    fontSize = 7.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (dockIdx >= 0) {
                                                Text(
                                                    "#${dockIdx + 1}",
                                                    color = DarkBackground,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(accent)
                                                        .padding(horizontal = 3.dp, vertical = 0.5.dp)
                                                )
                                            }
                                        } else {
                                            DTile(
                                                it0.label, it0.icon, Modifier.fillMaxSize(),
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
        AnimatedVisibility(
            visible = pinMode,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(140)) + shrinkVertically(tween(160))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CockpitPanel)
                    .border(1.dp, CockpitBorderStrong, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dockSlots.forEachIndexed { idx, bit ->
                    val act = findControlByBit(bit)
                    val isSlotPicked = selectedSlot == idx
                    val highlight = isSlotPicked || selectedBit != null
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .semantics { contentDescription = "Quick dock slot ${idx + 1}" }
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSlotPicked) accent.copy(alpha = 0.22f) else CockpitTile)
                            .border(
                                width = if (isSlotPicked) 1.5.dp else 1.dp,
                                color = when {
                                    isSlotPicked -> accent
                                    selectedBit != null -> accent.copy(alpha = 0.55f)
                                    else -> CockpitBorder
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                val pickBit = selectedBit
                                if (pickBit != null) {
                                    onUpdateQuickDock(placeInQuickDockSlot(dockSlots, idx, pickBit))
                                    selectedBit = null
                                    selectedSlot = null
                                } else {
                                    selectedSlot = if (selectedSlot == idx) null else idx
                                }
                            }
                            .padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "#${idx + 1}",
                            color = if (highlight) accent else TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (act != null) {
                            DIcon(act.icon, TextBright, Modifier.size(13.dp))
                            Text(act.label.uppercase(), color = TextBright, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    "Reset",
                    color = ScaniaAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CockpitTile)
                        .border(1.dp, ScaniaAmber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable {
                            onUpdateQuickDock(DEFAULT_QUICK_DOCK_BITS)
                            selectedBit = null
                            selectedSlot = null
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ip, onValueChange = { ip = it }, singleLine = true,
                label = { Text("PC IPv4 address (127.0.0.1 for USB ADB)") }, modifier = Modifier.weight(1f)
            )
            Text(
                "USB 127.0.0.1",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitDeep)
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable {
                        ip = "127.0.0.1"
                        onSetManualIp("127.0.0.1")
                        statusNotice = "Connecting over USB ADB (127.0.0.1)\u2026"
                    }
                    .padding(horizontal = 10.dp, vertical = 14.dp)
            )
        }
        Text("CHECKLIST", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        CheckLine(true, "1. Launch ETS2 & click OK on \"Request to use advanced SDK features\"")
        CheckLine(true, "2. Run [F] Firewall in start.bat (or enable Phone Hotspot / USB Tethering)")
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
