// File: SyncServer.kt (enhanced with detailed debugging)
package com.example.syncaudio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SyncServer {
    private const val PORT = 5001
    private var isRunning = false
    private var serverSocket: DatagramSocket? = null
    private var packetCount = 0

    fun start() {
        if (isRunning) {
            DebugUtils.logWarning("⚠️ Server already running on port $PORT")
            return
        }

        DebugUtils.logNetwork("🚀 Starting SyncServer on port $PORT")
        DebugUtils.log("📊 Current packet count: $packetCount")
        isRunning = true

        Thread {
            try {
                try {
                    // Try to bind to all network interfaces
                    serverSocket = DatagramSocket(PORT, InetAddress.getByName("0.0.0.0"))
                    DebugUtils.logSuccess("✅ Server bound to all interfaces (0.0.0.0:$PORT)")
                } catch (e: Exception) {
                    DebugUtils.logError("❌ Failed to bind to 0.0.0.0, trying default...", e)
                    serverSocket = DatagramSocket(PORT)
                }
                serverSocket?.broadcast = true // Enable broadcast reception
                DebugUtils.logSuccess("✅ Server started successfully on port $PORT")
                DebugUtils.logNetwork("📡 Listening for UDP packets...")
                DebugUtils.log("⚙️ Server socket timeout: ${serverSocket?.soTimeout}ms")
                DebugUtils.log("⚙️ Server socket broadcast enabled: ${serverSocket?.broadcast}")
                DebugUtils.log("⚙️ Server socket reuse address: ${serverSocket?.reuseAddress}")
                DebugUtils.log("⚙️ Server bound to: ${serverSocket?.localAddress}:${serverSocket?.localPort}")

                val buffer = ByteArray(1024)

                while (isRunning && serverSocket != null) {
                    val packet = DatagramPacket(buffer, buffer.size)

                    try {
                        serverSocket?.receive(packet)
                        // Clients poll several times a second, so answer quietly
                        if (packet.length >= 4 && String(packet.data, 0, 4) == "POLL") {
                            val request = String(packet.data, 0, packet.length)
                            val reply = AudioSyncManager.pollReply(packet.address.hostAddress ?: "", request).toByteArray()
                            serverSocket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            continue
                        }
                        // Joined phones asking whether party mode is on
                        if (packet.length == 5 && String(packet.data, 0, 5) == "PARTY") {
                            if (AudioSyncManager.isHosting) {
                                val reply = AudioSyncManager.partyReply().toByteArray()
                                serverSocket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            }
                            continue
                        }
                        // Joined phones asking which song comes next, to fetch it early
                        if (packet.length == 4 && String(packet.data, 0, 4) == "NEXT") {
                            if (AudioSyncManager.isHosting) {
                                val reply = "NEXT|${AudioSyncManager.nextSongName()}".toByteArray()
                                serverSocket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            }
                            continue
                        }
                        // Joining phones looking for hosts: answer with this phone's name
                        if (packet.length == 8 && String(packet.data, 0, 8) == "DISCOVER") {
                            if (AudioSyncManager.isHosting) {
                                val reply = "HOST|${AudioSyncManager.deviceName()}|${AudioSyncManager.joinedPhoneCount()}".toByteArray()
                                serverSocket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            }
                            continue
                        }
                        packetCount++

                        val clientAddress = packet.address.hostAddress ?: "Unknown"
                        val clientPort = packet.port
                        val message = String(packet.data, 0, packet.length)

                        DebugUtils.logNetwork("📨 Received packet #$packetCount from $clientAddress:$clientPort")
                        DebugUtils.log("📝 Message: '$message' (${message.length} bytes)")
                        DebugUtils.log("📍 Client details: ${packet.address} port $clientPort")

                        if (message.contains("Hello")) {
                            DebugUtils.logSuccess("🤝 Client connected successfully!")
                            AudioSyncManager.addPeer(clientAddress)
                            DebugUtils.log("📊 Total packets received: $packetCount")
                            DebugUtils.log("🏠 Server IP: ${InetAddress.getLocalHost().hostAddress}")

                            // Send acknowledgment back
                            val response = "ACK:Hello from Server! Packet #$packetCount".toByteArray()
                            val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                            serverSocket?.send(responsePacket)
                            DebugUtils.logNetwork("📤 Sent ACK response to $clientAddress:$clientPort")
                            DebugUtils.log("📤 Response size: ${response.size} bytes")
                            DebugUtils.log("📤 Response: '${String(response)}'")
                        } else {
                            DebugUtils.logWarning("⚠️ Unknown message type from $clientAddress")
                        }

                    } catch (e: Exception) {
                        if (isRunning) {
                            DebugUtils.logError("❌ Error receiving packet: ${e.message}", e)
                        } else {
                            DebugUtils.log("🔌 Server stopped receiving")
                        }
                    }
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ Server error on port $PORT", e)
                isRunning = false
            } finally {
                serverSocket?.close()
                DebugUtils.log("🔌 Server socket closed")
                DebugUtils.log("📊 Final packet count: $packetCount")
            }
        }.start()
    }

    fun stop() {
        DebugUtils.log("🛑 Stopping server...")
        isRunning = false
        serverSocket?.close()
        DebugUtils.logSuccess("✅ Server stopped on port $PORT")
    }

    fun isServerRunning(): Boolean {
        return isRunning
    }

    fun getPort(): Int {
        return PORT
    }

    fun getPacketCount(): Int {
        return packetCount
    }
}