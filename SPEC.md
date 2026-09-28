# GPS Emulator — Technical Specification

## Overview

The app has two concerns that must never interfere with each other:

1. **UI layer** — lets the user pick a location or draw a route
2. **Spoofing layer** — continuously feeds that location into the OS and intercepts any path that leaks the real location

These are in different processes. The UI runs in `com.gpsemu`. The Xposed hooks run inside every hooked app's process (e.g. `ru.yandex.taximeter`). Communication is via `Settings.Global`.

---

## Data model

### `GeoPoint`

```kotlin
data class GeoPoint(
    val latitude:  Double,
    val longitude: Double,
    val altitude:  Double = 0.0,
    val bearing:   Float  = 0f,   // degrees, 0 = north, clockwise
)
```

Serialized into `Intent` extras for service communication. `fromIntent` handles both `Double` extras (app path) and `String` extras (`am` shell path).

---

## MockLocationService

Foreground service, `START_STICKY`.

### Emission

- Calls `locationManager.setTestProviderLocation` for three providers: `gps`, `network`, `fused`
- Rate: 200 ms normal, 50 ms × 8 burst on teleport
- `LocationFactory.stationary` — zero speed, uses `GeoPoint.bearing`
- `LocationFactory.moving` — non-zero speed (used during route playback)

### Stationary mode

`onStartCommand` with no action → reads `GeoPoint` from intent → updates `point` → bursts if moved.

### Route mode

Triggered by `ACTION_START_ROUTE`. Carries waypoints as JSON, speed (km/h) or total time (seconds).

**Playback algorithm:**

```
for each segment (from, to):
    dist    = haversine(from, to)          // metres
    bearing = calcBearing(from, to)        // degrees
    durMs   = dist / speedMs * 1000        // or proportional to totalTimeSec
    loop at 150ms:
        t       = elapsed / durMs          // 0..1
        point   = lerp(from, to, t)        // linear lat/lon interpolation
        bearing = segment bearing
        speed   = dist / (durMs / 1000)    // m/s
```

Linear lat/lon interpolation introduces < 0.01% error for segments under 50 km, which is acceptable for city-scale navigation.

### Settings.Global writes

Throttled to once per 300 ms during route playback to limit IPC overhead. The Xposed hook caches the last read for 500 ms anyway.

---

## LocationInjectorHook (Xposed)

Hooks in every process in LSPosed scope.

### Sources intercepted

| Hook point | Why |
|---|---|
| `Location.createFromParcel` | Intercepts all location objects deserialized from IPC |
| `Location.setLatitude/setLongitude` | Blocks internal mutation back to real coords |
| `LbsPositionApiModel.getLatitude/getLongitude` | Yandex Flutter LBS SDK cell-tower fallback |
| `LbsPositionApiModel.<init>(DDDDDL…)` | Constructor-level interception as belt-and-suspenders |

### Coord delivery

```kotlin
private fun loadPoint(): GeoPoint? {
    val cached = cache   // 500ms TTL
    if (cached != null && age < 500ms) return cached
    val lat = Settings.Global.getString(resolver, "gpsemu_lat")?.toDoubleOrNull()
    val lon = Settings.Global.getString(resolver, "gpsemu_lon")?.toDoubleOrNull()
    ...
}
```

`lastValidPoint` persists the last known good point so a Settings.Global miss does not revert to real coords.

### Samsung One UI field names

AOSP `mLatitudeDegrees` → Samsung One UI `mLatitude`. Discovered at runtime:

```kotlin
private fun findLocationField(vararg hints: String): Field? {
    val doubles = Location::class.java.declaredFields.filter { it.type == Double::class.javaPrimitiveType }
    for (hint in hints) {
        doubles.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.let { return it }
    }
    for (hint in hints) {
        doubles.firstOrNull { it.name.contains(hint, ignoreCase = true) }?.let { return it }
    }
    return null
}
```

All reflection calls catch `Throwable`, not `Exception`, because `NoSuchFieldError extends Error`.

---

## Map (map.html)

Self-contained canvas renderer. No mapping library.

### Projection

Yandex tiles use **EPSG:3395 (elliptical Mercator)**, not EPSG:3857. The eccentricity term `e = 0.0818191908426` is required; spherical formulas produce a ~20 km error at 53°N.

Inverse projection (pixel → lat) uses Newton iteration, converging in 2–3 steps to 1e-12 rad.

### Modes

| Mode | Tap behaviour | Long-press |
|---|---|---|
| `point` | Set `marker`, call `Android.onLocationPicked` | — |
| `route` | Push waypoint to `routeWaypoints`, call `Android.onWaypointAdded` | Pop last waypoint, call `Android.onWaypointRemoved` |

Route is drawn as: blue polyline + numbered circles (last waypoint in red).

---

## UI layout constraints

- All touch targets ≥ 44 dp height
- Route primary controls (▶ / ⏹) share the full row width equally — no fixed-pixel widths that overflow on small screens
- Speed/time label and bearing value use `wrap_content` width and `gravity=end` so long values (e.g. "200 км/ч") don't clip
- Presets strip in a `HorizontalScrollView` — unlimited city count without wrapping
- Status text uses `singleLine + ellipsize=end` so long coords don't push the Stop button off-screen

---

## Intent protocol

### Point teleport
```
Action: (none)
Extras: lat=Double, lon=Double, alt=Double, bearing=Float
```

### Route start
```
Action: com.gpsemu.START_ROUTE
Extras:
  waypoints_json = JSON array [{lat,lon,alt}, ...]
  speed_kmh      = Float  (0 = use time_sec)
  time_sec       = Int    (0 = use speed_kmh)
  loop           = Boolean
```

### Playback control
```
Action: com.gpsemu.PAUSE
Action: com.gpsemu.RESUME
Action: com.gpsemu.STOP
```
