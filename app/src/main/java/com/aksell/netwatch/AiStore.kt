package com.aksell.netwatch

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

// AI Store — the SQLite home of the Intelligence Core's memory
// (ai_analyses + ai_chats, same DB file as History, HistoryDb v3).
//
// Rules (architecture §6-3, §9):
// - Capped like Bridge: newest 100 analyses, newest 200 chat messages;
//   older rows are pruned, never kept unbounded.
// - History.clearAll does NOT touch these tables: clearing AI history is
//   a separate, user-confirmed action (clearAiHistory).
// - No API key, no IP, no network detail is ever written here: the packet
//   is already sanitized by AiCore, and the reply is sanitized again on
//   receipt (defense in depth — sanitize is idempotent).
// - All DB access goes through History.withGate (the same central gate
//   as History), so a restore can never interleave with an AI write.
object AiStore {

    const val MAX_ANALYSES = 100
    const val MAX_CHATS = 200

    data class AiChat(
        val id: Long, val threadId: String, val role: String,
        val text: String, val createdMs: Long, val requestId: String?
    )

    /** CREATE TABLE statements (also used by HistoryDb.onCreate/onUpgrade).
     *  Pure — the JVM tests assert the schema from these strings.
     *  NOTE (implementation extension vs the architecture report): one
     *  extra column, share_uri, holds the Share file Uri of a pending
     *  round so AiActivity can launch the chooser later; two indexes make
     *  the request_id / thread_id lookups cheap. Nothing else differs. */
    fun createTablesSql(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS ai_analyses(id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT UNIQUE, created_ms INTEGER NOT NULL, kind TEXT NOT NULL, provider TEXT NOT NULL, model TEXT, status TEXT NOT NULL, packet_json TEXT NOT NULL, response_json TEXT, response_md TEXT, verdict TEXT, confidence INTEGER NOT NULL DEFAULT -1, latency_ms INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, error TEXT, share_uri TEXT)",
        "CREATE TABLE IF NOT EXISTS ai_chats(id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, created_ms INTEGER NOT NULL, request_id TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_ai_analyses_request ON ai_analyses(request_id)",
        "CREATE INDEX IF NOT EXISTS idx_ai_chats_thread ON ai_chats(thread_id)"
    )

    /** Prune SQL: keep only the newest `cap` rows. Pure. */
    fun pruneSql(table: String, cap: Int): String =
        "DELETE FROM $table WHERE id NOT IN (SELECT id FROM $table ORDER BY id DESC LIMIT $cap)"

    private fun roundValues(r: AiCore.AiRound): ContentValues {
        val v = ContentValues()
        v.put("request_id", r.requestId)
        v.put("created_ms", r.createdMs)
        v.put("kind", r.kind)
        v.put("provider", r.provider)
        v.put("model", r.model)
        v.put("status", r.status)
        v.put("packet_json", r.packetJson)
        v.put("response_json", r.responseJson)
        v.put("response_md", r.responseMd)
        v.put("verdict", r.verdict)
        v.put("confidence", r.confidence)
        v.put("latency_ms", r.latencyMs)
        v.put("attempts", r.attempts)
        v.put("error", r.error)
        v.put("share_uri", r.shareUri)
        return v
    }

    private fun cursorToRound(c: Cursor): AiCore.AiRound {
        fun s(col: String): String? {
            val i = c.getColumnIndex(col)
            return if (i < 0 || c.isNull(i)) null else c.getString(i)
        }
        fun l(col: String): Long {
            val i = c.getColumnIndex(col)
            return if (i < 0 || c.isNull(i)) 0L else c.getLong(i)
        }
        fun n(col: String, def: Int): Int {
            val i = c.getColumnIndex(col)
            return if (i < 0 || c.isNull(i)) def else c.getInt(i)
        }
        return AiCore.AiRound(
            id = l("id"), requestId = s("request_id") ?: "", createdMs = l("created_ms"),
            kind = s("kind") ?: "analysis", provider = s("provider") ?: "local",
            model = s("model"), status = s("status") ?: "",
            packetJson = s("packet_json") ?: "", responseJson = s("response_json"),
            responseMd = s("response_md"), verdict = s("verdict"),
            confidence = n("confidence", -1), latencyMs = l("latency_ms"),
            attempts = n("attempts", 0), error = s("error"), shareUri = s("share_uri")
        )
    }

