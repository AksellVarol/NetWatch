package com.aksell.netwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// SQLite storage (v2.6) — replaces the single big SharedPreferences string.
// Tables:
// - samples: one row per check (full or background), with ping/jitter/loss.
// - ping_points: every ping measurement with its timestamp; ms < 0 = failed
//   (charts draw a gap for failures, never 0ms).
// - loss_points: per-check packet-loss values with timestamps (loss sparkline).
// - gaming_sessions / gaming_samples: persisted gaming sessions with
//   per-sample timestamps, so a session survives leaving the screen.
// - meta (v2, added 2.9.4): key/value markers, e.g. "migration_legacy_v1"
//   (proof that the legacy-strings migration committed once).
// Retention: rows older than 30 days are pruned on write.
//
// v2.9.4 (Restore Security, P0-1):
// - DB version 1 -> 2; onUpgrade creates meta with CREATE TABLE IF NOT EXISTS.
// - The helper is parameterised by file name so the restore can build a
//   staging DB ("netwatch.db.staging") with the same schema.
// - getDb()/getReadDb() are the ONLY ways to obtain a SQLiteDatabase: they
//   record the returned instance in lastLiveDb/lastStagingDb so closeAndReset()
//   can prove no connection is left open before a restore renames the file.
// - Journal mode is DELETE (verified: no enableWriteAheadLogging / WAL call
//   anywhere in the app), so closing the last connection IS the checkpoint and
//   no -wal/-shm files can exist; closeAndReset() still asserts that no
//   sidecar file (-journal/-wal/-shm) survives the close.
class HistoryDb private constructor(ctx: Context, dbName: String) : SQLiteOpenHelper(ctx, dbName, null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: only the meta table is new. IF NOT EXISTS keeps this safe
        // even if onCreate already ran on a fresh install.
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        // v2 -> v3 (Intelligence Core, Phase 1): only the AI tables are
        // new (ai_analyses + ai_chats). No existing table is touched.
        if (oldVersion < 3) {
            for (sql in AiStore.createTablesSql()) db.execSQL(sql)
        }
    }

    companion object {
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
        const val LIVE_NAME = "netwatch.db"
        const val STAGING_NAME = "netwatch.db.staging"
        const val OLD_NAME = "netwatch.db.old"

        @Volatile private var inst: HistoryDb? = null
        @Volatile private var stagingInst: HistoryDb? = null
        // Last database objects handed out. Used only by closeAndReset() /
        // closeStaging() to prove the connection is really closed.
        @Volatile private var lastLiveDb: SQLiteDatabase? = null
        @Volatile private var lastStagingDb: SQLiteDatabase? = null

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, ok INTEGER NOT NULL, total INTEGER NOT NULL, avg_ms REAL NOT NULL, jitter_ms REAL NOT NULL, loss_pct REAL NOT NULL, source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE ping_points(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, ms REAL NOT NULL, ok INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE loss_points(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, loss REAL NOT NULL)")
            db.execSQL("CREATE TABLE gaming_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER NOT NULL, profile TEXT NOT NULL, region TEXT NOT NULL, host TEXT NOT NULL, sent INTEGER NOT NULL, received INTEGER NOT NULL, loss REAL NOT NULL, avg_ms REAL NOT NULL, min_ms REAL NOT NULL, max_ms REAL NOT NULL, jitter_ms REAL NOT NULL, p95 REAL NOT NULL, spikes INTEGER NOT NULL, burst INTEGER NOT NULL, score INTEGER, bb_grade TEXT NOT NULL)")
            db.execSQL("CREATE TABLE gaming_samples(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, time INTEGER NOT NULL, ms REAL NOT NULL, ok INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            // Intelligence Core (Phase 1, DB v3): AI history lives in the
            // same file. Fresh installs get the tables here; existing DBs
            // get them from onUpgrade (oldVersion < 3).
            for (sql in AiStore.createTablesSql()) db.execSQL(sql)
            db.execSQL("CREATE INDEX idx_samples_time ON samples(time)")
            db.execSQL("CREATE INDEX idx_ping_time ON ping_points(time)")
            db.execSQL("CREATE INDEX idx_loss_time ON loss_points(time)")
            db.execSQL("CREATE INDEX idx_gsamples_session ON gaming_samples(session_id)")
        }

        fun get(ctx: Context): HistoryDb = inst ?: synchronized(this) {
            inst ?: HistoryDb(ctx.applicationContext, LIVE_NAME).also { inst = it }
        }

        /** The only way to obtain the live writable database. Records the instance for closeAndReset(). */
        fun getDb(ctx: Context): SQLiteDatabase {
            val db = get(ctx).writableDatabase
            lastLiveDb = db
            return db
        }

        /** The only way to obtain the live readable database. Records the instance for closeAndReset(). */
        fun getReadDb(ctx: Context): SQLiteDatabase {
            val db = get(ctx).readableDatabase
            lastLiveDb = db
            return db
        }

        fun getStaging(ctx: Context): HistoryDb = stagingInst ?: synchronized(this) {
            stagingInst ?: HistoryDb(ctx.applicationContext, STAGING_NAME).also { stagingInst = it }
        }

        /** The only way to obtain the staging writable database. */
        fun getStagingDb(ctx: Context): SQLiteDatabase {
            val db = getStaging(ctx).writableDatabase
            lastStagingDb = db
            return db
        }

        /**
         * Closes the live helper and nulls the singleton. Must be called with
         * History.restoreLock (and dbLock) held, so no thread can be inside a
         * DB operation. Returns true only if the live database is really
         * closed afterwards (lastLiveDb is null or not open). If it returns
         * false the caller must abort the rename — never rename a live DB.
         */
        fun closeAndReset(): Boolean {
            synchronized(this) {
                try { inst?.close() } catch (_: Exception) { }
                inst = null
                val db = lastLiveDb
                return db == null || !db.isOpen
            }
        }

        /** Closes the staging helper and nulls its singleton. Returns true if really closed. */
        fun closeStaging(): Boolean {
            synchronized(this) {
                try { stagingInst?.close() } catch (_: Exception) { }
                stagingInst = null
                val db = lastStagingDb
                return db == null || !db.isOpen
            }
        }

        /** True if any SQLite sidecar file (-journal/-wal/-shm) exists next to the live DB. */
        fun liveSidecarExists(ctx: Context): Boolean {
            return ctx.getDatabasePath("$LIVE_NAME-journal").exists() ||
                ctx.getDatabasePath("$LIVE_NAME-wal").exists() ||
                ctx.getDatabasePath("$LIVE_NAME-shm").exists()
        }

        /** v2.9.8 — which live sidecar files exist right now, with sizes.
         *  For diagnostics only: the restore reports exactly which file
         *  survived a close, instead of a bare "sidecar remained". Never throws. */
        fun liveSidecars(ctx: Context): List<Pair<String, Long>> {
            val out = mutableListOf<Pair<String, Long>>()
            for (suffix in listOf("-journal", "-wal", "-shm")) {
                try {
                    val f = ctx.getDatabasePath("$LIVE_NAME$suffix")
                    if (f.exists()) out.add(suffix to f.length())
                } catch (_: Exception) { }
            }
            return out
        }
    }
}
