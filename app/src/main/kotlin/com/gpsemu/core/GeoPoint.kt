package com.gpsemu.core

import android.content.Intent

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val bearing: Float = 0f,
) {

    val isUnset: Boolean get() = latitude == 0.0 && longitude == 0.0

    fun format(): String = "%.5f,  %.5f".format(latitude, longitude)

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_LAT, latitude)
        .putExtra(EXTRA_LON, longitude)
        .putExtra(EXTRA_ALT, altitude)
        .putExtra(EXTRA_BEARING, bearing)

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALT = "alt"
        const val EXTRA_BEARING = "bearing"

        fun fromIntent(intent: Intent?, fallback: GeoPoint): GeoPoint {
            if (intent == null) return fallback
            return GeoPoint(
                latitude = intent.readDouble(EXTRA_LAT, fallback.latitude),
                longitude = intent.readDouble(EXTRA_LON, fallback.longitude),
                altitude = intent.readDouble(EXTRA_ALT, fallback.altitude),
                bearing = intent.getFloatExtra(EXTRA_BEARING, fallback.bearing),
            )
        }

        private fun Intent.readDouble(key: String, fallback: Double): Double =
            getStringExtra(key)?.toDoubleOrNull() ?: getDoubleExtra(key, fallback)
    }
}
