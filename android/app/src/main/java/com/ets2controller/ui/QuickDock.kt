package com.ets2controller.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.ets2controller.config.swapQuickDockSlots
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Quick dock: editable bottom drawer of eight customizable controls over a dimmed cockpit.
 * Supports smooth drag-to-close physics, slot reordering, and swapping any action from the More catalog.
 */
@Composable
fun QuickDockOverlay(
    accentColor: Color,
    telemetry: TruckTelemetryV2,
    holdMs: Long = 400L,
    slideOffCancel: Boolean = true,
    quickDockBits: List<Int> = DEFAULT_QUICK_DOCK_BITS,
    onUpdateQuickDock: (List<Int>) -> Unit = {},
    sheetModifier: Modifier = Modifier,
    onTrigger: (Int) -> Unit,
    onHoldChange: (Int, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val reduceMotion = LocalReduceMotion.current
    val dockSlots = remember(quickDockBits) { normalizeQuickDockBits(quickDockBits) }
    var editMode by remember { mutableStateOf(false) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }

    val dragOffsetY = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val backdropAlpha = 0.62f * (1f - (dragOffsetY.value / 420f).coerceIn(0f, 0.7f))

    val layoutDir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val hPad = maxOf(12.dp, safe.calculateLeftPadding(layoutDir), safe.calculateRightPadding(layoutDir))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoRows = maxWidth < 660.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Dismiss quick dock" }
                .background(Color.Black.copy(alpha = backdropAlpha))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(sheetModifier)
                .fillMaxWidth()
                .padding(start = hPad, end = hPad, bottom = maxOf(12.dp, safe.calculateBottomPadding()))
                .graphicsLayer { translationY = dragOffsetY.value }
                .clip(RoundedCornerShape(16.dp))
                .background(CockpitPanel)
                .border(1.dp, CockpitBorder, RoundedCornerShape(16.dp))
                .pointerInput(editMode, pickingSlot) {
                    if (editMode || pickingSlot != null) return@pointerInput
                    detectVerticalDragGestures(
                        onDragCancel = {
                            dragScope.launch {
                                dragOffsetY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragEnd = {
                            if (dragOffsetY.value > 110f) {
                                onDismiss()
                            } else {
                                dragScope.launch {
                                    dragOffsetY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        },
                        onVerticalDrag = { _, dy ->
                            dragScope.launch {
                                val next = dragOffsetY.value + (if (dy < 0f && dragOffsetY.value <= 0f) dy * 0.25f else dy)
                                dragOffsetY.snapTo(next.coerceAtLeast(-14f))
                            }
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (editMode) "QUICK DOCK \u00B7 TAP SLOT TO SWAP OR \u25C0 \u25B6 TO MOVE" else "QUICK DOCK \u00B7 SWIPE DOWN TO CLOSE",
                    color = if (editMode) accentColor else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.size(width = 44.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(CockpitBorderStrong))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editMode) {
                        Text(
                            "Reset",
                            color = ScaniaAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(CockpitTile)
                                .border(1.dp, ScaniaAmber.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                                .clickable {
                                    onUpdateQuickDock(DEFAULT_QUICK_DOCK_BITS)
                                    pickingSlot = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        if (editMode) "Done" else "Edit",
                        color = if (editMode) DarkBackground else accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (editMode) accentColor else CockpitTile)
                            .border(1.dp, if (editMode) accentColor else accentColor.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                            .clickable {
                                editMode = !editMode
                                pickingSlot = null
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = editMode && pickingSlot != null,
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(140)) + shrinkVertically(tween(160))
            ) {
                val activeSlot = pickingSlot ?: 0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 175.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CockpitDeep)
                        .border(1.dp, CockpitBorderStrong, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "REPLACE SLOT #${activeSlot + 1}",
                            color = accentColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.7.sp
                        )
                        Text(
                            "Cancel",
                            color = BrakePedalRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { pickingSlot = null }.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val cols = if (twoRows) listOf(CONTROL_GROUPS) else listOf(CONTROL_GROUPS.take(5), CONTROL_GROUPS.drop(5))
                        cols.forEach { colGroups ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                colGroups.forEach { (groupName, items) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(groupName, color = TextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            items.forEach { action ->
                                                val isCurrent = dockSlots.getOrNull(activeSlot) == action.bit
                                                val existingSlot = dockSlots.indexOf(action.bit)
                                                Row(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(32.dp)
                                                        .semantics { contentDescription = "Pick ${action.label}" }
                                                        .clip(RoundedCornerShape(7.dp))
                                                        .background(if (isCurrent) accentColor.copy(alpha = 0.2f) else CockpitTile)
                                                        .border(
                                                            width = if (isCurrent) 1.5.dp else 1.dp,
                                                            color = when {
                                                                isCurrent -> accentColor
                                                                existingSlot >= 0 -> accentColor.copy(alpha = 0.45f)
                                                                else -> CockpitBorder
                                                            },
                                                            shape = RoundedCornerShape(7.dp)
                                                        )
                                                        .clickable {
                                                            onUpdateQuickDock(placeInQuickDockSlot(dockSlots, activeSlot, action.bit))
                                                            pickingSlot = null
                                                        }
                                                        .padding(horizontal = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    DIcon(action.icon, if (isCurrent || existingSlot >= 0) accentColor else TextBright, Modifier.size(13.dp))
                                                    Text(
                                                        action.label.uppercase(),
                                                        color = if (isCurrent) accentColor else TextBright,
                                                        fontSize = 7.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
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
            }

            val indexedSlots = dockSlots.mapIndexed { index, bit -> index to bit }
            val rows = if (twoRows) listOf(indexedSlots.take(4), indexedSlots.drop(4)) else listOf(indexedSlots)
            rows.forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSlots.forEach { (slotIdx, bit) ->
                        val action = findControlByBit(bit) ?: ControlAction("SLOT ${slotIdx + 1}", "wheel", bit)
                        AnimatedContent(
                            targetState = bit,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                if (reduceMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    (fadeIn(tween(170)) + scaleIn(tween(170), initialScale = 0.92f)) togetherWith
                                        (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f))
                                }
                            },
                            label = "dockSlotAnim_$slotIdx"
                        ) { targetBit ->
                            val tile = findControlByBit(targetBit) ?: action
                            if (editMode) {
                                val isPicked = pickingSlot == slotIdx
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(68.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isPicked) accentColor.copy(alpha = 0.18f) else CockpitTile)
                                        .border(
                                            width = if (isPicked) 1.5.dp else 1.dp,
                                            color = if (isPicked) accentColor else CockpitBorderStrong,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable { pickingSlot = if (pickingSlot == slotIdx) null else slotIdx }
                                        .padding(horizontal = 4.dp, vertical = 3.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "\u25C0",
                                            color = if (slotIdx > 0) TextBright else TextMuted,
                                            fontSize = 9.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .alpha(if (slotIdx > 0) 1f else 0.35f)
                                                .semantics { contentDescription = "Move slot ${slotIdx + 1} left" }
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable(enabled = slotIdx > 0) {
                                                    onUpdateQuickDock(swapQuickDockSlots(dockSlots, slotIdx, slotIdx - 1))
                                                    pickingSlot = null
                                                }
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                        Text(
                                            "#${slotIdx + 1}",
                                            color = if (isPicked) accentColor else TextMuted,
                                            fontSize = 8.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            "\u25B6",
                                            color = if (slotIdx < dockSlots.lastIndex) TextBright else TextMuted,
                                            fontSize = 9.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .alpha(if (slotIdx < dockSlots.lastIndex) 1f else 0.35f)
                                                .semantics { contentDescription = "Move slot ${slotIdx + 1} right" }
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable(enabled = slotIdx < dockSlots.lastIndex) {
                                                    onUpdateQuickDock(swapQuickDockSlots(dockSlots, slotIdx, slotIdx + 1))
                                                    pickingSlot = null
                                                }
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    DIcon(tile.icon, if (isPicked) accentColor else TextBright, Modifier.size(16.dp))
                                    Text(
                                        tile.label.uppercase(),
                                        color = if (isPicked) accentColor else TextBright,
                                        fontSize = 8.sp,
                                        lineHeight = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                val on = isControlActive(tile.bit, telemetry)
                                DTile(
                                    label = tile.label.uppercase(),
                                    icon = tile.icon,
                                    modifier = Modifier.fillMaxWidth().height(60.dp),
                                    tint = if (on) accentColor else TextBright,
                                    ts = if (on) TS.LATCHED else TS.NONE,
                                    guarded = tile.guarded,
                                    holdMs = holdMs,
                                    slideOffCancel = slideOffCancel,
                                    onPressChanged = if (tile.momentary) { held -> onHoldChange(tile.bit, held) } else null,
                                    onTap = { if (!tile.momentary) onTrigger(tile.bit) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
