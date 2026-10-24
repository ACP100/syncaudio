// File: composables/SimpleSyncScreen.kt
package com.example.syncaudio.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Role { NONE, HOST, JOIN }

/**
 * The everyday flow: one phone hosts and picks a song, the others join, the host presses play.
 * Joining phones get the song from the host automatically.
 */
@Composable
fun SimpleSyncScreen(onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()

    var role by remember {
        mutableStateOf(
            when {
                AudioSyncManager.isHosting -> Role.HOST
                AudioSyncManager.isJoining -> Role.JOIN
                else -> Role.NONE
            }
        )
    }
    val status by AudioSyncManager.status.collectAsState()
    val songUri by FileSelectionManager.selectedFileUri.collectAsState()
    val songName = remember(songUri) { songUri?.let { AudioSyncManager.trackName(context, it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("SyncAudio", fontSize = 32.sp, fontWeight = FontWeight.Bold)

        when (role) {
            Role.NONE -> {
                Text(
                    "Play one song on several phones at the same time.\n" +
                        "Put all phones on the same Wi-Fi or hotspot first.",
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { AudioSyncManager.startHosting(); role = Role.HOST },
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text("Host: I'll pick the song", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { AudioSyncManager.startJoining(); role = Role.JOIN },
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text("Join another phone", fontSize = 18.sp) }
            }

            Role.HOST -> {
                var joined by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        joined = AudioSyncManager.joinedPhoneCount()
                        delay(1000)
                    }
                }
                val ip = remember { NetworkUtils.getLocalIpAddress(context) ?: "unknown" }

                Text("You're the host", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Joined phones: $joined", fontSize = 18.sp)
                Text("Your address: $ip", fontSize = 14.sp)

                Text(songName?.let { "Song: $it" } ?: "No song picked yet", textAlign = TextAlign.Center)
                OutlinedButton(
                    onClick = { FilePickerHelper.openAudioPicker(activity) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (songUri == null) "Pick a song" else "Pick a different song") }

                Button(
                    onClick = {
                        songUri?.let { uri ->
                            scope.launch { AudioSyncManager.broadcastPlayCommand(uri, delayMs = 3000L) }
                        }
                    },
                    enabled = songUri != null,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text("▶ Play for everyone", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { AudioSyncManager.stopEveryone() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("⏹ Stop for everyone") }

                FineTune()

                Text(
                    "Joined phones download the song automatically. Wait until they say Ready, then press play.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            Role.JOIN -> {
                var hostIp by remember { mutableStateOf("") }

                Text("Joining", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("The host picks the song and presses play. Just keep this screen open.", textAlign = TextAlign.Center)

                OutlinedButton(
                    onClick = { AudioSyncManager.stopHere() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("⏹ Stop on this phone") }

                FineTune()

                Text("Not finding the host? Type the address shown on the host's screen:", fontSize = 13.sp, textAlign = TextAlign.Center)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hostIp,
                        onValueChange = { hostIp = it },
                        label = { Text("Host address") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { AudioSyncManager.startJoining(hostIp) }) { Text("Connect") }
                }
            }
        }

        if (role != Role.NONE) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(16.dp), fontSize = 16.sp)
            }
            TextButton(onClick = {
                if (role == Role.HOST) AudioSyncManager.stopHosting() else AudioSyncManager.stopJoining()
                role = Role.NONE
            }) { Text("Leave") }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenAdvanced) { Text("Advanced testing tools", fontSize = 13.sp) }
    }
}

/** Per-phone nudge for what automatic sync can't see, such as a Bluetooth speaker's delay. */
@Composable
private fun FineTune() {
    var adjust by remember { mutableStateOf(AudioSyncManager.adjustMs) }
    fun set(value: Long) {
        adjust = value
        AudioSyncManager.adjustMs = value
    }
    Text("Still hear an echo? Fine-tune this phone:", fontSize = 13.sp, textAlign = TextAlign.Center)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { set(adjust - 20) }) { Text("◀ Earlier") }
        Text("${if (adjust > 0) "+" else ""}$adjust ms", modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        OutlinedButton(onClick = { set(adjust + 20) }) { Text("Later ▶") }
    }
}
