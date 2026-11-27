package com.aksell.netwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Future

// Background monitoring (v2.6): foreground service so Android does not kill
// it immediately. NOTE (honest Android limit): on Android 6+ Doze/App Standby
// can still delay background work; foreground service + notification is the
// reliable route, and the widget updates on each check.
//
// v2.6 changes:
// - Background cycles run a LIGHT check (8 probes, ~2-3s) instead of the full
//   40+ probe run every 30s — far less battery and data.
// - The user-set interval is re-read on EVERY cycle, so changing it in
//   Settings applies to the running service immediately.
// - Live Ping shares the one managed executor (no new Thread per 5s tick)
//   and is skipped while a check is running — no duplicate ping measurements.
// - The widget's Live state is based on a heartbeat timestamp (written on
//   every successful refresh), not on a static boolean.
//
// v2.9.9 — session-based background gaming monitoring (the user's decision
// 2026-11-10): the gaming session loop lives HERE, not in GamingActivity.
// The activity only starts/stops a session (Intent actions) and renders
// state from the DB. Leaving the Gaming screen, switching apps, or the
// activity being reclaimed no longer stops the session: it pings every 5s
// for the chosen duration (1/5/15/30 min), persists every sample, and can
// be stopped from the Gaming screen or from the notification. A session
// left unfinished by a killed process is closed from its recorded samples
// on the next service start (interrupted, data preserved — never resumed,
// never deleted). Starting a gaming session starts this service (so the
// light check runs too); stopping a session never stops the service —
// background monitoring keeps the state the user set from Home.
class MonitorService : Service() {
    companion object {
        @Volatile var running = false

        const val ACTION_START_GAMING = "START_GAMING"
        const val ACTION_STOP_GAMING = "STOP_GAMING"

        // Intelligence Core (Phase 1): analysis rounds are service-owned,
        // exactly like the gaming loop — one round at a time.
        const val ACTION_AI_ANALYZE = "AI_ANALYZE"
        const val ACTION_AI_CANCEL = "AI_CANCEL"
        @Volatile var aiBusy = false
        @Volatile var aiFuture: Future<*>? = null
        @Volatile var aiRoundId: String? = null

        // Gaming session state (service-owned). Only ONE session can run:
        // START_GAMING is ignored while gamingActive is true.
        @Volatile var gamingActive = false
        @Volatile var gamingSessionId = -1L
        @Volatile var gamingFuture: Future<*>? = null
        @Volatile var gamingEndsAtMs = 0L
        @Volatile var gamingProfile = ""
        @Volatile var gamingRegion = ""
        @Volatile var gamingHost = ""
        @Volatile var gamingDurationMin = 1
        @Volatile var gamingStopRequested = false
        // Sessions currently being finished (loop task vs recovery vs
        // onDestroy) — a session is finished by exactly one path.
        private val finishingIds = HashSet<Long>()
        fun tryBeginFinish(id: Long): Boolean = synchronized(finishingIds) { finishingIds.add(id) }
        fun endFinish(id: Long) { synchronized(finishingIds) { finishingIds.remove(id) } }
    }
    private val handler = Handler(Looper.getMainLooper())
    // No local futures or flags: the single shared guard lives in
    // MonitoringEngine/NetworkEngine.submitSingle. This service only owns its
    // Handler loops, which Android requires to live here. Cancellation goes
    // through MonitoringEngine.cancelCheck()/cancelLivePing().

