package com.aksell.netwatch

import android.content.Context
import android.os.BatteryManager
import kotlin.math.roundToInt

// Network Quality Score 0-100, weights explained in UI. NOT the same as Gaming Score:
// Gaming Score is benchmark-ping based; Quality Score uses the last full probe run.
object Quality {
    // Quality Score — Phase 1 (v1.9): sealed ScoreResult, Coverage Gate 70%.
    // - A missing component is N/A, never 0. avgMs <= 0 means "no valid ping data" -> ping N/A.
    // - total exists ONLY in Complete. If coverage < 0.7, no total number is shown at all.
    // - Stability stays in PartScores (per the finalized Phase-1 plan); making it an
    //   independent metric (out of the total) is an open decision for after Phase-1 testing.
    sealed interface ScoreResult {
        data class Complete(val total: Int, val coverage: Double, val parts: PartScores) : ScoreResult
        data class InsufficientData(val coverage: Double, val parts: PartScores) : ScoreResult
        object NotAvailable : ScoreResult
    }
    data class PartScores(val ping: Int?, val jitter: Int?, val loss: Int?, val stability: Int?)

    // Pure gate, separately unit-testable at the exact 70% boundary
    // (70 is not reachable with weights 40/20/25/15, so the boundary is tested here directly).
    // One status vocabulary for the app headline, the gauge and the widget:
    // the same score always gives the same word, so the app can never say
    // "Degraded" while the widget says "Offline" for the same check.
    fun widgetStatus(scoreTotal: Int?, scoreStatus: String): String = when {
        scoreTotal != null -> when { scoreTotal >= 80 -> "Connected"; scoreTotal >= 50 -> "Degraded"; else -> "Offline" }
        scoreStatus == "InsufficientData" -> "Degraded"
        else -> "Unknown"
    }

    fun coverageGate(validWeight: Int, totalWeight: Int): Boolean {
        if (totalWeight <= 0) return false
        return validWeight.toDouble() / totalWeight >= 0.7
    }

    // Shared ping table: the same cutoffs feed Quality.compute (max 40) and
    // Gaming.score (max 35), so one ping value always earns the same relative
    // ping score in both. Fractions: <50ms=1.0, <80=0.85, <120=0.65, <180=0.4, else 0.2.
    fun pingPoints(avgMs: Double, max: Int): Int {
        val frac = when {
            avgMs <= 0 -> 0.0
            avgMs < 50 -> 1.0
            avgMs < 80 -> 0.85
            avgMs < 120 -> 0.65
            avgMs < 180 -> 0.4
            else -> 0.2
        }
        return kotlin.math.round(max * frac).toInt()
    }

    fun compute(avgMs: Double, jitterMs: Double, lossPct: Double, okPct: Int, pingValid: Boolean = true, jitterValid: Boolean = true, lossValid: Boolean = true, stabValid: Boolean = true): ScoreResult {
        // Fix 1: avgMs <= 0 is "no valid measurement", not "score 0".
        // Jitter/Loss are judged on their own validity flags, not on ping's flag.
        val ping: Int? = if (!pingValid || avgMs <= 0) null else pingPoints(avgMs, 40)
        val jit: Int? = if (!jitterValid) null else when { jitterMs < 5 -> 20; jitterMs < 10 -> 16; jitterMs < 20 -> 11; jitterMs < 40 -> 6; else -> 2 }
        val loss: Int? = if (!lossValid) null else when { lossPct == 0.0 -> 25; lossPct < 1 -> 20; lossPct < 3 -> 14; lossPct < 5 -> 8; else -> 2 }
        val stab: Int? = if (!stabValid) null else when { okPct >= 95 -> 15; okPct >= 80 -> 12; okPct >= 60 -> 8; okPct >= 40 -> 4; else -> 1 }
        val parts = PartScores(ping, jit, loss, stab)
        val weights = listOf(ping to 40, jit to 20, loss to 25, stab to 15)
        val totalWeight = weights.sumOf { it.second }
        val validWeight = weights.filter { it.first != null }.sumOf { it.second }
        val validScore = weights.filter { it.first != null }.sumOf { it.first!! }
        if (validWeight == 0) return ScoreResult.NotAvailable
        val coverage = validWeight.toDouble() / totalWeight
        if (!coverageGate(validWeight, totalWeight)) return ScoreResult.InsufficientData(coverage, parts)
        val total = ((validScore.toDouble() / validWeight) * 100).roundToInt().coerceIn(0, 100)
        return ScoreResult.Complete(total, coverage, parts)
    }

