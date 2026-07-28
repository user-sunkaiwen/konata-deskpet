package com.example.deskpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryStartService()
        }

    private val requestUsageStats =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryStartService()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            tryStartService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            requestAllPermissions()
        }
    }

    private fun requestAllPermissions() {
        // 1. Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("此方桌宠需要悬浮窗权限才能显示在屏幕上")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    requestOverlayPermission.launch(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 2. Usage stats permission
        if (!hasUsageStatsPermission()) {
            AlertDialog.Builder(this)
                .setTitle("需要使用情况访问权限")
                .setMessage("此方需要知道你在用什么App才能做出反应")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    requestUsageStats.launch(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 3. Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        tryStartService()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            now - 1000 * 60 * 60 * 24,
            now
        )
        return stats?.isNotEmpty() == true
    }

    private fun tryStartService() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val usageGranted = hasUsageStatsPermission()
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (overlayGranted && usageGranted && notifGranted) {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            findViewById<TextView>(R.id.status_text).text = "✨ 此方正在屏幕上看着你哦～"
            findViewById<Button>(R.id.btn_start).isEnabled = false
        } else {
            findViewById<TextView>(R.id.status_text).text = "❌ 权限未完全授予，请重试"
        }
    }
}