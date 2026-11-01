package com.ets2controller.network

data class TruckTelemetry(
    val speedKmh: Float = 0.0f,
    val engineRpm: Float = 0.0f,
    val maxRpm: Float = 2500.0f,
    val gear: Int = 0,
    val fuelPercent: Int = 85,
    val airPressureBar: Float = 8.5f,
    val isLeftBlinker: Boolean = false,
    val isRightBlinker: Boolean = false,
    val isHazard: Boolean = false,
    val isParkBrake: Boolean = false,
    val isEngineOn: Boolean = false,
    val isLowAir: Boolean = false,
    val isCruiseControl: Boolean = false,
    val roundTripPingMs: Int = 0,
    // Supercharge v2.0 Extended Telemetry
    val speedLimitKmh: Int = 0,
    val routeDistanceKm: Int = 0,
    val routeTimeMin: Int = 0,
    val truckDamagePct: Int = 0,
    val cruiseSpeedKmh: Int = 0,
    val forwardGears: Int = 14,
    val isAutomatic: Boolean = true,
    val auxStateFlags: Int = 0
)
