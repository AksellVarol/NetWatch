package com.aksell.netwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// NETWATCH INTELLIGENCE CORE — Phase 1 (local analysis) + Phase 2 (gateway rounds).
//
// What this file is:
// - The service-owned smart core: it collects REAL data from the 4 engines
//   (Network / Monitoring / Analytics / History) + Bridge + LAB + Gaming,
//   analyses it locally (rule-based, offline, no AI call), and builds a
//   sanitized AiPacket (Bridge JSON Schema v1 + an "ai" section).
// - The owner of the analysis round: local analysis is stored as an
//   answered round (provider "local"); a provider round hands the packet
//   to AiGateway and stores the reply when it arrives.
//
// What this file is NOT:
// - No model, no training, no fine-tuning, no network call in Phase 1.
// - No Activity reference: the core never holds an Activity and never
//   depends on one being open. The service calls startRound(); the
//   Activity only renders rows from AiStore.
//
// Fault-location rule (binding, from the Bridge module rule):
// - file / line / function are set ONLY when a real stack trace provides
//   them (a Bridge event or a crash with locationSource == STACK_TRACE).
// - A LAB failure's location is the failing test itself (name + method);
//   a probe failure's location is the probe id + category; a gaming
//   anomaly's location is the session id. None of these ever invent a
//   file/line — locationSource stays NONE and file/line stay null.
// - A module is NEVER guessed from a stack trace alone: events carry
//   their explicit module; a crash keeps the module_source the Bridge
//   recorded (STACK_TRACE_INFERRED stays labelled as inferred).
// - UNKNOWN is never reported as a definite bug: an empty probe list
//   yields "unknown — بدون داده کافی", never "سالم" and never an outage.
object AiCore {

    // ---- DTOs (architecture §6-1) ----
    enum class AiKind { ANALYSIS, QUESTION }

    data class AiRequest(
        val requestId: String,
        val kind: AiKind,
        val parentRequestId: String?,
        val packet: JSONObject,
        val question: String?
    )

    data class AiResponse(
        val requestId: String,
        val provider: String,
        val model: String?,
        val verdict: String,          // ok | degraded | outage | unknown
        val cause: String,
        val fix: String,
        val confidence: Int,          // 0..100, -1 = unknown
        val evidence: List<String>,
        val generatedAt: String,
        val latencyMs: Long,
        val parseFailed: Boolean = false,
        val match: String = "exact"   // exact | newest | none
    )

    // One analysis/question round — mirrors the ai_analyses table.
    data class AiRound(
        val id: Long,
        val requestId: String,
        val createdMs: Long,
        val kind: String,             // analysis | question
        val provider: String,         // local | muse_share | muse_drive | openrouter | gemini
        val model: String?,
        val status: String,           // pending_muse | answered | failed | expired | cancelled
        val packetJson: String,
        val responseJson: String?,
        val responseMd: String?,
        val verdict: String?,
        val confidence: Int,          // -1 = unknown
        val latencyMs: Long,
        val attempts: Int,
        val error: String?,
        val shareUri: String?
    )

    // ---- statuses (architecture §6-3) ----
    const val STATUS_PENDING = "pending_muse"
    const val STATUS_ANSWERED = "answered"
    const val STATUS_FAILED = "failed"
    const val STATUS_EXPIRED = "expired"
    const val STATUS_CANCELLED = "cancelled"

    const val PROVIDER_LOCAL = "local"
    const val PROVIDER_SHARE = "muse_share"
    const val PROVIDER_DRIVE = "muse_drive"
    const val PROVIDER_OPENROUTER = "openrouter"
    const val PROVIDER_GEMINI = "gemini"

    // Packet cap (architecture §7-1): 8 KB, Bridge schema + ai section.
    const val MAX_PACKET_BYTES = 8 * 1024
    const val MAX_FINDINGS = 12
    const val MAX_EVENTS_IN_PACKET = 20

    // ---- local-analysis input (pure data; collected on device) ----
    data class ProbeAgg(
        val ok: Int, val total: Int, val domPct: Int, val intlPct: Int,
        val failedCategories: List<String>, val failedIds: List<String>
    )

    data class GamingInput(
        val activeSession: History.GamingSession?,
        val activeSamples: Int,
        val lastSession: History.GamingSession?,
        val lastStats: Gaming.SessionStats?,
        val unfinishedCount: Int
    )

    data class HistoryInput(val samples24h: Int, val avgLossPct: Double?, val worstHour: String?)

    data class LabInput(
        val lastRunMs: Long?, val pass: Int, val fail: Int, val notTested: Int,
        val failedTests: List<TestResult>
    )

    data class LocalInput(
        val classification: Quality.Classification,
        val probes: ProbeAgg,
        val gaming: GamingInput,
        val history: HistoryInput,
        val lab: LabInput,
        val events: List<Bridge.Event>,
        val health: Bridge.Health?,
        val crashes: List<Bridge.CrashSummary>,
        val connectionType: String
    )

    // One fault, with its exact location — only with real evidence.
    data class AiFinding(
        val module: Bridge.Module,
        val moduleSource: Bridge.ModuleSource,
        val status: Bridge.Status,
        val title: String,
        val detail: String,
        val file: String?,
        val line: Int?,
        val function: String?,
        val locationSource: Bridge.LocationSource,
        val evidence: String
    )

    data class LocalAnalysis(
        val verdict: String,          // ok | degraded | outage | unknown
        val confidence: Int,
        val faultSide: String,        // app | network | both | none
        val summaryFa: String,
        val findings: List<AiFinding>
    )

    // ================= pure helpers =================

    /** Probe aggregates from a fresh probe run. intl/dom percentages are
     *  passed in from NetworkEngine.summarize (same source as MainActivity)
     *  so this function never drifts from the app's own summary. Pure. */
    fun probeAgg(results: List<ProbeResult>, domPct: Int, intlPct: Int): ProbeAgg {
        val ok = results.count { it.ok }
        val failed = results.filter { !it.ok }
        return ProbeAgg(
            ok = ok, total = results.size, domPct = domPct, intlPct = intlPct,
            failedCategories = failed.map { it.category }.distinct().take(5),
            failedIds = failed.map { it.id }.take(10)
        )
    }

    /** 24h history summary from stored samples. avgLoss uses only valid
     *  (loss >= 0) samples; worstHour is the hour with the highest mean
     *  loss. Nulls when there is no valid measurement — never invented. */
    fun summarizeHistory(samples: List<History.Sample>): HistoryInput {
        val valid = samples.filter { it.lossPct >= 0.0 }
        val avgLoss = if (valid.isEmpty()) null else valid.map { it.lossPct }.average()
        var worstHour: String? = null
        if (valid.isNotEmpty()) {
            val byHour = valid.groupBy {
                SimpleDateFormat("HH:00", Locale.US).format(Date(it.time))
            }
            val worst = byHour.entries.maxByOrNull { (_, rows) -> rows.map { it.lossPct }.average() }
            worstHour = worst?.key
        }
        return HistoryInput(samples.size, avgLoss, worstHour)
    }

