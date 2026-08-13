package com.openlauncher.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {

    @Test
    fun `first fix is always accepted`() {
        assertTrue(isBetterLocation(1000L, 50f, "gps", null, null, null))
    }

    @Test
    fun `a much coarser Network fix does not override a fresh accurate GPS fix`() {
        // This is the exact bug reported live: GPS gives an accurate fix, then
        // Network fires moments later with a far coarser one and used to stomp
        // over it unconditionally.
        val accepted = isBetterLocation(
            newTime = 1_005_000L, newAccuracy = 800f, newProvider = "network",
            currentTime = 1_000_000L, currentAccuracy = 15f, currentProvider = "gps"
        )
        assertFalse(accepted)
    }

    @Test
    fun `a more accurate fix from either provider is accepted`() {
        val accepted = isBetterLocation(
            newTime = 1_005_000L, newAccuracy = 10f, newProvider = "network",
            currentTime = 1_000_000L, currentAccuracy = 15f, currentProvider = "gps"
        )
        assertTrue(accepted)
    }

    @Test
    fun `same-provider continuity accepts a newer, not-much-worse fix`() {
        val accepted = isBetterLocation(
            newTime = 1_005_000L, newAccuracy = 20f, newProvider = "gps",
            currentTime = 1_000_000L, currentAccuracy = 15f, currentProvider = "gps"
        )
        assertTrue(accepted)
    }

    @Test
    fun `a significantly newer fix is accepted even if coarser`() {
        val accepted = isBetterLocation(
            newTime = 1_000_000L + TWO_MINUTES_MS + 1L, newAccuracy = 900f, newProvider = "network",
            currentTime = 1_000_000L, currentAccuracy = 10f, currentProvider = "gps"
        )
        assertTrue(accepted)
    }

    @Test
    fun `a significantly older fix is rejected outright`() {
        val accepted = isBetterLocation(
            newTime = 1_000_000L - TWO_MINUTES_MS - 1L, newAccuracy = 5f, newProvider = "gps",
            currentTime = 1_000_000L, currentAccuracy = 900f, currentProvider = "network"
        )
        assertFalse(accepted)
    }
}