    fun insertRound(ctx: Context, round: AiCore.AiRound): Long {
        return History.withGate(ctx) {
            val db = HistoryDb.getDb(ctx)
            val id = db.insertWithOnConflict("ai_analyses", null, roundValues(round), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            pruneLocked(ctx)
            id
        }
    }

    fun updateRound(ctx: Context, round: AiCore.AiRound) {
        History.withGate(ctx) {
            val db = HistoryDb.getDb(ctx)
            db.update("ai_analyses", roundValues(round), "request_id=?", arrayOf(round.requestId))
        }
    }

    fun getRound(ctx: Context, requestId: String): AiCore.AiRound? {
        return History.withGate(ctx) {
            val db = HistoryDb.getReadDb(ctx)
            db.rawQuery("SELECT * FROM ai_analyses WHERE request_id=? LIMIT 1", arrayOf(requestId)).use { c ->
                if (c.moveToFirst()) cursorToRound(c) else null
            }
        }
    }

    fun listRounds(ctx: Context, limit: Int = 50): List<AiCore.AiRound> {
        return History.withGate(ctx) {
            val db = HistoryDb.getReadDb(ctx)
            val out = ArrayList<AiCore.AiRound>()
            db.rawQuery("SELECT * FROM ai_analyses ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
                while (c.moveToNext()) out.add(cursorToRound(c))
            }
            out
        }
    }

    /** The newest round still waiting for Muse (for the Share button). */
    fun pendingRound(ctx: Context): AiCore.AiRound? {
        return History.withGate(ctx) {
            val db = HistoryDb.getReadDb(ctx)
            db.rawQuery("SELECT * FROM ai_analyses WHERE status=? ORDER BY id DESC LIMIT 1", arrayOf(AiCore.STATUS_PENDING)).use { c ->
                if (c.moveToFirst()) cursorToRound(c) else null
            }
        }
    }

    fun insertChat(ctx: Context, threadId: String, role: String, text: String, requestId: String?): Long {
        return History.withGate(ctx) {
            val db = HistoryDb.getDb(ctx)
            val v = ContentValues()
            v.put("thread_id", threadId)
            v.put("role", role)
            v.put("text", Bridge.sanitize(text))
            v.put("created_ms", System.currentTimeMillis())
            v.put("request_id", requestId)
            val id = db.insert("ai_chats", null, v)
            pruneLocked(ctx)
            id
        }
    }

    fun listChats(ctx: Context, threadId: String): List<AiChat> {
        return History.withGate(ctx) {
            val db = HistoryDb.getReadDb(ctx)
            val out = ArrayList<AiChat>()
            db.rawQuery("SELECT * FROM ai_chats WHERE thread_id=? ORDER BY id ASC", arrayOf(threadId)).use { c ->
                while (c.moveToNext()) {
                    val i = c.getColumnIndex("id")
                    val t = c.getColumnIndex("thread_id")
                    val r = c.getColumnIndex("role")
                    val x = c.getColumnIndex("text")
                    val m = c.getColumnIndex("created_ms")
                    val q = c.getColumnIndex("request_id")
                    out.add(AiChat(
                        if (i < 0) 0L else c.getLong(i),
                        if (t < 0) "" else c.getString(t) ?: "",
                        if (r < 0) "" else c.getString(r) ?: "",
                        if (x < 0) "" else c.getString(x) ?: "",
                        if (m < 0) 0L else c.getLong(m),
                        if (q < 0 || c.isNull(q)) null else c.getString(q)
                    ))
                }
            }
            out
        }
    }

    /** Keeps only the newest MAX_ANALYSES / MAX_CHATS rows. Called after
     *  every insert (already inside withGate — never call it bare). */
    private fun pruneLocked(ctx: Context) {
        try {
            val db = HistoryDb.getDb(ctx)
            db.execSQL(pruneSql("ai_analyses", MAX_ANALYSES))
            db.execSQL(pruneSql("ai_chats", MAX_CHATS))
        } catch (_: Exception) { }
    }

    /** Deletes ALL AI history (both tables). Separate, user-confirmed
     *  action — History.clearAll never calls this. */
    fun clearAiHistory(ctx: Context) {
        History.withGate(ctx) {
            val db = HistoryDb.getDb(ctx)
            db.delete("ai_analyses", null, null)
            db.delete("ai_chats", null, null)
        }
    }

    // ================= JVM self-tests (pure only) =================

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "AI Store — ورودی ثابت، بدون دیتابیس", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        val sql = createTablesSql()
        t("AI Store — جدول ai_analyses ساخته می‌شود", "has ai_analyses + request_id UNIQUE + share_uri", "tables=${sql.size} has=${sql[0].contains("ai_analyses") && sql[0].contains("request_id TEXT UNIQUE") && sql[0].contains("share_uri TEXT")}",
            sql.size == 4 && sql[0].contains("ai_analyses") && sql[0].contains("request_id TEXT UNIQUE") && sql[0].contains("share_uri TEXT"))
        t("AI Store — جدول ai_chats ساخته می‌شود", "has ai_chats + thread_id", "has=${sql[1].contains("ai_chats") && sql[1].contains("thread_id TEXT NOT NULL")}",
            sql[1].contains("ai_chats") && sql[1].contains("thread_id TEXT NOT NULL"))
        t("AI Store — ستون‌های وضعیت و اطمینان", "status + verdict + confidence DEFAULT -1", "has=${sql[0].contains("status TEXT NOT NULL") && sql[0].contains("verdict TEXT") && sql[0].contains("confidence INTEGER NOT NULL DEFAULT -1")}",
            sql[0].contains("status TEXT NOT NULL") && sql[0].contains("verdict TEXT") && sql[0].contains("confidence INTEGER NOT NULL DEFAULT -1"))
        t("AI Store — سقف‌ها", "100 analyses / 200 chats", "caps=$MAX_ANALYSES/$MAX_CHATS", MAX_ANALYSES == 100 && MAX_CHATS == 200)
        val pruneA = pruneSql("ai_analyses", 100)
        t("AI Store — SQL هرس تحلیل‌ها", "keeps newest 100", "sql=$pruneA",
            pruneA == "DELETE FROM ai_analyses WHERE id NOT IN (SELECT id FROM ai_analyses ORDER BY id DESC LIMIT 100)")
        val pruneC = pruneSql("ai_chats", 200)
        t("AI Store — SQL هرس گفت‌وگو", "keeps newest 200", "sql=$pruneC",
            pruneC == "DELETE FROM ai_chats WHERE id NOT IN (SELECT id FROM ai_chats ORDER BY id DESC LIMIT 200)")
        return out
    }
}
