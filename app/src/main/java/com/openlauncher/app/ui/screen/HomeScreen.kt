package com.openlauncher.app.ui.screen

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.ClockStyle
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.GRID_COLS
import com.openlauncher.app.data.GRID_ROWS
import com.openlauncher.app.data.WidgetConfig
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.theme.onAccentColor
import com.openlauncher.app.ui.widget.*
import java.util.Calendar
import com.openlauncher.app.util.LocationData

private val WIDGET_RADIUS = RoundedCornerShape(0.dp)

private data class WidgetTypeInfo(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
    // Needs a live internet connection (WiFi/hotspot or the unit's own cellular
    // data) to actually populate — Weather (Open-Meteo) and Location's place
    // name (Nominatim reverse geocoding) both go blank offline, unlike every
    // other widget here which works from local sensors/data alone.
    val requiresNetwork: Boolean = false
)

private val ALL_WIDGET_TYPES = listOf(
    WidgetTypeInfo("CLOCK",       "CLOCK",       Icons.Default.AccessTime,  "Time & date"),
    WidgetTypeInfo("WEATHER",     "WEATHER",     Icons.Default.Cloud,       "Current conditions", requiresNetwork = true),
    WidgetTypeInfo("NOW_PLAYING", "NOW PLAYING", Icons.Default.MusicNote,   "Media controls"),
    WidgetTypeInfo("TELEMETRY",   "COMPASS",     Icons.Default.Explore,     "Speed & heading"),
    WidgetTypeInfo("ALTIMETER",   "ALTIMETER",   Icons.Default.FlightTakeoff, "Roll, pitch & altitude"),
    WidgetTypeInfo("SPEEDOMETER", "SPEED",       Icons.Default.Speed,         "GPS speed"),
    WidgetTypeInfo("VITALS",      "VITALS",      Icons.Default.Dns,           "Head Unit Health / Vitals"),
    WidgetTypeInfo("TRIP_TRACKER", "TRIP TRACKER", Icons.Default.Map,          "Trip logs & stats"),
    WidgetTypeInfo("SOUNDBOARD",  "SOUNDBOARD",  Icons.Default.Piano,         "Custom sound pads"),
    WidgetTypeInfo("FUEL_LOG",    "FUEL LOG",    Icons.Default.LocalGasStation, "Fill-ups & efficiency"),
    WidgetTypeInfo("QUICK_TOGGLES", "TOGGLES",   Icons.Default.ToggleOn,      "WiFi, Bluetooth & DND"),
    WidgetTypeInfo("LOCATION",    "LOCATION",    Icons.Default.GpsFixed,      "Live GPS coordinates", requiresNetwork = true)
)

private fun canAddWidget(settings: com.openlauncher.app.data.AppSettings): Boolean {
    val visibleIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
        if (settings.showFuelLog) add("FUEL_LOG")
        if (settings.showQuickToggles) add("QUICK_TOGGLES")
        if (settings.showLocation) add("LOCATION")
    }
    val activeWidgets = settings.widgetLayout.filter { it.enabled && it.id in visibleIds }
    val occupied = buildSet<Pair<Int, Int>> {
        activeWidgets.forEach { w ->
            for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
        }
    }
    val hasFreeCell = (0 until com.openlauncher.app.data.GRID_ROWS).any { r ->
        (0 until com.openlauncher.app.data.GRID_COLS).any { c -> (c to r) !in occupied }
    }
    // Also true if any active widget spans >1 cell and can be shrunk to make room
    val hasShrinkable = activeWidgets.any { it.spanX * it.spanY > 1 }
    return hasFreeCell || hasShrinkable
}