    /** Classification -> local verdict. The classifier itself is never
     *  re-implemented here: AiCore receives the Classification object that
     *  Quality.classifyDisruption produced, so the local verdict and the
     *  app's own verdict can never disagree. Pure. */
    fun verdictFor(cls: Quality.Classification): Pair<String, Int> {
        return when {
            cls.type == "سالم" -> "ok" to 95
            cls.type.contains("قطعی کامل") -> "outage" to 85
            cls.type.startsWith("Unknown") -> "unknown" to 50
            else -> "degraded" to cls.confidence
        }
    }

    /** Request id: r_<yyyyMMdd_HHmmss>_<4hex> (analysis) or q_... (question).
     *  Pure: the caller passes nowMs, so tests are deterministic. */
    fun newRequestId(kind: AiKind, nowMs: Long, counter: Int): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val hex = Integer.toHexString(((nowMs xor counter.toLong()) and 0xFFFF).toInt()).padStart(4, '0')
        return (if (kind == AiKind.ANALYSIS) "r_" else "q_") + ts + "_" + hex
    }

    /** Extracts a request id (r_... / q_...) from a reply text. Pure. */
    fun extractRequestId(text: String): String? {
        val m = Regex("(r|q)_\\d{8}_\\d{6}_[0-9a-f]{4}").find(text)
        return m?.value
    }

    // ---- local analysis (pure) ----

    /** The local analysis. Rules:
     *  - The verdict comes from the classifier, never from a second guess.
     *  - ERROR Bridge events are findings with their real stack location;
     *    WARNING events are findings too; UNKNOWN/NOT_RUN events are only
     *    counted in the summary — never listed as bugs.
     *  - LAB failures are WARNING findings located at the failing test.
     *  - Probe/gaming findings carry the probe id / session id as their
     *    location, never a file/line. */
    fun analyzeLocal(input: LocalInput): LocalAnalysis {
        val (verdict, confidence) = verdictFor(input.classification)
        val findings = ArrayList<AiFinding>()
        // 1. The classification itself (module ANALYTICS owns Quality.kt).
        // "سالم" needs no finding; "Unknown" is not a bug either.
        if (verdict == "outage") {
            findings.add(AiFinding(Bridge.Module.ANALYTICS, Bridge.ModuleSource.EXPLICIT, Bridge.Status.ERROR,
                "قطعی کامل اینترنت", Bridge.sanitize(input.classification.evidence),
                null, null, null, Bridge.LocationSource.NONE,
                "0/${input.probes.total} پروب موفق • داخلی ${input.probes.domPct}٪ • بین‌الملل ${input.probes.intlPct}٪"))
        } else if (verdict == "degraded") {
            findings.add(AiFinding(Bridge.Module.ANALYTICS, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                "اختلال: ${input.classification.type}", Bridge.sanitize(input.classification.evidence),
                null, null, null, Bridge.LocationSource.NONE,
                "${input.probes.ok}/${input.probes.total} پروب موفق • داخلی ${input.probes.domPct}٪ • بین‌الملل ${input.probes.intlPct}٪"))
        }
        // 2. Definite app errors from the Bridge ring (newest 5).
        val errors = input.events.filter { it.status == Bridge.Status.ERROR }.takeLast(5)
        for (ev in errors) {
            val (f, l, fn) = if (ev.locationSource == Bridge.LocationSource.STACK_TRACE) Triple(ev.file, ev.line, ev.function)
            else Triple(null, null, null)
            findings.add(AiFinding(ev.module, ev.moduleSource, Bridge.Status.ERROR,
                "خطای ${ev.kind}", Bridge.sanitize(ev.message), f, l, fn, ev.locationSource,
                "تکرار ${ev.occurrences} • آخرین ${Bridge.timeStr(ev.lastSeen)}"))
        }
        // 3. Warnings from the Bridge ring (newest 3).
        val warns = input.events.filter { it.status == Bridge.Status.WARNING }.takeLast(3)
        for (ev in warns) {
            val (f, l, fn) = if (ev.locationSource == Bridge.LocationSource.STACK_TRACE) Triple(ev.file, ev.line, ev.function)
            else Triple(null, null, null)
            findings.add(AiFinding(ev.module, ev.moduleSource, Bridge.Status.WARNING,
                "هشدار ${ev.kind}", Bridge.sanitize(ev.message), f, l, fn, ev.locationSource,
                "تکرار ${ev.occurrences} • آخرین ${Bridge.timeStr(ev.lastSeen)}"))
        }
        // 4. LAB failures — located at the failing test, never a file/line.
        for (f in input.lab.failedTests.take(5)) {
            findings.add(AiFinding(Bridge.Module.LAB, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                "تست LAB ناموفق: ${f.name}",
                "موردانتظار: ${Bridge.sanitize(f.expected)} • واقعی: ${Bridge.sanitize(f.actual)}",
                null, null, null, Bridge.LocationSource.NONE,
                "method: ${Bridge.sanitize(f.method)} • ${Bridge.sanitize(f.date)}"))
        }
        // 5. Gaming anomalies from the last finished session.
        val st = input.gaming.lastStats
        val last = input.gaming.lastSession
        if (st != null && last != null) {
            if (st.lossPct >= 50.0) {
                findings.add(AiFinding(Bridge.Module.GAMING, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                    "قطعی بالا در آخرین جلسه گیمینگ",
                    "جلسه «${Bridge.sanitize(last.profile)}» با قطعی ${"%.1f".format(st.lossPct)}٪ تمام شد",
                    null, null, null, Bridge.LocationSource.NONE,
                    "session ${last.id} • sent ${st.sent} • received ${st.received} • avg ${"%.0f".format(st.avgMs)}ms"))
            } else if (st.burst >= 3) {
                findings.add(AiFinding(Bridge.Module.GAMING, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                    "قطعی متوالی در آخرین جلسه گیمینگ",
                    "طولانی‌ترین قطعی متوالی ${st.burst} نمونه بود",
                    null, null, null, Bridge.LocationSource.NONE,
                    "session ${last.id} • burst ${st.burst} • spikes ${st.spikes}"))
            }
        }
        if (input.gaming.unfinishedCount > 1) {
            findings.add(AiFinding(Bridge.Module.GAMING, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                "جلسات گیمینگ ناتمام", "${input.gaming.unfinishedCount} جلسه ناتمام در دیتابیس است",
                null, null, null, Bridge.LocationSource.NONE,
                "unfinished=${input.gaming.unfinishedCount}"))
        }
        // 6. DoH blocked — a proven network fact on this device (0/3 DoH),
        // located at the failing probe ids, never at a file.
        if ("doh" in input.probes.failedCategories) {
            findings.add(AiFinding(Bridge.Module.NETWORK, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
                "DoH مسدود است",
                "DNS روی HTTPS روی این شبکه جواب نمی‌دهد",
                null, null, null, Bridge.LocationSource.NONE,
                "failed DoH: ${input.probes.failedIds.filter { it in listOf("cfdns", "gdns", "quad9") }.joinToString(", ")}"))
        }
        val capped = findings.take(MAX_FINDINGS)
        // Fault side: app modules vs network modules.
        val appModules = setOf(Bridge.Module.APP, Bridge.Module.MONITOR, Bridge.Module.HISTORY, Bridge.Module.RESTORE, Bridge.Module.GAMING, Bridge.Module.LAB, Bridge.Module.WIDGET, Bridge.Module.UI, Bridge.Module.EXPORT, Bridge.Module.SYSTEM)
        val hasApp = capped.any { it.module in appModules && it.status == Bridge.Status.ERROR }
        val hasNet = capped.any { it.module == Bridge.Module.NETWORK || it.module == Bridge.Module.GEO || it.module == Bridge.Module.ANALYTICS }
        val side = when {
            hasApp && hasNet -> "both"
            hasApp -> "app"
            hasNet -> "network"
            else -> "none"
        }
        val sb = StringBuilder()
        sb.append("تحلیل محلی (بدون هوش مصنوعی خارجی): «${input.classification.type}» با اطمینان ${input.classification.confidence}٪. ")
        sb.append("در آخرین بررسی ${input.probes.ok} از ${input.probes.total} پروب موفق بود (داخلی ${input.probes.domPct}٪، بین‌الملل ${input.probes.intlPct}٪). ")
        if (st != null && last != null) {
            sb.append("آخرین جلسه گیمینگ «${Bridge.sanitize(last.profile)}»: قطعی ${"%.1f".format(st.lossPct)}٪ • میانگین ${"%.0f".format(st.avgMs)}ms. ")
        } else {
            sb.append("جلسه گیمینگی ثبت نشده است. ")
        }
        if (input.lab.lastRunMs != null) {
            sb.append("آخرین LAB: ${input.lab.pass} موفق، ${input.lab.fail} ناموفق، ${input.lab.notTested} اجرا نشده. ")
        } else {
            sb.append("LAB هنوز اجرا نشده است. ")
        }
        val counts = Bridge.summaryCounts(input.events)
        sb.append("Bridge: ${counts[Bridge.Status.ERROR] ?: 0} خطای قطعی، ${counts[Bridge.Status.WARNING] ?: 0} هشدار.")
        if (verdict == "unknown") sb.append(" داده کافی برای حکم قطعی نیست.")
        return LocalAnalysis(verdict, confidence, side, sb.toString(), capped)
    }

    // ---- packet (pure) ----

    private fun findingJson(f: AiFinding): JSONObject {
        val o = JSONObject()
        o.put("module", f.module.name)
        o.put("module_source", when (f.moduleSource) {
            Bridge.ModuleSource.EXPLICIT -> "explicit"
            Bridge.ModuleSource.STACK_TRACE_INFERRED -> "stack_trace_inferred"
            Bridge.ModuleSource.UNKNOWN -> "unknown"
        })
        o.put("status", f.status.name)
        o.put("title", Bridge.sanitize(f.title))
        o.put("detail", Bridge.sanitize(f.detail))
        if (f.file != null) o.put("file", f.file) else o.put("file", JSONObject.NULL)
        if (f.line != null) o.put("line", f.line) else o.put("line", JSONObject.NULL)
        if (f.function != null) o.put("function", f.function) else o.put("function", JSONObject.NULL)
        o.put("location_source", if (f.locationSource == Bridge.LocationSource.STACK_TRACE) "stack_trace" else "none")
        o.put("evidence", Bridge.sanitize(f.evidence))
        return o
    }

    /** The AiPacket: the Bridge report JSON (Schema v1) + the "ai" section.
     *  Reuses BridgeReport.buildReportJson — no parallel report format.
     *  The packet is capped at 8 KB: events are trimmed first, then
     *  findings, then failed ids. Missing data stays null — never faked. */
    fun buildPacket(
        input: LocalInput, analysis: LocalAnalysis, requestId: String, kind: AiKind,
        question: String?, parentRequestId: String?,
        appVersion: String, versionCode: Int, deviceModel: String, androidSdk: Int,
        generatedAtMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val events = input.events.takeLast(MAX_EVENTS_IN_PACKET)
        val packet = BridgeReport.buildReportJson(
            events, input.health, input.crashes, input.lab.failedTests,
            appVersion, versionCode, deviceModel, androidSdk, generatedAtMs
        )
        val ai = JSONObject()
        ai.put("kind", if (kind == AiKind.ANALYSIS) "analysis" else "question")
        ai.put("request_id", requestId)
        if (parentRequestId != null) ai.put("parent_request_id", parentRequestId) else ai.put("parent_request_id", JSONObject.NULL)
        if (question != null) ai.put("question", Bridge.sanitize(question)) else ai.put("question", JSONObject.NULL)
        val cls = JSONObject()
        cls.put("type", input.classification.type)
        cls.put("confidence", input.classification.confidence)
        cls.put("evidence", Bridge.sanitize(input.classification.evidence))
        ai.put("local_classification", cls)
        ai.put("local_verdict", analysis.verdict)
        ai.put("fault_side", analysis.faultSide)
        ai.put("connection_type", Bridge.sanitize(input.connectionType))
        val pr = JSONObject()
        pr.put("ok", input.probes.ok)
        pr.put("total", input.probes.total)
        pr.put("domestic_ok_pct", input.probes.domPct)
        pr.put("intl_ok_pct", input.probes.intlPct)
        val fc = JSONArray(); for (c in input.probes.failedCategories) fc.put(c)
        pr.put("failed_categories", fc)
        val fi = JSONArray(); for (id in input.probes.failedIds) fi.put(id)
        pr.put("failed_ids", fi)
        ai.put("probes", pr)
        val gm = JSONObject()
        gm.put("active", input.gaming.activeSession != null)
        val last = input.gaming.lastSession
        val st = input.gaming.lastStats
        if (last != null && st != null) {
            val ls = JSONObject()
            ls.put("profile", Bridge.sanitize(last.profile))
            ls.put("region", Bridge.sanitize(last.region))
            ls.put("sent", st.sent)
            ls.put("received", st.received)
            ls.put("loss_pct", st.lossPct)
            ls.put("avg_ms", st.avgMs)
            ls.put("p95_ms", st.p95)
            ls.put("spikes", st.spikes)
            ls.put("burst", st.burst)
            ls.put("score", if (last.score != null) last.score!! else JSONObject.NULL)
            gm.put("last_session", ls)
        } else {
            gm.put("last_session", JSONObject.NULL)
        }
        gm.put("unfinished_count", input.gaming.unfinishedCount)
        ai.put("gaming", gm)
        val h24 = JSONObject()
        h24.put("samples", input.history.samples24h)
        if (input.history.avgLossPct != null) h24.put("avg_loss_pct", input.history.avgLossPct) else h24.put("avg_loss_pct", JSONObject.NULL)
        if (input.history.worstHour != null) h24.put("worst_hour", input.history.worstHour) else h24.put("worst_hour", JSONObject.NULL)
        ai.put("history_24h", h24)
        val lab = JSONObject()
        if (input.lab.lastRunMs != null) lab.put("last_run", BridgeReport.isoTime(input.lab.lastRunMs)) else lab.put("last_run", JSONObject.NULL)
        lab.put("pass", input.lab.pass)
        lab.put("fail", input.lab.fail)
        lab.put("not_tested", input.lab.notTested)
        val ft = JSONArray(); for (t in input.lab.failedTests) ft.put(Bridge.sanitize(t.name))
        lab.put("failed_tests", ft)
        ai.put("lab", lab)
        val fa = JSONArray(); for (f in analysis.findings) fa.put(findingJson(f))
        ai.put("findings", fa)
        packet.put("ai", ai)
        return enforcePacketCap(packet)
    }

    /** Trims the packet to the 8 KB cap: events first, then findings,
     *  then failed ids. Pure. */
    fun enforcePacketCap(packet: JSONObject): JSONObject {
        var cur = packet
        var guard = 0
        while (cur.toString().toByteArray(Charsets.UTF_8).size > MAX_PACKET_BYTES && guard < 8) {
            guard++
            val evArr = cur.optJSONArray("events")
            if (evArr != null && evArr.length() > 0) {
                // Keep the newest half of the events.
                val keep = (evArr.length() / 2).coerceAtLeast(0)
                val trimmed = JSONArray()
                for (i in evArr.length() - keep until evArr.length()) trimmed.put(evArr.get(i))
                cur.put("events", trimmed)
                continue
            }
            val ai = cur.optJSONObject("ai")
            val fa = ai?.optJSONArray("findings")
            if (fa != null && fa.length() > 1) {
                val trimmed = JSONArray()
                for (i in 0 until fa.length() / 2) trimmed.put(fa.get(i))
                ai.put("findings", trimmed)
                continue
            }
            val pr = ai?.optJSONObject("probes")
            val fi = pr?.optJSONArray("failed_ids")
            if (fi != null && fi.length() > 0) {
                pr.put("failed_ids", JSONArray())
                continue
            }
            break
        }
        return cur
    }

    /** Readable Persian text for the Share file: local analysis + findings
     *  with their exact locations + the full packet JSON. Pure. */
    fun buildReadableText(packet: JSONObject, analysis: LocalAnalysis): String {
        val sb = StringBuilder()
        val ai = packet.optJSONObject("ai")
        val reqId = ai?.optString("request_id", "—") ?: "—"
        sb.append("NETWATCH — AI Analysis (local)\n")
        sb.append("درخواست: $reqId\n")
        sb.append("حکم محلی: ${analysis.verdict} (اطمینان ${analysis.confidence}٪) • سمت ایراد: ${analysis.faultSide}\n\n")
        sb.append(analysis.summaryFa).append("\n\n")
        sb.append("== یافته‌ها (مکان دقیق فقط با شواهد واقعی) ==\n")
        if (analysis.findings.isEmpty()) {
            sb.append("یافتهٔ مشخصی نیست.\n")
        } else {
            for ((i, f) in analysis.findings.withIndex()) {
                sb.append("${i + 1}. [${f.module}] ${f.title}\n")
                sb.append("   ${f.detail}\n")
                val loc = if (f.file != null) "فایل: ${f.file}${if (f.line != null) ":${f.line}" else ""} • تابع: ${f.function ?: "—"} (منبع مکان: stack_trace)"
                else "مکان: ${if (f.module == Bridge.Module.LAB) "تست LAB" else if (f.module == Bridge.Module.GAMING) "جلسه گیمینگ" else "پروب/دسته"} (بدون فایل/خط — شواهد فایل ندارد)"
                sb.append("   $loc\n")
                sb.append("   شواهد: ${f.evidence}\n")
            }
        }
        sb.append("\n--- JSON ---\n")
        sb.append(packet.toString(2))
        return Bridge.sanitize(sb.toString())
    }

    // ---- reply parsing (pure) ----

    /** Parses a reply shared back from Muse. The reply is usually the
     *  rendered chat text, not raw JSON: if a JSON object with a verdict
     *  is found inside, it is used; otherwise the whole text is stored as
     *  readable markdown with verdict "unknown" and parse_failed=true —
     *  never a verdict that was not in the reply. Pure. */
    fun parseReplyText(rawText: String, requestId: String, provider: String, model: String?): AiResponse? {
        val clean = Bridge.sanitize(rawText).trim()
        if (clean.isEmpty()) return null
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val jo = JSONObject(clean.substring(start, end + 1))
                val verdict = jo.optString("verdict", "")
                if (verdict in listOf("ok", "degraded", "outage", "unknown")) {
                    val ev = ArrayList<String>()
                    val evArr = jo.optJSONArray("evidence")
                    if (evArr != null) for (i in 0 until evArr.length()) ev.add(Bridge.sanitize(evArr.optString(i, "")))
                    return AiResponse(
                        requestId = jo.optString("request_id", requestId),
                        provider = provider, model = model,
                        verdict = verdict,
                        cause = Bridge.sanitize(jo.optString("cause", "")),
                        fix = Bridge.sanitize(jo.optString("fix", "")),
                        confidence = jo.optInt("confidence", -1),
                        evidence = ev,
                        generatedAt = jo.optString("generated_at", ""),
                        latencyMs = jo.optLong("latency_ms", 0L),
                        parseFailed = false, match = "exact"
                    )
                }
            } catch (_: Exception) { /* fall through to raw-text storage */ }
        }
        return AiResponse(
            requestId = requestId, provider = provider, model = model,
            verdict = "unknown", cause = "", fix = "",
            confidence = -1, evidence = emptyList(),
            generatedAt = "", latencyMs = 0L,
            parseFailed = true, match = "exact"
        )
    }

    fun responseToJson(r: AiResponse, rawMd: String): JSONObject {
        val o = JSONObject()
        o.put("request_id", r.requestId)
        o.put("provider", r.provider)
        if (r.model != null) o.put("model", r.model) else o.put("model", JSONObject.NULL)
        o.put("verdict", r.verdict)
        o.put("cause", r.cause)
        o.put("fix", r.fix)
        o.put("confidence", r.confidence)
        val ev = JSONArray(); for (e in r.evidence) ev.put(e)
        o.put("evidence", ev)
        o.put("generated_at", r.generatedAt)
        o.put("latency_ms", r.latencyMs)
        o.put("parse_failed", r.parseFailed)
        o.put("match", r.match)
        o.put("raw_md", rawMd)
        return o
    }

    fun responseToReadable(r: AiResponse, rawMd: String): String {
        if (r.parseFailed) return rawMd
        val sb = StringBuilder()
        sb.append("وضعیت: ${r.verdict} (اطمینان ${if (r.confidence >= 0) "${r.confidence}٪" else "نامشخص"})\n")
        if (r.cause.isNotBlank()) sb.append("علت احتمالی: ${r.cause}\n")
        if (r.fix.isNotBlank()) sb.append("راهکار: ${r.fix}\n")
        if (r.evidence.isNotEmpty()) sb.append("شواهد: ${r.evidence.joinToString(" • ")}\n")
        return sb.toString().trim()
    }

    // ================= device-side (Context; NOT run on the JVM) =================

    /** Collects the freshest real data for one analysis round. Runs a
     *  fresh LIGHT probe run (same cost as a background check) plus the
     *  exact classifier inputs MainActivity.doCheck derives (gateway ping,
     *  UDP DNS, filtering from DoH/TLS) — so the local classification is
     *  the SAME call MainActivity makes, on the same inputs. Must run off
     *  the main thread (network + DB). */
    fun collectLocal(ctx: Context, serviceRunning: Boolean): LocalInput {
        val now = System.currentTimeMillis()
        val results = try { NetworkEngine.runProbes(true) } catch (_: Exception) { emptyList<ProbeResult>() }
        // Filtering derivation — same rules as MainActivity.doCheck (the
        // source of truth); heavy diagnostics (MTU/traceroute/multi-host)
        // are skipped: they do not feed the classifier.
        val dohCf = try { NetworkEngine.dohIps("https://cloudflare-dns.com/dns-query", "telegram.org") } catch (_: Exception) { emptyList<String>() }
        val dohGg = try { NetworkEngine.dohIps("https://dns.google/resolve", "telegram.org") } catch (_: Exception) { emptyList<String>() }
        val udpDns = try { NetworkEngine.dns("telegram.org") } catch (_: Exception) { null }
        val tcpTg = try { NetworkEngine.tcp("telegram.org", 443) } catch (_: Exception) { null }
        val tlsTg = try { NetworkEngine.tls("telegram.org") } catch (_: Exception) { null }
        val tlsIg = try { NetworkEngine.tls("www.instagram.com") } catch (_: Exception) { null }
        val filtering = StringBuilder()
        val dnsMismatch = dohCf.isNotEmpty() && dohGg.isNotEmpty() && dohCf.toSet() != dohGg.toSet()
        if (dnsMismatch) filtering.append("DNS Poisoning/Hijacking مشکوک: پاسخ DoH کلادفلر $dohCf با گوگل $dohGg متفاوت است. ")
        if (dohCf.isEmpty() && dohGg.isEmpty() && udpDns?.ok != true) filtering.append("DNS: هیچ DNSای جواب نداد. ")
        if (tcpTg?.ok == true && tlsTg?.ok == false) filtering.append("SNI/TLS مشکوک برای تلگرام: TCP وصل شد ولی TLS شکست خورد (${tlsTg.note}). ")
        if (tcpTg?.ok == true && tlsIg?.ok == false) filtering.append("SNI/TLS مشکوک برای اینستاگرام: TCP وصل شد ولی TLS شکست خورد. ")
        if (tcpTg?.ok == false && (tlsTg?.note?.contains("Reset") == true)) filtering.append("TCP Reset مشکوک برای تلگرام. ")
        val summary = try { NetworkEngine.summarize(results, filtering.toString()) } catch (_: Exception) { null }
        val gwIp = try { NetworkInfo.gateway(ctx) } catch (_: Exception) { "—" }
        val gwOk = if (gwIp == "—") true else try { NetworkEngine.pingStats(gwIp).ok } catch (_: Exception) { true }
        val cls = Quality.classifyDisruption(results, filtering.toString(), gwOk, udpDns?.ok == true, summary?.intlPct ?: 0, summary?.domPct ?: 0)
        val agg = probeAgg(results, summary?.domPct ?: 0, summary?.intlPct ?: 0)
        val events = try { Bridge.events() } catch (_: Exception) { emptyList<Bridge.Event>() }
        val health = try { Bridge.healthSnapshot(ctx, serviceRunning) } catch (_: Exception) { null }
        val crashes = try { Bridge.listCrashes() } catch (_: Exception) { emptyList<Bridge.CrashSummary>() }
        val runs = try { Lab.loadRuns(ctx) } catch (_: Exception) { emptyList<Lab.LabRun>() }
        val lastRun = runs.maxByOrNull { it.timestamp }
        val labInput = if (lastRun == null) LabInput(null, 0, 0, 0, emptyList())
        else LabInput(lastRun.timestamp, lastRun.results.count { it.pass == true }, lastRun.results.count { it.pass == false },
            lastRun.results.count { it.pass == null }, lastRun.results.filter { it.pass == false })
        val samples24 = try { HistoryEngine.samplesInRange(ctx, now - 24L * 60 * 60 * 1000) } catch (_: Exception) { emptyList<History.Sample>() }
        val sessions = try { HistoryEngine.gamingSessions(ctx) } catch (_: Exception) { emptyList<History.GamingSession>() }
        val active = try { HistoryEngine.activeGamingSession(ctx) } catch (_: Exception) { null }
        val activeSamples = if (active != null) try { HistoryEngine.gamingSessionSamples(ctx, active.id).size } catch (_: Exception) { 0 } else 0
        val finished = sessions.filter { it.ended != 0L }.maxByOrNull { it.started }
        val lastStats = if (finished != null) try {
            val s = HistoryEngine.gamingSessionSamples(ctx, finished.id).map { it.second }
            if (s.isEmpty()) null else Gaming.sessionStats(s)
        } catch (_: Exception) { null } else null
        val gaming = GamingInput(active, activeSamples, finished, lastStats, sessions.count { it.ended == 0L })
        val connType = try { Geo.connectionInfo(ctx) } catch (_: Exception) { "—" }
        return LocalInput(cls, agg, gaming, summarizeHistory(samples24), labInput, events, health, crashes, connType)
    }

    /** Phase 1 round: local analysis only (no provider, no network call
     *  beyond the fresh probe run). Stored as an answered round with
     *  provider "local" — the local verdict IS the answer. */
    fun startRound(ctx: Context, kind: AiKind, question: String?, parentRequestId: String?, providerId: String = PROVIDER_LOCAL, serviceRunning: Boolean): AiRound? {
        val now = System.currentTimeMillis()
        val requestId = newRequestId(kind, now, (now % 10000).toInt())
        return try {
            val input = collectLocal(ctx, serviceRunning)
            val analysis = analyzeLocal(input)
            val packet = buildPacket(input, analysis, requestId, kind, question, parentRequestId,
                appVersion(ctx), appVersionCode(ctx), android.os.Build.MODEL ?: "—", android.os.Build.VERSION.SDK_INT, now)
            val round = AiRound(0L, requestId, now, if (kind == AiKind.ANALYSIS) "analysis" else "question",
                PROVIDER_LOCAL, null, STATUS_ANSWERED, packet.toString(2), null,
                analysis.summaryFa + "\n\n" + readableFindings(analysis), analysis.verdict, analysis.confidence, 0L, 0, null, null)
            val id = AiStore.insertRound(ctx, round)
            val stored = round.copy(id = id)
            if (question != null) {
                AiStore.insertChat(ctx, parentRequestId ?: requestId, "user", question, requestId)
                AiStore.insertChat(ctx, parentRequestId ?: requestId, "muse", analysis.summaryFa, requestId)
            }
            stored
        } catch (e: Exception) {
            try { History.addLog(ctx, "خطا در تحلیل محلی AI: ${e.javaClass.simpleName}") } catch (_: Exception) { }
            null
        }
    }

    /** Rebuilds the provider request from a stored round (its packet JSON
     *  already carries kind / parent / question). Null when the stored
     *  packet cannot be parsed — the round is then failed, never sent
     *  with a guessed packet. Pure. */
    fun requestFromRound(round: AiRound): AiRequest? {
        return try {
            val packet = JSONObject(round.packetJson)
            val ai = packet.optJSONObject("ai") ?: return null
            val kind = if (round.kind == "question") AiKind.QUESTION else AiKind.ANALYSIS
            val parent = if (ai.isNull("parent_request_id")) null else ai.optString("parent_request_id")
            val question = if (ai.isNull("question")) null else ai.optString("question")
            AiRequest(round.requestId, kind, parent, packet, question)
        } catch (_: Exception) { null }
    }

    /** Phase 2: sends a locally-analyzed round through the gateway.
     *  Synchronous — the service calls it from a NetExecutor task.
     *  Answered stores the provider reply; Pending stores the share Uri
     *  and waits; Failed stores the sanitized error. The local analysis
     *  is never discarded: it stays in the packet. */
    fun startProviderRound(ctx: Context, round: AiRound, providerId: String): AiRound {
        val request = requestFromRound(round)
        if (request == null) {
            val failed = round.copy(status = STATUS_FAILED, provider = providerId, error = "parse: بسته خوانده نشد", attempts = 0)
            try { AiStore.updateRound(ctx, failed) } catch (_: Exception) { }
            return failed
        }
        val result = AiGateway.sendSync(ctx, providerId, request)
        val updated = when (result) {
            is AiResult.Answered -> {
                val r = result.response
                if (round.kind == "question") {
                    try { AiStore.insertChat(ctx, round.requestId, "muse", responseToReadable(r, ""), round.requestId) } catch (_: Exception) { }
                }
                round.copy(status = STATUS_ANSWERED, provider = providerId,
                    responseJson = responseToJson(r, "").toString(2),
                    responseMd = responseToReadable(r, ""),
                    verdict = r.verdict, confidence = r.confidence, latencyMs = r.latencyMs, attempts = 1, error = null)
            }
            is AiResult.Pending -> round.copy(status = STATUS_PENDING, provider = providerId,
                shareUri = result.shareUri, attempts = 1, error = null)
            is AiResult.Failed -> round.copy(status = STATUS_FAILED, provider = providerId,
                error = "${result.error.code}: ${result.error.message}", attempts = result.error.attempts)
        }
        try { AiStore.updateRound(ctx, updated) } catch (_: Exception) { }
        return updated
    }

    /** Phase 2: a reply shared back from Muse (ACTION_SEND text).
     *  Matches by request id when possible; otherwise attaches to the
     *  newest pending round and records match="newest"; with no pending
     *  round at all the text is stored as an unmatched chat message and
     *  null is returned. Never overwrites an already-answered round. */
    fun receiveReply(ctx: Context, rawText: String): AiRound? {
        val clean = Bridge.sanitize(rawText).trim()
        if (clean.isEmpty()) return null
        val extracted = extractRequestId(clean)
        val byId = if (extracted != null) try { AiStore.getRound(ctx, extracted) } catch (_: Exception) { null } else null
        val pending = try { AiStore.pendingRound(ctx) } catch (_: Exception) { null }
        val target = if (byId != null && byId.status == STATUS_PENDING) byId else pending
        if (target == null) {
            try { AiStore.insertChat(ctx, extracted ?: "unmatched", "muse", clean, extracted) } catch (_: Exception) { }
            return null
        }
        val match = if (byId != null && byId.status == STATUS_PENDING) "exact" else "newest"
        val parsed = parseReplyText(clean, target.requestId, target.provider, target.model) ?: return null
        val response = parsed.copy(match = match)
        val updated = target.copy(
            status = STATUS_ANSWERED,
            responseJson = responseToJson(response, clean).toString(2),
            responseMd = responseToReadable(response, clean),
            verdict = response.verdict, confidence = response.confidence,
            latencyMs = response.latencyMs, error = null
        )
        return try {
            AiStore.updateRound(ctx, updated)
            AiStore.insertChat(ctx, target.requestId, "muse", responseToReadable(response, clean), target.requestId)
            updated
        } catch (_: Exception) { null }
    }

    private fun readableFindings(a: LocalAnalysis): String {
        if (a.findings.isEmpty()) return "یافتهٔ مشخصی نیست."
        val sb = StringBuilder("یافته‌ها:\n")
        for ((i, f) in a.findings.withIndex()) {
            sb.append("${i + 1}. [${f.module}] ${f.title}")
            if (f.file != null) sb.append(" — ${f.file}${if (f.line != null) ":${f.line}" else ""} • ${f.function ?: ""}")
            sb.append("\n   ${f.detail}\n   شواهد: ${f.evidence}\n")
        }
        return sb.toString().trim()
    }

    private fun appVersion(ctx: Context): String {
        return try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—" } catch (_: Exception) { "—" }
    }

    private fun appVersionCode(ctx: Context): Int {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) { 0 }
    }

    // ================= JVM self-tests (pure only) =================

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "AI Core — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        fun probe(id: String, ok: Boolean, cat: String, dom: Boolean = false) =
            ProbeResult(id, id, if (id in listOf("cfdns", "gdns", "quad9")) "DoH" else "probe", ok, if (ok) 50L else 0L, "", dom, cat)
        fun emptyInput(cls: Quality.Classification, probes: ProbeAgg = ProbeAgg(0, 0, 0, 0, emptyList(), emptyList())): LocalInput {
            return LocalInput(cls, probes, GamingInput(null, 0, null, null, 0),
                HistoryInput(0, null, null), LabInput(null, 0, 0, 0, emptyList()),
                emptyList(), null, emptyList(), "Wi-Fi")
        }
        // 1. Verdict mapping — the local verdict IS the classifier's verdict.
        val clsOk = Quality.classifyDisruption(listOf(probe("cf", true, "intl"), probe("google", true, "intl")), "", true, true, 100, 100)
        val clsOut = Quality.classifyDisruption(List(5) { probe("p$it", false, "intl") }, "", false, false, 0, 0)
        val clsEmpty = Quality.classifyDisruption(emptyList(), "", true, true, 0, 0)
        t("AI — حکم محلی همان حکم طبقه‌بندی است (سالم)", "ok", verdictFor(clsOk).first, verdictFor(clsOk).first == "ok")
        t("AI — حکم محلی همان حکم طبقه‌بندی است (قطعی کامل)", "outage", verdictFor(clsOut).first, verdictFor(clsOut).first == "outage")
        t("AI — داده خالی «unknown» است نه سالم و نه قطعی", "unknown", verdictFor(clsEmpty).first, verdictFor(clsEmpty).first == "unknown")
        // 2. Agreement proof: the same Classification object flows into the analysis.
        val mixed = listOf(probe("cf", true, "intl"), probe("google", true, "intl"), probe("cfdns", false, "dns"), probe("gdns", false, "dns"), probe("quad9", false, "dns"))
        val clsMixed = Quality.classifyDisruption(mixed, "", true, false, 40, 100)
        val aggMixed = probeAgg(mixed, 100, 40)
        val analysisMixed = analyzeLocal(emptyInput(clsMixed, aggMixed))
        t("AI — تحلیل محلی با classifyDisruption مطابقت دارد", "packet type == ${clsMixed.type}", "analysis verdict=${analysisMixed.verdict} type=${clsMixed.type}",
            analysisMixed.verdict == verdictFor(clsMixed).first && analysisMixed.summaryFa.contains(clsMixed.type))
        t("AI — تجمیع پروب", "ok=2 total=5 dom=100 intl=40 failedCats=[dns] failedIds 3", "ok=${aggMixed.ok} total=${aggMixed.total} dom=${aggMixed.domPct} intl=${aggMixed.intlPct} cats=${aggMixed.failedCategories} ids=${aggMixed.failedIds}",
            aggMixed.ok == 2 && aggMixed.total == 5 && aggMixed.domPct == 100 && aggMixed.intlPct == 40 && aggMixed.failedCategories == listOf("dns") && aggMixed.failedIds.size == 3)
        // 3. Fault-location rules — file/line only with a real stack trace.
        val evWithLoc = Bridge.Event("e1", 1762795200000L, Bridge.Module.RESTORE, Bridge.ModuleSource.EXPLICIT, Bridge.Status.ERROR,
            Bridge.Kind.RESTORE_ABORT, "restore boom", "History.kt", 412, "phaseB", Bridge.LocationSource.STACK_TRACE,
            listOf(Bridge.Frame("com.aksell.netwatch.History", "phaseB", "History.kt", 412)),
            "sig-1", 1, 1762795200000L, 1762795200000L, emptyList(), emptyMap())
        val evNoLoc = Bridge.Event("e2", 1762795200000L, Bridge.Module.NETWORK, Bridge.ModuleSource.EXPLICIT, Bridge.Status.WARNING,
            Bridge.Kind.EXCEPTION, "net warn 10.0.0.5", null, null, null, Bridge.LocationSource.NONE,
            emptyList(), "sig-2", 2, 1762795200000L, 1762795200000L, emptyList(), emptyMap())
        val withLoc = analyzeLocal(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())).copy(events = listOf(evWithLoc, evNoLoc)))
        val fLoc = withLoc.findings.firstOrNull { it.title.contains("RESTORE_ABORT") }
        val fNoLoc = withLoc.findings.firstOrNull { it.title.contains("EXCEPTION") }
        t("AI — مکان دقیق فقط با Stack واقعی می‌آید", "History.kt:412 phaseB", "${fLoc?.file}:${fLoc?.line} ${fLoc?.function}",
            fLoc != null && fLoc.file == "History.kt" && fLoc.line == 412 && fLoc.function == "phaseB" && fLoc.locationSource == Bridge.LocationSource.STACK_TRACE)
        t("AI — بدون Stack، فایل/خط ساخته نمی‌شود", "file null + NONE", "${fNoLoc?.file} ${fNoLoc?.locationSource}",
            fNoLoc != null && fNoLoc.file == null && fNoLoc.line == null && fNoLoc.function == null && fNoLoc.locationSource == Bridge.LocationSource.NONE)
        t("AI — پاک‌سازی IP در یافته", "no raw IP", "detail=${fNoLoc?.detail}", fNoLoc != null && !fNoLoc.detail.contains("10.0.0.5") && fNoLoc.detail.contains("<ip>"))
        // 4. LAB failure location = the failing test itself, never a file.
        val labFail = TestResult("DNS واقعی — DoH", "Integration — پروب واقعی", "3/3", "0/3", false, "", "19:42:47", 1000)
        val labIn = analyzeLocal(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())).copy(lab = LabInput(1762795200000L, 70, 1, 7, listOf(labFail))))
        val fLab = labIn.findings.firstOrNull { it.module == Bridge.Module.LAB }
        t("AI — مکان خرابی LAB خودِ تست است", "LAB + file null + evidence has test name", "${fLab?.module} ${fLab?.file} ${fLab?.title}",
            fLab != null && fLab.file == null && fLab.title.contains("DNS واقعی — DoH") && fLab.status == Bridge.Status.WARNING)
        // 5. UNKNOWN is never a bug: no finding from an unknown classification.
        val unkIn = analyzeLocal(emptyInput(clsEmpty))
        t("AI — UNKNOWN هرگز به‌عنوان باگ گزارش نمی‌شود", "0 findings for unknown", "findings=${unkIn.findings.size} verdict=${unkIn.verdict}",
            unkIn.verdict == "unknown" && unkIn.findings.isEmpty() && unkIn.summaryFa.contains("داده کافی"))
        // 6. History summary.
        val hist = summarizeHistory(listOf(
            History.Sample(1762795200000L, 40, 40, 50.0, 5.0, 0.0, "full"),
            History.Sample(1762795200000L + 3600000L, 40, 40, 50.0, 5.0, 10.0, "full"),
            History.Sample(1762795200000L + 7200000L, 0, 40, -1.0, -1.0, -1.0, "full")
        ))
        t("AI — خلاصه تاریخچه ۲۴ ساعته", "samples=3 avgLoss=5.0 worstHour set", "samples=${hist.samples24h} avg=${hist.avgLossPct} worst=${hist.worstHour}",
            hist.samples24h == 3 && hist.avgLossPct == 5.0 && hist.worstHour != null)
        val histEmpty = summarizeHistory(emptyList())
        t("AI — تاریخچه خالی null می‌دهد نه صفر ساختگی", "samples=0 avg=null worst=null", "samples=${histEmpty.samples24h} avg=${histEmpty.avgLossPct} worst=${histEmpty.worstHour}",
            histEmpty.samples24h == 0 && histEmpty.avgLossPct == null && histEmpty.worstHour == null)
        // 7. Packet: schema, cap, no raw IP, nulls for missing data.
        val packet = buildPacket(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())),
            analyzeLocal(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList()))),
            "r_20261112_033000_7f3a", AiKind.ANALYSIS, null, null, "2.10.0", 34, "SM-A528B", 34, 1762795200000L)
        t("AI — بسته همان طرح Bridge + بخش ai است", "schema 1, ai.kind analysis, network_details false", "schema=${packet.optInt("schema_version")} kind=${packet.optJSONObject("ai")?.optString("kind")} net=${packet.optBoolean("network_details_included", true)}",
            packet.optInt("schema_version") == 1 && packet.optJSONObject("ai")?.optString("kind") == "analysis" && packet.optBoolean("network_details_included", true) == false && packet.optJSONObject("ai")?.optString("request_id") == "r_20261112_033000_7f3a")
        t("AI — بسته دادهٔ نداشتن را null می‌گذارد", "gaming.last_session null, lab.last_run null", "gaming=${packet.optJSONObject("ai")?.optJSONObject("gaming")?.isNull("last_session")} lab=${packet.optJSONObject("ai")?.optJSONObject("lab")?.isNull("last_run")}",
            packet.optJSONObject("ai")?.optJSONObject("gaming")?.isNull("last_session") == true && packet.optJSONObject("ai")?.optJSONObject("lab")?.isNull("last_run") == true)
        t("AI — بسته زیر سقف ۸ کیلوبایت است", "<= 8192 bytes", "bytes=${packet.toString().toByteArray(Charsets.UTF_8).size}",
            packet.toString().toByteArray(Charsets.UTF_8).size <= MAX_PACKET_BYTES)
        // 8. Cap enforcement with 200 long events.
        val bigEvents = (1..200).map { i ->
            Bridge.Event("e$i", 1762795200000L, Bridge.Module.NETWORK, Bridge.ModuleSource.EXPLICIT, Bridge.Status.ERROR,
                Bridge.Kind.EXCEPTION, "failure number $i at 10.0.0.5 with a long message " + "x".repeat(200), null, null, null,
                Bridge.LocationSource.NONE, emptyList(), "sig-$i", 1, 1762795200000L, 1762795200000L, emptyList(), emptyMap())
        }
        val bigInput = emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())).copy(events = bigEvents)
        val bigPacket = buildPacket(bigInput, analyzeLocal(bigInput), "r_20261112_033000_7f3a", AiKind.ANALYSIS, null, null, "2.10.0", 34, "SM-A528B", 34, 1762795200000L)
        t("AI — سقف ۸ کیلوبایت با ۲۰۰ رویداد هم نگه داشته می‌شود", "<= 8192 bytes + no raw IP", "bytes=${bigPacket.toString().toByteArray(Charsets.UTF_8).size} hasIp=${bigPacket.toString().contains("10.0.0.5")}",
            bigPacket.toString().toByteArray(Charsets.UTF_8).size <= MAX_PACKET_BYTES && !bigPacket.toString().contains("10.0.0.5"))
        // 9. Request id format + reply parsing.
        val rid = newRequestId(AiKind.ANALYSIS, 1762795200000L, 7)
        t("AI — شناسه دور قالب درست دارد", "r_yyyymmdd_hhmmss_4hex", "id=$rid", Regex("r_\\d{8}_\\d{6}_[0-9a-f]{4}").matches(rid))
        t("AI — استخراج شناسه از متن پاسخ", "r_20261112_033000_7f3a", "id=${extractRequestId("پاسخ برای r_20261112_033000_7f3a آماده است")}",
            extractRequestId("پاسخ برای r_20261112_033000_7f3a آماده است") == "r_20261112_033000_7f3a")
        val jsonReply = "{\"request_id\": \"r_20261112_033000_7f3a\", \"verdict\": \"degraded\", \"cause\": \"Gateway down\", \"fix\": \"restart\", \"confidence\": 72, \"evidence\": [\"a\", \"b\"]}"
        val parsed = parseReplyText("متن قبل $jsonReply متن بعد", "r_20261112_033000_7f3a", PROVIDER_SHARE, null)
        t("AI — پاسخ JSON از متن استخراج می‌شود", "degraded 72", "verdict=${parsed?.verdict} conf=${parsed?.confidence}",
            parsed != null && parsed.verdict == "degraded" && parsed.confidence == 72 && parsed.evidence.size == 2 && !parsed.parseFailed)
        val rawReply = parseReplyText("فقط یک متن ساده بدون JSON", "r_20261112_033000_7f3a", PROVIDER_SHARE, null)
        t("AI — پاسخ بدون JSON با parse_failed ذخیره می‌شود", "unknown + parseFailed", "verdict=${rawReply?.verdict} failed=${rawReply?.parseFailed}",
            rawReply != null && rawReply.verdict == "unknown" && rawReply.parseFailed)
        t("AI — پاسخ خالی null می‌دهد", "null", "parsed=${parseReplyText("   ", "r_20261112_033000_7f3a", PROVIDER_SHARE, null)}",
            parseReplyText("   ", "r_20261112_033000_7f3a", PROVIDER_SHARE, null) == null)
        // 10. Readable text carries the findings + locations + JSON.
        val txt = buildReadableText(packet, analyzeLocal(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())).copy(events = listOf(evWithLoc))))
        t("AI — متن خوانا یافته و مکان و JSON دارد", "has finding + History.kt:412 + JSON", "has=${txt.contains("RESTORE_ABORT") && txt.contains("History.kt:412") && txt.contains("\"request_id\"")}",
            txt.contains("RESTORE_ABORT") && txt.contains("History.kt:412") && txt.contains("\"request_id\""))
        // 11. requestFromRound round-trip (analysis + question) and corrupt packet.
        val rtRound = AiRound(1L, "r_20261112_033000_7f3a", 1762795200000L, "analysis", PROVIDER_LOCAL, null, STATUS_ANSWERED, packet.toString(2), null, "x", "ok", 95, 0L, 0, null, null)
        val rtReq = requestFromRound(rtRound)
        t("AI — ساخت درخواست از دور ذخیره‌شده", "same id + kind", "id=${rtReq?.requestId} kind=${rtReq?.kind}",
            rtReq != null && rtReq.requestId == "r_20261112_033000_7f3a" && rtReq.kind == AiKind.ANALYSIS)
        val qPacket = buildPacket(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList())),
            analyzeLocal(emptyInput(clsOk, ProbeAgg(2, 2, 100, 100, emptyList(), emptyList()))),
            "q_20261112_033500_1b7e", AiKind.QUESTION, "چرا لگ دارم؟", "r_20261112_033000_7f3a", "2.10.0", 34, "SM-A528B", 34, 1762795200000L)
        val qRound = AiRound(2L, "q_20261112_033500_1b7e", 1762795200000L, "question", PROVIDER_LOCAL, null, STATUS_ANSWERED, qPacket.toString(2), null, "x", "ok", 95, 0L, 0, null, null)
        val qReq = requestFromRound(qRound)
        t("AI — درخواست سوال شناسه والد و متن سوال را نگه می‌دارد", "question + parent kept", "kind=${qReq?.kind} parent=${qReq?.parentRequestId} q=${qReq?.question}",
            qReq != null && qReq.kind == AiKind.QUESTION && qReq.parentRequestId == "r_20261112_033000_7f3a" && qReq.question == "چرا لگ دارم؟")
        val badRound = rtRound.copy(packetJson = "{not json")
        t("AI — بسته خراب null می‌دهد", "null", "req=${requestFromRound(badRound)}", requestFromRound(badRound) == null)
        // 12. Response JSON/readable round-trip.
        val resp = AiResponse("r_20261112_033000_7f3a", PROVIDER_SHARE, null, "degraded", "Gateway down", "restart modem", 72, listOf("a"), "2026-11-12T03:31:05+03:30", 4200L)
        val respJson = responseToJson(resp, "raw text")
        val respMd = responseToReadable(resp, "raw text")
        t("AI — پاسخ به JSON و متن خوانا تبدیل می‌شود", "verdict degraded + fix in md", "verdict=${respJson.optString("verdict")} mdHas=${respMd.contains("restart modem")}",
            respJson.optString("verdict") == "degraded" && respJson.optInt("confidence") == 72 && respMd.contains("restart modem") && respMd.contains("72٪"))
        return out
    }
}
