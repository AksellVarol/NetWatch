package com.aksell.netwatch

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// History (v2.6): structured samples live in SQLite (HistoryDb); settings,
// logs and the timeline stay in SharedPreferences (append-only logs).
// Old SharedPreferences data (samples string + ping history) is migrated once
// into the database — nothing the user collected is lost.
// All DB writes are synchronized on one lock, so the service and the UI can
// never lose each other's samples.
object History {
    private const val PREF = "netwatch"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_LOG = "log"
    private const val KEY_OUTAGE_START = "outage_start"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_CUSTOM_TARGET = "custom_target"
    private const val KEY_PING_HIST = "ping_hist"
    private const val KEY_MIGRATED = "db_migrated_v1"
    private val dbLock = Any()

    // ---- Restore Security (v2.9.4, stage 2 P0-1 + P0-2) ----
    // Central gate: EVERY real read/write of the live DB or of the
    // restore-affected prefs goes through withGate (restoreLock). The flag
    // `recovered` only skips re-reading the journal file — it NEVER skips
    // the lock. Lock order (never reversed):
    // restoreLock -> dbLock -> HistoryDb companion lock -> SQLiteOpenHelper.
    @PublishedApi internal val restoreLock = Any()
    @Volatile @PublishedApi internal var recovered = false
    private val restoreRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    const val RESTORE_MAX_BYTES: Long = 50L * 1024 * 1024

    /** The single gate for all live-DB / prefs access. Inline so method
     *  bodies wrapped in it keep their bare `return` statements. */
    inline fun <T> withGate(ctx: Context, block: () -> T): T {
        return synchronized(restoreLock) {
            if (!recovered) { recoverIfNeeded(ctx); recovered = true }
            block()
        }
    }

    /** Run central recovery now (component entry points call this first). */
    fun ensureRecovered(ctx: Context) {
        synchronized(restoreLock) {
            if (!recovered) { recoverIfNeeded(ctx); recovered = true }
        }
    }

    /** v2.9.8 — diagnostic from the last restore's sidecar reconcile
     *  (which file survived the close, journal_mode, integrity, what was
     *  done). Set by phaseB; read by LAB tests so the cause is visible in
     *  the result text instead of a bare "sidecar remained". Cleared at
     *  the start of every doRestore. */
    @Volatile var lastRestoreDiag: String = ""

    /** v2.9.8 — fresh recovery for the restore path: ALWAYS re-reads the
     *  real journal prefs + file state, never trusting the once-per-process
     *  `recovered` flag. (v2.9.7 bug: doRestore called ensureRecovered,
     *  which is a no-op while restoreRunning is true — so a stale STARTED
     *  journal from an earlier aborted restore was never re-examined
     *  during any later restore in the same process.) */
    fun recoverFresh(ctx: Context) {
        synchronized(restoreLock) {
            recoverIfNeededLocked(ctx)
            recovered = true
        }
    }

    // ---- Accessors for components that used to read prefs directly ----
    // (MainActivity dns_rounds / alerts_on, MonitorService last_pct,
    // Advanced last_ip, Quality last_fingerprint). All go through the gate.
    fun dnsRounds(ctx: Context): Int = withGate(ctx) { prefs(ctx).getInt("dns_rounds", 20) }
    fun setDnsRounds(ctx: Context, n: Int) { withGate(ctx) {  prefs(ctx).edit().putInt("dns_rounds", n).apply()  }}
    fun alertsOn(ctx: Context): Boolean = withGate(ctx) { prefs(ctx).getBoolean("alerts_on", true) }
    fun setAlertsOn(ctx: Context, on: Boolean) { withGate(ctx) {  prefs(ctx).edit().putBoolean("alerts_on", on).apply()  }}
    fun lastPct(ctx: Context): Int = withGate(ctx) { prefs(ctx).getInt("last_pct", 100) }
    fun setLastPct(ctx: Context, pct: Int) { withGate(ctx) {  prefs(ctx).edit().putInt("last_pct", pct).apply()  }}
    fun lastIp(ctx: Context): String = withGate(ctx) { prefs(ctx).getString("last_ip", "") ?: "" }
    fun setLastIp(ctx: Context, ip: String) { withGate(ctx) {  prefs(ctx).edit().putString("last_ip", ip).apply()  }}
    fun lastFingerprint(ctx: Context): String = withGate(ctx) { prefs(ctx).getString("last_fingerprint", "") ?: "" }
    fun setLastFingerprint(ctx: Context, fp: String) { withGate(ctx) {  prefs(ctx).edit().putString("last_fingerprint", fp).apply()  }}

    // ---- Recovery journal (P0-1) ----
    // A dedicated prefs file; state is STARTED or COMMITTED. STARTED ALWAYS
    // means rollback (even if every mutation already happened); COMMITTED
    // means roll forward (cleanup only). The journal holds a snapshot of
    // every prefs key the restore writes, so recovery can restore the exact
    // old values — never a mixed state.
    private const val JOURNAL_PREF = "restore_journal"
    private data class SnapSpec(val key: String, val kind: Int) // 0=String, 1=Int, 2=Boolean
    private val MAIN_SNAP = listOf(
        SnapSpec(KEY_LOG, 0), SnapSpec("timeline", 0), SnapSpec("hourly", 0), SnapSpec("blackbox", 0),
        SnapSpec(KEY_INTERVAL, 1), SnapSpec("alert_threshold", 1), SnapSpec("live_ping", 2),
        SnapSpec(KEY_CUSTOM_TARGET, 0), SnapSpec("dns_rounds", 1), SnapSpec("alerts_on", 2),
        SnapSpec("last_check", 0)
    )
    private val GAMING_SNAP = listOf(SnapSpec("log", 0), SnapSpec("matches", 0), SnapSpec("spikes", 0))

    // ---- Test hooks for the restore (null/false in production; set ONLY by
    // Lab tests, cleared in finally — same pattern as the v2.9.1 gate hooks) ----
    @Volatile var testFailRenameStep: Int? = null // 1 = fail rename 1, 2 = fail rename 2
    @Volatile var testFailJournalWrite: Boolean = false
    @Volatile var testParkPoint: String? = null // "beforeRename1" | "betweenRenames" | "beforePrefs" | "beforeCommit" | "afterCommit"
    val testParked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Test-only: writes a real STARTED journal (with the prefs snapshot) so
     *  a LAB test can build a crash state and verify central recovery.
     *  Never called in production. */
    internal fun testWriteStartedJournal(ctx: Context, backupName: String) {
        writeJournalStarted(ctx, backupName, -1, "test")
    }

    private fun journalPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(JOURNAL_PREF, Context.MODE_PRIVATE)

    /** Bridge health snapshot (read-only): the restore journal state
     *  (NONE | STARTED | COMMITTED). Unreadable means UNKNOWN, never NONE. */
    fun restoreJournalState(ctx: Context): String {
        return try { journalPrefs(ctx).getString("state", null) ?: "NONE" } catch (e: Exception) { "UNKNOWN" }
    }
    private fun dbFile(ctx: Context, name: String): File = ctx.getDatabasePath(name)

    private fun snapshotPrefsInto(j: SharedPreferences.Editor, p: SharedPreferences, prefix: String, specs: List<SnapSpec>) {
        for (sp in specs) {
            val present = p.contains(sp.key)
            j.putBoolean("snap_${prefix}${sp.key}_present", present)
            if (present) {
                when (sp.kind) {
                    0 -> j.putString("snap_${prefix}${sp.key}", p.getString(sp.key, ""))
                    1 -> j.putInt("snap_${prefix}${sp.key}", p.getInt(sp.key, 0))
                    else -> j.putBoolean("snap_${prefix}${sp.key}", p.getBoolean(sp.key, false))
                }
            }
        }
    }

    private fun restorePrefsFrom(j: SharedPreferences, p: SharedPreferences, prefix: String, specs: List<SnapSpec>): Boolean {
        val e = p.edit()
        for (sp in specs) {
            if (j.getBoolean("snap_${prefix}${sp.key}_present", false)) {
                when (sp.kind) {
                    0 -> e.putString(sp.key, j.getString("snap_${prefix}${sp.key}", ""))
                    1 -> e.putInt(sp.key, j.getInt("snap_${prefix}${sp.key}", 0))
                    else -> e.putBoolean(sp.key, j.getBoolean("snap_${prefix}${sp.key}", false))
                }
            } else {
                e.remove(sp.key)
            }
        }
        return e.commit()
    }

