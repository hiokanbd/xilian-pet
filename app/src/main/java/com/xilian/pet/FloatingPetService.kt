package com.xilian.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingPetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView
    private lateinit var bubbleView: TextView
    private lateinit var bridge: PetBridge
    private var petLayoutParams: WindowManager.LayoutParams? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null
    private var petSize = DEFAULT_SIZE
    private var isPetVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var bubbleFadeTask: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // ── pet view ──
        petView = PetView(this)
        petView.onSingleTap = { showRandomBubble() }
        petView.onDoubleTap = { showInputDialog() }
        petView.onDrag = { x, y -> movePetTo(x, y) }
        petView.onResize = { scale -> resizePet(scale) }
        petView.onResizeEnd = { commitResize(1f) }
        petView.onSpeechChanged = { text -> updateBubble(text) }

        loadDefaultImages()

        petView.onSwingStateChanged = { updateNotification() }
        petView.onSleepStateChanged = { updateNotification() }
        petView.onShyStateChanged = { updateNotification() }

        startForeground(NOTIFICATION_ID, buildNotification())

        // pet window (with padding for rotation)
        val petWinSize = (petSize * 1.5f).toInt()
        petLayoutParams = WindowManager.LayoutParams(
            petWinSize, petWinSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 300
        }
        windowManager.addView(petView, petLayoutParams)

        // ── bubble view (separate overlay, above pet) ──
        bubbleView = TextView(this).apply {
            setTextColor(0xFF3A3028.toInt())
            setPadding(28, 18, 28, 18)
            textSize = 14f
            maxWidth = dp(280)
            visibility = View.GONE
            val bg = GradientDrawable().apply {
                setColor(0xEEFFFFFA.toInt())
                cornerRadius = 18f
                setStroke(2, 0xFFD4C5B2.toInt())
            }
            background = bg
        }
        bubbleLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }
        windowManager.addView(bubbleView, bubbleLayoutParams)

        bridge = PetBridge(this, petView)
        bridge.start()
    }

    private fun loadDefaultImages() {
        try {
            val states = mapOf(
                "open" to R.drawable.pet_open,
                "half" to R.drawable.pet_half,
                "closed" to R.drawable.pet_closed,
                "sleep" to R.drawable.pet_sleep,
                "shy" to R.drawable.pet_shy,
                "read" to R.drawable.pet_read,
                "swing0" to R.drawable.pet_swing0,
                "swing1" to R.drawable.pet_swing1,
                "swing2" to R.drawable.pet_swing2
            )
            states.forEach { (key, resId) ->
                val drawable = resources.getDrawable(resId, theme)
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    petView.setStateBitmap(key, drawable.bitmap)
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateBubble(text: String?) {
        handler.removeCallbacks(bubbleFadeTask ?: return)
        if (text.isNullOrEmpty()) {
            bubbleView.visibility = View.GONE
            return
        }
        bubbleView.text = text
        bubbleView.visibility = View.VISIBLE
        positionBubble()
        // schedule fade
        if (!petView.isChatting) {
            bubbleFadeTask = Runnable { bubbleView.visibility = View.GONE }
            handler.postDelayed(bubbleFadeTask!!, 4000L)
        }
    }

    private fun positionBubble() {
        petLayoutParams?.let { plp ->
            val petCenterX = plp.x + (plp.width / 2)
            val petTopY = plp.y + (plp.height / 4) // top area of pet window
            val bw = bubbleView.width.takeIf { it > 0 } ?: dp(200)
            bubbleLayoutParams?.x = (petCenterX - bw / 2).coerceAtLeast(0)
            bubbleLayoutParams?.y = (petTopY - dp(80)).coerceAtLeast(0)
            windowManager.updateViewLayout(bubbleView, bubbleLayoutParams)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggleVisibility()
            ACTION_OPACITY_UP -> adjustOpacity(+0.1f)
            ACTION_OPACITY_DOWN -> adjustOpacity(-0.1f)
            ACTION_HIDE -> { isPetVisible = false; petView.visibility = View.GONE; bubbleView.visibility = View.GONE; updateNotification() }
            ACTION_SHOW -> { isPetVisible = true; petView.visibility = View.VISIBLE; updateNotification() }
            ACTION_SWING -> petView.toggleSwing()
            ACTION_SLEEP -> petView.toggleSleep()
            ACTION_SHY -> petView.triggerShy()
            ACTION_READ -> petView.toggleRead()
            ACTION_CONTROLS -> startActivity(Intent(this, ControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return START_STICKY
    }

    private fun movePetTo(rawX: Float, rawY: Float) {
        petLayoutParams?.let { lp ->
            lp.x = rawX.toInt() - lp.width / 2
            lp.y = rawY.toInt() - lp.height / 2 - STATUS_BAR_OFFSET
            windowManager.updateViewLayout(petView, lp)
            positionBubble()
        }
    }

    private fun resizePet(scale: Float) {
        petLayoutParams?.let { lp ->
            val baseWin = (petSize * 1.5f * scale).toInt().coerceIn(MIN_WIN, MAX_WIN)
            lp.width = baseWin; lp.height = baseWin
            windowManager.updateViewLayout(petView, lp)
        }
    }

    fun commitResize(scale: Float) {
        petLayoutParams?.let { lp ->
            petSize = (lp.width / 1.5f).toInt()
        }
    }

    private fun showRandomBubble() {
        updateBubble(petView.randomPhrase())
    }

    private fun showInputDialog() {
        startActivity(Intent(this, InputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleVisibility() {
        isPetVisible = !isPetVisible
        petView.visibility = if (isPetVisible) View.VISIBLE else View.GONE
        if (!isPetVisible) bubbleView.visibility = View.GONE
        updateNotification()
    }

    private fun adjustOpacity(delta: Float) {
        petView.petAlpha = petView.petAlpha + delta
        bubbleView.alpha = petView.petAlpha
        updateNotification()
    }

    private fun buildNotification(): Notification {
        val toggleLabel = if (isPetVisible) "隐藏" else "显示"
        val swingLabel = if (petView.isSwingMode()) "停秋千" else "秋千"
        val sleepLabel = if (petView.isSleepMode()) "醒来" else "睡觉"
        val readLabel = if (petView.isReadMode()) "看完" else "看书"

        val stateText = when {
            petView.isSwingMode() -> "荡秋千中…"
            petView.isSleepMode() -> "Zzz…"
            petView.isShyMode() -> "撒娇中~"
            petView.isReadMode() -> "看书中"
            else -> "陪着伙伴"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("昔涟")
                .setContentText(stateText)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(0, swingLabel, pendingIntent(ACTION_SWING))
                .addAction(0, sleepLabel, pendingIntent(ACTION_SLEEP))
                .addAction(0, readLabel, pendingIntent(ACTION_READ))
                .addAction(0, "控制", pendingIntent(ACTION_CONTROLS))
                .addAction(0, toggleLabel, pendingIntent(
                    if (isPetVisible) ACTION_HIDE else ACTION_SHOW
                ))
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("昔涟")
                .setContentText(stateText)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(this, FloatingPetService::class.java).apply { this.action = action }
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, action.hashCode(), intent, flag)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "昔涟桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "昔涟桌宠控制"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bridge.stop()
        windowManager.removeView(petView)
        windowManager.removeView(bubbleView)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL_ID = "xilian_pet"
        const val NOTIFICATION_ID = 28765
        const val ACTION_TOGGLE = "com.xilian.pet.TOGGLE"
        const val ACTION_OPACITY_UP = "com.xilian.pet.OPACITY_UP"
        const val ACTION_OPACITY_DOWN = "com.xilian.pet.OPACITY_DOWN"
        const val ACTION_HIDE = "com.xilian.pet.HIDE"
        const val ACTION_SHOW = "com.xilian.pet.SHOW"
        const val ACTION_SWING = "com.xilian.pet.SWING"
        const val ACTION_SLEEP = "com.xilian.pet.SLEEP"
        const val ACTION_SHY = "com.xilian.pet.SHY"
        const val ACTION_READ = "com.xilian.pet.READ"
        const val ACTION_CONTROLS = "com.xilian.pet.CONTROLS"
        const val DEFAULT_SIZE = 220
        const val MIN_WIN = 120
        const val MAX_WIN = 900
        const val STATUS_BAR_OFFSET = 80
    }
}
