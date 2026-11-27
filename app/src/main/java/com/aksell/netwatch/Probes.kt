package com.aksell.netwatch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class ProbeDef(val id: String, val name: String, val detail: String, val kind: String, val target: String, val domestic: Boolean = false, val category: String = "intl")
data class ProbeResult(val id: String, val name: String, val detail: String, val ok: Boolean, val ms: Long, val note: String, val domestic: Boolean = false, val category: String = "intl")

object Probes {
    val defs = listOf(
        // DNS / CDN
        ProbeDef("cf", "کلادفلر", "HTTPS", "HTTPS", "https://www.cloudflare.com/cdn-cgi/trace", false, "cdn"),
        ProbeDef("cfdns", "DNS کلادفلر 1.1.1.1", "DoH", "DoH", "https://cloudflare-dns.com/dns-query?name=google.com&type=A", false, "dns"),
        ProbeDef("gdns", "DNS گوگل 8.8.8.8", "DoH", "DoH", "https://dns.google/resolve?name=google.com&type=A", false, "dns"),
        ProbeDef("quad9", "DNS کواد۹ 9.9.9.9", "DoH", "DoH", "https://dns.quad9.net/dns-query?name=google.com&type=A", false, "dns"),
        ProbeDef("dot-cf", "DoT کلادفلر :853", "DoT", "DOT", "1.1.1.1:853", false, "dns"),
        ProbeDef("dot-gg", "DoT گوگل :853", "DoT", "DOT", "8.8.8.8:853", false, "dns"),
        ProbeDef("dot-q9", "DoT کواد۹ :853", "DoT", "DOT", "9.9.9.9:853", false, "dns"),
        ProbeDef("dns-udp", "DNS معمولی UDP", "UDP DNS", "DNS", "google.com", false, "dns"),
        ProbeDef("udp-cf", "UDP DNS کلادفلر", "UDP :53", "UDPDNS", "1.1.1.1", false, "dns"),
        ProbeDef("udp-gg", "UDP DNS گوگل", "UDP :53", "UDPDNS", "8.8.8.8", false, "dns"),
        ProbeDef("akamai", "Akamai", "HTTPS CDN", "HTTPS", "https://www.akamai.com/", false, "cdn"),
        ProbeDef("fastly", "Fastly", "HTTPS CDN", "HTTPS", "https://www.fastly.com/", false, "cdn"),
        // Search / general intl
        ProbeDef("google", "گوگل", "HTTPS", "HTTPS", "https://www.google.com/generate_204", false, "intl"),
        ProbeDef("tcp-gg", "TCP گوگل :443", "TCP", "TCP", "www.google.com:443", false, "intl"),
        ProbeDef("icmp-cf", "پینگ ICMP کلادفلر", "ICMP 1.1.1.1", "ICMP", "1.1.1.1", false, "intl"),
        ProbeDef("icmp-gg", "پینگ ICMP گوگل", "ICMP 8.8.8.8", "ICMP", "8.8.8.8", false, "intl"),
        ProbeDef("tcp-cf", "TCP کلادفلر :443", "TCP", "TCP", "www.cloudflare.com:443", false, "intl"),
        ProbeDef("http", "HTTP ساده", "HTTP example.com", "HTTP", "http://example.com/", false, "intl"),
        ProbeDef("ipv4", "IPv4", "آی‌پی نسخه ۴", "HTTPS", "https://api.ipify.org?format=json", false, "ip"),
        ProbeDef("ipv6", "IPv6", "آی‌پی نسخه ۶", "HTTPS", "https://ipv6.google.com/", false, "ip"),
        // Messengers / social
        ProbeDef("telegram", "تلگرام", "HTTPS", "HTTPS", "https://telegram.org/", false, "social"),
        ProbeDef("tcp-tg", "TCP تلگرام :443", "TCP", "TCP", "telegram.org:443", false, "social"),
        ProbeDef("tls-tg", "TLS تلگرام", "TLS Handshake", "TLS", "telegram.org", false, "social"),
        ProbeDef("instagram", "اینستاگرام", "HTTPS", "HTTPS", "https://www.instagram.com/", false, "social"),
        ProbeDef("tls-ig", "TLS اینستاگرام", "TLS Handshake", "TLS", "www.instagram.com", false, "social"),
        ProbeDef("whatsapp", "واتس‌اپ", "HTTPS", "HTTPS", "https://web.whatsapp.com/", false, "social"),
        ProbeDef("signal", "سیگنال", "HTTPS", "HTTPS", "https://signal.org/", false, "social"),
        ProbeDef("discord", "دیسکورد", "HTTPS", "HTTPS", "https://discord.com/", false, "social"),
        // Video / dev
        ProbeDef("youtube", "یوتیوب", "HTTPS", "HTTPS", "https://www.youtube.com/generate_204", false, "video"),
        ProbeDef("github", "گیت‌هاب", "HTTPS", "HTTPS", "https://api.github.com/", false, "dev"),
        ProbeDef("gitlab", "گیت‌لب", "HTTPS", "HTTPS", "https://gitlab.com/", false, "dev"),
        ProbeDef("cfmeta", "کلادفلر Meta", "HTTPS", "HTTPS", "https://speed.cloudflare.com/meta", false, "cdn"),
        // Domestic Iran
        ProbeDef("aparat", "آپارات", "HTTPS • داخلی", "HTTPS", "https://www.aparat.com/", true, "domestic"),
        ProbeDef("digikala", "دیجی‌کالا", "HTTPS • داخلی", "HTTPS", "https://www.digikala.com/", true, "domestic"),
        ProbeDef("divar", "دیوار", "HTTPS • داخلی", "HTTPS", "https://divar.ir/", true, "domestic"),
        ProbeDef("telewebion", "تلوبیون", "HTTPS • داخلی", "HTTPS", "https://telewebion.com/", true, "domestic"),
        ProbeDef("irna", "ایرنا", "HTTPS • داخلی", "HTTPS", "https://www.irna.ir/", true, "domestic"),
        ProbeDef("tcp-aparat", "TCP آپارات :443", "TCP • داخلی", "TCP", "www.aparat.com:443", true, "domestic"),
        // Status pages
        ProbeDef("radar", "کلادفلر رادار ایران", "HTTPS", "HTTPS", "https://radar.cloudflare.com/ir", false, "status"),
        ProbeDef("netblocks", "نت‌بلاکس", "HTTPS", "HTTPS", "https://netblocks.org/", false, "status"),
        ProbeDef("ooni", "OONI", "HTTPS API", "HTTPS", "https://api.ooni.io/api/v1/measurements?limit=1", false, "status")
    )

