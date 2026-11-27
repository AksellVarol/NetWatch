package com.aksell.netwatch

import android.content.Context

// AI Provider contract (architecture §3-1) — the ONLY thing AiCore and
// AiGateway know about a provider. Swapping the model = swapping the
// adapter + settings; the core, the packet, the store and the UI never
// change.
//
// Result is three-state, because file providers (Drive/Share) have no
// instant answer: Answered (now), Pending (the reply comes later) or
// Failed (with a sanitized error code — never a key, header or raw HTTP
// body).
interface AiProvider {
    val id: String            // "muse_drive" | "muse_share" | "openrouter" | "gemini"
    val displayName: String   // display name in settings
    val isAvailable: Boolean  // usable today (key present? Drive connected?)
    val unavailableReason: String? // exact reason when not available (for the UI)

    // Synchronous (blocking) — with its own internal timeout. The gateway
    // runs it on NetExecutor.
    fun send(request: AiCore.AiRequest): AiResult

    // Only for file providers (Drive/Share): check whether a reply has
    // arrived. Returns null for HTTP providers (their answer comes back
    // on the same connection). Share returns null too: its reply arrives
    // through the Android share intent, not a file.
    fun poll(roundId: String): AiResult?

    fun cancel(roundId: String)
}

/** A provider that needs an Android Context (e.g. ShareProvider, which
 *  writes the share file). The base interface stays exactly as the
 *  architecture specifies; the gateway calls this variant when the
 *  provider implements it. */
interface ContextAwareProvider : AiProvider {
    fun sendWithContext(ctx: Context, request: AiCore.AiRequest): AiResult
}

sealed class AiResult {
    data class Answered(val response: AiCore.AiResponse) : AiResult()
    // shareUri: the file Uri of a prepared Share payload (null for Drive).
    data class Pending(val roundId: String, val hint: String, val shareUri: String? = null) : AiResult()
    data class Failed(val error: AiError) : AiResult()
}

data class AiError(
    val code: String,   // timeout | http_429 | http_401 | http_403 | network | parse | drive_no_access | share_cancelled | share_failed | provider_disabled | busy
    val message: String, // sanitized Persian message — never a key, header or raw HTTP error
    val attempts: Int,
    val retryable: Boolean
)
