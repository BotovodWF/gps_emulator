# GPS Emulator

An LSPosed/Xposed module for Android that spoofs device location at the framework level, bypassing both the standard Location API and Yandex's proprietary Flutter LBS SDK. Designed for Yandex Pro and similar apps that use cell-tower positioning as a fallback.

## Requirements

- Android 12+ (API 31+)
- Magisk + Zygisk
- LSPosed (Zygisk variant)
- Root: `pm grant com.gpsemu android.permission.WRITE_SECURE_SETTINGS`

## Features

- **Point mode** — teleport to a tapped map location instantly; optional camera bearing (0–359°)
- **Route mode** — draw a multi-waypoint route on the map, then play it back at a chosen speed (km/h) or within a fixed total time (minutes); loop support
- **Multi-layer spoofing** — patches `LocationManager` test providers, hooks `android.location.Location` field writes (`mLatitude`/`mLongitude` on Samsung One UI), and intercepts `LbsPositionApiModel.getLatitude/getLongitude` so Yandex's cell-tower fallback returns spoofed coords
- **City presets** — quick-jump buttons for Samara, Moscow, Saint Petersburg, Sochi
- **Boot persistence** — active point survives reboots via `BootReceiver`

## Architecture

```
MainActivity
  │
  ├── WebView (map.html)        Yandex Mercator tile map (EPSG:3395)
  │     └── YandexTileProxy    Proxies /tile requests to Yandex CDN
  │
  └── MockLocationService      Foreground service; feeds test providers
        └── LocationFactory    Builds realistic Location objects with jitter

LSPosed module (separate process):
  LocationInjectorHook
    ├── hooks Location.createFromParcel / setLatitude / setLongitude
    ├── hooks LbsPositionApiModel.getLatitude / getLongitude
    └── reads coords from Settings.Global (gpsemu_lat / gpsemu_lon)
```

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable the module in LSPosed → scope: **Yandex Pro** (and `android` system scope).

## Route playback

1. Switch to **Маршрут** mode
2. Tap the map to add waypoints (numbered blue circles appear)
3. Long-press to remove the last waypoint
4. Set speed (km/h) or total travel time (minutes) with the toggle + slider
5. Toggle **🔁 Петля** to loop indefinitely
6. Press **▶ Старт**

The service interpolates position linearly between waypoints and computes the heading (bearing) at each segment, so navigation apps show realistic movement direction.

## IPC between module and service

Coordinates are shared via `Settings.Global` keys:

| Key              | Value               |
|------------------|---------------------|
| `gpsemu_lat`     | latitude (double)   |
| `gpsemu_lon`     | longitude (double)  |
| `gpsemu_alt`     | altitude (double)   |
| `gpsemu_bearing` | bearing (float, °)  |

The module reads these on every `Location.createFromParcel` interception and on `LbsPositionApiModel` getter calls with a 500 ms cache.

## Samsung One UI compatibility

Samsung renames internal `Location` fields:
- AOSP: `mLatitudeDegrees` / `mLongitudeDegrees`
- One UI: `mLatitude` / `mLongitude`

The module discovers field names at runtime via `getDeclaredFields()` and catches `Throwable` (not `Exception`) because `NoSuchFieldError` is an `Error` subclass.
