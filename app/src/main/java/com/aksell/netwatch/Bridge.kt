package com.aksell.netwatch

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// NETWATCH–MUSE BRIDGE — core (Phase 1; spec v1.0 approved 2026-11-10).
//
// What this file is:
// - A module-tagged event logger (in-memory ring + persisted JSON lines).
// - A sanitizer: sensitive data is stripped BEFORE anything is stored.
// - A chained uncaught-exception handler: records a crash atomically and
//   then chains to the previous handler, so a crash still crashes.
// - Breadcrumbs: the steps leading up to an error.
// - A read-only health snapshot (on demand, off the main thread).
//
// What this file is NOT:
// - No chatbot, no AI analysis, no network upload, no new dependency.
// - No behaviour change to any existing feature: nothing here is called
//   from existing code except the crash-handler install (NetWatchApp /
//   MainActivity) and the breadcrumb mirror (History.addTimeline).
//
// Pure vs on-device:
// - sanitize(), signatureOf(), extractLocation(), inferModule(),
//   isDuplicateCrash() and the dedup/eviction rules are pure and covered
//   by Bridge.selfTests() on the JVM.
// - Persistence, crash files and healthSnapshot() need a real device
//   (Context / SQLite / org.json on Android) and are NOT run on the JVM.
object Bridge {

    enum class Module { APP, MONITOR, NETWORK, GEO, ANALYTICS, HISTORY, RESTORE, GAMING, LAB, WIDGET, UI, EXPORT, SYSTEM }
    enum class Status { ERROR, WARNING, UNKNOWN, NOT_RUN }
    enum class Kind { CRASH, EXCEPTION, LAB_FAIL, HEALTH_FAIL, RESTORE_ABORT, SERVICE_DEATH, EXPORT_FAIL, WIDGET_STALE }
    enum class ModuleSource { EXPLICIT, STACK_TRACE_INFERRED, UNKNOWN }
    enum class LocationSource { STACK_TRACE, NONE }

    data class Frame(val cls: String, val method: String, val file: String?, val line: Int)
    data class Breadcrumb(val ts: Long, val kind: String, val text: String)
    // One level of a throwable's cause chain (audit fix 2).
    data class CauseInfo(val cls: String, val message: String, val frames: List<Frame>)

    data class Event(
        val eventId: String,
        val ts: Long,
        val module: Module,
        val moduleSource: ModuleSource,
        val status: Status,
        val kind: Kind,
        val message: String,
        val file: String?,
        val line: Int?,
        val function: String?,
        val locationSource: LocationSource,
        val stackTrace: List<Frame>,
        val stackSignature: String,
        val occurrences: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val breadcrumbs: List<String>,
        val context: Map<String, Any>,
        val causedBy: List<CauseInfo> = emptyList()
    )

    // Read-only health snapshot (spec §9). lastCheck carries what
    // History.LastCheck actually stores (intl/dom percentages + score),
    // not an invented ok/total count.
    data class Health(
        val serviceRunning: Boolean,
        val monitoringOn: Boolean,
        val lastHeartbeatAgeS: Long?,
        val lastCheckPingAvg: Double?,
        val lastCheckLossPct: Double?,
        val lastCheckIntlPct: Int?,
        val lastCheckDomPct: Int?,
        val lastCheckScore: Int?,
        val lastCheckTime: Long?,
        val widgetLastRefreshAgeS: Long?,
        val dbIntegrity: String, // ok | fail | not_checked
        val dbIntegrityDetail: String,
        val dbSamples: Long?,
        val dbPingPoints: Long?,
        val dbGamingSessions: Long?,
        val dbGamingSamplesLast: Long?,
        val restoreJournalState: String, // NONE | STARTED | COMMITTED | UNKNOWN
        val lastRestoreDiag: String,
        val lastGamingSession: String,
        val lastLabRun: String,
        val storageFreeMb: Long?
    )

    // ---- caps (spec §13) ----
    const val MAX_EVENTS = 200
    const val MAX_CRASH_FILES = 5
    const val MAX_BREADCRUMBS = 100
    const val MAX_MESSAGE_CHARS = 500
    const val MAX_STACK_FRAMES = 30
    const val MAX_EVENT_BYTES = 8 * 1024
    const val MAX_CRASH_BYTES = 32 * 1024
    const val MAX_BRIDGE_BYTES = 512 * 1024L
    const val CRASH_DEDUP_WINDOW_MS = 10 * 60 * 1000L
    const val MAX_EVENT_AGE_MS = 30L * 24 * 60 * 60 * 1000

    const val PKG = "com.aksell.netwatch"
    private const val DIR_NAME = "bridge"
    private const val EVENTS_NAME = "events.jsonl"
    private const val CRASHES_DIR = "crashes"

    private val lock = Any()
    // File lock (audit fix 3): serializes every read/write on the bridge
    // dir (append, atomic rewrite, cap enforcement, crash write, startup
    // load). It is NEVER held at the same time as `lock`: log() releases
    // `lock` before persisting, recordCrash snapshots breadcrumbs (a brief
    // `lock`) before entering fileLock, and loadPersistedState reads files
    // under fileLock first and only then merges into the ring under `lock`.
    // No nesting in either order means no deadlock between the two locks.
    private val fileLock = Any()
    private val ring = ArrayList<Event>()
    private val crumbs = ArrayList<Breadcrumb>()
    private val counter = AtomicLong(0)
    private val handlerInstalled = AtomicBoolean(false)
    @Volatile private var appCtx: Context? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    // Test-only storage override (JVM harness): when set, bridgeDir()
    // returns this directory without needing a Context, so the file path
    // (persist, enforceCaps, crash write, temp cleanup) is testable on
    // the JVM. Never set in production.
    @Volatile private var testDir: File? = null