@Composable
fun HomeScreen(
    settings: AppSettings,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    location: LocationData?,
    placeName: String? = null,
    bearing: Float,
    isWifi: Boolean,
    isData: Boolean,
    voltage: Float? = null,
    isDayMode: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: () -> Unit,
    onClearPip: () -> Unit,
    onLaunchPip: () -> Unit,
    onTapNowPlaying: () -> Unit,
    onUpdateWidget: (id: String, spanX: Int, spanY: Int) -> Unit,
    onMoveWidget: (id: String, gridX: Int, gridY: Int) -> Unit,
    onAddWidget: (id: String) -> Unit,
    onRemoveWidget: (id: String) -> Unit,
    onApplyPreset: (List<WidgetConfig>) -> Unit = {},
    onAddFuelEntry: (odometerKm: Double, volume: Double, cost: Double) -> Unit = { _, _, _ -> },
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit = {},
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit = {},
    onSetLocationDetailLevel: (com.openlauncher.app.data.LocationDetailLevel) -> Unit = {},
    onUpdateSoundPad: (index: Int, pad: com.openlauncher.app.data.SoundPadConfig) -> Unit = { _, _ -> },
    onUpdate: (AppSettings.() -> AppSettings) -> Unit = {},
    voiceState: com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState = com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.IDLE,
    voiceTranscript: String? = null,
    voiceReply: String? = null,
    onStartVoiceCommand: () -> Unit = {},
    onStopVoiceCommand: () -> Unit = {},
    wakeWordDebug: String = "",
    wakeWordPulse: Long = 0L,
    volumeLevel: Float = 0f,
    onVolumeUp: () -> Unit = {},
    onVolumeDown: () -> Unit = {},
    onOpenSystemWifiPanel: () -> Unit = {}, // Settings.Panel.ACTION_WIFI — the only way to actually add/switch networks, see WifiPanel
    wifiPanelOpen: Boolean = false,
    onRequestWifiPanel: () -> Unit = {},
    onDismissWifiPanel: () -> Unit = {},
    onRequestBluetoothPanel: () -> Unit = {}, // checks/requests BLUETOOTH_CONNECT, then opens bluetoothPanelOpen below
    bluetoothPanelOpen: Boolean = false,
    onDismissBluetoothPanel: () -> Unit = {},
    pairedBluetoothDeviceNames: () -> List<String> = { emptyList() },
    onConnectBluetoothDevice: (String) -> Unit = {},
    onDisconnectBluetoothDevice: (String) -> Unit = {},
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState? = null,
    onLaunchHardwareRadio: () -> Unit = {},
    onStopHardwareRadio: () -> Unit = {},
    onRadioSeekUp: () -> Unit = {},
    onRadioSeekDown: () -> Unit = {},
    onRadioCycleFm: () -> Unit = {},
    onRadioSwitchAm: () -> Unit = {},
    onRadioTune: (band: String, freq: Float) -> Unit = { _, _ -> },
    onAssignRadio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // A preset themeId supplies its own accent + tile surface for the current mode;
    // "custom" (or an unrecognized id) falls through to the manually-picked accent
    // and the original wallpaper/day-mode tile logic below, unchanged.
    val resolvedTheme = com.openlauncher.app.data.resolveDashboardTheme(settings.themeId, isDayMode)
    val accent       = resolvedTheme?.accent ?: Color(settings.accentColor)
    val gap          = 6.dp
    val hasWallpaper = settings.wallpaperUri.isNotEmpty()
    val widgetBg     = when {
        resolvedTheme != null && !hasWallpaper -> resolvedTheme.background
        isDayMode    -> Color(0xFFFFFFFF)
        hasWallpaper -> Color(0xCC000000)
        else         -> Color.Black.copy(alpha = 0.35f)
    }
    val widgetBorder = when {
        resolvedTheme != null && !hasWallpaper -> accent.copy(alpha = if (isDayMode) 0.35f else 0.25f)
        isDayMode    -> Color(0xFFCCCCCC)
        hasWallpaper -> Color(0x22FFFFFF)
        else         -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    }
    val headerTextColor   = if (isDayMode) Color(0xFF111111) else accent
    val statusIconColor   = if (isDayMode) Color(0xFF444444) else Color(0xFF666666)
    val controlIconColor  = if (isDayMode) Color(0xFF666666) else Color(0xFF444444)

    var resizingId    by remember { mutableStateOf<String?>(null) }
    var contextMenuId by remember { mutableStateOf<String?>(null) }

    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editMode         by remember { mutableStateOf(false) }
    var widgetLibraryOpen by remember { mutableStateOf(false) }
    var presetPickerOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text          = settings.vehicleName.uppercase(),
                style         = MaterialTheme.typography.titleLarge,
                color         = headerTextColor,
                letterSpacing = 3.sp,
                fontSize      = 14.sp
            )
            Spacer(Modifier.weight(1f))
            if (voltage != null) {
                val voltageColor = if (voltage < 12f) Color(0xFFE05252) else statusIconColor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Default.Bolt, "Voltage", tint = voltageColor, modifier = Modifier.size(14.dp))
                    Text(
                        text = "%.1fV".format(voltage),
                        color = voltageColor,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            // WiFi icon dropped from the header — the control rail's own
            // WiFi tile is both live (real NetworkCallback, not on-resume
            // polling) and reachable, so this was pure duplication.
            AnimatedVisibility(visible = isData, enter = fadeIn(), exit = fadeOut()) {
                Icon(Icons.Default.SignalCellularAlt, "Data", tint = statusIconColor, modifier = Modifier.size(16.dp))
            }
            // Voice assistant trigger used to live here as a small 28dp
            // header icon — moved to a large floating button (see
            // VoiceFab below) since it's the hands-on-wheel fallback for
            // when the wake word can't be heard over music, and a header
            // icon that size wasn't reliably hittable while driving.
            if (isLandscape) {
                Spacer(Modifier.width(8.dp))
                if (editMode) {
                    IconButton(
                        onClick  = { widgetLibraryOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Dashboard,
                            contentDescription = "Widget library",
                            tint               = controlIconColor,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick  = { presetPickerOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.ViewQuilt,
                            contentDescription = "Layout presets",
                            tint               = controlIconColor,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }
                IconButton(
                    onClick  = { editMode = !editMode },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Default.Edit,
                        contentDescription = "Edit widgets",
                        tint               = if (editMode) accent else controlIconColor,
                        modifier           = Modifier.size(15.dp)
                    )
                }
            }
        }

        // The score/peak/mic debug readout that lived here was for tuning
        // "Hi Sebastian" sensitivity during development — replaced with a
        // small status dot now that it's confirmed reliable. wakeWordDebug
        // is still computed (see WakeWordService) in case tuning is ever
        // needed again, just no longer rendered.
        run {
            var pulse by remember { mutableStateOf(false) }
            LaunchedEffect(wakeWordPulse) {
                if (wakeWordPulse > 0L) {
                    pulse = true
                    kotlinx.coroutines.delay(1500)
                    pulse = false
                }
            }
            Box(
                modifier = Modifier
                    .padding(start = 20.dp, top = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (pulse) Color(0xFF4CAF50) else accent.copy(alpha = 0.25f))
            )
        }

        AnimatedVisibility(
            visible = voiceState != com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.IDLE,
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            val bannerText = when (voiceState) {
                com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.LISTENING -> "Listening…"
                com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.THINKING   -> voiceTranscript ?: "Thinking…"
                else -> voiceReply ?: ""
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.1f))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, null, tint = accent, modifier = Modifier.size(14.dp))
                Text(
                    text     = bannerText,
                    color    = if (isDayMode) Color(0xFF111111) else Color.White,
                    fontSize = 11.sp,
                    // Bumped from 2 — Live error messages now carry real
                    // diagnostic detail (chunk/message counts, raw server
                    // text) and getting cut off defeats the point.
                    maxLines = 6
                )
            }
        }

        HorizontalDivider(color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF141414))

        // ── Widget Grid + control rail ────────────────────────────────────────
        // The rail is a dedicated column the grid never lays widgets into —
        // not a floating overlay on top of it. A fully-packed layout (e.g. the
        // Split Panel preset) runs edge to edge, so anything floated on top
        // WILL eventually sit over live content; reserving real space is the
        // only placement that's correct for every layout, not just today's.
        Row(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(gap)
        ) {
            val cellW = (maxWidth  - gap * (GRID_COLS - 1)) / GRID_COLS
            val cellH = (maxHeight - gap * (GRID_ROWS - 1)) / GRID_ROWS
            val density = LocalDensity.current
            val cellStepXPx = with(density) { (cellW + gap).toPx() }
            val cellStepYPx = with(density) { (cellH + gap).toPx() }

            // WEATHER stays in the set even with no data: the commit path
            // (LauncherViewModel.moveWidgetConfig) computes against settings flags
            // only, so dropping it here would make the drop ghost and the committed
            // layout disagree. With no data the cell renders fully transparent.
            val visibleIds = buildSet {
                if (settings.showClock) add("CLOCK")
                if (settings.showWeather) add("WEATHER")
                if (settings.showNowPlaying) add("NOW_PLAYING")
                if (settings.showTelemetry) add("TELEMETRY")
                if (settings.showAltimeter) add("ALTIMETER")
                if (settings.showSpeedometer) add("SPEEDOMETER")
                if (settings.showVitals) add("VITALS")
                if (settings.showTripTracker) add("TRIP_TRACKER")
                if (settings.showSoundboard) add("SOUNDBOARD")
                if (settings.showFuelLog) add("FUEL_LOG")
                if (settings.showQuickToggles) add("QUICK_TOGGLES")
                if (settings.showLocation) add("LOCATION")
            }

            // Keep only visible widgets exactly as configured in settings, allowing explicit resizing to dictate layout
            val visible = settings.widgetLayout.filter { it.enabled && it.id in visibleIds }
            val rendered = visible

            // ── Drag state ───────────────────────────────────────────────────
            var draggingId   by remember { mutableStateOf<String?>(null) }
            var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }

            // Compute snap target for the widget being dragged (uses original spanX)
            val draggingOriginal = if (draggingId != null) visible.find { it.id == draggingId } else null
            val targetGridX = draggingOriginal?.let {
                (it.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt()).coerceIn(0, GRID_COLS - it.spanX)
            }
            val targetGridY = draggingOriginal?.let {
                (it.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt()).coerceIn(0, GRID_ROWS - it.spanY)
            }

            // Compute proposed layout (push preview) while dragging
            val proposedLayout = if (draggingOriginal != null && targetGridX != null && targetGridY != null)
                computeWidgetMove(visible, draggingOriginal.id, targetGridX, targetGridY)
            else null

            // Drop ghost — rendered before widgets so it appears beneath them
            if (draggingOriginal != null && targetGridX != null && targetGridY != null) {
                val gX = (cellW + gap) * targetGridX
                val gY = (cellH + gap) * targetGridY
                val gW = cellW * draggingOriginal.spanX + gap * (draggingOriginal.spanX - 1)
                val gH = cellH * draggingOriginal.spanY + gap * (draggingOriginal.spanY - 1)
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = gX, y = gY)
                        .size(gW, gH)
                        .background(accent.copy(alpha = 0.08f))
                        .border(1.dp, accent.copy(alpha = 0.5f), WIDGET_RADIUS)
                )
            }

            // Displacement ghosts — show where pushed widgets will land
            if (proposedLayout != null && draggingOriginal != null) {
                proposedLayout
                    .filter { it.id != draggingOriginal.id }
                    .forEach { proposed ->
                        val original = visible.find { it.id == proposed.id } ?: return@forEach
                        if (proposed.gridX != original.gridX || proposed.gridY != original.gridY) {
                            val dX = (cellW + gap) * proposed.gridX
                            val dY = (cellH + gap) * proposed.gridY
                            val dW = cellW * proposed.spanX + gap * (proposed.spanX - 1)
                            val dH = cellH * proposed.spanY + gap * (proposed.spanY - 1)
                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = dX, y = dY)
                                    .size(dW, dH)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), WIDGET_RADIUS)
                            )
                        }
                    }
            }

            rendered.forEach { w ->
                val xOff   = (cellW + gap) * w.gridX
                val yOff   = (cellH + gap) * w.gridY
                val width  = cellW * w.spanX + gap * (w.spanX - 1)
                val height = cellH * w.spanY + gap * (w.spanY - 1)

                val label = when (w.id) {
                    // CLOCK renders its own greeting/icon row internally now — the generic
                    // corner label would just duplicate it (this was the "two MORNING" bug).
                    "CLOCK"       -> ""
                    "WEATHER"     -> "WEATHER"
                    "NOW_PLAYING" -> "NOW PLAYING"
                    "TELEMETRY"   -> "COMPASS"
                    "ALTIMETER"   -> "ALTIMETER"
                    "SPEEDOMETER" -> "SPEED"
                    "TRIP_TRACKER" -> "TRIP"
                    "SOUNDBOARD"  -> "SOUND"
                    "FUEL_LOG"    -> "FUEL"
                    "QUICK_TOGGLES" -> "TOGGLES"
                    // LOCATION renders its own "LIVE LOCATION" header internally —
                    // same duplicate-label issue the Clock widget had.
                    "LOCATION"    -> ""
                    else          -> w.id
                }

                // Original (pre-auto-expand) spanX needed for drag boundary clamping
                val origSpanX  = visible.find { it.id == w.id }?.spanX ?: 1
                val isDragging = draggingId == w.id
                // Weather with no data reserves its cell but draws nothing
                // (still visible in edit mode so it can be moved/removed)
                val isGhost    = w.id == "WEATHER" && weather == null && !editMode
                val dragDpX    = if (isDragging) with(density) { dragOffsetPx.x.toDp() } else 0.dp
                val dragDpY    = if (isDragging) with(density) { dragOffsetPx.y.toDp() } else 0.dp

                @OptIn(ExperimentalFoundationApi::class)
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = xOff + dragDpX, y = yOff + dragDpY)
                        .size(width, height)
                        .zIndex(if (isDragging) 1f else 0f)
                        .clip(WIDGET_RADIUS)
                        .background(if (isGhost) Color.Transparent else widgetBg)
                        .border(
                            width = if (editMode) 1.5.dp else 1.dp,
                            color = when {
                                editMode -> accent.copy(alpha = 0.45f)
                                isGhost  -> Color.Transparent
                                else     -> widgetBorder
                            },
                            shape = WIDGET_RADIUS
                        )
                        .combinedClickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick           = { if (editMode) contextMenuId = w.id },
                            onLongClick       = { if (!editMode) contextMenuId = w.id }
                        )
                        .then(
                            if (editMode) Modifier.pointerInput(editMode, w.id, w.gridX, w.gridY) {
                                var hasSignificantDrag = false
                                // Touch-slop gate: without it, sub-pixel jitter during a
                                // long-press counts as a drag and the context menu never opens
                                val slop = viewConfiguration.touchSlop
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { _ ->
                                        draggingId         = w.id
                                        dragOffsetPx       = Offset.Zero
                                        hasSignificantDrag = false
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx      += dragAmount
                                        if (!hasSignificantDrag && dragOffsetPx.getDistance() > slop) {
                                            hasSignificantDrag = true
                                        }
                                    },
                                    onDragEnd = {
                                        if (hasSignificantDrag) {
                                            val newX = (w.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt())
                                                .coerceIn(0, GRID_COLS - origSpanX)
                                            val newY = (w.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt())
                                                .coerceIn(0, GRID_ROWS - w.spanY)
                                            onMoveWidget(w.id, newX, newY)
                                        } else {
                                            contextMenuId = w.id
                                        }
                                        draggingId   = null
                                        dragOffsetPx = Offset.Zero
                                    },
                                    onDragCancel = {
                                        draggingId   = null
                                        dragOffsetPx = Offset.Zero
                                    }
                                )
                            } else Modifier
                        )
                ) {
                    when (w.id) {
                        "CLOCK" -> ClockWidget(
                            style      = settings.clockStyle,
                            accent     = accent,
                            isDayMode  = isDayMode,
                            location   = location,
                            showSunriseSunset = settings.showSunriseSunset,
                            isEditing  = editMode,
                            use24HourFormat = settings.use24HourFormat,
                            modifier   = Modifier.fillMaxSize()
                        )
                        "WEATHER" -> WeatherWidget(
                            state      = weather,
                            accent     = accent,
                            metric     = settings.unitSystem.name == "METRIC",
                            isDayMode  = isDayMode,
                            location   = location,
                            placeName  = placeName,
                            showWindSpeed  = settings.showWindSpeed,
                            showFeelsLike  = settings.showFeelsLike,
                            showRainChance = settings.showRainChance,
                            modifier   = Modifier.fillMaxSize()
                        )
                        "NOW_PLAYING" -> NowPlayingWidget(
                            state               = nowPlaying,
                            accent              = accent,
                            carPlayPackage      = settings.carPlayPackage,
                            androidAutoPackage  = settings.androidAutoPackage,
                            onPlayPause         = onPlayPause,
                            onNext              = onNext,
                            onPrev              = onPrev,
                            onLaunchCarPlay     = onLaunchCarPlay,
                            onLaunchAndroidAuto = onLaunchAndroidAuto,
                            onTapToOpenApp      = onTapNowPlaying,
                            modifier            = Modifier.fillMaxSize(),
                            isEditing           = editMode,
                            isDayMode           = isDayMode,
                            hardwareRadio         = hardwareRadio,
                            onLaunchHardwareRadio = onLaunchHardwareRadio,
                            onStopHardwareRadio   = onStopHardwareRadio,
                            onRadioSeekUp         = onRadioSeekUp,
                            onRadioSeekDown       = onRadioSeekDown,
                            onRadioCycleFm        = onRadioCycleFm,
                            onRadioSwitchAm       = onRadioSwitchAm,
                            onRadioTune           = onRadioTune,
                            onAssignRadio         = onAssignRadio,
                            showSourceBadge       = settings.showNowPlayingSourceBadge
                        )
                        "TELEMETRY" -> TelemetryWidget(
                            location  = location,
                            bearing   = (bearing + settings.compassOffset + 360f) % 360f,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "ALTIMETER" -> AltimeterWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "SPEEDOMETER" -> SpeedometerWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            digitalOnly = settings.speedometerDigitalOnly,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "VITALS" -> VitalsWidget(
                            accent    = accent,
                            isDayMode = isDayMode,
                            asBars    = settings.vitalsAsBars,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "TRIP_TRACKER" -> TripTrackerWidget(
                            location  = location,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "SOUNDBOARD" -> SoundboardWidget(
                            pads      = settings.soundboardPads,
                            accent    = accent,
                            isDayMode = isDayMode,
                            isEditing = editMode,
                            onUpdatePad = onUpdateSoundPad,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "FUEL_LOG" -> FuelLogWidget(
                            entries   = settings.fuelLog,
                            isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                            accent    = accent,
                            isDayMode = isDayMode,
                            isEditing = editMode,
                            onAddEntry = onAddFuelEntry,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "QUICK_TOGGLES" -> QuickTogglesWidget(
                            accent    = accent,
                            isDayMode = isDayMode,
                            isEditing = editMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                        "LOCATION" -> LocationWidget(
                            location  = location,
                            placeName = placeName,
                            accent    = accent,
                            isDayMode = isDayMode,
                            modifier  = Modifier.fillMaxSize()
                        )
                    }

                    // Label — Now Playing's art is a small thumbnail now, not a full-bleed
                    // background, so the corner label no longer needs to hide behind it.
                    val labelColor = when {
                        isGhost -> Color.Transparent
                        isDayMode -> Color(0xFF999999)
                        else      -> Color(0xFF3A3A3A)
                    }
                    Text(
                        text          = label,
                        style         = MaterialTheme.typography.labelSmall,
                        color         = labelColor,
                        letterSpacing = 2.sp,
                        fontSize      = 8.sp,
                        modifier      = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 7.dp)
                    )
                }
            }
        }

        // ── Control rail ─────────────────────────────────────────────────────
        // Dedicated space the grid excludes from its own layout math, not an
        // overlay — see the comment above where this Row starts. Bottom to
        // top: voice (the fallback for when the wake word can't be heard —
        // loudest case: music playing, see WakeWordService), volume down/up
        // (mirrors the physical rocker on the far-LEFT bezel — a real reach
        // problem for a driver on the right, not a preference), Bluetooth,
        // WiFi. Same reach logic moved these out of the Clock widget's
        // corner, which only had room for them at 18dp — too small to hit
        // reliably while driving, same complaint voice/volume already had.
        ControlRail(
            voiceState = voiceState,
            accent = accent,
            widgetBg = widgetBg,
            widgetBorder = widgetBorder,
            volumeLevel = volumeLevel,
            onStartVoiceCommand = onStartVoiceCommand,
            onStopVoiceCommand = onStopVoiceCommand,
            onVolumeUp = onVolumeUp,
            onVolumeDown = onVolumeDown,
            onOpenWifiPanel = onRequestWifiPanel,
            onOpenBluetoothPanel = onRequestBluetoothPanel,
            modifier = Modifier
                .width(60.dp)
                .fillMaxHeight()
                .padding(vertical = gap, horizontal = 4.dp)
        )
        }
    }

    // ── Widget context menu (long-press any cell) ────────────────────────────
    contextMenuId?.let { id ->
        WidgetContextMenu(
            widgetId            = id,
            accent              = accent,
            clockStyle          = settings.clockStyle,
            vitalsAsBars        = settings.vitalsAsBars,
            speedometerDigitalOnly = settings.speedometerDigitalOnly,
            locationDetailLevel = settings.locationDetailLevel,
            carPlayPackage      = settings.carPlayPackage,
            androidAutoPackage  = settings.androidAutoPackage,
            pipAppPackage       = settings.pipAppPackage,
            use24HourFormat     = settings.use24HourFormat,
            showSunriseSunset   = settings.showSunriseSunset,
            showWindSpeed       = settings.showWindSpeed,
            showFeelsLike       = settings.showFeelsLike,
            showRainChance      = settings.showRainChance,
            showNowPlayingSourceBadge = settings.showNowPlayingSourceBadge,
            isDayMode           = isDayMode,
            onResize            = { contextMenuId = null; resizingId = id },
            onAssignCarPlay     = { contextMenuId = null; onAssignCarPlay() },
            onAssignAndroidAuto = { contextMenuId = null; onAssignAndroidAuto() },
            onClearCarPlay      = { contextMenuId = null; onClearCarPlay() },
            onClearAndroidAuto  = { contextMenuId = null; onClearAndroidAuto() },
            onAssignPip         = { contextMenuId = null; onAssignPip() },
            onClearPip          = { contextMenuId = null; onClearPip() },
            onSetClockStyle     = { onSetClockStyle(it) },
            onSetVitalsAsBars   = { onSetVitalsAsBars(it) },
            onSetSpeedometerDigitalOnly = { onSetSpeedometerDigitalOnly(it) },
            onSetLocationDetailLevel = { onSetLocationDetailLevel(it) },
            onToggle            = { block -> onUpdate(block) },
            onDismiss           = { contextMenuId = null }
        )
    }

    // ── Resize dialog ────────────────────────────────────────────────────────
    resizingId?.let { id ->
        val config = settings.widgetLayout.find { it.id == id }
        if (config != null) {
            WidgetResizeDialog(
                config    = config,
                accent    = accent,
                isDayMode = isDayMode,
                onDismiss = { resizingId = null },
                onConfirm = { sx, sy ->
                    onUpdateWidget(id, sx, sy)
                    resizingId = null
                }
            )
        }
    }

    // ── Widget library ────────────────────────────────────────────────────────
    if (widgetLibraryOpen) {
        WidgetLibraryDialog(
            settings  = settings,
            accent    = accent,
            isDayMode = isDayMode,
            onAdd     = { id -> onAddWidget(id) },
            onRemove  = { id -> onRemoveWidget(id) },
            onDismiss = { widgetLibraryOpen = false }
        )
    }

    // ── Layout presets ───────────────────────────────────────────────────────
    if (presetPickerOpen) {
        LayoutPresetDialog(
            accent    = accent,
            isDayMode = isDayMode,
            onApply   = { preset -> onApplyPreset(preset); presetPickerOpen = false },
            onDismiss = { presetPickerOpen = false }
        )
    }

    // ── WiFi panel ────────────────────────────────────────────────────────────
    // Android's own Settings.Panel.ACTION_WIFI is the only way to actually
    // connect to a new network — apps have not been able to drive that flow
    // themselves since Android 10 — so it stays as the "add/switch network"
    // action, but living inside a themed status card instead of being the
    // rail's direct tap target means the common case (glancing at what
    // you're already connected to) never leaves OpenLauncher's own look.
    if (wifiPanelOpen) {
        WifiPanel(
            accent    = accent,
            isDayMode = isDayMode,
            onOpenSystemPanel = { onDismissWifiPanel(); onOpenSystemWifiPanel() },
            onDismiss = onDismissWifiPanel
        )
    }

    // ── Bluetooth panel ───────────────────────────────────────────────────────
    // Curated in-app equivalent of the above — no Bluetooth equivalent of
    // Settings.Panel.ACTION_WIFI exists in the public API, so tapping
    // Bluetooth used to jump all the way out to the system Settings app.
    // This stays inside OpenLauncher instead.
    if (bluetoothPanelOpen) {
        BluetoothPanel(
            accent      = accent,
            isDayMode   = isDayMode,
            devices     = remember(bluetoothPanelOpen) { pairedBluetoothDeviceNames() },
            onConnect   = onConnectBluetoothDevice,
            onDisconnect = onDisconnectBluetoothDevice,
            onDismiss   = onDismissBluetoothPanel
        )
    }
}

