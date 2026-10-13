package app.bluey

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Float, val stiffness: Float = 200f, val damping: Float = 0.7f) {
    var velocity = 0f
    fun step(target: Float, dt: Float) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

enum class Mood { LISTENING, TALKING, POINTING, THINKING, HAPPY, RESTING, SLEEPY }

/**
 * The big Bluey on the app screen (the design from the iPhone app): he peeks up from the bottom edge.
 * Double-tap to wake or sleep him, press and hold to ask.
 */
class FaceView(context: Context) : View(context) {
    companion object {
        const val DESIGN_W = 844f
        const val DESIGN_H = 390f
    }

    // Animation state.
    private var lastTime = -1f
    private var mood = Mood.RESTING
    private val gazeX = Spring(0f, 260f, 0.82f)
    private val gazeY = Spring(0f, 260f, 0.82f)
    private val pupil = Spring(1f, 160f, 0.5f)
    private val browLift = Spring(0f, 180f, 0.55f)
    private val browTilt = Spring(0f, 150f, 0.6f)
    private val lean = Spring(0f, 60f, 0.8f)
    private val hop = Spring(0f, 260f, 0.35f)
    private val blush = Spring(0.25f, 40f, 1f)
    private val squint = Spring(0f, 150f, 0.7f)
    private var talk = 0f
    private var blinkStart = -1f
    private var doubleBlink = false
    private var nextBlink = 2f
    private var nextSaccade = 0f
    private var wanderX = 0f
    private var wanderY = -0.3f
    private var jitterX = 0f
    private var jitterY = 0f
    private var nextJitter = 0f
    private var nextHop = 6f
    private var touchGaze: Pair<Float, Float>? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun wantedMood(): Mood = when (Bluey.voice.state) {
        LiveVoice.State.ASLEEP -> Mood.RESTING
        LiveVoice.State.WAKING -> Mood.HAPPY
        LiveVoice.State.LISTENING, LiveVoice.State.ASKING -> Mood.LISTENING
        LiveVoice.State.THINKING -> Mood.THINKING
        LiveVoice.State.SPEAKING -> Mood.TALKING
    }

    // MARK: Touch

    private var holding = false
    private var lastTap = 0L
    private val longPress = Runnable {
        holding = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Bluey.voice.beginAsk()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gx = ((event.x / width) * 2 - 1).coerceIn(-1f, 1f)
        val gy = ((event.y / height) * 2 - 1.2f).coerceIn(-1f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = false
                touchGaze = gx to gy
                postDelayed(longPress, 380)
            }

            MotionEvent.ACTION_MOVE -> touchGaze = gx to gy
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touchGaze = null
                if (holding) {
                    holding = false
                    Bluey.voice.endAsk()
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 320) {
                        lastTap = 0
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        Bluey.voice.toggle()
                    } else {
                        lastTap = now
                    }
                }
            }
        }
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        val minH = (180 * resources.displayMetrics.density).toInt()
        val h = min((w * DESIGN_H / DESIGN_W).toInt(), maxH).coerceAtLeast(minH)
        setMeasuredDimension(w, h)
    }

    // MARK: Animation

    private fun step(now: Float): Float {
        val dt = if (lastTime < 0) 0.016f else min(now - lastTime, 1f / 20)
        lastTime = now

        val wanted = wantedMood()
        if (wanted != mood) {
            mood = wanted
            blinkStart = now
            if (mood != Mood.SLEEPY && mood != Mood.RESTING) {
                hop.velocity += 260f
                pupil.velocity += 3f
            }
        }

        var wantX: Float
        var wantY: Float
        val touch = touchGaze
        if (touch != null) {
            wantX = touch.first
            wantY = touch.second
        } else {
            if (now > nextSaccade) {
                wanderX = Random.nextFloat() * 1.8f - 0.9f
                wanderY = Random.nextFloat() * 1.3f - 0.9f
                nextSaccade = now + 0.6f + Random.nextFloat() * 1.6f
            }
            wantX = wanderX
            wantY = wanderY
        }
        if (mood == Mood.THINKING) {
            wantX = 0.6f + 0.08f * sin(now * 1.3f)
            wantY = -0.85f
        }
        if (now > nextJitter) {
            val amount = when (mood) {
                Mood.TALKING -> 0.07f
                Mood.THINKING -> 0.05f
                else -> 0.02f
            }
            jitterX = (Random.nextFloat() * 2 - 1) * amount
            jitterY = (Random.nextFloat() * 2 - 1) * amount
            nextJitter = now + 0.5f + Random.nextFloat() * 0.9f
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        val wantTalk = if (mood == Mood.TALKING) 0.5f + 0.5f * sin(now * 19f) * sin(now * 7.3f) else 0f
        talk += (wantTalk - talk) * min(1f, dt * 25)

        var wantPupil = 1f
        var wantLift = 0f
        var wantTilt = 0f
        var wantBlush = 0.25f
        var wantSquint = 0f
        when (mood) {
            Mood.LISTENING -> { wantPupil = 1.12f; wantLift = 10f }
            Mood.TALKING -> { wantPupil = 1.05f; wantLift = 6 + talk * 18; wantTilt = 0.05f * sin(now * 2.3f) }
            Mood.POINTING -> { wantPupil = 0.95f; wantLift = -4f; wantTilt = -0.14f }
            Mood.THINKING -> { wantPupil = 0.9f; wantLift = 4f; wantTilt = 0.22f }
            Mood.HAPPY -> { wantPupil = 1.2f; wantLift = 16f; wantBlush = 0.85f; wantSquint = 1f }
            Mood.RESTING -> { wantPupil = 0.9f; wantLift = -8f; wantBlush = 0.15f }
            Mood.SLEEPY -> { wantPupil = 0.88f; wantLift = -10f; wantTilt = 0.12f; wantBlush = 0.15f }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045f, dt)

        if (now > nextHop) {
            if (talk < 0.05f && mood != Mood.SLEEPY && mood != Mood.RESTING) hop.velocity += 180f + Random.nextFloat() * 140f
            nextHop = now + 7f + Random.nextFloat() * 7f
        }
        hop.step(0f, dt)

        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = Random.nextFloat() < 0.25f
            nextBlink = now + 2f + Random.nextFloat() * 3f
        }
        return dt
    }

    private fun blinkClosed(now: Float): Float {
        val p = (now - blinkStart) / 0.15f
        var closed = if (p in 0f..1f) sin(PI.toFloat() * p) else 0f
        if (doubleBlink && p in 1.3f..2.3f) closed = sin(PI.toFloat() * (p - 1.3f))
        return closed
    }

    // MARK: Drawing

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis() / 1000f
        step(now)
        val closed = blinkClosed(now)
        val breathe = sin(now * if (mood == Mood.RESTING) 1.1f else 1.8f)
        canvas.drawColor(Color.BLACK)

        val scale = max(width / DESIGN_W, height / DESIGN_H)
        canvas.save()
        canvas.translate((width - DESIGN_W * scale) / 2, height - DESIGN_H * scale)
        canvas.scale(scale, scale)

        val bounce = -talk * 16 + breathe * 3 - hop.value
        val body = RectF(12f, 44f + bounce, 832f, 744f + bounce)
        val pivotX = body.centerX()
        val pivotY = body.bottom
        canvas.translate(pivotX, pivotY)
        canvas.rotate(Math.toDegrees(lean.value.toDouble()).toFloat())
        val stretch = 1 + (hop.value / 900).coerceIn(-0.03f, 0.03f)
        canvas.scale(2 - stretch, stretch)
        canvas.translate(-pivotX, -pivotY)

        val blob = blobPath(body)

        fill.shader = null
        fill.color = Palette.BERRY3
        fill.setShadowLayer(45f, 0f, -10f, Palette.alpha(Palette.BERRY2, 0.5f))
        canvas.drawPath(blob, fill)
        fill.clearShadowLayer()

        fill.shader = skin(body)
        canvas.drawPath(blob, fill)
        canvas.save()
        canvas.clipPath(blob)
        fill.shader = shine(body)
        canvas.drawPath(blob, fill)
        fill.shader = null
        fill.color = Palette.alpha(Color.WHITE, 0.35f)
        canvas.drawOval(RectF(body.left + 190, body.top + 40, body.left + 236, body.top + 62), fill)
        canvas.restore()

        val navy = Palette.NOSE
        val ink = Palette.INK
        fill.color = navy
        canvas.drawPath(crown(RectF(body.left + 368, body.top - 20 - browLift.value * 0.3f, body.left + 452, body.top + 36 - browLift.value * 0.3f)), fill)

        val eyeY = body.top + 157
        val eyes = listOf(body.left + 280 to eyeY, body.left + 540 to eyeY)
        val squash = 1 - talk * 0.12f
        val sq = squint.value.coerceIn(0f, 1f)

        // Blushy cheeks, rosier when he's happy.
        fill.maskFilter = BlurMaskFilter(10f * scale, BlurMaskFilter.Blur.NORMAL)
        eyes.forEachIndexed { i, (cx, cy) ->
            val side = if (i == 0) -1 else 1
            fill.color = Palette.alpha(Palette.CHEEK, 0.25f + 0.45f * blush.value)
            canvas.drawOval(RectF(cx - 45 + side * 62, cy + 96, cx + 45 + side * 62, cy + 138), fill)
        }
        fill.maskFilter = null

        eyes.forEachIndexed { i, (cx, cy) ->
            val side = if (i == 0) -1f else 1f

            // Little arched eyebrows do a lot of the acting.
            canvas.save()
            val browY = cy - 112 - browLift.value.coerceIn(-10f, 18f) * 0.7f
            canvas.translate(cx + side * 6, browY)
            val tilt = browTilt.value * side * -1 + if (mood == Mood.THINKING && i == 1) -0.25f else 0f
            canvas.rotate(Math.toDegrees(tilt.toDouble()).toFloat())
            val arch = Path().apply {
                moveTo(-38f, 6f)
                quadTo(0f, -12f, 38f, 6f)
            }
            stroke.color = navy
            stroke.strokeWidth = 14f
            canvas.drawPath(arch, stroke)
            canvas.restore()

            when {
                mood == Mood.RESTING -> {
                    val shut = Path().apply {
                        moveTo(cx - 62, cy + 6)
                        quadTo(cx, cy + 52, cx + 62, cy + 6)
                    }
                    stroke.color = ink
                    stroke.strokeWidth = 18f
                    canvas.drawPath(shut, stroke)
                }

                mood == Mood.HAPPY && sq > 0.6f -> {
                    val arc = Path().apply {
                        moveTo(cx - 60, cy + 34)
                        quadTo(cx, cy - 54, cx + 60, cy + 34)
                    }
                    stroke.color = ink
                    stroke.strokeWidth = 24f
                    canvas.drawPath(arc, stroke)
                }

                else -> drawEye(canvas, cx, cy, side, closed, squash, sq, body, now)
            }
        }

        if (mood == Mood.RESTING) {
            for (i in 0 until 3) {
                val phase = (now * 0.35f + i / 3f) % 1f
                zPaint.textSize = 26f + i * 8f
                zPaint.color = Palette.alpha(Color.WHITE, 0.9f * sin(PI.toFloat() * phase))
                canvas.drawText(
                    "z",
                    body.left + 660 + phase * 70 + sin(phase * 6) * 8,
                    body.top + 90 - phase * 110,
                    zPaint
                )
            }
        }

        if (mood == Mood.THINKING) {
            for (i in 0 until 3) {
                val wobble = sin(now * 3 + i) * 4
                fill.color = Palette.alpha(Color.WHITE, 1f - i * 0.3f)
                val x = body.left + 690 + i * 26
                val y = body.top + 40 - i * 12 + wobble
                canvas.drawOval(RectF(x, y, x + 16, y + 16), fill)
            }
        }

        // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
        val noseW = 60 - talk * 8
        val noseH = 38 + talk * 34
        val nose = RectF(body.left + 410 - noseW / 2, body.top + 272 - talk * 6, 0f, 0f).apply {
            right = left + noseW
            bottom = top + noseH
        }
        fill.color = navy
        if (mood == Mood.HAPPY && talk < 0.1f) {
            val smile = Path().apply {
                moveTo(nose.left - 8, nose.top + 8)
                quadTo(nose.centerX(), nose.top + 58, nose.right + 8, nose.top + 8)
                close()
            }
            canvas.drawPath(smile, fill)
        } else {
            canvas.drawOval(nose, fill)
            if (talk > 0.25f) {
                fill.color = Palette.alpha(0xFFE58BC4.toInt(), 0.9f)
                val tw = noseW * 0.56f
                val th = noseH * 0.3f
                val tx = nose.centerX() - tw / 2
                val ty = nose.bottom - noseH * 0.38f
                canvas.drawOval(RectF(tx, ty, tx + tw, ty + th), fill)
            }
        }

        canvas.restore()
        postInvalidateOnAnimation()
    }

    private fun drawEye(
        canvas: Canvas, cx: Float, cy: Float, side: Float, closed: Float, squash: Float, sq: Float, body: RectF, now: Float,
    ) {
        val open = max(0.06f, 1 - closed) * squash
        val white = RectF(cx - 95, cy - 95 * open, cx + 95, cy + 95 * open)
        fill.color = Color.WHITE
        canvas.drawOval(white, fill)
        if (open <= 0.2f) return

        var gx = gazeX.value
        var gy = gazeY.value
        val len = hypot(gx, gy)
        if (len > 1) {
            gx /= len
            gy /= len
        }
        val reach = 50f
        val r = 46 * pupil.value
        val px = cx + gx * reach - side * 3
        var py = cy + gy * reach * open
        if (mood == Mood.SLEEPY) py = max(py, cy) + 28

        canvas.save()
        canvas.clipPath(Path().apply { addOval(white, Path.Direction.CW) })
        fill.color = Palette.INK
        canvas.drawCircle(px, py, r, fill)
        fill.color = Color.WHITE
        canvas.drawOval(RectF(px - r * 0.58f, py - r * 0.72f, px - r * 0.02f, py - r * 0.16f), fill)
        fill.color = Palette.alpha(Color.WHITE, 0.85f)
        canvas.drawOval(RectF(px + r * 0.28f, py + r * 0.22f, px + r * 0.52f, py + r * 0.46f), fill)

        // Happy cheeks push up from below as he smiles.
        if (sq > 0.02f) {
            fill.shader = skin(body)
            canvas.drawOval(RectF(cx - 110, cy + 95 * open - 70 * sq, cx + 110, cy + 95 * open - 70 * sq + 160), fill)
            fill.shader = null
        }
        // Heavy, droopy lids when he's sleepy.
        if (mood == Mood.SLEEPY) {
            val droop = 0.5f + 0.06f * sin(now * 1.3f)
            fill.shader = skin(body)
            canvas.drawRect(cx - 100, white.top - 10, cx + 100, white.top + white.height() * droop, fill)
            fill.shader = null
            stroke.color = Palette.alpha(Palette.NOSE, 0.7f)
            stroke.strokeWidth = 8f
            val ly = white.top + white.height() * droop
            canvas.drawLine(cx - 92, ly, cx + 92, ly, stroke)
        }
        // A soft upper lid when he's focused on pointing.
        if (mood == Mood.POINTING) {
            fill.shader = skin(body)
            canvas.drawRect(cx - 100, white.top - 20, cx + 100, white.top + 20, fill)
            fill.shader = null
        }
        canvas.restore()
    }

    private fun skin(r: RectF): Shader = LinearGradient(
        r.left + 0.25f * r.width(), r.top + 0.067f * r.height(),
        r.left + 0.75f * r.width(), r.top + 0.933f * r.height(),
        Palette.GRADIENT, Palette.STOPS, Shader.TileMode.CLAMP
    )

    private fun shine(r: RectF): Shader = RadialGradient(
        r.left + 246, r.top + 126, 243f,
        intArrayOf(Palette.alpha(Color.WHITE, 0.5f), Palette.alpha(Color.WHITE, 0f)), null, Shader.TileMode.CLAMP
    )

    /** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%`. */
    private fun blobPath(r: RectF): Path {
        val w = r.width()
        val h = r.height()
        val tlW = 0.52f * w; val tlH = 0.58f * h
        val trW = 0.48f * w; val trH = 0.56f * h
        val brW = 0.46f * w; val brH = 0.44f * h
        val blW = 0.54f * w; val blH = 0.42f * h
        val k = 0.5523f
        return Path().apply {
            moveTo(r.left + tlW, r.top)
            lineTo(r.right - trW, r.top)
            cubicTo(r.right - trW * (1 - k), r.top, r.right, r.top + trH * (1 - k), r.right, r.top + trH)
            lineTo(r.right, r.bottom - brH)
            cubicTo(r.right, r.bottom - brH * (1 - k), r.right - brW * (1 - k), r.bottom, r.right - brW, r.bottom)
            lineTo(r.left + blW, r.bottom)
            cubicTo(r.left + blW * (1 - k), r.bottom, r.left, r.bottom - blH * (1 - k), r.left, r.bottom - blH)
            lineTo(r.left, r.top + tlH)
            cubicTo(r.left, r.top + tlH * (1 - k), r.left + tlW * (1 - k), r.top, r.left + tlW, r.top)
            close()
        }
    }

    /** The little blueberry crown on his head (from the design's 60×40 box). */
    private fun crown(r: RectF): Path {
        val pts = listOf(30 to 4, 36 to 16, 52 to 12, 40 to 24, 46 to 36, 30 to 28, 14 to 36, 20 to 24, 8 to 12, 24 to 16)
        return Path().apply {
            pts.forEachIndexed { i, (x, y) ->
                val px = r.left + x / 60f * r.width()
                val py = r.top + y / 40f * r.height()
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }
}
