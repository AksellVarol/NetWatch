package com.aksell.netwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

// Mini sparkline for the Home stat cards (v2.6).
// - Invalid values (NaN, negative) are filtered out before drawing — they
//   used to corrupt the scale.
// - Points spread across the full width (the old code divided by a fixed 59,
//   so fewer points were squeezed into the left corner).
// - Flat data (all values equal) draws a flat line instead of a broken scale.
class SparklineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var values: List<Double> = emptyList()
        set(v) { field = v.filter { !it.isNaN() && it >= 0 }; invalidate() }
    var lineColor: Int = Color.parseColor("#2DD4BF")
    var fillColor: Int = Color.parseColor("#142E2B")

    private val linePaint = Paint().apply { strokeWidth = 4f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; alpha = 140 }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val maxV = values.maxOrNull() ?: 0.0
        val minV = values.minOrNull() ?: 0.0
        val span = (maxV - minV).coerceAtLeast(1.0)
        linePaint.color = lineColor; fillPaint.color = fillColor
        val path = Path(); val fill = Path()
        val n = values.size
        val stepX = if (n > 1) w / (n - 1) else w
        for (i in values.indices) {
            val x = i * stepX
            val y = h - 4f - ((values[i] - minV) / span * (h - 8f)).toFloat()
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo((n - 1) * stepX, h); fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
