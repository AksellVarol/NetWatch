package com.aksell.netwatch

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import java.util.concurrent.TimeUnit

data class GameProfile(val id: String, val name: String, val pkg: String)
data class Region(val id: String, val name: String, val hosts: List<String>)
data class PingResult(
    val host: String, val sent: Int, val received: Int, val lossPct: Double,
    val minMs: Double, val avgMs: Double, val maxMs: Double,
    val p50: Double, val p90: Double, val p95: Double, val p99: Double,
    val jitterMs: Double, val spikes: Int, val burstLoss: Int, val times: List<Double>
)

object Gaming {
    val profiles = listOf(
        GameProfile("codm", "Call of Duty Mobile", "com.activision.callofduty.shooter"),
        GameProfile("pubg", "PUBG Mobile", "com.tencent.ig"),
        GameProfile("warzone", "Call of Duty Warzone Mobile", "com.activision.callofduty.warzone"),
        GameProfile("custom", "بازی دیگر (مقصد اختصاصی)", "")
    )
    // IMPORTANT: these are public benchmark endpoints (anycast DNS/CDN), NOT real COD servers.
    // Activision does not publish CODM server IPs; inventing one would be fake. In-game ping is recorded separately.
    val regions = listOf(
        Region("me", "Middle East", listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")),
        Region("eu", "Europe", listOf("8.8.8.8", "1.0.0.1", "9.9.9.9", "8.8.4.4")),
        Region("asia", "Asia", listOf("1.1.1.1", "8.8.4.4", "9.9.9.9")),
        Region("na", "North America", listOf("8.8.8.8", "1.1.1.1", "9.9.9.9"))
    )

    // Percentile method (unified everywhere): nearest-rank, rank = ceil(p/100 * n), 1-indexed.
    // e.g. [42,48,50,55,72] n=5: P50 = 3rd = 50, P90 = 5th = 72. Samples < 10 have low statistical validity (shown in UI).
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    // Bounded ping via Diagnostics.pingLines: the process is killed after
    // count*2+5s, so a hung ping can never hang this thread (the old code used
    // waitFor() with NO timeout). If the ping binary is missing, the result is
    // honestly empty (100% loss) — never faked with a re-measured sample.
    fun pingHost(host: String, count: Int = 20): PingResult {
        return try {
            val run = Diagnostics.pingLines(host, count)
            if (run.binaryMissing) {
                return PingResult(host, count, 0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, emptyList())
            }
            val times = run.times
            var burst = 0; var curBurst = 0
            for (line in run.raw.lines()) {
                val t = Regex("time=([0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                if (t != null) curBurst = 0
                else if (line.contains("timeout", true)) { curBurst++; if (curBurst > burst) burst = curBurst }
            }
            val sorted = times.sorted()
            val avg = if (times.isEmpty()) 0.0 else times.average()
            // Jitter here = mean absolute successive difference of reply times.
            // This is NOT RFC 3550 interarrival jitter; it is labelled as such in the UI.
            var jitter = 0.0
            if (times.size > 1) jitter = (1 until times.size).map { abs(times[it] - times[it - 1]) }.average()
            val spikes = times.count { it > 150 || (avg > 0 && it > avg * 2) }
            PingResult(host, count, times.size, if (count == 0) 0.0 else (count - times.size) * 100.0 / count,
                sorted.firstOrNull() ?: 0.0, avg, sorted.lastOrNull() ?: 0.0,
                percentile(sorted, 50.0), percentile(sorted, 90.0), percentile(sorted, 95.0), percentile(sorted, 99.0),
                jitter, spikes, burst, times)
        } catch (e: Exception) {
            PingResult(host, count, 0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, emptyList())
        }
    }

    // TCP fallback when ICMP is blocked
    fun tcpPing(host: String, port: Int = 443, count: Int = 10): PingResult {
        val times = mutableListOf<Double>()
        repeat(count) {
            val t = Diagnostics.tcpTime(host, port)
            if (t.ok) times.add(t.ms.toDouble())
            try { Thread.sleep(200) } catch (e: Exception) {}
        }
        val sorted = times.sorted()
        val avg = if (times.isEmpty()) 0.0 else times.average()
        var jitter = 0.0
        if (times.size > 1) jitter = (1 until times.size).map { abs(times[it] - times[it - 1]) }.average()
        return PingResult("$host:$port (TCP)", count, times.size, (count - times.size) * 100.0 / count,
            sorted.firstOrNull() ?: 0.0, avg, sorted.lastOrNull() ?: 0.0,
            percentile(sorted, 50.0), percentile(sorted, 90.0), percentile(sorted, 95.0), percentile(sorted, 99.0), jitter, times.count { it > 150 }, 0, times)
    }

    data class Bufferbloat(
        val idleMs: Double,
        val loadedMs: Double,
        val increaseMs: Double,
        val grade: String,
        val score: Int?,                  // null = measurement not valid
        val loadApplied: Boolean = false, // did the download really load the link
        val throughputMbps: Double = 0.0,
        val valid: Boolean = false,
        val note: String = ""
    )
    // Bufferbloat: the download keeps running until the loaded ping finishes, so
    // the "loaded" samples are really measured under load. Throughput during the
    // ping window is recorded; if the load was too small the result is flagged
    // as not valid instead of graded. Median (p50) is used so one outlier sample
    // cannot skew the result. Takes ~20-40s; call only on a background thread.
    // NOTE: the download can use tens of MB of mobile data (capped at 50 MB).
    fun bufferbloat(
        target: String = "1.1.1.1",
        idleCount: Int = 10,
        loadedCount: Int = 10,
        minLoadMbps: Double = 1.0,
        shouldStop: (() -> Boolean)? = null
    ): Bufferbloat {
        val idle = pingHost(target, idleCount)
        if (shouldStop?.invoke() == true) {
            return Bufferbloat(0.0, 0.0, 0.0, "-", null, note = "تست Bufferbloat توسط کاربر متوقف شد")
        }
        if (idle.received < idleCount / 2) {
            return Bufferbloat(0.0, 0.0, 0.0, "-", null, note = "پینگ بی‌بار ناکافی است (${idle.received}/$idleCount)")
        }
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        val connRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        // The download runs on the shared managed executor (not a raw Thread):
        // it is cancellable and never leaks a thread when the test is stopped.
        val dlFuture = NetExecutor.submit {
            try {
                val conn = URL("https://speed.cloudflare.com/__down?bytes=200000000").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                connRef.set(conn)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (shouldStop?.invoke() != true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        bytes.addAndGet(n.toLong())
                        if (bytes.get() > 50_000_000L) break // data cap: 50 MB
                    }
                }
            } catch (_: Exception) {
                // Deliberate disconnect or network error; bytes read so far stay valid.
            }
        }
        Thread.sleep(1000) // let the queue fill before pinging under load
        val bytesBefore = bytes.get()
        val windowStart = System.nanoTime()
        val loaded = pingHost(target, loadedCount)
        val windowSec = (System.nanoTime() - windowStart) / 1e9
        val bytesAfter = bytes.get()
        connRef.get()?.disconnect()   // stop the download once the ping is done
        try { dlFuture.get(3, TimeUnit.SECONDS) } catch (_: Exception) { NetExecutor.cancel(dlFuture) }
        val throughputMbps = if (windowSec > 0) (bytesAfter - bytesBefore) * 8.0 / 1e6 / windowSec else 0.0
        val loadApplied = throughputMbps >= minLoadMbps
        val validLoaded = loaded.received >= loadedCount / 2
        if (!validLoaded) {
            return Bufferbloat(idle.p50, 0.0, 0.0, "-", null, loadApplied, throughputMbps, false,
                "پینگ زیر بار ناکافی است (${loaded.received}/$loadedCount)")
        }
        val idleMs = idle.p50
        val loadedMs = loaded.p50
        val inc = loadedMs - idleMs
        val (grade, score) = when {
            inc < 20 -> "A" to 100
            inc < 50 -> "B" to 80
            inc < 100 -> "C" to 60
            inc < 200 -> "D" to 40
            else -> "F" to 20
        }
        return if (!loadApplied) {
            Bufferbloat(idleMs, loadedMs, inc, "?", null, false, throughputMbps, false,
                "بار شبکه کافی اعمال نشد (${"%.1f".format(throughputMbps)} Mbps)؛ نتیجه قابل اتکا نیست")
        } else {
            Bufferbloat(idleMs, loadedMs, inc, grade, score, true, throughputMbps, true,
                "اندازه‌گیری معتبر با ${"%.1f".format(throughputMbps)} Mbps بار")
        }
    }

    fun bufferbloatGradeForTest(idleMs: Double, loadedMs: Double): Pair<String, Double> {
        val inc = loadedMs - idleMs
        val grade = when { inc < 20 -> "A"; inc < 50 -> "B"; inc < 100 -> "C"; inc < 200 -> "D"; else -> "F" }
        return grade to inc
    }
    // Gaming score 0-100, thresholds shown in UI. Benchmark ping, NOT in-game ping.
    // total is NULL while bufferbloat has not been measured: an unmeasured
    // component gets no assumed points (the old code gave a fake 5/10).
    data class Score(val total: Int?, val latency: Int, val jitter: Int, val loss: Int, val stability: Int, val loaded: Int?)
    fun score(p: PingResult, bb: Bufferbloat?): Score {
        val lat = Quality.pingPoints(p.avgMs, 35) // shared cutoffs with Quality.compute (max 40 there)
        val jit = when { p.jitterMs < 5 -> 20; p.jitterMs < 10 -> 16; p.jitterMs < 20 -> 11; p.jitterMs < 40 -> 6; else -> 2 }
        val loss = when { p.lossPct == 0.0 -> 25; p.lossPct < 1 -> 20; p.lossPct < 3 -> 14; p.lossPct < 5 -> 8; else -> 2 }
        val stab = when { p.spikes == 0 -> 10; p.spikes <= 2 -> 7; p.spikes <= 5 -> 4; else -> 1 }
        val load = bb?.score?.let { it * 10 / 100 }
        val total = if (load == null) null else (lat + jit + loss + stab + load).coerceIn(0, 100)
        return Score(total, lat, jit, loss, stab, load)
    }

    fun saveLast(ctx: Context, p: PingResult, bb: Bufferbloat?, score: Score, profile: String, region: String, conn: String) {
        History.withGate(ctx) {
        ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit()
            .putString("last", "${System.currentTimeMillis()}|${p.avgMs}|${p.jitterMs}|${p.lossPct}|${p.p95}|${bb?.grade ?: "-"}|${score.total ?: ""}|$profile|$region|$conn|${p.minMs}|${p.maxMs}")
            .apply()
        val old = ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("log", "") ?: ""
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val line = "$dateStr | $profile | $region | avg ${"%.0f".format(p.avgMs)}ms jitter ${"%.1f".format(p.jitterMs)} loss ${"%.1f".format(p.lossPct)}% P50 ${"%.0f".format(p.p50)} P90 ${"%.0f".format(p.p90)} P95 ${"%.0f".format(p.p95)} score ${score.total?.toString() ?: "—"}/100"
        ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit().putString("log", (line + "\n" + old).lines().take(100).joinToString("\n")).apply()
        }
    }

    fun lastSummary(ctx: Context): String = History.withGate(ctx) { ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("last", "") ?: "" }

    // ---- Bufferbloat persistence (v2.9.6, widget tile) ----
    // Stored record: "bb_last" = ts|idle|loaded|increase|grade|score|valid|note
    // in the "gaming" prefs. The note is sanitised (| -> ¦, newline -> space)
    // so the pipe format can never break. A malformed record parses to null
    // (never measured), never a crash and never a fake grade.
    data class StoredBufferbloat(val ts: Long, val bb: Bufferbloat)

    fun formatBufferbloatRecord(ts: Long, bb: Bufferbloat): String =
        "$ts|${bb.idleMs}|${bb.loadedMs}|${bb.increaseMs}|${bb.grade}|${bb.score ?: ""}|${bb.valid}|${bb.note.replace("|", "¦").replace("\n", " ")}"

    fun parseBufferbloatRecord(raw: String): StoredBufferbloat? {
        if (raw.isBlank()) return null
        val a = raw.split("|")
        if (a.size != 8) return null
        val ts = a[0].toLongOrNull() ?: return null
        val idle = a[1].toDoubleOrNull() ?: return null
        val loaded = a[2].toDoubleOrNull() ?: return null
        val inc = a[3].toDoubleOrNull() ?: return null
        val grade = a[4]
        val score = a[5].toIntOrNull()
        val valid = a[6].toBooleanStrictOrNull() ?: return null
        val note = a[7]
        return StoredBufferbloat(ts, Bufferbloat(idle, loaded, inc, grade, score, valid = valid, note = note))
    }

    // Unconditional: called even when there is no last ping, so a
    // bufferbloat result is never lost through the saveLast path.
    fun saveBufferbloat(ctx: Context, bb: Bufferbloat) {
        History.withGate(ctx) {
            ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit()
                .putString("bb_last", formatBufferbloatRecord(System.currentTimeMillis(), bb))
                .apply()
        }
    }

    fun lastBufferbloat(ctx: Context): StoredBufferbloat? = History.withGate(ctx) {
        parseBufferbloatRecord(ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("bb_last", "") ?: "")
    }

    // In-progress flag: stores the start timestamp. Testing is stale after
    // 10 minutes (e.g. the activity was killed mid-test) — a stale flag
    // never shows "Testing" forever.
    fun setBufferbloatTesting(ctx: Context, testing: Boolean) {
        History.withGate(ctx) {
            ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit()
                .putLong("bb_testing_since", if (testing) System.currentTimeMillis() else 0L)
                .apply()
        }
    }

    fun isBufferbloatTesting(ctx: Context): Boolean = History.withGate(ctx) {
        val since = ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getLong("bb_testing_since", 0L)
        since > 0 && System.currentTimeMillis() - since < 10 * 60 * 1000L
    }
    fun log(ctx: Context): String = History.withGate(ctx) { ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("log", "") ?: "" }
    fun saveMatch(ctx: Context, profile: String, mode: String, inGamePing: String, note: String) {
        History.withGate(ctx) {
        val old = ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("matches", "") ?: ""
        val line = "${History.timeStr(System.currentTimeMillis())} | $profile | $mode | پینگ داخل بازی: $inGamePing | $note"
        ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit().putString("matches", (line + "\n" + old).lines().take(100).joinToString("\n")).apply()
        }
    }
    fun matches(ctx: Context): String = History.withGate(ctx) { ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("matches", "") ?: "" }

    // ---- v2.9.9: session-based background gaming monitoring ----
    // Pure session statistics, computed from the recorded sample series
    // (time order, -1.0 = drop). The SAME rules as the old activity loop:
    // a spike is a reply > 150ms or > 2x the running average (including
    // the sample itself); burst = longest run of consecutive drops;
    // jitter = mean absolute successive difference of successful replies.
    // Pure so the service can finish a session from the DB after a kill
    // and so the JVM tests can prove the math without a device.
    data class SessionStats(
        val sent: Int, val received: Int, val lossPct: Double,
        val avgMs: Double, val minMs: Double, val maxMs: Double,
        val jitterMs: Double, val p50: Double, val p90: Double, val p95: Double, val p99: Double,
        val spikes: Int, val burst: Int, val times: List<Double>
    )

    fun sessionStats(samples: List<Double>): SessionStats {
        val times = samples.filter { it >= 0.0 }
        val drops = samples.size - times.size
        val sent = samples.size
        val received = times.size
        val loss = if (sent == 0) 100.0 else drops * 100.0 / sent
        if (times.isEmpty()) {
            return SessionStats(sent, 0, loss, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, drops, emptyList())
        }
        val sorted = times.sorted()
        val avg = times.average()
        var jitter = 0.0
        if (times.size > 1) jitter = (1 until times.size).map { abs(times[it] - times[it - 1]) }.average()
        // Spike rule (same as the old live loop): running average INCLUDING
        // the current sample; burst = longest run of consecutive drops.
        var spikes = 0
        var burst = 0
        var curBurst = 0
        val running = mutableListOf<Double>()
        for (t in samples) {
            if (t < 0.0) { curBurst++; if (curBurst > burst) burst = curBurst; continue }
            curBurst = 0
            running.add(t)
            val avgSoFar = running.average()
            if (t > 150 || t > avgSoFar * 2) spikes++
        }
        return SessionStats(sent, received, loss, avg, sorted.first(), sorted.last(), jitter,
            percentile(sorted, 50.0), percentile(sorted, 90.0), percentile(sorted, 95.0), percentile(sorted, 99.0),
            spikes, burst, times)
    }

    // Session durations offered by the UI (minutes). Anything else is
    // coerced to 1 minute — a session never runs unbounded.
    fun durationToMs(min: Int): Long = when (min) { 1, 5, 15, 30 -> min * 60 * 1000L; else -> 60 * 1000L }

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "Gaming — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        val normal = sessionStats(listOf(40.0, 42.0, 44.0, 41.0, 43.0))
        t("Gaming — آمار جلسه عادی", "sent=5 recv=5 loss=0 avg=42 min=40 max=44 jitter=2.25 p50=42 p95=44 spikes=0 burst=0",
            "sent=${normal.sent} recv=${normal.received} loss=${normal.lossPct} avg=${normal.avgMs} min=${normal.minMs} max=${normal.maxMs} jitter=${normal.jitterMs} p50=${normal.p50} p95=${normal.p95} spikes=${normal.spikes} burst=${normal.burst}",
            normal.sent == 5 && normal.received == 5 && normal.lossPct == 0.0 && normal.avgMs == 42.0 && normal.minMs == 40.0 && normal.maxMs == 44.0 && normal.jitterMs == 2.25 && normal.p50 == 42.0 && normal.p95 == 44.0 && normal.spikes == 0 && normal.burst == 0)
        val drops = sessionStats(listOf(40.0, -1.0, 42.0, -1.0, -1.0, 44.0))
        t("Gaming — آمار جلسه با قطعی", "sent=6 recv=3 loss=50.0 avg=42 burst=2 times=[40,42,44]",
            "sent=${drops.sent} recv=${drops.received} loss=${drops.lossPct} avg=${drops.avgMs} burst=${drops.burst} times=${drops.times}",
            drops.sent == 6 && drops.received == 3 && drops.lossPct == 50.0 && drops.avgMs == 42.0 && drops.burst == 2 && drops.times == listOf(40.0, 42.0, 44.0))
        val allDrop = sessionStats(listOf(-1.0, -1.0, -1.0))
        t("Gaming — آمار جلسه همه قطعی", "sent=3 recv=0 loss=100 avg=0 burst=3 times=[]",
            "sent=${allDrop.sent} recv=${allDrop.received} loss=${allDrop.lossPct} avg=${allDrop.avgMs} burst=${allDrop.burst} times=${allDrop.times}",
            allDrop.sent == 3 && allDrop.received == 0 && allDrop.lossPct == 100.0 && allDrop.avgMs == 0.0 && allDrop.burst == 3 && allDrop.times.isEmpty())
        val empty = sessionStats(emptyList())
        t("Gaming — آمار جلسه خالی", "sent=0 recv=0 loss=100 avg=0",
            "sent=${empty.sent} recv=${empty.received} loss=${empty.lossPct} avg=${empty.avgMs}",
            empty.sent == 0 && empty.received == 0 && empty.lossPct == 100.0 && empty.avgMs == 0.0)
        val single = sessionStats(listOf(87.0))
        t("Gaming — آمار جلسه تک‌نمونه", "sent=1 recv=1 loss=0 avg=87 min=max=87 jitter=0 p50=87",
            "sent=${single.sent} recv=${single.received} loss=${single.lossPct} avg=${single.avgMs} min=${single.minMs} max=${single.maxMs} jitter=${single.jitterMs} p50=${single.p50}",
            single.sent == 1 && single.received == 1 && single.lossPct == 0.0 && single.avgMs == 87.0 && single.minMs == 87.0 && single.maxMs == 87.0 && single.jitterMs == 0.0 && single.p50 == 87.0)
        val absSpike = sessionStats(listOf(40.0, 40.0, 200.0))
        t("Gaming — اسپایک مطلق (بالای ۱۵۰)", "spikes=1",
            "spikes=${absSpike.spikes}", absSpike.spikes == 1)
        val ratioSpike = sessionStats(listOf(10.0, 10.0, 10.0, 40.0))
        t("Gaming — اسپایک نسبی (دو برابر میانگین جاری)", "spikes=1",
            "spikes=${ratioSpike.spikes}", ratioSpike.spikes == 1)
        val noSpike = sessionStats(listOf(40.0, 40.0, 41.0, 90.0))
        t("Gaming — بدون اسپایک", "spikes=0",
            "spikes=${noSpike.spikes}", noSpike.spikes == 0)
        val burst = sessionStats(listOf(50.0, -1.0, -1.0, -1.0, 60.0, -1.0))
        t("Gaming — طولانی‌ترین قطعی متوالی", "burst=3",
            "burst=${burst.burst}", burst.burst == 3)
        val jit = sessionStats(listOf(10.0, 20.0, 10.0, 20.0))
        t("Gaming — جیتر", "jitter=10.0",
            "jitter=${jit.jitterMs}", jit.jitterMs == 10.0)
        val pct = sessionStats(listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0))
        t("Gaming — صدک‌ها", "p50=50 p90=90 p95=100 p99=100",
            "p50=${pct.p50} p90=${pct.p90} p95=${pct.p95} p99=${pct.p99}",
            pct.p50 == 50.0 && pct.p90 == 90.0 && pct.p95 == 100.0 && pct.p99 == 100.0)
        t("Gaming — تبدیل مدت جلسه به میلی‌ثانیه", "1→60000 5→300000 15→900000 30→1800000 7→60000 0→60000",
            "1→${durationToMs(1)} 5→${durationToMs(5)} 15→${durationToMs(15)} 30→${durationToMs(30)} 7→${durationToMs(7)} 0→${durationToMs(0)}",
            durationToMs(1) == 60000L && durationToMs(5) == 300000L && durationToMs(15) == 900000L && durationToMs(30) == 1800000L && durationToMs(7) == 60000L && durationToMs(0) == 60000L)
        return out
    }
}
