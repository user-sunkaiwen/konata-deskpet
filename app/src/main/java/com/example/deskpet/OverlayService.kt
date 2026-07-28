package com.example.deskpet

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.*
import android.os.FileObserver

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    companion object {
        private const val CHANNEL_ID = "konata_pet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_W_DP = 200
        private const val PET_H_DP = 260
    }

    // Touch
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var lastTapInWindow = 0L

    // Usage tracker
    private var usageTimer: Timer? = null
    private var lastApp = ""

    // Screenshot observer
    private var screenshotObservers = mutableListOf<FileObserver>()

    // Whisper
    private val whisperHandler = Handler(Looper.getMainLooper())
    private val WHISPER_INTERVAL = 1800_000L // 30 min

    // Idle timer
    private var idleHandler = Handler(Looper.getMainLooper())
    private var idleMinutes = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("此方在此～"))
        setupOverlay()
        startWhisperRotation()
        startUsageTracking()
        startScreenshotObservation()
        startIdleTimer()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_W_DP),
            dpToPx(PET_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(200)
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(KonataBridge(), "KonataBridge")
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    inner class KonataBridge {
        @JavascriptInterface
        fun onGesture(gestureType: String) {
            // Reset idle timer on any interaction
            resetIdleTimer()
        }
    }

    // ===== GESTURES =====

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> callJs("onLongPress()")
                            System.currentTimeMillis() - lastTapTime < 350 -> {
                                tapCount++
                                lastTapTime = 0L
                                if (tapCount >= 3) {
                                    callJs("onTripleTap()")
                                    tapCount = 0
                                } else {
                                    callJs("onDoubleTap()")
                                }
                            }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                if (System.currentTimeMillis() - lastTapInWindow > 2000) {
                                    tapCount = 1
                                }
                                lastTapInWindow = System.currentTimeMillis()
                                callJs("onTap()")
                            }
                        }
                    } else {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        val vel = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (vel > 300 && elapsed < 500) {
                            callJs("onFling()")
                        } else {
                            callJs("onDragEnd()")
                        }
                    }
                    resetIdleTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun callJs(func: String) {
        overlayView?.post {
            overlayView?.evaluateJavascript("window.petEngine && window.petEngine.$func", null)
        }
    }

    // ===== USAGE TRACKING =====

    private fun startUsageTracking() {
        usageTimer = Timer()
        usageTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val current = getForegroundApp()
                if (current != lastApp && current.isNotEmpty()) {
                    lastApp = current
                    val appName = getAppName(current)
                    val js = when {
                        current.contains("douyin") || current.contains("tiktok") -> "onAppChanged('看抖音呢～都不理我')"
                        current.contains("taobao") || current.contains("jd") || current.contains("pinduoduo") -> "onAppChanged('又在买买买！')"
                        current.contains("weixin") || current.contains("qq") -> "onAppChanged('跟谁聊天呢～')"
                        current.contains("bilibili") -> "onAppChanged('看B站也不带我！')"
                        current.contains("game") || current.contains("王者") || current.contains("genshin") -> "onAppChanged('打游戏怎么不叫我！')"
                        current.contains("chrome") || current.contains("browser") -> "onAppChanged('在搜什么呢～')"
                        else -> "onAppChanged('你在用${appName}呢')"
                    }
                    overlayView?.post {
                        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.$js", null)
                    }
                }
            }
        }, 3000, 3000)
    }

    private fun getForegroundApp(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = android.app.usage.UsageEvents.Event()
            var foreground = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foreground = event.packageName
                }
            }
            foreground
        } catch (e: Exception) { "" }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val app = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(app).toString()
        } catch (e: Exception) { packageName }
    }

    // ===== SCREENSHOT DETECTION =====

    private fun startScreenshotObservation() {
        val paths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).resolve("Screenshots").absolutePath,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).resolve("Screenshots").absolutePath,
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        )
        for (path in paths) {
            val dir = File(path)
            if (!dir.exists()) continue
            val observer = object : FileObserver(dir.absolutePath, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && (path.lowercase().endsWith(".png") || path.lowercase().endsWith(".jpg"))) {
                        overlayView?.post {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onScreenshot()", null
                            )
                        }
                    }
                }
            }
            observer.startWatching()
            screenshotObservers.add(observer)
        }
    }

    // ===== NOTIFICATION WHISPERS =====

    private fun startWhisperRotation() {
        whisperHandler.postDelayed(object : Runnable {
            override fun run() {
                updateNotification()
                whisperHandler.postDelayed(this, WHISPER_INTERVAL)
            }
        }, WHISPER_INTERVAL)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val whispers = when {
            hour in 0..5 -> listOf("还不睡吗…明天会起不来的哦", "已经凌晨了诶……", "熬夜对身体不好啦")
            hour in 6..8 -> listOf("早安～今天也要元气满满！", "早上好！今天有什么计划？")
            hour in 12..14 -> listOf("午饭时间到了哦～", "吃饭了吗！")
            else -> listOf("我就在这里陪着你～", "戳我一下试试？", "无聊的话就找我玩吧！", "在看什么呢？")
        }
        return whispers.random()
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83E\uDD8A 此方")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "此方桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ===== IDLE TIMER =====

    private fun startIdleTimer() {
        idleMinutes = 0
        idleHandler.postDelayed(object : Runnable {
            override fun run() {
                idleMinutes++
                when (idleMinutes) {
                    5 -> callJs("onIdle('偷看中…')")
                    10 -> callJs("onIdle('好无聊啊～')")
                    15 -> callJs("onIdle('zzz…')")
                    20 -> {
                        callJs("onIdle('睡着了…')")
                        callJs("onSleep()")
                    }
                }
                idleHandler.postDelayed(this, 60000)
            }
        }, 60000)
    }

    private fun resetIdleTimer() {
        idleMinutes = 0
        idleHandler.removeCallbacksAndMessages(null)
        startIdleTimer()
    }

    // ===== UTILS =====

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        usageTimer?.cancel()
        for (obs in screenshotObservers) obs.stopWatching()
        screenshotObservers.clear()
        whisperHandler.removeCallbacksAndMessages(null)
        idleHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}