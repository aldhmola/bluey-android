package app.bluey

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Blueberry colors from the design. */
object Palette {
    const val BERRY1 = 0xFFA9BCFF.toInt()
    const val BERRY2 = 0xFF6C86F5.toInt()
    const val BERRY3 = 0xFF4254D6.toInt()
    const val BERRY4 = 0xFF2B2F8F.toInt()
    const val NOSE = 0xFF1C1F66.toInt()
    const val INK = 0xFF17151F.toInt()
    const val INK_SOFT = 0xFFB9B2CC.toInt()
    const val PANEL = 0xFF1E1B29.toInt()
    const val CHEEK = 0xFFF3A6D8.toInt()
    val GRADIENT = intArrayOf(BERRY1, BERRY2, BERRY3, BERRY4)
    val STOPS = floatArrayOf(0f, 0.34f, 0.68f, 1f)

    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/**
 * Bluey floating over every app: his teardrop cursor (which flies off to point and act), his speech
 * bubble and the research card. Lives in the accessibility service's windows, so it needs no extra
 * "draw over other apps" permission. All on the main thread.
 */
class Overlay(private val context: Context) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val stage = StageView(context)
    private val handle = HandleView(context)
    private var report: ReportCard? = null
    private var shown = false

    private val stageParams = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    private val handleParams = baseParams(0, 0)

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }

    // MARK: State the brain sets

    var voiceState: LiveVoice.State = LiveVoice.State.ASLEEP
        set(value) {
            field = value
            stage.kick()
        }

    var caption: String? = null
        set(value) {
            field = value
            stage.kick()
        }

    /** The thing he's pointing at, so it gets a soft outline and his bubble sits clear of it. */
    var speechTarget: Rect? = null
        set(value) {
            field = value
            stage.kick()
        }

    /** True while the Bluey app itself is on screen (it shows the big Bluey instead). */
    var appInFront = false
        set(value) {
            field = value
            updateHandle()
            stage.kick()
        }

    // MARK: Cursor flight (screen pixels)

    private val pos = PointF()
    private val vel = PointF()
    private val goal = PointF()
    private var pinned = false
    private var slowFlight = false
    private var placed = false

    val isHome: Boolean get() = !pinned

    private val bodyRadius get() = dp(19f) * Bluey.settings.cursorScale
    private val tipToCenter get() = dp(29f) * Bluey.settings.cursorScale

    /** His resting spot (his cursor tip), kept inside the screen. */
    private fun homeTip(): PointF {
        val (w, h) = screenSize()
        val reach = tipToCenter * 0.71f + bodyRadius + dp(6f)
        val x = (Bluey.settings.homeX * w).coerceIn(dp(8f), w - reach)
        val y = (Bluey.settings.homeY * h).coerceIn(dp(40f), h - reach - dp(24f))
        return PointF(x, y)
    }

    private var dragTip: PointF? = null

    private fun restTip(): PointF = dragTip ?: homeTip()

    private fun screenSize(): Pair<Int, Int> =
        (context as? BlueyAccessibility)?.screenSize() ?: (context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels)

    fun pinTo(point: PointF, thing: Rect?, slow: Boolean = false) {
        val (w, h) = screenSize()
        goal.set(point.x.coerceIn(0f, w.toFloat()), point.y.coerceIn(0f, h.toFloat()))
        pinned = true
        slowFlight = slow
        stage.kick()
    }

    fun goHome() {
        pinned = false
        slowFlight = false
        speechTarget = null
        stage.kick()
    }

    /** Flies the cursor to a spot and waits until it has landed. */
    suspend fun flyTo(point: PointF) {
        pinTo(point, null)
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < 1600) {
            if (hypot(pos.x - goal.x, pos.y - goal.y) < dp(2f) && hypot(vel.x, vel.y) < dp(30f)) break
            delay(16)
        }
    }

    private fun step(dt: Float) {
        val target = if (pinned) goal else restTip()
        if (!placed) {
            pos.set(target)
            placed = true
        }
        if (dragTip != null && !pinned) {
            pos.set(target)
            vel.set(0f, 0f)
            return
        }
        val k = if (slowFlight) 45f else 150f
        val c = 2f * sqrt(k) * 0.92f
        vel.x += (k * (target.x - pos.x) - c * vel.x) * dt
        vel.y += (k * (target.y - pos.y) - c * vel.y) * dt
        pos.x += vel.x * dt
        pos.y += vel.y * dt
    }

    private fun settled(): Boolean {
        val target = if (pinned) goal else restTip()
        return hypot(pos.x - target.x, pos.y - target.y) < 0.5f && hypot(vel.x, vel.y) < 2f
    }

    // MARK: Effects

    private class Effect(val point: PointF, val long: Boolean, val start: Long)
    private class Label(val text: String, val start: Long, val life: Long)

    private val effects = mutableListOf<Effect>()
    private var label: Label? = null

    fun clickEffect(point: PointF, long: Boolean) {
        effects.add(Effect(PointF(point.x, point.y), long, SystemClock.uptimeMillis()))
        stage.kick()
    }

    /** A small label by the cursor ("Stopped", "↓", "Opening Chrome"). */
    fun bubble(text: String, lifeMs: Long) {
        label = Label(text, SystemClock.uptimeMillis(), lifeMs)
        stage.kick()
    }

    // MARK: Windows

    fun show() {
        if (shown) return
        runCatching { wm.addView(stage, stageParams) }
        runCatching { wm.addView(handle, handleParams) }
        shown = true
        updateHandle()
        stage.kick()
    }

    fun remove() {
        if (!shown) return
        runCatching { wm.removeView(stage) }
        runCatching { wm.removeView(handle) }
        report?.let { runCatching { wm.removeView(it) } }
        report = null
        shown = false
    }

    fun screenChanged() {
        placed = false
        updateHandle()
        stage.kick()
    }

    private var touchBlocked = false

    /** Puts his touch target over his resting spot (screen coords converted to the window's). */
    private fun updateHandle() {
        if (!shown) return
        val tip = restTip()
        val size = ((bodyRadius + dp(18f)) * 2).toInt()
        val cx = tip.x + tipToCenter * 0.7071f
        val cy = tip.y + tipToCenter * 0.7071f
        val offset = stage.screenOffset()
        handleParams.width = size
        handleParams.height = size
        handleParams.x = (cx - size / 2f - offset[0]).toInt()
        handleParams.y = (cy - size / 2f - offset[1]).toInt()
        val untouchable = appInFront || touchBlocked
        handleParams.flags = if (untouchable) handleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(handle, handleParams) }
    }

    /** While he taps or swipes for you, his own windows let the touch through. */
    suspend fun <T> passThrough(block: suspend () -> T): T {
        touchBlocked = true
        updateHandle()
        report?.setTouchable(false)
        delay(20)
        try {
            return block()
        } finally {
            touchBlocked = false
            updateHandle()
            report?.setTouchable(true)
        }
    }

    private var hiddenForCapture = false

    fun hideForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        stage.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        report?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        stage.kick()
    }

    // MARK: Report card

    fun showReportLoading(question: String) = card().loading(question)
    fun showReport(r: ResearchReport) = card().show(r)
    fun showReportError(message: String) = card().error(message)

    private fun card(): ReportCard {
        report?.let { return it }
        val made = ReportCard(context) { closeReport() }
        val (w, _) = screenSize()
        val params = baseParams(min(w - dp(24f).toInt(), dp(520f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56f).toInt()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        made.params = params
        runCatching { wm.addView(made, params) }
        report = made
        return made
    }

    private fun closeReport() {
        report?.let { runCatching { wm.removeView(it) } }
        report = null
    }

    private inner class ReportCard(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {
        var params: WindowManager.LayoutParams? = null
        private val title = TextView(context)
        private val body = TextView(context)
        private val sources = LinearLayout(context)

        init {
            orientation = VERTICAL
            setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(14f).toInt())
            background = GradientDrawable().apply {
                setColor(Palette.alpha(Palette.PANEL, 0.97f))
                cornerRadius = dp(20f)
                setStroke(dp(1.5f).toInt(), Palette.alpha(Palette.BERRY2, 0.7f))
            }
            elevation = dp(10f)

            val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            title.setTextColor(Color.WHITE)
            title.textSize = 17f
            title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val close = TextView(context).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Palette.INK_SOFT)
                setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
                setOnClickListener { onClose() }
            }
            header.addView(close)
            addView(header)

            body.setTextColor(0xFFE6E2F2.toInt())
            body.textSize = 15f
            body.setLineSpacing(0f, 1.15f)
            sources.orientation = VERTICAL
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(body)
                addView(sources)
            }
            val scroll = MaxHeightScroll(context, (context.resources.displayMetrics.heightPixels * 0.5f).toInt())
            scroll.addView(column)
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        }

        fun loading(question: String) {
            title.text = "Doing some research…"
            body.text = question
            sources.removeAllViews()
        }

        fun error(message: String) {
            title.text = "Research didn't work"
            body.text = message
            sources.removeAllViews()
        }

        fun show(r: ResearchReport) {
            title.text = r.title
            body.text = r.paragraphs.joinToString("\n\n")
            sources.removeAllViews()
            r.sources.forEach { (name, url) ->
                sources.addView(TextView(context).apply {
                    text = "↗ $name"
                    textSize = 13f
                    setTextColor(Palette.BERRY1)
                    setPadding(0, dp(8f).toInt(), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        onClose()
                    }
                })
            }
        }

        fun setTouchable(on: Boolean) {
            val p = params ?: return
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(this, p) }
        }
    }

    private class MaxHeightScroll(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    // MARK: Touch: double-tap to wake or sleep, hold to ask, drag to move him, tap to stop

    @SuppressLint("ViewConstructor")
    private inner class HandleView(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        private var downX = 0f
        private var downY = 0f
        private var startTip = PointF()
        private var moved = false
        private var holding = false
        private var lastTapUp = 0L
        private val longPress = Runnable {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Bluey.voice.beginAsk()
            stage.kick()
        }
        private val singleTap = Runnable { onSingleTap() }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTip = restTip()
                    moved = false
                    holding = false
                    postDelayed(longPress, 380)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!holding && !moved && hypot(dx, dy) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        goHome()
                    }
                    if (moved) {
                        dragTip = PointF(startTip.x + dx, startTip.y + dy)
                        updateHandle()
                        stage.kick()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    when {
                        holding -> {
                            holding = false
                            Bluey.voice.endAsk()
                        }

                        moved -> dropped()
                        event.actionMasked == MotionEvent.ACTION_UP -> {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTapUp < 320) {
                                removeCallbacks(singleTap)
                                lastTapUp = 0
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                Bluey.voice.toggle()
                            } else {
                                lastTapUp = now
                                postDelayed(singleTap, 320)
                            }
                        }
                    }
                    stage.kick()
                }
            }
            return true
        }

        private fun dropped() {
            val tip = dragTip ?: return
            val (w, h) = screenSize()
            Bluey.settings.homeX = (tip.x / w).coerceIn(0f, 1f)
            Bluey.settings.homeY = (tip.y / h).coerceIn(0f, 1f)
            dragTip = null
            (context as? BlueyAccessibility)?.dropped(homeTip())
            updateHandle()
            bubble("Here?", 900)
        }

        private fun onSingleTap() {
            when (Bluey.voice.state) {
                LiveVoice.State.ASLEEP -> bubble("Double-tap to wake me", 1800)
                LiveVoice.State.THINKING -> Bluey.brain.stopActions()
                else -> bubble("Hold me to ask", 1400)
            }
        }
    }

    // MARK: Drawing

    private inner class StageView(context: Context) : View(context) {
        private var lastFrame = 0L
        private val location = IntArray(2)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(15f)
        }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private var blinkStart = -10f
        private var nextBlink = 2f
        private var wander = PointF()
        private var nextWander = 0f

        fun screenOffset(): IntArray {
            getLocationOnScreen(location)
            return location
        }

        /** Something changed: draw a fresh frame (and keep animating while there's motion). */
        fun kick() {
            postInvalidateOnAnimation()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { updateHandle() }
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0.016f else min((now - lastFrame) / 1000f, 0.05f)
            lastFrame = now
            step(dt)
            if (hiddenForCapture || appInFront) {
                if (!settled()) postInvalidateOnAnimation()
                return
            }
            val t = now / 1000f
            val offset = screenOffset()
            canvas.save()
            canvas.translate(-offset[0].toFloat(), -offset[1].toFloat())

            speechTarget?.let { drawTarget(canvas, it) }
            drawEffects(canvas, now)
            if (pinned) drawHomeMarker(canvas)
            drawCursor(canvas, t)
            drawLabel(canvas, now)
            caption?.takeIf { it.isNotBlank() }?.let { drawCaption(canvas, it) }

            canvas.restore()

            // Keep animating while anything moves; idle and asleep, just tick slowly for blinks.
            val busy = !settled() || effects.isNotEmpty() || label != null || dragTip != null ||
                voiceState != LiveVoice.State.ASLEEP
            if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(90)
        }

        private fun drawTarget(canvas: Canvas, r: Rect) {
            val box = RectF(r).apply { inset(-dp(5f), -dp(4f)) }
            stroke.strokeWidth = dp(2.5f)
            stroke.color = Palette.alpha(Palette.BERRY2, 0.9f)
            stroke.setShadowLayer(dp(8f), 0f, 0f, Palette.alpha(Palette.BERRY2, 0.8f))
            canvas.drawRoundRect(box, dp(8f), dp(8f), stroke)
            stroke.clearShadowLayer()
        }

        private fun drawEffects(canvas: Canvas, now: Long) {
            val iterator = effects.iterator()
            while (iterator.hasNext()) {
                val e = iterator.next()
                val life = if (e.long) 900f else 450f
                val p = (now - e.start) / life
                if (p >= 1f) {
                    iterator.remove()
                    continue
                }
                stroke.strokeWidth = dp(3f) * (1 - p)
                stroke.color = Palette.alpha(Color.WHITE, 0.9f * (1 - p))
                canvas.drawCircle(e.point.x, e.point.y, dp(10f) + dp(26f) * p, stroke)
                fill.shader = null
                fill.color = Palette.alpha(Palette.BERRY2, 0.35f * (1 - p))
                canvas.drawCircle(e.point.x, e.point.y, dp(12f) * (1 - p * 0.5f), fill)
            }
        }

        private fun drawHomeMarker(canvas: Canvas) {
            val tip = restTip()
            val cx = tip.x + tipToCenter * 0.7071f
            val cy = tip.y + tipToCenter * 0.7071f
            stroke.strokeWidth = dp(1.5f)
            stroke.color = Palette.alpha(Palette.BERRY1, 0.45f)
            stroke.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            canvas.drawCircle(cx, cy, bodyRadius, stroke)
            stroke.pathEffect = null
        }

        private fun drawCursor(canvas: Canvas, t: Float) {
            val r = bodyRadius
            val d = tipToCenter
            val state = voiceState
            val asleep = state == LiveVoice.State.ASLEEP
            val talking = state == LiveVoice.State.SPEAKING
            val bob = if (talking) sin(t * 18f) * dp(1.6f) else sin(t * 2.2f) * dp(0.8f)
            val tipX = pos.x
            val tipY = pos.y + bob
            val angle = -45f + (vel.x * 0.004f).coerceIn(-12f, 12f)

            // Listening ring while you hold him to ask.
            val cx = tipX + d * 0.7071f
            val cy = tipY + d * 0.7071f
            if (state == LiveVoice.State.ASKING) {
                val pulse = (t * 1.6f) % 1f
                stroke.strokeWidth = dp(3f)
                stroke.color = Palette.alpha(Palette.BERRY1, 0.7f * (1 - pulse))
                canvas.drawCircle(cx, cy, r + dp(6f) + dp(14f) * pulse, stroke)
            }

            canvas.save()
            canvas.translate(tipX, tipY)
            canvas.rotate(angle)
            val drop = dropPath(r, d)
            val corner = CornerPathEffect(r * 0.22f)

            glow.shader = null
            glow.color = Palette.alpha(Palette.BERRY3, if (asleep) 0.5f else 0.85f)
            glow.pathEffect = corner
            glow.setShadowLayer(dp(14f), 0f, 0f, Palette.alpha(Palette.BERRY2, if (asleep) 0.35f else 0.7f))
            canvas.drawPath(drop, glow)

            fill.pathEffect = corner
            fill.shader = LinearGradient(0f, -r * 0.2f, 0f, d + r, Palette.GRADIENT, Palette.STOPS, Shader.TileMode.CLAMP)
            canvas.drawPath(drop, fill)
            fill.shader = RadialGradient(
                -r * 0.35f, d - r * 0.45f, r * 0.9f,
                intArrayOf(Palette.alpha(Color.WHITE, 0.45f), Palette.alpha(Color.WHITE, 0f)), null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(drop, fill)
            fill.shader = null
            fill.pathEffect = null

            stroke.strokeWidth = dp(2.4f)
            stroke.color = Color.WHITE
            stroke.pathEffect = corner
            canvas.drawPath(drop, stroke)
            stroke.pathEffect = null
            canvas.restore()

            drawEyes(canvas, cx, cy, r, t, state)

            if (state == LiveVoice.State.THINKING || state == LiveVoice.State.WAKING) {
                for (i in 0 until 3) {
                    val wobble = sin(t * 4f + i) * dp(2f)
                    fill.color = Palette.alpha(Color.WHITE, 1f - i * 0.28f)
                    canvas.drawCircle(cx + r + dp(6f) + i * dp(8f), cy - r - i * dp(5f) + wobble, dp(3f) - i * dp(0.4f), fill)
                }
            }
            if (asleep) {
                for (i in 0 until 2) {
                    val phase = (t * 0.35f + i / 2f) % 1f
                    small.color = Palette.alpha(Color.WHITE, 0.85f * sin(Math.PI.toFloat() * phase))
                    small.textSize = dp(11f + i * 3f)
                    canvas.drawText("z", cx + r * 0.6f + phase * dp(14f), cy - r - phase * dp(18f), small)
                }
                small.color = Color.WHITE
                small.textSize = dp(13f)
            }
        }

        private fun dropPath(r: Float, d: Float): Path {
            val beta = Math.toDegrees(acos((r / d).toDouble())).toFloat()
            val b = Math.toRadians(beta.toDouble())
            val path = Path()
            path.moveTo(0f, 0f)
            path.lineTo((r * sin(b)).toFloat(), (d - r * cos(b)).toFloat())
            path.arcTo(RectF(-r, d - r, r, d + r), beta - 90f, 360f - 2 * beta, false)
            path.close()
            return path
        }

        private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, state: LiveVoice.State) {
            // Blinks every few seconds.
            if (t > nextBlink) {
                blinkStart = t
                nextBlink = t + 2f + (Math.random() * 3).toFloat()
            }
            val p = (t - blinkStart) / 0.16f
            val closed = if (p in 0f..1f) sin(Math.PI.toFloat() * p) else 0f

            // Where he looks: where he's flying, up at what he's pointing at, or a little wander.
            val speed = hypot(vel.x, vel.y)
            var gx: Float
            var gy: Float
            if (speed > dp(60f)) {
                gx = vel.x / speed
                gy = vel.y / speed
            } else if (pinned) {
                gx = -0.7f
                gy = -0.7f
            } else {
                if (t > nextWander) {
                    wander = PointF((Math.random() * 1.6 - 0.8).toFloat(), (Math.random() * 1.2 - 0.6).toFloat())
                    nextWander = t + 1f + (Math.random() * 2.5).toFloat()
                }
                gx = wander.x
                gy = wander.y
            }
            if (state == LiveVoice.State.THINKING) {
                gx = 0.6f
                gy = -0.8f
            }

            val tilt = Math.toRadians(20.0)
            val spread = r * 0.36f
            val eyeR = r * 0.25f * if (state == LiveVoice.State.ASKING || state == LiveVoice.State.LISTENING) 1.08f else 1f
            for (side in listOf(-1f, 1f)) {
                val ex = cx + side * spread * cos(tilt).toFloat()
                val ey = cy + side * spread * sin(tilt).toFloat() - r * 0.08f
                if (state == LiveVoice.State.ASLEEP) {
                    stroke.color = Palette.INK
                    stroke.strokeWidth = dp(2f)
                    stroke.strokeCap = Paint.Cap.ROUND
                    canvas.drawArc(RectF(ex - eyeR, ey - eyeR * 0.6f, ex + eyeR, ey + eyeR * 0.9f), 20f, 140f, false, stroke)
                    continue
                }
                val open = max(0.08f, 1f - closed)
                fill.color = Color.WHITE
                canvas.drawOval(RectF(ex - eyeR, ey - eyeR * open, ex + eyeR, ey + eyeR * open), fill)
                if (open > 0.3f) {
                    fill.color = Palette.INK
                    val pr = eyeR * 0.52f
                    canvas.drawCircle(ex + gx * eyeR * 0.42f, ey + gy * eyeR * 0.42f * open, pr, fill)
                    fill.color = Color.WHITE
                    canvas.drawCircle(ex + gx * eyeR * 0.42f - pr * 0.35f, ey + gy * eyeR * 0.42f * open - pr * 0.4f, pr * 0.3f, fill)
                }
            }
        }

        private fun drawLabel(canvas: Canvas, now: Long) {
            val l = label ?: return
            val age = now - l.start
            if (age > l.life) {
                label = null
                return
            }
            val a = min(1f, min(age / 120f, (l.life - age) / 200f))
            val tip = pos
            val w = small.measureText(l.text) + dp(20f)
            val h = dp(28f)
            val (sw, _) = screenSize()
            var x = tip.x + tipToCenter * 0.7f - w / 2
            x = x.coerceIn(dp(8f), sw - w - dp(8f))
            var y = tip.y - h - dp(10f)
            if (y < dp(30f)) y = tip.y + tipToCenter + bodyRadius + dp(10f)
            fill.color = Palette.alpha(Palette.PANEL, 0.94f * a)
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, fill)
            small.color = Palette.alpha(Color.WHITE, a)
            canvas.drawText(l.text, x + dp(10f), y + h / 2 + small.textSize * 0.35f, small)
            small.color = Color.WHITE
        }

        private fun drawCaption(canvas: Canvas, raw: String) {
            val (sw, sh) = screenSize()
            val shownText = if (raw.length > 320) "…" + raw.takeLast(320) else raw
            val maxW = min(sw * 0.72f, dp(340f)).toInt()
            val pad = dp(14f)
            val textW = min(maxW - (pad * 2).toInt(), (text.measureText(shownText) + 2).toInt()).coerceAtLeast(dp(40f).toInt())
            val layout = StaticLayout.Builder.obtain(shownText, 0, shownText.length, text, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)
                .build()
            val w = textW + pad * 2
            val h = layout.height + pad * 1.6f

            val cx = pos.x + tipToCenter * 0.7071f
            val cy = pos.y + tipToCenter * 0.7071f
            val r = bodyRadius
            // Beside his body, on whichever side has room; never on top of what he's pointing at.
            var x = if (cx + r + dp(10f) + w < sw - dp(8f)) cx + r + dp(10f) else cx - r - dp(10f) - w
            x = x.coerceIn(dp(8f), sw - w - dp(8f))
            var y = cy - h / 2
            speechTarget?.let { thing ->
                if (y < thing.bottom + dp(8f) && y + h > thing.top - dp(8f) && x < thing.right && x + w > thing.left) {
                    y = thing.bottom + dp(12f)
                }
            }
            y = y.coerceIn(dp(36f), sh - h - dp(24f))

            fill.color = Palette.alpha(Palette.PANEL, 0.96f)
            fill.setShadowLayer(dp(12f), 0f, dp(3f), Palette.alpha(Color.BLACK, 0.45f))
            val box = RectF(x, y, x + w, y + h)
            canvas.drawRoundRect(box, dp(18f), dp(18f), fill)
            fill.clearShadowLayer()
            stroke.strokeWidth = dp(1.5f)
            stroke.color = Palette.alpha(Palette.BERRY2, 0.75f)
            canvas.drawRoundRect(box, dp(18f), dp(18f), stroke)

            canvas.save()
            canvas.translate(x + pad, y + pad * 0.8f)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
