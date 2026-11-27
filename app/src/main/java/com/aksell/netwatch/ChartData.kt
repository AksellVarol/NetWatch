package com.aksell.netwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Pure chart math — no Android classes, so it runs in the JVM unit tests.
// A point is (timestampMs, valueMs). valueMs < 0 means "measurement failed":
// charts draw a GAP there, never 0ms, and stats ignore failed points.
object ChartData {
    data class Stats(val count: Int, val valid: Int, val failed: Int, val min: Double, val avg: Double, val max: Double)

    fun inRange(points: List<Pair<Long, Double>>, sinceMs: Long): List<Pair<Long, Double>> =
        points.filter { it.first >= sinceMs }

    fun stats(points: List<Pair<Long, Double>>): Stats {
        val valid = points.filter { it.second >= 0 }
        return Stats(
            points.size, valid.size, points.size - valid.size,
            valid.minOfOrNull { it.second } ?: 0.0,
            if (valid.isEmpty()) 0.0 else valid.sumOf { it.second } / valid.size,
            valid.maxOfOrNull { it.second } ?: 0.0
        )
    }

    // Split a time-ordered series into drawable segments: a failed point breaks
    // the line. A lone valid point between failures is its own segment (a dot).
    fun segments(points: List<Pair<Long, Double>>): List<List<Pair<Long, Double>>> {
        val out = mutableListOf<List<Pair<Long, Double>>>()
        var cur = mutableListOf<Pair<Long, Double>>()
        for (p in points) {
            if (p.second < 0) {
                if (cur.isNotEmpty()) { out.add(cur); cur = mutableListOf() }
            } else cur.add(p)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    // Y-axis max: P95-based, so one huge spike does not flatten the rest of
    // the chart. Points above the max are clipped at the top edge.
    fun yMax(values: List<Double>): Double {
        if (values.isEmpty()) return 100.0
        val sorted = values.sorted()
        val p95 = sorted[(kotlin.math.ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)) - 1]
        return maxOf(100.0, p95 * 1.2)
    }

    // v2.9.7 — time-axis labels with SECOND resolution. The v2.9.6 widget
    // used HH:mm over a 60-second window, so the first and last sample
    // usually fell in the same minute and both labels were identical.
    // Returns start, optional middle, end — the middle only when it
    // differs from both ends; a single timestamp returns one label.
    // Pure — JVM-tested.
    fun timeLabels(t0: Long, t1: Long): List<String> {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val start = fmt.format(Date(t0))
        val end = fmt.format(Date(t1))
        if (t0 == t1 || start == end) return listOf(start)
        val mid = fmt.format(Date(t0 + (t1 - t0) / 2))
        return if (mid == start || mid == end) listOf(start, end) else listOf(start, mid, end)
    }

    // Packet-loss strip (v2.9.6): per-bucket loss fraction over [windowStart,
    // windowEnd). A bucket's value is failed/total for the points that fall
    // in it; a bucket with no points is 0.0 (no loss evidence, not unknown).
    // A failed point is any value that is not a valid ping (>= 0) — that
    // includes NaN, which stats() also counts as failed. Points outside the
    // window are ignored. Pure — JVM-tested.
    fun lossBuckets(points: List<Pair<Long, Double>>, windowStart: Long, windowEnd: Long, bucketCount: Int): List<Double> {
        if (bucketCount <= 0) return emptyList()
        if (windowEnd <= windowStart) return List(bucketCount) { 0.0 }
        val width = (windowEnd - windowStart).toDouble() / bucketCount
        val total = IntArray(bucketCount)
        val failed = IntArray(bucketCount)
        for ((t, v) in points) {
            if (t < windowStart || t >= windowEnd) continue
            val idx = ((t - windowStart).toDouble() / width).toInt().coerceIn(0, bucketCount - 1)
            total[idx]++
            if (!(v >= 0)) failed[idx]++
        }
        return List(bucketCount) { i -> if (total[i] == 0) 0.0 else failed[i].toDouble() / total[i] }
    }
}
