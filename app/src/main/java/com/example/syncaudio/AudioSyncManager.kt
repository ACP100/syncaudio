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
 * One phone hosts ([startHosting]): it picks the song, serves it to joiners and decides when to play.
 * Other phones join ([startJoining]): they find the host, poll it several times a second, download
 * its song, and start their copy at the host's chosen instant using the clock offset measured from
 * the poll replies. The host also broadcasts "PLAY|<track name>|<target host time>" as a fast path.
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

    // Host side
    @Volatile var isHosting = false
        private set
    @Volatile private var stopSeq = 0 // bumped by "Stop for everyone"; joiners stop when it changes
    private val joinedPhones = ConcurrentHashMap<String, Long>() // ip -> last poll time
    private var cachedSongUri: Uri? = null
    private var cachedSongName = ""

    // Joining side
    @Volatile var isJoining = false
        private set
    @Volatile private var manualHostIp: String? = null
    @Volatile private var lastConnectAttempt = 0L
    @Volatile private var downloadedSong: Uri? = null
    @Volatile private var downloadedName = ""
    @Volatile private var downloading = false
    @Volatile private var lastDownloadAttempt = 0L
    @Volatile private var lastRemoteStopSeq = -1

    // Players that are playing or waiting to start, so Stop can reach them
    private val activePlayers = ConcurrentHashMap.newKeySet<AudioSyncPlayer>()

    // Clock samples from POLL replies: (offset, round trip). The lowest round trip is the most accurate.
    private val pollSamples = ArrayDeque<Pair<Long, Long>>()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private fun setStatus(text: String) {
        _status.value = text
        DebugUtils.log("🎵 $text")
    }

    /** What SyncServer replies to a POLL: "STATE|<host now>|<song name>|<target or 0>|<stop seq>". */
    fun pollReply(fromIp: String): String {
        val now = System.currentTimeMillis()
        joinedPhones[fromIp] = now
        val target = currentPlayTarget.takeIf { it > now } ?: 0L
        return "STATE|$now|${hostSongName()}|$target|$stopSeq"
    }

    /** Phones that polled within the last few seconds. */
    fun joinedPhoneCount(): Int {
        val cutoff = System.currentTimeMillis() - 3000
        return joinedPhones.values.count { it > cutoff }
    }

    private fun hostSongName(): String {
        val ctx = context ?: return ""
        val uri = FileSelectionManager.getSelectedFile() ?: return ""
        if (uri != cachedSongUri) {
            cachedSongName = trackName(ctx, uri)
            cachedSongUri = uri
        }
        return cachedSongName
    }

    fun startHosting() {
        val ctx = context ?: return
        isJoining = false
        isHosting = true
        SyncServer.start()
        SongServer.start(ctx)
        setStatus("Hosting. Pick a song, then press Play for everyone.")
    }

    fun stopHosting() {
        stopEveryone()
        isHosting = false
        SyncServer.stop()
        SongServer.stop()
        setStatus("Idle")
    }

    /** Join a host; with [hostIp] null the host is found by broadcast. */
    fun startJoining(hostIp: String? = null) {
        isHosting = false
        manualHostIp = hostIp?.trim()?.takeIf { it.isNotEmpty() }
        lastConnectAttempt = 0L
        isJoining = true
        ConnectionManager.updateConnection(isConnected = false)
        setStatus(manualHostIp?.let { "Connecting to $it…" } ?: "Looking for a host on this network…")
    }

    fun stopJoining() {
        isJoining = false
        lastRemoteStopSeq = -1
        stopHere()
        ConnectionManager.updateConnection(isConnected = false)
        setStatus("Idle")
    }

    /** Stop on this phone and tell joined phones to stop too. */
    fun stopEveryone() {
        stopSeq++
        currentPlayTarget = 0L
        currentPlay = null
        stopHere()
    }

    /** Stop playback on this phone only. */
    fun stopHere() {
        activePlayers.forEach { it.stop() }
        activePlayers.clear()
    }

    private fun downloadSong(hostIp: String, song: String) {
        val ctx = context ?: return
        downloading = true
        lastDownloadAttempt = System.currentTimeMillis()
        setStatus("⬇️ Getting '$song' from the host…")
        var lastPercent = -1
        try {
            kotlinx.coroutines.runBlocking {
                FileTransferClient(
                    onProgress = { percent ->
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _status.value = "⬇️ Getting '$song' from the host… $percent%"
                        }
                    },
                    onCompleted = { file ->
                        downloadedSong = Uri.fromFile(file)
                        downloadedName = song
                    }
                ).downloadFile(ctx, hostIp)
            }
        } finally {
            downloading = false
        }
        setStatus(
            if (downloadedName == song) "✅ Ready: '$song'. Waiting for the host to press play."
            else "❌ Couldn't get '$song' from the host, retrying…"
        )
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
                    val now = System.currentTimeMillis()
                    if (isJoining && !SyncClient.isConnecting() && now - lastConnectAttempt > 3000) {
                        lastConnectAttempt = now
                        SyncClient.connect(manualHostIp)
                    }
                    Thread.sleep(500)
                    continue
                }
                if (serverIp != polledIp) {
                    polledIp = serverIp
                    synchronized(pollSamples) { pollSamples.clear() }
                    setStatus("Connected to host $serverIp")
                }
                if (socket == null) socket = DatagramSocket().apply { soTimeout = 500 }

                val request = "POLL".toByteArray()
                val t0 = System.currentTimeMillis()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(serverIp), SYNC_SERVER_PORT))
                val buffer = ByteArray(1024)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val t3 = System.currentTimeMillis()

                // "STATE|<host now>|<song name>|<target or 0>|<stop seq>"
                val parts = String(reply.data, 0, reply.length).split("|")
                if (parts.size == 5 && parts[0] == "STATE") {
                    parts[1].toLongOrNull()?.let { hostNow -> addPollSample(hostNow - (t0 + t3) / 2, t3 - t0) }
                    val song = parts[2]
                    val targetTime = parts[3].toLongOrNull() ?: 0L
                    val remoteStopSeq = parts[4].toIntOrNull() ?: 0

                    if (remoteStopSeq != lastRemoteStopSeq) {
                        if (lastRemoteStopSeq != -1) {
                            stopHere()
                            setStatus("⏹️ The host stopped playback")
                        }
                        lastRemoteStopSeq = remoteStopSeq
                    }
                    if (song.isNotEmpty() && song != downloadedName && !downloading &&
                        System.currentTimeMillis() - lastDownloadAttempt > 3000
                    ) {
                        downloading = true
                        Thread { downloadSong(serverIp, song) }.start()
                    }
                    if (targetTime > 0 && targetTime != lastTargetSeen) {
                        lastTargetSeen = targetTime
                        Thread { handlePlay(song, targetTime, serverIp) }.start()
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
        if (downloading) {
            setStatus("⏳ Still getting '$trackName' from the host. Ask the host to press play again.")
            return
        }
        val localFile = downloadedSong?.takeIf { downloadedName == trackName } ?: FileSelectionManager.getSelectedFile()
        if (localFile == null) {
            setStatus("⚠️ The host played '$trackName' but this phone doesn't have it yet")
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

        setStatus("▶️ Playing '$localName' in sync with the host")
        DebugUtils.log("⏱️ clock offset $offset ms, rtt $rtt ms")
        activePlayers.add(player)
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
                        activePlayers.add(player)
                        setStatus("▶️ Playing '$trackName' for ${joinedPhoneCount() + 1} phones")
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

    fun trackName(ctx: Context, uri: Uri): String = displayName(ctx, uri).replace('|', '_')

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
