package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlin.math.abs

class VolumeOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "volume_overlay_channel"
        private const val NOTIFICATION_ID = 1042
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: VolumeButtonView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var audioManager: AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        if (::overlayView.isInitialized) {
            overlayView.animate()
                .alpha(0.18f)
                .setDuration(400)
                .start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        startServiceForeground()
        buildFloatingIcon()
        resetFadeTimer()
    }

    private fun resetFadeTimer() {
        if (::overlayView.isInitialized) {
            overlayView.animate()
                .alpha(0.85f)
                .setDuration(150)
                .start()
        }
        mainHandler.removeCallbacks(fadeRunnable)
        mainHandler.postDelayed(fadeRunnable, 3000) // Fades to low visibility after 3 seconds of idle
    }

    private fun buildFloatingIcon() {
        // Overlay standard parameters
        params = WindowManager.LayoutParams(
            132, // Width in pixels (approx 48dp on standard densities, but we coerce it below)
            132, // Height in pixels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 500
        }

        // Convert dp to pixels for standard circular touch target sizing (60dp)
        val density = resources.displayMetrics.density
        val sizeInPx = (58 * density).toInt()
        params.width = sizeInPx
        params.height = sizeInPx

        overlayView = VolumeButtonView(this)
        overlayView.alpha = 0.85f

        // Touch dynamics
        overlayView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                resetFadeTimer()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                        }

                        if (isDragging) {
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            try {
                                windowManager.updateViewLayout(overlayView, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Tapped! Show standard system volume HUD on screen immediately
                            try {
                                audioManager.adjustSuggestedStreamVolume(
                                    AudioManager.ADJUST_SAME,
                                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                                    AudioManager.FLAG_SHOW_UI
                                )
                            } catch (e: Exception) {
                                // Fallback stream type music adjustment to trigger native panel
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_SAME,
                                    AudioManager.FLAG_SHOW_UI
                                )
                            }
                        }
                        isDragging = false
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        resetFadeTimer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(fadeRunnable)
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: Exception) {}
        }
    }

    private fun startServiceForeground() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Volume Overlay Service"
            // Using IMPORTANCE_MIN to completely prevent status bar icon clutter and minimize active notification footprint
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Enables floating volume controller"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            CHANNEL_ID
        } else {
            ""
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VolumeOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val appLabel = getString(R.string.app_name)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("$appLabel Running")
            .setContentText("Tap overlay to toggle system volume HUD.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Button", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Hides status bar clutter on older Android versions as well
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}

// Incredibly lightweight custom canvas-drawn vector view for optimal performance
class VolumeButtonView(context: Context) : View(context) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 120f, 120f,
            Color.parseColor("#4B5EE3"), Color.parseColor("#0FC3E0"),
            android.graphics.Shader.TileMode.CLAMP
        )
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2.0f
        val cy = height / 2.0f
        val r = (width.coerceAtMost(height) / 2.0f)

        // Subtle bottom shadow
        canvas.drawCircle(cx, cy + (r * 0.06f), r * 0.90f, shadowPaint)

        // Modern primary base
        canvas.drawCircle(cx, cy, r * 0.86f, circlePaint)

        // Programmatic perfectly symmetrical vector speaker
        path.reset()
        
        val boxLeft = cx - r * 0.35f
        val boxRight = cx - r * 0.15f
        val flareRight = cx + r * 0.05f
        val boxHeightHalf = r * 0.12f
        val flareHeightHalf = r * 0.32f

        path.moveTo(boxLeft, cy - boxHeightHalf)
        path.lineTo(boxRight, cy - boxHeightHalf)
        path.lineTo(flareRight, cy - flareHeightHalf)
        path.lineTo(flareRight, cy + flareHeightHalf)
        path.lineTo(boxRight, cy + boxHeightHalf)
        path.lineTo(boxLeft, cy + boxHeightHalf)
        path.close()
        
        iconPaint.style = Paint.Style.FILL
        canvas.drawPath(path, iconPaint)

        // Audio propagation wave lines (concentric around a common focus)
        val waveCenter = cx - r * 0.10f
        val strokeW = r * 0.07f
        
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = strokeW
        iconPaint.strokeCap = Paint.Cap.ROUND

        // Small wave (radius 0.25f)
        val r1 = r * 0.25f
        canvas.drawArc(
            waveCenter - r1, cy - r1, waveCenter + r1, cy + r1,
            -40f, 80f, false, iconPaint
        )

        // Large wave (radius 0.45f)
        val r2 = r * 0.45f
        canvas.drawArc(
            waveCenter - r2, cy - r2, waveCenter + r2, cy + r2,
            -40f, 80f, false, iconPaint
        )
        
        iconPaint.style = Paint.Style.FILL
    }
}
