package com.aksell.netwatch

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Manual Speed Test (v2.11.0 Phase 1). Runs ONLY when the user taps the button
// (after a data warning) — never automatic, never continuous. Download AND
// upload are real measurements against speed.cloudflare.com; the result
// stores the ACTUAL counted bytes, and the card shows the measured data
// consumption. No fake numbers: before any real test every value is "—".
object SpeedTest {
    const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
    const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val MAX_DOWNLOAD_BYTES = 50_000_000L
    const val MAX_UPLOAD_BYTES = 20_000_000L
    const val MAX_TEST_MS = 10_000L
    const val BUFFER_SIZE = 64 * 1024
    const val PREFS_NAME = "speedtest"
    const val PREF_KEY = "speed_last"

    data class SpeedResult(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val bytesDownloaded: Long,
        val bytesUploaded: Long,
        val timestampMs: Long
    )

    // Pure: bytes * 8 bits / seconds / 1e6. Non-positive input is "no measurement" -> 0.0.
    fun throughputMbps(bytes: Long, elapsedMs: Long): Double {
        if (bytes <= 0 || elapsedMs <= 0) return 0.0
        return bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
    }

    // Prefs format (v2.11.0): dl|ul|bytesDown|bytesUp|ts — all five fields,
    // so the real data consumption survives a restart.
    fun formatResult(r: SpeedResult): String =
        "${r.downloadMbps}|${r.uploadMbps}|${r.bytesDownloaded}|${r.bytesUploaded}|${r.timestampMs}"

    fun parseResult(raw: String?): SpeedResult? {
        if (raw.isNullOrBlank()) return null
        val p = raw.split("|")
        if (p.size != 5) return null
        val dl = p[0].toDoubleOrNull() ?: return null
        val ul = p[1].toDoubleOrNull() ?: return null
        val down = p[2].toLongOrNull() ?: return null
        val up = p[3].toLongOrNull() ?: return null
        val ts = p[4].toLongOrNull() ?: return null
        if (dl < 0 || ul < 0 || down < 0 || up < 0 || ts < 0) return null
        return SpeedResult(dl, ul, down, up, ts)
    }

