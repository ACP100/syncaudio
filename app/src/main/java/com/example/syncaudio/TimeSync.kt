// File: TimeSync.kt (updated with peer sync capability)
package com.example.syncaudio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToLong

object TimeSync {
    private const val PORT = 5002
    private var serverRunning = false
    private var serverSocket: DatagramSocket? = null

    fun getOffset(serverIp: String): Long {
        DebugUtils.log("⏰ Starting time synchronization with $serverIp")
        DebugUtils.logNetwork("📡 TimeSync client connecting to port $PORT")

        val socket = DatagramSocket()
        socket.soTimeout = 3000 // 3 second timeout
        val addr = InetAddress.getByName(serverIp)

        val t0 = System.currentTimeMillis()
        DebugUtils.log("⏱️  Client send time (t0): $t0 ms")

        val buf = "SYNC".toByteArray()
        DebugUtils.log("📤 Sending SYNC request to $serverIp:$PORT")

        try {
            socket.send(DatagramPacket(buf, buf.size, addr, PORT))
            DebugUtils.logSuccess("✅ SYNC request sent")

            val response = ByteArray(64)
            val packet = DatagramPacket(response, response.size)

            DebugUtils.logNetwork("⏳ Waiting for time server response...")
            socket.receive(packet)

            val t3 = System.currentTimeMillis()
            DebugUtils.log("⏱️  Client receive time (t3): $t3 ms")

            val responseStr = String(packet.data, 0, packet.length).trim()
            DebugUtils.log("📥 Received response: '$responseStr' (${responseStr.length} chars)")

            val t1t2 = responseStr.split(",")

            if (t1t2.size < 2) {
                DebugUtils.logError("❌ Invalid response from time server: '$responseStr'")
                return 0L
            }

            val t1 = t1t2[0].toLong()
            val t2 = t1t2[1].toLong()

            DebugUtils.log("📊 Time values: t0=$t0, t1=$t1, t2=$t2, t3=$t3")
            DebugUtils.log("📐 Calculating round-trip delay: (t3-t0) - (t2-t1) = ${(t3-t0) - (t2-t1)}")

            // NTP-style offset calculation
            val offset = (((t1 - t0) + (t2 - t3)) / 2.0).roundToLong()

            DebugUtils.logSuccess("✅ Time offset calculated: $offset ms")
            DebugUtils.log("📈 Client is ${
                if (offset > 0) "ahead of" else "behind"
            } server by ${Math.abs(offset)} ms")

            socket.close()
            DebugUtils.log("🔌 TimeSync socket closed")

            return offset
        } catch (e: Exception) {
            DebugUtils.logError("❌ Error in time sync: ${e.message}", e)
            socket.close()
            throw e
        }
    }

    fun startServer() {
        if (serverRunning) {
            DebugUtils.logWarning("⚠️ TimeSync server already running")
            return
        }

        DebugUtils.logNetwork("🚀 Starting TimeSync server on port $PORT")
        serverRunning = true

        Thread {
            try {
                serverSocket = DatagramSocket(PORT)
                serverSocket?.soTimeout = 5000 // 5 second timeout
                DebugUtils.logSuccess("✅ TimeSync server started successfully")
                DebugUtils.logNetwork("👂 Listening for time sync requests...")
                DebugUtils.log("⚙️ Server socket timeout: ${serverSocket?.soTimeout}ms")

                val buffer = ByteArray(128)

                while (serverRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        DebugUtils.logNetwork("⏳ Waiting for time sync request...")
                        serverSocket?.receive(packet)

                        val clientAddress = packet.address.hostAddress ?: "Unknown"
                        val clientPort = packet.port
                        DebugUtils.log("📨 Received time sync request from $clientAddress:$clientPort")

                        val request = String(packet.data, 0, packet.length)
                        DebugUtils.log("📝 Request content: '$request' (${request.length} chars)")

                        val t1 = System.currentTimeMillis()
                        // Echo the request so a client can match the reply to the request it sent
                        val response = "$t1,$t1,$request".toByteArray()
                        val reply = DatagramPacket(response, response.size, packet.address, packet.port)

                        DebugUtils.log("⏱️  Server timestamp (t1/t2): $t1 ms")
                        DebugUtils.log("📤 Sending time response to $clientAddress:$clientPort")
                        serverSocket?.send(reply)

                        DebugUtils.logSuccess("✅ Time response sent to client")

                    } catch (e: java.net.SocketTimeoutException) {
                        // Normal timeout, just continue
                        continue
                    } catch (e: Exception) {
                        if (serverRunning) {
                            DebugUtils.logError("❌ Error in server loop", e)
                        }
                    }
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ TimeSync server error", e)
            } finally {
                serverSocket?.close()
                DebugUtils.log("🔌 TimeSync server socket closed")
                serverRunning = false
                DebugUtils.log("🛑 TimeSync server stopped")
            }
        }.start()
    }

