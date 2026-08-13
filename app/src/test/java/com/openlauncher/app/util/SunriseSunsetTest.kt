package com.openlauncher.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

class SunriseSunsetTest {

    // Singapore is near-equatorial, so sunrise/sunset barely drift across the
    // year (observed on-device: 07:04 / 19:14) — a tolerant range is robust
    // regardless of what real-world date this test runs on, since localMinutes()
    // has no injectable date parameter (always Calendar.getInstance()).
    @Test
    fun `Singapore sunrise and sunset fall in the expected daily range`() {
        val (riseMin, setMin) = SunriseSunset.localMinutes(1.35, 103.8)

        val riseHour = riseMin / 60f
        val setHour = setMin / 60f

        assertTrue("sunrise $riseHour was outside 5:30-8:30", riseHour in 5.5f..8.5f)
        assertTrue("sunset $setHour was outside 17:30-20:30", setHour in 17.5f..20.5f)
        assertTrue("sunrise should be before sunset at the equator", riseMin < setMin)
    }

    @Test
    fun `isDay agrees with the computed sunrise-sunset window`() {
        val (riseMin, setMin) = SunriseSunset.localMinutes(1.35, 103.8)
        val nowMin = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val expectedIsDay = nowMin in riseMin..setMin
        assertTrue(SunriseSunset.isDay(1.35, 103.8) == expectedIsDay)
    }
}