    fun formulaText(): String = "وزن‌ها: Ping 40 • Jitter 20 • Packet Loss 25 • Stability 15. " +
        "Ping: <50ms=40، <80=34، <120=26، <180=16، بیشتر=8. " +
        "Jitter: <5ms=20، <10=16، <20=11، <40=6. " +
        "Loss: 0٪=25، <1٪=20، <3٪=14، <5٪=8. " +
        "Stability (درصد منبع‌های سالم): ≥95٪=15، ≥80٪=12، ≥60٪=8، ≥40٪=4. " +
        "Coverage Gate: امتیاز کل فقط وقتی نمایش داده می‌شود که پوشش داده ≥70٪ باشد؛ کمتر از آن، فقط «داده ناقص» نمایش داده می‌شود و عدد کل وجود ندارد. " +
        "آستانه‌های Ping تجربی بر اساس RTT هستند (الهام‌گرفته از راهنمای ITU-T G.114 که تأخیر یک‌طرفه را بررسی می‌کند؛ RTT تقریباً دو برابر تأخیر یک‌طرفه است)؛ آستانه‌های Jitter قاعده تجربی هستند، نه استاندارد."

    // DNS Benchmark: response time + success, per resolver. DoH via HTTPS, UDP via real query.
    // (No "System DNS" row: it used InetAddress, whose cache fakes 0ms repeats,
    // and the UDP-based dnsTime would just duplicate the Cloudflare UDP row.)
    data class DnsBench(val name: String, val ms: Long, val ok: Boolean, val method: String)
    fun dnsBenchmark(): List<DnsBench> {
        val out = mutableListOf<DnsBench>()
        val doh = listOf("Cloudflare DoH" to "https://cloudflare-dns.com/dns-query", "Google DoH" to "https://dns.google/resolve", "Quad9 DoH" to "https://dns.quad9.net/dns-query")
        for ((name, url) in doh) {
            val s = System.currentTimeMillis()
            val ips = Diagnostics.dohIps(url, "google.com")
            out.add(DnsBench(name, System.currentTimeMillis() - s, ips.isNotEmpty(), "DoH"))
        }
        for ((name, ip) in listOf("Cloudflare UDP" to "1.1.1.1", "Google UDP" to "8.8.8.8", "Quad9 UDP" to "9.9.9.9")) {
            val t = Diagnostics.udpDns(ip)
            out.add(DnsBench(name, t.ms, t.ok, "UDP :53"))
        }
        return out
    }