/**
 * Vertical control rail — voice and volume — occupying real, reserved space
 * beside the widget grid rather than floating on top of it. A floating
 * overlay looked fine against a sparse layout but sat directly over the
 * weather/clock panel once the grid was actually packed (Split Panel preset
 * runs edge to edge); reserving space is the only placement guaranteed not
 * to cover live content regardless of which layout is active.
 */
@Composable
private fun ControlRail(
    voiceState: com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState,
    accent: Color,
    widgetBg: Color,
    widgetBorder: Color,
    volumeLevel: Float,
    onStartVoiceCommand: () -> Unit,
    onStopVoiceCommand: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onOpenWifiPanel: () -> Unit,
    onOpenBluetoothPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listening = voiceState == com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.LISTENING
    val voiceEnabled = voiceState == com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.IDLE || listening
    val voiceActive = listening || voiceState == com.openlauncher.app.viewmodel.LauncherViewModel.VoiceAssistantState.ERROR

    val context = androidx.compose.ui.platform.LocalContext.current
    val wifiManager = remember {
        context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    }
    val btAdapter = remember {
        (context.applicationContext.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
    }
    // BluetoothAdapter.isEnabled() requires BLUETOOTH_CONNECT on API 31+ and
    // throws SecurityException without it — confirmed on-device as a real
    // crash, unguarded, on the very first composition of this rail right
    // after onboarding finishes (BLUETOOTH_CONNECT isn't requested until the
    // driver actually opens the Bluetooth panel or uses a voice command, so
    // a fresh install reaches this composable without it every time).
    var wifiOn by remember { mutableStateOf(wifiManager?.isWifiEnabled == true) }
    var btOn by remember { mutableStateOf(runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)) }
    // Same 2s poll the Clock widget's own WiFi/Bluetooth icons used — no
    // broadcast exists for "radio enabled" that's worth registering a
    // receiver for just to save a cheap poll.
    LaunchedEffect(Unit) {
        while (true) {
            wifiOn = wifiManager?.isWifiEnabled == true
            btOn = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)
            kotlinx.coroutines.delay(2000)
        }
    }
    val inactiveTint = accent.copy(alpha = 0.35f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RailTile(size = 36.dp, accent = accent, widgetBg = widgetBg, widgetBorder = widgetBorder, onClick = onOpenWifiPanel) {
            Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = if (wifiOn) accent else inactiveTint, modifier = Modifier.size(17.dp))
        }
        RailTile(size = 36.dp, accent = accent, widgetBg = widgetBg, widgetBorder = widgetBorder, onClick = onOpenBluetoothPanel) {
            Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth", tint = if (btOn) accent else inactiveTint, modifier = Modifier.size(17.dp))
        }

        Spacer(Modifier.height(2.dp))

        RailTile(size = 40.dp, accent = accent, widgetBg = widgetBg, widgetBorder = widgetBorder, onClick = onVolumeUp) {
            Icon(Icons.Default.VolumeUp, contentDescription = "Volume up", tint = accent, modifier = Modifier.size(18.dp))
        }
        Text(
            text       = "${(volumeLevel * 100).roundToInt()}%",
            color      = accent,
            fontSize   = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        RailTile(size = 40.dp, accent = accent, widgetBg = widgetBg, widgetBorder = widgetBorder, onClick = onVolumeDown) {
            Icon(Icons.Default.VolumeDown, contentDescription = "Volume down", tint = accent, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.weight(1f))

        // Bigger than the volume tiles — this is the primary fallback
        // control, not a secondary one — and tinted red while listening or
        // on error, same language as the rest of the voice UI elsewhere.
        RailTile(
            size = 52.dp,
            accent = if (voiceActive) Color(0xFFE05252) else accent,
            widgetBg = widgetBg,
            widgetBorder = if (voiceActive) Color(0xFFE05252).copy(alpha = 0.6f) else widgetBorder,
            enabled = voiceEnabled,
            onClick = { if (listening) onStopVoiceCommand() else onStartVoiceCommand() }
        ) {
            Icon(
                imageVector        = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Voice assistant",
                tint               = if (voiceActive) Color(0xFFE05252) else accent,
                modifier           = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * One flat control tile — same visual language as every widget card
 * (widgetBg fill, widgetBorder stroke, sharp WIDGET_RADIUS corners, no
 * elevation/shadow) rather than a Material filled circular FAB, which read
 * as a foreign UI-kit element glued on top of this dashboard's HUD look.
 */
@Composable
private fun RailTile(
    size: Dp,
    accent: Color,
    widgetBg: Color,
    widgetBorder: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(WIDGET_RADIUS)
            .background(widgetBg)
            .border(1.dp, widgetBorder, WIDGET_RADIUS)
            .clickable(
                enabled           = enabled,
                indication        = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Curated Bluetooth panel — Android ships Settings.Panel.ACTION_WIFI, a
 * bottom-sheet-style panel that stays inside the calling app, but never
 * shipped an equivalent for Bluetooth. Without this, tapping Bluetooth meant
 * leaving OpenLauncher entirely for the system Settings app. Lists paired
 * devices with direct connect/disconnect (see LauncherViewModel's
 * setBluetoothDeviceConnected — the same reflection-based A2DP proxy call
 * "connect to Zoe's phone" already uses by voice).
 */
@Composable
private fun BluetoothPanel(
    accent: Color,
    isDayMode: Boolean,
    devices: List<String>,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val panelBg     = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val panelBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val labelColor  = if (isDayMode) Color(0xFF111111) else Color(0xFFEFEFEF)
    val subtleColor = if (isDayMode) Color(0xFF888888) else Color(0xFF666666)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick           = onDismiss
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {} // swallow taps so they don't fall through to the scrim
                    .background(panelBg)
                    .border(1.dp, panelBorder)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text          = "BLUETOOTH",
                        color         = labelColor,
                        letterSpacing = 2.sp,
                        fontSize      = 14.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.Close, contentDescription = "Close", tint = subtleColor,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                indication        = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick           = onDismiss
                            )
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (devices.isEmpty()) {
                    Text(
                        text     = "No paired devices. Pair one from system Bluetooth settings first.",
                        color    = subtleColor,
                        fontSize = 12.sp
                    )
                } else {
                    devices.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text     = name,
                                color    = labelColor,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text     = "CONNECT",
                                color    = accent,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                                modifier = Modifier
                                    .clickable(
                                        indication        = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick           = { onConnect(name) }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            Text(
                                text     = "DISCONNECT",
                                color    = subtleColor,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                                modifier = Modifier
                                    .clickable(
                                        indication        = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick           = { onDisconnect(name) }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        HorizontalDivider(color = panelBorder)
                    }
                }
            }
        }
    }
}

/**
 * Themed WiFi status card — SSID, signal, connected/not. Android hasn't let
 * regular apps drive an actual "pick a network, enter a password, connect"
 * flow since API 29 (a security restriction, not a gap this app could close
 * even with more work — the same class of wall as the CarPlay-rooting
 * question elsewhere in this project), so this can only ever be a status
 * view. The one action it offers hands off to Settings.Panel.ACTION_WIFI —
 * the system's own bottom-sheet panel — for the rare case of actually
 * adding or switching networks, which is the one thing this screen can't do
 * on-theme no matter how it's built.
 */
@Composable
private fun WifiPanel(
    accent: Color,
    isDayMode: Boolean,
    onOpenSystemPanel: () -> Unit,
    onDismiss: () -> Unit
) {
    val panelBg     = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val panelBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val labelColor  = if (isDayMode) Color(0xFF111111) else Color(0xFFEFEFEF)
    val subtleColor = if (isDayMode) Color(0xFF888888) else Color(0xFF666666)

    val context = androidx.compose.ui.platform.LocalContext.current
    val wifiManager = remember {
        context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    }
    var connected by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf<String?>(null) }
    var signalLevel by remember { mutableStateOf(0) } // 0..4

    LaunchedEffect(Unit) {
        while (true) {
            val enabled = wifiManager?.isWifiEnabled == true
            @Suppress("DEPRECATION")
            val info = if (enabled) wifiManager?.connectionInfo else null
            val rawSsid = info?.ssid?.trim('"')
            connected = enabled && rawSsid != null && rawSsid != "<unknown ssid>" && rawSsid.isNotBlank()
            ssid = if (connected) rawSsid else null
            @Suppress("DEPRECATION")
            signalLevel = if (connected && info != null) {
                runCatching { android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5) }.getOrDefault(0)
            } else 0
            kotlinx.coroutines.delay(2000)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick           = onDismiss
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {} // swallow taps so they don't fall through to the scrim
                    .background(panelBg)
                    .border(1.dp, panelBorder)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text = "WIFI", color = labelColor, letterSpacing = 2.sp, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.Close, contentDescription = "Close", tint = subtleColor,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                indication        = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick           = onDismiss
                            )
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text     = ssid ?: "Not connected",
                    color    = if (connected) labelColor else subtleColor,
                    fontSize = 18.sp
                )
                if (connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text     = when (signalLevel) {
                            4, 3 -> "Strong signal"
                            2    -> "Fair signal"
                            else -> "Weak signal"
                        },
                        color    = subtleColor,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = panelBorder)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick           = onOpenSystemPanel
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text     = "ADD OR SWITCH NETWORK",
                        color    = accent,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private data class LayoutPresetInfo(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val layout: List<WidgetConfig>
)

private val LAYOUT_PRESETS = listOf(
    LayoutPresetInfo(
        label       = "DEFAULT",
        description = "Clock, weather, compass & now playing — balanced grid",
        icon        = Icons.Default.GridView,
        layout      = com.openlauncher.app.data.defaultWidgetLayout()
    ),
    LayoutPresetInfo(
        label       = "SPLIT PANEL",
        description = "Big media card on the left, weather & clock stacked on the right",
        icon        = Icons.Default.ViewQuilt,
        layout      = com.openlauncher.app.data.splitPanelWidgetLayout()
    )
)

@Composable
private fun LayoutPresetDialog(
    accent: Color,
    isDayMode: Boolean,
    onApply: (List<WidgetConfig>) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg    = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF0C0C0C)
    val dialogBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    val titleColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF555555)
    val closeColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF444444)
    val labelColor  = if (isDayMode) Color(0xFF212529) else Color(0xFFDDDDDD)
    val descColor   = if (isDayMode) Color(0xFF6C757D) else Color(0xFF888888)
    val cardBg      = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val cardBorder  = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .widthIn(min = 280.dp, max = 420.dp)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = "LAYOUT PRESETS",
                    color         = titleColor,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = closeColor, modifier = Modifier.size(14.dp))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LAYOUT_PRESETS.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(cardBg)
                            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
                            .clickable { onApply(preset.layout) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(preset.icon, null, tint = accent, modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                text          = preset.label,
                                color         = labelColor,
                                fontSize      = 11.sp,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text     = preset.description,
                                color    = descColor,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetContextMenu(
    widgetId: String,
    accent: Color,
    clockStyle: ClockStyle,
    vitalsAsBars: Boolean,
    speedometerDigitalOnly: Boolean,
    locationDetailLevel: com.openlauncher.app.data.LocationDetailLevel = com.openlauncher.app.data.LocationDetailLevel.NEIGHBORHOOD,
    carPlayPackage: String = "",
    androidAutoPackage: String = "",
    pipAppPackage: String = "",
    use24HourFormat: Boolean = true,
    showSunriseSunset: Boolean = true,
    showWindSpeed: Boolean = true,
    showFeelsLike: Boolean = true,
    showRainChance: Boolean = true,
    showNowPlayingSourceBadge: Boolean = true,
    isDayMode: Boolean,
    onResize: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: () -> Unit,
    onClearPip: () -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit,
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit,
    onSetLocationDetailLevel: (com.openlauncher.app.data.LocationDetailLevel) -> Unit = {},
    onToggle: (AppSettings.() -> AppSettings) -> Unit = {},
    onDismiss: () -> Unit
) {
    val menuBg    = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF111111)
    val menuBorder = if (isDayMode) Color(0xFFDDE1E5) else Color(0xFF1E1E1E)
    val menuDivider = if (isDayMode) Color(0xFFF1F3F5) else Color(0xFF1A1A1A)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(menuBg)
                .border(1.dp, menuBorder, RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(200.dp)
        ) {
            val inactiveMenuTint = if (isDayMode) Color(0xFF777777) else Color(0xFF555555)
            ContextRow("RESIZE", Icons.Default.OpenWith, accent, onResize, isDayMode = isDayMode)
            if (widgetId == "CLOCK") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL",
                    icon    = Icons.Default.Schedule,
                    tint    = if (clockStyle == ClockStyle.DIGITAL) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.DIGITAL); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "ANALOG",
                    icon    = Icons.Default.Watch,
                    tint    = if (clockStyle == ClockStyle.ANALOG) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.ANALOG); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (use24HourFormat) "24-HOUR" else "12-HOUR",
                    icon    = Icons.Default.Schedule,
                    tint    = accent,
                    onClick = { onToggle { copy(use24HourFormat = !use24HourFormat) } },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (showSunriseSunset) "SUNRISE/SUNSET  ON" else "SUNRISE/SUNSET  OFF",
                    icon    = Icons.Default.WbSunny,
                    tint    = if (showSunriseSunset) accent else inactiveMenuTint,
                    onClick = { onToggle { copy(showSunriseSunset = !showSunriseSunset) } },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "VITALS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL GAUGES",
                    icon    = Icons.Default.Adjust,
                    tint    = if (!vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "BARS VIEW",
                    icon    = Icons.Default.FormatAlignLeft,
                    tint    = if (vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "SPEEDOMETER") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL TRACK",
                    icon    = Icons.Default.Speed,
                    tint    = if (!speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL ONLY",
                    icon    = Icons.Default.Dialpad,
                    tint    = if (speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "LOCATION") {
                val levels = com.openlauncher.app.data.LocationDetailLevel.entries
                levels.forEach { level ->
                    HorizontalDivider(color = menuDivider)
                    ContextRow(
                        label   = when (level) {
                            com.openlauncher.app.data.LocationDetailLevel.NEIGHBORHOOD -> "NEIGHBORHOOD"
                            com.openlauncher.app.data.LocationDetailLevel.CITY         -> "CITY"
                            com.openlauncher.app.data.LocationDetailLevel.REGION       -> "REGION / STATE"
                        },
                        icon    = Icons.Default.Explore,
                        tint    = if (locationDetailLevel == level) accent else inactiveMenuTint,
                        onClick = { onSetLocationDetailLevel(level); onDismiss() },
                        isDayMode = isDayMode
                    )
                }
            }
            if (widgetId == "WEATHER") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (showWindSpeed) "WIND SPEED  ON" else "WIND SPEED  OFF",
                    icon    = Icons.Default.Air,
                    tint    = if (showWindSpeed) accent else inactiveMenuTint,
                    onClick = { onToggle { copy(showWindSpeed = !showWindSpeed) } },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (showFeelsLike) "FEELS LIKE  ON" else "FEELS LIKE  OFF",
                    icon    = Icons.Default.Thermostat,
                    tint    = if (showFeelsLike) accent else inactiveMenuTint,
                    onClick = { onToggle { copy(showFeelsLike = !showFeelsLike) } },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (showRainChance) "RAIN CHANCE  ON" else "RAIN CHANCE  OFF",
                    icon    = Icons.Default.WaterDrop,
                    tint    = if (showRainChance) accent else inactiveMenuTint,
                    onClick = { onToggle { copy(showRainChance = !showRainChance) } },
                    isDayMode = isDayMode
                )
            }
            if (widgetId == "NOW_PLAYING") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = if (showNowPlayingSourceBadge) "SOURCE BADGE  ON" else "SOURCE BADGE  OFF",
                    icon    = Icons.Default.Apps,
                    tint    = if (showNowPlayingSourceBadge) accent else inactiveMenuTint,
                    onClick = { onToggle { copy(showNowPlayingSourceBadge = !showNowPlayingSourceBadge) } },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow("ASSIGN CARPLAY APP",      Icons.Default.PhoneAndroid,  accent, onAssignCarPlay, isDayMode = isDayMode)
                if (carPlayPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow("CLEAR CARPLAY APP", Icons.Default.PhoneAndroid, Color(0xFF884444), onClearCarPlay, isDayMode = isDayMode)
                }
                HorizontalDivider(color = menuDivider)
                ContextRow("ASSIGN ANDROID AUTO APP", Icons.Default.DirectionsCar, accent, onAssignAndroidAuto, isDayMode = isDayMode)
                if (androidAutoPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow("CLEAR ANDROID AUTO APP", Icons.Default.DirectionsCar, Color(0xFF884444), onClearAndroidAuto, isDayMode = isDayMode)
                }
            }

        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    isDayMode: Boolean = false
) {
    val finalTint = if (isDayMode) {
        if (tint == Color(0xFF884444)) {
            tint
        } else if (tint == Color(0xFF777777)) {
            Color(0xFF888888)
        } else {
            Color(0xFF111111)
        }
    } else {
        tint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = finalTint, modifier = Modifier.size(16.dp))
        Text(label, color = finalTint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun WidgetResizeDialog(
    config: WidgetConfig,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (spanX: Int, spanY: Int) -> Unit
) {
    var spanX by remember { mutableStateOf(config.spanX) }
    var spanY by remember { mutableStateOf(config.spanY) }

    val maxSpanX = GRID_COLS - config.gridX
    val maxSpanY = GRID_ROWS - config.gridY

    val dialogBg     = if (isDayMode) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.background
    val dialogText   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val cancelColor  = if (isDayMode) Color(0xFF6C757D) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text          = config.id.replace('_', ' '),
                color         = dialogText,
                fontSize      = 11.sp,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SpanRow(label = "WIDTH",  value = spanX, min = 1, max = maxSpanX, accent = accent, isDayMode = isDayMode) { spanX = it }
                SpanRow(label = "HEIGHT", value = spanY, min = 1, max = maxSpanY, accent = accent, isDayMode = isDayMode) { spanY = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(spanX, spanY) }) {
                Text("APPLY", color = accent, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = cancelColor, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        },
        containerColor    = dialogBg,
        titleContentColor = dialogText,
        textContentColor  = dialogText
    )
}

@Composable
private fun SpanRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    accent: Color,
    isDayMode: Boolean,
    onChange: (Int) -> Unit
) {
    val textColor   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor    = if (isDayMode) Color(0xFF495057) else Color(0xFF666666)
    val disabledC   = if (isDayMode) Color(0xFFCED4DA) else Color(0xFF333333)
    val inactiveBg  = if (isDayMode) Color(0xFFE9ECEF) else Color(0xFF2A2A2A)
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text          = label,
            color         = dimColor,
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier.width(52.dp)
        )
        IconButton(
            onClick  = { if (value > min) onChange(value - 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Remove, null,
                tint     = if (value > min) textColor else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text      = "$value",
            color     = textColor,
            fontSize  = 16.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier.width(24.dp)
        )
        IconButton(
            onClick  = { if (value < max) onChange(value + 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Add, null,
                tint     = if (value < max) accent else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(max) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 10.dp)
                        .background(
                            if (i < value) accent.copy(alpha = 0.7f) else inactiveBg,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

// ── Widget Library ────────────────────────────────────────────────────────────

@Composable
private fun WidgetLibraryDialog(
    settings: AppSettings,
    accent: Color,
    isDayMode: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg    = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF0C0C0C)
    val dialogBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    val titleColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF555555)
    val closeColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF444444)

    val activeIds = buildSet {
        if (settings.showClock) add("CLOCK")
        if (settings.showWeather) add("WEATHER")
        if (settings.showNowPlaying) add("NOW_PLAYING")
        if (settings.showTelemetry) add("TELEMETRY")
        if (settings.showAltimeter) add("ALTIMETER")
        if (settings.showSpeedometer) add("SPEEDOMETER")
        if (settings.showVitals) add("VITALS")
        if (settings.showTripTracker) add("TRIP_TRACKER")
        if (settings.showSoundboard) add("SOUNDBOARD")
        if (settings.showFuelLog) add("FUEL_LOG")
        if (settings.showQuickToggles) add("QUICK_TOGGLES")
        if (settings.showLocation) add("LOCATION")
    }
    val canAdd = canAddWidget(settings)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .widthIn(min = 320.dp, max = 520.dp)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = "WIDGET LIBRARY",
                    color         = titleColor,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = closeColor, modifier = Modifier.size(14.dp))
                }
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                items(ALL_WIDGET_TYPES) { info ->
                    val isActive = info.id in activeIds
                    WidgetLibraryCard(
                        info     = info,
                        isActive = isActive,
                        canAdd   = canAdd,
                        accent   = accent,
                        isDayMode = isDayMode,
                        onToggle = { if (isActive) onRemove(info.id) else onAdd(info.id) }
                    )
                }
            }

            if (!canAdd) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text          = "ALL ${GRID_COLS * GRID_ROWS} CELLS OCCUPIED — REMOVE A WIDGET TO ADD MORE",
                    color         = if (isDayMode) Color(0xFFE03131) else Color(0xFF3A3A3A),
                    fontSize      = 8.sp,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.fillMaxWidth(),
                    textAlign     = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WidgetLibraryCard(
    info: WidgetTypeInfo,
    isActive: Boolean,
    canAdd: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onToggle: () -> Unit
) {
    val enabled    = isActive || canAdd
    val cardBorder = if (isActive) accent else if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val cardBg     = if (isActive) accent.copy(alpha = 0.15f) else if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val iconTint   = if (isActive) accent else if (isDayMode) Color(0xFF495057) else Color(0xFF333333)
    val labelColor = if (isActive) accent else if (isDayMode) Color(0xFF212529) else Color(0xFF3A3A3A)
    // Amber/yellow reads clearly against edit mode's grey card shading without
    // being confused for the active-state accent color or an error state.
    val networkTagColor = Color(0xFFE8A93D)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(info.icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(5.dp))
            Text(
                text          = info.label,
                color         = labelColor,
                fontSize      = 7.sp,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center,
                maxLines      = 2,
                lineHeight    = 9.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text          = when {
                    isActive -> "ACTIVE"
                    !canAdd  -> "FULL"
                    else     -> "ADD"
                },
                color         = when {
                    isActive -> accent.copy(alpha = 0.75f)
                    !canAdd  -> if (isDayMode) Color(0xFFADB5BD) else Color(0xFF282828)
                    else     -> if (isDayMode) Color(0xFF495057) else Color(0xFF3A3A3A)
                },
                fontSize      = 6.sp,
                letterSpacing = 1.sp,
                textAlign     = TextAlign.Center
            )
            if (info.requiresNetwork) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text          = "NEEDS DATA",
                    color         = networkTagColor,
                    fontSize      = 5.sp,
                    letterSpacing = 0.5.sp,
                    textAlign     = TextAlign.Center
                )
            }
        }
        if (info.requiresNetwork) {
            Icon(
                imageVector        = Icons.Default.Wifi,
                contentDescription = "Requires internet connection",
                tint               = networkTagColor,
                modifier           = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(10.dp)
            )
        }
    }
}
