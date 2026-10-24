package com.example.syncaudio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

object NetworkUtils {

    fun getLocalIpAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
            if (ip != "0.0.0.0") {
                DebugUtils.log("📱 Got IP from WiFiManager: $ip")
                ip
            } else {
                DebugUtils.log("📱 WiFiManager returned 0.0.0.0, trying fallback")
                getLocalIpAddressFallback()
            }
        } catch (e: Exception) {
            DebugUtils.logError("Error getting IP address from WiFiManager: ${e.message}")
            getLocalIpAddressFallback()
        }
    }

    private fun getLocalIpAddressFallback(): String? {
        try {
            DebugUtils.log("🔍 Starting fallback IP detection")
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.displayName

                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    DebugUtils.log("  ⏭️ Skipping interface $name (loopback or down)")
                    continue
                }

                DebugUtils.log("  🔍 Checking interface: $name")
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    DebugUtils.log("    📍 Found address: ${address.hostAddress}")

                    // Look for IPv4 addresses
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        DebugUtils.logSuccess("✅ Selected IP: $ip from interface $name")
                        return ip
                    }
                }
            }
        } catch (e: SocketException) {
            DebugUtils.logError("SocketException in getLocalIpAddressFallback: ${e.message}")
        }

        DebugUtils.logWarning("⚠️ No suitable IP address found")
        return null
    }

    fun getBroadcastAddress(ipAddress: String): String? {
        return try {
            DebugUtils.log("🔢 Calculating broadcast address for: $ipAddress")
            val parts = ipAddress.split(".")
            if (parts.size == 4) {
                val broadcast = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                DebugUtils.log("📡 Broadcast address: $broadcast")
                broadcast
            } else {
                DebugUtils.logError("❌ Invalid IP format: $ipAddress")
                null
            }
        } catch (e: Exception) {
            DebugUtils.logError("Error calculating broadcast address: ${e.message}")
            null
        }
    }

    fun getNetworkInfo(context: Context): String {
        val ip = getLocalIpAddress(context) ?: "Unknown"
        val broadcast = getBroadcastAddress(ip) ?: "Unknown"

        // Check network connectivity
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)

        val networkType = when {
            caps == null -> "No network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Unknown"
        }

        return """
            📱 Device IP: $ip
            📡 Broadcast: $broadcast
            🌐 Network Type: $networkType
            📶 Connected: ${network != null}
        """.trimIndent()
    }

    fun isOnSameNetwork(ip1: String?, ip2: String?): Boolean {
        if (ip1 == null || ip2 == null) return false

        return try {
            val parts1 = ip1.split(".")
            val parts2 = ip2.split(".")

            if (parts1.size == 4 && parts2.size == 4) {
                // Check if first 3 octets match (same subnet)
                parts1[0] == parts2[0] &&
                        parts1[1] == parts2[1] &&
                        parts1[2] == parts2[2]
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun pingAddress(ipAddress: String, timeout: Int = 1000): Boolean {
        return try {
            DebugUtils.log("🏓 Pinging $ipAddress with timeout ${timeout}ms")
            val address = InetAddress.getByName(ipAddress)
            // isReachable needs ICMP, which Android apps usually cannot send, so also ask
            // the other phone's TimeSync port, which every phone running this app answers
            val result = address.isReachable(timeout) || TimeSync.measureBestOffset(ipAddress, samples = 1) != null
            DebugUtils.log(if (result) "✅ $ipAddress is reachable" else "❌ $ipAddress is not reachable")
            result
        } catch (e: Exception) {
            DebugUtils.logError("Ping failed for $ipAddress: ${e.message}")
            false
        }
    }
}