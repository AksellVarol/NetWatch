package com.aksell.netwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.widget.RemoteViews

// NetWatch widget (v2.2, stats panel + chart strip v2.9.6) — matches the home-screen mockup: header + status card +
// 4 metric tiles + latency chart + live network statistics + tip bar. Every value is real data
// from the last full check (History.lastCheck), the ping history, or the last
// gaming test. Nothing is invented: missing data shows "—", never a sample number.
class NetWatchWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(ctx, mgr, id)
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, NetWatchWidget::class.java))
            for (id in ids) updateOne(ctx, mgr, id)
        }

        private fun ageText(ts: Long): String {
            if (ts <= 0) return "time unknown"
            val m = (System.currentTimeMillis() - ts) / 60000
            return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
        }

        fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            History.ensureRecovered(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_netwatch)
            try {
                val vn = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                views.setTextViewText(R.id.wVersion, "Internet Monitor v$vn")
            } catch (e: Exception) { }
            val lc = HistoryEngine.lastCheck(ctx)
            val samples = HistoryEngine.samples(ctx)
            val last = samples.lastOrNull()
            // One status source of truth: the widget shows the same word as the app
            // headline (Quality.widgetStatus) for the last full check, with the check
            // age next to it. Only when no full check exists yet does it fall back
            // to the last background sample.
            val (status, sub, statusColor) = when {
                lc != null && lc.scoreTotal != null -> {
                    val w = AnalyticsEngine.widgetStatus(lc.scoreTotal, lc.scoreStatus)
                    val c = when (w) { "Connected" -> "#22C55E"; "Degraded" -> "#F59E0B"; else -> "#EF4444" }
                    Triple(w, "Quality ${lc.scoreTotal}/100 • ${ageText(lc.checkedAt)}", c)
                }
                lc != null && lc.scoreStatus == "InsufficientData" -> Triple("Degraded", "Incomplete data • ${ageText(lc.checkedAt)}", "#F59E0B")
                last == null -> Triple("Not checked", "Open the app to run a check", "#94A3B8")
                else -> {
                    val ok = last.second; val total = last.third
                    val okPct = if (total == 0) -1 else ok * 100 / total
                    when {
                        ok == total -> Triple("Connected", "Background check • ${ageText(last.first)}", "#22C55E")
                        okPct >= 70 -> Triple("Degraded", "Background check • ${ageText(last.first)}", "#F59E0B")
                        else -> Triple("Offline", "Background check • ${ageText(last.first)}", "#EF4444")
                    }
                }
            }
            views.setTextViewText(R.id.wStatus, status)
            views.setTextViewText(R.id.wStatusSub, sub)
            views.setTextColor(R.id.wStatus, Color.parseColor(statusColor))

            val intl = lc?.intlPct ?: -1
            val (intlText, intlColor) = when {
                intl < 0 -> "—" to "#94A3B8"
                intl >= 70 -> "Active" to "#22C55E"
                intl >= 40 -> "Degraded" to "#F59E0B"
                else -> "Down" to "#EF4444"
            }
            views.setTextViewText(R.id.wIntl, intlText)
            views.setTextColor(R.id.wIntl, Color.parseColor(intlColor))
            views.setTextViewText(R.id.wOperator, Geo.operatorName(ctx))

            // Metric tiles. If the last full check's Cloudflare ping failed, the
            // tiles used to show "—" even though real ping data exists in history.
            // Fall back to the last successful value instead of a bare dash.
            val histAll = HistoryEngine.pingHistory(ctx)
            val lastPing = HistoryEngine.lastPing(ctx)
            val liveOn = MonitoringEngine.livePingOn(ctx)
            val freshLive = liveOn && lastPing != null && System.currentTimeMillis() - lastPing.first < 10_000
            if (freshLive && lastPing != null) {
                // Live Ping is on: show the newest measurement, labelled as live.
                views.setTextViewText(R.id.wPing, "${"%.0f".format(lastPing.second)} ms")
                views.setTextViewText(R.id.wPingSub, "زنده")
            } else if (lc != null && lc.pingAvg >= 0 && lc.checkedAt >= (lastPing?.first ?: 0)) {
                views.setTextViewText(R.id.wPing, "${"%.0f".format(lc.pingAvg)} ms")
                val prev = if (histAll.size >= 2) histAll[histAll.size - 2] else null
                val change = if (prev != null && prev > 0) ((prev - lc.pingAvg) / prev * 100).toInt() else null
                views.setTextViewText(R.id.wPingSub, if (change == null) "Avg" else if (change >= 0) "↓ $change%" else "↑ ${-change}%")
            } else if (histAll.isNotEmpty()) {
                views.setTextViewText(R.id.wPing, "${"%.0f".format(histAll.last())} ms")
                views.setTextViewText(R.id.wPingSub, "آخرین موفق")
            } else {
                views.setTextViewText(R.id.wPing, "—")
                views.setTextViewText(R.id.wPingSub, "Avg")
            }
            // The "Live" pill must mean something: it says Live only while the
            // service's heartbeat is fresh (a real measurement landed in the
            // last 15 seconds). A switch left on after the service died, or a
            // stale ping, never shows Live.
            val pillLive = MonitoringEngine.heartbeatFresh(ctx)
            views.setTextViewText(R.id.wLive, if (pillLive) "Live" else "Paused")
            views.setTextColor(R.id.wLive, Color.parseColor(if (pillLive) "#22C55E" else "#94A3B8"))
            if (lc != null && lc.jitterMs >= 0) {
                views.setTextViewText(R.id.wJitter, "${"%.1f".format(lc.jitterMs)} ms")
                views.setTextViewText(R.id.wJitterSub, "mdev")
            } else if (histAll.size >= 2) {
                val tail = histAll.takeLast(10)
                val approx = (1 until tail.size).map { kotlin.math.abs(tail[it] - tail[it - 1]) }.average()
                views.setTextViewText(R.id.wJitter, "${"%.1f".format(approx)} ms")
                views.setTextViewText(R.id.wJitterSub, "تقریبی")
            } else {
                views.setTextViewText(R.id.wJitter, "—")
                views.setTextViewText(R.id.wJitterSub, "mdev")
            }
            val lastLoss = HistoryEngine.lossHistory(ctx).lastOrNull()
            if (lc != null && lc.lossPct >= 0) {
                views.setTextViewText(R.id.wLoss, "${"%.1f".format(lc.lossPct)}%")
                views.setTextViewText(R.id.wLossSub, when { lc.lossPct == 0.0 -> "Good"; lc.lossPct < 3 -> "Fair"; else -> "Poor" })
            } else if (lastLoss != null) {
                views.setTextViewText(R.id.wLoss, "${"%.1f".format(lastLoss)}%")
                views.setTextViewText(R.id.wLossSub, when { lastLoss == 0.0 -> "Good"; lastLoss < 3 -> "Fair"; else -> "Poor" })
            } else {
                views.setTextViewText(R.id.wLoss, "—")
                views.setTextViewText(R.id.wLossSub, "—")
            }
            // Bufferbloat tile (v2.9.6): wired to the REAL measurement stored
            // by Gaming.saveBufferbloat — four distinct states, never a fake
            // value. The old "last" summary grade is no longer used here.
            val storedBb = try { Gaming.lastBufferbloat(ctx) } catch (e: Exception) { null }
            val bbTesting = try { Gaming.isBufferbloatTesting(ctx) } catch (e: Exception) { false }
            val (bbGrade, bbSub, bbColor) = WidgetStats.bufferbloatDisplay(storedBb?.bb, storedBb?.ts ?: 0L, bbTesting, System.currentTimeMillis())
            views.setTextViewText(R.id.wBuffer, bbGrade)
            views.setTextViewText(R.id.wBufferSub, bbSub)
            views.setTextColor(R.id.wBuffer, Color.parseColor(bbColor))
            views.setTextColor(R.id.wBufferSub, Color.parseColor(if (storedBb?.bb?.valid == true && !bbTesting) "#94A3B8" else bbColor))

            // Latency chart (v2.9.6): timestamped points from the same data
            // source as the app, filtered to the LAST 60 SECONDS — the title
            // says "Last 60s", so passing all points was a bug. Failed pings
            // are gaps in the line + red bars in the loss strip, not zeros.
            // The stats line comes from the same points, and names the age
            // of the last measurement so an old chart is never mistaken for
            // a live one.
            val nowMs = System.currentTimeMillis()
            val pts = HistoryEngine.pingPoints(ctx, nowMs - 60_000)
            views.setImageViewBitmap(R.id.wGraph, drawLatencyChart(pts))
            val wst = ChartData.stats(pts)
            views.setTextViewText(R.id.wChartStats, if (wst.valid == 0) "Min: —  Avg: —  Max: —"
                else "Min: ${"%.0f".format(wst.min)}  Avg: ${"%.0f".format(wst.avg)}  Max: ${"%.0f".format(wst.max)} ms • last ${ageText(pts.last().first)}")

            // Live Network Statistics panel (v2.9.6): speeds, DNS/gateway
            // latency, local/public IP, connection type, data usage — all
            // real, from WidgetStats.snapshot (no network I/O here). The
            // old Game Profile / Server Region / Connection Type rows are
            // gone; connection type now comes from WidgetStats.transportType
            // (transports only), not Geo.connectionType.
            try {
                val snap = WidgetStats.snapshot(ctx)
                views.setTextViewText(R.id.wNetDown, snap.down)
                views.setTextViewText(R.id.wNetUp, snap.up)
                views.setTextViewText(R.id.wNetDns, snap.dns)
                views.setTextViewText(R.id.wNetGw, snap.gateway)
                views.setTextViewText(R.id.wNetLocalIp, snap.localIp)
                views.setTextViewText(R.id.wNetPubIp, snap.publicIp)
                views.setTextViewText(R.id.wNetConn, snap.connType)
                views.setTextViewText(R.id.wNetUsage, snap.usage)
                views.setTextColor(R.id.wNetDns, Color.parseColor(when (snap.dnsOk) { true -> "#22C55E"; false -> "#EF4444"; null -> "#94A3B8" }))
                views.setTextColor(R.id.wNetGw, Color.parseColor(when (snap.gwOk) { true -> "#22C55E"; false -> "#EF4444"; null -> "#94A3B8" }))
                val agePart = if (snap.updatedAtMs > 0) "Updated ${ageText(snap.updatedAtMs)}" else "Updated —"
                val winPart = if (snap.speedWindowSec > 0) "speed window ${snap.speedWindowSec}s" else "speed window —"
                views.setTextViewText(R.id.wNetAge, "$agePart • $winPart • usage since boot")
            } catch (e: Exception) {
                // The panel keeps its layout defaults; a stats failure must
                // never break the rest of the widget update.
            }

            // Network Score row (kept).
            val score = lc?.scoreTotal
            if (score != null) {
                views.setTextViewText(R.id.wScore, "$score / 100")
                views.setProgressBar(R.id.wScoreBar, 100, score, false)
            } else {
                views.setTextViewText(R.id.wScore, if (lc?.scoreStatus == "InsufficientData") "Insufficient data" else "—")
                views.setProgressBar(R.id.wScoreBar, 100, 0, false)
            }
            val tip = when {
                lc == null -> "No data yet — open the app to run a check."
                lc.scoreStatus == "InsufficientData" -> "Not enough data yet — run a check."
                score != null && score >= 80 -> "Excellent connection! Enjoy your game."
                score != null && score >= 50 -> "Connection is fair — check details."
                score != null -> "Connection is poor — see details."
                else -> "No data yet — open the app to run a check."
            }
            views.setTextViewText(R.id.wTip, tip)

            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.wRoot, pi)
            views.setOnClickPendingIntent(R.id.wOverflow, pi)
            views.setOnClickPendingIntent(R.id.wTipBar, pi)
            val gi = Intent(ctx, GamingActivity::class.java).putExtra("start_bufferbloat", true)
            val gpi = PendingIntent.getActivity(ctx, 2, gi, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.wTileBuffer, gpi)
            mgr.updateAppWidget(id, views)
        }

        // Widget latency chart (v2.9.6), rewritten on the same data layer as
        // the app: x is real time (first..last sample), y is ms scaled by
        // ChartData.yMax (P95-based, so one 986 ms outlier does not squash
        // the line), failed pings break the line (a gap + a red bar in the
        // loss strip below) instead of being drawn as 0 ms. One continuous
        // readable line; a lone valid point between failures is a minimal
        // 3.5f data mark, not a decorated circle, and there is no halo on
        // the last point and no red tick inside the chart area. Values above
        // yMax clip at the top edge. Bitmap is 800x340: chart + loss strip
        // + time labels. Built only when a widget exists — refreshAll() is
        // only called with real widget ids.
        private fun drawLatencyChart(points: List<Pair<Long, Double>>): Bitmap {
            val w = 800; val h = 340
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#101A2E"))
            val grid = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = 2f }
            val axis = Paint().apply { color = Color.parseColor("#7C8AA0"); textSize = 30f; isAntiAlias = true }
            val stripLabel = Paint().apply { color = Color.parseColor("#7C8AA0"); textSize = 24f; isAntiAlias = true }
            val valid = points.filter { it.second >= 0 }.map { it.second }
            val maxV = ChartData.yMax(valid)
            val padL = 70f; val padR = 16f; val padT = 16f; val padB = 34f
            val stripH = 36f; val stripGap = 14f
            val plotW = w - padL - padR; val plotH = h - padT - padB - stripH - stripGap
            for (i in 0..4) {
                val y = padT + plotH * i / 4
                c.drawLine(padL, y, w - padR, y, grid)
                c.drawText("${"%.0f".format(maxV * (4 - i) / 4)}", 8f, y + 10f, axis)
            }
            if (points.isEmpty()) {
                val empty = Paint().apply { color = Color.parseColor("#94A3B8"); textSize = 32f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
                c.drawText("No data yet", w / 2f, padT + plotH / 2f, empty)
                return bmp
            }
            val t0 = points.first().first; val t1 = points.last().first
            val span = (t1 - t0).coerceAtLeast(1L)
            val xOf: (Long) -> Float = { t -> padL + plotW * (t - t0).toFloat() / span }
            val yOf: (Double) -> Float = { v -> padT + plotH - (v.coerceAtMost(maxV) / maxV * plotH).toFloat() }
            // Time labels (v2.9.7): ChartData.timeLabels with SECOND
            // resolution — HH:mm gave the same label for both ends over a
            // 60s window. Middle label only when distinct; a single
            // timestamp draws one label.
            val labels = ChartData.timeLabels(t0, t1)
            c.drawText(labels[0], padL, h - 6f, axis)
            if (labels.size == 3) {
                val mid = labels[1]
                val midX = w / 2f - axis.measureText(mid) / 2
                val leftEnd = padL + axis.measureText(labels[0])
                val rightStart = w - padR - axis.measureText(labels[2])
                if (midX > leftEnd + 8f && midX + axis.measureText(mid) < rightStart - 8f) {
                    c.drawText(mid, midX, h - 6f, axis)
                }
            }
            if (labels.size > 1) {
                val lastLabel = labels.last()
                c.drawText(lastLabel, w - padR - axis.measureText(lastLabel), h - 6f, axis)
            }
            val line = Paint().apply { color = Color.parseColor("#22C55E"); strokeWidth = 5f; isAntiAlias = true; style = Paint.Style.STROKE }
            val dot = Paint().apply { color = Color.parseColor("#22C55E"); isAntiAlias = true; style = Paint.Style.FILL }
            val fillPaint = Paint().apply {
                style = Paint.Style.FILL
                shader = LinearGradient(0f, padT, 0f, padT + plotH, Color.parseColor("#1B4332"), Color.parseColor("#101A2E"), Shader.TileMode.CLAMP)
            }
            for (seg in ChartData.segments(points)) {
                if (seg.size == 1) {
                    c.drawCircle(xOf(seg[0].first), yOf(seg[0].second), 3.5f, dot)
                    continue
                }
                val path = Path(); val fp = Path()
                for ((i, pt) in seg.withIndex()) {
                    val x = xOf(pt.first); val y = yOf(pt.second)
                    if (i == 0) { path.moveTo(x, y); fp.moveTo(x, padT + plotH); fp.lineTo(x, y) } else { path.lineTo(x, y); fp.lineTo(x, y) }
                }
                fp.lineTo(xOf(seg.last().first), padT + plotH); fp.close()
                c.drawPath(fp, fillPaint); c.drawPath(path, line)
            }
            // Packet-loss strip (v2.9.7): visually separated from the
            // latency plot — its own band with a divider line above it and
            // its own "%" scale label, so the loss scale is never read as
            // part of the latency ms scale. Timeouts appear here as red
            // bars + a gap in the line above — never as a fake ping value.
            val stripTop = padT + plotH + stripGap
            val stripBottom = stripTop + stripH
            val bandPaint = Paint().apply { color = Color.parseColor("#0C1526"); style = Paint.Style.FILL }
            c.drawRect(padL, stripTop - 6f, w - padR, stripBottom + 4f, bandPaint)
            val dividerPaint = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = 2f; isAntiAlias = true }
            c.drawLine(padL, stripTop - 6f, w - padR, stripTop - 6f, dividerPaint)
            val bucketCount = 40
            val buckets = ChartData.lossBuckets(points, t0, t1, bucketCount)
            val bw = plotW / bucketCount
            val lossPaint = Paint().apply { color = Color.parseColor("#EF4444"); isAntiAlias = true; style = Paint.Style.FILL }
            for ((i, frac) in buckets.withIndex()) {
                if (frac > 0) {
                    val left = padL + i * bw + 1f
                    c.drawRect(left, stripBottom - frac.toFloat() * stripH, left + bw - 2f, stripBottom, lossPaint)
                }
            }
            val basePaint = Paint().apply { color = Color.parseColor("#22C55E"); strokeWidth = 3f; isAntiAlias = true }
            c.drawLine(padL, stripBottom, w - padR, stripBottom, basePaint)
            c.drawText("Loss %", 8f, stripTop + stripH / 2 + 8f, stripLabel)
            return bmp
        }
    }
}
