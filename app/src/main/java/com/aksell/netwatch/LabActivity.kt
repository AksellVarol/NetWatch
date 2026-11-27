package com.aksell.netwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class LabActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var tvSummary: TextView
    private val allResults = mutableListOf<TestResult>()
    // Displayed results with their section — a test name can exist in two
    // sections (e.g. "Network Simulation" in Unit and in Simulation), so
    // the section is stored with each result for the Details re-run.
    private val displayed = mutableListOf<Pair<String, TestResult>>()
    // Time of the last recorded test result (section thread only). timed()
    // stamps each result with the time since the previous one — each test's
    // work happens right before its result is recorded, so the duration is
    // attributed to the test by name.
    private val lastMarkMs = AtomicLong(0L)

    private fun timed(name: String, method: String, expected: String, actual: String, pass: Boolean?, err: String = ""): TestResult {
        val nowMs = System.currentTimeMillis()
        val prev = lastMarkMs.getAndSet(nowMs)
        val d = if (prev == 0L) 0 else nowMs - prev
        return TestResult(name, method, expected, actual, pass, err, History.timeStr(nowMs), d)
    }

    // v2.9.5 — cleanup safety net for LAB test rows. Only rows that are
    // identifiable as LAB test data are deleted (source "lab-6000-test",
    // impossible ping ms=50000.0, impossible loss>=1000.0). Real user rows
    // are never touched. Idempotent: safe to call after a timeout AND at
    // the start of a Unit run (removes leftovers of a crashed run).
    private fun cleanupLabRows() {
        try {
            History.withGate(this) {
                val dbC = HistoryDb.getDb(this)
                dbC.delete("samples", "source=?", arrayOf("lab-6000-test"))
                dbC.delete("ping_points", "ms=?", arrayOf("50000.0"))
                dbC.delete("loss_points", "loss>=?", arrayOf("1000.0"))
            }
        } catch (e: Exception) { /* cleanup must never break the Lab run */ }
    }

    private fun hasNetwork(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }

    private fun fetchPublicIp(): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("https://api.ipify.org?format=json").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            org.json.JSONObject(body).optString("ip", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null } finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }

    private fun appVersion(): String {
        return try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }

    private fun setBadge(v: TextView, text: String, colorHex: String) {
        v.text = text
        v.setTextColor(Color.parseColor(colorHex))
    }
    // v2.9.7 — the badge rule lives in Lab.groupBadge (pure, JVM-tested):
    // it is computed from the section's COMPLETE results, never from one
    // group's results alone (the v2.9.6 RUN ALL bug).
    private fun badgeFor(rs: List<TestResult>): Pair<String, String> = Lab.groupBadge(rs)
    private fun showResults(rs: List<TestResult>, section: String) {
        Lab.save(this, rs)
        Lab.saveRun(this, appVersion(), section, rs)
        displayed.clear()
        displayed.addAll(rs.map { section to it })
        renderDisplayed()
    }
    // Render without saving — used by RUN ALL so history is saved once at the end.
    private fun renderOnly(rs: List<TestResult>, section: String) {
        displayed.addAll(rs.map { section to it })
        renderDisplayed()
    }

    // v2.9.5 — results are grouped (failed first, then passed, then not
    // tested) so failures are visible at a glance, and every result block
    // is tappable: tapping opens its Details dialog (status, duration,
    // input, expected, actual, exact error, last-run time) with a
    // per-test re-run button.
    private inner class TestSpan(val section: String, val result: TestResult) : ClickableSpan() {
        override fun onClick(widget: View) { showDetails(section, result) }
        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
            ds.color = tv.currentTextColor
        }
    }

    private fun renderDisplayed() {
        val sb = SpannableStringBuilder()
        val fail = displayed.filter { it.second.pass == false }
        val pass = displayed.filter { it.second.pass == true }
        val skip = displayed.filter { it.second.pass == null }
        if (displayed.isNotEmpty()) {
            sb.append("❌ ناموفق (${fail.size}) • ✅ موفق (${pass.size}) • ⏸ تست‌نشده (${skip.size}) — روی هر نتیجه بزن تا جزئیات آن را ببینی.\n\n")
        }
        fun appendGroup(title: String, list: List<Pair<String, TestResult>>) {
            if (list.isEmpty()) return
            sb.append(title).append("\n")
            for ((section, r) in list) {
                val start = sb.length
                sb.append("${if (r.pass == true) "✅ PASS" else if (r.pass == false) "❌ FAIL" else "⏸ NOT TESTED"} — ${r.name}  •  ${r.durationMs}ms\nروش: ${r.method}\nمورد انتظار: ${r.expected}\nواقعی: ${r.actual}${if (r.error.isNotBlank()) "\nخطا: ${r.error}" else ""}\n")
                val end = sb.length
                sb.append("\n")
                sb.setSpan(TestSpan(section, r), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        appendGroup("── ناموفق ──", fail)
        appendGroup("── موفق ──", pass)
        appendGroup("── تست‌نشده ──", skip)
        if (displayed.isNotEmpty()) {
            sb.append("— تاریخچه تست‌ها —\n").append(Lab.load(this))
        }
        tv.text = sb
        updateSummary()
    }

    private fun showDetails(section: String, result: TestResult) {
        val status = if (result.pass == true) "✅ PASS" else if (result.pass == false) "❌ FAIL" else "⏸ NOT TESTED / SKIP"
        val msg = "نام: ${result.name}\nوضعیت: $status\nمدت اجرا: ${result.durationMs} میلی‌ثانیه\nورودی: ${result.method}\nخروجی مورد انتظار: ${result.expected}\nخروجی واقعی: ${result.actual}\nعلت دقیق خطا: ${if (result.error.isBlank()) "—" else result.error}\nآخرین اجرا: ${result.date}\nبخش: $section"
        AlertDialog.Builder(this)
            .setTitle("جزئیات تست")
            .setMessage(msg)
            .setPositiveButton("اجرای مجدد") { _, _ -> rerunSingle(section, result.name) }
            .setNegativeButton("بستن", null)
            .show()
    }

    // v2.9.5 final control — independent re-run: the section is re-run
    // (tests share fixtures inside their section functions — the re-run is
    // real, not faked; grouped sections re-run their groups in order until
    // the group containing the wanted test finishes, later groups are NOT
    // re-run), but ONLY the requested test is replaced
    // in `displayed` and in history. Every other test keeps its last
    // full-run value (status, duration, actual), and stored earlier run
    // entries are never rewritten or mutated: the new history entry is
    // the merged section list, older entries stay byte-identical. If the
    // section times out and no fresh result exists, displayed results and
    // history are left completely untouched.
    private fun rerunSingle(sectionName: String, testName: String) {
        val def = sections.find { it.name == sectionName } ?: return
        setBadge(findViewById(def.badgeId), "RUNNING", "#F59E0B")
        tv.text = "اجرای مجدد «$testName» در بخش $sectionName…"
        NetExecutor.submit {
            // v2.9.6 — grouped sections re-run their groups IN ORDER until
            // the group containing the requested test finishes; later groups
            // are NOT re-run. Only the fresh test replaces the old one
            // (mergeRerun below) — the rest is untouched.
            var fresh: TestResult? = null
            val groups = def.groups?.invoke()
            if (groups != null) {
                for (g in groups) {
                    val grs = runGroup(g)
                    if (def.name == "Unit Tests") cleanupLabRows()
                    fresh = grs.find { it.name == testName }
                    if (fresh != null) break
                }
            } else {
                val rs = runTimed(def.name, def.maxSec, def.fn)
                if (def.name == "Unit Tests") cleanupLabRows()
                fresh = rs.find { it.name == testName }
            }
            NetExecutor.runOnMain {
                if (fresh == null) {
                    renderDisplayed()
                    Toast.makeText(this, "تست «$testName» در اجرای جدید پیدا نشد (ممکن است بخش تایم‌اوت خورده باشد) — نتایج قبلی دست نخورده باقی ماند", Toast.LENGTH_LONG).show()
                    return@runOnMain
                }
                val sectionResults = displayed.filter { it.first == def.name }.map { it.second }
                val merged = Lab.mergeRerun(sectionResults, fresh)
                // Replace only this test in `displayed`, keeping its
                // position and every other result exactly as it was.
                val idx = displayed.indexOfFirst { it.first == def.name && it.second.name == testName }
                if (idx >= 0) displayed[idx] = def.name to fresh else displayed.add(def.name to fresh)
                // History: append only the fresh test line to the plain
                // text, and save the MERGED section list as the new run
                // entry — older entries are never touched.
                Lab.save(this, listOf(fresh))
                Lab.saveRun(this, appVersion(), def.name, merged)
                renderDisplayed()
                val b = badgeFor(merged); setBadge(findViewById(def.badgeId), b.first, b.second)
                Toast.makeText(this, "${fresh.name}: ${if (fresh.pass == true) "PASS" else if (fresh.pass == false) "FAIL" else "NOT TESTED"} (${fresh.durationMs}ms)", Toast.LENGTH_LONG).show()
                showDetails(def.name, fresh)
            }
        }
    }

    // v2.9.5 — summary header: success %, PASS/FAIL/SKIP counts, total
    // test duration, last run info + the previous saved run for comparison.
    private fun updateSummary() {
        val total = displayed.size
        val p = displayed.count { it.second.pass == true }
        val f = displayed.count { it.second.pass == false }
        val s = displayed.count { it.second.pass == null }
        val totalMs = displayed.sumOf { it.second.durationMs }
        val pct = if (total == 0) 0 else p * 100 / total
        val runs = Lab.loadRuns(this)
        val last = runs.firstOrNull()
        val prev = runs.drop(1).firstOrNull()
        tvSummary.text = if (total == 0 && last == null) {
            "هنوز تستی اجرا نشده است — یک بخش یا «Run All Tests» را اجرا کن. روی هر نتیجه بزن تا جزئیات آن را ببینی."
        } else if (total == 0) {
            val lp = last!!.results.count { it.pass == true }
            "آخرین اجرای ذخیره‌شده: ${last.section} • نسخه ${last.version} • $lp/${last.results.size} Pass • ${History.timeStr(last.timestamp)}"
        } else {
            val secs = totalMs / 1000.0
            val base = "موفقیت: $pct٪ • ✅ $p • ❌ $f • ⏸ $s • مدت کل تست‌ها: ${"%.1f".format(secs)} ثانیه"
            if (prev != null) {
                val pp = prev.results.count { it.pass == true }
                "$base\nاجرای قبلی (${prev.section} • نسخه ${prev.version}): $pp/${prev.results.size} Pass"
            } else base
        }
    }

    // v2.9.5 — one registry for all sections: the same caps are used by the
    // single-section buttons, by RUN ALL and by per-test re-run, so a cap
    // can never drift between the button and RUN ALL again (the v2.9.4 bug:
    // Unit was 240s on its button but 60s in RUN ALL).
    // v2.9.6 — a section may be split into independent groups, each with
    // its own budget (GroupDef below). maxSec is then the SUM of the group
    // caps (worst case), used only for progress text — never a single
    // deadline that discards a whole section's results on timeout.
    private data class SectionDef(val badgeId: Int, val name: String, val maxSec: Int, val fn: () -> List<TestResult>, val groups: (() -> List<GroupDef>)? = null)
    private val sections: List<SectionDef> by lazy {
        listOf(
            SectionDef(R.id.tvBadgeUnit, "Unit Tests", 720, ::unitSection, ::unitGroups), // ChatGPT review round 3, item 4: +60s for the Bridge (device) group; v2.9.9: +90s for the Gaming lifecycle group
            SectionDef(R.id.tvBadgeIntegration, "Integration Tests", 375, ::integrationSection, ::integrationGroups),
            SectionDef(R.id.tvBadgeCross, "Cross Validation", 90, ::crossSection),
            SectionDef(R.id.tvBadgeStress, "Stress Tests", 75, ::stressSection),
            SectionDef(R.id.tvBadgeSim, "Network Simulation", 10, ::simSection),
            SectionDef(R.id.tvBadgeStats, "Statistical Validation", 10, ::statsSection)
        )
    }

    // ---- One section = one synchronous function returning its results. ----
    // Row taps and RUN ALL both use these, so a section can never be skipped.


    // ---- v2.9.6: per-group runner (Unit + Integration) ----
    // One group = one independent set of tests with its own budget, run on
    // its own thread. Tests append to `sink` AS THEY FINISH, so on timeout
    // the completed results are KEPT and only the unfinished remainder is
    // lost; a TIMEOUT row (pass=false, never PASS) names the group and the
    // last completed test — the stall is the next one. A timeout/failure in
    // one group never stops the other groups. Caps are NOT inflated and no
    // threshold is changed to hide an error.
    private data class GroupDef(val name: String, val maxSec: Int, val fn: (MutableList<TestResult>) -> Unit)

    private fun runGroup(group: GroupDef): List<TestResult> {
        lastMarkMs.set(System.currentTimeMillis())
        val sink = Collections.synchronizedList(mutableListOf<TestResult>())
        val t0 = System.currentTimeMillis()
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "netwatch-lab-group").apply { isDaemon = true } }
        val future = exec.submit { group.fn(sink) }
        return try {
            future.get(group.maxSec.toLong(), TimeUnit.SECONDS)
            synchronized(sink) { sink.toList() }
        } catch (e: TimeoutException) {
            future.cancel(true)
            val done = synchronized(sink) { sink.toList() }
            val last = done.lastOrNull()?.name ?: "—"
            done + TestResult("${group.name} — تایم‌اوت", "سقف گروهی ${group.maxSec} ثانیه", "گروه باید در زمان مقرر تمام می‌شد",
                "گروه پس از ${group.maxSec} ثانیه متوقف شد؛ ${done.size} تست کامل شد، آخرین تست کامل: «$last» — تست بعدی (متوقف‌شده) همان تست کند/گیرکرده است. تایم‌اوت صادقانه ثبت شد، نه Pass.",
                false, "TIMEOUT", History.timeStr(System.currentTimeMillis()), group.maxSec * 1000L)
        } catch (e: Exception) {
            val done = synchronized(sink) { sink.toList() }
            done + TestResult("خطا در اجرای گروه ${group.name}", "اجرای گروه", "بدون Crash", "خطا: ${e.message}", false, "", History.timeStr(System.currentTimeMillis()), System.currentTimeMillis() - t0)
        } finally {
            exec.shutdownNow()
        }
    }

    // Runs a whole section: grouped sections run group by group (each with
    // its own cap); other sections keep the single runTimed deadline.
    private fun runSectionAll(def: SectionDef): List<TestResult> {
        val groups = def.groups?.invoke() ?: return runTimed(def.name, def.maxSec, def.fn)
        val out = mutableListOf<TestResult>()
        for (g in groups) out.addAll(runGroup(g))
        return out
    }

    // Shared state between Integration groups (set by the Probes / TLS /
    // DNS-kinds groups, read by the Traceroute classification row). Groups
    // always run in list order, so a later group sees the earlier groups'
    // values; reset at the start of every Integration run.
    private var integResults: List<ProbeResult> = emptyList()
    private var integDnsOk: Boolean? = null
    private var integFiltering: String = ""
    private var integIntlPct: Int = 0
    private var integDomPct: Int = 0

    // Integration rows without the section-local add() helper (group
    // functions are members, so they use this instead).
    private fun iadd(rs: MutableList<TestResult>, name: String, method: String, expected: String, actual: String, pass: Boolean?, err: String = "") {
        rs.add(timed(name, method, expected, actual, pass, err))
    }

    private fun unitGroups(): List<GroupDef> = listOf(
        GroupDef("Unit — Pure", 60, ::unitPureGroup),
        GroupDef("Unit — History", 90, ::unitHistoryGroup),
        GroupDef("Unit — Restore-negative", 120, ::unitRestoreNegativeGroup),
        GroupDef("Unit — Streaming+Recovery", 120, ::unitStreamingGroup),
        GroupDef("Unit — Large-RAM", 180, ::unitLargeRamGroup),
        GroupDef("Unit — Bridge (device)", 60, ::unitBridgeDeviceGroup),
        GroupDef("Unit — Gaming lifecycle", 90, ::unitGamingLifecycleGroup)
    )

    // v2.9.8 — LAB isolation: the Unit section writes/reads the LIVE
    // history DB (count tests, restore tests). While the MonitorService
    // runs, its 30s cycle inserts real rows mid-test (proven in v2.9.7:
    // 2 real samples displaced test rows, 6 real ping points, 2 real
    // loss rows) and its in-flight check can hold the DB when a restore
    // needs it quiescent. So the Unit section stops the service first
    // and restarts it after. The restart is guaranteed (finally in
    // unitSection + onDestroy) — a LAB run never leaves the monitor
    // stopped. Only the Unit section pauses the monitor; Integration
    // runs with the service back up (its Background Service row reports
    // the real state again).
    @Volatile private var labPausedMonitor = false

    private fun pauseMonitorForLab() {
        if (!MonitorService.running) return
        try { stopService(Intent(this, MonitorService::class.java)) } catch (_: Exception) { }
        labPausedMonitor = true
        // Wait for any in-flight check / live ping to finish or be
        // cancelled (onDestroy cancels both). Cap the wait at 5s; a
        // check's DB write is short, and a cancelled one never writes.
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 5000) {
            if (!MonitoringEngine.isCheckRunning() && !MonitoringEngine.isLiveRunning()) break
            try { Thread.sleep(100) } catch (_: Exception) { break }
        }
        try { Thread.sleep(200) } catch (_: Exception) { }
    }

    private fun resumeMonitorAfterLab() {
        if (!labPausedMonitor) return
        labPausedMonitor = false
        // Restart exactly like MainActivity does (foreground service).
        // From a background thread, post to the main thread and wait, so
        // the service is really up before the next section starts.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { startForegroundService(Intent(this, MonitorService::class.java)) } catch (_: Exception) { }
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        NetExecutor.runOnMain {
            try { startForegroundService(Intent(this, MonitorService::class.java)) } catch (_: Exception) { }
            latch.countDown()
        }
        try { latch.await(5, TimeUnit.SECONDS) } catch (_: Exception) { }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 2000) {
            if (MonitorService.running) break
            try { Thread.sleep(50) } catch (_: Exception) { break }
        }
    }

    private fun unitSection(): List<TestResult> {
        pauseMonitorForLab()
        try {
            // v2.9.5 — self-healing: remove leftovers of a previous timed-out
            // run before taking any baseline counts.
            cleanupLabRows()
            val out = mutableListOf<TestResult>()
            for (g in unitGroups()) out.addAll(runGroup(g))
            return out
        } finally {
            resumeMonitorAfterLab()
        }
    }

    // Pure: Lab.unitTests() is called ONCE (v2.9.5 called it three times
    // for the regression row). Determinism is now a cheap check: the same
    // pure functions executed twice must give the same result.
    private fun unitPureGroup(rs: MutableList<TestResult>) {
        rs.addAll(Lab.unitTests())
        val now = History.timeStr(System.currentTimeMillis())
        val detA = Lab.computeStats(listOf(42.0, 48.0, 50.0, 55.0, 72.0), 5)
        val detB = Lab.computeStats(listOf(42.0, 48.0, 50.0, 55.0, 72.0), 5)
        val detPr = PingResult("test", 20, 20, 0.0, 40.0, 40.0, 45.0, 40.0, 41.0, 42.0, 44.0, 3.0, 0, 0, listOf(40.0))
        val detScA = Gaming.score(detPr, Gaming.Bufferbloat(40.0, 45.0, 5.0, "A", 100))
        val detScB = Gaming.score(detPr, Gaming.Bufferbloat(40.0, 45.0, 5.0, "A", 100))
        rs.add(timed("Regression — تکرار محاسبات خالص", "computeStats + Gaming.score هر کدام دو بار با همان ورودی", "نتیجه دقیقاً یکسان", if (detA == detB && detScA.total == detScB.total) "یکسان ✅" else "متفاوت ❌", detA == detB && detScA.total == detScB.total))
        val sim = Lab.computeStats(listOf(40.0, 45.0, 200.0, 42.0, 44.0), 10)
        rs.add(timed("Network Simulation — شبکه شبیه‌سازی‌شده", "SIMULATION: داده مصنوعی با Loss 50٪ و Spike 200ms", "Loss 50٪، Spike شناسایی شود", "Loss ${sim.lossPct}٪، Max ${sim.max}ms — شبیه‌سازی، نه شبکه واقعی", sim.lossPct == 50.0, "SIMULATION"))
        // Group-runner self-test (A6, on-device): a synthetic group with
        // two fast tests + one that sleeps 5s, cap 2s → the two finished
        // results are KEPT plus a TIMEOUT row with pass=false. lastMarkMs
        // is saved/restored so the outer group's durations stay honest.
        run {
            val savedMark = lastMarkMs.get()
            val synth = GroupDef("GroupRunner — synthetic", 2) { sink ->
                sink.add(timed("Synthetic — fast 1", "ورودی ثابت", "PASS", "PASS", true))
                sink.add(timed("Synthetic — fast 2", "ورودی ثابت", "PASS", "PASS", true))
                Thread.sleep(5000)
                sink.add(timed("Synthetic — slow (نباید برسد)", "ورودی ثابت", "PASS", "PASS", true))
            }
            val got = runGroup(synth)
            lastMarkMs.set(savedMark)
            val runnerOk = got.size == 3 && got[0].pass == true && got[1].pass == true &&
                got[2].pass == false && got[2].error == "TIMEOUT" && got[2].name.contains("تایم‌اوت")
            rs.add(timed("Group Runner — نتایج جزئی + TIMEOUT", "گروه مصنوعی: ۲ تست سریع + ۱ تست ۵ ثانیه‌ای با سقف ۲ ثانیه", "۲ نتیجه کامل حفظ شود + ردیف TIMEOUT با pass=false", "نتایج: ${got.size} • اول=${got.getOrNull(0)?.pass} دوم=${got.getOrNull(1)?.pass} • TIMEOUT: ${got.getOrNull(2)?.pass} (${got.getOrNull(2)?.error})", runnerOk))
        }
        // v2.6 on-device: widget and app read the same source — lastPing must be
        // the newest successful ping point, the one the widget tile shows.
        val lp = History.lastPing(this)
        val pts = History.pingPoints(this)
        val lastOkPt = pts.lastOrNull { it.second >= 0 }
        val widgetOk = if (lp == null) lastOkPt == null else lastOkPt != null && lp.first == lastOkPt.first && lp.second == lastOkPt.second
        rs.add(timed("Widget — سازگاری داده با اپ", "lastPing == آخرین نقطه موفق pingPoints (همان منبع داده ویجت و اپ)", "یکسان", "lastPing=${lp?.second}ms • آخرین نقطه=${lastOkPt?.second}ms", widgetOk))
        val badPing = NetworkEngine.ping("invalid.invalid", 2)
        val badTcp = NetworkEngine.tcp("127.0.0.1", 1)
        rs.add(timed("Error Handling — هاست نامعتبر", "ping به invalid.invalid", "Fail بدون Crash، Loss 100٪", "Loss ${badPing.lossPct}٪، received ${badPing.received}", badPing.received == 0 && badPing.lossPct == 100.0))
        rs.add(timed("Error Handling — پورت بسته", "TCP به 127.0.0.1:1", "Fail بدون Crash", if (badTcp.ok) "باز (غیرمنتظره)" else "بسته (${badTcp.note})", !badTcp.ok))
        // v2.9.5 — structured history round-trip (on-device): saveRun →
        // loadRuns must preserve version, section, durations and statuses;
        // the test run is removed afterwards so history is not polluted.
        // v2.9.5 final control — history must be sorted by REAL timestamp,
        // newest first: a second saved run must come before the first.
        run {
            val fake = listOf(
                TestResult("History Test — نمونه", "ورودی ثابت", "PASS", "PASS", true, "", now, 123),
                TestResult("History Test — نمونه ناموفق", "ورودی ثابت", "FAIL", "FAIL", false, "دلیل ساختگی", now, 45)
            )
            Lab.saveRun(this, "2.9.5-test", "Unit Tests", fake)
            val top = Lab.loadRuns(this).firstOrNull()
            val okHist = top != null && top.version == "2.9.5-test" && top.section == "Unit Tests" && top.results.size == 2 &&
                top.results[0].durationMs == 123L && top.results[1].pass == false && top.results[1].error == "دلیل ساختگی"
            Lab.saveRun(this, "2.9.5-test", "Unit Tests", fake)
            val runs2 = Lab.loadRuns(this)
            val sortedOk = runs2.size >= 2 && runs2[0].timestamp >= runs2[1].timestamp && runs2[0].version == "2.9.5-test"
            for (r in Lab.loadRuns(this).filter { it.version == "2.9.5-test" }) Lab.removeRun(this, r.timestamp)
            val cleanedHist = Lab.loadRuns(this).none { it.version == "2.9.5-test" }
            rs.add(timed("History — ذخیره و بازیابی تاریخچه ساخت‌یافته (v2.9.5)", "saveRun با ۲ نتیجه ساختگی → loadRuns → بررسی نسخه/بخش/مدت/وضعیت + ترتیب زمانی (جدیدترین اول) → حذف اجراهای تست", "نسخه و بخش و مدت‌ها حفظ شوند + جدیدترین اول + اجراهای تست حذف شوند", "بازیابی: ${if (okHist) "درست ✅" else "خراب ❌"} • مرتب زمانی: $sortedOk • تاریخچه تمیز: $cleanedHist", okHist && sortedOk && cleanedHist))
        }
        // v2.9.5 final control — independent re-run merge (on-device):
        // replacing one test must leave every other test byte-identical
        // (same objects, same order) — in `displayed` AND in history.
        run {
            val base = listOf(
                TestResult("Merge Test — A", "ورودی ثابت", "PASS", "a1", true, "", now, 10),
                TestResult("Merge Test — B", "ورودی ثابت", "PASS", "b1", true, "", now, 20),
                TestResult("Merge Test — C", "ورودی ثابت", "NOT TESTED", "c1", null, "", now, 30)
            )
            val freshB = TestResult("Merge Test — B", "ورودی ثابت", "FAIL", "b2", false, "دلیل تازه", now, 99)
            val merged = Lab.mergeRerun(base, freshB)
            val othersIdentical = merged.size == 3 && merged[0] == base[0] && merged[2] == base[2] && merged[1] == freshB
            rs.add(timed("Merge Re-run — بقیه تست‌ها دست نخورده (v2.9.5)", "۳ نتیجه ثابت → جایگزینی فقط تست میانی با نتیجه تازه → دو تست دیگر باید دقیقاً مثل قبل باشند", "A و C دقیقاً مثل قبل، B تازه", "یکسان: $othersIdentical • B=${merged[1].actual}", othersIdentical))
        }
    }

    // History: round-trip + range + 6000-row tests. Each group builds its
    // own fixtures (buildBackupJson when needed) — no group depends on
    // another group's rows.
    private fun unitHistoryGroup(rs: MutableList<TestResult>) {
        // v2.6 on-device: History round-trip — addSample writes 6 fields,
        // samples()/samplesFull() must return the exact same rows (the v2.5 bug
        // returned nothing here). Test rows are removed afterwards.
        val rt0 = System.currentTimeMillis()
        History.addSample(this, 38, 40, 120.5, 8.2, 0.0, "lab-test")
        History.addSample(this, 35, 40, 200.0, 15.0, 2.5, "lab-test")
        History.addSample(this, 0, 40, -1.0, -1.0, -1.0, "lab-test")
        val afterRt = History.samplesFull(this)
        val added = afterRt.filter { it.time >= rt0 }
        val rtOk = added.size == 3 &&
            added[0].ok == 38 && added[0].total == 40 && added[0].avgMs == 120.5 && added[0].jitterMs == 8.2 && added[0].lossPct == 0.0 &&
            added[1].avgMs == 200.0 && added[1].jitterMs == 15.0 && added[1].lossPct == 2.5 &&
            added[2].avgMs == -1.0 && added[2].lossPct == -1.0
        val triplesOk = History.samples(this).size == afterRt.size
        // v2.9.5 — delete ONLY the test rows (source "lab-test"). The old
        // deleteSamplesSince removed every row with time >= rt0, including
        // real user rows recorded by the background service mid-test.
        History.withGate(this) { HistoryDb.getDb(this).delete("samples", "source=?", arrayOf("lab-test")) }
        val cleaned = History.withGate(this) { HistoryDb.getDb(this).rawQuery("SELECT COUNT(*) FROM samples WHERE source=?", arrayOf("lab-test")).use { c -> c.moveToFirst(); c.getLong(0) } } == 0L
        rs.add(timed("History Round-trip — ذخیره و بازیابی ۶ فیلدی", "۳ رکورد addSample → samples()/samplesFull() → مقایسه دقیق مقادیر → حذف ردیف‌های تست", "۳ رکورد، مقادیر دقیقاً برابر، samples() هم‌اندازه samplesFull()", "بازیابی ${added.size} رکورد • مقادیر ${if (rtOk) "دقیقاً برابر ✅" else "متفاوت ❌"} • samples() هم‌اندازه: $triplesOk • پاک‌سازی ردیف‌های تست: $cleaned", rtOk && triplesOk && cleaned))
        // v2.8 on-device: range filter with FIXED timestamps (the v2.7 bug
        // made "7 days" show 7 hours and "30 days" show 30 hours). Four rows
        // are inserted directly with explicit times; only the test rows are
        // counted, so the user's real samples cannot skew the result.
        val nowR = System.currentTimeMillis()
        val hMs = 3_600_000L; val dMs = 24 * hMs
        val rangeRows = listOf(nowR - 1 * hMs, nowR - 25 * hMs, nowR - 8 * dMs, nowR - 31 * dMs)
        // Direct DB access behind the gate (v2.9.4): no HistoryDb use
        // outside withGate, so a restore's Phase B can never close the
        // connection under this test.
        History.withGate(this) {
            val dbW = HistoryDb.getDb(this)
            dbW.beginTransaction()
            try {
                for (ts in rangeRows) {
                    val cv = android.content.ContentValues()
                    cv.put("time", ts); cv.put("ok", 40); cv.put("total", 40)
                    cv.put("avg_ms", 50.0); cv.put("jitter_ms", 5.0); cv.put("loss_pct", 0.0)
                    cv.put("source", "lab-range-test")
                    dbW.insert("samples", null, cv)
                }
                dbW.setTransactionSuccessful()
            } finally { dbW.endTransaction() }
        }
        val r24 = History.samplesInRange(this, History.HistoryRange.H24.cutoff(nowR)).count { it.time in rangeRows }
        val r7 = History.samplesInRange(this, History.HistoryRange.D7.cutoff(nowR)).count { it.time in rangeRows }
        val r30 = History.samplesInRange(this, History.HistoryRange.D30.cutoff(nowR)).count { it.time in rangeRows }
        History.withGate(this) {
            HistoryDb.getDb(this).delete("samples", "source=?", arrayOf("lab-range-test"))
        }
        rs.add(timed("History Range — فیلتر ۲۴ ساعت / ۷ روز / ۳۰ روز (روی دستگاه)", "۴ ردیف با زمان ثابت: ۱ ساعت، ۲۵ ساعت، ۸ روز، ۳۱ روز پیش → شمارش در هر بازه", "24h=1 • 7d=2 • 30d=3", "24h=$r24 7d=$r7 30d=$r30", r24 == 1 && r7 == 2 && r30 == 3))
        // v2.9 on-device: >5000 rows with FIXED timestamps (the v2.8 bug:
        // "time ASC LIMIT 5000" returned the OLDEST 5000, so ranged views and
        // backups lost every new row once the table passed 5000 rows).
        // Everything runs inside ONE transaction that is ROLLED BACK at the
        // end — the test rows never touch the user's real history.
        val nowR2 = System.currentTimeMillis()
        val sTimes = (0 until 6000).map { nowR2 - it * 60_000L }.toSet()
        val pTimes = (0 until 6000).map { nowR2 - it * 5_000L }.toSet()
        // v2.9.4: the restore now swaps the live DB FILE, so the old
        // rollback-sandbox transaction cannot hold the restore tests any
        // more (a restore would close the connection under it). The test
        // rows are inserted in ONE committed transaction and deleted
        // explicitly afterwards (by source / ms / loss value).
        val preSamp6000 = History.samplesFull(this).size
        val prePing6000 = History.pingPoints(this, limit = 0).size
        val preLoss6000 = History.lossPoints(this, limit = 0).size
        // ChatGPT final review, fix 3: old LAB rows must NOT count as
        // "real" rows — the 6000 stranded rows (source 'restore' + the
        // fixture fingerprint) sit in the window and inflated preReal, which
        // made the displacement bound vacuous (5000 - 6000 < 0 always
        // passes). Real rows and old-LAB rows are counted separately; the
        // bound allows for both, and the fixture rows themselves are now
        // counted by source, not by time alone (an old row can share a
        // fixture time). Loss needs no change: old loss rows (>= 1000.0)
        // are removed by cleanupLabRows() before the group runs, and the
        // Loss assertion is already an exact sequence.
        fun isOldLab(s: History.Sample): Boolean =
            s.source == "lab-6000-test" || s.source == "lab-test" || s.source == "lab-range-test" || s.source == "lab-source-test" ||
            (s.source == "restore" && s.ok == 40 && s.total == 40 && s.avgMs == 50.0 && s.jitterMs == 5.0 && s.lossPct == 0.0)
        // runall-fixes §3 — real rows already present in each window BEFORE
        // the fixture insert. Each can displace at most one fixture row from
        // a capped result, so the expected fixture counts below allow for
        // exactly that many displaced rows (measured, not guessed).
        val preReal7 = History.samplesFull(this).count { it.time >= History.HistoryRange.D7.cutoff(nowR2) && !isOldLab(it) }
        val preOldLab7 = History.samplesFull(this).count { it.time >= History.HistoryRange.D7.cutoff(nowR2) && isOldLab(it) }
        val preReal30 = History.samplesFull(this).count { it.time >= History.HistoryRange.D30.cutoff(nowR2) && !isOldLab(it) }
        val preOldLab30 = History.samplesFull(this).count { it.time >= History.HistoryRange.D30.cutoff(nowR2) && isOldLab(it) }
        // ChatGPT review round 2, item 3: the 24h expectation needs the
        // same displacement treatment as 7d/30d. With the monitor's default
        // 30s interval (History.intervalSec default 30), the 24h window can
        // hold ~2880 real samples + old-LAB rows + 1441 fixture rows — over
        // the 5000 cap — so fixture rows CAN be displaced from the capped
        // 24h list and `s24 == 1441` was not guaranteed (it passed at 22:03
        // only because that window was under the cap). The displacement
        // bound is the sound form, consistent with 7d/30d.
        val preReal24 = History.samplesFull(this).count { it.time >= History.HistoryRange.H24.cutoff(nowR2) && !isOldLab(it) }
        val preOldLab24 = History.samplesFull(this).count { it.time >= History.HistoryRange.H24.cutoff(nowR2) && isOldLab(it) }
        val preRealPing24 = History.pingPoints(this, limit = 0).count { it.first >= History.HistoryRange.H24.cutoff(nowR2) && it.second != 50000.0 }
        val preOldLabPing24 = History.pingPoints(this, limit = 0).count { it.first >= History.HistoryRange.H24.cutoff(nowR2) && it.second == 50000.0 }
        val dbT = HistoryDb.getDb(this)
        History.withGate(this) {
            dbT.beginTransaction()
            try {
                for (ts in sTimes) {
                    val cv = android.content.ContentValues()
                    cv.put("time", ts); cv.put("ok", 40); cv.put("total", 40)
                    cv.put("avg_ms", 50.0); cv.put("jitter_ms", 5.0); cv.put("loss_pct", 0.0)
                    cv.put("source", "lab-6000-test")
                    dbT.insert("samples", null, cv)
                }
                for (ts in pTimes) {
                    val cv = android.content.ContentValues()
                    // ChatGPT review round 3, item 3 — the fixture marker,
                    // documented against the real data structure:
                    // ping_points rows are (time, ms, ok). Real ms comes
                    // from Diagnostics.pingOnce (`ping -c 1 -W 1`, 3s
                    // command timeout), so a real measurement is < 3000 ms,
                    // and a failed ping stores ms=-1.0 (addPingFailed).
                    // 50000.0 (50s) is an order of magnitude beyond any
                    // possible real value, so ms==50000.0 identifies a
                    // fixture point exactly. (ok does NOT distinguish:
                    // the fixture sets ok=1 like a real success.)
                    // The cleanup below can delete exactly these rows.
                    cv.put("time", ts); cv.put("ms", 50000.0); cv.put("ok", 1)
                    dbT.insert("ping_points", null, cv)
                }
                // Loss values 1000..1099 are impossible in real data (loss is
                // 0..100), so the newest-60 check can identify test rows exactly.
                for (k in 0 until 100) {
                    val cv = android.content.ContentValues()
                    cv.put("time", nowR2 - k * 60_000L); cv.put("loss", 1000.0 + k)
                    dbT.insert("loss_points", null, cv)
                }
                dbT.setTransactionSuccessful()
            } finally { dbT.endTransaction() }
        }
        try {
            // 24h of 1-min samples = k=0..1440 (inclusive cutoff) = 1441 rows;
            // 7d and 30d hold all 6000 (6000 min = 4.2 days).
            // runall-fixes §3: the cap is 5000, so 7d/30d can never return
            // 6000 — the old expectation was impossible. The capped lists
            // must hold exactly 5000 rows, and the fixture rows among them
            // are 5000 minus the real rows already in the window (each real
            // row displaces at most one fixture row).
            val r24 = History.samplesInRange(this, History.HistoryRange.H24.cutoff(nowR2))
            val r7 = History.samplesInRange(this, History.HistoryRange.D7.cutoff(nowR2))
            val r30 = History.samplesInRange(this, History.HistoryRange.D30.cutoff(nowR2))
            val s24 = r24.count { it.time in sTimes && it.source == "lab-6000-test" }
            val s7 = r7.count { it.time in sTimes && it.source == "lab-6000-test" }
            val s30 = r30.count { it.time in sTimes && it.source == "lab-6000-test" }
            val s24HasNewest = r24.any { it.time == nowR2 && it.source == "lab-6000-test" }
            // ChatGPT review round 3, item 3: the 24h assertion must test
            // the 5000 cap itself, not only a fixture lower bound. The
            // 24h window's total is exactly measurable: preReal24 +
            // preOldLab24 rows were already there, plus exactly 1441 new
            // fixture rows in the window (i=0..1440; i=1441 falls before
            // the cutoff) — the monitor is paused and the group inserts
            // nothing else in between. So the capped 24h list must hold
            // exactly min(total, 5000) rows. (7d/30d already assert
            // r.size == 5000, and the 24h Ping list asserts
            // p24List.size == 5000 — the 24h ping window always exceeds
            // the cap: 24h of 5s points is 17,280 rows.)
            // ChatGPT review round 4, item 3 — verified: the pre-counts
            // come from samplesFull (uncapped, time ASC) at the same
            // nowR2, and the monitor is paused while the group runs, so
            // no sample row can be inserted between the pre-capture and
            // this assertion except the fixture itself. total24 is exact,
            // and the assertion is self-checking: a stray insert would
            // FAIL it (r24.size would exceed min(total, 5000)), never
            // false-PASS.
            val total24 = preReal24 + preOldLab24 + 1441
            val r24CapOk = r24.size == minOf(total24, 5000)
            rs.add(timed("History — بیش از ۵۰۰۰ رکورد (روی دستگاه)", "۶۰۰۰ نمونه با زمان ثابت (۱ دقیقه‌ای) → فیلتر ۲۴ ساعت / ۷ روز / ۳۰ روز", "24h=1441 • 7d/30d سقف 5000 • جدیدترین ردیف حاضر", "24h=$s24 7d=$s7/${r7.size} 30d=$s30/${r30.size} newest=$s24HasNewest • سقف 24h: ${r24.size}/$total24 • واقعی در پنجره: 24h=$preReal24 7d=$preReal7 30d=$preReal30 • LAB قدیمی: 24h=$preOldLab24 7d=$preOldLab7 30d=$preOldLab30", s24 >= 1441 - preReal24 - preOldLab24 && r24CapOk && r7.size == 5000 && s7 >= 5000 - preReal7 - preOldLab7 && r30.size == 5000 && s30 >= 5000 - preReal30 - preOldLab30 && s24HasNewest))
            // 24h of 5s ping points = 17,280 rows → the chart cap keeps the
            // NEWEST 5000; the backup path (limit=0) must return all 6000.
            // runall-fixes §3: same displacement rule as History — the
            // capped 24h list must hold exactly 5000 rows; the fixture rows
            // among them are 5000 minus the real rows already in the window.
            // ChatGPT review round 2, item 3: count fixture points by
            // identity (time AND ms=50000.0), not by time alone — a real
            // point recorded at a fixture time would otherwise be counted
            // as fixture. A real ping is never exactly 50000.0 ms.
            // (Diagnostics.pingOnce caps at 3s; failed pings store -1.0.)
            val p24List = History.pingPoints(this, History.HistoryRange.H24.cutoff(nowR2))
            val p24 = p24List.count { it.first in pTimes && it.second == 50000.0 }
            val p24HasNewest = p24List.any { it.first == nowR2 && it.second == 50000.0 }
            val pAll = History.pingPoints(this, limit = 0).count { it.first in pTimes && it.second == 50000.0 }
            rs.add(timed("Ping Points — بیش از ۵۰۰۰ رکورد (روی دستگاه)", "۶۰۰۰ نقطه پینگ (۵ ثانیه‌ای) → نمودار ۲۴ ساعته جدیدترین ۵۰۰۰ را دارد؛ مسیر بکاپ همه را دارد", "24h سقف 5000 • newest حاضر • backup=6000", "24h=$p24/${p24List.size} newest=$p24HasNewest backup=$pAll • واقعی در پنجره: $preRealPing24 • LAB قدیمی: $preOldLabPing24", p24List.size == 5000 && p24 >= 5000 - preRealPing24 - preOldLabPing24 && p24HasNewest && pAll == 6000))
            // lossHistory must be the NEWEST 60 (k=0..59 → 1000..1059).
            // v2.9.8 — the old expectation was inverted: lossHistory
            // returns time-ASC (lossPoints queries DESC + reverses), and
            // for this fixture loss=1000+k at time now-k*60s, so the
            // oldest of the newest 60 (k=59, loss 1059) comes FIRST:
            // [1059..1000]. The function was right; the test was wrong.
            // runall-fixes §3: a real loss row inside the newest-60 window
            // displaces the oldest fixture value (1059). The fixture values
            // present must be exactly the newest ones, in time-ASC order —
            // i.e. [1000+lh.size-1 .. 1000] — with the window still full (60).
            // ChatGPT review round 2, item 2: do NOT assume a real row
            // always displaces the oldest fixture value — which value is
            // displaced depends on the real row's time. Check by real time
            // order and fixture identity instead, using (time, loss) pairs:
            // every fixture row in the window must carry the value its own
            // time implies (loss = 1000 + k, k = (nowR2 - time)/60_000,
            // k in 0..99), and the fixture rows present must be exactly the
            // newest ones the window can hold (their k values a prefix
            // 0..59-R, where R is the number of real rows in the window —
            // a consequence of the window being the newest 60 by time, not
            // an assumption about which value a real row displaces).
            // nowR2 is an unrounded currentTimeMillis, so a real row tying
            // a fixture time exactly (same millisecond) is negligible.
            // ChatGPT review round 3, item 2 — verified against the real
            // lossPoints implementation (History.kt:662): it queries time
            // DESC LIMIT 60 and reverses, so the window is the newest 60
            // in time-ASC order — exactly the same 60 rows lossHistory
            // returns. Duplicate timestamps: fixture times are distinct
            // (unrounded nowR2 minus exact 60_000 multiples); a real row
            // tying a fixture time exactly is ~1e-9, and it would FAIL
            // the prefix check, never false-PASS. The real/fixture split
            // is exact by construction: real loss is 0..100, fixture loss
            // is 1000..1099 (disjoint), and old fixture loss rows are
            // removed by cleanupLabRows() before the group — so R counts
            // the real rows in the window and the prefix check verifies
            // the window really holds the newest fixture rows, not an
            // arbitrary 60.
            // ChatGPT review round 5, item 2 — the prefix is PROVEN, not
            // assumed. Preconditions (all verified against the source):
            // (1) the window is the newest 60 rows BY TIME (lossPoints
            // queries time DESC LIMIT 60); (2) fixture times are distinct
            // and strictly ordered in k (nowR2 - k*60_000, k=0..99);
            // (3) the real/fixture split is exact (disjoint value
            // ranges above). Proof: in a time-descending list the
            // fixture rows appear in order k=0,1,2,...; if fixture k is
            // in the top 60, every fixture with a smaller k (a later
            // time) is also in the top 60 — so the fixture rows in the
            // window are always a prefix {0..m}, with m = 59 - R. A real
            // row between two fixture rows occupies one of the 60 slots
            // and pushes out the OLDEST fixture row in the window; it
            // never removes a fixture row from the middle, and it never
            // breaks the prefix. The check is therefore kept as a
            // consequence of the ordering, not as an assumption about
            // which value a real row displaces.
            val lpAll = History.lossPoints(this, limit = 60)
            val lpFix = lpAll.filter { it.second >= 1000.0 }
            val lpReal = lpAll.size - lpFix.size
            val lpIdentityOk = lpFix.all { (t, v) ->
                val k = (nowR2 - t) / 60_000L
                (nowR2 - t) % 60_000L == 0L && k in 0..99 && v == 1000.0 + k
            }
            val lpPrefixOk = lpFix.map { (nowR2 - it.first) / 60_000L }.toSet() == (0 until 60 - lpReal).map { it.toLong() }.toSet()
            // ChatGPT review round 4, item 2: lossHistory must be tested
            // directly, not only through lossPoints — the production
            // display path uses lossHistory, so a lossPoints-only test
            // would miss a lossHistory bug (e.g. a different limit).
            // Both must agree exactly on the same window.
            val lhValues = History.lossHistory(this)
            val lhMatchesLp = lhValues == lpAll.map { it.second }
            // ChatGPT review round 5, item 2: the fixture-presence
            // precondition is checked EXPLICITLY, not via isNotEmpty.
            // Fixture k=0 sits at time == nowR2 — the newest time any
            // row can have (the monitor is paused, so no real row can
            // be newer) — so k=0 is always the first row of the window
            // and the window can never fill with real data alone. If
            // k=0 is missing, the precondition itself failed and the
            // test FAILs with that exact reason, reported below.
            val lpHasNewest = lpAll.any { it.first == nowR2 && it.second == 1000.0 }
            val lhOk = lpAll.size == 60 && lpHasNewest && lpIdentityOk && lpPrefixOk && lhMatchesLp
            rs.add(timed("Loss History — جدیدترین ۶۰ رکورد (روی دستگاه)", "۱۰۰ مقدار loss آزمایشی (۱۰۰۰ تا ۱۰۹۹) → lossHistory باید ۶۰ جدیدترین را به ترتیب زمانی صعودی برگرداند", "پنجره 60 تایی پر + هویت فیکسچر بر اساس زمان (مقدار = ۱۰۰۰ + k) + جدیدترین فیکسچر (k=0) حاضر + lossHistory همان ۶۰ مقدار را بدهد", "همه=${lpAll.size} testRows=${lpFix.size} realRows=$lpReal هویت=$lpIdentityOk پیشوند=$lpPrefixOk جدیدترین‌فیکسچر=$lpHasNewest تطابق lossHistory=$lhMatchesLp", lhOk))
            // Backup/Restore with >5000 rows: the backup must contain EVERY
            // valid row, and restoring it must give back the same counts.
            // v2.9.8 — the table counts are captured BEFORE building the
            // JSON (the JSON is built from the same read), so the
            // comparison is against one instant, not a snapshot vs a
            // later live count. (With the monitor paused for the Unit
            // section there are no new rows anyway; this makes the test
            // non-racy by construction.)
            val fullSamp = History.samplesFull(this).size
            val fullPing = History.pingPoints(this, limit = 0).size
            val bJson2 = History.buildBackupJson(this)
            val bRoot2 = org.json.JSONObject(bJson2)
            val bSamp2 = bRoot2.getJSONArray("samples").length()
            val bPing2 = bRoot2.getJSONArray("ping_points").length()
            val rReason2 = History.restoreFromJson(this, bJson2)
            val rSamp2 = History.samplesFull(this).size
            val rPing2 = History.pingPoints(this, limit = 0).size
            val diag2 = History.lastRestoreDiag
            rs.add(timed("Backup/Restore — بیش از ۵۰۰۰ رکورد (روی دستگاه)", "buildBackupJson → شمارش ردیف‌های JSON با جدول → restoreFromJson همان JSON → مقایسه تعدادها", "JSON همه ردیف‌ها را دارد، restore=OK، تعدادها قبل = بعد", "samples JSON=$bSamp2 جدول=$fullSamp • ping JSON=$bPing2 جدول=$fullPing • restore=$rReason2 • بعد: $rSamp2/$rPing2" + if (diag2.isNotEmpty()) " • تشخیص: $diag2" else "", bSamp2 == fullSamp && bPing2 == fullPing && rReason2 == "OK" && rSamp2 == fullSamp && rPing2 == fullPing))
            // Restore pre-validation: a file with ONE malformed row must be
            // rejected with an exact error and leave the data untouched.
            val badRoot = org.json.JSONObject(bJson2)
            badRoot.getJSONArray("samples").getJSONObject(0).remove("time")
            val badJson = badRoot.toString()
            val preSamp = History.samplesFull(this).size
            val badReason = History.restoreFromJson(this, badJson)
            val postSamp = History.samplesFull(this).size
            rs.add(timed("Restore — ردیف خراب (اعتبارسنجی پیش از تراکنش)", "حذف فیلد time از یک ردیف samples در JSON → restore باید رد کند و داده دست نخورده بماند", "خطای دقیق + داده سالم", "نتیجه: $badReason • داده دست نخورده: ${preSamp == postSamp}", badReason != "OK" && preSamp == postSamp))
        } finally {
            // Explicit cleanup: only the test rows (identifiable by source /
            // impossible ms / impossible loss value) are deleted; real user
            // rows are never touched. (Same cleanup also runs after a
            // timeout, outside the timed section — see runSection/RUN ALL.)
            cleanupLabRows()
            val cleanSamp = History.samplesFull(this).size
            val cleanPing = History.pingPoints(this, limit = 0).size
            val cleanLoss = History.lossPoints(this, limit = 0).size
            rs.add(timed("History — پاک‌سازی ردیف‌های تست ۶۰۰۰ (v2.9.4)", "حذف صریح ردیف‌های تست (source/ms/loss) → تعدادها باید دقیقاً به قبل از تست برگردند", "تعدادها قبل = بعد", "samples $preSamp6000->$cleanSamp • ping $prePing6000->$cleanPing • loss $preLoss6000->$cleanLoss", cleanSamp == preSamp6000 && cleanPing == prePing6000 && cleanLoss == preLoss6000))
        }
        // v2.9.5 final control — cleanup safety net (on-device): insert
        // identifiable LAB test rows directly, run cleanupLabRows(), and
        // assert the counts are exactly restored. Only the identifiable
        // test rows are deleted; real user rows are never touched.
        run {
            val cSamp0 = History.samplesFull(this).size
            val cPing0 = History.pingPoints(this, limit = 0).size
            val cLoss0 = History.lossPoints(this, limit = 0).size
            val nowC = System.currentTimeMillis()
            History.withGate(this) {
                val dbC = HistoryDb.getDb(this)
                val cvS = android.content.ContentValues()
                cvS.put("time", nowC); cvS.put("ok", 40); cvS.put("total", 40)
                cvS.put("avg_ms", 50.0); cvS.put("jitter_ms", 5.0); cvS.put("loss_pct", 0.0)
                cvS.put("source", "lab-6000-test")
                dbC.insert("samples", null, cvS)
                val cvP = android.content.ContentValues()
                cvP.put("time", nowC); cvP.put("ms", 50000.0); cvP.put("ok", 1)
                dbC.insert("ping_points", null, cvP)
                val cvL = android.content.ContentValues()
                cvL.put("time", nowC); cvL.put("loss", 1000.0)
                dbC.insert("loss_points", null, cvL)
            }
            val inserted = History.samplesFull(this).size == cSamp0 + 1 && History.pingPoints(this, limit = 0).size == cPing0 + 1 && History.lossPoints(this, limit = 0).size == cLoss0 + 1
            cleanupLabRows()
            val cSamp1 = History.samplesFull(this).size
            val cPing1 = History.pingPoints(this, limit = 0).size
            val cLoss1 = History.lossPoints(this, limit = 0).size
            rs.add(timed("Cleanup — پاک‌سازی ردیف‌های تست LAB (v2.9.5)", "درج مستقیم ۳ ردیف تست قابل‌شناسایی → cleanupLabRows() → تعدادها باید دقیقاً به قبل برگردند", "تعدادها قبل = بعد، بدون دست‌خوردن داده واقعی", "samples $cSamp0->$cSamp1 • ping $cPing0->$cPing1 • loss $cLoss0->$cLoss1 • درج شد: $inserted", inserted && cSamp1 == cSamp0 && cPing1 == cPing0 && cLoss1 == cLoss0))
        }
        // runall-fixes §2 — DRY RUN ONLY (user condition 1): count the
        // stranded 6000 test rows and show samples. NOTHING is deleted here;
        // deletion needs the user's separate approval. The stranded rows
        // carry source='restore' now (the v2.9.8 run's own restore rewrote
        // them), with the exact fixture fingerprint: ok=40, total=40,
        // avg_ms=50.0, jitter_ms=5.0, loss_pct=0.0.
        val stranded = History.samplesFull(this).filter {
            it.source == "restore" && it.ok == 40 && it.total == 40 &&
            it.avgMs == 50.0 && it.jitterMs == 5.0 && it.lossPct == 0.0
        }
        val sampleText = stranded.take(3).joinToString(" | ") {
            "${History.timeStr(it.time)} ok=${it.ok}/${it.total} avg=${it.avgMs}"
        }.ifBlank { "—" }
        val totalSamples = History.samplesFull(this).size
        // ChatGPT final review, fix 2: show the time range of the matching
        // rows (oldest + newest) next to the exact count, so the range can
        // be reviewed before any deletion is ever approved. Still NO deletion.
        val oldestStr = stranded.minOfOrNull { it.time }?.let { History.timeStr(it) } ?: "—"
        val newestStr = stranded.maxOfOrNull { it.time }?.let { History.timeStr(it) } ?: "—"
        // ChatGPT review round 2, item 1: source + values alone are not
        // fully specific — a real row could in principle match the
        // fingerprint. The fixture was inserted on an exact 1-minute grid
        // (nowR2 - i*60_000, i=0..5999), so the stranded rows must form
        // that exact grid: 6000 rows, consecutive gaps of exactly 60_000 ms,
        // span exactly 5999*60_000 ms. Anything else (a gap, a wrong span,
        // an extra/missing row) FAILs — never delete on a partial match.
        val sortedTimes = stranded.map { it.time }.sorted()
        val gridOk = stranded.size == 6000 &&
            sortedTimes.zipWithNext().all { (a, b) -> b - a == 60_000L } &&
            sortedTimes.last() - sortedTimes.first() == 5999L * 60_000L
        val gridNote = if (stranded.isEmpty()) " • شبکه: — (ردیفی نیست)"
        else " • شبکه: ${if (gridOk) "کامل (۶۰ ثانیه‌ای، بدون گاف)" else "ناقص — حذف نکن"}"
        // ChatGPT review round 3, item 1: the grid alone proves
        // regularity, not identity with the original 22:03 fixture. Two
        // stronger identity links were investigated against the source:
        // - SQLite ids are NOT usable: the Backup/Restore>5000 test
        //   restored the DB from backup JSON, and RestoreStream
        //   re-inserts every row in time-ASC order (buildBackupJson
        //   writes samplesFull, time ASC), so the restored fixture rows
        //   got fresh ids interleaved with real rows — consecutiveness
        //   does NOT hold for the stranded rows. Not checked.
        // - The original RUN ALL's record IS usable: Lab.saveRun stores
        //   one "RUN ALL" LabRun with the run's end timestamp. The
        //   History group ran during that run, so the grid's newest time
        //   must precede that timestamp by less than the run's duration
        //   (RUN ALL took ~250s; 10 minutes is a generous bound). If no
        //   such run is in the kept history (loadRuns keeps only the
        //   newest 5), the check is reported as skipped, never failed —
        //   the grid + fingerprint stay the primary identity.
        // ChatGPT review round 6, item 1 — CORRECTION of the round-5
        // claim: the 10-minute window does NOT prove which run created
        // the rows, and the round-5 comment overstated it. Verified
        // against the source:
        // - LabRun has NO id field (Lab.kt:771: version, timestamp,
        //   section, results only), and the samples table has NO run-id
        //   column (HistoryDb.kt:57). The 22:03 rows carry no run stamp,
        //   and a run-id scheme added now could not retroactively tag
        //   them. Direct identity is UNPROVABLE for these rows. (The
        //   merged record's results list also lacks the grid's
        //   timestamps — it shows the fixture counts, not which rows
        //   came from which run.)
        // - A RUN ALL saves exactly ONE record: Lab.saveRun(..., "RUN
        //   ALL", allResults) at LabActivity.kt:1995 (per-section saves
        //   are NOT used by RUN ALL — it uses renderOnly). saveRun keeps
        //   only the newest 5 records, so up to FIVE "RUN ALL" records
        //   can sit in the kept history at once. Several of them CAN fall
        //   in the same 10-minute window: back-to-back runs are ~4-5 min
        //   apart, so run #1 (end ~22:07) and run #2 (end ~22:12) both
        //   satisfy the window after the grid's newest time (~22:04),
        //   and `any` cannot tell #1 from #2. Concrete case: RUN ALL
        //   #3's dry-run would match #2's record while the rows came
        //   from #1.
        // - What the window DOES exclude: runs that ended before the
        //   grid's newest time, and runs that ended more than 10 minutes
        //   after it (other hours/days). What it CANNOT do: identify the
        //   creating run. So the check is a PLAUSIBILITY signal, not a
        //   match — renamed runMatch -> runProbable, and the display
        //   says «RUN ALL در بازه زمانی محتمل» with no definite-match
        //   claim. The grid + fingerprint remain the primary identity;
        //   the window only corroborates plausibility. A slower run
        //   (gap > 10 min) reports "record exists but range does not
        //   match" — manual review, never a false match and never a FAIL.
        //   NO deletion happens here in any case; deletion still needs
        //   the user's separate approval after the on-device dry-run.
        // ChatGPT review round 4, item 1: the run-window check is
        // REPORTED, never part of dryOk — a missing RUN ALL record is
        // SKIP by design (the history keeps only the newest 5 runs), not
        // a failure. Three distinct states are reported, so "no record"
        // is never confused with "a record exists but the time range
        // does not match".
        val newestTime = stranded.maxOfOrNull { it.time }
        val runAllRuns = Lab.loadRuns(this).filter { it.section == "RUN ALL" }
        val runProbable = newestTime?.let { newest ->
            runAllRuns.any { r -> r.timestamp >= newest && r.timestamp - newest <= 600_000L }
        }
        val runNote = when {
            stranded.isEmpty() -> " • اجرای اصلی: — (ردیفی نیست)"
            runProbable == true -> " • اجرای اصلی: RUN ALL در بازه زمانی محتمل (تطبیق قطعی نیست)"
            runAllRuns.isEmpty() -> " • اجرای اصلی: رکورد RUN ALL در تاریخچه نیست — بازه بررسی نشد (SKIP)"
            else -> " • اجرای اصلی: رکورد RUN ALL هست ولی بازه زمانی منطبق نیست — بررسی دستی لازم است"
        }
        // ChatGPT review round 6, item 1: the «آماده حذف» note is gated
        // on runProbable (a RUN ALL in the plausible window), not on a
        // definite match — the wording says so explicitly. A missing
        // record is SKIP (no match is claimed) and a non-matching record
        // means manual review — neither shows «آماده حذف». dryOk is
        // unchanged: the window check is REPORTED, never part of the
        // verdict (round 4, item 1).
        val dryNote = when {
            stranded.isEmpty() -> " • قبلاً پاک شده، چیزی برای حذف نیست"
            stranded.size == 6000 && gridOk && runProbable == true -> " • آماده حذف با تأیید جداگانه — RUN ALL در بازه زمانی محتمل است (تطبیق قطعی نیست)؛ فقط گزارش Dry Run، نه مجوز حذف"
            stranded.size == 6000 && gridOk && runAllRuns.isEmpty() -> " • تطبیق اجرا ممکن نشد (SKIP — رکورد RUN ALL در تاریخچه نیست)؛ هویت فقط با شبکه + اثر انگشت — بررسی دستی لازم است"
            stranded.size == 6000 && gridOk -> " • رکورد RUN ALL هست ولی بازه زمانی منطبق نیست — بررسی دستی لازم است؛ آماده حذف نیست"
            else -> " • حذف ممنوع — تعداد یا شبکه با فیکسچر اصلی مطابقت ندارد"
        }
        val dryOk = stranded.isEmpty() || (stranded.size == 6000 && gridOk)
        rs.add(timed("Cleanup — خشک‌سنجی ردیف‌های تست ۶۰۰۰ (dry-run، بدون حذف)", "شمارش ردیف‌های باقی‌مانده با اثر انگشت فیکسچر + نمایش ۳ نمونه + بازه زمانی + شبکه زمانی + تطبیق با اجرای اصلی (فقط گزارش)", "تعداد دقیق + بازه زمانی + شبکه زمانی کامل؛ «آماده حذف» فقط وقتی شبکه کامل و یک RUN ALL در بازه زمانی محتمل باشد (تطبیق قطعی نیست)؛ نبودن رکورد SKIP و عدم تطبیق یعنی بررسی دستی؛ هیچ ردیفی حذف نشود", "مطابق: ${stranded.size} ردیف • از $totalSamples نمونه • بازه: $oldestStr تا $newestStr • نمونه‌ها: $sampleText$dryNote$gridNote$runNote", dryOk))
        // v2.6 on-device: backup/restore round-trip — restoring the backup just
        // built must give back the same record counts; a corrupt file must fail
        // with a reason and leave the existing data untouched.
        val bJson = History.buildBackupJson(this)
        val bSamples = History.samplesFull(this).size
        val bPings = History.pingPoints(this).size
        val bReason = History.restoreFromJson(this, bJson)
        val rSamples = History.samplesFull(this).size
        val rPings = History.pingPoints(this).size
        val diagRT = History.lastRestoreDiag
        rs.add(timed("Backup/Restore Round-trip", "buildBackupJson → restoreFromJson(همان JSON) → مقایسه تعداد رکوردها", "restore=OK، تعدادها قبل = بعد", "samples $bSamples→$rSamples • ping_points $bPings→$rPings • نتیجه: $bReason" + if (diagRT.isNotEmpty()) " • تشخیص: $diagRT" else "", bReason == "OK" && bSamples == rSamples && bPings == rPings))
        // runall-fixes §1: source must survive backup → restore. A marker row
        // with a distinctive source is backed up and restored; it must come
        // back with the same source. The same JSON with the source field
        // stripped (an old backup) must still restore, defaulting to
        // "restore". Review amendment: the marker row is deleted in a
        // finally block, so it can never linger if a restore throws.
        val markerTime = System.currentTimeMillis()
        val markerSrc = "lab-source-test"
        History.withGate(this) {
            val dbM = HistoryDb.getDb(this)
            val cv = android.content.ContentValues()
            cv.put("time", markerTime); cv.put("ok", 41); cv.put("total", 41)
            cv.put("avg_ms", 42.0); cv.put("jitter_ms", 2.0); cv.put("loss_pct", 0.0)
            cv.put("source", markerSrc)
            dbM.insert("samples", null, cv)
        }
        try {
            val jsonWithSrc = History.buildBackupJson(this)
            val rSrc = History.restoreFromJson(this, jsonWithSrc)
            val backSrc = History.samplesFull(this).firstOrNull { it.time == markerTime }?.source
            val jsonOld = org.json.JSONObject(jsonWithSrc)
            val arrOld = jsonOld.getJSONArray("samples")
            for (i in 0 until arrOld.length()) {
                val o = arrOld.getJSONObject(i)
                if (o.optLong("time") == markerTime) o.remove("source")
            }
            val rOld = History.restoreFromJson(this, jsonOld.toString())
            val backOldSrc = History.samplesFull(this).firstOrNull { it.time == markerTime }?.source
            val srcOk = rSrc == "OK" && backSrc == markerSrc && rOld == "OK" && backOldSrc == "restore"
            rs.add(timed("Restore — حفظ ستون source", "ردیف با source مشخص → بکاپ → ریستور → source باید بماند؛ بکاپ قدیمی بدون source هم باید برگردد با source پیش‌فرض", "source حفظ شود + بکاپ قدیمی OK با source=restore", "بکاپ تازه: $rSrc • برگشته: $backSrc • بکاپ قدیمی: $rOld • برگشته: $backOldSrc", srcOk))
        } catch (e: Exception) {
            // Review amendment: a throw inside the test must be reported
            // as a FAIL for this test, never abort the LAB run — there is
            // no per-test catch around the group, so an uncaught exception
            // here would skip the rest of the section.
            rs.add(timed("Restore — حفظ ستون source", "ردیف با source مشخص → بکاپ → ریستور → source باید بماند؛ بکاپ قدیمی بدون source هم باید برگردد با source پیش‌فرض", "source حفظ شود + بکاپ قدیمی OK با source=restore", "خطا در تست: ${e.javaClass.simpleName} — ${e.message ?: "—"}", false))
        } finally {
            History.withGate(this) { HistoryDb.getDb(this).delete("samples", "time=?", arrayOf(markerTime.toString())) }
        }
        val corruptReason = History.restoreFromJson(this, "{broken json")
        val stillThere = History.samplesFull(this).size == rSamples
        rs.add(timed("Restore — فایل خراب", "JSON خراب → خطای دقیق، داده قبلی دست نخورده", "خطا + داده سالم", "نتیجه: $corruptReason • داده دست نخورده: $stillThere", corruptReason != "OK" && stillThere))
        // v2.9.6 — on-device export test (A6; WRITTEN, NOT RUN on JVM):
        // PDF+JSON go to Downloads/NetWatch, a second export gets a
        // different name, the first files are still preserved; the four
        // test files are deleted afterwards so LAB runs never pollute the
        // user's Downloads.
        run {
            val pdf1 = History.exportPdf(this)
            val json1 = History.exportJson(this)
            val pdf2 = History.exportPdf(this)
            val json2 = History.exportJson(this)
            val namesDiffer = pdf1.displayName != pdf2.displayName && json1.displayName != json2.displayName
            val firstPreserved = ExportHelper.exists(this, pdf1) && ExportHelper.exists(this, json1)
            val secondExists = ExportHelper.exists(this, pdf2) && ExportHelper.exists(this, json2)
            val okExport = pdf1.ok && json1.ok && pdf2.ok && json2.ok && namesDiffer && firstPreserved && secondExists
            ExportHelper.delete(this, pdf1); ExportHelper.delete(this, json1)
            ExportHelper.delete(this, pdf2); ExportHelper.delete(this, json2)
            val cleanedUp = !ExportHelper.exists(this, pdf1) && !ExportHelper.exists(this, json1) && !ExportHelper.exists(this, pdf2) && !ExportHelper.exists(this, json2)
            rs.add(timed("Export — PDF+JSON در Downloads/NetWatch (روی دستگاه)", "دو بار exportPdf + exportJson → نام‌ها، وجود فایل‌ها، حفظ فایل اول → حذف ۴ فایل تست", "هر دو export موفق • نام دوم متفاوت • فایل اول حفظ شد • فایل‌های تست پاک شدند", "PDF: ${pdf1.displayName} / ${pdf2.displayName} • JSON: ${json1.displayName} / ${json2.displayName} • نام متفاوت: $namesDiffer • اول حفظ شد: $firstPreserved • دوم موجود: $secondExists • پاک‌سازی: $cleanedUp", okExport && cleanedUp))
        }

    }

    // Restore-negative: each test corrupts ONE field of a FRESH backup of
    // the current data and asserts pre-validation rejects it with the
    // field named, leaving DB + prefs untouched.
    private fun unitRestoreNegativeGroup(rs: MutableList<TestResult>) {
        val nowR2 = System.currentTimeMillis()
        val bJsonNeg = History.buildBackupJson(this)
            // v2.9.1 — settings / last_check / score_total / gaming session:
            // restoreFromJson این بخش‌ها را AFTER تراکنش می‌خواند؛ در ۲.۹
            // اعتبارسنجی نمی‌شدند و فایل خراب می‌توانست بعد از پاک‌سازی
            // داده‌ها خطا بدهد. هر تست: JSON معتبر را خراب کن → restore
            // باید رد کند و داده + تنظیمات قبلی کاملاً دست نخورده بمانند.
            // (1) settings رشته به‌جای آبجکت
            run {
                val r1 = org.json.JSONObject(bJsonNeg)
                r1.put("settings", "abc")
                val ivBefore = History.intervalSec(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r1.toString())
                rs.add(timed("Restore — settings رشته به‌جای آبجکت", "settings به رشته تبدیل شد → restore باید رد کند و interval و داده دست نخورده بمانند", "خطا + interval و داده سالم", "نتیجه: $reason • interval: $ivBefore -> ${History.intervalSec(this)} • samples: $nBefore -> ${History.samplesFull(this).size}", reason != "OK" && History.intervalSec(this) == ivBefore && History.samplesFull(this).size == nBefore))
            }
            // (2) فیلد settings با نوع اشتباه (interval_sec رشته)
            run {
                val r2 = org.json.JSONObject(bJsonNeg)
                r2.getJSONObject("settings").put("interval_sec", "abc")
                val ivBefore = History.intervalSec(this)
                val reason = History.restoreFromJson(this, r2.toString())
                rs.add(timed("Restore — فیلد settings با نوع اشتباه", "interval_sec رشته شد → restore باید رد کند و تنظیمات دست نخورده بمانند", "خطا + interval سالم", "نتیجه: $reason • interval: $ivBefore -> ${History.intervalSec(this)}", reason != "OK" && History.intervalSec(this) == ivBefore))
            }
            // (3) last_check رشته به‌جای آبجکت
            run {
                val r3 = org.json.JSONObject(bJsonNeg)
                r3.put("last_check", "abc")
                val lcBefore = History.lastCheck(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r3.toString())
                val lcAfter = History.lastCheck(this)
                rs.add(timed("Restore — last_check رشته به‌جای آبجکت", "last_check به رشته تبدیل شد → restore باید رد کند و last_check و داده دست نخورده بمانند", "خطا + last_check و داده سالم", "نتیجه: $reason • last_check سالم: ${lcBefore == lcAfter} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && lcBefore == lcAfter && History.samplesFull(this).size == nBefore))
            }
            // (4) score_total خراب (رشته به‌جای عدد)
            run {
                val r4 = org.json.JSONObject(bJsonNeg)
                r4.getJSONObject("last_check").put("score_total", "abc")
                val lcBefore = History.lastCheck(this)
                val reason = History.restoreFromJson(this, r4.toString())
                val lcAfter = History.lastCheck(this)
                rs.add(timed("Restore — score_total خراب", "score_total رشته شد → restore باید رد کند و last_check دست نخورده بماند", "خطا + last_check سالم", "نتیجه: $reason • last_check سالم: ${lcBefore == lcAfter}", reason != "OK" && lcBefore == lcAfter))
            }
            // (5) gaming session با score خراب (رشته به‌جای عدد یا null)
            run {
                val r5 = org.json.JSONObject(bJsonNeg)
                val ga = r5.getJSONArray("gaming_sessions")
                if (ga.length() > 0) {
                    ga.getJSONObject(0).put("score", "abc")
                } else {
                    val fake = org.json.JSONObject()
                    fake.put("started", nowR2 - 60000); fake.put("ended", nowR2)
                    fake.put("profile", "CODM"); fake.put("region", "ME"); fake.put("host", "1.1.1.1")
                    fake.put("sent", 10); fake.put("received", 10); fake.put("loss", 0.0)
                    fake.put("avg_ms", 50.0); fake.put("min_ms", 40.0); fake.put("max_ms", 60.0)
                    fake.put("jitter_ms", 5.0); fake.put("p95", 58.0); fake.put("spikes", 0); fake.put("burst", 0)
                    fake.put("score", "abc"); fake.put("bb_grade", "-")
                    ga.put(fake)
                }
                val gsBefore = History.gamingSessions(this).size
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r5.toString())
                rs.add(timed("Restore — gaming session با score خراب", "score یک جلسه رشته شد → restore باید رد کند و جلسات و داده دست نخورده بمانند", "خطا + جلسات و داده سالم", "نتیجه: $reason • sessions: $gsBefore -> ${History.gamingSessions(this).size} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && History.gamingSessions(this).size == gsBefore && History.samplesFull(this).size == nBefore))
            }
            // v2.9.2 — negative restore tests (strict REAL JSON types):
            // org.json getters ACCEPT numeric strings ("123" -> 123), so
            // the validator must reject them by type, BEFORE the
            // transaction. Each test appends ONE bad row (or mutates one
            // settings field) and asserts the restore is rejected by the
            // pre-validation (reason mentions the field) with DB + prefs
            // untouched.
            // (6) samples.time به‌صورت String
            run {
                val r6 = org.json.JSONObject(bJsonNeg)
                val bad = org.json.JSONObject()
                bad.put("time", "1700000000000"); bad.put("ok", 40); bad.put("total", 40)
                bad.put("avg_ms", 50.0); bad.put("jitter_ms", 5.0); bad.put("loss_pct", 0.0)
                r6.getJSONArray("samples").put(bad)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r6.toString())
                rs.add(timed("Restore — عدد به‌صورت String در samples", "ردیف samples با time رشته‌ای اضافه شد → restore باید پیش از تراکنش رد کند", "خطای اعتبارسنجی با نام فیلد + داده سالم", "نتیجه: $reason • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.samplesFull(this).size == nBefore))
            }
            // (7) samples.ok به‌صورت Boolean
            run {
                val r7 = org.json.JSONObject(bJsonNeg)
                val bad = org.json.JSONObject()
                bad.put("time", nowR2); bad.put("ok", true); bad.put("total", 40)
                bad.put("avg_ms", 50.0); bad.put("jitter_ms", 5.0); bad.put("loss_pct", 0.0)
                r7.getJSONArray("samples").put(bad)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r7.toString())
                rs.add(timed("Restore — Boolean به‌جای عدد در samples", "ردیف samples با ok بولین اضافه شد → restore باید پیش از تراکنش رد کند", "خطای اعتبارسنجی با نام فیلد + داده سالم", "نتیجه: $reason • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.samplesFull(this).size == nBefore))
            }
            // (8) samples.avg_ms به‌صورت String (فیلد اختیاری double)
            run {
                val r8 = org.json.JSONObject(bJsonNeg)
                val bad = org.json.JSONObject()
                bad.put("time", nowR2); bad.put("ok", 40); bad.put("total", 40)
                bad.put("avg_ms", "50.0"); bad.put("jitter_ms", 5.0); bad.put("loss_pct", 0.0)
                r8.getJSONArray("samples").put(bad)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r8.toString())
                rs.add(timed("Restore — عدد به‌صورت String در فیلد اختیاری", "ردیف samples با avg_ms رشته‌ای اضافه شد → restore باید پیش از تراکنش رد کند", "خطای اعتبارسنجی با نام فیلد + داده سالم", "نتیجه: $reason • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.samplesFull(this).size == nBefore))
            }
            // (9) ping_points.ms به‌صورت String
            run {
                val r9 = org.json.JSONObject(bJsonNeg)
                val bad = org.json.JSONObject()
                bad.put("time", nowR2); bad.put("ms", "50.0")
                r9.getJSONArray("ping_points").put(bad)
                val pBefore = History.pingPoints(this, limit = 0).size
                val reason = History.restoreFromJson(this, r9.toString())
                rs.add(timed("Restore — عدد به‌صورت String در ping_points", "نقطه پینگ با ms رشته‌ای اضافه شد → restore باید پیش از تراکنش رد کند", "خطای اعتبارسنجی با نام فیلد + داده سالم", "نتیجه: $reason • ping سالم: ${History.pingPoints(this, limit = 0).size == pBefore}", reason != "OK" && reason.contains("فیلد") && History.pingPoints(this, limit = 0).size == pBefore))
            }
            // (10) settings.live_ping به‌صورت String به‌جای Boolean
            run {
                val r10 = org.json.JSONObject(bJsonNeg)
                r10.getJSONObject("settings").put("live_ping", "true")
                val lpBefore = History.livePingOn(this)
                val reason = History.restoreFromJson(this, r10.toString())
                rs.add(timed("Restore — String به‌جای Boolean در settings", "live_ping رشته‌ای شد → restore باید پیش از تراکنش رد کند و تنظیمات سالم بمانند", "خطای اعتبارسنجی با نام فیلد + تنظیمات سالم", "نتیجه: $reason • live_ping سالم: ${History.livePingOn(this) == lpBefore}", reason != "OK" && reason.contains("فیلد") && History.livePingOn(this) == lpBefore))
            }
            // (11) settings.interval_sec به‌صورت Boolean به‌جای عدد
            run {
                val r11 = org.json.JSONObject(bJsonNeg)
                r11.getJSONObject("settings").put("interval_sec", true)
                val ivBefore = History.intervalSec(this)
                val reason = History.restoreFromJson(this, r11.toString())
                rs.add(timed("Restore — Boolean به‌جای عدد در settings", "interval_sec بولین شد → restore باید پیش از تراکنش رد کند و تنظیمات سالم بمانند", "خطای اعتبارسنجی با نام فیلد + تنظیمات سالم", "نتیجه: $reason • interval سالم: ${History.intervalSec(this) == ivBefore}", reason != "OK" && reason.contains("فیلد") && History.intervalSec(this) == ivBefore))
            }
            // v2.9.3 — negative restore tests (top-level fields):
            // "version" was checked with optInt (accepts the string "1")
            // and the text fields were stored with optString AFTER the
            // transaction (silently coerces numbers/objects via toString).
            // Each test corrupts ONE field and asserts the restore is
            // rejected by the pre-validation (reason mentions the field)
            // with the DB, the main prefs AND the gaming prefs untouched.
            // (12) version به‌صورت String "1"
            run {
                val r12 = org.json.JSONObject(bJsonNeg)
                r12.put("version", "1")
                val nBefore = History.samplesFull(this).size
                val ivBefore = History.intervalSec(this)
                val reason = History.restoreFromJson(this, r12.toString())
                rs.add(timed("Restore — version به‌صورت String", "version رشته‌ای «1» شد → restore باید پیش از هر تغییر رد کند", "خطای اعتبارسنجی با نام فیلد + داده و تنظیمات سالم", "نتیجه: $reason • samples سالم: ${History.samplesFull(this).size == nBefore} • interval سالم: ${History.intervalSec(this) == ivBefore}", reason != "OK" && reason.contains("فیلد") && History.samplesFull(this).size == nBefore && History.intervalSec(this) == ivBefore))
            }
            // (13) version به‌صورت Boolean
            run {
                val r13 = org.json.JSONObject(bJsonNeg)
                r13.put("version", true)
                val nBefore = History.samplesFull(this).size
                val ivBefore = History.intervalSec(this)
                val reason = History.restoreFromJson(this, r13.toString())
                rs.add(timed("Restore — version به‌صورت Boolean", "version بولین شد → restore باید پیش از هر تغییر رد کند", "خطای اعتبارسنجی با نام فیلد + داده و تنظیمات سالم", "نتیجه: $reason • samples سالم: ${History.samplesFull(this).size == nBefore} • interval سالم: ${History.intervalSec(this) == ivBefore}", reason != "OK" && reason.contains("فیلد") && History.samplesFull(this).size == nBefore && History.intervalSec(this) == ivBefore))
            }
            // (14) log به‌صورت عدد
            run {
                val r14 = org.json.JSONObject(bJsonNeg)
                r14.put("log", 123)
                val logBefore = History.log(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r14.toString())
                rs.add(timed("Restore — log به‌صورت عدد", "log عددی شد → restore باید پیش از هر تغییر رد کند و log قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + log و داده سالم", "نتیجه: $reason • log سالم: ${History.log(this) == logBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.log(this) == logBefore && History.samplesFull(this).size == nBefore))
            }
            // (15) timeline به‌صورت آبجکت
            run {
                val r15 = org.json.JSONObject(bJsonNeg)
                r15.put("timeline", org.json.JSONObject())
                val tlBefore = History.timeline(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r15.toString())
                rs.add(timed("Restore — timeline به‌صورت آبجکت", "timeline آبجکت شد → restore باید پیش از هر تغییر رد کند و timeline قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + timeline و داده سالم", "نتیجه: $reason • timeline سالم: ${History.timeline(this) == tlBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.timeline(this) == tlBefore && History.samplesFull(this).size == nBefore))
            }
            // (16) hourly به‌صورت عدد
            run {
                val r16 = org.json.JSONObject(bJsonNeg)
                r16.put("hourly", 123)
                val hBefore = History.hourly(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r16.toString())
                rs.add(timed("Restore — hourly به‌صورت عدد", "hourly عددی شد → restore باید پیش از هر تغییر رد کند و hourly قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + hourly و داده سالم", "نتیجه: $reason • hourly سالم: ${History.hourly(this) == hBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.hourly(this) == hBefore && History.samplesFull(this).size == nBefore))
            }
            // (17) blackbox به‌صورت Boolean
            run {
                val r17 = org.json.JSONObject(bJsonNeg)
                r17.put("blackbox", true)
                val bBefore = History.blackboxLog(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r17.toString())
                rs.add(timed("Restore — blackbox به‌صورت Boolean", "blackbox بولین شد → restore باید پیش از هر تغییر رد کند و blackbox قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + blackbox و داده سالم", "نتیجه: $reason • blackbox سالم: ${History.blackboxLog(this) == bBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.blackboxLog(this) == bBefore && History.samplesFull(this).size == nBefore))
            }
            // (18) gaming_log به‌صورت عدد
            run {
                val r18 = org.json.JSONObject(bJsonNeg)
                r18.put("gaming_log", 123)
                val gBefore = Gaming.log(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r18.toString())
                rs.add(timed("Restore — gaming_log به‌صورت عدد", "gaming_log عددی شد → restore باید پیش از هر تغییر رد کند و log گیمینگ قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + log گیمینگ و داده سالم", "نتیجه: $reason • gaming_log سالم: ${Gaming.log(this) == gBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && Gaming.log(this) == gBefore && History.samplesFull(this).size == nBefore))
            }
            // (19) gaming_matches به‌صورت آبجکت
            run {
                val r19 = org.json.JSONObject(bJsonNeg)
                r19.put("gaming_matches", org.json.JSONObject())
                val mBefore = Gaming.matches(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r19.toString())
                rs.add(timed("Restore — gaming_matches به‌صورت آبجکت", "gaming_matches آبجکت شد → restore باید پیش از هر تغییر رد کند و matches قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + matches و داده سالم", "نتیجه: $reason • matches سالم: ${Gaming.matches(this) == mBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && Gaming.matches(this) == mBefore && History.samplesFull(this).size == nBefore))
            }
            // (20) spikes به‌صورت عدد
            run {
                val r20 = org.json.JSONObject(bJsonNeg)
                r20.put("spikes", 123)
                val sBefore = History.spikes(this)
                val nBefore = History.samplesFull(this).size
                val reason = History.restoreFromJson(this, r20.toString())
                rs.add(timed("Restore — spikes به‌صورت عدد", "spikes عددی شد → restore باید پیش از هر تغییر رد کند و spikes قبلی سالم بماند", "خطای اعتبارسنجی با نام فیلد + spikes و داده سالم", "نتیجه: $reason • spikes سالم: ${History.spikes(this) == sBefore} • samples سالم: ${History.samplesFull(this).size == nBefore}", reason != "OK" && reason.contains("فیلد") && History.spikes(this) == sBefore && History.samplesFull(this).size == nBefore))
            }
    }

    // Streaming + recovery: streaming-validator fixtures, rename/journal
    // failure injection, oversize rejection, parse park/resume, simulated
    // crash recovery, migration, and the manual kill test (NOT TESTED).
    private fun unitStreamingGroup(rs: MutableList<TestResult>) {
            // v2.9.4 — Restore Security: streaming-validator fixtures.
            // The parser runs against the STAGING path only (never the live
            // DB), so even "accept" fixtures cannot touch real data. Each
            // fixture asserts the exact accept/reject decision AND that the
            // live data is untouched afterwards.
            run {
                val base = "{\"version\":1,\"samples\":[{\"time\":1700000000000,\"ok\":40,\"total\":40,\"avg_ms\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0}],\"ping_points\":[{\"time\":1700000000000,\"ms\":50.0}]}"
                val full = "{\"version\":1,\"samples\":[],\"ping_points\":[],\"settings\":{\"interval_sec\":30,\"alert_threshold\":40,\"live_ping\":false,\"custom_target\":\"\",\"dns_rounds\":20,\"alerts_on\":true},\"last_check\":{\"ping_avg\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0,\"intl_pct\":90,\"dom_pct\":95,\"score_total\":85,\"score_status\":\"Complete\",\"checked_at\":1700000000000}}"
                fun fixture(json: String): String? {
                    HistoryDb.closeStaging()
                    val sf = getDatabasePath("netwatch.db.staging")
                    if (sf.exists()) sf.delete()
                    val sj = getDatabasePath("netwatch.db.staging-journal")
                    if (sj.exists()) sj.delete()
                    val stagingDb = HistoryDb.getStagingDb(this)
                    return try {
                        RestoreStream.streamIntoStaging(java.io.ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), History.RESTORE_MAX_BYTES, stagingDb, null)
                        null
                    } catch (e: Exception) {
                        e.message
                    } finally {
                        HistoryDb.closeStaging()
                        if (sf.exists()) sf.delete()
                        if (sj.exists()) sj.delete()
                    }
                }
                val liveBefore = History.samplesFull(this).size
                val cases = listOf(
                    Triple("valid-minimal", base, true),
                    Triple("version-string", base.replace("\"version\":1", "\"version\":\"1\""), false),
                    Triple("version-1.0", base.replace("\"version\":1", "\"version\":1.0"), false),
                    Triple("version-2", base.replace("\"version\":1", "\"version\":2"), false),
                    Triple("version-bool", base.replace("\"version\":1", "\"version\":true"), false),
                    Triple("samples-time-string", base.replace("\"time\":1700000000000", "\"time\":\"1700000000000\""), false),
                    Triple("samples-time-1.0", base.replace("\"time\":1700000000000", "\"time\":1700000000000.0"), false),
                    Triple("samples-ok-bool", base.replace("\"ok\":40", "\"ok\":true"), false),
                    Triple("samples-avg-string", base.replace("\"avg_ms\":50.0", "\"avg_ms\":\"50.0\""), false),
                    Triple("ping-ms-string", base.replace("\"ms\":50.0", "\"ms\":\"50.0\""), false),
                    Triple("settings-live-string", full.replace("\"live_ping\":false", "\"live_ping\":\"false\""), false),
                    Triple("settings-interval-bool", full.replace("\"interval_sec\":30", "\"interval_sec\":true"), false),
                    Triple("settings-as-string", "{\"version\":1,\"samples\":[],\"ping_points\":[],\"settings\":\"abc\",\"last_check\":{\"ping_avg\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0,\"intl_pct\":90,\"dom_pct\":95,\"score_total\":85,\"score_status\":\"Complete\",\"checked_at\":1700000000000}}", false),
                    Triple("log-number", base.dropLast(1) + ",\"log\":123}", false),
                    Triple("duplicate-field", "{\"version\":1,\"samples\":[{\"time\":1700000000000,\"time\":1700000000001,\"ok\":40,\"total\":40}],\"ping_points\":[]}", false),
                    Triple("unknown-top-field", base.dropLast(1) + ",\"unknown_field\":{\"x\":1}}", true),
                    Triple("unknown-row-field", base.replace("\"loss_pct\":0.0", "\"loss_pct\":0.0,\"extra\":\"hi\""), true),
                    Triple("truncated", "{\"version\":1,\"samples\":[{\"time\":1700000000000,\"ok\":40", false),
                    Triple("trailing-data", base + " {\"x\":1}", false),
                    Triple("missing-samples", "{\"version\":1,\"ping_points\":[]}", false),
                    Triple("missing-version", "{\"samples\":[],\"ping_points\":[]}", false),
                    Triple("empty-object", "{}", false),
                    Triple("score-null-accept", "{\"version\":1,\"samples\":[],\"ping_points\":[],\"gaming_sessions\":[{\"started\":1700000000000,\"ended\":1700000001000,\"profile\":\"CODM\",\"region\":\"ME\",\"host\":\"1.1.1.1\",\"sent\":10,\"received\":10,\"loss\":0.0,\"avg_ms\":50.0,\"min_ms\":40.0,\"max_ms\":60.0,\"jitter_ms\":5.0,\"p95\":58.0,\"spikes\":0,\"burst\":0,\"score\":null,\"bb_grade\":\"-\"}]}", true),
                    Triple("score-string", "{\"version\":1,\"samples\":[],\"ping_points\":[],\"gaming_sessions\":[{\"started\":1700000000000,\"ended\":1700000001000,\"profile\":\"CODM\",\"region\":\"ME\",\"host\":\"1.1.1.1\",\"sent\":10,\"received\":10,\"loss\":0.0,\"avg_ms\":50.0,\"min_ms\":40.0,\"max_ms\":60.0,\"jitter_ms\":5.0,\"p95\":58.0,\"spikes\":0,\"burst\":0,\"score\":\"abc\",\"bb_grade\":\"-\"}]}", false)
                )
                var fixPass = 0
                var fixFail = ""
                for ((name, json, shouldAccept) in cases) {
                    val err = fixture(json)
                    val accepted = err == null
                    if (accepted == shouldAccept) fixPass++ else fixFail += "$name(می‌خواست ${if (shouldAccept) "قبول" else "رد"}، شد ${if (accepted) "قبول" else "رد: $err"})؛ "
                }
                val liveAfter = History.samplesFull(this).size
                rs.add(timed("Stream — Fixture Suite اعتبارسنجی جریانی (v2.9.4)", "۲۴ فیکسچر: معتبر حداقلی، version رشته/۱٫۰/۲/بولین، عدد رشته‌ای و اعشاری در فیلد صحیح، settings خراب، log عددی، فیلد تکراری، فیلد ناشناخته (قبول)، JSON ناقص، داده اضافی، نبود samples/version، score null (قبول) و score رشته‌ای — همه فقط روی staging", "همه ۲۴ فیکسچر تصمیم درست + داده زنده دست نخورده", "گذشت $fixPass/24 • ${if (fixFail.isEmpty()) "همه درست ✅" else fixFail} • داده زنده: $liveBefore->$liveAfter", fixPass == cases.size && liveAfter == liveBefore))
            }
            // v2.9.4 — rename failure injection (P0-1): a forced rename
            // failure must abort the restore and leave the live data intact.
            run {
                val small = "{\"version\":1,\"samples\":[{\"time\":1700000000000,\"ok\":40,\"total\":40,\"avg_ms\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0}],\"ping_points\":[{\"time\":1700000000000,\"ms\":50.0}]}"
                val nBefore = History.samplesFull(this).size
                val ivBefore = History.intervalSec(this)
                History.testFailRenameStep = 1
                val r1 = try { History.restoreFromJson(this, small) } finally { History.testFailRenameStep = null }
                val journal1 = getSharedPreferences("restore_journal", Context.MODE_PRIVATE).contains("state")
                val old1 = getDatabasePath("netwatch.db.old").exists()
                rs.add(timed("Restore — خطای Rename اول (تزریق)", "rename ۱ مجبور به شکست شد → restore باید لغو شود و داده + ژورنال تمیز بمانند", "خطا + داده سالم + بدون ژورنال + بدون .old", "نتیجه: $r1 • داده سالم: ${History.samplesFull(this).size == nBefore} • interval سالم: ${History.intervalSec(this) == ivBefore} • ژورنال باقی‌مانده: $journal1 • .old باقی‌مانده: $old1" + if (History.lastRestoreDiag.isNotEmpty()) " • تشخیص: ${History.lastRestoreDiag}" else "", r1 != "OK" && History.samplesFull(this).size == nBefore && History.intervalSec(this) == ivBefore && !journal1 && !old1))
                History.testFailRenameStep = 2
                val r2 = try { History.restoreFromJson(this, small) } finally { History.testFailRenameStep = null }
                val journal2 = getSharedPreferences("restore_journal", Context.MODE_PRIVATE).contains("state")
                val old2 = getDatabasePath("netwatch.db.old").exists()
                val live2 = getDatabasePath("netwatch.db").exists()
                rs.add(timed("Restore — خطای Rename دوم (تزریق)", "rename ۲ مجبور به شکست شد → rollback داخلی (.old ← live) و داده سالم بماند", "خطا + rollback کامل + بدون ژورنال + بدون .old + live موجود", "نتیجه: $r2 • داده سالم: ${History.samplesFull(this).size == nBefore} • ژورنال باقی‌مانده: $journal2 • .old باقی‌مانده: $old2 • live موجود: $live2" + if (History.lastRestoreDiag.isNotEmpty()) " • تشخیص: ${History.lastRestoreDiag}" else "", r2 != "OK" && History.samplesFull(this).size == nBefore && !journal2 && !old2 && live2))
            }
            // v2.9.4 — journal write failure (P0-1): the restore must abort
            // BEFORE any mutation when the journal cannot be written.
            run {
                val small = "{\"version\":1,\"samples\":[{\"time\":1700000000000,\"ok\":40,\"total\":40,\"avg_ms\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0}],\"ping_points\":[{\"time\":1700000000000,\"ms\":50.0}]}"
                val nBefore = History.samplesFull(this).size
                History.testFailJournalWrite = true
                val r = try { History.restoreFromJson(this, small) } finally { History.testFailJournalWrite = false }
                val journal = getSharedPreferences("restore_journal", Context.MODE_PRIVATE).contains("state")
                rs.add(timed("Restore — خطای نوشتن ژورنال (تزریق)", "نوشتن ژورنال مجبور به شکست شد → restore باید پیش از هر تغییر لغو شود", "خطا + داده سالم + بدون ژورنال", "نتیجه: $r • داده سالم: ${History.samplesFull(this).size == nBefore} • ژورنال باقی‌مانده: $journal" + if (History.lastRestoreDiag.isNotEmpty()) " • تشخیص: ${History.lastRestoreDiag}" else "", r != "OK" && History.samplesFull(this).size == nBefore && !journal))
            }
            // v2.9.6 — the cap is 10 bytes (not 1024): each group
            // builds its own fresh backup, whose size varies with the
            // real history, so the test asserts over-cap rejection with
            // a cap any real backup exceeds.
            // v2.9.4 — oversize rejection with a small test limit (P0-2):
            // a file over the cap is rejected BEFORE parsing, data intact.
            run {
                val nBefore = History.samplesFull(this).size
                val r = History.restoreFromJson(this, History.buildBackupJson(this), 10)
                rs.add(timed("Restore — فایل بیش از سقف (سقف کوچک تست)", "بکاپ واقعی با سقف ۱۰ بایت → باید پیش از Parse رد شود و داده سالم بماند", "خطای حجم + داده سالم", "نتیجه: $r • داده سالم: ${History.samplesFull(this).size == nBefore}", r != "OK" && r.contains("حجم") && History.samplesFull(this).size == nBefore))
            }
            // v2.9.4 — parse park / resume (P0-1): the restore parks after N
            // rows (kill-test hook); while parked there is no journal and the
            // live data is intact; releasing the hook lets it finish.
            run {
                val nBefore = History.samplesFull(this).size
                RestoreStream.testStopAfterRows = 10
                RestoreStream.testParked.set(false)
                val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
                // v2.9.6 — fixture = the LIVE backup PLUS 50 synthetic rows:
                // the park hook stops the parser after 10 rows, so the fixture
                // must have more than 10 rows no matter how much real history
                // exists. The synthetic rows carry fixed Nov-2023 times, outside
                // the 30-day retention — no real row can carry them — and are
                // deleted again right after the test.
                val parkBase = org.json.JSONObject(History.buildBackupJson(this))
                val parkBaseTime = 1700000000000L
                run {
                    val parkSamples = parkBase.getJSONArray("samples")
                    val parkPings = parkBase.getJSONArray("ping_points")
                    for (i in 0 until 50) {
                        val s = org.json.JSONObject()
                        s.put("time", parkBaseTime + i * 60000L); s.put("ok", 40); s.put("total", 40)
                        s.put("avg_ms", 50.0); s.put("jitter_ms", 5.0); s.put("loss_pct", 0.0)
                        parkSamples.put(s)
                        val p = org.json.JSONObject()
                        p.put("time", parkBaseTime + i * 5000L); p.put("ms", 50.0)
                        parkPings.put(p)
                    }
                }
                val parkJson = parkBase.toString()
                val th = Thread { result.set(History.restoreFromJson(this, parkJson)) }
                th.start()
                var parked = false
                for (i in 0 until 100) {
                    if (RestoreStream.testParked.get()) { parked = true; break }
                    Thread.sleep(100)
                }
                val journalWhileParked = getSharedPreferences("restore_journal", Context.MODE_PRIVATE).contains("state")
                val dataWhileParked = History.samplesFull(this).size == nBefore
                RestoreStream.testStopAfterRows = null
                th.join(60000)
                val done = result.get()
                // Delete the 50 synthetic park rows again (fixed Nov-2023
                // times — no real row can carry them); the live data must be
                // exactly as before, and none of the synthetic rows may stay.
                val parkClean = History.withGate(this) {
                    val dbP = HistoryDb.getDb(this)
                    for (i in 0 until 50) {
                        dbP.delete("samples", "time=?", arrayOf((parkBaseTime + i * 60000L).toString()))
                        dbP.delete("ping_points", "time=?", arrayOf((parkBaseTime + i * 5000L).toString()))
                    }
                    val sLeft = dbP.rawQuery("SELECT COUNT(*) FROM samples WHERE time>=? AND time<?", arrayOf(parkBaseTime.toString(), (parkBaseTime + 50 * 60000L).toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                    val pLeft = dbP.rawQuery("SELECT COUNT(*) FROM ping_points WHERE time>=? AND time<?", arrayOf(parkBaseTime.toString(), (parkBaseTime + 50 * 5000L).toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                    sLeft == 0L && pLeft == 0L
                }
                rs.add(timed("Restore — توقف و ادامه Parse (P0-1)", "پارسر بعد از ۱۰ ردیف متوقف شد → حین توقف ژورنال نیست و داده سالم است → با آزادشدن Hook، restore کامل می‌شود → ۵۰ ردیف مصنوعی پارک دوباره حذف شوند", "پارک شد + بدون ژورنال + داده سالم + در پایان OK + ردیف‌های مصنوعی پاک شدند", "پارک: $parked • ژورنال حین توقف: $journalWhileParked • داده حین توقف سالم: $dataWhileParked • نتیجه نهایی: $done • پاک‌سازی: $parkClean" + if (History.lastRestoreDiag.isNotEmpty()) " • تشخیص: ${History.lastRestoreDiag}" else "", parked && !journalWhileParked && dataWhileParked && done == "OK" && parkClean))
            }
            // v2.9.4 — simulated crash recovery (P0-1): build a real crash
            // state (STARTED journal + .old + missing live) and run the
            // central recovery directly. This tests the ROLLBACK code path
            // that runs on relaunch after a real force-stop.
            run {
                val nBefore = History.samplesFull(this).size
                val ivBefore = History.intervalSec(this)
                var recoveredOk = false
                var detail = ""
                History.withGate(this) {
                    try {
                        HistoryDb.closeAndReset()
                        val live = getDatabasePath("netwatch.db")
                        val old = getDatabasePath("netwatch.db.old")
                        if (old.exists()) old.delete()
                        // copy live -> .old, then delete live (crash between renames)
                        live.copyTo(old, overwrite = true)
                        live.delete()
                        History.testWriteStartedJournal(this, "lab-crash-sim")
                        History.recoverIfNeeded(this)
                        val liveBack = getDatabasePath("netwatch.db").exists()
                        val oldGone = !getDatabasePath("netwatch.db.old").exists()
                        val journalGone = !getSharedPreferences("restore_journal", Context.MODE_PRIVATE).contains("state")
                        val nAfter = History.samplesFull(this).size
                        val ivAfter = History.intervalSec(this)
                        recoveredOk = liveBack && oldGone && journalGone && nAfter == nBefore && ivAfter == ivBefore
                        detail = "live برگشت: $liveBack • .old پاک شد: $oldGone • ژورنال پاک شد: $journalGone • samples: $nBefore->$nAfter • interval سالم: ${ivAfter == ivBefore}"
                    } catch (e: Exception) {
                        detail = "استثنا: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                rs.add(timed("Recovery — شبیه‌سازی Crash بین دو Rename (v2.9.4)", "وضعیت Crash واقعی ساخته شد (ژورنال STARTED + ‎.old + live حذف‌شده) → recoverIfNeeded باید live را از ‎.old برگرداند و ژورنال را پاک کند", "rollback کامل + داده و تنظیمات سالم + بدون ژورنال", detail, recoveredOk))
            }
            // v2.9.4 — migration with identical legacy records (P0-1):
            // data preservation wins — two identical legacy lines must stay
            // TWO rows (never merged), and a re-run must insert nothing.
            // The whole test runs under the gate (its fixture setup writes
            // the legacy prefs strings directly).
            run { History.withGate(this) {
                val p = getSharedPreferences("netwatch", Context.MODE_PRIVATE)
                val savedSamples = p.getString("samples", "") ?: ""
                val savedPing = p.getString("ping_hist", "") ?: ""
                val savedFlag = p.getBoolean("db_migrated_v1", false)
                val markerBefore = HistoryDb.getReadDb(this).rawQuery("SELECT value FROM meta WHERE key = 'migration_legacy_v1'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val fixTime = 1000000000000L
                var detail = ""
                var ok = false
                try {
                    History.withGate(this) {
                        val db = HistoryDb.getDb(this)
                        db.delete("meta", "key=?", arrayOf("migration_legacy_v1"))
                        db.delete("samples", "time=?", arrayOf(fixTime.toString()))
                        db.delete("ping_points", "time=?", arrayOf(fixTime.toString()))
                    }
                    p.edit().putString("samples", "$fixTime,40,40,50.0,5.0,0.0;$fixTime,40,40,50.0,5.0,0.0").putString("ping_hist", "$fixTime,50.0").putBoolean("db_migrated_v1", false).commit()
                    History.migrateIfNeeded(this)
                    val count = HistoryDb.getReadDb(this).rawQuery("SELECT COUNT(*) FROM samples WHERE time=? AND ok=40 AND total=40", arrayOf(fixTime.toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                    val pingCount = HistoryDb.getReadDb(this).rawQuery("SELECT COUNT(*) FROM ping_points WHERE time=?", arrayOf(fixTime.toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                    val markerAfter = HistoryDb.getReadDb(this).rawQuery("SELECT value FROM meta WHERE key = 'migration_legacy_v1'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val legacyCleared = (p.getString("samples", "") ?: "").isEmpty() && (p.getString("ping_hist", "") ?: "").isEmpty()
                    History.migrateIfNeeded(this) // re-run: must insert nothing
                    val count2 = HistoryDb.getReadDb(this).rawQuery("SELECT COUNT(*) FROM samples WHERE time=? AND ok=40 AND total=40", arrayOf(fixTime.toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                    ok = count == 2L && pingCount == 1L && markerAfter != null && legacyCleared && count2 == 2L
                    detail = "ردیف‌های یکسان: $count (باید ۲ باشد، ادغام نشود) • پینگ: $pingCount • marker نوشته شد: ${markerAfter != null} • رشته‌های قدیمی پاک شدند: $legacyCleared • اجرای مجدد: $count2 (باید همان ۲ بماند)"
                } catch (e: Exception) {
                    detail = "استثنا: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    History.withGate(this) {
                        val db = HistoryDb.getDb(this)
                        db.delete("samples", "time=?", arrayOf(fixTime.toString()))
                        db.delete("ping_points", "time=?", arrayOf(fixTime.toString()))
                        if (markerBefore != null) {
                            val cv = android.content.ContentValues().apply { put("key", "migration_legacy_v1"); put("value", markerBefore) }
                            db.insertWithOnConflict("meta", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                        } else {
                            db.delete("meta", "key=?", arrayOf("migration_legacy_v1"))
                        }
                    }
                    p.edit().putString("samples", savedSamples).putString("ping_hist", savedPing).putBoolean("db_migrated_v1", savedFlag).commit()
                }
                rs.add(timed("Migration — رکوردهای یکسان حفظ شوند (v2.9.4)", "دو خط قدیمی کاملاً یکسان + یک پینگ → migrate باید هر دو را درج کند (ادغام ممنوع) و اجرای مجدد چیزی اضافه نکند", "۲ ردیف یکسان + ۱ پینگ + marker + اجرای مجدد بدون تغییر", detail, ok))
            } }
            // v2.9.4 — manual kill test (NOT TESTED): a real force-stop
            // mid-restore cannot be automated (the test would kill its own
            // process). Do it by hand: run Unit Tests, watch the log for
            // "Restore — توقف و ادامه Parse" (the parser parks), force-stop
            // the app from Settings while it is parked, relaunch, run Unit
            // Tests again — the simulated-crash test above proves the same
            // recovery code path, and the live data must be the old state.
            rs.add(timed("Kill Test دستی — Force-Stop وسط Restore (v2.9.4)", "دستی: پارک کردن restore (Hook testStopAfterRows) → Force-Stop از تنظیمات → راه‌اندازی مجدد → بررسی اینکه داده قدیمی کامل برگشته و ژورنال پاک شده", "NOT TESTED — فقط دستی قابل انجام است", "انجام نشده — برای انجام: در LAB تست «توقف و ادامه Parse» را ببین؛ همان Hook را می‌توان برای توقف دستی استفاده کرد", null))
    }

    // Large-RAM: the ~29MB synthetic 30-day backup, streamed into staging
    // only, with heap/PSS measurement. Its own group (cap 180s) and
    // clearly labelled heavy, so it can never stall the other groups.
    private fun unitLargeRamGroup(rs: MutableList<TestResult>) {
            // v2.9.4 — large restore with RAM measurement (P0-2): a synthetic
            // ~29 MB backup (86,400 samples + 518,400 ping points, the real
            // 30-day worst case with Live Ping on) is streamed into STAGING
            // only (the live DB is never touched). Heap/PSS are sampled by a
            // polling thread while the parse runs.
            run {
                val extDir = getExternalFilesDir(null)
                val synth = java.io.File(extDir, "lab-large-backup.json")
                var detail = ""
                var ok = false
                try {
                    // Generate the synthetic backup by chunked writes (never a whole String).
                    val w = java.io.BufferedWriter(java.io.FileWriter(synth), 65536)
                    val baseT = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    w.write("{\"version\":1,\"samples\":[")
                    for (i in 0 until 86400) {
                        if (i > 0) w.write(",")
                        w.write("{\"time\":${baseT + i * 30000L},\"ok\":40,\"total\":40,\"avg_ms\":50.0,\"jitter_ms\":5.0,\"loss_pct\":0.0}")
                    }
                    w.write("],\"ping_points\":[")
                    for (i in 0 until 518400) {
                        if (i > 0) w.write(",")
                        w.write("{\"time\":${baseT + i * 5000L},\"ms\":50.0}")
                    }
                    w.write("],\"loss_points\":[],\"log\":\"\",\"timeline\":\"\",\"hourly\":\"\",\"blackbox\":\"\"}")
                    w.close()
                    val fileMB = synth.length() / 1024 / 1024
                    val rt = Runtime.getRuntime()
                    fun heapMB(): Long = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                    fun pssMB(): Long { val mi = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(mi); return mi.totalPss.toLong() / 1024 }
                    val heap0 = heapMB(); val pss0 = pssMB()
                    val peakHeap = java.util.concurrent.atomic.AtomicLong(heap0)
                    val peakPss = java.util.concurrent.atomic.AtomicLong(pss0)
                    val stopSampler = java.util.concurrent.atomic.AtomicBoolean(false)
                    val sampler = Thread {
                        while (!stopSampler.get()) {
                            val h = heapMB(); val p = pssMB()
                            if (h > peakHeap.get()) peakHeap.set(h)
                            if (p > peakPss.get()) peakPss.set(p)
                            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                        }
                    }
                    sampler.start()
                    val t0 = System.currentTimeMillis()
                    HistoryDb.closeStaging()
                    val sf = getDatabasePath("netwatch.db.staging")
                    if (sf.exists()) sf.delete()
                    val payload = try {
                        RestoreStream.streamIntoStaging(java.io.FileInputStream(synth), History.RESTORE_MAX_BYTES, HistoryDb.getStagingDb(this), null)
                    } finally {
                        stopSampler.set(true)
                        sampler.join(5000)
                        HistoryDb.closeStaging()
                        if (sf.exists()) sf.delete()
                    }
                    val durMs = System.currentTimeMillis() - t0
                    val heapDelta = peakHeap.get() - heap0
                    val pssDelta = peakPss.get() - pss0
                    val countsOk = payload.counts.samples == 86400 && payload.counts.pingPoints == 518400
                    ok = countsOk && heapDelta <= 32 && pssDelta <= 64
                    detail = "فایل: $fileMB MB • ردیف‌ها: samples=${payload.counts.samples}/86400 ping=${payload.counts.pingPoints}/518400 • زمان: ${durMs}ms • heap Δ: ${heapDelta}MB (سقف ۳۲) • PSS Δ: ${pssDelta}MB (سقف ۶۴)"
                } catch (e: Exception) {
                    detail = "استثنا: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    if (synth.exists()) synth.delete()
                    HistoryDb.closeStaging()
                    val sf = getDatabasePath("netwatch.db.staging")
                    if (sf.exists()) sf.delete()
                }
                rs.add(timed("Restore بزرگ — RAM و Streaming (v2.9.4)", "بکاپ مصنوعی ~۲۹ مگابایت (۳۰ روزه با Live Ping روشن) فقط روی staging استریم شود → heap و PSS اندازه‌گیری شوند", "تعداد ردیف‌ها دقیق + heap Δ ≤ ۳۲MB + PSS Δ ≤ ۶۴MB + بدون ANR/OOM", detail, ok))
            }
    }


    private fun integrationGroups(): List<GroupDef> = listOf(
        GroupDef("Integration — Probes", 90, ::integrationProbesGroup),
        GroupDef("Integration — TLS/TCP", 30, ::integrationTlsGroup),
        GroupDef("Integration — IP-change + DNS-kinds", 45, ::integrationIpDnsGroup),
        GroupDef("Integration — Traceroute", 60, ::integrationTracerouteGroup),
        GroupDef("Integration — Backup/Restore", 60, ::integrationBackupGroup),
        GroupDef("Integration — Multi-Host + QUIC", 90, ::integrationMultiGroup)
    )

    // ChatGPT review round 3, item 4 — the gamingSessionText decision is
    // JVM-tested, but the real path (reading the session row + counting
    // its samples inside healthSnapshot) needs the device. This group
    // drives that exact path: it creates real session rows, snapshots
    // them with no samples, with samples + a finished all-drops session,
    // and with a normal finished session — then deletes every row it
    // created (gaming tables are never pruned, so a test must not leave
    // rows behind). Runs last in the Unit section; the monitor is paused
    // while it runs, so no real session can overtake the test rows.
    private fun unitBridgeDeviceGroup(rs: MutableList<TestResult>) {
        val profile = "lab-bridge-test"
        // v2.9.9 — Bridge locale (TEST-ONLY): Bridge.gamingSessionText
        // formats with "%.0f"/"%.1f".format() and NO explicit locale, so on
        // a Persian-locale phone it renders Persian digits ("loss 100.0%"
        // becomes "loss ۱۰۰٫۰%") while the assertions below checked Latin
        // digits — the 19:42 device FAILs were false failures; the Bridge
        // logic was correct (proven by the actual strings). Normalize
        // Persian + Arabic-Indic digits and the Persian decimal separator
        // before comparing, so the assertions pass under BOTH locales.
        // The production formatting is NOT changed (Persian digits are
        // correct for the Persian UI).
        fun normDigits(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                when {
                    ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                    ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                    ch == '٫' -> sb.append('.')
                    ch == '٬' -> sb.append(',')
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
        var sessionId = -1L
        var sessionId2 = -1L
        try {
            sessionId = HistoryEngine.startGamingSession(this, profile, "lab", "1.1.1.1")
            // 1. No samples yet -> healthSnapshot must report no_data.
            val snapEmpty = Bridge.healthSnapshot(this, false)
            val emptyOk = snapEmpty.lastGamingSession == "no_data"
            rs.add(timed("Bridge (device) — جلسه گیمینگ بدون نمونه", "جلسه واقعی بدون نمونه → healthSnapshot", "lastGamingSession = no_data", "got=${snapEmpty.lastGamingSession}", emptyOk))
            // 2. Samples + a finished all-drops session (loss 100) -> must
            // SHOW, never no_data.
            repeat(5) { HistoryEngine.addGamingSample(this, sessionId, 45.0, true) }
            HistoryEngine.finishGamingSession(this, sessionId, 5, 0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 50, "")
            val snapDrops = Bridge.healthSnapshot(this, false)
            val dropsOk = snapDrops.lastGamingSession != "no_data" && normDigits(snapDrops.lastGamingSession).contains("loss 100.0%")
            rs.add(timed("Bridge (device) — جلسه تمام‌شده با Loss=100%", "۵ نمونه + finish با loss=100 → healthSnapshot", "نمایش رشته جلسه با loss 100.0٪ (نه no_data)", "got=${snapDrops.lastGamingSession}", dropsOk))
            // 3. A normal finished session -> shows its real avg/loss.
            sessionId2 = HistoryEngine.startGamingSession(this, profile, "lab", "1.1.1.1")
            repeat(10) { HistoryEngine.addGamingSample(this, sessionId2, 45.0, true) }
            HistoryEngine.finishGamingSession(this, sessionId2, 10, 9, 10.0, 45.0, 20.0, 80.0, 5.0, 70.0, 1, 0, 85, "A")
            val snapNormal = Bridge.healthSnapshot(this, false)
            val normalOk = normDigits(snapNormal.lastGamingSession).contains("avg 45ms") && normDigits(snapNormal.lastGamingSession).contains("loss 10.0%")
            rs.add(timed("Bridge (device) — جلسه تمام‌شده عادی", "۱۰ نمونه + finish با avg=45/loss=10 → healthSnapshot", "رشته جلسه با avg/loss واقعی", "got=${snapNormal.lastGamingSession}", normalOk))
        } catch (e: Exception) {
            rs.add(timed("Bridge (device) — مسیر healthSnapshot", "خواندن جلسه + شمارش نمونه‌ها روی گوشی", "بدون استثنا", "err=${e.javaClass.simpleName}: ${e.message}", false))
        } finally {
            if (sessionId > 0) deleteGamingSession(sessionId)
            if (sessionId2 > 0) deleteGamingSession(sessionId2)
            // ChatGPT review round 4, item 4: the cleanup test must cover
            // BOTH tables — a session row deleted while its samples
            // survive would still pass a sessions-only check. If either
            // count is non-zero (or the check itself errors), the test
            // FAILs. Expected runtime of the whole group is a few seconds
            // (3 healthSnapshots + a handful of DB writes); the 60s
            // budget is an order of magnitude above that.
            val left = try { HistoryEngine.gamingSessions(this).count { it.profile == profile } } catch (_: Exception) { -1 }
            val leftSamples = try {
                val db = HistoryDb.getDb(this)
                var n = 0
                for (id in listOf(sessionId, sessionId2)) {
                    if (id > 0) db.query("gaming_samples", arrayOf("id"), "session_id=?", arrayOf(id.toString()), null, null, null).use { c -> n += c.count }
                }
                n
            } catch (_: Exception) { -1 }
            rs.add(timed("Bridge (device) — پاک‌سازی ردیف‌های تست", "حذف جلسه‌ها و نمونه‌های تست بعد از اجرا", "۰ جلسه و ۰ نمونه lab-bridge-test باقی بماند", "sessions=$left samples=$leftSamples", left == 0 && leftSamples == 0))
        }
    }

    // v2.9.9 — Gaming lifecycle (device-only): proves the session loop is
    // owned by MonitorService, not by GamingActivity. Runs last in the Unit
    // section; GamingActivity is never opened here, so test 2 really runs
    // with the activity dead. 90s budget: the 12s wait + service start/stop
    // polls. The monitor was paused by pauseMonitorForLab at the start of
    // the Unit section; this group starts the service itself (START_GAMING)
    // and leaves it running — resumeMonitorAfterLab (in unitSection's
    // finally) restarts it the same way afterwards.
    // NOTE: test 1's START_GAMING triggers finishStaleSessions() in the
    // service, which closes the user's unfinished CODM sessions (the 5 from
    // the Reset bug) from their recorded samples — that is the authorized
    // recovery (item 2), not a deletion: the rows stay in history,
    // finished, with stats from their real samples. A session with zero
    // samples closes with the defined empty-session semantics (loss=100,
    // avg=0) — reported as defined behavior, not measured data.
    private fun unitGamingLifecycleGroup(rs: MutableList<TestResult>) {
        val profile = "lab-lifecycle-test"
        val host = "1.1.1.1"
        var sessionId = -1L
        fun activeCount(): Int = try { HistoryEngine.gamingSessions(this).count { it.ended == 0L } } catch (e: Exception) { -1 }
        fun startGamingIntent() {
            val i = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START_GAMING
                putExtra("profile", profile)
                putExtra("region", "lab")
                putExtra("host", host)
                putExtra("durationMin", 1)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (e: Exception) { }
        }
        fun stopGamingIntent() {
            val i = Intent(this, MonitorService::class.java).apply { action = MonitorService.ACTION_STOP_GAMING }
            try { startService(i) } catch (e: Exception) { }
        }
        fun waitFor(cond: () -> Boolean, timeoutMs: Long): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                try { if (cond()) return true } catch (e: Exception) { }
                try { Thread.sleep(200) } catch (e: Exception) { break }
            }
            return try { cond() } catch (e: Exception) { false }
        }
        try {
            // 1. START_GAMING from the service -> exactly one active session.
            startGamingIntent()
            val started = waitFor({ MonitorService.gamingActive && activeCount() == 1 }, 10000)
            val active = try { HistoryEngine.activeGamingSession(this) } catch (e: Exception) { null }
            sessionId = active?.id ?: -1L
            rs.add(timed("Gaming lifecycle — شروع از سرویس", "ارسال START_GAMING (۱ دقیقه، هاست 1.1.1.1) از LabActivity", "gamingActive=true و دقیقاً یک ردیف جلسه با ended==0",
                "gamingActive=${MonitorService.gamingActive} active=${activeCount()} sessionId=$sessionId", started && sessionId > 0))
            // 2. ~12s with GamingActivity NOT alive -> samples accumulate.
            try { Thread.sleep(12000) } catch (e: Exception) { }
            val samples = try { HistoryEngine.gamingSessionSamples(this, sessionId) } catch (e: Exception) { emptyList() }
            val timesAsc = samples.zipWithNext().all { (a, b) -> b.first >= a.first }
            val timesUnique = samples.map { it.first }.toSet().size == samples.size
            val samplesOk = samples.size >= 2 && timesAsc && timesUnique
            rs.add(timed("Gaming lifecycle — ادامه بدون Activity", "۱۲ ثانیه صبر در حالی که GamingActivity اصلاً زنده نیست", "نمونه‌های DB برای آن جلسه ≥ ۲، زمان‌ها صعودی، بدون (session_id,time) تکراری",
                "samples=${samples.size} asc=$timesAsc unique=$timesUnique", samplesOk))
            // 3. START again -> still exactly one active session.
            startGamingIntent()
            try { Thread.sleep(2000) } catch (e: Exception) { }
            val stillOne = activeCount() == 1 && try { HistoryEngine.activeGamingSession(this)?.id == sessionId } catch (e: Exception) { false }
            rs.add(timed("Gaming lifecycle — ضدتکرار", "ارسال دوباره START_GAMING", "هنوز دقیقاً یک جلسه فعال، همان sessionId",
                "active=${activeCount()}", stillOne))
            // 4. STOP from the service -> finished with real stats.
            stopGamingIntent()
            val stopped = waitFor({
                try {
                    val s = HistoryEngine.gamingSessions(this).firstOrNull { it.id == sessionId }
                    s != null && s.ended > 0L
                } catch (e: Exception) { false }
            }, 10000)
            val fin = try { HistoryEngine.gamingSessions(this).firstOrNull { it.id == sessionId } } catch (e: Exception) { null }
            val statsOk = fin != null && fin.ended > 0L && fin.sent >= samples.size && fin.loss in 0.0..100.0 && fin.avgMs.isFinite()
            val noUnfinished = activeCount() == 0
            rs.add(timed("Gaming lifecycle — توقف از سرویس", "ارسال STOP_GAMING", "جلسه بسته شود (ended>0) با آمار واقعی و هیچ جلسه ناتمامی نماند",
                "ended=${fin?.ended} sent=${fin?.sent} loss=${fin?.loss} avg=${fin?.avgMs} unfinished=${activeCount()}", stopped && statsOk && noUnfinished))
            sessionId = -1L
            // 5. Stale-session recovery: stop the service, THEN insert an
            // unfinished row + 3 samples (service dead, so no recovery runs
            // yet), start the service again -> onCreate recovery closes it
            // from its DB samples + the recovery log line is recorded.
            try { stopService(Intent(this, MonitorService::class.java)) } catch (e: Exception) { }
            try { Thread.sleep(1500) } catch (e: Exception) { }
            val staleId = try { HistoryEngine.startGamingSession(this, profile, "lab", host) } catch (e: Exception) { -1L }
            if (staleId > 0) {
                try {
                    HistoryEngine.addGamingSample(this, staleId, 40.0, true)
                    HistoryEngine.addGamingSample(this, staleId, 42.0, true)
                    HistoryEngine.addGamingSample(this, staleId, -1.0, false)
                } catch (e: Exception) { }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, MonitorService::class.java)) else startService(Intent(this, MonitorService::class.java))
                } catch (e: Exception) { }
                val recovered = waitFor({
                    try {
                        val s = HistoryEngine.gamingSessions(this).firstOrNull { it.id == staleId }
                        s != null && s.ended > 0L
                    } catch (e: Exception) { false }
                }, 10000)
                val recRow = try { HistoryEngine.gamingSessions(this).firstOrNull { it.id == staleId } } catch (e: Exception) { null }
                val recStatsOk = recRow != null && recRow.ended > 0L && recRow.sent == 3 && recRow.received == 2
                val logOk = try { History.log(this).contains("جلسه گیمینگ ناتمام بسته شد") } catch (e: Exception) { false }
                rs.add(timed("Gaming lifecycle — بازیابی جلسه ناتمام", "ساخت ردیف ناتمام با ۳ نمونه، توقف و شروع دوباره سرویس", "بازیابی آن را از روی نمونه‌های DB ببندد (ended>0، آمار واقعی) + خط لاگ بازیابی ثبت شود",
                    "ended=${recRow?.ended} sent=${recRow?.sent} recv=${recRow?.received} log=$logOk", recovered && recStatsOk && logOk))
            } else {
                rs.add(timed("Gaming lifecycle — بازیابی جلسه ناتمام", "ساخت ردیف ناتمام با ۳ نمونه، توقف و شروع دوباره سرویس", "بازیابی آن را از روی نمونه‌های DB ببندد (ended>0، آمار واقعی) + خط لاگ بازیابی ثبت شود",
                    "ساخت جلسه ناتمام ناموفق", false))
            }
        } catch (e: Exception) {
            rs.add(timed("Gaming lifecycle — مسیر سرویس", "شروع/توقف/بازیابی جلسه از سرویس روی گوشی", "بدون استثنا", "err=${e.javaClass.simpleName}: ${e.message}", false))
        } finally {
            // 6. Two-table cleanup: only the lab-lifecycle-test rows.
            try { stopGamingIntent() } catch (e: Exception) { }
            waitFor({ activeCount() == 0 }, 5000)
            var left = -1
            var leftSamples = -1
            try {
                val ids = HistoryEngine.gamingSessions(this).filter { it.profile == profile }.map { it.id }
                val db = HistoryDb.getDb(this)
                for (id in ids) {
                    db.delete("gaming_samples", "session_id=?", arrayOf(id.toString()))
                    db.delete("gaming_sessions", "id=?", arrayOf(id.toString()))
                }
                left = HistoryEngine.gamingSessions(this).count { it.profile == profile }
                var n = 0
                for (id in ids) {
                    db.query("gaming_samples", arrayOf("id"), "session_id=?", arrayOf(id.toString()), null, null, null).use { c -> n += c.count }
                }
                leftSamples = n
            } catch (e: Exception) { }
            rs.add(timed("Gaming lifecycle — پاک‌سازی ردیف‌های تست", "حذف جلسه‌ها و نمونه‌های lab-lifecycle-test بعد از اجرا", "۰ جلسه و ۰ نمونه lab-lifecycle-test باقی بماند",
                "sessions=$left samples=$leftSamples", left == 0 && leftSamples == 0))
        }
    }

    private fun deleteGamingSession(id: Long) {
        try {
            val db = HistoryDb.getDb(this)
            db.delete("gaming_samples", "session_id=?", arrayOf(id.toString()))
            db.delete("gaming_sessions", "id=?", arrayOf(id.toString()))
        } catch (_: Exception) { }
    }

    private fun integrationSection(): List<TestResult> {
        // v2.9.5 — internet-dependent section: with NO network at all the
        // tests report SKIP (pass=null, "بدون اینترنت"), not FAIL. A service
        // failing while the network is up is still an honest FAIL.
        if (!hasNetwork()) {
            return listOf(timed("Integration Tests — بدون اینترنت", "بررسی اتصال شبکه پیش از تست", "بدون شبکه: تست‌های اینترنتی SKIP شوند، نه FAIL", "بدون اتصال شبکه — تست‌های اینترنتی اجرا نشدند", null, "بدون اینترنت"))
        }
        integResults = emptyList(); integDnsOk = null; integFiltering = ""; integIntlPct = 0; integDomPct = 0
        val out = mutableListOf<TestResult>()
        for (g in integrationGroups()) out.addAll(runGroup(g))
        return out
    }

    // Probes (+DoH/UDP/IPv4/IPv6/HTTP verdicts from the probe results).
    private fun integrationProbesGroup(rs: MutableList<TestResult>) {
        val results = NetworkEngine.runProbes(false)
        val ok = results.count { it.ok }
        // v2.9.5 — DoH is matched by detail ("DoH"), never by name: the
        // v2.9.4 row counted name.contains("DoH") and always got 0/3
        // (DoH probe names are "DNS کلادفلر 1.1.1.1" etc.). The verdict is
        // split into DoH x/3 (with a per-provider reason) + UDP y/3.
        val (dohOk, dohTotal, dohReasons) = Lab.summarizeDoh(results)
        val (udpOk, udpTotal, udpReasons) = Lab.summarizeUdp(results)
        // v2.9.8 — environment verdict: 0/3 DoH while UDP DNS works is a
        // measured fact about the network (DoH is blocked here), not an
        // app failure. The FAIL stands (no DoH works), but the text says
        // plainly that the network blocks it.
        val dohEnv = if (dohOk == 0 && udpOk > 0) " — DoH روی این شبکه مسدود است (UDP DNS سالم است)" else ""
        iadd(rs, "DNS واقعی — DoH", "DoH Cloudflare/Google/Quad9 — پاسخ JSON با Status=0 و Answer غیرخالی", "حداقل یک DoH جواب بدهد", "$dohOk/$dohTotal DoH موفق • $dohReasons$dohEnv", dohOk > 0, if (dohOk > 0) "" else dohReasons)
        iadd(rs, "DNS واقعی — UDP", "UDP DNS به 1.1.1.1 و 8.8.8.8 :53 + DNS معمولی (اعتبارسنجی ID و rcode)", "حداقل یک UDP DNS جواب بدهد", "$udpOk/$udpTotal UDP موفق • $udpReasons", udpOk > 0, if (udpOk > 0) "" else udpReasons)
        iadd(rs, "IPv4", "HTTPS/TCP به 1.1.1.1 و 8.8.8.8", "IPv4 در دسترس", "${results.count { it.ok }} منبع موفق", results.any { it.ok })
        val v6 = results.find { it.name.contains("IPv6") }
        // v2.9.8 — a DNS-resolve failure means IPv6 cannot be measured
        // here at all (no IPv6 route / no IPv6 DNS): NOT TESTED, not FAIL.
        val v6Pass: Boolean? = when {
            v6 == null -> null
            !v6.ok && v6.note.contains("resolve") -> null
            else -> v6.ok
        }
        iadd(rs, "IPv6", "HTTPS به ipv6", "IPv6 در دسترس یا Not Tested", (v6?.note ?: "—") + if (v6Pass == null && v6 != null) " — IPv6 قابل اندازه‌گیری نیست" else "", v6Pass)
        iadd(rs, "HTTP/HTTPS", "GET به example.com و Cloudflare", "HTTPS 200", "$ok/${results.size} منبع موفق", ok > 0)
        // Share the probe results + intl/dom % with the later groups.
        val summary = NetworkEngine.summarize(results, "")
        integResults = results
        integIntlPct = summary.intlPct
        integDomPct = summary.domPct
    }

    // TLS handshake + TCP/UDP verdicts from the probe results.
    private fun integrationTlsGroup(rs: MutableList<TestResult>) {
        val tls = NetworkEngine.tls("cloudflare.com")
        iadd(rs, "TLS Handshake", "TLS به cloudflare.com:443", "Handshake موفق", if (tls.ok) "${tls.ms}ms" else tls.note, if (tls.ok) true else false)
        iadd(rs, "TCP", "TCP :443 به 1.1.1.1 و 8.8.8.8", "اتصال TCP موفق", "${integResults.count { it.name.contains("TCP") && it.ok }} موفق", integResults.any { it.name.contains("TCP") && it.ok })
        iadd(rs, "UDP", "UDP DNS به 1.1.1.1:53", "پاسخ UDP با ID درست", "${integResults.count { it.name.contains("UDP") && it.ok }} موفق", integResults.any { it.name.contains("UDP") && it.ok })
        // TLS evidence for the classifier: TCP connects but the TLS
        // handshake fails -> "TLS" filtering signal.
        val tcpOk = integResults.any { it.name.contains("TCP") && it.ok }
        if (tcpOk && !tls.ok) integFiltering = "TLS"
    }

    // IP-change detection + DNS error kinds (NXDOMAIN vs timeout vs
    // ID-mismatch — three distinct outcomes).
    private fun integrationIpDnsGroup(rs: MutableList<TestResult>) {
        // v2.9.5 — IP-change detection: the current public IP (api.ipify.org)
        // is compared with the last one stored by LAB (lab prefs, LAB-only).
        // The test validates DETECTION: a detected change is a PASS for the
        // detector, not a failure. The first run stores the IP (NOT TESTED).
        run {
            val curIp = fetchPublicIp()
            val p = getSharedPreferences("lab", Context.MODE_PRIVATE)
            val prevIp = p.getString("lab_last_public_ip", "") ?: ""
            if (curIp == null) {
                iadd(rs, "تشخیص تغییر IP", "IP عمومی فعلی (api.ipify.org) با IP ذخیره‌شده مقایسه شود", "IP خوانده شود و تغییر (یا عدم تغییر) تشخیص داده شود", "IP عمومی خوانده نشد (api.ipify.org جواب نداد)", false, "IP خوانده نشد")
            } else {
                p.edit().putString("lab_last_public_ip", curIp).apply()
                if (prevIp.isBlank()) {
                    iadd(rs, "تشخیص تغییر IP", "IP عمومی فعلی با IP ذخیره‌شده مقایسه شود", "اولین ثبت: IP ذخیره شود، تشخیص از اجرای بعد", "اولین ثبت IP عمومی: $curIp — تغییر در اجرای بعد قابل تشخیص است", null, "اولین ثبت IP")
                } else if (prevIp == curIp) {
                    iadd(rs, "تشخیص تغییر IP", "IP عمومی فعلی با IP ذخیره‌شده مقایسه شود", "IP بدون تغییر تشخیص داده شود", "IP بدون تغییر: $curIp", true)
                } else {
                    iadd(rs, "تشخیص تغییر IP", "IP عمومی فعلی با IP ذخیره‌شده مقایسه شود", "تغییر IP تشخیص داده شود", "IP تغییر کرد: $prevIp → $curIp", true)
                }
            }
        }
        // v2.9.5 — DNS error kinds: NXDOMAIN vs timeout vs ID-mismatch must
        // be distinguished (Diagnostics.udpDns notes). google.com must
        // answer, a .invalid name must be NXDOMAIN, and an unroutable
        // resolver (TEST-NET-1) must time out — three distinct outcomes.
        run {
            val okDns = Diagnostics.udpDns("1.1.1.1", "google.com")
            val nx = Diagnostics.udpDns("1.1.1.1", "does-not-exist-12345.invalid")
            val to = Diagnostics.udpDns("192.0.2.1", "google.com")
            // v2.9.8 — 192.0.2.1 is unroutable (TEST-NET-1): a valid answer
            // proves UDP/53 is intercepted on this network, so the
            // "timeout" case cannot be produced here at all. That is an
            // environment fact, not a test failure: NOT TESTED, with the
            // interception noted. (google.com + NXDOMAIN still measured.)
            val intercepted = to.ok
            val kindsOk = okDns.ok && nx.note.contains("NXDOMAIN") && !to.ok && !to.note.contains("NXDOMAIN")
            integDnsOk = okDns.ok
            val kindsPass: Boolean? = if (intercepted) null else kindsOk
            iadd(rs, "تشخیص خطاهای DNS", "google.com (موفق) • دامنه ناموجود .invalid (باید NXDOMAIN) • resolver غیرقابل‌دسترس 192.0.2.1 (باید timeout)", "سه نتیجه متمایز: موفق • NXDOMAIN • timeout/خطا", "google.com: ${if (okDns.ok) "OK" else okDns.note} • ناموجود: ${nx.note} • غیرقابل‌دسترس: ${to.note}" + if (intercepted) " • DNS رهگیری می‌شود: 192.0.2.1 پاسخ داد" else "", kindsPass, if (intercepted) "DNS رهگیری می‌شود — مورد timeout در این شبکه قابل‌تولید نیست" else if (kindsOk) "" else "انواع خطا تفکیک نشدند: google=${okDns.note} • nx=${nx.note} • timeout=${to.note}")
        }
    }

    // Traceroute + the REAL outage-classification check.
    private fun integrationTracerouteGroup(rs: MutableList<TestResult>) {
        val tr = NetworkEngine.traceroute("8.8.8.8")
        iadd(rs, "Traceroute", "ping TTL 1..8 تا 8.8.8.8", "حداقل یک Hop جواب بدهد", tr.lines().size.toString() + " خط", tr.contains("ms"))
        // v2.9.6 — REAL outage-classification check (the old row was
        // vacuous: `if (ok == 0) true else if (ok < size*0.4) true else
        // true` — always true, a fake pass). The classifier runs on the
        // REAL inputs (gateway ping, plain-DNS ok, intl/dom %, filtering
        // evidence) and the row PASSES only if its verdict is consistent
        // with the observed probe counts (Lab.classificationConsistent —
        // pure, JVM-tested). Honest note: on a network where DoH is
        // genuinely blocked the DoH row still FAILs — that is correct, not
        // a bug to fake away.
        run {
            val res = integResults
            if (res.isEmpty()) {
                rs.add(timed("تشخیص قطعی — طبقه‌بندی واقعی", "Quality.classifyDisruption با نتایج پروب‌ها + ورودی‌های واقعی", "طبقه‌بندی سازگار با شواهد مشاهده‌شده", "بدون نتایج پروب — طبقه‌بندی ممکن نیست", null, "بدون نتایج پروب (گروه Probes تایم‌اوت خورد یا خالی برگشت)"))
            } else {
                val gwIp = NetworkInfo.gateway(this)
                val gwOk = if (gwIp == "—") true else try { NetworkEngine.pingStats(gwIp).ok } catch (e: Exception) { true }
                val dnsOk = integDnsOk ?: res.any { it.category == "dns" && it.ok }
                val cls = Quality.classifyDisruption(res, integFiltering, gwOk, dnsOk, integIntlPct, integDomPct)
                val consistent = Lab.classificationConsistent(res, cls)
                val okN = res.count { it.ok }
                rs.add(timed("تشخیص قطعی — طبقه‌بندی واقعی", "Quality.classifyDisruption: gateway=$gwIp (ok=$gwOk) • DNS ok=$dnsOk • داخلی ${integDomPct}٪ • بین‌الملل ${integIntlPct}٪ • فیلترینگ: ${integFiltering.ifBlank { "—" }}", "طبقه‌بندی سازگار با شواهد مشاهده‌شده", "طبقه‌بندی: «${cls.type}» (${cls.confidence}٪) — ${cls.evidence} • شواهد: $okN/${res.size} پروب موفق • داخلی ${integDomPct}٪ • بین‌الملل ${integIntlPct}٪ • سازگار: $consistent", consistent, if (consistent) "" else "طبقه‌بندی «${cls.type}» با شواهد نمی‌خواند ($okN/${res.size} موفق)"))
            }
        }
    }

    // Backup to the private file, then restore; the backup must not
    // erase the original and the log must be unchanged.
    private fun integrationBackupGroup(rs: MutableList<TestResult>) {
        val beforeLog = History.log(this)
        val bf = History.backup(this)
        val afterLog = History.log(this)
        val restored = History.restore(this)
        iadd(rs, "Backup/Restore", "Backup به فایل، سپس Restore؛ Backup نباید اصل را پاک کند", "فایل موجود + Log قبل = بعد + Restore موفق", "فایل ${bf.length()} بایت • Log بدون تغییر: ${beforeLog == afterLog} • Restore: $restored", bf.exists() && bf.length() > 0 && beforeLog == afterLog && restored)
    }

    // Background service status + multi-host stress + QUIC tri-state.
    private fun integrationMultiGroup(rs: MutableList<TestResult>) {
        iadd(rs, "QUIC / HTTP/3", "UDP :443 بدون Handshake کامل", "نامشخص — بدون Handshake کامل نمی‌توان Pass داد", "NOT TESTED — گزارش صادقانه", null, "QUIC نیاز به Handshake کامل دارد")
        iadd(rs, "Background Service", "سرویس پس‌زمینه در حال اجراست؟ + اعلان", "وضعیت سرویس گزارش شود، نه حدس", "running=${MonitorService.running}", true)
        val mhTimes = mutableListOf<Long>(); val mhRam = mutableListOf<Long>()
        repeat(3) { val m = Quality.multiHost(); mhTimes.add(m.durationMs); val rt2 = Runtime.getRuntime(); mhRam.add((rt2.totalMemory() - rt2.freeMemory()) / 1024 / 1024) }
        iadd(rs, "Multi-Host Stress (۳ اجرای متوالی)", "هر اجرا: ۵ هاست × ۳ روش (ICMP/TCP/DNS)", "زمان و RAM در ۳ اجرا ثبت شود؛ CPU/باتری اندازه‌گیری نشده", "زمان: ${mhTimes.joinToString(", ")}ms • RAM: ${mhRam.joinToString(", ")}MB • CPU/باتری: اندازه‌گیری نشده (بدون Profiler)", true, "CPU و باتری با ابزار مناسب اندازه‌گیری نشده‌اند")
        val q = NetworkEngine.quic("1.1.1.1")
        // A genuine QUIC reply is a Success, not a failure: the old test only
        // accepted Unknown, so it FAILED on a real reply (the v2.4 LAB bug).
        iadd(rs, "QUIC Tri-State", "Success / Failed / Unknown با دلیل", "Unknown یا Success با دلیل، نه Failed قطعی", "${q.state} — ${q.reason}", q.state == "Unknown" || q.state == "Success")
    }

    private fun crossSection(): List<TestResult> {
        if (!hasNetwork()) {
            return listOf(timed("Cross Validation — بدون اینترنت", "بررسی اتصال شبکه پیش از تست", "بدون شبکه: تست SKIP شود، نه FAIL", "بدون اتصال شبکه — مقایسه ICMP/TCP اجرا نشد", null, "بدون اینترنت"))
        }
        val icmp = NetworkEngine.ping("1.1.1.1", 10)
        val tcp = NetworkEngine.tcpPing("1.1.1.1", 443, 10)
        return listOf(
            timed("Cross Validation — ICMP vs TCP", "ping (ICMP) در برابر TCP :443، هاست 1.1.1.1، ۱۰ بسته", "هر دو روش عدد بدهند؛ اختلاف روش در گزارش لحاظ شود", "ICMP avg ${"%.0f".format(icmp.avgMs)}ms (loss ${"%.0f".format(icmp.lossPct)}%) • TCP avg ${"%.0f".format(tcp.avgMs)}ms (loss ${"%.0f".format(tcp.lossPct)}%) — TCP معمولاً بالاتر است چون Handshake دارد، این اختلاف خطا نیست", icmp.received > 0 && tcp.received > 0),
            timed("Cross Validation — روش‌ها", "ICMP مسدود بود؟ TCP جایگزین", "اگر ICMP صفر بود، TCP جایگزین شود", if (icmp.received == 0) "ICMP مسدود — TCP استفاده شد" else "ICMP باز است", true)
        )
    }

    private fun stressSection(): List<TestResult> {
        if (!hasNetwork()) {
            return listOf(timed("Stress Tests — بدون اینترنت", "بررسی اتصال شبکه پیش از تست", "بدون شبکه: تست SKIP شود، نه FAIL", "بدون اتصال شبکه — استرس ۱ دقیقه‌ای اجرا نشد", null, "بدون اینترنت"))
        }
        val rt = Runtime.getRuntime()
        val memBefore = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        var drops = 0; var n = 0
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 60000) {
            val p = NetworkEngine.ping("1.1.1.1", 1); n++; if (p.received == 0) drops++
            try { Thread.sleep(2000) } catch (e: Exception) {}
        }
        val memAfter = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        return listOf(
            timed("Stress 1 دقیقه", "پینگ هر ۲ ثانیه به مدت ۶۰ ثانیه", "بدون Crash، بدون از دست رفتن تاریخچه", "$n پینگ، $drops قطعی کوتاه، RAM $memBefore → $memAfter MB", true),
            timed("Battery & Performance", "RAM و باتری قبل/بعد از استرس ۱ دقیقه‌ای", "RAM و باتری ثبت شوند؛ CPU دقیق بدون Profiler ممکن نیست", Quality.perfSnapshot(this), true),
            timed("Stress 1 ساعت", "اجرای مداوم ۱ ساعته", "نیاز به گوشی واقعی", "NOT TESTED", null, "زمان طولانی، روی این بیلد انجام نشده"),
            timed("Stress 6 / 24 ساعت", "اجرای مداوم ۶ و ۲۴ ساعته + باتری", "نیاز به گوشی واقعی", "NOT TESTED", null, "مصرف باتری/CPU در مانیتورینگ طولانی فقط روی گوشی واقعی قابل سنجش است"),
            timed("Widget", "به‌روزرسانی ویجت بعد از هر بررسی", "ویجت به‌روز شود", "در سرویس پس‌زمینه فراخوانی می‌شود؛ تست دستی روی گوشی لازم است", null, "نیاز به تست روی گوشی واقعی")
        )
    }

    private fun simSection(): List<TestResult> {
        val sim = Lab.computeStats(listOf(40.0, 45.0, 200.0, 42.0, 44.0), 10)
        return listOf(timed("Network Simulation — شبکه شبیه‌سازی‌شده", "SIMULATION: داده مصنوعی با Loss 50٪ و Spike 200ms", "Loss 50٪، Spike شناسایی شود", "Loss ${sim.lossPct}٪، Max ${sim.max}ms — شبیه‌سازی، نه شبکه واقعی", sim.lossPct == 50.0, "SIMULATION"))
    }

    private fun statsSection(): List<TestResult> {
        // v2.9.5 — expanded: Average, Median, P50/P90/P95/P99, Jitter,
        // Packet Loss and Standard Deviation on fixed data. Percentile
        // method (nearest-rank, rank = ceil(p/100*n)) is stated in every
        // percentile test's expected text.
        val data = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val st = Lab.computeStats(data, 5)
        val big = Lab.computeStats((1..100).map { it.toDouble() }, 100)
        val jit = Lab.computeStats(listOf(42.0, 48.0, 50.0, 55.0, 72.0), 5)
        val loss = Lab.computeStats(listOf(40.0, 45.0), 5)
        val evenMedian = Lab.computeStats(listOf(10.0, 20.0, 30.0, 40.0), 4).median
        return listOf(
            timed("Statistical Validation — Average", "ورودی ثابت [10,20,30,40,50]، ۵ بسته", "Avg 30 • Min 10 • Max 50", "Avg ${st.avg} • Min ${st.min} • Max ${st.max}", st.avg == 30.0 && st.min == 10.0 && st.max == 50.0),
            timed("Statistical Validation — Median", "ورودی ثابت [10,20,30,40,50] + [10,20,30,40] (زوج)", "Median فرد 30 • Median زوج 25", "فرد ${st.median} • زوج $evenMedian", st.median == 30.0 && evenMedian == 25.0),
            timed("Statistical Validation — P50/P90/P95/P99", "ورودی ثابت [10,20,30,40,50] — روش Percentile: nearest-rank، رتبه = ceil(p/100×n)", "P50 30 • P90 50 • P95 50 • P99 50", "P50 ${st.p50} • P90 ${st.p90} • P95 ${st.p95} • P99 ${st.p99}", st.p50 == 30.0 && st.p90 == 50.0 && st.p95 == 50.0 && st.p99 == 50.0),
            timed("Statistical Validation — Percentile 1..100", "۱۰۰ مقدار ۱ تا ۱۰۰ — روش Percentile: nearest-rank، رتبه = ceil(p/100×n)", "P50 50 • P90 90 • P95 95 • P99 99", "P50 ${big.p50} • P90 ${big.p90} • P95 ${big.p95} • P99 ${big.p99}", big.p50 == 50.0 && big.p90 == 90.0 && big.p95 == 95.0 && big.p99 == 99.0),
            timed("Statistical Validation — Jitter", "ورودی ثابت [42,48,50,55,72] — Jitter = میانگین قدرمطلق اختلاف‌های متوالی", "Jitter 7.5", "Jitter ${"%.1f".format(jit.jitter)}", jit.jitter == 7.5),
            timed("Statistical Validation — Packet Loss", "ورودی [42,48,50,55,72] با sent=5 و [40,45] با sent=5", "Loss 0٪ و 60٪", "Loss ${jit.lossPct}٪ • ${loss.lossPct}٪", jit.lossPct == 0.0 && loss.lossPct == 60.0),
            timed("Statistical Validation — Standard Deviation", "ورودی ثابت [10,20,30,40,50] — انحراف معیار جمعیتی (/n)، نه نمونه‌ای (n-1)", "StdDev √200 ≈ 14.1421", "StdDev ${"%.4f".format(st.stdDev)}", kotlin.math.abs(st.stdDev - 14.14213562) < 0.001)
        )
    }

    // Runs one section with a hard deadline. If it overruns, an honest TIMEOUT
    // result is recorded and the run moves on — no stage can stall the whole Lab.
    private fun runTimed(name: String, maxSec: Int, fn: () -> List<TestResult>): List<TestResult> {
        lastMarkMs.set(System.currentTimeMillis())
        val t0 = System.currentTimeMillis()
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = exec.submit<List<TestResult>> { fn() }
        return try {
            future.get(maxSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            listOf(TestResult("$name — تایم‌اوت", "سقف زمانی $maxSec ثانیه", "بخش باید در زمان مقرر تمام می‌شد", "بیش از $maxSec ثانیه طول کشید؛ به مرحله بعد رفتیم. تایم‌اوت صادقانه ثبت شد، نه Pass.", false, "TIMEOUT", History.timeStr(System.currentTimeMillis()), maxSec * 1000L))
        } catch (e: Exception) {
            listOf(TestResult("خطا در اجرای تست", "اجرای بخش", "بدون Crash", "خطا: ${e.message}", false, "", History.timeStr(System.currentTimeMillis()), System.currentTimeMillis() - t0))
        } finally {
            exec.shutdownNow()
        }
    }

    // v2.9.8 — safety net: if the user leaves the LAB screen mid-run,
    // the monitor stopped for the Unit section must come back.
    override fun onDestroy() {
        if (labPausedMonitor) {
            labPausedMonitor = false
            try { startForegroundService(Intent(this, MonitorService::class.java)) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun runSection(def: SectionDef, onDone: ((List<TestResult>) -> Unit)? = null) {
        setBadge(findViewById(def.badgeId), "RUNNING", "#F59E0B")
        // Lab sections run on the shared managed executor, like every other
        // background job — no raw Thread per tap any more.
        NetExecutor.submit {
            val rs = runSectionAll(def)
            // v2.9.5 — cleanup safety net: if the section timed out, its
            // finally-cleanup may never have run; remove only the
            // identifiable LAB test rows here (never real user rows).
            if (def.name == "Unit Tests") cleanupLabRows()
            NetExecutor.runOnMain { showResults(rs, def.name); val b = badgeFor(rs); setBadge(findViewById(def.badgeId), b.first, b.second); onDone?.invoke(rs) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        History.ensureRecovered(this)
        setContentView(R.layout.activity_lab)
        tv = findViewById(R.id.tvLab)
        tv.movementMethod = LinkMovementMethod.getInstance()
        tvSummary = findViewById(R.id.tvLabSummary)
        tv.text = "NETWATCH LAB — تست‌های شبیه‌سازی‌شده جدا از تست‌های واقعی شبکه ثبت می‌شوند.\nتست ناموفق پنهان نمی‌شود؛ فقط اجرای موفق کد به معنی Pass نیست.\n\nهر ردیف چیست: Unit = منطق خالص بدون شبکه (آفلاین) • Integration = تست‌های واقعی شبکه، نیازمند اینترنت • Cross = مقایسه روش‌ها روی یک مقصد • Stress = تکرار Multi-Host • Simulation = شبیه‌سازی شبکه (SIMULATION) • Stats = اعتبارسنجی آماری.\n\nروی هر نتیجه بزن تا جزئیات آن (مدت، ورودی، مورد انتظار، واقعی، علت خطا) را ببینی و همان تست را دوباره اجرا کنی."
        updateSummary()
        val byName = sections.associateBy { it.name }
        findViewById<LinearLayout>(R.id.btnLabUnit).setOnClickListener {
            val def = byName.getValue("Unit Tests")
            runSection(def) { rs ->
                Toast.makeText(this, "Unit (۵ گروه): ${rs.count { it.pass == true }}/${rs.size} Pass", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<LinearLayout>(R.id.btnLabIntegration).setOnClickListener {
            tv.text = "در حال اجرای تست‌های واقعی شبکه…"
            val def = byName.getValue("Integration Tests")
            runSection(def)
        }
        findViewById<LinearLayout>(R.id.btnLabCross).setOnClickListener {
            tv.text = "Cross Validation در حال اجراست… (یک دستگاه، یک اتصال، یک مقصد، بازه نزدیک)"
            val def = byName.getValue("Cross Validation")
            runSection(def)
        }
        findViewById<LinearLayout>(R.id.btnLabStress).setOnClickListener {
            tv.text = "تست استرس ۱ دقیقه‌ای در حال اجراست… (تست‌های ۱/۶/۲۴ ساعته روی گوشی واقعی و زمان طولانی نیاز دارند — NOT TESTED)"
            val def = byName.getValue("Stress Tests")
            runSection(def)
        }
        findViewById<LinearLayout>(R.id.btnLabSim).setOnClickListener {
            val def = byName.getValue("Network Simulation")
            runSection(def)
        }
        findViewById<LinearLayout>(R.id.btnLabStats).setOnClickListener {
            val def = byName.getValue("Statistical Validation")
            runSection(def)
        }

        // RUN ALL TESTS — runs every section in order, one background thread.
        // Grouped sections (Unit, Integration) run group by group, each with
        // its own cap from the sections registry; a group's timeout keeps
        // its finished results and moves on to the next group.
        findViewById<Button>(R.id.btnLabAll).setOnClickListener {
            val btn = it as Button
            btn.isEnabled = false
            allResults.clear()
            displayed.clear()
            sections.forEach { def -> setBadge(findViewById(def.badgeId), "—", "#94A3B8") }
            tv.text = "در حال اجرای همه تست‌ها… (حداکثر حدود ۱۹ دقیقه: Unit تا ۵۷۰ ثانیه در ۵ گروه ۶۰+۹۰+۱۲۰+۱۲۰+۱۸۰، Integration تا ۳۷۵ ثانیه در ۶ گروه ۹۰+۳۰+۴۵+۶۰+۶۰+۹۰، Cross ۹۰، Stress ۷۵، Sim ۱۰، Stats ۱۰ — هر گروه سقف خودش را دارد و گیر نمی‌کند)"
            Toast.makeText(this, "همه تست‌ها در حال اجراست — هر گروه سقف زمانی خودش را دارد و گیر نمی‌کند", Toast.LENGTH_LONG).show()
            val t0 = System.currentTimeMillis()
            NetExecutor.submit {
                try {
                    for ((index, def) in sections.withIndex()) {
                        // v2.9.7 — the section badge is set ONCE, from the
                        // section's COMPLETE results (all its groups). In
                        // v2.9.6 the badge was set per group, so the final
                        // badge was only the LAST group's badge and hid
                        // earlier groups' FAILs.
                        val sectionResults = mutableListOf<TestResult>()
                        try {
                            val groups = def.groups?.invoke()
                            if (groups == null) {
                                NetExecutor.runOnMain {
                                    setBadge(findViewById(def.badgeId), "RUNNING", "#F59E0B")
                                    tv.text = "مرحله ${index + 1} از ۶: ${def.name} در حال اجراست… (سقف ${def.maxSec} ثانیه — اگر بیشتر طول کشید، تایم‌اوت ثبت می‌شود و به مرحله بعد می‌رویم)"
                                }
                                val rs = runTimed(def.name, def.maxSec, def.fn)
                                sectionResults.addAll(rs)
                                if (def.name == "Unit Tests") cleanupLabRows()
                                NetExecutor.runOnMain { allResults.addAll(rs); renderOnly(rs, def.name) }
                            } else {
                                for ((gIndex, g) in groups.withIndex()) {
                                    NetExecutor.runOnMain {
                                        setBadge(findViewById(def.badgeId), "RUNNING", "#F59E0B")
                                        tv.text = "مرحله ${index + 1} از ۶: ${def.name} — گروه ${gIndex + 1} از ${groups.size}: ${g.name} در حال اجراست… (سقف گروه ${g.maxSec} ثانیه — اگر بیشتر طول کشید، تایم‌اوت ثبت می‌شود و به گروه بعد می‌رویم)"
                                    }
                                    val rs = runGroup(g)
                                    sectionResults.addAll(rs)
                                    if (def.name == "Unit Tests") cleanupLabRows()
                                    NetExecutor.runOnMain { allResults.addAll(rs); renderOnly(rs, def.name) }
                                }
                            }
                        } catch (e: Exception) {
                            // One section crashing must never abort RUN ALL:
                            // record an honest error row and move on.
                            val err = TestResult("${def.name} — خطا در اجرا", "اجرای بخش", "بدون Crash", "خطا: ${e.message}", false, "", History.timeStr(System.currentTimeMillis()), 0)
                            sectionResults.add(err)
                            NetExecutor.runOnMain { allResults.add(err); renderOnly(listOf(err), def.name) }
                        }
                        val b = badgeFor(sectionResults)
                        NetExecutor.runOnMain { setBadge(findViewById(def.badgeId), b.first, b.second) }
                    }
                } finally {
                    NetExecutor.runOnMain {
                        Lab.save(this, allResults)
                        Lab.saveRun(this, appVersion(), "RUN ALL", allResults)
                        val secs = (System.currentTimeMillis() - t0) / 1000
                        // v2.9.7 — "all tests finished" only when every test
                        // reached a final status (Lab.runAllSummary). With
                        // untested or timed-out tests, the message says so
                        // instead of claiming completion.
                        val s = Lab.runAllSummary(allResults)
                        val msg = if (s.complete) "همه تست‌ها تمام شد: ${s.passed}/${s.total} Pass — ${secs} ثانیه"
                            else "اجرا پایان یافت ولی کامل نیست: ${s.passed}/${s.total} Pass • ${s.failed} ناموفق • ${s.untested} تست‌نشده • ${s.timeouts} تایم‌اوت — ${secs} ثانیه"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                    }
                }
            }
        }
        findViewById<Button>(R.id.btnLabExport).setOnClickListener {
            Toast.makeText(this, "گزارش LAB در حال ساخت است…", Toast.LENGTH_SHORT).show()
            // v2.9.6 — export runs on a background thread; the result dialog
            // (exact filenames, Downloads/NetWatch path, Open folder, Share
            // PDF/JSON, exact error cause) shows on the main thread.
            NetExecutor.submitRestore {
                val labJson = "{\"lab_results\":\"" + Lab.load(this).replace("\"", "'").replace("\n", "\\n") + "\"}"
                val jsonResult = ExportHelper.save(this, "NETWATCH_Lab", "json", "application/json") { it.write(labJson.toByteArray(Charsets.UTF_8)) }
                val pdfResult = History.exportPdf(this)
                NetExecutor.runOnMain {
                    if (!isFinishing && !isDestroyed) {
                        ExportHelper.showExportDialog(this, "گزارش LAB", listOf("PDF" to pdfResult, "JSON" to jsonResult))
                    }
                }
            }
        }
    }
}
