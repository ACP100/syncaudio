// File: SyncServer.kt (enhanced with detailed debugging)
package com.example.syncaudio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

object SyncServer {
    private const val PORT = 5001
    private var isRunning = false
    private var serverSocket: DatagramSocket? = null

    fun start() {
        if (isRunning) {
            DebugUtils.logWarning("⚠️ Server already running on port $PORT")
            return
        }

        DebugUtils.logNetwork("🚀 Starting SyncServer on port $PORT")
        isRunning = true

        Thread {
            try {
                serverSocket = DatagramSocket(PORT)
                DebugUtils.logSuccess("✅ Server started successfully on port $PORT")
                DebugUtils.logNetwork("📡 Listening for UDP packets...")
                DebugUtils.log("⚙️ Server socket timeout: ${serverSocket?.soTimeout}ms")
                DebugUtils.log("⚙️ Server socket broadcast: ${serverSocket?.broadcast}")

                val buffer = ByteArray(1024)
                var packetCount = 0

                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    DebugUtils.logNetwork("⏳ Waiting for client message... (packet #${++packetCount})")

                    serverSocket?.receive(packet)

                    val clientAddress = packet.address.hostAddress ?: "Unknown"
                    val clientPort = packet.port
                    val message = String(packet.data, 0, packet.length)

                    DebugUtils.logNetwork("📨 Received packet #$packetCount from $clientAddress:$clientPort")
                    DebugUtils.log("📝 Message: '$message' (${message.length} bytes)")

                    if (message.contains("Hello")) {
                        DebugUtils.logSuccess("🤝 Client connected successfully!")
                        DebugUtils.log("📊 Total packets received: $packetCount")

                        // Send acknowledgment back
                        val response = "ACK:Hello from Server!".toByteArray()
                        val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                        serverSocket?.send(responsePacket)
                        DebugUtils.logNetwork("📤 Sent ACK response to $clientAddress:$clientPort")
                    }
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ Server error on port $PORT", e)
                isRunning = false
            } finally {
                serverSocket?.close()
                DebugUtils.log("🔌 Server socket closed")
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        DebugUtils.log("🛑 Server stopped on port $PORT")
    }

    fun isServerRunning(): Boolean {
        return isRunning
    }

    fun getPort(): Int {
        return PORT
    }
}