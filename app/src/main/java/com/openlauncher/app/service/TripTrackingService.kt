package com.openlauncher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openlauncher.app.MainActivity
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.util.currentDayKey
import com.openlauncher.app.util.haversineDistanceMeters
import com.openlauncher.app.util.isBetterLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps accumulating today's driving distance regardless of which app is in
 * the foreground — a real foreground service, not tied to MainActivity's
 * lifecycle. The ViewModel's own location subscription stops the moment this
 * launcher itself is backgrounded (see MainActivity onStart/onStop), which
 * happens constantly in normal use (Waze full-screen for actual navigation,
 * Spotify, etc.) — this service is what makes "how far have I driven today"
 * mean the whole day's driving, not just the minutes OpenLauncher's own
 * dashboard happened to be on screen.
 *
 * Started once from MainActivity.onCreate() and left running — no user-facing
 * stop control, since the intent is for this to run continuously whenever the
 * unit is powered, same as the rest of this always-on car dashboard.
 */
class TripTrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var locationManager: LocationManager
    private lateinit var settingsRepo: SettingsRepository
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var bestLocation: Location? = null
    private var lastTripLocation: Location? = null
    private var pendingDistanceKm = 0.0 // accumulated since the last DataStore flush
    private var lastFlushMs = 0L

    // Seeded once from DataStore at startup, then kept purely in memory —
    // location callbacks run on the main thread (no Looper passed to
    // requestLocationUpdates), so a blocking DataStore read on every single
    // fix would risk janking this service. Reads happen once, async; writes
    // are fire-and-forget after that.
    private var localDayKey = ""
    private var localTotalKm = 0.0
    private var seeded = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val best = bestLocation
            if (!isBetterLocation(loc.time, loc.accuracy, loc.provider, best?.time, best?.accuracy, best?.provider)) return
            bestLocation = loc
            if (seeded) accumulate(loc)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        // Confirmed on-device: the permission-gate fix on the caller side
        // (MainActivity) wasn't the whole story — this entire block had zero
        // exception handling, and a Service's onCreate() runs on a Binder
        // dispatch the caller's own runCatching around startForegroundService()
        // can never see, let alone catch. Same failure class as onStartCommand
        // below, different call.
        runCatching {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            settingsRepo = SettingsRepository(applicationContext)
            createNotificationChannel()
        }.onFailure {
            // Without a LocationManager/SettingsRepository there's nothing
            // useful this service can do — stop rather than limp along and
            // NPE on first location callback.
            stopSelf()
            return
        }
        scope.launch {
            runCatching {
                val initial = settingsRepo.settingsFlow.first()
                localDayKey = initial.tripDayKey
                localTotalKm = initial.tripDayDistanceKm
            }
            // Seeded either way — falling back to today/zero rather than
            // leaving accumulate() permanently blocked (seeded stays false
            // forever) just because the one-time DataStore read failed.
            seeded = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // foregroundServiceType="location" (manifest) means this throws a
        // SecurityException if location permission isn't currently held —
        // the caller (MainActivity) now gates on that before ever starting
        // this service, but START_STICKY means the OS itself can also
        // restart this service later (e.g. after the process was killed
        // under memory pressure) without going through that gate again. A
        // permission revoked in between would otherwise crash the whole
        // app from inside a callback the caller has no way to guard.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { return START_NOT_STICKY }
        runCatching {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener)
            }
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, locationListener)
            }
        }
        // START_STICKY — if the OS kills this under memory pressure, it
        // restarts on its own rather than silently staying dead for the rest
        // of the drive (a real risk on a 2GB device).
        return START_STICKY
    }

    private fun accumulate(loc: Location) {
        val todayKey = currentDayKey()
        if (localDayKey != todayKey) {
            // New day (or first run) — start today's total from zero rather
            // than carrying over/mixing with a prior day's distance.
            localDayKey = todayKey
            localTotalKm = 0.0
            pendingDistanceKm = 0.0
            lastTripLocation = loc
            scope.launch { settingsRepo.updateSettings { it.copy(tripDayKey = todayKey, tripDayDistanceKm = 0.0) } }
            return
        }
        val last = lastTripLocation
        lastTripLocation = loc
        if (last != null) {
            val deltaMeters = haversineDistanceMeters(last.latitude, last.longitude, loc.latitude, loc.longitude)
            // Low end filters GPS jitter while stationary; high end filters a
            // wild/bad single fix (teleport-style jump) from ever counting.
            if (deltaMeters in 8.0..2000.0 && loc.accuracy <= 50f) {
                pendingDistanceKm += deltaMeters / 1000.0
                localTotalKm += deltaMeters / 1000.0
            }
        }
        // Flushed to DataStore at most every 10s rather than on every GPS
        // tick — flash writes that frequent are unnecessary churn. Worst
        // case on an unexpected process death: up to ~10s of distance since
        // the last flush is lost, not the running total itself.
        val now = System.currentTimeMillis()
        if (pendingDistanceKm > 0.0 && now - lastFlushMs >= 10_000L) {
            lastFlushMs = now
            pendingDistanceKm = 0.0
            val totalToWrite = localTotalKm
            scope.launch { settingsRepo.updateSettings { it.copy(tripDayDistanceKm = totalToWrite) } }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip Tracking",
            NotificationManager.IMPORTANCE_MIN // silent, minimal visibility — required to exist, not meant to be noticed
        ).apply { description = "Tracks total driving distance for the day" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trip tracking active")
            .setContentText("Recording today's driving distance")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
