package com.aksell.netwatch

import android.content.Context
import java.util.concurrent.Future

// Monitoring Engine — owns the *policy* of when measurements run. The
// Android mechanics stay where Android requires them (MainActivity's 1s tick
// Handler, MonitorService's Foreground Service loops), but every decision —
// "should a check run now?", "is one already running?", "is the Live state
// fresh?" — is answered here, in one place, with pure testable functions.
// v2.8: the guard itself moved into NetworkEngine.submitSingle (one key,
// "check", for every check path). This engine keeps only the policy
// (pure time math) on top of it. Before v2.8 MainActivity kept its own
// `checking` boolean while the Service used this engine's AtomicBoolean —
// two guards that could disagree, so a manual check and a background cycle
// could run at the same time.
//
// Cadence policy (battery/data):
// - Full check (40+ probes): manual only, or an explicit user schedule.
// - Light check (8 probes): timer + background service, at the user interval.
// - Live ping: one real ICMP ping every 5s, only while monitoring runs and
//   the user switched it on. Never two live pings on top of each other.
object MonitoringEngine {

    // ONE shared guard for every check path (manual button, auto timer,
    // background service). v2.7 had two guards that could disagree:
    // MainActivity kept its own `checking` boolean while the Service used
    // this engine's AtomicBoolean — a manual check and a background cycle
    // could run at the same time and measure twice. Now there is a single
    // key ("check") in NetworkEngine.submitSingle, and this engine is only
    // the policy layer on top of it. The guard needs no finally: the entry
    // is removed by submitSingle itself on completion, and cancelCheck()
    // removes it on cancellation. A double release is impossible by design.
    const val CHECK_KEY = "check"
    const val LIVE_KEY = "live-ping"

    // Runs the check only if no check is running; returns the Future, or
    // null when a check is already in flight (caller skips or cancels first).
    fun runCheck(task: () -> Unit): Future<*>? = NetworkEngine.submitSingle(CHECK_KEY, task)
    fun cancelCheck() = NetworkEngine.cancelSingle(CHECK_KEY)
    fun isCheckRunning(): Boolean = NetworkEngine.isRunning(CHECK_KEY)

    // Live ping has its own key (it is not a check) but is still single-flight.
    fun runLivePing(task: () -> Unit): Future<*>? = NetworkEngine.submitSingle(LIVE_KEY, task)
    fun cancelLivePing() = NetworkEngine.cancelSingle(LIVE_KEY)
    fun isLiveRunning(): Boolean = NetworkEngine.isRunning(LIVE_KEY)

    // Pure policy: enough time passed since the last cycle AND monitoring
    // is wanted. JVM-testable, no Context needed.
    // v2.9.8 — the old version also checked isCheckRunning() here, so it
    // was NOT pure despite this comment: on a device with a check in
    // flight it returned false for a pure time question, and the LAB test
    // failed for a reason that had nothing to do with the policy. The
    // single-flight guard belongs at the call site (runCheck refuses a
    // second check); the LAB test now returns NOT TESTED when the guard
    // is busy instead of failing the policy. No production caller used
    // this function (verified: only Lab tests call it).
    fun shouldRunCycle(lastCycleMs: Long, nowMs: Long, intervalSec: Int, wantMonitoring: Boolean): Boolean {
        if (!wantMonitoring) return false
        if (intervalSec <= 0) return false
        return nowMs - lastCycleMs >= intervalSec * 1000L
    }

    // Pure policy: a live ping is due only when monitoring runs, the user
    // switched live ping on, the interval elapsed, and no live ping is in
    // flight (a slow ping must never stack another one on top of it).
    fun livePingDue(lastLiveMs: Long, nowMs: Long, monitoringOn: Boolean, liveOn: Boolean): Boolean {
        if (!monitoringOn || !liveOn) return false
        if (isLiveRunning()) return false
        return nowMs - lastLiveMs >= 5000L
    }

    // The "Live" pill is honest only when the service's heartbeat is fresh:
    // a real measurement landed in the last 15 seconds. A switch left on
    // after the service died never shows Live.
    fun heartbeatFresh(ctx: Context): Boolean =
        History.livePingOn(ctx) && System.currentTimeMillis() - History.lastHeartbeat(ctx) < 15_000

    fun livePingOn(ctx: Context): Boolean = History.livePingOn(ctx)
    fun setLivePing(ctx: Context, on: Boolean) = History.setLivePing(ctx, on)

    // The background service reports its state here (set in onCreate /
    // onDestroy). The engine never imports the Service class — the dependency
    // goes one way only: Service -> Engine. The widget and the UI read the
    // state through here, not through a static flag of their own.
    @Volatile private var serviceRunningFlag = false
    fun setServiceRunning(v: Boolean) { serviceRunningFlag = v }
    fun serviceRunning(): Boolean = serviceRunningFlag
}
