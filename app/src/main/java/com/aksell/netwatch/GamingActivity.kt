package com.aksell.netwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Future
// Gaming Monitor (v2.6).
// - ONE background task per test (NetExecutor): no new Thread every 5s, no
//   shared mutable lists across threads, no overlapping runs.
// - Stop really stops: a flag + Future.cancel; in-flight pings are bounded
//   (pingLines kills the process), so stopping never hangs.
// - Profile/region/host are SNAPSHOTTED at session start; changing them
//   mid-session is refused until the run stops.
// - Every sample is persisted to SQLite with its timestamp, so a session
//   survives leaving the screen and its chart is drawn from real data.
// - Bufferbloat is NEVER automatic after monitoring (it can use up to 50 MB):
//   it runs only from its own button, after a data-use warning, with progress
//   and a working cancel. Until it is measured, the gaming score is incomplete
//   (null total) — no fake 5/10 points.
//
// v2.9.9 — the gaming session loop moved to MonitorService (session-based
// background monitoring). This activity only starts/stops a session via
// Intent actions and renders state from the DB (refresh every 3s while
// visible). Leaving this screen no longer stops or clears anything: the
// session keeps pinging in the service, and reopening shows its live data
// from the DB. The stability test and Bufferbloat stay activity-bound by
// design (one-off diagnostics, not sessions).
class GamingActivity : Activity() {
    private var profile = Gaming.profiles[0]
    private var region = Gaming.regions[0]
    private var lastPing: PingResult? = null
    private var lastBb: Gaming.Bufferbloat? = null
    private var lastScore: Gaming.Score? = null
    private var durationMin = 1
    private val stabStop = AtomicBoolean(false)
    private val bbStop = AtomicBoolean(false)
    private var stabFuture: Future<*>? = null
    private var bbFuture: Future<*>? = null
    // v2.9.9: the session lives in MonitorService; this screen only renders
    // it from the DB (refresh every 3s while visible).
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshBusy = AtomicBoolean(false)
    private var uiSessionActive = false
    private var lastLiveSessionId = -1L
    private var renderedSessionId = -1L
    private var renderedSessionEnded = 0L
    private lateinit var tvMain: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvDiag: TextView
    private lateinit var tvHistory: TextView
    private lateinit var graph: GraphView
    private lateinit var tvBigPing: TextView
    private lateinit var tvBigLoss: TextView
    private lateinit var tvGChartStats: TextView
    private lateinit var tvGJitter: TextView
    private lateinit var tvGMinMax: TextView
    private lateinit var tvGBb: TextView
    private lateinit var bannerReady: LinearLayout
    private lateinit var tvReadyTitle: TextView
    private lateinit var tvReadySub: TextView
    private lateinit var tvGameTitle: TextView
    private lateinit var tvGameSub: TextView
    private lateinit var sectionMain: LinearLayout
    private lateinit var sectionHistory: LinearLayout
    private lateinit var btnGPing: Button

    private fun show(s: LinearLayout) { sectionMain.visibility = View.GONE; sectionHistory.visibility = View.GONE; s.visibility = View.VISIBLE }

    private fun setRangeChip(min: Int) {
        if (monitoringRunning()) { Toast.makeText(this, "اول مانیتورینگ را متوقف کن، بعد مدت را عوض کن.", Toast.LENGTH_SHORT).show(); return }
        durationMin = min
        val sel = R.drawable.chip_sel; val unsel = R.drawable.chip_unsel
        findViewById<Button>(R.id.btnRange1).setBackgroundResource(if (min == 1) sel else unsel)
        findViewById<Button>(R.id.btnRange5).setBackgroundResource(if (min == 5) sel else unsel)
        findViewById<Button>(R.id.btnRange15).setBackgroundResource(if (min == 15) sel else unsel)
        findViewById<Button>(R.id.btnRange30).setBackgroundResource(if (min == 30) sel else unsel)
    }

    private fun paintBigStats(avgMs: Double, lossPct: Double, jitterMs: Double, minMs: Double, maxMs: Double, bbGrade: String, score: Int?) {
        tvBigPing.text = "${"%.0f".format(avgMs)} ms"
        tvBigLoss.text = "${"%.1f".format(lossPct)}%"
        tvGJitter.text = "${"%.1f".format(jitterMs)} ms"
        tvGMinMax.text = "${"%.0f".format(minMs)} / ${"%.0f".format(maxMs)} ms"
        tvGBb.text = bbGrade
        if (score != null) {
            bannerReady.visibility = View.VISIBLE
            when {
                score >= 80 -> { tvReadyTitle.text = "Gaming Ready"; tvReadySub.text = "Connection is stable for gaming" }
                score >= 50 -> { tvReadyTitle.text = "Playable, not ideal"; tvReadySub.text = "Ping or loss is above the ideal range — see details" }
                else -> { tvReadyTitle.text = "Not ready for gaming"; tvReadySub.text = "Connection is too unstable right now" }
            }
        } else {
            bannerReady.visibility = View.GONE
        }
    }

