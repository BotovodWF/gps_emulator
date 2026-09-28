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
import android.widget.SeekBar
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

    private enum class AppMode { POINT, ROUTE }
    private enum class SpeedMode { SPEED, TIME }
    private enum class RouteState { IDLE, PLAYING, PAUSED }

    private lateinit var tvStatus: TextView
    private lateinit var btnStop: Button
    private lateinit var btnModePoint: Button
    private lateinit var btnModeRoute: Button
    private lateinit var mapView: WebView
    private lateinit var panelPoint: LinearLayout
    private lateinit var panelRoute: LinearLayout
    private lateinit var presetsContainer: LinearLayout
    private lateinit var seekBearing: SeekBar
    private lateinit var tvBearing: TextView
    private lateinit var btnSpeedMode: Button
    private lateinit var seekSpeedTime: SeekBar
    private lateinit var tvSpeedTime: TextView
    private lateinit var tvRouteInfo: TextView
    private lateinit var btnRoutePlay: Button
    private lateinit var btnRoutePause: Button
    private lateinit var btnRouteStop: Button
    private lateinit var btnRouteLoop: Button
    private lateinit var btnRouteClear: Button

    private var appMode = AppMode.POINT
    private var speedMode = SpeedMode.SPEED
    private var routeState = RouteState.IDLE
    private var loopEnabled = false
    private var currentBearing = 0f
    private val routeWaypoints = mutableListOf<GeoPoint>()
    private var serviceActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnStop = findViewById(R.id.btnStop)
        btnModePoint = findViewById(R.id.btnModePoint)
        btnModeRoute = findViewById(R.id.btnModeRoute)
        mapView = findViewById(R.id.mapView)
        panelPoint = findViewById(R.id.panelPoint)
        panelRoute = findViewById(R.id.panelRoute)
        presetsContainer = findViewById(R.id.presetsContainer)
        seekBearing = findViewById(R.id.seekBearing)
        tvBearing = findViewById(R.id.tvBearing)
        btnSpeedMode = findViewById(R.id.btnSpeedMode)
        seekSpeedTime = findViewById(R.id.seekSpeedTime)
        tvSpeedTime = findViewById(R.id.tvSpeedTime)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        btnRoutePlay = findViewById(R.id.btnRoutePlay)
        btnRoutePause = findViewById(R.id.btnRoutePause)
        btnRouteStop = findViewById(R.id.btnRouteStop)
        btnRouteLoop = findViewById(R.id.btnRouteLoop)
        btnRouteClear = findViewById(R.id.btnRouteClear)

        setupWebView()
        setupPresets()
        setupModeButtons()
        setupBearingSlider()
        setupRouteControls()

        btnStop.setOnClickListener {
            MockLocationService.stop(this)
            serviceActive = false
            routeState = RouteState.IDLE
            tvStatus.text = getString(R.string.status_idle)
            btnStop.visibility = View.GONE
            updateRouteButtons()
        }

        requestPermissionsIfNeeded()
    }

    // ── Mode switching ─────────────────────────────────────────────────────

    private fun setupModeButtons() {
        btnModePoint.setOnClickListener { switchMode(AppMode.POINT) }
        btnModeRoute.setOnClickListener { switchMode(AppMode.ROUTE) }
    }

    private fun switchMode(mode: AppMode) {
        appMode = mode
        panelPoint.visibility = if (mode == AppMode.POINT) View.VISIBLE else View.GONE
        panelRoute.visibility = if (mode == AppMode.ROUTE) View.VISIBLE else View.GONE
        btnModePoint.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (mode == AppMode.POINT) 0xFF1B8A3C.toInt() else 0xFFBDBDBD.toInt()
        )
        btnModePoint.setTextColor(if (mode == AppMode.POINT) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        btnModeRoute.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (mode == AppMode.ROUTE) 0xFF1B8A3C.toInt() else 0xFFBDBDBD.toInt()
        )
        btnModeRoute.setTextColor(if (mode == AppMode.ROUTE) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        mapView.evaluateJavascript("setMode('${if (mode == AppMode.ROUTE) "route" else "point"}')", null)
    }

    // ── Point mode ─────────────────────────────────────────────────────────

    private fun setupBearingSlider() {
        seekBearing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentBearing = progress.toFloat()
                tvBearing.text = "${progress}°"
                mapView.evaluateJavascript("setMarkerBearing($progress)", null)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
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
                        teleportTo(preset.point.copy(bearing = currentBearing))
                    }
                }
            )
        }
    }

    // ── Route mode controls ────────────────────────────────────────────────

    private fun setupRouteControls() {
        seekSpeedTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateSpeedTimeLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        updateSpeedTimeLabel(seekSpeedTime.progress)

        btnSpeedMode.setOnClickListener {
            speedMode = if (speedMode == SpeedMode.SPEED) SpeedMode.TIME else SpeedMode.SPEED
            btnSpeedMode.text = if (speedMode == SpeedMode.SPEED) "км/ч" else "мин"
            seekSpeedTime.max = if (speedMode == SpeedMode.SPEED) 199 else 119
            seekSpeedTime.progress = if (speedMode == SpeedMode.SPEED) 59 else 9
            updateSpeedTimeLabel(seekSpeedTime.progress)
        }

        btnRoutePlay.setOnClickListener {
            when (routeState) {
                RouteState.IDLE -> startRoute()
                RouteState.PAUSED -> resumeRoute()
                RouteState.PLAYING -> {} // shouldn't happen (btn hidden)
            }
        }

        btnRoutePause.setOnClickListener {
            if (routeState == RouteState.PLAYING) pauseRoute()
        }

        btnRouteStop.setOnClickListener {
            stopRoute()
        }

        btnRouteLoop.setOnClickListener {
            loopEnabled = !loopEnabled
            btnRouteLoop.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (loopEnabled) 0xFF1B8A3C.toInt() else 0xFFBDBDBD.toInt()
            )
        }

        btnRouteClear.setOnClickListener {
            clearRouteWaypoints()
        }
    }

    private fun updateSpeedTimeLabel(progress: Int) {
        val value = progress + 1
        tvSpeedTime.text = if (speedMode == SpeedMode.SPEED) "$value км/ч" else "$value мин"
    }

    private fun addRouteWaypoint(point: GeoPoint) {
        routeWaypoints.add(point)
        updateRouteInfo()
    }

    private fun clearRouteWaypoints() {
        routeWaypoints.clear()
        updateRouteInfo()
        mapView.evaluateJavascript("clearRoute()", null)
    }

    private fun updateRouteInfo() {
        tvRouteInfo.text = "${routeWaypoints.size} ${pointsWord(routeWaypoints.size)}"
    }

    private fun pointsWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..19 -> "точек"
            mod10 == 1 -> "точка"
            mod10 in 2..4 -> "точки"
            else -> "точек"
        }
    }

    private fun startRoute() {
        if (routeWaypoints.size < 2) {
            tvStatus.text = "Нужно минимум 2 точки"
            return
        }
        val speedVal = seekSpeedTime.progress + 1
        val speedKmh = if (speedMode == SpeedMode.SPEED) speedVal.toFloat() else 0f
        val timeSec = if (speedMode == SpeedMode.TIME) speedVal * 60 else 0
        MockLocationService.startRoute(this, routeWaypoints.toList(), speedKmh, timeSec, loopEnabled)
        serviceActive = true
        routeState = RouteState.PLAYING
        val desc = if (speedMode == SpeedMode.SPEED) "$speedVal км/ч" else "$speedVal мин"
        tvStatus.text = "▶ Маршрут ($desc)"
        btnStop.visibility = View.VISIBLE
        updateRouteButtons()
    }

    private fun pauseRoute() {
        MockLocationService.pause(this)
        routeState = RouteState.PAUSED
        tvStatus.text = "⏸ Маршрут (пауза)"
        updateRouteButtons()
    }

    private fun resumeRoute() {
        MockLocationService.resume(this)
        routeState = RouteState.PLAYING
        tvStatus.text = "▶ Маршрут (продолжение)"
        updateRouteButtons()
    }

    private fun stopRoute() {
        MockLocationService.stop(this)
        serviceActive = false
        routeState = RouteState.IDLE
        tvStatus.text = getString(R.string.status_idle)
        btnStop.visibility = View.GONE
        updateRouteButtons()
    }

    private fun updateRouteButtons() {
        btnRoutePlay.visibility = if (routeState == RouteState.IDLE || routeState == RouteState.PAUSED) View.VISIBLE else View.GONE
        btnRoutePlay.text = if (routeState == RouteState.PAUSED) "▶" else "▶"
        btnRoutePause.visibility = if (routeState == RouteState.PLAYING) View.VISIBLE else View.GONE
    }

    // ── Map bridge ─────────────────────────────────────────────────────────

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
        evaluateJavascript("goTo(${point.latitude}, ${point.longitude}, ${MapPresets.ZOOM})", null)
    }

    private fun teleportTo(point: GeoPoint) {
        MockLocationService.start(this, point)
        serviceActive = true
        runOnUiThread {
            tvStatus.text = "▶ ${point.format()}"
            if (point.bearing > 0) tvStatus.text = "${tvStatus.text}  ${point.bearing.toInt()}°"
            btnStop.visibility = View.VISIBLE
        }
    }

    inner class MapBridge {
        @JavascriptInterface
        fun onLocationPicked(lat: Double, lon: Double) {
            teleportTo(GeoPoint(lat, lon, 0.0, currentBearing))
        }

        @JavascriptInterface
        fun onWaypointAdded(lat: Double, lon: Double) {
            runOnUiThread { addRouteWaypoint(GeoPoint(lat, lon)) }
        }

        @JavascriptInterface
        fun onWaypointRemoved() {
            runOnUiThread {
                if (routeWaypoints.isNotEmpty()) {
                    routeWaypoints.removeAt(routeWaypoints.size - 1)
                    updateRouteInfo()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
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
