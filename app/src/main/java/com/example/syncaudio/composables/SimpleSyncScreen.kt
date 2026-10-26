// File: composables/SimpleSyncScreen.kt
package com.example.syncaudio.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.example.syncaudio.*
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

private val WAVE = listOf(30, 52, 74, 40, 96, 120, 84, 60, 140, 170, 120, 90, 150, 190, 160, 110, 130, 170, 140, 100, 80, 120, 90, 60, 70, 44, 56, 32)

private enum class Screen { WELCOME, JOIN, HOST, LISTENER }

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
            Screen.HOST -> HostScreen(
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
private fun HostScreen(onSettings: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val songUri by FileSelectionManager.selectedFileUri.collectAsState()
    val pickedName = remember(songUri) { songUri?.let { AudioSyncManager.trackName(context, it).substringBeforeLast('.') } }
    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val now = rememberNow()
    var joined by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            joined = AudioSyncManager.joinedPhoneCount()
            delay(1000)
        }
    }

    val playing = nowPlaying?.takeIf { isPlaying(it, now) }
    val progress = playing?.let { progressOf(it, now) } ?: 0f

    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionLabel("You're hosting")
                Text(AudioSyncManager.deviceName(), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SquareIconButton("Settings", onClick = onSettings) { SettingsIcon() }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (joined > 0) Accent else Faint))
            Text(
                when (joined) {
                    0 -> "No phones joined yet"
                    1 -> "1 phone joined"
                    else -> "$joined phones joined"
                },
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
        }

        Box(
            Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).background(Panel),
            contentAlignment = Alignment.Center
        ) {
            val played = (progress * WAVE.size).toInt()
            Bars(WAVE.map { it * 2 / 3 }, barWidth = 5.dp, gap = 5.dp) { i -> if (i < played) Accent else Unplayed }
        }

        SongTitle(
            title = playing?.title ?: pickedName ?: "No song picked",
            artist = playing?.artist ?: if (songUri == null) "Pick a song to play for everyone" else ""
        )
        ProgressBar(playing, now)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            val isOn = nowPlaying != null && (playing != null || nowPlaying!!.startsAtLocalMs > now)
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(if (songUri == null && !isOn) Line else Accent)
                    .semantics { contentDescription = if (isOn) "Stop for everyone" else "Play for everyone" },
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (isOn) {
                            AudioSyncManager.stopEveryone()
                        } else {
                            songUri?.let { uri -> scope.launch { AudioSyncManager.broadcastPlayCommand(uri, delayMs = 3000L) } }
                        }
                    },
                    enabled = isOn || songUri != null,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isOn) StopIcon(Bg) else PlayIcon(if (songUri == null) Faint else Bg)
                }
            }
        }
        if (nowPlaying != null && playing == null && nowPlaying!!.startsAtLocalMs > now) {
            Text(
                "Starting on every phone…",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton(if (songUri == null) "Pick a song" else "Change song", height = 52.dp) {
            FilePickerHelper.openAudioPicker(activity)
        }
        TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Stop hosting", color = Faint, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ListenerScreen(onSettings: () -> Unit, onLeave: () -> Unit) {
    val nowPlaying by AudioSyncManager.nowPlaying.collectAsState()
    val drift by AudioSyncManager.driftMs.collectAsState()
    val status by AudioSyncManager.status.collectAsState()
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
            Box(
                Modifier.size(220.dp).border(2.dp, if (playing != null) Accent else Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(168.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Bars(listOf(24, 44, 68, 52, 84, 60, 40, 56, 28), barWidth = 6.dp, gap = 5.dp) {
                        if (playing != null) Accent else Unplayed
                    }
                }
            }

            if (playing != null) {
                val d = drift
                Badge(if (d == null) "Getting in sync…" else "In sync · ±${abs(d)} ms", Accent, AccentDim)
            } else {
                Badge(status.ifEmpty { "Waiting for the host" }, Muted, Panel)
            }

            SongTitle(
                title = playing?.title ?: "Waiting for the host",
                artist = playing?.artist ?: "",
                centered = true
            )
            ProgressBar(playing, now)
        }

        Text(
            "The host controls playback. Keep this screen open.",
            color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        SecondaryButton("Leave room", height = 52.dp, onClick = onLeave)
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lines by SyncLog.lines.collectAsState()
    var adjust by remember { mutableStateOf(AudioSyncManager.adjustMs) }
    var copied by remember { mutableStateOf(false) }
    fun set(value: Long) {
        adjust = value
        AudioSyncManager.adjustMs = value
        SyncLog.add("Fine-tune: $value ms")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = Ink,
        textContentColor = Ink,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Hear an echo? Nudge this phone earlier or later.", color = Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { set(adjust - 5) }, border = BorderStroke(1.dp, Line)) { Text("Earlier", color = Ink) }
                    Text(
                        "${if (adjust > 0) "+" else ""}$adjust ms",
                        color = Ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp)
                    )
                    OutlinedButton(onClick = { set(adjust + 5) }, border = BorderStroke(1.dp, Line)) { Text("Later", color = Ink) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Debug log", color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(SyncLog.report(context)))
                        copied = true
                    }) { Text(if (copied) "Copied" else "Copy log", color = Accent) }
                }
                Box(Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (lines.isEmpty()) "Nothing yet" else lines.asReversed().joinToString("\n"),
                        color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp
                    )
                }
                Text("v${SyncLog.appVersion(context)}", color = Faint, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Accent) } },
        dismissButton = { TextButton(onClick = onOpenAdvanced) { Text("Testing tools", color = Faint) } }
    )
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

private fun progressOf(song: AudioSyncManager.NowPlaying, now: Long): Float =
    if (song.durationMs <= 0) 0f else ((now - song.startsAtLocalMs).toFloat() / song.durationMs).coerceIn(0f, 1f)

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

@Composable
private fun ProgressBar(song: AudioSyncManager.NowPlaying?, now: Long) {
    val elapsed = song?.let { now - it.startsAtLocalMs } ?: 0L
    val fraction = song?.let { progressOf(it, now) } ?: 0f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsed), color = Faint, fontSize = 12.sp)
            Text(formatTime(song?.durationMs ?: 0L), color = Faint, fontSize = 12.sp)
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

@Composable
private fun PlayIcon(color: Color) {
    Canvas(Modifier.size(30.dp)) {
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
