package com.aksell.netwatch

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

// Honest advanced diagnostics. Anything Android cannot measure is returned as Unknown, never faked.
object Advanced {
    data class Captive(val result: String)
    fun captivePortal(): Captive {
        return try {
            val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
            val code = c.responseCode; c.disconnect()
            when (code) { 204 -> Captive("✅ No captive portal (204)"); 200 -> Captive("⚠️ پاسخ 200 — احتمال Captive Portal یا پروکسی"); else -> Captive("⚠️ کد $code — احتمال Captive Portal / redirect") }
        } catch (e: Exception) { Captive("نامشخص — ${e.javaClass.simpleName} (ممکن است فیلتر باشد، نه Captive Portal)") }
    }

    fun publicIpChange(ctx: Context): String {
        val g = Geo.fetch()
        val old = History.lastIp(ctx)
        History.setLastIp(ctx, g.ip)
        return if (old.isBlank()) "IP عمومی فعلی: ${g.ip} (${g.city}, ${g.country}) — اولین ثبت"
        else if (old == g.ip) "IP عمومی بدون تغییر: ${g.ip}"
        else "⚠️ IP عمومی عوض شد: $old ← ${g.ip}"
    }

    // NAT type needs a STUN server exchange; Android has no built-in STUN API and no bundled STUN client.
    fun natType(): String = "NAT Type: Unknown — برای تشخیص دقیق NAT به STUN نیاز است؛ در این نسخه STUN پیاده نشده و عددی حدس زده نمی‌شود."

    fun portTest(host: String, port: Int): String {
        val t = Diagnostics.tcpTime(host, port)
        return "TCP $host:$port — ${if (t.ok) "✅ باز (${t.ms}ms)" else "❌ ${t.note}"}"
    }

    // Micro-lag: spikes in a short 10-packet burst; loaded jitter: jitter of ping while downloading.
    fun microLag(): String {
        val r = Gaming.pingHost("1.1.1.1", 10)
        return "Micro-lag (۱۰ بسته): میانگین ${"%.0f".format(r.avgMs)}ms • اسپایک ${r.spikes} • جیتر ${"%.1f".format(r.jitterMs)}ms • Burst Loss ${r.burstLoss}"
    }
    fun loadedJitter(): String {
        val bb = Gaming.bufferbloat()
        return "RTT تحت بار: Idle ${"%.0f".format(bb.idleMs)}ms • Loaded ${"%.0f".format(bb.loadedMs)}ms • افزایش ${"%.0f".format(bb.increaseMs)}ms • درجه ${bb.grade}"
    }
    // Packet reordering cannot be measured with the system ping command (no sequence timing exposed) — say so.
    fun reordering(): String = "Packet Reordering: نامشخص — دستور ping سیستم زمان‌بندی توالی بسته‌ها را نمی‌دهد؛ بدون Raw Socket (نیاز به Root) قابل اندازه‌گیری دقیق نیست."
}
