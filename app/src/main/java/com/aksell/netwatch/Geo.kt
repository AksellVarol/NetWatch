package com.aksell.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GeoInfo(val ip: String, val country: String, val countryCode: String, val city: String, val isp: String, val ok: Boolean)

object Geo {
    // ip-api.com رایگان و بدون کلید است؛ موقعیت آی‌پی فعلی را می‌دهد.
    // اگر VPN روشن باشد، این همان لوکیشن خروجی VPN است؛ اگر خاموش باشد، لوکیشن واقعی اپراتور.
    fun fetch(): GeoInfo {
        // Primary: ip-api.com (free, no key). Fallback: ipapi.co (free, no key)
        // when ip-api does not answer — common on filtered routes / VPN exits.
        // Both failing still shows an honest "not available", never a fake location.
        return fetchIpApi() ?: fetchIpapiCo() ?: GeoInfo("", "", "", "", "", false)
    }

    private fun fetchIpApi(): GeoInfo? {
        return try {
            val conn = URL("http://ip-api.com/json/?fields=status,country,countryCode,city,isp,query").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val br = BufferedReader(InputStreamReader(conn.inputStream))
            val body = br.readText(); br.close()
            if (!body.contains("\"success\"")) return null
            fun f(k: String): String = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""
            GeoInfo(f("query"), f("country"), f("countryCode"), f("city"), f("isp"), true)
        } catch (e: Exception) { null }
    }

    private fun fetchIpapiCo(): GeoInfo? {
        return try {
            val conn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val br = BufferedReader(InputStreamReader(conn.inputStream))
            val body = br.readText(); br.close()
            fun f(k: String): String = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""
            val ip = f("ip"); if (ip.isBlank()) return null
            GeoInfo(ip, f("country_name"), f("country_code"), f("city"), f("org"), true)
        } catch (e: Exception) { null }
    }

    fun operatorName(ctx: Context): String {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val op = tm?.networkOperatorName ?: ""
            if (op.isNotBlank()) op else "—"
        } catch (e: Exception) { "—" }
    }

    fun connectionType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (Build.VERSION.SDK_INT >= 23 && net != null) cm.getNetworkCapabilities(net) else null
            when {
                caps == null -> "—"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val freq = try {
                        @Suppress("DEPRECATION")
                        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo.frequency
                    } catch (e: Exception) { 0 }
                    if (freq >= 5000) "Wi-Fi (5 GHz)" else if (freq > 0) "Wi-Fi (2.4 GHz)" else "Wi-Fi"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    when (tm?.networkType) {
                        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                        TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                        else -> "Mobile Data"
                    }
                }
                else -> "—"
            }
        } catch (e: Exception) { "—" }
    }

    fun vpnOn(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else false
        } catch (e: Exception) { false }
    }

    fun connectionInfo(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (Build.VERSION.SDK_INT >= 23 && net != null) cm.getNetworkCapabilities(net) else null
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val op = try { tm?.networkOperatorName ?: "" } catch (e: Exception) { "" }
            // Underlying type: from network capabilities when available, otherwise
            // from TelephonyManager — mobile data is still determinable when the
            // active network read returns nothing (e.g. while a VPN tunnel is up).
            val underlying: String? = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> when (tm?.networkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                    else -> "داده همراه"
                }
                tm?.networkType == TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                tm?.networkType == TelephonyManager.NETWORK_TYPE_NR -> "5G"
                tm?.networkType == TelephonyManager.NETWORK_TYPE_HSPAP || tm?.networkType == TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                tm != null && tm.simState == TelephonyManager.SIM_STATE_READY -> "داده همراه"
                else -> null
            }
            val vpn = vpnOn(ctx)
            val type = when {
                vpn && underlying != null -> "VPN ($underlying)"
                vpn -> "VPN"
                underlying != null -> underlying
                else -> "نامشخص"
            }
            val opSuffix = if (!vpn && op.isNotBlank()) " • $op" else ""
            val vpnSuffix = if (vpn) " • VPN روشن" else " • VPN خاموش"
            type + opSuffix + vpnSuffix
        } catch (e: Exception) { "نامشخص" }
    }
}
