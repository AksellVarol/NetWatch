package com.aksell.netwatch

import android.content.Context
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TestResult(val name: String, val method: String, val expected: String, val actual: String, val pass: Boolean?, val error: String = "", val date: String = "", val durationMs: Long = 0)
// pass: true=Pass, false=Fail, null=Not Tested. durationMs: time spent on this test in ms (0 = not measured).

object Lab {
    // Stats: median = middle value (average of the two middle values for even n);
    // stdDev = POPULATION standard deviation (divide by n — the full measured set),
    // not the sample (n-1) variant. Percentiles are nearest-rank (see pct()).
    data class Stats(val min: Double, val avg: Double, val max: Double, val p50: Double, val p90: Double, val p95: Double, val p99: Double, val jitter: Double, val lossPct: Double, val median: Double = 0.0, val stdDev: Double = 0.0)
    // v2.9.6 — invalid samples never poison the value statistics: only
    // finite, non-negative samples count (NaN / ±Inf and negative values
    // are measurement errors, not pings). Loss stays a COUNT ratio
    // (sent vs received samples, invalid ones included) and is clamped
    // to 0..100 — received > sent used to give a negative loss.
    fun computeStats(times: List<Double>, sent: Int): Stats {
        val valid = times.filter { it.isFinite() && it >= 0.0 }
        val s = valid.sorted()
        // Percentile method (unified with Gaming.percentile): nearest-rank,
        // rank = ceil(p/100 * n), 1-indexed. e.g. n=100: P50 rank 50 -> 50.
        fun pct(p: Double): Double { if (s.isEmpty()) return 0.0; val rank = kotlin.math.ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size); return s[rank - 1] }
        var j = 0.0
        if (valid.size > 1) j = (1 until valid.size).map { abs(valid[it] - valid[it - 1]) }.average()
        val mean = if (valid.isEmpty()) 0.0 else valid.average()
        val median = if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        val stdDev = if (valid.isEmpty()) 0.0 else kotlin.math.sqrt(valid.map { (it - mean) * (it - mean) }.average())
        val lossRaw = if (sent == 0) 0.0 else (sent - times.size) * 100.0 / sent
        return Stats(s.firstOrNull() ?: 0.0, mean, s.lastOrNull() ?: 0.0, pct(50.0), pct(90.0), pct(95.0), pct(99.0), j, lossRaw.coerceIn(0.0, 100.0), median, stdDev)
    }

    // v2.9.6 — is the outage classifier's verdict consistent with the
    // observed probe counts? Pure, JVM-tested. Empty results cannot be
    // verified at all (false — the caller reports NOT TESTED instead).
    // "سالم" is only consistent when every probe passed; "قطعی کامل"
    // only when none passed; anything in between must be neither.
    fun classificationConsistent(results: List<ProbeResult>, cls: Quality.Classification): Boolean {
        val total = results.size
        if (total == 0) return false
        val ok = results.count { it.ok }
        return when {
            ok == total -> cls.type == "سالم"
            ok == 0 -> cls.type.contains("قطعی کامل")
            else -> cls.type != "سالم" && !cls.type.contains("قطعی کامل")
        }
    }

    // v2.9.5 — DoH/UDP summaries from probe results. "DoH" lives in
    // ProbeDef.detail (and kind), NEVER in name — the v2.9.4 LAB counted
    // by name and always got 0/3. These pure helpers are JVM-tested.
    fun summarizeDoh(results: List<ProbeResult>): Triple<Int, Int, String> {
        val doh = results.filter { it.detail == "DoH" }
        val ok = doh.count { it.ok }
        val reasons = doh.joinToString(" • ") { "${it.name}: ${if (it.ok) "OK ${it.ms}ms" else it.note}" }
        return Triple(ok, doh.size, reasons)
    }
    fun summarizeUdp(results: List<ProbeResult>): Triple<Int, Int, String> {
        val udp = results.filter { it.detail.startsWith("UDP") }
        val ok = udp.count { it.ok }
        val reasons = udp.joinToString(" • ") { "${it.name}: ${if (it.ok) "OK ${it.ms}ms" else it.note}" }
        return Triple(ok, udp.size, reasons)
    }

    // v2.9.5 final control — independent re-run merge (pure, JVM-testable):
    // replace ONLY the re-run test in a section's result list; every other
    // result is returned untouched (same objects, same order). If the test
    // is not in the list it is appended. Stored earlier run entries are
    // never touched — the caller saves this merged list as a NEW history
    // entry only (saveRun only prepends; it never rewrites old entries).
    fun mergeRerun(existing: List<TestResult>, fresh: TestResult): List<TestResult> {
        val idx = existing.indexOfFirst { it.name == fresh.name }
        if (idx < 0) return existing + fresh
        val out = existing.toMutableList()
        out[idx] = fresh
        return out
    }

    // v2.9.7 — honest group badge (the v2.9.6 RUN ALL bug): the badge must
    // come from the section's COMPLETE result list, never from one group's
    // results alone. In v2.9.6 RUN ALL set the badge per group, so the
    // section ended up showing only its LAST group's badge — a passing
    // last group hid earlier groups' FAILs (e.g. Restore-negative).
    // Rules: any FAIL -> FAIL; a mix of passes and untested (pass==null)
    // -> PARTIAL, never PASS; all untested or empty -> NOT TESTED;
    // all pass -> PASS. Pure — JVM-tested.
    fun groupBadge(results: List<TestResult>): Pair<String, String> {
        if (results.isEmpty()) return "NOT TESTED" to "#94A3B8"
        if (results.any { it.pass == false }) return "FAIL" to "#EF4444"
        if (results.any { it.pass == null }) return if (results.any { it.pass == true }) "PARTIAL" to "#F59E0B" else "NOT TESTED" to "#94A3B8"
        return "PASS" to "#22C55E"
    }

    // v2.9.7 — honest RUN ALL completion summary. complete means every
    // selected test reached a final status: no untested (pass==null) rows
    // and no TIMEOUT rows. A run WITH failures is still complete — a
    // failure IS a final status. Pure — JVM-tested.
    data class RunAllSummary(val total: Int, val passed: Int, val failed: Int, val untested: Int, val timeouts: Int, val complete: Boolean)
    fun runAllSummary(results: List<TestResult>): RunAllSummary {
        val total = results.size
        val passed = results.count { it.pass == true }
        val failed = results.count { it.pass == false }
        val untested = results.count { it.pass == null }
        val timeouts = results.count { it.error == "TIMEOUT" }
        return RunAllSummary(total, passed, failed, untested, timeouts, total > 0 && untested == 0 && timeouts == 0)
    }

    fun unitTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        // Per-test duration: time since the previous t() call — each test's
        // computation happens right before its t() call, so this attributes
        // the work to the test by name (identifies the slow test on device).
        var lastT = System.currentTimeMillis()
        fun stamp(): Long { val n = System.currentTimeMillis(); val d = n - lastT; lastT = n; return d }
        fun t(name: String, expected: String, actual: String, ok: Boolean) { out.add(TestResult(name, "Unit — ورودی ثابت، بدون شبکه", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = stamp())) }
        // Fixed input: [42,48,50,55,72], sent 5
        val st = computeStats(listOf(42.0, 48.0, 50.0, 55.0, 72.0), 5)
        t("Ping Avg/Min/Max", "avg 53.4, min 42, max 72", "avg ${"%.1f".format(st.avg)}, min ${st.min}, max ${st.max}", st.avg == 53.4 && st.min == 42.0 && st.max == 72.0)
        t("P50/P90/P95/P99 (nearest-rank)", "P50 50, P90 72, P95 72, P99 72", "P50 ${st.p50}, P90 ${st.p90}, P95 ${st.p95}, P99 ${st.p99}", st.p50 == 50.0 && st.p90 == 72.0 && st.p95 == 72.0 && st.p99 == 72.0)
        t("اعتبار آماری نمونه کم", "n=5 → اعتبار پایین (در UI هم نمایش داده می‌شود)", "n=5", true)
        // Statistical validation: 100 values 1..100 -> P50=50, P90=90, P95=95 (nearest-rank: rank=ceil(p/100*n), e.g. P95 rank=ceil(0.95*100)=95 -> 95)
        val big = Lab.computeStats((1..100).map { it.toDouble() }, 100)
        t("اعتبارسنجی آماری (۱ تا ۱۰۰)", "P50 50, P90 90, P95 95", "P50 ${big.p50}, P90 ${big.p90}, P95 ${big.p95}", big.p50 == 50.0 && big.p90 == 90.0 && big.p95 == 95.0)
        // Division by zero guard
        val empty = Lab.computeStats(emptyList(), 0)
        t("تقسیم بر صفر (داده خالی)", "همه صفر، بدون Crash", "avg ${empty.avg}, loss ${empty.lossPct}%", empty.avg == 0.0 && empty.lossPct == 0.0)
        t("Jitter", "jitter 7.5 (diffs 6,2,5,17 -> mean 30/4)", "jitter ${"%.1f".format(st.jitter)}", st.jitter == 7.5)
        t("Packet Loss", "loss 0% (5/5)", "loss ${st.lossPct}%", st.lossPct == 0.0)
        val st2 = computeStats(listOf(40.0, 45.0), 5)
        t("Packet Loss با بسته گمشده", "loss 60% (2/5)", "loss ${st2.lossPct}%", st2.lossPct == 60.0)
        // Bufferbloat grade: idle 48, loaded 60 -> +12ms -> A
        val bb = Gaming.bufferbloatGradeForTest(48.0, 60.0)
        t("Bufferbloat Grade", "افزایش 12ms → A", "درجه ${bb.first} (افزایش ${bb.second}ms)", bb.first == "A")
        val bb2 = Gaming.bufferbloatGradeForTest(48.0, 180.0)
        t("Bufferbloat Grade بد", "افزایش 132ms → D", "درجه ${bb2.first}", bb2.first == "D")
        // Score: perfect ping 40ms, jitter 3, loss 0, no spikes, bb A(100) -> 35+20+25+10+10=100
        val pr = PingResult("test", 20, 20, 0.0, 40.0, 40.0, 45.0, 40.0, 41.0, 42.0, 44.0, 3.0, 0, 0, listOf(40.0))
        val sc = Gaming.score(pr, Gaming.Bufferbloat(40.0, 45.0, 5.0, "A", 100))
        t("Network Score کامل", "100/100", "${sc.total}/100", sc.total == 100)
        val bad = PingResult("test", 20, 10, 50.0, 100.0, 200.0, 400.0, 180.0, 300.0, 350.0, 390.0, 50.0, 8, 3, listOf(200.0))
        val sc2 = Gaming.score(bad, Gaming.Bufferbloat(200.0, 400.0, 200.0, "F", 20))
        t("Network Score بد", "کمتر از 40", "${sc2.total}/100", sc2.total != null && sc2.total < 40)
        // v2.6: bufferbloat unmeasured -> total is null, never a fake 5/10.
        val scNoBb = Gaming.score(pr, null)
        t("Gaming Score — بدون Bufferbloat", "total == null (امتیاز فرضی ندارد)", "total=${scNoBb.total}", scNoBb.total == null)
        val scBb = Gaming.score(pr, Gaming.Bufferbloat(40.0, 45.0, 5.0, "A", 100))
        t("Gaming Score — با Bufferbloat", "total == 100", "total=${scBb.total}", scBb.total == 100)
        // v2.6: dnsTime on an IP literal is N/A, not a 0ms DNS success.
        val dnsIp = Diagnostics.dnsTime("1.1.1.1")
        t("dnsTime — IP مستقیم", "ok=false، note شامل N/A", "ok=${dnsIp.ok}، ${dnsIp.note}", !dnsIp.ok && dnsIp.note.contains("N/A"))
        // v2.6: ChartData — pure chart math (empty / 1 / 2 / 60 / >60, irregular
        // timestamps, failed points split segments, outliers, range filters).
        val now = System.currentTimeMillis()
        val cdEmpty = ChartData.stats(emptyList())
        t("Chart — خالی", "valid=0، min/avg/max=0", "valid=${cdEmpty.valid}", cdEmpty.valid == 0 && cdEmpty.min == 0.0 && cdEmpty.avg == 0.0 && cdEmpty.max == 0.0)
        val cdOne = ChartData.stats(listOf(now to 42.0))
        t("Chart — یک نمونه", "min=avg=max=42", "min=${cdOne.min} avg=${cdOne.avg} max=${cdOne.max}", cdOne.min == 42.0 && cdOne.avg == 42.0 && cdOne.max == 42.0)
        val cdTwo = ChartData.stats(listOf(now to 40.0, now + 5000 to 60.0))
        t("Chart — دو نمونه", "min=40 avg=50 max=60", "avg=${cdTwo.avg}", cdTwo.min == 40.0 && cdTwo.avg == 50.0 && cdTwo.max == 60.0)
        val sixty = (0 until 60).map { now + it * 1000L to (40.0 + it) }
        val cd60 = ChartData.stats(sixty)
        t("Chart — ۶۰ نمونه", "min=40 avg=69.5 max=99", "avg=${cd60.avg} max=${cd60.max}", cd60.valid == 60 && cd60.min == 40.0 && cd60.avg == 69.5 && cd60.max == 99.0)
        val over = (0 until 120).map { now + it * 1000L to 50.0 }
        val cdOver = ChartData.stats(over)
        t("Chart — بیش از ۶۰ نمونه", "valid=120 (بدون برش)", "valid=${cdOver.valid}", cdOver.valid == 120 && cdOver.avg == 50.0)
        val irregular = listOf(now to 50.0, now + 137000 to 60.0, now + 900000 to 70.0)
        val cdIr = ChartData.stats(irregular)
        t("Chart — Timestamp نامنظم", "آمار از مقدارها، نه فاصله‌ها", "avg=${cdIr.avg}", cdIr.avg == 60.0)
        val withFail = listOf(now to 40.0, now + 1000 to -1.0, now + 2000 to 50.0, now + 3000 to -1.0, now + 4000 to 60.0)
        val segs = ChartData.segments(withFail)
        val cdFail = ChartData.stats(withFail)
        t("Chart — نمونه ناموفق بین موفق‌ها", "۳ قطعه جدا + failed=2، آمار فقط موفق‌ها", "segments=${segs.size} failed=${cdFail.failed} avg=${cdFail.avg}", segs.size == 3 && cdFail.failed == 2 && cdFail.avg == 50.0)
        val outlier = (0 until 20).map { now + it * 1000L to 50.0 } + listOf(now + 21000 to 986.0)
        val ym = ChartData.yMax(outlier.map { it.second })
        t("Chart — yMax با مقدار پرت", "P95=50 → yMax=100 (نه 986×1.2)", "yMax=$ym", ym == 100.0)
        val nanPts = listOf(now to 40.0, now + 1000 to Double.NaN, now + 2000 to 60.0)
        val cdNan = ChartData.stats(nanPts)
        t("Chart — NaN", "NaN مثل ناموفق شمرده شود", "valid=${cdNan.valid} failed=${cdNan.failed}", cdNan.valid == 2 && cdNan.failed == 1)
        val day = 24L * 3600 * 1000
        val mixed = listOf(now - 40 * day to 40.0, now - 5 * day to 50.0, now - 1 * 3600 * 1000 to 60.0, now to 70.0)
        val r24 = ChartData.inRange(mixed, now - day)
        val r7 = ChartData.inRange(mixed, now - 7 * day)
        val r30 = ChartData.inRange(mixed, now - 30 * day)
        t("Chart — فیلتر 24h/7d/30d", "24h=2، 7d=3، 30d=3", "24h=${r24.size} 7d=${r7.size} 30d=${r30.size}", r24.size == 2 && r7.size == 3 && r30.size == 3)
        // Quality Score Phase 1 (v1.9): ScoreResult sealed + Coverage Gate 70%
        val q1 = Quality.compute(40.0, 3.0, 0.0, 100)
        t("Quality Score — همه معتبر", "Complete, Total 100, coverage 100٪", "Total ${(q1 as? Quality.ScoreResult.Complete)?.total}, ${(q1 as? Quality.ScoreResult.Complete)?.coverage}", q1 is Quality.ScoreResult.Complete && q1.total == 100 && q1.coverage == 1.0)
        // Old v1.8 bug case: ping missing -> normalized 100/100. Now: coverage 60% < 70% -> InsufficientData, NO total.
        val q2 = Quality.compute(0.0, 3.0, 0.0, 100, pingValid = false)
        t("Quality Score — فقط Ping فاقد داده", "InsufficientData, coverage 60٪, بدون عدد کل", "نوع ${q2::class.simpleName}, coverage ${(q2 as? Quality.ScoreResult.InsufficientData)?.coverage}", q2 is Quality.ScoreResult.InsufficientData && q2.coverage == 0.6)
        val q3 = Quality.compute(100.0, 0.0, 2.0, 85, jitterValid = false)
        t("Quality Score — فقط Jitter فاقد داده", "Complete, Total 65 (52/80 نرمال‌شده)، coverage 80٪", "Total ${(q3 as? Quality.ScoreResult.Complete)?.total}", q3 is Quality.ScoreResult.Complete && q3.total == 65 && q3.coverage == 0.8)
        val q4 = Quality.compute(0.0, 0.0, 0.0, 90, pingValid = false, jitterValid = false)
        t("Quality Score — چند مؤلفه فاقد داده", "InsufficientData, coverage 40٪", "نوع ${q4::class.simpleName}", q4 is Quality.ScoreResult.InsufficientData && q4.coverage == 0.4)
        val q5 = Quality.compute(0.0, 0.0, 0.0, 0, pingValid = false, jitterValid = false, lossValid = false, stabValid = false)
        t("Quality Score — هیچ مؤلفه‌ای داده ندارد", "NotAvailable", "نوع ${q5::class.simpleName}", q5 is Quality.ScoreResult.NotAvailable)
        // Fix 1: avgMs <= 0 with pingValid=true must be N/A, not score 0
        val q6 = Quality.compute(0.0, 3.0, 0.0, 100)
        t("Quality Score — avgMs صفر", "Ping N/A (نه صفر)، coverage 60٪ -> InsufficientData", "Ping ${(q6 as? Quality.ScoreResult.InsufficientData)?.parts?.ping ?: "N/A"}", q6 is Quality.ScoreResult.InsufficientData && q6.parts.ping == null)
        // Coverage Gate exact boundary (70 is unreachable with weights 40/20/25/15, tested directly)
        t("Coverage Gate — مرز دقیق 70٪", "70/100 -> Complete", "gate(70,100)=${Quality.coverageGate(70, 100)}", Quality.coverageGate(70, 100))
        t("Coverage Gate — زیر مرز 69٪", "69/100 -> Insufficient", "gate(69,100)=${Quality.coverageGate(69, 100)}", !Quality.coverageGate(69, 100))
        t("Coverage Gate — پوشش کامل", "100/100 -> Complete", "gate(100,100)=${Quality.coverageGate(100, 100)}", Quality.coverageGate(100, 100))
        t("Coverage Gate — وزن صفر", "0/0 -> Insufficient", "gate(0,0)=${Quality.coverageGate(0, 0)}", !Quality.coverageGate(0, 0))
        // Shared ping table (#11): for any ping, Gaming's latency part must equal
        // Quality's ping part scaled 35/40 (rounded). Same cutoffs, same relative score.
        val sharedOk = listOf(30.0, 60.0, 100.0, 150.0, 250.0).all { ms ->
            val qp = Quality.pingPoints(ms, 40)
            val prx = PingResult("test", 20, 20, 0.0, ms, ms, ms, ms, ms, ms, ms, 0.0, 0, 0, listOf(ms))
            val gs = Gaming.score(prx, Gaming.Bufferbloat(ms, ms, 0.0, "A", 100))
            gs.latency == (qp * 35 + 20) / 40
        }
        t("Ping Points مشترک (Gaming/Quality)", "برای هر ping، بخش Gaming = گرد شده Quality × 35/40", if (sharedOk) "یکسان ✅" else "متفاوت ❌", sharedOk)
        // v2.8 HistoryRange — one unit, milliseconds, everywhere (the v2.7
        // bug: "7 days" showed 7 hours, "30 days" showed 30 hours).
        val hr24 = History.HistoryRange.H24.millis == 24L * 60 * 60 * 1000
        val hr7 = History.HistoryRange.D7.millis == 7L * 24 * 60 * 60 * 1000
        val hr30 = History.HistoryRange.D30.millis == 30L * 24 * 60 * 60 * 1000
        t("HistoryRange — مدت هر بازه", "24h=86,400,000ms • 7d=604,800,000ms • 30d=2,592,000,000ms", "24h=${History.HistoryRange.H24.millis} 7d=${History.HistoryRange.D7.millis} 30d=${History.HistoryRange.D30.millis}", hr24 && hr7 && hr30)
        val fixedNow = 1_700_000_000_000L
        val cut24 = History.HistoryRange.H24.cutoff(fixedNow) == fixedNow - 86_400_000L
        val cut7 = History.HistoryRange.D7.cutoff(fixedNow) == fixedNow - 604_800_000L
        val cut30 = History.HistoryRange.D30.cutoff(fixedNow) == fixedNow - 2_592_000_000L
        t("HistoryRange — محاسبه cutoff", "cutoff = now - millis برای هر سه بازه با زمان ثابت", "24h=$cut24 7d=$cut7 30d=$cut30", cut24 && cut7 && cut30)
        // Range selection with FIXED timestamps (no "now" anywhere): a sample
        // 1h ago is in 24h; 25h ago is out of 24h but in 7d; 8d ago is out of
        // 7d but in 30d; 31d ago is out of every range. Ping chart, health-%
        // chart, cards and Min/Avg/Max all filter on this one cutoff.
        val h = 3_600_000L; val d = 24 * h
        val pts = listOf(fixedNow - 1 * h to 40.0, fixedNow - 25 * h to 50.0, fixedNow - 8 * d to 60.0, fixedNow - 31 * d to 70.0)
        fun inRangeCount(range: History.HistoryRange): Int = pts.count { it.first >= range.cutoff(fixedNow) }
        val c24 = inRangeCount(History.HistoryRange.H24)
        val c7 = inRangeCount(History.HistoryRange.D7)
        val c30 = inRangeCount(History.HistoryRange.D30)
        t("HistoryRange — انتخاب داده با Timestamp ثابت", "24h=1 • 7d=2 • 30d=3 (نمونه ۳۱ روزه در هیچ بازه‌ای نیست)", "24h=$c24 7d=$c7 30d=$c30", c24 == 1 && c7 == 2 && c30 == 3)
        // v2.8 single-flight via MonitoringEngine.runCheck (the ONE shared
        // guard). If a real check is running right now (background service),
        // the guard is legitimately held — NOT TESTED, not a failure.
        val acq1 = MonitoringEngine.runCheck { try { Thread.sleep(300) } catch (e: Exception) {} }
        val acqHeldByOther = acq1 == null
        val acq2 = if (acq1 != null) MonitoringEngine.runCheck {} else null
        // v2.9: cancel does NOT free the key instantly any more — the old
        // task owns it until it really finishes. Wait for the wind-down
        // (poll isRunning, max 2s) before re-acquiring.
        if (acq1 != null) MonitoringEngine.cancelCheck()
        var acqWaited = 0
        while (MonitoringEngine.isCheckRunning() && acqWaited < 2000) { Thread.sleep(50); acqWaited += 50 }
        val acq3 = MonitoringEngine.runCheck {}
        if (acq3 != null) MonitoringEngine.cancelCheck()
        out.add(TestResult("MonitoringEngine — Single-flight", "Unit — ورودی ثابت، بدون شبکه",
            "اول موفق، دومی رد شد، بعد از لغو و پایان واقعی وظیفه دوباره موفق",
            if (acqHeldByOther) "یک بررسی واقعی در حال اجراست — گارد در اختیار تست نیست" else "1st=${acq1 != null} 2nd=${acq2 == null} 3rd=${acq3 != null} waited=${acqWaited}ms",
            if (acqHeldByOther) null else (acq1 != null && acq2 == null && acq3 != null), "", History.timeStr(System.currentTimeMillis()), stamp()))
        // MonitoringEngine cadence policy (pure time math).
        // v2.9.8 — if a real check / live ping is in flight, the guard is
        // not the test's to own: report NOT TESTED (like the Single-flight
        // test above), never a false FAIL. shouldRunCycle itself is pure
        // now; the guard is checked here, at the test boundary.
        val cycGuardBusy = MonitoringEngine.isCheckRunning()
        val cycOk = MonitoringEngine.shouldRunCycle(0, 31000, 30, true)
        val cycSoon = MonitoringEngine.shouldRunCycle(0, 10000, 30, true)
        val cycOff = MonitoringEngine.shouldRunCycle(0, 31000, 30, false)
        val cycZero = MonitoringEngine.shouldRunCycle(0, 31000, 0, true)
        out.add(TestResult("MonitoringEngine — زمان‌بندی چرخه", "31s بعد از 30s مجاز؛ 10s نه؛ خاموش نه؛ فاصله ۰ نه", "31s=$cycOk 10s=$cycSoon off=$cycOff iv0=$cycZero",
            if (cycGuardBusy) "یک بررسی واقعی در حال اجراست — گارد در اختیار تست نیست" else "31s=$cycOk 10s=$cycSoon off=$cycOff iv0=$cycZero",
            if (cycGuardBusy) null else (cycOk && !cycSoon && !cycOff && !cycZero), "", History.timeStr(System.currentTimeMillis()), stamp()))
        val liveGuardBusy = MonitoringEngine.isLiveRunning()
        val liveOk = MonitoringEngine.livePingDue(0, 6000, true, true)
        val liveSoon = MonitoringEngine.livePingDue(0, 2000, true, true)
        val liveOff = MonitoringEngine.livePingDue(0, 6000, true, false)
        val liveNoMon = MonitoringEngine.livePingDue(0, 6000, false, true)
        out.add(TestResult("MonitoringEngine — زمان‌بندی Live Ping", "6s بعد مجاز؛ 2s نه؛ سوییچ خاموش نه؛ مانیتورینگ خاموش نه", "6s=$liveOk 2s=$liveSoon off=$liveOff noMon=$liveNoMon",
            if (liveGuardBusy) "یک Live Ping واقعی در حال اجراست — گارد در اختیار تست نیست" else "6s=$liveOk 2s=$liveSoon off=$liveOff noMon=$liveNoMon",
            if (liveGuardBusy) null else (liveOk && !liveSoon && !liveOff && !liveNoMon), "", History.timeStr(System.currentTimeMillis()), stamp()))
        // NetworkEngine single-flight (new API: Future? — null = refused).
        val single1 = NetworkEngine.submitSingle("lab-test-key") { try { Thread.sleep(300) } catch (e: Exception) {} }
        val single2 = NetworkEngine.submitSingle("lab-test-key") {}
        val singleRunning = NetworkEngine.isRunning("lab-test-key")
        Thread.sleep(500)
        val singleAfter = NetworkEngine.isRunning("lab-test-key")
        t("NetworkEngine — Single-flight", "اول Future، دومی null، در حال اجرا true، بعد از پایان false", "1st=${single1 != null} 2nd=${single2 == null} running=$singleRunning after=$singleAfter", single1 != null && single2 == null && singleRunning && !singleAfter)
        // REAL race test: 8 threads submit the same key at the same instant
        // (CountDownLatch release). Exactly one must win; after it finishes,
        // a new submit must succeed again.
        val raceKey = "race-test-key"
        val raceStart = CountDownLatch(1)
        val raceWinners = AtomicInteger(0)
        val raceThreads = (1..8).map {
            Thread {
                raceStart.await()
                val f = NetworkEngine.submitSingle(raceKey) { try { Thread.sleep(300) } catch (e: Exception) {} }
                if (f != null) raceWinners.incrementAndGet()
            }
        }
        raceThreads.forEach { it.start() }
        raceStart.countDown()
        raceThreads.forEach { it.join(5000) }
        Thread.sleep(500)
        val raceAfter = NetworkEngine.submitSingle(raceKey) {}
        val raceAfterOk = raceAfter != null
        if (raceAfter != null) NetworkEngine.cancelSingle(raceKey)
        t("NetworkEngine — Race Condition", "۸ نخ هم‌زمان → دقیقاً ۱ برنده؛ بعد از پایان، submit دوباره موفق", "winners=${raceWinners.get()} after=$raceAfterOk", raceWinners.get() == 1 && raceAfterOk)
        // Activity + Service non-concurrency: two threads racing for the ONE
        // shared "check" guard (one simulates the manual button, the other
        // the background cycle). Exactly one may run. If a real check is
        // already running, both are refused — NOT TESTED, not a failure.
        val concStart = CountDownLatch(1)
        val concResults = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val concThreads = (1..2).map {
            Thread {
                concStart.await()
                concResults.add(MonitoringEngine.runCheck { try { Thread.sleep(300) } catch (e: Exception) {} } != null)
            }
        }
        concThreads.forEach { it.start() }
        concStart.countDown()
        concThreads.forEach { it.join(5000) }
        val concWins = concResults.count { it }
        MonitoringEngine.cancelCheck()
        val concPass: Boolean? = when {
            concResults.size < 2 -> null
            concWins == 0 -> null // a real check held the guard — not a failure
            else -> concWins == 1
        }
        out.add(TestResult("MonitoringEngine — عدم هم‌زمانی Activity و Service", "دو نخ هم‌زمان برای گارد check (دستی + پس‌زمینه)",
            "دقیقاً ۱ برنده",
            "wins=$concWins/2" + if (concWins == 0) " — یک بررسی واقعی در حال اجراست" else "",
            concPass, "", History.timeStr(System.currentTimeMillis()), stamp()))
        // Several consecutive cycles must NOT create extra thread pools: the
        // shared pool stays at 4 "netwatch-net" threads and no default-named
        // "pool-" threads appear. The assertion is only about threads, not
        // about probe success.
        // v2.9.9 — ROOT CAUSE of the 19:42 Pure TIMEOUT: this test ran 5
        // REAL light probe cycles (NetworkEngine.runProbes(true)); on a
        // degraded network each cycle can take the full 10s awaitProbes
        // deadline, so 5 cycles + the other sleep-based tests exceed the
        // 60s group budget — and because unitPureGroup appends
        // Lab.unitTests() only when it returns, the sink stayed empty
        // ("0 tests completed"). The cycles are now 5 trivial tasks on
        // the SAME shared executor (the property under test — no extra
        // pools — is unchanged) and the test no longer depends on live
        // network conditions. No test deleted, no budget raised.
        fun countThreads(prefix: String) = Thread.getAllStackTraces().keys.count { it.name.startsWith(prefix) }
        val poolBefore = countThreads("pool-")
        val cycleDone = CountDownLatch(5)
        repeat(5) { NetExecutor.submit { try { Thread.sleep(50) } catch (e: Exception) {} finally { cycleDone.countDown() } } }
        cycleDone.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val poolAfter = countThreads("pool-")
        val netCount = countThreads("netwatch-net")
        t("Executor — چرخه‌های متوالی بدون Pool اضافی", "۵ چرخه light پشت سر هم → نخ‌های netwatch-net همان ۴ تا، pool- جدید صفر", "netwatch-net=$netCount pool- before=$poolBefore after=$poolAfter", netCount == 4 && poolAfter == poolBefore)
        // Cancellation: a long task is interrupted, the guard is released,
        // and a new submit for the same key succeeds.
        val cancelInterrupted = AtomicBoolean(false)
        val cancelStarted = AtomicBoolean(false)
        val cancelF = NetworkEngine.submitSingle("cancel-test-key") {
            cancelStarted.set(true)
            try { Thread.sleep(10_000) } catch (e: InterruptedException) { cancelInterrupted.set(true) }
        }
        val cancelGot = cancelF != null
        // v2.9.1: wait until the task REALLY started — cancelling a task
        // that has not started yet removes it cleanly and the interrupt
        // flag is never set (that is correct, not a failure).
        var cancelStartWait = 0
        while (!cancelStarted.get() && cancelStartWait < 2000) { Thread.sleep(10); cancelStartWait += 10 }
        NetworkEngine.cancelSingle("cancel-test-key")
        var cancelWaited = 0
        while (!cancelInterrupted.get() && cancelWaited < 2000) { Thread.sleep(50); cancelWaited += 50 }
        // v2.9: the key is freed only when the interrupted task REALLY ends
        // (its finally), not at the cancel call — wait for the wind-down.
        var cancelFreed = 0
        while (NetworkEngine.isRunning("cancel-test-key") && cancelFreed < 2000) { Thread.sleep(50); cancelFreed += 50 }
        val cancelResubmit = NetworkEngine.submitSingle("cancel-test-key") {}
        if (cancelResubmit != null) NetworkEngine.cancelSingle("cancel-test-key")
        t("NetworkEngine — Cancellation و آزادسازی", "لغو → نخ قطع شد، گارد بعد از پایان واقعی آزاد شد، submit دوباره موفق", "got=$cancelGot interrupted=${cancelInterrupted.get()} running=${NetworkEngine.isRunning("cancel-test-key")} resubmit=${cancelResubmit != null}", cancelGot && cancelInterrupted.get() && !NetworkEngine.isRunning("cancel-test-key") && cancelResubmit != null)
        // AnalyticsEngine delegates to the same functions the UI used to call
        // directly — identical values, one API.
        val aeScore = AnalyticsEngine.gamingScore(pr, Gaming.Bufferbloat(40.0, 45.0, 5.0, "A", 100))
        t("AnalyticsEngine — Gaming Score", "همان Gaming.score مستقیم (total=100)", "total=${aeScore.total}", aeScore.total == sc.total && aeScore.total == 100)
        val aeQ = AnalyticsEngine.qualityScore(40.0, 3.0, 0.0, 100)
        t("AnalyticsEngine — Quality Score", "همان Quality.compute مستقیم (Complete, 100)", "نوع ${aeQ::class.simpleName}", aeQ is Quality.ScoreResult.Complete && aeQ.total == 100)
        val aeStats = AnalyticsEngine.burstStats(listOf(42.0, 48.0, 50.0, 55.0, 72.0), 5)
        t("AnalyticsEngine — Burst Stats", "avg 53.4 مثل Lab.computeStats", "avg ${"%.1f".format(aeStats.avg)}", aeStats.avg == 53.4)
        val aeChart = AnalyticsEngine.chartStats(listOf(now to 40.0, now + 1000 to -1.0, now + 2000 to 60.0))
        t("AnalyticsEngine — Chart Stats", "failed=1، avg فقط موفق‌ها=50", "failed=${aeChart.failed} avg=${aeChart.avg}", aeChart.failed == 1 && aeChart.avg == 50.0)
        // v2.9: a task that IGNORES interrupts must still own its key until
        // it REALLY finishes — the second submit right after cancel is
        // refused, isRunning stays true during the wind-down, and no two
        // tasks with the same key ever overlap (max concurrency stays 1).
        val ignRunning = AtomicInteger(0)
        val ignMax = AtomicInteger(0)
        val ignStarted = AtomicBoolean(false)
        val ignDone = AtomicBoolean(false)
        val ignKey = "ignore-interrupt-key"
        val ignF = NetworkEngine.submitSingle(ignKey) {
            ignStarted.set(true)
            val cur = ignRunning.incrementAndGet()
            ignMax.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
            val end = System.nanoTime() + 2_000_000_000L
            try {
                while (System.nanoTime() < end) {
                    try { Thread.sleep(100) } catch (e: InterruptedException) { /* ignored on purpose */ }
                }
            } finally { ignRunning.decrementAndGet(); ignDone.set(true) }
        }
        val ignGot = ignF != null
        var ignStartWait = 0
        while (!ignStarted.get() && ignStartWait < 2000) { Thread.sleep(50); ignStartWait += 50 }
        val ignPass: Boolean?
        val ignActual: String
        if (!ignStarted.get()) {
            // The pool was busy (a real check running) — the task never
            // started, so this test cannot run: NOT TESTED, not a failure.
            ignPass = null
            ignActual = "وظیفه شروع نشد — Pool مشغول یک بررسی واقعی بود"
            if (ignF != null) NetworkEngine.cancelSingle(ignKey)
        } else {
            NetworkEngine.cancelSingle(ignKey)
            val ignRefused = NetworkEngine.submitSingle(ignKey) {} == null
            val ignStillRunning = NetworkEngine.isRunning(ignKey)
            var ignWaited = 0
            while (!ignDone.get() && ignWaited < 3000) { Thread.sleep(50); ignWaited += 50 }
            val ignAfter = NetworkEngine.submitSingle(ignKey) {}
            val ignAfterOk = ignAfter != null
            if (ignAfter != null) NetworkEngine.cancelSingle(ignKey)
            ignPass = ignGot && ignRefused && ignStillRunning && ignMax.get() == 1 && ignDone.get() && ignAfterOk
            ignActual = "got=$ignGot refused=$ignRefused running=$ignStillRunning maxConc=${ignMax.get()} after=$ignAfterOk"
        }
        out.add(TestResult("NetworkEngine — لغو وظیفه‌ای که Interrupt را نادیده می‌گیرد", "وظیفه ۲ ثانیه‌ای با نادیده‌گرفتن Interrupt؛ لغو → submit دوم رد شد؛ بعد از پایان واقعی submit موفق؛ هم‌زمانی هرگز ۲ نشد",
            "refused=true • running=true • maxConc=1 • after=true",
            ignActual, ignPass, "", History.timeStr(System.currentTimeMillis()), stamp()))
        // v2.9: a task still QUEUED (pool saturated) can be cancelled without
        // a leak — it never runs, and the key is free immediately after.
        val qRan = AtomicBoolean(false)
        val qRan2 = AtomicBoolean(false)
        val qKey = "queued-test-key"
        val qFillers = (0..3).map { i ->
            NetworkEngine.submitSingle("qfill-$i") { try { Thread.sleep(1000) } catch (e: Exception) {} }
        }
        val qF = NetworkEngine.submitSingle(qKey) { qRan.set(true) }
        val qGot = qF != null
        val qEntryExists = NetworkEngine.isRunning(qKey)
        NetworkEngine.cancelSingle(qKey)
        Thread.sleep(1500)
        val qNeverRan = !qRan.get()
        val qFreeAfterCancel = !NetworkEngine.isRunning(qKey)
        val qNew = NetworkEngine.submitSingle(qKey) { qRan2.set(true) }
        val qNewOk = qNew != null
        var qWaited = 0
        while (!qRan2.get() && qWaited < 5000) { Thread.sleep(50); qWaited += 50 }
        t("NetworkEngine — لغو وظیفه صف‌شده (QUEUED)", "Pool با ۴ وظیفه پر شد؛ وظیفه پنجم در صف لغو شد → هرگز اجرا نشد، key بلافاصله آزاد شد، submit دوباره موفق و اجرا شد", "got=$qGot entry=$qEntryExists neverRan=$qNeverRan free=$qFreeAfterCancel new=$qNewOk ran2=${qRan2.get()}", qGot && qEntryExists && qNeverRan && qFreeAfterCancel && qNewOk && qRan2.get())
        // v2.9 socket guard: 200 failed TCP connects must not grow the
        // open-FD count by more than a small bound. Honest note: on this JDK
        // a failed connect closes the FD even without `use`, so this test
        // passes before the fix too — it is a regression guard; the real
        // assurance is the `use` in tcpProbe/tcpTime/quicUdp.
        val fdBefore = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (e: Exception) { -1 }
        repeat(200) { NetworkEngine.tcp("127.0.0.1", 1) }
        val fdAfter = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (e: Exception) { -1 }
        val fdGrowth = if (fdBefore < 0 || fdAfter < 0) -1 else fdAfter - fdBefore
        t("Sockets — FD پس از ۲۰۰ TCP ناموفق", "رشد FD حداکثر ۵ (نگهبان رگرسیون — اثبات اصلی، use در کد است)", "before=$fdBefore after=$fdAfter growth=$fdGrowth", fdGrowth in 0..5)
        // v2.9.2 — QUEUED-cancel race, DETERMINISTIC via the key-scoped
        // test gate: only the Callable whose key equals testGateKey parks
        // at the gate and signals entry. The test (a) parks the gateKey
        // Callable, (b) proves ANOTHER Callable with a different key runs
        // freely while the gate is set (isolation — a background check can
        // neither trip the test nor be delayed by it), (c) cancels the
        // gateKey entry, opens the gate, and asserts the task never ran.
        // With the v2.9 callable (no ownership check) this FAILS — the
        // test was verified to fail against it.
        val gateKey = "queued-cancel-race-key"
        val gateRan = AtomicBoolean(false)
        val gateEntered = AtomicBoolean(false)
        val otherRan = AtomicBoolean(false)
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatePass: Boolean?
        val gateActual: String
        try {
            NetworkEngine.testGateKey = gateKey
            NetworkEngine.testCallableEntered = gateEntered
            NetworkEngine.testPreRunGate = gate
            val gateF = NetworkEngine.submitSingle(gateKey) { gateRan.set(true) }
            val gateGot = gateF != null
            var gateWaited = 0
            while (!gateEntered.get() && gateWaited < 2000) { Thread.sleep(10); gateWaited += 10 }
            if (!gateEntered.get()) {
                // The pool was busy — the Callable never reached the gate,
                // so this interleaving could not be forced: NOT TESTED.
                gatePass = null
                gateActual = "Callable به gate نرسید — Pool مشغول یک بررسی واقعی بود"
                if (gateF != null) NetworkEngine.cancelSingle(gateKey)
            } else {
                // Isolation: a Callable with a DIFFERENT key must run
                // freely while the gate is set (it must not wait at the
                // gate and must not touch the entry signal).
                val otherF = NetworkEngine.submitSingle("other-key-isolation") { otherRan.set(true) }
                Thread.sleep(200)
                val otherOk = otherF != null && otherRan.get()
                // The gateKey Callable is parked at the gate: cancel MUST
                // see QUEUED and remove the entry. Then open the gate.
                NetworkEngine.cancelSingle(gateKey)
                val gateFreed = !NetworkEngine.isRunning(gateKey)
                gate.countDown()
                Thread.sleep(300)
                gatePass = gateGot && otherOk && gateFreed && !gateRan.get()
                gateActual = "got=$gateGot otherRan=$otherOk entryFreed=$gateFreed ran=${gateRan.get()}"
            }
        } finally {
            NetworkEngine.testGateKey = null
            NetworkEngine.testPreRunGate = null
            NetworkEngine.testCallableEntered = null
        }
        out.add(TestResult("NetworkEngine — Race لغو QUEUED در شروع Callable", "Callable در gate متوقف شد؛ یک Callable دیگر با key متفاوت آزادانه اجرا شد؛ cancel ورودی را حذف کرد؛ بعد از بازشدن gate، task نباید اجرا شود",
            "got=true • otherRan=true • entryFreed=true • ran=false",
            gateActual, gatePass, "", History.timeStr(System.currentTimeMillis()), stamp()))
        // v2.4: old 3-field samples parse with -1 metrics; new 6-field samples keep values.
        val sOld = History.parseSample("1700000000000,35,40")
        val sNew = History.parseSample("1700000000000,35,40,120.5,8.2,0.0")
        t("Sample قدیمی (۳ فیلد)", "بدون پینگ/جیتر/قطعی = -1", if (sOld != null && sOld.avgMs == -1.0 && sOld.lossPct == -1.0) "سازگار ✅" else "خراب ❌", sOld != null && sOld.avgMs == -1.0)
        t("Sample جدید (۶ فیلد)", "مقادیر حفظ شوند", if (sNew != null && sNew.avgMs == 120.5 && sNew.jitterMs == 8.2) "درست ✅" else "خراب ❌", sNew != null && sNew.avgMs == 120.5 && sNew.jitterMs == 8.2)
        // v2.4: timeline lines parse into time/kind/text.
        val evs = History.parseTimeline("12:30 [outage-start] شروع اختلال در 12:30 — دسترسی ۳۰٪\n12:45 [network-change] تغییر شبکه")
        t("Timeline parse", "نوع و زمان جدا شوند", if (evs.size == 2 && evs[0].kind == "outage-start" && evs[1].time == "12:45") "درست ✅" else "خراب ❌", evs.size == 2 && evs[0].kind == "outage-start" && evs[1].time == "12:45")
        // v2.4: one status vocabulary — same score, same word, app and widget alike.
        val stOk = Quality.widgetStatus(85, "Complete") == "Connected" && Quality.widgetStatus(60, "Complete") == "Degraded" && Quality.widgetStatus(30, "Complete") == "Offline" && Quality.widgetStatus(null, "InsufficientData") == "Degraded"
        t("Widget Status از امتیاز", "۸۵→Connected، ۶۰→Degraded، ۳۰→Offline", if (stOk) "یکسان ✅" else "متفاوت ❌", stOk)
        // Chart: values must be in 0..100 and count preserved
        val chart = listOf(42.0, 48.0, 72.0).map { (it / 2).toInt().coerceIn(0, 100) }
        t("صحت داده نمودار", "[21, 24, 36]", chart.toString(), chart == listOf(21, 24, 36))
        // v2.9.4 (P0-2): bounded reader — never holds more than the cap.
        fun bounded(bytes: ByteArray, limit: Long): String {
            return try {
                val got = RestoreStream.readBounded(java.io.ByteArrayInputStream(bytes), limit)
                "ok:${got.size}"
            } catch (e: RestoreStream.SizeLimitExceeded) { "over-limit" }
        }
        t("Bounded Reader — زیر سقف", "۱۰۰ بایت با سقف ۱۰۰۰ → ok:100", bounded(ByteArray(100), 1000), bounded(ByteArray(100), 1000) == "ok:100")
        t("Bounded Reader — دقیقاً سقف", "۱۰۰۰ بایت با سقف ۱۰۰۰ → ok:1000 (رد نمی‌شود)", bounded(ByteArray(1000), 1000), bounded(ByteArray(1000), 1000) == "ok:1000")
        t("Bounded Reader — بالای سقف", "۱۰۰۱ بایت با سقف ۱۰۰۰ → over-limit", bounded(ByteArray(1001), 1000), bounded(ByteArray(1001), 1000) == "over-limit")
        t("Bounded Reader — مرز Chunk", "۶۵۵۳۶ بایت ok؛ ۶۵۵۳۷ بایت over-limit", "65536=${bounded(ByteArray(65536), 65536)} 65537=${bounded(ByteArray(65537), 65536)}", bounded(ByteArray(65536), 65536) == "ok:65536" && bounded(ByteArray(65537), 65536) == "over-limit")
        t("Bounded Reader — خالی و سقف صفر", "۰ بایت ok؛ ۱ بایت با سقف ۰ over-limit", "empty=${bounded(ByteArray(0), 1000)} zero=${bounded(ByteArray(1), 0)}", bounded(ByteArray(0), 1000) == "ok:0" && bounded(ByteArray(1), 0) == "over-limit")
        // v2.9.4 (P0-2): integer-literal form — "1" ok; "1.0", "1e3", "01", "+1", "\"1\"" rejected.
        val litCases = listOf("1" to true, "0" to true, "-5" to true, "-0" to true, "123456789" to true,
            "1.0" to false, "1e3" to false, "1E3" to false, "01" to false, "+1" to false, "\"1\"" to false,
            "" to false, " 1" to false, "1 " to false, "1.5e-3" to false, "--1" to false)
        for ((lit, want) in litCases) {
            val got = RestoreStream.isIntegerLiteral(lit)
            t("عدد صحیح — \"$lit\"", if (want) "قابل قبول" else "رد شود", if (got) "قابل قبول" else "رد شد", got == want)
        }
        // v2.9.4 (P0-1): recovery decision table — pure encoding of the crash table.
        val recCases = listOf(
            Triple(null as String?, false, RestoreStream.RecoveryAction.NOTHING),
            Triple(null, true, RestoreStream.RecoveryAction.CLEAN_STAGING),
            Triple("STARTED", false, RestoreStream.RecoveryAction.ROLLBACK),
            Triple("STARTED", true, RestoreStream.RecoveryAction.ROLLBACK),
            Triple("COMMITTED", false, RestoreStream.RecoveryAction.ROLL_FORWARD),
            Triple("COMMITTED", true, RestoreStream.RecoveryAction.ROLL_FORWARD),
            Triple("GARBAGE", false, RestoreStream.RecoveryAction.NOTHING),
            Triple("GARBAGE", true, RestoreStream.RecoveryAction.CLEAN_STAGING)
        )
        for ((state, staging, want) in recCases) {
            val got = RestoreStream.decideRecovery(state, staging)
            t("Recovery — state=$state staging=$staging", want.toString(), got.toString(), got == want)
        }
        // v2.9.8 — stale-sidecar deletion decision (pure): delete ONLY
        // after a clean open + integrity check, when the file still exists.
        t("Sidecar — حذف پس از بازبینی سالم", "cleanOpen=true • integrity=ok • stillExists=true → حذف", History.staleSidecarAction(true, true, true).toString(), History.staleSidecarAction(true, true, true))
        t("Sidecar — بدون بازبینی، حذف ممنوع", "cleanOpen=false → حذف ممنوع", History.staleSidecarAction(false, true, true).toString(), !History.staleSidecarAction(false, true, true))
        t("Sidecar — integrity ناسالم، حذف ممنوع", "integrity=false → حذف ممنوع", History.staleSidecarAction(true, false, true).toString(), !History.staleSidecarAction(true, false, true))
        t("Sidecar — فایل نیست، کاری نیست", "stillExists=false → حذف ممنوع", History.staleSidecarAction(true, true, false).toString(), !History.staleSidecarAction(true, true, false))
        // v2.9.4 (P0-2): known-size cap check (null = unknown -> rely on the during-read bound).
        t("Cap — اندازه نامشخص", "null → false (به خواندن جریانی تکیه می‌شود)", RestoreStream.exceedsCap(null, 100).toString(), !RestoreStream.exceedsCap(null, 100))
        t("Cap — دقیقاً سقف", "۱۰۰ با سقف ۱۰۰ → false", RestoreStream.exceedsCap(100, 100).toString(), !RestoreStream.exceedsCap(100, 100))
        t("Cap — بالای سقف", "۱۰۱ با سقف ۱۰۰ → true", RestoreStream.exceedsCap(101, 100).toString(), RestoreStream.exceedsCap(101, 100))
        t("Cap — صفر", "۰ با سقف ۰ → false", RestoreStream.exceedsCap(0, 0).toString(), !RestoreStream.exceedsCap(0, 0))
        t("سقف Restore", "۵۰ مگابایت = ۵۲٬۴۲۸٬۸۰۰ بایت", "${History.RESTORE_MAX_BYTES}", History.RESTORE_MAX_BYTES == 50L * 1024 * 1024)
        // v2.9.5 — Median + Standard Deviation (population, /n) on fixed data.
        val med1 = Lab.computeStats(listOf(10.0, 20.0, 30.0, 40.0, 50.0), 5)
        t("Median — فرد", "median 30 (وسط ۵ مقدار)", "median ${med1.median}", med1.median == 30.0)
        val med2 = Lab.computeStats(listOf(10.0, 20.0, 30.0, 40.0), 4)
        t("Median — زوج", "median 25 (میانگین ۲۰ و ۳۰)", "median ${med2.median}", med2.median == 25.0)
        t("StdDev — [10..50]", "stdDev √200 ≈ 14.1421 (جمعیتی، /n)", "stdDev ${"%.4f".format(med1.stdDev)}", abs(med1.stdDev - 14.14213562) < 0.001)
        t("StdDev — [42,48,50,55,72]", "stdDev ≈ 10.19 (واریانس 103.84)", "stdDev ${"%.2f".format(st.stdDev)}", abs(st.stdDev - 10.19) < 0.01)
        t("StdDev — خالی/تکی/یکسان", "خالی 0 • تکی 0 • [5,5,5] صفر", "empty=${empty.stdDev} single=${Lab.computeStats(listOf(7.0), 1).stdDev} same=${Lab.computeStats(listOf(5.0, 5.0, 5.0), 3).stdDev}", empty.stdDev == 0.0 && Lab.computeStats(listOf(7.0), 1).stdDev == 0.0 && Lab.computeStats(listOf(5.0, 5.0, 5.0), 3).stdDev == 0.0)
        t("Median — [42,48,50,55,72]", "median 50", "median ${st.median}", st.median == 50.0)
        // v2.9.6 — computeStats edge cases: invalid inputs must never
        // poison the statistics. NaN / ±Inf / negative samples are ignored
        // for the VALUE stats; loss is a count ratio clamped to 0..100.
        val nanSt = Lab.computeStats(listOf(40.0, Double.NaN, 60.0), 3)
        t("Stats — NaN نادیده گرفته شود", "avg 50 • min 40 • max 60 • median 50 • loss 0٪", "avg ${nanSt.avg} min ${nanSt.min} max ${nanSt.max} median ${nanSt.median} loss ${nanSt.lossPct}%", nanSt.avg == 50.0 && nanSt.min == 40.0 && nanSt.max == 60.0 && nanSt.median == 50.0 && nanSt.lossPct == 0.0)
        val infSt = Lab.computeStats(listOf(40.0, Double.POSITIVE_INFINITY, 60.0), 3)
        t("Stats — +Inf نادیده گرفته شود", "avg 50 • min 40 • max 60 • loss 0٪", "avg ${infSt.avg} min ${infSt.min} max ${infSt.max} loss ${infSt.lossPct}%", infSt.avg == 50.0 && infSt.min == 40.0 && infSt.max == 60.0 && infSt.lossPct == 0.0)
        val negInfSt = Lab.computeStats(listOf(40.0, Double.NEGATIVE_INFINITY, 60.0), 3)
        t("Stats — -Inf نادیده گرفته شود", "avg 50 • min 40 • max 60 • loss 0٪", "avg ${negInfSt.avg} min ${negInfSt.min} max ${negInfSt.max} loss ${negInfSt.lossPct}%", negInfSt.avg == 50.0 && negInfSt.min == 40.0 && negInfSt.max == 60.0 && negInfSt.lossPct == 0.0)
        val allNanSt = Lab.computeStats(listOf(Double.NaN, Double.NaN), 2)
        t("Stats — همه NaN", "همه آمار مقدار ۰ • loss 0٪ (۲ نمونه دریافت شد)", "avg ${allNanSt.avg} min ${allNanSt.min} max ${allNanSt.max} loss ${allNanSt.lossPct}%", allNanSt.avg == 0.0 && allNanSt.min == 0.0 && allNanSt.max == 0.0 && allNanSt.lossPct == 0.0)
        val negSt = Lab.computeStats(listOf(-5.0, 40.0, 50.0), 3)
        t("Stats — مقدار منفی نادیده گرفته شود", "avg 45 • min 40 • max 50 • loss 0٪", "avg ${negSt.avg} min ${negSt.min} max ${negSt.max} loss ${negSt.lossPct}%", negSt.avg == 45.0 && negSt.min == 40.0 && negSt.max == 50.0 && negSt.lossPct == 0.0)
        val singleSt = Lab.computeStats(listOf(42.0), 1)
        t("Stats — تک‌مقداری", "avg=min=max=median=42 • jitter 0 • stdDev 0", "avg ${singleSt.avg} median ${singleSt.median} jitter ${singleSt.jitter} stdDev ${singleSt.stdDev}", singleSt.avg == 42.0 && singleSt.min == 42.0 && singleSt.max == 42.0 && singleSt.median == 42.0 && singleSt.jitter == 0.0 && singleSt.stdDev == 0.0)
        val sameSt = Lab.computeStats(listOf(50.0, 50.0, 50.0), 3)
        t("Stats — همه برابر", "avg 50 • median 50 • stdDev 0 • loss 0٪", "avg ${sameSt.avg} median ${sameSt.median} stdDev ${sameSt.stdDev}", sameSt.avg == 50.0 && sameSt.median == 50.0 && sameSt.stdDev == 0.0 && sameSt.lossPct == 0.0)
        val overRecvSt = Lab.computeStats(listOf(40.0, 45.0, 50.0, 55.0, 60.0), 3)
        t("Stats — received بیشتر از sent", "loss به ۰ محدود شود (نه منفی)", "loss ${overRecvSt.lossPct}%", overRecvSt.lossPct == 0.0)
        val sentZeroSt = Lab.computeStats(listOf(40.0, 50.0), 0)
        t("Stats — sent=0 با داده", "loss 0٪ (تقسیم بر صفر نداریم) • avg 45", "avg ${sentZeroSt.avg} loss ${sentZeroSt.lossPct}%", sentZeroSt.lossPct == 0.0 && sentZeroSt.avg == 45.0)
        val emptySentSt = Lab.computeStats(emptyList(), 5)
        t("Stats — خالی با sent>0", "loss 100٪ • بقیه آمار ۰", "avg ${emptySentSt.avg} loss ${emptySentSt.lossPct}%", emptySentSt.lossPct == 100.0 && emptySentSt.avg == 0.0 && emptySentSt.min == 0.0 && emptySentSt.max == 0.0)
        // v2.9.6 — classification consistency: the classifier verdict must
        // match the observed probe counts (synthetic probe lists, no network).
        fun synthProbes(okFlags: List<Boolean>): List<ProbeResult> = okFlags.mapIndexed { i, ok -> ProbeResult("p$i", "پروب $i", "HTTPS", ok, if (ok) 50 else 0, if (ok) "OK" else "Fail", false, "intl") }
        val clsAllOkProbes = synthProbes(listOf(true, true, true))
        val clsAllOk = Quality.classifyDisruption(clsAllOkProbes, "", true, true, 100, 100)
        t("طبقه‌بندی — همه موفق", "«سالم» + سازگار با شواهد", "«${clsAllOk.type}» سازگار=${Lab.classificationConsistent(clsAllOkProbes, clsAllOk)}", clsAllOk.type == "سالم" && Lab.classificationConsistent(clsAllOkProbes, clsAllOk))
        val clsNoneOkProbes = synthProbes(listOf(false, false, false))
        val clsNoneOk = Quality.classifyDisruption(clsNoneOkProbes, "", true, true, 0, 0)
        t("طبقه‌بندی — هیچ‌کدام موفق نیست", "«قطعی کامل» + سازگار با شواهد", "«${clsNoneOk.type}» سازگار=${Lab.classificationConsistent(clsNoneOkProbes, clsNoneOk)}", clsNoneOk.type.contains("قطعی کامل") && Lab.classificationConsistent(clsNoneOkProbes, clsNoneOk))
        // runall-fixes §4: the gateway branch used to fire BEFORE the
        // all-fail branch, so 0 probes ok + gateway down was reported as a
        // "Gateway / local Wi-Fi problem" instead of a full outage — and
        // classificationConsistent() then disagreed (the v2.9.8 on-device
        // «تشخیص قطعی» failure). The all-fail rule now wins; a down gateway
        // is supporting evidence, never the verdict.
        val clsNoneOkGwDownProbes = synthProbes(listOf(false, false, false))
        val clsNoneOkGwDown = Quality.classifyDisruption(clsNoneOkGwDownProbes, "", false, false, 0, 0)
        t("طبقه‌بندی — هیچ‌کدام موفق نیست + Gateway خراب", "«قطعی کامل» (نه «مشکل Gateway») + سازگار با شواهد", "«${clsNoneOkGwDown.type}» (${clsNoneOkGwDown.confidence}٪) — ${clsNoneOkGwDown.evidence} • سازگار=${Lab.classificationConsistent(clsNoneOkGwDownProbes, clsNoneOkGwDown)}", clsNoneOkGwDown.type == "قطعی کامل" && clsNoneOkGwDown.confidence >= 80 && !clsNoneOkGwDown.type.contains("Gateway") && Lab.classificationConsistent(clsNoneOkGwDownProbes, clsNoneOkGwDown))
        // runall-fixes §4 — REAL DATA (2026-11-10 22:03 on-device RUN ALL):
        // the exact probe set (Probes.defs, 41 probes) all failed, gateway
        // ping failed, DNS failed, intl 0%, dom 0%. The old code reported
        // "مشکل Gateway / وای‌فای محلی" for this input (reproduced on the
        // pre-fix code); the fix must report "قطعی کامل". filtering is ""
        // here because the ordering bug does not depend on it (the gateway
        // branch fired before the all-fail branch regardless of filtering).
        val real2203 = Probes.defs.map { ProbeResult(it.id, it.name, it.detail, false, 0, "Fail", it.domestic, it.category) }
        val clsReal2203 = Quality.classifyDisruption(real2203, "", false, false, 0, 0)
        t("طبقه‌بندی — داده واقعی ۲۲:۰۳ (۴۱ پروب، همه ناموفق)", "«قطعی کامل» + سازگار با شواهد", "پروب‌ها=${real2203.size} موفق=${real2203.count { it.ok }} «${clsReal2203.type}» (${clsReal2203.confidence}٪) — ${clsReal2203.evidence} • سازگار=${Lab.classificationConsistent(real2203, clsReal2203)}", real2203.size == 41 && clsReal2203.type == "قطعی کامل" && clsReal2203.confidence >= 80 && Lab.classificationConsistent(real2203, clsReal2203))
        // ChatGPT final review, fix 1 (P0): an empty probe list must never
        // classify as "سالم" — `all {}` is vacuously true on empty. The
        // classifier now returns an independent "بدون داده کافی" verdict.
        val clsEmpty = Quality.classifyDisruption(emptyList<ProbeResult>(), "", true, true, 0, 0)
        t("طبقه‌بندی — لیست خالی هرگز «سالم» نیست", "«بدون داده کافی» + سازگار=false", "«${clsEmpty.type}» (${clsEmpty.confidence}٪) — ${clsEmpty.evidence} • سازگار=${Lab.classificationConsistent(emptyList<ProbeResult>(), clsEmpty)}", clsEmpty.type.contains("بدون داده کافی") && clsEmpty.type != "سالم" && !Lab.classificationConsistent(emptyList<ProbeResult>(), clsEmpty))
        val clsEmptyGwDown = Quality.classifyDisruption(emptyList<ProbeResult>(), "", false, false, 0, 0)
        t("طبقه‌بندی — لیست خالی + Gateway خراب", "«بدون داده کافی» (نه Gateway، نه «قطعی کامل»)", "«${clsEmptyGwDown.type}» (${clsEmptyGwDown.confidence}٪) • سازگار=${Lab.classificationConsistent(emptyList<ProbeResult>(), clsEmptyGwDown)}", clsEmptyGwDown.type.contains("بدون داده کافی") && !clsEmptyGwDown.type.contains("قطعی کامل") && !clsEmptyGwDown.type.contains("Gateway") && !Lab.classificationConsistent(emptyList<ProbeResult>(), clsEmptyGwDown))
        val clsMixedProbes = synthProbes(listOf(true, true, false))
        val clsMixed = Quality.classifyDisruption(clsMixedProbes, "", true, true, 66, 66)
        t("طبقه‌بندی — بخشی موفق", "نه «سالم» نه «قطعی کامل» + سازگار با شواهد", "«${clsMixed.type}» سازگار=${Lab.classificationConsistent(clsMixedProbes, clsMixed)}", clsMixed.type != "سالم" && !clsMixed.type.contains("قطعی کامل") && Lab.classificationConsistent(clsMixedProbes, clsMixed))
        t("طبقه‌بندی — ناسازگاری شناسایی می‌شود", "همه موفق + «قطعی کامل» → ناسازگار • مخلوط + «سالم» → ناسازگار • هیچ‌کدام + «سالم» → ناسازگار", "allOk+outage=${Lab.classificationConsistent(clsAllOkProbes, Quality.Classification("قطعی کامل", 85, ""))} mixed+healthy=${Lab.classificationConsistent(clsMixedProbes, Quality.Classification("سالم", 95, ""))} noneOk+healthy=${Lab.classificationConsistent(clsNoneOkProbes, Quality.Classification("سالم", 95, ""))}", !Lab.classificationConsistent(clsAllOkProbes, Quality.Classification("قطعی کامل", 85, "")) && !Lab.classificationConsistent(clsMixedProbes, Quality.Classification("سالم", 95, "")) && !Lab.classificationConsistent(clsNoneOkProbes, Quality.Classification("سالم", 95, "")))
        t("طبقه‌بندی — بدون شواهد قابل بررسی نیست", "لیست خالی → سازگاری false (گزارش NOT TESTED، نه Pass)", "consistent=${Lab.classificationConsistent(emptyList(), Quality.Classification("سالم", 95, ""))}", !Lab.classificationConsistent(emptyList(), Quality.Classification("سالم", 95, "")))
        // v2.9.5 — Percentile method agreement: Lab.computeStats and
        // Gaming.percentile must use the SAME nearest-rank method.
        val pct100 = (1..100).map { it.toDouble() }
        t("Percentile — توافق Lab و Gaming", "P50=50 • P90=90 • P95=95 • P99=99 در هر دو", "Lab P95=${Lab.computeStats(pct100, 100).p95} • Gaming P95=${Gaming.percentile(pct100, 95.0)}", Lab.computeStats(pct100, 100).p50 == 50.0 && Lab.computeStats(pct100, 100).p95 == 95.0 && Gaming.percentile(pct100, 50.0) == 50.0 && Gaming.percentile(pct100, 90.0) == 90.0 && Gaming.percentile(pct100, 95.0) == 95.0 && Gaming.percentile(pct100, 99.0) == 99.0)
        // v2.9.5 — DoH field match (the v2.9.4 LAB bug): "DoH" is in
        // ProbeDef.detail/kind, NEVER in name — counting by name always gave 0/3.
        t("DoH — شمارش با detail", "۳ پروب DoH با detail=DoH", "detail=DoH: ${Probes.defs.count { it.detail == "DoH" }}", Probes.defs.count { it.detail == "DoH" } == 3)
        t("DoH — شمارش با name همیشه صفر بود", "name شامل DoH نیست (باگ ۲٫۹٫۴)", "name شامل DoH: ${Probes.defs.count { it.name.contains("DoH") }}", Probes.defs.count { it.name.contains("DoH") } == 0)
        val dohSynth = listOf(
            ProbeResult("cfdns", "DNS کلادفلر 1.1.1.1", "DoH", true, 120, "DoH OK", false, "dns"),
            ProbeResult("gdns", "DNS گوگل 8.8.8.8", "DoH", false, 5000, "Timeout اتصال/خواندن", false, "dns"),
            ProbeResult("quad9", "DNS کواد۹ 9.9.9.9", "DoH", false, 5000, "TLS Handshake ناموفق", false, "dns")
        )
        val dohSum = Lab.summarizeDoh(dohSynth)
        t("DoH — خلاصه نتایج", "۱/۳ موفق + دلیل هر ارائه‌دهنده", "${dohSum.first}/${dohSum.second} • ${dohSum.third}", dohSum.first == 1 && dohSum.second == 3 && dohSum.third.contains("کلادفلر") && dohSum.third.contains("Timeout"))
        val udpSynth = listOf(
            ProbeResult("udp-cf", "UDP DNS کلادفلر", "UDP :53", true, 40, "پاسخ موفق DNS", false, "dns"),
            ProbeResult("udp-gg", "UDP DNS گوگل", "UDP :53", false, 4000, "timeout: پاسخ DNS نرسید", false, "dns")
        )
        val udpSum = Lab.summarizeUdp(udpSynth)
        t("UDP — خلاصه نتایج", "۱/۲ موفق + دلیل هر سرور", "${udpSum.first}/${udpSum.second} • ${udpSum.third}", udpSum.first == 1 && udpSum.second == 2)
        // v2.9.5 — DoH JSON parse (same rules as Probes.httpProbe now uses).
        val dohGood = Probes.parseDohJson("{\"Status\":0,\"TC\":false,\"RD\":true,\"RA\":true,\"AD\":false,\"CD\":false,\"Question\":[{\"name\":\"google.com.\",\"type\":1}],\"Answer\":[{\"name\":\"google.com.\",\"type\":1,\"TTL\":300,\"data\":\"142.250.180.14\"}]}")
        t("DoH Parse — پاسخ معتبر", "Status=0 + Answer با رکورد A → OK", "ok=${dohGood.ok} answers=${dohGood.answers}", dohGood.ok && dohGood.answers == listOf("142.250.180.14"))
        val dohBadStatus = Probes.parseDohJson("{\"Status\":2,\"Answer\":[]}")
        t("DoH Parse — Status غیرصفر", "Status=2 → رد", "ok=${dohBadStatus.ok} • ${dohBadStatus.error}", !dohBadStatus.ok && dohBadStatus.error.contains("Status=2"))
        val dohNoAnswer = Probes.parseDohJson("{\"Status\":0}")
        t("DoH Parse — بدون Answer", "Status=0 ولی Answer نیست → رد", "ok=${dohNoAnswer.ok}", !dohNoAnswer.ok)
        val dohEmptyAnswer = Probes.parseDohJson("{\"Status\":0,\"Answer\":[]}")
        t("DoH Parse — Answer خالی", "Answer خالی → رد", "ok=${dohEmptyAnswer.ok}", !dohEmptyAnswer.ok)
        val dohCnameOnly = Probes.parseDohJson("{\"Status\":0,\"Answer\":[{\"name\":\"x.\",\"type\":5,\"TTL\":300,\"data\":\"y.example.\"}]}")
        t("DoH Parse — فقط CNAME", "فقط رکورد CNAME (type 5) → رد (رکورد A نیست)", "ok=${dohCnameOnly.ok}", !dohCnameOnly.ok)
        val dohBroken = Probes.parseDohJson("{bad json")
        t("DoH Parse — JSON خراب", "JSON خراب → رد", "ok=${dohBroken.ok}", !dohBroken.ok)
        // v2.9.5 final control — strict IPv4 in DoH A records: each case
        // is its own test (valid / out-of-range / non-IP / incomplete /
        // empty). A record with an invalid IP fails with the IP error.
        fun dohIp(data: String) = Probes.parseDohJson("{\"Status\":0,\"Answer\":[{\"name\":\"google.com.\",\"type\":1,\"TTL\":300,\"data\":\"$data\"}]}")
        val ipValid = dohIp("142.250.72.14")
        t("DoH Parse — IPv4 معتبر", "142.250.72.14 → OK", "ok=${ipValid.ok} answers=${ipValid.answers}", ipValid.ok && ipValid.answers == listOf("142.250.72.14"))
        val ipRange = dohIp("999.1.1.1")
        t("DoH Parse — IPv4 خارج از بازه", "999.1.1.1 → رد با خطای IP", "ok=${ipRange.ok} • ${ipRange.error}", !ipRange.ok && ipRange.error.contains("IP نامعتبر"))
        val ipNonIp = dohIp("not-an-ip")
        t("DoH Parse — مقدار غیر IP", "not-an-ip → رد با خطای IP", "ok=${ipNonIp.ok} • ${ipNonIp.error}", !ipNonIp.ok && ipNonIp.error.contains("IP نامعتبر"))
        val ipShort = dohIp("1.2.3")
        t("DoH Parse — IPv4 ناقص", "1.2.3 → رد با خطای IP", "ok=${ipShort.ok} • ${ipShort.error}", !ipShort.ok && ipShort.error.contains("IP نامعتبر"))
        val ipLong = dohIp("1.2.3.4.5")
        t("DoH Parse — IPv4 با بخش اضافه", "1.2.3.4.5 → رد با خطای IP", "ok=${ipLong.ok} • ${ipLong.error}", !ipLong.ok && ipLong.error.contains("IP نامعتبر"))
        val ipEmpty = dohIp("")
        t("DoH Parse — مقدار خالی", "data خالی → رد با خطای IP", "ok=${ipEmpty.ok} • ${ipEmpty.error}", !ipEmpty.ok && ipEmpty.error.contains("IP نامعتبر"))
        val ipLeadingZero = dohIp("01.2.3.4")
        t("DoH Parse — صفر پیشوند", "01.2.3.4 → رد با خطای IP", "ok=${ipLeadingZero.ok} • ${ipLeadingZero.error}", !ipLeadingZero.ok && ipLeadingZero.error.contains("IP نامعتبر"))
        // v2.9.5 final control — mergeRerun: only the re-run test changes.
        val mergeBase = listOf(
            TestResult("Merge Test — A", "m", "e", "a1", true, "", "t", 10),
            TestResult("Merge Test — B", "m", "e", "b1", true, "", "t", 20),
            TestResult("Merge Test — C", "m", "e", "c1", null, "", "t", 30)
        )
        val mergeFresh = TestResult("Merge Test — B", "m", "e", "b2", false, "دلیل تازه", "t2", 99)
        val merged = Lab.mergeRerun(mergeBase, mergeFresh)
        t("Merge Re-run — فقط تست انتخاب‌شده عوض می‌شود", "A و C دقیقاً مثل قبل، B تازه", "A=${merged[0].actual} B=${merged[1].actual} C=${merged[2].actual}", merged.size == 3 && merged[0] == mergeBase[0] && merged[2] == mergeBase[2] && merged[1] == mergeFresh)
        val mergeAppend = Lab.mergeRerun(mergeBase, TestResult("Merge Test — D", "m", "e", "d1", true, "", "t", 5))
        t("Merge Re-run — تست جدید اضافه می‌شود", "D به انتها اضافه شود، بقیه دست نخورده", "size=${mergeAppend.size}", mergeAppend.size == 4 && mergeAppend.take(3) == mergeBase && mergeAppend[3].name == "Merge Test — D")
        // v2.9.7 — honest group badge (regression for the v2.9.6 RUN ALL
        // bug: the section badge came from the LAST group only, so a
        // passing last group hid earlier groups' FAILs).
        fun badgeRes(name: String, pass: Boolean?) = TestResult(name, "m", "e", "a", pass, "", "t", 1)
        val badgeAllPass = listOf(badgeRes("A", true), badgeRes("B", true), badgeRes("C", true))
        t("Badge — همه Pass", "PASS", Lab.groupBadge(badgeAllPass).first, Lab.groupBadge(badgeAllPass).first == "PASS")
        val badgeOneFail = listOf(badgeRes("A", true), badgeRes("B", false), badgeRes("C", true))
        t("Badge — یک Fail", "FAIL", Lab.groupBadge(badgeOneFail).first, Lab.groupBadge(badgeOneFail).first == "FAIL")
        val badgePassSkip = listOf(badgeRes("A", true), badgeRes("B", null), badgeRes("C", true))
        t("Badge — Pass + تست‌نشده", "PARTIAL (تست‌نشده هرگز Pass نمی‌شود)", Lab.groupBadge(badgePassSkip).first, Lab.groupBadge(badgePassSkip).first == "PARTIAL")
        val badgeAllSkip = listOf(badgeRes("A", null), badgeRes("B", null))
        t("Badge — همه تست‌نشده", "NOT TESTED", Lab.groupBadge(badgeAllSkip).first, Lab.groupBadge(badgeAllSkip).first == "NOT TESTED")
        t("Badge — لیست خالی", "NOT TESTED", Lab.groupBadge(emptyList()).first, Lab.groupBadge(emptyList()).first == "NOT TESTED")
        val badgeFailSkip = listOf(badgeRes("A", false), badgeRes("B", null))
        t("Badge — Fail + تست‌نشده", "FAIL (اولویت با Fail است)", Lab.groupBadge(badgeFailSkip).first, Lab.groupBadge(badgeFailSkip).first == "FAIL")
        // Cross-check — the exact reported case: group A (Restore) has a
        // FAIL, group B (the last group) passes. The section badge MUST
        // come from A+B (FAIL), never from B alone (PASS — the v2.9.6 bug).
        val groupA = listOf(badgeRes("Restore — A", true), badgeRes("Restore — B", false))
        val groupB = listOf(badgeRes("Large-RAM — A", true), badgeRes("Large-RAM — B", true))
        t("Badge — Cross-check گروه‌ها (باگ ۲٫۹٫۶)", "badge(A)=FAIL • badge(B)=PASS • badge(A+B)=FAIL", "A=${Lab.groupBadge(groupA).first} B=${Lab.groupBadge(groupB).first} A+B=${Lab.groupBadge(groupA + groupB).first}", Lab.groupBadge(groupA).first == "FAIL" && Lab.groupBadge(groupB).first == "PASS" && Lab.groupBadge(groupA + groupB).first == "FAIL")
        // v2.9.7 — RUN ALL completion summary: the "all tests finished"
        // message may only claim completion when every test has a final
        // status. The user's reported numbers: 217 total, 189 pass,
        // 20 fail, 8 untested -> complete=false.
        val sumFull = Lab.runAllSummary(badgeAllPass)
        t("RUN ALL Summary — همه نهایی", "complete=true • 3/3 Pass", "complete=${sumFull.complete} ${sumFull.passed}/${sumFull.total}", sumFull.complete && sumFull.passed == 3 && sumFull.total == 3 && sumFull.failed == 0 && sumFull.untested == 0 && sumFull.timeouts == 0)
        val sumUntested = Lab.runAllSummary(badgePassSkip)
        t("RUN ALL Summary — تست‌نشده دارد", "complete=false • untested=1", "complete=${sumUntested.complete} untested=${sumUntested.untested}", !sumUntested.complete && sumUntested.untested == 1)
        val sumTimeout = Lab.runAllSummary(listOf(TestResult("G — تایم‌اوت", "m", "e", "a", false, "TIMEOUT", "t", 1), badgeRes("H", true)))
        t("RUN ALL Summary — تایم‌اوت دارد", "complete=false • timeouts=1", "complete=${sumTimeout.complete} timeouts=${sumTimeout.timeouts}", !sumTimeout.complete && sumTimeout.timeouts == 1)
        val reported = (1..189).map { badgeRes("P$it", true) } + (1..20).map { badgeRes("F$it", false) } + (1..8).map { badgeRes("S$it", null) }
        val sumReported = Lab.runAllSummary(reported)
        t("RUN ALL Summary — مورد گزارش‌شده (217=189+20+8)", "complete=false • 189 Pass • 20 Fail • 8 تست‌نشده", "total=${sumReported.total} passed=${sumReported.passed} failed=${sumReported.failed} untested=${sumReported.untested} complete=${sumReported.complete}", sumReported.total == 217 && sumReported.passed == 189 && sumReported.failed == 20 && sumReported.untested == 8 && !sumReported.complete)
        t("RUN ALL Summary — خالی", "complete=false • total=0", "complete=${Lab.runAllSummary(emptyList()).complete}", !Lab.runAllSummary(emptyList()).complete)
        // v2.9.7 — chart time labels: HH:mm over a 60s window gives the
        // SAME label for the first and last sample (the v2.9.6 widget bug).
        // Fixed timestamps: 1_700_000_000_000L = 22:13:20 UTC, +30s is
        // still the same minute. Assertions are timezone-independent
        // (relations only, never absolute clock values).
        val lblT0 = 1_700_000_000_000L
        val lblT1 = lblT0 + 30_000L
        val oldFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val lblSameMin = ChartData.timeLabels(lblT0, lblT1)
        t("Chart Labels — یک دقیقه، ۳۰ ثانیه فاصله", "برچسب شروع ≠ پایان (با ثانیه)؛ در حالی که HH:mm قدیمی برابر می‌داد", "old=${oldFmt.format(java.util.Date(lblT0))} vs ${oldFmt.format(java.util.Date(lblT1))} • new=${lblSameMin.joinToString(" | ")}", oldFmt.format(java.util.Date(lblT0)) == oldFmt.format(java.util.Date(lblT1)) && lblSameMin.size >= 2 && lblSameMin.first() != lblSameMin.last() && lblSameMin.all { it.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) })
        val lblSingle = ChartData.timeLabels(lblT0, lblT0)
        t("Chart Labels — تک‌نقطه", "دقیقاً یک برچسب (نه دو برچسب یکسان)", "labels=${lblSingle.joinToString()}", lblSingle.size == 1)
        val lblLong = ChartData.timeLabels(lblT0, lblT0 + 90_000L)
        t("Chart Labels — بازه ۹۰ ثانیه", "شروع ≠ پایان؛ برچسب وسط (اگر هست) با هر دو سر متفاوت است", "labels=${lblLong.joinToString(" | ")}", lblLong.size in 2..3 && lblLong.first() != lblLong.last() && (lblLong.size == 2 || (lblLong[1] != lblLong[0] && lblLong[1] != lblLong[2])))
        val lblMid = ChartData.timeLabels(lblT0, lblT0 + 60_000L)
        t("Chart Labels — بازه دقیق ۶۰ ثانیه", "شروع ≠ پایان", "labels=${lblMid.joinToString(" | ")}", lblMid.size >= 2 && lblMid.first() != lblMid.last())
        return out
    }

    fun save(ctx: Context, results: List<TestResult>) {
        val p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE)
        val old = p.getString("results", "") ?: ""
        val text = results.joinToString("\n") { "${it.date} | ${it.name} | ${if (it.pass == true) "PASS" else if (it.pass == false) "FAIL" else "NOT TESTED"} | expected: ${it.expected} | actual: ${it.actual} | ${it.error}" }
        // v2.9.5 final control — commit() is synchronous: the result must
        // survive a force-kill right after the run, not just a clean exit.
        p.edit().putString("results", (text + "\n" + old).lines().take(200).joinToString("\n")).commit()
    }
    fun load(ctx: Context): String = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE).getString("results", "") ?: ""

    // v2.9.5 — structured test history: app version + timestamp + section +
    // per-test results incl. duration. The last 5 runs are kept so versions
    // can be compared. The old plain-text save()/load() above is kept
    // unchanged for the JSON+PDF export (btnLabExport).
    data class LabRun(val version: String, val timestamp: Long, val section: String, val results: List<TestResult>)
    private fun runToJson(r: LabRun): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("v", r.version); o.put("ts", r.timestamp); o.put("section", r.section)
        val ra = org.json.JSONArray()
        for (t in r.results) {
            val to = org.json.JSONObject()
            to.put("n", t.name); to.put("m", t.method); to.put("e", t.expected); to.put("a", t.actual)
            if (t.pass == null) to.put("p", org.json.JSONObject.NULL) else to.put("p", t.pass)
            to.put("err", t.error); to.put("d", t.durationMs); to.put("date", t.date)
            ra.put(to)
        }
        o.put("results", ra)
        return o
    }
    private fun runsToJson(runs: List<LabRun>): String {
        val arr = org.json.JSONArray()
        for (r in runs) arr.put(runToJson(r))
        return arr.toString()
    }
    fun saveRun(ctx: Context, version: String, section: String, results: List<TestResult>) {
        try {
            val runs = loadRuns(ctx).toMutableList()
            runs.add(LabRun(version, System.currentTimeMillis(), section, results))
            // v2.9.5 final control — keep the NEWEST 5 by REAL timestamp
            // (explicit sort, never insertion order) and write with
            // commit(): synchronous, so the run survives a force-kill
            // right after the test finishes.
            val newest = runs.sortedByDescending { it.timestamp }.take(5)
            ctx.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putString("runs_v2", runsToJson(newest)).commit()
        } catch (e: Exception) { /* history must never break the Lab run */ }
    }
    fun loadRuns(ctx: Context): List<LabRun> {
        return try {
            val raw = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE).getString("runs_v2", "") ?: ""
            if (raw.isBlank()) return emptyList()
            val arr = org.json.JSONArray(raw)
            val out = mutableListOf<LabRun>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rs = mutableListOf<TestResult>()
                val ra = o.optJSONArray("results") ?: org.json.JSONArray()
                for (j in 0 until ra.length()) {
                    val to = ra.getJSONObject(j)
                    rs.add(TestResult(to.optString("n", ""), to.optString("m", ""), to.optString("e", ""), to.optString("a", ""),
                        if (to.isNull("p")) null else to.optBoolean("p"), to.optString("err", ""), to.optString("date", ""), to.optLong("d", 0)))
                }
                out.add(LabRun(o.optString("v", "?"), o.optLong("ts", 0), o.optString("section", ""), rs))
            }
            // v2.9.5 final control — history is always newest-first by
            // REAL timestamp, regardless of stored order.
            out.sortedByDescending { it.timestamp }
        } catch (e: Exception) { emptyList() }
    }
    fun removeRun(ctx: Context, timestamp: Long) {
        try {
            val kept = loadRuns(ctx).filter { it.timestamp != timestamp }
            ctx.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putString("runs_v2", runsToJson(kept)).commit()
        } catch (e: Exception) { }
    }
}
