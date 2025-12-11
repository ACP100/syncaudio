package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.TimeSync
import com.example.syncaudio.NetworkUtils
import com.example.syncaudio.composables.components.CompactTestSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

// Added so your debug logs stop breaking
fun getFormattedTime(): String {
    val now = System.currentTimeMillis()
    val seconds = (now / 1000) % 60
    val minutes = (now / 1000 / 60) % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun TimeSyncSection(
    serverIpAddress: String,
    localIpAddress: String,
    onStartTimeServer: () -> Unit,
    onStopTimeServer: () -> Unit,
    onTestTimeSync: () -> Unit,
    onTestLocalSync: () -> Unit,
    debugLogs: MutableList<String>,
    onStatusUpdate: (String) -> Unit,
    onTimeSyncStatusUpdate: (String) -> Unit,
    onConnectionResult: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    CompactTestSection(
        title = "⏰ Time Sync Tests",
        description = "NTP-style sync"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartTimeServer,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Time", fontSize = 15.sp)
                }
                Button(
                    onClick = onStopTimeServer,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Stop Time", fontSize = 15.sp)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onTimeSyncStatusUpdate("Syncing...")
                        onStatusUpdate("Starting time sync...")
                        debugLogs.add("🔄 [${getFormattedTime()}] Test Sync button clicked - starting...")

                        if (serverIpAddress.isEmpty()) {
                            onStatusUpdate("Enter server IP")
                            onTimeSyncStatusUpdate("Failed")
                            debugLogs.add("❌ [${getFormattedTime()}] No server IP provided")
                            onConnectionResult("Error: No server IP")
                            return@Button
                        }

                        if (serverIpAddress == localIpAddress) {
                            onStatusUpdate("Cannot sync with own IP")
                            onTimeSyncStatusUpdate("Failed")
                            debugLogs.add("⚠️ [${getFormattedTime()}] Cannot sync with own IP")
                            onConnectionResult("Error: Cannot sync with self")
                            return@Button
                        }

                        debugLogs.add("🎯 [${getFormattedTime()}] Attempting to sync with: $serverIpAddress")

                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    withContext(Dispatchers.Main) {
                                        debugLogs.add("🏓 [${getFormattedTime()}] Testing connection...")
                                    }

                                    val isReachable = NetworkUtils.pingAddress(serverIpAddress, 2000)

                                    if (!isReachable) {
                                        withContext(Dispatchers.Main) {
                                            onStatusUpdate("Server not reachable")
                                            onTimeSyncStatusUpdate("Failed")
                                            debugLogs.add("❌ [${getFormattedTime()}] Server not reachable")
                                            onConnectionResult("Error: Server not reachable")
                                        }
                                        return@withContext
                                    }

                                    withContext(Dispatchers.Main) {
                                        debugLogs.add("✅ [${getFormattedTime()}] Server reachable, syncing...")
                                    }

                                    val offset = TimeSync.getOffset(serverIpAddress)

                                    withContext(Dispatchers.Main) {
                                        onTimeSyncStatusUpdate("Synced: ${offset}ms")
                                        onStatusUpdate("Offset: ${offset}ms")
                                        debugLogs.add("✅ [${getFormattedTime()}] Sync successful! Offset: ${offset}ms")
                                        onConnectionResult("Success: Offset ${offset}ms")
                                    }
                                }
                            } catch (e: SocketTimeoutException) {
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate("Server timeout")
                                    onTimeSyncStatusUpdate("Timeout")
                                    debugLogs.add("⏰ [${getFormattedTime()}] Timeout: ${e.message}")
                                    onConnectionResult("Error: Timeout")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate("Sync error")
                                    onTimeSyncStatusUpdate("Failed")
                                    debugLogs.add("❌ [${getFormattedTime()}] Sync failed: ${e.message}")
                                    onConnectionResult("Error: ${e.message}")
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Sync", fontSize = 15.sp)
                }

                Button(
                    onClick = onTestLocalSync,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Local", fontSize = 15.sp)
                }
            }
        }
    }
}
