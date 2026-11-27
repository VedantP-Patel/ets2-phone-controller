package com.ets2controller.config

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickDockSettingsTest {

    @Test
    fun normalizeQuickDockBitsPadsDeduplicatesAndBounds() {
        assertEquals(DEFAULT_QUICK_DOCK_BITS, normalizeQuickDockBits(emptyList()))
        assertEquals(
            listOf(12, 18, 22, 1, 2, 58, 54, 55),
            normalizeQuickDockBits(listOf(12, 12, 18, 999, -5, 22))
        )
        val trimmed = normalizeQuickDockBits(listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19))
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16, 17), trimmed)
    }

    @Test
    fun swapQuickDockSlotsExchangesValidNeighborsAndIgnoresInvalid() {
        val base = listOf(1, 2, 22, 58, 54, 55, 32, 59)
        assertEquals(listOf(2, 1, 22, 58, 54, 55, 32, 59), swapQuickDockSlots(base, 0, 1))
        assertEquals(base, swapQuickDockSlots(base, 0, -1))
        assertEquals(base, swapQuickDockSlots(base, 0, 8))
        assertEquals(base, swapQuickDockSlots(base, 3, 3))
    }

    @Test
    fun placeInQuickDockSlotPlacesNewActionAndSwapsDuplicatesWithoutDuplicates() {
        val base = listOf(1, 2, 22, 58, 54, 55, 32, 59)
        // Placing an unlisted bit replaces only the target slot
        assertEquals(
            listOf(12, 2, 22, 58, 54, 55, 32, 59),
            placeInQuickDockSlot(base, 0, 12)
        )
        // Placing an existing bit swaps it cleanly with the displaced bit so all 8 stay unique
        assertEquals(
            listOf(22, 2, 1, 58, 54, 55, 32, 59),
            placeInQuickDockSlot(base, 0, 22)
        )
    }
}