    // v2.9.5 — DoH JSON answer (DNS-over-HTTPS JSON API): only Status==0
    // with a non-empty A (type 1) Answer counts as a passed DoH test.
    // An HTTP 200 alone is NOT a pass — same rules as Diagnostics.dohIps.
    // NOTE: parsed with small regexes, NOT org.json — this function must
    // also run in the JVM unit tests, where android.jar's org.json is a
    // stub that throws. The DoH JSON shape is fixed (Status + Answer array
    // of flat objects), so this extraction is enough — and it is validated
    // by JVM tests below (Lab.unitTests).
    // v2.9.5 final control — strict IPv4: exactly 4 decimal octets, each
    // 0-255, no leading zeros except a lone "0". Blank, wrong part count,
    // non-numeric parts and out-of-range octets are all invalid.
    fun isValidIPv4(s: String): Boolean {
        if (s.isEmpty()) return false
        val parts = s.split(".")
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return false
            if (p.length > 1 && p[0] == '0') return false
            if (!p.all { it in '0'..'9' }) return false
            val v = p.toIntOrNull() ?: return false
            if (v > 255) return false
        }
        return true
    }

    data class DohParse(val ok: Boolean, val answers: List<String>, val error: String)
    fun parseDohJson(body: String): DohParse {
        val statusM = Regex("\"Status\"\\s*:\\s*(-?\\d+)").find(body)
            ?: return DohParse(false, emptyList(), "Parse ناموفق: Status پیدا نشد")
        val status = statusM.groupValues[1].toIntOrNull()
            ?: return DohParse(false, emptyList(), "Parse ناموفق: Status عدد نیست")
        if (status != 0) return DohParse(false, emptyList(), "Status=$status (پاسخ DNS خطا دارد)")
        val ansM = Regex("\"Answer\"\\s*:\\s*\\[").find(body)
            ?: return DohParse(false, emptyList(), "بدون Answer (پاسخ خالی)")
        var depth = 1
        var i = ansM.range.last + 1
        var ansEnd = -1
        while (i < body.length && depth > 0) {
            when (body[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) ansEnd = i }
            }
            i++
        }
        if (ansEnd < 0) return DohParse(false, emptyList(), "Parse ناموفق: Answer بسته نشد")
        val ansBody = body.substring(ansM.range.last + 1, ansEnd)
        if (ansBody.isBlank()) return DohParse(false, emptyList(), "بدون Answer (پاسخ خالی)")
        val out = mutableListOf<String>()
        for (obj in Regex("\\{[^{}]*\\}").findAll(ansBody)) {
            val chunk = obj.value
            val type = Regex("\"type\"\\s*:\\s*(\\d+)").find(chunk)?.groupValues?.get(1)?.toIntOrNull()
            if (type == 1) {
                val d = Regex("\"data\"\\s*:\\s*\"([^\"]*)\"").find(chunk)?.groupValues?.get(1) ?: ""
                // v2.9.5 final control — an A record must hold a valid
                // IPv4 address; anything else (blank, partial, extra
                // parts, out-of-range, non-numeric, leading zeros)
                // invalidates the whole answer — it is not a DNS answer.
                if (!isValidIPv4(d)) return DohParse(false, emptyList(), "IP نامعتبر در رکورد A: $d")
                out.add(d)
            }
        }
        return if (out.isEmpty()) DohParse(false, emptyList(), "بدون رکورد A در Answer") else DohParse(true, out, "")
    }

    private fun httpProbe(def: ProbeDef): ProbeResult {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(def.target).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "NetWatch/1.1")
            if (def.kind == "DoH") conn.setRequestProperty("Accept", "application/dns-json")
            conn.requestMethod = "GET"
            val code = conn.responseCode
            var body = ""
            try {
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder(); var line: String?; var n = 0
                while (br.readLine().also { line = it } != null && n < 5) { sb.append(line); n++ }
                br.close(); body = sb.toString()
            } catch (e: Exception) {}
            val ms = System.currentTimeMillis() - start
            // Captive portal: a generate_204 endpoint MUST answer 204. A 200
            // means a portal or proxy answered instead of the real service —
            // that is NOT a healthy result.
            val is204Endpoint = def.target.contains("generate_204")
            if (is204Endpoint && code == 200) return ProbeResult(def.id, def.name, def.detail, false, ms, "Captive Portal مشکوک (200 به‌جای 204)", def.domestic, def.category)
            if (code !in 200..399) return ProbeResult(def.id, def.name, def.detail, false, ms, "کد $code", def.domestic, def.category)
            // v2.9.5 — DoH: an HTTP 200 is NOT a passed DNS test. The JSON
            // answer must parse with Status==0 and a non-empty A Answer.
            if (def.kind == "DoH") {
                val parsed = parseDohJson(body)
                return if (parsed.ok) ProbeResult(def.id, def.name, def.detail, true, ms, "DoH OK • ${parsed.answers.size} پاسخ • ${parsed.answers.firstOrNull() ?: ""}", def.domestic, def.category)
                else ProbeResult(def.id, def.name, def.detail, false, ms, "DoH: ${parsed.error}", def.domestic, def.category)
            }
            return ProbeResult(def.id, def.name, def.detail, true, ms, when (def.id) { "ipv4" -> body.take(60); "ipv6" -> "IPv6 OK"; "cf" -> body.lines().firstOrNull { it.startsWith("ip=") } ?: ""; else -> "کد $code" }, def.domestic, def.category)
        } catch (e: Exception) {
            // v2.9.5 — classify the failure instead of only naming the
            // exception class: DNS resolve / connect timeout / TLS / TCP.
            val note = when (e) {
                is java.net.UnknownHostException -> "DNS resolve ناموفق"
                is java.net.SocketTimeoutException -> "Timeout اتصال/خواندن"
                is javax.net.ssl.SSLHandshakeException -> "TLS Handshake ناموفق"
                is javax.net.ssl.SSLException -> "TLS ناموفق"
                is java.net.ConnectException -> "TCP Connect ناموفق"
                else -> e.javaClass.simpleName
            }
            ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, note, def.domestic, def.category)
        } finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }

    private fun tcpProbe(def: ProbeDef): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val parts = def.target.split(":")
            // v2.9: `use` closes the socket on EVERY path — success, connect
            // failure, timeout, or any other exception. The old code called
            // close() only after a successful connect.
            Socket().use { sock ->
                sock.connect(InetSocketAddress(parts[0], parts[1].toInt()), 5000)
                ProbeResult(def.id, def.name, def.detail, true, System.currentTimeMillis() - start, "پورت باز", def.domestic, def.category)
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val note = if (msg.contains("reset", true)) "TCP Reset مشکوک" else if (msg.contains("refused", true)) "Connection refused" else e.javaClass.simpleName
            ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, note, def.domestic, def.category)
        }
    }

    private fun icmpProbe(def: ProbeDef): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val ms = Diagnostics.pingOnce(def.target)
            if (ms != null) ProbeResult(def.id, def.name, def.detail, true, System.currentTimeMillis() - start, "ICMP واقعی (system ping) ${"%.0f".format(ms)}ms", def.domestic, def.category)
            else if (Diagnostics.pingMissing) ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, "دستور ping در دسترس نیست — نه قطعی اینترنت", def.domestic, def.category)
            else ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, "بدون پاسخ ICMP (مسدود یا قطع)", def.domestic, def.category)
        } catch (e: Exception) { ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, e.javaClass.simpleName, def.domestic, def.category) }
    }

    private fun dnsProbe(def: ProbeDef): ProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val a = InetAddress.getByName(def.target)
            ProbeResult(def.id, def.name, def.detail, true, System.currentTimeMillis() - start, a.hostAddress ?: "", def.domestic, def.category)
        } catch (e: Exception) { ProbeResult(def.id, def.name, def.detail, false, System.currentTimeMillis() - start, e.javaClass.simpleName, def.domestic, def.category) }
    }

    fun runOne(def: ProbeDef): ProbeResult = when (def.kind) {
        "TCP" -> tcpProbe(def)
        "ICMP" -> icmpProbe(def)
        "DNS" -> dnsProbe(def)
        "DOT" -> { val host = def.target.substringBefore(":"); val t = Diagnostics.dotQuery(host); ProbeResult(def.id, def.name, def.detail, t.ok, t.ms, t.note, def.domestic, def.category) }
        "TLS" -> { val t = Diagnostics.tlsTime(def.target); ProbeResult(def.id, def.name, def.detail, t.ok, t.ms, t.note, def.domestic, def.category) }
        "UDPDNS" -> { val t = Diagnostics.udpDns(def.target); ProbeResult(def.id, def.name, def.detail, t.ok, t.ms, t.note, def.domestic, def.category) }
        else -> httpProbe(def)
    }

    // Waits for probe futures until a shared deadline. On timeout or on
    // interruption of the waiting thread (Stop button / onDestroy cancels the
    // calling Future), every still-running probe is cancelled so it cannot
    // keep the shared pool busy for the next cycle. Missing results become
    // honest "تایم‌اوت" entries — never silently dropped.
    private fun awaitProbes(futures: List<Future<*>>, timeoutSec: Long) {
        val deadline = System.nanoTime() + timeoutSec * 1_000_000_000L
        for (f in futures) {
            val rem = deadline - System.nanoTime()
            if (rem <= 0) break
            try { f.get(rem, TimeUnit.NANOSECONDS) } catch (e: Exception) { break }
        }
        for (f in futures) if (!f.isDone) NetExecutor.cancel(f)
    }

    fun runAll(): List<ProbeResult> {
        val results = arrayOfNulls<ProbeResult>(defs.size)
        // v2.8: probes run on the ONE shared pool (NetExecutor, 4 threads)
        // instead of a fresh 8-thread pool per check. Probes are I/O-bound
        // (network waits), so 4 threads still run them in parallel; the
        // calling thread blocks in awaitProbes while the pool works.
        val futures = defs.mapIndexed { i, def ->
            NetExecutor.submit {
                try { results[i] = runOne(def) } catch (e: Exception) {
                    results[i] = ProbeResult(def.id, def.name, def.detail, false, 0, e.javaClass.simpleName, def.domestic, def.category)
                }
            }
        }
        awaitProbes(futures, 30)
        return results.mapIndexed { i, r -> r ?: ProbeResult(defs[i].id, defs[i].name, defs[i].detail, false, 0, "تایم‌اوت", defs[i].domestic, defs[i].category) }
    }

    // Light background check: 8 probes that answer "is the internet up and
    // is international traffic flowing" — ICMP, TCP, DoH, HTTPS intl + domestic,
    // UDP DNS, IPv4, TLS. ~2-3s instead of the full 40+ probe run.
    private val lightIds = listOf("icmp-cf", "tcp-gg", "cfdns", "google", "aparat", "udp-cf", "ipv4", "tls-tg")
    fun runLight(): List<ProbeResult> {
        val light = defs.filter { it.id in lightIds }
        val results = arrayOfNulls<ProbeResult>(light.size)
        val futures = light.mapIndexed { i, def ->
            NetExecutor.submit {
                try { results[i] = runOne(def) } catch (e: Exception) {
                    results[i] = ProbeResult(def.id, def.name, def.detail, false, 0, e.javaClass.simpleName, def.domestic, def.category)
                }
            }
        }
        awaitProbes(futures, 10)
        return results.mapIndexed { i, r -> r ?: ProbeResult(light[i].id, light[i].name, light[i].detail, false, 0, "تایم‌اوت", light[i].domestic, light[i].category) }
    }

    data class Summary(val title: String, val sub: String, val color: String, val intlPct: Int, val domPct: Int, val confidence: Int, val cause: String)

    fun summarize(results: List<ProbeResult>, filtering: String): Summary {
        val ok = results.count { it.ok }; val total = results.size
        val intl = results.filter { !it.domestic }; val dom = results.filter { it.domestic }
        val intlPct = if (intl.isEmpty()) 0 else intl.count { it.ok } * 100 / intl.size
        val domPct = if (dom.isEmpty()) 0 else dom.count { it.ok } * 100 / dom.size
        val socialFail = results.filter { it.category == "social" && !it.ok }.size
        val intlOk = results.filter { it.id in listOf("cf", "cfdns", "gdns", "google", "github") }.count { it.ok }
        // confidence: filtering only if multiple independent methods fail while controls pass
        val conf = when {
            domPct >= 80 && intlPct < 40 -> 85
            socialFail >= 2 && intlOk >= 4 -> 80
            filtering.contains("TLS") -> 75
            filtering.contains("DNS") -> 70
            ok == total -> 95
            else -> 50
        }
        val cause = when {
            ok == total -> "همه‌ی تست‌ها موفق؛ اختلالی دیده نشد."
            domPct >= 80 && intlPct < 40 -> "علت احتمالی: قطعی اینترنت بین‌الملل در حالی که شبکه‌ی داخلی فعال است (اینترانت). اعتماد $conf٪."
            socialFail >= 2 && intlOk >= 4 -> "علت احتمالی: فیلترینگ پیام‌رسان‌ها/شبکه‌های اجتماعی؛ اینترنت بین‌المللی عمومی وصل است. اعتماد $conf٪."
            filtering.contains("DNS") -> "علت احتمالی: اختلال DNS — پاسخ DNS معمولی با DoH متفاوت است. اعتماد $conf٪."
            filtering.contains("TLS") -> "علت احتمالی: فیلتر SNI/TLS — اتصال TCP برقرار می‌شود ولی هندشیک TLS شکست می‌خورد. اعتماد $conf٪."
            ok == 0 -> "علت احتمالی: قطعی کامل یا بدون اتصال؛ وای‌فای/داده را چک کن."
            else -> "علت احتمالی: اختلال عمومی اینترنت؛ چند منبع هم‌زمان جواب نمی‌دهند."
        }
        val (title, sub, color) = when {
            ok == total -> Triple("سالم", "همه‌ی منبع‌ها جواب دادند.", "#22C55E")
            domPct >= 80 && intlPct < 40 -> Triple("اینترنت داخلی سالم — بین‌الملل قطع", "شبکه‌ی داخلی فعال است ولی دسترسی جهانی قطع شده.", "#F97316")
            ok * 100 / total >= 70 -> Triple("اختلال خفیف", "$ok از $total منبع جواب دادند.", "#F59E0B")
            ok * 100 / total >= 40 -> Triple("اختلال شدید", "فقط $ok از $total منبع جواب دادند.", "#F97316")
            else -> Triple("قطع یا بدون اتصال", "هیچ/کمتر منبعی جواب داد.", "#EF4444")
        }
        return Summary(title, sub, color, intlPct, domPct, conf, cause)
    }
}
