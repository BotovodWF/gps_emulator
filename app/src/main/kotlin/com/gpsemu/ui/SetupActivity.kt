package com.gpsemu.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gpsemu.map.MapPresets
import com.gpsemu.service.MockLocationService

class SetupActivity : AppCompatActivity() {

    private lateinit var btnAction: Button
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Если разрешение геолокации уже есть — сразу запускаем
        if (hasLocationPerm()) { launchMain(); return }

        buildUI()
    }

    override fun onResume() {
        super.onResume()
        if (!::btnAction.isInitialized) return
        if (hasLocationPerm()) { launchMain(); return }
        refresh()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 64)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "GPS Emulator"
            textSize = 28f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        })

        tvHint = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, 0, 0, 48)
        }
        root.addView(tvHint)

        btnAction = Button(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onAction() }
        }
        root.addView(btnAction)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })

        refresh()
    }

    private fun refresh() {
        if (hasLocationPerm()) {
            btnAction.text = "▶  Запустить GPS"
            tvHint.text = "Готово!"
        } else {
            btnAction.text = "Разрешить геолокацию"
            tvHint.text = "Нажмите кнопку и разрешите\nдоступ к местоположению."
        }
    }

    private fun onAction() {
        if (!hasLocationPerm()) {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        } else {
            launchMain()
        }
    }

    private fun hasLocationPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun launchMain() {
        MockLocationService.start(this, MapPresets.DEFAULT)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPerm()) { launchMain(); return }
        refresh()
    }
}
