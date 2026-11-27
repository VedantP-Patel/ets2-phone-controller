package com.ets2controller.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.ets2controller.config.ControllerSettings
import com.ets2controller.config.DEFAULT_QUICK_DOCK_BITS
import com.ets2controller.network.RadioStation
import com.ets2controller.network.TruckTelemetryV2
import com.ets2controller.network.UiNavCommand
import com.ets2controller.ui.theme.GasPedalGreen
import com.ets2controller.ui.theme.ScaniaAmber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CockpitAnimationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guardedTileRequiresFullHoldDurationBeforeFiring() {
        composeRule.mainClock.autoAdvance = false
        var engineTriggered = false

        composeRule.setContent {
            CompositionLocalProvider(LocalAccent provides ScaniaAmber) {
                DTile(
                    label = "Engine",
                    icon = "engine",
                    modifier = Modifier.size(100.dp, 60.dp),
                    guarded = true,
                    holdMs = 400L,
                    onTap = { engineTriggered = true }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Engine").performTouchInput {
            down(center)
        }
        composeRule.mainClock.advanceTimeBy(200L)
        assertFalse("Guarded tile must NOT fire before holdMs", engineTriggered)

        composeRule.mainClock.advanceTimeBy(260L)
        assertTrue("Guarded tile should fire after holdMs completes", engineTriggered)

        composeRule.onNodeWithContentDescription("Engine").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(200L)
    }

    @Test
    fun pedalDragUpdatesLivePercentageAndResetsOnRelease() {
        composeRule.mainClock.autoAdvance = false
        var latestGas by mutableFloatStateOf(0f)

        composeRule.setContent {
            DPedal(
                label = "GAS",
                icon = "gas",
                color = GasPedalGreen,
                value = latestGas,
                modifier = Modifier.size(120.dp, 220.dp),
                onChange = { latestGas = it }
            )
        }

        composeRule.onNodeWithContentDescription("GAS pedal").performTouchInput {
            swipeUp(startY = bottom * 0.85f, endY = top + 10f, durationMillis = 120L)
        }
        composeRule.mainClock.advanceTimeBy(80L)
        // After swipeUp finishes, release zeroes out the pedal
        assertEquals(0f, latestGas, 1e-4f)

        // Holding near top shows live percentage badge
        composeRule.onNodeWithContentDescription("GAS pedal").performTouchInput {
            down(Offset(center.x, top + 12f))
        }
        composeRule.mainClock.advanceTimeBy(80L)
        assertTrue("GAS should saturate near 1.0 near top: $latestGas", latestGas > 0.95f)
        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("GAS pedal").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(80L)
        assertEquals(0f, latestGas, 1e-4f)
    }

    @Test
    fun slideSteeringBarSpringsBackToZeroAfterRelease() {
        composeRule.mainClock.autoAdvance = false
        var steerValue = 0f

        composeRule.setContent {
            SlideSteeringBar(
                accentColor = ScaniaAmber,
                onSteerChange = { steerValue = it },
                modifier = Modifier.fillMaxWidth().height(72.dp)
            )
        }

        composeRule.onNodeWithText("+0\u00B0").assertIsDisplayed()
        composeRule.onNodeWithText("+0\u00B0").performTouchInput {
            swipeLeft(startX = centerX, endX = centerX - 180f, durationMillis = 100L)
        }
        assertTrue("Steering should move negative on left swipe: $steerValue", steerValue < -0.2f)

        // Advance spring animation so handle returns cleanly to center
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.onNodeWithText("+0\u00B0").assertIsDisplayed()
    }

    @Test
    fun overlayTransitionsAndGearShiftAnimationStepCleanly() {
        composeRule.mainClock.autoAdvance = false
        var telem by mutableStateOf(TruckTelemetryV2(gear = 0, engineRpm = 650f, maxRpm = 2200f))

        composeRule.setContent {
            DesignCockpitScreen(
                isConnected = true,
                connectedIp = "127.0.0.1",
                packetsPerSec = 100,
                savedManualIp = "127.0.0.1",
                onSetManualIp = {},
                rawAngleProvider = { 0f },
                sensorModeLabel = "ACCEL",
                throttle = 0f,
                brake = 0f,
                clutch = 0f,
                buttonMask = 0L,
                settings = ControllerSettings(),
                telemetry = telem,
                onThrottleChange = {},
                onBrakeChange = {},
                onClutchChange = {},
                onButtonStateChange = { _, _ -> },
                onPulseBit = {},
                onCalibrateClick = {},
                onSaveSettings = {},
                onLookChange = { _, _ -> },
                onTouchSteerChange = {}
            )
        }

        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithText("N").assertIsDisplayed()

        // Step gear + RPM upward (triggers gear pop & RPM peak-hold animation)
        telem = telem.copy(gear = 3, engineRpm = 1950f)
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.onNodeWithText("3").assertIsDisplayed()

        // Open More overlay via AnimatedVisibility and close
        composeRule.onNodeWithContentDescription("more").performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.onNodeWithText("ACC GAP").assertIsDisplayed()
        composeRule.onNodeWithText("\u2715 Close").performClick()
        composeRule.mainClock.advanceTimeBy(300L)
    }

    @Test
    fun radioBrowserAndDeliveryUiNavWorkCleanly() {
        composeRule.mainClock.autoAdvance = false
        val cmds = mutableListOf<Byte>()
        var pickedStation: RadioStation? = null
        var phoneStream by mutableStateOf(false)
        val sampleStations = listOf(
            RadioStation(0, "TruckersFM", "Sim radio", "EN", 320, true, "https://radio.truckers.fm/"),
            RadioStation(1, "Simulator Radio", "Sim radio", "EN", 128, false, "http://radio.simulatorradio.com:8002/stream.mp3")
        )

        composeRule.setContent {
            DesignCockpitScreen(
                isConnected = true,
                connectedIp = "127.0.0.1",
                packetsPerSec = 100,
                savedManualIp = "127.0.0.1",
                onSetManualIp = {},
                rawAngleProvider = { 0f },
                sensorModeLabel = "ACCEL",
                throttle = 0f,
                brake = 0f,
                clutch = 0f,
                buttonMask = 0L,
                settings = ControllerSettings(),
                telemetry = TruckTelemetryV2(gear = 5, safetyFlags = 1 shl 5),
                onThrottleChange = {},
                onBrakeChange = {},
                onClutchChange = {},
                onButtonStateChange = { _, _ -> },
                onPulseBit = {},
                onCalibrateClick = {},
                onSaveSettings = {},
                onLookChange = { _, _ -> },
                onTouchSteerChange = {},
                radioStations = sampleStations,
                usePhoneStream = phoneStream,
                onToggleRadioMode = { phoneStream = it },
                onPlayStreamStation = { pickedStation = it },
                onUiCommand = { cmds.add(it) }
            )
        }

        composeRule.mainClock.advanceTimeBy(150L)
        // Top bar shows real station name instead of hardcoded CAB RADIO
        composeRule.onNodeWithText("TRUCKERSFM").assertIsDisplayed()
        // Auto gear displayed cleanly
        composeRule.onNodeWithText("D5").assertIsDisplayed()

        // Open Radio Browser, switch to PHONE STREAM, pick Simulator Radio, and close
        composeRule.onNodeWithContentDescription("Open radio browser").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("PHONE STREAM").performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithText("Simulator Radio").performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        assertEquals("Simulator Radio", pickedStation?.name)
        composeRule.onNodeWithText("\u2715 Close").performClick()
        composeRule.mainClock.advanceTimeBy(250L)

        // Open Trailer Delivery & UI Nav Pad, pick Option 1 and Confirm
        composeRule.onNodeWithContentDescription("dpad").performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("Where do you need it?").performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithText("\u2713  CONFIRM DELIVERY").performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        assertTrue(cmds.contains(UiNavCommand.DELIVERY_OPT_1))
        assertTrue(cmds.contains(UiNavCommand.DELIVERY_CONFIRM))
    }

    @Test
    fun editableQuickDockMorePinAndOrientationControlsWorkCleanly() {
        composeRule.mainClock.autoAdvance = false
        var activeSettings by mutableStateOf(ControllerSettings())

        composeRule.setContent {
            DesignCockpitScreen(
                isConnected = true,
                connectedIp = "127.0.0.1",
                packetsPerSec = 100,
                savedManualIp = "127.0.0.1",
                onSetManualIp = {},
                rawAngleProvider = { 0f },
                sensorModeLabel = "ACCEL",
                throttle = 0f,
                brake = 0f,
                clutch = 0f,
                buttonMask = 0L,
                settings = activeSettings,
                telemetry = TruckTelemetryV2(),
                onThrottleChange = {},
                onBrakeChange = {},
                onClutchChange = {},
                onButtonStateChange = { _, _ -> },
                onPulseBit = {},
                onCalibrateClick = {},
                onSaveSettings = { activeSettings = it },
                onLookChange = { _, _ -> },
                onTouchSteerChange = {}
            )
        }

        // Open Quick Dock -> enter Edit mode -> reorder slot 1 right -> swap slot 1 with Wipers -> Reset -> Done
        composeRule.onNodeWithContentDescription("Open quick dock").performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.onNodeWithContentDescription("Move slot 1 right").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        assertEquals(listOf(2, 1, 22, 58, 54, 55, 32, 59), activeSettings.quickDockBits)

        composeRule.onNodeWithText("#1").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("REPLACE SLOT #1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pick Wipers").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        assertEquals(12, activeSettings.quickDockBits[0])

        composeRule.onNodeWithText("Reset").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        assertEquals(DEFAULT_QUICK_DOCK_BITS, activeSettings.quickDockBits)

        composeRule.onNodeWithText("Done").performClick()
        composeRule.mainClock.advanceTimeBy(150L)
        composeRule.onNodeWithContentDescription("Dismiss quick dock").performTouchInput { click(Offset(20f, 20f)) }
        composeRule.mainClock.advanceTimeBy(300L)

        // Open More -> enter Pin to Dock mode -> pin Diff Lock into slot #3 -> Close
        composeRule.onNodeWithContentDescription("more").performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.onNodeWithText("Pin to Dock").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithContentDescription("Pin Diff lock").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithContentDescription("Quick dock slot 3").performClick()
        composeRule.mainClock.advanceTimeBy(150L)
        assertEquals(18, activeSettings.quickDockBits[2])
        composeRule.onNodeWithText("\u2715 Close").performClick()
        composeRule.mainClock.advanceTimeBy(300L)

        // Hold Tuning (gear) tile to open Tuning -> Cockpit -> select Lock R -> Save & apply
        composeRule.onNodeWithContentDescription("gear").performTouchInput {
            down(center)
            advanceEventTime(480L)
            up()
        }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.onNodeWithText("COCKPIT").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("SCREEN ORIENTATION").assertIsDisplayed()
        composeRule.onNodeWithText("Lock R").performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.onNodeWithText("Save & apply").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        assertEquals("REVERSE", activeSettings.orientationMode)
    }
}
