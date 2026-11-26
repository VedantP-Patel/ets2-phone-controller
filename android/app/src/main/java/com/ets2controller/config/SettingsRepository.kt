package com.ets2controller.config

import android.content.Context
import android.content.SharedPreferences

data class ControllerSettings(
    val serverIp: String = "",
    val serverPort: Int = 5005,
    val steeringDeadzoneDeg: Float = 0.5f,
    val steeringMaxAngleDeg: Float = 45.0f,
    val steeringCurvePower: Float = 1.0f,
    val filterMinCutoff: Float = 1.2f,
    val filterBeta: Float = 0.06f,
    val invertSteering: Boolean = false,
    val wheelMode1to1: Boolean = false,
    val wheelDegreesRange: Float = 900.0f,
    val casterSpringStrength: Float = 0.0f, // 0.0 to 1.0
    val clusterTheme: String = "SCANIA_AMBER", // SCANIA_AMBER, VOLVO_CYAN, MERCEDES_GREEN, DAF_WHITE
    val speedUnitMph: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val forceFeedbackEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true,
    val autoCancelBlinkers: Boolean = true,
    val steeringHapticTension: Boolean = true,
    val gravityAnchorEnabled: Boolean = true,
    val autoZeroRecenterSensitivity: Float = 1.0f,
    val centerSnapAssistance: Boolean = true,
    // Design v2 controls and safety
    val holdConfirmMs: Int = 400,
    val controlScale: Float = 1.0f,
    val leftHanded: Boolean = false,
    val hapticStrength: Float = 0.8f,
    val edgeGuardDp: Int = 14,
    val brakeWins: Boolean = true,
    val slideOffCancel: Boolean = true,
    val hazardNeedsHold: Boolean = true,
    val speedSensitivity: Float = 0.4f,
    val speedSensitiveDeadzone: Boolean = true,
    // Ergonomics & motion preferences
    val ergonomicLayout: Boolean = true,
    val relativePedalDrag: Boolean = false,
    val reduceMotion: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("ets2_controller_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): ControllerSettings {
        val effectiveIp = prefs.getString("server_ip", "") ?: ""

        val savedBeta = prefs.getFloat("beta", 0.06f)
        val effectiveBeta = savedBeta.coerceIn(0.02f, 0.25f)

        val savedMinCutoff = prefs.getFloat("min_cutoff", 1.2f)
        val effectiveMinCutoff = savedMinCutoff.coerceIn(0.4f, 2.5f)

        val savedDz = prefs.getFloat("deadzone", 0.5f)
        val effectiveDz = if (savedDz >= 2.9f && savedDz <= 3.1f) 0.5f else savedDz

        val savedCurve = prefs.getFloat("curve_power", 1.0f)
        val effectiveCurve = if (savedCurve >= 1.35f && savedCurve <= 1.45f) 1.0f else savedCurve

        return ControllerSettings(
            serverIp = effectiveIp,
            serverPort = prefs.getInt("server_port", 5005),
            steeringDeadzoneDeg = effectiveDz,
            steeringMaxAngleDeg = prefs.getFloat("max_angle", 45.0f),
            steeringCurvePower = effectiveCurve,
            filterMinCutoff = effectiveMinCutoff,
            filterBeta = effectiveBeta,
            invertSteering = prefs.getBoolean("invert_steering", false),
            wheelMode1to1 = prefs.getBoolean("wheel_mode_1to1", false),
            wheelDegreesRange = prefs.getFloat("wheel_degrees_range", 900.0f),
            casterSpringStrength = prefs.getFloat("caster_spring", 0.0f),
            clusterTheme = prefs.getString("cluster_theme", "SCANIA_AMBER") ?: "SCANIA_AMBER",
            speedUnitMph = prefs.getBoolean("speed_unit_mph", false),
            hapticFeedbackEnabled = prefs.getBoolean("haptics", true),
            forceFeedbackEnabled = prefs.getBoolean("ffb", true),
            soundEffectsEnabled = prefs.getBoolean("sound_effects", true),
            autoCancelBlinkers = prefs.getBoolean("auto_cancel_blinkers", true),
            steeringHapticTension = prefs.getBoolean("steering_haptic_tension", true),
            gravityAnchorEnabled = prefs.getBoolean("gravity_anchor_enabled", true),
            autoZeroRecenterSensitivity = prefs.getFloat("auto_zero_sensitivity", 1.0f),
            centerSnapAssistance = prefs.getBoolean("center_snap_assistance", true),
            holdConfirmMs = prefs.getInt("hold_confirm_ms", 400),
            controlScale = prefs.getFloat("control_scale", 1.0f),
            leftHanded = prefs.getBoolean("left_handed", false),
            hapticStrength = prefs.getFloat("haptic_strength", 0.8f),
            edgeGuardDp = prefs.getInt("edge_guard_dp", 14),
            brakeWins = prefs.getBoolean("brake_wins", true),
            slideOffCancel = prefs.getBoolean("slide_off_cancel", true),
            hazardNeedsHold = prefs.getBoolean("hazard_needs_hold", true),
            speedSensitivity = prefs.getFloat("speed_sensitivity", 0.4f),
            speedSensitiveDeadzone = prefs.getBoolean("speed_sensitive_deadzone", true),
            ergonomicLayout = prefs.getBoolean("ergonomic_layout", true),
            relativePedalDrag = prefs.getBoolean("relative_pedal_drag", false),
            reduceMotion = prefs.getBoolean("reduce_motion", false)
        )
    }

    fun saveSettings(settings: ControllerSettings) {
        prefs.edit()
            .putString("server_ip", settings.serverIp)
            .putInt("server_port", settings.serverPort)
            .putFloat("deadzone", settings.steeringDeadzoneDeg)
            .putFloat("max_angle", settings.steeringMaxAngleDeg)
            .putFloat("curve_power", settings.steeringCurvePower)
            .putFloat("min_cutoff", settings.filterMinCutoff)
            .putFloat("beta", settings.filterBeta)
            .putBoolean("invert_steering", settings.invertSteering)
            .putBoolean("wheel_mode_1to1", settings.wheelMode1to1)
            .putFloat("wheel_degrees_range", settings.wheelDegreesRange)
            .putFloat("caster_spring", settings.casterSpringStrength)
            .putString("cluster_theme", settings.clusterTheme)
            .putBoolean("speed_unit_mph", settings.speedUnitMph)
            .putBoolean("haptics", settings.hapticFeedbackEnabled)
            .putBoolean("ffb", settings.forceFeedbackEnabled)
            .putBoolean("sound_effects", settings.soundEffectsEnabled)
            .putBoolean("auto_cancel_blinkers", settings.autoCancelBlinkers)
            .putBoolean("steering_haptic_tension", settings.steeringHapticTension)
            .putBoolean("gravity_anchor_enabled", settings.gravityAnchorEnabled)
            .putFloat("auto_zero_sensitivity", settings.autoZeroRecenterSensitivity)
            .putBoolean("center_snap_assistance", settings.centerSnapAssistance)
            .putInt("hold_confirm_ms", settings.holdConfirmMs)
            .putFloat("control_scale", settings.controlScale)
            .putBoolean("left_handed", settings.leftHanded)
            .putFloat("haptic_strength", settings.hapticStrength)
            .putInt("edge_guard_dp", settings.edgeGuardDp)
            .putBoolean("brake_wins", settings.brakeWins)
            .putBoolean("slide_off_cancel", settings.slideOffCancel)
            .putBoolean("hazard_needs_hold", settings.hazardNeedsHold)
            .putFloat("speed_sensitivity", settings.speedSensitivity)
            .putBoolean("speed_sensitive_deadzone", settings.speedSensitiveDeadzone)
            .putBoolean("ergonomic_layout", settings.ergonomicLayout)
            .putBoolean("relative_pedal_drag", settings.relativePedalDrag)
            .putBoolean("reduce_motion", settings.reduceMotion)
            .apply()
    }
}
