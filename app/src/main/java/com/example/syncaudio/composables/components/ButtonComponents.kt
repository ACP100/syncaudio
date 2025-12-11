// File: composables/components/ButtonComponents.kt
package com.example.syncaudio.composables.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompactNetworkTestButtons(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onStartClient: () -> Unit,
    onResetClient: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartServer,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Server", fontSize = 15.sp)
            }
            Button(
                onClick = onStopServer,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Stop Server", fontSize = 15.sp)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartClient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Search", fontSize = 15.sp)
            }
            Button(
                onClick = onResetClient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset", fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun CompactTimeSyncTestButtons(
    onStartTimeServer: () -> Unit,
    onStopTimeServer: () -> Unit,
    onTestTimeSync: () -> Unit,
    onTestLocalSync: () -> Unit
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
                onClick = onTestTimeSync,
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

@Composable
fun CompactFileTransferTestButtons(
    onStartFileServer: () -> Unit,
    onTestFileClient: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStartFileServer,
            modifier = Modifier.weight(1f)
        ) {
            Text("Start File", fontSize = 15.sp)
        }
        Button(
            onClick = onTestFileClient,
            modifier = Modifier.weight(1f)
        ) {
            Text("Test Client", fontSize = 15.sp)
        }
    }
}

@Composable
fun CompactWifiDirectTestButtons(
    isSupported: Boolean = true,
    onDiscoverPeers: () -> Unit
) {
    Button(
        onClick = onDiscoverPeers,
        modifier = Modifier.fillMaxWidth(),
        enabled = isSupported
    ) {
        Text("Discover Peers", fontSize = 15.sp)
    }
}

@Composable
fun CompactAudioPlayerTestButtons(
    onLoadTestAudio: () -> Unit,
    onTestPlayback: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onLoadTestAudio,
            modifier = Modifier.weight(1f)
        ) {
            Text("Load Audio", fontSize = 15.sp)
        }
        Button(
            onClick = onTestPlayback,
            modifier = Modifier.weight(1f)
        ) {
            Text("Test Play", fontSize = 15.sp)
        }
    }
}

@Composable
fun CompactDebugUtilityButtons(
    onClearLogs: () -> Unit,
    onAddTestLog: () -> Unit,
    onSystemInfo: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onClearLogs,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("Clear", fontSize = 15.sp)
        }
        Button(
            onClick = onAddTestLog,
            modifier = Modifier.weight(1f)
        ) {
            Text("Add Log", fontSize = 15.sp)
        }
        Button(
            onClick = onSystemInfo,
            modifier = Modifier.weight(1f)
        ) {
            Text("Info", fontSize = 15.sp)
        }
    }
}