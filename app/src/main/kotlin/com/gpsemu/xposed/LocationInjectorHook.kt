package com.gpsemu.xposed

import android.location.Location
import android.location.LocationManager
import com.gpsemu.core.GeoPoint
import com.gpsemu.core.LocationFactory
import com.gpsemu.service.MockLocationService
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Actively injects our emulated coordinates into every location-read path.
 *
 * Two-layer strategy:
 *  1. High-level: override LocationManager.getLastKnownLocation and
 *     LocationResult (FusedLocationProvider) so the app receives our point
 *     as the primary source.
 *  2. Low-level: hook Location.getLatitude / getLongitude / getAltitude so
 *     that even if Yandex MapKit (or any other SDK) receives a real Location
 *     object it still reads our coordinates — this covers the case where the
 *     SDK wraps android.location.Location in its own type before the app sees it.
 *
 * Coordinates are shared via SharedPreferences written by MockLocationService
 * and read through LSPosed's XSharedPreferences bridge. A 500 ms cache avoids
 * per-call file reads on the hot getLatitude/getLongitude path.
 */
class LocationInjectorHook : IXposedHookLoadPackage {

    private lateinit var prefs: XSharedPreferences

    @Volatile private var cachedPoint: GeoPoint? = null
    @Volatile private var cacheTime: Long = 0

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.gpsemu") return
        prefs = XSharedPreferences("com.gpsemu", MockLocationService.PREFS_NAME)

        hookGetLastKnownLocation()
        hookIsProviderEnabled()
        hookLocationCoordinates()          // nuclear option — covers Yandex MapKit
        hookFusedLocationResult(lpparam.classLoader)
        hookLocationAvailability(lpparam.classLoader)
    }

    private fun loadPoint(): GeoPoint? {
        val now = System.currentTimeMillis()
        if (now - cacheTime > 500L) {
            prefs.reload()
            cachedPoint = if (prefs.getBoolean("active", false)) {
                val lat = prefs.getString("lat", null)?.toDoubleOrNull()
                val lon = prefs.getString("lon", null)?.toDoubleOrNull()
                val alt = prefs.getString("alt", "0")?.toDoubleOrNull() ?: 0.0
                if (lat != null && lon != null) GeoPoint(lat, lon, alt) else null
            } else null
            cacheTime = now
        }
        return cachedPoint
    }

    // ── High-level hooks ───────────────────────────────────────────────────

    private fun hookGetLastKnownLocation() = runCatching {
        XposedHelpers.findAndHookMethod(
            LocationManager::class.java,
            "getLastKnownLocation",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pt = loadPoint() ?: return
                    val provider = param.args[0] as? String ?: LocationManager.GPS_PROVIDER
                    param.result = LocationFactory.stationary(provider, pt)
                }
            },
        )
    }

    private fun hookIsProviderEnabled() = runCatching {
        XposedHelpers.findAndHookMethod(
            LocationManager::class.java,
            "isProviderEnabled",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val provider = param.args[0] as? String ?: return
                    if (loadPoint() != null && provider in TRACKED_PROVIDERS)
                        param.result = true
                }
            },
        )
    }

    // ── Low-level: intercept every lat/lon read on any Location object ─────
    //
    // Yandex MapKit receives an android.location.Location from Android's
    // location subsystem, reads .getLatitude()/.getLongitude(), then stores
    // the result in its own com.yandex.mapkit.location.Location. Hooking here
    // catches that read before MapKit copies the value out.

    private fun hookLocationCoordinates() {
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = loadPoint()?.latitude ?: return
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = loadPoint()?.longitude ?: return
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "getAltitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = loadPoint()?.altitude ?: return
                    }
                })
        }
    }

    // ── FusedLocationProvider (Play Services) ─────────────────────────────

    private fun hookFusedLocationResult(cl: ClassLoader) {
        runCatching {
            val cls = cl.loadClass("com.google.android.gms.location.LocationResult")

            XposedHelpers.findAndHookMethod(cls, "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        param.result = LocationFactory.stationary(FUSED, pt)
                    }
                })

            XposedHelpers.findAndHookMethod(cls, "getLocations",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        param.result = listOf(LocationFactory.stationary(FUSED, pt))
                    }
                })

            runCatching {
                XposedHelpers.findAndHookMethod(cls, "isMockLocation",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam) = false
                    })
            }
        }
    }

    private fun hookLocationAvailability(cl: ClassLoader) = runCatching {
        val cls = cl.loadClass("com.google.android.gms.location.LocationAvailability")
        XposedHelpers.findAndHookMethod(cls, "isLocationAvailable",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (loadPoint() != null) param.result = true
                }
            })
    }

    companion object {
        private const val FUSED = "fused"
        private val TRACKED_PROVIDERS = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            FUSED,
        )
    }
}