    fun timeStr(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))

    // ================= sanitizer (pure) =================
    // Order matters: IPs first (so a long hex token rule never sees them),
    // credentials before the generic long-hex rule.
    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val IPV6_COMPRESSED = Regex("[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4})*::[0-9a-fA-F:]*")
    private val IPV6_FULL = Regex("\\b(?=[0-9a-fA-F:]*[a-fA-F])(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\\b")
    private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")
    private val EMAIL = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val IR_MOBILE_INTL = Regex("\\+98\\d{10}\\b")
    private val IR_MOBILE = Regex("\\b09\\d{9}\\b")
    private val PATH_SENSITIVE = Regex("/(?:data|storage|sdcard)/[^\\s\"']*")
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~-]+")
    private val CRED_ASSIGN = Regex("(?i)(token|api[_-]?key|password|passwd|secret)[=:]\\s*[^\\s,;]+")
    private val SSID_Q = Regex("(?i)(ssid\\s*[:=]\\s*\")[^\"]*(\")")
    private val SSID_BARE = Regex("(?i)(ssid\\s*[:=]\\s*)[^\\s,;]+")
    private val HEX_TOKEN = Regex("\\b[0-9a-fA-F]{32,}\\b")

    /** Strips network identifiers, credentials, paths and tokens. Pure. */
    fun sanitize(text: String): String {
        var s = text
        s = IPV4.replace(s, "<ip>")
        // MAC before IPv6: "AA:BB:CC:DD:EE:FF" is hex-colon shaped and
        // would otherwise match the IPv6 full form.
        s = MAC.replace(s, "<mac>")
        s = IPV6_COMPRESSED.replace(s, "<ip6>")
        s = IPV6_FULL.replace(s, "<ip6>")
        s = EMAIL.replace(s, "<email>")
        s = IR_MOBILE_INTL.replace(s, "<phone>")
        s = IR_MOBILE.replace(s, "<phone>")
        s = PATH_SENSITIVE.replace(s, "<path>")
        s = BEARER.replace(s, "$1<token>")
        s = CRED_ASSIGN.replace(s, "$1=<redacted>")
        s = SSID_Q.replace(s, "$1<ssid>$2")
        s = SSID_BARE.replace(s, "$1<ssid>")
        s = HEX_TOKEN.replace(s, "<token>")
        return s
    }

    // ================= pure location / signature / module =================

    /** First frame in our own package. Line only with real evidence (>= 1). */
    fun extractLocation(t: Throwable): Triple<String?, Int?, String?> {
        for (e in t.stackTrace) {
            if (e.className.startsWith(PKG)) {
                val line = if (e.lineNumber >= 1) e.lineNumber else null
                return Triple(e.fileName, line, e.methodName)
            }
        }
        return Triple(null, null, null)
    }

    /** Dedup signature: exception class + top 3 frames. Pure. */
    fun signatureOf(t: Throwable): String {
        val top = t.stackTrace.take(3).joinToString("|") { "${it.className}.${it.methodName}" }
        return t.javaClass.name + "|" + top
    }

    /** Maps a top-level class name to its owning module (spec §3). Pure. */
    fun moduleForClass(topClass: String): Module = when (topClass) {
        "MainActivity", "NetWatchApp" -> Module.APP
        "MonitorService", "MonitoringEngine" -> Module.MONITOR
        "NetworkEngine", "Probes", "Diagnostics", "Advanced" -> Module.NETWORK
        "Geo", "NetworkInfo" -> Module.GEO
        "AnalyticsEngine", "Quality" -> Module.ANALYTICS
        "History", "HistoryDb", "HistoryEngine" -> Module.HISTORY
        "RestoreStream" -> Module.RESTORE
        "Gaming", "GamingActivity" -> Module.GAMING
        "Lab", "LabActivity" -> Module.LAB
        "NetWatchWidget", "WidgetStats", "ChartData" -> Module.WIDGET
        "GaugeView", "GraphView", "SparklineView" -> Module.UI
        "ExportHelper" -> Module.EXPORT
        "NetExecutor", "Bridge" -> Module.SYSTEM
        else -> Module.SYSTEM
    }

    // History.kt hosts both HISTORY and RESTORE functions; the method name
    // decides for that file. Everything else is decided by the class.
    private fun isRestoreMethod(m: String): Boolean {
        return m.startsWith("doRestore") || m == "phaseB" || m.startsWith("rollback") ||
            m.startsWith("recover") || m.startsWith("reconcile") || m.startsWith("migrate") ||
            m.contains("Journal") || m.startsWith("restore")
    }

    /**
     * Module inference — ONLY for uncaught crashes, where no caller passed
     * a module. The result is always labelled STACK_TRACE_INFERRED (or
     * UNKNOWN), never presented as certain. Pure.
     */
    fun inferModule(t: Throwable): Pair<Module, ModuleSource> {
        for (e in t.stackTrace) {
            if (!e.className.startsWith(PKG)) continue
            val top = e.className.removePrefix("$PKG.").substringBefore('$').substringBefore('.')
            val mod = if (top == "History" && isRestoreMethod(e.methodName)) Module.RESTORE else moduleForClass(top)
            return Pair(mod, ModuleSource.STACK_TRACE_INFERRED)
        }
        return Pair(Module.SYSTEM, ModuleSource.UNKNOWN)
    }

    /** Crash dedup rule (decision 3): same signature inside 10 minutes. Pure. */
    fun isDuplicateCrash(existingTs: Long, now: Long, sameSignature: Boolean): Boolean {
        if (!sameSignature) return false
        val age = now - existingTs
        return age in 0 until CRASH_DEDUP_WINDOW_MS
    }

    /**
     * The cause chain of a throwable, up to 3 levels (audit fix 2).
     * Cycle-safe: a cause already seen (by identity — Throwable does not
     * override equals) stops the walk, so an a<->b cycle can never loop
     * forever. Messages are sanitized at every level. Pure.
     */
    fun causeChain(t: Throwable, maxDepth: Int = 3, framesPerCause: Int = 10): List<CauseInfo> {
        val out = ArrayList<CauseInfo>()
        val seen = HashSet<Throwable>()
        seen.add(t)
        var c = t.cause
        while (c != null && out.size < maxDepth) {
            if (!seen.add(c)) break
            val msg = sanitize(c.message ?: c.javaClass.simpleName).take(MAX_MESSAGE_CHARS)
            val frames = c.stackTrace.take(framesPerCause).map { Frame(it.className, it.methodName, it.fileName, it.lineNumber) }
            out.add(CauseInfo(c.javaClass.name, msg, frames))
            c = c.cause
        }
        return out
    }

    /** Last 10 breadcrumbs as formatted strings (audit fix 1). Never throws. */
    fun snapshotBreadcrumbs(): List<String> {
        return try {
            synchronized(lock) { crumbs.takeLast(10).map { "${timeStr(it.ts)} [${it.kind}] ${it.text}" } }
        } catch (_: Exception) { emptyList() }
    }

    /** Test-only: overrides the bridge storage directory (JVM harness). */
    fun setTestDirForTests(dir: File?) {
        testDir = dir
    }

    private fun framesToJson(frames: List<Frame>): JSONArray {
        val arr = JSONArray()
        for (fr in frames) {
            val fo = JSONObject()
            fo.put("class", fr.cls)
            fo.put("method", fr.method)
            if (fr.file != null) fo.put("file", fr.file) else fo.put("file", JSONObject.NULL)
            fo.put("line", fr.line)
            arr.put(fo)
        }
        return arr
    }

    // ================= event log =================

    fun events(): List<Event> = synchronized(lock) { ring.toList() }
    fun breadcrumbs(): List<Breadcrumb> = synchronized(lock) { crumbs.toList() }

    /** Test-only: clears the in-memory rings and the id counter. */
    fun resetForTests() {
        synchronized(lock) {
            ring.clear()
            crumbs.clear()
            counter.set(0)
        }
    }

    private fun evictIfNeededLocked() {
        while (ring.size > MAX_EVENTS) {
            // Definite errors are evicted last: drop the oldest non-ERROR
            // first; only when the ring is all ERROR drop the oldest one.
            val idx = ring.indexOfFirst { it.status != Status.ERROR }
            if (idx >= 0) ring.removeAt(idx) else ring.removeFirst()
        }
    }

    /**
     * Records one event. The module is ALWAYS passed explicitly by the
     * caller — never guessed here. The message is sanitized before it is
     * stored. A repeat of the same signature only bumps `occurrences`.
     * ERROR events are also persisted (when an app context is known);
     * without one (JVM tests) the ring is memory-only.
     */
    fun log(
        module: Module,
        status: Status,
        kind: Kind,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ): Event {
        val now = System.currentTimeMillis()
        val cleanMsg = sanitize(message).take(MAX_MESSAGE_CHARS)
        val (file, line, fn) = if (throwable != null) extractLocation(throwable) else Triple(null, null, null)
        val frames = if (throwable != null) {
            throwable.stackTrace.take(MAX_STACK_FRAMES).map { Frame(it.className, it.methodName, it.fileName, it.lineNumber) }
        } else emptyList()
        val sig = if (throwable != null) signatureOf(throwable) else module.name + "|" + kind.name + "|" + cleanMsg.hashCode()
        val locSrc = if (throwable != null && file != null) LocationSource.STACK_TRACE else LocationSource.NONE
        // Fix 5: context values are sanitized before storage (and again on
        // output in eventToJson). Non-string values pass through unchanged.
        val cleanContext = context.mapValues { (_, v) -> if (v is String) sanitize(v) else v }
        val causes = if (throwable != null) causeChain(throwable) else emptyList()
        val crumbsCopy = snapshotBreadcrumbs()
        val result: Event
        synchronized(lock) {
            val idx = ring.indexOfFirst { it.stackSignature == sig && it.module == module && it.kind == kind }
            if (idx >= 0) {
                val old = ring[idx]
                val updated = old.copy(occurrences = old.occurrences + 1, lastSeen = now, message = cleanMsg)
                ring[idx] = updated
                result = updated
            } else {
                val id = "$now-${counter.incrementAndGet()}"
                val ev = Event(id, now, module, ModuleSource.EXPLICIT, status, kind, cleanMsg, file, line, fn, locSrc, frames, sig, 1, now, now, crumbsCopy, cleanContext, causes)
                ring.add(ev)
                evictIfNeededLocked()
                result = ev
            }
        }
        if (result.status == Status.ERROR && result.occurrences == 1) persistEvent(result)
        return result
    }

    /** Records one breadcrumb (a step leading up to an error). */
    fun breadcrumb(kind: String, text: String) {
        val clean = sanitize(text).take(200)
        synchronized(lock) {
            crumbs.add(Breadcrumb(System.currentTimeMillis(), kind, clean))
            while (crumbs.size > MAX_BREADCRUMBS) crumbs.removeFirst()
        }
        // Mirror into the existing timeline store (best effort, never fatal).
        try {
            appCtx?.let { History.addTimeline(it, kind, clean) }
        } catch (_: Exception) {
        }
    }

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    // ================= persistence (on-device) =================

    private fun bridgeDir(ctx: Context?): File? {
        return try {
            val override = testDir
            if (override != null) {
                if (!override.exists()) override.mkdirs()
                if (override.isDirectory) override else null
            } else {
                val c = ctx ?: return null
                val d = File(c.filesDir, DIR_NAME)
                if (!d.exists()) d.mkdirs()
                if (d.isDirectory) d else null
            }
        } catch (_: Exception) { null }
    }

    private fun eventToJson(ev: Event): JSONObject {
        val o = JSONObject()
        o.put("schema_version", 1)
        o.put("event_id", ev.eventId)
        o.put("ts", ev.ts)
        o.put("module", ev.module.name)
        o.put("module_source", ev.moduleSource.name)
        o.put("status", ev.status.name)
        o.put("kind", ev.kind.name)
        o.put("message", ev.message)
        if (ev.file != null) o.put("file", ev.file) else o.put("file", JSONObject.NULL)
        if (ev.line != null) o.put("line", ev.line) else o.put("line", JSONObject.NULL)
        if (ev.function != null) o.put("function", ev.function) else o.put("function", JSONObject.NULL)
        o.put("location_source", ev.locationSource.name)
        o.put("stack_trace", framesToJson(ev.stackTrace))
        o.put("stack_signature", ev.stackSignature)
        o.put("occurrences", ev.occurrences)
        o.put("first_seen", ev.firstSeen)
        o.put("last_seen", ev.lastSeen)
        val ba = JSONArray()
        for (b in ev.breadcrumbs) ba.put(b)
        o.put("breadcrumbs", ba)
        val co = JSONObject()
        for ((k, v) in ev.context) {
            when (v) {
                // Fix 5: sanitize again on output (defense in depth; the
                // values were already sanitized at log time).
                is String -> co.put(k, sanitize(v))
                is Number -> co.put(k, v)
                is Boolean -> co.put(k, v)
                else -> co.put(k, sanitize(v.toString()))
            }
        }
        o.put("context", co)
        val ca = JSONArray()
        for (ci in ev.causedBy) {
            val cjo = JSONObject()
            cjo.put("class", ci.cls)
            cjo.put("message", ci.message)
            cjo.put("stack_trace", framesToJson(ci.frames))
            ca.put(cjo)
        }
        o.put("caused_by", ca)
        return o
    }

    private fun parseEvent(o: JSONObject): Event? {
        return try {
            val frames = ArrayList<Frame>()
            val arr = o.optJSONArray("stack_trace")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val fo = arr.optJSONObject(i) ?: continue
                    frames.add(Frame(fo.optString("class", "?"), fo.optString("method", "?"), if (fo.isNull("file")) null else fo.optString("file"), fo.optInt("line", -1)))
                }
            }
            val ctxMap = HashMap<String, Any>()
            val co = o.optJSONObject("context")
            if (co != null) {
                val keys = co.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = co.opt(k)
                    if (v is String || v is Number || v is Boolean) ctxMap[k] = v
                }
            }
            val crumbsList = ArrayList<String>()
            val ba = o.optJSONArray("breadcrumbs")
            if (ba != null) for (i in 0 until ba.length()) crumbsList.add(ba.optString(i, ""))
            val causesList = ArrayList<CauseInfo>()
            val ca = o.optJSONArray("caused_by")
            if (ca != null) {
                for (i in 0 until ca.length()) {
                    val cjo = ca.optJSONObject(i) ?: continue
                    val cframes = ArrayList<Frame>()
                    val fa = cjo.optJSONArray("stack_trace")
                    if (fa != null) {
                        for (j in 0 until fa.length()) {
                            val fo = fa.optJSONObject(j) ?: continue
                            cframes.add(Frame(fo.optString("class", "?"), fo.optString("method", "?"), if (fo.isNull("file")) null else fo.optString("file"), fo.optInt("line", -1)))
                        }
                    }
                    causesList.add(CauseInfo(cjo.optString("class", "?"), cjo.optString("message", ""), cframes))
                }
            }
            Event(
                eventId = o.optString("event_id", "${o.optLong("ts", 0)}-x"),
                ts = o.optLong("ts", 0),
                module = Module.valueOf(o.optString("module", "SYSTEM")),
                moduleSource = ModuleSource.valueOf(o.optString("module_source", "UNKNOWN")),
                status = Status.valueOf(o.optString("status", "UNKNOWN")),
                kind = Kind.valueOf(o.optString("kind", "EXCEPTION")),
                message = o.optString("message", ""),
                file = if (o.isNull("file")) null else o.optString("file"),
                line = if (o.isNull("line")) null else o.optInt("line"),
                function = if (o.isNull("function")) null else o.optString("function"),
                locationSource = LocationSource.valueOf(o.optString("location_source", "NONE")),
                stackTrace = frames,
                stackSignature = o.optString("stack_signature", ""),
                occurrences = o.optInt("occurrences", 1),
                firstSeen = o.optLong("first_seen", o.optLong("ts", 0)),
                lastSeen = o.optLong("last_seen", o.optLong("ts", 0)),
                breadcrumbs = crumbsList,
                context = ctxMap,
                causedBy = causesList
            )
        } catch (_: Exception) { null }
    }

    private fun persistEvent(ev: Event) {
        // Fix 3: the whole append + cap enforcement runs under fileLock,
        // so a concurrent ERROR log can never interleave with the
        // read-modify-write of enforceCaps and lose an event.
        synchronized(fileLock) {
            try {
                val dir = bridgeDir(appCtx) ?: return
                val line = eventToJson(ev).toString()
                if (line.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) return
                File(dir, EVENTS_NAME).appendText(line + "\n")
                enforceCaps(dir)
            } catch (_: Exception) { }
        }
    }

    // Enforces every cap from spec §13. Oldest first: crash files, then
    // event lines. Caller must hold fileLock (persistEvent, recordCrash).
    // Never throws.
    private fun enforceCaps(dir: File) {
        try {
            val cd = File(dir, CRASHES_DIR)
            val crashes = cd.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: emptyList()
            if (crashes.size > MAX_CRASH_FILES) {
                for (f in crashes.take(crashes.size - MAX_CRASH_FILES)) {
                    try { f.delete() } catch (_: Exception) { }
                }
            }
            val ef = File(dir, EVENTS_NAME)
            if (ef.exists()) {
                val cutoff = System.currentTimeMillis() - MAX_EVENT_AGE_MS
                val kept = ef.readLines().mapNotNull { line ->
                    try {
                        val o = JSONObject(line)
                        if (o.optLong("ts", 0) >= cutoff) line else null
                    } catch (_: Exception) { null }
                }.takeLast(MAX_EVENTS)
                val tmp = File(dir, EVENTS_NAME + ".tmp")
                tmp.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
                if (!tmp.renameTo(ef)) {
                    try { ef.delete() } catch (_: Exception) { }
                    tmp.renameTo(ef)
                }
            }
            var total = 0L
            try {
                total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (_: Exception) { }
            if (total > MAX_BRIDGE_BYTES) {
                val remaining = cd.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: emptyList()
                for (f in remaining) {
                    if (total <= MAX_BRIDGE_BYTES) break
                    val sz = f.length()
                    if (f.delete()) total -= sz
                }
            }
            if (total > MAX_BRIDGE_BYTES && ef.exists()) {
                val kept = ef.readLines().takeLast(50)
                ef.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
            }
        } catch (_: Exception) { }
    }

    /** Deletes stale *.tmp files left by a killed process (fix 4). Only
     *  inside the Bridge dir and its crashes subdir; only ".tmp" names —
     *  a valid .json/.jsonl file can never match. Safe to run at startup:
     *  a .tmp is only ever an in-progress write from a dead process. */
    private fun cleanTempFiles(dir: File) {
        try {
            val targets = ArrayList<File>()
            dir.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".tmp")) targets.add(f) }
            val cd = File(dir, CRASHES_DIR)
            cd.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".tmp")) targets.add(f) }
            for (f in targets) {
                try { f.delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /** Loads persisted events and crashes into the ring (startup). */
    fun loadPersistedState(ctx: Context) {
        init(ctx)
        // File reads happen under fileLock; the ring merge happens after,
        // under `lock` — the two locks are never held at the same time.
        val loaded = ArrayList<Event>()
        synchronized(fileLock) {
            try {
                val dir = bridgeDir(ctx) ?: return
                cleanTempFiles(dir)
                val f = File(dir, EVENTS_NAME)
                if (f.exists()) {
                    for (line in f.readLines().takeLast(MAX_EVENTS)) {
                        try { parseEvent(JSONObject(line))?.let { loaded.add(it) } } catch (_: Exception) { }
                    }
                }
                val cd = File(dir, CRASHES_DIR)
                val files = cd.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: emptyList()
                for (cf in files) {
                    try { parseEvent(JSONObject(cf.readText()))?.let { loaded.add(it) } } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
        for (ev in loaded) addPersisted(ev)
    }

    private fun addPersisted(ev: Event) {
        synchronized(lock) {
            if (ring.none { it.stackSignature == ev.stackSignature && it.module == ev.module && it.kind == ev.kind }) {
                ring.add(ev)
            }
            evictIfNeededLocked()
        }
    }

    // ================= crash handler =================

    /**
     * Installs the Bridge crash handler (idempotent). The previous handler
     * is saved and always chained afterwards, so a crash still crashes.
     * Call once from Application.onCreate; re-asserting from MainActivity
     * is a harmless no-op.
     */
    fun installCrashHandler(ctx: Context) {
        init(ctx)
        if (!handlerInstalled.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { recordCrash(thread, throwable) } catch (_: Exception) { }
            try {
                val prev = previousHandler
                if (prev != null) {
                    prev.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            } catch (_: Exception) {
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Exception) { }
            }
        }
    }

    private fun crashJson(now: Long, threadName: String, mod: Module, modSrc: ModuleSource, msg: String, file: String?, line: Int?, fn: String?, sig: String, frames: List<Frame>, causes: List<CauseInfo>, crumbs: List<String>): JSONObject {
        val o = JSONObject()
        o.put("schema_version", 1)
        o.put("ts", now)
        o.put("thread", threadName)
        o.put("module", mod.name)
        o.put("module_source", modSrc.name)
        o.put("status", "ERROR")
        o.put("kind", "CRASH")
        o.put("message", msg)
        if (file != null) o.put("file", file) else o.put("file", JSONObject.NULL)
        if (line != null) o.put("line", line) else o.put("line", JSONObject.NULL)
        if (fn != null) o.put("function", fn) else o.put("function", JSONObject.NULL)
        o.put("location_source", if (file != null) "STACK_TRACE" else "NONE")
        o.put("stack_signature", sig)
        o.put("stack_trace", framesToJson(frames))
        // Fix 1: the steps leading up to the crash.
        val ba = JSONArray()
        for (b in crumbs) ba.put(b)
        o.put("breadcrumbs", ba)
        // Fix 2: the cause chain (max 3 levels, cycle-safe, sanitized).
        val ca = JSONArray()
        for (ci in causes) {
            val cjo = JSONObject()
            cjo.put("class", ci.cls)
            cjo.put("message", ci.message)
            cjo.put("stack_trace", framesToJson(ci.frames))
            ca.put(cjo)
        }
        o.put("caused_by", ca)
        return o
    }

    /**
     * Records one crash atomically (temp file + rename) and without
     * duplicates (same signature inside 10 minutes writes no new file).
     * Every step is guarded: a failure here must never stop the crash
     * from being chained to the previous handler. Sensitive data is
     * sanitized BEFORE the file is written.
     */
    private fun recordCrash(thread: Thread, t: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val sig = signatureOf(t)
            val (file, line, fn) = extractLocation(t)
            val (mod, modSrc) = inferModule(t)
            val frames = t.stackTrace.take(MAX_STACK_FRAMES).map { Frame(it.className, it.methodName, it.fileName, it.lineNumber) }
            val msg = sanitize(t.message ?: t.javaClass.simpleName).take(MAX_MESSAGE_CHARS)
            // Fix 1: snapshot the breadcrumbs BEFORE touching the files.
            // snapshotBreadcrumbs() never throws; if it somehow returned
            // nothing, the crash is still recorded — only the context is lost.
            val crumbsCopy = snapshotBreadcrumbs()
            // Fix 2: cause chain, max 3 levels, cycle-safe, sanitized.
            val causes = causeChain(t)
            val baseDir = bridgeDir(appCtx) ?: return
            val dir = File(baseDir, CRASHES_DIR).apply { if (!exists()) mkdirs() }
            // Fix 3: the whole file section (dedup read, write, caps)
            // runs under fileLock. The memory lock was already released
            // by snapshotBreadcrumbs(); the two locks never nest.
            synchronized(fileLock) {
                // Dedup first: the same crash inside 10 minutes is not new.
                val existing = dir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
                for (f in existing) {
                    try {
                        val eo = JSONObject(f.readText())
                        if (eo.optString("stack_signature") == sig && isDuplicateCrash(eo.optLong("ts", 0), now, true)) return
                    } catch (_: Exception) { }
                }
                var text = crashJson(now, thread.name, mod, modSrc, msg, file, line, fn, sig, frames, causes, crumbsCopy).toString()
                if (text.toByteArray(Charsets.UTF_8).size > MAX_CRASH_BYTES) {
                    text = crashJson(now, thread.name, mod, modSrc, msg, file, line, fn, sig, frames.take(10), causes.map { it.copy(frames = emptyList()) }, crumbsCopy).toString()
                }
                if (text.toByteArray(Charsets.UTF_8).size > MAX_CRASH_BYTES) {
                    text = crashJson(now, thread.name, mod, modSrc, msg, file, line, fn, sig, emptyList(), emptyList(), emptyList()).toString()
                }
                val tmp = File(dir, "crash-$now.tmp")
                val dst = File(dir, "crash-$now-${Integer.toHexString(sig.hashCode())}.json")
                tmp.writeText(text)
                if (!tmp.renameTo(dst)) {
                    try { tmp.delete() } catch (_: Exception) { }
                    dst.writeText(text)
                }
                enforceCaps(baseDir)
            }
        } catch (_: Exception) { }
    }

    // ================= health snapshot (on-device, on demand) =================

    private fun countOf(db: android.database.sqlite.SQLiteDatabase, sql: String): Long? {
        return try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) { null }
    }

    /**
     * Read-only snapshot of component health. Must be called OFF the main
     * thread: the DB section is skipped on the main thread (integrity
     * stays "not_checked") instead of risking a UI stall. A failure to
     * read one part never fails the whole snapshot — that part reports
     * null / "not_checked" / "UNKNOWN".
     */
    fun healthSnapshot(ctx: Context, serviceRunning: Boolean): Health {
        val now = System.currentTimeMillis()
        val hb = try { HistoryEngine.lastHeartbeat(ctx) } catch (_: Exception) { 0L }
        val hbAge = if (hb > 0) (now - hb) / 1000 else null
        val lc = try { HistoryEngine.lastCheck(ctx) } catch (_: Exception) { null }
        val wAge = try {
            val u = WidgetStats.snapshot(ctx).updatedAtMs
            if (u > 0) (now - u) / 1000 else null
        } catch (_: Exception) { null }
        var integrity = "not_checked"
        var integrityDetail = ""
        var samples: Long? = null
        var pingPoints: Long? = null
        var gamingSessions: Long? = null
        var gamingSamplesLast: Long? = null
        var lastSessionText = "none"
        if (Looper.getMainLooper().thread != Thread.currentThread()) {
            try {
                val db = HistoryDb.getReadDb(ctx)
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) {
                        val v = c.getString(0) ?: ""
                        if (v == "ok") {
                            integrity = "ok"
                        } else {
                            integrity = "fail"
                            integrityDetail = sanitize(v).take(200)
                        }
                    } else {
                        integrity = "fail"
                        integrityDetail = "empty result"
                    }
                }
                samples = countOf(db, "SELECT COUNT(*) FROM samples")
                pingPoints = countOf(db, "SELECT COUNT(*) FROM ping_points")
                gamingSessions = countOf(db, "SELECT COUNT(*) FROM gaming_sessions")
                val lastSession = try { HistoryEngine.gamingSessions(ctx).maxByOrNull { it.started } } catch (_: Exception) { null }
                if (lastSession != null) {
                    lastSessionText = "${lastSession.profile} • ${lastSession.region} • avg ${"%.0f".format(lastSession.avgMs)}ms • loss ${"%.1f".format(lastSession.loss)}% • score ${lastSession.score ?: "—"}"
                    gamingSamplesLast = countOf(db, "SELECT COUNT(*) FROM gaming_samples WHERE session_id=${lastSession.id}")
                }
            } catch (e: Exception) {
                if (integrity == "not_checked") integrityDetail = e.javaClass.simpleName
            }
        }
        val journal = try { History.restoreJournalState(ctx) } catch (_: Exception) { "UNKNOWN" }
        val diag = try { sanitize(History.lastRestoreDiag).take(200) } catch (_: Exception) { "" }
        val lastLab = try {
            val run = Lab.loadRuns(ctx).maxByOrNull { it.timestamp }
            if (run == null) {
                "none"
            } else {
                val passed = run.results.count { it.pass == true }
                val failed = run.results.count { it.pass == false }
                val notTested = run.results.count { it.pass == null }
                val failing = run.results.filter { it.pass == false }.take(5).joinToString(", ") { it.name }
                "v${run.version} • ${run.section} • ${run.results.size} tests • $passed pass • $failed fail • $notTested not tested" +
                    if (failing.isNotEmpty()) " • failing: $failing" else ""
            }
        } catch (_: Exception) { "unreadable" }
        val freeMb = try { ctx.filesDir.freeSpace / 1024 / 1024 } catch (_: Exception) { null }
        return Health(
            serviceRunning = serviceRunning,
            monitoringOn = serviceRunning,
            lastHeartbeatAgeS = hbAge,
            lastCheckPingAvg = lc?.pingAvg,
            lastCheckLossPct = lc?.lossPct,
            lastCheckIntlPct = lc?.intlPct,
            lastCheckDomPct = lc?.domPct,
            lastCheckScore = lc?.scoreTotal,
            lastCheckTime = lc?.checkedAt?.takeIf { it > 0 },
            widgetLastRefreshAgeS = wAge,
            dbIntegrity = integrity,
            dbIntegrityDetail = integrityDetail,
            dbSamples = samples,
            dbPingPoints = pingPoints,
            dbGamingSessions = gamingSessions,
            dbGamingSamplesLast = gamingSamplesLast,
            restoreJournalState = journal,
            lastRestoreDiag = diag,
            lastGamingSession = lastSessionText,
            lastLabRun = lastLab,
            storageFreeMb = freeMb
        )
    }

    // ================= JVM self-tests (pure only) =================

    private fun fakeThrowable(vararg frames: StackTraceElement): Throwable {
        val t = RuntimeException("test")
        t.stackTrace = frames
        return t
    }

    /**
     * Pure JVM tests for the Bridge core: sanitizer, location/signature/
     * module inference, dedup, caps and status separation. Anything that
     * needs a Context, SQLite or org.json on Android is on-device only
     * and is NOT asserted here.
     */
    fun selfTests(): List<TestResult> {
        resetForTests()
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "Bridge — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        t("Bridge — پاک‌سازی IPv4", "gateway <ip> failed", sanitize("gateway 192.168.1.1 failed"), sanitize("gateway 192.168.1.1 failed") == "gateway <ip> failed")
        val ipv6In = "peer fe80:0000:0000:0000:0202:b3ff:fe1e:8329 at 19:30:00"
        val ipv6Out = sanitize(ipv6In)
        t("Bridge — پاک‌سازی IPv6 و حفظ ساعت", "fe80… -> <ip6>, 19:30:00 kept", ipv6Out, ipv6Out.contains("<ip6>") && ipv6Out.contains("19:30:00") && !ipv6Out.contains("fe80"))
        val mailMac = "user ali@example.com mac AA:BB:CC:DD:EE:FF"
        val mailMacOut = sanitize(mailMac)
        t("Bridge — پاک‌سازی ایمیل و MAC", "<email> + <mac>", mailMacOut, mailMacOut.contains("<email>") && mailMacOut.contains("<mac>") && !mailMacOut.contains("ali@") && !mailMacOut.contains("AA:BB"))
        val pathHex = "open /data/data/com.aksell.netwatch/files/x key a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        val pathHexOut = sanitize(pathHex)
        t("Bridge — پاک‌سازی مسیر و توکن hex", "<path> + <token>", pathHexOut, pathHexOut.contains("<path>") && pathHexOut.contains("<token>") && !pathHexOut.contains("/data/") && !pathHexOut.contains("a1b2c3"))
        val cred = "auth Bearer abcDEF123456._-xyz ssid: \"MyWifi\" token=secretvalue99"
        val credOut = sanitize(cred)
        t("Bridge — پاک‌سازی Bearer و توکن و SSID", "Bearer <token>, ssid <ssid>, token=<redacted>", credOut, credOut.contains("Bearer <token>") && credOut.contains("ssid: <ssid>") && credOut.contains("token=<redacted>") && !credOut.contains("MyWifi") && !credOut.contains("secretvalue99"))
        val phones = "call 09123456789 or +989123456789 now"
        val phonesOut = sanitize(phones)
        t("Bridge — پاک‌سازی موبایل ایرانی", "two <phone>", phonesOut, phonesOut == "call <phone> or <phone> now")
        val normal = "versionCode 32 • 200ms • 3,288,530 bytes • خطا در Restore"
        t("Bridge — حفظ متن عادی", "unchanged", sanitize(normal), sanitize(normal) == normal)
        val locT = fakeThrowable(
            StackTraceElement("com.aksell.netwatch.History", "doRestore", "History.kt", 412),
            StackTraceElement("com.aksell.netwatch.History", "restoreFromJson", "History.kt", 900)
        )
        val (lf, ll, lfn) = extractLocation(locT)
        t("Bridge — استخراج مکان از Stack", "History.kt:412 doRestore", "$lf:$ll $lfn", lf == "History.kt" && ll == 412 && lfn == "doRestore")
        val negT = fakeThrowable(StackTraceElement("com.aksell.netwatch.History", "doRestore", "History.kt", -1))
        val (nf, nl, _) = extractLocation(negT)
        t("Bridge — خط منفی یعنی بدون شواهد", "file kept, line null", "$nf:$nl", nf == "History.kt" && nl == null)
        val foreignT = fakeThrowable(StackTraceElement("java.lang.Thread", "run", "Thread.java", 100))
        val (ff, fl, _) = extractLocation(foreignT)
        t("Bridge — بدون فریم اپ یعنی بدون مکان", "nulls", "$ff:$fl", ff == null && fl == null)
        val (im, imSrc) = inferModule(locT)
        t("Bridge — حدس ماژول Restore از Stack", "RESTORE / STACK_TRACE_INFERRED", "$im / $imSrc", im == Module.RESTORE && imSrc == ModuleSource.STACK_TRACE_INFERRED)
        val gameT = fakeThrowable(StackTraceElement("com.aksell.netwatch.GamingActivity", "runPingTest", "GamingActivity.kt", 120))
        val (gm, _) = inferModule(gameT)
        t("Bridge — حدس ماژول Gaming از Stack", "GAMING", "$gm", gm == Module.GAMING)
        val (fm, fmSrc) = inferModule(foreignT)
        t("Bridge — بدون فریم اپ یعنی SYSTEM/UNKNOWN", "SYSTEM / UNKNOWN", "$fm / $fmSrc", fm == Module.SYSTEM && fmSrc == ModuleSource.UNKNOWN)
        val sig1 = signatureOf(locT)
        val sig2 = signatureOf(fakeThrowable(StackTraceElement("com.aksell.netwatch.History", "doRestore", "History.kt", 412), StackTraceElement("com.aksell.netwatch.History", "restoreFromJson", "History.kt", 900)))
        val sig3 = signatureOf(fakeThrowable(StackTraceElement("com.aksell.netwatch.History", "phaseB", "History.kt", 500)))
        t("Bridge — امضای Stack", "same stack -> same sig; other frame -> other sig", "$sig1 == $sig2: ${sig1 == sig2}, != $sig3: ${sig1 != sig3}", sig1 == sig2 && sig1 != sig3)
        t("Bridge — قانون dedup کرش", "same<10min true; >10min false; other sig false", "dup=${isDuplicateCrash(1000, 1000 + 9 * 60 * 1000, true)} old=${isDuplicateCrash(1000, 1000 + 11 * 60 * 1000, true)} other=${isDuplicateCrash(1000, 1000 + 1000, false)}",
            isDuplicateCrash(1000, 1000 + 9 * 60 * 1000, true) && !isDuplicateCrash(1000, 1000 + 11 * 60 * 1000, true) && !isDuplicateCrash(1000, 1000 + 1000, false))
        val warnEv = log(Module.NETWORK, Status.WARNING, Kind.EXCEPTION, "connect failed 192.168.1.1")
        t("Bridge — پاک‌سازی پیش از ذخیره", "stored message has <ip>", warnEv.message, warnEv.message == "connect failed <ip>")
        val dupT = fakeThrowable(StackTraceElement("com.aksell.netwatch.History", "doRestore", "History.kt", 412))
        log(Module.RESTORE, Status.ERROR, Kind.EXCEPTION, "restore failed", dupT)
        log(Module.RESTORE, Status.ERROR, Kind.EXCEPTION, "restore failed", dupT)
        val restoreEvents = events().filter { it.module == Module.RESTORE && it.kind == Kind.EXCEPTION }
        t("Bridge — dedup رویداد تکراری", "one restore event, occurrences 2", "count=${restoreEvents.size} occ=${restoreEvents.firstOrNull()?.occurrences}", restoreEvents.size == 1 && restoreEvents.firstOrNull()?.occurrences == 2)
        resetForTests()
        for (i in 0 until MAX_EVENTS) log(Module.NETWORK, Status.WARNING, Kind.EXCEPTION, "warning $i")
        val errEv = log(Module.RESTORE, Status.ERROR, Kind.EXCEPTION, "definite error kept")
        log(Module.NETWORK, Status.WARNING, Kind.EXCEPTION, "one more warning")
        val ringAfterCap = events()
        t("Bridge — سقف حلقه و حفظ ERROR", "size 200, ERROR still present", "size=${ringAfterCap.size} hasError=${ringAfterCap.any { it.status == Status.ERROR }}", ringAfterCap.size == MAX_EVENTS && ringAfterCap.any { it.status == Status.ERROR } && ringAfterCap.any { it.eventId == errEv.eventId })
        val unkEv = log(Module.NETWORK, Status.UNKNOWN, Kind.EXCEPTION, "probe skipped: no route")
        t("Bridge — تفکیک UNKNOWN از ERROR", "UNKNOWN stays UNKNOWN", "${unkEv.status}", unkEv.status == Status.UNKNOWN)

        // ---- audit fixes (Phase 1.1) ----
        // Fix 5: context values are sanitized before storage and on output.
        resetForTests()
        val ctxMap = mapOf<String, Any>(
            "ip" to "192.168.1.1",
            "mac" to "AA:BB:CC:DD:EE:FF",
            "ssid" to "ssid: \"MyWifi\"",
            "path" to "/data/data/com.aksell.netwatch/files/x",
            "email" to "ali@example.com",
            "phone" to "09123456789",
            "token" to "Bearer abcDEF123456._-xyz",
            "count" to 42,
            "ok" to true,
            "name" to "restore"
        )
        val ctxEv = log(Module.RESTORE, Status.WARNING, Kind.EXCEPTION, "ctx test", null, ctxMap)
        val storedCtx = events().firstOrNull()?.context ?: emptyMap()
        val ctxOk = (storedCtx["ip"] == "<ip>") && (storedCtx["mac"] == "<mac>") &&
            (storedCtx["ssid"] == "ssid: <ssid>") && (storedCtx["path"] == "<path>") &&
            (storedCtx["email"] == "<email>") && (storedCtx["phone"] == "<phone>") &&
            (storedCtx["token"] == "Bearer <token>") &&
            (storedCtx["count"] == 42) && (storedCtx["ok"] == true) && (storedCtx["name"] == "restore")
        t("Bridge — پاک‌سازی context پیش از ذخیره", "ip/mac/ssid/path/email/phone/token replaced; values kept", "stored=$storedCtx", ctxOk)
        val ctxJson = eventToJson(ctxEv).toString()
        t("Bridge — پاک‌سازی context در خروجی JSON", "no raw value in JSON", ctxJson.take(120), !ctxJson.contains("192.168.1.1") && !ctxJson.contains("AA:BB") && !ctxJson.contains("MyWifi") && !ctxJson.contains("ali@example.com") && !ctxJson.contains("09123456789") && !ctxJson.contains("abcDEF123456"))

        // Fix 2: cause chain — 3 levels max, cycle-safe, sanitized.
        val d = RuntimeException("d-level")
        val c = RuntimeException("c-level", d)
        val b = RuntimeException("b-level", c)
        val a = RuntimeException("a-level", b)
        d.initCause(b) // cycle: d -> b -> c -> d ...
        val chain = causeChain(a)
        t("Bridge — زنجیره Cause تا ۳ سطح و ضدحلقه", "3 causes, no loop, frames kept", "size=${chain.size} frames0=${chain.firstOrNull()?.frames?.size}", chain.size == 3 && chain[0].frames.isNotEmpty())
        val causeC = RuntimeException("deep 10.0.0.1")
        val causeB = RuntimeException("mid", causeC)
        val causeA = RuntimeException("top", causeB)
        val chain2 = causeChain(causeA)
        t("Bridge — پاک‌سازی پیام Cause", "deep <ip>", "msgs=${chain2.map { it.message }}", chain2.size == 2 && chain2[1].message == "deep <ip>")
        val x = RuntimeException("x")
        val y = RuntimeException("y", x)
        x.initCause(y) // x <-> y cycle
        val chainX = causeChain(x)
        t("Bridge — حلقهٔ دوتایی Cause", "terminates with 1 cause", "size=${chainX.size}", chainX.size == 1)

        // Fix 1: breadcrumb snapshot — last 10 of 15, in order.
        resetForTests()
        for (i in 1..15) breadcrumb("STEP", "step $i")
        val snap = snapshotBreadcrumbs()
        t("Bridge — Snapshot آخرین ۱۰ Breadcrumb", "10 items, step 6..15 in order", "size=${snap.size} first=${snap.firstOrNull()} last=${snap.lastOrNull()}", snap.size == 10 && snap.first().contains("step 6") && snap.last().contains("step 15"))

        // Fix 1+2: crash file contains breadcrumbs and caused_by (file level).
        val crashDir = File(System.getProperty("java.io.tmpdir"), "bridge-crash-test-${System.nanoTime()}")
        crashDir.mkdirs()
        setTestDirForTests(crashDir)
        resetForTests()
        breadcrumb("RESTORE_START", "restore started")
        breadcrumb("CHECK_DONE", "check 7/8")
        val crashT = RuntimeException("boom 192.168.1.1")
        val crashCause = RuntimeException("inner 10.0.0.2")
        crashT.initCause(crashCause)
        crashT.stackTrace = arrayOf(StackTraceElement("com.aksell.netwatch.History", "doRestore", "History.kt", 412))
        recordCrash(Thread.currentThread(), crashT)
        val crashFiles = crashDir.resolve(CRASHES_DIR).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        var crashOk = crashFiles.size == 1
        var crashDetail = "files=${crashFiles.size}"
        if (crashFiles.size == 1) {
            val jo = JSONObject(crashFiles[0].readText())
            val crumbsArr = jo.optJSONArray("breadcrumbs")
            val causesArr = jo.optJSONArray("caused_by")
            crashOk = crashOk && jo.optString("status") == "ERROR" && jo.optString("kind") == "CRASH" &&
                jo.optString("module") == "RESTORE" && jo.optString("file") == "History.kt" && jo.optInt("line", -1) == 412 &&
                crumbsArr != null && crumbsArr.length() == 2 &&
                causesArr != null && causesArr.length() == 1 &&
                causesArr.optJSONObject(0)?.optString("message") == "inner <ip>" &&
                jo.optString("message") == "boom <ip>"
            crashDetail = "status=${jo.optString("status")} crumbs=${crumbsArr?.length()} causes=${causesArr?.length()} msg=${jo.optString("message")}"
        }
        t("Bridge — فایل Crash: Breadcrumb و Cause", "1 file, 2 crumbs, 1 cause, sanitized", crashDetail, crashOk)
        // Fix 1 (dedup at file level): same crash again within 10 min -> no new file.
        recordCrash(Thread.currentThread(), crashT)
        val crashFiles2 = crashDir.resolve(CRASHES_DIR).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        t("Bridge — dedup فایل Crash", "still 1 file", "files=${crashFiles2.size}", crashFiles2.size == 1)
        setTestDirForTests(null)
        crashDir.deleteRecursively()

        // Fix 4: temp cleanup — .tmp deleted, valid files kept.
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "bridge-tmp-test-${System.nanoTime()}")
        val tmpCrashes = File(tmpDir, CRASHES_DIR)
        tmpCrashes.mkdirs()
        File(tmpDir, "events.jsonl.tmp").writeText("partial")
        File(tmpDir, "events.jsonl").writeText("{\"ts\":1}\n")
        File(tmpCrashes, "crash-1.tmp").writeText("partial")
        File(tmpCrashes, "crash-1.json").writeText("{}")
        cleanTempFiles(tmpDir)
        val tmpOk = !File(tmpDir, "events.jsonl.tmp").exists() && !File(tmpCrashes, "crash-1.tmp").exists() &&
            File(tmpDir, "events.jsonl").exists() && File(tmpCrashes, "crash-1.json").exists()
        t("Bridge — پاک‌سازی فایل‌های موقت", ".tmp gone, valid files kept", "tmpGone=${!File(tmpDir, "events.jsonl.tmp").exists()} validKept=${File(tmpCrashes, "crash-1.json").exists()}", tmpOk)
        tmpDir.deleteRecursively()

        // Fix 3: concurrent in-memory logging — no lost or corrupt events.
        resetForTests()
        val memThreads = (1..8).map { ti ->
            Thread {
                for (i in 1..25) log(Module.NETWORK, Status.WARNING, Kind.EXCEPTION, "mem t$ti-$i")
            }
        }
        memThreads.forEach { it.start() }
        memThreads.forEach { it.join() }
        val memEvents = events()
        val memIds = memEvents.map { it.eventId }.toSet()
        t("Bridge — لاگ هم‌زمان در حافظه", "200 events, 200 distinct ids", "size=${memEvents.size} distinct=${memIds.size}", memEvents.size == 200 && memIds.size == 200)

        // Fix 3: concurrent file writes — no silently lost event.
        val fileDir = File(System.getProperty("java.io.tmpdir"), "bridge-file-test-${System.nanoTime()}")
        fileDir.mkdirs()
        setTestDirForTests(fileDir)
        resetForTests()
        val fileThreads = (1..8).map { ti ->
            Thread {
                for (i in 1..20) log(Module.NETWORK, Status.ERROR, Kind.EXCEPTION, "file t$ti-$i")
            }
        }
        fileThreads.forEach { it.start() }
        fileThreads.forEach { it.join() }
        val lines = File(fileDir, EVENTS_NAME).readLines().filter { it.isNotBlank() }
        val lineIds = lines.mapNotNull { line ->
            try { JSONObject(line).optString("event_id", "") } catch (_: Exception) { null }
        }.toSet()
        val memIds2 = events().map { it.eventId }.toSet()
        val fileOk = lines.size == 160 && lineIds.size == 160 && lineIds == memIds2
        t("Bridge — نوشتن هم‌زمان فایل", "160 lines, 160 distinct ids, == memory", "lines=${lines.size} distinct=${lineIds.size} matchMem=${lineIds == memIds2}", fileOk)
        setTestDirForTests(null)
        fileDir.deleteRecursively()
        resetForTests()

        return out
    }
}
