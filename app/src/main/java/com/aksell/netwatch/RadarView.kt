package com.aksell.netwatch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Radar hero (Home, v2.11.0 Phase 1). UI only: concentric rings + a sweeping
// wedge + a center status dot, drawn on Canvas like GaugeView.
// SEPARATION (binding rule): the sweep is a UI-thread ValueAnimator that only
// invalidates THIS view. It never posts to NetExecutor, never calls
// MonitorService, and never touches sampling: the 5s gaming ticks and the
// 30s monitor loop run on NetExecutor and are completely independent of this
// animation. The activity starts the sweep in onResume (only while monitoring
// is on) and stops it in onPause; onDetachedFromWindow cancels it.
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    companion object {
        const val UNKNOWN = 0
        const val ONLINE = 1
        const val WEAK = 2
        const val OFFLINE = 3
    }

    var state: Int = UNKNOWN
        private set

    private var sweepAngle = 0f
    private var sweeping = false
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweepAngle = it.animatedValue as Float; invalidate() }
    }

    private val ringPaint = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = 2f; isAntiAlias = true; style = Paint.Style.STROKE }
    // v2.11.1 Phase 1b: the wedge is a gradient (transparent -> state colour
    // at the leading edge), so the paint itself is fully opaque — the fade
    // comes from the SweepGradient, not from a flat alpha.
    private val sweepPaint = Paint().apply { color = Color.parseColor("#3B82F6"); isAntiAlias = true; style = Paint.Style.FILL }
    private val sweepLinePaint = Paint().apply { color = Color.parseColor("#60A5FA"); strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val dotPaint = Paint().apply { color = Color.parseColor("#7C8AA0"); isAntiAlias = true; style = Paint.Style.FILL }

    // Gradient state (UI only): the SweepGradient is rebuilt only when the
    // size or the state colour changes; every frame only its rotation matrix
    // is updated to follow sweepAngle.
    private var sweepShader: SweepGradient? = null
    private var shaderW = 0
    private var shaderH = 0
    private var shaderColor = 0
    private var sweepColor = Color.parseColor("#7C8AA0")

    // The sweep colour follows the state, so the state stays visible even
    // though the status text overlay covers the center dot.
    fun setState(s: Int) {
        state = when (s) { ONLINE, WEAK, OFFLINE -> s; else -> UNKNOWN }
        val col = when (state) {
            ONLINE -> "#22C55E"
            WEAK -> "#F59E0B"
            OFFLINE -> "#EF4444"
            else -> "#7C8AA0"
        }
        dotPaint.color = Color.parseColor(col)
        sweepPaint.color = Color.parseColor(col)
        sweepLinePaint.color = Color.parseColor(col)
        sweepColor = Color.parseColor(col)
        sweepShader = null // state colour changed — rebuild the gradient
        invalidate()
    }

    fun setSweeping(on: Boolean) {
        if (on == sweeping) return
        sweeping = on
        if (on) {
            if (!animator.isRunning) animator.start()
        } else {
            animator.cancel()
            sweepAngle = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        sweeping = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8f
        if (r <= 0f) return
        for (f in floatArrayOf(1f, 0.66f, 0.33f)) canvas.drawCircle(cx, cy, r * f, ringPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, ringPaint)
        if (sweeping) {
            if (sweepShader == null || shaderW != width || shaderH != height || shaderColor != sweepColor) {
                // Gradient space: 0 = 3 o'clock, clockwise. Transparent for
                // most of the circle, fading up to the state colour at the
                // leading edge of the 60-degree wedge (position 1.0).
                sweepShader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, sweepColor), floatArrayOf(0f, 0.833f, 1f))
                shaderW = width; shaderH = height; shaderColor = sweepColor
            }
            // Rotate the gradient so its full-colour end sits exactly on the
            // leading edge of the wedge (view angle sweepAngle).
            val m = Matrix()
            m.setRotate(sweepAngle - 360f, cx, cy)
            sweepShader!!.setLocalMatrix(m)
            sweepPaint.shader = sweepShader
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, sweepAngle - 60f, 60f, true, sweepPaint)
            val rad = Math.toRadians(sweepAngle.toDouble())
            canvas.drawLine(cx, cy, cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), sweepLinePaint)
        }
        canvas.drawCircle(cx, cy, 9f, dotPaint)
    }
}
