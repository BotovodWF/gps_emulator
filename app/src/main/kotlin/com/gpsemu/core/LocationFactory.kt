package com.gpsemu.core

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

object LocationFactory {

    fun stationary(provider: String, point: GeoPoint): Location =
        build(provider, point, 0f)

    fun moving(provider: String, point: GeoPoint, speedMps: Float): Location =
        build(provider, point, speedMps)

    private fun build(provider: String, point: GeoPoint, speedMps: Float): Location =
        Location(provider).apply {
            latitude = point.latitude
            longitude = point.longitude
            altitude = point.altitude
            accuracy = gaussianAccuracy()
            speed = speedMps
            bearing = point.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = accuracy * 1.5f
                speedAccuracyMetersPerSecond = if (speedMps > 0f) 0.5f else 0.1f
                bearingAccuracyDegrees = if (speedMps > 0f) 5f else 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                elapsedRealtimeUncertaintyNanos = 500.0
            }
            extras = satelliteExtras()
            clearMockFlag(this)
        }

    private fun clearMockFlag(loc: Location) {
        runCatching {
            val f = Location::class.java.getDeclaredField("mIsFromMockProvider")
            f.isAccessible = true
            f.setBoolean(loc, false)
        }
        runCatching {
            val f = Location::class.java.getDeclaredField("mMock")
            f.isAccessible = true
            f.setBoolean(loc, false)
        }
    }

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
