package com.aksell.netwatch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

// Network Engine — the single public API for every network measurement in the
// app. The actual measurement code lives in its workers (Diagnostics for
// sockets/processes, Probes for probe definitions, Gaming for ping bursts);
// this object is the boundary: one place for method labels, timeouts and
// single-flight guards, so no caller can run the same measurement twice or
// mix ICMP and TCP results.
//
// Design decisions (deliberate, see the v2.7 architecture report):
// - No coroutines dependency: NetExecutor (fixed pool + Future cancellation)
//   already provides the required timeout/cancellation semantics. Adding
//   kotlinx-coroutines would grow the APK for no functional gain.
// - No new threads are created here: everything runs on NetExecutor.
// - Every function is bounded: the workers kill processes and close sockets
//   on timeout; nothing here can block forever.
object NetworkEngine {

    // How a ping was measured. ICMP and TCP results are never mixed: the
    // method travels with the result (PingResult.host carries "(TCP)").
    enum class Method { ICMP, TCP }

    // Single-flight registry: one in-flight measurement per key. A second
    // request for the same key is refused instead of stacking a duplicate.
    //
    // v2.9 state machine (QUEUED / RUNNING / DONE): the v2.8 bug was in
    // cancelSingle — it removed the entry FIRST and then cancelled, so a new
    // task could start while the old one (ignoring interrupts) was still
    // running. Now the running task OWNS its key until its finally really
    // runs:
    // - QUEUED (submitted, pool thread has not started it yet): cancel
    //   removes the entry first, then cancels. A cancelled FutureTask never
    //   runs, so nothing leaks and nothing can start under this key after.
    // - RUNNING: cancel only interrupts; the entry STAYS until the task's
    //   finally removes it. A new submit is refused meanwhile, so no second
    //   task can ever overlap the old one — even if the old task ignores
    //   interrupts. (Every operation under a key has a bounded timeout, so
    //   the key cannot be held forever: worst case ~2 min pathological.)
    // - DONE: the task finished; its finally is about to remove the entry.
    // All state reads/writes are synchronized on the entry itself, so the
    // check-and-act in cancelSingle is atomic against the task's own
    // QUEUED -> RUNNING -> DONE transitions. Removals are value-checked
    // (remove(key, entry)) so a finished task can never delete a newer
    // task's entry.
    private enum class FlightState { QUEUED, RUNNING, DONE }

    private class FlightEntry(val ft: java.util.concurrent.FutureTask<Unit>) {
        @Volatile var state: FlightState = FlightState.QUEUED
    }

    private val inFlight = ConcurrentHashMap<String, FlightEntry>()

    // TEST-ONLY hooks (v2.9.1): let the race test force the exact
    // interleaving — Callable entered, cancel removes the entry, Callable
    // proceeds. The Callable waits on testPreRunGate (when set) BEFORE the
    // ownership check and signals testCallableEntered when it gets there.
    // Never set in production code; a volatile read + null check per task
    // costs nothing when unused.
    // v2.9.2: the gate is scoped to ONE key (testGateKey). Only the
    // Callable whose key equals it waits at the gate and signals entry;
    // every other Callable skips the gate entirely.
    @Volatile
    var testGateKey: String? = null
    @Volatile
    var testPreRunGate: java.util.concurrent.CountDownLatch? = null
    @Volatile
    var testCallableEntered: java.util.concurrent.atomic.AtomicBoolean? = null

    // Returns the Future when the task was accepted, null when the same key
    // is already QUEUED or RUNNING. Callers keep the Future to cancel it
    // (Stop button, onDestroy); the registry entry is removed by the task's
    // finally when it really finishes.
    fun submitSingle(key: String, task: () -> Unit): java.util.concurrent.Future<*>? {
        while (true) {
            lateinit var entry: FlightEntry
            lateinit var ft: java.util.concurrent.FutureTask<Unit>
            ft = java.util.concurrent.FutureTask<Unit>({
                // TEST-ONLY gate (v2.9.2): scoped to testGateKey. Only the
                // Callable whose key equals testGateKey waits here BEFORE
                // the ownership check and signals testCallableEntered.
                // Any other Callable (background check, service, live
                // ping) skips the gate entirely: it can neither trip the
                // test nor be delayed by it. Interrupted while waiting =
                // cancelled before start: exit without running the task.
                val gateOk = if (testGateKey == key) {
                    try {
                        testCallableEntered?.set(true)
                        testPreRunGate?.await()
                        true
                    } catch (e: InterruptedException) {
                        false
                    }
                } else true
                if (!gateOk) {
                    synchronized(entry) { entry.state = FlightState.DONE }
                    inFlight.remove(key, entry)
                } else {
                // v2.9.1 — ownership check (fixes a real race the v2.9 review
                // found): FutureTask.run() may have passed its NEW check and
                // entered this Callable while cancelSingle was removing the
                // entry. In that window cancelSingle sees QUEUED, removes
                // the entry and cancels — but the Callable is already inside
                // run() and would still execute task() after the lock is
                // released, overlapping a new task under the same key.
                // So the QUEUED -> RUNNING transition happens ONLY while
                // this entry is still the registered one for the key.
                // The check and cancelSingle's removal are serialized on
                // the same entry monitor, so exactly one of them wins.
                val ownsKey = synchronized(entry) {
                    if (inFlight[key] === entry) {
                        entry.state = FlightState.RUNNING
                        true
                    } else false
                }
                if (ownsKey) {
                    try { task() } finally {
                        synchronized(entry) { entry.state = FlightState.DONE }
                        inFlight.remove(key, entry)
                    }
                } else {
                    // Cancelled while queued (or replaced) — the task must
                    // NEVER run. Mark DONE so isRunning stays consistent.
                    synchronized(entry) { entry.state = FlightState.DONE }
                    inFlight.remove(key, entry)
                }
                }
            }, Unit)
            entry = FlightEntry(ft)
            val prev = inFlight.putIfAbsent(key, entry)
            if (prev == null) {
                NetExecutor.execute(ft)
                return ft
            }
            // The previous entry finished but its finally has not removed it
            // yet — replace it only if it is DONE and still the same entry.
            val prevDone = synchronized(prev) { prev.state == FlightState.DONE }
            if (prevDone && inFlight.replace(key, prev, entry)) {
                NetExecutor.execute(ft)
                return ft
            }
            if (prevDone) continue // lost the replace race — retry the claim
            return null // same key QUEUED or RUNNING — refused
        }
    }

