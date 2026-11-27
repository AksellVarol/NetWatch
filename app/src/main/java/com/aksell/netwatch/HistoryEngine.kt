package com.aksell.netwatch

import android.content.Context

// History Engine — the single API for storing, reading and analysing
// history. The implementation is History (SQLite via HistoryDb, 30-day
// retention, transactional writes, one-time migration of old data); this
// object is the stable boundary the UI, the widget and the services call, so
// no caller assembles a check record from five separate History calls any
// more. All timestamps are real (System.currentTimeMillis at measurement
// time); ranges are 24h / 7d / 30d and are applied to the query, not to the
// title.
object HistoryEngine {

    // One call records a whole check: the sample row (ok/total/ping/jitter/
    // loss) and the "last check" summary the dashboard and widget read.
    fun recordCheck(
        ctx: Context, ok: Int, total: Int,
        pingAvg: Double, jitterMs: Double, lossPct: Double,
        intlPct: Int, domPct: Int,
        scoreTotal: Int?, scoreStatus: String, source: String = "full",
        saveSummary: Boolean = true
    ) {
        History.addSample(ctx, ok, total, pingAvg, jitterMs, lossPct, source)
        // The background service only updates the "last check" summary when
        // the ping actually completed; a failed cycle still records its sample.
        if (saveSummary) History.saveLastCheck(ctx, pingAvg, jitterMs, lossPct, intlPct, domPct, scoreTotal, scoreStatus)
    }

    // One call records one ping measurement: success goes to the ping series,
    // failure is recorded as a failed point (ms < 0) so charts draw a gap,
    // and a completed burst also records its loss.
    fun recordPing(ctx: Context, ping: PingStats) {
        if (ping.ok) History.addPing(ctx, ping.avgMs) else History.addPingFailed(ctx)
        if (ping.completed) History.addLoss(ctx, ping.lossPct.toDouble())
    }

    // One live-ping measurement: success stores the value, failure stores
    // a failed point so the chart draws a gap instead of a zero.
    fun recordPingMs(ctx: Context, ms: Double?) {
        if (ms != null) History.addPing(ctx, ms) else History.addPingFailed(ctx)
    }

    // LEGACY: (time, ok, total) only — no ping/jitter/loss. Only the
    // widget fallback status and the home gauge use this; everything that
    // shows Ping/Jitter/Loss uses samplesFull()/samplesInRange().
    fun samples(ctx: Context): List<Triple<Long, Int, Int>> = History.samples(ctx)

    fun pingHistory(ctx: Context): List<Double> = History.pingHistory(ctx)
    fun lossHistory(ctx: Context): List<Double> = History.lossHistory(ctx)
    fun addGamingSample(ctx: Context, sessionId: Long, ms: Double, ok: Boolean) =
        History.addGamingSample(ctx, sessionId, ms, ok)
    fun addSpike(ctx: Context, ms: Double) = History.addSpike(ctx, ms)

    fun pingPoints(ctx: Context, sinceMs: Long = 0L): List<Pair<Long, Double>> =
        History.pingPoints(ctx, sinceMs)

    fun samplesInRange(ctx: Context, sinceMs: Long): List<History.Sample> =
        History.samplesInRange(ctx, sinceMs)

    fun lastCheck(ctx: Context): History.LastCheck? = History.lastCheck(ctx)
    fun lastPing(ctx: Context): Pair<Long, Double>? = History.lastPing(ctx)

    fun startGamingSession(ctx: Context, profile: String, region: String, host: String): Long =
        History.startGamingSession(ctx, profile, region, host)

    fun finishGamingSession(
        ctx: Context, sessionId: Long, sent: Int, received: Int, loss: Double,
        avgMs: Double, minMs: Double, maxMs: Double, jitterMs: Double, p95: Double,
        spikes: Int, burst: Int, score: Int?, bbGrade: String
    ) = History.finishGamingSession(ctx, sessionId, sent, received, loss, avgMs, minMs, maxMs, jitterMs, p95, spikes, burst, score, bbGrade)

    fun gamingSessions(ctx: Context): List<History.GamingSession> = History.gamingSessions(ctx)
    fun gamingSessionSamples(ctx: Context, sessionId: Long): List<Pair<Long, Double>> =
        History.gamingSessionSamples(ctx, sessionId)

    // v2.9.9: the newest session whose end time is still 0 — i.e. a session
    // that was started but never finished (still running in the service, or
    // left behind by a killed process). Null when no session is open.
    fun activeGamingSession(ctx: Context): History.GamingSession? =
        History.gamingSessions(ctx).firstOrNull { it.ended == 0L }

    fun buildBackupJson(ctx: Context): String = History.buildBackupJson(ctx)
    fun restoreFromJson(ctx: Context, json: String): String = History.restoreFromJson(ctx, json)

    fun setHeartbeat(ctx: Context) = History.setHeartbeat(ctx)
    fun lastHeartbeat(ctx: Context): Long = History.lastHeartbeat(ctx)
    fun setLastAttempt(ctx: Context) = History.setLastAttempt(ctx)
    fun setLastSuccess(ctx: Context) = History.setLastSuccess(ctx)
}
