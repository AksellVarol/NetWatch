package com.aksell.netwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

// Dashboard / History / Gaming latency chart (v2.6, strip v2.9.6) — TIME-based, not
// "last 60 samples".
// - points: (timestampMs, ms); ms < 0 means the measurement FAILED: the line
//   breaks there (a visible gap) and a red bar marks the loss in the strip
//   below the chart. Failed samples are never drawn as 0ms.
// - The x axis is real time (first sample time … last sample time, HH:mm).
// - The y axis uses ChartData.yMax (P95-based), so one huge spike does not
//   flatten the rest of the chart; points above it are clipped at the top.
// - A lone valid point between failures is a minimal 3.5f data mark.
// - pctPoints: optional second series (sources-ok %), drawn on the same
//   time axis — the two series can never be misaligned, because both carry
//   their own timestamps.
class GraphView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var points: List<Pair<Long, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    var pctPoints: List<Pair<Long, Double>> = emptyList()
        set(v) { field = v; invalidate() }

    private val gridPaint = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = 2f }
    private val axisPaint = Paint().apply { color = Color.parseColor("#7C8AA0"); textSize = 28f; isAntiAlias = true }
    private val linePaint = Paint().apply { color = Color.parseColor("#2DD4BF"); strokeWidth = 4f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val fillPaint = Paint().apply { color = Color.parseColor("#142E2B"); style = Paint.Style.FILL; alpha = 160 }
    private val dotPaint = Paint().apply { color = Color.parseColor("#2DD4BF"); isAntiAlias = true; style = Paint.Style.FILL }
    private val stripLabelPaint = Paint().apply { color = Color.parseColor("#7C8AA0"); textSize = 20f; isAntiAlias = true }
    private val stripBasePaint = Paint().apply { color = Color.parseColor("#22C55E"); strokeWidth = 3f; isAntiAlias = true }
    private val stripLossPaint = Paint().apply { color = Color.parseColor("#EF4444"); isAntiAlias = true; style = Paint.Style.FILL }
    private val emptyPaint = Paint().apply { color = Color.parseColor("#94A3B8"); textSize = 32f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val emptySubPaint = Paint().apply { color = Color.parseColor("#7C8AA0"); textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        // v2.9.6: the bottom of the view is the packet-loss strip (red bars
        // on a green baseline) plus the time labels — the line chart sits
        // above it. Failed samples are gaps in the line + red bars in the
        // strip, never a fake ping value and never a tick inside the chart.
        val padL = 92f; val padR = 12f; val padT = 12f
        val stripH = 22f; val stripGap = 8f; val labelH = 28f
        val padB = stripH + stripGap + labelH
        val plotW = w - padL - padR; val plotH = h - padT - padB
        val valid = points.filter { it.second >= 0 }.map { it.second }
        val maxV = ChartData.yMax(valid)
        val topLabel = "${"%.0f".format(maxV)}ms"
        val midLabel = "${"%.0f".format(maxV / 2)}ms"
        for (i in 0..2) {
            val y = padT + plotH * i / 2
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            val label = when (i) { 0 -> topLabel; 1 -> midLabel; else -> "0ms" }
            canvas.drawText(label, 4f, y + 10f, axisPaint)
        }
        if (points.isEmpty()) {
            canvas.drawText("هنوز داده‌ای نیست", w / 2, h / 2 - 8f, emptyPaint)
            canvas.drawText("بعد از اولین بررسی، نمودار اینجا رسم می‌شود", w / 2, h / 2 + 30f, emptySubPaint)
            return
        }
        // Real time axis: first … last sample time.
        val t0 = points.minOf { it.first }
        val t1 = points.maxOf { it.first }
        val span = (t1 - t0).coerceAtLeast(1L)
        fun xOf(t: Long): Float = padL + (t - t0).toFloat() / span * plotW
        fun yOf(ms: Double): Float = padT + plotH - (ms.coerceAtMost(maxV) / maxV * plotH).toFloat()
        // Time labels (v2.9.7): ChartData.timeLabels with SECOND
        // resolution — HH:mm gave the same label for both ends over a
        // 60s window. Middle label only when distinct and non-overlapping.
        val labels = ChartData.timeLabels(t0, t1)
        canvas.drawText(labels[0], padL, h - 6f, axisPaint)
        if (labels.size == 3) {
            val mid = labels[1]
            val midX = w / 2f - axisPaint.measureText(mid) / 2
            val leftEnd = padL + axisPaint.measureText(labels[0])
            val rightStart = w - padR - axisPaint.measureText(labels[2])
            if (midX > leftEnd + 8f && midX + axisPaint.measureText(mid) < rightStart - 8f) {
                canvas.drawText(mid, midX, h - 6f, axisPaint)
            }
        }
        if (labels.size > 1) {
            val nowLabel = labels.last()
            canvas.drawText(nowLabel, w - padR - axisPaint.measureText(nowLabel), h - 6f, axisPaint)
        }
        // Latency line, broken at failures. A lone valid point between
        // failures is a minimal 3.5f data mark — not a decorated circle —
        // and there is no halo on the last point.
        for (seg in ChartData.segments(points)) {
            if (seg.size == 1) {
                canvas.drawCircle(xOf(seg[0].first), yOf(seg[0].second), 3.5f, dotPaint)
                continue
            }
            val path = Path(); val fill = Path()
            for ((i, p) in seg.withIndex()) {
                val x = xOf(p.first); val y = yOf(p.second)
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, padT + plotH); fill.lineTo(x, y) }
                else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(xOf(seg.last().first), padT + plotH); fill.close()
            canvas.drawPath(fill, fillPaint)
            canvas.drawPath(path, linePaint)
        }
        // Packet-loss strip (v2.9.7): its own band with a divider line
        // above it and its own "%" scale label — visually separated from
        // the latency ms scale. Green baseline when there is no loss; red
        // bars where packets were lost. Timeouts appear here as red bars
        // + a gap in the line.
        val stripTop = padT + plotH + stripGap
        val stripBottom = stripTop + stripH
        val bandPaint = Paint().apply { color = Color.parseColor("#0C1526"); style = Paint.Style.FILL }
        canvas.drawRect(padL, stripTop - 4f, w - padR, stripBottom + 3f, bandPaint)
        canvas.drawLine(padL, stripTop - 4f, w - padR, stripTop - 4f, gridPaint)
        val bucketCount = 24
        val buckets = ChartData.lossBuckets(points, t0, t1, bucketCount)
        val bw = plotW / bucketCount
        for ((i, frac) in buckets.withIndex()) {
            if (frac > 0) {
                val left = padL + i * bw + 1f
                canvas.drawRect(left, stripBottom - frac.toFloat() * stripH, left + bw - 2f, stripBottom, stripLossPaint)
            }
        }
        canvas.drawLine(padL, stripBottom, w - padR, stripBottom, stripBasePaint)
        canvas.drawText("Loss %", 4f, stripTop + stripH / 2 + 7f, stripLabelPaint)
        // Sources-ok % on the same time axis (purple, right side).
        if (pctPoints.isNotEmpty()) {
            val pctPaint = Paint().apply { color = Color.parseColor("#A855F7"); strokeWidth = 4f; isAntiAlias = true; style = Paint.Style.STROKE }
            val pctPath = Path()
            var started = false
            for (p in pctPoints) {
                val x = xOf(p.first)
                val y = padT + plotH - (p.second.coerceIn(0.0, 100.0) / 100.0 * plotH).toFloat()
                if (!started) { pctPath.moveTo(x, y); started = true } else pctPath.lineTo(x, y)
            }
            canvas.drawPath(pctPath, pctPaint)
            val rightPaint = Paint().apply { color = Color.parseColor("#A855F7"); textSize = 26f; isAntiAlias = true }
            canvas.drawText("100%", w - padR - rightPaint.measureText("100%"), padT + 10f, rightPaint)
            canvas.drawText("0%", w - padR - rightPaint.measureText("0%"), padT + plotH, rightPaint)
        }
    }
}
