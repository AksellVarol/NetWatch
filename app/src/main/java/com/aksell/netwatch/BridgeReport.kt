package com.aksell.netwatch

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// BridgeReport (Phase 2): builds the diagnostic report (JSON Schema v1 +
// readable text, spec §11/§12) and the health-row mapping for the Bridge
// page. Pure functions only: no Context, no I/O, no Android APIs except
// org.json — so everything here is JVM-testable.
//
// The main report NEVER contains network details; the network supplement
// is a separate object, built only with the user's explicit per-export
// consent, and it is never merged into the main report and never goes to
// the Clipboard.
object BridgeReport {
    const val SCHEMA_VERSION = 1
    const val SANITIZER_VERSION = 1

    // UI-only chip for a health row. OK is a display state only: it is
    // never written into the report JSON — the Schema has exactly the
    // four statuses ERROR / WARNING / UNKNOWN / NOT_RUN.
    enum class HealthChip { OK, ERROR, WARNING, UNKNOWN, NOT_RUN }
    data class HealthRow(val label: String, val chip: HealthChip, val detail: String)

    // Approved thresholds (Phase 2 decisions): both map to WARNING, never ERROR.
    const val HEARTBEAT_STALE_S = 120L
    const val STORAGE_LOW_MB = 100L

    fun isoTime(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(ms))
    }

    private fun moduleSourceSchema(src: Bridge.ModuleSource): String = when (src) {
        Bridge.ModuleSource.EXPLICIT -> "explicit"
        Bridge.ModuleSource.STACK_TRACE_INFERRED -> "stack_trace_inferred"
        Bridge.ModuleSource.UNKNOWN -> "unknown"
    }

    private fun locationSourceSchema(src: Bridge.LocationSource): String = when (src) {
        Bridge.LocationSource.STACK_TRACE -> "stack_trace"
        Bridge.LocationSource.NONE -> "none"
    }

    // ---- health-row mapping (pure; the page renders these rows) ----
    fun healthRows(h: Bridge.Health): List<HealthRow> {
        val rows = ArrayList<HealthRow>()
        // Service. Note: healthSnapshot sets monitoringOn == serviceRunning
        // today, so the ERROR branch cannot fire yet — but the rule is the
        // correct one (spec §5: service death while monitoring is on), and
        // the mapping stays correct if the snapshot ever distinguishes them.
        when {
            h.monitoringOn && !h.serviceRunning -> rows.add(HealthRow("سرویس مانیتورینگ", HealthChip.ERROR, "مانیتورینگ روشن است ولی سرویس در حال اجرا نیست"))
            !h.monitoringOn && !h.serviceRunning -> rows.add(HealthRow("سرویس مانیتورینگ", HealthChip.NOT_RUN, "مانیتورینگ خاموش است"))
            else -> rows.add(HealthRow("سرویس مانیتورینگ", HealthChip.OK, "سرویس در حال اجراست"))
        }
        // Heartbeat
        val hb = h.lastHeartbeatAgeS
        when {
            hb == null -> rows.add(HealthRow("Heartbeat", HealthChip.UNKNOWN, "هنوز heartbeat ثبت نشده است"))
            hb > HEARTBEAT_STALE_S -> rows.add(HealthRow("Heartbeat", HealthChip.WARNING, "heartbeat کهنه است — $hb ثانیه پیش"))
            else -> rows.add(HealthRow("Heartbeat", HealthChip.OK, "$hb ثانیه پیش"))
        }
        // Last check
        if (h.lastCheckTime == null) {
            rows.add(HealthRow("آخرین بررسی", HealthChip.NOT_RUN, "هنوز بررسی کامل انجام نشده است"))
        } else {
            val avg = h.lastCheckPingAvg?.let { "%.0f".format(it) } ?: "—"
            val loss = h.lastCheckLossPct?.let { "%.1f".format(it) } ?: "—"
            rows.add(HealthRow("آخرین بررسی", HealthChip.OK, "پینگ $avg ms • قطعی $loss٪ • بین‌الملل ${h.lastCheckIntlPct ?: "—"}٪ • داخلی ${h.lastCheckDomPct ?: "—"}٪ • امتیاز ${h.lastCheckScore ?: "—"} • ${Bridge.timeStr(h.lastCheckTime)}"))
        }
        // Widget
        val wAge = h.widgetLastRefreshAgeS
        when {
            !h.monitoringOn -> rows.add(HealthRow("ویجت", HealthChip.NOT_RUN, "مانیتورینگ خاموش است؛ ویجت به‌روز نمی‌شود"))
            wAge == null -> rows.add(HealthRow("ویجت", HealthChip.UNKNOWN, "زمان آخرین رفرش ویجت ثبت نشده است"))
            wAge > HEARTBEAT_STALE_S -> rows.add(HealthRow("ویجت", HealthChip.WARNING, "رفرش ویجت کهنه است — $wAge ثانیه پیش"))
            else -> rows.add(HealthRow("ویجت", HealthChip.OK, "$wAge ثانیه پیش به‌روز شده است"))
        }
        // Database
        when (h.dbIntegrity) {
            "fail" -> rows.add(HealthRow("دیتابیس", HealthChip.ERROR, "integrity_check ناموفق: ${h.dbIntegrityDetail}"))
            "ok" -> rows.add(HealthRow("دیتابیس", HealthChip.OK, "سالم • نمونه‌ها ${h.dbSamples ?: "—"} • پینگ ${h.dbPingPoints ?: "—"} • جلسات گیمینگ ${h.dbGamingSessions ?: "—"}"))
            else -> rows.add(HealthRow("دیتابیس", HealthChip.UNKNOWN, "بررسی integrity انجام نشده است"))
        }
        // Restore journal
        when (h.restoreJournalState) {
            "STARTED" -> rows.add(HealthRow("Restore", HealthChip.WARNING, "ژورنال بازیابی در حالت STARTED است — بازیابی ناتمام؛ داده سالم است و در دسترسی بعدی بازیابی می‌شود" + if (h.lastRestoreDiag.isNotBlank()) " • ${h.lastRestoreDiag}" else ""))
            "COMMITTED", "NONE" -> rows.add(HealthRow("Restore", HealthChip.OK, "ژورنال: ${h.restoreJournalState}" + if (h.lastRestoreDiag.isNotBlank()) " • ${h.lastRestoreDiag}" else ""))
            else -> rows.add(HealthRow("Restore", HealthChip.UNKNOWN, "وضعیت ژورنال خوانده نشد"))
        }
        // Gaming
        if (h.lastGamingSession == "none") rows.add(HealthRow("گیمینگ", HealthChip.NOT_RUN, "هنوز جلسهٔ گیمینگ ثبت نشده است"))
        else rows.add(HealthRow("گیمینگ", HealthChip.OK, h.lastGamingSession))
        // LAB
        val lf = h.lastLabFailed
        when {
            h.lastLabRun == "none" -> rows.add(HealthRow("LAB", HealthChip.NOT_RUN, "هنوز اجرای LAB ثبت نشده است"))
            h.lastLabRun == "unreadable" -> rows.add(HealthRow("LAB", HealthChip.UNKNOWN, "نتیجهٔ LAB خوانده نشد"))
            lf != null && lf > 0 -> rows.add(HealthRow("LAB", HealthChip.WARNING, "${h.lastLabRun} — ردیف‌های FAIL در بخش خطاها هستند"))
            else -> rows.add(HealthRow("LAB", HealthChip.OK, h.lastLabRun))
        }
        // Storage
        val free = h.storageFreeMb
        when {
            free == null -> rows.add(HealthRow("فضای آزاد", HealthChip.UNKNOWN, "فضای آزاد خوانده نشد"))
            free < STORAGE_LOW_MB -> rows.add(HealthRow("فضای آزاد", HealthChip.WARNING, "فقط $free MB فضای آزاد است"))
            else -> rows.add(HealthRow("فضای آزاد", HealthChip.OK, "$free MB"))
        }
        return rows
    }

    // ---- report JSON (Schema v1, spec §11) ----
    fun buildReportJson(
        events: List<Bridge.Event>,
        health: Bridge.Health?,
        crashes: List<Bridge.CrashSummary>,
        labFailures: List<TestResult>,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidSdk: Int,
        generatedAtMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val o = JSONObject()
        o.put("schema_version", SCHEMA_VERSION)
        o.put("generated_at", isoTime(generatedAtMs))
        val app = JSONObject()
        app.put("version", appVersion)
        app.put("version_code", versionCode)
        app.put("package", Bridge.PKG)
        o.put("app", app)
        val dev = JSONObject()
        dev.put("model", deviceModel)
        dev.put("android_sdk", androidSdk)
        o.put("device", dev)
        val counts = Bridge.summaryCounts(events)
        val summary = JSONObject()
        summary.put("error", counts[Bridge.Status.ERROR] ?: 0)
        summary.put("warning", counts[Bridge.Status.WARNING] ?: 0)
        summary.put("unknown", counts[Bridge.Status.UNKNOWN] ?: 0)
        summary.put("not_run", counts[Bridge.Status.NOT_RUN] ?: 0)
        o.put("summary", summary)
        val arr = JSONArray()
        for (ev in events) {
            val jo = Bridge.eventToJson(ev)
            // Schema v1 uses lowercase for these two enums (storage keeps
            // the uppercase enum names). The message is sanitized again
            // (defense in depth — sanitize is idempotent).
            jo.put("module_source", moduleSourceSchema(ev.moduleSource))
            jo.put("location_source", locationSourceSchema(ev.locationSource))
            jo.put("message", Bridge.sanitize(ev.message))
            arr.put(jo)
        }
        o.put("events", arr)
        o.put("health", healthJson(health, labFailures))
        // The main report NEVER carries network details.
        o.put("network_details_included", false)
        o.put("sanitizer_version", SANITIZER_VERSION)
        return o
    }

    private fun healthJson(h: Bridge.Health?, labFailures: List<TestResult>): JSONObject {
        val o = JSONObject()
        // No snapshot yet: an empty object is the honest answer — never
        // invent a service state that was not measured.
        if (h == null) return o
        o.put("service_running", h.serviceRunning)
        o.put("monitoring_on", h.monitoringOn)
        if (h.lastHeartbeatAgeS != null) o.put("last_heartbeat_age_s", h.lastHeartbeatAgeS) else o.put("last_heartbeat_age_s", JSONObject.NULL)
        if (h.lastCheckTime != null) {
            val lc = JSONObject()
            if (h.lastCheckPingAvg != null) lc.put("ping_avg", h.lastCheckPingAvg) else lc.put("ping_avg", JSONObject.NULL)
            if (h.lastCheckLossPct != null) lc.put("loss_pct", h.lastCheckLossPct) else lc.put("loss_pct", JSONObject.NULL)
            if (h.lastCheckIntlPct != null) lc.put("intl_pct", h.lastCheckIntlPct) else lc.put("intl_pct", JSONObject.NULL)
            if (h.lastCheckDomPct != null) lc.put("dom_pct", h.lastCheckDomPct) else lc.put("dom_pct", JSONObject.NULL)
            if (h.lastCheckScore != null) lc.put("score", h.lastCheckScore) else lc.put("score", JSONObject.NULL)
            lc.put("time", h.lastCheckTime)
            o.put("last_check", lc)
        } else {
            o.put("last_check", JSONObject.NULL)
        }
        if (h.widgetLastRefreshAgeS != null) o.put("widget_last_refresh_age_s", h.widgetLastRefreshAgeS) else o.put("widget_last_refresh_age_s", JSONObject.NULL)
        o.put("db_integrity", h.dbIntegrity)
        val counts = JSONObject()
        if (h.dbSamples != null) counts.put("samples", h.dbSamples) else counts.put("samples", JSONObject.NULL)
        if (h.dbPingPoints != null) counts.put("ping_points", h.dbPingPoints) else counts.put("ping_points", JSONObject.NULL)
        if (h.dbGamingSessions != null) counts.put("gaming_sessions", h.dbGamingSessions) else counts.put("gaming_sessions", JSONObject.NULL)
        o.put("db_counts", counts)
        o.put("restore_journal_state", h.restoreJournalState)
        if (h.lastRestoreDiag.isNotBlank()) o.put("last_restore_diag", h.lastRestoreDiag) else o.put("last_restore_diag", JSONObject.NULL)
        if (h.lastLabTotal != null) {
            val lab = JSONObject()
            lab.put("total", h.lastLabTotal)
            lab.put("passed", h.lastLabPassed ?: JSONObject.NULL)
            lab.put("failed", h.lastLabFailed ?: JSONObject.NULL)
            lab.put("not_tested", h.lastLabNotTested ?: JSONObject.NULL)
            val failing = JSONArray()
            for (f in labFailures) failing.put(f.name)
            lab.put("failing_tests", failing)
            o.put("last_lab_run", lab)
        } else {
            o.put("last_lab_run", JSONObject.NULL)
        }
        if (h.storageFreeMb != null) o.put("storage_free_mb", h.storageFreeMb) else o.put("storage_free_mb", JSONObject.NULL)
        return o
    }

    // ---- readable report (spec §12, same data as the JSON) ----
    fun buildReadableText(
        events: List<Bridge.Event>,
        health: Bridge.Health?,
        crashes: List<Bridge.CrashSummary>,
        labFailures: List<TestResult>,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidSdk: Int,
        generatedAtMs: Long = System.currentTimeMillis()
    ): String {
        val counts = Bridge.summaryCounts(events)
        val sb = StringBuilder()
        // 1. Header
        sb.append("NETWATCH — MUSE BRIDGE Report\n")
        sb.append("نسخهٔ اپ: $appVersion (code $versionCode) • زمان تولید: ${isoTime(generatedAtMs)} • Schema v$SCHEMA_VERSION\n")
        sb.append("دستگاه: $deviceModel • Android SDK $androidSdk\n\n")
        // 2. Summary counts
        sb.append("خلاصه: خطای قطعی ${counts[Bridge.Status.ERROR] ?: 0} • هشدار ${counts[Bridge.Status.WARNING] ?: 0} • نامشخص ${counts[Bridge.Status.UNKNOWN] ?: 0} • اجرا نشده ${counts[Bridge.Status.NOT_RUN] ?: 0}\n\n")
        // 3. Definite errors (ERROR only — never UNKNOWN/NOT_RUN)
        sb.append("== خطاهای قطعی ==\n")
        val errors = events.filter { it.status == Bridge.Status.ERROR }
        if (errors.isEmpty()) {
            sb.append("هیچ خطای قطعی ثبت نشده است.\n")
        } else {
            for (ev in errors) {
                sb.append("[${ev.module}] ${ev.file ?: "—"}${if (ev.line != null) ":${ev.line}" else ""} ${ev.function ?: ""} — ${ev.kind}\n")
                sb.append("پیام: ${Bridge.sanitize(ev.message)}\n")
                sb.append("تکرار: ${ev.occurrences} • اولین: ${Bridge.timeStr(ev.firstSeen)} • آخرین: ${Bridge.timeStr(ev.lastSeen)}\n")
                if (ev.stackTrace.isNotEmpty()) {
                    sb.append("Stack (۱۰ فریم اول):\n")
                    for (fr in ev.stackTrace.take(10)) sb.append("  ${fr.cls}.${fr.method}(${fr.file ?: "?"}:${fr.line})\n")
                }
                if (ev.breadcrumbs.isNotEmpty()) {
                    sb.append("Breadcrumbs:\n")
                    for (b in ev.breadcrumbs) sb.append("  $b\n")
                }
                sb.append("\n")
            }
        }
        // 4. Warnings
        sb.append("== هشدارها ==\n")
        val warns = events.filter { it.status == Bridge.Status.WARNING }
        if (warns.isEmpty()) sb.append("هشداری ثبت نشده است.\n")
        else for (ev in warns) sb.append("[${ev.module}] ${Bridge.sanitize(ev.message)}\n")
        sb.append("\n")
        // 5. Unknown (never bugs — only with the reason)
        sb.append("== وضعیت‌های نامشخص ==\n")
        sb.append("این‌ها باگ نیستند — فقط اندازه‌گیری نشده‌اند.\n")
        val unks = events.filter { it.status == Bridge.Status.UNKNOWN }
        if (unks.isEmpty()) sb.append("مورد نامشخصی نیست.\n")
        else for (ev in unks) sb.append("[${ev.module}] ${Bridge.sanitize(ev.message)}\n")
        sb.append("\n")
        // 6. Not run
        sb.append("== تست‌های اجرا نشده ==\n")
        val notRun = events.filter { it.status == Bridge.Status.NOT_RUN }
        if (notRun.isEmpty()) sb.append("مورد اجرا نشده‌ای نیست.\n")
        else for (ev in notRun) sb.append("[${ev.module}] ${Bridge.sanitize(ev.message)}\n")
        sb.append("\n")
        // 7. Health snapshot
        sb.append("== عکس سلامت ==\n")
        if (health == null) {
            sb.append("هنوز بررسی سلامت انجام نشده است.\n")
        } else {
            for (row in healthRows(health)) {
                val chip = when (row.chip) {
                    HealthChip.OK -> "سالم"
                    HealthChip.ERROR -> "ERROR"
                    HealthChip.WARNING -> "WARNING"
                    HealthChip.UNKNOWN -> "UNKNOWN"
                    HealthChip.NOT_RUN -> "NOT_RUN"
                }
                sb.append("${row.label}: [$chip] ${row.detail}\n")
            }
        }
        sb.append("\n")
        // 8. LAB failures
        sb.append("== خرابی‌های LAB ==\n")
        if (labFailures.isEmpty()) {
            sb.append("ردیف FAILای در آخرین اجرای LAB نیست.\n")
        } else {
            for (f in labFailures) {
                sb.append("${f.name}\n  موردانتظار: ${f.expected}\n  واقعی: ${f.actual}\n")
                if (f.error.isNotBlank()) sb.append("  خطا: ${f.error}\n")
            }
        }
        sb.append("\n")
        // 9. Footnote
        sb.append("== پانوشت ==\n")
        sb.append("گزارش با پاک‌ساز نسخهٔ $SANITIZER_VERSION ساخته شد؛ جزئیات شبکه در این گزارش نیست (network_details_included=false).\n")
        sb.append("Crashهای ثبت‌شده: ${crashes.size} فایل (حداکثر ۵ فایل نگه داشته می‌شود).\n")
        return sb.toString()
    }

    // ---- network supplement (separate file, consent per export) ----
    // Values must already be resolved to null when unavailable (Android 10+
    // SSID restriction, unanswered GeoIP, missing gateway/DNS). Null is
    // written as "unavailable" — never faked, never guessed.
    fun buildNetworkSupplement(
        publicIp: String?,
        gateway: String?,
        dnsServers: String?,
        ssid: String?,
        generatedAtMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val o = JSONObject()
        o.put("schema_version", SCHEMA_VERSION)
        o.put("generated_at", isoTime(generatedAtMs))
        o.put("label", "NETWORK SUPPLEMENT — contains network details; built only with the user's explicit consent for this export")
        val net = JSONObject()
        net.put("public_ip", publicIp ?: "unavailable")
        net.put("gateway", gateway ?: "unavailable")
        net.put("dns_servers", dnsServers ?: "unavailable")
        net.put("ssid", ssid ?: "unavailable")
        o.put("network", net)
        return o
    }

    // ---- crash detail (for the dialog; a corrupt file never crashes the page) ----
    fun crashDetailText(crashJsonText: String): String {
        return try {
            val jo = JSONObject(crashJsonText)
            val sb = StringBuilder()
            val ts = jo.optLong("ts", 0)
            sb.append("زمان: ${if (ts > 0) Bridge.timeStr(ts) else "—"}\n")
            sb.append("ماژول: ${jo.optString("module", "—")} (${jo.optString("module_source", "—")})\n")
            sb.append("Exception: ${jo.optString("exception", "—")}\n")
            sb.append("پیام: ${jo.optString("message", "—")}\n")
            val file = if (jo.isNull("file")) null else jo.optString("file")
            val line = if (jo.isNull("line")) null else jo.optInt("line")
            val fn = if (jo.isNull("function")) null else jo.optString("function")
            sb.append("محل: ${file ?: "—"}${if (line != null) ":$line" else ""} • ${fn ?: "—"}\n")
            sb.append("امضا: ${jo.optString("stack_signature", "—")}\n")
            val st = jo.optJSONArray("stack_trace")
            if (st != null && st.length() > 0) {
                sb.append("\nStack Trace:\n")
                for (i in 0 until st.length()) {
                    val fo = st.optJSONObject(i) ?: continue
                    sb.append("  ${fo.optString("class", "?")}.${fo.optString("method", "?")}(${fo.optString("file", "?")}:${fo.optInt("line", -1)})\n")
                }
            }
            val ca = jo.optJSONArray("caused_by")
            if (ca != null && ca.length() > 0) {
                sb.append("\nCause chain:\n")
                for (i in 0 until ca.length()) {
                    val cjo = ca.optJSONObject(i) ?: continue
                    sb.append("  ${cjo.optString("class", "?")}: ${cjo.optString("message", "")}\n")
                }
            }
            val ba = jo.optJSONArray("breadcrumbs")
            if (ba != null && ba.length() > 0) {
                sb.append("\nBreadcrumbs:\n")
                for (i in 0 until ba.length()) sb.append("  ${ba.optString(i, "")}\n")
            }
            sb.toString().trim()
        } catch (_: Exception) {
            "جزئیات این Crash خوانده نشد (فایل خراب است)."
        }
    }

    // ================= JVM self-tests (pure only) =================
    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "BridgeReport — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = Bridge.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        fun ev(module: Bridge.Module, status: Bridge.Status, kind: Bridge.Kind, msg: String, src: Bridge.ModuleSource = Bridge.ModuleSource.EXPLICIT): Bridge.Event {
            return Bridge.Event(
                eventId = "t-${module.name}-${status.name}-${msg.hashCode()}", ts = 1762795200000L,
                module = module, moduleSource = src, status = status, kind = kind,
                message = msg, file = "History.kt", line = 412, function = "phaseB",
                locationSource = Bridge.LocationSource.STACK_TRACE,
                stackTrace = listOf(Bridge.Frame("com.aksell.netwatch.History", "phaseB", "History.kt", 412)),
                stackSignature = "sig-${msg.hashCode()}", occurrences = 1,
                firstSeen = 1762795200000L, lastSeen = 1762795200000L,
                breadcrumbs = listOf("19:29:58 [RESTORE_START] restore started"),
                context = emptyMap(), causedBy = emptyList()
            )
        }
        fun health(
            serviceRunning: Boolean = true, monitoringOn: Boolean = true,
            hbAge: Long? = 10L, lastCheckTime: Long? = 1762795200000L,
            widgetAge: Long? = 20L, integrity: String = "ok",
            journal: String = "NONE", gaming: String = "none",
            labRun: String = "none", labFailed: Int? = null, freeMb: Long? = 500L
        ): Bridge.Health {
            return Bridge.Health(
                serviceRunning = serviceRunning, monitoringOn = monitoringOn,
                lastHeartbeatAgeS = hbAge, lastCheckPingAvg = 50.0, lastCheckLossPct = 0.0,
                lastCheckIntlPct = 74, lastCheckDomPct = 16, lastCheckScore = 80,
                lastCheckTime = lastCheckTime, widgetLastRefreshAgeS = widgetAge,
                dbIntegrity = integrity, dbIntegrityDetail = "", dbSamples = 100L,
                dbPingPoints = 200L, dbGamingSessions = 3L, dbGamingSamplesLast = 40L,
                restoreJournalState = journal, lastRestoreDiag = "",
                lastGamingSession = gaming, lastLabRun = labRun,
                lastLabTotal = null, lastLabPassed = null, lastLabFailed = labFailed,
                lastLabNotTested = null, storageFreeMb = freeMb
            )
        }
        val fixedTs = 1762795200000L
        val events = listOf(
            ev(Bridge.Module.RESTORE, Bridge.Status.ERROR, Bridge.Kind.RESTORE_ABORT, "restore boom"),
            ev(Bridge.Module.NETWORK, Bridge.Status.ERROR, Bridge.Kind.EXCEPTION, "net boom"),
            ev(Bridge.Module.WIDGET, Bridge.Status.WARNING, Bridge.Kind.WIDGET_STALE, "widget stale"),
            ev(Bridge.Module.LAB, Bridge.Status.UNKNOWN, Bridge.Kind.LAB_FAIL, "lab unknown"),
            ev(Bridge.Module.GAMING, Bridge.Status.NOT_RUN, Bridge.Kind.EXCEPTION, "gaming not run")
        )
        val h = health()
        val json = buildReportJson(events, h, emptyList(), emptyList(), "2.9.8", 32, "SM-A528B", 34, fixedTs)
        t("Report — کلیدهای Schema v1", "schema_version 1, app, device, summary, events, health", "keys=${json.keys().asSequence().toList().sorted()}", json.optInt("schema_version", -1) == 1 && json.has("app") && json.has("device") && json.has("summary") && json.has("events") && json.has("health") && json.optInt("sanitizer_version", -1) == 1 && json.optBoolean("network_details_included", true) == false)
        val sum = json.optJSONObject("summary")
        t("Report — شمارش خلاصه", "error 2 / warning 1 / unknown 1 / not_run 1", "sum=${sum?.toString()}", sum != null && sum.optInt("error") == 2 && sum.optInt("warning") == 1 && sum.optInt("unknown") == 1 && sum.optInt("not_run") == 1)
        t("Report — generated_at قطعی", "isoTime($fixedTs)", "gen=${json.optString("generated_at")}", json.optString("generated_at") == isoTime(fixedTs))
        val evArr = json.optJSONArray("events")
        var statusOk = evArr != null && evArr.length() == 5
        val allowed = setOf("ERROR", "WARNING", "UNKNOWN", "NOT_RUN")
        if (evArr != null) for (i in 0 until evArr.length()) {
            val st = evArr.optJSONObject(i)?.optString("status", "?") ?: "?"
            if (st !in allowed) statusOk = false
        }
        t("Report — هیچ وضعیت پنجمی نیست", "only the 4 schema statuses", "statusOk=$statusOk", statusOk)
        val inferred = ev(Bridge.Module.NETWORK, Bridge.Status.ERROR, Bridge.Kind.EXCEPTION, "inferred", Bridge.ModuleSource.STACK_TRACE_INFERRED)
        val json2 = buildReportJson(listOf(inferred), null, emptyList(), emptyList(), "2.9.8", 32, "SM-A528B", 34, fixedTs)
        val ms = json2.optJSONArray("events")?.optJSONObject(0)?.optString("module_source", "?")
        t("Report — module_source کوچک در Schema", "stack_trace_inferred", "ms=$ms", ms == "stack_trace_inferred")
        t("Report — health خالی وقتی Snapshot نیست", "empty health object", "len=${json2.optJSONObject("health")?.length()}", json2.optJSONObject("health")?.length() == 0)

        val txt = buildReadableText(events, h, emptyList(), emptyList(), "2.9.8", 32, "SM-A528B", 34, fixedTs)
        val sections = listOf("== خطاهای قطعی ==", "== هشدارها ==", "== وضعیت‌های نامشخص ==", "== تست‌های اجرا نشده ==", "== عکس سلامت ==", "== خرابی‌های LAB ==", "== پانوشت ==")
        var orderOk = true
        var lastIdx = -1
        for (s in sections) {
            val idx = txt.indexOf(s)
            if (idx < 0 || idx <= lastIdx) orderOk = false
            lastIdx = idx
        }
        t("Report — متن خوانا: ۹ بخش به ترتیب", "7 sections in order after header+summary", "orderOk=$orderOk", orderOk && txt.contains("خلاصه: خطای قطعی 2 • هشدار 1 • نامشخص 1 • اجرا نشده 1"))
        val errStart = txt.indexOf("== خطاهای قطعی ==")
        val errEnd = txt.indexOf("== هشدارها ==")
        val errSection = if (errStart >= 0 && errEnd > errStart) txt.substring(errStart, errEnd) else ""
        t("Report — بخش خطاها فقط ERROR دارد", "restore+net boom in, widget/lab/gaming out", "hasErr=${errSection.contains("restore boom") && errSection.contains("net boom")} hasOther=${errSection.contains("widget stale") || errSection.contains("lab unknown") || errSection.contains("gaming not run")}", errSection.contains("restore boom") && errSection.contains("net boom") && !errSection.contains("widget stale") && !errSection.contains("lab unknown") && !errSection.contains("gaming not run"))

        val ipEv = ev(Bridge.Module.NETWORK, Bridge.Status.ERROR, Bridge.Kind.EXCEPTION, "failed at 10.0.0.5")
        val jsonIp = buildReportJson(listOf(ipEv), null, emptyList(), emptyList(), "2.9.8", 32, "SM-A528B", 34, fixedTs)
        val txtIp = buildReadableText(listOf(ipEv), null, emptyList(), emptyList(), "2.9.8", 32, "SM-A528B", 34, fixedTs)
        t("Report — پاک‌سازی در خروجی گزارش", "no raw IP in JSON or txt", "jsonHas=${jsonIp.toString().contains("10.0.0.5")} txtHas=${txtIp.contains("10.0.0.5")}", !jsonIp.toString().contains("10.0.0.5") && !txtIp.contains("10.0.0.5"))

        val supp = buildNetworkSupplement("1.2.3.4", "192.168.1.1", "8.8.8.8", "MyWifi", fixedTs)
        t("Report — مکمل شبکه جداست", "main has no IP, supplement has it", "mainHas=${jsonIp.toString().contains("1.2.3.4")} suppHas=${supp.toString().contains("1.2.3.4")}", !jsonIp.toString().contains("1.2.3.4") && supp.toString().contains("1.2.3.4"))
        val suppNull = buildNetworkSupplement(null, null, null, null, fixedTs)
        val netObj = suppNull.optJSONObject("network")
        t("Report — مکمل: ناشناخته = unavailable", "4x unavailable", "net=${netObj?.toString()}", netObj != null && netObj.optString("public_ip") == "unavailable" && netObj.optString("gateway") == "unavailable" && netObj.optString("dns_servers") == "unavailable" && netObj.optString("ssid") == "unavailable")

        // Health-row mapping (approved thresholds: 120s / 100 MB -> WARNING).
        fun chipFor(hh: Bridge.Health, label: String): HealthChip? = healthRows(hh).firstOrNull { it.label == label }?.chip
        t("Report — نگاشت: مرگ سرویس + مانیتورینگ روشن", "ERROR", "chip=${chipFor(health(serviceRunning = false, monitoringOn = true), "سرویس مانیتورینگ")}", chipFor(health(serviceRunning = false, monitoringOn = true), "سرویس مانیتورینگ") == HealthChip.ERROR)
        t("Report — نگاشت: هر دو خاموش", "NOT_RUN", "chip=${chipFor(health(serviceRunning = false, monitoringOn = false), "سرویس مانیتورینگ")}", chipFor(health(serviceRunning = false, monitoringOn = false), "سرویس مانیتورینگ") == HealthChip.NOT_RUN)
        t("Report — نگاشت: heartbeat کهنه", "WARNING at 121s", "chip=${chipFor(health(hbAge = 121L), "Heartbeat")}", chipFor(health(hbAge = 121L), "Heartbeat") == HealthChip.WARNING)
        t("Report — نگاشت: heartbeat ثبت‌نشده", "UNKNOWN", "chip=${chipFor(health(hbAge = null), "Heartbeat")}", chipFor(health(hbAge = null), "Heartbeat") == HealthChip.UNKNOWN)
        t("Report — نگاشت: integrity ناموفق", "ERROR", "chip=${chipFor(health(integrity = "fail"), "دیتابیس")}", chipFor(health(integrity = "fail"), "دیتابیس") == HealthChip.ERROR)
        t("Report — نگاشت: integrity بررسی‌نشده", "UNKNOWN", "chip=${chipFor(health(integrity = "not_checked"), "دیتابیس")}", chipFor(health(integrity = "not_checked"), "دیتابیس") == HealthChip.UNKNOWN)
        t("Report — نگاشت: ژورنال STARTED", "WARNING", "chip=${chipFor(health(journal = "STARTED"), "Restore")}", chipFor(health(journal = "STARTED"), "Restore") == HealthChip.WARNING)
        t("Report — نگاشت: فضای آزاد کم", "WARNING at 50MB", "chip=${chipFor(health(freeMb = 50L), "فضای آزاد")}", chipFor(health(freeMb = 50L), "فضای آزاد") == HealthChip.WARNING)
        t("Report — نگاشت: فضای آزاد خوانده‌نشده", "UNKNOWN", "chip=${chipFor(health(freeMb = null), "فضای آزاد")}", chipFor(health(freeMb = null), "فضای آزاد") == HealthChip.UNKNOWN)
        t("Report — نگاشت: LAB با FAIL", "WARNING", "chip=${chipFor(health(labRun = "v2.9.8 • Unit • 5 tests • 4 pass • 1 fail • 0 not tested", labFailed = 1), "LAB")}", chipFor(health(labRun = "v2.9.8 • Unit • 5 tests • 4 pass • 1 fail • 0 not tested", labFailed = 1), "LAB") == HealthChip.WARNING)
        t("Report — نگاشت: بررسی انجام‌نشده", "NOT_RUN", "chip=${chipFor(health(lastCheckTime = null), "آخرین بررسی")}", chipFor(health(lastCheckTime = null), "آخرین بررسی") == HealthChip.NOT_RUN)
        t("Report — نگاشت: ویجت کهنه", "WARNING at 200s", "chip=${chipFor(health(widgetAge = 200L), "ویجت")}", chipFor(health(widgetAge = 200L), "ویجت") == HealthChip.WARNING)

        // Crash detail text: good file renders, corrupt file falls back.
        val goodCrash = JSONObject().apply {
            put("ts", 1762795200000L); put("module", "RESTORE"); put("module_source", "EXPLICIT")
            put("exception", "IllegalStateException"); put("message", "boom")
            put("file", "History.kt"); put("line", 412); put("function", "phaseB")
            put("stack_signature", "sig-1")
            put("stack_trace", JSONArray().apply {
                put(JSONObject().apply { put("class", "com.aksell.netwatch.History"); put("method", "phaseB"); put("file", "History.kt"); put("line", 412) })
            })
            put("breadcrumbs", JSONArray().apply { put("19:29:58 [RESTORE_START] restore started") })
            put("caused_by", JSONArray())
        }.toString()
        val detail = crashDetailText(goodCrash)
        t("Report — جزئیات Crash", "module/exception/frame/breadcrumb", "has=${detail.contains("RESTORE") && detail.contains("IllegalStateException") && detail.contains("phaseB") && detail.contains("RESTORE_START")}", detail.contains("RESTORE") && detail.contains("IllegalStateException") && detail.contains("phaseB") && detail.contains("RESTORE_START"))
        t("Report — جزئیات Crash خراب", "fallback text", "detail=${crashDetailText("{bad")}", crashDetailText("{bad").contains("خوانده نشد"))
        return out
    }
}
