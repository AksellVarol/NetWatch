package com.aksell.netwatch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

// v2.9.4 — Restore Security (P0-2): bounded reading + streaming JSON parser.
// The old restore read the whole backup into a String and parsed it with
// JSONObject (2-4x the file size in transient heap). For a real ~29 MB backup
// that peaked at an estimated 200-300 MB — unsafe on a 256 MB heap. This file
// streams the backup through android.util.JsonReader straight into the staging
// DB in 5,000-row batches, so heap stays flat regardless of file size.
//
// Strictness is DELIBERATELY identical to the old validateRestoreJson
// (53 checks, zero getters): missing field = default, present field with the
// wrong type = reject with the field name. Two documented tightenings:
// - duplicate field names inside one object are REJECTED (JSONObject kept
//   the last one silently);
// - integer fields must be written as plain integer literals ("1" ok,
//   "1.0" / "1e3" / "01" / "+1" / "\"1\"" rejected) because JsonToken.NUMBER
//   alone does not prove the literal is an integer.
object RestoreStream {

    class SizeLimitExceeded : IOException("حجم فایل بیشتر از حد مجاز است")
    class ValidationFailed(field: String) : Exception("فیلد $field نوع معتبر ندارد")
    class CorruptBackup(msg: String) : Exception(msg)

    data class RestoreCounts(
        val samples: Int, val pingPoints: Int, val lossPoints: Int,
        val gamingSessions: Int, val gamingSamples: Int
    )

    // Prefs values carried by the backup (null = field absent -> keep current value,
    // EXCEPT inside a present "settings"/"last_check" object, where the old code
    // wrote the documented defaults for missing fields).
    data class RestorePayload(
        val log: String?, val timeline: String?, val hourly: String?, val blackbox: String?,
        val gamingLog: String?, val gamingMatches: String?, val spikes: String?,
        val intervalSec: Int?, val alertThreshold: Int?, val livePing: Boolean?,
        val customTarget: String?, val dnsRounds: Int?, val alertsOn: Boolean?,
        val lastCheck: String?, // pipe-joined, same format as History.saveLastCheck
        val counts: RestoreCounts
    )

    enum class RecoveryAction { NOTHING, CLEAN_STAGING, ROLLBACK, ROLL_FORWARD }

    // ---- Test-only hooks (null/false in production; set ONLY by Lab tests,
    // cleared in finally — same pattern as the v2.9.1 gate hooks) ----
    /** Park the parse after this many inserted rows (kill-test arming). */
    @Volatile var testStopAfterRows: Int? = null
    /** Set to true while a parse is parked by testStopAfterRows. */
    val testParked = AtomicBoolean(false)

    // ---- Pure helpers (JVM-testable) ----

    private val INT_LITERAL = Regex("^-?(0|[1-9][0-9]*)$")

    /** "1" -> true; "1.0", "1e3", "01", "+1", "\"1\"", "" -> false. */
    fun isIntegerLiteral(lit: String): Boolean = INT_LITERAL.matches(lit)

    /** True only when the size is KNOWN and over the limit (null = unknown -> rely on the during-read bound). */
    fun exceedsCap(knownSize: Long?, limitBytes: Long): Boolean =
        knownSize != null && knownSize > limitBytes

    /** Reads the whole stream but aborts as soon as the cap is exceeded. Never holds more than the cap. */
    @Throws(IOException::class)
    fun readBounded(input: InputStream, limitBytes: Long): ByteArray {
        require(limitBytes >= 0) { "limit" }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limitBytes) throw SizeLimitExceeded()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Pure encoding of the crash table (journal state + staging presence).
     * live/old existence does not change the ACTION — it only changes which
     * file steps the ROLLBACK/ROLL_FORWARD implementation takes.
     */
    fun decideRecovery(journalState: String?, stagingExists: Boolean): RecoveryAction {
        return when (journalState) {
            "STARTED" -> RecoveryAction.ROLLBACK
            "COMMITTED" -> RecoveryAction.ROLL_FORWARD
            else -> if (stagingExists) RecoveryAction.CLEAN_STAGING else RecoveryAction.NOTHING
        }
    }

    // ---- Streaming parser ----

