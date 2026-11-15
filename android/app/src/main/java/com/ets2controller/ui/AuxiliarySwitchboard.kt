package com.ets2controller.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ets2controller.ui.theme.*

data class MoreTileDef(
    val id: String,
    val label: String,
    val icon: String,
    val bitIndex: Int,
    val isLatched: Boolean = false
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoreDrawerOverlay(
    pinnedTileId: String,
    accentColor: Color,
    onTriggerTile: (MoreTileDef) -> Unit,
    onPinTile: (MoreTileDef) -> Unit,
    onDismiss: () -> Unit
) {
    val leftCategories = remember {
        listOf(
            "DRIVE ASSIST" to listOf(
                MoreTileDef("acc_gap", "ACC GAP", "CAR_RADAR", 46),
                MoreTileDef("acc_mode", "ACC MODE", "CRUISE", 48),
                MoreTileDef("lane_assist", "LANE ASSIST", "LANE", 47),
                MoreTileDef("drive", "DRIVE", "D_LETTER", 56),
                MoreTileDef("reverse", "REVERSE", "R_LETTER", 57)
            ),
            "CHASSIS" to listOf(
                MoreTileDef("diff_lock", "DIFF LOCK", "DIFF_LOCK", 18, true),
                MoreTileDef("front", "FRONT", "SUSP", 49),
                MoreTileDef("rear", "REAR", "SUSP", 51),
                MoreTileDef("eng_brake", "ENG BRAKE", "ARROW_RIGHT", 7),
                MoreTileDef("flash", "FLASH", "FLASH", 35)
            ),
            "VIEWS" to listOf(
                MoreTileDef("look_l", "LOOK L", "EYE", 38),
                MoreTileDef("look_r", "LOOK R", "EYE", 39),
                MoreTileDef("mirrors", "MIRRORS", "MIRROR", 40),
                MoreTileDef("next_cam", "NEXT CAM", "CAMERA", 13),
                MoreTileDef("map", "MAP", "MAP", 42)
            )
        )
    }

    val rightCategories = remember {
        listOf(
            "TRAILER" to listOf(
                MoreTileDef("couple", "COUPLE", "LINK", 20),
                MoreTileDef("trailer_brake", "TRAILER\nBRAKE", "TRAILER", 45),
                MoreTileDef("trailer_axle", "TRAILER AXLE", "AXLE", 44),
                MoreTileDef("susp_reset", "SUSP RESET", "SUSP", 53),
                MoreTileDef("truck_axle", "TRUCK AXLE", "AXLE", 19)
            ),
            "CABIN" to listOf(
                MoreTileDef("window_l", "WINDOW L", "WINDOW", 54),
                MoreTileDef("window_r", "WINDOW R", "WINDOW", 55),
                MoreTileDef("beacon", "BEACON", "BEACON", 22, true),
                MoreTileDef("aux_light", "AUX LIGHT", "LIGHTS_LOW", 58),
                MoreTileDef("wipers", "WIPERS", "WIPERS", 12)
            ),
            "GAME" to listOf(
                MoreTileDef("route", "ROUTE", "ROUTE", 8),
                MoreTileDef("job_info", "JOB INFO", "JOB", 31),
                MoreTileDef("photo", "PHOTO", "CAMERA", 59),
                MoreTileDef("quick_save", "QUICK SAVE", "SAVE", 32),
                MoreTileDef("pause", "PAUSE", "PAUSE", 43)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "More",
                color = TextBright,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "LONG-PRESS ANY TILE TO PIN IT TO THE MAIN SCREEN",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "✕ Close",
                color = BrakePedalRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 2-Column x 3-Category Grid (30 Tiles Total)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            listOf(leftCategories, rightCategories).forEach { colCategories ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    colCategories.forEach { (catTitle, tiles) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = catTitle,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tiles.forEach { tile ->
                                    val isPinned = tile.id == pinnedTileId
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(62.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(CockpitTile)
                                            .border(
                                                width = if (isPinned) 1.5.dp else 1.dp,
                                                color = if (isPinned) accentColor else CockpitBorder,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .combinedClickable(
                                                onClick = { onTriggerTile(tile) },
                                                onLongClick = { onPinTile(tile) }
                                            )
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        when (tile.icon) {
                                            "D_LETTER" -> Text("D", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Black)
                                            "R_LETTER" -> Text("R", color = TextBright, fontSize = 17.sp, fontWeight = FontWeight.Black)
                                            else -> CockpitVectorIcon(
                                                name = tile.icon,
                                                tint = if (isPinned) accentColor else TextBright,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = tile.label,
                                            color = if (isPinned) accentColor else TextBright,
                                            fontSize = 8.5.sp,
                                            lineHeight = 9.5.sp,
                                            textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 2
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