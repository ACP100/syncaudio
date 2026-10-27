// File: composables/SimpleSyncScreen.kt
package com.example.syncaudio.composables

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.example.syncaudio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Bg = Color(0xFF0E0F13)
private val Panel = Color(0xFF181A20)
private val Line = Color(0xFF2C2F38)
private val Ink = Color(0xFFF2F2F5)
private val Muted = Color(0xFFA3A6B1)
private val Faint = Color(0xFF8A8D98)
private val Accent = Color(0xFF3DDC97)
private val AccentDim = Color(0xFF15241D)
private val Unplayed = Color(0xFF3A3D47)
private val Raised = Color(0xFF22252D)
private val Amber = Color(0xFFE8B04A)

private val WAVE = listOf(30, 52, 74, 40, 96, 120, 84, 60, 140, 170, 120, 90, 150, 190, 160, 110, 130, 170, 140, 100, 80, 120, 90, 60, 70, 44, 56, 32)

private enum class Screen { WELCOME, JOIN, HOST, LISTENER, PARTY_STUDIO, PARTY_LINEUP }

/**
 * The everyday flow: one phone starts a room and picks a song, the others join it from a list,
 * the host presses play. Joined phones get the song from the host automatically.
 */
@Composable
fun SimpleSyncScreen(onOpenAdvanced: () -> Unit) {
    var screen by remember {
        mutableStateOf(
            when {
                AudioSyncManager.isHosting -> Screen.HOST
                AudioSyncManager.isJoining -> Screen.LISTENER
                else -> Screen.WELCOME
            }
        )
    }
    var showSettings by remember { mutableStateOf(false) }
    val activity = LocalContext.current as MainActivity

    // System back goes one screen back; on the host or joined screen it only sends the app to the
    // background, like a music app, so the music and the room keep going
    BackHandler(enabled = screen != Screen.WELCOME) {
        when (screen) {
            Screen.JOIN -> screen = Screen.WELCOME
            Screen.PARTY_STUDIO -> {
                if (AudioSyncManager.party.value?.lineup == true) AudioSyncManager.stopParty()
                screen = Screen.HOST
            }
            Screen.PARTY_LINEUP -> screen = Screen.PARTY_STUDIO
            else -> activity.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (screen) {
            Screen.WELCOME -> WelcomeScreen(
                onHost = {
                    AudioSyncManager.startHosting()
                    screen = Screen.HOST
                },
                onJoin = { screen = Screen.JOIN }
            )
            Screen.JOIN -> JoinScreen(
                onBack = { screen = Screen.WELCOME },
                onJoin = { host ->
                    AudioSyncManager.joinHost(host)
                    screen = Screen.LISTENER
                }
            )
            Screen.PARTY_STUDIO -> PartyStudioScreen(
                onBack = {
                    // backing out before the party started cancels it
                    if (AudioSyncManager.party.value?.lineup == true) AudioSyncManager.stopParty()
                    screen = Screen.HOST
                },
                onNext = { screen = Screen.PARTY_LINEUP }
            )
            Screen.PARTY_LINEUP -> PartyLineupScreen(
                onBack = { screen = Screen.PARTY_STUDIO },
                onStart = {
                    AudioSyncManager.updateParty { it.copy(lineup = false) }
                    screen = Screen.HOST
                }
            )
            Screen.HOST -> HostScreen(
                onParty = { screen = Screen.PARTY_STUDIO },
                onSettings = { showSettings = true },
                onLeave = {
                    AudioSyncManager.stopHosting()
                    screen = Screen.WELCOME
                }
            )
            Screen.LISTENER -> ListenerScreen(
                onSettings = { showSettings = true },
                onLeave = {
                    AudioSyncManager.stopJoining()
                    screen = Screen.WELCOME
                }
            )
        }
        val party by AudioSyncManager.party.collectAsState()
        var hiddenHere by remember(party) { mutableStateOf(false) }
        val currentParty = party
        if (currentParty != null && !hiddenHere && (screen == Screen.HOST || screen == Screen.LISTENER)) {
            val isHost = screen == Screen.HOST
            // On a joined phone, back leaves party mode on that phone, like its ✕
            BackHandler(enabled = !isHost) { hiddenHere = true }
            PartyScreen(currentParty, isHost, onExit = { if (isHost) AudioSyncManager.stopParty() else hiddenHere = true })
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onOpenAdvanced = {
                showSettings = false
                onOpenAdvanced()
            }
        )
    }
}

@Composable
private fun WelcomeScreen(onHost: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Bars(listOf(8, 16, 24, 14, 8), barWidth = 3.dp, gap = 2.dp) { Accent }
            Text("SyncAudio", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Text("Many phones.\nOne sound.", color = Ink, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "Play the same song on every phone in the room, perfectly in time. " +
                    "Works on any Wi-Fi or hotspot — no internet needed.",
                color = Muted, fontSize = 16.sp, lineHeight = 24.sp
            )
        }
        PrimaryButton("Start a room", onClick = onHost)
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Join a room", onClick = onJoin)
        Spacer(Modifier.height(16.dp))
        Text(
            "Put all phones on the same Wi-Fi first",
            color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun JoinScreen(onBack: () -> Unit, onJoin: (AudioSyncManager.FoundHost) -> Unit) {
    val hosts by AudioSyncManager.hosts.collectAsState()
    DisposableEffect(Unit) {
        AudioSyncManager.startDiscovery()
        onDispose { AudioSyncManager.stopDiscovery() }
    }

    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        SquareIconButton("Back", onClick = onBack) { BackIcon() }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join a room", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Phones hosting on this Wi-Fi show up here. Tap one to join.", color = Muted, fontSize = 15.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Nearby")
            hosts.forEach { host ->
                Surface(
                    onClick = { onJoin(host) },
                    shape = RoundedCornerShape(18.dp),
                    color = Panel,
                    border = BorderStroke(1.5.dp, Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF23262E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Bars(listOf(8, 14, 20, 12, 8), barWidth = 3.dp, gap = 2.dp) { Accent }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(host.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Hosting · ${host.listeners} listening", color = Muted, fontSize = 13.sp)
                        }
                        Text("Join", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                if (hosts.isEmpty()) "Looking for hosts…" else "Still looking…",
                color = Faint, fontSize = 13.sp, modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
private fun HostScreen(onParty: () -> Unit, onSettings: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val songUri by FileSelectionManager.selectedFileUri.collectAsState()
    val pickedName = remember(songUri) { songUri?.let { AudioSyncManager.trackName(context, it).substringBeforeLast('.') } }
    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val paused by AudioSyncManager.paused.collectAsState()
    val playlist by AudioSyncManager.playlist.collectAsState()
    val index by AudioSyncManager.playlistIndex.collectAsState()
    val now = rememberNow()
    var showQueue by remember { mutableStateOf(false) }
    val side by AudioSyncManager.speakerSide.collectAsState()
    var joined by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            joined = AudioSyncManager.joinedPhoneCount()
            delay(1000)
        }
    }

    val song = nowPlaying ?: paused?.song
    val playing = nowPlaying?.takeIf { isPlaying(it, now) }
    val starting = nowPlaying?.let { it.startsAtLocalMs > now } ?: false
    val active = playing != null || starting
    val position = paused?.positionMs ?: playing?.let { now - it.startsAtLocalMs } ?: 0L
    val duration = song?.durationMs ?: 0L
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("You're hosting")
                Text(AudioSyncManager.deviceName(), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SquareIconButton("Party mode", onClick = onParty) { PartyIcon() }
            Spacer(Modifier.width(8.dp))
            SquareIconButton("Settings", onClick = onSettings) { SettingsIcon() }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(
                when {
                    paused != null -> Amber
                    joined > 0 -> Accent
                    else -> Faint
                }
            ))
            Text(
                when {
                    paused != null -> "Paused on ${joined + 1} phone${if (joined == 0) "" else "s"}"
                    joined == 0 -> "No phones joined yet"
                    joined == 1 -> "1 phone joined"
                    else -> "$joined phones joined"
                } + if (side != AudioSyncManager.SIDE_BOTH) " · $side speaker" else "",
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
        }

        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)).background(Panel),
            contentAlignment = Alignment.Center
        ) {
            val played = (progress * WAVE.size).toInt()
            Bars(WAVE.map { it / 2 }, barWidth = 5.dp, gap = 5.dp) { i ->
                if (i < played) (if (paused != null) Muted else Accent) else Unplayed
            }
        }

        SongTitle(
            title = song?.title ?: pickedName ?: "No song picked",
            artist = song?.artist ?: if (songUri == null) "Pick songs to play for everyone" else ""
        )
        ProgressBar(
            positionMs = position,
            durationMs = duration,
            onSeek = if (song != null) { pos: Long -> scope.launch { AudioSyncManager.playFrom(pos) }; Unit } else null
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { scope.launch { if (index > 0) AudioSyncManager.skip(-1) else AudioSyncManager.playFrom(0L) } },
                enabled = index > 0 || song != null,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Previous song" }
            ) { SkipIcon(forward = false, color = if (index > 0 || song != null) Ink else Line) }

            val canPlay = active || songUri != null
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(if (canPlay) Accent else Line)
                    .semantics { contentDescription = if (active) "Pause for everyone" else "Play for everyone" },
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        when {
                            active -> AudioSyncManager.pause()
                            paused != null -> scope.launch { AudioSyncManager.playFrom(paused!!.positionMs) }
                            else -> scope.launch { AudioSyncManager.playFrom(0L) }
                        }
                    },
                    enabled = canPlay,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (active) PauseIcon(Bg) else PlayIcon(if (canPlay) Bg else Faint)
                }
            }

            val hasNext = index + 1 < playlist.size
            IconButton(
                onClick = { scope.launch { AudioSyncManager.skip(1) } },
                enabled = hasNext,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Next song" }
            ) { SkipIcon(forward = true, color = if (hasNext) Ink else Line) }
        }

        if (starting) {
            Text(
                "Starting on every phone…",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }

        if (playlist.isNotEmpty()) {
            val nextUri = playlist.getOrNull(index + 1)
            val nextTitle by produceState<String?>(null, nextUri) {
                value = nextUri?.let { uri ->
                    withContext(Dispatchers.IO) { AudioSyncManager.songDetails(uri) }
                        ?.let { if (it.artist.isNotEmpty()) "${it.title} · ${it.artist}" else it.title }
                }
            }
            Surface(
                onClick = { showQueue = true },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionLabel("Up next")
                    Text(
                        if (nextUri == null) "End of the list" else nextTitle ?: "…",
                        color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("${index + 1} of ${playlist.size}", color = Muted, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton(if (songUri == null) "Pick songs" else "Change songs", height = 52.dp) {
            FilePickerHelper.openAudioPicker(activity)
        }
        TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Stop hosting", color = Faint, fontSize = 14.sp)
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

/** The host's playlist: tap a song to switch to it, or add more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val playlist by AudioSyncManager.playlist.collectAsState()
    val index by AudioSyncManager.playlistIndex.collectAsState()
    val details by produceState(emptyMap<Uri, AudioSyncManager.NowPlaying>(), playlist) {
        value = withContext(Dispatchers.IO) {
            playlist.mapNotNull { uri -> AudioSyncManager.songDetails(uri)?.let { uri to it } }.toMap()
        }
    }
    val totalMinutes = details.values.sumOf { it.durationMs } / 60_000

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, contentColor = Ink) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Up next", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "${playlist.size} song${if (playlist.size == 1) "" else "s"}" + if (totalMinutes > 0) " · $totalMinutes min" else "",
                    color = Muted, fontSize = 13.sp
                )
            }
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                playlist.forEachIndexed { i, uri ->
                    val song = details[uri]
                    val current = i == index
                    Surface(
                        onClick = { scope.launch { AudioSyncManager.playIndex(i) } },
                        shape = RoundedCornerShape(14.dp),
                        color = if (current) Raised else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                                if (current) PlayIcon(Accent, 16.dp)
                                else Text("${i + 1}", color = Faint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    song?.title ?: "…", color = if (current) Accent else Ink, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (!song?.artist.isNullOrEmpty()) {
                                    Text(song!!.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if ((song?.durationMs ?: 0L) > 0) Text(formatTime(song!!.durationMs), color = Faint, fontSize = 13.sp)
                        }
                    }
                }
            }
            Text(
                "Joined phones fetch the next song while this one plays, so the switch is seamless.",
                color = Faint, fontSize = 13.sp, lineHeight = 19.sp
            )
            SecondaryButton("Add songs", height = 52.dp) {
                AudioSyncManager.addingSongs = true
                FilePickerHelper.openAudioPicker(activity)
            }
        }
    }
}

@Composable
private fun ListenerScreen(onSettings: () -> Unit, onLeave: () -> Unit) {
    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val drift by AudioSyncManager.driftMs.collectAsState()
    val status by AudioSyncManager.status.collectAsState()
    val download by AudioSyncManager.download.collectAsState()
    val side by AudioSyncManager.speakerSide.collectAsState()
    val now = rememberNow()
    val playing = nowPlaying?.takeIf { isPlaying(it, now) }

    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("Listening with")
                Text(AudioSyncManager.hostName.ifEmpty { "the host" }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            SquareIconButton("Settings", onClick = onSettings) { SettingsIcon() }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)
        ) {
          val dl = download
          if (playing == null && dl != null) {
            DownloadRing(dl.percent)
            Badge("Getting the song from the host", Muted, Panel)
            SongTitle(title = dl.song.substringBeforeLast('.'), artist = "", centered = true)
            Text(
                "This phone joins in, in sync, as soon as the song is here.",
                color = Faint, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp)
            )
          } else {
            Box(
                Modifier.size(220.dp).border(2.dp, if (playing != null) Accent else Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(168.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    if (side != AudioSyncManager.SIDE_BOTH) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(side.take(1), color = Accent, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                            Text("$side speaker", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Bars(listOf(24, 44, 68, 52, 84, 60, 40, 56, 28), barWidth = 6.dp, gap = 5.dp) {
                            if (playing != null) Accent else Unplayed
                        }
                    }
                }
            }

            if (playing != null) {
                val d = drift
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(if (d == null) "Getting in sync…" else "In sync · ±${abs(d)} ms", Accent, AccentDim)
                    if (side != AudioSyncManager.SIDE_BOTH) {
                        OutlinedButton(onClick = onSettings, border = BorderStroke(1.5.dp, Line), shape = RoundedCornerShape(20.dp)) {
                            Text("Change side", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Badge(status.ifEmpty { "Waiting for the host" }, Muted, Panel)
            }

            SongTitle(
                title = playing?.title ?: "Waiting for the host",
                artist = playing?.artist ?: "",
                centered = true
            )
            ProgressBar(
                positionMs = playing?.let { now - it.startsAtLocalMs } ?: 0L,
                durationMs = playing?.durationMs ?: 0L
            )
          }
        }

        Text(
            if (side == AudioSyncManager.SIDE_BOTH) "The host controls playback. Keep this screen open."
            else "Put this phone on the ${side.lowercase()}, facing the room.",
            color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        SecondaryButton("Leave room", height = 52.dp, onClick = onLeave)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var adjust by remember { mutableStateOf(AudioSyncManager.adjustMs) }
    var copied by remember { mutableStateOf(false) }
    fun set(value: Long) {
        adjust = value
        AudioSyncManager.adjustMs = value
        SyncLog.add("Fine-tune: $value ms")
    }

    val side by AudioSyncManager.speakerSide.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, contentColor = Ink) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Settings", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Raised).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Speaker side", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "For stereo, set one phone to Left and one to Right, and place them that way round.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(AudioSyncManager.SIDE_LEFT, AudioSyncManager.SIDE_BOTH, AudioSyncManager.SIDE_RIGHT).forEach { option ->
                        val selected = side == option
                        val content = if (selected) Bg else Ink
                        val off = if (selected) Bg.copy(alpha = 0.25f) else Unplayed
                        Column(
                            Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) Accent else Color.Transparent)
                                .clickable { AudioSyncManager.setSpeakerSide(option) }
                                .semantics { contentDescription = "$option speaker" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(if (option != AudioSyncManager.SIDE_RIGHT) content else off))
                                Box(Modifier.size(12.dp).clip(CircleShape).background(if (option != AudioSyncManager.SIDE_LEFT) content else off))
                            }
                            Text(option, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    when (side) {
                        AudioSyncManager.SIDE_LEFT -> "This phone plays only the left channel."
                        AudioSyncManager.SIDE_RIGHT -> "This phone plays only the right channel."
                        else -> "This phone plays the full mix, as normal."
                    } + " Mono songs sound the same either way.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
            }

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Raised).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Fine-tune this phone", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Hear an echo, e.g. with a Bluetooth speaker? Nudge this phone until it lines up.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { set(adjust - 5) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Unplayed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) { Text("Earlier", fontWeight = FontWeight.SemiBold) }
                    Text(
                        "${if (adjust > 0) "+" else ""}$adjust ms",
                        color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.width(84.dp)
                    )
                    OutlinedButton(
                        onClick = { set(adjust + 5) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Unplayed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) { Text("Later", fontWeight = FontWeight.SemiBold) }
                }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Raised)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(SyncLog.report(context)))
                            copied = true
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Report a problem", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Copies the sync log to paste in a message", color = Muted, fontSize = 13.sp)
                    }
                    Text(if (copied) "Copied" else "Copy log", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Line))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenAdvanced).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Testing tools", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("›", color = Muted, fontSize = 22.sp)
                }
            }

            Text(
                "SyncAudio v${SyncLog.appVersion(context)}",
                color = Faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton("Done", onClick = onDismiss)
        }
    }
}

// ---- pieces ----

/** Current time, refreshed a few times a second for progress bars. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return now
}

private fun isPlaying(song: AudioSyncManager.NowPlaying, now: Long): Boolean {
    val elapsed = now - song.startsAtLocalMs
    return elapsed >= 0 && (song.durationMs <= 0 || elapsed < song.durationMs)
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun SongTitle(title: String, artist: String, centered: Boolean = false) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        if (artist.isNotEmpty()) Text(artist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Song position bar; with [onSeek], tapping it jumps there (for everyone, on the host). */
@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long, onSeek: ((Long) -> Unit)? = null) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seek by rememberUpdatedState(onSeek)
    val duration by rememberUpdatedState(durationMs)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val onTap = seek ?: return@detectTapGestures
                        if (duration > 0) onTap((tap.x / size.width * duration).toLong().coerceIn(0L, (duration - 1000).coerceAtLeast(0L)))
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink))
            }
            if (onSeek != null && durationMs > 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(fraction.coerceAtLeast(0.0001f)))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Ink))
                    Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), color = Faint, fontSize = 12.sp)
            Text(formatTime(durationMs), color = Faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, background: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(background).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun Bars(heights: List<Int>, barWidth: Dp, gap: Dp, color: (Int) -> Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { i, h ->
            Box(Modifier.width(barWidth).height(h.dp).clip(RoundedCornerShape(50)).background(color(i)))
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, height: Dp = 60.dp, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SquareIconButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        modifier = Modifier.size(44.dp).semantics { contentDescription = label }
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun BackIcon() {
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.25f)
            lineTo(size.width * 0.37f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.75f)
        }
        drawPath(path, Ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun SettingsIcon() {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawLine(Ink, Offset(w * 0.15f, h * 0.3f), Offset(w * 0.85f, h * 0.3f), stroke, StrokeCap.Round)
        drawLine(Ink, Offset(w * 0.15f, h * 0.7f), Offset(w * 0.85f, h * 0.7f), stroke, StrokeCap.Round)
        drawCircle(Panel, w * 0.13f, Offset(w * 0.65f, h * 0.3f))
        drawCircle(Ink, w * 0.11f, Offset(w * 0.65f, h * 0.3f), style = Stroke(stroke))
        drawCircle(Panel, w * 0.13f, Offset(w * 0.38f, h * 0.7f))
        drawCircle(Ink, w * 0.11f, Offset(w * 0.38f, h * 0.7f), style = Stroke(stroke))
    }
}

/** Ring that fills as the song arrives, with the percentage inside. */
@Composable
private fun DownloadRing(percent: Int) {
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(Accent, -90f, 360f * percent / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(
            Modifier.size(168.dp).clip(CircleShape).background(Panel),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("$percent%", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("received", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayIcon(color: Color, iconSize: Dp = 30.dp) {
    Canvas(Modifier.size(iconSize)) {
        val path = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.2f)
            lineTo(size.width * 0.82f, size.height * 0.5f)
            lineTo(size.width * 0.32f, size.height * 0.8f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PauseIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val barWidth = size.width * 0.18f
        val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        drawRoundRect(color, Offset(size.width * 0.22f, size.height * 0.18f), Size(barWidth, size.height * 0.64f), radius)
        drawRoundRect(color, Offset(size.width * 0.60f, size.height * 0.18f), Size(barWidth, size.height * 0.64f), radius)
    }
}

/** Previous / next: a triangle against a bar. */
@Composable
private fun SkipIcon(forward: Boolean, color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val tip = if (forward) w * 0.72f else w * 0.28f
        val base = if (forward) w * 0.25f else w * 0.75f
        val path = Path().apply {
            moveTo(base, h * 0.22f)
            lineTo(tip, h * 0.5f)
            lineTo(base, h * 0.78f)
            close()
        }
        drawPath(path, color)
        val barX = if (forward) w * 0.74f else w * 0.18f
        drawRoundRect(color, Offset(barX, h * 0.22f), Size(w * 0.08f, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun StopIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
            size = Size(size.width * 0.7f, size.height * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        )
    }
}

// ---- party mode ----

private val PARTY_COLORS = listOf(Accent, Color(0xFF5B8CFF), Color(0xFFC77DFF), Color(0xFFFF8A3D))
private const val PARTY_MIX = 4 // colour choice that cycles through all of PARTY_COLORS

/** The looks, with what each does. */
private val PARTY_LOOKS = listOf(
    "Pulse" to "Every screen flickers brighter on each hit in the music.",
    "Chase" to "Light runs from phone to phone, in the order you line them up.",
    "Message" to "Spell a word across the phones, one letter each, flashing to the music.",
    "Lava" to "Slow, calm colour blobs that swell with the music. For chill songs.",
    "Strobe" to "Short flashes on big hits, at most 3 a second. Not for anyone sensitive to flashing light."
)

private val PARTY_REACT = listOf("Bass" to "Bass", "Song" to "Whole song", "Vocals" to "Vocals")

private fun seatColor(seat: Int) = PARTY_COLORS[seat % PARTY_COLORS.size]

/** Host: choose the look and how it reacts, then line up the phones. */
@Composable
private fun PartyStudioScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val current = AudioSyncManager.party.collectAsState().value
    var style by remember { mutableStateOf(current?.style?.takeIf { s -> PARTY_LOOKS.any { it.first == s } } ?: "Pulse") }
    var color by remember { mutableStateOf(current?.color ?: 0) }
    var react by remember { mutableStateOf(current?.react ?: "Bass") }
    var intensity by remember { mutableStateOf((current?.intensity ?: 70).toFloat()) }
    var torch by remember { mutableStateOf(current?.torch ?: false) }
    var message by remember { mutableStateOf(current?.message ?: "PARTY") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareIconButton("Back", onClick = onBack) { BackIcon() }
            Spacer(Modifier.width(12.dp))
            Text("Party studio", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        SectionLabel("Look")
        PARTY_LOOKS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, _) ->
                    val selected = style == key
                    Surface(
                        onClick = { style = key },
                        shape = RoundedCornerShape(16.dp),
                        color = Panel,
                        border = BorderStroke(2.dp, if (selected) Accent else Color.Transparent),
                        modifier = Modifier.weight(1f).semantics { contentDescription = key }
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LookPreview(key)
                            Text(key, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(PARTY_LOOKS.first { it.first == style }.second, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)

        if (style == "Message") {
            OutlinedTextField(
                value = message,
                onValueChange = { text -> message = text.uppercase().filter { it != '|' }.take(20) },
                label = { Text("Word to spell") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                    focusedBorderColor = Accent, unfocusedBorderColor = Line,
                    focusedLabelColor = Accent, unfocusedLabelColor = Muted
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (0..PARTY_MIX).forEach { i ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (i < PARTY_COLORS.size) PARTY_COLORS[i] else Raised)
                            .border(3.dp, if (color == i) Ink else Color.Transparent, CircleShape)
                            .clickable { color = i }
                            .semantics { contentDescription = if (i < PARTY_COLORS.size) "Colour ${i + 1}" else "Mix of colours" },
                        contentAlignment = Alignment.Center
                    ) {
                        if (i == PARTY_MIX) Row { PARTY_COLORS.forEach { Box(Modifier.size(width = 6.dp, height = 18.dp).background(it)) } }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("React to", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PARTY_REACT.forEach { (key, label) ->
                    val selected = react == key
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp))
                            .background(if (selected) Accent else Color.Transparent)
                            .clickable { react = key },
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = if (selected) Bg else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(intensityLabel(intensity.toInt()), color = Muted, fontSize = 13.sp)
            }
            Slider(
                value = intensity, onValueChange = { intensity = it }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Accent, inactiveTrackColor = Line)
            )
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Camera flash on big hits", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Uses each phone's torch, at most 3 flashes a second.", color = Muted, fontSize = 13.sp)
            }
            Switch(
                checked = torch, onCheckedChange = { torch = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Bg)
            )
        }

        PrimaryButton("Next: line up the phones") {
            AudioSyncManager.startParty(
                AudioSyncManager.Party(style, color, intensity.toInt(), react, torch, lineup = true, message = message.ifBlank { "PARTY" })
            )
            onNext()
        }
    }
}

private fun intensityLabel(value: Int) = when {
    value < 34 -> "Calm"
    value < 67 -> "Lively"
    else -> "Wild"
}

/** Small picture of a look for the studio tiles. */
@Composable
private fun LookPreview(look: String) {
    val shape = RoundedCornerShape(10.dp)
    val modifier = Modifier.fillMaxWidth().height(84.dp).clip(shape)
    when (look) {
        "Pulse" -> Box(modifier.background(Accent))
        "Chase" -> Row(modifier) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Unplayed))
            Box(Modifier.weight(1f).fillMaxHeight().background(PARTY_COLORS[1]))
            Box(Modifier.weight(1f).fillMaxHeight().background(Unplayed))
        }
        "Message" -> Box(modifier.background(PARTY_COLORS[2]), contentAlignment = Alignment.Center) {
            Text("HI", color = Bg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        "Lava" -> Box(
            modifier.background(
                Brush.radialGradient(listOf(PARTY_COLORS[3], PARTY_COLORS[2], Color(0xFF2A1838)))
            )
        )
        else -> Column(modifier) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Ink))
            Box(Modifier.weight(1f).fillMaxWidth().background(Bg))
        }
    }
}

/** Host: every phone shows its number; put them in order, 1 on the left. */
@Composable
private fun PartyLineupScreen(onBack: () -> Unit, onStart: () -> Unit) {
    val order by AudioSyncManager.partyOrder.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareIconButton("Back", onClick = onBack) { BackIcon() }
            Spacer(Modifier.width(12.dp))
            Text("Line up the phones", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Each phone now shows a big number. Put them in a row, 1 on the left, so Chase and Message travel the right way.",
            color = Muted, fontSize = 14.sp, lineHeight = 21.sp
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            order.indices.forEach { i ->
                Box(
                    Modifier.size(width = 60.dp, height = 108.dp).clip(RoundedCornerShape(14.dp)).background(Raised).padding(4.dp)
                ) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(seatColor(i)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Bg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            order.forEachIndexed { i, id ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(seatColor(i)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        AudioSyncManager.phoneName(id), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { AudioSyncManager.moveInLine(id, -1) }, enabled = i > 0, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "Move left" }) {
                        Text("▲", color = if (i > 0) Ink else Line)
                    }
                    TextButton(onClick = { AudioSyncManager.moveInLine(id, 1) }, enabled = i < order.size - 1, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "Move right" }) {
                        Text("▼", color = if (i < order.size - 1) Ink else Line)
                    }
                    OutlinedButton(
                        onClick = { AudioSyncManager.flashPhone(id) },
                        border = BorderStroke(1.5.dp, Line), shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("Flash it", color = Ink, fontSize = 13.sp) }
                }
            }
        }
        Text(
            if (order.size < 2) "No other phones yet. Phones that joined show up here within a few seconds."
            else "Use ▲ ▼ to change the order. \"Flash it\" blinks that phone so you can find it.",
            color = Faint, fontSize = 13.sp, lineHeight = 19.sp
        )

        PrimaryButton("Start the party", onClick = onStart)
    }
}

/** The camera light, for flashes on big hits; quietly does nothing on phones that won't share it. */
private class Torch(context: Context) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val id: String? = try {
        cameras?.cameraIdList?.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        null
    }

    fun set(on: Boolean) {
        try {
            id?.let { cameras?.setTorchMode(it, on) }
        } catch (e: Exception) {
            // camera busy or not allowed; skip this flash
        }
    }
}

