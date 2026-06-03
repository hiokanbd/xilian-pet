package com.xilian.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.atan2

class PetView(context: Context) : View(context) {

    enum class Expression { NEUTRAL, HAPPY, THINKING, SLEEPY, BLUSH, SHY }

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onResize: ((Float) -> Unit)? = null
    var onResizeEnd: (() -> Unit)? = null
    var onSpeechChanged: ((String?) -> Unit)? = null
    var onWanderMove: ((Float, Float) -> Unit)? = null  // dx, dy for wander

    var expression = Expression.NEUTRAL
        set(value) { field = value; invalidate() }
    var speechText: String? = null
        set(value) {
            field = value
            onSpeechChanged?.invoke(value)
            if (!isChatting) scheduleBubbleFade()
        }

    var petAlpha = 1f
        set(value) { field = value.coerceIn(0.1f, 1f); invalidate() }

    // image slots — one Bitmap per state. at minimum open/half/closed for blink
    private val stateBitmaps = mutableMapOf<String, Bitmap>()

    fun setStateBitmap(state: String, bitmap: Bitmap?) {
        if (bitmap != null) stateBitmaps[state] = bitmap
        else stateBitmaps.remove(state)
        invalidate()
    }

    fun clearAllBitmaps() { stateBitmaps.clear(); invalidate() }