    fun cancelSingle(key: String) {
        val entry = inFlight[key] ?: return
        synchronized(entry) {
            if (entry.state == FlightState.QUEUED) {
                val removed = inFlight.remove(key, entry)
                if (removed) entry.ft.cancel(true)
                return
            }
            if (entry.state == FlightState.RUNNING) {
                // Interrupt only — the entry stays until the task's
                // finally removes it, so no new task can overlap it.
                entry.ft.cancel(true)
                return
            }
            inFlight.remove(key, entry)
        }
    }

    // True while a task is QUEUED or RUNNING — including the wind-down after
    // a cancel, until the task really finishes (v2.8 returned false right
    // after cancel, because FutureTask.isDone() is true once cancelled).
    fun isRunning(key: String): Boolean {
        val entry = inFlight[key] ?: return false
        return synchronized(entry) { entry.state != FlightState.DONE }
    }

    fun pingMissing(): Boolean = Diagnostics.pingMissing

    // Real ICMP ping burst. Returns an empty result (received=0, loss=100%)
    // when nothing answers — never a fake 0ms.
    fun ping(host: String, count: Int = 20): PingResult = Gaming.pingHost(host, count)

    // TCP connect-time burst, used ONLY when ICMP is blocked. The result is
    // labelled "(TCP)" in PingResult.host so the UI can never present it as
    // an ICMP ping.
    fun tcpPing(host: String, port: Int = 443, count: Int = 10): PingResult =
        Gaming.tcpPing(host, port, count)

    // One-shot ping statistics (avg/min/max/jitter/loss) for the dashboard.
    // Bounded by Diagnostics.pingLines; never returns a fake 0ms.
    fun pingStats(host: String): PingStats = Diagnostics.pingStats(host)

    // Single ping, null when unanswered (used by the live-ping loop).
    fun pingOnce(host: String = "1.1.1.1"): Double? = Diagnostics.pingOnce(host)

    fun tcp(host: String, port: Int): Timed = Diagnostics.tcpTime(host, port)
    fun tls(host: String, port: Int = 443): Timed = Diagnostics.tlsTime(host, port)

    // DNS the honest way: a real query to a resolver. An IP literal needs no
    // resolution and returns ok=false with an "N/A" note — never 0ms success.
    fun dns(host: String, resolver: String = "1.1.1.1"): Timed = Diagnostics.dnsTime(host, resolver)
    fun udpDns(serverIp: String, hostname: String = "google.com"): Timed = Diagnostics.udpDns(serverIp, hostname)

    // Real DNS-over-TLS: TLS handshake to :853, then a DNS query over the TLS
    // stream with the response validated. Not a bare TCP connect.
    fun dot(serverIp: String, hostname: String = "google.com"): Timed = Diagnostics.dotQuery(serverIp, hostname)

    // QUIC: no valid handshake exists in this app, so the status stays
    // Unknown with a reason — never a fake Success.
    fun quic(host: String): Diagnostics.QuicResult = Diagnostics.quicStatus(host)

    // Full probe set (40+, manual only) or the light set (8 probes, for the
    // timer and the background service). One call, one policy.
    fun runProbes(light: Boolean): List<ProbeResult> =
        if (light) Probes.runLight() else Probes.runAll()

    // Route diagnostics (best-effort on a non-root phone; "unavailable" is
    // reported honestly when the ping binary cannot do it).
    fun traceroute(host: String = "8.8.8.8"): String = Diagnostics.traceroute(host)
    fun mtuTest(host: String = "1.1.1.1"): String = Diagnostics.mtuTest(host)

    // DNS-over-HTTPS answers for one hostname, from one DoH endpoint.
    fun dohIps(dohUrl: String, host: String): List<String> = Diagnostics.dohIps(dohUrl, host)

    fun summarize(results: List<ProbeResult>, filtering: String): Probes.Summary =
        Probes.summarize(results, filtering)

    fun networkInfo(ctx: android.content.Context): String = NetworkInfo.summary(ctx)
}
