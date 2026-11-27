package com.aksell.netwatch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

data class PingStats(val host: String, val ok: Boolean, val avgMs: Double, val minMs: Double, val maxMs: Double, val lossPct: Int, val jitterMs: Double, val raw: String, val completed: Boolean = true)
data class Timed(val ok: Boolean, val ms: Long, val note: String)

object Diagnostics {
    // Bounded command runner: the output is read on a separate thread (so a full
    // buffer can never deadlock the process) and the process is forcibly killed
    // after timeoutSec — a command can never run longer than ~timeoutSec + 2s.
    data class CmdRun(val out: String, val timedOut: Boolean, val startFailed: Boolean)
    fun runCmdDetailed(vararg args: String, timeoutSec: Int = 8): CmdRun {
        return try {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    val br = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (br.readLine().also { line = it } != null) { sb.append(line).append("\n") }
                } catch (e: Exception) {}
            }
            reader.start()
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                try { p.destroyForcibly() } catch (e: Exception) {}
                try { p.waitFor(1, TimeUnit.SECONDS) } catch (e: Exception) {}
            }
            reader.join(1000)
            CmdRun(sb.toString(), !finished, false)
        } catch (e: Exception) { CmdRun("", false, true) }
    }
    private fun runCmd(vararg args: String, timeoutSec: Int = 8): String = runCmdDetailed(*args, timeoutSec = timeoutSec).out

    // True when the system ping binary is missing (not "no internet" — a
    // different failure, reported as such wherever ping is used).
    @Volatile var pingMissing: Boolean = false
        private set

    // Bounded ping run: returns every reply time, whether the process finished
    // in time, and whether the ping binary itself is missing. Never blocks
    // longer than count*2 + 5 seconds.
    data class PingRun(val times: List<Double>, val completed: Boolean, val binaryMissing: Boolean, val raw: String)
    fun pingLines(host: String, count: Int): PingRun {
        val r = runCmdDetailed("ping", "-c", count.toString(), "-W", "2", host, timeoutSec = count * 2 + 5)
        if (r.startFailed) { pingMissing = true; return PingRun(emptyList(), false, true, "دستور ping در دسترس نیست") }
        val times = Regex("time=([0-9.]+)").findAll(r.out).mapNotNull { it.groupValues.get(1).toDoubleOrNull() }.toList()
        return PingRun(times, !r.timedOut, false, r.out)
    }

    // One single ICMP ping, for live updates. Returns the RTT in ms, or null on timeout.
    fun pingOnce(host: String = "1.1.1.1"): Double? {
        val r = runCmdDetailed("ping", "-c", "1", "-W", "1", host, timeoutSec = 3)
        if (r.startFailed) { pingMissing = true; return null }
        return Regex("time=([0-9.]+)").find(r.out)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun pingStats(host: String): PingStats {
        val out = runCmd("ping", "-c", "4", "-W", "2", host)
        if (out.isBlank()) return PingStats(host, false, 0.0, 0.0, 0.0, 100, 0.0, "دستور ping در دسترس نیست یا جواب نداد", completed = false)
        val loss = Regex("(\\d+)% packet loss").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 100
        val rtt = Regex("rtt min/avg/max/mdev = ([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)").find(out)
        return if (rtt != null) {
            PingStats(host, loss < 100, rtt.groupValues[2].toDouble(), rtt.groupValues[1].toDouble(), rtt.groupValues[3].toDouble(), loss, rtt.groupValues[4].toDouble(), out.trim())
        } else PingStats(host, loss < 100, 0.0, 0.0, 0.0, loss, 0.0, out.trim())
    }

    // Real UDP DNS query with a random transaction ID — no JVM/system DNS cache
    // is involved, so repeated measurements give real, differing times.
    private fun buildDnsQuery(host: String, id: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(((id shr 8) and 0xFF).toByte())
        out.add((id and 0xFF).toByte())
        out.add(0x01); out.add(0x00)                 // flags: RD
        out.add(0x00); out.add(0x01)                 // QDCOUNT = 1
        repeat(6) { out.add(0x00) }                  // AN, NS, AR = 0
        for (label in host.split(".").filter { it.isNotEmpty() }) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= 63) { "برچسب DNS بیش از ۶۳ بایت است" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0x00)
        out.add(0x00); out.add(0x01)                 // QTYPE = A
        out.add(0x00); out.add(0x01)                 // QCLASS = IN
        return out.toByteArray()
    }

    fun udpDns(serverIp: String, hostname: String = "google.com"): Timed {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        return try {
            val id = Random.nextInt(0, 0xFFFF)
            val query = buildDnsQuery(hostname, id)
            DatagramSocket().use { sock ->
                sock.soTimeout = 4000
                sock.send(DatagramPacket(query, query.size, InetAddress.getByName(serverIp), 53))
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val ms = elapsedMs()
                if (pkt.length < 12) return Timed(false, ms, "پاسخ DNS کوتاه است")
                val rid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val isResponse = (buf[2].toInt() and 0x80) != 0
                val rcode = buf[3].toInt() and 0x0F
                return when {
                    rid != id -> Timed(false, ms, "ID پاسخ مطابقت ندارد")
                    !isResponse -> Timed(false, ms, "پیام دریافتی پاسخ DNS نیست")
                    rcode == 0 -> Timed(true, ms, "پاسخ موفق DNS")
                    rcode == 3 -> Timed(false, ms, "NXDOMAIN: دامنه وجود ندارد")
                    else -> Timed(false, ms, "خطای DNS با کد $rcode")
                }
            }
        } catch (e: SocketTimeoutException) {
            Timed(false, elapsedMs(), "timeout: پاسخ DNS نرسید")
        } catch (e: Exception) {
            Timed(false, elapsedMs(), e.javaClass.simpleName)
        }
    }

    // DNS resolution time on a specific resolver (default Cloudflare 1.1.1.1).
    // A real UDP query is sent, so the system DNS cache cannot fake a 0ms result.
    // An IP literal needs no resolution at all.
    fun dnsTime(host: String, resolver: String = "1.1.1.1"): Timed {
        // An IP literal needs no resolution — that is N/A, NOT a 0ms DNS success.
        // Callers that test IP hosts must not count this as a passed DNS test.
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return Timed(false, 0, "N/A — IP مستقیم نیازی به resolve ندارد")
        return udpDns(resolver, host)
    }

    // Real DNS-over-TLS: TLS handshake to :853, then a real DNS query over the
    // TLS stream (2-byte length prefix + DNS message, response validated).
    // This is a genuine DoT test — not a bare TCP connect to port 853.
    fun dotQuery(serverIp: String, hostname: String = "google.com"): Timed {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(serverIp, 853), 5000)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, serverIp, 853, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = 5000
                    ssl.startHandshake()
                    val id = Random.nextInt(0, 0xFFFF)
                    val query = buildDnsQuery(hostname, id)
                    val out = ssl.outputStream
                    out.write(byteArrayOf(((query.size shr 8) and 0xFF).toByte(), (query.size and 0xFF).toByte()))
                    out.write(query); out.flush()
                    val input = ssl.inputStream
                    val lenBytes = ByteArray(2)
                    var read = 0
                    while (read < 2) { val n = input.read(lenBytes, read, 2 - read); if (n < 0) return Timed(false, elapsedMs(), "DoT: پاسخ ناقص"); read += n }
                    val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                    if (len <= 0 || len > 4096) return Timed(false, elapsedMs(), "DoT: طول پاسخ نامعتبر")
                    val resp = ByteArray(len)
                    var got = 0
                    while (got < len) { val n = input.read(resp, got, len - got); if (n < 0) break; got += n }
                    if (got < 12) return Timed(false, elapsedMs(), "DoT: پاسخ کوتاه")
                    val rid = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
                    if (rid != id) return Timed(false, elapsedMs(), "DoT: ID پاسخ مطابقت ندارد")
                    val rcode = resp[3].toInt() and 0x0F
                    if (rcode == 0) Timed(true, elapsedMs(), "DoT واقعی: TLS + DNS روی 853") else Timed(false, elapsedMs(), "DoT: خطای DNS با کد $rcode")
                }
            }
        } catch (e: SocketTimeoutException) {
            Timed(false, elapsedMs(), "DoT: timeout در اتصال یا هندشیک")
        } catch (e: SSLHandshakeException) {
            Timed(false, elapsedMs(), "DoT: خطای TLS Handshake")
        } catch (e: Exception) {
            Timed(false, elapsedMs(), "DoT: ${e.javaClass.simpleName}")
        }
    }

    fun tcpTime(host: String, port: Int): Timed {
        val s = System.currentTimeMillis()
        return try {
            // v2.9: `use` — same fix as Probes.tcpProbe: the socket is closed
            // on every path, not only after a successful connect.
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 5000)
                Timed(true, System.currentTimeMillis() - s, "متصل شد")
            }
        } catch (e: Exception) { Timed(false, System.currentTimeMillis() - s, e.message ?: e.javaClass.simpleName) }
    }

    // TLS handshake with a bounded TCP connect: a plain socket is connected first
    // (5s timeout), then wrapped in TLS. All sockets are closed via `use`, even
    // when the handshake fails. TCP and TLS times are reported separately.
    fun tlsTime(host: String, port: Int = 443, timeoutMs: Int = 5000): Timed {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                val tcpMs = elapsedMs()
                val tlsStart = System.nanoTime()
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                }
                val tlsMs = (System.nanoTime() - tlsStart) / 1_000_000
                Timed(true, elapsedMs(), "TCP ${tcpMs}ms + TLS ${tlsMs}ms")
            }
        } catch (e: SocketTimeoutException) {
            Timed(false, elapsedMs(), "timeout در اتصال یا هندشیک")
        } catch (e: SSLHandshakeException) {
            Timed(false, elapsedMs(), "خطای TLS Handshake")
        } catch (e: java.net.ConnectException) {
            Timed(false, elapsedMs(), "اتصال TCP رد شد")
        } catch (e: java.net.SocketException) {
            val msg = e.message ?: ""
            val kind = if (msg.contains("reset", true)) "TCP Reset" else "خطای سوکت: $msg"
            Timed(false, elapsedMs(), kind)
        } catch (e: Exception) {
            Timed(false, elapsedMs(), "خطای نامشخص: ${e.javaClass.simpleName}")
        }
    }

    // QUIC/HTTP3 tri-state: Success / Failed / Unknown. A reply counts as Success
    // ONLY if it has a valid QUIC long-header structure (first bit set, version
    // field present). Any other UDP reply is Unknown, never Success.
    data class QuicResult(val state: String, val reason: String)
    fun quicStatus(host: String): QuicResult {
        val t = quicUdp(host)
        return if (t.ok) QuicResult("Success", t.note)
        else QuicResult("Unknown", t.note)
    }
    fun quicUdp(host: String): Timed {
        val s = System.currentTimeMillis()
        return try {
            // v2.9: `use` closes the DatagramSocket on EVERY path. The old
            // code closed it on the receive paths, but a getByName/send
            // failure before the inner try leaked it.
            DatagramSocket().use { sock ->
                sock.soTimeout = 1500
                val data = ByteArray(32) { 0x00 }
                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(host), 443))
                val buf = ByteArray(512)
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val ms = System.currentTimeMillis() - s
                    if (pkt.length < 5 || (buf[0].toInt() and 0x80) == 0) {
                        Timed(false, ms, "پاسخ UDP رسید ولی ساختار QUIC معتبر نداشت — Success نیست")
                    } else {
                        val version = ((buf[1].toInt() and 0xFF) shl 24) or ((buf[2].toInt() and 0xFF) shl 16) or ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                        if (version == 0) Timed(true, ms, "پاسخ QUIC معتبر: Version Negotiation")
                        else Timed(true, ms, "پاسخ QUIC معتبر: Long Header")
                    }
                } catch (e: Exception) {
                    Timed(false, System.currentTimeMillis() - s, "نامشخص — ارسال UDP 443 انجام شد ولی بدون Handshake کامل QUIC پاسخی نمی‌رسد؛ این شکست نیست")
                }
            }
        } catch (e: Exception) { Timed(false, System.currentTimeMillis() - s, e.javaClass.simpleName) }
    }

    fun mtuTest(host: String = "1.1.1.1"): String {
        val recv = Regex("(\\d+)\\s+received")
        var smallOk = false; var bigOk = false; var unsupported = false; var anyReply = false
        for (h in listOf(host, "8.8.8.8")) {
            repeat(2) {
                val small = runCmd("ping", "-c", "1", "-W", "2", "-s", "100", "-M", "do", h, timeoutSec = 4)
                val big = runCmd("ping", "-c", "1", "-W", "2", "-s", "1400", "-M", "do", h, timeoutSec = 4)
                if (small.contains("bad option", true) || small.contains("invalid option", true) ||
                    big.contains("bad option", true) || big.contains("invalid option", true)) unsupported = true
                val sN = recv.find(small)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bN = recv.find(big)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (sN > 0) { smallOk = true; anyReply = true }
                if (bN > 0) { bigOk = true; anyReply = true }
            }
        }
        return when {
            unsupported -> "قابل بررسی نیست (-M do روی این گوشی پشتیبانی نمی‌شود)"
            !anyReply -> "پینگ جواب نداد — MTU قابل بررسی نیست"
            smallOk && bigOk -> "سالم: بسته‌ی ۱۴۰۰ بایتی بدون فرگمنت عبور کرد"
            smallOk && !bigOk -> "مشکوک به MTU: بسته‌ی کوچک عبور کرد ولی ۱۴۰۰ بایتی با Don't Fragment شکست خورد — احتمال MTU کمتر از ۱۴۰۰"
            else -> "پینگ کوچک هم جواب نداد — MTU قابل بررسی نیست"
        }
    }

    fun traceroute(host: String = "8.8.8.8"): String {
        val sb = StringBuilder()
        for (ttl in 1..8) {
            val out = runCmd("ping", "-c", "1", "-W", "1", "-t", ttl.toString(), host, timeoutSec = 4)
            if (out.isBlank()) { sb.append("$ttl. * (پاسخ نداد)\n"); continue }
            if (out.contains("bad option", true) || out.contains("invalid option", true) || out.contains("unrecognized", true)) {
                return "ping -t روی این گوشی پشتیبانی نمی‌شود — traceroute در دسترس نیست"
            }
            // TTL-exceeded lines say "From <ip> ... Time to live exceeded" (capital F,
            // no time= field), so match case-insensitively and measure the hop's RTT
            // with a separate direct ping to that hop's IP.
            val from = Regex("(?i)from\\s+([0-9.]+)").find(out)?.groupValues?.get(1)
            if (from == null) {
                sb.append("$ttl. * (بدون پاسخ)\n")
            } else {
                val rtt = runCmd("ping", "-c", "1", "-W", "1", from, timeoutSec = 3)
                val time = Regex("time=([0-9.]+)").find(rtt)?.groupValues?.get(1)
                sb.append("$ttl. $from — ${time ?: "?"}ms\n")
                if (from == host) break
            }
        }
        val r = sb.toString()
        return if (r.isBlank()) "traceroute در این گوشی در دسترس نیست" else r
    }

    // DoH answer IPs for a hostname, for DNS-poisoning comparison.
    // Only type 1 (A) records are returned; CNAMEs are excluded.
    fun dohIps(dohUrl: String, host: String): List<String> {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = "$dohUrl?name=" + URLEncoder.encode(host, "UTF-8") + "&type=A"
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/dns-json")
            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val ans = org.json.JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until ans.length()) {
                val o = ans.optJSONObject(i) ?: continue
                if (o.optInt("type", -1) == 1) {
                    val d = o.optString("data", "")
                    if (d.isNotBlank()) out.add(d)
                }
            }
            out
        } catch (e: Exception) { emptyList() } finally { try { conn?.disconnect() } catch (e: Exception) {} }
    }
}
