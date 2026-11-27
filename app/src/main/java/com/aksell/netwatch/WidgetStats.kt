package com.aksell.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build

// Live Network Statistics for the home-screen widget (v2.9.6, Scope B).
// Everything here is read from real sources; nothing is estimated or
// invented:
// - Connection type: NetworkCapabilities TRANSPORT only (Wi-Fi / Mobile
//   Data / VPN / Ethernet). No TelephonyManager, no READ_PHONE_STATE.
// - Speeds: TrafficStats deltas across the widget's EXISTING refresh
//   cadence (the previous rx/tx + timestamp live in prefs "widget_net").
//   The first run has no previous sample and shows "—", never a fake 0,
//   and the value is always labelled with its measured window.
// - Data usage: TrafficStats device totals since boot, labelled as such.
// - DNS / gateway / public IP: cached (value + timestamp) and refreshed
//   ONLY by MonitorService's cycle via refreshNetworkStats() — never by
//   the widget itself, so the widget never does network I/O.
// - When offline, whatever is still extractable (local IP, connection
//   type, data usage, speeds) is still shown; unknown values are "—".
object WidgetStats {
    private const val PREFS = "widget_net"
    private const val PUBLIC_IP_MAX_AGE_MS = 10 * 60 * 1000L

    // ---- Pure formatters (JVM-tested in selfTests()) ----