    private fun loadLastSummary() {
        val g = Gaming.lastSummary(this)
        if (g.isBlank()) return
        val a = g.split("|")
        if (a.size > 8) {
            tvGameTitle.text = a[7]
            paintBigStats(a[1].toDoubleOrNull() ?: 0.0, a[3].toDoubleOrNull() ?: 0.0, a[2].toDoubleOrNull() ?: 0.0, 0.0, 0.0, a[5], a[6].toIntOrNull())
            tvGMinMax.text = "—"
        }
    }

    private fun gameRunning(): String {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val procs = am.runningAppProcesses ?: return "نامشخص — اندروید لیست پردازش‌های دیگر اپ‌ها را نمی‌دهد"
            val found = procs.any { it.processName == profile.pkg }
            if (found) "✅ ${profile.name} در حال اجراست" else "❌ ${profile.name} در حال اجرا نیست"
        } catch (e: Exception) { "نامشخص — ${e.message}" }
    }

    // Runs on a BACKGROUND thread only (does a gateway ping). Never call from UI.
    private fun wifiDiag(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return "Wi-Fi متصل نیست"
            val freq = info.frequency
            val band = when { freq in 2400..2500 -> "2.4GHz"; freq in 4900..5900 -> "5GHz"; freq in 5900..7200 -> "6GHz"; else -> "$freq MHz" }
            val channel = when { freq in 2400..2500 -> ((freq - 2407) / 5).toString(); freq in 4900..5900 -> ((freq - 5000) / 5).toString(); else -> "—" }
            val dhcp: DhcpInfo? = wm.dhcpInfo
            val gw = dhcp?.gateway ?: 0
            val gwIp = "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
            val gwPing = if (gw != 0) NetworkEngine.pingStats(gwIp) else null
            "Wi-Fi: ${info.ssid} • باند $band • کانال $channel\n" +
            "سیگنال: ${info.rssi} dBm • سرعت لینک: ${info.linkSpeed} Mbps\n" +
            "گیت‌وی مودم: $gwIp • پینگ گیت‌وی: ${if (gwPing?.ok == true) "${gwPing.avgMs}ms" else "ناموفق"}\n" +
            "اگر پینگ گیت‌وی پایین ولی پینگ اینترنت بالاست، مشکل از مودم نیست، از مسیر اینترنت است. اگر پینگ گیت‌وی هم بالاست، احتمال تداخل Wi-Fi یا فاصله از مودم است."
        } catch (e: Exception) { "Wi-Fi در دسترس نیست: ${e.message}" }
    }

    private fun mobileDiag(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val gen = when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                else -> "نامشخص (${tm.networkType})"
            }
            var cell = "Cell ID در دسترس نیست — اندروید بدون مجوز مکان/تلفن نمی‌دهد"
            var sig = ""
            try {
                val infos = tm.allCellInfo
                val lte = infos?.firstOrNull { it is android.telephony.CellInfoLte } as? android.telephony.CellInfoLte
                if (lte != null) {
                    cell = "Cell ID: ${lte.cellIdentity.ci} • TAC: ${lte.cellIdentity.tac}"
                    val ss = lte.cellSignalStrength
                    sig = "RSRP: ${ss.rsrp} dBm • RSRQ: ${ss.rsrq} dB • RSSI: ${ss.rssi} dBm • SINR: ${try { ss.rssnr } catch (e: Exception) { "—" }} dB"
                }
            } catch (e: Exception) { sig = "سیگنال: نیاز به مجوز مکان دارد — ${e.javaClass.simpleName}" }
            "موبایل: $gen • اپراتور: ${try { tm.networkOperatorName } catch (e: Exception) { "—" }}\n$cell\n$sig\n" +
            "نوسان سیگنال را با تست پایداری در زمان‌های مختلف مقایسه کن."
        } catch (e: Exception) { "موبایل در دسترس نیست: ${e.message}" }
    }

    // A session is running when the service owns one or this screen has
    // rendered a live one from the DB. The stability test counts too.
    private fun monitoringRunning(): Boolean = MonitorService.gamingActive || uiSessionActive || stabFuture?.isDone == false

    // One monitoring task on the shared executor: a single ping every 5s,
    // samples persisted per tick, UI updated from a snapshot. Stop sets the
    // flag and cancels the Future — the bounded ping can never hang the stop.
    // v2.9.9: start/stop toggle. The session itself lives in
    // MonitorService — this screen only sends the Intent. Starting also
    // starts the service (so the light check runs too); stopping a session
    // never stops the service.
    private fun runPingTest() {
        if (MonitorService.gamingActive || uiSessionActive) {
            val i = Intent(this, MonitorService::class.java).apply { action = MonitorService.ACTION_STOP_GAMING }
            try { startService(i) } catch (e: Exception) { Toast.makeText(this, "توقف ناموفق: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show(); return }
            btnGPing.text = "▶  شروع مانیتورینگ"
            Toast.makeText(this, "در حال توقف مانیتورینگ…", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START_GAMING
            putExtra("profile", profile.name)
            putExtra("region", region.name)
            putExtra("host", region.hosts[0])
            putExtra("durationMin", durationMin)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            btnGPing.text = "⏸  توقف مانیتورینگ"
            tvMain.text = "مانیتورینگ گیمینگ شروع شد — ${durationMin} دقیقه، هر ۵ ثانیه یک پینگ به ${region.hosts[0]}. در پس‌زمینه ادامه پیدا می‌کند؛ می‌توانی از صفحه خارج شوی و با همین دکمه یا از نوتیفیکیشن متوقفش کنی."
        } catch (e: Exception) {
            Toast.makeText(this, "شروع مانیتورینگ ناموفق: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    // v2.9.9: render state from the DB every 3s while this screen is
    // visible. A live session is read from its recorded samples; a
    // finished session is rendered from its row. Reopening this screen
    // recovers the session (condition 9) — nothing is kept in memory.
    private val uiRefresh = object : Runnable {
        override fun run() {
            refreshGamingUi()
            if (!isFinishing && !isDestroyed) uiHandler.postDelayed(this, 3000)
        }
    }

    private fun refreshGamingUi() {
        if (uiRefreshBusy.getAndSet(true)) return
        NetExecutor.submit {
            try {
                val active = HistoryEngine.activeGamingSession(this@GamingActivity)
                if (active != null) {
                    val samples = HistoryEngine.gamingSessionSamples(this@GamingActivity, active.id)
                    NetExecutor.runOnMain {
                        if (isFinishing || isDestroyed) { uiRefreshBusy.set(false); return@runOnMain }
                        renderLiveSession(active, samples)
                        uiRefreshBusy.set(false)
                    }
                } else {
                    val last = HistoryEngine.gamingSessions(this@GamingActivity).firstOrNull { it.ended > 0 }
                    val lastSamples = if (last != null) HistoryEngine.gamingSessionSamples(this@GamingActivity, last.id) else emptyList()
                    val justFinished = last != null && last.id == lastLiveSessionId
                    NetExecutor.runOnMain {
                        if (isFinishing || isDestroyed) { uiRefreshBusy.set(false); return@runOnMain }
                        if (last != null) {
                            if (last.id != renderedSessionId || last.ended != renderedSessionEnded) {
                                renderFinishedSession(last, lastSamples)
                                renderedSessionId = last.id; renderedSessionEnded = last.ended
                            }
                            btnGPing.text = "▶  شروع مانیتورینگ"
                            uiSessionActive = false
                            if (justFinished) {
                                lastLiveSessionId = -1L
                                runSessionDiagnostics()
                            }
                        } else {
                            loadLastSummary()
                            btnGPing.text = "▶  شروع مانیتورینگ"
                            uiSessionActive = false
                        }
                        uiRefreshBusy.set(false)
                    }
                }
            } catch (e: Exception) {
                NetExecutor.runOnMain { uiRefreshBusy.set(false) }
            }
        }
    }

    private fun renderLiveSession(session: History.GamingSession, samples: List<Pair<Long, Double>>) {
        uiSessionActive = true
        lastLiveSessionId = session.id
        tvGameTitle.text = session.profile
        btnGPing.text = "⏸  توقف مانیتورینگ"
        val st = Gaming.sessionStats(samples.map { it.second })
        val pts = samples.map { it.first to it.second }
        val drops = samples.count { it.second < 0.0 }
        val elMs = System.currentTimeMillis() - session.started
        val totalMs = Gaming.durationToMs(if (MonitorService.gamingActive) MonitorService.gamingDurationMin else durationMin)
        updateLiveStats(st.times, pts, drops, st.spikes, st.burst, elMs, totalMs)
        tvMain.text = "🎮 مانیتورینگ گیمینگ در پس‌زمینه فعال است — ${session.profile} • ${session.region} • هر ۵ ثانیه یک پینگ به ${session.host}. می‌توانی از صفحه خارج شوی؛ با همین دکمه یا از نوتیفیکیشن متوقفش کن."
    }

    private fun renderFinishedSession(row: History.GamingSession, samples: List<Pair<Long, Double>>) {
        uiSessionActive = false
        btnGPing.text = "▶  شروع مانیتورینگ"
        tvGameTitle.text = row.profile
        paintBigStats(row.avgMs, row.loss, row.jitterMs, row.minMs, row.maxMs, row.bbGrade, row.score)
        val st = Gaming.sessionStats(samples.map { it.second })
        // Keep lastPing/lastScore populated (match report + bufferbloat).
        val p = PingResult(row.host, row.sent, row.received, row.loss, row.minMs, row.avgMs, row.maxMs, st.p50, st.p90, st.p95, st.p99, row.jitterMs, row.spikes, row.burst, st.times)
        lastPing = p
        lastScore = AnalyticsEngine.gamingScore(p, lastBb)
        tvGChartStats.text = "Min ${"%.0f".format(row.minMs)} • Avg ${"%.0f".format(row.avgMs)} • Max ${"%.0f".format(row.maxMs)} ms"
        val durMin = ((row.ended - row.started) / 60000).coerceAtLeast(0L)
        tvStats.text = "🎮 ${row.profile} • منطقه ${row.region} • مدت ${durMin} دقیقه (بنچمارک — نه سرور واقعی بازی)\n" +
            "Live Ping: ${"%.0f".format(row.avgMs)}ms • Min ${"%.0f".format(row.minMs)} • Max ${"%.0f".format(row.maxMs)}\n" +
            "P50 ${"%.0f".format(st.p50)} • P90 ${"%.0f".format(st.p90)} • P95 ${"%.0f".format(st.p95)} • P99 ${"%.0f".format(st.p99)} ms\n" +
            "جیتر: ${"%.1f".format(row.jitterMs)}ms • قطعی: ${"%.1f".format(row.loss)}٪ • قطعی کوتاه: ${samples.count { it.second < 0.0 }} • اسپایک: ${row.spikes} • Burst Loss: ${row.burst}\n" +
            "پایداری اتصال: ${"%.0f".format(100 - row.loss)}٪ • نمونه‌های معتبر: ${st.received} • نامعتبر/Timeout: ${samples.count { it.second < 0.0 }}\n" +
            "Bufferbloat: ${if (row.bbGrade == "-") "اندازه‌گیری نشده — برای امتیاز کامل، دکمه «تست Bufferbloat» را بزن (تا ۵۰ مگابایت مصرف دارد)." else "درجه ${row.bbGrade}"}\n" +
            "⭐ Gaming Score: ${row.score?.let { "$it/100" } ?: "ناقص (Bufferbloat اندازه‌گیری نشده)"}\n" +
            recommendation(row.avgMs, row.loss)
        tvMain.text = "✅ مانیتورینگ کامل شد — ${gameRunning()}\n⚠️ سرور واقعی COD Mobile مشخص نیست (Unknown)؛ این بنچمارک عمومی است، نه پینگ داخل بازی."
        graph.points = samples
    }

    // The diagnostics the old finishMonitoring ran after a session:
    // gateway + mobile diagnostics, once, when a live session ends.
    private fun runSessionDiagnostics() {
        NetExecutor.submit {
            val diag = wifiDiag() + "\n\n" + mobileDiag()
            NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) tvDiag.text = diag }
        }
    }

    private fun updateLiveStats(times: List<Double>, pts: List<Pair<Long, Double>>, drops: Int, spikes: Int, burst: Int, elMs: Long, totalMs: Long) {
        val n = times.size
        tvGChartStats.text = if (n > 0) "Min ${"%.0f".format(times.minOrNull() ?: 0.0)} • Avg ${"%.0f".format(times.average())} • Max ${"%.0f".format(times.maxOrNull() ?: 0.0)} ms" else "Min — • Avg — • Max —"
        if (n > 0) { tvBigPing.text = "${"%.0f".format(times.average())} ms"; tvBigLoss.text = "${"%.1f".format(drops * 100.0 / (n + drops))}%" }
        tvStats.text = "⏱ ${elMs / 1000}s / ${totalMs / 1000}s • نمونه‌ها: $n • قطعی کوتاه: $drops • اسپایک: $spikes • Burst: $burst\n" +
            (if (n > 0) "میانگین تا اینجا: ${"%.0f".format(times.average())}ms • Min ${"%.0f".format(times.minOrNull() ?: 0.0)} • Max ${"%.0f".format(times.maxOrNull() ?: 0.0)}" else "هنوز نمونه‌ای نرسیده") +
            "\nاعتبار آماری: " + if (n < 10) "پایین (نمونه کمتر از ۱۰)" else if (n < 30) "متوسط" else "خوب"
        graph.points = pts
    }

    private fun recommendation(avgMs: Double, lossPct: Double): String = when {
        avgMs < 80 && lossPct < 1 -> "توصیه: برای بازی رقابتی مناسب است — همین پروفایل و منطقه را نگه دار."
        avgMs < 150 && lossPct < 3 -> "توصیه: قابل بازی ولی ایده‌آل نیست — اگر سرور منطقه نزدیک‌تری (ترکیه/خلیج فارس) دارد، آن منطقه را هم تست کن و مقایسه کن."
        else -> "توصیه: برای بازی رقابتی مناسب نیست — اول منطقه نزدیک‌تر را تست کن؛ اگر پینگ همه مناطق بالاست، مشکل از خط موبایل است نه سرور بازی."
    }


    // Bufferbloat: only on purpose, after a data-use warning, with progress
    // and a working cancel. Recomputes the gaming score once it is measured.
    private fun runBufferbloat() {
        if (bbFuture?.isDone == false) {
            bbStop.set(true)
            NetExecutor.cancel(bbFuture)
            Gaming.setBufferbloatTesting(this, false)
            Toast.makeText(this, "در حال توقف تست Bufferbloat…", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("تست Bufferbloat")
            .setMessage("این تست حدود ۲۰ تا ۴۰ ثانیه طول می‌کشد و تا ۵۰ مگابایت اینترنت مصرف می‌کند (دانلود تحت بار).\n\nروی وای‌فای یا با حجم کافی انجام بده. برای توقف، دوباره همین دکمه را بزن.\n\nادامه می‌دهی؟")
            .setPositiveButton("شروع تست") { _, _ ->
                bbStop.set(false)
                Gaming.setBufferbloatTesting(this, true)
                tvMain.text = "در حال اندازه‌گیری Bufferbloat… (برای توقف، دوباره دکمه Bufferbloat را بزن)"
                bbFuture = NetExecutor.submit {
                    val bb = AnalyticsEngine.bufferbloat(shouldStop = { bbStop.get() })
                    NetExecutor.runOnMain {
                        if (isFinishing || isDestroyed) return@runOnMain
                        lastBb = bb
                        tvGBb.text = bb.grade
                        // v2.9.6: the result is stored UNCONDITIONALLY, even
                        // when there is no last ping — the old code only
                        // saved it inside lastPing?.let, so a bufferbloat
                        // result measured on its own was lost.
                        Gaming.setBufferbloatTesting(this, false)
                        Gaming.saveBufferbloat(this, bb)
                        NetWatchWidget.refreshAll(this)
                        tvStats.text = "Bufferbloat: درجه ${bb.grade}\nIdle ${"%.0f".format(bb.idleMs)}ms • تحت بار ${"%.0f".format(bb.loadedMs)}ms • افزایش ${"%.0f".format(bb.increaseMs)}ms\n${bb.note}\nپیشنهاد: اگر درجه C یا بدتر است، در مودم QoS/SQM را فعال کن و دانلود هم‌زمان با بازی را قطع کن."
                        tvMain.text = if (bb.valid) "✅ Bufferbloat اندازه‌گیری شد." else "⚠️ Bufferbloat معتبر نیست: ${bb.note}"
                        lastPing?.let { p ->
                            val sc = AnalyticsEngine.gamingScore(p, bb)
                            lastScore = sc
                            paintBigStats(p.avgMs, p.lossPct, p.jitterMs, p.minMs, p.maxMs, bb.grade, sc.total)
                            // v2.9.9: attach the score/grade to the last
                            // FINISHED session only. finishGamingSession
                            // sets ended=now, so it must never run on a
                            // live session (that would end it early). If a
                            // session is running, the grade still reaches
                            // the widget via saveBufferbloat above.
                            val last = HistoryEngine.gamingSessions(this).firstOrNull { it.ended > 0 }
                            Gaming.saveLast(this, p, bb, sc, last?.profile ?: profile.name, last?.region ?: region.name, Geo.connectionInfo(this))
                            if (last != null) HistoryEngine.finishGamingSession(this, last.id, p.sent, p.received, p.lossPct, p.avgMs, p.minMs, p.maxMs, p.jitterMs, p.p95, p.spikes, p.burstLoss, sc.total, bb.grade)
                        }
                    }
                }
            }
            .setNegativeButton("لغو", null).show()
    }

    // v2.9.9: leaving this screen must NOT stop a gaming session — the
    // service owns it. Only the activity-bound diagnostics are cancelled.
    override fun onDestroy() {
        uiHandler.removeCallbacks(uiRefresh)
        stabStop.set(true); bbStop.set(true)
        NetExecutor.cancel(stabFuture); NetExecutor.cancel(bbFuture)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshGamingUi()
        uiHandler.postDelayed(uiRefresh, 3000)
        // v2.9.9 + auto-start (user decision 2026-11-11): entering Gaming
        // starts monitoring automatically when no session is active, with
        // the selected duration (default 1 minute). Re-entry after a manual
        // stop starts a NEW session (simple "entry = start" rule). The
        // service itself guards duplicates (START ignored while active),
        // and startGaming() first recovers any stale session from its
        // recorded samples — so a killed service's leftover row never
        // blocks a new session.
        autoStartGamingIfIdle()
    }

    private fun autoStartGamingIfIdle() {
        if (MonitorService.gamingActive) return
        if (stabFuture?.isDone == false) return // a stability test runs here
        val i = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START_GAMING
            putExtra("profile", profile.name)
            putExtra("region", region.name)
            putExtra("host", region.hosts[0])
            putExtra("durationMin", durationMin)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            btnGPing.text = "⏸  توقف مانیتورینگ"
        } catch (e: Exception) { }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiRefresh)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.ensureRecovered(this)
        setContentView(R.layout.activity_gaming)
        tvMain = findViewById(R.id.tvGMain); tvStats = findViewById(R.id.tvGStats); tvDiag = findViewById(R.id.tvGDiag); tvHistory = findViewById(R.id.tvGHistory)
        graph = findViewById(R.id.graphGaming)
        tvBigPing = findViewById(R.id.tvBigPing); tvBigLoss = findViewById(R.id.tvBigLoss)
        tvGChartStats = findViewById(R.id.tvGChartStats); tvGJitter = findViewById(R.id.tvGJitter)
        tvGMinMax = findViewById(R.id.tvGMinMax); tvGBb = findViewById(R.id.tvGBb)
        bannerReady = findViewById(R.id.bannerReady); tvReadyTitle = findViewById(R.id.tvReadyTitle); tvReadySub = findViewById(R.id.tvReadySub)
        tvGameTitle = findViewById(R.id.tvGameTitle); tvGameSub = findViewById(R.id.tvGameSub)
        tvGameTitle.text = profile.name
        btnGPing = findViewById(R.id.btnGPing)
        refreshGamingUi()
        findViewById<Button>(R.id.btnGBack).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.btnGRowWifi).setOnClickListener { findViewById<Button>(R.id.btnGDiag).performClick() }
        findViewById<LinearLayout>(R.id.btnGRowVpn).setOnClickListener { findViewById<Button>(R.id.btnGVpn).performClick() }
        sectionMain = findViewById(R.id.sectionGMain); sectionHistory = findViewById(R.id.sectionGHistory)
        tvMain.text = "پروفایل: ${profile.name}\nمنطقه: ${region.name}\n\nمانیتورینگ در پس‌زمینه ادامه پیدا می‌کند؛ می‌توانی از صفحه خارج شوی. توجه: سرور واقعی بازی Unknown است — این تست بنچمارک عمومی است."

        findViewById<Button>(R.id.btnGMenu).setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "داشبورد گیمینگ")
            popup.menu.add(0, 2, 0, "تاریخچه و گزارش مسابقه‌ها")
            popup.menu.add(0, 3, 0, "پروفایل: COD Mobile")
            popup.menu.add(0, 4, 0, "پروفایل: PUBG Mobile")
            popup.menu.add(0, 5, 0, "پروفایل: Warzone Mobile")
            popup.menu.add(0, 6, 0, "منطقه: Middle East")
            popup.menu.add(0, 7, 0, "منطقه: Europe")
            popup.menu.add(0, 8, 0, "منطقه: Asia")
            popup.menu.add(0, 9, 0, "منطقه: North America")
            popup.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    1 -> show(sectionMain)
                    2 -> {
                        val sessions = HistoryEngine.gamingSessions(this)
                        val sb = StringBuilder("جلسه‌های گیمینگ ثبت‌شده (${sessions.size}):\n")
                        for (gs in sessions.take(10)) {
                            sb.append("${History.dateTimeStr(gs.started)} — ${gs.profile} • ${gs.region} • avg ${"%.0f".format(gs.avgMs)}ms • loss ${"%.1f".format(gs.loss)}٪ • score ${gs.score ?: "—"} • Bufferbloat ${gs.bbGrade}\n")
                        }
                        val logSessions = Gaming.log(this).lines().filter { it.isNotBlank() }
                        fun avgOf(list: List<String>): String { val avgs = list.mapNotNull { Regex("avg ([0-9.]+)ms").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }; return if (avgs.isEmpty()) "—" else "%.0f".format(avgs.average()) + "ms" }
                        val byHour = logSessions.groupBy { it.substring(11, 13) }.map { (h, list) -> "ساعت $h: ${list.size} جلسه، میانگین ${avgOf(list)}" }.joinToString(" • ")
                        val byDay = logSessions.groupBy { it.substring(0, 10) }.map { (d, list) -> "$d: ${list.size} جلسه، میانگین ${avgOf(list)}" }.joinToString(" • ")
                        tvHistory.text = sb.toString() + "\nمقایسه ساعات روز: $byHour\nمقایسه روزها: $byDay\n(میانگین پینگ هر ساعت/روز از روی خط‌های بالا قابل مقایسه است — داده فقط از جلسه‌های ثبت‌شده خودت است.)" + "\n\n📈 تاریخچه کامل Ping Spikeها:\n" + History.spikes(this).ifBlank { "هنوز Spike ثبت نشده." } + "\n\nمسابقه‌ها (پینگ داخل بازی — دستی):\n" + Gaming.matches(this)
                        show(sectionHistory)
                    }
                    3, 4, 5 -> {
                        if (monitoringRunning()) { Toast.makeText(this, "اول مانیتورینگ را متوقف کن، بعد پروفایل را عوض کن.", Toast.LENGTH_SHORT).show(); return@setOnMenuItemClickListener true }
                        profile = Gaming.profiles[item.itemId - 3]; Toast.makeText(this, profile.name, Toast.LENGTH_SHORT).show()
                    }
                    6, 7, 8, 9 -> {
                        if (monitoringRunning()) { Toast.makeText(this, "اول مانیتورینگ را متوقف کن، بعد منطقه را عوض کن.", Toast.LENGTH_SHORT).show(); return@setOnMenuItemClickListener true }
                        region = Gaming.regions[item.itemId - 6]
                    }
                }
                tvMain.text = "پروفایل: ${profile.name}\nمنطقه: ${region.name}"
                tvGameTitle.text = profile.name
                true
            }
            popup.show()
        }
        btnGPing.setOnClickListener { runPingTest() }
        findViewById<Button>(R.id.btnRange1).setOnClickListener { setRangeChip(1); Toast.makeText(this, "مدت مانیتورینگ: ۱ دقیقه واقعی", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnRange5).setOnClickListener { setRangeChip(5); Toast.makeText(this, "مدت مانیتورینگ: ۵ دقیقه واقعی", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnRange15).setOnClickListener { setRangeChip(15); Toast.makeText(this, "مدت مانیتورینگ: ۱۵ دقیقه واقعی", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnRange30).setOnClickListener { setRangeChip(30); Toast.makeText(this, "مدت مانیتورینگ: ۳۰ دقیقه واقعی", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnGBuffer).setOnClickListener { runBufferbloat() }
        findViewById<Button>(R.id.btnGStability).setOnClickListener {
            if (stabFuture?.isDone == false) {
                stabStop.set(true); NetExecutor.cancel(stabFuture)
                Toast.makeText(this, "تست پایداری متوقف شد.", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            stabStop.set(false)
            val stabSession = HistoryEngine.startGamingSession(this, profile.name, region.name + " (پایداری)", "1.1.1.1")
            tvMain.text = "تست پایداری ۵ دقیقه‌ای شروع شد — هر ۵ ثانیه یک پینگ. اگر بازی در حال اجراست، تست سنگین به‌صورت خودکار سبک می‌شود."
            val start = System.currentTimeMillis()
            stabFuture = NetExecutor.submit {
                val times = mutableListOf<Double>(); val stabPts = mutableListOf<Pair<Long, Double>>(); var drops = 0
                try {
                    while (!stabStop.get() && System.currentTimeMillis() - start < 300000) {
                        val tickStart = System.currentTimeMillis()
                        val p = NetworkEngine.ping("1.1.1.1", 1)
                        if (stabStop.get()) break
                        if (p.received == 0) { drops++; stabPts.add(System.currentTimeMillis() to -1.0); HistoryEngine.addGamingSample(this@GamingActivity, stabSession, -1.0, false) }
                        else for (t in p.times) { times.add(t); stabPts.add(System.currentTimeMillis() to t); HistoryEngine.addGamingSample(this@GamingActivity, stabSession, t, true) }
                        val snap = times.toList(); val sDrops = drops; val snapPts = stabPts.toList()
                        val el = (System.currentTimeMillis() - start) / 1000
                        NetExecutor.runOnMain {
                            if (isFinishing || isDestroyed) return@runOnMain
                            tvStats.text = "تست پایداری: ${el}s / 300s • نمونه‌ها: ${snap.size} • قطعی کوتاه: $sDrops • میانگین: ${if (snap.isEmpty()) "—" else "%.0f".format(snap.average())}ms"
                            graph.points = snapPts
                        }
                        val spent = System.currentTimeMillis() - tickStart
                        if (5000L - spent > 0) {
                            try { Thread.sleep(5000L - spent) } catch (e: InterruptedException) { break }
                        }
                    }
                } catch (e: Exception) {}
                val fTimes = times.toList(); val fDrops = drops
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    if (!stabStop.get()) {
                        tvMain.text = "✅ تست پایداری ۵ دقیقه‌ای کامل شد. قطعی کوتاه: $fDrops"
                        val avg = if (fTimes.isEmpty()) 0.0 else fTimes.average()
                        HistoryEngine.finishGamingSession(this@GamingActivity, stabSession, fTimes.size + fDrops, fTimes.size, if (fTimes.isEmpty()) 100.0 else fDrops * 100.0 / (fTimes.size + fDrops), avg, fTimes.minOrNull() ?: 0.0, fTimes.maxOrNull() ?: 0.0, 0.0, 0.0, 0, 0, null, "-")
                    } else tvMain.text = "تست پایداری متوقف شد — ${fTimes.size} نمونه ثبت شد."
                }
            }
        }
        findViewById<Button>(R.id.btnGRoute).setOnClickListener {
            tvMain.text = "در حال Traceroute…"
            NetExecutor.submit {
                val tr = NetworkEngine.traceroute("8.8.8.8"); val tcp = NetworkEngine.tcp("8.8.8.8", 443)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) { tvDiag.text = "ICMP Traceroute تا 8.8.8.8:\n$tr\nTCP :443 به 8.8.8.8: ${if (tcp.ok) "${tcp.ms}ms" else "ناموفق (${tcp.note})"}\nتوجه: پاسخ ندادن یک Hop به ICMP لزوماً Packet Loss واقعی نیست؛ خیلی از روترها ICMP را جواب نمی‌دهند."; tvMain.text = "✅ مسیر بررسی شد." } }
            }
        }
        findViewById<Button>(R.id.btnGVpn).setOnClickListener {
            val vpn = Geo.vpnOn(this)
            NetExecutor.submit {
                val p = NetworkEngine.ping("1.1.1.1", 10)
                val snap = "VPN ${if (vpn) "روشن" else "خاموش"} • پینگ ${"%.0f".format(p.avgMs)}ms • جیتر ${"%.1f".format(p.jitterMs)} • قطعی ${"%.1f".format(p.lossPct)}٪ • لوکیشن: ${Geo.fetch().let { "${it.city}, ${it.country}" }}"
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    if (vpnSnapA == null) { vpnSnapA = snap; tvMain.text = "اسنپ‌شات اول ذخیره شد:\n$snap\n\nحالا VPN را عوض کن (روشن/خاموش) و دوباره همین دکمه را بزن تا مقایسه شود." }
                    else { tvMain.text = "مقایسه VPN:\nقبل: $vpnSnapA\nالان: $snap"; vpnSnapA = null }
                }
            }
        }
        findViewById<Button>(R.id.btnGMatch).setOnClickListener {
            val p = lastPing
            val inGame = "— (داخل بازی ببین و اینجا ثبت کن)"
            Gaming.saveMatch(this, profile.name, "Multiplayer", inGame, "بنچمارک قبل از مسابقه: ${if (p == null) "—" else "%.0f".format(p.avgMs) + "ms"}")
            Toast.makeText(this, "گزارش مسابقه ذخیره شد. پینگ داخل بازی را از صفحه‌ی بازی بخوان و در تاریخچه یادداشت کن — اپ بدون VPNService به ترافیک بازی دسترسی ندارد.", Toast.LENGTH_LONG).show()
            tvHistory.text = "تست‌ها:\n" + Gaming.log(this) + "\n\nمسابقه‌ها:\n" + Gaming.matches(this)
        }
        findViewById<Button>(R.id.btnGChart).setOnClickListener {
            try {
                val bmp = android.graphics.Bitmap.createBitmap(graph.width.coerceAtLeast(100), graph.height.coerceAtLeast(100), android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                graph.draw(canvas)
                val f = File(getExternalFilesDir(null), "netwatch-gaming-chart.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(this, "نمودار ذخیره شد: ${f.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Toast.makeText(this, "خروجی نمودار ناموفق: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
        }
        findViewById<Button>(R.id.btnGExport).setOnClickListener {
            val f = File(getExternalFilesDir(null), "netwatch-gaming.csv")
            f.writeText("time,profile,region,avg_ms,jitter_ms,loss_pct,p95,score\n" + Gaming.log(this).replace("\n", ";") + "\n")
            val fj = File(getExternalFilesDir(null), "netwatch-gaming.json")
            fj.writeText("{\"log\":\"" + Gaming.log(this).replace("\"", "'").replace("\n", "\\n") + "\",\"matches\":\"" + Gaming.matches(this).replace("\"", "'").replace("\n", "\\n") + "\"}")
            Toast.makeText(this, "خروجی ذخیره شد: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnGDiag).setOnClickListener {
            tvMain.text = "در حال بررسی تشخیص‌ها…"
            NetExecutor.submit {
                val cap = Advanced.captivePortal().result
                val ip = Advanced.publicIpChange(this)
                val micro = Advanced.microLag()
                // NO bufferbloat here: Advanced.loadedJitter() runs a download
                // test (up to 50 MB) — it must only run from its own button,
                // after the data-use warning.
                val loaded = "Bufferbloat: تست جداگانه با دکمه Bufferbloat (تا ۵۰ مگابایت مصرف دارد)"
                val ports = Advanced.portTest("8.8.8.8", 443) + " • " + Advanced.portTest("1.1.1.1", 53)
                val diag = wifiDiag() + "\n\n" + mobileDiag() + "\n\n" + gameRunning() + "\n\n" + cap + "\n" + ip + "\n" + micro + "\n" + loaded + "\n" + ports + "\n" + Advanced.natType() + "\n" + Advanced.reordering() + "\n\nمحدودیت: اندروید اجازه‌ی دیدن آی‌پی/پورت مقصدهای بازی را بدون VPNService نمی‌دهد؛ VPNService فقط با رضایت صریح تو فعال می‌شود و در این نسخه فعال نیست، پس مقصد بازی Unknown می‌ماند."
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) { tvDiag.text = diag; tvMain.text = "✅ تشخیص‌ها کامل شد." } }
            }
        }
        // v2.9.6: the widget's bufferbloat tile opens this screen with
        // start_bufferbloat=true — run the test (the data-use warning
        // dialog still shows first). Only on a fresh launch, not on
        // rotation (the extra survives recreation).
        if (savedInstanceState == null && intent?.getBooleanExtra("start_bufferbloat", false) == true) {
            runBufferbloat()
        }
        show(sectionMain)
    }

    private var vpnSnapA: String? = null
}
