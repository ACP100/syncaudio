// File: AudioSyncManager.kt
package com.example.syncaudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Plays the same track on every phone at the same instant.
 *
 * Every phone runs [startListening] (and so answers TimeSync requests). The phone that presses
 * "Play Synchronized" broadcasts "PLAY|<track name>|<target server time>" and plays locally at that
 * same instant. Each listening phone measures its clock offset against the sender and starts its
 * own copy of the track at the matching local time.
 */
object AudioSyncManager {

    private const val SYNC_PORT = 5004 // separate port for audio commands
    private const val MAX_LATE_MS = 50L // starting this much later than planned is audibly out of sync

    @Volatile private var isListening = false
    private var listenerSocket: DatagramSocket? = null

    private var context: Context? = null

    // Target time of the last command seen, so a rebroadcast or our own echo is only played once.
    @Volatile private var lastTargetSeen = -1L

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    fun startListening() {
        if (isListening) return
        isListening = true

        TimeSync.startServer() // this phone is a clock reference for the others

        Thread {
            try {
                listenerSocket = DatagramSocket(SYNC_PORT)
                val buffer = ByteArray(1024)

                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenerSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    DebugUtils.logNetwork("📨 AudioSync command from ${packet.address.hostAddress}: $message")

                    // Expected format: "PLAY|<track name>|<target server time>"
                    val parts = message.split("|")
                    if (parts.size != 3 || parts[0] != "PLAY") continue
                    val targetTime = parts[2].toLongOrNull() ?: continue
                    if (targetTime == lastTargetSeen) continue

                    lastTargetSeen = targetTime
                    val serverIp = packet.address.hostAddress ?: continue

                    // Handle off the receive loop, so a slow clock sync cannot stall the listener
                    val trackName = parts[1]
                    Thread { handlePlay(trackName, targetTime, serverIp) }.start()
                }
            } catch (e: Exception) {
                if (isListening) DebugUtils.logError("❌ AudioSync listener error", e)
                isListening = false
            } finally {
                listenerSocket?.close()
            }
        }.start()
    }

    fun stopListening() {
        isListening = false
        listenerSocket?.close()
    }

    /** Runs on a listening phone when a PLAY command arrives. */
    private fun handlePlay(trackName: String, targetTime: Long, serverIp: String) {
        val ctx = context ?: return
        val localFile = FileSelectionManager.getSelectedFile()
        if (localFile == null) {
            DebugUtils.logWarning("⚠️ No track selected on this phone, cannot play '$trackName'")
            return
        }

        val localName = displayName(ctx, localFile)
        if (localName != trackName) {
            DebugUtils.logWarning("⚠️ Selected track '$localName' differs from '$trackName' on the server")
        }

        val player = AudioSyncPlayer(ctx)
        if (!player.loadAudio(localFile)) {
            DebugUtils.logError("❌ Could not open '$localName' for playback")
            return
        }

        // Measure after loading, so the decode time does not eat into the clock sample
        val measured = TimeSync.measureBestOffset(serverIp)
        if (measured == null) {
            DebugUtils.logError("❌ No clock reply from $serverIp, not playing out of sync")
            player.release()
            return
        }
        val (offset, rtt) = measured

        val localStart = targetTime - offset
        val lateBy = System.currentTimeMillis() - localStart
        if (lateBy > MAX_LATE_MS) {
            DebugUtils.logWarning("⚠️ Missed start for '$localName' by $lateBy ms, skipping")
            player.release()
            return
        }

        DebugUtils.log("⏱️ Scheduling '$localName' at server time $targetTime (offset=$offset ms, rtt=$rtt ms)")
        player.playAtTimestamp(targetTime, offset)
    }

    /** Called on the phone the user presses play on. */
    suspend fun broadcastPlayCommand(fileUri: Uri, delayMs: Long = 2000L) {
        withContext(Dispatchers.IO) {
            val ctx = context
            val trackName = (ctx?.let { displayName(it, fileUri) } ?: fileUri.lastPathSegment ?: "audio")
                .replace('|', '_')
            val targetTime = System.currentTimeMillis() + delayMs

            lastTargetSeen = targetTime // don't play our own broadcast a second time
            val message = "PLAY|$trackName|$targetTime".toByteArray()
            sendBroadcast(message)
            DebugUtils.logNetwork("📤 Play command broadcast for '$trackName' at $targetTime")

            // This phone is the clock reference, so its local play uses an offset of 0
            Thread {
                ctx?.let { c ->
                    val player = AudioSyncPlayer(c)
                    if (player.loadAudio(fileUri)) {
                        player.playAtTimestamp(targetTime, 0L)
                    } else {
                        DebugUtils.logError("❌ Could not open '$trackName' for local playback")
                    }
                }
            }.start()
        }
    }

    private fun sendBroadcast(message: ByteArray) {
        val targets = mutableListOf("255.255.255.255")
        context?.let { NetworkUtils.getLocalIpAddress(it) }
            ?.let { NetworkUtils.getBroadcastAddress(it) }
            ?.let { targets.add(it) }

        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(message, message.size, InetAddress.getByName(target), SYNC_PORT))
                } catch (e: Exception) {
                    DebugUtils.logError("❌ Failed to send PLAY command to $target", e)
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { return it }
                }
            }
        } catch (e: Exception) {
            DebugUtils.logWarning("⚠️ Could not read track name: ${e.message}")
        }
        return uri.lastPathSegment ?: "audio"
    }
}