    // drawing
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF5C4B3C.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xEEFFFFFA.toInt()
    }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFD4C5B2.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3028.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x33E88B9D.toInt()
    }

    // animation state
    private var swayOffset = 0f
    private var stretchScale = 1f
    private var turnAngle = 0f
    private var userRotation = 0f
    private var bubbleAlpha = 0f
    private var idleTimer = 0L
    private var currentIdleAnim: ValueAnimator? = null

    // blink animation
    private var blinkFrameIndex = 0
    private var blinkAnimRunning = false

    // swing animation
    private var swingFrameIndex = 0
    private var swingOffsetX = 0f       // horizontal displacement during swing
    private var swingMode = false       // continuous swing loop
    private var swingAnimRunning = false // brief idle swing
    private var swingAnimator: ValueAnimator? = null

    // wander state
    private var wanderAnimator: ValueAnimator? = null
    private var lastWanderTime = 0L
    private var wanderFirstDone = false
    private val wanderBubbles = listOf(
        "啦啦啦~", "嗯…去哪呢…", "人家到处走走…", "这里风景不错呢",
        "一蹦一跳~", "伙伴在看着吗？", "哼着小曲~", "世界好大呢…"
    )

    // two-tier idle system
    private var idleTier = 0       // 0=normal, 1=tier1(read/shy), 2=tier2(swing/sleep)
    private var idleAction = ""    // "read","shy","swing","sleep"
    private var tierEnteredAt = 0L
    var isChatting = false
    var freezeMode = false
    var wanderEnabled = true
    var wanderBubblesOn = true
    var screenW = 1080
    var screenH = 1920
    var currentWinX = 0   // current window X in screen coords
    var currentWinY = 0   // current window Y in screen coords
    private var sleepTimer: Runnable? = null
    private var shyTimer: Runnable? = null
    private var pinchDirChanges = 0
    private var lastPinchDist = 0f
    private var pinchDir = 0  // 0=none, 1=zooming in, -1=zooming out

    var onSwingStateChanged: ((Boolean) -> Unit)? = null
    var onSleepStateChanged: ((Boolean) -> Unit)? = null
    var onShyStateChanged: ((Boolean) -> Unit)? = null

    // gesture state
    private var touchStartTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var isPinching = false
    private var pinchStartDist = 0f
    private var pinchStartAngle = 0f
    private var pinchStartRotation = 0f
    private var pinchStartSize = 0f
    private var touchHitPet = false
    private var prePinchSize = 0
    private var pinchHappened = false  // true if any resize occurred during pinch
    private var lastTapTime = 0L
    private var pendingSingleTap: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    // random phrases
    private val phrases = listOf(
        "伙伴~", "嗯？", "在呢", "想你了", "嘻嘻", "今天天气真好",
        "人家在看着你哦", "累了就休息一下嘛", "三千万世的轮回,只为这一刻的相遇",
        "伙伴最好啦", "诶嘿~", "不许欺负人家", "温柔地注视着你",
        "人家有句悄悄话...", "哼", "呀,被发现了"
    )
    private val sleepyPhrases = listOf(
        "呼…别打扰人家…", "好困…让睡一会儿嘛…", "Zzz…嗯…伙伴？",
        "人家正在梦里跟你聊天呢…", "再睡五分钟…", "不要摇人家啦…"
    )

    init { startIdleCheck() }

    private fun cancelIdle() {
        currentIdleAnim?.cancel()
        swayOffset = 0f; stretchScale = 1f; turnAngle = 0f
        blinkFrameIndex = 0; blinkAnimRunning = false
        invalidate()
    }

    private fun enterTier1() {
        if (idleTier >= 1 || isChatting) return
        val options = mutableListOf<String>()
        if (stateBitmaps["read"] != null) options.add("read")
        if (stateBitmaps["shy"] != null) options.add("shy")
        if (options.isEmpty()) return
        idleAction = options.random()
        idleTier = 1; tierEnteredAt = System.currentTimeMillis()
        invalidate()
    }

    private fun enterTier2() {
        if (idleTier >= 2 || isChatting) return
        val options = mutableListOf<String>()
        if (stateBitmaps["swing0"] != null) options.add("swing")
        if (stateBitmaps["sleep"] != null) options.add("sleep")
        if (options.isEmpty()) return
        idleAction = options.random()
        idleTier = 2; tierEnteredAt = System.currentTimeMillis()
        if (idleAction == "swing") startSwingLoop()
        onSleepStateChanged?.invoke(idleAction == "sleep")
        invalidate()
    }

    private fun actionBubble(): String? {
        return when (idleAction) {
            "read" -> listOf("人家正在看书呢…","这本书很有意思呢…","嘘…别吵…").random()
            "shy" -> listOf("哼，不理你了…","别这样盯着人家看啦…","人家害羞了…").random()
            "swing" -> listOf("呼~好舒服…","荡秋千真开心…","风吹得人真舒服…").random()
            "sleep" -> sleepyPhrases.random()
            else -> null
        }
    }

    // ── swing animation ──

    private fun startSwingLoop() {
        val seq = intArrayOf(0, 1, 2, 1, 0, 1, 2, 1)
        applySwingOffset(0)
        var step = 0
        swingAnimator = ValueAnimator.ofInt(0, seq.size - 1).apply {
            duration = 500L * seq.size
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                val idx = seq[it.animatedValue as Int]
                swingFrameIndex = idx; applySwingOffset(idx); invalidate()
            }
            start()
        }
    }

    private fun applySwingOffset(idx: Int) {
        val baseR = minOf(width, height) * 0.26f
        swingOffsetX = when (idx) {
            0 -> -baseR * 0.35f  // left
            1 -> 0f              // center
            2 -> baseR * 0.35f   // right
            else -> 0f
        }
    }

    private fun animateSwingIdle() {
        if (swingMode || swingAnimRunning) return
        val hasAll = stateBitmaps["swing0"] != null && stateBitmaps["swing1"] != null && stateBitmaps["swing2"] != null
        if (!hasAll) return
        swingAnimRunning = true
        // two full oscillations then auto-stop
        val seq = intArrayOf(0, 1, 2, 1, 0, 1, 2, 1, 0)
        applySwingOffset(0)
        currentIdleAnim = ValueAnimator.ofInt(0, seq.size - 1).apply {
            duration = 500L * seq.size
            addUpdateListener {
                val idx = seq[it.animatedValue as Int]
                swingFrameIndex = idx; applySwingOffset(idx); invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    swingFrameIndex = 0; swingOffsetX = 0f; swingAnimRunning = false; invalidate()
                }
            })
            start()
        }
    }

    fun isSwingMode(): Boolean = idleAction == "swing"
    fun isSleepMode(): Boolean = idleAction == "sleep"
    fun isShyMode(): Boolean = idleAction == "shy"
    fun isReadMode(): Boolean = idleAction == "read"

    fun forceAction(action: String) {
        if (isChatting) return
        if (idleAction == action) { exitIdleAction(); return }
        exitIdleAction()
        // reset ALL timers so the action gets its full duration
        idleTimer = System.currentTimeMillis()
        tierEnteredAt = System.currentTimeMillis()
        if (action == "read" || action == "shy") {
            idleTier = 1; idleAction = action
        } else if (action == "swing" || action == "sleep") {
            idleTier = 2; idleAction = action
            if (action == "swing") startSwingLoop()
        }
        invalidate()
        onSleepStateChanged?.invoke(idleAction == "sleep")
        onShyStateChanged?.invoke(idleAction == "shy")
        onSwingStateChanged?.invoke(idleAction == "swing")
    }

    fun toggleRead() { forceAction("read") }
    fun toggleSleep() { forceAction("sleep") }
    fun triggerShy() { forceAction("shy") }
    fun toggleSwing() { forceAction("swing") }

    fun enterSleep() { forceAction("sleep") }
    fun wakeUp() { if (idleAction == "sleep") exitIdleAction() }
    fun startRead() { forceAction("read") }

    fun toggleFreeze() {
        if (freezeMode) { freezeMode = false; exitIdleAction() }
        else if (idleTier > 0) {
            freezeMode = true
            lastWanderTime = System.currentTimeMillis()
            wanderFirstDone = false
        }
    }

    fun isFrozen(): Boolean = freezeMode

    // ── wander: donut zone around screen edges ──

    /** rejection sample a uniform point in the donut area (outer 30% screen edges) */
    private fun pickWanderTarget(): Pair<Float, Float> {
        val margin = 0.30f
        val innerL = screenW * margin; val innerR = screenW * (1f - margin)
        val innerT = screenH * margin; val innerB = screenH * (1f - margin)
        // rejection sample: keep trying until we land outside the inner rect
        var x: Float; var y: Float
        do {
            x = (Math.random() * screenW).toFloat()
            y = (Math.random() * screenH).toFloat()
        } while (x in innerL..innerR && y in innerT..innerB)
        return Pair(x, y)
    }

    private fun maybeWander() {
        if (wanderAnimator?.isRunning == true) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastWanderTime
        val shouldWander = if (!wanderFirstDone) {
            elapsed > 20_000L
        } else {
            elapsed > 10_000L && Math.random() < 0.5
        }

        if (!shouldWander) { animateSway(); return }

        wanderFirstDone = true
        lastWanderTime = now

        val (tx, ty) = pickWanderTarget()
        // compute total delta from current window position to target
        val totalDx = tx - currentWinX - screenW / 2f  // adjust for window center
        val totalDy = ty - currentWinY - screenH / 2f

        if (wanderBubblesOn && Math.random() < 0.25) speechText = wanderBubbles.random()

        var lastX = 0f; var lastY = 0f
        wanderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val ox = totalDx * t; val oy = totalDy * t
                val hop = (kotlin.math.sin(t * Math.PI) * 12f).toFloat()
                swayOffset = (kotlin.math.sin(t * 3 * Math.PI) * 6f).toFloat()
                onWanderMove?.invoke(ox - lastX, (oy - lastY) + hop)
                lastX = ox; lastY = oy
                invalidate()
            }
            start()
        }
    }

    fun randomAction() {
        val all = mutableListOf<String>()
        if (stateBitmaps["read"] != null) all.add("read")
        if (stateBitmaps["shy"] != null) all.add("shy")
        if (stateBitmaps["swing0"] != null) all.add("swing")
        if (stateBitmaps["sleep"] != null) all.add("sleep")
        if (all.isNotEmpty()) forceAction(all.random())
    }

    private fun exitIdleAction() {
        swingAnimator?.cancel(); swingAnimator = null
        swingFrameIndex = 0; swingOffsetX = 0f
        idleTier = 0; idleAction = ""; tierEnteredAt = 0L
        invalidate()
        onSleepStateChanged?.invoke(false)
        onShyStateChanged?.invoke(false)
        onSwingStateChanged?.invoke(false)
    }

    // ── two-tier idle timer ──

    private var idleCheckRunnable: Runnable? = null

    private fun startIdleCheck() {
        idleCheckRunnable?.let { handler.removeCallbacks(it) }
        idleCheckRunnable = object : Runnable {
            override fun run() {
                if (isChatting) { handler.postDelayed(this, 2000L); return }
                if (freezeMode) {
                    if (wanderEnabled) maybeWander()
                    else animateSway()  // in-place sway when wander off
                    handler.postDelayed(this, 2000L); return
                }
                val elapsed = System.currentTimeMillis() - idleTimer
                // tier progression
                if (idleTier == 0) {
                    if (elapsed > 60000L) enterTier2()
                    else if (elapsed > 30000L) enterTier1()
                    else if (Math.random() < 0.2) animateBlink()
                } else if (idleTier == 1) {
                    // tier 1 → tier 2 after 30s in tier 1
                    if (System.currentTimeMillis() - tierEnteredAt > 30000L) enterTier2()
                }
                // tier 2 stays until pinch-interrupted
                handler.postDelayed(this, 2000L)
            }
        }
        handler.postDelayed(idleCheckRunnable!!, 2000L)
    }

    private fun animateSway() {
        currentIdleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; repeatCount = 3; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { swayOffset = (it.animatedValue as Float - 0.5f) * 16f; invalidate() }
            start()
        }
    }

    private fun animateBlink() {
        val open = stateBitmaps["open"] ?: return
        val half = stateBitmaps["half"] ?: return
        val closed = stateBitmaps["closed"] ?: return
        if (blinkAnimRunning) return
        blinkAnimRunning = true
        // sequence: open→half→closed→half→open
        val sequence = intArrayOf(0, 1, 2, 1, 0)
        currentIdleAnim = ValueAnimator.ofInt(0, sequence.size - 1).apply {
            duration = 150L * sequence.size
            addUpdateListener {
                blinkFrameIndex = sequence[it.animatedValue as Int]
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    blinkFrameIndex = 0; blinkAnimRunning = false; invalidate()
                }
            })
            start()
        }
    }

    private fun animateStretch() {
        currentIdleAnim = ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 400; repeatMode = ValueAnimator.REVERSE
            interpolator = OvershootInterpolator()
            addUpdateListener { stretchScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateTurn() {
        currentIdleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { turnAngle = (it.animatedValue as Float - 0.5f) * 14f; invalidate() }
            start()
        }
    }

    // ── speech bubble ──

    private fun scheduleBubbleFade() {
        handler.removeCallbacks(bubbleFader)
        handler.postDelayed(bubbleFader, 4000L)
    }

    private val bubbleFader = Runnable {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600; interpolator = DecelerateInterpolator()
            addUpdateListener { bubbleAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ── resolve current bitmap ──

    private fun currentBitmap(): Bitmap? {
        if (idleAction == "swing") {
            val key = when (swingFrameIndex) {
                0 -> "swing0"; 1 -> "swing1"; 2 -> "swing2"; else -> "swing0"
            }
            return stateBitmaps[key] ?: stateBitmaps["open"]
        }
        if (idleAction == "sleep" && stateBitmaps["sleep"] != null) return stateBitmaps["sleep"]
        if (idleAction == "read" && stateBitmaps["read"] != null) return stateBitmaps["read"]
        if (idleAction == "shy" && stateBitmaps["shy"] != null) return stateBitmaps["shy"]
        // blink animation in normal mode
        if (blinkAnimRunning && stateBitmaps.size >= 3) {
            val key = when (blinkFrameIndex) {
                0 -> "open"; 1 -> "half"; 2 -> "closed"; else -> "open"
            }
            return stateBitmaps[key]
        }
        return stateBitmaps["open"]
    }

    // ── drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = minOf(width, height) * 0.26f
        val r = baseR * stretchScale

        val layerAlpha = (petAlpha * 255).toInt()
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), layerAlpha)

        val bmp = currentBitmap()
        if (bmp != null) {
            canvas.save()
            canvas.translate(cx + swayOffset + swingOffsetX, cy)
            canvas.scale(stretchScale, stretchScale)
            canvas.rotate(turnAngle + userRotation)

            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            val imgScale = (r * 2.8f) / maxOf(bw, bh)
            val dst = RectF(-bw * imgScale / 2f, -bh * imgScale / 2f, bw * imgScale / 2f, bh * imgScale / 2f)
            canvas.drawBitmap(bmp, null, dst, null)
            canvas.restore()
        } else {
            // Canvas fallback
            canvas.save()
            canvas.translate(cx + swayOffset + swingOffsetX, cy)
            canvas.rotate(turnAngle + userRotation)

            drawHair(canvas, 0f, 0f, r)
            fillPaint.color = 0xFFFFF5EE.toInt(); canvas.drawCircle(0f, 0f, r, fillPaint)
            facePaint.strokeWidth = 3f; canvas.drawCircle(0f, 0f, r, facePaint)
            if (expression == Expression.BLUSH || expression == Expression.SHY) {
                canvas.drawCircle(-r * 0.55f, r * 0.35f, r * 0.18f, blushPaint)
                canvas.drawCircle(r * 0.55f, r * 0.35f, r * 0.18f, blushPaint)
            }
            drawEyes(canvas, r)
            drawMouth(canvas, r)
            drawBangs(canvas, 0f, 0f, r)
            drawBody(canvas, 0f, r)
            canvas.restore()
        }

        canvas.restore() // close saveLayerAlpha
    }

    private fun drawHair(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3A2A1A.toInt() }
        val path = Path()
        path.addArc(cx - r - 4f, cy - r - 4f, cx + r + 4f, cy + r * 0.2f, 180f, 180f)
        path.lineTo(cx + r * 0.85f, cy - r * 1.25f)
        path.quadTo(cx + r * 0.8f, cy - r * 1.55f, cx + r * 0.3f, cy - r * 1.4f)
        path.quadTo(cx, cy - r * 1.5f, cx - r * 0.4f, cy - r * 1.35f)
        path.quadTo(cx - r * 0.85f, cy - r * 1.4f, cx - r - 2f, cy - r * 0.6f)
        path.close(); canvas.drawPath(path, p)
    }

    private fun drawBangs(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3A2A1A.toInt() }
        val path = Path()
        path.moveTo(cx - r * 0.95f, cy - r * 0.5f)
        path.quadTo(cx - r * 0.7f, cy - r * 0.92f, cx - r * 0.2f, cy - r * 0.7f)
        path.quadTo(cx + r * 0.05f, cy - r * 0.9f, cx + r * 0.45f, cy - r * 0.6f)
        path.quadTo(cx + r * 0.7f, cy - r * 0.8f, cx + r * 0.9f, cy - r * 0.45f)
        path.lineTo(cx + r * 0.95f, cy - r * 0.15f)
        path.quadTo(cx + r * 0.6f, cy - r * 0.3f, cx + r * 0.2f, cy - r * 0.25f)
        path.quadTo(cx - r * 0.1f, cy - r * 0.2f, cx - r * 0.4f, cy - r * 0.3f)
        path.quadTo(cx - r * 0.7f, cy - r * 0.2f, cx - r * 0.9f, cy - r * 0.2f)
        path.close(); canvas.drawPath(path, p)
    }

    private fun drawEyes(canvas: Canvas, r: Float) {
        val blink = 0f
        val eyeY = -r * 0.15f; val eyeR = r * 0.1f; val eyeSpacing = r * 0.35f
        when (expression) {
            Expression.HAPPY -> {
                facePaint.strokeWidth = 3f
                val path = Path()
                path.moveTo(-eyeSpacing - eyeR * 2, eyeY + eyeR * 0.5f)
                path.quadTo(-eyeSpacing, eyeY - eyeR * 1.2f, -eyeSpacing + eyeR * 2, eyeY + eyeR * 0.5f)
                canvas.drawPath(path, facePaint)
                path.reset()
                path.moveTo(eyeSpacing - eyeR * 2, eyeY + eyeR * 0.5f)
                path.quadTo(eyeSpacing, eyeY - eyeR * 1.2f, eyeSpacing + eyeR * 2, eyeY + eyeR * 0.5f)
                canvas.drawPath(path, facePaint)
            }
            Expression.SLEEPY -> {
                facePaint.strokeWidth = 2f
                canvas.drawLine(-eyeSpacing - eyeR, eyeY, -eyeSpacing + eyeR, eyeY, facePaint)
                canvas.drawLine(eyeSpacing - eyeR, eyeY, eyeSpacing + eyeR, eyeY, facePaint)
            }
            else -> {
                fillPaint.color = 0xFF3A3028.toInt()
                canvas.drawCircle(-eyeSpacing, eyeY, eyeR * (1f - blink), fillPaint)
                canvas.drawCircle(eyeSpacing, eyeY, eyeR * (1f - blink), fillPaint)
                fillPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(-eyeSpacing + eyeR * 0.3f, eyeY - eyeR * 0.35f, eyeR * 0.3f, fillPaint)
                canvas.drawCircle(eyeSpacing + eyeR * 0.3f, eyeY - eyeR * 0.35f, eyeR * 0.3f, fillPaint)
            }
        }
    }

    private fun drawMouth(canvas: Canvas, r: Float) {
        val my = r * 0.38f; val mw = r * 0.22f
        facePaint.strokeWidth = 2.5f
        when (expression) {
            Expression.HAPPY -> { val path = Path(); path.arcTo(-mw, my - mw * 0.3f, mw, my + mw * 0.8f, 0f, -180f, false); canvas.drawPath(path, facePaint) }
            Expression.THINKING -> { canvas.drawCircle(r * 0.3f, my + mw * 0.4f, mw * 0.35f, fillPaint.apply { color = 0xFF3A3028.toInt() }) }
            Expression.SLEEPY -> { fillPaint.color = 0xFF3A3028.toInt(); canvas.drawOval(-mw * 0.5f, my, mw * 0.5f, my + mw * 0.7f, fillPaint) }
            Expression.BLUSH -> { val path = Path(); path.arcTo(-mw * 0.7f, my - mw * 0.2f, mw * 0.7f, my + mw * 0.6f, 0f, -180f, false); canvas.drawPath(path, facePaint) }
            Expression.SHY -> { canvas.drawLine(-mw * 0.6f, my + mw * 0.3f, mw * 0.6f, my, facePaint) }
            else -> { canvas.drawLine(-mw * 0.5f, my, mw * 0.5f, my, facePaint) }
        }
    }

    private fun drawBody(canvas: Canvas, cx: Float, headBottom: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFF5C4B3C.toInt() }
        val bw = headBottom * 0.45f; val bh = headBottom * 0.5f; val top = headBottom + 6f
        val path = Path()
        path.moveTo(cx - bw * 0.3f, top); path.quadTo(cx - bw * 0.5f, top + bh * 0.5f, cx - bw * 0.4f, top + bh)
        path.lineTo(cx + bw * 0.4f, top + bh); path.quadTo(cx + bw * 0.5f, top + bh * 0.5f, cx + bw * 0.3f, top)
        path.close(); canvas.drawPath(path, p)
        p.strokeWidth = 1.5f
        canvas.drawLine(cx - bw * 0.25f, top + 2f, cx, top - 4f, p)
        canvas.drawLine(cx, top - 4f, cx + bw * 0.25f, top + 2f, p)
    }

    // ── touch ──

    private fun hitTest(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2); getLocationOnScreen(loc)
        val cx = loc[0] + width / 2f; val cy = loc[1] + height / 2f
        val r = minOf(width, height) * 0.5f * stretchScale * 1.5f
        val dx = rawX - cx; val dy = rawY - cy
        return sqrt(dx * dx + dy * dy) < r
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchHitPet = hitTest(event.rawX, event.rawY)
                if (!touchHitPet) return false
                touchStartTime = System.currentTimeMillis(); touchStartX = event.rawX; touchStartY = event.rawY
                isDragging = false; isPinching = false
                // if two fingers land simultaneously, enter pinch immediately
                if (event.pointerCount >= 2) {
                    isPinching = true
                    prePinchSize = layoutParams?.width ?: 200
                    pinchStartDist = fingerDist(event); pinchStartAngle = fingerAngle(event)
                    pinchStartSize = minOf(layoutParams?.width?.toFloat() ?: 200f, layoutParams?.width?.toFloat() ?: 200f)
                    pinchStartRotation = userRotation
                }
                if (idleTier == 0) cancelIdle()
                idleTimer = System.currentTimeMillis()
                performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // enter pinch; record size before pinch for exit detection
                    isPinching = true; isDragging = false; pinchHappened = false
                    prePinchSize = layoutParams?.width ?: 200
                    pinchStartDist = fingerDist(event); pinchStartAngle = fingerAngle(event)
                    pinchStartSize = minOf(layoutParams?.width?.toFloat() ?: 200f, layoutParams?.width?.toFloat() ?: 200f)
                    pinchStartRotation = userRotation
                    pendingSingleTap?.let { handler.removeCallbacks(it) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchHitPet) return true
                if (isPinching && event.pointerCount == 2) {
                    val newDist = fingerDist(event); val newAngle = fingerAngle(event)
                    val scale = newDist / pinchStartDist.coerceAtLeast(1f)
                    userRotation = pinchStartRotation + Math.toDegrees((newAngle - pinchStartAngle).toDouble()).toFloat()
                    if (kotlin.math.abs(newDist - pinchStartDist) > 5f) pinchHappened = true
                    onResize?.invoke(scale)
                } else if (!isPinching && event.pointerCount == 1) {
                    val dx = event.rawX - touchStartX; val dy = event.rawY - touchStartY
                    if (sqrt(dx * dx + dy * dy) > 10f && System.currentTimeMillis() - touchStartTime > 200L) {
                        isDragging = true; pendingSingleTap?.let { handler.removeCallbacks(it) }
                        onDrag?.invoke(event.rawX, event.rawY)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchHitPet) return true
                if (isPinching || pinchHappened) {
                    isPinching = false
                    // tier1: exit on actual zoom; tier2: ANY two-finger attempt exits
                    if ((idleTier == 1 && pinchHappened) || idleTier >= 2) {
                        exitIdleAction()
                    }
                    pinchHappened = false
                    onResizeEnd?.invoke()
                    idleTimer = System.currentTimeMillis()
                }
                else if (isDragging) {
                    isDragging = false
                    if (idleTier == 1) exitIdleAction()
                    idleTimer = System.currentTimeMillis()
                }
                else if (idleTier > 0) {
                    actionBubble()?.let { speechText = it }
                }
                else handleTap()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // don't reset isPinching here — ACTION_UP handles it
            }
        }
        return true
    }

    private fun handleTap() {
        if (isChatting) return
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 350L) {
            handler.removeCallbacks(pendingSingleTap ?: return); pendingSingleTap = null; lastTapTime = 0L
            onDoubleTap?.invoke()
        } else {
            lastTapTime = now
            pendingSingleTap = Runnable {
                expression = Expression.HAPPY
                handler.postDelayed({ expression = Expression.NEUTRAL }, 1200)
                onSingleTap?.invoke()
            }
            handler.postDelayed(pendingSingleTap!!, 360L)
        }
    }

    private fun fingerDist(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1); val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun fingerAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return atan2((event.getY(0) - event.getY(1)).toDouble(), (event.getX(0) - event.getX(1)).toDouble()).toFloat()
    }

    fun randomPhrase(): String = phrases.random()

    override fun performClick(): Boolean { super.performClick(); return true }
}
