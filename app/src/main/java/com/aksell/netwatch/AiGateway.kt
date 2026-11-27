package com.aksell.netwatch

import android.content.Context
import java.util.concurrent.Future

// AI Gateway (architecture §3) — the independent bridge between AiCore
// and the providers. It owns: the provider registry, the active-provider
// choice, Timeout / Retry / Backoff, the in-app daily cap, execution on
// NetExecutor, and cancellation. It never analyses and never builds a
// packet — that is AiCore's job.
//
// Policy numbers (architecture §3-2):
// - HTTP: 10s connect / 30s read / 45s total, max 3 attempts (1 + 2),
//   backoff 2s then 5s. Retry only on IO/timeout/429/5xx — never on
//   400/401/403 (those need the user: wrong key, Gemini 403).
// - In-app daily cap: 40 analyses/day (under OpenRouter's free 50/day).
//   Share/Drive are user-paced and have no API quota, so the cap applies
//   only to HTTP providers.
// - One round at a time: a second send while one runs is refused
//   ("busy") — no queue, no parallel calls (quota + battery).
object AiGateway {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000
    const val TOTAL_TIMEOUT_MS = 45_000L
    const val MAX_ATTEMPTS = 3
    const val DAILY_CAP = 40

    // Backoff per retry: after attempt 1 wait 2s, after attempt 2 wait 5s;
    // attempt 3 is the last one (0 = no more retries). Pure.
    fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 2000L
        2 -> 5000L
        else -> 0L
    }

    /** Retryable error codes only. 400/401/403/parse/provider problems
     *  need the user, not another attempt. Pure. */
    fun shouldRetry(code: String): Boolean {
        return code == "timeout" || code == "http_429" || code == "network" ||
            code == "io" || code.startsWith("http_5")
    }

    /** In-app daily cap (HTTP providers only). Pure. */
    fun dailyCapReached(countToday: Int): Boolean = countToday >= DAILY_CAP

    /** Provider availability today. Pure — the caller passes the real
     *  state (Drive connected? key entered?). Phase 2: only Share is
     *  available; Drive needs Phase 3, OpenRouter Phase 4, Gemini is
     *  403-blocked (Phase 5). */
    fun availability(id: String, driveConnected: Boolean, openRouterKeySet: Boolean): Pair<Boolean, String?> {
        return when (id) {
            AiCore.PROVIDER_SHARE -> true to null
            AiCore.PROVIDER_DRIVE -> if (driveConnected) true to null
                else false to "Drive وصل نیست — فاز ۳ (صندوق Drive) هنوز ساخته نشده است"
            AiCore.PROVIDER_OPENROUTER -> if (openRouterKeySet) true to null
                else false to "کلید OpenRouter در اپ وارد نشده — فاز ۴"
            AiCore.PROVIDER_GEMINI -> false to "Gemini فعلاً 403 است («سهمیه رایگان برای این پروژه در دسترس نیست») — فاز ۵ (مسدود)"
            else -> false to "ارائه‌دهنده ناشناخته: $id"
        }
    }

    /** Default provider: Drive when connected, else Share (works today). */
    fun defaultProviderId(driveConnected: Boolean): String =
        if (driveConnected) AiCore.PROVIDER_DRIVE else AiCore.PROVIDER_SHARE

    fun providers(): List<AiProvider> = listOf(ShareProvider)

    fun providerById(id: String): AiProvider? = providers().firstOrNull { it.id == id }

    // ---- one-round-at-a-time guard ----
    @Volatile private var busyRoundId: String? = null
    private val activeFutures = HashMap<String, Future<*>?>()

    fun isBusy(): Boolean = busyRoundId != null

    /** Blocking send with the retry loop. Runs on the caller's thread —
     *  the service calls it from a NetExecutor task. Returns the first
     *  Answered/Pending, or the last Failed after the retries. */
    fun sendSync(ctx: Context?, providerId: String, request: AiCore.AiRequest): AiResult {
        val provider = providerById(providerId)
            ?: return AiResult.Failed(AiError("provider_disabled", "ارائه‌دهنده ناشناخته: $providerId", 0, false))
        val (ok, reason) = availability(providerId, driveConnected = false, openRouterKeySet = false)
        if (!ok || !provider.isAvailable) {
            return AiResult.Failed(AiError("provider_disabled", reason ?: "ارائه‌دهنده در دسترس نیست", 0, false))
        }
        var attempt = 0
        var lastError: AiError? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val result = try {
                if (ctx != null && provider is ContextAwareProvider) provider.sendWithContext(ctx, request)
                else provider.send(request)
            } catch (e: Exception) {
                AiResult.Failed(AiError("network", "خطای داخلی دروازه: ${e.javaClass.simpleName}", attempt, true))
            }
            when (result) {
                is AiResult.Answered -> return result
                is AiResult.Pending -> return result
                is AiResult.Failed -> {
                    lastError = result.error
                    if (!shouldRetry(result.error.code) || attempt >= MAX_ATTEMPTS) {
                        return AiResult.Failed(result.error.copy(attempts = attempt))
                    }
                    val wait = backoffMs(attempt)
                    if (wait > 0) {
                        try { Thread.sleep(wait) } catch (_: InterruptedException) {
                            return AiResult.Failed(result.error.copy(attempts = attempt))
                        }
                    }
                }
            }
        }
        return AiResult.Failed(lastError?.copy(attempts = attempt)
            ?: AiError("network", "بدون پاسخ", attempt, false))
    }

    /** Async send on NetExecutor. Returns false when another round is
     *  already running (the caller keeps its local round — nothing is
     *  lost). The result callback runs on the executor thread. */
    fun sendAsync(ctx: Context?, providerId: String, request: AiCore.AiRequest, onResult: (AiResult) -> Unit): Boolean {
        synchronized(activeFutures) {
            if (busyRoundId != null) return false
            busyRoundId = request.requestId
        }
        val f = NetExecutor.submit {
            try {
                val r = sendSync(ctx, providerId, request)
                onResult(r)
            } finally {
                synchronized(activeFutures) {
                    if (busyRoundId == request.requestId) busyRoundId = null
                    activeFutures.remove(request.requestId)
                }
            }
        }
        synchronized(activeFutures) { activeFutures[request.requestId] = f }
        return true
    }

    /** Cancels the running round (if it is this one) and tells the
     *  provider to stop. The stored round is never deleted. */
    fun cancel(roundId: String) {
        val f = synchronized(activeFutures) { activeFutures[roundId] }
        NetExecutor.cancel(f)
        try { providerById(AiCore.PROVIDER_SHARE)?.cancel(roundId) } catch (_: Exception) { }
        synchronized(activeFutures) {
            if (busyRoundId == roundId) busyRoundId = null
            activeFutures.remove(roundId)
        }
    }

    // ---- daily counter (HTTP providers only; SharedPreferences "ai") ----
    fun countToday(ctx: Context): Int {
        return try {
            val p = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            if (p.getString("ai_count_date", "") != today) 0 else p.getInt("ai_count", 0)
        } catch (_: Exception) { 0 }
    }

    fun bumpToday(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val cur = if (p.getString("ai_count_date", "") != today) 0 else p.getInt("ai_count", 0)
            p.edit().putString("ai_count_date", today).putInt("ai_count", cur + 1).apply()
        } catch (_: Exception) { }
    }

    // ================= JVM self-tests (pure only) =================

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "AI Gateway — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        t("AI Gateway — Backoff", "attempt 1→2000, 2→5000, 3→0", "1→${backoffMs(1)} 2→${backoffMs(2)} 3→${backoffMs(3)}",
            backoffMs(1) == 2000L && backoffMs(2) == 5000L && backoffMs(3) == 0L)
        val retryOk = shouldRetry("timeout") && shouldRetry("http_429") && shouldRetry("http_500") && shouldRetry("http_503") && shouldRetry("network") && shouldRetry("io")
        val retryNo = !shouldRetry("http_400") && !shouldRetry("http_401") && !shouldRetry("http_403") && !shouldRetry("parse") && !shouldRetry("provider_disabled") && !shouldRetry("share_cancelled") && !shouldRetry("share_failed") && !shouldRetry("busy")
        t("AI Gateway — قانون Retry", "retry: timeout/429/5xx/network/io — no retry: 400/401/403/parse/provider/share/busy", "retryOk=$retryOk retryNo=$retryNo", retryOk && retryNo)
        t("AI Gateway — سقف روزانه ۴۰", "39 not reached, 40 reached, 41 reached", "39=${dailyCapReached(39)} 40=${dailyCapReached(40)} 41=${dailyCapReached(41)}",
            !dailyCapReached(39) && dailyCapReached(40) && dailyCapReached(41))
        val (shareOk, _) = availability(AiCore.PROVIDER_SHARE, false, false)
        val (driveOk, driveWhy) = availability(AiCore.PROVIDER_DRIVE, false, false)
        val (orOk, orWhy) = availability(AiCore.PROVIDER_OPENROUTER, false, false)
        val (gemOk, gemWhy) = availability(AiCore.PROVIDER_GEMINI, false, false)
        val (unkOk, _) = availability("nope", false, false)
        t("AI Gateway — در دسترس بودن ارائه‌دهنده‌ها", "only share available today", "share=$shareOk drive=$driveOk($driveWhy) or=$orOk($orWhy) gem=$gemOk($gemWhy) unk=$unkOk",
            shareOk && !driveOk && !orOk && !gemOk && !unkOk && (driveWhy?.contains("فاز ۳") == true) && (orWhy?.contains("فاز ۴") == true) && (gemWhy?.contains("403") == true))
        t("AI Gateway — ارائه‌دهنده پیش‌فرض", "no drive → muse_share; drive → muse_drive", "noDrive=${defaultProviderId(false)} drive=${defaultProviderId(true)}",
            defaultProviderId(false) == AiCore.PROVIDER_SHARE && defaultProviderId(true) == AiCore.PROVIDER_DRIVE)
        return out
    }
}