    private val loop = object : Runnable {
        override fun run() {
            val iv = History.intervalSec(this@MonitorService)
            HistoryEngine.setLastAttempt(this@MonitorService)
            // If a manual check is running, this cycle is skipped — the ONE
            // shared guard refuses a duplicate run. No finally is needed:
            // submitSingle removes the registry entry on completion.
            val f = MonitoringEngine.runCheck {
                try {
                    val results = NetworkEngine.runProbes(true)
                    val ok = results.count { it.ok }
                    val pct = if (results.isEmpty()) 0 else ok * 100 / results.size
                    val pingBg = NetworkEngine.pingStats("1.1.1.1")
                    HistoryEngine.recordPing(this@MonitorService, pingBg)
                    val intl = results.filter { !it.domestic }; val dom = results.filter { it.domestic }
                    val intlPct = if (intl.isEmpty()) 0 else intl.count { it.ok } * 100 / intl.size
                    val domPct = if (dom.isEmpty()) 0 else dom.count { it.ok } * 100 / dom.size
                    History.addHourly(this@MonitorService, ok, results.size)
                    // The quality score is computed once, and only when the ping
                    // burst completed; a failed cycle still records its sample
                    // but does not overwrite the "last check" summary.
                    val q = if (pingBg.completed) AnalyticsEngine.qualityScore(pingBg.avgMs, pingBg.jitterMs, pingBg.lossPct.toDouble(), pct,
                        pingValid = pingBg.ok && pingBg.avgMs > 0,
                        jitterValid = pingBg.completed && pingBg.lossPct <= 50,
                        lossValid = pingBg.completed, stabValid = results.isNotEmpty()) else null
                    val scoreTotal = when (q) { is Quality.ScoreResult.Complete -> q.total; else -> null }
                    val scoreStatus = when (q) { is Quality.ScoreResult.Complete -> "Complete"; is Quality.ScoreResult.InsufficientData -> "InsufficientData"; Quality.ScoreResult.NotAvailable -> "NotAvailable"; null -> "NotAvailable" }
                    if (pingBg.completed) History.saveConnType(this@MonitorService, Geo.connectionType(this@MonitorService))
                    HistoryEngine.recordCheck(this@MonitorService, ok, results.size,
                        if (pingBg.ok) pingBg.avgMs else -1.0,
                        if (pingBg.ok) pingBg.jitterMs else -1.0,
                        if (pingBg.ok) pingBg.lossPct.toDouble() else -1.0,
                        intlPct, domPct, scoreTotal, scoreStatus, "background", saveSummary = pingBg.completed)
                    History.blackbox(this@MonitorService, "$ok/${results.size} ok ($pct%) — light check")
                    val prev = History.lastPct(this@MonitorService)
                    val th = History.alertThreshold(this@MonitorService)
                    if (pct < th && prev >= th) alert("⚠️ قطعی اینترنت", "دسترسی به $pct٪ مقصدها قطع شد (آستانه هشدار $th٪) — جزئیات در Blackbox ثبت شد.")
                    if (pct >= 70 && prev < th) alert("✅ اتصال برگشت", "اینترنت دوباره سالم شد ($pct٪ مقصدها).")
                    History.setLastPct(this@MonitorService, pct)
                    HistoryEngine.setLastSuccess(this@MonitorService)
                    // v2.9.6 (Scope B): refresh the widget's cached network
                    // stats (DNS / gateway / public IP) on this cycle only.
                    // Bounded and never throws: a failure here must never
                    // break the monitoring cycle or the widget refresh.
                    try { WidgetStats.refreshNetworkStats(this@MonitorService) } catch (e: Exception) {}
                    NetWatchWidget.refreshAll(this@MonitorService)
                    HistoryEngine.setHeartbeat(this@MonitorService)
                } catch (e: Exception) {
                    // Crash protection: a failed check must never kill the service
                    History.addLog(this@MonitorService, "خطا در بررسی پس‌زمینه: ${e.javaClass.simpleName}")
                }
            }
            if (f == null) { handler.postDelayed(this, iv * 1000L); return }
            handler.postDelayed(this, iv * 1000L)
        }
    }

