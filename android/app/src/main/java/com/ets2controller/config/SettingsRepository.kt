package com.ets2controller.config

import android.content.Context
import android.content.SharedPreferences

data class ControllerSettings(
    val serverIp: String = "10.196.227.236",
    val serverPort: Int = 5005,
    val steeringDeadzoneDeg: Float = 0.5f,
    val steeringMaxAngleDeg: Float = 45.0f,
    val steeringCurvePower: Float = 1.0f,
    val filterMinCutoff: Float = 0.6f,
    val filterBeta: Float = 0.008f,
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
    val centerSnapAssistance: Boolean = true
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("ets2_controller_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): ControllerSettings {
        val savedIp = prefs.getString("server_ip", "") ?: ""
        val effectiveIp = if (savedIp.isNotEmpty()) savedIp else "10.196.227.236"

        val savedBeta = prefs.getFloat("beta", 0.008f)
        val effectiveBeta = if (savedBeta > 0.05f || savedBeta < 0.001f) 0.008f else savedBeta

        val savedMinCutoff = prefs.getFloat("min_cutoff", 0.6f)
        val effectiveMinCutoff = if (savedMinCutoff >= 0.95f) 0.6f else savedMinCutoff

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
            centerSnapAssistance = prefs.getBoolean("center_snap_assistance", true)
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
            .apply()
    }
}
