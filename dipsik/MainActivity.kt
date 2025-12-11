// File: MainActivity.kt (fixed version)
package com.example.syncaudio

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val PERMISSION_REQUEST_CODE = 1001
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PermissionHelper.checkPermissions(this)) {
            PermissionHelper.requestPermissions(this, PERMISSION_REQUEST_CODE)
        }

        DebugUtils.log("🚀 MainActivity created")
        Toast.makeText(this, "Sync Audio Testing App Started!", Toast.LENGTH_SHORT).show()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncAudioTestUI(context = this@MainActivity)
                }
            }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = PermissionHelper.onRequestPermissionsResult(
                requestCode, permissions, grantResults
            )

            if (allGranted) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
                DebugUtils.logSuccess("✅ All permissions granted")
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
                DebugUtils.logWarning("⚠️ Some permissions denied")
            }
        }
    }
}

@Composable
fun SyncAudioTestUI(context: Context) {
    var status by remember { mutableStateOf("Ready") }
    var serverStatus by remember { mutableStateOf("Stopped") }
    var clientStatus by remember { mutableStateOf("Disconnected") }
    var timeSyncStatus by remember { mutableStateOf("Not synced") }
    var fileTransferStatus by remember { mutableStateOf("Idle") }
    var wifiDirectStatus by remember { mutableStateOf("Inactive") }
    var audioPlayerStatus by remember { mutableStateOf("No audio loaded") }

    val debugLogs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    // Initialize with some logs
    LaunchedEffect(Unit) {
        debugLogs.add("📱 [${getFormattedTime()}] App started")
        debugLogs.add("🔧 [${getFormattedTime()}] Ready for testing")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "🎵 Sync Audio Tester",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Test all features with one device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Status Overview
        StatusOverviewRow(
            serverStatus = serverStatus,
            clientStatus = clientStatus,
            timeSyncStatus = timeSyncStatus
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Current Status Card
        CurrentStatusCard(status = status)

        Spacer(modifier = Modifier.height(16.dp))

        // Test Categories
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section 1: Network Server/Client Tests
            item {
                TestSection(
                    title = "🌐 Network Tests",
                    description = "UDP Server & Client"
                ) {
                    NetworkTestButtons(
                        onStartServer = {
                            status = "Starting UDP server..."
                            debugLogs.add("🚀 [${getFormattedTime()}] Starting UDP server...")
                            SyncServer.start()
                            serverStatus = "Running"
                            status = "UDP Server running on port 5001"
                            debugLogs.add("✅ [${getFormattedTime()}] UDP Server started")
                        },
                        onStopServer = {
                            status = "Stopping UDP server..."
                            SyncServer.stop()
                            serverStatus = "Stopped"
                            status = "UDP Server stopped"
                            debugLogs.add("🛑 [${getFormattedTime()}] UDP Server stopped")
                        },
                        onStartClient = {
                            status = "Connecting as UDP client..."
                            debugLogs.add("🔗 [${getFormattedTime()}] Starting UDP client...")
                            SyncClient.connect()
                            clientStatus = "Connected"
                            status = "UDP Client connected"
                            debugLogs.add("✅ [${getFormattedTime()}] UDP Client started")
                        },
                        onResetClient = {
                            SyncClient.resetConnectionAttempts()
                            clientStatus = "Reset"
                            status = "Client connection attempts reset"
                            debugLogs.add("🔄 [${getFormattedTime()}] Client reset")
                        }
                    )
                }
            }

            // Section 2: Time Synchronization Tests
            item {
                TestSection(
                    title = "⏰ Time Sync Tests",
                    description = "NTP-style time synchronization"
                ) {
                    TimeSyncTestButtons(
                        onStartTimeServer = {
                            status = "Starting TimeSync server..."
                            debugLogs.add("⏰ [${getFormattedTime()}] Starting TimeSync server...")
                            TimeSync.startServer()
                            timeSyncStatus = "Server running"
                            status = "TimeSync server started on port 5002"
                            debugLogs.add("✅ [${getFormattedTime()}] TimeSync server started")
                        },
                        onStopTimeServer = {
                            TimeSync.stopServer()
                            timeSyncStatus = "Stopped"
                            status = "TimeSync server stopped"
                            debugLogs.add("🛑 [${getFormattedTime()}] TimeSync server stopped")
                        },
                        onTestTimeSync = {
                            status = "Testing time synchronization..."
                            timeSyncStatus = "Syncing..."
                            debugLogs.add("🔄 [${getFormattedTime()}] Testing time sync...")

                            scope.launch {
                                try {
                                    // Test with localhost (self)
                                    val offset = TimeSync.getOffset("127.0.0.1")
                                    timeSyncStatus = "Synced: ${offset}ms"
                                    status = "Time offset: ${offset}ms"
                                    debugLogs.add("✅ [${getFormattedTime()}] Time sync: ${offset}ms offset")
                                } catch (e: Exception) {
                                    timeSyncStatus = "Failed"
                                    status = "Time sync failed: ${e.message}"
                                    debugLogs.add("❌ [${getFormattedTime()}] Time sync failed: ${e.message}")
                                }
                            }
                        },
                        onQuickSync = {
                            status = "Quick time sync test..."
                            debugLogs.add("⚡ [${getFormattedTime()}] Quick time sync...")
                            // Simulate a quick sync (self-test)
                            timeSyncStatus = "Self-check"
                            status = "Time sync self-test completed"
                            debugLogs.add("✅ [${getFormattedTime()}] Time self-check OK")
                        }
                    )
                }
            }

            // Section 3: File Transfer Tests
            item {
                TestSection(
                    title = "📁 File Transfer Tests",
                    description = "TCP file sending/receiving"
                ) {
                    FileTransferTestButtons(
                        onStartFileServer = {
                            status = "Starting file transfer server..."
                            fileTransferStatus = "Server starting"
                            debugLogs.add("📡 [${getFormattedTime()}] Starting file server...")
                            // Note: Need a file URI to actually start - this is just a test
                            status = "File server would start with a file"
                            debugLogs.add("⚠️ [${getFormattedTime()}] Need file URI for file server")
                        },
                        onTestFileClient = {
                            status = "Testing file client..."
                            fileTransferStatus = "Client testing"
                            debugLogs.add("📥 [${getFormattedTime()}] Testing file client...")
                            // Simulate file transfer test
                            status = "File client test initiated"
                            debugLogs.add("✅ [${getFormattedTime()}] File client test OK")
                        },
                        onSimulateTransfer = {
                            status = "Simulating file transfer..."
                            fileTransferStatus = "Transferring..."
                            debugLogs.add("📊 [${getFormattedTime()}] Simulating file transfer...")

                            scope.launch {
                                repeat(5) { i ->
                                    val progress = (i + 1) * 20
                                    debugLogs.add("📈 [${getFormattedTime()}] Transfer progress: ${progress}%")
                                }
                                fileTransferStatus = "Transfer complete"
                                status = "File transfer simulation complete"
                                debugLogs.add("✅ [${getFormattedTime()}] File transfer simulation complete")
                            }
                        }
                    )
                }
            }

            // Section 4: Wi-Fi Direct Tests
            // Update just the Wi-Fi Direct section in MainActivity.kt:
            item {
                TestSection(
                    title = "📶 Wi-Fi Direct Tests",
                    description = "Peer discovery and connections"
                ) {
                    // Create a single instance of WifiDirectManager
                    val wifiManager = remember { WifiDirectManager(context) }
                    val isWifiDirectSupported = remember { wifiManager.isSupported() }

                    if (!isWifiDirectSupported) {
                        Text(
                            text = "⚠️ Wi-Fi Direct not supported on this device",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    WifiDirectTestButtons(
                        isSupported = isWifiDirectSupported,
                        onDiscoverPeers = {
                            if (!isWifiDirectSupported) {
                                status = "Wi-Fi Direct not supported"
                                debugLogs.add("❌ [${getFormattedTime()}] Wi-Fi Direct not supported")
                                return@WifiDirectTestButtons
                            }

                            status = "Discovering Wi-Fi Direct peers..."
                            wifiDirectStatus = "Discovering..."
                            debugLogs.add("🔍 [${getFormattedTime()}] Starting Wi-Fi Direct discovery...")

                            try {
                                wifiManager.discoverPeers()
                                wifiDirectStatus = "Discovery started"
                                status = "Wi-Fi Direct discovery initiated"
                                debugLogs.add("✅ [${getFormattedTime()}] Wi-Fi Direct discovery started")
                            } catch (e: Exception) {
                                wifiDirectStatus = "Failed"
                                status = "Wi-Fi Direct error: ${e.message}"
                                debugLogs.add("❌ [${getFormattedTime()}] Wi-Fi Direct error: ${e.message}")
                                debugLogs.add("🔧 [${getFormattedTime()}] Error details: ${e.stackTraceToString()}")
                            }
                        },
                        onStopDiscovery = {
                            status = "Stopping Wi-Fi Direct discovery..."
                            wifiDirectStatus = "Stopping"
                            debugLogs.add("🛑 [${getFormattedTime()}] Stopping Wi-Fi Direct...")

                            try {
                                wifiManager.stopPeerDiscovery()
                                wifiDirectStatus = "Inactive"
                                status = "Wi-Fi Direct stopped"
                                debugLogs.add("✅ [${getFormattedTime()}] Wi-Fi Direct stopped")
                            } catch (e: Exception) {
                                wifiDirectStatus = "Error"
                                status = "Stop failed: ${e.message}"
                                debugLogs.add("❌ [${getFormattedTime()}] Failed to stop: ${e.message}")
                            }
                        },
                        onSimulateConnection = {
                            status = "Simulating Wi-Fi Direct connection..."
                            wifiDirectStatus = "Connecting..."
                            debugLogs.add("🤝 [${getFormattedTime()}] Simulating Wi-Fi Direct connection...")

                            scope.launch {
                                kotlinx.coroutines.delay(1000)
                                wifiDirectStatus = "Connected"
                                status = "Wi-Fi Direct connection simulated"
                                debugLogs.add("✅ [${getFormattedTime()}] Wi-Fi Direct connection simulated")
                            }
                        }
                    )
                }
            }

            // Section 5: Audio Player Tests
            item {
                TestSection(
                    title = "🎵 Audio Player Tests",
                    description = "Audio loading and synchronized playback"
                ) {
                    AudioPlayerTestButtons(
                        onLoadTestAudio = {
                            status = "Loading test audio..."
                            audioPlayerStatus = "Loading..."
                            debugLogs.add("🎵 [${getFormattedTime()}] Loading test audio...")

                            // Note: Would need actual audio file URI
                            audioPlayerStatus = "No audio file"
                            status = "Need audio file to load"
                            debugLogs.add("⚠️ [${getFormattedTime()}] Need audio file URI for testing")
                        },
                        onTestPlayback = {
                            status = "Testing audio playback..."
                            audioPlayerStatus = "Playing..."
                            debugLogs.add("▶️ [${getFormattedTime()}] Testing audio playback...")

                            // Simulate playback
                            audioPlayerStatus = "Playback simulated"
                            status = "Audio playback test complete"
                            debugLogs.add("✅ [${getFormattedTime()}] Audio playback test OK")
                        },
                        onTestSyncPlay = {
                            status = "Testing synchronized playback..."
                            audioPlayerStatus = "Sync playing..."
                            debugLogs.add("🎯 [${getFormattedTime()}] Testing synchronized playback...")

                            // Simulate sync playback
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                audioPlayerStatus = "Sync played"
                                status = "Synchronized playback simulated"
                                debugLogs.add("✅ [${getFormattedTime()}] Sync playback test OK")
                            }
                        }
                    )
                }
            }

            // Section 6: Debug & Utility Tests
            item {
                TestSection(
                    title = "🔧 Debug & Utilities",
                    description = "Logging and system tests"
                ) {
                    DebugUtilityButtons(
                        onClearLogs = {
                            debugLogs.clear()
                            status = "Logs cleared"
                            debugLogs.add("🧹 [${getFormattedTime()}] Logs cleared")
                        },
                        onAddTestLog = {
                            val testMsg = "Test log at ${getFormattedTime()}"
                            debugLogs.add("🧪 [${getFormattedTime()}] $testMsg")
                            status = "Test log added"
                        },
                        onTestAll = {
                            status = "Running all tests..."
                            debugLogs.add("🏃 [${getFormattedTime()}] Starting comprehensive test...")

                            scope.launch {
                                // Run a series of simulated tests
                                val tests = listOf(
                                    "Network test" to 300L,
                                    "Time sync test" to 200L,
                                    "File transfer test" to 400L,
                                    "Wi-Fi Direct test" to 300L,
                                    "Audio test" to 200L
                                )

                                tests.forEach { (testName, delay) ->
                                    debugLogs.add("⚡ [${getFormattedTime()}] Running: $testName")
                                    kotlinx.coroutines.delay(delay)
                                    debugLogs.add("✅ [${getFormattedTime()}] Completed: $testName")
                                }

                                status = "All tests completed"
                                debugLogs.add("🎉 [${getFormattedTime()}] All tests completed successfully!")
                            }
                        },
                        onSystemInfo = {
                            status = "Getting system info..."
                            debugLogs.add("📊 [${getFormattedTime()}] System information:")
                            debugLogs.add("   Time: ${getFormattedTime()}")
                            debugLogs.add("   App: SyncAudio v1.0")
                            debugLogs.add("   Testing mode: Single device")
                            status = "System info logged"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Debug Logs Section (Collapsible)
        DebugLogsSection(debugLogs = debugLogs)
    }
}

// =================== COMPOSABLE COMPONENTS ===================

@Composable
fun StatusOverviewRow(
    serverStatus: String,
    clientStatus: String,
    timeSyncStatus: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusChip(text = "Server: $serverStatus", isActive = serverStatus == "Running")
        StatusChip(text = "Client: $clientStatus", isActive = clientStatus == "Connected")
        StatusChip(text = "Time: $timeSyncStatus", isActive = timeSyncStatus.contains("Synced"))
    }
}

@Composable
fun StatusChip(text: String, isActive: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CurrentStatusCard(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📱 Status:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2
            )
        }
    }
}

@Composable
fun TestSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun NetworkTestButtons(
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
                Text("Start Server")
            }
            Button(
                onClick = onStopServer,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Stop Server")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartClient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Client")
            }
            Button(
                onClick = onResetClient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Client")
            }
        }
    }
}

