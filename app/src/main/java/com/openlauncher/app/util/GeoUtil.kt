package com.openlauncher.app.util

import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0 // Earth radius, meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Plain local-date key ("2026-08-16") — used to detect a day rollover
 * without pulling in a full date/time library for one comparison. */
fun currentDayKey(): String {
    val cal = Calendar.getInstance()
    return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
}

fun headingToCompassDirection(degrees: Float): String {
    val dirs = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    val index = (((degrees % 360) + 360) % 360 / 45.0).let { Math.round(it).toInt() % 8 }
    return dirs[index]
}
