package com.aksell.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

// NetworkInfo (v2.6): local addresses come from ConnectivityManager /
// LinkProperties of the ACTIVE network — not from WifiManager.dhcpInfo,
// which is Wi-Fi-only and returns "—" on mobile data. Wi-Fi and mobile
// details are reported separately. API 21-34 compatible.
object NetworkInfo {
    private fun linkProps(ctx: Context): LinkProperties? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            cm.getLinkProperties(net)
        } catch (e: Exception) { null }
    }

    private fun dhcpIp(ctx: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val n = wm.dhcpInfo.ipAddress
            if (n == 0) "—" else "${n and 0xff}.${(n shr 8) and 0xff}.${(n shr 16) and 0xff}.${(n shr 24) and 0xff}"
        } catch (e: Exception) { "—" }
    }

    fun localIp(ctx: Context): String {
        val lp = linkProps(ctx)
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address?.hostAddress
        if (v4 != null) return v4
        val v6 = lp?.linkAddresses?.firstOrNull { it.address is Inet6Address && !it.address.isLoopbackAddress }?.address?.hostAddress
        if (v6 != null) return v6
        // Fallback: network interfaces (works without an active network object).
        try {
            val ips = NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filter { !it.isLoopbackAddress && it is Inet4Address }
                ?.map { it.hostAddress ?: "" } ?: emptyList()
            if (ips.isNotEmpty()) return ips.first()
        } catch (e: Exception) {}
        return dhcpIp(ctx)
    }

    fun gateway(ctx: Context): String {
        val lp = linkProps(ctx)
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        if (gw != null) return gw
        return try {
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val n = wm.dhcpInfo.gateway
            if (n == 0) "—" else "${n and 0xff}.${(n shr 8) and 0xff}.${(n shr 16) and 0xff}.${(n shr 24) and 0xff}"
        } catch (e: Exception) { "—" }
    }

    fun dns(ctx: Context): String {
        val lp = linkProps(ctx)
        val list = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        if (list.isNotEmpty()) return list.joinToString(" ، ")
        return try {
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val n = wm.dhcpInfo.dns1
            if (n != 0) "${n and 0xff}.${(n shr 8) and 0xff}.${(n shr 16) and 0xff}.${(n shr 24) and 0xff}" else "—"
        } catch (e: Exception) { "—" }
    }

    fun wifiInfo(ctx: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo ?: return "Wi-Fi متصل نیست"
            val freq = info.frequency
            val band = when { freq in 2400..2500 -> "2.4GHz"; freq in 4900..5900 -> "5GHz"; freq in 5900..7200 -> "6GHz"; else -> "$freq MHz" }
            "Wi-Fi: ${info.ssid} • باند $band • سیگنال ${info.rssi} dBm • سرعت لینک ${info.linkSpeed} Mbps"
        } catch (e: Exception) { "Wi-Fi در دسترس نیست" }
    }

    fun mobileInfo(ctx: Context): String {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "موبایل در دسترس نیست"
            val gen = when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                else -> "نامشخص (${tm.networkType})"
            }
            "موبایل: $gen • اپراتور: ${try { tm.networkOperatorName } catch (e: Exception) { "—" }}"
        } catch (e: Exception) { "موبایل در دسترس نیست" }
    }

    fun summary(ctx: Context): String {
        val sb = StringBuilder()
        val lp = linkProps(ctx)
        val v4 = lp?.linkAddresses?.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }?.map { it.address.hostAddress ?: "" } ?: emptyList()
        val v6 = lp?.linkAddresses?.filter { it.address is Inet6Address && !it.address.isLoopbackAddress }?.map { it.address.hostAddress ?: "" } ?: emptyList()
        if (v4.isNotEmpty()) sb.append("آی‌پی خصوصی (IPv4): ${v4.joinToString(", ")}\n")
        if (v6.isNotEmpty()) sb.append("آی‌پی خصوصی (IPv6): ${v6.joinToString(", ")}\n")
        val gw = gateway(ctx)
        if (gw != "—") sb.append("گیت‌وی: $gw\n")
        val dns = dns(ctx)
        if (dns != "—") sb.append("DNS فعال: $dns\n")
        val type = Geo.connectionType(ctx)
        sb.append("نوع اتصال: $type\n")
        if (type.startsWith("Wi-Fi")) sb.append(wifiInfo(ctx)).append("\n")
        if (type.contains("4G") || type.contains("5G") || type.contains("Mobile") || type.contains("داده")) sb.append(mobileInfo(ctx)).append("\n")
        val vpn = Geo.vpnOn(ctx)
        sb.append(if (vpn) "VPN: روشن" else "VPN: خاموش")
        if (sb.isEmpty()) sb.append("اطلاعات شبکه محلی در دسترس نیست.")
        return sb.toString().trim()
    }
}
