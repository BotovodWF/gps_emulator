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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var point = GeoPoint(0.0, 0.0)
    @Volatile private var currentSpeedMps = 0f
    @Volatile private var emitting = false
    @Volatile private var routeActive = false
    @Volatile private var routePaused = false
    private var routeJob: Job? = null
    private var lastGlobalWriteMs = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        restoreSavedPoint()
        applySystemSettings()
        createChannel()
        startInForeground()
        registerProviders()
    }

    private fun restoreSavedPoint() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!p.getBoolean("active", false)) return
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return
        val alt = p.getString("alt", "0")?.toDoubleOrNull() ?: 0.0
        val brg = p.getString("bearing", "0")?.toFloatOrNull() ?: 0f
        point = GeoPoint(lat, lon, alt, brg)
    }

    private fun applySystemSettings() {
        runCatching { Settings.Secure.putString(contentResolver, "mock_location_app", packageName) }
        runCatching {
            Settings.Secure.putInt(contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP    -> handleStop()
            ACTION_PAUSE   -> handlePause()
            ACTION_RESUME  -> handleResume()
            ACTION_START_ROUTE -> handleStartRoute(intent)
            else           -> handleTeleport(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterProviders()
        saveState(active = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Intent handlers ────────────────────────────────────────────────────

    private fun handleStop(): Int {
        cancelRoute()
        saveState(active = false)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun handlePause(): Int {
        routePaused = true
        currentSpeedMps = 0f
        return START_STICKY
    }

    private fun handleResume(): Int {
        routePaused = false
        return START_STICKY
    }

    private fun handleStartRoute(intent: Intent): Int {
        val json  = intent.getStringExtra(EXTRA_WAYPOINTS_JSON) ?: return START_STICKY
        val speed = intent.getFloatExtra(EXTRA_SPEED_KMH, 60f)
        val time  = intent.getIntExtra(EXTRA_TIME_SEC, 0)
        val loop  = intent.getBooleanExtra(EXTRA_LOOP, false)
        val wps   = parseWaypoints(json)
        if (wps.size < 2) return START_STICKY

        cancelRoute()
        point = wps[0]
        routeActive = true
        routePaused = false
        saveState(active = true)
        ensureEmitting()
        routeJob = scope.launch { playRoute(wps, speed, time, loop) }
        return START_STICKY
    }

    private fun handleTeleport(intent: Intent?): Int {
        val newPoint = GeoPoint.fromIntent(intent, point)
        val moved = newPoint != point
        point = newPoint
        currentSpeedMps = 0f
        saveState(active = true)
        PROVIDERS.forEach { runCatching { locationManager.setTestProviderEnabled(it, true) } }
        if (!emitting) {
            ensureEmitting()
        } else if (moved) {
            scope.launch { emitBurst(newPoint) }
        }
        return START_STICKY
    }

    // ── Route playback ─────────────────────────────────────────────────────

    private suspend fun playRoute(
        waypoints: List<GeoPoint>,
        speedKmh: Float,
        totalTimeSec: Int,
        loop: Boolean,
    ) {
        val totalDist = waypoints.zipWithNext { a, b -> haversine(a, b) }.sum()
        do {
            for (i in 0 until waypoints.size - 1) {
                if (!routeActive) return
                traverseSegment(waypoints[i], waypoints[i + 1], speedKmh, totalTimeSec, totalDist)
            }
            // Settle on the last waypoint with zero speed
            val last = waypoints.last()
            val prev = waypoints[waypoints.size - 2]
            point = last.copy(bearing = calcBearing(prev, last))
            currentSpeedMps = 0f
            publishGlobal()
            if (!loop) break
            delay(600)
        } while (routeActive)

        routeActive = false
        currentSpeedMps = 0f
    }

    private suspend fun traverseSegment(
        from: GeoPoint, to: GeoPoint,
        speedKmh: Float, totalTimeSec: Int, totalDist: Double,
    ) {
        val dist    = haversine(from, to)
        val bearing = calcBearing(from, to)
        val durationMs: Long = when {
            totalTimeSec > 0 && totalDist > 0 ->
                (dist / totalDist * totalTimeSec * 1000.0).toLong().coerceAtLeast(200L)
            speedKmh > 0 ->
                (dist / (speedKmh / 3.6) * 1000.0).toLong().coerceAtLeast(200L)
            else -> 5_000L
        }
        val segSpeedMps = (dist / (durationMs / 1000.0)).toFloat()
        val startMs = System.currentTimeMillis()
        val UPDATE_MS = 150L

        while (System.currentTimeMillis() < startMs + durationMs) {
            if (!routeActive) return
            if (routePaused) { currentSpeedMps = 0f; delay(100); continue }

            currentSpeedMps = segSpeedMps
            val t = ((System.currentTimeMillis() - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
            point = GeoPoint(
                latitude  = from.latitude  + (to.latitude  - from.latitude)  * t,
                longitude = from.longitude + (to.longitude - from.longitude) * t,
                altitude  = from.altitude,
                bearing   = bearing,
            )
            maybePublishGlobal()
            delay(UPDATE_MS)
        }
    }

    // ── Emission helpers ───────────────────────────────────────────────────

    private fun ensureEmitting() {
        if (emitting) return
        emitting = true
        scope.launch { emitLoop() }
    }

    private suspend fun emitBurst(pt: GeoPoint) {
        repeat(BURST_COUNT) { pushPoint(pt); delay(BURST_INTERVAL_MS) }
    }

    private suspend fun emitLoop() {
        var tick = 0L
        while (true) {
            pushPoint(point)
            if (++tick % 50L == 0L) Log.d(TAG, "tick=$tick lat=${point.latitude} spd=$currentSpeedMps")
            delay(EMIT_INTERVAL_MS)
        }
    }

    private fun pushPoint(pt: GeoPoint) {
        PROVIDERS.forEach { provider ->
            runCatching {
                val loc = if (currentSpeedMps > 0.1f)
                    LocationFactory.moving(provider, pt, currentSpeedMps)
                else
                    LocationFactory.stationary(provider, pt)
                locationManager.setTestProviderLocation(provider, loc)
            }
        }
    }

    // ── Settings.Global IPC (throttled) ────────────────────────────────────

    private fun maybePublishGlobal() {
        val now = System.currentTimeMillis()
        if (now - lastGlobalWriteMs < GLOBAL_WRITE_THROTTLE_MS) return
        publishGlobal()
        lastGlobalWriteMs = now
    }

    private fun publishGlobal() {
        runCatching {
            Settings.Global.putString(contentResolver, "gpsemu_active", "1")
            Settings.Global.putString(contentResolver, "gpsemu_lat",     point.latitude.toString())
            Settings.Global.putString(contentResolver, "gpsemu_lon",     point.longitude.toString())
            Settings.Global.putString(contentResolver, "gpsemu_alt",     point.altitude.toString())
            Settings.Global.putString(contentResolver, "gpsemu_bearing", point.bearing.toString())
        }
    }

    private fun saveState(active: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean("active", active)
            if (active) {
                putString("lat",     point.latitude.toString())
                putString("lon",     point.longitude.toString())
                putString("alt",     point.altitude.toString())
                putString("bearing", point.bearing.toString())
            }
            apply()
        }
        if (active) {
            publishGlobal()
        } else {
            // Signal the Xposed hook to stop injecting and release lastValidPoint
            runCatching {
                Settings.Global.putString(contentResolver, "gpsemu_active", "0")
            }
        }
    }

    // ── Route utilities ────────────────────────────────────────────────────

    private fun cancelRoute() {
        routeJob?.cancel(); routeJob = null
        routeActive = false; routePaused = false; currentSpeedMps = 0f
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val R = 6_371_000.0
        val φ1 = Math.toRadians(a.latitude);  val φ2 = Math.toRadians(b.latitude)
        val Δφ = Math.toRadians(b.latitude  - a.latitude)
        val Δλ = Math.toRadians(b.longitude - a.longitude)
        val s = sin(Δφ / 2).pow(2) + cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
        return R * 2 * atan2(sqrt(s), sqrt(1 - s))
    }

    private fun calcBearing(from: GeoPoint, to: GeoPoint): Float {
        val φ1 = Math.toRadians(from.latitude);  val φ2 = Math.toRadians(to.latitude)
        val Δλ = Math.toRadians(to.longitude - from.longitude)
        val y  = sin(Δλ) * cos(φ2)
        val x  = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return ((Math.toDegrees(atan2(y, x)).toFloat() + 360) % 360)
    }

    private fun parseWaypoints(json: String): List<GeoPoint> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoPoint(o.getDouble("lat"), o.getDouble("lon"), o.optDouble("alt", 0.0))
        }
    }.getOrElse { Log.w(TAG, "parseWaypoints failed: ${it.message}"); emptyList() }

    // ── Provider management ────────────────────────────────────────────────

    private fun registerProviders() {
        PROVIDERS.forEach { name ->
            runCatching { locationManager.removeTestProvider(name) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val props = ProviderProperties.Builder()
                        .setHasNetworkRequirement(false).setHasSatelliteRequirement(false)
                        .setHasCellRequirement(false).setHasMonetaryCost(false)
                        .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE).build()
                    locationManager.addTestProvider(name, props)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(name, false, false, false, false,
                        true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE)
                }
                locationManager.setTestProviderEnabled(name, true)
            }.onFailure { Log.w(TAG, "provider $name register failed: ${it.message}") }
        }
    }

    private fun unregisterProviders() {
        PROVIDERS.forEach { name ->
            runCatching {
                locationManager.setTestProviderEnabled(name, false)
                locationManager.removeTestProvider(name)
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun startInForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        }.onFailure {
            runCatching { startForeground(NOTIF_ID, buildNotification()) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); setSound(null, null)
                enableLights(false); enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Service")
            .setContentText(if (routeActive) "Маршрут активен" else "Работает")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    // ── Companion ──────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GpsEmu"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "gpsemu"
        private const val EMIT_INTERVAL_MS = 200L
        private const val BURST_COUNT = 8
        private const val BURST_INTERVAL_MS = 50L
        private const val GLOBAL_WRITE_THROTTLE_MS = 300L  // limit Settings.Global IPC during route

        const val ACTION_STOP        = "com.gpsemu.STOP"
        const val ACTION_PAUSE       = "com.gpsemu.PAUSE"
        const val ACTION_RESUME      = "com.gpsemu.RESUME"
        const val ACTION_START_ROUTE = "com.gpsemu.START_ROUTE"
        const val EXTRA_WAYPOINTS_JSON = "waypoints_json"
        const val EXTRA_SPEED_KMH      = "speed_kmh"
        const val EXTRA_TIME_SEC       = "time_sec"
        const val EXTRA_LOOP           = "loop"
        const val PREFS_NAME = "gps_emu_prefs"

        private const val FUSED_PROVIDER = "fused"
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            FUSED_PROVIDER,
        )

        fun start(ctx: Context, point: GeoPoint) {
            ctx.startForegroundService(point.putInto(Intent(ctx, MockLocationService::class.java)))
        }

        fun startRoute(ctx: Context, waypoints: List<GeoPoint>, speedKmh: Float, timeSec: Int, loop: Boolean) {
            val json = JSONArray().apply {
                waypoints.forEach { pt ->
                    put(JSONObject().put("lat", pt.latitude).put("lon", pt.longitude).put("alt", pt.altitude))
                }
            }.toString()
            ctx.startForegroundService(
                Intent(ctx, MockLocationService::class.java)
                    .setAction(ACTION_START_ROUTE)
                    .putExtra(EXTRA_WAYPOINTS_JSON, json)
                    .putExtra(EXTRA_SPEED_KMH, speedKmh)
                    .putExtra(EXTRA_TIME_SEC, timeSec)
                    .putExtra(EXTRA_LOOP, loop)
            )
        }

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_STOP))

        fun pause(ctx: Context) =
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_PAUSE))

        fun resume(ctx: Context) =
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_RESUME))
    }
}