    fun saveResult(ctx: Context, r: SpeedResult) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, formatResult(r)).apply()
    }

    fun loadResult(ctx: Context): SpeedResult? =
        parseResult(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY, null))

    // Display helpers: "—" means "not measured yet", never a made-up number.
    fun formatSpeed(mbps: Double): String =
        if (mbps <= 0.0 || !mbps.isFinite()) "—" else "%.1f Mbps".format(mbps)

    fun formatDataUsage(bytesDownloaded: Long, bytesUploaded: Long): String {
        val totalMb = (bytesDownloaded + bytesUploaded) / 1_000_000.0
        return "مصرف داده: %.1f مگابایت".format(totalMb)
    }

    fun formatTestTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

    // Download: GET the Cloudflare speed-test file, 64KB buffer, stop at 10s
    // or 50MB. Returns (actual bytes read, elapsed ms). Call only on a
    // background thread. Throws on network failure (caller shows "ناموفق").
    fun runDownload(): Pair<Long, Long> {
        val start = System.currentTimeMillis()
        var bytes = 0L
        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            conn.connect()
            conn.inputStream.use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed >= MAX_TEST_MS || bytes >= MAX_DOWNLOAD_BYTES) break
                }
            }
        } finally {
            conn.disconnect()
        }
        return bytes to (System.currentTimeMillis() - start)
    }

    // Upload: POST generated zero bytes (one 64KB buffer reused, chunked
    // streaming — the whole payload is never allocated) to the Cloudflare
    // speed-test endpoint, stop at 10s or 20MB. Returns (actual bytes sent,
    // elapsed ms). Call only on a background thread.
    fun runUpload(): Pair<Long, Long> {
        val start = System.currentTimeMillis()
        var bytes = 0L
        val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setChunkedStreamingMode(BUFFER_SIZE)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            conn.connect()
            conn.outputStream.use { out ->
                val buf = ByteArray(BUFFER_SIZE) // zeros, reused
                while (true) {
                    out.write(buf)
                    bytes += BUFFER_SIZE
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed >= MAX_TEST_MS || bytes >= MAX_UPLOAD_BYTES) break
                }
                out.flush()
            }
            // Drain the (small) response so the request completes server-side.
            try {
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (input.read(buf) >= 0) { /* drain */ }
                }
            } catch (_: Exception) { /* error body or none — result still counts */ }
        } finally {
            conn.disconnect()
        }
        return bytes to (System.currentTimeMillis() - start)
    }

    // Compose a result from the two phases; null if either phase failed.
    fun buildResult(down: Pair<Long, Long>?, up: Pair<Long, Long>?): SpeedResult? {
        if (down == null || up == null) return null
        return SpeedResult(
            throughputMbps(down.first, down.second),
            throughputMbps(up.first, up.second),
            down.first, up.first, System.currentTimeMillis()
        )
    }

    fun selfTests(): List<TestResult> {
        val out = mutableListOf<TestResult>()
        fun t(name: String, expected: String, actual: String, ok: Boolean) {
            out.add(TestResult(name, "SpeedTest — ورودی ثابت، بدون شبکه", expected, actual, ok, date = History.timeStr(System.currentTimeMillis()), durationMs = 0))
        }
        t("throughput 10MB/10s", "8.0", throughputMbps(10_000_000L, 10_000L).toString(), throughputMbps(10_000_000L, 10_000L) == 8.0)
        t("throughput 25MB/10s", "20.0", throughputMbps(25_000_000L, 10_000L).toString(), throughputMbps(25_000_000L, 10_000L) == 20.0)
        t("throughput 0 bytes", "0.0", throughputMbps(0L, 10_000L).toString(), throughputMbps(0L, 10_000L) == 0.0)
        t("throughput negative bytes", "0.0", throughputMbps(-100L, 10_000L).toString(), throughputMbps(-100L, 10_000L) == 0.0)
        t("throughput 0 ms", "0.0", throughputMbps(10_000_000L, 0L).toString(), throughputMbps(10_000_000L, 0L) == 0.0)
        t("throughput negative ms", "0.0", throughputMbps(10_000_000L, -5L).toString(), throughputMbps(10_000_000L, -5L) == 0.0)
        val r = SpeedResult(42.5, 10.2, 30_000_000L, 12_000_000L, 1790000000000L)
        val rt = parseResult(formatResult(r))
        t("prefs round-trip", r.toString(), rt.toString(), rt == r)
        t("parse garbage", "null", parseResult("abc").toString(), parseResult("abc") == null)
        t("parse short", "null", parseResult("1|2|3").toString(), parseResult("1|2|3") == null)
        t("parse bad number", "null", parseResult("x|2|3|4|5").toString(), parseResult("x|2|3|4|5") == null)
        t("parse negative field", "null", parseResult("42.5|10.2|-1|12|1790000000000").toString(), parseResult("42.5|10.2|-1|12|1790000000000") == null)
        t("formatSpeed 0", "—", formatSpeed(0.0), formatSpeed(0.0) == "—")
        t("formatSpeed negative", "—", formatSpeed(-5.0), formatSpeed(-5.0) == "—")
        val fs = formatSpeed(42.34)
        t("formatSpeed 42.34", "42.3 Mbps", fs, fs.startsWith("42.3") && fs.endsWith("Mbps"))
        val du = formatDataUsage(24_500_000L, 0L)
        t("formatDataUsage 24.5MB", "مصرف داده: 24.5 مگابایت", du, du.contains("24.5") && du.contains("مگابایت"))
        val du0 = formatDataUsage(0L, 0L)
        t("formatDataUsage 0", "مصرف داده: 0.0 مگابایت", du0, du0.contains("0.0") && du0.contains("مگابایت"))
        val ft = formatTestTime(0L)
        t("formatTestTime shape", "yyyy-MM-dd HH:mm", ft, ft.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        t("buildResult null down", "null", buildResult(null, 5_000_000L to 5_000L).toString(), buildResult(null, 5_000_000L to 5_000L) == null)
        t("buildResult null up", "null", buildResult(10_000_000L to 10_000L, null).toString(), buildResult(10_000_000L to 10_000L, null) == null)
        val built = buildResult(10_000_000L to 10_000L, 5_000_000L to 5_000L)
        t("buildResult values", "dl=8.0 ul=8.0 down=10000000 up=5000000", "dl=${built?.downloadMbps} ul=${built?.uploadMbps} down=${built?.bytesDownloaded} up=${built?.bytesUploaded}",
            built != null && built.downloadMbps == 8.0 && built.uploadMbps == 8.0 && built.bytesDownloaded == 10_000_000L && built.bytesUploaded == 5_000_000L)
        return out
    }
}
