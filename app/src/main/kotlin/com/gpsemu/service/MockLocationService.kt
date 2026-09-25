package com.gpsemu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gpsemu.core.GeoPoint
import com.gpsemu.core.LocationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds a fixed position into the system location providers.
 *
 * The service registers GPS and network as test providers once, then pushes the
 * current point to both once a second. Teleporting does not restart anything — it
 * only swaps [point], and the running loop picks up the new value on its next tick.
 * Re-registering the providers on every teleport used to drop the fix for a moment
 * and made maps apps fall back to their last known position.
 */
class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var point = GeoPoint(0.0, 0.0)
    @Volatile private var emitting = false

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "onCreate START")
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Restore last active point so a START_STICKY restart doesn't land on (0,0)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) {
            val lat = prefs.getString("lat", null)?.toDoubleOrNull()
            val lon = prefs.getString("lon", null)?.toDoubleOrNull()
            val alt = prefs.getString("alt", "0")?.toDoubleOrNull() ?: 0.0
            if (lat != null && lon != null) point = GeoPoint(lat, lon, alt)
        }
        Log.e(TAG, "onCreate point=${point.latitude},${point.longitude}")

        // Auto-register as mock_location_app and enable high-accuracy GPS (requires WRITE_SECURE_SETTINGS)
        runCatching {
            Settings.Secure.putString(contentResolver, "mock_location_app", packageName)
        }.onFailure { Log.e(TAG, "mock_location_app write failed: ${it.message}") }
        runCatching {
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY,
            )
        }.onFailure { Log.e(TAG, "location_mode write failed: ${it.message}") }

        createChannel()
        Log.e(TAG, "calling startInForeground")
        try {
            startInForeground()
            Log.e(TAG, "startInForeground OK")
        } catch (e: Exception) {
            Log.e(TAG, "startInForeground FAILED: ${e.javaClass.simpleName}: ${e.message}")
            // Fall back to non-typed foreground (Android < 14 path)
            try {
                startForeground(NOTIF_ID, buildNotification())
                Log.e(TAG, "startForeground fallback OK")
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback also FAILED: ${e2.message}")
            }
        }
        Log.e(TAG, "calling registerProviders")
        registerProviders()
        Log.e(TAG, "onCreate DONE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "onStartCommand action=${intent?.action} emitting=$emitting")
        if (intent?.action == ACTION_STOP) {
            saveActive(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val newPoint = GeoPoint.fromIntent(intent, point)
        val teleported = newPoint != point
        point = newPoint
        Log.e(TAG, "onStartCommand point=${point.latitude},${point.longitude} teleported=$teleported")
        saveActive(true)

        // Re-assert providers on every teleport (MEmu can disable them)
        PROVIDERS.forEach { runCatching { locationManager.setTestProviderEnabled(it, true) } }

        if (!emitting) {
            emitting = true
            Log.e(TAG, "launching emitLoop")
            scope.launch { emitLoop() }
        } else if (teleported) {
            // Burst-emit the new position immediately so the map snaps rather than flies
            Log.e(TAG, "launching emitBurst")
            scope.launch { emitBurst(newPoint) }
        }
        return START_STICKY
    }

    // Fires BURST_COUNT rapid updates so the map settles on the new point instantly
    private suspend fun emitBurst(pt: GeoPoint) {
        repeat(BURST_COUNT) {
            pushPoint(pt)
            delay(BURST_INTERVAL_MS)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun emitLoop() {
        Log.e(TAG, "emitLoop STARTED")
        var count = 0L
        while (true) {
            pushPoint(point)
            count++
            if (count % 50 == 0L) {
                Log.e(TAG, "emitLoop tick $count lat=${point.latitude}")
                // Read back what the system actually delivers to apps
                PROVIDERS.forEach { provider ->
                    runCatching {
                        val loc = locationManager.getLastKnownLocation(provider)
                        Log.e(TAG, "getLastKnown($provider): lat=${loc?.latitude} lon=${loc?.longitude} mock=${loc?.isFromMockProvider} et=${loc?.elapsedRealtimeNanos}")
                    }.onFailure { Log.e(TAG, "getLastKnown($provider) err: ${it.message}") }
                }
            }
            delay(EMIT_INTERVAL_MS)
        }
    }

    private var pushLogCount = 0L

    private fun pushPoint(pt: GeoPoint) {
        pushLogCount++
        val verbose = (pushLogCount == 1L || pushLogCount % 50 == 0L)
        PROVIDERS.forEach { provider ->
            runCatching {
                locationManager.setTestProviderLocation(
                    provider,
                    LocationFactory.stationary(provider, pt),
                )
                if (verbose) Log.e(TAG, "setTestProviderLocation $provider OK #$pushLogCount lat=${pt.latitude}")
            }.onFailure { Log.e(TAG, "setTestProviderLocation $provider FAIL #$pushLogCount: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    // ── Test providers ─────────────────────────────────────────────────────

    /**
     * Registers every provider independently: a ROM that refuses one of them (fused
     * is the likely candidate) must not stop the others from being set up.
     */
    private fun registerProviders() {
        PROVIDERS.forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
                .onSuccess { Log.e(TAG, "removeTestProvider $provider OK") }
                .onFailure { Log.e(TAG, "removeTestProvider $provider FAIL: ${it.message}") }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val props = ProviderProperties.Builder()
                        .setHasNetworkRequirement(false)
                        .setHasSatelliteRequirement(false)
                        .setHasCellRequirement(false)
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build()
                    locationManager.addTestProvider(provider, props)
                    Log.e(TAG, "addTestProvider $provider (ProviderProperties API) OK")
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        Criteria.POWER_HIGH, Criteria.ACCURACY_FINE,
                    )
                    Log.e(TAG, "addTestProvider $provider (legacy API) OK")
                }
                locationManager.setTestProviderEnabled(provider, true)
                Log.e(TAG, "setTestProviderEnabled $provider OK")
            }.onFailure { Log.e(TAG, "provider $provider FAIL: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun unregisterProviders() {
        PROVIDERS.forEach { provider ->
            runCatching {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterProviders()
        saveActive(false)
        super.onDestroy()
    }

    private fun saveActive(active: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean("active", active)
            if (active) {
                putString("lat", point.latitude.toString())
                putString("lon", point.longitude.toString())
                putString("alt", point.altitude.toString())
            }
            commit()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────────────

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    }
            )
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "GpsEmu"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "gpsemu"
        private const val EMIT_INTERVAL_MS = 200L
        private const val BURST_COUNT = 8
        private const val BURST_INTERVAL_MS = 50L

        const val ACTION_STOP = "com.gpsemu.STOP"
        const val PREFS_NAME = "gps_emu_prefs"

        /**
         * "fused" is where most modern apps actually read from — Play Services'
         * FusedLocationProviderClient and, since Android 12, LocationManager.FUSED_PROVIDER.
         * The constant is @hide on older releases, so it is spelled out. Feeding only
         * gps and network leaves those apps on a stale position.
         *
         * Registering it can fail depending on the ROM; [registerProviders] tolerates that.
         */
        private const val FUSED_PROVIDER = "fused"

        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            FUSED_PROVIDER,
        )

        fun start(ctx: Context, point: GeoPoint) {
            ctx.startForegroundService(
                point.putInto(Intent(ctx, MockLocationService::class.java))
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, MockLocationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
