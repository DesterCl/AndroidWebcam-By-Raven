package com.webcam.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            }
        } catch (e: Exception) {}
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
                intf.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false)
                        return addr.hostAddress ?: "?.?.?.?"
                }
            }
        } catch (e: Exception) {}
        return "?.?.?.?"
    }
}
