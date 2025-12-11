// File: AudioSyncManager.kt
package com.example.syncaudio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object AudioSyncManager {

    private const val SYNC_PORT = 5004 // separate port for audio commands
    private var isListening = false
    private var listenerSocket: DatagramSocket? = null

    private var context: Context? = null
    private val playerMap = mutableMapOf<String, AudioSyncPlayer>()

    fun setContext(ctx: Context) {
        context = ctx
    }

    fun startListening() {
        if (isListening) return
        isListening = true

        Thread {
            try {
                listenerSocket = DatagramSocket(SYNC_PORT)
                val buffer = ByteArray(1024)

                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenerSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    DebugUtils.logNetwork("📨 AudioSync command received: $message")

                    // Expected format: "PLAY|<filename>|<timestamp>|<serverIp>"
                    val parts = message.split("|")
                    if (parts.size >= 3 && parts[0] == "PLAY") {
                        val filename = parts[1]
                        val targetTime = parts[2].toLongOrNull() ?: continue
                        val serverIp = parts.getOrNull(3) ?: ""

                        // Start playback in background
                        context?.let { ctx ->
                            val localFile = FileSelectionManager.getSelectedFile() // should match filename
                            if (localFile != null) {
                                val player = AudioSyncPlayer(ctx)
                                player.loadAudio(localFile)
                                val offset = TimeSync.syncWithPeer(serverIp)
                                DebugUtils.log("⏱️ Scheduling playback for $filename at $targetTime with offset $offset")
                                Thread {
                                    player.playAtTimestamp(targetTime, offset)
                                }.start()
                            } else {
                                DebugUtils.logWarning("⚠️ File $filename not found locally")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugUtils.logError("❌ AudioSync listener error", e)
            } finally {
                listenerSocket?.close()
            }
        }.start()
    }

    fun stopListening() {
        isListening = false
        listenerSocket?.close()
    }

    suspend fun broadcastPlayCommand(serverIp: String, fileUri: Uri, delayMs: Long = 3000L, clients: List<String>) {
        withContext(Dispatchers.IO) {
            val filename = fileUri.lastPathSegment ?: "audio.mp3"
            val targetTime = System.currentTimeMillis() + delayMs

            clients.forEach { clientIp ->
                try {
                    val socket = DatagramSocket()
                    val msg = "PLAY|$filename|$targetTime|$serverIp"
                    val packet = DatagramPacket(msg.toByteArray(), msg.length, InetAddress.getByName(clientIp), SYNC_PORT)
                    socket.send(packet)
                    socket.close()
                    DebugUtils.logNetwork("📤 Play command sent to $clientIp for $filename at $targetTime")
                } catch (e: Exception) {
                    DebugUtils.logError("❌ Failed to send PLAY command to $clientIp", e)
                }
            }

            // Also play locally on server
            context?.let { ctx ->
                val player = AudioSyncPlayer(ctx)
                player.loadAudio(fileUri)
                player.playAtTimestamp(targetTime, 0L) // server offset = 0
            }
        }
    }
}
