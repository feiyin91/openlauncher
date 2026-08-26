package com.openlauncher.app.model

/**
 * Raw reverse-geocoded address components for one cached spot, keyed by a
 * rounded "lat,lon" string (see LauncherViewModel.placeCacheKey) — not the
 * final resolved display string, so a cache hit still respects whatever
 * Location Detail Level is currently selected rather than baking in
 * whatever level was active the moment this entry was first fetched.
 */
data class PlaceNameCacheEntry(
    val broader: String? = null,
    val fine: String? = null,
    val state: String? = null,
    val county: String? = null,
    val displayNameFallback: String? = null
)
