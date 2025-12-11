// File: SyncClient.kt (enhanced with detailed debugging)
package com.example.syncaudio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SyncClient {
    private const val PORT = 5001
    private var isConnecting = false
    private var connectionAttempts = 0

    fun connect() {
        if (isConnecting) {
            DebugUtils.logWarning("⚠️ Already connecting to server (attempt #${connectionAttempts + 1})")
            return
        }

        DebugUtils.logNetwork("🔗 Attempting to connect to server...")
        isConnecting = true
        connectionAttempts++

        Thread {
            try {
                val socket = DatagramSocket()
                DebugUtils.logNetwork("📡 Client socket created, timeout: ${socket.soTimeout}ms")

                // Try different broadcast addresses
                val addresses = listOf(
                    "255.255.255.255",
                    "192.168.1.255",  // Common home network
                    "10.0.0.255",      // Common office network
                    "127.0.0.1"        // Localhost
                )

                DebugUtils.log("🔍 Testing ${addresses.size} network addresses")

                for ((index, address) in addresses.withIndex()) {
                    try {
                        val serverAddress = InetAddress.getByName(address)
                        DebugUtils.logNetwork("📡 Attempt ${index + 1}: Trying address: $address")
                        DebugUtils.log("⚙️ Address info: ${serverAddress.hostName} | ${serverAddress.hostAddress}")

                        val message = "Hello Server from SyncAudio App - Attempt $connectionAttempts".toByteArray()
                        val packet = DatagramPacket(message, message.size, serverAddress, PORT)

                        socket.send(packet)
                        DebugUtils.logSuccess("📤 Packet sent to $address:$PORT (${message.size} bytes)")
                        DebugUtils.log("📝 Message: '${String(message)}'")

                        // Wait for response
                        val buffer = ByteArray(1024)
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        socket.soTimeout = 3000  // 3 second timeout

                        try {
                            DebugUtils.logNetwork("⏳ Waiting for server response (3s timeout)...")
                            socket.receive(responsePacket)

                            val response = String(responsePacket.data, 0, responsePacket.length)
                            val responseAddress = responsePacket.address.hostAddress ?: "Unknown"
                            val responsePort = responsePacket.port

                            DebugUtils.logSuccess("📥 Received response from $responseAddress:$responsePort")
                            DebugUtils.log("📝 Response: '$response' (${response.length} bytes)")

                            if (response.contains("ACK")) {
                                DebugUtils.logSuccess("✅ Server connection established successfully!")
                            }

                            break  // Success, stop trying addresses

                        } catch (e: java.net.SocketTimeoutException) {
                            DebugUtils.logWarning("⏰ Timeout waiting for response from $address")
                            DebugUtils.log("🔄 Trying next address...")
                        }
                    } catch (e: Exception) {
                        DebugUtils.logError("❌ Error with address $address: ${e.message}", e)
                    }
                }

                socket.close()
                DebugUtils.log("🔌 Client socket closed")
                DebugUtils.log("📊 Connection attempts: $connectionAttempts")

            } catch (e: Exception) {
                DebugUtils.logError("❌ Connection error: ${e.message}", e)
            } finally {
                isConnecting = false
                DebugUtils.log("🔄 Connection attempt completed")
            }
        }.start()
    }

    fun resetConnectionAttempts() {
        connectionAttempts = 0
        DebugUtils.log("🔄 Reset connection attempts counter")
    }

    fun getConnectionAttempts(): Int {
        return connectionAttempts
    }
}