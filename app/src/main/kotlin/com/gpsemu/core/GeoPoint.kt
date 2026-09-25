package com.gpsemu.core

import android.content.Intent

/**
 * A geographic point: degrees in WGS84, altitude in metres.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
) {

    val isUnset: Boolean get() = latitude == 0.0 && longitude == 0.0

    fun format(): String = "%.5f,  %.5f".format(latitude, longitude)

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_LAT, latitude)
        .putExtra(EXTRA_LON, longitude)
        .putExtra(EXTRA_ALT, altitude)

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALT = "alt"

        /**
         * Reads a point from an Intent, accepting both ways coordinates reach us:
         *
         *  - Double extras — what [putInto] writes when the app starts the service;
         *  - String extras — what the test scripts pass (`am ... --es lat "53.188"`).
         *
         * The String path exists because `am` has no double flag. Its `--ef` writes a
         * *float* extra, and `getDoubleExtra` on a float extra silently returns the
         * default — which had every scripted teleport landing on 0°N 0°E.
         */
        fun fromIntent(intent: Intent?, fallback: GeoPoint): GeoPoint {
            if (intent == null) return fallback
            return GeoPoint(
                latitude = intent.readDouble(EXTRA_LAT, fallback.latitude),
                longitude = intent.readDouble(EXTRA_LON, fallback.longitude),
                altitude = intent.readDouble(EXTRA_ALT, fallback.altitude),
            )
        }

        private fun Intent.readDouble(key: String, fallback: Double): Double =
            getStringExtra(key)?.toDoubleOrNull() ?: getDoubleExtra(key, fallback)
    }
}
