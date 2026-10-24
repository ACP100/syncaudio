// File: composables/sections/AudioPlayerSection.kt
package com.example.syncaudio.composables.sections

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.AudioSyncManager
import com.example.syncaudio.AudioSyncPlayer
import com.example.syncaudio.FileSelectionManager
import com.example.syncaudio.composables.components.CompactTestSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AudioPlayerSection(
    context: Context,
    scope: CoroutineScope,
    onStatusUpdate: (String) -> Unit
) {
    // Store a shared player instance
    var localPlayer by remember { mutableStateOf<AudioSyncPlayer?>(null) }

    CompactTestSection(
        title = "🎵 Audio Player Tests",
        description = "Audio playback"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                // Load audio button
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                onStatusUpdate("🎵 Loading audio...")
                                val fileUri: Uri? = FileSelectionManager.getSelectedFile()
                                if (fileUri != null) {
                                    // Reuse shared player
                                    val player = AudioSyncPlayer(context)
                                    player.loadAudio(fileUri)
                                    localPlayer = player
                                    onStatusUpdate("✅ Audio loaded locally")
                                } else {
                                    onStatusUpdate("⚠️ No audio file selected")
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Load Audio", fontSize = 15.sp)
                }

                // Play locally button
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                val player = localPlayer
                                if (player != null) {
                                    player.playAtTimestamp(System.currentTimeMillis() + 700L, 0L) // room for the output lead-in
                                    onStatusUpdate("▶️ Playing locally")
                                } else {
                                    onStatusUpdate("⚠️ Cannot play: Load audio first")
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Play Locally", fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Play synchronized button
            Button(
                onClick = {
                    scope.launch {
                        val fileUri: Uri? = FileSelectionManager.getSelectedFile()
                        if (fileUri != null) {
                            AudioSyncManager.broadcastPlayCommand(fileUri, delayMs = 2000L)
                            onStatusUpdate("🌐 Play command broadcast to all phones on the network")
                        } else {
                            onStatusUpdate("⚠️ Cannot play: No audio file selected")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Play Synchronized", fontSize = 15.sp)
            }

            // What the sync engine is doing; on the second phone this says why it didn't play
            val syncStatus by AudioSyncManager.status.collectAsState()
            Text(syncStatus, fontSize = 13.sp)
        }
    }
}
