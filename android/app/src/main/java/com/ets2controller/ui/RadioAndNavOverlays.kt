package com.ets2controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.network.RadioStation
import com.ets2controller.network.UiNavCommand
import com.ets2controller.ui.theme.*
import kotlin.math.roundToInt

/**
 * Scrollable Cab Radio browser backed by ETS2's live_streams.sii catalog.
 * Supports both in-drive ETS2 radio controls (radiotoggle/radioup/radiodown) and
 * direct phone streaming to jump straight to any of the 329 stations without pausing ETS2.
 */
@Composable
fun DRadioOverlay(
    stations: List<RadioStation>,
    accent: Color,
    usePhoneStream: Boolean,
    ets2Playing: Boolean,
    ets2Index: Int,
    streamPlaying: Boolean,
    streamBuffering: Boolean,
    streamStation: RadioStation?,
    streamError: String?,
    streamVolume: Float,
    onToggleMode: (Boolean) -> Unit,
    onSelectStation: (RadioStation) -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onEts2VolDown: () -> Unit,
    onEts2VolUp: () -> Unit,
    onEts2Fav: () -> Unit,
    onOpenEts2RadioScreen: () -> Unit,
    onStreamVolumeChange: (Float) -> Unit,
    onRequestSync: () -> Unit,
    onDismiss: () -> Unit
) {
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val hPad = maxOf(14.dp, safe.calculateLeftPadding(layoutDir), safe.calculateRightPadding(layoutDir))
    val vPad = maxOf(8.dp, safe.calculateTopPadding(), safe.calculateBottomPadding())

    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }

    val filtered = remember(stations, query, favOnly) {
        val q = query.trim().lowercase()
        stations.filter { st ->
            (!favOnly || st.isFavorite) &&
                (q.isEmpty() || st.name.lowercase().contains(q) || st.genre.lowercase().contains(q) || st.country.lowercase().contains(q))
        }
    }
    val activeIdx = if (usePhoneStream) streamStation?.index ?: -1 else ets2Index
    val isPlayingNow = if (usePhoneStream) streamPlaying else ets2Playing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = hPad, end = hPad, top = vPad, bottom = vPad),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Cab Radio", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            ModePillPair(
                left = "ETS2 RADIO",
                right = "PHONE STREAM",
                rightSelected = usePhoneStream,
                accent = accent,
                onSelect = onToggleMode
            )
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (favOnly) accent else CockpitPanel)
                    .border(1.dp, if (favOnly) accent else CockpitBorder, RoundedCornerShape(8.dp))
                    .clickable { favOnly = !favOnly }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (favOnly) "\u2605 Favs" else "All (${stations.size})",
                    color = if (favOnly) DarkBackground else TextBright,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitDeep)
                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text("Search station, genre, country...", color = TextMuted, fontSize = 11.sp, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextBright, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitPanel)
                    .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
                    .clickable { onRequestSync() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Sync", color = TextBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "\u2715 Close",
                color = BrakePedalRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Station list
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CockpitDeep)
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (stations.isEmpty()) "No stations synced yet from ETS2 live_streams.sii"
                        else "No matching stations for \"$query\"",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (stations.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent)
                                .clickable { onRequestSync() }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text("Sync ETS2 Stations", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.index }) { st ->
                        val active = st.index == activeIdx
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) accent.copy(alpha = 0.16f) else CockpitPanel)
                                .border(1.dp, if (active) accent else CockpitBorder, RoundedCornerShape(8.dp))
                                .clickable { onSelectStation(st) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "${st.index + 1}",
                                color = if (active) accent else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                st.name,
                                color = if (active) TextBright else TextBright.copy(alpha = 0.92f),
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${st.genre} \u00B7 ${st.country} \u00B7 ${st.bitrate}k",
                                color = TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                            if (st.isFavorite) {
                                Text("\u2605", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (active) {
                                DEq(isPlayingNow)
                            }
                        }
                    }
                }
            }
        }

        // Bottom player & volume controls bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CockpitPanel)
                .border(1.dp, CockpitBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallActionPill(icon = "prev", label = "", tint = TextBright, onClick = onPrev)
            SmallActionPill(
                icon = if (isPlayingNow) "pause" else "play",
                label = if (streamBuffering && usePhoneStream) "BUFF..." else if (isPlayingNow) "PAUSE" else "PLAY",
                tint = DarkBackground,
                bg = accent,
                onClick = onPlayPause
            )
            SmallActionPill(icon = "next", label = "", tint = TextBright, onClick = onNext)

            if (usePhoneStream) {
                DIcon("volume", accent, Modifier.size(16.dp))
                Slider(
                    value = streamVolume,
                    onValueChange = onStreamVolumeChange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = CockpitDeep
                    )
                )
                Text("${(streamVolume * 100).roundToInt()}%", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (streamError != null) {
                    Text(streamError, color = BrakePedalRed, fontSize = 10.sp, maxLines = 1)
                }
            } else {
                Spacer(Modifier.width(4.dp))
                DIcon("volume", accent, Modifier.size(16.dp))
                SmallActionPill(icon = "minus", label = "VOL -", tint = TextBright, onClick = onEts2VolDown)
                SmallActionPill(icon = "plus", label = "VOL +", tint = TextBright, onClick = onEts2VolUp)
                SmallActionPill(icon = "star", label = "FAV", tint = accent, onClick = onEts2Fav)
                Spacer(Modifier.weight(1f))
                SmallActionPill(icon = "list", label = "ETS2 UI", tint = TextMuted, onClick = onOpenEts2RadioScreen)
            }
        }
    }
}

