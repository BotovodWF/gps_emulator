package com.gpsemu.map

import com.gpsemu.core.GeoPoint

/**
 * Shortcut buttons above the map.
 *
 * Every coordinate here was verified against Yandex Maps with a `geo:` intent and
 * lands on a real street. Getting these right took some care around Samara: the
 * Volga loops back on itself there (Самарская Лука), so the east bank sits at a
 * different longitude depending on latitude, and a plausible-looking guess easily
 * ends up in the river or in a floodplain island.
 */
object MapPresets {

    data class Preset(val title: String, val point: GeoPoint)

    /** Where the map opens on launch. */
    val DEFAULT = GeoPoint(55.7558, 37.6173)

    /** Zoom level used for the map view and for every preset jump. */
    const val ZOOM = 15

    val ALL = listOf(
        Preset("Самара Центр", GeoPoint(53.1880, 50.1800)),
        Preset("Самара Набер.", GeoPoint(53.1858, 50.1440)),
        Preset("Самара Ж/Д", GeoPoint(53.1983, 50.1513)),
        Preset("Самара Сев.", GeoPoint(53.2450, 50.2100)),
        Preset("Самара Аэропорт", GeoPoint(53.5028, 50.1640)),
        Preset("Москва", GeoPoint(55.7558, 37.6176)),
        Preset("Питер", GeoPoint(59.9311, 30.3609)),
        Preset("Сочи", GeoPoint(43.5853, 39.7203)),
    )
}
