// File: TimeSync.kt (updated with more debug messages)
package com.example.syncaudio

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToLong

object TimeSync {
    private const val PORT = 5002
    private var serverRunning = false

    fun getOffset(serverIp: String): Long {
        DebugUtils.log("⏰ Starting time synchronization with $serverIp")
        DebugUtils.logNetwork("📡 TimeSync client connecting to port $PORT")

        val socket = DatagramSocket()
        val addr = InetAddress.getByName(serverIp)

        val t0 = System.currentTimeMillis()
        DebugUtils.log("⏱️  Client send time (t0): $t0 ms")

        val buf = "SYNC".toByteArray()
        DebugUtils.log("📤 Sending SYNC request to $serverIp:$PORT")

        socket.send(DatagramPacket(buf, buf.size, addr, PORT))
        DebugUtils.logSuccess("✅ SYNC request sent")

        val response = ByteArray(64)
        val packet = DatagramPacket(response, response.size)

        DebugUtils.logNetwork("⏳ Waiting for time server response...")
        socket.receive(packet)

        val t3 = System.currentTimeMillis()
        DebugUtils.log("⏱️  Client receive time (t3): $t3 ms")

        val t1t2 = String(packet.data).trim().split(",")

        if (t1t2.size < 2) {
            DebugUtils.logError("❌ Invalid response from time server: ${String(packet.data)}")
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
                val socket = DatagramSocket(PORT)
                DebugUtils.logSuccess("✅ TimeSync server started successfully")
                DebugUtils.logNetwork("👂 Listening for time sync requests...")

                val buffer = ByteArray(128)

                while (serverRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    DebugUtils.logNetwork("⏳ Waiting for time sync request...")
                    socket.receive(packet)

                    val clientAddress = packet.address.hostAddress ?: "Unknown"
                    val clientPort = packet.port
                    DebugUtils.log("📨 Received time sync request from $clientAddress:$clientPort")

                    val request = String(packet.data, 0, packet.length)
                    DebugUtils.log("📝 Request content: '$request'")

                    val t1 = System.currentTimeMillis()
                    val response = "$t1,$t1".toByteArray()
                    val reply = DatagramPacket(response, response.size, packet.address, packet.port)

                    DebugUtils.log("⏱️  Server timestamp (t1/t2): $t1 ms")
                    DebugUtils.log("📤 Sending time response to $clientAddress:$clientPort")
                    socket.send(reply)

                    DebugUtils.logSuccess("✅ Time response sent to client")
                }

                socket.close()
                DebugUtils.log("🛑 TimeSync server stopped")

            } catch (e: Exception) {
                DebugUtils.logError("❌ TimeSync server error", e)
                serverRunning = false
            }
        }.start()
    }

    fun stopServer() {
        serverRunning = false
        DebugUtils.log("🛑 Stopping TimeSync server")
    }
}