/**
 * Compact modal for ETS2 Trailer Delivery parking choices (+90/+15/+0 XP)
 * and universal menu navigation (Arrow D-Pad, Tab, Enter, Esc).
 */
@Composable
fun DUiNavOverlay(
    accent: Color,
    onUiCommand: (Byte) -> Unit,
    onDismiss: () -> Unit
) {
    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val hPad = maxOf(14.dp, safe.calculateLeftPadding(layoutDir), safe.calculateRightPadding(layoutDir))
    val vPad = maxOf(8.dp, safe.calculateTopPadding(), safe.calculateBottomPadding())
    var selectedDelivery by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = hPad, end = hPad, top = vPad, bottom = vPad),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trailer Delivery & UI Menu", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "ETS2 WINDOW FOCUSED \u00B7 PICK OPTION THEN CONFIRM",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            Text(
                "\u2715 Close",
                color = BrakePedalRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left: Trailer Delivery 3 options
            DCard(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("TRAILER DELIVERY OPTIONS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    listOf(
                        Triple(1, "Where do you need it?", "+90 XP \u00B7 Skilled Reverse"),
                        Triple(2, "Let's play it safe", "+15 XP \u00B7 Easy Pull-In"),
                        Triple(3, "Sorry, in a hurry (Skip)", "+0 XP \u00B7 Instant Finish")
                    ).forEach { (opt, title, sub) ->
                        val picked = selectedDelivery == opt
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (picked) accent.copy(alpha = 0.16f) else CockpitDeep)
                                .border(1.dp, if (picked) accent else CockpitBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedDelivery = opt
                                    val cmd = when (opt) {
                                        1 -> UiNavCommand.DELIVERY_OPT_1
                                        2 -> UiNavCommand.DELIVERY_OPT_2
                                        else -> UiNavCommand.DELIVERY_OPT_3
                                    }
                                    onUiCommand(cmd)
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(title, color = TextBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(sub, color = if (picked) accent else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (picked) "SELECTED" else "SELECT", color = if (picked) accent else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ConnectedGreen)
                            .clickable { onUiCommand(UiNavCommand.DELIVERY_CONFIRM) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\u2713  CONFIRM DELIVERY", color = DarkBackground, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            // Right: Universal UI D-Pad
            DCard(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("UNIVERSAL MENU D-PAD", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavPadBtn("TAB", Modifier.weight(1f).fillMaxHeight()) { onUiCommand(UiNavCommand.TAB) }
                        NavPadBtn("\u25B2 UP", Modifier.weight(1f).fillMaxHeight(), accent) { onUiCommand(UiNavCommand.UP) }
                        NavPadBtn("ESC", Modifier.weight(1f).fillMaxHeight(), SoftRed) { onUiCommand(UiNavCommand.ESC) }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavPadBtn("\u25C0 LEFT", Modifier.weight(1f).fillMaxHeight(), accent) { onUiCommand(UiNavCommand.LEFT) }
                        NavPadBtn("\u2713 ENTER", Modifier.weight(1.1f).fillMaxHeight(), DarkBackground, accent) { onUiCommand(UiNavCommand.ENTER) }
                        NavPadBtn("RIGHT \u25B6", Modifier.weight(1f).fillMaxHeight(), accent) { onUiCommand(UiNavCommand.RIGHT) }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        NavPadBtn("\u25BC DOWN", Modifier.weight(1f).fillMaxHeight(), accent) { onUiCommand(UiNavCommand.DOWN) }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePillPair(left: String, right: String, rightSelected: Boolean, accent: Color, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CockpitPanel)
            .border(1.dp, CockpitBorder, RoundedCornerShape(8.dp))
    ) {
        listOf(false to left, true to right).forEach { (isRight, label) ->
            val on = rightSelected == isRight
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(isRight) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) DarkBackground else TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun SmallActionPill(
    icon: String,
    label: String,
    tint: Color,
    bg: Color = CockpitDeep,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (bg == CockpitDeep) CockpitBorder else bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (label.isEmpty()) 12.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        DIcon(icon, tint, Modifier.size(15.dp))
        if (label.isNotEmpty()) {
            Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
private fun NavPadBtn(
    label: String,
    modifier: Modifier,
    fg: Color = TextBright,
    bg: Color = CockpitDeep,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, if (bg == CockpitDeep) CockpitBorder else bg, RoundedCornerShape(10.dp))
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}
