package com.openlauncher.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.*
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.viewmodel.LauncherViewModel

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // Started once per process, independent of this Activity's own
        // lifecycle from here on — keeps accumulating today's driving
        // distance even while this launcher itself is backgrounded (Waze
        // full-screen, etc.).
        //
        // The old comment here claimed this "no-ops safely via its own
        // runCatching if location permission isn't granted yet" — that was
        // wrong, and confirmed on-device as the real cause of a hard crash
        // on every single launch (even a fresh install) before location
        // permission is granted. This service is manifest-declared
        // foregroundServiceType="location", and Android throws a
        // SecurityException from inside startForeground() if that
        // permission isn't already held — but that throw happens inside the
        // service's own onStartCommand(), dispatched asynchronously by the
        // OS, not synchronously inside this startForegroundService() call.
        // A runCatching wrapped around the call site here literally cannot
        // catch an exception thrown later, on a different dispatch, inside
        // the service itself — hence "safely" was never actually true.
        // Gated the same way WakeWordService already is below: only start
        // if the permission is already held (a prior session), and also
        // started from onComplete() once onboarding grants it fresh.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, com.openlauncher.app.service.TripTrackingService::class.java)
                )
            }
        }

        // Voice "go home" (LauncherViewModel.bringAppToForeground) needs the
        // "display over other apps" permission to work while another app has
        // the screen. Used to auto-launch ACTION_MANAGE_OVERLAY_PERMISSION
        // here to prompt for it — removed: on this unit's vendor Settings app
        // that intent doesn't resolve to an app-specific screen at all, it
        // falls through to their generic WiFi settings page, and was hijacking
        // every single cold boot into that screen instead of the dashboard.
        // The permission was granted manually instead (Settings > Apps >
        // OpenLauncher > Display over other apps) and confirmed "go home"
        // works — nothing left for this app to do at startup.

        // "Hi Sebastian" wake-word listener — only meaningful once mic
        // permission is granted (checked here for the case it was already
        // granted in a prior session; also (re)started from the mic
        // permission launcher below the moment it's first granted, so a
        // fresh install doesn't need an app restart before wake word works).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, com.openlauncher.app.service.WakeWordService::class.java)
                )
            }
        }

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val placeName   by vm.placeName.collectAsStateWithLifecycle()
            val voltage     by vm.voltage.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val isWifi      by vm.isWifi.collectAsStateWithLifecycle()
            val isData      by vm.isData.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            val voiceState      by vm.voiceState.collectAsStateWithLifecycle()
            val voiceTranscript by vm.voiceTranscript.collectAsStateWithLifecycle()
            val voiceReply      by vm.voiceReply.collectAsStateWithLifecycle()
            val availableVoices by vm.availableVoices.collectAsStateWithLifecycle()
            val ttsDebugInfo    by vm.ttsDebugInfo.collectAsStateWithLifecycle()
            val wakeWordDebug   by vm.wakeWordDebug.collectAsStateWithLifecycle()
            val wakeWordPulse   by vm.wakeWordPulse.collectAsStateWithLifecycle()
            val volumeLevel     by vm.volumeLevel.collectAsStateWithLifecycle()
            val micContext = LocalContext.current
            // Bundled with the mic permission request rather than a separate
            // prompt later — BLUETOOTH_CONNECT is needed for "connect to
            // Zoe's phone"-style voice commands (enumerating/connecting
            // paired devices by name), and asking once up front is less
            // disruptive while driving than a second permission dialog
            // interrupting a later command.
            val voicePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (results[Manifest.permission.RECORD_AUDIO] == true) {
                    vm.startVoiceCommand()
                    runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(
                            this@MainActivity, Intent(this@MainActivity, com.openlauncher.app.service.WakeWordService::class.java)
                        )
                    }
                }
            }
            val onStartVoiceCommand: () -> Unit = {
                val micGranted = ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val btGranted = android.os.Build.VERSION.SDK_INT < 31 ||
                    ContextCompat.checkSelfPermission(micContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                if (micGranted && btGranted) {
                    vm.startVoiceCommand()
                } else {
                    val perms = if (android.os.Build.VERSION.SDK_INT >= 31) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                    voicePermissionLauncher.launch(perms)
                }
            }

            var bluetoothPanelOpen by remember { mutableStateOf(false) }
            val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> if (granted) bluetoothPanelOpen = true }
            val onRequestBluetoothPanel: () -> Unit = {
                val granted = android.os.Build.VERSION.SDK_INT < 31 ||
                    ContextCompat.checkSelfPermission(micContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    bluetoothPanelOpen = true
                } else {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            // Settings.Panel.ACTION_WIFI is the system's own bottom-sheet-style
            // panel — the only way to actually add/switch a network (apps
            // haven't been able to drive that flow themselves since API 29).
            // Kept as the fallback action inside the themed WifiPanel rather
            // than the rail's direct tap target, so the common case (glancing
            // at what's already connected) stays on-theme.
            val onOpenSystemWifiPanel: () -> Unit = {
                runCatching {
                    startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            var wifiPanelOpen by remember { mutableStateOf(false) }

            // BluetoothPanel only manages already-paired devices (connect/
            // disconnect) — actually pairing a new one still has to go
            // through the system's own Bluetooth settings screen. Unlike
            // WiFi, there's no Settings.Panel API for Bluetooth at all (only
            // ACTION_WIFI/NFC/INTERNET_CONNECTIVITY/VOLUME exist), so this is
            // just the plain settings intent. Confirmed on-device this
            // unit's vendor Settings app doesn't surface a Bluetooth menu
            // item anywhere, so a plain "go find it yourself" message left
            // no real way in — firing the intent directly can still reach
            // the underlying system screen even when the vendor's own
            // Settings app doesn't link to it.
            val onOpenSystemBluetoothSettings: () -> Unit = {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            // A preset themeId overrides the manually-picked accent/background/font
            // colors below; "custom" (or an unrecognized id) falls through to those.
            val resolvedTheme  = com.openlauncher.app.data.resolveDashboardTheme(settings.themeId, isDayMode)
            val accent         = resolvedTheme?.accent ?: Color(settings.accentColor)
            val bg             = resolvedTheme?.background ?: if (settings.useCustomBackgroundColor) {
                Color(settings.backgroundColor)
            } else {
                if (isDayMode) Color(0xFFEEEEEE) else Color.Black
            }
            val textColor      = resolvedTheme?.ink
                ?: if (isDayMode) Color(0xFF111111) else Color(settings.fontColor)
            val bgGradientEnd  = Color(settings.gradientEndColor)
            val bgBrush        = if (settings.useCustomBackgroundColor && settings.useGradient) {
                val colors = listOf(bg, bgGradientEnd)
                when (settings.gradientDirection) {
                    GradientDirection.TOP_TO_BOTTOM -> androidx.compose.ui.graphics.Brush.verticalGradient(colors)
                    GradientDirection.LEFT_TO_RIGHT -> androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                    GradientDirection.DIAGONAL -> androidx.compose.ui.graphics.Brush.linearGradient(colors)
                    GradientDirection.RADIAL -> androidx.compose.ui.graphics.Brush.radialGradient(colors)
                }
            } else null

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OpenLauncherTheme(
                    accent     = accent,
                    background = bg,
                    textColor  = textColor,
                    fontBold   = settings.fontBold,
                    textScale  = settings.textScale,
                    appFont    = settings.appFont,
                    isDayMode  = isDayMode,
                    useCustomBg = resolvedTheme != null || settings.useCustomBackgroundColor
                ) {
                if (!settings.onboardingCompleted) {
                    OnboardingScreen(
                        accent = accent,
                        onComplete = {
                            vm.updateSettings { copy(onboardingCompleted = true) }
                            // Start location updates immediately upon completion
                            vm.startLocationUpdates()
                            // See the onCreate() comment on this same call —
                            // gated on permission being held right now, since
                            // onboarding may have been skipped ("SKIP FOR
                            // NOW") rather than granted.
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                runCatching {
                                    androidx.core.content.ContextCompat.startForegroundService(
                                        this, Intent(this, com.openlauncher.app.service.TripTrackingService::class.java)
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().let { m ->
                        if (bgBrush != null) m.background(bgBrush) else m.background(bg)
                    }) {
                        // Optional wallpaper layer
                        if (settings.wallpaperUri.isNotEmpty()) {
                            AsyncImage(
                                model              = android.net.Uri.parse(settings.wallpaperUri),
                                contentDescription = null,
                                contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                            Box(modifier = Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = settings.wallpaperDim)))
                        }

                        val isBottomBar    = settings.sidebarPosition == SidebarPosition.BOTTOM
                        val layoutDivColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)

                        val sidebarContent: @Composable () -> Unit = {
                            val sidebarDensity = Density(
                                density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                                fontScale = baseDensity.fontScale
                            )
                            CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                                Sidebar(
                                    currentDest   = nav,
                                    settings      = settings,
                                    isHorizontal  = isBottomBar,
                                    themeAccent   = accent,
                                    themeBg       = resolvedTheme?.background,
                                    installedIconFor = { pkg ->
                                        apps.find { it.packageName == pkg }?.icon
                                    },
                                    onNavigate    = { dest ->
                                        vm.cancelShortcutPicker()
                                        vm.cancelCarPlayPicker()
                                        vm.exitRearrangeMode()
                                        vm.navigate(dest)
                                    },
                                    onShortcutClick = { slot ->
                                        val shortcut = settings.shortcuts[slot]
                                        if (shortcut.packageName.isNotEmpty()) {
                                            vm.launchApp(shortcut.packageName)
                                        }
                                    },
                                    onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                                    onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                                    onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                                    onReorder            = { from, to -> vm.reorderShortcut(from, to) }
                                )
                            }
                        }

                        val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                            // ── Main content pane ─────────────────────────────
                            AnimatedContent(
                                targetState   = nav,
                                transitionSpec = {
                                    fadeIn() + slideInHorizontally { it / 10 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 10 }
                                },
                                modifier = paneModifier,
                                label    = "pane_transition"
                            ) { destination ->
                                when (destination) {
                                    NavDestination.HOME -> HomeScreen(
                                        settings            = settings,
                                        weather             = weather,
                                        nowPlaying          = nowPlaying,
                                        location            = location,
                                        placeName           = placeName,
                                        voltage             = voltage,
                                        bearing             = bearing,
                                        isWifi              = isWifi,
                                        isData              = isData,
                                        isDayMode           = isDayMode,
                                        onPlayPause         = vm::playPause,
                                        onNext              = vm::skipNext,
                                        onPrev              = vm::skipPrev,
                                        onLaunchCarPlay     = { vm.launchApp(settings.carPlayPackage) },
                                        onLaunchAndroidAuto = { vm.launchApp(settings.androidAutoPackage) },
                                        onAssignCarPlay     = { vm.startCarPlayPicker() },
                                        onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                                        onClearCarPlay      = { vm.clearCarPlayApp() },
                                        onClearAndroidAuto  = { vm.clearAndroidAutoApp() },
                                        onAssignPip         = { vm.startPipPicker() },
                                        onClearPip          = { vm.clearPipApp() },
                                        onLaunchPip         = { vm.launchApp(settings.pipAppPackage) },
                                        onTapNowPlaying     = {
                                            val pkg = nowPlaying?.controller?.packageName
                                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                                        },
                                        onUpdateWidget      = { id, sx, sy -> vm.updateWidgetConfig(id, sx, sy) },
                                        onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                                        onAddWidget         = { id -> vm.addWidget(id) },
                                        onRemoveWidget      = { id -> vm.removeWidget(id) },
                                        onApplyPreset       = { preset -> vm.applyLayoutPreset(preset) },
                                        onAddFuelEntry      = { odo, vol, cost -> vm.addFuelEntry(odo, vol, cost) },
                                        onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                                        onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                                        onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                                        onSetLocationDetailLevel = { level ->
                                            vm.updateSettings { copy(locationDetailLevel = level) }
                                            location?.let { vm.fetchPlaceName(it.latitude, it.longitude) }
                                        },
                                        onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                                        hardwareRadio         = hardwareRadio,
                                        onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                                        onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                                        onRadioSeekUp         = { vm.radioSeekUp() },
                                        onRadioSeekDown       = { vm.radioSeekDown() },
                                        onRadioCycleFm        = { vm.radioCycleFm() },
                                        onRadioSwitchAm       = { vm.radioSwitchAm() },
                                        onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                                        onAssignRadio         = { vm.startRadioPicker() },
                                        onUpdate              = { block -> vm.updateSettings(block) },
                                        voiceState            = voiceState,
                                        voiceTranscript       = voiceTranscript,
                                        voiceReply            = voiceReply,
                                        onStartVoiceCommand   = onStartVoiceCommand,
                                        onStopVoiceCommand    = { vm.stopVoiceCommand() },
                                        wakeWordDebug         = wakeWordDebug,
                                        wakeWordPulse         = wakeWordPulse,
                                        volumeLevel           = volumeLevel,
                                        onVolumeUp            = { vm.bumpVolume(up = true) },
                                        onVolumeDown          = { vm.bumpVolume(up = false) },
                                        onOpenSystemWifiPanel    = onOpenSystemWifiPanel,
                                        wifiPanelOpen            = wifiPanelOpen,
                                        onRequestWifiPanel       = { wifiPanelOpen = true },
                                        onDismissWifiPanel       = { wifiPanelOpen = false },
                                        onRequestBluetoothPanel  = onRequestBluetoothPanel,
                                        bluetoothPanelOpen       = bluetoothPanelOpen,
                                        onDismissBluetoothPanel  = { bluetoothPanelOpen = false },
                                        pairedBluetoothDeviceNames = { vm.pairedBluetoothDeviceNames() },
                                        onConnectBluetoothDevice  = { name -> vm.setBluetoothDeviceConnected(name, true) },
                                        onDisconnectBluetoothDevice = { name -> vm.setBluetoothDeviceConnected(name, false) },
                                        onOpenSystemBluetoothSettings = onOpenSystemBluetoothSettings
                                    )

                                    NavDestination.APP_LIBRARY -> AppLibraryScreen(
                                        apps                = apps,
                                        isLoading           = appsLoading,
                                        isPickerMode        = pickerSlot != null,
                                        pickerSlot          = pickerSlot,
                                        isCarPlayPickerMode = appPickerTarget != null,
                                        carPlayPickerLabel  = when (appPickerTarget) {
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> "CHOOSE ANDROID AUTO APP"
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.PIP          -> "CHOOSE PIP APP"
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.RADIO        -> "CHOOSE RADIO APP"
                                            else -> "CHOOSE CARPLAY APP"
                                        },
                                        accent              = accent,
                                        onAppClick          = { app -> vm.launchApp(app.packageName) },
                                        onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                                        onCarPlaySelect     = { app -> vm.assignPickerApp(app) }
                                    )

                                    NavDestination.SETTINGS -> SettingsScreen(
                                        settings = settings,
                                        accent   = accent,
                                        onUpdate = { block -> vm.updateSettings(block) },
                                        onReset  = { vm.resetSettings() },
                                        availableVoices = availableVoices,
                                        ttsDebugInfo    = ttsDebugInfo,
                                        onPreviewVoice  = { name -> vm.previewVoice(name) },
                                        onRetryTts      = { vm.ensureTts() },
                                        hasLocationFix  = location != null,
                                        hasMagnetometer = vm.hasMagnetometer
                                    )
                                }
                            }
                        }

                        if (isBottomBar) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                mainPane(Modifier.weight(1f).fillMaxWidth())
                                androidx.compose.material3.HorizontalDivider(color = layoutDivColor)
                                sidebarContent()
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                val vDivider: @Composable () -> Unit = {
                                    androidx.compose.material3.VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color    = layoutDivColor
                                    )
                                }
                                if (settings.sidebarPosition == SidebarPosition.LEFT) {
                                    sidebarContent()
                                    vDivider()
                                }
                                mainPane(Modifier.weight(1f).fillMaxHeight())
                                if (settings.sidebarPosition == SidebarPosition.RIGHT) {
                                    vDivider()
                                    sidebarContent()
                                }
                            }
                        }
                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshConnectivity()
        vm.refreshMedia()
    }

    override fun onStop() {
        super.onStop()
        vm.stopLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        vm.startLocationUpdates()
    }
}
