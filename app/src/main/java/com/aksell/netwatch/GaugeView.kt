package com.aksell.netwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// Circular gauge ring (Home, mockup v2.3). UI only: progress = % of healthy sources
// from the last check (0-100). Arc runs 270° starting at 135°.
class GaugeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var progress: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    private val bgPaint = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = 26f; isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fgPaint = Paint().apply { strokeWidth = 26f; isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 20f
        val rect = RectF(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 135f, 270f, false, bgPaint)
        val col = when {
            progress >= 90 -> "#22C55E"
            progress >= 70 -> "#2DD4BF"
            progress >= 40 -> "#F59E0B"
            else -> "#EF4444"
        }
        fgPaint.color = Color.parseColor(col)
        canvas.drawArc(rect, 135f, 270f * progress / 100f, false, fgPaint)
    }
}
