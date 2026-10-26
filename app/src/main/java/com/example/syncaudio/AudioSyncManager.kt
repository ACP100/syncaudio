// File: AudioSyncManager.kt
package com.example.syncaudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
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

    @Volatile private var isListening = false
    private var listenerSocket: DatagramSocket? = null

    private var context: Context? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Phones seen via the connect handshake. Play is also sent straight to them, because many
    // Wi-Fi drivers and hotspots drop broadcast packets.
    private val peers = ConcurrentHashMap.newKeySet<String>()

    private const val SYNC_SERVER_PORT = 5001 // SyncServer, which also answers POLL
    private const val POLL_INTERVAL_MS = 100L
    private const val POLL_SAMPLES_KEPT = 150 // 15 s of samples at POLL_INTERVAL_MS

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
    // Songs this phone has from the host, by name: the current one and, fetched early, the next
    private val songCache = ConcurrentHashMap<String, Uri>()
    @Volatile private var downloading = false
    @Volatile private var lastNextQuery = 0L

    private const val SONG_PORT = 5003
    private const val NEXT_SONG_PORT = 5006
    private val songServer = SongServer(SONG_PORT) { FileSelectionManager.getSelectedFile() }
    private val nextSongServer = SongServer(NEXT_SONG_PORT) { nextSongUri() }

    /** A song being fetched from the host for playing now (not an early fetch of the next one). */
    data class Download(val song: String, val percent: Int)

    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    /** Set before opening the picker from the playlist sheet: the picked songs are added, not swapped in. */
    @Volatile var addingSongs = false
    @Volatile private var lastDownloadAttempt = 0L
    @Volatile private var lastRemoteStopSeq = -1

    /** Manual fine-tune for this phone, e.g. for a Bluetooth speaker: positive plays later. */
    @Volatile var adjustMs = 0L

    // Players that are playing or waiting to start, so Stop can reach them
    private val activePlayers = ConcurrentHashMap.newKeySet<AudioSyncPlayer>()

    // Clock samples from POLL replies: (offset, round trip). The lowest round trip is the most accurate.
    private val pollSamples = ArrayDeque<Pair<Long, Long>>()

    /** What this phone is playing; startsAtLocalMs is when its first frame is heard, on this phone's clock. */
    data class NowPlaying(val title: String, val artist: String, val startsAtLocalMs: Long, val durationMs: Long)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Last measured gap to the host while playing on a joined phone, in ms (null: not measured yet). */
    private val _driftMs = MutableStateFlow<Long?>(null)
    val driftMs: StateFlow<Long?> = _driftMs.asStateFlow()

    /** A song paused by the host, and where. */
    data class Paused(val song: NowPlaying, val positionMs: Long)

    private val _paused = MutableStateFlow<Paused?>(null)
    val paused: StateFlow<Paused?> = _paused.asStateFlow()

    /** The host's songs, in order; the current one is also FileSelectionManager's selected file. */
    private val _playlist = MutableStateFlow<List<Uri>>(emptyList())
    val playlist: StateFlow<List<Uri>> = _playlist.asStateFlow()
    private val _playlistIndex = MutableStateFlow(0)
    val playlistIndex: StateFlow<Int> = _playlistIndex.asStateFlow()

    /** Party mode as the host set it: [style] Pulse, Wave or Strobe; [color] an index into the party colours. */
    data class Party(val style: String, val color: Int)

    private val _party = MutableStateFlow<Party?>(null)
    val party: StateFlow<Party?> = _party.asStateFlow()

    const val SIDE_LEFT = "Left"
    const val SIDE_BOTH = "Both"
    const val SIDE_RIGHT = "Right"
    private const val PREF_SPEAKER_SIDE = "speaker_side"

    private fun prefs() = context?.getSharedPreferences("syncaudio", Context.MODE_PRIVATE)

    /** Which channel this phone plays: Left, Right, or Both (the full mix). Set per phone. */
    private val _speakerSide = MutableStateFlow(SIDE_BOTH)
    val speakerSide: StateFlow<String> = _speakerSide.asStateFlow()

    fun setSpeakerSide(side: String) {
        _speakerSide.value = side
        prefs()?.edit()?.putString(PREF_SPEAKER_SIDE, side)?.apply() // kept across app restarts
        SyncLog.add("Speaker side: $side")
    }

    /** A phone hosting on this Wi-Fi, found by [startDiscovery]. */
    data class FoundHost(val ip: String, val name: String, val listeners: Int)

    private val _hosts = MutableStateFlow<List<FoundHost>>(emptyList())
    val hosts: StateFlow<List<FoundHost>> = _hosts.asStateFlow()
    @Volatile private var discovering = false

    /** Name of the host this phone joined, for display. */
    @Volatile var hostName = ""
        private set

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private fun setStatus(text: String) {
        _status.value = text
        SyncLog.add("Status: $text")
    }

    /**
     * What SyncServer replies to "POLL|<seq>": "STATE|<seq>|<host now>|<song name>|<target or 0>|<stop seq>".
     * The seq is echoed so the joiner can tell this reply from a late reply to an earlier poll.
     */
    fun pollReply(fromIp: String, request: String): String {
        val now = System.currentTimeMillis()
        val seq = request.substringAfter("POLL|", "0")
        val lastSeen = joinedPhones.put(fromIp, now)
        if (lastSeen == null || now - lastSeen > 3000) SyncLog.add("Host: phone $fromIp is polling")
        val duration = _nowPlaying.value?.durationMs ?: 0L
        val stillPlaying = currentPlayTarget > 0 && (duration <= 0 || now < currentPlayTarget + duration)
        val target = if (stillPlaying) currentPlayTarget else 0L
        return "STATE|$seq|$now|${hostSongName()}|$target|$stopSeq"
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
        songServer.start(ctx)
        nextSongServer.start(ctx)
        Thread { advanceWhenSongEnds() }.start()
        holdLowLatencyWifi(true)
        SyncLog.add("Host: started, address ${NetworkUtils.getLocalIpAddress(ctx)}")
        setStatus("Hosting. Pick a song, then press Play for everyone.")
    }

    fun stopHosting() {
        stopEveryone()
        isHosting = false
        SyncServer.stop()
        songServer.stop()
        stopParty()
        nextSongServer.stop()
        holdLowLatencyWifi(false)
        setStatus("Idle")
    }

    /** Join a host; with [hostIp] null the host is found by broadcast. */
    fun startJoining(hostIp: String? = null) {
        isHosting = false
        manualHostIp = hostIp?.trim()?.takeIf { it.isNotEmpty() }
        lastConnectAttempt = 0L
        isJoining = true
        holdLowLatencyWifi(true)
        ConnectionManager.updateConnection(isConnected = false)
        setStatus(manualHostIp?.let { "Connecting to $it…" } ?: "Looking for a host on this network…")
    }

    fun stopJoining() {
        stopDiscovery()
        isJoining = false
        lastRemoteStopSeq = -1
        stopHere()
        holdLowLatencyWifi(false)
        ConnectionManager.updateConnection(isConnected = false)
        setStatus("Idle")
    }

    /**
     * Wi-Fi power saving parks the radio between beacons, delaying packets by a random few to
     * 100 ms. That skews every clock sample, so keep Wi-Fi awake while hosting or joining.
     */
    @Suppress("DEPRECATION")
    private fun holdLowLatencyWifi(on: Boolean) {
        try {
            if (!on) {
                wifiLock?.release()
                wifiLock = null
                return
            }
            if (wifiLock?.isHeld == true) return
            val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "syncaudio:sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            DebugUtils.logWarning("⚠️ Could not keep Wi-Fi in low-latency mode: ${e.message}")
        }
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
        _nowPlaying.value = null
        _driftMs.value = null
    }

    /** Host: play the current song for everyone from [positionMs]. */
    suspend fun playFrom(positionMs: Long = 0L) {
        val uri = FileSelectionManager.getSelectedFile() ?: return
        broadcastPlayCommand(uri, if (positionMs > 0) 2000L else 3000L, positionMs)
    }

    /** Host: pause for everyone, remembering where. */
    fun pause() {
        val song = _nowPlaying.value ?: return
        val position = (System.currentTimeMillis() - song.startsAtLocalMs).coerceAtLeast(0L)
        stopEveryone()
        _paused.value = Paused(song, position)
        SyncLog.add("Host: paused at ${position / 1000} s")
    }

    /** Host: songs came back from the picker. */
    fun onSongsPicked(uris: List<Uri>) {
        if (addingSongs && _playlist.value.isNotEmpty()) {
            _playlist.value = _playlist.value + uris
        } else {
            setPlaylist(uris)
        }
        addingSongs = false
    }

    /** Title, artist and length of [uri], for lists. */
    fun songDetails(uri: Uri): NowPlaying? = context?.let { songInfo(it, uri, 0L) }

    /** Name of the song after the current one, for joined phones to fetch early ("" if none). */
    fun nextSongName(): String {
        val ctx = context ?: return ""
        return nextSongUri()?.let { trackName(ctx, it) } ?: ""
    }

    private fun nextSongUri(): Uri? = _playlist.value.getOrNull(_playlistIndex.value + 1)

    /** Host: replace the playlist with [uris] and select the first. */
    fun setPlaylist(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _playlist.value = uris
        select(0)
    }

    private fun select(index: Int) {
        _playlistIndex.value = index
        _paused.value = null
        FileSelectionManager.setSelectedFile(_playlist.value[index])
    }

    /** Host: go [delta] songs along the playlist; keeps playing if it was playing. */
    suspend fun skip(delta: Int) = playIndex(_playlistIndex.value + delta)

    /** Host: switch to song [index]; keeps playing if it was playing. */
    suspend fun playIndex(index: Int) {
        val list = _playlist.value
        if (index !in list.indices) return
        val wasPlaying = _nowPlaying.value != null
        stopEveryone()
        select(index)
        if (wasPlaying) playFrom(0L)
    }

    /** Host: when a song ends, play the next one, or stop after the last. */
    private fun advanceWhenSongEnds() {
        while (isHosting) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            val song = _nowPlaying.value ?: continue
            if (song.durationMs <= 0 || System.currentTimeMillis() < song.startsAtLocalMs + song.durationMs + 500) continue
            if (_playlistIndex.value + 1 < _playlist.value.size) {
                kotlinx.coroutines.runBlocking { skip(1) }
            } else {
                stopEveryone()
            }
        }
    }

    /** Host: turn party mode on for every phone. */
    fun startParty(style: String, color: Int) {
        _party.value = Party(style, color)
        SyncLog.add("Host: party mode $style, colour $color")
    }

    /** Host: turn party mode off for every phone. */
    fun stopParty() {
        _party.value = null
    }

    /** What SyncServer replies to "PARTY": "PARTY|<style>|<colour>", or "PARTY|" when off. */
    fun partyReply(): String = _party.value?.let { "PARTY|${it.style}|${it.color}" } ?: "PARTY|"

    /** Loudness of what this phone is playing right now, 0..1, for party mode. */
    fun partyLevel(): Float {
        val song = _nowPlaying.value ?: return 0f
        val position = System.currentTimeMillis() - song.startsAtLocalMs
        return activePlayers.firstOrNull()?.levelAt(position) ?: 0f
    }

    /** How far into the song this phone is, in ms (0 when not playing). */
    fun songPositionMs(): Long =
        _nowPlaying.value?.let { (System.currentTimeMillis() - it.startsAtLocalMs).coerceAtLeast(0L) } ?: 0L

    /** This phone's place in a colour wave: the host is 0, joined phones spread by name. */
    fun partySeat(): Int = if (isHosting) 0 else 1 + (deviceName().hashCode() and 0x7fffffff) % 3

    /** Joined phone: follow the host's party mode. Hosts without party mode don't answer; it stays off. */
    private fun partyLoop() {
        while (isListening) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }
            if (!isJoining) {
                if (!isHosting) _party.value = null
                continue
            }
            val status = ConnectionManager.connectionStatus.value
            if (!status.isConnected || status.serverIp.isEmpty()) continue
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 400
                    val request = "PARTY".toByteArray()
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName(status.serverIp), SYNC_SERVER_PORT))
                    val buffer = ByteArray(128)
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    val parts = String(reply.data, 0, reply.length).split("|")
                    _party.value = if (parts.size >= 3 && parts[0] == "PARTY") {
                        Party(parts[1], parts[2].toIntOrNull() ?: 0)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                // no answer this time; keep the last known state
            }
        }
    }

    fun deviceName(): String =
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".replace('|', ' ')

    /** Keep asking the Wi-Fi who is hosting, updating [hosts], until [stopDiscovery]. */
    fun startDiscovery() {
        if (discovering) return
        discovering = true
        _hosts.value = emptyList()
        Thread { discoverLoop() }.start()
    }

    fun stopDiscovery() {
        discovering = false
    }

    private fun discoverLoop() {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 300
        }
        val lastSeen = linkedMapOf<String, Pair<FoundHost, Long>>()
        val buffer = ByteArray(256)
        try {
            while (discovering) {
                val request = "DISCOVER".toByteArray()
                for (target in broadcastTargets()) {
                    try {
                        socket.send(DatagramPacket(request, request.size, InetAddress.getByName(target), SYNC_SERVER_PORT))
                    } catch (e: Exception) {
                        // an interface that can't broadcast; the others still can
                    }
                }
                val roundEnds = System.currentTimeMillis() + 1500
                while (discovering && System.currentTimeMillis() < roundEnds) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        // "HOST|<device name>|<listeners>"
                        val parts = String(packet.data, 0, packet.length).split("|")
                        val ip = packet.address.hostAddress
                        if (parts.size >= 3 && parts[0] == "HOST" && ip != null) {
                            lastSeen[ip] = FoundHost(ip, parts[1], parts[2].toIntOrNull() ?: 0) to System.currentTimeMillis()
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // no reply this moment; keep listening until the round ends
                    }
                }
                // Keep a host through one missed round, so the list doesn't flicker
                val cutoff = System.currentTimeMillis() - 4000
                lastSeen.entries.removeAll { it.value.second < cutoff }
                if (discovering) _hosts.value = lastSeen.values.map { it.first }
            }
        } catch (e: Exception) {
            SyncLog.add("Join: looking for hosts failed: ${e.message}")
        } finally {
            socket.close()
        }
    }

    /** Join the host at [ip], found by discovery. */
    fun joinHost(host: FoundHost) {
        stopDiscovery()
        isHosting = false
        hostName = host.name
        manualHostIp = host.ip
        lastConnectAttempt = System.currentTimeMillis()
        isJoining = true
        holdLowLatencyWifi(true)
        ConnectionManager.updateConnection(isConnected = true, serverIp = host.ip, serverPort = SYNC_SERVER_PORT, message = "joined")
        SyncLog.add("Join: joined ${host.name} at ${host.ip}")
    }

    /** Title, artist and length from the file's tags, falling back to its file name. */
    private fun songInfo(ctx: Context, uri: Uri, startsAtLocalMs: Long): NowPlaying {
        var title = ""
        var artist = ""
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ctx, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            DebugUtils.logWarning("⚠️ Could not read song tags: ${e.message}")
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        if (title.isBlank()) title = displayName(ctx, uri).substringBeforeLast('.')
        return NowPlaying(title, artist, startsAtLocalMs, duration)
    }

    /** Fetch [song] from the host; [early] = the next song, fetched quietly while this one plays. */
    private fun downloadSong(hostIp: String, song: String, early: Boolean = false) {
        val ctx = context ?: return
        downloading = true
        lastDownloadAttempt = System.currentTimeMillis()
        if (!early) {
            _download.value = Download(song, 0)
            setStatus("⬇️ Getting '$song' from the host…")
        }
        var lastPercent = -1
        try {
            kotlinx.coroutines.runBlocking {
                FileTransferClient(
                    port = if (early) NEXT_SONG_PORT else SONG_PORT,
                    onProgress = { percent ->
                        if (!early && percent != lastPercent) {
                            lastPercent = percent
                            _download.value = Download(song, percent)
                            _status.value = "⬇️ Getting '$song' from the host… $percent%"
                        }
                    },
                    onCompleted = { file ->
                        SyncLog.add("Join: got ${if (early) "next song " else ""}'$song', ${file.length() / 1024} KB in ${System.currentTimeMillis() - lastDownloadAttempt} ms")
                        songCache[song] = Uri.fromFile(file)
                    }
                ).downloadFile(ctx, hostIp)
            }
        } finally {
            downloading = false
            if (!early) _download.value = null
        }
        if (!early) {
            setStatus(
                if (songCache.containsKey(song)) "✅ Ready: '$song'. Waiting for the host to press play."
                else "❌ Couldn't get '$song' from the host, retrying…"
            )
        }
    }

    /** Ask the host which song comes next, then fetch it early if this phone doesn't have it. */
    private fun fetchNextSong(hostIp: String) {
        try {
            val next = DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val request = "NEXT".toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(hostIp), SYNC_SERVER_PORT))
                val buffer = ByteArray(512)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                // "NEXT|<song name>" ("" when the current song is the last)
                String(reply.data, 0, reply.length).substringAfter("NEXT|", "")
            }
            if (next.isNotEmpty() && !songCache.containsKey(next)) {
                downloadSong(hostIp, next, early = true)
            }
        } catch (e: Exception) {
            // older host without NEXT, or no reply: the next song is fetched when it starts instead
        } finally {
            downloading = false
        }
    }

    fun addPeer(ip: String) {
        if (peers.add(ip)) DebugUtils.log("🤝 Added sync peer $ip")
    }

    // Target time of the last command seen, so a rebroadcast or our own echo is only played once.
    @Volatile private var lastTargetSeen = -1L

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
        _speakerSide.value = prefs()?.getString(PREF_SPEAKER_SIDE, SIDE_BOTH) ?: SIDE_BOTH
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
        Thread { partyLoop() }.start()
    }

    /**
     * While connected to a server, ask it every POLL_INTERVAL_MS whether a play is scheduled.
     * Pushed PLAY packets are often dropped by routers and hotspots, but replies to a request
     * the client sent itself get through, as the connect handshake shows.
     */
    private fun pollLoop() {
        var socket: DatagramSocket? = null
        var polledIp = ""
        var polls = 0
        var timeouts = 0
        var stale = 0
        var seq = 0
        val buffer = ByteArray(1024)
        val reply = DatagramPacket(buffer, buffer.size)
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
                    polls = 0
                    timeouts = 0
                    stale = 0
                    setStatus("Connected to host $serverIp")
                }
                if (socket == null) socket = DatagramSocket().apply { soTimeout = 500 }

                seq++
                val request = "POLL|$seq".toByteArray()
                val t0 = System.currentTimeMillis()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(serverIp), SYNC_SERVER_PORT))

                // A reply that arrived after its poll timed out is still queued on the socket. Skip
                // anything not answering this poll: pairing this send time with an older host time
                // made the clock look ~100 ms off with a 0 ms round trip.
                var parts: List<String>
                var t3: Long
                do {
                    reply.length = buffer.size // receive() shrinks the packet to the last reply's size
                    socket.receive(reply)
                    t3 = System.currentTimeMillis()
                    parts = String(reply.data, 0, reply.length).split("|")
                    val answersThisPoll = parts.getOrNull(1) == seq.toString()
                    if (!answersThisPoll) stale++
                } while (!answersThisPoll)

                // "STATE|<seq>|<host now>|<song name>|<target or 0>|<stop seq>"
                if (parts.size == 6 && parts[0] == "STATE") {
                    parts[2].toLongOrNull()?.let { hostNow -> addPollSample(hostNow - (t0 + t3) / 2, t3 - t0) }
                    if (++polls % 50 == 0) logClock(t3 - t0, timeouts, stale)
                    val song = parts[3]
                    val targetTime = parts[4].toLongOrNull() ?: 0L
                    val remoteStopSeq = parts[5].toIntOrNull() ?: 0

                    if (remoteStopSeq != lastRemoteStopSeq) {
                        if (lastRemoteStopSeq != -1) {
                            stopHere()
                            setStatus("⏸ The host paused")
                        }
                        lastRemoteStopSeq = remoteStopSeq
                    }
                    val haveSong = songCache.containsKey(song)
                    val nowMs = System.currentTimeMillis()
                    if (song.isNotEmpty() && !haveSong && !downloading && nowMs - lastDownloadAttempt > 3000) {
                        downloading = true
                        Thread { downloadSong(serverIp, song) }.start()
                    } else if (haveSong && !downloading && nowMs - lastNextQuery > 3000) {
                        lastNextQuery = nowMs
                        downloading = true
                        Thread { fetchNextSong(serverIp) }.start()
                    }
                    // Only once the song is here: a play that arrives mid-download is joined late, in sync
                    if (targetTime > 0 && targetTime != lastTargetSeen && haveSong) {
                        lastTargetSeen = targetTime
                        Thread { handlePlay(song, targetTime, serverIp) }.start()
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: java.net.SocketTimeoutException) {
                // Server didn't answer this round; try again
                timeouts++
            } catch (e: Exception) {
                DebugUtils.logWarning("⚠️ Poll failed: ${e.message}")
                socket?.close()
                socket = null
                try { Thread.sleep(1000) } catch (ie: InterruptedException) {}
            }
        }
        socket?.close()
    }

    /** Every ~5 s while connected: how good the clock estimate is. */
    private fun logClock(lastRoundTrip: Long, timeouts: Int, stale: Int) {
        val samples = synchronized(pollSamples) { pollSamples.toList() }
        val estimate = bestPollSample() ?: return
        val rtts = samples.map { it.second }
        val offsets = samples.sortedBy { it.second }.take(5).map { it.first }
        SyncLog.add(
            "Clock: offset ${estimate.first} ms (fastest offsets ${offsets.joinToString()}), " +
                "rtt min ${rtts.minOrNull()} / median ${rtts.sorted()[rtts.size / 2]} / last $lastRoundTrip ms, " +
                "${rtts.size} samples, $timeouts timeouts, $stale late replies skipped"
        )
    }

    private fun addPollSample(offset: Long, roundTrip: Long) {
        synchronized(pollSamples) {
            pollSamples.addLast(offset to roundTrip)
            while (pollSamples.size > POLL_SAMPLES_KEPT) pollSamples.removeFirst()
        }
    }

    /**
     * Clock offset from recent POLL samples as (offset, round trip), or null if none yet.
     * Uses the median offset of the 5 fastest round trips: fast samples are the least skewed by
     * queuing delay, and the median keeps one lucky outlier from moving the estimate.
     */
    private fun bestPollSample(): Pair<Long, Long>? {
        synchronized(pollSamples) {
            if (pollSamples.isEmpty()) return null
            val fastest = pollSamples.sortedBy { it.second }.take(5)
            val offsets = fastest.map { it.first }.sorted()
            return offsets[offsets.size / 2] to fastest.first().second
        }
    }

    fun stopListening() {
        isListening = false
        listenerSocket?.close()
        multicastLock?.release()
        multicastLock = null
    }

    /** Runs on a listening phone when a PLAY command arrives. */
    private fun handlePlay(trackName: String, targetTime: Long, serverIp: String) {
        val ctx = context ?: return
        val localFile = songCache[trackName] ?: FileSelectionManager.getSelectedFile()
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
        stopHere() // a seek or a new song replaces what's playing

        SyncLog.add("Join: play '$localName' starts in ${-lateBy} ms (clock offset $offset ms, rtt $rtt ms, fine-tune $adjustMs ms)")
        setStatus("▶️ Playing '$localName' in sync with the host")
        activePlayers.add(player)
        _driftMs.value = null
        _nowPlaying.value = songInfo(ctx, localFile, localStart)
        player.playAtTimestamp(
            targetTime,
            offset,
            currentOffset = { bestPollSample()?.takeIf { polledFrom(serverIp) }?.first ?: offset },
            extraDelayMs = { adjustMs },
            onDrift = { aheadMs ->
                _driftMs.value = aheadMs
                _status.value = "▶️ Playing '$localName' · ${kotlin.math.abs(aheadMs)} ms from the host"
            }
        )
    }

    private fun polledFrom(serverIp: String): Boolean {
        val status = ConnectionManager.connectionStatus.value
        return status.isConnected && status.serverIp == serverIp
    }

    /** Called on the phone the user presses play on. */
    suspend fun broadcastPlayCommand(fileUri: Uri, delayMs: Long = 2000L, startAtMs: Long = 0L) {
        withContext(Dispatchers.IO) {
            val ctx = context
            val trackName = (ctx?.let { displayName(it, fileUri) } ?: fileUri.lastPathSegment ?: "audio")
                .replace('|', '_')
            // When the song's start would have been heard; starting mid-song puts it in the past
            val targetTime = System.currentTimeMillis() + delayMs - startAtMs
            stopHere()
            _paused.value = null

            lastTargetSeen = targetTime // don't play our own broadcast a second time
            currentPlayTarget = targetTime
            currentPlay = "$trackName|$targetTime"
            val message = "PLAY|$trackName|$targetTime".toByteArray()
            sendBroadcast(message)
            SyncLog.add("Host: play '$trackName' in $delayMs ms, ${joinedPhoneCount()} phones polling, fine-tune $adjustMs ms")

            // This phone is the clock reference, so its local play uses an offset of 0
            Thread {
                ctx?.let { c ->
                    val player = AudioSyncPlayer(c)
                    if (player.loadAudio(fileUri)) {
                        activePlayers.add(player)
                        _nowPlaying.value = songInfo(c, fileUri, targetTime)
                        setStatus("▶️ Playing '$trackName' for ${joinedPhoneCount() + 1} phones")
                        player.playAtTimestamp(targetTime, 0L, extraDelayMs = { adjustMs })
                    } else {
                        DebugUtils.logError("❌ Could not open '$trackName' for local playback")
                    }
                }
            }.start()
        }
    }

    /** Broadcast addresses to try. */
    private fun broadcastTargets(): MutableSet<String> {
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
        return targets
    }

    private fun sendBroadcast(message: ByteArray) {
        val targets = broadcastTargets()
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
