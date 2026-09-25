package com.gpsemu.core

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Builds [Location] objects that look like readings from a real GNSS receiver:
 * accuracy that drifts around a plausible mean, a satellite count, HDOP and
 * consistent timestamps. A fix with a hardcoded accuracy of exactly 5.0 m and no
 * satellite extras is trivially recognisable as synthetic.
 */
object LocationFactory {

    /** A fix for a device standing still: zero speed, zero bearing. */
    fun stationary(provider: String, point: GeoPoint): Location =
        Location(provider).apply {
            latitude = point.latitude
            longitude = point.longitude
            altitude = point.altitude
            accuracy = gaussianAccuracy()
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = accuracy * 1.5f
                speedAccuracyMetersPerSecond = 0.1f
                bearingAccuracyDegrees = 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                elapsedRealtimeUncertaintyNanos = 500.0
            }
            extras = satelliteExtras()
            // Clear the mock flag so anti-cheat apps (e.g. Yandex Pro) do not reject this location.
            // LocationManagerService re-sets it on delivery in some ROM versions; clearing it here
            // at least gives the delivery path a chance to pass it through unchanged.
            clearMockFlag(this)
        }

    private fun clearMockFlag(loc: Location) {
        runCatching {
            val f = Location::class.java.getDeclaredField("mIsFromMockProvider")
            f.isAccessible = true
            f.setBoolean(loc, false)
        }
        // Android 12+ uses a different field name in some ROMs
        runCatching {
            val f = Location::class.java.getDeclaredField("mMock")
            f.isAccessible = true
            f.setBoolean(loc, false)
        }
    }

    /** Box-Muller: accuracy jitters around 5 m the way a real fix does. */
    private fun gaussianAccuracy(): Float {
        val u1 = Math.random().coerceAtLeast(1e-10)
        val u2 = Math.random()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        return (5.0 + z * 1.2).toFloat().coerceIn(2.5f, 12f)
    }

    private fun satelliteExtras(): Bundle = Bundle().apply {
        val visible = (8..14).random()
        putInt("satellites", visible)
        putInt("numSatellites", visible)
        putFloat("hdop", 0.8f + Math.random().toFloat() * 0.7f)
    }
}
