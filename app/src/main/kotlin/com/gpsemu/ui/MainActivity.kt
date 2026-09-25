package com.gpsemu.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gpsemu.R
import com.gpsemu.core.GeoPoint
import com.gpsemu.map.MapPresets
import com.gpsemu.map.YandexTileProxy
import com.gpsemu.service.MockLocationService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnStop: Button
    private lateinit var presetsContainer: LinearLayout
    private lateinit var mapView: WebView

    @Volatile private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnStop = findViewById(R.id.btnStop)
        presetsContainer = findViewById(R.id.presetsContainer)
        mapView = findViewById(R.id.mapView)

        setupWebView()
        setupPresets()

        btnStop.setOnClickListener {
            MockLocationService.stop(this)
            active = false
            tvStatus.text = getString(R.string.status_idle)
            btnStop.visibility = View.GONE
        }

        requestPermissionsIfNeeded()
    }

    // ── Map ────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        mapView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        mapView.addJavascriptInterface(MapBridge(), "Android")

        mapView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                android.util.Log.d("NavMap", "${msg.message()} [${msg.lineNumber()}]")
                return true
            }
        }

        mapView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.goTo(MapPresets.DEFAULT)
            }
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? =
                if (YandexTileProxy.handles(request.url)) YandexTileProxy.fetch(request.url)
                else null
        }

        val html = assets.open("map.html").bufferedReader().readText()
        mapView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)
    }

    private fun WebView.goTo(point: GeoPoint) {
        evaluateJavascript(
            "goTo(${point.latitude}, ${point.longitude}, ${MapPresets.ZOOM})", null
        )
    }

    private fun setupPresets() {
        MapPresets.ALL.forEach { preset ->
            presetsContainer.addView(
                Button(this).apply {
                    text = preset.title
                    textSize = 12f
                    setPadding(24, 0, 24, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    ).also { it.setMargins(4, 4, 4, 4) }
                    setOnClickListener {
                        mapView.goTo(preset.point)
                        teleportTo(preset.point)
                    }
                }
            )
        }
    }

    // ── Bridge from map.html ───────────────────────────────────────────────

    inner class MapBridge {
        @JavascriptInterface
        fun onLocationPicked(lat: Double, lon: Double) {
            teleportTo(GeoPoint(lat, lon))
        }
    }

    private fun teleportTo(point: GeoPoint) {
        MockLocationService.start(this, point)
        active = true
        runOnUiThread {
            tvStatus.text = "▶ ${point.format()}"
            btnStop.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (mapView.canGoBack()) mapView.goBack() else super.onBackPressed()
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val perms = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty())
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 0)
    }
}
