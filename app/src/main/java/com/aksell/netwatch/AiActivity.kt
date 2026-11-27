package com.aksell.netwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

// AiActivity — the "هوشمند" page (Intelligence Core, Phase 1+2).
// Display only: it renders AI rounds from AiStore (SQLite) and sends
// Intents to MonitorService to start/cancel an analysis. It never
// analyses, never measures and never uploads anything itself.
// - The core lives in the service: leaving this page (or any page) never
//   stops an analysis — the result is in the DB and shows on return.
// - 3s DB poll rendering (same pattern as GamingActivity); onDestroy
//   cancels only the UI poll.
// - No Clipboard use anywhere (same rule as the Bridge page).
// - Phase 2 adds: the Share button (send the packet to the Muse app) and
//   the incoming-share reply handling (ACTION_SEND).
class AiActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvLatest: TextView
    private lateinit var findingsContainer: LinearLayout
    private lateinit var tvFindingsEmpty: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var btnAnalyze: Button
    private lateinit var btnShare: Button
    private lateinit var etQuestion: EditText
    private lateinit var btnAsk: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClear: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var latestRound: AiCore.AiRound? = null
    private var pendingRound: AiCore.AiRound? = null
    private var analyzeBusy = false

    private val uiPoll = object : Runnable {
        override fun run() {
            loadRounds()
            uiHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        tvStatus = findViewById(R.id.tvAiStatus)
        tvLatest = findViewById(R.id.tvAiLatest)
        findingsContainer = findViewById(R.id.aiFindingsContainer)
        tvFindingsEmpty = findViewById(R.id.tvAiFindingsEmpty)
        historyContainer = findViewById(R.id.aiHistoryContainer)
        tvHistoryEmpty = findViewById(R.id.tvAiHistoryEmpty)
        btnAnalyze = findViewById(R.id.btnAiAnalyze)
        btnShare = findViewById(R.id.btnAiShare)
        etQuestion = findViewById(R.id.etAiQuestion)
        btnAsk = findViewById(R.id.btnAiAsk)
        btnRefresh = findViewById(R.id.btnAiRefresh)
        btnClear = findViewById(R.id.btnAiClear)

        btnAnalyze.setOnClickListener { startAnalysis() }
        btnShare.setOnClickListener { sharePending() }
        btnAsk.setOnClickListener { askQuestion() }
        btnRefresh.setOnClickListener { loadRounds() }
        btnClear.setOnClickListener { confirmClear() }
        handleSharedText(intent)
        loadRounds()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    // ---- Phase 2: a reply shared back from the Muse app ----
    // The Muse app shares its answer as text; we store it on the pending
    // round (matched by request id when possible) and never execute it.
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (text.isBlank()) return
        NetExecutor.submit {
            val round = try { AiCore.receiveReply(this, text) } catch (_: Exception) { null }
            NetExecutor.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain
                if (round != null) Toast.makeText(this, "پاسخ Muse ذخیره شد.", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "پاسخ دریافت شد ولی دور در انتظاری نیست — به‌عنوان پیام جدا ذخیره شد.", Toast.LENGTH_LONG).show()
                loadRounds()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadRounds()
        uiHandler.post(uiPoll)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiPoll)
    }

    override fun onDestroy() {
        // Only the UI poll dies here — an analysis running in the service
        // keeps going and its result is in the DB on return.
        uiHandler.removeCallbacks(uiPoll)
        super.onDestroy()
    }

    private fun startAnalysis() {
        if (analyzeBusy) return
        analyzeBusy = true
        btnAnalyze.isEnabled = false
        btnAnalyze.text = "در حال تحلیل…"
        try {
            if (!MonitorService.running) {
                val start = Intent(this, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
            }
            val i = Intent(this, MonitorService::class.java)
            i.action = MonitorService.ACTION_AI_ANALYZE
            i.putExtra("kind", "analysis")
            // Phase 2: the local round goes through the Share bridge by
            // default (the development-time connection to Muse).
            i.putExtra("provider", AiCore.PROVIDER_SHARE)
            startService(i)
            Toast.makeText(this, "تحلیل شروع شد — نتیجه در همین صفحه می‌آید.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "شروع تحلیل ممکن نشد: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            analyzeBusy = false
            btnAnalyze.isEnabled = true
            btnAnalyze.text = "↻ تحلیل تازه"
        }
    }

    // Phase 2 (fixed 2.10.1): sends the pending round to the Muse app.
    // The 2.10.0 bug: the intent carried ONLY the file stream
    // (EXTRA_STREAM, MediaStore Uri) and NO text — the Muse app opens
    // with an empty composer because it reads EXTRA_TEXT. Now the
    // readable summary goes in EXTRA_TEXT alongside the file, and the
    // file is served by our own FileProvider from a cache copy
    // (guaranteed readable on API 29+). The Download/NetWatch file is
    // still written by ShareProvider for the user — only the shared Uri
    // changed. Nothing is sent automatically — the user picks Muse in
    // the chooser and taps Send in the composer. If the cache copy
    // cannot be written, the text alone is still shared (the fix for
    // the empty composer does not depend on the file).
    private fun sharePending() {
        val round = pendingRound
        if (round == null) {
            Toast.makeText(this, "دور در انتظاری نیست.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Exactly the same readable text as the .txt file, rebuilt
            // from the round's stored packet JSON (pure — no I/O).
            val text = ShareProvider.shareTextForRound(round.packetJson)
                ?: "NETWATCH — AI Analysis\nدرخواست: ${round.requestId}\nحکم محلی: ${round.verdict ?: "—"}\n"
            val uri = try {
                val fileName = ShareProvider.shareFileBase(round.requestId) + ".txt"
                val dir = File(cacheDir, "netwatch-share")
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(text, Charsets.UTF_8)
                ExportFileProvider.cacheShareUri(fileName)
            } catch (_: Exception) { null }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uri != null) send.putExtra(Intent.EXTRA_STREAM, uri)
            startActivity(Intent.createChooser(send, "ارسال به Muse"))
        } catch (e: Exception) {
            Toast.makeText(this, "اشتراک‌گذاری ممکن نشد: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    // Phase 2: a follow-up question — same machinery as an analysis, with
    // kind=question and the latest round as its parent.
    private fun askQuestion() {
        val q = etQuestion.text?.toString()?.trim() ?: ""
        if (q.isBlank()) {
            Toast.makeText(this, "سوال را بنویس.", Toast.LENGTH_SHORT).show()
            return
        }
        if (analyzeBusy) return
        try {
            if (!MonitorService.running) {
                val start = Intent(this, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
            }
            val i = Intent(this, MonitorService::class.java)
            i.action = MonitorService.ACTION_AI_ANALYZE
            i.putExtra("kind", "question")
            i.putExtra("question", q)
            i.putExtra("parentRequestId", latestRound?.requestId)
            i.putExtra("provider", AiCore.PROVIDER_SHARE)
            startService(i)
            etQuestion.setText("")
            Toast.makeText(this, "سوال فرستاده شد — پاسخ در همین صفحه می‌آید.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ارسال سوال ممکن نشد: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("پاک کردن تاریخچه AI")
            .setMessage("همه تحلیل‌ها و گفت‌وگوهای AI پاک می‌شوند. تاریخچه شبکه و بازیابی دست نمی‌خورند. ادامه بدهیم؟")
            .setPositiveButton("پاک کن") { _, _ ->
                NetExecutor.submit {
                    try { AiStore.clearAiHistory(this) } catch (_: Exception) { }
                    NetExecutor.runOnMain {
                        if (isFinishing || isDestroyed) return@runOnMain
                        loadRounds()
                        Toast.makeText(this, "تاریخه AI پاک شد.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    // ---- rendering (DB reads on a background thread) ----
    private fun loadRounds() {
        NetExecutor.submit {
            val rounds = try { AiStore.listRounds(this, 20) } catch (_: Exception) { emptyList<AiCore.AiRound>() }
            val pending = try { AiStore.pendingRound(this) } catch (_: Exception) { null }
            NetExecutor.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain
                latestRound = rounds.firstOrNull()
                pendingRound = pending
                val busy = MonitorService.aiBusy
                analyzeBusy = busy
                btnAnalyze.isEnabled = !busy
                btnAnalyze.text = if (busy) "در حال تحلیل…" else "↻ تحلیل تازه"
                btnShare.visibility = if (pending != null) View.VISIBLE else View.GONE
                val statusText = when {
                    busy -> "وضعیت: در حال تحلیل… (هسته در سرویس کار می‌کند — خروج از صفحه آن را متوقف نمی‌کند)"
                    pending != null -> "وضعیت: یک دور در انتظار پاسخ Muse است (${pending.requestId})"
                    rounds.isEmpty() -> "وضعیت: هنوز تحلیلی انجام نشده است — «تحلیل تازه» را بزن."
                    else -> "وضعیت: آخرین تحلیل ${Bridge.timeStr(rounds.first().createdMs)} • ارائه‌دهنده: ${rounds.first().provider}"
                }
                tvStatus.text = statusText
                renderLatest(rounds.firstOrNull())
                renderHistory(rounds)
            }
        }
    }

    private fun renderLatest(round: AiCore.AiRound?) {
        if (round == null) {
            tvLatest.text = "هنوز تحلیلی نیست."
            findingsContainer.removeAllViews()
            tvFindingsEmpty.visibility = View.VISIBLE
            return
        }
        val verdictFa = when (round.verdict) {
            "ok" -> "سالم"
            "degraded" -> "اختلال"
            "outage" -> "قطعی کامل"
            else -> "نامشخص"
        }
        val conf = if (round.confidence >= 0) "اطمینان ${round.confidence}٪" else "اطمینان نامشخص"
        tvLatest.text = "حکم: $verdictFa ($conf) • ${Bridge.timeStr(round.createdMs)}\n\n${round.responseMd ?: "—"}"
        findingsContainer.removeAllViews()
        tvFindingsEmpty.visibility = View.GONE
        // Findings are stored inside the packet JSON (ai.findings) — the
        // exact locations, only with real evidence.
        try {
            val packet = org.json.JSONObject(round.packetJson)
            val fa = packet.optJSONObject("ai")?.optJSONArray("findings")
            if (fa == null || fa.length() == 0) {
                tvFindingsEmpty.visibility = View.VISIBLE
                return
            }
            for (i in 0 until fa.length()) {
                val fo = fa.optJSONObject(i) ?: continue
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(28, 20, 28, 20)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 12; layoutParams = lp
                }
                val l1 = TextView(this).apply {
                    text = "[${fo.optString("module", "—")}] ${fo.optString("title", "—")}"
                    setTextColor(Color.parseColor("#F1F5F9")); textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val loc = if (!fo.isNull("file")) "${fo.optString("file")}:${fo.optInt("line", -1)} • ${fo.optString("function", "—")}"
                else "بدون فایل/خط (شواهد فایل ندارد)"
                val l2 = TextView(this).apply {
                    text = "${fo.optString("detail", "—")}\nمکان: $loc\nشواهد: ${fo.optString("evidence", "—")}"
                    setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f
                }
                card.addView(l1); card.addView(l2)
                findingsContainer.addView(card)
            }
        } catch (_: Exception) {
            tvFindingsEmpty.visibility = View.VISIBLE
        }
    }

    private fun renderHistory(rounds: List<AiCore.AiRound>) {
        historyContainer.removeAllViews()
        tvHistoryEmpty.visibility = if (rounds.isEmpty()) View.VISIBLE else View.GONE
        for (r in rounds) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(28, 20, 28, 20)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = 12; layoutParams = lp
                isClickable = true; isFocusable = true
            }
            val verdictFa = when (r.verdict) {
                "ok" -> "سالم"
                "degraded" -> "اختلال"
                "outage" -> "قطعی کامل"
                else -> "نامشخص"
            }
            val l1 = TextView(this).apply {
                text = "${Bridge.timeStr(r.createdMs)} • ${if (r.kind == "question") "سوال" else "تحلیل"} • $verdictFa • ${r.status}"
                setTextColor(Color.parseColor("#F1F5F9")); textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val l2 = TextView(this).apply {
                text = "${r.requestId} • ارائه‌دهنده: ${r.provider}"
                setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f
            }
            card.addView(l1); card.addView(l2)
            card.setOnClickListener { showRoundDetail(r) }
            historyContainer.addView(card)
        }
    }

    private fun showRoundDetail(r: AiCore.AiRound) {
        val text = StringBuilder()
        text.append("درخواست: ${r.requestId}\n")
        text.append("نوع: ${if (r.kind == "question") "سوال" else "تحلیل"} • ارائه‌دهنده: ${r.provider} • وضعیت: ${r.status}\n")
        text.append("حکم: ${r.verdict ?: "—"} • اطمینان: ${if (r.confidence >= 0) "${r.confidence}٪" else "نامشخص"}\n\n")
        text.append(r.responseMd ?: "پاسخی ثبت نشده است.")
        if (r.error != null) text.append("\n\nخطا: ${r.error}")
        AlertDialog.Builder(this)
            .setTitle("جزئیات دور AI")
            .setMessage(text.toString())
            .setPositiveButton("بستن", null)
            .show()
    }
}