    // Live Ping: one ICMP ping every 5s while this service runs, only when the
    // user enabled it in Settings. A 1-second loop is not used: Android throttles
    // widget updates and it would burn battery for no visible benefit.
    // Skipped while a background check is running (no duplicate measurement).
    private val liveLoop = object : Runnable {
        override fun run() {
            // One live ping at a time: the guard is atomic, so a slow ping can
            // never stack a second one on top of it. Skipped while a check runs.
            if (MonitoringEngine.livePingOn(this@MonitorService) && !MonitoringEngine.isCheckRunning()) {
                MonitoringEngine.runLivePing {
                    try {
                        val ms = NetworkEngine.pingOnce("1.1.1.1")
                        HistoryEngine.recordPingMs(this@MonitorService, ms)
                        if (ms != null) {
                            NetWatchWidget.refreshAll(this@MonitorService)
                            HistoryEngine.setHeartbeat(this@MonitorService)
                        }
                    } catch (e: Exception) {}
                }
            }
            handler.postDelayed(this, 5000)
        }
    }

    // ---- v2.9.9: session-based background gaming monitoring ----

    private fun piFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    private fun monitorNotification(): Notification {
        val iv = History.intervalSec(this)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "netwatch").setContentTitle("NetWatch در حال بررسی اینترنت است").setContentText("بررسی سبک هر $iv ثانیه — برای توقف، از داخل اپ خاموش کن.").setSmallIcon(R.drawable.netwatch_icon).build()
        else Notification.Builder(this).setContentTitle("NetWatch در حال بررسی اینترنت است").setSmallIcon(R.drawable.netwatch_icon).build()
    }

    private fun restoreMonitorNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, monitorNotification())
        } catch (e: Exception) {}
    }

    private fun gamingNotification(avgMs: Double, lossPct: Double, remainingMs: Long): Notification {
        val remain = if (remainingMs < 60 * 1000L) "${remainingMs / 1000} ثانیه مانده" else "${remainingMs / 60000} دقیقه مانده"
        val text = "میانگین ${"%.0f".format(avgMs)}ms • قطعی ${"%.1f".format(lossPct)}٪ • $remain"
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, GamingActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }, piFlags())
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MonitorService::class.java).apply { action = ACTION_STOP_GAMING }, piFlags())
        val action = if (Build.VERSION.SDK_INT >= 20) Notification.Action.Builder(android.R.drawable.ic_media_pause, "توقف", stop).build() else null
        return if (Build.VERSION.SDK_INT >= 26) {
            val b = Notification.Builder(this, "netwatch").setContentTitle("🎮 مانیتورینگ گیمینگ — $gamingProfile").setContentText(text).setSmallIcon(R.drawable.netwatch_icon).setContentIntent(open)
            if (action != null) b.addAction(action)
            b.build()
        } else {
            val b = Notification.Builder(this).setContentTitle("🎮 مانیتورینگ گیمینگ — $gamingProfile").setContentText(text).setSmallIcon(R.drawable.netwatch_icon).setContentIntent(open)
            if (action != null) b.addAction(action)
            b.build()
        }
    }

    private fun updateGamingNotification(avgMs: Double, lossPct: Double, remainingMs: Long) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, gamingNotification(avgMs, lossPct, remainingMs))
        } catch (e: Exception) {}
    }

    // Finish one session from its recorded samples (pure stats). Called by
    // the loop task (its own in-memory samples) and by the recovery path
    // (samples re-read from the DB). Exactly one caller wins per session.
    private fun finishSession(sessionId: Long, host: String, profile: String, region: String, samples: List<Double>) {
        if (!tryBeginFinish(sessionId)) return
        try {
            val st = Gaming.sessionStats(samples)
            val r = PingResult(host, st.sent, st.received, st.lossPct, st.minMs, st.avgMs, st.maxMs,
                st.p50, st.p90, st.p95, st.p99, st.jitterMs, st.spikes, st.burst, st.times)
            // NO automatic bufferbloat here (it can use up to 50 MB): the
            // score stays incomplete (total = null) until the user runs it
            // on purpose — same rule as the old activity loop.
            val sc = AnalyticsEngine.gamingScore(r, null)
            HistoryEngine.finishGamingSession(this, sessionId, r.sent, r.received, r.lossPct, r.avgMs, r.minMs, r.maxMs,
                r.jitterMs, r.p95, r.spikes, r.burstLoss, null, "-")
            try { Gaming.saveLast(this, r, null, sc, profile, region, Geo.connectionInfo(this)) } catch (e: Exception) {}
            try { NetWatchWidget.refreshAll(this) } catch (e: Exception) {}
        } catch (e: Exception) {
            try { History.addLog(this, "خطا در پایان جلسه گیمینگ: ${e.javaClass.simpleName}") } catch (_: Exception) {}
        } finally {
            endFinish(sessionId)
            if (sessionId == gamingSessionId) {
                gamingActive = false; gamingSessionId = -1L; gamingFuture = null
                restoreMonitorNotification()
            }
        }
    }

    // Close every session left unfinished (ended == 0) — e.g. the process
    // was killed mid-session. Each is finished from its recorded samples
    // (interrupted, data preserved). Never resumes a session, never deletes
    // one. The newest 50 sessions are enough: a stale session is always
    // the most recently started one.
    private fun finishStaleSessions() {
        try {
            val stale = HistoryEngine.gamingSessions(this).filter { it.ended == 0L }
            for (s in stale) {
                val samples = HistoryEngine.gamingSessionSamples(this, s.id).map { it.second }
                finishSession(s.id, s.host, s.profile, s.region, samples)
                // v2.9.9 — recovery log line (NEW vs the v2.9.9 draft): only
                // when the session was really closed from its recorded
                // samples. Never a delete, never a resume — the row stays
                // in history, finished, with stats from its real samples.
                val closed = try { HistoryEngine.gamingSessions(this).firstOrNull { it.id == s.id }?.ended ?: 0L } catch (e: Exception) { 0L }
                if (closed > 0L) {
                    try { History.addLog(this, "جلسه گیمینگ ناتمام بسته شد") } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            try { History.addLog(this, "خطا در بازیابی جلسه گیمینگ: ${e.javaClass.simpleName}") } catch (_: Exception) {}
        }
    }

    // One monitoring task on the shared executor: a single ping every 5s,
    // samples persisted per tick. Same tick rules as the old activity loop
    // (spike = > 150ms or > 2x running average; burst = consecutive drops).
    // Stop sets the flag and cancels the Future — the bounded ping can
    // never hang the stop. On exit the session is finished from its
    // recorded samples (completed or stopped — the DB row is the same).
    private fun gamingLoopTask(): Runnable = Runnable {
        val host = gamingHost
        val sessionId = gamingSessionId
        val profile = gamingProfile
        val region = gamingRegion
        val endsAt = gamingEndsAtMs
        val allTimes = mutableListOf<Double>()
        val allSamples = mutableListOf<Double>()
        try {
            while (!gamingStopRequested && System.currentTimeMillis() < endsAt) {
                val tickStart = System.currentTimeMillis()
                val p = NetworkEngine.ping(host, 1)
                if (gamingStopRequested) break
                if (p.received == 0) {
                    allSamples.add(-1.0)
                    HistoryEngine.addGamingSample(this@MonitorService, sessionId, -1.0, false)
                } else {
                    for (t in p.times) {
                        allTimes.add(t)
                        allSamples.add(t)
                        HistoryEngine.addGamingSample(this@MonitorService, sessionId, t, true)
                        val avgSoFar = allTimes.average()
                        if (t > 150 || t > avgSoFar * 2) HistoryEngine.addSpike(this@MonitorService, t)
                    }
                }
                val st = Gaming.sessionStats(allSamples.toList())
                val remainingMs = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                updateGamingNotification(st.avgMs, st.lossPct, remainingMs)
                val spent = System.currentTimeMillis() - tickStart
                if (5000L - spent > 0) {
                    try { Thread.sleep(5000L - spent) } catch (e: InterruptedException) { break }
                }
            }
        } catch (e: Exception) {
            // A failed tick must never kill the service; the session is
            // finished from its recorded samples below.
            try { History.addLog(this@MonitorService, "خطا در مانیتورینگ گیمینگ: ${e.javaClass.simpleName}") } catch (_: Exception) {}
        }
        finishSession(sessionId, host, profile, region, allSamples.toList())
    }

    private fun startGaming(profile: String, region: String, host: String, durationMin: Int) {
        // No duplicate sessions: a start while one runs is ignored. A
        // stale (finished) loop can never leave gamingActive stuck: the
        // loop clears it in finishSession's finally.
        if (gamingActive) {
            if (gamingFuture?.isDone == true) { gamingActive = false; gamingSessionId = -1L; gamingFuture = null } else return
        }
        finishStaleSessions()
        val id = try { HistoryEngine.startGamingSession(this, profile, region, host) } catch (e: Exception) { -1L }
        if (id < 0) return
        gamingProfile = profile; gamingRegion = region; gamingHost = host
        gamingDurationMin = durationMin
        gamingSessionId = id
        gamingEndsAtMs = System.currentTimeMillis() + Gaming.durationToMs(durationMin)
        gamingStopRequested = false
        gamingActive = true
        updateGamingNotification(0.0, 0.0, Gaming.durationToMs(durationMin))
        gamingFuture = NetExecutor.submit { gamingLoopTask().run() }
    }

    private fun stopGamingSession() {
        gamingStopRequested = true
        NetExecutor.cancel(gamingFuture)
        // The loop task finishes the session from its recorded samples on
        // exit; nothing more to do here (no second finisher).
    }

    // ---- Intelligence Core (Phase 1): service-owned analysis rounds ----

    private fun aiNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, AiActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }, piFlags())
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "netwatch").setContentTitle("NetWatch — هوشمند").setContentText(text).setSmallIcon(R.drawable.netwatch_icon).setContentIntent(open).build()
        } else {
            Notification.Builder(this).setContentTitle("NetWatch — هوشمند").setContentText(text).setSmallIcon(R.drawable.netwatch_icon).setContentIntent(open).build()
        }
    }

    private fun updateAiNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, aiNotification(text))
        } catch (e: Exception) {}
    }

    // One analysis round at a time: a second start while one runs is
    // ignored (the Activity disables its button from MonitorService.aiBusy
    // too). The round runs on the shared executor; leaving any screen
    // never stops it — the result is in AiStore and shows on return.
    private fun startAiRound(kind: String, question: String?, parentRequestId: String?, providerId: String) {
        if (aiBusy) return
        aiBusy = true
        updateAiNotification("در حال تحلیل…")
        aiFuture = NetExecutor.submit {
            var done = false
            try {
                val k = if (kind == "question") AiCore.AiKind.QUESTION else AiCore.AiKind.ANALYSIS
                val round = AiCore.startRound(this@MonitorService, k, question, parentRequestId, providerId, MonitorService.running)
                aiRoundId = round?.requestId
                if (round == null) {
                    updateAiNotification("تحلیل انجام نشد — دوباره تلاش کن.")
                } else if (providerId == AiCore.PROVIDER_LOCAL) {
                    done = true
                    updateAiNotification("تحلیل آماده است — برای دیدن نتیجه ضربه بزن.")
                } else {
                    // Phase 2: the local round goes through the gateway
                    // (Share by default). Pending = the file is ready and
                    // the user sends it from AiActivity.
                    val sent = AiCore.startProviderRound(this@MonitorService, round, providerId)
                    aiRoundId = sent.requestId
                    when (sent.status) {
                        AiCore.STATUS_PENDING -> {
                            done = true
                            updateAiNotification("بسته آماده است — «ارسال به Muse» را در صفحه هوشمند بزن.")
                        }
                        AiCore.STATUS_ANSWERED -> {
                            done = true
                            updateAiNotification("تحلیل آماده است — برای دیدن نتیجه ضربه بزن.")
                        }
                        else -> updateAiNotification("ارسال به Muse ناموفق بود${if (sent.error != null) ": ${sent.error}" else ""} — تحلیل محلی در صفحه هوشمند هست.")
                    }
                }
            } catch (e: Exception) {
                try { History.addLog(this@MonitorService, "خطا در دور AI: ${e.javaClass.simpleName}") } catch (_: Exception) { }
                updateAiNotification("تحلیل انجام نشد — دوباره تلاش کن.")
            } finally {
                aiBusy = false; aiFuture = null
                // On failure/cancel the monitor notification comes back; on
                // success the "ready" notification stays (see above). A
                // running gaming session re-posts its own notification on
                // the next 5s tick either way.
                if (!done && !gamingActive) restoreMonitorNotification()
            }
        }
    }

    private fun cancelAiRound() {
        NetExecutor.cancel(aiFuture)
        aiRoundId?.let { try { AiGateway.cancel(it) } catch (_: Exception) { } }
        aiBusy = false; aiFuture = null; aiRoundId = null
        if (!gamingActive) restoreMonitorNotification()
    }

    override fun onCreate() {
        super.onCreate()
        History.ensureRecovered(this)
        running = true
        MonitoringEngine.setServiceRunning(true)
        val iv = History.intervalSec(this)
        handler.post(liveLoop)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("netwatch", "NetWatch monitoring", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        startForeground(1, monitorNotification())
        handler.post(loop)
        // v2.9.9 recovery: close any session a killed process left open.
        try { finishStaleSessions() } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START_GAMING -> {
                val profile = intent.getStringExtra("profile") ?: Gaming.profiles[0].name
                val region = intent.getStringExtra("region") ?: Gaming.regions[0].name
                val host = intent.getStringExtra("host") ?: Gaming.regions[0].hosts[0]
                val durationMin = intent.getIntExtra("durationMin", 1)
                startGaming(profile, region, host, durationMin)
                return START_STICKY
            }
            ACTION_STOP_GAMING -> { stopGamingSession(); return START_STICKY }
            ACTION_AI_ANALYZE -> {
                val kind = intent.getStringExtra("kind") ?: "analysis"
                val question = intent.getStringExtra("question")
                val parent = intent.getStringExtra("parentRequestId")
                val provider = intent.getStringExtra("provider") ?: AiCore.PROVIDER_LOCAL
                startAiRound(kind, question, parent, provider)
                return START_STICKY
            }
            ACTION_AI_CANCEL -> { cancelAiRound(); return START_STICKY }
            else -> return START_STICKY
        }
    }

    private fun alert(title: String, text: String) {
        if (!History.alertsOn(this)) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("netwatch-alert", "NetWatch alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "netwatch-alert").setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.netwatch_icon).build()
            else Notification.Builder(this).setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.netwatch_icon).build()
            nm.notify(2, n)
        } catch (e: Exception) {}
    }
    override fun onDestroy() {
        handler.removeCallbacks(loop); handler.removeCallbacks(liveLoop)
        // Intelligence Core: an analysis in flight is cancelled (its
        // NetExecutor task ends); a stored round is never deleted.
        NetExecutor.cancel(aiFuture)
        aiBusy = false; aiFuture = null; aiRoundId = null
        // v2.9.9: an active gaming session is closed from its recorded
        // samples (interrupted), never lost. The loop task is cancelled
        // first; tryBeginFinish guarantees exactly one finisher.
        gamingStopRequested = true
        NetExecutor.cancel(gamingFuture)
        try { finishStaleSessions() } catch (e: Exception) {}
        gamingActive = false; gamingSessionId = -1L; gamingFuture = null
        MonitoringEngine.cancelCheck(); MonitoringEngine.cancelLivePing()
        MonitoringEngine.setServiceRunning(false)
        running = false; super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
