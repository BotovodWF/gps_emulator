package com.gpsemu.map

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Serves map tiles to the WebView.
 *
 * The page cannot fetch tiles from Yandex directly — WebView blocks image requests
 * to a foreign origin, and the failure is silent. So map.html asks a local path
 * (`/tile?z=&x=&y=`) instead, [MainActivity] intercepts that request through
 * `shouldInterceptRequest`, and this object downloads the tile natively and hands
 * the bytes back as the response.
 */
object YandexTileProxy {

    private const val HOST = "localhost"
    private const val PATH = "/tile"
    private const val TIMEOUT_MS = 8_000

    /** True when this request is the page asking for a tile. */
    fun handles(uri: Uri): Boolean = uri.host == HOST && uri.path == PATH

    /** Downloads the tile named by the query string, or null to let the load fail. */
    fun fetch(uri: Uri): WebResourceResponse? {
        val z = uri.getQueryParameter("z") ?: return null
        val x = uri.getQueryParameter("x") ?: return null
        val y = uri.getQueryParameter("y") ?: return null

        return runCatching {
            val url = "https://core-renderer-tiles.maps.yandex.net/tiles" +
                "?l=map&x=$x&y=$y&z=$z&lang=ru_RU&scale=1"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9)")
            }
            WebResourceResponse("image/png", null, ByteArrayInputStream(conn.inputStream.readBytes()))
        }.getOrNull()
    }
}