    private fun restorePrefsFromJournal(ctx: Context): Boolean {
        val j = journalPrefs(ctx)
        val okMain = restorePrefsFrom(j, prefs(ctx), "m_", MAIN_SNAP)
        val okGaming = restorePrefsFrom(j, ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE), "g_", GAMING_SNAP)
        return okMain && okGaming
    }

    private fun writeJournalStarted(ctx: Context, backupName: String, backupSize: Long, backupSha256: String) {
        if (testFailJournalWrite) throw IllegalStateException("journal write failed (test)")
        val j = journalPrefs(ctx).edit()
        j.putString("state", "STARTED")
        j.putLong("started_at", System.currentTimeMillis())
        j.putString("backup_name", backupName)
        j.putLong("backup_size", backupSize)
        j.putString("backup_sha256", backupSha256)
        snapshotPrefsInto(j, prefs(ctx), "m_", MAIN_SNAP)
        snapshotPrefsInto(j, ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE), "g_", GAMING_SNAP)
        if (!j.commit()) throw IllegalStateException("journal write failed")
    }

    /** Central recovery. Called with restoreLock held. Uses ONLY direct
     *  prefs/file access — never History methods (no recursion). Idempotent:
     *  every step checks file existence first; a failure stops here and
     *  keeps the journal, so the next launch retries.
     *  v2.9.8 — the restoreRunning guard stays here (a concurrent
     *  ensureRecovered must never run recovery mid-restore), but the
     *  restore itself uses recoverFresh() → recoverIfNeededLocked(),
     *  which bypasses that guard: the restore owns the recovery. */
    @PublishedApi internal fun recoverIfNeeded(ctx: Context) {
        if (restoreRunning.get()) return // a restore is in Phase A; its own recoverFresh already ran
        recoverIfNeededLocked(ctx)
    }

    @PublishedApi internal fun recoverIfNeededLocked(ctx: Context) {
        val j = journalPrefs(ctx)
        val state = j.getString("state", null)
        val stagingFile = dbFile(ctx, HistoryDb.STAGING_NAME)
        when (RestoreStream.decideRecovery(state, stagingFile.exists())) {
            RestoreStream.RecoveryAction.NOTHING -> return
            RestoreStream.RecoveryAction.CLEAN_STAGING -> {
                HistoryDb.closeStaging()
                deleteFileChecked(stagingFile)
                deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME + "-journal"))
            }
            RestoreStream.RecoveryAction.ROLL_FORWARD -> {
                deleteFileChecked(dbFile(ctx, HistoryDb.OLD_NAME))
                deleteFileChecked(dbFile(ctx, HistoryDb.OLD_NAME + "-journal"))
                // v2.9.8 — the restored live DB is final now; a stale live
                // sidecar next to it belongs to the pre-restore file.
                if (HistoryDb.liveSidecarExists(ctx)) {
                    reconcileLiveSidecars(ctx)
                    if (HistoryDb.liveSidecarExists(ctx)) return // keep journal; retry next launch
                }
                j.edit().clear().commit()
            }
            RestoreStream.RecoveryAction.ROLLBACK -> rollbackFromJournal(ctx, j)
        }
    }

    /** v2.9.8 — pure decision for stale-sidecar deletion (JVM-tested):
     *  delete ONLY when a clean open + integrity check already ran and the
     *  file STILL exists. Any other combination aborts — a sidecar is
     *  never deleted blindly, without proof that no transaction holds it. */
    internal fun staleSidecarAction(cleanOpenDone: Boolean, integrityOk: Boolean, stillExists: Boolean): Boolean =
        cleanOpenDone && integrityOk && stillExists

    /** v2.9.8 — prove whether leftover live sidecars are stale, then
     *  remove them ONLY if proven stale. Sequence (all under restoreLock,
     *  so no other thread can write): reopen the live DB — SQLite itself
     *  performs its own hot-journal recovery on open — record
     *  journal_mode, run integrity_check, close again, re-list. A sidecar
     *  still present after a clean open+check+close cannot be a valid hot
     *  journal (SQLite would have consumed it), so it is safe to delete;
     *  the deletion is logged in the returned diagnostic. A sidecar is
     *  NEVER deleted before the clean open+check. Returns a diagnostic. */
    private fun reconcileLiveSidecars(ctx: Context): String {
        val mode: String
        val integrity: String
        try {
            val db = HistoryDb.getDb(ctx)
            mode = db.rawQuery("PRAGMA journal_mode", null).use { c -> if (c.moveToFirst()) c.getString(0) else "?" }
            integrity = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "?" }
        } catch (e: Exception) {
            return "باز کردن مجدد پایگاه ممکن نشد (${e.javaClass.simpleName}) — sidecar حذف نشد"
        }
        if (!HistoryDb.closeAndReset()) {
            return "journal_mode=$mode • integrity=$integrity • بستن مجدد ممکن نشد — sidecar حذف نشد"
        }
        val still = HistoryDb.liveSidecars(ctx)
        if (still.isEmpty()) return "journal_mode=$mode • integrity=$integrity • sidecar پس از بازبینی SQLite پاک شد"
        if (!staleSidecarAction(true, integrity == "ok", true)) {
            return "journal_mode=$mode • integrity=$integrity • sidecar کهنه است ولی integrity سالم نیست — حذف نشد: $still"
        }
        val deleted = mutableListOf<String>()
        var failed = false
        for ((suffix, _) in still) {
            if (deleteFileChecked(dbFile(ctx, HistoryDb.LIVE_NAME + suffix))) deleted.add(suffix) else failed = true
        }
        val after = HistoryDb.liveSidecars(ctx)
        return if (after.isEmpty() && !failed) "journal_mode=$mode • integrity=$integrity • sidecar کهنه حذف شد: $deleted"
        else "journal_mode=$mode • integrity=$integrity • حذف sidecar ناموفق: باقی‌مانده=$after"
    }

    private fun rollbackFromJournal(ctx: Context, j: SharedPreferences) {
        HistoryDb.closeAndReset()
        HistoryDb.closeStaging()
        val live = dbFile(ctx, HistoryDb.LIVE_NAME)
        val old = dbFile(ctx, HistoryDb.OLD_NAME)
        val staging = dbFile(ctx, HistoryDb.STAGING_NAME)
        if (old.exists()) {
            if (live.exists()) {
                if (!deleteFileChecked(live)) return // keep journal; retry next launch
            }
            if (!renameFileChecked(old, live, 0)) return // recovery rename (never forced to fail)
        } else if (!live.exists()) {
            // Both gone: should not happen (the rename is atomic). Log it;
            // prefs rollback still runs and the next HistoryDb access creates
            // a fresh empty DB. History is lost, but the app keeps working.
            android.util.Log.w("NetWatch", "recovery: live DB missing and no .old — history lost")
        }
        deleteFileChecked(staging)
        deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME + "-journal"))
        if (!restorePrefsFromJournal(ctx)) return // keep journal; retry next launch
        deleteFileChecked(old)
        deleteFileChecked(dbFile(ctx, HistoryDb.OLD_NAME + "-journal"))
        // v2.9.8 — the live file is final now; a stale live -journal file
        // (e.g. from the interrupted transaction this journal describes)
        // is never cleaned by SQLite recovery alone when it is not a valid
        // hot journal. Reconcile it with the same prove-then-delete rule
        // as phaseB; if it survives, keep the journal and retry next launch.
        if (HistoryDb.liveSidecarExists(ctx)) {
            reconcileLiveSidecars(ctx)
            if (HistoryDb.liveSidecarExists(ctx)) return
        }
        j.edit().clear().commit()
    }

    private fun renameFileChecked(from: File, to: File, step: Int): Boolean {
        if (testFailRenameStep != null && testFailRenameStep == step) return false
        if (!from.exists()) return false
        val ok = from.renameTo(to)
        if (!ok) return false
        return to.exists() && !from.exists()
    }

    private fun deleteFileChecked(f: File): Boolean {
        if (!f.exists()) return true
        val ok = f.delete()
        return ok && !f.exists()
    }

    /** Best-effort directory fsync (durability aid; failure is logged, never fatal).
     *  Force-stop / process kill is covered by the journal; true power-loss
     *  durability is best-effort. */
    private fun fsyncDir(dir: File) {
        try {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        } catch (e: Exception) {
            android.util.Log.w("NetWatch", "fsync dir failed: ${e.message}")
        }
    }

    private fun parkIf(point: String) {
        if (testParkPoint != point) return
        testParked.set(true)
        while (testParkPoint != null) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        testParked.set(false)
    }

    private fun capText(limitBytes: Long): String =
        if (limitBytes >= 1024 * 1024) "${limitBytes / 1024 / 1024} مگابایت" else "$limitBytes بایت"
    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun intervalSec(ctx: Context): Int = withGate(ctx) { prefs(ctx).getInt(KEY_INTERVAL, 30) }
    fun setInterval(ctx: Context, sec: Int) { withGate(ctx) {  prefs(ctx).edit().putInt(KEY_INTERVAL, sec).apply()  }}
    fun customTarget(ctx: Context): String = withGate(ctx) { prefs(ctx).getString(KEY_CUSTOM_TARGET, "") ?: "" }
    fun setCustomTarget(ctx: Context, t: String) { withGate(ctx) {  prefs(ctx).edit().putString(KEY_CUSTOM_TARGET, t).apply()  }}

    fun livePingOn(ctx: Context): Boolean = withGate(ctx) { prefs(ctx).getBoolean("live_ping", false) }
    fun setLivePing(ctx: Context, on: Boolean) { withGate(ctx) {  prefs(ctx).edit().putBoolean("live_ping", on).apply()  }}

    fun alertThreshold(ctx: Context): Int = withGate(ctx) { prefs(ctx).getInt("alert_threshold", 40) }
    fun setAlertThreshold(ctx: Context, pct: Int) { withGate(ctx) {  prefs(ctx).edit().putInt("alert_threshold", pct).apply()  }}

    // ---- Service heartbeat (v2.6): the widget's Live pill is based on this,
    // not on a static boolean. lastAttempt = last cycle start, lastSuccess =
    // last cycle that produced a result, lastHeartbeat = last widget refresh
    // the service performed. ----
    fun setHeartbeat(ctx: Context) { withGate(ctx) {  prefs(ctx).edit().putLong("last_heartbeat", System.currentTimeMillis()).apply()  }}
    fun lastHeartbeat(ctx: Context): Long = withGate(ctx) { prefs(ctx).getLong("last_heartbeat", 0L) }
    fun setLastAttempt(ctx: Context, t: Long = System.currentTimeMillis()) { withGate(ctx) {  prefs(ctx).edit().putLong("last_attempt", t).apply()  }}
    fun lastAttempt(ctx: Context): Long = withGate(ctx) { prefs(ctx).getLong("last_attempt", 0L) }
    fun setLastSuccess(ctx: Context, t: Long = System.currentTimeMillis()) { withGate(ctx) {  prefs(ctx).edit().putLong("last_success", t).apply()  }}
    fun lastSuccess(ctx: Context): Long = withGate(ctx) { prefs(ctx).getLong("last_success", 0L) }

    // ---- Migration: old prefs strings -> SQLite, once. ----
        // ---- Migration: old prefs strings -> SQLite, once. ----
    // v2.9.4: the marker lives in the DB (meta table), not only in a prefs
    // flag. Priority is DATA PRESERVATION: if neither the marker nor the old
    // flag can prove the migration already ran, ALL legacy rows are inserted
    // (no dedup, no deficit computation) and the marker is written in the
    // SAME transaction. Documented limitation (see README): in the narrow
    // crash window where the old migration committed its transaction but the
    // flag write (apply()) was lost, this creates identical-content
    // duplicate rows. Duplicates are identical measurements and are accepted
    // as the price of never losing data. The migration NEVER deletes
    // anything (insert-only).
    fun migrateIfNeeded(ctx: Context) {
        withGate(ctx) {
        val p = prefs(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            // 1. Marker exists -> already migrated. Done.
            val marker = db.rawQuery("SELECT value FROM meta WHERE key = 'migration_legacy_v1'", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (marker != null) return
            // 2. Old flag set -> the old migration committed (the flag is
            //    written only after a successful transaction). Adopt it:
            //    write the marker in its own transaction, clear the legacy
            //    strings, insert nothing.
            if (p.getBoolean(KEY_MIGRATED, false)) {
                db.beginTransaction()
                try {
                    val cv = ContentValues().apply { put("key", "migration_legacy_v1"); put("value", "adopted") }
                    db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                clearLegacyStrings(p)
                return
            }
            // 3. Ambiguous (never ran, or ran but the flag write was lost):
            //    insert ALL legacy rows + write the marker in ONE transaction.
            db.beginTransaction()
            try {
                val old = p.getString(KEY_SAMPLES, "") ?: ""
                for (line in old.split(";").filter { it.isNotBlank() }) {
                    parseSample(line)?.let { s -> insertSampleRow(db, s.time, s.ok, s.total, s.avgMs, s.jitterMs, s.lossPct, "full") }
                }
                val ph = p.getString(KEY_PING_HIST, "") ?: ""
                for (e in ph.split(";").filter { it.isNotBlank() }) {
                    val a = e.split(",")
                    val t = a.getOrNull(0)?.toLongOrNull()
                    val ms = a.getOrNull(1)?.toDoubleOrNull()
                    if (t != null && ms != null) insertPingRow(db, t, ms, 1)
                }
                val cv = ContentValues().apply { put("key", "migration_legacy_v1"); put("value", "v1") }
                db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            clearLegacyStrings(p)
        }
        }
    }

    private fun clearLegacyStrings(p: SharedPreferences) {
        // One synchronous commit: if it fails, the strings stay and the
        // marker (already committed) prevents any re-insert, so nothing is lost.
        p.edit().remove(KEY_SAMPLES).remove(KEY_PING_HIST).commit()
    }

    private fun insertSampleRow(db: SQLiteDatabase, time: Long, ok: Int, total: Int, avgMs: Double, jitterMs: Double, lossPct: Double, source: String) {
        val cv = ContentValues().apply {
            put("time", time); put("ok", ok); put("total", total)
            put("avg_ms", avgMs); put("jitter_ms", jitterMs); put("loss_pct", lossPct); put("source", source)
        }
        db.insert("samples", null, cv)
    }
    private fun insertPingRow(db: SQLiteDatabase, time: Long, ms: Double, ok: Int) {
        val cv = ContentValues().apply { put("time", time); put("ms", ms); put("ok", ok) }
        db.insert("ping_points", null, cv)
    }
    private fun pruneOld(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - HistoryDb.RETENTION_MS
        db.delete("samples", "time < ?", arrayOf(cutoff.toString()))
        db.delete("ping_points", "time < ?", arrayOf(cutoff.toString()))
        db.delete("loss_points", "time < ?", arrayOf(cutoff.toString()))
    }

    // ---- Samples: one row per check. THE v2.5 BUG was here: addSample wrote
    // 6 fields but samples() only accepted exactly 3, so it always returned
    // an empty list and the widget, gauge, CSV, JSON, PDF and backup all saw
    // ---- History ranges (v2.8 fix) ----
    // The v2.7 bug: refreshHistory() computed the cutoff as `range * 3600000`
    // while the buttons sent MIXED units — 24 meant hours but 7 and 30 meant
    // days. So "7 days" showed only 7 hours and "30 days" only 30 hours.
    // Every ranged view (ping chart, health-% chart, cards, Min/Avg/Max) must
    // use ONE HistoryRange: the duration in milliseconds lives here and
    // nowhere else. Never pass a bare Int and guess its unit again.
    enum class HistoryRange(val label: String, val millis: Long) {
        H24("۲۴ ساعت", 24L * 60 * 60 * 1000),
        D7("۷ روز", 7L * 24 * 60 * 60 * 1000),
        D30("۳۰ روز", 30L * 24 * 60 * 60 * 1000);
        fun cutoff(nowMs: Long = System.currentTimeMillis()): Long = nowMs - millis
        companion object {
            // The old button values (24/7/30) map here so a wrong unit can
            // never sneak back in through an Int.
            fun fromButton(value: Int): HistoryRange = when (value) {
                24 -> H24; 7 -> D7; 30 -> D30; else -> H24
            }
        }
    }
    // no data. samples() is now backed by the same rows addSample writes. ----
    data class Sample(val time: Long, val ok: Int, val total: Int, val avgMs: Double = -1.0, val jitterMs: Double = -1.0, val lossPct: Double = -1.0)
    fun parseSample(line: String): Sample? {
        val a = line.split(",")
        if (a.size < 3) return null
        val time = a[0].toLongOrNull() ?: return null
        val ok = a[1].toIntOrNull() ?: return null
        val total = a[2].toIntOrNull() ?: return null
        return Sample(time, ok, total,
            if (a.size > 3) a[3].toDoubleOrNull() ?: -1.0 else -1.0,
            if (a.size > 4) a[4].toDoubleOrNull() ?: -1.0 else -1.0,
            if (a.size > 5) a[5].toDoubleOrNull() ?: -1.0 else -1.0)
    }

    // v2.9: NO LIMIT here. The old "time ASC LIMIT 5000" returned the
    // OLDEST 5000 rows, so after ~42h of monitoring the newest data vanished
    // from every ranged view — and from backups too. The 30-day retention
    // prune bounds this table instead (max ~86,400 rows). Ranged reads use
    // samplesInRange() below, which caps AFTER the time filter.
    fun samplesFull(ctx: Context): List<Sample> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<Sample>()
            db.query("samples", arrayOf("time", "ok", "total", "avg_ms", "jitter_ms", "loss_pct"), null, null, null, null, "time ASC", null).use { c ->
                while (c.moveToNext()) out.add(Sample(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
            }
            return out
        }
        }
    }
    // v2.9: the time filter runs in SQL and the 5000 cap applies to the
    // NEWEST rows in range (DESC + LIMIT, then reversed to ASC for charts).
    // Filtering the oldest 5000 in memory — the v2.8 way — dropped every new
    // row once the table passed 5000 rows.
    fun samplesInRange(ctx: Context, sinceMs: Long): List<Sample> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<Sample>()
            db.query("samples", arrayOf("time", "ok", "total", "avg_ms", "jitter_ms", "loss_pct"),
                "time >= ?", arrayOf(sinceMs.toString()), null, null, "time DESC", "5000").use { c ->
                while (c.moveToNext()) out.add(Sample(c.getLong(0), c.getInt(1), c.getInt(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
            }
            out.reverse()
            return out
        }
        }
    }
    // LEGACY COMPAT ONLY — samples() returns (time, ok, total) and DROPS
    // avgMs/jitterMs/lossPct. It exists so old callers that only need the
    // ok/total ratio (widget fallback status, home gauge) keep working.
    // Anything that shows Ping, Jitter or Loss MUST use samplesFull() (or
    // samplesInRange()) instead — reading those values from samples() is
    // impossible by design, and silently showing 0 or -1 would be fake data.
    fun samples(ctx: Context): List<Triple<Long, Int, Int>> = withGate(ctx) { samplesFull(ctx).map { Triple(it.time, it.ok, it.total) } }

    fun addSample(ctx: Context, ok: Int, total: Int, avgMs: Double = -1.0, jitterMs: Double = -1.0, lossPct: Double = -1.0, source: String = "full") {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.beginTransaction()
            try {
                insertSampleRow(db, now, ok, total, avgMs, jitterMs, lossPct, source)
                pruneOld(db)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        // outage start/end tracking: outage = <40% ok
        val p = prefs(ctx)
        val pct = if (total == 0) 0 else ok * 100 / total
        val start = p.getLong(KEY_OUTAGE_START, 0L)
        if (pct < 40 && start == 0L) {
            p.edit().putLong(KEY_OUTAGE_START, now).apply()
            addTimeline(ctx, "outage-start", "شروع اختلال در ${timeStr(now)} — دسترسی $pct٪ (قبل از آن سالم بود)")
        }
        if (pct >= 70 && start != 0L) {
            val durMin = (now - start) / 60000
            addLog(ctx, "پایان اختلال: از ${timeStr(start)} تا ${timeStr(now)} — حدود $durMin دقیقه")
            addTimeline(ctx, "outage-end", "پایان اختلال: شروع ${timeStr(start)} • پایان ${timeStr(now)} • مدت حدود $durMin دقیقه • دسترسی قبل از اختلال سالم، بعد از آن $pct٪ • علت احتمالی: در گزارش تشخیص نوع اختلال ببین")
            p.edit().putLong(KEY_OUTAGE_START, 0L).apply()
        }
        }
    }

    // ---- Ping points: every measurement with its timestamp. ms < 0 = failed. ----
    // Removes samples/ping/loss rows written at or after a timestamp — used by
    // the Lab round-trip test to clean up its own test rows afterwards.
    fun deleteSamplesSince(ctx: Context, sinceMs: Long) {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.delete("samples", "time >= ?", arrayOf(sinceMs.toString()))
            db.delete("ping_points", "time >= ?", arrayOf(sinceMs.toString()))
            db.delete("loss_points", "time >= ?", arrayOf(sinceMs.toString()))
        }
        }
    }

    fun addPing(ctx: Context, avgMs: Double) {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.beginTransaction()
            try { insertPingRow(db, System.currentTimeMillis(), avgMs, 1); pruneOld(db); db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }
        }
    }
    fun addPingFailed(ctx: Context) {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.beginTransaction()
            try { insertPingRow(db, System.currentTimeMillis(), -1.0, 0); pruneOld(db); db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }
        }
    }
    // Time-ordered (time, ms) pairs; failed points carry ms = -1.0 (gap, not 0).
    // v2.9: time filter in SQL, newest `limit` rows (DESC), final order
    // ASC for charts. limit = 0 means no cap (backup/export only).
    fun pingPoints(ctx: Context, sinceMs: Long = 0L, limit: Int = 5000): List<Pair<Long, Double>> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<Pair<Long, Double>>()
            val sel = if (sinceMs > 0) "time >= ?" else null
            val args = if (sinceMs > 0) arrayOf(sinceMs.toString()) else null
            db.query("ping_points", arrayOf("time", "ms"), sel, args, null, null, "time DESC", if (limit > 0) limit.toString() else null).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getDouble(1))
            }
            out.reverse()
            return out
        }
        }
    }
    fun pingHistory(ctx: Context): List<Double> = withGate(ctx) { pingPoints(ctx).filter { it.second >= 0 }.map { it.second } }
    // Newest SUCCESSFUL ping with its timestamp (widget live tile).
    fun lastPing(ctx: Context): Pair<Long, Double>? {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            db.query("ping_points", arrayOf("time", "ms"), "ok = 1", null, null, null, "time DESC", "1").use { c ->
                return if (c.moveToFirst()) c.getLong(0) to c.getDouble(1) else null
            }
        }
        }
    }
    // Newest ping attempt of any kind (for "last measurement" age).
    fun lastPingTime(ctx: Context): Long {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            db.query("ping_points", arrayOf("MAX(time)"), null, null, null, null, null).use { c ->
                return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }
        }
    }

    // ---- Loss points (loss sparkline source). ----
    fun addLoss(ctx: Context, lossPct: Double) {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.beginTransaction()
            try {
                val cv = ContentValues().apply { put("time", System.currentTimeMillis()); put("loss", lossPct) }
                db.insert("loss_points", null, cv)
                pruneOld(db)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        }
    }
    // v2.9: the NEWEST 60 loss values (the old ASC LIMIT 60 returned the
    // oldest 60 — a sparkline frozen on the first hour of data).
    fun lossHistory(ctx: Context): List<Double> = withGate(ctx) { lossPoints(ctx, limit = 60).map { it.second } }
    // v2.9: same newest-first pattern as pingPoints (limit = 0: no cap).
    fun lossPoints(ctx: Context, sinceMs: Long = 0L, limit: Int = 5000): List<Pair<Long, Double>> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<Pair<Long, Double>>()
            val sel = if (sinceMs > 0) "time >= ?" else null
            val args = if (sinceMs > 0) arrayOf(sinceMs.toString()) else null
            db.query("loss_points", arrayOf("time", "loss"), sel, args, null, null, "time DESC", if (limit > 0) limit.toString() else null).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getDouble(1))
            }
            out.reverse()
            return out
        }
        }
    }

    // ---- Gaming sessions (persisted, with per-sample timestamps). ----
    data class GamingSession(val id: Long, val started: Long, val ended: Long, val profile: String, val region: String, val host: String,
        val sent: Int, val received: Int, val loss: Double, val avgMs: Double, val minMs: Double, val maxMs: Double,
        val jitterMs: Double, val p95: Double, val spikes: Int, val burst: Int, val score: Int?, val bbGrade: String)
    fun startGamingSession(ctx: Context, profile: String, region: String, host: String): Long {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            val cv = ContentValues().apply {
                put("started", System.currentTimeMillis()); put("ended", 0L)
                put("profile", profile); put("region", region); put("host", host)
                put("sent", 0); put("received", 0); put("loss", 0.0)
                put("avg_ms", 0.0); put("min_ms", 0.0); put("max_ms", 0.0)
                put("jitter_ms", 0.0); put("p95", 0.0); put("spikes", 0); put("burst", 0)
                put("score", null as Int?); put("bb_grade", "-")
            }
            return db.insert("gaming_sessions", null, cv)
        }
        }
    }
    fun addGamingSample(ctx: Context, sessionId: Long, ms: Double, ok: Boolean) {
        withGate(ctx) {
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            val cv = ContentValues().apply { put("session_id", sessionId); put("time", System.currentTimeMillis()); put("ms", if (ok) ms else -1.0); put("ok", if (ok) 1 else 0) }
            db.insert("gaming_samples", null, cv)
        }
        }
    }
    fun finishGamingSession(ctx: Context, sessionId: Long, sent: Int, received: Int, loss: Double, avgMs: Double, minMs: Double, maxMs: Double, jitterMs: Double, p95: Double, spikes: Int, burst: Int, score: Int?, bbGrade: String) {
        withGate(ctx) {
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            val cv = ContentValues().apply {
                put("ended", System.currentTimeMillis()); put("sent", sent); put("received", received)
                put("loss", loss); put("avg_ms", avgMs); put("min_ms", minMs); put("max_ms", maxMs)
                put("jitter_ms", jitterMs); put("p95", p95); put("spikes", spikes); put("burst", burst)
                put("score", score); put("bb_grade", bbGrade)
            }
            db.update("gaming_sessions", cv, "id = ?", arrayOf(sessionId.toString()))
        }
        }
    }
    // v2.9: uncapped read for backup only — the UI keeps showing the
    // newest 50 via gamingSessions(), but a backup must not drop sessions.
    fun gamingSessionsAll(ctx: Context): List<GamingSession> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<GamingSession>()
            db.query("gaming_sessions", null, null, null, null, null, "started DESC", null).use { c ->
                while (c.moveToNext()) {
                    out.add(GamingSession(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5),
                        c.getInt(6), c.getInt(7), c.getDouble(8), c.getDouble(9), c.getDouble(10), c.getDouble(11),
                        c.getDouble(12), c.getDouble(13), c.getInt(14), c.getInt(15),
                        if (c.isNull(16)) null else c.getInt(16), c.getString(17)))
                }
            }
            return out
        }
        }
    }

    fun gamingSessions(ctx: Context): List<GamingSession> {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<GamingSession>()
            db.query("gaming_sessions", null, null, null, null, null, "started DESC", "50").use { c ->
                while (c.moveToNext()) {
                    out.add(GamingSession(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5),
                        c.getInt(6), c.getInt(7), c.getDouble(8), c.getDouble(9), c.getDouble(10), c.getDouble(11),
                        c.getDouble(12), c.getDouble(13), c.getInt(14), c.getInt(15),
                        if (c.isNull(16)) null else c.getInt(16), c.getString(17)))
                }
            }
            return out
        }
        }
    }
    fun gamingSessionSamples(ctx: Context, sessionId: Long): List<Pair<Long, Double>> {
        withGate(ctx) {
        synchronized(dbLock) {
            val db = HistoryDb.getReadDb(ctx)
            val out = mutableListOf<Pair<Long, Double>>()
            db.query("gaming_samples", arrayOf("time", "ms"), "session_id = ?", arrayOf(sessionId.toString()), null, null, "time ASC").use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getDouble(1))
            }
            return out
        }
        }
    }

    // ---- Last full check, for the widget. ----
    data class LastCheck(val pingAvg: Double, val jitterMs: Double, val lossPct: Double, val intlPct: Int, val domPct: Int, val scoreTotal: Int?, val scoreStatus: String, val checkedAt: Long = 0)
    fun saveLastCheck(ctx: Context, pingAvg: Double, jitterMs: Double, lossPct: Double, intlPct: Int, domPct: Int, scoreTotal: Int?, scoreStatus: String) {
        withGate(ctx) {
        prefs(ctx).edit()
            .putString("last_check", "$pingAvg|$jitterMs|$lossPct|$intlPct|$domPct|${scoreTotal ?: ""}|$scoreStatus|${System.currentTimeMillis()}").apply()
        }
    }
    fun lastCheck(ctx: Context): LastCheck? {
        withGate(ctx) {
        val t = prefs(ctx).getString("last_check", "") ?: ""
        if (t.isBlank()) return null
        val a = t.split("|")
        if (a.size < 7) return null
        return LastCheck(a[0].toDoubleOrNull() ?: -1.0, a[1].toDoubleOrNull() ?: -1.0, a[2].toDoubleOrNull() ?: -1.0, a[3].toIntOrNull() ?: 0, a[4].toIntOrNull() ?: 0, a[5].toIntOrNull(), a[6], if (a.size > 7) a[7].toLongOrNull() ?: 0 else 0)
        }
    }
    fun saveConnType(ctx: Context, t: String) {
        withGate(ctx) {
        if (t != "—") prefs(ctx).edit().putString("last_conn_type", t).apply()
        }
    }
    fun connType(ctx: Context): String? {
        withGate(ctx) {
        val t = prefs(ctx).getString("last_conn_type", "") ?: ""
        return if (t.isBlank()) null else t
        }
    }

    // ---- Logs / timeline (append-only, stay in prefs). ----
    fun addLog(ctx: Context, line: String) {
        withGate(ctx) {
        val p = prefs(ctx)
        val old = p.getString(KEY_LOG, "")!!
        val lines = (line + "\n" + old).lines().take(200).joinToString("\n")
        p.edit().putString(KEY_LOG, lines).apply()
        }
    }
    fun log(ctx: Context): String = withGate(ctx) { prefs(ctx).getString(KEY_LOG, "") ?: "" }

    fun compareText(ctx: Context): String {
        withGate(ctx) {
        val lines = Gaming.log(ctx).lines().filter { it.isNotBlank() }
        if (lines.size < 2) return "مقایسه تست‌ها: هنوز دو تست گیمینگ ثبت نشده."
        val a = lines[0]; val b = lines[1]
        val hourlyData = hourly(ctx).split(";").filter { it.isNotBlank() }
        val avg = if (hourlyData.isEmpty()) 0 else hourlyData.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull() }.average()
        return "مقایسه دو تست آخر گیمینگ:\nآخرین: $a\nقبلی: $b\n\nمیانگین دسترسی ساعتی (ISP): ${"%.0f".format(avg)}٪ از ${hourlyData.size} نمونه ساعتی — نمودار روزانه/هفتگی از همین داده‌هاست."
        }
    }
    fun addTimeline(ctx: Context, kind: String, line: String) {
        withGate(ctx) {
        val p = prefs(ctx)
        val old = (p.getString("timeline", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
        old.add(0, "${timeStr(System.currentTimeMillis())} [$kind] $line")
        while (old.size > 100) old.removeAt(old.size - 1)
        p.edit().putString("timeline", old.joinToString("\n")).apply()
        }
    }
    data class TimelineEvent(val time: String, val kind: String, val text: String)
    fun parseTimeline(text: String): List<TimelineEvent> {
        return text.lines().filter { it.isNotBlank() }.map { line ->
            val open = line.indexOf(" [")
            val close = if (open >= 0) line.indexOf("] ", open) else -1
            if (open < 0 || close < 0) TimelineEvent("", "", line)
            else TimelineEvent(line.substring(0, open), line.substring(open + 2, close), line.substring(close + 2))
        }
    }
    fun timeline(ctx: Context): String = withGate(ctx) { prefs(ctx).getString("timeline", "") ?: "" }
    fun addSpike(ctx: Context, ms: Double) {
        withGate(ctx) {
        val p = ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE)
        val old = (p.getString("spikes", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
        old.add(0, "${timeStr(System.currentTimeMillis())} — Spike ${"%.0f".format(ms)}ms")
        while (old.size > 100) old.removeAt(old.size - 1)
        p.edit().putString("spikes", old.joinToString("\n")).apply()
        }
    }
    fun spikes(ctx: Context): String = withGate(ctx) { ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).getString("spikes", "") ?: "" }
    fun addHourly(ctx: Context, ok: Int, total: Int) {
        withGate(ctx) {
        val p = prefs(ctx)
        val hour = SimpleDateFormat("HH", Locale.US).format(Date())
        val old = p.getString("hourly", "") ?: ""
        p.edit().putString("hourly", (old + ";" + hour + ":" + (if (total == 0) 0 else ok * 100 / total)).split(";").filter { it.isNotBlank() }.takeLast(96).joinToString(";")).apply()
        }
    }
    fun hourly(ctx: Context): String = withGate(ctx) { prefs(ctx).getString("hourly", "") ?: "" }
    fun blackbox(ctx: Context, line: String) {
        withGate(ctx) {
        val p = prefs(ctx)
        val old = (p.getString("blackbox", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
        old.add("${timeStr(System.currentTimeMillis())} $line")
        while (old.size > 20) old.removeAt(0)
        p.edit().putString("blackbox", old.joinToString("\n")).apply()
        }
    }
    fun blackboxLog(ctx: Context): String = withGate(ctx) { prefs(ctx).getString("blackbox", "") ?: "" }

    fun clearAll(ctx: Context) {
        withGate(ctx) {
        prefs(ctx).edit().clear().apply()
        ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit().clear().apply()
        synchronized(dbLock) {
            val db = HistoryDb.getDb(ctx)
            db.delete("samples", null, null); db.delete("ping_points", null, null)
            db.delete("loss_points", null, null); db.delete("gaming_sessions", null, null); db.delete("gaming_samples", null, null)
        }
        }
    }

    // ---- Backup / restore: one valid JSON document (org.json — no hand-built
    // string escaping). Restore validates the structure + version first and
    // writes everything in ONE transaction, so a corrupt file can never
    // destroy the existing data. ----
    fun buildBackupJson(ctx: Context): String {
        withGate(ctx) {
        migrateIfNeeded(ctx)
        val root = org.json.JSONObject()
        root.put("version", 1)
        root.put("exported_at", dateTimeStr(System.currentTimeMillis()))
        val samples = org.json.JSONArray()
        for (s in samplesFull(ctx)) {
            val o = org.json.JSONObject()
            o.put("time", s.time); o.put("ok", s.ok); o.put("total", s.total)
            o.put("avg_ms", s.avgMs); o.put("jitter_ms", s.jitterMs); o.put("loss_pct", s.lossPct)
            samples.put(o)
        }
        root.put("samples", samples)
        val pings = org.json.JSONArray()
        for ((t, ms) in pingPoints(ctx, limit = 0)) { val o = org.json.JSONObject(); o.put("time", t); o.put("ms", ms); pings.put(o) }
        root.put("ping_points", pings)
        val losses = org.json.JSONArray()
        for ((t, l) in lossPoints(ctx, limit = 0)) { val o = org.json.JSONObject(); o.put("time", t); o.put("loss", l); losses.put(o) }
        root.put("loss_points", losses)
        root.put("log", log(ctx))
        root.put("timeline", timeline(ctx))
        root.put("hourly", hourly(ctx))
        root.put("blackbox", blackboxLog(ctx))
        root.put("gaming_log", Gaming.log(ctx))
        root.put("gaming_matches", Gaming.matches(ctx))
        root.put("spikes", spikes(ctx))
        val sessions = org.json.JSONArray()
        for (gs in gamingSessionsAll(ctx)) {
            val o = org.json.JSONObject()
            o.put("started", gs.started); o.put("ended", gs.ended); o.put("profile", gs.profile); o.put("region", gs.region)
            o.put("host", gs.host); o.put("sent", gs.sent); o.put("received", gs.received); o.put("loss", gs.loss)
            o.put("avg_ms", gs.avgMs); o.put("min_ms", gs.minMs); o.put("max_ms", gs.maxMs); o.put("jitter_ms", gs.jitterMs)
            o.put("p95", gs.p95); o.put("spikes", gs.spikes); o.put("burst", gs.burst)
            if (gs.score != null) o.put("score", gs.score) else o.put("score", org.json.JSONObject.NULL)
            o.put("bb_grade", gs.bbGrade)
            val ss = org.json.JSONArray()
            for ((t, ms) in gamingSessionSamples(ctx, gs.id)) { val so = org.json.JSONObject(); so.put("time", t); so.put("ms", ms); ss.put(so) }
            o.put("samples", ss)
            sessions.put(o)
        }
        root.put("gaming_sessions", sessions)
        val lc = lastCheck(ctx)
        if (lc != null) {
            val o = org.json.JSONObject()
            o.put("ping_avg", lc.pingAvg); o.put("jitter_ms", lc.jitterMs); o.put("loss_pct", lc.lossPct)
            o.put("intl_pct", lc.intlPct); o.put("dom_pct", lc.domPct)
            if (lc.scoreTotal != null) o.put("score_total", lc.scoreTotal) else o.put("score_total", org.json.JSONObject.NULL)
            o.put("score_status", lc.scoreStatus); o.put("checked_at", lc.checkedAt)
            root.put("last_check", o)
        }
        val settings = org.json.JSONObject()
        settings.put("interval_sec", intervalSec(ctx)); settings.put("alert_threshold", alertThreshold(ctx))
        settings.put("live_ping", livePingOn(ctx)); settings.put("custom_target", customTarget(ctx))
        settings.put("dns_rounds", prefs(ctx).getInt("dns_rounds", 20))
        settings.put("alerts_on", prefs(ctx).getBoolean("alerts_on", true))
        root.put("settings", settings)
        return root.toString()
        }
    }

    // ---- Restore (v2.9.4, staging + journal + streaming parser) ----
    // The old restore parsed the whole backup into a String + JSONObject
    // (2-4x the file size in transient heap) and then deleted + reinserted
    // everything inside ONE transaction while prefs were written afterwards
    // with separate async apply() calls — a crash mid-restore left a mixed
    // state. The new flow:
    //   Phase A (no restoreLock): stream + validate + insert into the
    //     STAGING db in 5,000-row batches; the live DB and prefs are
    //     untouched, so probes/monitoring keep running.
    //   Phase B (restoreLock, sub-second): journal STARTED (with a prefs
    //     snapshot) -> close the live connection -> two atomic renames
    //     (live -> .old, staging -> live) -> prefs commits -> COMMITTED.
    //   STARTED always rolls back; COMMITTED only rolls forward.
    // Old backups (v2.9.3, no HMAC) stay restorable: strict structure/type
    // validation still applies; the HMAC check is a later stage-2 item.
    // Returns "OK" or an exact error string.
    fun restoreFromJson(ctx: Context, json: String, limitBytes: Long = RESTORE_MAX_BYTES): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (RestoreStream.exceedsCap(bytes.size.toLong(), limitBytes)) {
            return "حجم فایل بکاپ بیشتر از حد مجاز است (${capText(limitBytes)}) — داده‌های قبلی دست نخورده باقی ماند"
        }
        return doRestore(ctx, java.io.ByteArrayInputStream(bytes), "text", limitBytes, null)
    }

    private fun restoreErrorMessage(e: Exception): String {
        return when (e) {
            is RestoreStream.SizeLimitExceeded -> "حجم فایل بکاپ بیشتر از حد مجاز است (${capText(RESTORE_MAX_BYTES)}) — داده‌های قبلی دست نخورده باقی ماند"
            is RestoreStream.ValidationFailed -> "فایل بکاپ خراب است: ${e.message} — داده‌های قبلی دست نخورده باقی ماند"
            is RestoreStream.CorruptBackup -> "${e.message} — داده‌های قبلی دست نخورده باقی ماند"
            else -> "بازیابی ناموفق شد: ${e.message ?: e.javaClass.simpleName} — داده‌های قبلی دست نخورده باقی ماند"
        }
    }

    /** Counts + hashes the backup bytes as they are read (identification only, for the journal). */
    private class CountingDigestStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        private val md = java.security.MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
            private set
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { bytesRead++; md.update(b.toByte()) }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { bytesRead += n; md.update(b, off, n) }
            return n
        }
        fun sha256Hex(): String = md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun doRestore(ctx: Context, input: java.io.InputStream, sourceName: String, limitBytes: Long, onProgress: ((Int) -> Unit)?): String {
        if (!restoreRunning.compareAndSet(false, true)) return "در حال بازیابی است — لطفاً صبر کن"
        try {
            lastRestoreDiag = ""
            // v2.9.8 — fresh recovery: always re-read the real journal/file
            // state (never the once-per-process flag). The old
            // ensureRecovered() call here was a no-op while restoreRunning
            // is true, so a stale STARTED journal was never re-examined.
            recoverFresh(ctx)
            migrateIfNeeded(ctx)
            // ---- Phase A: stream into staging (live state untouched) ----
            HistoryDb.closeStaging()
            deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME))
            deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME + "-journal"))
            val stagingDb = HistoryDb.getStagingDb(ctx)
            val counting = CountingDigestStream(input)
            val payload: RestoreStream.RestorePayload
            try {
                payload = RestoreStream.streamIntoStaging(counting, limitBytes, stagingDb, onProgress)
            } catch (e: Exception) {
                HistoryDb.closeStaging()
                deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME))
                deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME + "-journal"))
                return restoreErrorMessage(e)
            }
            val sha256 = counting.sha256Hex()
            // Staging was verified inside streamIntoStaging; close it before the rename.
            if (!HistoryDb.closeStaging()) {
                deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME))
                return "بازیابی ناموفق شد: پایگاه موقت بسته نشد — داده‌های قبلی دست نخورده باقی ماند"
            }
            if (!dbFile(ctx, HistoryDb.STAGING_NAME).exists()) {
                return "بازیابی ناموفق شد: پایگاه موقت ساخته نشد — داده‌های قبلی دست نخورده باقی ماند"
            }
            // ---- Phase B: journal + renames + prefs (under restoreLock) ----
            return synchronized(restoreLock) {
                phaseB(ctx, payload, sourceName, counting.bytesRead, sha256)
            }
        } finally {
            restoreRunning.set(false)
        }
    }

    /** Inline rollback of a failed Phase B: live := .old. True if the live DB is back. */
    private fun rollbackInline(ctx: Context): Boolean {
        val live = dbFile(ctx, HistoryDb.LIVE_NAME)
        val old = dbFile(ctx, HistoryDb.OLD_NAME)
        if (!old.exists()) return live.exists()
        if (live.exists()) {
            if (!deleteFileChecked(live)) return false
        }
        if (!renameFileChecked(old, live, 0)) return false
        return live.exists() && !old.exists()
    }

    private fun applyPayloadPrefs(ctx: Context, payload: RestoreStream.RestorePayload) {
        val p = prefs(ctx).edit()
        payload.log?.let { p.putString(KEY_LOG, it) }
        payload.timeline?.let { p.putString("timeline", it) }
        payload.hourly?.let { p.putString("hourly", it) }
        payload.blackbox?.let { p.putString("blackbox", it) }
        payload.intervalSec?.let { p.putInt(KEY_INTERVAL, it) }
        payload.alertThreshold?.let { p.putInt("alert_threshold", it) }
        payload.livePing?.let { p.putBoolean("live_ping", it) }
        payload.customTarget?.let { p.putString(KEY_CUSTOM_TARGET, it) }
        payload.dnsRounds?.let { p.putInt("dns_rounds", it) }
        payload.alertsOn?.let { p.putBoolean("alerts_on", it) }
        payload.lastCheck?.let { p.putString("last_check", it) }
        if (!p.commit()) throw IllegalStateException("prefs commit failed")
        val g = ctx.getSharedPreferences("gaming", Context.MODE_PRIVATE).edit()
        payload.gamingLog?.let { g.putString("log", it) }
        payload.gamingMatches?.let { g.putString("matches", it) }
        payload.spikes?.let { g.putString("spikes", it) }
        if (!g.commit()) throw IllegalStateException("gaming prefs commit failed")
    }

    /** Phase B. Called with restoreLock held. Returns "OK" or an exact error. */
    private fun phaseB(ctx: Context, payload: RestoreStream.RestorePayload, sourceName: String, backupSize: Long, sha256: String): String {
        val live = dbFile(ctx, HistoryDb.LIVE_NAME)
        val old = dbFile(ctx, HistoryDb.OLD_NAME)
        val staging = dbFile(ctx, HistoryDb.STAGING_NAME)
        // Stale .old with NO journal (should be impossible after cleanup, but
        // be total): verify the live DB opens, log, delete the stale .old.
        if (!journalPrefs(ctx).contains("state") && old.exists()) {
            try { HistoryDb.getDb(ctx).rawQuery("SELECT 1", null).use { } } catch (e: Exception) { return "بازیابی ناموفق شد: پایگاه فعلی باز نمی‌شود — داده‌های قبلی دست نخورده باقی ماند" }
            android.util.Log.w("NetWatch", "stale .old file found with no journal; deleting")
            deleteFileChecked(old)
        }
        // 1. Journal STARTED (with prefs snapshot) — synchronous commit.
        //    From here, a crash means ROLLBACK.
        try {
            writeJournalStarted(ctx, sourceName, backupSize, sha256)
        } catch (e: Exception) {
            return "بازیابی ناموفق شد: نوشتن ژورنال ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        fsyncDir(live.parentFile ?: ctx.filesDir)
        // 2. Close the live connection (in DELETE mode this IS the checkpoint)
        //    and prove no sidecar file survives.
        //    v2.9.8 — a leftover sidecar no longer aborts blindly, and is
        //    never deleted blindly either: reconcileLiveSidecars() first
        //    lets SQLite itself recover (clean open + integrity_check),
        //    and deletes the file ONLY if it is proven stale. The outcome
        //    is recorded in lastRestoreDiag for the LAB result. Abort only
        //    if a sidecar still survives the reconcile.
        if (!HistoryDb.closeAndReset()) {
            return "بازیابی ناموفق شد: اتصال پایگاه بسته نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        val sidecarsBefore = HistoryDb.liveSidecars(ctx)
        if (sidecarsBefore.isNotEmpty()) {
            val diag = reconcileLiveSidecars(ctx)
            lastRestoreDiag = "sidecar قبل از بازبینی: $sidecarsBefore • $diag"
            // Re-prove the connection is really closed before the renames:
            // closeAndReset is idempotent, and a failed close here means
            // the live DB must not be renamed.
            if (!HistoryDb.closeAndReset() || HistoryDb.liveSidecarExists(ctx)) {
                return "بازیابی ناموفق شد: فایل جانبی پایگاه باقی ماند ($diag) — داده‌های قبلی دست نخورده باقی ماند"
            }
        }
        // 3. Two atomic renames.
        parkIf("beforeRename1")
        if (old.exists()) {
            return "بازیابی ناموفق شد: فایل پشتیبان قبلی پاک نشده است — داده‌های قبلی دست نخورده باقی ماند"
        }
        if (!live.exists()) {
            return "بازیابی ناموفق شد: پایگاه فعلی یافت نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        if (!renameFileChecked(live, old, 1)) {
            // Inline abort: live is untouched; delete staging + journal.
            deleteFileChecked(staging)
            deleteFileChecked(dbFile(ctx, HistoryDb.STAGING_NAME + "-journal"))
            journalPrefs(ctx).edit().clear().commit()
            return "بازیابی ناموفق شد: جایگزینی پایگاه ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        parkIf("betweenRenames")
        if (live.exists() || !staging.exists()) {
            if (!rollbackInline(ctx)) return "بازیابی ناموفق شد: خطای بحرانی — ژورنال باقی ماند و در راه‌اندازی بعد بازیابی می‌شود"
            journalPrefs(ctx).edit().clear().commit()
            return "بازیابی ناموفق شد: جایگزینی پایگاه ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        if (!renameFileChecked(staging, live, 2)) {
            if (!rollbackInline(ctx)) return "بازیابی ناموفق شد: خطای بحرانی — ژورنال باقی ماند و در راه‌اندازی بعد بازیابی می‌شود"
            journalPrefs(ctx).edit().clear().commit()
            return "بازیابی ناموفق شد: جایگزینی پایگاه ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        fsyncDir(live.parentFile ?: ctx.filesDir)
        // 4. Prefs (synchronous commits). On failure, roll back the DB AND
        //    the prefs from the snapshot; if either fails, the journal stays
        //    STARTED and the next launch retries.
        parkIf("beforePrefs")
        try {
            applyPayloadPrefs(ctx, payload)
        } catch (e: Exception) {
            val dbOk = rollbackInline(ctx)
            val prefsOk = try { restorePrefsFromJournal(ctx) } catch (_: Exception) { false }
            if (!dbOk || !prefsOk) return "بازیابی ناموفق شد: خطای بحرانی — ژورنال باقی ماند و در راه‌اندازی بعد بازیابی می‌شود"
            journalPrefs(ctx).edit().clear().commit()
            return "بازیابی ناموفق شد: نوشتن تنظیمات ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        // 5. COMMITTED — only after ALL mutations. A crash between the last
        //    prefs commit and this write rolls back a COMPLETED restore
        //    (safe; the user retries).
        parkIf("beforeCommit")
        try {
            if (testFailJournalWrite) throw IllegalStateException("journal write failed (test)")
            val j = journalPrefs(ctx).edit()
            j.putString("state", "COMMITTED")
            if (!j.commit()) throw IllegalStateException("journal commit failed")
        } catch (e: Exception) {
            val dbOk = rollbackInline(ctx)
            val prefsOk = try { restorePrefsFromJournal(ctx) } catch (_: Exception) { false }
            if (!dbOk || !prefsOk) return "بازیابی ناموفق شد: خطای بحرانی — ژورنال باقی ماند و در راه‌اندازی بعد بازیابی می‌شود"
            journalPrefs(ctx).edit().clear().commit()
            return "بازیابی ناموفق شد: ثبت نهایی ممکن نشد — داده‌های قبلی دست نخورده باقی ماند"
        }
        fsyncDir(live.parentFile ?: ctx.filesDir)
        // 6. Cleanup: delete .old, then the journal LAST.
        parkIf("afterCommit")
        deleteFileChecked(old)
        deleteFileChecked(dbFile(ctx, HistoryDb.OLD_NAME + "-journal"))
        journalPrefs(ctx).edit().clear().commit()
        recovered = true
        return "OK"
    }

        fun backup(ctx: Context): File {
        val f = File(ctx.getExternalFilesDir(null), "netwatch-backup.json")
        f.writeText(buildBackupJson(ctx))
        return f
    }

    /** Local-file restore with the exact reason (NOT_FOUND if no backup file). */
    fun restoreReason(ctx: Context): String {
        val f = File(ctx.getExternalFilesDir(null), "netwatch-backup.json")
        if (!f.exists()) return "NOT_FOUND"
        return try {
            if (RestoreStream.exceedsCap(f.length(), RESTORE_MAX_BYTES)) return "حجم فایل بکاپ بیشتر از حد مجاز است (${capText(RESTORE_MAX_BYTES)}) — داده‌های قبلی دست نخورده باقی ماند"
            java.io.FileInputStream(f).use { stream ->
                doRestore(ctx, stream, f.name, RESTORE_MAX_BYTES, null)
            }
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }
    fun restore(ctx: Context): Boolean = restoreReason(ctx) == "OK"

    fun backupToUri(ctx: Context, uri: android.net.Uri): Boolean {
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(buildBackupJson(ctx).toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) { false }
    }

    // SAF restore with the exact failure reason (used by MainActivity's toast).
    // The file is streamed (bounded reader + JsonReader), never read whole.
    fun restoreFromUriReason(ctx: Context, uri: android.net.Uri, limitBytes: Long = RESTORE_MAX_BYTES): String {
        return try {
            val fd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return "فایل خوانده نشد."
            val size = fd.statSize
            if (RestoreStream.exceedsCap(if (size >= 0) size else null, limitBytes)) {
                try { fd.close() } catch (_: Exception) { }
                return "حجم فایل بکاپ بیشتر از حد مجاز است (${capText(limitBytes)}) — داده‌های قبلی دست نخورده باقی ماند"
            }
            val stream = java.io.FileInputStream(fd.fileDescriptor)
            try {
                doRestore(ctx, stream, uri.toString(), limitBytes, null)
            } finally {
                try { stream.close() } catch (_: Exception) { }
                try { fd.close() } catch (_: Exception) { }
            }
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    fun restoreFromUri(ctx: Context, uri: android.net.Uri): Boolean {
        return restoreFromUriReason(ctx, uri) == "OK"
    }

    // ---- Exports: full timestamps, real metrics, valid formats. ----
    // v2.9.6 — report exports go to Downloads/NetWatch/ through the ONE
    // ExportHelper path (MediaStore on API 29+, public folder on 21-28)
    // and return an ExportResult (uri, file, exact name, ok/error).
    // Backup/restore stay in the app-private dir — see ExportHelper.
    fun exportCsv(ctx: Context): ExportHelper.ExportResult {
        val sb = StringBuilder("time,ok,total,pct,avg_ms,jitter_ms,loss_pct\n")
        for (s in samplesFull(ctx)) {
            val pct = if (s.total == 0) 0 else s.ok * 100 / s.total
            sb.append("\"${dateTimeStr(s.time)}\",${s.ok},${s.total},$pct,${fmtNum(s.avgMs)},${fmtNum(s.jitterMs)},${fmtNum(s.lossPct)}\n")
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        return ExportHelper.save(ctx, "NETWATCH_History", "csv", "text/csv") { it.write(bytes) }
    }
    private fun fmtNum(v: Double): String = if (v < 0) "" else "%.1f".format(v)

    fun exportJson(ctx: Context): ExportHelper.ExportResult {
        val bytes = buildBackupJson(ctx).toByteArray(Charsets.UTF_8)
        return ExportHelper.save(ctx, "NETWATCH_History", "json", "application/json") { it.write(bytes) }
    }

    // Real Persian PDF: multi-page, RTL (text drawn from the right edge),
    // wrapped lines, full timestamps. Android PdfDocument renders Persian
    // shaping/joining correctly with the system font.
    fun exportPdf(ctx: Context): ExportHelper.ExportResult {
        val doc = android.graphics.pdf.PdfDocument()
        val paint = android.graphics.Paint().apply { textSize = 12f; color = android.graphics.Color.BLACK }
        val titlePaint = android.graphics.Paint().apply { textSize = 18f; color = android.graphics.Color.BLACK; isFakeBoldText = true }
        val headPaint = android.graphics.Paint().apply { textSize = 14f; color = android.graphics.Color.BLACK; isFakeBoldText = true }
        val pw = 595f; val ph = 842f; val margin = 40f; val rightX = pw - margin
        var pageNum = 1
        var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pageNum).create())
        var canvas = page.canvas
        var y = 50f
        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pageNum).create())
            canvas = page.canvas; y = 50f
        }
        fun drawRtl(t: String, p: android.graphics.Paint) {
            // Wrap a long line to the page width, right-aligned.
            var rest = t
            while (rest.isNotEmpty()) {
                var n = rest.length
                while (n > 0 && p.measureText(rest, 0, n) > pw - 2 * margin) n--
                if (n == 0) n = 1
                val part = rest.substring(0, n)
                canvas.drawText(part, rightX - p.measureText(part), y, p)
                y += 20f
                if (y > ph - 60) newPage()
                rest = rest.substring(n).trimStart()
            }
        }
        fun heading(t: String) { y += 8f; drawRtl(t, headPaint); y += 4f }
        drawRtl("NetWatch — Diagnostic Report", titlePaint)
        drawRtl("تاریخ گزارش: " + dateTimeStr(System.currentTimeMillis()), paint)
        drawRtl("Connection: local only, no server upload", paint)
        heading("تاریخچه بررسی‌ها (Ping / Jitter / Loss):")
        val samples = samplesFull(ctx)
        if (samples.isEmpty()) drawRtl("هنوز بررسی‌ای ثبت نشده است.", paint)
        for (s in samples.takeLast(200)) {
            val pct = if (s.total == 0) 0 else s.ok * 100 / s.total
            drawRtl("${dateTimeStr(s.time)} — ${s.ok}/${s.total} سالم ($pct٪) • Ping ${if (s.avgMs < 0) "—" else "%.0f".format(s.avgMs) + "ms"} • Jitter ${if (s.jitterMs < 0) "—" else "%.1f".format(s.jitterMs) + "ms"} • Loss ${if (s.lossPct < 0) "—" else "%.1f".format(s.lossPct) + "٪"}", paint)
        }
        heading("Gaming sessions:")
        val sessions = gamingSessions(ctx)
        if (sessions.isEmpty()) drawRtl("هنوز جلسه گیمینگ ثبت نشده است.", paint)
        for (gs in sessions) {
            drawRtl("${dateTimeStr(gs.started)} — ${gs.profile} • ${gs.region} • avg ${"%.0f".format(gs.avgMs)}ms • loss ${"%.1f".format(gs.loss)}٪ • score ${gs.score ?: "—"} • Bufferbloat ${gs.bbGrade}", paint)
        }
        heading("Blackbox (last 20 checks):")
        for (raw in blackboxLog(ctx).lines().take(20)) drawRtl(raw, paint)
        heading("History log:")
        for (raw in log(ctx).lines().take(100)) drawRtl(raw, paint)
        doc.finishPage(page)
        // v2.9.6 — the PDF bytes go to Downloads/NetWatch/ via ExportHelper;
        // a failed save deletes the partial file and reports the exact cause.
        return try {
            ExportHelper.save(ctx, "NETWATCH_Report", "pdf", "application/pdf") { out -> doc.writeTo(out) }
        } finally { doc.close() }
    }

    fun timeStr(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))
    fun dateTimeStr(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}
