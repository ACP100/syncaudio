package com.example.syncaudio

import java.net.DatagramSocket
import java.net.NetworkInterface

object SocketTest {

    fun testBasicSockets() {
        Thread {
            try {
                DebugUtils.log("🔌 Testing basic socket creation...")

                // Test 1: Create UDP socket
                val udpSocket = DatagramSocket()
                DebugUtils.logSuccess("✅ UDP socket created successfully")
                udpSocket.close()

                // Test 2: Create TCP socket
                try {
                    val tcpSocket = java.net.Socket()
                    DebugUtils.logSuccess("✅ TCP socket created successfully")
                    tcpSocket.close()
                } catch (e: Exception) {
                    DebugUtils.logError("❌ TCP socket failed: ${e.message}")
                }

                // Test 3: Get network interfaces
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    var count = 0
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (iface.isUp && !iface.isLoopback) {
                            count++
                            DebugUtils.log("📡 Found active interface: ${iface.displayName}")
                        }
                    }
                    DebugUtils.log("📊 Total active interfaces: $count")
                } catch (e: Exception) {
                    DebugUtils.logError("❌ Network interface scan failed: ${e.message}")
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ Socket test failed completely: ${e.message}", e)
            }
        }.start()
    }
}