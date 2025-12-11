package com.example.syncaudio

import java.net.DatagramSocket

object PortTest {

    fun testDifferentPort(port: Int = 5555) {
        Thread {
            try {
                DebugUtils.log("🔧 Testing port $port...")
                val socket = DatagramSocket(port)
                DebugUtils.logSuccess("✅ Successfully bound to port $port")
                socket.close()
            } catch (e: Exception) {
                DebugUtils.logError("❌ Failed to bind to port $port: ${e.message}")
            }
        }.start()
    }
}