package com.gpsemu.xposed

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import com.gpsemu.core.GeoPoint
import com.gpsemu.core.LocationFactory
import com.gpsemu.service.MockLocationService
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LocationInjectorHook : IXposedHookLoadPackage {

    private lateinit var prefs: XSharedPreferences

    @Volatile private var cachedPoint: GeoPoint? = null
    @Volatile private var lastValidPoint: GeoPoint? = null
    @Volatile private var cacheTime: Long = 0

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.gpsemu") return
        prefs = XSharedPreferences("com.gpsemu", MockLocationService.PREFS_NAME)

        hookGetLastKnownLocation()
        hookIsProviderEnabled()
        hookSetCoordinates()
        hookLocationCoordinates()
        hookLocationFromParcel()
        hookRequestLocationUpdates(lpparam.classLoader)
        hookFusedLocationResult(lpparam.classLoader)
        hookFusedLocationCallback(lpparam.classLoader)
        hookLocationResultFromParcel(lpparam.classLoader)
        hookLocationAvailability(lpparam.classLoader)
        hookYandexLbsPosition(lpparam.classLoader)
    }

    private fun getAppContext(): android.content.Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
    }.getOrNull()

    private fun loadPoint(): GeoPoint? {
        val now = System.currentTimeMillis()
        if (now - cacheTime > 500L) {
            var lat: Double? = null
            var lon: Double? = null
            var alt = 0.0

            // Primary: Settings.Global — no file permissions needed, readable by any process.
            // MockLocationService writes here via WRITE_SECURE_SETTINGS it already holds.
            runCatching {
                val cr = getAppContext()?.contentResolver
                if (cr != null) {
                    // Check active flag first; "0" means the service stopped intentionally
                    val active = Settings.Global.getString(cr, "gpsemu_active")
                    if (active == "0") {
                        cachedPoint = null
                        lastValidPoint = null
                        cacheTime = now
                        return null
                    }
                    lat = Settings.Global.getString(cr, "gpsemu_lat")?.toDoubleOrNull()
                    lon = Settings.Global.getString(cr, "gpsemu_lon")?.toDoubleOrNull()
                    alt = Settings.Global.getString(cr, "gpsemu_alt")?.toDoubleOrNull() ?: 0.0
                }
            }

            // Fallback: XSharedPreferences (broken on some devices but kept for safety)
            if (lat == null || lon == null) {
                runCatching {
                    prefs.reload()
                    lat = prefs.getString("lat", null)?.toDoubleOrNull()
                    lon = prefs.getString("lon", null)?.toDoubleOrNull()
                    alt = prefs.getString("alt", "0")?.toDoubleOrNull() ?: 0.0
                }
            }

            cachedPoint = if (lat != null && lon != null) GeoPoint(lat!!, lon!!, alt) else null
            if (cachedPoint != null) lastValidPoint = cachedPoint
            else Log.w(TAG, "loadPoint: all sources null lastValid=$lastValidPoint")
            cacheTime = now
        }
        return cachedPoint ?: lastValidPoint
    }

    // Modifies a Location's private fields directly so JNI field-reads also get fake coords.
    // This is necessary for native SDKs (e.g. Yandex MapKit C++) that access
    // mLatitudeDegrees / mLongitudeDegrees via GetDoubleField rather than calling the getter.
    // Lazy discovery of Samsung-renamed Location fields. AOSP names are used as hints only;
    // the actual field names may differ on Samsung One UI builds.
    // Samsung One UI uses mLatitude/mLongitude/mAltitude (not the AOSP mLatitudeDegrees names).
    // findLocationField() auto-discovers whichever variant is present via substring match.
    private val latField: java.lang.reflect.Field? by lazy {
        findLocationField("mLatitude", "lat", "latitude")
    }
    private val lonField: java.lang.reflect.Field? by lazy {
        findLocationField("mLongitude", "lon", "longitude")
    }
    private val altField: java.lang.reflect.Field? by lazy {
        findLocationField("mAltitude", "alt", "altitude")
    }
    private val locationDoubleFieldNames: String by lazy {
        Location::class.java.declaredFields
            .filter { it.type == Double::class.javaPrimitiveType }
            .joinToString { it.name }
    }

    private fun findLocationField(vararg hints: String): java.lang.reflect.Field? {
        val allDoubles = Location::class.java.declaredFields.filter { it.type == Double::class.javaPrimitiveType }
        Log.d(TAG, "Location double fields: $locationDoubleFieldNames")
        for (hint in hints) {
            allDoubles.firstOrNull { it.name.equals(hint, ignoreCase = true) }
                ?.also { it.isAccessible = true; Log.d(TAG, "latField exact match: ${it.name}") }
                ?.let { return it }
        }
        for (hint in hints) {
            allDoubles.firstOrNull { it.name.contains(hint, ignoreCase = true) }
                ?.also { it.isAccessible = true; Log.d(TAG, "latField substring match: ${it.name}") }
                ?.let { return it }
        }
        Log.w(TAG, "findLocationField: no match for hints=${hints.toList()}")
        return null
    }

    private fun injectIntoLocation(location: Location, pt: GeoPoint) {
        // Use dynamically-discovered fields so Samsung One UI field renames don't crash us.
        try { latField?.set(location, pt.latitude) } catch (_: Throwable) {}
        try { lonField?.set(location, pt.longitude) } catch (_: Throwable) {}
        // Fallback: try the AOSP names (no-op if already tried above successfully)
        try { XposedHelpers.setDoubleField(location, "mLatitudeDegrees", pt.latitude) } catch (_: Throwable) {}
        try { XposedHelpers.setDoubleField(location, "mLongitudeDegrees", pt.longitude) } catch (_: Throwable) {}
        try { XposedHelpers.setDoubleField(location, "mAltitudeMeters", pt.altitude) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(location, "mHasAltitude", true) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(location, "mHasMock", false) } catch (_: Throwable) {}
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

    // ── requestLocationUpdates: wrap ALL listeners to modify Location fields ──
    //
    // This is the definitive fix for Samsung FLP and any other source that bypasses
    // Android mock providers: regardless of where the Location originates, when it
    // arrives in the app's onLocationChanged() callback, we modify the fields
    // directly before the app (or native MapKit) reads them.

    private fun hookRequestLocationUpdates(cl: ClassLoader) {
        val lm = LocationManager::class.java
        val listenerCls = LocationListener::class.java
        val looperCls = Looper::class.java

        // requestLocationUpdates(String, long, float, LocationListener)
        runCatching {
            XposedHelpers.findAndHookMethod(lm, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java, listenerCls,
                makeListenerWrapHook(3))
        }
        // requestLocationUpdates(String, long, float, LocationListener, Looper)
        runCatching {
            XposedHelpers.findAndHookMethod(lm, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java, listenerCls, looperCls,
                makeListenerWrapHook(3))
        }
        // requestSingleUpdate(String, LocationListener, Looper)
        runCatching {
            XposedHelpers.findAndHookMethod(lm, "requestSingleUpdate",
                String::class.java, listenerCls, looperCls,
                makeListenerWrapHook(1))
        }
        // requestLocationUpdates(LocationRequest, LocationListener, Looper) — API 31+
        runCatching {
            val lrCls = Class.forName("android.location.LocationRequest")
            XposedHelpers.findAndHookMethod(lm, "requestLocationUpdates",
                lrCls, listenerCls, looperCls,
                makeListenerWrapHook(1))
        }
        // requestLocationUpdates(LocationRequest, Executor, LocationListener) — API 31+
        runCatching {
            val lrCls = Class.forName("android.location.LocationRequest")
            val exCls = Class.forName("java.util.concurrent.Executor")
            XposedHelpers.findAndHookMethod(lm, "requestLocationUpdates",
                lrCls, exCls, listenerCls,
                makeListenerWrapHook(2))
        }
    }

    private fun makeListenerWrapHook(listenerArgIndex: Int) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val real = param.args[listenerArgIndex] as? LocationListener ?: return
            if (real is WrappedListener) return
            param.args[listenerArgIndex] = WrappedListener(real)
        }
    }

    inner class WrappedListener(private val delegate: LocationListener) : LocationListener {
        override fun onLocationChanged(location: Location) {
            val pt = loadPoint()
            Log.d(TAG, "WrappedListener.onLocationChanged: real=${location.latitude},${location.longitude} pt=$pt")
            pt?.let { injectIntoLocation(location, it) }
            delegate.onLocationChanged(location)
        }
        override fun onLocationChanged(locations: MutableList<Location>) {
            loadPoint()?.let { pt -> locations.forEach { injectIntoLocation(it, pt) } }
            delegate.onLocationChanged(locations)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) =
            delegate.onStatusChanged(provider, status, extras)
        override fun onProviderEnabled(provider: String) = delegate.onProviderEnabled(provider)
        override fun onProviderDisabled(provider: String) = delegate.onProviderDisabled(provider)
    }

    // ── Block internal Location mutation (prevent MapKit from writing real coords) ──
    //
    // Yandex MapKit can create Location objects internally via setLatitude/setLongitude
    // without going through Binder, bypassing createFromParcel. Hook the setters so
    // any attempt to store real coords into any Location in this process is overridden.

    private fun hookSetCoordinates() {
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "setLatitude", Double::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val real = param.args[0] as? Double ?: return
                        val pt = loadPoint() ?: return
                        if (Math.abs(real - pt.latitude) > 0.0001) {
                            Log.w(TAG, "setLatitude BLOCKED real=$real → fake=${pt.latitude}")
                        }
                        param.args[0] = pt.latitude
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "setLongitude", Double::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val real = param.args[0] as? Double ?: return
                        val pt = loadPoint() ?: return
                        if (Math.abs(real - pt.longitude) > 0.0001) {
                            Log.w(TAG, "setLongitude BLOCKED real=$real → fake=${pt.longitude}")
                        }
                        param.args[0] = pt.longitude
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "setAltitude", Double::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        param.args[0] = pt.altitude
                    }
                })
        }
    }

    // ── Low-level: intercept every lat/lon read on any Location object ─────
    //
    // Also sets the underlying field so that subsequent JNI GetDoubleField
    // calls on the same object return the injected value.

    private fun hookLocationCoordinates() {
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        try { latField?.set(param.thisObject, pt.latitude) } catch (_: Throwable) {}
                        try { XposedHelpers.setDoubleField(param.thisObject, "mLatitudeDegrees", pt.latitude) } catch (_: Throwable) {}
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = loadPoint()?.latitude ?: return
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        try { lonField?.set(param.thisObject, pt.longitude) } catch (_: Throwable) {}
                        try { XposedHelpers.setDoubleField(param.thisObject, "mLongitudeDegrees", pt.longitude) } catch (_: Throwable) {}
                    }
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

    // Hooks the LocationCallback.onLocationResult entry point in the app process.
    // Modifies all Location fields BEFORE the callback body runs, so MapKit
    // JNI reads on the same objects get our injected values.
    private fun hookFusedLocationCallback(cl: ClassLoader) {
        runCatching {
            val resultCls = cl.loadClass("com.google.android.gms.location.LocationResult")
            val callbackCls = cl.loadClass("com.google.android.gms.location.LocationCallback")
            XposedHelpers.findAndHookMethod(callbackCls, "onLocationResult", resultCls,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pt = loadPoint() ?: return
                        val result = param.args[0] ?: return
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val locs = XposedHelpers.callMethod(result, "getLocations") as? List<Location>
                            Log.d(TAG, "FusedCallback.onLocationResult: ${locs?.size} locations pt=$pt")
                            locs?.forEach { injectIntoLocation(it, pt) }
                        }
                    }
                })
        }
    }

    // ── Binder deserialization hooks ──────────────────────────────────────
    //
    // Intercept Location and LocationResult objects as they are deserialized
    // from the Binder parcel in the hooked process. This fires BEFORE any
    // callback (including subclass overrides of LocationCallback.onLocationResult)
    // can read the data, so it catches Samsung FLP and all other delivery paths.

    private fun hookLocationFromParcel() = runCatching {
        val creator = XposedHelpers.getStaticObjectField(Location::class.java, "CREATOR")
        XposedHelpers.findAndHookMethod(creator.javaClass, "createFromParcel", Parcel::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pt = loadPoint() ?: return
                    val location = param.result as? Location ?: return
                    Log.d(TAG, "createFromParcel Location: was ${location.latitude},${location.longitude} → inject ${pt.latitude},${pt.longitude}")
                    injectIntoLocation(location, pt)
                }
            })
    }

    private fun hookLocationResultFromParcel(cl: ClassLoader) = runCatching {
        val cls = cl.loadClass("com.google.android.gms.location.LocationResult")
        val creator = XposedHelpers.getStaticObjectField(cls, "CREATOR")
        XposedHelpers.findAndHookMethod(creator.javaClass, "createFromParcel", Parcel::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pt = loadPoint() ?: return
                    val result = param.result ?: return
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val locs = XposedHelpers.callMethod(result, "getLocations") as? List<Location>
                        locs?.forEach { injectIntoLocation(it, pt) }
                    }
                }
            })
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

    // ── Yandex LBS (network-based positioning) hook ──────────────────────
    //
    // Yandex Pro uses a Flutter LBS SDK that fetches position from cell/WiFi
    // towers via Yandex network services and returns it as LbsPositionApiModel.
    // This is completely independent of android.location.Location, so all the
    // standard hooks above miss it. Hook the model's getters directly.

    private fun hookYandexLbsPosition(cl: ClassLoader) {
        val clsName = "ru.yandextaxi.flutter_location_sdk.controller.lbs.model.LbsPositionApiModel"
        runCatching {
            val cls = cl.loadClass(clsName)
            Log.d(TAG, "hookYandexLbsPosition: hooked $clsName")

            val latHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pt = loadPoint() ?: return
                    val real = param.result as? Double ?: return
                    if (Math.abs(real - pt.latitude) > 0.0001)
                        Log.w(TAG, "LbsPosition.getLatitude BLOCKED real=$real → fake=${pt.latitude}")
                    param.result = pt.latitude
                }
            }
            val lonHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pt = loadPoint() ?: return
                    val real = param.result as? Double ?: return
                    if (Math.abs(real - pt.longitude) > 0.0001)
                        Log.w(TAG, "LbsPosition.getLongitude BLOCKED real=$real → fake=${pt.longitude}")
                    param.result = pt.longitude
                }
            }

            XposedHelpers.findAndHookMethod(cls, "getLatitude", latHook)
            XposedHelpers.findAndHookMethod(cls, "getLongitude", lonHook)

            // Also hook the constructor to set fake coords at creation time
            // (for any JNI code that reads the fields directly after construction)
            runCatching {
                XposedHelpers.findAndHookConstructor(cls, Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pt = loadPoint() ?: return
                            // Constructor: (altitude, altitudePrecision, latitude, longitude, precision, type)
                            // Index 0=altitude, 1=altitudePrecision, 2=latitude, 3=longitude, 4=precision, 5=type
                            val realLat = param.args[2] as? Double ?: return
                            val realLon = param.args[3] as? Double ?: return
                            if (Math.abs(realLat - pt.latitude) > 0.0001)
                                Log.w(TAG, "LbsPosition.<init> BLOCKED lat=$realLat → fake=${pt.latitude}")
                            param.args[2] = pt.latitude
                            param.args[3] = pt.longitude
                        }
                    })
            }
        }.onFailure { Log.w(TAG, "hookYandexLbsPosition: $clsName not found (${it.message})") }
    }

    companion object {
        private const val TAG = "GpsEmu"
        private const val FUSED = "fused"
        private val TRACKED_PROVIDERS = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            FUSED,
        )
    }
}
