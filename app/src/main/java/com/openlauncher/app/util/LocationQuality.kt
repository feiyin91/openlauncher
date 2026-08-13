package com.openlauncher.app.util

const val TWO_MINUTES_MS = 2 * 60 * 1000L

/**
 * Standard Android "isBetterLocation" heuristic, extracted to operate on plain
 * primitives rather than android.location.Location — that keeps it a pure
 * function, unit-testable on the local JVM without Robolectric/instrumentation.
 *
 * Prefers a significantly newer fix outright; otherwise prefers a more
 * accurate one; a same-provider fix wins ties; a much-less-accurate fix from
 * a different provider is only accepted if the current fix isn't newer.
 */
fun isBetterLocation(
    newTime: Long,
    newAccuracy: Float,
    newProvider: String?,
    currentTime: Long?,
    currentAccuracy: Float?,
    currentProvider: String?
): Boolean {
    if (currentTime == null || currentAccuracy == null) return true

    val timeDeltaMs = newTime - currentTime
    val isSignificantlyNewer = timeDeltaMs > TWO_MINUTES_MS
    val isSignificantlyOlder = timeDeltaMs < -TWO_MINUTES_MS
    if (isSignificantlyNewer) return true
    if (isSignificantlyOlder) return false

    val isNewer = timeDeltaMs > 0
    val accuracyDelta = newAccuracy - currentAccuracy
    val isSignificantlyLessAccurate = accuracyDelta > 200f
    val isFromSameProvider = newProvider == currentProvider

    return when {
        accuracyDelta < 0 -> true // more accurate
        isNewer && accuracyDelta <= 0 -> true
        isNewer && !isSignificantlyLessAccurate && isFromSameProvider -> true
        else -> false
    }
}