    /** InputStream that throws SizeLimitExceeded as soon as more than limitBytes are read. */
    class BoundedInputStream(input: InputStream, private val limitBytes: Long) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                total++
                if (total > limitBytes) throw SizeLimitExceeded()
            }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > limitBytes) throw SizeLimitExceeded()
            }
            return n
        }
    }

    /**
     * Streams a backup into the staging DB (batched transactions, 5,000 rows)
     * and verifies it (PRAGMA integrity_check + per-table row counts).
     * Returns the prefs payload for Phase B. Throws SizeLimitExceeded,
     * ValidationFailed or CorruptBackup; the staging DB is disposable on
     * failure (the caller deletes it). onProgress(rows) is called every
     * 1,000 inserted rows (RAM sampling by the caller).
     */
    @Throws(IOException::class)
    fun streamIntoStaging(
        input: InputStream,
        limitBytes: Long,
        stagingDb: SQLiteDatabase,
        onProgress: ((Int) -> Unit)?
    ): RestorePayload {
        val reader = JsonReader(InputStreamReader(BoundedInputStream(input, limitBytes), Charsets.UTF_8))
        reader.isLenient = false
        val batcher = Batcher()
        val counts = MutableCounts()
        var version: Int? = null
        var samplesSeen = false
        var pingSeen = false
        var log: String? = null; var timeline: String? = null; var hourly: String? = null
        var blackbox: String? = null; var gamingLog: String? = null
        var gamingMatches: String? = null; var spikes: String? = null
        var intervalSec: Int? = null; var alertThreshold: Int? = null; var livePing: Boolean? = null
        var customTarget: String? = null; var dnsRounds: Int? = null; var alertsOn: Boolean? = null
        var lastCheck: String? = null
        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw CorruptBackup("فایل بکاپ خراب است: ساختار JSON معتبر نیست")
            reader.beginObject()
            val seenTop = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!seenTop.add(name)) throw ValidationFailed("$name (تکراری)")
                when (name) {
                    "version" -> version = readInt(reader, "version")
                    "exported_at" -> reader.skipValue() // informational only; any type is fine
                    "samples" -> { samplesSeen = true; parseSamples(reader, stagingDb, batcher, counts, onProgress) }
                    "ping_points" -> { pingSeen = true; parsePingPoints(reader, stagingDb, batcher, counts, onProgress) }
                    "loss_points" -> parseLossPoints(reader, stagingDb, batcher, counts, onProgress)
                    "log" -> log = readString(reader, "log")
                    "timeline" -> timeline = readString(reader, "timeline")
                    "hourly" -> hourly = readString(reader, "hourly")
                    "blackbox" -> blackbox = readString(reader, "blackbox")
                    "gaming_log" -> gamingLog = readString(reader, "gaming_log")
                    "gaming_matches" -> gamingMatches = readString(reader, "gaming_matches")
                    "spikes" -> spikes = readString(reader, "spikes")
                    "gaming_sessions" -> parseGamingSessions(reader, stagingDb, batcher, counts, onProgress)
                    "settings" -> {
                        val s = parseSettings(reader)
                        intervalSec = s.intervalSec; alertThreshold = s.alertThreshold
                        livePing = s.livePing; customTarget = s.customTarget
                        dnsRounds = s.dnsRounds; alertsOn = s.alertsOn
                    }
                    "last_check" -> lastCheck = parseLastCheck(reader)
                    else -> reader.skipValue() // unknown top-level field: same as before (forward-compatible)
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw CorruptBackup("فایل بکاپ خراب است: داده اضافی بعد از پایان فایل")
            // Pre-checks (same messages as the old restoreFromJson):
            val v = version ?: throw CorruptBackup("فایل بکاپ خراب است: نسخه فایل مشخص نیست")
            if (v != 1) throw CorruptBackup("نسخه فایل بکاپ پشتیبانی نمی‌شود: $v")
            if (!samplesSeen || !pingSeen) throw CorruptBackup("فایل بکاپ ناقص است: بخش نمونه‌ها وجود ندارد")
            batcher.finish(stagingDb)
            verifyStaging(stagingDb, counts)
        } catch (e: SizeLimitExceeded) {
            batcher.abort(stagingDb)
            throw e
        } catch (e: ValidationFailed) {
            batcher.abort(stagingDb)
            throw e
        } catch (e: CorruptBackup) {
            batcher.abort(stagingDb)
            throw e
        } catch (e: IOException) {
            batcher.abort(stagingDb)
            throw CorruptBackup("فایل بکاپ خراب است: ${e.message}")
        } finally {
            try { reader.close() } catch (_: Exception) { }
        }
        return RestorePayload(
            log, timeline, hourly, blackbox, gamingLog, gamingMatches, spikes,
            intervalSec, alertThreshold, livePing, customTarget, dnsRounds, alertsOn,
            lastCheck, RestoreCounts(counts.samples, counts.pingPoints, counts.lossPoints, counts.gamingSessions, counts.gamingSamples)
        )
    }

    private class MutableCounts {
        var samples = 0; var pingPoints = 0; var lossPoints = 0
        var gamingSessions = 0; var gamingSamples = 0
        fun total(): Int = samples + pingPoints + lossPoints + gamingSessions + gamingSamples
    }

    /** Batched transactions over the staging DB (5,000 rows per commit). */
    private class Batcher {
        private var inTx = false
        private var rows = 0
        fun insert(db: SQLiteDatabase, table: String, cv: ContentValues) {
            if (!inTx) { db.beginTransaction(); inTx = true }
            db.insert(table, null, cv)
            rows++
            if (rows >= 5000) {
                db.setTransactionSuccessful(); db.endTransaction()
                inTx = false; rows = 0
            }
        }
        fun finish(db: SQLiteDatabase) {
            if (inTx) { db.setTransactionSuccessful(); db.endTransaction(); inTx = false; rows = 0 }
        }
        fun abort(db: SQLiteDatabase) {
            if (inTx) { try { db.endTransaction() } catch (_: Exception) { }; inTx = false; rows = 0 }
        }
    }

    private fun verifyStaging(db: SQLiteDatabase, counts: MutableCounts) {
        db.rawQuery("PRAGMA integrity_check", null).use { c ->
            if (!c.moveToFirst() || c.getString(0) != "ok") throw CorruptBackup("پایگاه موقت سالم نیست")
        }
        val actual = mapOf(
            "samples" to counts.samples, "ping_points" to counts.pingPoints,
            "loss_points" to counts.lossPoints, "gaming_sessions" to counts.gamingSessions,
            "gaming_samples" to counts.gamingSamples
        )
        for ((table, expected) in actual) {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                c.moveToFirst()
                if (c.getLong(0) != expected.toLong()) throw CorruptBackup("پایگاه موقت ناقص است: $table")
            }
        }
    }

    /** Per-row tick: park hook + progress callback (every 1,000 rows). */
    private fun tick(counts: MutableCounts, onProgress: ((Int) -> Unit)?) {
        val t = counts.total()
        maybePark(t)
        if (onProgress != null && t % 1000 == 0) onProgress(t)
    }

    /** Test hook: parks the parse (sets testParked) until testStopAfterRows is cleared. */
    private fun maybePark(totalRows: Int) {
        val stop = testStopAfterRows ?: return
        if (totalRows < stop) return
        testParked.set(true)
        while (testStopAfterRows != null) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        testParked.set(false)
    }

    // ---- Typed readers (peek BEFORE reading; JsonReader.nextInt() is lenient and must not be used) ----

    private fun readInt(r: JsonReader, field: String): Int {
        if (r.peek() != JsonToken.NUMBER) throw ValidationFailed(field)
        val lit = r.nextString()
        if (!isIntegerLiteral(lit)) throw ValidationFailed(field)
        val v = lit.toLongOrNull() ?: throw ValidationFailed(field)
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ValidationFailed(field)
        return v.toInt()
    }

    private fun readLong(r: JsonReader, field: String): Long {
        if (r.peek() != JsonToken.NUMBER) throw ValidationFailed(field)
        val lit = r.nextString()
        if (!isIntegerLiteral(lit)) throw ValidationFailed(field)
        return lit.toLongOrNull() ?: throw ValidationFailed(field)
    }

    private fun readDouble(r: JsonReader, field: String): Double {
        if (r.peek() != JsonToken.NUMBER) throw ValidationFailed(field)
        val lit = r.nextString()
        val v = lit.toDoubleOrNull() ?: throw ValidationFailed(field)
        if (!v.isFinite()) throw ValidationFailed(field)
        return v
    }

    private fun readString(r: JsonReader, field: String): String {
        if (r.peek() != JsonToken.STRING) throw ValidationFailed(field)
        return r.nextString()
    }

    private fun readBool(r: JsonReader, field: String): Boolean {
        if (r.peek() != JsonToken.BOOLEAN) throw ValidationFailed(field)
        return r.nextBoolean()
    }

    private fun readIntOrNull(r: JsonReader, field: String): Int? {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null }
        return readInt(r, field)
    }

    // ---- Row parsers (required fields reject with the field name; optional fields keep the old defaults) ----

    private fun parseSamples(r: JsonReader, db: SQLiteDatabase, b: Batcher, counts: MutableCounts, onProgress: ((Int) -> Unit)?) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw ValidationFailed("samples")
        r.beginArray()
        while (r.hasNext()) {
            r.beginObject()
            var time: Long? = null; var ok: Int? = null; var total: Int? = null
            var avg = -1.0; var jit = -1.0; var loss = -1.0
            var src: String? = null
            val seen = HashSet<String>()
            while (r.hasNext()) {
                val name = r.nextName()
                if (!seen.add(name)) throw ValidationFailed("samples.$name (تکراری)")
                when (name) {
                    "time" -> time = readLong(r, "samples.time")
                    "ok" -> ok = readInt(r, "samples.ok")
                    "total" -> total = readInt(r, "samples.total")
                    "avg_ms" -> avg = readDouble(r, "samples.avg_ms")
                    "jitter_ms" -> jit = readDouble(r, "samples.jitter_ms")
                    "loss_pct" -> loss = readDouble(r, "samples.loss_pct")
                    "source" -> src = readString(r, "samples.source")
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val t = time ?: throw ValidationFailed("samples.time (وجود ندارد)")
            val o = ok ?: throw ValidationFailed("samples.ok (وجود ندارد)")
            val tot = total ?: throw ValidationFailed("samples.total (وجود ندارد)")
            val cv = ContentValues().apply {
                put("time", t); put("ok", o); put("total", tot)
                put("avg_ms", avg); put("jitter_ms", jit); put("loss_pct", loss)
                put("source", src ?: "restore")
            }
            b.insert(db, "samples", cv)
            counts.samples++
            tick(counts, onProgress)
        }
        r.endArray()
    }

    private fun parsePingPoints(r: JsonReader, db: SQLiteDatabase, b: Batcher, counts: MutableCounts, onProgress: ((Int) -> Unit)?) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw ValidationFailed("ping_points")
        r.beginArray()
        while (r.hasNext()) {
            r.beginObject()
            var time: Long? = null; var ms: Double? = null
            val seen = HashSet<String>()
            while (r.hasNext()) {
                val name = r.nextName()
                if (!seen.add(name)) throw ValidationFailed("ping_points.$name (تکراری)")
                when (name) {
                    "time" -> time = readLong(r, "ping_points.time")
                    "ms" -> ms = readDouble(r, "ping_points.ms")
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val t = time ?: throw ValidationFailed("ping_points.time (وجود ندارد)")
            val m = ms ?: throw ValidationFailed("ping_points.ms (وجود ندارد)")
            val cv = ContentValues().apply {
                put("time", t); put("ms", m); put("ok", if (m >= 0) 1 else 0)
            }
            b.insert(db, "ping_points", cv)
            counts.pingPoints++
            tick(counts, onProgress)
        }
        r.endArray()
    }

    private fun parseLossPoints(r: JsonReader, db: SQLiteDatabase, b: Batcher, counts: MutableCounts, onProgress: ((Int) -> Unit)?) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw ValidationFailed("loss_points")
        r.beginArray()
        while (r.hasNext()) {
            r.beginObject()
            var time: Long? = null; var loss: Double? = null
            val seen = HashSet<String>()
            while (r.hasNext()) {
                val name = r.nextName()
                if (!seen.add(name)) throw ValidationFailed("loss_points.$name (تکراری)")
                when (name) {
                    "time" -> time = readLong(r, "loss_points.time")
                    "loss" -> loss = readDouble(r, "loss_points.loss")
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val t = time ?: throw ValidationFailed("loss_points.time (وجود ندارد)")
            val l = loss ?: throw ValidationFailed("loss_points.loss (وجود ندارد)")
            val cv = ContentValues().apply { put("time", t); put("loss", l) }
            b.insert(db, "loss_points", cv)
            counts.lossPoints++
            tick(counts, onProgress)
        }
        r.endArray()
    }

    private fun parseGamingSessions(r: JsonReader, db: SQLiteDatabase, b: Batcher, counts: MutableCounts, onProgress: ((Int) -> Unit)?) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw ValidationFailed("gaming_sessions")
        r.beginArray()
        while (r.hasNext()) {
            r.beginObject()
            var started: Long? = null; var ended: Long? = null
            var profile = ""; var region = ""; var host = ""
            var sent: Int? = null; var received: Int? = null
            var loss: Double? = null; var avg: Double? = null; var min: Double? = null
            var max: Double? = null; var jit: Double? = null; var p95: Double? = null
            var spikes: Int? = null; var burst: Int? = null
            var score: Int? = null; var scoreSeen = false
            var bbGrade = ""
            val nested = ArrayList<Pair<Long, Double>>()
            val seen = HashSet<String>()
            while (r.hasNext()) {
                val name = r.nextName()
                if (!seen.add(name)) throw ValidationFailed("gaming_sessions.$name (تکراری)")
                when (name) {
                    "started" -> started = readLong(r, "gaming_sessions.started")
                    "ended" -> ended = readLong(r, "gaming_sessions.ended")
                    "profile" -> profile = readString(r, "gaming_sessions.profile")
                    "region" -> region = readString(r, "gaming_sessions.region")
                    "host" -> host = readString(r, "gaming_sessions.host")
                    "sent" -> sent = readInt(r, "gaming_sessions.sent")
                    "received" -> received = readInt(r, "gaming_sessions.received")
                    "loss" -> loss = readDouble(r, "gaming_sessions.loss")
                    "avg_ms" -> avg = readDouble(r, "gaming_sessions.avg_ms")
                    "min_ms" -> min = readDouble(r, "gaming_sessions.min_ms")
                    "max_ms" -> max = readDouble(r, "gaming_sessions.max_ms")
                    "jitter_ms" -> jit = readDouble(r, "gaming_sessions.jitter_ms")
                    "p95" -> p95 = readDouble(r, "gaming_sessions.p95")
                    "spikes" -> spikes = readInt(r, "gaming_sessions.spikes")
                    "burst" -> burst = readInt(r, "gaming_sessions.burst")
                    "score" -> { score = readIntOrNull(r, "gaming_sessions.score"); scoreSeen = true }
                    "bb_grade" -> bbGrade = readString(r, "gaming_sessions.bb_grade")
                    "samples" -> {
                        if (r.peek() != JsonToken.BEGIN_ARRAY) throw ValidationFailed("gaming_sessions.samples")
                        r.beginArray()
                        while (r.hasNext()) {
                            r.beginObject()
                            var st: Long? = null; var sm: Double? = null
                            val sSeen = HashSet<String>()
                            while (r.hasNext()) {
                                val sn = r.nextName()
                                if (!sSeen.add(sn)) throw ValidationFailed("gaming_sessions.samples.$sn (تکراری)")
                                when (sn) {
                                    "time" -> st = readLong(r, "gaming_sessions.samples.time")
                                    "ms" -> sm = readDouble(r, "gaming_sessions.samples.ms")
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            val stt = st ?: throw ValidationFailed("gaming_sessions.samples.time (وجود ندارد)")
                            val smm = sm ?: throw ValidationFailed("gaming_sessions.samples.ms (وجود ندارد)")
                            nested.add(Pair(stt, smm))
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val st = started ?: throw ValidationFailed("gaming_sessions.started (وجود ندارد)")
            val en = ended ?: throw ValidationFailed("gaming_sessions.ended (وجود ندارد)")
            val se = sent ?: throw ValidationFailed("gaming_sessions.sent (وجود ندارد)")
            val re = received ?: throw ValidationFailed("gaming_sessions.received (وجود ندارد)")
            val lo = loss ?: throw ValidationFailed("gaming_sessions.loss (وجود ندارد)")
            val av = avg ?: throw ValidationFailed("gaming_sessions.avg_ms (وجود ندارد)")
            val mi = min ?: throw ValidationFailed("gaming_sessions.min_ms (وجود ندارد)")
            val ma = max ?: throw ValidationFailed("gaming_sessions.max_ms (وجود ندارد)")
            val ji = jit ?: throw ValidationFailed("gaming_sessions.jitter_ms (وجود ندارد)")
            val p9 = p95 ?: throw ValidationFailed("gaming_sessions.p95 (وجود ندارد)")
            val sp = spikes ?: throw ValidationFailed("gaming_sessions.spikes (وجود ندارد)")
            val bu = burst ?: throw ValidationFailed("gaming_sessions.burst (وجود ندارد)")
            val sc = if (scoreSeen) score else null
            val cv = ContentValues().apply {
                put("started", st); put("ended", en); put("profile", profile); put("region", region)
                put("host", host); put("sent", se); put("received", re); put("loss", lo)
                put("avg_ms", av); put("min_ms", mi); put("max_ms", ma); put("jitter_ms", ji)
                put("p95", p9); put("spikes", sp); put("burst", bu)
                if (sc == null) putNull("score") else put("score", sc)
                put("bb_grade", bbGrade)
            }
            // NOTE: the session row is inserted directly (not through the Batcher)
            // because the returned row id is needed for its samples. It lands in
            // the Batcher's open transaction when one is active, or auto-commits
            // otherwise; staging is disposable on failure either way.
            val sessionId = db.insert("gaming_sessions", null, cv)
            counts.gamingSessions++
            tick(counts, onProgress)
            for ((stt, smm) in nested) {
                val scv = ContentValues().apply {
                    put("session_id", sessionId); put("time", stt); put("ms", smm)
                    put("ok", if (smm >= 0) 1 else 0)
                }
                b.insert(db, "gaming_samples", scv)
                counts.gamingSamples++
                tick(counts, onProgress)
            }
        }
        r.endArray()
    }

    private data class SettingsParsed(
        val intervalSec: Int, val alertThreshold: Int, val livePing: Boolean,
        val customTarget: String, val dnsRounds: Int, val alertsOn: Boolean
    )

    private fun parseSettings(r: JsonReader): SettingsParsed {
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw ValidationFailed("settings")
        var intervalSec = 30; var alertThreshold = 40; var livePing = false
        var customTarget = ""; var dnsRounds = 20; var alertsOn = true
        r.beginObject()
        val seen = HashSet<String>()
        while (r.hasNext()) {
            val name = r.nextName()
            if (!seen.add(name)) throw ValidationFailed("settings.$name (تکراری)")
            when (name) {
                "interval_sec" -> intervalSec = readInt(r, "settings.interval_sec")
                "alert_threshold" -> alertThreshold = readInt(r, "settings.alert_threshold")
                "live_ping" -> livePing = readBool(r, "settings.live_ping")
                "custom_target" -> customTarget = readString(r, "settings.custom_target")
                "dns_rounds" -> dnsRounds = readInt(r, "settings.dns_rounds")
                "alerts_on" -> alertsOn = readBool(r, "settings.alerts_on")
                else -> r.skipValue()
            }
        }
        r.endObject()
        return SettingsParsed(intervalSec, alertThreshold, livePing, customTarget, dnsRounds, alertsOn)
    }

    private fun parseLastCheck(r: JsonReader): String {
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw ValidationFailed("last_check")
        var ping = -1.0; var jit = -1.0; var loss = -1.0
        var intl = 0; var dom = 0
        var score: Int? = null; var scoreSeen = false
        var status = ""; var checkedAt = 0L
        r.beginObject()
        val seen = HashSet<String>()
        while (r.hasNext()) {
            val name = r.nextName()
            if (!seen.add(name)) throw ValidationFailed("last_check.$name (تکراری)")
            when (name) {
                "ping_avg" -> ping = readDouble(r, "last_check.ping_avg")
                "jitter_ms" -> jit = readDouble(r, "last_check.jitter_ms")
                "loss_pct" -> loss = readDouble(r, "last_check.loss_pct")
                "intl_pct" -> intl = readInt(r, "last_check.intl_pct")
                "dom_pct" -> dom = readInt(r, "last_check.dom_pct")
                "score_total" -> { score = readIntOrNull(r, "last_check.score_total"); scoreSeen = true }
                "score_status" -> status = readString(r, "last_check.score_status")
                "checked_at" -> checkedAt = readLong(r, "last_check.checked_at")
                else -> r.skipValue()
            }
        }
        r.endObject()
        // Same pipe format as History.saveLastCheck.
        val sc = if (scoreSeen) score else null
        return listOf(
            ping.toString(), jit.toString(), loss.toString(),
            intl.toString(), dom.toString(),
            if (sc == null) "" else sc.toString(),
            status, checkedAt.toString()
        ).joinToString("|")
    }
}