    // Multi-Host Monitoring v2.6: per host, THREE independent methods — ICMP ping, TCP :443, DNS resolution.
    // A host is "down" only if ALL APPLICABLE methods fail; ICMP silence alone is NOT an outage (many hosts block ICMP).
    // DNS is TRI-STATE: for IP literals DNS is N/A (an IP needs no resolution — the old
    // code counted dnsTime's 0ms "success" for IPs, so IP hosts could never fail DNS).
    // For domain hosts DNS is a real UDP resolution against 1.1.1.1.
    // ICMP uses one real ping (pingOnce) instead of a 4-packet pingStats — this
    // cuts a multi-host run from ~40s to a few seconds.
    data class HostCheck(val host: String, val icmp: Boolean, val tcp: Boolean, val dns: Boolean?, val ok: Boolean)
    data class MultiHost(val results: List<HostCheck>, val verdict: String, val durationMs: Long, val perfNote: String)
    fun multiHost(): MultiHost {
        val start = System.currentTimeMillis()
        val rt = Runtime.getRuntime()
        val memBefore = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val hosts = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "www.google.com", "www.aparat.com")
        val isIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val res = hosts.map { h ->
            val icmp = try { Diagnostics.pingOnce(h) != null } catch (e: Exception) { false }
            val tcp = try { Diagnostics.tcpTime(h, 443).ok } catch (e: Exception) { false }
            val dns: Boolean? = if (isIp.matches(h)) null else try { Diagnostics.dnsTime(h).ok } catch (e: Exception) { false }
            HostCheck(h, icmp, tcp, dns, icmp || tcp || (dns == true))
        }
        val okCount = res.count { it.ok }
        val verdict = when {
            okCount == res.size -> "همه ۵ مقصد مستقل سالم‌اند (حداقل یک روش از ICMP/TCP/DNS برای هر کدام جواب داد) — اینترنت و مسیر سالم است."
            okCount == 0 -> "هیچ‌کدام از ۵ مقصد با هیچ‌کدام از سه روش جواب ندادند — اختلال از اینترنت/اتصال خودت است، نه یک مقصد خاص."
            res.last().ok && okCount < 3 -> "مقصد داخلی (آپارات) سالم ولی مقصدهای بین‌المللی خراب‌اند — اختلال بین‌الملل، نه کل اینترنت."
            else -> "بعضی مقصدها سالم، بعضی خراب‌اند ($okCount/${res.size}) — اختلال از مسیر یا مقصد خاص است، نه کل اینترنت."
        }
        val memAfter = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val dur = System.currentTimeMillis() - start
        val perf = "زمان اجرا: ${dur}ms (ICMP تک‌پینگ + TCP + DNS واقعی؛ برای IPها DNS = N/A) • RAM: $memBefore → $memAfter MB • باتری: در این تست کوتاه قابل اندازه‌گیری نیست."
        return MultiHost(res, verdict, dur, perf)
    }

    // DNS Benchmark v1.7: 3 rounds per resolver, Median + P95 + success rate. Cache note: round 1 is cold,
    // rounds 2-3 may be faster due to DNS cache — both are shown, median is reported.
    data class DnsBenchRound(val name: String, val medianMs: Long, val p95Ms: Long, val successPct: Int, val rounds: List<Long>, val cacheNote: String)
    // rounds: configurable, default 20 (minimum recommended for P95). If n < 20, P95 gets a statistical warning.
    fun dnsBenchmarkRounds(rounds: Int = 20): List<DnsBenchRound> {
        val out = mutableListOf<DnsBenchRound>()
        val targets = listOf("Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9")
        for ((name, ip) in targets) {
            val times = mutableListOf<Long>(); var ok = 0
            repeat(rounds) { val t = Diagnostics.udpDns(ip); if (t.ok) { ok++; times.add(t.ms) } }
            val sorted = times.sorted()
            val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
            val p95 = if (sorted.isEmpty()) 0 else sorted[(kotlin.math.ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)) - 1]
            val warn = if (times.size < 20) " ⚠️ هشدار آماری: نمونه کمتر از ۲۰ است، P95 اعتبار پایینی دارد." else ""
            out.add(DnsBenchRound(name, median, p95, if (rounds == 0) 0 else ok * 100 / rounds, times, "دور ۱ سرد (بدون Cache)، دورهای بعد ممکن است به‌خاطر DNS Cache سریع‌تر باشند؛ Median از همه دورها حساب می‌شود.$warn"))
        }
        // DoH rounds
        for ((name, url) in listOf("Cloudflare DoH" to "https://cloudflare-dns.com/dns-query", "Google DoH" to "https://dns.google/resolve")) {
            val times = mutableListOf<Long>(); var ok = 0
            repeat(rounds) { val s = System.currentTimeMillis(); val ips = Diagnostics.dohIps(url, "google.com"); if (ips.isNotEmpty()) { ok++; times.add(System.currentTimeMillis() - s) } }
            val sorted = times.sorted()
            val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
            val p95 = if (sorted.isEmpty()) 0 else sorted[(kotlin.math.ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)) - 1]
            val warn = if (times.size < 20) " ⚠️ هشدار آماری: نمونه کمتر از ۲۰ است، P95 اعتبار پایینی دارد." else ""
            out.add(DnsBenchRound(name, median, p95, if (rounds == 0) 0 else ok * 100 / rounds, times, "DoH روی HTTPS است؛ Cache مرورگر/سیستم روی آن تأثیر کمتری دارد.$warn"))
        }
        return out
    }

    // Disruption classifier: only with sufficient evidence; otherwise Probable / Unknown.
    data class Classification(val type: String, val confidence: Int, val evidence: String)
    fun classifyDisruption(results: List<ProbeResult>, filtering: String, gatewayOk: Boolean, dnsOk: Boolean, intlPct: Int, domPct: Int): Classification {
        val socialFail = results.filter { it.category == "social" && !it.ok }.size
        val intlOk = results.filter { it.id in listOf("cf", "cfdns", "gdns", "google", "github") }.count { it.ok }
        return when {
            // ChatGPT final review, fix 1 (P0): `results.all { it.ok }` is
            // true for an EMPTY list, so an empty probe list was classified
            // "سالم" with confidence 95 — a vacuous truth. An empty list
            // now yields an independent "بدون داده کافی" verdict, never
            // "سالم" (MainActivity calls this with the live check results,
            // which can be empty; the LAB device test guards empty first
            // and reports NOT TESTED). classificationConsistent() already
            // returns false for empty input.
            results.isEmpty() -> Classification("Unknown — بدون داده کافی", 50, "هیچ نتیجهٔ پروبی در دست نیست؛ طبقه‌بندی ممکن نیست.")
            results.isNotEmpty() && results.all { it.ok } -> Classification("سالم", 95, "همه مقصدها با چند روش جواب دادند.")
            // runall-fixes §4: the all-fail rule MUST win over the gateway
            // branch — when no probe succeeds the verdict is a full outage,
            // never "Gateway / local Wi-Fi problem". A down gateway is only
            // supporting evidence, appended to the evidence text.
            // Review amendment: the verdict is a definite "قطعی کامل"
            // (Complete Outage), not an "Unknown" guess — with 0/41 probes
            // answering across many methods, confidence in the outage itself
            // is high (85); the remaining uncertainty is about the CAUSE
            // (ISP vs device), which the evidence text still states.
            results.count { it.ok } == 0 -> Classification("قطعی کامل", 85, "هیچ مقصدی جواب نداد؛ نمی‌توان گفت مشکل از ISP است یا دستگاه — وای‌فای/داده را چک کن." + if (!gatewayOk) " پینگ گیت‌وی مودم هم ناموفق است." else "")
            !gatewayOk -> Classification("احتمالی: مشکل Gateway / وای‌فای محلی", 60, "پینگ گیت‌وی مودم ناموفق است؛ مشکل احتمالاً بین گوشی و مودم است، نه اینترنت.")
            !dnsOk && results.any { it.category == "dns" && it.ok } -> Classification("احتمالی: اختلال DNS", 70, "DNS معمولی خراب ولی DoH سالم است — پاسخ DNS با DoH متفاوت است.")
            filtering.contains("TLS") -> Classification("احتمالی: فیلتر SNI/TLS", 75, "TCP وصل می‌شود ولی TLS Handshake شکست می‌خورد.")
            domPct >= 80 && intlPct < 40 -> Classification("احتمالی: اختلال مسیر بین‌المللی / ISP", 85, "داخلی سالم (≥80٪) ولی بین‌الملل قطع (<40٪) — الگوی مشاهده‌شده با محدودیت مسیر سازگار است؛ علت قطعی (ISP یا مسیر بین‌الملل) از این داده به‌تنهایی مشخص نیست.")
            socialFail >= 2 && intlOk >= 4 -> Classification("احتمالی: فیلترینگ مقصد خاص", 80, "شبکه‌های اجتماعی خراب‌اند ولی اینترنت عمومی سالم است — مشکل از سرور/فیلتر مقصد است، نه کل اینترنت.")
            else -> Classification("Unknown — شواهد کافی نیست", 50, "الگو با هیچ دسته‌بندی قطعی نمی‌خواند؛ نتیجه «احتمالی» اعلام نمی‌شود.")
        }
    }

    // Network Change Detection: compare current network fingerprint with last stored one.
    fun detectChange(ctx: Context): String {
        val conn = Geo.connectionInfo(ctx)
        val geo = Geo.fetch()
        val net = NetworkInfo.summary(ctx)
        val fingerprint = "$conn | ${geo.ip} | $net"
        val old = History.lastFingerprint(ctx)
        val now = History.timeStr(System.currentTimeMillis())
        return if (old.isBlank()) {
            History.setLastFingerprint(ctx, fingerprint)
            "اولین ثبت شبکه در $now — $conn"
        } else if (old == fingerprint) {
            "بدون تغییر شبکه از آخرین بررسی."
        } else {
            History.setLastFingerprint(ctx, fingerprint)
            val line = "تغییر شبکه در $now: $conn • IP ${geo.ip}"
            History.addLog(ctx, line)
            History.addTimeline(ctx, "network-change", line)
            line
        }
    }

    // Stability Timeline: events recorded with exact time.
    fun perfSnapshot(ctx: Context): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
        return "RAM اپ: ${usedMb}MB (Heap) • باتری: ${if (level >= 0) "$level٪" else "نامشخص"} • CPU: اندازه‌گیری دقیق CPU بدون Profiler ممکن نیست — فقط زمان هر بررسی ثبت می‌شود."
    }
}