    fun stopServer() {
        DebugUtils.log("🛑 Stopping TimeSync server...")
        serverRunning = false
        serverSocket?.close()
        DebugUtils.logSuccess("✅ TimeSync server stopped")
    }

    fun isServerRunning(): Boolean {
        return serverRunning
    }

    // This should only be used for local testing, NOT for peer sync
    fun testLocalSync() {
        Thread {
            try {
                DebugUtils.log("🧪 Testing local time sync...")

                // First start the server, remembering whether it was already serving peers
                val wasRunning = serverRunning
                startServer()
                Thread.sleep(1000) // Wait for server to start

                // Then try to sync with localhost (for testing only!)
                val offset = getOffset("127.0.0.1")
                DebugUtils.logSuccess("✅ Local sync test passed! Offset: $offset ms")

                // Stop the server only if this test started it; other phones rely on it
                if (!wasRunning) stopServer()
            } catch (e: Exception) {
                DebugUtils.logError("❌ Local sync test failed: ${e.message}")
            }
        }.start()
    }

    // NEW: Function specifically for peer synchronization
    fun syncWithPeer(peerIp: String): Long {
        DebugUtils.log("🌐 Synchronizing time with peer: $peerIp")
        return getOffset(peerIp)
    }

    /**
     * Takes several clock samples from [serverIp] and keeps the one with the shortest round trip,
     * since that sample is the least affected by Wi-Fi jitter.
     *
     * Returns (offset, roundTripMs), where offset is server time minus local time, or null if no
     * sample got a reply. Blocks, so call it off the main thread.
     */
    fun measureBestOffset(serverIp: String, samples: Int = 5): Pair<Long, Long>? {
        val socket = DatagramSocket()
        socket.soTimeout = 1000
        val addr = InetAddress.getByName(serverIp)
        val response = ByteArray(64)

        var best: Pair<Long, Long>? = null
        try {
            repeat(samples) { i ->
                try {
                    val tag = "SYNC|$i"
                    val request = tag.toByteArray()
                    val t0 = System.currentTimeMillis()
                    socket.send(DatagramPacket(request, request.size, addr, PORT))

                    // Skip late replies to earlier samples; they would pair this send time with an old server time
                    var parts: List<String>
                    var t3: Long
                    do {
                        val packet = DatagramPacket(response, response.size)
                        socket.receive(packet)
                        t3 = System.currentTimeMillis()
                        parts = String(packet.data, 0, packet.length).trim().split(",")
                    } while (parts.getOrNull(2) != tag)
                    val t1 = parts[0].toLong()
                    val t2 = parts[1].toLong()

                    val roundTrip = (t3 - t0) - (t2 - t1)
                    val offset = ((t1 - t0) + (t2 - t3)) / 2
                    if (best == null || roundTrip < best!!.second) {
                        best = offset to roundTrip
                    }
                } catch (e: Exception) {
                    DebugUtils.logWarning("⚠️ Clock sample failed: ${e.message}")
                }
            }
        } finally {
            socket.close()
        }

        best?.let { DebugUtils.log("⏰ Clock offset to $serverIp: ${it.first} ms (rtt ${it.second} ms)") }
        return best
    }

    // Async version that runs in background
    fun getOffsetAsync(serverIp: String, onComplete: (Long?) -> Unit) {
        Thread {
            try {
                val offset = getOffset(serverIp)
                onComplete(offset)
            } catch (e: Exception) {
                DebugUtils.logError("❌ Async time sync failed: ${e.message}")
                onComplete(null)
            }
        }.start()
    }
}