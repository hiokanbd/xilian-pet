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

    var expression = Expression.NEUTRAL
        set(value) { field = value; invalidate() }
    var speechText: String? = null
        set(value) { field = value; bubbleAlpha = 1f; invalidate(); scheduleBubbleFade() }

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

    // sleep / shy / read state
    private var sleepMode = false
    private var shyMode = false
    private var readMode = false
    private var sleepTimer: Runnable? = null
    private var shyTimer: Runnable? = null
    private var pinchDirChanges = 0
    private var lastPinchScale = 1f

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

    init { startIdleLoop(); resetSleepTimer() }

    // ── idle loop ──

    private fun startIdleLoop() {
        idleTimer = System.currentTimeMillis()
        handler.postDelayed(idleTick, 4000L)
    }

    private val idleTick = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - idleTimer
            if (elapsed > 12000L) playRandomIdle()
            else if (elapsed > 6000L && Math.random() < 0.3) playRandomIdle()
            handler.postDelayed(this, 2000L)
        }
    }

    private fun playRandomIdle() {
        if (swingMode || sleepMode || shyMode || readMode) return
        idleTimer = System.currentTimeMillis()
        val hasSwing = stateBitmaps["swing0"] != null
        val hasRead = stateBitmaps["read"] != null
        val total = 4 + (if (hasSwing) 1 else 0) + (if (hasRead) 1 else 0)
        val n = (Math.random() * total).toInt()
        when {
            n < 4 -> when (n) {
                0 -> animateSway(); 1 -> animateBlink(); 2 -> animateStretch(); 3 -> animateTurn()
            }
            n == 4 && hasSwing -> animateSwingIdle()
            else -> startRead()
        }
    }

    private fun cancelIdle() {
        currentIdleAnim?.cancel()
        swayOffset = 0f; stretchScale = 1f; turnAngle = 0f
        blinkFrameIndex = 0; blinkAnimRunning = false
        invalidate()
    }

    // ── swing animation ──

    private fun applySwingOffset(idx: Int) {
        val baseR = minOf(width, height) * 0.38f
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

    fun toggleSwing() {
        if (swingMode) stopSwing() else startSwing()
    }

    fun startSwing() {
        val hasAll = stateBitmaps["swing0"] != null && stateBitmaps["swing1"] != null && stateBitmaps["swing2"] != null
        if (!hasAll || swingMode) return
        swingMode = true; swingAnimRunning = false
        cancelIdle()
        // clean pendulum: left→middle→right→middle
        val seq = intArrayOf(0, 1, 2, 1)
        applySwingOffset(0)
        swingAnimator = ValueAnimator.ofInt(0, Int.MAX_VALUE).apply {
            duration = 500L * Int.MAX_VALUE
            addUpdateListener {
                val idx = (it.animatedValue as Int) % seq.size
                swingFrameIndex = seq[idx]; applySwingOffset(seq[idx]); invalidate()
            }
            start()
        }
        onSwingStateChanged?.invoke(true)
        idleTimer = System.currentTimeMillis()
    }

    fun stopSwing() {
        swingMode = false
        swingAnimator?.cancel()
        swingAnimator = null
        swingFrameIndex = 0
        swingOffsetX = 0f
        onSwingStateChanged?.invoke(false)
        idleTimer = System.currentTimeMillis()
        invalidate()
    }

    fun isSwingMode(): Boolean = swingMode
    fun isSleepMode(): Boolean = sleepMode
    fun isShyMode(): Boolean = shyMode
    fun isReadMode(): Boolean = readMode

    fun toggleRead() { if (readMode) stopRead() else startRead() }

    fun startRead() {
        if (stateBitmaps["read"] == null || readMode) return
        if (sleepMode) wakeUp()
        readMode = true
        invalidate()
    }

    fun stopRead() {
        readMode = false
        invalidate()
    }

    // ── sleep ──

    fun toggleSleep() { if (sleepMode) wakeUp() else enterSleep() }

    fun enterSleep() {
        if (sleepMode) return
        sleepMode = true; shyMode = false
        sleepTimer?.let { handler.removeCallbacks(it) }
        invalidate()
        onSleepStateChanged?.invoke(true)
    }

    fun wakeUp() {
        sleepMode = false
        sleepTimer?.let { handler.removeCallbacks(it) }
        resetSleepTimer()
        invalidate()
        onSleepStateChanged?.invoke(false)
    }

    private fun resetSleepTimer() {
        sleepTimer?.let { handler.removeCallbacks(it) }
        sleepTimer = Runnable {
            if (!sleepMode && !swingMode && !shyMode) enterSleep()
        }
        handler.postDelayed(sleepTimer!!, 60000L)
    }

    // ── shy ──

    fun triggerShy() {
        if (shyMode) { clearShy(); return }
        if (sleepMode) wakeUp()
        shyMode = true
        shyTimer?.let { handler.removeCallbacks(it) }
        shyTimer = Runnable { clearShy() }
        handler.postDelayed(shyTimer!!, 3000L)
        invalidate()
        onShyStateChanged?.invoke(true)
    }

    private fun clearShy() {
        shyMode = false
        shyTimer?.let { handler.removeCallbacks(it) }
        invalidate()
        onShyStateChanged?.invoke(false)
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
        // swing takes priority
        if (swingMode || swingAnimRunning) {
            val key = when (swingFrameIndex) {
                0 -> "swing0"; 1 -> "swing1"; 2 -> "swing2"; else -> "swing0"
            }
            return stateBitmaps[key] ?: stateBitmaps["open"]
        }
        // sleep and shy override normal expression
        if (readMode && stateBitmaps["read"] != null) return stateBitmaps["read"]
        if (sleepMode && stateBitmaps["sleep"] != null) return stateBitmaps["sleep"]
        if (shyMode && stateBitmaps["shy"] != null) return stateBitmaps["shy"]
        // blink animation
        if (blinkAnimRunning && stateBitmaps.size >= 3) {
            val key = when (blinkFrameIndex) {
                0 -> "open"; 1 -> "half"; 2 -> "closed"; else -> "open"
            }
            return stateBitmaps[key]
        }
        val expKey = when (expression) {
            Expression.SLEEPY -> "sleep"
            Expression.BLUSH, Expression.SHY -> "shy"
            else -> "open"
        }
        return stateBitmaps[expKey] ?: stateBitmaps["open"]
    }

    // ── drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = minOf(width, height) * 0.38f
        val r = baseR * stretchScale

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

        drawSpeechBubble(canvas, cx, cy - r * stretchScale - 20f)
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

    private fun drawSpeechBubble(canvas: Canvas, px: Float, py: Float) {
        if (speechText.isNullOrEmpty() || bubbleAlpha <= 0.01f) return
        val text = speechText ?: return
        val maxWidth = 380f; val padding = 24f; val lineHeight = 38f
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val measured = textPaint.breakText(remaining, true, maxWidth - padding * 2, null)
            if (measured == 0) break
            lines.add(remaining.take(measured)); remaining = remaining.drop(measured)
        }
        val bubbleW = minOf(maxWidth, lines.maxOfOrNull { textPaint.measureText(it) } ?: 100f) + padding * 2
        val bubbleH = lines.size * lineHeight + padding * 2
        val bx = (width / 2f) - bubbleW / 2f; val by = py - bubbleH - 16f

        canvas.save(); canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat()); canvas.translate(bx, by)
        bubblePaint.alpha = (bubbleAlpha * 255).toInt(); bubbleStroke.alpha = (bubbleAlpha * 255).toInt()
        textPaint.alpha = (bubbleAlpha * 255).toInt()
        val rect = RectF(0f, 0f, bubbleW, bubbleH)
        canvas.drawRoundRect(rect, 18f, 18f, bubblePaint); canvas.drawRoundRect(rect, 18f, 18f, bubbleStroke)
        val triPath = Path(); triPath.moveTo(bubbleW / 2f - 10f, bubbleH); triPath.lineTo(bubbleW / 2f, bubbleH + 14f)
        triPath.lineTo(bubbleW / 2f + 10f, bubbleH); triPath.close()
        canvas.drawPath(triPath, bubblePaint); canvas.drawPath(triPath, bubbleStroke)
        lines.forEachIndexed { i, line -> canvas.drawText(line, bubbleW / 2f, padding + lineHeight * (i + 0.7f), textPaint) }
        canvas.restore()
    }

    // ── touch ──

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartTime = System.currentTimeMillis(); touchStartX = event.rawX; touchStartY = event.rawY
                isDragging = false; isPinching = false
                if (swingMode) stopSwing()
                if (readMode) stopRead()
                else if (!sleepMode) cancelIdle()
                if (shyMode) clearShy()
                resetSleepTimer()
                performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (System.currentTimeMillis() - touchStartTime > 180L && event.pointerCount == 2) {
                    isPinching = true
                    pinchStartDist = fingerDist(event); pinchStartAngle = fingerAngle(event)
                    pinchStartSize = minOf(layoutParams?.width?.toFloat() ?: 200f, layoutParams?.width?.toFloat() ?: 200f)
                    pinchStartRotation = userRotation
                    pinchDirChanges = 0; lastPinchScale = 1f
                    pendingSingleTap?.let { handler.removeCallbacks(it) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount == 2) {
                    val newDist = fingerDist(event); val newAngle = fingerAngle(event)
                    val scale = newDist / pinchStartDist.coerceAtLeast(1f)
                    userRotation = pinchStartRotation + Math.toDegrees((newAngle - pinchStartAngle).toDouble()).toFloat()
                    // track zoom direction changes for auto-shy
                    if (scale > lastPinchScale * 1.08f) { /* zoom out */ }
                    else if (scale < lastPinchScale * 0.92f && lastPinchScale > 1.08f) pinchDirChanges++
                    lastPinchScale = scale
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
                if (isPinching) {
                    isPinching = false
                    if (sleepMode) wakeUp() // pinch wakes up sleeping pet
                    if (pinchDirChanges >= 1) triggerShy() // auto-shy on one zoom reversal
                    onResizeEnd?.invoke()
                    idleTimer = System.currentTimeMillis()
                }
                else if (isDragging) { isDragging = false; idleTimer = System.currentTimeMillis() }
                else handleTap()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isPinching && event.pointerCount <= 2) { isPinching = false; idleTimer = System.currentTimeMillis() }
            }
        }
        return true
    }

    private fun handleTap() {
        // in sleep mode, tap shows sleepy bubble instead of normal interaction
        if (sleepMode) {
            speechText = sleepyPhrases.random()
            return
        }
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
