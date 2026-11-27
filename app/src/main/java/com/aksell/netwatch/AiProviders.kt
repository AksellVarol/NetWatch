package com.aksell.netwatch

import android.content.Context
import org.json.JSONObject

// ShareProvider — Muse via the Android Share loop (Phase 2, the first
// real connection; the development-time bridge until NetWatch is verified
// working correctly).
//
// How a round works:
// 1. AiCore builds the sanitized AiPacket (local analysis).
// 2. This provider writes the readable text + JSON into ONE .txt file in
//    Downloads/NetWatch (ExportHelper — same pattern as the Bridge page)
//    and returns Pending with the file Uri.
// 3. AiActivity launches the chooser (ACTION_SEND, text/plain) — the user
//    picks the Muse app. Nothing is sent automatically.
// 4. Muse's reply comes back with Long-press -> Share to NetWatch;
//    AiActivity hands the text to AiCore.receiveReply, which stores it.
// 5. The reply is usually rendered chat text, not raw JSON: AiCore
//    stores it with parse_failed=true and verdict "unknown" when no JSON
//    verdict is found — never a verdict that was not in the reply.
//
// No key, no server, no new permission. The packet is already sanitized
// by AiCore; the text is sanitized once more here (idempotent).
object ShareProvider : AiProvider, ContextAwareProvider {
    override val id: String = AiCore.PROVIDER_SHARE
    override val displayName: String = "Muse — اشتراک‌گذاری"
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null

    fun shareFileBase(requestId: String): String = "NETWATCH_AI_$requestId"

    /** Readable text for the .txt file: header + local verdict + findings
     *  with their exact locations + the full packet JSON. Pure. */
    fun buildShareText(packet: JSONObject): String {
        val ai = packet.optJSONObject("ai")
        val sb = StringBuilder()
        sb.append("NETWATCH — AI Analysis (for Muse)\n")
        sb.append("درخواست: ${ai?.optString("request_id", "—") ?: "—"}\n")
        sb.append("این یک گزارش تشخیصی Sanitize‌شده است (بدون IP، SSID، MAC، DNS، رمز یا توکن). ")
        sb.append("لطفاً فقط یک JSON با همین کلیدها برگردان: request_id (همین شناسه)، verdict (ok | degraded | outage | unknown)، cause (علت احتمالی به فارسی)، fix (راهکار به فارسی)، confidence (۰ تا ۱۰۰)، evidence (فهرست شواهد).\n\n")
        val cls = ai?.optJSONObject("local_classification")
        sb.append("حکم محلی: ${ai?.optString("local_verdict", "—") ?: "—"} • طبقه‌بندی: ${cls?.optString("type", "—") ?: "—"} (اطمینان ${cls?.optInt("confidence", -1) ?: -1}٪)\n")
        val pr = ai?.optJSONObject("probes")
        if (pr != null) {
            sb.append("پروب‌ها: ${pr.optInt("ok", 0)} از ${pr.optInt("total", 0)} موفق (داخلی ${pr.optInt("domestic_ok_pct", 0)}٪، بین‌الملل ${pr.optInt("intl_ok_pct", 0)}٪)\n")
        }
        val q = if (ai?.isNull("question") == false) ai.optString("question", "") else ""
        if (q.isNotBlank()) sb.append("سوال کاربر: $q\n")
        val fa = ai?.optJSONArray("findings")
        sb.append("\n== یافته‌ها ==\n")
        if (fa == null || fa.length() == 0) {
            sb.append("یافتهٔ مشخصی نیست.\n")
        } else {
            for (i in 0 until fa.length()) {
                val fo = fa.optJSONObject(i) ?: continue
                sb.append("${i + 1}. [${fo.optString("module", "—")}] ${fo.optString("title", "—")}\n")
                sb.append("   ${fo.optString("detail", "—")}\n")
                val loc = if (!fo.isNull("file")) "${fo.optString("file")}:${fo.optInt("line", -1)} • ${fo.optString("function", "—")}"
                else "بدون فایل/خط (شواهد فایل ندارد)"
                sb.append("   مکان: $loc\n")
                sb.append("   شواهد: ${fo.optString("evidence", "—")}\n")
            }
        }
        sb.append("\n--- JSON ---\n")
        sb.append(packet.toString(2))
        return Bridge.sanitize(sb.toString())
    }

    // The architecture's interface has no Context: without one this
    // provider cannot write the share file, so it fails honestly instead
    // of pretending. The gateway calls sendWithContext on the device.
    override fun send(request: AiCore.AiRequest): AiResult {
        return AiResult.Failed(AiError("share_failed", "ShareProvider بدون Context اندروید نمی‌تواند فایل بسازد", 0, false))
    }

    /** The EXTRA_TEXT for the share intent (2.10.1 fix): the same readable
     *  text as the .txt file, rebuilt from the round's stored packet JSON.
     *  Pure — null when the stored JSON is missing or malformed, so the
     *  caller can fall back to a minimal text instead of failing. */
    fun shareTextForRound(packetJson: String): String? {
        if (packetJson.isBlank()) return null
        return try {
            buildShareText(JSONObject(packetJson))
        } catch (_: Exception) { null }
    }

