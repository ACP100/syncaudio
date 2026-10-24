// File: AudioSyncManager.kt
package com.example.syncaudio

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

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
    private var multicastLock: WifiManager.MulticastLock? = null

    // Phones seen via the connect handshake. Play is also sent straight to them, because many
    // Wi-Fi drivers and hotspots drop broadcast packets.
    private val peers = ConcurrentHashMap.newKeySet<String>()

    private const val SYNC_SERVER_PORT = 5001 // SyncServer, which also answers POLL
    private const val POLL_INTERVAL_MS = 250L

    // Play command the server phone is currently offering to pollers: "<track name>|<target time>"
    @Volatile var currentPlay: String? = null
        private set
    @Volatile private var currentPlayTarget = 0L

    // Clock samples from POLL replies: (offset, round trip). The lowest round trip is the most accurate.
    private val pollSamples = ArrayDeque<Pair<Long, Long>>()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private fun setStatus(text: String) {
        _status.value = text
        DebugUtils.log("🎵 $text")
    }

    /** What SyncServer replies to a POLL: "STATE|<server now>|<track>|<target>" or "STATE|<server now>|NONE". */
    fun pollReply(): String {
        val now = System.currentTimeMillis()
        val play = currentPlay?.takeIf { currentPlayTarget > now }
        return "STATE|$now|${play ?: "NONE"}"
    }

    fun addPeer(ip: String) {
        if (peers.add(ip)) DebugUtils.log("🤝 Added sync peer $ip")
    }

    // Target time of the last command seen, so a rebroadcast or our own echo is only played once.
    @Volatile private var lastTargetSeen = -1L

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    fun startListening() {
        if (isListening) return
        isListening = true

        TimeSync.startServer() // this phone is a clock reference for the others

        // Without this lock many phones silently discard incoming broadcast packets
        try {
            val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("syncaudio")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            DebugUtils.logWarning("⚠️ Could not acquire multicast lock: ${e.message}")
        }

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
                    addPeer(serverIp)

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

        Thread { pollLoop() }.start()
    }

    /**
     * While connected to a server, ask it every POLL_INTERVAL_MS whether a play is scheduled.
     * Pushed PLAY packets are often dropped by routers and hotspots, but replies to a request
     * the client sent itself get through, as the connect handshake shows.
     */
    private fun pollLoop() {
        var socket: DatagramSocket? = null
        var polledIp = ""
        while (isListening) {
            try {
                val serverIp = ConnectionManager.connectionStatus.value
                    .takeIf { it.isConnected }?.serverIp.orEmpty()
                if (serverIp.isEmpty()) {
                    Thread.sleep(500)
                    continue
                }
                if (serverIp != polledIp) {
                    polledIp = serverIp
                    synchronized(pollSamples) { pollSamples.clear() }
                    setStatus("Connected to $serverIp, waiting for play")
                }
                if (socket == null) socket = DatagramSocket().apply { soTimeout = 500 }

                val request = "POLL".toByteArray()
                val t0 = System.currentTimeMillis()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(serverIp), SYNC_SERVER_PORT))
                val buffer = ByteArray(1024)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val t3 = System.currentTimeMillis()

                // "STATE|<server now>|<track>|<target>" or "STATE|<server now>|NONE"
                val parts = String(reply.data, 0, reply.length).split("|")
                if (parts.size >= 3 && parts[0] == "STATE") {
                    val serverNow = parts[1].toLongOrNull()
                    if (serverNow != null) {
                        addPollSample(serverNow - (t0 + t3) / 2, t3 - t0)
                    }
                    val targetTime = parts.getOrNull(3)?.toLongOrNull()
                    if (parts.size == 4 && targetTime != null && targetTime != lastTargetSeen) {
                        lastTargetSeen = targetTime
                        val trackName = parts[2]
                        Thread { handlePlay(trackName, targetTime, serverIp) }.start()
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: java.net.SocketTimeoutException) {
                // Server didn't answer this round; try again
            } catch (e: Exception) {
                DebugUtils.logWarning("⚠️ Poll failed: ${e.message}")
                socket?.close()
                socket = null
                try { Thread.sleep(1000) } catch (ie: InterruptedException) {}
            }
        }
        socket?.close()
    }

    private fun addPollSample(offset: Long, roundTrip: Long) {
        synchronized(pollSamples) {
            pollSamples.addLast(offset to roundTrip)
            while (pollSamples.size > 20) pollSamples.removeFirst()
        }
    }

    /** Lowest-round-trip recent POLL sample as (offset, round trip), or null if none yet. */
    private fun bestPollSample(): Pair<Long, Long>? =
        synchronized(pollSamples) { pollSamples.minByOrNull { it.second } }

    fun stopListening() {
        isListening = false
        listenerSocket?.close()
        multicastLock?.release()
        multicastLock = null
    }

    /** Runs on a listening phone when a PLAY command arrives. */
    private fun handlePlay(trackName: String, targetTime: Long, serverIp: String) {
        val ctx = context ?: return
        val localFile = FileSelectionManager.getSelectedFile()
        if (localFile == null) {
            setStatus("⚠️ Server played '$trackName' but no song is selected on this phone. Pick the same song.")
            return
        }

        val localName = displayName(ctx, localFile)
        if (localName != trackName) {
            DebugUtils.logWarning("⚠️ Selected track '$localName' differs from '$trackName' on the server")
        }

        val player = AudioSyncPlayer(ctx)
        if (!player.loadAudio(localFile)) {
            setStatus("❌ Could not open '$localName' for playback")
            return
        }

        // Measure after loading, so the decode time does not eat into the clock sample
        val measured = bestPollSample()?.takeIf { polledFrom(serverIp) } ?: TimeSync.measureBestOffset(serverIp)
        if (measured == null) {
            setStatus("❌ No clock reply from $serverIp, not playing out of sync")
            player.release()
            return
        }
        val (offset, rtt) = measured

        val localStart = targetTime - offset
        val lateBy = System.currentTimeMillis() - localStart
        if (lateBy > MAX_LATE_MS) {
            setStatus("⚠️ Play command for '$localName' arrived $lateBy ms too late, skipped")
            player.release()
            return
        }

        setStatus("▶️ Playing '$localName' in sync (clock offset $offset ms, rtt $rtt ms)")
        player.playAtTimestamp(targetTime, offset)
    }

    private fun polledFrom(serverIp: String): Boolean {
        val status = ConnectionManager.connectionStatus.value
        return status.isConnected && status.serverIp == serverIp
    }

    /** Called on the phone the user presses play on. */
    suspend fun broadcastPlayCommand(fileUri: Uri, delayMs: Long = 2000L) {
        withContext(Dispatchers.IO) {
            val ctx = context
            val trackName = (ctx?.let { displayName(it, fileUri) } ?: fileUri.lastPathSegment ?: "audio")
                .replace('|', '_')
            val targetTime = System.currentTimeMillis() + delayMs

            lastTargetSeen = targetTime // don't play our own broadcast a second time
            currentPlayTarget = targetTime
            currentPlay = "$trackName|$targetTime"
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
        val targets = linkedSetOf("255.255.255.255")
        // Broadcast on every interface: on a phone hosting a hotspot, 255.255.255.255 often
        // leaves through mobile data instead of the hotspot
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    if (ifaceAddr.address is Inet4Address) {
                        ifaceAddr.broadcast?.hostAddress?.let { targets.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            DebugUtils.logWarning("⚠️ Could not list network interfaces: ${e.message}")
        }
        targets.addAll(peers)
        ConnectionManager.connectionStatus.value.serverIp.takeIf { it.isNotEmpty() }?.let { targets.add(it) }
        DebugUtils.log("📡 Sending PLAY to: ${targets.joinToString()}")

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
