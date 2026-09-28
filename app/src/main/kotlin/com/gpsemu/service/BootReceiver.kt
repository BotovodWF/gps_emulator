package com.gpsemu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gpsemu.core.GeoPoint

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(MockLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return
        val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return
        val alt = prefs.getString("alt", "0")?.toDoubleOrNull() ?: 0.0
        val brg = prefs.getString("bearing", "0")?.toFloatOrNull() ?: 0f

        prefs.edit().putBoolean("active", true).commit()
        MockLocationService.start(context, GeoPoint(lat, lon, alt, brg))
    }
}