    override fun sendWithContext(ctx: Context, request: AiCore.AiRequest): AiResult {
        val text = buildShareText(request.packet)
        val res = try {
            ExportHelper.save(ctx, shareFileBase(request.requestId), "txt", "text/plain") { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            return AiResult.Failed(AiError("share_failed", "ذخیرهٔ فایل اشتراک ناموفق بود: ${e.javaClass.simpleName}", 0, false))
        }
        if (!res.ok || res.uri == null) {
            return AiResult.Failed(AiError("share_failed", "ذخیرهٔ فایل اشتراک ناموفق بود${if (res.error.isNotBlank()) ": ${res.error}" else ""}", 0, false))
        }
        return AiResult.Pending(request.requestId, "فایل آماده است — «ارسال به Muse» را بزن و اپ Muse را انتخاب کن.", res.uri.toString())
    }

    // The reply arrives through the Android share intent, not a file —
    // there is nothing to poll. AiCore.receiveReply handles the return.
    override fun poll(roundId: String): AiResult? = null

    override fun cancel(roundId: String) {
        // Nothing to cancel: the prepared file stays in Downloads/NetWatch.
    }

    // ================= JVM self-tests (pure only) =================

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "AI Share — ورودی ثابت، بدون شبکه/دیتابیس", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        val packet = JSONObject()
        packet.put("schema_version", 1)
        val ai = JSONObject()
        ai.put("request_id", "r_20261112_033000_7f3a")
        ai.put("kind", "analysis")
        ai.put("local_verdict", "degraded")
        val cls = JSONObject()
        cls.put("type", "احتمالی: مشکل Gateway")
        cls.put("confidence", 60)
        ai.put("local_classification", cls)
        val pr = JSONObject()
        pr.put("ok", 18); pr.put("total", 41); pr.put("domestic_ok_pct", 83); pr.put("intl_ok_pct", 37)
        ai.put("probes", pr)
        val fa = org.json.JSONArray()
        val f0 = JSONObject()
        f0.put("module", "NETWORK"); f0.put("title", "DoH مسدود است"); f0.put("detail", "DoH جواب نمی‌دهد")
        f0.put("file", JSONObject.NULL); f0.put("line", JSONObject.NULL); f0.put("function", JSONObject.NULL)
        f0.put("evidence", "failed DoH: cfdns, gdns")
        fa.put(f0)
        ai.put("findings", fa)
        packet.put("ai", ai)
        val txt = buildShareText(packet)
        t("AI Share — متن اشتراک شامل شناسه و حکم و یافته و JSON است", "has request_id + verdict + finding + JSON", "has=${txt.contains("r_20261112_033000_7f3a") && txt.contains("degraded") && txt.contains("DoH مسدود است") && txt.contains("\"request_id\"")}",
            txt.contains("r_20261112_033000_7f3a") && txt.contains("degraded") && txt.contains("DoH مسدود است") && txt.contains("\"request_id\""))
        t("AI Share — متن اشتراک از Muse JSON با همان شناسه می‌خواهد", "asks for request_id + verdict + cause + fix", "has=${txt.contains("request_id") && txt.contains("verdict") && txt.contains("cause") && txt.contains("fix")}",
            txt.contains("request_id") && txt.contains("verdict") && txt.contains("cause") && txt.contains("fix"))
        t("AI Share — نام فایل", "NETWATCH_AI_<requestId>", "base=${shareFileBase("r_20261112_033000_7f3a")}",
            shareFileBase("r_20261112_033000_7f3a") == "NETWATCH_AI_r_20261112_033000_7f3a")
        val req = AiCore.AiRequest("r_20261112_033000_7f3a", AiCore.AiKind.ANALYSIS, null, packet, null)
        val noCtx = send(req)
        t("AI Share — بدون Context صادقانه خطا می‌دهد", "Failed share_failed", "res=$noCtx",
            noCtx is AiResult.Failed && (noCtx as AiResult.Failed).error.code == "share_failed")
        t("AI Share — Poll همیشه null است (پاسخ با Intent برمی‌گردد)", "null", "poll=${poll("r_20261112_033000_7f3a")}", poll("r_20261112_033000_7f3a") == null)
        // 2.10.1 fix: EXTRA_TEXT is rebuilt from the round's stored packet
        // JSON — the exact code path AiActivity.sharePending() uses.
        val roundText = shareTextForRound(packet.toString())
        t("AI Share — shareTextForRound از packetJson همان متن فایل را می‌سازد", "has request_id + verdict + finding + JSON", "has=${roundText?.contains("r_20261112_033000_7f3a") == true && roundText.contains("degraded") && roundText.contains("DoH مسدود است") && roundText.contains("\"request_id\"")}",
            roundText?.contains("r_20261112_033000_7f3a") == true && roundText.contains("degraded") && roundText.contains("DoH مسدود است") && roundText.contains("\"request_id\""))
        t("AI Share — shareTextForRound با JSON خراب null می‌دهد", "null", "res=${shareTextForRound("{broken json")}", shareTextForRound("{broken json") == null)
        t("AI Share — shareTextForRound با رشته خالی null می‌دهد", "null", "res=${shareTextForRound("")}", shareTextForRound("") == null)
        return out
    }
}