    // Bytes/sec -> "12.0 Mbps" / "800 Kbps" / "800 bps". Negative or NaN
    // (no measurement) is "—"; a measured 0 is honestly "0 bps".
    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec.isNaN() || bytesPerSec < 0) return "—"
        val bps = bytesPerSec * 8.0
        return when {
            bps >= 1_000_000 -> "${"%.1f".format(bps / 1_000_000)} Mbps"
            bps >= 1_000 -> "${"%.0f".format(bps / 1_000)} Kbps"
            else -> "${"%.0f".format(bps)} bps"
        }
    }

    // Device total bytes -> "3.2 GB" / "2.5 MB" / "1.5 KB" / "0 B".
    // Negative (TrafficStats unsupported) is "—".
    fun formatBytes(totalBytes: Long): String {
        if (totalBytes < 0) return "—"
        return when {
            totalBytes >= 1_000_000_000L -> "${"%.1f".format(totalBytes / 1e9)} GB"
            totalBytes >= 1_000_000L -> "${"%.1f".format(totalBytes / 1e6)} MB"
            totalBytes >= 1_000L -> "${"%.1f".format(totalBytes / 1e3)} KB"
            else -> "$totalBytes B"
        }
    }

    // Milliseconds -> "42 ms"; a failed/unknown measurement (< 0) is "—".
    fun formatMs(ms: Long): String = if (ms < 0) "—" else "$ms ms"

    // Persian relative age ("۵ دقیقه پیش", "همین حالا"). Pure.
    fun ageFa(ts: Long, nowMs: Long): String {
        if (ts <= 0) return "نامشخص"
        val m = (nowMs - ts) / 60000
        val fa = { n: Long -> n.toString().map { c -> "۰۱۲۳۴۵۶۷۸۹".getOrNull(c - '0') ?: c }.joinToString("") }
        return when {
            m < 1 -> "همین حالا"
            m < 60 -> "${fa(m)} دقیقه پیش"
            m < 1440 -> "${fa(m / 60)} ساعت پیش"
            else -> "${fa(m / 1440)} روز پیش"
        }
    }

    // Bufferbloat tile display (grade, sub, colour), pure. Four distinct
    // states, never a fake value:
    // - Testing wins over any stored result (a re-test is in progress).
    // - null stored result -> Not Tested ("اندازه‌گیری نشده", "—", gray).
    // - valid=false -> Failed ("ناموفق", "—", red; the reason lives in
    //   GamingActivity, which shows bb.note in full).
    // - valid -> grade A–F coloured + "idle→loaded ms • age".
    fun bufferbloatDisplay(bb: Gaming.Bufferbloat?, measuredAtMs: Long, testing: Boolean, nowMs: Long): Triple<String, String, String> {
        if (testing) return Triple("…", "در حال اندازه‌گیری", "#F59E0B")
        if (bb == null) return Triple("—", "اندازه‌گیری نشده", "#94A3B8")
        if (!bb.valid) return Triple("—", "ناموفق", "#EF4444")
        val color = when (bb.grade) {
            "A" -> "#22C55E"
            "B" -> "#4ADE80"
            "C" -> "#F59E0B"
            "D" -> "#F97316"
            else -> "#EF4444"
        }
        val sub = "${"%.0f".format(bb.idleMs)}→${"%.0f".format(bb.loadedMs)}ms • ${ageFa(measuredAtMs, nowMs)}"
        return Triple(bb.grade, sub, color)
    }

    // ---- Connection type: transports only ----

    fun transportType(ctx: Context): String {
        return try {
            if (Build.VERSION.SDK_INT < 23) return "—"
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "—"
            val caps = cm.getNetworkCapabilities(net) ?: return "—"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "—"
            }
        } catch (e: Exception) { "—" }
    }

    // ---- Cached network stats (refreshed by MonitorService only) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fetchPublicIp(): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("https://api.ipify.org?format=json").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            org.json.JSONObject(body).optString("ip", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null } finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }

    // Called ONLY from MonitorService's existing cycle (background thread).
    // Bounded: DNS ~4s socket timeout, gateway ping is one bounded burst,
    // public IP only when the cached value is older than 10 min. Never
    // throws and never breaks the cycle: every item is guarded on its own.
    fun refreshNetworkStats(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val p = prefs(ctx)
            try {
                val t = NetworkEngine.dns("google.com")
                p.edit().putLong("dns_ms", if (t.ok) t.ms else -1L).putLong("dns_ts", now).apply()
            } catch (e: Exception) {}
            try {
                val gw = NetworkInfo.gateway(ctx)
                if (gw != "—" && gw.isNotBlank()) {
                    val s = NetworkEngine.pingStats(gw)
                    p.edit().putLong("gw_ms", if (s.ok) s.avgMs.toLong() else -1L).putLong("gw_ts", now).apply()
                }
            } catch (e: Exception) {}
            try {
                val lastPub = p.getLong("pub_ts", 0L)
                if (now - lastPub > PUBLIC_IP_MAX_AGE_MS) {
                    val ip = fetchPublicIp()
                    if (ip != null) p.edit().putString("pub_ip", ip).putLong("pub_ts", now).apply()
                    // A failed fetch leaves pub_ts untouched: next cycle
                    // retries instead of stamping a failure as fresh.
                }
            } catch (e: Exception) {}
            try { p.edit().putLong("net_ts", now).apply() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    // ---- Snapshot for the widget (no network I/O here) ----

    data class NetSnapshot(
        val down: String, val up: String,
        val dns: String, val gateway: String,
        val localIp: String, val publicIp: String,
        val connType: String, val usage: String,
        val dnsOk: Boolean?, val gwOk: Boolean?,
        val speedWindowSec: Long, val updatedAtMs: Long
    )

    fun snapshot(ctx: Context): NetSnapshot {
        return try {
            val now = System.currentTimeMillis()
            val p = prefs(ctx)
            // Speeds from TrafficStats deltas over the widget's own refresh
            // cadence. First run (or a counter reset, e.g. reboot) stores
            // the baseline and reports "—" — never a fake 0.
            var downText = "—"
            var upText = "—"
            var windowSec = 0L
            try {
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                if (rx >= 0 && tx >= 0) {
                    val prevRx = p.getLong("prev_rx", -1L)
                    val prevTx = p.getLong("prev_tx", -1L)
                    val prevTs = p.getLong("prev_ts", 0L)
                    val dtSec = (now - prevTs) / 1000.0
                    if (prevRx >= 0 && prevTx >= 0 && prevTs > 0 && dtSec >= 1.0) {
                        val dRx = rx - prevRx
                        val dTx = tx - prevTx
                        if (dRx >= 0 && dTx >= 0) {
                            windowSec = (now - prevTs) / 1000
                            downText = "${formatSpeed(dRx / dtSec)} (${windowSec}s)"
                            upText = "${formatSpeed(dTx / dtSec)} (${windowSec}s)"
                        }
                    }
                    p.edit().putLong("prev_rx", rx).putLong("prev_tx", tx).putLong("prev_ts", now).apply()
                }
            } catch (e: Exception) {}
            // Cached DNS / gateway: ms < 0 means the last attempt failed
            // ("ناموفق"), absent means never measured ("—").
            val dnsMs = if (p.contains("dns_ms")) p.getLong("dns_ms", -1L) else Long.MIN_VALUE
            val gwMs = if (p.contains("gw_ms")) p.getLong("gw_ms", -1L) else Long.MIN_VALUE
            val dnsText = when {
                dnsMs == Long.MIN_VALUE -> "—"
                dnsMs < 0 -> "ناموفق"
                else -> formatMs(dnsMs)
            }
            val gwText = when {
                gwMs == Long.MIN_VALUE -> "—"
                gwMs < 0 -> "ناموفق"
                else -> formatMs(gwMs)
            }
            val dnsOk: Boolean? = when {
                dnsMs == Long.MIN_VALUE -> null
                dnsMs < 0 -> false
                else -> true
            }
            val gwOk: Boolean? = when {
                gwMs == Long.MIN_VALUE -> null
                gwMs < 0 -> false
                else -> true
            }
            val localIp = try { NetworkInfo.localIp(ctx) } catch (e: Exception) { "—" }
            val publicIp = p.getString("pub_ip", "") ?: ""
            val usage = try {
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                if (rx < 0 || tx < 0) "—" else formatBytes(rx + tx)
            } catch (e: Exception) { "—" }
            val updatedAt = maxOf(p.getLong("dns_ts", 0L), p.getLong("gw_ts", 0L), p.getLong("pub_ts", 0L))
            NetSnapshot(
                downText, upText, dnsText, gwText,
                localIp, if (publicIp.isBlank()) "—" else publicIp,
                transportType(ctx), usage, dnsOk, gwOk, windowSec, updatedAt
            )
        } catch (e: Exception) {
            NetSnapshot("—", "—", "—", "—", "—", "—", "—", "—", null, null, 0L, 0L)
        }
    }

    // ---- JVM self-tests (pure; called by the parent's harness) ----

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        var lastT = System.currentTimeMillis()
        fun stamp(): Long { val n = System.currentTimeMillis(); val d = n - lastT; lastT = n; return d }
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "Unit — ورودی ثابت، بدون شبکه", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = stamp()))
        }
        // formatSpeed: bytes/sec -> bits/sec display.
        t("WidgetStats — formatSpeed Mbps", "1,500,000 B/s → 12.0 Mbps", formatSpeed(1_500_000.0), formatSpeed(1_500_000.0) == "12.0 Mbps")
        t("WidgetStats — formatSpeed Kbps", "100,000 B/s → 800 Kbps", formatSpeed(100_000.0), formatSpeed(100_000.0) == "800 Kbps")
        t("WidgetStats — formatSpeed bps", "100 B/s → 800 bps", formatSpeed(100.0), formatSpeed(100.0) == "800 bps")
        t("WidgetStats — formatSpeed صفر", "0 B/s → 0 bps (مقدار واقعی، نه خطا)", formatSpeed(0.0), formatSpeed(0.0) == "0 bps")
        t("WidgetStats — formatSpeed نامعتبر", "منفی و NaN → —", "${formatSpeed(-1.0)} / ${formatSpeed(Double.NaN)}", formatSpeed(-1.0) == "—" && formatSpeed(Double.NaN) == "—")
        // formatBytes: device totals.
        t("WidgetStats — formatBytes B", "0 → 0 B", formatBytes(0L), formatBytes(0L) == "0 B")
        t("WidgetStats — formatBytes KB", "1536 → 1.5 KB", formatBytes(1536L), formatBytes(1536L) == "1.5 KB")
        t("WidgetStats — formatBytes MB", "2,500,000 → 2.5 MB", formatBytes(2_500_000L), formatBytes(2_500_000L) == "2.5 MB")
        t("WidgetStats — formatBytes GB", "3,221,225,472 → 3.2 GB", formatBytes(3_221_225_472L), formatBytes(3_221_225_472L) == "3.2 GB")
        t("WidgetStats — formatBytes نامعتبر", "منفی → —", formatBytes(-5L), formatBytes(-5L) == "—")
        // Bufferbloat display mapping: the four widget states.
        val now = System.currentTimeMillis()
        val notTested = bufferbloatDisplay(null, 0L, false, now)
        t("WidgetStats — Bufferbloat اندازه‌گیری‌نشده", "— • اندازه‌گیری نشده • خاکستری", "${notTested.first} • ${notTested.second} • ${notTested.third}", notTested == Triple("—", "اندازه‌گیری نشده", "#94A3B8"))
        val validBb = Gaming.Bufferbloat(48.0, 60.0, 12.0, "A", 100, true, 12.0, true, "ok")
        val testing = bufferbloatDisplay(validBb, now - 5 * 60_000L, true, now)
        t("WidgetStats — Bufferbloat در حال تست", "تست بر نتیجه قبلی غلبه می‌کند • کهربایی", "${testing.first} • ${testing.second} • ${testing.third}", testing == Triple("…", "در حال اندازه‌گیری", "#F59E0B"))
        val failedBb = Gaming.Bufferbloat(48.0, 0.0, 0.0, "-", null, valid = false, note = "پینگ زیر بار ناکافی است")
        val failed = bufferbloatDisplay(failedBb, now - 5 * 60_000L, false, now)
        t("WidgetStats — Bufferbloat ناموفق", "— • ناموفق • قرمز", "${failed.first} • ${failed.second} • ${failed.third}", failed == Triple("—", "ناموفق", "#EF4444"))
        val measured = bufferbloatDisplay(validBb, now - 5 * 60_000L, false, now)
        t("WidgetStats — Bufferbloat اندازه‌گیری‌شده", "A • ‏48→60ms • ۵ دقیقه پیش • سبز", "${measured.first} • ${measured.second} • ${measured.third}", measured.first == "A" && measured.second == "48→60ms • ۵ دقیقه پیش" && measured.third == "#22C55E")
        val measuredF = bufferbloatDisplay(Gaming.Bufferbloat(50.0, 300.0, 250.0, "F", 20, true, 12.0, true, "ok"), now - 90_000L, false, now)
        t("WidgetStats — Bufferbloat درجه F", "F • ‏50→300ms • ۱ دقیقه پیش • قرمز", "${measuredF.first} • ${measuredF.second} • ${measuredF.third}", measuredF.first == "F" && measuredF.second == "50→300ms • ۱ دقیقه پیش" && measuredF.third == "#EF4444")
        // ageFa edge cases.
        t("WidgetStats — ageFa همین حالا", "کمتر از ۱ دقیقه → همین حالا", ageFa(now - 30_000L, now), ageFa(now - 30_000L, now) == "همین حالا")
        t("WidgetStats — ageFa ساعت", "۹۰ دقیقه → ۱ ساعت پیش", ageFa(now - 90 * 60_000L, now), ageFa(now - 90 * 60_000L, now) == "۱ ساعت پیش")
        t("WidgetStats — ageFa نامشخص", "ts=0 → نامشخص", ageFa(0L, now), ageFa(0L, now) == "نامشخص")
        // Bufferbloat record round-trip (Gaming pure format/parse).
        val rec = Gaming.formatBufferbloatRecord(1_700_000_000_000L, validBb)
        val parsed = Gaming.parseBufferbloatRecord(rec)
        t("WidgetStats — رکورد Bufferbloat رفت‌وبرگشت", "همه فیلدها (زمان، idle، loaded، درجه، امتیاز، valid) حفظ شوند", parsed?.let { "${it.ts}|${it.bb.idleMs}|${it.bb.loadedMs}|${it.bb.grade}|${it.bb.score}|${it.bb.valid}" } ?: "null", parsed != null && parsed.ts == 1_700_000_000_000L && parsed.bb.idleMs == 48.0 && parsed.bb.loadedMs == 60.0 && parsed.bb.grade == "A" && parsed.bb.score == 100 && parsed.bb.valid)
        val recPipeNote = Gaming.formatBufferbloatRecord(1_700_000_000_000L, Gaming.Bufferbloat(48.0, 60.0, 12.0, "A", 100, valid = true, note = "a|b"))
        val parsedPipe = Gaming.parseBufferbloatRecord(recPipeNote)
        t("WidgetStats — رکورد با | در یادداشت", "یادداشت حاوی | قالب را نمی‌شکند (۸ فیلد)", parsedPipe?.bb?.grade ?: "null", parsedPipe != null && parsedPipe.bb.grade == "A")
        t("WidgetStats — رکورد خراب", "رشته خالی و ناقص → null", "${Gaming.parseBufferbloatRecord("")} / ${Gaming.parseBufferbloatRecord("1|2|3")}", Gaming.parseBufferbloatRecord("") == null && Gaming.parseBufferbloatRecord("1|2|3") == null && Gaming.parseBufferbloatRecord("not-a-number|48|60|12|A|100|true|x") == null)
        // lossBuckets (ChartData pure helper): the loss strip's data.
        val w0 = 1_700_000_000_000L
        val w1 = w0 + 60_000L
        t("WidgetStats — lossBuckets خالی", "بدون نقطه → همه صفر", ChartData.lossBuckets(emptyList(), w0, w1, 4).toString(), ChartData.lossBuckets(emptyList(), w0, w1, 4) == listOf(0.0, 0.0, 0.0, 0.0))
        val allFail = (0 until 10).map { w0 + it * 6_000L to -1.0 }
        t("WidgetStats — lossBuckets همه ناموفق", "۱۰ نقطه ناموفق → همه باکت‌ها 1.0", ChartData.lossBuckets(allFail, w0, w1, 6).toString(), ChartData.lossBuckets(allFail, w0, w1, 6).all { it == 1.0 })
        val mixed = listOf(w0 + 1_000L to 50.0, w0 + 2_000L to -1.0, w0 + 31_000L to 60.0, w0 + 32_000L to 70.0)
        val mixedBuckets = ChartData.lossBuckets(mixed, w0, w1, 2)
        t("WidgetStats — lossBuckets مختلط", "باکت اول ۱/۲ ناموفق=0.5 • باکت دوم 0/۲=0.0", mixedBuckets.toString(), mixedBuckets == listOf(0.5, 0.0))
        val outside = listOf(w0 - 10_000L to -1.0, w0 + 5_000L to 50.0, w1 + 10_000L to -1.0)
        t("WidgetStats — lossBuckets خارج از بازه", "نقاط بیرون بازه نادیده گرفته شوند → همه صفر", ChartData.lossBuckets(outside, w0, w1, 2).toString(), ChartData.lossBuckets(outside, w0, w1, 2) == listOf(0.0, 0.0))
        t("WidgetStats — lossBuckets لبه‌ها", "bucketCount=0 → خالی • بازه معکوس → صفرها", "${ChartData.lossBuckets(mixed, w0, w1, 0)} / ${ChartData.lossBuckets(mixed, w1, w0, 3)}", ChartData.lossBuckets(mixed, w0, w1, 0).isEmpty() && ChartData.lossBuckets(mixed, w1, w0, 3) == listOf(0.0, 0.0, 0.0))
        val nanPts = listOf(w0 + 1_000L to Double.NaN, w0 + 2_000L to 50.0)
        t("WidgetStats — lossBuckets NaN", "NaN مثل ناموفق شمرده شود → 0.5", ChartData.lossBuckets(nanPts, w0, w1, 1).toString(), ChartData.lossBuckets(nanPts, w0, w1, 1) == listOf(0.5))
        return out
    }
}
