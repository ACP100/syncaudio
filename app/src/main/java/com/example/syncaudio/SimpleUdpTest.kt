package com.example.syncaudio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SimpleUdpTest {

    fun testLocalUdp() {
        Thread {
            try {
                DebugUtils.log("🧪 Starting Simple UDP Test")

                // Test 1: Send to localhost
                val socket = DatagramSocket()
                socket.soTimeout = 3000

                val localAddress = InetAddress.getByName("127.0.0.1")
                val message = "TEST".toByteArray()
                val packet = DatagramPacket(message, message.size, localAddress, 5001)

                DebugUtils.log("📤 Sending UDP to 127.0.0.1:5001")
                socket.send(packet)

                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)

                try {
                    socket.receive(response)
                    DebugUtils.logSuccess("✅ Received response from localhost")
                } catch (e: java.net.SocketTimeoutException) {
                    DebugUtils.logWarning("⏰ No response from localhost (expected if no server)")
                }

                socket.close()

                // Test 2: Try to bind to port
                try {
                    val serverSocket = DatagramSocket(5001)
                    DebugUtils.logSuccess("✅ Can bind to UDP port 5001")
                    serverSocket.close()
                } catch (e: Exception) {
                    DebugUtils.logError("❌ Cannot bind to port 5001: ${e.message}")
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ UDP test failed: ${e.message}", e)
            }
        }.start()
    }
}