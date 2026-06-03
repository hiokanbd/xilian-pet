package com.xilian.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class FloatingPetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView
    private lateinit var bridge: PetBridge
    private var layoutParams: WindowManager.LayoutParams? = null
    private var petSize = DEFAULT_SIZE
    private var isPetVisible = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        petView = PetView(this)
        petView.onSingleTap = { showRandomBubble() }
        petView.onDoubleTap = { showInputDialog() }
        petView.onDrag = { x, y -> movePetTo(x, y) }
        petView.onResize = { scale -> resizePet(scale) }
        petView.onResizeEnd = { commitResize(1f) }

        // load default state images
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

        petView.onSwingStateChanged = { updateNotification() }
        petView.onSleepStateChanged = { updateNotification() }
        petView.onShyStateChanged = { updateNotification() }

        startForeground(NOTIFICATION_ID, buildNotification())

        layoutParams = WindowManager.LayoutParams(
            petSize, petSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 400
        }

        windowManager.addView(petView, layoutParams)

        bridge = PetBridge(this, petView)
        bridge.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggleVisibility()
            ACTION_OPACITY_UP -> adjustOpacity(+0.1f)
            ACTION_OPACITY_DOWN -> adjustOpacity(-0.1f)
            ACTION_HIDE -> { isPetVisible = false; petView.visibility = View.GONE; updateNotification() }
            ACTION_SHOW -> { isPetVisible = true; petView.visibility = View.VISIBLE; updateNotification() }
            ACTION_SWING -> petView.toggleSwing()
            ACTION_SLEEP -> petView.toggleSleep()
            ACTION_SHY -> petView.triggerShy()
            ACTION_READ -> petView.toggleRead()
        }
        return START_STICKY
    }

    private fun movePetTo(rawX: Float, rawY: Float) {
        layoutParams?.let { lp ->
            lp.x = rawX.toInt() - petSize / 2
            lp.y = rawY.toInt() - petSize / 2 - STATUS_BAR_OFFSET
            windowManager.updateViewLayout(petView, lp)
        }
    }

    private fun resizePet(scale: Float) {
        layoutParams?.let { lp ->
            val newSize = (petSize * scale).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
            lp.width = newSize; lp.height = newSize
            windowManager.updateViewLayout(petView, lp)
        }
    }

    fun commitResize(scale: Float) {
        layoutParams?.let { lp ->
            petSize = lp.width
        }
    }

    private fun showRandomBubble() {
        petView.speechText = petView.randomPhrase()
    }

    private fun showInputDialog() {
        val intent = Intent(this, InputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toggleVisibility() {
        isPetVisible = !isPetVisible
        petView.visibility = if (isPetVisible) View.VISIBLE else View.GONE
        updateNotification()
    }

    private fun adjustOpacity(delta: Float) {
        petView.petAlpha = petView.petAlpha + delta
        updateNotification()
    }

    // ── notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "昔涟桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "昔涟桌宠运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val toggleAction = if (isPetVisible)
            Notification.Action(0, "隐藏", pendingIntent(ACTION_HIDE))
        else
            Notification.Action(0, "显示", pendingIntent(ACTION_SHOW))

        val swingLabel = if (petView.isSwingMode()) "停秋千" else "秋千"
        val sleepLabel = if (petView.isSleepMode()) "醒来" else "睡觉"
        val shyLabel = if (petView.isShyMode()) "好啦" else "撒娇"

        val stateText = when {
            petView.isSwingMode() -> "荡秋千中…"
            petView.isSleepMode() -> "Zzz…"
            petView.isShyMode() -> "撒娇中~"
            else -> "陪着伙伴"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("昔涟")
                .setContentText("$stateText (${(petView.petAlpha * 100).toInt()}%)")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(0, swingLabel, pendingIntent(ACTION_SWING))
                .addAction(0, sleepLabel, pendingIntent(ACTION_SLEEP))
                .addAction(0, shyLabel, pendingIntent(ACTION_SHY))
                .addAction(0, if (petView.isReadMode()) "看完" else "看书", pendingIntent(ACTION_READ))
                .addAction(0, "透明-", pendingIntent(ACTION_OPACITY_DOWN))
                .addAction(0, "透明+", pendingIntent(ACTION_OPACITY_UP))
                .addAction(toggleAction)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("昔涟")
                .setContentText("陪着伙伴")
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bridge.stop()
        windowManager.removeView(petView)
        super.onDestroy()
    }

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
        const val DEFAULT_SIZE = 240
        const val MIN_SIZE = 80
        const val MAX_SIZE = 600
        const val STATUS_BAR_OFFSET = 80
    }
}
