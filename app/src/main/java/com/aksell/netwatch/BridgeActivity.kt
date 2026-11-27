package com.aksell.netwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.ArrayList

// BridgeActivity (Phase 2): the independent MUSE BRIDGE page.
// Display only: it reads what Phase 1 already stores (events, crashes,
// health snapshot) and builds/shares the diagnostic report. It never
// logs, never measures, never uploads, and never touches the Clipboard.
// No copy button (Phase 2 decision 1): preview + file sharing is enough.
class BridgeActivity : Activity() {
    private lateinit var tvCounts: TextView
    private lateinit var tvLastCrash: TextView
    private lateinit var tvRecording: TextView
    private lateinit var btnHealth: Button
    private lateinit var healthContainer: LinearLayout
    private lateinit var tvHealthHint: TextView
    private lateinit var crashContainer: LinearLayout
    private lateinit var tvCrashEmpty: TextView
    private lateinit var cbNetwork: CheckBox
    private lateinit var tvNetworkWarn: TextView
    private lateinit var btnPreview: Button
    private lateinit var btnPreviewToggle: Button
    private lateinit var tvPreview: TextView
    private lateinit var btnShare: Button
    private lateinit var btnShareNetwork: Button

    // Session-only state. The health result is NOT persisted (the report
    // takes its own fresh snapshot at build time); the built report is
    // kept in memory so preview/share do not rebuild it every tap.
    private var lastHealth: Bridge.Health? = null
    private var builtJson: JSONObject? = null
    private var builtText: String? = null
    private var builtSupplement: JSONObject? = null
    private var previewShowsJson = false
    private var healthBusy = false
    private var reportBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bridge)
        tvCounts = findViewById(R.id.tvBridgeCounts)
        tvLastCrash = findViewById(R.id.tvBridgeLastCrash)
        tvRecording = findViewById(R.id.tvBridgeRecording)
        btnHealth = findViewById(R.id.btnBridgeHealth)
        healthContainer = findViewById(R.id.bridgeHealthContainer)
        tvHealthHint = findViewById(R.id.tvBridgeHealthHint)
        crashContainer = findViewById(R.id.bridgeCrashContainer)
        tvCrashEmpty = findViewById(R.id.tvBridgeCrashEmpty)
        cbNetwork = findViewById(R.id.cbBridgeNetwork)
        tvNetworkWarn = findViewById(R.id.tvBridgeNetworkWarn)
        btnPreview = findViewById(R.id.btnBridgePreview)
        btnPreviewToggle = findViewById(R.id.btnBridgePreviewToggle)
        tvPreview = findViewById(R.id.tvBridgePreview)
        btnShare = findViewById(R.id.btnBridgeShare)
        btnShareNetwork = findViewById(R.id.btnBridgeShareNetwork)

        btnHealth.setOnClickListener { runHealthCheck() }
        btnPreview.setOnClickListener { showPreview() }
        btnPreviewToggle.setOnClickListener {
            previewShowsJson = !previewShowsJson
            btnPreviewToggle.text = if (previewShowsJson) "نمایش متن خوانا" else "نمایش JSON"
            renderPreview()
        }
        btnShare.setOnClickListener { shareReport() }
        btnShareNetwork.setOnClickListener { shareSupplement() }
        cbNetwork.setOnCheckedChangeListener { _, checked ->
            // The choice is never persisted (spec §10.2: no "always", no
            // saved consent). Unchecking drops the built supplement.
            tvNetworkWarn.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) {
                builtSupplement = null
                btnShareNetwork.visibility = View.GONE
            } else {
                btnShareNetwork.visibility = if (builtSupplement != null) View.VISIBLE else View.GONE
            }
        }
        loadStatusCard()
        loadCrashList()
        renderHealth(lastHealth)
    }

    override fun onResume() {
        super.onResume()
        // Cheap reads only; the health check is never re-run by itself.
        loadStatusCard()
        loadCrashList()
    }

    // ---- status card (in-memory ring + bounded dir walk; main thread OK) ----
    private fun loadStatusCard() {
        try {
            val counts = Bridge.summaryCounts()
            val e = counts[Bridge.Status.ERROR] ?: 0
            val w = counts[Bridge.Status.WARNING] ?: 0
            val u = counts[Bridge.Status.UNKNOWN] ?: 0
            val n = counts[Bridge.Status.NOT_RUN] ?: 0
            tvCounts.text = "خطای قطعی: $e • هشدار: $w • نامشخص: $u • اجرا نشده: $n"
            val last = Bridge.listCrashes().firstOrNull()
            tvLastCrash.text = if (last == null) "آخرین Crash: هنوز Crash ثبت نشده است."
            else "آخرین Crash: ${Bridge.timeStr(last.ts)} • ${last.module} • ${last.exceptionClass}"
            val usedKb = Bridge.storageUsageBytes() / 1024
            val installed = Bridge.handlerInstalled()
            tvRecording.text = if (installed) "ثبت گزارش‌ها: فعال • ${Bridge.events().size} رویداد در حافظه • $usedKb KB از ۵۱۲ KB"
            else "ثبت گزارش‌ها: غیرفعال — Crash Handler نصب نشده است"
            tvRecording.setTextColor(Color.parseColor(if (installed) "#22C55E" else "#EF4444"))
        } catch (e: Exception) {
            tvCounts.text = "خواندن وضعیت Bridge ممکن نشد: ${e.javaClass.simpleName}"
        }
    }

    // ---- crash list (file reads on a background thread) ----
    private fun loadCrashList() {
        NetExecutor.submit {
            val list = try { Bridge.listCrashes() } catch (_: Exception) { emptyList<Bridge.CrashSummary>() }
            NetExecutor.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain
                crashContainer.removeAllViews()
                tvCrashEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                for (s in list) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.card_bg)
                        setPadding(28, 20, 28, 20)
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        lp.topMargin = 12; layoutParams = lp
                        isClickable = true; isFocusable = true
                    }
                    val l1 = TextView(this).apply {
                        text = "${Bridge.timeStr(s.ts)} • ${s.module} • ${s.exceptionClass}"
                        setTextColor(Color.parseColor("#F1F5F9")); textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val l2 = TextView(this).apply {
                        text = s.message.ifBlank { "—" } + if (s.file != null) " • ${s.file}:${s.line ?: "—"}" else ""
                        setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f
                    }
                    card.addView(l1); card.addView(l2)
                    card.setOnClickListener { showCrashDetail(s) }
                    crashContainer.addView(card)
                }
            }
        }
    }

    // A corrupt crash file falls back to a message — it never closes this page.
    private fun showCrashDetail(s: Bridge.CrashSummary) {
        val text = try {
            val raw = Bridge.crashJsonText(s.fileName)
            if (raw == null) "جزئیات این Crash خوانده نشد (فایل در دسترس نیست)."
            else BridgeReport.crashDetailText(raw)
        } catch (_: Exception) {
            "جزئیات این Crash خوانده نشد."
        }
        AlertDialog.Builder(this)
            .setTitle("جزئیات Crash — ${s.module}")
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .show()
    }

    // ---- health check (on demand only; DB work off the main thread) ----
    private fun runHealthCheck() {
        if (healthBusy) return
        healthBusy = true
        btnHealth.isEnabled = false
        btnHealth.text = "در حال بررسی سلامت…"
        tvHealthHint.text = "در حال بررسی…"
        NetExecutor.submit {
            val h = try { Bridge.healthSnapshot(this, MonitorService.running) } catch (_: Exception) { null }
            NetExecutor.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain
                healthBusy = false
                btnHealth.isEnabled = true
                btnHealth.text = "↻ اجرای بررسی سلامت"
                lastHealth = h
                renderHealth(h)
            }
        }
    }

    private fun renderHealth(h: Bridge.Health?) {
        healthContainer.removeAllViews()
        if (h == null) {
            tvHealthHint.text = "هنوز بررسی سلامت انجام نشده است — دکمهٔ بالا را بزن."
            return
        }
        tvHealthHint.text = "بررسی انجام شد — هر ردیف وضعیت خودش را دارد؛ UNKNOWN و NOT_RUN خطا نیستند."
        for (row in BridgeReport.healthRows(h)) {
            val chipColor = when (row.chip) {
                BridgeReport.HealthChip.OK -> "#22C55E"
                BridgeReport.HealthChip.ERROR -> "#EF4444"
                BridgeReport.HealthChip.WARNING -> "#F59E0B"
                BridgeReport.HealthChip.UNKNOWN -> "#94A3B8"
                BridgeReport.HealthChip.NOT_RUN -> "#2DD4BF"
            }
            val chipText = when (row.chip) {
                BridgeReport.HealthChip.OK -> "سالم"
                BridgeReport.HealthChip.ERROR -> "ERROR"
                BridgeReport.HealthChip.WARNING -> "WARNING"
                BridgeReport.HealthChip.UNKNOWN -> "UNKNOWN"
                BridgeReport.HealthChip.NOT_RUN -> "NOT_RUN"
            }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = 10; layoutParams = lp
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = row.label; setTextColor(Color.parseColor("#F1F5F9")); textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val chip = TextView(this).apply {
                text = chipText; setTextColor(Color.parseColor(chipColor)); textSize = 12f
                setBackgroundResource(R.drawable.badge_bg)
                setPadding(20, 10, 20, 10)
            }
            top.addView(label); top.addView(chip)
            val detail = TextView(this).apply {
                text = row.detail; setTextColor(Color.parseColor("#CBD5E1")); textSize = 13f
            }
            wrap.addView(top); wrap.addView(detail)
            healthContainer.addView(wrap)
        }
    }

    // ---- report build (background; fresh health snapshot at build time) ----
    private data class BuiltReport(
        val json: JSONObject,
        val text: String,
        val supplement: JSONObject?,
        val health: Bridge.Health?
    )

    private fun appVersionInfo(): Pair<String, Int> {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
            (pi.versionName ?: "—") to code
        } catch (_: Exception) { "—" to 0 }
    }

    private fun ensureReport(onReady: (BuiltReport?) -> Unit) {
        if (builtJson != null && builtText != null) {
            onReady(BuiltReport(builtJson!!, builtText!!, builtSupplement, lastHealth))
            return
        }
        if (reportBusy) return
        reportBusy = true
        Toast.makeText(this, "گزارش در حال ساخت است…", Toast.LENGTH_SHORT).show()
        NetExecutor.submit {
            val built = try {
                val events = Bridge.events()
                val health = Bridge.healthSnapshot(this, MonitorService.running)
                val crashes = Bridge.listCrashes()
                val labFailures = try {
                    Lab.loadRuns(this).maxByOrNull { it.timestamp }?.results?.filter { it.pass == false } ?: emptyList()
                } catch (_: Exception) { emptyList<TestResult>() }
                val (ver, code) = appVersionInfo()
                val json = BridgeReport.buildReportJson(events, health, crashes, labFailures, ver, code, Build.MODEL ?: "—", Build.VERSION.SDK_INT)
                val txt = BridgeReport.buildReadableText(events, health, crashes, labFailures, ver, code, Build.MODEL ?: "—", Build.VERSION.SDK_INT)
                val supp = if (cbNetwork.isChecked) BridgeReport.buildNetworkSupplement(
                    gatherPublicIp(), gatherGateway(), gatherDns(), readSsid()
                ) else null
                BuiltReport(json, txt, supp, health)
            } catch (_: Exception) { null }
            NetExecutor.runOnMain {
                if (isFinishing || isDestroyed) return@runOnMain
                reportBusy = false
                if (built != null) {
                    builtJson = built.json
                    builtText = built.text
                    builtSupplement = built.supplement
                    lastHealth = built.health
                    renderHealth(built.health)
                }
                onReady(built)
            }
        }
    }

    private fun renderPreview() {
        if (builtText == null && builtJson == null) {
            tvPreview.text = "هنوز گزارشی ساخته نشده است — «پیش‌نمایش گزارش» را بزن."
            return
        }
        tvPreview.text = if (previewShowsJson) builtJson?.toString(2) ?: "—" else builtText ?: "—"
    }

    private fun showPreview() {
        ensureReport { built ->
            if (built == null) {
                Toast.makeText(this, "ساخت گزارش ممکن نشد.", Toast.LENGTH_SHORT).show()
                return@ensureReport
            }
            renderPreview()
        }
    }

    private fun shareReport() {
        ensureReport { built ->
            if (built == null) {
                Toast.makeText(this, "ساخت گزارش ممکن نشد.", Toast.LENGTH_SHORT).show()
                return@ensureReport
            }
            NetExecutor.submit {
                val jsonRes = ExportHelper.save(this, "NETWATCH_Bridge_Report", "json", "application/json") { out ->
                    out.write(built.json.toString(2).toByteArray(Charsets.UTF_8))
                }
                val txtRes = ExportHelper.save(this, "NETWATCH_Bridge_Report", "txt", "text/plain") { out ->
                    out.write(built.text.toByteArray(Charsets.UTF_8))
                }
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    if (!jsonRes.ok || !txtRes.ok) {
                        val err = listOf(jsonRes.error, txtRes.error).filter { it.isNotBlank() }.joinToString(" • ")
                        Toast.makeText(this, "ذخیرهٔ گزارش ناموفق بود: $err", Toast.LENGTH_LONG).show()
                        return@runOnMain
                    }
                    val uris = ArrayList<Uri>()
                    jsonRes.uri?.let { uris.add(it) }
                    txtRes.uri?.let { uris.add(it) }
                    if (uris.isEmpty()) {
                        Toast.makeText(this, "ذخیره شد ولی نشانی اشتراک‌گذاری در دسترس نیست.", Toast.LENGTH_LONG).show()
                        return@runOnMain
                    }
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                        .setType("*/*")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(Intent.createChooser(send, "اشتراک‌گذاری گزارش Bridge"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "اشتراک‌گذاری ممکن نشد: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    Toast.makeText(this, "گزارش ذخیره شد: ${jsonRes.displayName} • ${txtRes.displayName} — در ${ExportHelper.FOLDER_LABEL}/", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareSupplement() {
        if (!cbNetwork.isChecked) return
        ensureReport { built ->
            val supp = built?.supplement
            if (supp == null) {
                Toast.makeText(this, "مکمل شبکه ساخته نشده است — تیک «شامل جزئیات شبکه» را بزن و دوباره تلاش کن.", Toast.LENGTH_LONG).show()
                return@ensureReport
            }
            NetExecutor.submit {
                val res = ExportHelper.save(this, "NETWATCH_Bridge_Report_network", "json", "application/json") { out ->
                    out.write(supp.toString(2).toByteArray(Charsets.UTF_8))
                }
                NetExecutor.runOnMain {
                    if (isFinishing || isDestroyed) return@runOnMain
                    if (!res.ok || res.uri == null) {
                        Toast.makeText(this, "ذخیرهٔ مکمل شبکه ناموفق بود: ${res.error}", Toast.LENGTH_LONG).show()
                        return@runOnMain
                    }
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, res.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(Intent.createChooser(send, "اشتراک‌گذاری مکمل شبکه (حاوی اطلاعات شبکه)"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "اشتراک‌گذاری ممکن نشد: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ---- network supplement values (Android 10+ rules) ----
    // Every value is resolved to null when it is not genuinely available:
    // a missing value is written as "unavailable", never faked. The SSID
    // needs location permission on Android 10+; without it the system
    // returns "<unknown ssid>", which we treat as unavailable.
    private fun gatherPublicIp(): String? {
        return try {
            val ip = WidgetStats.snapshot(this).publicIp
            if (ip.isBlank() || ip == "—") null else ip
        } catch (_: Exception) { null }
    }

    private fun gatherGateway(): String? {
        return try {
            val gw = NetworkInfo.gateway(this)
            if (gw.isBlank() || gw == "—") null else gw
        } catch (_: Exception) { null }
    }

    private fun gatherDns(): String? {
        return try {
            val d = NetworkInfo.dns(this)
            if (d.isBlank() || d == "—") null else d
        } catch (_: Exception) { null }
    }

    private fun readSsid(): String? {
        return try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid.trim('"')
        } catch (_: Exception) { null }
    }
}
