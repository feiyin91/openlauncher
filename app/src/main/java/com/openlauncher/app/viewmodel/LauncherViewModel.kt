package com.openlauncher.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DASHBOARD_THEMES
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.GRID_COLS
import com.openlauncher.app.data.GRID_ROWS
import com.openlauncher.app.data.GeminiApi
import com.openlauncher.app.data.GeminiContent
import com.openlauncher.app.data.GeminiPart
import com.openlauncher.app.data.GeminiRequest
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.SoundPadConfig
import com.openlauncher.app.data.WeatherApi
import com.openlauncher.app.data.activeWidgetIds
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.defaultShortcuts
import com.openlauncher.app.util.SunriseSunset
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.VoiceActionResult
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.GeminiLiveTranscriber
import com.openlauncher.app.util.LocationCompassManager
import com.openlauncher.app.util.LocationData
import com.openlauncher.app.util.VoiceContext
import com.openlauncher.app.util.buildVoiceSystemPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val locationMgr  = LocationCompassManager(application)

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .onEach { _settingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateSettings(block: AppSettings.() -> AppSettings) {
        viewModelScope.launch { settingsRepo.updateSettings { it.block() } }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepo.resetToDefaults() }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private val _nav = MutableStateFlow(NavDestination.HOME)
    val nav: StateFlow<NavDestination> = _nav

    fun navigate(dest: NavDestination) { _nav.value = dest }

    // ── Shortcut picker ───────────────────────────────────────────────────────
    private val _shortcutPickerSlot = MutableStateFlow<Int?>(null)
    val shortcutPickerSlot: StateFlow<Int?> = _shortcutPickerSlot

    fun startShortcutPicker(slot: Int) {
        _shortcutPickerSlot.value = slot
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignShortcut(slot: Int, app: AppInfo) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                while (list.size <= slot) list.add(ShortcutConfig())
                list[slot] = ShortcutConfig(packageName = app.packageName, label = app.appName)
            })
        }
        _shortcutPickerSlot.value = null
        _nav.value = NavDestination.HOME
    }

    fun removeShortcut(slot: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) {
                    list[slot] = defaultShortcuts().getOrNull(slot) ?: ShortcutConfig()
                }
            })
        }
    }

    fun reorderShortcut(from: Int, to: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                val item = list.removeAt(from)
                list.add(to.coerceIn(0, list.size), item)
            })
        }
    }

    fun setShortcutIcon(slot: Int, icon: DefaultShortcutIcon?) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) list[slot] = list[slot].copy(customIconOverride = icon)
            })
        }
    }

    fun cancelShortcutPicker() {
        _shortcutPickerSlot.value = null
    }

    // ── CarPlay / Android Auto picker ─────────────────────────────────────────
    enum class AppPickerTarget { CARPLAY, ANDROID_AUTO, PIP, RADIO }

    private val _appPickerTarget = MutableStateFlow<AppPickerTarget?>(null)
    val carPlayPickerActive: StateFlow<Boolean> = MutableStateFlow(false) // kept for compat
    val appPickerTarget: StateFlow<AppPickerTarget?> = _appPickerTarget

    fun startCarPlayPicker() {
        _appPickerTarget.value = AppPickerTarget.CARPLAY
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startAndroidAutoPicker() {
        _appPickerTarget.value = AppPickerTarget.ANDROID_AUTO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startPipPicker() {
        _appPickerTarget.value = AppPickerTarget.PIP
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startRadioPicker() {
        _appPickerTarget.value = AppPickerTarget.RADIO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignPickerApp(app: AppInfo) {
        when (_appPickerTarget.value) {
            AppPickerTarget.CARPLAY      -> updateSettings { copy(carPlayPackage = app.packageName) }
            AppPickerTarget.ANDROID_AUTO -> updateSettings { copy(androidAutoPackage = app.packageName) }
            AppPickerTarget.PIP          -> updateSettings { copy(pipAppPackage = app.packageName) }
            AppPickerTarget.RADIO        -> updateSettings { copy(radioPackage = app.packageName) }
            null -> {}
        }
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun clearCarPlayApp()      { updateSettings { copy(carPlayPackage = "") } }
    fun clearAndroidAutoApp()  { updateSettings { copy(androidAutoPackage = "") } }
    fun clearPipApp()          { updateSettings { copy(pipAppPackage = "") } }
    fun clearRadioApp()        { updateSettings { copy(radioPackage = "") } }

    fun updateWidgetConfig(id: String, spanX: Int, spanY: Int) {
        updateSettings {
            val resized = widgetLayout.map { w ->
                if (w.id == id) w.copy(
                    spanX = spanX.coerceIn(1, GRID_COLS - w.gridX),
                    spanY = spanY.coerceIn(1, GRID_ROWS - w.gridY)
                ) else w
            }
            // Re-run collision resolution so enlarging a widget pushes neighbors
            // aside instead of stacking on top of them
            val activeIds = activeWidgetIds()
            val active    = resized.filter { it.enabled && it.id in activeIds }
            val inactive  = resized.filter { !it.enabled || it.id !in activeIds }
            val target    = active.find { it.id == id }
            copy(widgetLayout = if (target != null)
                computeWidgetMove(active, id, target.gridX, target.gridY) + inactive
            else resized)
        }
    }

    fun moveWidgetConfig(id: String, gridX: Int, gridY: Int) {
        updateSettings {
            val activeIds = activeWidgetIds()
            val active   = widgetLayout.filter { it.enabled && it.id in activeIds }
            val inactive = widgetLayout.filter { !it.enabled || it.id !in activeIds }
            copy(widgetLayout = computeWidgetMove(active, id, gridX, gridY) + inactive)
        }
    }

    fun addWidget(id: String) {
        updateSettings {
            val activeIds = activeWidgetIds()
            var layout    = widgetLayout
            var cell      = freeCellIn(layout, activeIds)

            // If grid is full, shrink the largest multi-cell widget by one span to make room
            if (cell == null) {
                val candidate = layout
                    .filter { it.enabled && it.id in activeIds && it.spanX * it.spanY > 1 }
                    .maxByOrNull { it.spanX * it.spanY }
                if (candidate != null) {
                    layout = layout.map { w ->
                        if (w.id == candidate.id)
                            if (w.spanY > 1) w.copy(spanY = w.spanY - 1) else w.copy(spanX = w.spanX - 1)
                        else w
                    }
                    cell = freeCellIn(layout, activeIds)
                }
            }

            val cell_ = cell ?: return@updateSettings this

            val withShow = when (id) {
                "CLOCK"       -> copy(showClock = true)
                "WEATHER"     -> copy(showWeather = true)
                "NOW_PLAYING" -> copy(showNowPlaying = true)
                "TELEMETRY"   -> copy(showTelemetry = true)
                "ALTIMETER"   -> copy(showAltimeter = true)
                "SPEEDOMETER" -> copy(showSpeedometer = true)
                "VITALS"      -> copy(showVitals = true)
                "TRIP_TRACKER" -> copy(showTripTracker = true)
                "SOUNDBOARD"  -> copy(showSoundboard = true)
                "FUEL_LOG"    -> copy(showFuelLog = true)
                "QUICK_TOGGLES" -> copy(showQuickToggles = true)
                "LOCATION"    -> copy(showLocation = true)
                else          -> this
            }
            val idx       = layout.indexOfFirst { it.id == id }
            val newLayout = if (idx >= 0) layout.toMutableList().also { list ->
                val w = list[idx]
                // Try to keep the widget's previous span; if no free area fits it,
                // fall back to 1×1 at the free cell so it can't overlap neighbors
                val area = if (w.spanX > 1 || w.spanY > 1) freeAreaIn(layout, activeIds, w.spanX, w.spanY) else null
                list[idx] = if (area != null)
                    w.copy(enabled = true, gridX = area.first, gridY = area.second)
                else
                    w.copy(enabled = true, gridX = cell_.first, gridY = cell_.second, spanX = 1, spanY = 1)
            } else {
                layout + com.openlauncher.app.data.WidgetConfig(id, cell_.first, cell_.second)
            }
            withShow.copy(widgetLayout = newLayout)
        }
    }

    fun removeWidget(id: String) {
        updateSettings {
            when (id) {
                "CLOCK"       -> copy(showClock = false)
                "WEATHER"     -> copy(showWeather = false)
                "NOW_PLAYING" -> copy(showNowPlaying = false)
                "TELEMETRY"   -> copy(showTelemetry = false)
                "ALTIMETER"   -> copy(showAltimeter = false)
                "SPEEDOMETER" -> copy(showSpeedometer = false)
                "VITALS"      -> copy(showVitals = false)
                "TRIP_TRACKER" -> copy(showTripTracker = false)
                "SOUNDBOARD"  -> copy(showSoundboard = false)
                "FUEL_LOG"    -> copy(showFuelLog = false)
                "QUICK_TOGGLES" -> copy(showQuickToggles = false)
                "LOCATION"    -> copy(showLocation = false)
                else          -> this
            }
        }
    }

    fun addFuelEntry(odometerKm: Double, volume: Double, cost: Double) {
        updateSettings {
            val entry = com.openlauncher.app.data.FuelEntry(
                timestampMs = System.currentTimeMillis(),
                odometerKm  = odometerKm,
                volume      = volume,
                cost        = cost,
                isMetric    = unitSystem == com.openlauncher.app.data.UnitSystem.METRIC
            )
            copy(fuelLog = (fuelLog + entry).sortedBy { it.timestampMs })
        }
    }

    fun deleteFuelEntry(timestampMs: Long) {
        updateSettings { copy(fuelLog = fuelLog.filterNot { it.timestampMs == timestampMs }) }
    }

    /**
     * Replaces the widget grid wholesale with a named preset (e.g. [splitPanelWidgetLayout])
     * and syncs each toggleable widget's show-flag to whether it's present in the preset,
     * so the grid renders exactly the preset with no stray widgets left enabled from before.
     */
    fun applyLayoutPreset(preset: List<com.openlauncher.app.data.WidgetConfig>) {
        updateSettings {
            val presentIds = preset.map { it.id }.toSet()
            val withShow = copy(
                showClock        = "CLOCK" in presentIds,
                showWeather      = "WEATHER" in presentIds,
                showNowPlaying   = "NOW_PLAYING" in presentIds,
                showTelemetry    = "TELEMETRY" in presentIds,
                showAltimeter    = "ALTIMETER" in presentIds,
                showSpeedometer  = "SPEEDOMETER" in presentIds,
                showVitals       = "VITALS" in presentIds,
                showTripTracker  = "TRIP_TRACKER" in presentIds,
                showSoundboard   = "SOUNDBOARD" in presentIds
            )
            // Keep any widget configs not part of this preset around (disabled) so their
            // spans/positions aren't lost if the user switches presets again later.
            val untouched = widgetLayout.filter { it.id !in presentIds }
            withShow.copy(widgetLayout = preset + untouched)
        }
    }

    fun updateSoundboardPad(index: Int, pad: SoundPadConfig) {
        updateSettings {
            // Persisted lists from older versions may be shorter than the 6 pads
            // the widget displays — pad before assigning to avoid IndexOutOfBounds
            val padded = soundboardPads.toMutableList()
            while (padded.size <= index) padded.add(SoundPadConfig("+", synthType = ""))
            padded[index] = pad
            copy(soundboardPads = padded)
        }
    }

    private fun freeCellIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>
    ): Pair<Int, Int>? = freeAreaIn(layout, activeIds, 1, 1)

    private fun freeAreaIn(
        layout: List<com.openlauncher.app.data.WidgetConfig>,
        activeIds: Set<String>,
        spanX: Int,
        spanY: Int
    ): Pair<Int, Int>? {
        val occupied = buildSet<Pair<Int, Int>> {
            layout.filter { it.enabled && it.id in activeIds }.forEach { w ->
                for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
            }
        }
        for (row in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
            if (col + spanX > GRID_COLS || row + spanY > GRID_ROWS) continue
            if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
                return col to row
        }
        return null
    }

    fun cancelCarPlayPicker() {
        _appPickerTarget.value = null
    }

    // ── Rearrange mode ────────────────────────────────────────────────────────
    private val _rearrangeMode = MutableStateFlow(false)
    val rearrangeMode: StateFlow<Boolean> = _rearrangeMode

    fun toggleRearrangeMode() { _rearrangeMode.value = !_rearrangeMode.value }
    fun exitRearrangeMode()   { _rearrangeMode.value = false }

    // ── Installed apps ────────────────────────────────────────────────────────
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager

            // Use getInstalledApplications — same source Android Settings uses,
            // catches apps with no launcher/ACTION_MAIN activity (e.g. CarPlay companions)
            _apps.value = pm.getInstalledApplications(0)
                .mapNotNull { appInfo ->
                    try {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        if (label.isBlank()) return@mapNotNull null
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName     = label,
                            icon        = pm.getApplicationIcon(appInfo),
                            isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (_: Exception) { null }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appName }
            _appsLoading.value = false
        }
    }

    fun launchApp(packageName: String) {
        val app = getApplication<Application>()
        val pm  = app.packageManager
        // Try standard launch intent first; fall back to first ACTION_MAIN activity in package
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).setPackage(packageName), 0
            ).firstOrNull()?.activityInfo?.let { ai ->
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(ai.packageName, ai.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        intent?.let { app.startActivity(it) }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    val nowPlaying: StateFlow<NowPlayingState?> = MediaListenerService.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playPause() { nowPlaying.value?.controller?.also { ctrl ->
        val state = ctrl.playbackState?.state
        if (state == android.media.session.PlaybackState.STATE_PLAYING)
            ctrl.transportControls?.pause()
        else
            ctrl.transportControls?.play()
    }}

    fun skipNext() { nowPlaying.value?.controller?.transportControls?.skipToNext() }
    fun skipPrev() { nowPlaying.value?.controller?.transportControls?.skipToPrevious() }

    // ── Weather ───────────────────────────────────────────────────────────────
    private val _weather = MutableStateFlow<WeatherState?>(null)
    val weather: StateFlow<WeatherState?> = _weather

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError

    private var weatherJob: Job? = null
    // Stamped on successful fetch only (see fetchWeather/fetchPlaceName) — if these
    // were stamped on *attempt*, a single failure right at boot (network not up yet)
    // would lock the panel empty for a full throttle window instead of retrying.
    private var lastWeatherFetchMs = 0L
    private var lastPlaceFetchMs = 0L

    fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            try {
                // Always request celsius — the state stores celsius and the widget
                // converts for display, so requesting fahrenheit just round-tripped
                // the value through two lossy conversions
                val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                resp.currentWeather?.let { cw ->
                    // Open-Meteo's hourly block is parallel arrays keyed by ISO timestamp
                    // ("2026-08-12T23:00") — keep only points from the current hour onward
                    // so the strip reads as "coming up," not partly-past.
                    val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:00", java.util.Locale.US)
                        .format(java.util.Date())
                    val hourly = resp.hourly
                    val currentHourIndices = hourly?.time?.indices
                        ?.filter { hourly.time[it] >= nowIso }
                        ?.take(6) ?: emptyList()
                    val forecast = if (hourly != null) {
                        currentHourIndices.mapNotNull { i ->
                                val hourStr = hourly.time.getOrNull(i)?.takeLast(5)?.take(2) ?: return@mapNotNull null
                                val hour = hourStr.toIntOrNull() ?: return@mapNotNull null
                                com.openlauncher.app.model.HourlyPoint(
                                    hour               = hour,
                                    temperatureCelsius = hourly.temperature2m.getOrNull(i) ?: return@mapNotNull null,
                                    weatherCode        = hourly.weathercode.getOrNull(i) ?: 0,
                                    isDay              = hour in 6..17,
                                    precipitationChance = hourly.precipitationProbability.getOrNull(i) ?: 0
                                )
                            }
                    } else emptyList()
                    // First index in the "current hour onward" filter above is the
                    // present hour itself — reuse it rather than a second lookup.
                    val feelsLike = currentHourIndices.firstOrNull()
                        ?.let { hourly?.apparentTemperature?.getOrNull(it) } ?: cw.temperature

                    _weather.value = WeatherState(
                        temperatureCelsius = cw.temperature,
                        weatherCode       = cw.weathercode,
                        windspeedKmh      = cw.windspeed,
                        isDay             = cw.isDay == 1,
                        hourlyForecast    = forecast,
                        feelsLikeCelsius  = feelsLike
                    )
                }
                _weatherError.value = null
                lastWeatherFetchMs = System.currentTimeMillis()
            } catch (e: Exception) {
                _weatherError.value = e.message
                // Deliberately not stamping lastWeatherFetchMs — leaving it at its
                // last successful value means the next location/minute tick retries
                // immediately instead of waiting out the full throttle window.
            }
        }
    }

    // ── Reverse geocoding (coordinates → place name) ───────────────────────────
    private val _placeName = MutableStateFlow<String?>(null)
    val placeName: StateFlow<String?> = _placeName

    fun fetchPlaceName(lat: Double, lon: Double) {
        viewModelScope.launch {
            try {
                val resp = com.openlauncher.app.data.NominatimApi.service.reverseGeocode(lat, lon)
                val addr = resp.address
                // Always request finest detail from the API; which fields we actually
                // show is a client-side choice per locationDetailLevel, so switching
                // the setting doesn't need a fresh network call.
                //
                // "Broader" is the wrapper locality — city, or the nearest thing to
                // it (falls back to state/county for places with no city field, e.g.
                // some rural areas). "Fine" is the specific neighbourhood/quarter.
                // Paired as "Broader, Fine" — e.g. "Singapore, Boon Keng" or
                // "Johor Bahru, Megah Ria" — not paired with state, which is often
                // too coarse to add anything (and Singapore has no state at all).
                val broader = addr?.city ?: addr?.town ?: addr?.village ?: addr?.county ?: addr?.state
                val fine = addr?.neighbourhood ?: addr?.quarter ?: addr?.suburb ?: addr?.cityDistrict
                val resolved = when (settings.value.locationDetailLevel) {
                    com.openlauncher.app.data.LocationDetailLevel.NEIGHBORHOOD -> when {
                        fine != null && broader != null && fine != broader -> "$broader, $fine"
                        fine != null -> fine
                        else -> broader
                    }
                    com.openlauncher.app.data.LocationDetailLevel.CITY -> broader
                    com.openlauncher.app.data.LocationDetailLevel.REGION -> addr?.state ?: addr?.county ?: broader
                } ?: resp.displayName?.split(",")?.map { it.trim() }?.take(2)?.joinToString(", ")
                if (resolved != null) {
                    _placeName.value = resolved
                    lastPlaceFetchMs = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // transient network hiccup — leave the last known place name showing,
                // and leave lastPlaceFetchMs unstamped so the next tick retries soon
            }
        }
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // Re-evaluated every minute: a parked car produces no location updates
    // (minDistance filters), so AUTO mode must also flip on time alone.
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000L) } }

    val isDayMode: StateFlow<Boolean> = combine(settings, locationMgr.location, minuteTicker) { s, loc, _ ->
        when (s.dayNightMode) {
            DayNightMode.DARK   -> false
            DayNightMode.LIGHT  -> true
            DayNightMode.AUTO   -> if (loc != null) SunriseSunset.isDay(loc.latitude, loc.longitude) else false
            DayNightMode.SYSTEM -> false // placeholder — overridden in MainActivity via isSystemInDarkTheme()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun startLocationUpdates() = locationMgr.start()
    fun stopLocationUpdates()  = locationMgr.stop()

    // ── Connectivity ──────────────────────────────────────────────────────────
    private val _isWifi = MutableStateFlow(false)
    private val _isData = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi
    val isData: StateFlow<Boolean> = _isData

    fun refreshMedia() {
        MediaListenerService.requestRefresh()
    }

    // ── Radio ─────────────────────────────────────────────────────────────────
    // Android has no public FM tuner API (the Broadcast Radio HAL is @SystemApi),
    // so the radio widget mirrors the unit's real tuner through two backends:
    //   1. szchoiceway MCU units — Settings.Global JSON + canbus broadcasts.
    //      Full control: seek, band switching, direct frequency tuning.
    //   2. Any other unit — the vendor radio app's MediaSession. Frequency and
    //      station are parsed from session metadata; seek maps to skip next/prev
    //      (how steering-wheel keys drive these apps). No direct tuning.
    data class HardwareRadioState(
        val band: String,
        val freq: String,
        val stationName: String? = null,
        // Direct tuning + FM1/FM2/FM3/AM switching — vendor MCU backend only
        val canTune: Boolean = false
    ) {
        val display get() = "$band  $freq"
        val isAm    get() = band.equals("AM", ignoreCase = true)
    }

    private val _mcuRadio = MutableStateFlow<HardwareRadioState?>(null)

    private fun packageInstalled(pkg: String) = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    private val hasSzchoicewayMcu: Boolean by lazy {
        packageInstalled("com.szchoiceway.radio") ||
        packageInstalled("com.szchoiceway.eventcenter") ||
        runCatching {
            AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) != null
        }.getOrDefault(false)
    }

    private fun parseRadioJson(): HardwareRadioState? {
        return try {
            val json = AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) ?: return null
            val title = org.json.JSONObject(json).optString("mediaTitle", "") // e.g. "FM1 90.10"
            if (title.isEmpty()) return null
            val parts = title.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            HardwareRadioState(band = parts[0], freq = parts[1], canTune = true)
        } catch (e: Exception) {
            android.util.Log.e("RadioMcu", "parseRadioJson error", e)
            null
        }
    }

    private var radioObserver: ContentObserver? = null

    private fun startHardwareRadioObserver() {
        if (radioObserver != null) return
        _mcuRadio.value = parseRadioJson()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _mcuRadio.value = parseRadioJson()
            }
        }
        getApplication<Application>().contentResolver.registerContentObserver(
            AndroidSettings.Global.getUriFor("SYS_MEDIA_INFO_JSON"),
            false, observer
        )
        radioObserver = observer
    }

    // ── MediaSession radio backend (universal fallback) ───────────────────────

    private fun looksLikeRadioPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
    }

    private fun isRadioSessionPackage(pkg: String): Boolean {
        val assigned = settings.value.radioPackage
        return if (assigned.isNotEmpty()) pkg == assigned else looksLikeRadioPackage(pkg)
    }

    // Matches "FM1 90.10", "99.9 MHz", "FM 99.9 WXYZ", "1040 kHz", "99,9" …
    private val sessionFreqPattern =
        Regex("""(?:\b(FM\s?\d?|AM)\b)?\s*(\d{2,4}(?:[.,]\d{1,2})?)\s*(MHz|kHz)?""", RegexOption.IGNORE_CASE)

    private fun parseSessionRadio(np: NowPlayingState): HardwareRadioState? {
        val pkg = np.controller?.packageName ?: return null
        if (!isRadioSessionPackage(pkg)) return null

        val text = listOf(np.title, np.artist)
            .filter { it.isNotBlank() && it != "Unknown" }
            .joinToString("  ")
        val match   = sessionFreqPattern.find(text)
        val rawFreq = match?.groupValues?.get(2)?.replace(',', '.')?.takeIf { it.isNotEmpty() }
        val freqVal = rawFreq?.toFloatOrNull()
        val explicitBand = match?.groupValues?.get(1)?.replace(" ", "")?.uppercase().orEmpty()
        val band = when {
            explicitBand.isNotEmpty()                 -> explicitBand
            freqVal != null && freqVal in 520f..1710f -> "AM"
            else                                      -> "FM"
        }
        // Whatever isn't the frequency is the station / RDS text
        val station = (if (match != null) text.replace(match.value, "") else text)
            .trim(' ', '-', '|', '/', '•')
            .takeIf { it.isNotBlank() }
        return HardwareRadioState(band = band, freq = rawFreq ?: "—", stationName = station, canTune = false)
    }

    // MCU backend wins when present; otherwise mirror the radio app's session
    val hardwareRadio: StateFlow<HardwareRadioState?> =
        combine(_mcuRadio, nowPlaying, settings) { mcu, np, _ ->
            mcu ?: np?.let { parseSessionRadio(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun radioSessionController() = nowPlaying.value?.controller?.takeIf { c ->
        c.packageName?.let { isRadioSessionPackage(it) } == true
    }

    // Send a radio keyCode to the MCU via the EventService broadcast channel.
    // Permission com.szchoiceway.permission.broadcast (prot=normal) is required and declared.
    // keyCodes: 15=seek_up, 14=seek_down, 30=fm_cycle, 31=am
    private fun sendMcuByteArray(bytes: ByteArray) {
        runCatching {
            val intent = Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").apply {
                putExtra("EventUtils.MCU_CMD_DATA", bytes)
            }
            getApplication<Application>().sendBroadcast(intent)
        }
    }

    private fun sendMcuBytes(vararg bytes: Byte) = sendMcuByteArray(bytes)

    // Tune to a specific frequency.
    // Canbus frame: 0x5a 0xa5 0x0d [0x91 bandByte bank(2) zeros... freqAscii] checksum
    // Checksum = low byte of sum of all preceding bytes.
    // Wrapped with 0x0d 0x08 sub-command prefix for MCU_CMD_DATA.
    fun radioTune(band: String, freqMhz: Float) {
        if (!hasSzchoicewayMcu) return // direct tuning is MCU-only
        val bandByte  = when (band) { "FM3" -> 0x03.toByte(); "AM" -> 0x04.toByte(); else -> 0x01.toByte() }
        val bankStr   = if (band == "FM2") "02" else "01"
        val freqStr   = if (band == "AM") String.format(java.util.Locale.US, "%.0f", freqMhz) else String.format(java.util.Locale.US, "%.1f", freqMhz)
        val padding   = 9 - freqStr.length
        val header    = byteArrayOf(0x5a.toByte(), 0xa5.toByte(), 0x0d.toByte())
        val data      = byteArrayOf(0x91.toByte(), bandByte,
                            bankStr[0].code.toByte(), bankStr[1].code.toByte()) +
                        ByteArray(padding) +
                        freqStr.map { it.code.toByte() }.toByteArray()
        val frame     = header + data
        val checksum  = (frame.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        val finalPayload = byteArrayOf(0x0d.toByte(), 0x08.toByte()) + frame + byteArrayOf(checksum)
        sendMcuByteArray(finalPayload)
    }

    private val mcuActive get() = _mcuRadio.value != null

    // Seek routes to the MCU when that backend is live, otherwise to the radio
    // app's MediaSession — skip next/prev is how these apps expose seek.
    fun radioSeekUp()   { if (mcuActive) sendMcuBytes(0x02, 0x0f) else radioSessionController()?.transportControls?.skipToNext() }
    fun radioSeekDown() { if (mcuActive) sendMcuBytes(0x02, 0x0e) else radioSessionController()?.transportControls?.skipToPrevious() }
    // Band switching and direct tuning only exist on the MCU backend
    fun radioCycleFm()  { if (mcuActive) sendMcuBytes(0x02, 0x1e) }
    fun radioSwitchAm() { if (mcuActive) sendMcuBytes(0x02, 0x1f) }
    fun radioStart()    { sendMcuBytes(0x01, 0x01) }
    fun radioStop()     { sendMcuBytes(0x01, 0x63) }

    fun stopHardwareRadioApp() {
        if (mcuActive) radioStop()
        else radioSessionController()?.transportControls?.pause()
    }

    fun launchHardwareRadioApp() {
        if (hasSzchoicewayMcu) radioStart()
        val pkg = settings.value.radioPackage.ifEmpty {
            when {
                hasSzchoicewayMcu -> "com.szchoiceway.radio"
                else              -> radioSessionController()?.packageName ?: ""
            }
        }
        if (pkg.isNotEmpty()) {
            runCatching {
                val intent = getApplication<Application>().packageManager
                    .getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).apply {
                        `package` = pkg
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            }
        }
        // Resume playback if the session is just paused
        radioSessionController()?.transportControls?.play()
    }

    fun refreshConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _isWifi.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            _isData.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            // activeNetwork requires API 23 — legacy path for Android 5.x head units
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            val connected = info?.isConnected == true
            @Suppress("DEPRECATION")
            val type = info?.type
            _isWifi.value = connected && type == ConnectivityManager.TYPE_WIFI
            _isData.value = connected && type == ConnectivityManager.TYPE_MOBILE
        }
    }

    // ── Voltage ───────────────────────────────────────────────────────────────
    // Most aftermarket head units have no real internal battery — the vendor ROM
    // wires the car's 12V input straight into Android's standard battery-voltage
    // reporting (the same field a phone would use for its own cell), so this reads
    // through the public BatteryManager API rather than anything unit-specific.
    private val _voltage = MutableStateFlow<Float?>(null)
    val voltage: StateFlow<Float?> = _voltage

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            // A real car electrical system is never anywhere near 0V while the unit
            // is on — this unit's standard-Android battery field turned out to be a
            // meaningless placeholder, not the real CAN-bus-fed voltage sensor its
            // own status bar reads from. Better to show nothing than a false 0.0V.
            if (millivolts >= 5000) _voltage.value = millivolts / 1000f
        }
    }

    private fun startVoltageObserver() {
        runCatching {
            getApplication<Application>().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationMgr.stop()
        radioObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        radioObserver = null
        runCatching { getApplication<Application>().unregisterReceiver(batteryReceiver) }
        speechRecognizer?.destroy()
        tts?.shutdown()
    }

    // ── Voice Assistant (Gemini) ─────────────────────────────────────────────
    // Personal feature, not part of the upstream PR — push-to-talk only (no
    // wake-word): mic button -> on-device speech-to-text -> Gemini for intent
    // parsing -> dispatch to a real action -> spoken (TTS) confirmation/answer.
    enum class VoiceAssistantState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private val _voiceState = MutableStateFlow(VoiceAssistantState.IDLE)
    val voiceState: StateFlow<VoiceAssistantState> = _voiceState

    private val _voiceTranscript = MutableStateFlow<String?>(null)
    val voiceTranscript: StateFlow<String?> = _voiceTranscript

    private val _voiceReply = MutableStateFlow<String?>(null)
    val voiceReply: StateFlow<String?> = _voiceReply

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(getApplication<Application>()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
    }

    private fun speak(text: String) {
        ensureTts()
        _voiceReply.value = text
        _voiceState.value = VoiceAssistantState.SPEAKING
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
        }
        // Whether or not TTS is ready, don't leave the UI stuck showing
        // "speaking" forever — settle back to idle after a beat.
        viewModelScope.launch {
            delay(400L + text.length * 60L)
            if (_voiceState.value == VoiceAssistantState.SPEAKING) _voiceState.value = VoiceAssistantState.IDLE
        }
    }

    private var liveTranscriber: GeminiLiveTranscriber? = null

    // Gemini Live is tried first — meaningfully better at road/shop/place
    // names than Android's on-device recognizer (the same reason Harvard
    // Studio's lesson-note transcription moved off browser ASR for musical
    // terms). Falls back to on-device SpeechRecognizer on any failure
    // (offline, connect timeout, mic unavailable) so a voice command still
    // works with no signal, just with lower proper-noun accuracy.
    fun startVoiceCommand() {
        ensureTts()
        _voiceTranscript.value = null
        _voiceReply.value = null
        _voiceState.value = VoiceAssistantState.LISTENING

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            startVoiceCommandFallback()
            return
        }

        liveTranscriber = GeminiLiveTranscriber(BuildConfig.GEMINI_API_KEY, viewModelScope).also {
            it.start(
                onTranscriptUpdate = { partial -> _voiceTranscript.value = partial },
                onFinal = { text ->
                    _voiceTranscript.value = text
                    processVoiceCommand(text)
                },
                onError = { startVoiceCommandFallback() }
            )
        }
    }

    /** Stops an in-progress Live listen (e.g. driver taps the mic again to finish speaking). */
    fun stopVoiceCommand() {
        liveTranscriber?.stop()
        speechRecognizer?.stopListening() // no-op if the fallback path wasn't the one running
    }

    private fun startVoiceCommandFallback() {
        val app = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _voiceState.value = VoiceAssistantState.ERROR
            speak("Speech recognition isn't available on this device.")
            return
        }
        _voiceState.value = VoiceAssistantState.LISTENING

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(app).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    _voiceState.value = VoiceAssistantState.ERROR
                    speak("Didn't catch that.")
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) {
                        _voiceState.value = VoiceAssistantState.ERROR
                        speak("Didn't catch that.")
                    } else {
                        _voiceTranscript.value = text
                        processVoiceCommand(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    private fun buildVoiceContext(): VoiceContext {
        val w = weather.value
        val loc = location.value
        val np = nowPlaying.value
        val fuel = settings.value.fuelLog.takeLast(3)
        return VoiceContext(
            currentThemeId  = settings.value.themeId,
            dayNightMode    = settings.value.dayNightMode.name,
            use24HourFormat = settings.value.use24HourFormat,
            weatherSummary  = w?.let {
                "${it.temperatureDisplay(settings.value.unitSystem.name == "METRIC")}, feels ${it.feelsLikeDisplay(settings.value.unitSystem.name == "METRIC")}, ${it.conditionLabel.lowercase()}, wind ${it.windspeedDisplay(settings.value.unitSystem.name == "METRIC")}"
            },
            placeName       = placeName.value,
            sunriseLocal    = loc?.let { val (r, _) = SunriseSunset.localMinutes(it.latitude, it.longitude); "%02d:%02d".format(r / 60, r % 60) },
            sunsetLocal     = loc?.let { val (_, s) = SunriseSunset.localMinutes(it.latitude, it.longitude); "%02d:%02d".format(s / 60, s % 60) },
            nowPlayingSummary = np?.takeIf { it.title.isNotEmpty() }?.let { "'${it.title}' by ${it.artist}" },
            fuelLogSummary  = fuel.takeIf { it.isNotEmpty() }?.joinToString("; ") {
                "%.1fL for %.2f at %.0fkm".format(it.volume, it.cost, it.odometerKm)
            },
            unitSystem      = settings.value.unitSystem.name
        )
    }

    private fun processVoiceCommand(transcript: String) {
        _voiceState.value = VoiceAssistantState.THINKING
        viewModelScope.launch {
            try {
                val prompt = buildVoiceSystemPrompt(buildVoiceContext())
                val resp = GeminiApi.service.generateContent(
                    model  = "gemini-3.5-flash-lite",
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    request = GeminiRequest(
                        contents = listOf(GeminiContent(parts = listOf(GeminiPart(transcript)))),
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart(prompt)))
                    )
                )
                val raw = resp.text?.trim()
                    ?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim()
                val result = raw?.let { runCatching { Gson().fromJson(it, VoiceActionResult::class.java) }.getOrNull() }
                if (result == null) {
                    _voiceState.value = VoiceAssistantState.ERROR
                    speak("Sorry, something went wrong understanding that.")
                } else {
                    dispatchVoiceAction(result)
                }
            } catch (e: Exception) {
                _voiceState.value = VoiceAssistantState.ERROR
                speak("Couldn't reach the assistant — check your connection.")
            }
        }
    }

    private fun dispatchVoiceAction(result: VoiceActionResult) {
        when (result.action) {
            "SET_THEME" -> result.theme?.let { id ->
                if (DASHBOARD_THEMES.any { it.id == id }) updateSettings { copy(themeId = id) }
            }
            "SET_DAY_NIGHT_MODE" -> result.dayNightMode?.let { mode ->
                runCatching { DayNightMode.valueOf(mode) }.getOrNull()?.let { m -> updateSettings { copy(dayNightMode = m) } }
            }
            "NAVIGATE_SCREEN" -> result.screen?.let { s ->
                runCatching { NavDestination.valueOf(s) }.getOrNull()?.let { navigate(it) }
            }
            "SET_CLOCK_FORMAT" -> result.clockFormat?.let { fmt ->
                updateSettings { copy(use24HourFormat = fmt.trim() == "24") }
            }
            "SET_VOLUME" -> result.direction?.let { adjustDeviceVolume(it) }
            "PLAY_MUSIC" -> result.query?.let { playFromSearch(it) }
            "NAVIGATE_WAZE" -> result.destination?.let { launchWazeNavigation(it) }
            "ADD_FUEL_ENTRY" -> {
                val odo = result.odometerKm; val vol = result.volumeLiters; val cost = result.cost
                if (odo != null && vol != null && cost != null) addFuelEntry(odo, vol, cost)
            }
            else -> {} // ANSWER / UNKNOWN — nothing to dispatch, spokenReply covers it
        }
        speak(result.spokenReply)
    }

    private fun adjustDeviceVolume(direction: String) {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        when (direction.uppercase()) {
            "UP"   -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "DOWN" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "MUTE" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
    }

    // Standard MediaSession command — the same one "OK Google, play X on Spotify"
    // uses, so any MediaSession-compatible app already supports it with no
    // Spotify-specific integration needed on our end.
    private fun playFromSearch(query: String) {
        nowPlaying.value?.controller?.transportControls?.playFromSearch(query, null)
    }

    // Waze's documented URI scheme — no Waze API needed. Exact place/address
    // only for now; fuzzy "somewhere for dinner"-style search needs a Places
    // API call this app doesn't make yet (Gemini alone has no live business
    // data), so the prompt steers those to UNKNOWN instead of guessing.
    private fun launchWazeNavigation(destination: String) {
        val app = getApplication<Application>()
        val uri = Uri.parse("waze://?q=${Uri.encode(destination)}&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }.onFailure {
            // Waze not installed — fall back to the generic navigation intent,
            // which prompts whatever maps app is available.
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    init {
        loadInstalledApps()
        refreshConnectivity()
        startVoltageObserver()
        if (hasSzchoicewayMcu) startHardwareRadioObserver()
        // Weather refreshes every 30 minutes (conditions don't change fast enough
        // to justify more). Place name refresh interval is user-configurable
        // (Settings > Location Refresh, 30s/1min/2min/5min — default 30s); the
        // Nominatim usage policy caps at 1 req/sec, and even the fastest option
        // is nowhere near that. Note this only actually tightens the refresh
        // while driving (GPS fires new fixes every few seconds on movement);
        // parked, the retry cadence is still bottlenecked by the once-a-minute
        // ticker below, since a stationary GPS provider won't emit new updates
        // at all — that ticker covers the parked case where no location updates arrive.
        viewModelScope.launch {
            merge(
                locationMgr.location.filterNotNull(),
                minuteTicker.mapNotNull { locationMgr.location.value }
            ).collect { loc ->
                val now = System.currentTimeMillis()
                // lastWeatherFetchMs/lastPlaceFetchMs are only stamped on a *successful*
                // fetch (see fetchWeather/fetchPlaceName), so a failed attempt — e.g. right
                // at boot before the network is up — retries on the next tick instead of
                // locking the panel empty for the rest of the throttle window.
                if (now - lastWeatherFetchMs >= 30 * 60 * 1_000L) {
                    fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
                }
                if (now - lastPlaceFetchMs >= settings.value.locationRefreshInterval.millis) {
                    fetchPlaceName(loc.latitude, loc.longitude)
                }
            }
        }
    }
}