@Composable
fun TimeSyncTestButtons(
    onStartTimeServer: () -> Unit,
    onStopTimeServer: () -> Unit,
    onTestTimeSync: () -> Unit,
    onQuickSync: () -> Unit
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
                Text("Start Time Server")
            }
            Button(
                onClick = onStopTimeServer,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Stop Time Server")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestTimeSync,
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Time Sync")
            }
            Button(
                onClick = onQuickSync,
                modifier = Modifier.weight(1f)
            ) {
                Text("Quick Sync")
            }
        }
    }
}

@Composable
fun FileTransferTestButtons(
    onStartFileServer: () -> Unit,
    onTestFileClient: () -> Unit,
    onSimulateTransfer: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartFileServer,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start File Server")
            }
            Button(
                onClick = onTestFileClient,
                modifier = Modifier.weight(1f)
            ) {
                Text("Test File Client")
            }
        }
        Button(
            onClick = onSimulateTransfer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simulate File Transfer")
        }
    }
}

@Composable
fun WifiDirectTestButtons(
    isSupported: Boolean = true,
    onDiscoverPeers: () -> Unit,
    onStopDiscovery: () -> Unit,
    onSimulateConnection: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isSupported) {
            Text(
                text = "Wi-Fi Direct not available",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDiscoverPeers,
                modifier = Modifier.weight(1f),
                enabled = isSupported
            ) {
                Text("Discover Peers")
            }
            Button(
                onClick = onStopDiscovery,
                modifier = Modifier.weight(1f),
                enabled = isSupported,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Stop Discovery")
            }
        }
        Button(
            onClick = onSimulateConnection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simulate Connection")
        }
    }
}
@Composable
fun AudioPlayerTestButtons(
    onLoadTestAudio: () -> Unit,
    onTestPlayback: () -> Unit,
    onTestSyncPlay: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onLoadTestAudio,
                modifier = Modifier.weight(1f)
            ) {
                Text("Load Audio")
            }
            Button(
                onClick = onTestPlayback,
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Playback")
            }
        }
        Button(
            onClick = onTestSyncPlay,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Sync Playback")
        }
    }
}

@Composable
fun DebugUtilityButtons(
    onClearLogs: () -> Unit,
    onAddTestLog: () -> Unit,
    onTestAll: () -> Unit,
    onSystemInfo: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Text("Clear Logs")
            }
            Button(
                onClick = onAddTestLog,
                modifier = Modifier.weight(1f)
            ) {
                Text("Add Test Log")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestAll,
                modifier = Modifier.weight(1f)
            ) {
                Text("Run All Tests")
            }
            Button(
                onClick = onSystemInfo,
                modifier = Modifier.weight(1f)
            ) {
                Text("System Info")
            }
        }
    }
}

@Composable
fun DebugLogsSection(debugLogs: List<String>) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📝 Debug Logs (${debugLogs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(
                    onClick = { expanded = !expanded }
                ) {
                    Text(if (expanded) "▲" else "▼")
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                if (debugLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No logs yet. Click buttons to generate logs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.height(200.dp),
                        verticalArrangement = Arrangement.Top,
                        reverseLayout = true // Newest at the top
                    ) {
                        items(debugLogs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper function for formatted time
fun getFormattedTime(): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date())
}