/**
 * Full-screen light show, the same on every phone: the music drives it (each phone measures the song it
 * plays in sync), and anything that moves over time uses the song's position, which is in sync too.
 */
@Composable
private fun PartyScreen(party: AudioSyncManager.Party, isHost: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val torch = remember { Torch(context) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            torch.set(false)
        }
    }
    val currentParty by rememberUpdatedState(party)

    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val paused by AudioSyncManager.paused.collectAsState()
    val drift by AudioSyncManager.driftMs.collectAsState()

    var level by remember { mutableStateOf(0f) }
    var hit by remember { mutableStateOf(0f) } // how far the music just jumped above its recent average
    var songMs by remember { mutableStateOf(0L) }
    var strobeOn by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var average = 0f
        var lastFlash = 0L
        var lastTorch = 0L
        while (true) {
            withFrameNanos { }
            val now = System.currentTimeMillis()
            val l = AudioSyncManager.partyLevel()
            average += (l - average) * 0.04f
            val h = ((l - average) / (1f - average).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
            level = l
            hit = h
            songMs = AudioSyncManager.songPositionMs()
            clock = now
            // Strobe and torch: a short flash on a big hit, at most 3 a second
            val bigHit = h > 0.45f && l > 0.3f
            strobeOn = if (bigHit && now - lastFlash >= 333) {
                lastFlash = now
                true
            } else {
                now - lastFlash < 70
            }
            if (currentParty.torch && bigHit && now - lastTorch >= 333) {
                lastTorch = now
                torch.set(true)
                scope.launch {
                    delay(60)
                    torch.set(false)
                }
            }
        }
    }

    var controlsShownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsShownAt) {
        controlsVisible = true
        delay(5000)
        controlsVisible = false
    }

    val k = party.intensity / 100f
    val cycle = (songMs / 4000).toInt() // the mix of colours moves on every 4 s, in step on every phone
    val base = if (party.color >= PARTY_COLORS.size) PARTY_COLORS[(party.seat + cycle) % PARTY_COLORS.size] else PARTY_COLORS[party.color]
    val blink = (clock / 150) % 2 == 0L
    val playing = songMs > 0

    // Pulse flickers on each hit rather than following overall loudness, so it reacts fast
    val pulse = (hit * (0.6f + 0.9f * k)).coerceIn(0f, 1f)
    val background = when {
        party.lineup -> if (party.flashMe && blink) Ink else seatColor(party.seat)
        party.flashMe -> if (blink) Ink else Bg
        party.drop -> Ink
        party.style == "Strobe" -> if (strobeOn) base else Bg
        party.style == "Chase" -> {
            val stepMs = (800 - 600 * k).toLong().coerceAtLeast(150L)
            val lit = playing && (songMs / stepMs) % party.seats == party.seat.toLong()
            if (lit) lerp(Bg, seatColorOr(base, party), 0.55f + 0.45f * level) else lerp(Bg, base, 0.05f)
        }
        party.style == "Message" -> lerp(Bg, seatColorOr(base, party), 0.35f + 0.65f * pulse)
        party.style == "Lava" -> lerp(Bg, base, 0.12f)
        else -> lerp(Bg, base, 0.06f + 0.94f * pulse)
    }
    val onBackground = if (background.luminance() > 0.4f) Bg else Ink

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) { detectTapGestures { controlsShownAt = System.currentTimeMillis() } }
    ) {
        if (party.style == "Lava" && !party.lineup && !party.flashMe && !party.drop) {
            Canvas(Modifier.fillMaxSize()) {
                val t = songMs / 1000f
                val swell = 0.32f + 0.25f * level * (0.5f + k)
                val second = PARTY_COLORS[(PARTY_COLORS.indexOf(base).coerceAtLeast(0) + 1) % PARTY_COLORS.size]
                listOf(
                    Triple(base, 0.6f + party.seat, 0.45f),
                    Triple(second, 1.9f + party.seat, 0.6f)
                ).forEach { (c, phase, speed) ->
                    val center = Offset(
                        size.width * (0.5f + 0.3f * kotlin.math.sin(t * speed + phase)),
                        size.height * (0.45f + 0.25f * kotlin.math.cos(t * speed * 0.8f + phase))
                    )
                    val radius = size.width * swell
                    drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, radius), radius, center)
                }
            }
        }

        // Big centre content: the phone's number while lining up, a letter for Message
        when {
            party.lineup -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${party.seat + 1}", color = Bg, fontSize = 160.sp, fontWeight = FontWeight.Bold, lineHeight = 160.sp)
                Text("Phone ${party.seat + 1} of ${party.seats}", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            party.style == "Message" && !party.flashMe && !party.drop -> {
                val word = party.message.ifBlank { "PARTY" }
                val index = if (word.length > party.seats) ((party.seat + songMs / 1000) % word.length).toInt() else party.seat
                Text(
                    word.getOrNull(index)?.toString() ?: "",
                    color = onBackground, fontSize = 200.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        if (!party.lineup) {
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)) {
                if (controlsVisible && !isHost) {
                    Row {
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(onBackground.copy(alpha = 0.14f))
                                .clickable(onClick = onExit)
                                .semantics { contentDescription = "Exit party mode on this phone" },
                            contentAlignment = Alignment.Center
                        ) { CloseIcon(onBackground) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!isHost && party.style != "Message") {
                    val song = nowPlaying ?: paused?.song
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            song?.title ?: "Waiting for the host", color = onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        val d = drift
                        if (nowPlaying != null && d != null) {
                            Text("In sync · ±${abs(d)} ms", color = onBackground.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (isHost && controlsVisible) PartyControls(party, onExit)
            }
        }
    }
}

/** A Chase or Message phone in a mix keeps its own colour, so the line reads as different phones. */
private fun seatColorOr(base: Color, party: AudioSyncManager.Party) =
    if (party.color >= PARTY_COLORS.size) seatColor(party.seat) else base

/** Host's live panel: the song, the look, intensity, the big flash, and End. */
@Composable
private fun PartyControls(party: AudioSyncManager.Party, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val paused by AudioSyncManager.paused.collectAsState()
    val playlist by AudioSyncManager.playlist.collectAsState()
    val index by AudioSyncManager.playlistIndex.collectAsState()
    var intensity by remember { mutableStateOf(party.intensity.toFloat()) }
    val song = nowPlaying ?: paused?.song
    val active = nowPlaying != null

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Bg.copy(alpha = 0.92f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: "No song playing", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Party on ${party.seats} phone${if (party.seats == 1) "" else "s"}", color = Muted, fontSize = 12.sp)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Ink)
                    .clickable {
                        when {
                            active -> AudioSyncManager.pause()
                            paused != null -> scope.launch { AudioSyncManager.playFrom(paused!!.positionMs) }
                            else -> scope.launch { AudioSyncManager.playFrom(0L) }
                        }
                    }
                    .semantics { contentDescription = if (active) "Pause for everyone" else "Play for everyone" },
                contentAlignment = Alignment.Center
            ) { if (active) PauseIcon(Bg) else PlayIcon(Bg, 22.dp) }
            val hasNext = index + 1 < playlist.size
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Raised)
                    .clickable(enabled = hasNext) { scope.launch { AudioSyncManager.skip(1) } }
                    .semantics { contentDescription = "Next song" },
                contentAlignment = Alignment.Center
            ) { SkipIcon(forward = true, color = if (hasNext) Ink else Line) }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PARTY_LOOKS.forEach { (key, _) ->
                val selected = party.style == key
                Text(
                    key, color = if (selected) Bg else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) Ink else Raised)
                        .clickable { AudioSyncManager.updateParty { it.copy(style = key) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intensity", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
            Slider(
                value = intensity,
                onValueChange = { intensity = it },
                onValueChangeFinished = { AudioSyncManager.updateParty { it.copy(intensity = intensity.toInt()) } },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Accent, inactiveTrackColor = Line),
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Accent)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            AudioSyncManager.setDrop(true)
                            tryAwaitRelease()
                            AudioSyncManager.setDrop(false)
                        })
                    }
                    .semantics { contentDescription = "Hold for a big flash on every phone" },
                contentAlignment = Alignment.Center
            ) { Text("Hold for a big flash", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Line)
            ) { Text("End", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PartyIcon() {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        // a burst: short rays around a dot
        drawCircle(Accent, w * 0.14f, Offset(w / 2, h / 2))
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            drawLine(
                Ink,
                Offset(w / 2 + cos * w * 0.28f, h / 2 + sin * h * 0.28f),
                Offset(w / 2 + cos * w * 0.46f, h / 2 + sin * h * 0.46f),
                stroke, StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CloseIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.2.dp.toPx()
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), stroke, StrokeCap.Round)
    }
}
