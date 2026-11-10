package com.aksell.netwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvProbes: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvChartRange: TextView
    private lateinit var recentContainer: LinearLayout
    private lateinit var timelineContainer: LinearLayout
    private lateinit var tvNetSummary: TextView
    private lateinit var tvDetailsSummary: TextView
    private lateinit var netTechContainer: LinearLayout
    private lateinit var btnNetTechToggle: Button
    private lateinit var tvAlertVal: TextView
    private lateinit var tvAbout: TextView
    private lateinit var tvIntl: TextView
    private lateinit var tvIsp: TextView
    private lateinit var tvFilter: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvNet: TextView
    private lateinit var graph: GraphView
    private lateinit var sectionHome: LinearLayout
    private lateinit var sectionHistory: LinearLayout
    private lateinit var sectionNetwork: LinearLayout
    private lateinit var sectionGaming: LinearLayout
    private lateinit var sectionSettings: LinearLayout
    private lateinit var tvGamingSum: TextView
    private lateinit var btnBg: Button
    // Dashboard v2.3 (UI only — no logic change)
    private lateinit var gauge: GaugeView
    private lateinit var sparkPing: SparklineView
    private lateinit var sparkLoss: SparklineView
    private lateinit var tvStatPing: TextView
    private lateinit var tvStatPingSub: TextView
    private lateinit var tvStatLoss: TextView
    private lateinit var tvStatLossSub: TextView
    private lateinit var tvIpLocal: TextView
    private lateinit var tvIpPublic: TextView
    private lateinit var tvDns: TextView
    private lateinit var tvGateway: TextView
    private lateinit var graphHistory: GraphView
        private lateinit var tvAutoTestVal: TextView
    private lateinit var tvStatusNet: TextView
    private lateinit var tvChartStats: TextView
    private lateinit var tvGuidance: TextView
    private lateinit var detailsContainer: LinearLayout
    private lateinit var btnDetailsToggle: Button
    private lateinit var tvScoreValue: TextView
    private lateinit var tvScoreBadge: TextView
    private lateinit var tvScoreCoverage: TextView
    private lateinit var tvScoreParts: TextView
    private lateinit var scoreBar: android.widget.ProgressBar
    private lateinit var dotStatus: View
    private lateinit var navHome: Button
    private lateinit var navGaming: Button
    private lateinit var navHistory: Button
    private lateinit var navLab: Button
    private lateinit var navSettings: Button
    private var lastPublicIp = "—"
    private var auto = true
    private var secondsLeft = 30
    // No local `checking` flag any more (v2.8): the single shared guard
    // lives in MonitoringEngine/NetworkEngine.submitSingle, so a manual check
    // and a background cycle can never run at the same time.
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (auto && !MonitoringEngine.isCheckRunning()) {
                secondsLeft--
                val iv = History.intervalSec(this@MainActivity)
                tvCount.text = "بررسی بعدی تا $secondsLeft ثانیه • هر $iv ثانیه (قابل تنظیم از منو)"
                if (secondsLeft <= 0) { lightCheck(); secondsLeft = iv }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun show(s: LinearLayout) {
        sectionHome.visibility = View.GONE; sectionHistory.visibility = View.GONE; sectionNetwork.visibility = View.GONE; sectionGaming.visibility = View.GONE; sectionSettings.visibility = View.GONE
        s.visibility = View.VISIBLE
        // Bottom nav selected state (UI only). Network is a sub-screen of Home;
        // Gaming and Lab open their own activities, so they never show as selected here.
        val sel = Color.parseColor("#2DD4BF"); val unsel = Color.parseColor("#94A3B8")
        val selBg = Color.parseColor("#16233A"); val unselBg = Color.parseColor("#0C1424")
        val homeSel = s == sectionHome || s == sectionNetwork
        navHome.setTextColor(if (homeSel) sel else unsel); navHome.setBackgroundColor(if (homeSel) selBg else unselBg)
        navGaming.setTextColor(if (s == sectionGaming) sel else unsel); navGaming.setBackgroundColor(if (s == sectionGaming) selBg else unselBg)
        navHistory.setTextColor(if (s == sectionHistory) sel else unsel); navHistory.setBackgroundColor(if (s == sectionHistory) selBg else unselBg)
        navLab.setTextColor(unsel); navLab.setBackgroundColor(unselBg)
        navSettings.setTextColor(if (s == sectionSettings) sel else unsel); navSettings.setBackgroundColor(if (s == sectionSettings) selBg else unselBg)
        val last = Gaming.lastSummary(this)
        if (last.isNotBlank()) { val a = last.split("|"); if (a.size > 8) tvGamingSum.text = "🎮 ${a[7]} • ${a[8]}\nپینگ ${a[1]}ms • جیتر ${a[2]}ms • قطعی ${a[3]}٪ • P95 ${a[4]}ms • Bufferbloat ${a[5]} • امتیاز ${if (a[6].isBlank()) "—" else "${a[6]}/100"}" }
    }

    private fun timeAgo(ts: Long): String {
        val m = ((System.currentTimeMillis() - ts) / 60000).toInt()
        return when {
            m < 1 -> "همین حالا"
            m < 60 -> "$m دقیقه پیش"
            m < 1440 -> "${m / 60} ساعت پیش"
            else -> "${m / 1440} روز پیش"
        }
    }

    // Why is the score low? Name the weakest parts (below 60% of their max);
    // for Stability, count the outages recorded in the timeline.
    private fun scoreWhy(q: Quality.ScoreResult.Complete, pingAvg: Double): String {
        val weak = mutableListOf<String>()
        q.parts.ping?.let { if (it < 24) weak.add("Ping $it/40 (میانگین ${"%.0f".format(pingAvg)}ms)") }
        q.parts.jitter?.let { if (it < 12) weak.add("Jitter $it/20") }
        q.parts.loss?.let { if (it < 15) weak.add("Loss $it/25") }
        q.parts.stability?.let {
            if (it < 9) {
                val outages = History.timeline(this).lines().count { l -> l.contains("outage-start") }
                weak.add("Stability $it/15" + if (outages > 0) " ($outages قطعی در تاریخچه ثبت‌شده)" else "")
            }
        }
        return if (weak.isEmpty()) "همه بخش‌ها خوب‌اند." else weak.joinToString(" • ")
    }

    private fun refreshHistory(range: History.HistoryRange) {
        // Chips (UI only)
        val selBg = R.drawable.chip_sel; val unselBg = R.drawable.chip_unsel
        findViewById<Button>(R.id.btnH24).setBackgroundResource(if (range == History.HistoryRange.H24) selBg else unselBg)
        findViewById<Button>(R.id.btnH7).setBackgroundResource(if (range == History.HistoryRange.D7) selBg else unselBg)
        findViewById<Button>(R.id.btnH30).setBackgroundResource(if (range == History.HistoryRange.D30) selBg else unselBg)
        // v2.8 fix: the cutoff comes from the range itself (milliseconds).
        // The old code did `range * 3600000` with mixed units, so "7 days"
        // showed only 7 hours and "30 days" only 30 hours. Ping chart,
        // health-% chart, cards and Min/Avg/Max below all use this one cutoff.
        val cutoff = range.cutoff()
        // Both chart series are filtered to the selected range — the old code
        // plotted the unfiltered ping history while the cards were filtered.
        val pts = History.pingPoints(this, cutoff)
        val full = History.samplesInRange(this, cutoff)
        graphHistory.points = pts
        graphHistory.pctPoints = full.map { it.time to (if (it.total == 0) 0.0 else it.ok * 100.0 / it.total) }
        val st = ChartData.stats(pts)
        tvChartRange.text = if (pts.isEmpty() && full.isEmpty()) "نمودار: هنوز داده‌ای در این بازه نیست."
            else "نمودار ${full.size} بررسی • ${st.valid} نقطه پینگ (${st.failed} ناموفق) • Min ${"%.0f".format(st.min)} • Avg ${"%.0f".format(st.avg)} • Max ${"%.0f".format(st.max)}ms — محور افقی زمان واقعی، محور عمودی میلی‌ثانیه (ms) است."
        // Recent tests as cards: 3 main numbers each, details on tap.
        recentContainer.removeAllViews()
        val recent = full.takeLast(5).reversed()
        if (recent.isEmpty()) {
            val t = TextView(this); t.text = "هنوز تستی در این بازه ثبت نشده."; t.setTextColor(Color.parseColor("#CBD5E1")); t.textSize = 13f
            recentContainer.addView(t)
        } else for (smp in recent) {
            val pct = if (smp.total == 0) 0 else smp.ok * 100 / smp.total
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(28, 20, 28, 20)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = 12; layoutParams = lp
                isClickable = true; isFocusable = true
            }
            val l1 = TextView(this).apply { text = "${timeAgo(smp.time)} — ${smp.ok} از ${smp.total} منبع سالم ($pct٪)"; setTextColor(Color.parseColor("#F1F5F9")); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }
            val l2 = TextView(this).apply {
                text = if (smp.avgMs < 0) "Ping — • Jitter — • Loss — (داده قدیمی — پینگ ثبت نشده)"
                    else "Ping ${"%.0f".format(smp.avgMs)} ms • Jitter ${"%.1f".format(smp.jitterMs)} ms • Loss ${"%.1f".format(smp.lossPct)}٪"
                setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f
            }
            card.addView(l1); card.addView(l2)
            card.setOnClickListener {
                val detail = "زمان دقیق: ${History.timeStr(smp.time)}\nمنبع‌های سالم: ${smp.ok} از ${smp.total} ($pct٪)\n" +
                    if (smp.avgMs < 0) "Ping/Jitter/Loss: ثبت نشده (تست قدیمی، قبل از نسخه ۲٫۴)"
                    else "Ping: ${"%.0f".format(smp.avgMs)} ms\nJitter: ${"%.1f".format(smp.jitterMs)} ms\nLoss: ${"%.1f".format(smp.lossPct)}٪"
                android.app.AlertDialog.Builder(this).setTitle("جزئیات تست").setMessage(detail).setPositiveButton("بستن", null).show()
            }
            recentContainer.addView(card)
        }
        // Stability Timeline as readable rows — one event per row, wrapped text,
        // time in bold and coloured by kind (no more overlapping dump).
        timelineContainer.removeAllViews()
        if (range == History.HistoryRange.H24) {
            val events = History.parseTimeline(History.timeline(this))
            if (events.isEmpty()) {
                val t = TextView(this); t.text = "📈 Stability Timeline: هنوز رویدادی ثبت نشده."; t.setTextColor(Color.parseColor("#CBD5E1")); t.textSize = 13f
                timelineContainer.addView(t)
            } else for (e in events) {
                val color = when (e.kind) { "outage-start" -> "#EF4444"; "outage-end" -> "#22C55E"; "network-change" -> "#2DD4BF"; else -> "#F59E0B" }
                val label = when (e.kind) { "outage-start" -> "شروع اختلال"; "outage-end" -> "پایان اختلال"; "network-change" -> "تغییر شبکه"; else -> e.kind }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 10; layoutParams = lp
                }
                val time = TextView(this).apply { text = e.time; setTextColor(Color.parseColor(color)); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); val p2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); p2.marginEnd = 16; layoutParams = p2 }
                val body = TextView(this).apply { text = "[$label] ${e.text}"; setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                row.addView(time); row.addView(body)
                timelineContainer.addView(row)
            }
            tvHistory.text = History.compareText(this) + "\n\n" + History.log(this)
        } else {
            tvHistory.text = (if (range == History.HistoryRange.D7) "۷ روز: داده ساعتی ${History.hourly(this).split(";").filter { it.isNotBlank() }.size} نمونه — برای ۷ روز کامل باید اپ یک هفته کار کند.\n\n" else "۳۰ روز: داده ساعتی ${History.hourly(this).split(";").filter { it.isNotBlank() }.size} نمونه — برای ۳۰ روز کامل باید اپ یک ماه کار کند.\n\n") + History.log(this)
        }
        show(sectionHistory)
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { Toast.makeText(this, "نمی‌توانم باز کنم.", Toast.LENGTH_SHORT).show() }
    }

    // Full check (manual button only): 40+ probes + filtering detection.
    // Everything network-related runs on the shared executor. The UI block
    // only assigns precomputed values — no ping, no TLS, no gateway check on
    // the main thread (the old code ran the gateway ping and quicStatus inside
    // runOnUiThread). The shared guard (MonitoringEngine/NetworkEngine)
    // is released by submitSingle itself on completion or cancellation, so
    // a failure can never leave the app stuck in "checking" forever.
    private fun doCheck() {
        // A tap while a check runs cancels it — but v2.9 no longer restarts
        // instantly: the cancelled check keeps its key until it REALLY
        // finishes, so runCheck() below is refused while it winds down
        // (a few seconds at most — every check step has a bounded timeout).
        // The user taps again and it starts. This is the agreed behaviour:
        // a cancel must never let two checks overlap.
        if (MonitoringEngine.isCheckRunning()) MonitoringEngine.cancelCheck()
        tvStatus.text = "در حال بررسی…"
        val f = MonitoringEngine.runCheck {
            try {
                val results = NetworkEngine.runProbes(false)
                val dohCf = NetworkEngine.dohIps("https://cloudflare-dns.com/dns-query", "telegram.org")
                val dohGg = NetworkEngine.dohIps("https://dns.google/resolve", "telegram.org")
                val dohQ9 = NetworkEngine.dohIps("https://dns.quad9.net/dns-query", "telegram.org")
                val udpDns = NetworkEngine.dns("telegram.org")
                val dnsMismatch = dohCf.isNotEmpty() && dohGg.isNotEmpty() && dohCf.toSet() != dohGg.toSet()
                val tlsTg = NetworkEngine.tls("telegram.org")
                val tlsIg = NetworkEngine.tls("www.instagram.com")
                val tcpTg = NetworkEngine.tcp("telegram.org", 443)
                val filtering = StringBuilder()
                if (dnsMismatch) filtering.append("DNS Poisoning/Hijacking مشکوک: پاسخ DoH کلادفلر $dohCf با گوگل $dohGg متفاوت است. ")
                if (dohCf.isEmpty() && dohGg.isEmpty() && !udpDns.ok) filtering.append("DNS: هیچ DNSای جواب نداد. ")
                if (tcpTg.ok && !tlsTg.ok) filtering.append("SNI/TLS مشکوک برای تلگرام: TCP وصل شد ولی TLS شکست خورد (${tlsTg.note}). ")
                if (tcpTg.ok && !tlsIg.ok) filtering.append("SNI/TLS مشکوک برای اینستاگرام: TCP وصل شد ولی TLS شکست خورد. ")
                if (!tcpTg.ok && tlsTg.note.contains("Reset")) filtering.append("TCP Reset مشکوک برای تلگرام. ")
                val summary = NetworkEngine.summarize(results, filtering.toString())
                val ok = results.count { it.ok }
                val pingCf = NetworkEngine.pingStats("1.1.1.1")
                val pingGg = NetworkEngine.pingStats("8.8.8.8")
                val dnsT = NetworkEngine.dns("google.com")
                val tcpT = NetworkEngine.tcp("www.google.com", 443)
                val tlsT = NetworkEngine.tls("www.google.com")
                val udpCf = NetworkEngine.udpDns("1.1.1.1")
                val quic = NetworkEngine.quic("1.1.1.1")
                val mtu = NetworkEngine.mtuTest()
                val trace = NetworkEngine.traceroute()
                val geo = Geo.fetch()
                val changeLine = Quality.detectChange(this)
                val multi = Quality.multiHost()
                val vpn = Geo.vpnOn(this)
                val connInfo = Geo.connectionInfo(this)
                val gwIp = NetworkInfo.gateway(this)
                val gwOk = if (gwIp == "—") true else try { NetworkEngine.pingStats(gwIp).ok } catch (e: Exception) { true }
                val cls = Quality.classifyDisruption(results, filtering.toString(), gwOk, udpDns.ok, summary.intlPct, summary.domPct)
                val custom = History.customTarget(this)
                var customLine = ""
                if (custom.isNotBlank()) {
                    val t = NetworkEngine.tls(custom)
                    customLine = "\nمقصد اختصاصی $custom: TLS ${if (t.ok) "موفق ${t.ms}ms" else "ناموفق (${t.note})"}"
                }
                val pingValid = pingCf.completed && pingCf.avgMs > 0
                val jitterValid = pingCf.completed && pingCf.lossPct <= 50
                val lossValid = pingCf.completed
                val stabValid = results.isNotEmpty()
                val q = AnalyticsEngine.qualityScore(if (pingCf.ok) pingCf.avgMs else 0.0, if (pingCf.ok) pingCf.jitterMs else 0.0, if (pingCf.ok) pingCf.lossPct.toDouble() else 100.0, ok * 100 / results.size, pingValid = pingValid, jitterValid = jitterValid, lossValid = lossValid, stabValid = stabValid)
                val scoreTotal = when (q) { is Quality.ScoreResult.Complete -> q.total; else -> null }
                val scoreStatus = when (q) { is Quality.ScoreResult.Complete -> "Complete"; is Quality.ScoreResult.InsufficientData -> "InsufficientData"; Quality.ScoreResult.NotAvailable -> "NotAvailable" }
                // History writes happen here (background), never in the UI block.
                HistoryEngine.recordPing(this, pingCf)
                val pingHist = History.pingHistory(this)
                val lossHist = History.lossHistory(this)
                HistoryEngine.recordCheck(this, ok, results.size, if (pingCf.ok) pingCf.avgMs else -1.0, if (pingCf.ok) pingCf.jitterMs else -1.0, if (pingCf.ok) pingCf.lossPct.toDouble() else -1.0, summary.intlPct, summary.domPct, scoreTotal, scoreStatus, "full")
                History.saveConnType(this, Geo.connectionType(this))
                History.addLog(this, History.timeStr(System.currentTimeMillis()) + " — ${summary.title} ($ok/${results.size}) • بین‌الملل ${summary.intlPct}٪ • داخلی ${summary.domPct}٪ • اطمینان ${summary.confidence}٪")
                val guidanceBase = when (q) {
                    is Quality.ScoreResult.Complete -> when {
                        q.total >= 80 -> "اتصال سالم است."
                        q.total >= 50 -> "کیفیت متوسط است — پینگ یا قطعی بالاتر از حد مطلوب است."
                        else -> "کیفیت پایین است — علت را در جزئیات بررسی ببین."
                    }
                    is Quality.ScoreResult.InsufficientData -> "داده ناکافی است — پوشش فقط ${"%.0f".format(q.coverage * 100)}٪ است، پس عدد کیفیت نمایش داده نمی‌شود."
                    Quality.ScoreResult.NotAvailable -> "هنوز داده‌ای برای ارزیابی کیفیت نیست."
                }
                val why = if (q is Quality.ScoreResult.Complete) "\nچرا این امتیاز؟ " + scoreWhy(q, if (pingCf.ok) pingCf.avgMs else 0.0) else ""
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    paintDashboard(results, ok, pingCf, summary, q, pingHist, lossHist, guidanceBase + why)
                    tvIpLocal.text = NetworkInfo.localIp(this)
                    lastPublicIp = if (geo.ok) geo.ip else "—"
                    tvIpPublic.text = lastPublicIp
                    tvDns.text = NetworkInfo.dns(this)
                    tvGateway.text = NetworkInfo.gateway(this)
                    val scoreText = scoreTextFor(q)
                    tvIntl.text = "$scoreText\n${Quality.formulaText()}\n\n" +
                        "خلاصه: ${summary.sub}\nعلت احتمالی: ${summary.cause}\n\n" +
                        "🔍 تشخیص نوع اختلال: ${cls.type} (اطمینان ${cls.confidence}٪)\nشواهد: ${cls.evidence}\nشواهد واقعی تست‌ها: DoH کلادفلر ${if (dohCf.isNotEmpty()) "✅ $dohCf" else "❌"} • DoH گوگل ${if (dohGg.isNotEmpty()) "✅ $dohGg" else "❌"} • TCP تلگرام ${if (tcpTg.ok) "✅ ${tcpTg.ms}ms" else "❌ ${tcpTg.note}"} • TLS تلگرام ${if (tlsTg.ok) "✅" else "❌ ${tlsTg.note}"} • TLS اینستاگرام ${if (tlsIg.ok) "✅" else "❌"} • گیت‌وی ${if (gwOk) "✅" else "❌"} • داخلی ${summary.domPct}٪ • بین‌الملل ${summary.intlPct}٪\n\n" +
                        "Multi-Host (ICMP + TCP + DNS برای هر مقصد): موفق ${multi.results.count { it.ok }}/5 • ناموفق ${multi.results.count { !it.ok }}/5\n${multi.verdict}\nتوجه: شکست هر سه روش برای «یک» مقصد به‌تنهایی به معنی قطعی اینترنت نیست — فقط وقتی همه مقصدها با هر سه روش خراب‌اند، قطعی اینترنت محتمل است.\n" + multi.results.joinToString("\n") { "${it.host}: ICMP ${if (it.icmp) "✅" else "❌"} • TCP ${if (it.tcp) "✅" else "❌"} • DNS ${when (it.dns) { true -> "✅"; false -> "❌"; null -> "N/A" }} — ${if (it.ok) "سالم" else "خراب"}" } + "\n${multi.perfNote}\n$changeLine\n\n" +
                        "بین‌الملل: ${summary.intlPct}٪ سالم\nداخلی (ایران): ${summary.domPct}٪ سالم\n" + if (summary.domPct >= 80 && summary.intlPct < 40) "⚠️ شبکه‌ی داخلی فعال است ولی اینترنت جهانی قطع است." else "تفکیک داخلی/خارجی: داخلی از آپارات، دیجی‌کالا، دیوار، تلوبیون و ایرنا سنجیده می‌شود."
                    tvIsp.text = "اتصال: $connInfo\n" + NetworkInfo.summary(this) + "\n" +
                        if (geo.ok) "آی‌پی فعلی: ${geo.ip}\nلوکیشن آی‌پی: ${geo.city}، ${geo.country} (${geo.countryCode})\nاپراتور/ISP آی‌پی: ${geo.isp}\n" + if (vpn) "✅ VPN روشن است — لوکیشن بالا، لوکیشن خروجی VPN است، نه لوکیشن واقعی تو." else "VPN خاموش است — لوکیشن بالا، لوکیشن واقعی اتصال توست."
                        else "لوکیشن آی‌پی در دسترس نبود (ip-api و ipapi.co جواب ندادند)."
                    tvFilter.text = (if (filtering.isEmpty()) "فیلترینگ مشخصی با چند تست مستقل دیده نشد." else filtering.toString()) +
                        "\nمیزان اطمینان: ${summary.confidence}٪ — این نتیجه فقط از یک سایت گرفته نشده؛ DNS از ۳ منبع DoH مقایسه شد و TCP با TLS جداگانه تست شد." + customLine
                    tvLatency.text = "پینگ 1.1.1.1: ${if (pingCf.ok) "${pingCf.avgMs}ms (min ${pingCf.minMs} / max ${pingCf.maxMs})، جیتر ${pingCf.jitterMs}ms، قطعی ${pingCf.lossPct}٪" else "ناموفق"}\n" +
                        "پینگ 8.8.8.8: ${if (pingGg.ok) "${pingGg.avgMs}ms، جیتر ${pingGg.jitterMs}ms، قطعی ${pingGg.lossPct}٪" else "ناموفق"}\n" +
                        "زمان DNS: ${dnsT.ms}ms ${if (dnsT.ok) "(${dnsT.note})" else "ناموفق (${dnsT.note})"}\n" +
                        "زمان TCP گوگل: ${tcpT.ms}ms • زمان TLS گوگل: ${tlsT.ms}ms ${if (!tlsT.ok) "(${tlsT.note})" else ""}\n" +
                        "UDP DNS کلادفلر: ${if (udpCf.ok) "${udpCf.ms}ms" else "ناموفق"}\n" +
                        "MTU: $mtu"
                    tvNet.text = NetworkInfo.summary(this) + "\n\n" + "DNS: DoH کلادفلر/گوگل/کواد۹، DoT :853 (TLS واقعی)، UDP :53 — پاسخ تلگرام از DoH: کلادفلر $dohCf ، گوگل $dohGg ، کواد۹ $dohQ9\n" +
                        "IPv4: ${if (results.find { it.id == "ipv4" }?.ok == true) "سالم" else "مشکل"} • IPv6: ${if (results.find { it.id == "ipv6" }?.ok == true) "سالم" else "در دسترس نیست/مشکل"}\n" +
                        "QUIC/UDP 443: ${quic.state} — ${quic.reason}\n" +
                        "Traceroute تا 8.8.8.8:\n$trace"
                    tvNetSummary.text = "خلاصه شبکه: تلگرام ${if (tcpTg.ok && tlsTg.ok) "در دسترس است" else "در دسترس نیست"} • اینستاگرام ${if (tlsIg.ok) "در دسترس است" else "در دسترس نیست"} • DNS ${if (udpDns.ok || dohCf.isNotEmpty()) "سالم است" else "مشکل دارد"} • Multi-Host ${multi.results.count { it.ok }}/5 مقصد سالم • پینگ ۱٫۱٫۱٫۱ ${if (pingCf.ok) "${"%.0f".format(pingCf.avgMs)}ms" else "ناموفق"}"
                    tvDetailsSummary.text = "خلاصه بررسی: ${summary.title} — ${summary.sub} • امتیاز کیفیت ${when (q) { is Quality.ScoreResult.Complete -> "${q.total}/100"; else -> "—" }} • تشخیص: ${cls.type} (اطمینان ${cls.confidence}٪)"
                    val sb = StringBuilder()
                    for (r in results) {
                        sb.append(if (r.ok) "✅ " else "❌ ").append(r.name).append(" • ").append(r.detail)
                        if (r.ok) sb.append(" • ").append(r.ms).append("ms")
                        if (r.note.isNotBlank() && r.note != "کد 200") sb.append(" • ").append(r.note)
                        sb.append("\n")
                    }
                    tvProbes.text = sb.toString()
                    tvHistory.text = History.log(this)
                    NetWatchWidget.refreshAll(this)
                }
            } catch (e: Exception) {
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) { tvStatus.text = "خطا در بررسی"; tvStatusSub.text = e.javaClass.simpleName } }
            }
            // No finally for the guard: submitSingle removes the registry
            // entry itself when the task finishes or is cancelled.
        }
        if (f == null) {
            // Either a check is running, or the previous one was just
            // cancelled and is still winding down (it still owns the key).
            tvStatus.text = "در حال توقف بررسی قبلی…"
            Toast.makeText(this, "بررسی قبلی در حال توقف است — لحظه‌ای دیگر دوباره بزن", Toast.LENGTH_SHORT).show()
            return
        }
    }

    // Light auto-check (timer + background service cadence): 8 probes + one
    // ping. The full 40+ probe run is manual-only — running it every 30s in
    // the background was wasteful battery and data.
    private fun lightCheck() {
        // The timer skips this tick when any check is running — no duplicate
        // measurement, no queueing. The guard is shared with doCheck and the
        // background service, so at most one check runs in the whole app.
        val f = MonitoringEngine.runCheck {
            try {
                val results = NetworkEngine.runProbes(true)
                val ok = results.count { it.ok }
                val summary = NetworkEngine.summarize(results, "")
                val pingCf = NetworkEngine.pingStats("1.1.1.1")
                val q = AnalyticsEngine.qualityScore(if (pingCf.ok) pingCf.avgMs else 0.0, if (pingCf.ok) pingCf.jitterMs else 0.0, if (pingCf.ok) pingCf.lossPct.toDouble() else 100.0, if (results.isEmpty()) 0 else ok * 100 / results.size,
                    pingValid = pingCf.completed && pingCf.avgMs > 0,
                    jitterValid = pingCf.completed && pingCf.lossPct <= 50,
                    lossValid = pingCf.completed, stabValid = results.isNotEmpty())
                HistoryEngine.recordPing(this, pingCf)
                val pingHist = History.pingHistory(this)
                val lossHist = History.lossHistory(this)
                val scoreTotal = when (q) { is Quality.ScoreResult.Complete -> q.total; else -> null }
                val scoreStatus = when (q) { is Quality.ScoreResult.Complete -> "Complete"; is Quality.ScoreResult.InsufficientData -> "InsufficientData"; Quality.ScoreResult.NotAvailable -> "NotAvailable" }
                HistoryEngine.recordCheck(this, ok, results.size, if (pingCf.ok) pingCf.avgMs else -1.0, if (pingCf.ok) pingCf.jitterMs else -1.0, if (pingCf.ok) pingCf.lossPct.toDouble() else -1.0, summary.intlPct, summary.domPct, scoreTotal, scoreStatus, "background")
                History.saveConnType(this, Geo.connectionType(this))
                val guidanceBase = when (q) {
                    is Quality.ScoreResult.Complete -> if (q.total >= 80) "اتصال سالم است." else if (q.total >= 50) "کیفیت متوسط است." else "کیفیت پایین است."
                    is Quality.ScoreResult.InsufficientData -> "داده ناکافی است."
                    Quality.ScoreResult.NotAvailable -> "هنوز داده‌ای برای ارزیابی کیفیت نیست."
                }
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    paintDashboard(results, ok, pingCf, summary, q, pingHist, lossHist, guidanceBase)
                    NetWatchWidget.refreshAll(this)
                }
            } catch (e: Exception) {
                // A failed light check must not break the timer loop.
            }
            // No finally for the guard (see doCheck).
        }
        if (f == null) return // a check is already running — skip this tick
    }

    private fun scoreTextFor(q: Quality.ScoreResult): String {
        fun comp(v: Int?, w: Int) = if (v == null) "N/A" else "$v/$w"
        return when (q) {
            is Quality.ScoreResult.Complete -> "⭐ Network Quality Score: ${q.total}/100 (Complete — پوشش داده ${"%.0f".format(q.coverage * 100)}٪) — Ping ${comp(q.parts.ping, 40)} • Jitter ${comp(q.parts.jitter, 20)} • Loss ${comp(q.parts.loss, 25)} • Stability ${comp(q.parts.stability, 15)}"
            is Quality.ScoreResult.InsufficientData -> "⭐ Network Quality Score: N/A (Insufficient Data — پوشش داده فقط ${"%.0f".format(q.coverage * 100)}٪، کمتر از ۷۰٪) — عدد کل نمایش داده نمی‌شود. Ping ${comp(q.parts.ping, 40)} • Jitter ${comp(q.parts.jitter, 20)} • Loss ${comp(q.parts.loss, 25)} • Stability ${comp(q.parts.stability, 15)}"
            Quality.ScoreResult.NotAvailable -> "⭐ Network Quality Score: N/A — هیچ مؤلفه‌ای داده معتبر ندارد."
        }
    }

    private fun missingPartsText(parts: Quality.PartScores): String {
        val m = mutableListOf<String>()
        if (parts.ping == null) m.add("Ping")
        if (parts.jitter == null) m.add("Jitter")
        if (parts.loss == null) m.add("Loss")
        if (parts.stability == null) m.add("Stability")
        return if (m.isEmpty()) "" else " (بدون ${m.joinToString("، ")})"
    }

    // Dashboard painting only: view assignments from precomputed values.
    // Used by both the full check and the light auto-check, so the two can
    // never show different numbers for the same measurement.
    private fun paintDashboard(results: List<ProbeResult>, ok: Int, pingCf: PingStats, summary: Probes.Summary, q: Quality.ScoreResult, pingHist: List<Double>, lossHist: List<Double>, guidance: String) {
        tvStatusSub.text = summary.title
        tvStatus.setTextColor(Color.parseColor(summary.color))
        gauge.progress = if (results.isEmpty()) 0 else ok * 100 / results.size
        tvStatusNet.text = Geo.connectionInfo(this) + " • $ok از ${results.size} منبع سالم"
        tvStatPing.text = if (pingCf.ok) "${"%.0f".format(pingCf.avgMs)} ms" else "—"
        tvStatPingSub.text = if (pingCf.ok) "min ${"%.0f".format(pingCf.minMs)} / max ${"%.0f".format(pingCf.maxMs)} ms" else "ناموفق"
        tvStatLoss.text = if (pingCf.completed) "${pingCf.lossPct}٪" else "N/A"
        tvStatLossSub.text = if (pingCf.completed) "۴ بسته ICMP" else "داده ناکافی"
        val qualityWord = when (q) {
            is Quality.ScoreResult.Complete -> when { q.total >= 80 -> "عالی"; q.total >= 50 -> "خوب"; else -> "ضعیف" }
            is Quality.ScoreResult.InsufficientData -> "داده ناکافی"
            Quality.ScoreResult.NotAvailable -> "—"
        }
        tvStatus.text = qualityWord
        tvStatusSub.text = "${summary.title} — ${summary.sub}"
        sparkPing.values = pingHist
        sparkLoss.values = lossHist
        when (q) {
            is Quality.ScoreResult.Complete -> {
                tvScoreValue.text = "${q.total}"
                scoreBar.progress = q.total
                tvScoreBadge.text = "Complete"
                tvScoreBadge.setTextColor(Color.parseColor("#22C55E"))
                tvScoreCoverage.text = "پوشش داده ${"%.0f".format(q.coverage * 100)}٪ — امتیاز کامل و معتبر است." + missingPartsText(q.parts)
            }
            is Quality.ScoreResult.InsufficientData -> {
                tvScoreValue.text = "—"
                scoreBar.progress = 0
                tvScoreBadge.text = "Insufficient Data"
                tvScoreBadge.setTextColor(Color.parseColor("#F59E0B"))
                tvScoreCoverage.text = "پوشش داده فقط ${"%.0f".format(q.coverage * 100)}٪ (کمتر از ۷۰٪) — عدد کل نمایش داده نمی‌شود." + missingPartsText(q.parts)
            }
            Quality.ScoreResult.NotAvailable -> {
                tvScoreValue.text = "—"
                scoreBar.progress = 0
                tvScoreBadge.text = "Not Available"
                tvScoreBadge.setTextColor(Color.parseColor("#EF4444"))
                tvScoreCoverage.text = "هیچ مؤلفه‌ای داده معتبر ندارد."
            }
        }
        fun comp2(v: Int?, w: Int) = if (v == null) "N/A" else "$v/$w"
        val partsForCard = when (q) {
            is Quality.ScoreResult.Complete -> q.parts
            is Quality.ScoreResult.InsufficientData -> q.parts
            Quality.ScoreResult.NotAvailable -> Quality.PartScores(null, null, null, null)
        }
        tvScoreParts.text = "Ping ${comp2(partsForCard.ping, 40)}  •  Jitter ${comp2(partsForCard.jitter, 20)}  •  Loss ${comp2(partsForCard.loss, 25)}  •  Stability ${comp2(partsForCard.stability, 15)}"
        try { dotStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(summary.color)) } catch (e: Exception) {}
        graph.points = History.pingPoints(this)
        val st = ChartData.stats(History.pingPoints(this))
        tvChartStats.text = if (st.valid == 0) "Min — • Avg — • Max —" else "Min ${"%.0f".format(st.min)}ms • Avg ${"%.0f".format(st.avg)}ms • Max ${"%.0f".format(st.max)}ms"
        tvGuidance.text = guidance
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.ensureRecovered(this)
        Bridge.installCrashHandler(this) // Bridge Phase 1: chained handler installed in NetWatchApp; this re-asserts it (idempotent)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus); tvStatusSub = findViewById(R.id.tvStatusSub); tvCount = findViewById(R.id.tvCount)
        tvProbes = findViewById(R.id.tvProbes); tvHistory = findViewById(R.id.tvHistory); tvAbout = findViewById(R.id.tvAbout)
        tvIntl = findViewById(R.id.tvIntl); tvIsp = findViewById(R.id.tvIsp); tvFilter = findViewById(R.id.tvFilter)
        tvLatency = findViewById(R.id.tvLatency); tvNet = findViewById(R.id.tvNet)
        graph = findViewById(R.id.graph)
        sectionHome = findViewById(R.id.sectionHome); sectionHistory = findViewById(R.id.sectionHistory); sectionNetwork = findViewById(R.id.sectionNetwork); sectionGaming = findViewById(R.id.sectionGaming); sectionSettings = findViewById(R.id.sectionSettings)
        tvGamingSum = findViewById(R.id.tvGamingSum)
        btnBg = findViewById(R.id.btnBg)
        gauge = findViewById(R.id.gauge); sparkPing = findViewById(R.id.sparkPing); sparkLoss = findViewById(R.id.sparkLoss)
        tvStatPing = findViewById(R.id.tvStatPing); tvStatPingSub = findViewById(R.id.tvStatPingSub)
        tvStatLoss = findViewById(R.id.tvStatLoss); tvStatLossSub = findViewById(R.id.tvStatLossSub)
        tvIpLocal = findViewById(R.id.tvIpLocal); tvIpPublic = findViewById(R.id.tvIpPublic)
        tvIpLocal.text = "— (بعد از اولین تست کامل)"; tvIpPublic.text = "— (بعد از اولین تست کامل)"
        tvDns = findViewById(R.id.tvDns); tvGateway = findViewById(R.id.tvGateway)
        tvDns.text = "— (بعد از اولین تست کامل)"; tvGateway.text = "— (بعد از اولین تست کامل)"
        tvNetSummary = findViewById(R.id.tvNetSummary); tvDetailsSummary = findViewById(R.id.tvDetailsSummary)
        netTechContainer = findViewById(R.id.netTechContainer); btnNetTechToggle = findViewById(R.id.btnNetTechToggle)
        graphHistory = findViewById(R.id.graphHistory); recentContainer = findViewById(R.id.recentContainer); timelineContainer = findViewById(R.id.timelineContainer); tvChartRange = findViewById(R.id.tvChartRange)
        tvAutoTestVal = findViewById(R.id.tvAutoTestVal); tvAlertVal = findViewById(R.id.tvAlertVal)
        tvStatusNet = findViewById(R.id.tvStatusNet); tvChartStats = findViewById(R.id.tvChartStats)
        tvGuidance = findViewById(R.id.tvGuidance); detailsContainer = findViewById(R.id.detailsContainer)
        btnDetailsToggle = findViewById(R.id.btnDetailsToggle)
        tvScoreValue = findViewById(R.id.tvScoreValue); tvScoreBadge = findViewById(R.id.tvScoreBadge)
        tvScoreCoverage = findViewById(R.id.tvScoreCoverage); tvScoreParts = findViewById(R.id.tvScoreParts)
        scoreBar = findViewById(R.id.scoreBar); dotStatus = findViewById(R.id.dotStatus)
        navHome = findViewById(R.id.navHome); navGaming = findViewById(R.id.navGaming); navHistory = findViewById(R.id.navHistory); navLab = findViewById(R.id.navLab); navSettings = findViewById(R.id.navSettings)

        tvAbout.text = "پروتکل‌ها و مقصدها (v2.6):\n\n" +
            "• ذخیره‌سازی: تاریخچه و نمونه‌های پینگ در پایگاه داده داخلی (SQLite) ذخیره می‌شوند و تا ۳۰ روز نگه داشته می‌شوند؛ بررسی خودکار فقط یک تست سبک (۸ پروب) انجام می‌دهد و تست کامل ۴۰+ پروب فقط با دکمه «بررسی» اجرا می‌شود تا باتری و اینترنت کمتر مصرف شود.\n" +
            "• HTTPS/HTTP: کلادفلر، گوگل، Akamai، Fastly، تلگرام، اینستاگرام، واتس‌اپ، سیگنال، دیسکورد، یوتیوب، گیت‌هاب، گیت‌لب، آپارات، دیجی‌کالا، دیوار، تلوبیون، ایرنا\n" +
            "• DNS: DoH (کلادفلر/گوگل/کواد۹)، DoT :853، UDP :53، DNS معمولی — پاسخ‌ها بین ۳ منبع مقایسه می‌شوند تا DNS Poisoning مشخص شود.\n" +
            "• TLS: هندشیک جداگانه برای تلگرام/اینستاگرام/گوگل؛ اگر TCP وصل شود ولی TLS نه، SNI/TLS مشکوک است.\n" +
            "• TCP/ICMP/UDP/QUIC: پینگ ICMP، TCP :443، UDP DNS واقعی، UDP 443 برای QUIC (بدون هندشیک کامل پاسخی نمی‌رسد، پس «نامشخص» گزارش می‌شود نه شکست).\n" +
            "• MTU با ping -M do و Traceroute با ping -t — در گوشی بدون روت بهترین روش ممکن است؛ اگر ping سیستم در دسترس نباشد، این دو بخش «در دسترس نیست» می‌گویند.\n\n" +
            "محدودیت‌های صادقانه:\n" +
            "• نت‌بلاکس API عمومی رایگان ندارد؛ فقط سایتش چک می‌شود.\n" +
            "• اندروید در پس‌زمینه (Doze) کار را به تاخیر می‌اندازد؛ مانیتورینگ پس‌زمینه با Foreground Service و اعلان انجام می‌شود و فاصله را می‌توانی ۳۰ ثانیه / ۱ دقیقه / ۵ دقیقه بگذاری تا باتری و اینترنت کمتر مصرف شود.\n" +
            "• لوکیشن VPN از آی‌پی فعلی (ip-api) خوانده می‌شود؛ اگر VPN روشن باشد، همان کشور/شهر خروجی VPN است."

        findViewById<Button>(R.id.btnMenu).setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            menu.menu.add("فرمول امتیاز")
            menu.menu.add("مانیتورینگ پس‌زمینه")
            menu.menu.add("تنظیمات")
            menu.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "فرمول امتیاز" -> android.app.AlertDialog.Builder(this).setTitle("فرمول Network Quality Score").setMessage(Quality.formulaText()).setPositiveButton("فهمیدم", null).show()
                    "مانیتورینگ پس‌زمینه" -> btnBg.performClick()
                    "تنظیمات" -> show(sectionSettings)
                }
                true
            }
            menu.show()
        }
        btnNetTechToggle.setOnClickListener {
            val open = netTechContainer.visibility != View.VISIBLE
            netTechContainer.visibility = if (open) View.VISIBLE else View.GONE
            btnNetTechToggle.text = if (open) "پنهان کردن جزئیات فنی شبکه" else "نمایش جزئیات فنی شبکه"
        }
        btnDetailsToggle.setOnClickListener {
            val open = detailsContainer.visibility != View.VISIBLE
            detailsContainer.visibility = if (open) View.VISIBLE else View.GONE
            btnDetailsToggle.text = if (open) "پنهان کردن جزئیات بررسی" else "نمایش جزئیات بررسی"
        }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { doCheck() }
        btnBg.setOnClickListener {
            if (MonitorService.running) {
                val i = Intent(this, MonitorService::class.java); i.action = "STOP"; startService(i)
                btnBg.text = "▶  مانیتورینگ پس‌زمینه (با اعلان)"
                Toast.makeText(this, "مانیتورینگ پس‌زمینه متوقف شد.", Toast.LENGTH_SHORT).show()
            } else {
                startForegroundService(Intent(this, MonitorService::class.java))
                btnBg.text = "⏸  توقف مانیتورینگ پس‌زمینه"
                Toast.makeText(this, "مانیتورینگ پس‌زمینه روشن شد؛ ویجت هم به‌روز می‌شود.", Toast.LENGTH_LONG).show()
            }
        }
        // v2.9.6 — exports go to Downloads/NetWatch/ via ExportHelper and
        // report back with a dialog (exact filename, path, Open folder,
        // Share, exact error cause) instead of a Toast with a private path.
        findViewById<Button>(R.id.btnCsv).setOnClickListener {
            Toast.makeText(this, "گزارش CSV در حال ساخت است…", Toast.LENGTH_SHORT).show()
            NetExecutor.submitRestore {
                val r = History.exportCsv(this)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) ExportHelper.showExportDialog(this, "گزارش CSV", listOf("CSV" to r)) }
            }
        }
        findViewById<Button>(R.id.btnJson).setOnClickListener {
            Toast.makeText(this, "گزارش JSON در حال ساخت است…", Toast.LENGTH_SHORT).show()
            NetExecutor.submitRestore {
                val r = History.exportJson(this)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) ExportHelper.showExportDialog(this, "گزارش JSON", listOf("JSON" to r)) }
            }
        }
        findViewById<Button>(R.id.navHome).setOnClickListener { show(sectionHome) }
        findViewById<Button>(R.id.navGaming).setOnClickListener { startActivity(Intent(this, GamingActivity::class.java)) }
        findViewById<Button>(R.id.navHistory).setOnClickListener { refreshHistory(History.HistoryRange.H24) }
        findViewById<Button>(R.id.navLab).setOnClickListener { startActivity(Intent(this, LabActivity::class.java)) }
        findViewById<Button>(R.id.navSettings).setOnClickListener { show(sectionSettings) }
        findViewById<LinearLayout>(R.id.btnOpenNetwork).setOnClickListener { show(sectionNetwork) }
        findViewById<Button>(R.id.btnHistClear).setOnClickListener { findViewById<Button>(R.id.btnClear).performClick() }
        findViewById<Button>(R.id.btnOpenGaming).setOnClickListener { startActivity(Intent(this, GamingActivity::class.java)) }
        fun intervalLabel(sec: Int) = when (sec) { 30 -> "30 sec"; 60 -> "1 min"; else -> "5 min" }
        tvAutoTestVal.text = intervalLabel(History.intervalSec(this))
        findViewById<LinearLayout>(R.id.btnAutoTestRow).setOnClickListener {
            val next = when (History.intervalSec(this)) { 30 -> 60; 60 -> 300; else -> 30 }
            History.setInterval(this, next); secondsLeft = next
            tvAutoTestVal.text = intervalLabel(next)
            Toast.makeText(this, "فاصله بررسی خودکار: ${intervalLabel(next)}", Toast.LENGTH_SHORT).show()
        }
        tvAlertVal.text = "${History.alertThreshold(this)}٪"
        findViewById<LinearLayout>(R.id.btnAlertRow).setOnClickListener {
            val next = when (History.alertThreshold(this)) { 40 -> 50; 50 -> 70; else -> 40 }
            History.setAlertThreshold(this, next)
            tvAlertVal.text = "$next٪"
            Toast.makeText(this, "هشدار قطعی وقتی دسترسی کمتر از $next٪ شد.", Toast.LENGTH_SHORT).show()
        }
        val swLive = findViewById<android.widget.Switch>(R.id.swLive)
        swLive.isChecked = History.livePingOn(this)
        swLive.setOnCheckedChangeListener { _, on ->
            History.setLivePing(this, on)
            if (on) {
                if (!MonitorService.running) startForegroundService(Intent(this, MonitorService::class.java))
                Toast.makeText(this, "پینگ زنده روشن شد — ویجت هر ۵ ثانیه به‌روز می‌شود. (هر ثانیه ممکن نیست؛ باتری و محدودیت اندروید)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "پینگ زنده خاموش شد.", Toast.LENGTH_SHORT).show()
            }
        }
        val swAuto = findViewById<android.widget.Switch>(R.id.swAuto)
        swAuto.isChecked = auto
        swAuto.setOnCheckedChangeListener { _, on -> auto = on; if (on) secondsLeft = History.intervalSec(this); Toast.makeText(this, if (on) "بررسی خودکار روشن شد." else "بررسی خودکار خاموش شد.", Toast.LENGTH_SHORT).show() }
        // The app is dark-only: the switch is shown checked and disabled, not
        // a fake control that springs back with a toast. The language row had a
        // "soon" dialog and no real function, so it has no click handler now —
        // the row itself is removed from the layout (see activity_main.xml).
        val swDark = findViewById<android.widget.Switch>(R.id.swDark)
        swDark.isChecked = true
        swDark.isEnabled = false
        findViewById<LinearLayout>(R.id.btnTestOptions).setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("Test Options — DNS Benchmark").setItems(arrayOf("۵ دور (P95 کم‌اعتبار)", "۲۰ دور (پیش‌فرض)", "۵۰ دور (زمان‌بر)")) { _, which ->
                val rounds = when (which) { 0 -> 5; 2 -> 50; else -> 20 }
                History.setDnsRounds(this, rounds)
                Toast.makeText(this, "DNS Benchmark: $rounds دور", Toast.LENGTH_SHORT).show()
            }.show()
        }
        findViewById<LinearLayout>(R.id.btnSecurity).setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("Security & Privacy").setMessage("همه داده‌ها فقط روی همین گوشی ذخیره می‌شوند؛ هیچ حسابی لازم نیست.\n\nپروتکل‌ها فقط به مقصدهای عمومی وصل می‌شوند (کلادفلر، گوگل، تلگرام و…). لوکیشن آی‌پی فقط از سرویس‌های رایگان ip-api و ipapi.co خوانده می‌شود.\n\nنت‌بلاکس API عمومی رایگان ندارد؛ فقط سایتش چک می‌شود.\n\nسرور واقعی بازی Unknown است؛ اپ به ترافیک بازی دسترسی ندارد.").setPositiveButton("فهمیدم", null).show()
        }
        findViewById<LinearLayout>(R.id.btnAboutRow).setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("About NetWatch").setMessage("NetWatch v${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }} (code ${try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { "?" }})\nPackage: com.aksell.netwatch\nMin Android 5.0 (API 21) • Target Android 14\n\nمحدودیت‌های صادقانه: نت‌بلاکس API ندارد؛ QUIC واقعی پیاده نشده (نامشخص گزارش می‌شود)؛ سرور واقعی بازی Unknown است؛ مانیتورینگ طولانی و باتری فقط روی گوشی واقعی قابل سنجش است.").setPositiveButton("فهمیدم", null).show()
        }
        findViewById<Button>(R.id.btnCheckUpdates).setOnClickListener { openUrl("https://github.com/AksellVarol/NetWatch/releases") }
        findViewById<Button>(R.id.btnCustomTg).setOnClickListener { History.setCustomTarget(this, "telegram.org"); Toast.makeText(this, "مقصد اختصاصی: telegram.org", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnCustomIg).setOnClickListener { History.setCustomTarget(this, "instagram.com"); Toast.makeText(this, "مقصد اختصاصی: instagram.com", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnNetblocks).setOnClickListener { openUrl("https://netblocks.org/") }
        findViewById<Button>(R.id.btnLab).setOnClickListener { startActivity(Intent(this, LabActivity::class.java)) }
        findViewById<Button>(R.id.btnDns5).setOnClickListener { History.setDnsRounds(this, 5); Toast.makeText(this, "DNS Benchmark: ۵ دور (P95 اعتبار پایین دارد)", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnDns20).setOnClickListener { History.setDnsRounds(this, 20); Toast.makeText(this, "DNS Benchmark: ۲۰ دور", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnDns50).setOnClickListener { History.setDnsRounds(this, 50); Toast.makeText(this, "DNS Benchmark: ۵۰ دور (زمان‌بر)", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnDnsBench).setOnClickListener {
            tvNet.text = "DNS Benchmark در حال اجراست…"
            NetExecutor.submit {
                val rounds = History.dnsRounds(this)
                val bench = Quality.dnsBenchmarkRounds(rounds)
                val best = bench.filter { it.successPct > 0 }.minByOrNull { it.medianMs }
                val txt = "🔍 DNS Benchmark ($rounds دور برای هر DNS):\n" + bench.joinToString("\n\n") { "${it.name}: Median ${it.medianMs}ms • P95 ${it.p95Ms}ms • موفقیت ${it.successPct}٪ • دورها: ${it.rounds.joinToString(", ")}ms\n${it.cacheNote}" } + "\n\nبهترین (بر اساس Median): ${best?.name ?: "هیچ‌کدام"}"
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) { tvNet.text = txt; show(sectionNetwork) } }
            }
        }
        findViewById<Button>(R.id.btnBackupSaf).setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "application/json"; i.putExtra(Intent.EXTRA_TITLE, "netwatch-backup.json"); startActivityForResult(i, 1001)
        }
        findViewById<Button>(R.id.btnRestoreSaf).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "application/json"; startActivityForResult(i, 1002)
        }
        val sw = findViewById<android.widget.Switch>(R.id.swAlerts)
        sw.isChecked = History.alertsOn(this)
        sw.setOnCheckedChangeListener { _, on -> History.setAlertsOn(this, on); Toast.makeText(this, if (on) "اعلان‌ها روشن شد." else "اعلان‌ها خاموش شد.", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnH24).setOnClickListener { refreshHistory(History.HistoryRange.H24) }
        findViewById<Button>(R.id.btnH7).setOnClickListener { refreshHistory(History.HistoryRange.D7) }
        findViewById<Button>(R.id.btnH30).setOnClickListener { refreshHistory(History.HistoryRange.D30) }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            tvAbout.text = "🧪 تست خودکار در حال اجراست…"
            NetExecutor.submit {
                val results = NetworkEngine.runProbes(false)
                val g = NetworkEngine.ping("1.1.1.1", 5)
                val pass = results.count { it.ok }
                val txt = "🧪 تست خودکار — ${results.size} پروتکل: $pass موفق، ${results.size - pass} ناموفق\n" +
                    "پینگ 1.1.1.1: ${if (g.received > 0) "✅ ${"%.0f".format(g.avgMs)}ms" else "❌ بدون پاسخ (ICMP مسدود؟ TCP جایگزین را امتحان کن)"}\n" +
                    "DNS: ${if (results.any { it.name.contains("DoH") && it.ok }) "✅" else "❌" } • TCP:443: ${if (results.any { it.name.contains("TCP") && it.ok }) "✅" else "❌" } • IPv6: ${if (results.any { it.name.contains("IPv6") && it.ok }) "✅" else "❌/در دسترس نیست" }\n" +
                    "تاریخچه ساعتی ISP: " + (History.hourly(this).ifBlank { "هنوز داده‌ای نیست" })
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) tvAbout.text = txt }
            }
        }
        findViewById<Button>(R.id.btnPdf).setOnClickListener {
            Toast.makeText(this, "گزارش PDF در حال ساخت است…", Toast.LENGTH_SHORT).show()
            NetExecutor.submitRestore {
                val r = History.exportPdf(this)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) ExportHelper.showExportDialog(this, "گزارش PDF", listOf("PDF" to r)) }
            }
        }
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            Toast.makeText(this, "پشتیبان در حال ساخت است…", Toast.LENGTH_SHORT).show()
            NetExecutor.submitRestore {
                val f = History.backup(this)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) Toast.makeText(this, "پشتیبان ذخیره شد: ${f.absolutePath}", Toast.LENGTH_LONG).show() }
            }
        }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            Toast.makeText(this, "بازیابی در حال انجام است…", Toast.LENGTH_SHORT).show()
            NetExecutor.submitRestore {
                val reason = History.restoreReason(this)
                NetExecutor.runOnMain {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, when (reason) {
                        "OK" -> "بازیابی شد."
                        "NOT_FOUND" -> "پشتیبانی پیدا نشد."
                        else -> "بازیابی ناموفق بود — $reason"
                    }, Toast.LENGTH_LONG).show()
                }
            }
        }
        // Connection profile for current operator + chart ranges are shown in History text
        val op = Geo.connectionInfo(this)
        History.addLog(this, "پروفایل اتصال فعلی: $op — فاصله ${History.intervalSec(this)}s")
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("حذف همه تاریخچه؟").setMessage("همه تاریخچه، نمونه‌ها و گزارش‌های گیمینگ پاک می‌شوند. این کار قابل برگشت نیست، مگر اینکه پشتیبان داشته باشی. ادامه می‌دهی؟")
                .setPositiveButton("بله، پاک کن") { _, _ -> History.clearAll(this); tvHistory.text = ""; Toast.makeText(this, "همه تاریخچه حذف شد.", Toast.LENGTH_LONG).show() }
                .setNegativeButton("لغو", null).show()
        }
        findViewById<Button>(R.id.btnRadar).setOnClickListener { openUrl("https://radar.cloudflare.com/ir") }
        findViewById<Button>(R.id.btnIoda).setOnClickListener { openUrl("https://ioda.caida.org/ioda/dashboard#view=inspect&entity=country/IR&last=24") }
        secondsLeft = History.intervalSec(this)
        graph.points = History.pingPoints(this)
        sparkPing.values = History.pingHistory(this)
        sparkLoss.values = History.lossHistory(this)
        tvIpLocal.text = NetworkInfo.localIp(this)
        tvIpPublic.text = lastPublicIp
        tvDns.text = NetworkInfo.dns(this)
        tvGateway.text = NetworkInfo.gateway(this)
        val lastSample = History.samples(this).lastOrNull()
        if (lastSample != null && lastSample.third > 0) gauge.progress = lastSample.second * 100 / lastSample.third
        tvHistory.text = History.log(this)
        show(sectionHome)
        handler.post(tick)
        doCheck()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 1001) {
            NetExecutor.submitRestore {
                val ok = History.backupToUri(this, uri)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) Toast.makeText(this, if (ok) "پشتیبان در محل انتخاب‌شده ذخیره شد." else "ذخیره پشتیبان ناموفق بود.", Toast.LENGTH_LONG).show() }
            }
        }
        if (requestCode == 1002) {
            NetExecutor.submitRestore {
                val reason = History.restoreFromUriReason(this, uri)
                NetExecutor.runOnMain { if (!isFinishing && !isDestroyed) Toast.makeText(this, if (reason == "OK") "بازیابی شد." else "بازیابی ناموفق بود — $reason", Toast.LENGTH_LONG).show() }
            }
        }
    }
    override fun onDestroy() { handler.removeCallbacks(tick); MonitoringEngine.cancelCheck(); super.onDestroy() }
}
