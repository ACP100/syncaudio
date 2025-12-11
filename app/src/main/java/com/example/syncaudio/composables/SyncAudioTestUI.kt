// File: composables/SyncAudioTestUI.kt
package com.example.syncaudio.composables

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.syncaudio.*
import com.example.syncaudio.composables.sections.*
import com.example.syncaudio.composables.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.example.syncaudio.listeners.ConnectionListenerImpl

@Composable
fun SyncAudioTestUI() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Ready") }
    var serverStatus by remember { mutableStateOf("Stopped") }
    var clientStatus by remember { mutableStateOf("Disconnected") }
    var timeSyncStatus by remember { mutableStateOf("Not synced") }
//    var fileTransferStatus by remember { mutableStateOf("Idle") }
    var wifiDirectStatus by remember { mutableStateOf("Inactive") }
    var audioPlayerStatus by remember { mutableStateOf("No audio loaded") }
    var localIpAddress by remember { mutableStateOf("Unknown") }
    var serverIpAddress by remember { mutableStateOf("") }
    var lastConnectionResult by remember { mutableStateOf("") }
    var deviceRole by remember { mutableStateOf("Unknown") }

    val debugLogs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    // Get Wifi Direct Receiver from Activity
    val activity = context as MainActivity
    val wifiDirectReceiver = activity.getWifiDirectManager()
    val selectedFileUri by FileSelectionManager.selectedFileUri.collectAsState()

    // Connection status flow
    val connectionStatus by ConnectionManager.connectionStatus.collectAsState()

//    var selectedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }  // Add this line

    var fileTransferStatus by remember { mutableStateOf("No file selected") }
    // Initialize with some logs
    LaunchedEffect(Unit) {
        localIpAddress = NetworkUtils.getLocalIpAddress(context) ?: "Unknown"
        debugLogs.add("📱 [${getFormattedTime()}] App started")
        debugLogs.add("🔧 [${getFormattedTime()}] Ready for testing")
        debugLogs.add("🌐 [${getFormattedTime()}] Local IP: $localIpAddress")

        // Set up connection listener
        SyncClient.setConnectionListener(ConnectionListenerImpl(
            debugLogs = debugLogs,
            onClientStatusUpdate = { newStatus -> clientStatus = newStatus },
            onStatusUpdate = { newStatus -> status = newStatus },
            onConnectionResult = { result -> lastConnectionResult = result }
        ))
    }

    // Update clientStatus based on connection
    LaunchedEffect(connectionStatus.isConnected) {
        if (connectionStatus.isConnected) {
            clientStatus = "Connected to ${connectionStatus.serverIp}:${connectionStatus.serverPort}"
        } else {
            clientStatus = "Disconnected"
        }
    }

    // Update device role based on server status
    LaunchedEffect(serverStatus) {
        deviceRole = if (serverStatus == "Running") "Server" else "Client"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "🎵 Sync Audio Tester",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Device Role Indicator
        DeviceRoleIndicatorCompact(deviceRole = deviceRole)

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Status Overview
        CompactStatusOverview(
            serverStatus = serverStatus,
            clientStatus = clientStatus,
            timeSyncStatus = timeSyncStatus
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Connection Status
        CompactConnectionStatus(connectionStatus = connectionStatus)

        Spacer(modifier = Modifier.height(12.dp))

        // Current Status
        CurrentStatusCompact(status = status)

        Spacer(modifier = Modifier.height(12.dp))

        // Test Categories
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            // Section 0: IP Configuration
            item {
                NetworkConfigSection(
                    deviceRole = deviceRole,
                    localIpAddress = localIpAddress,
                    serverIpAddress = serverIpAddress,
                    onServerIpChange = { serverIpAddress = it },
                    onRefreshIp = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val newIp = NetworkUtils.getLocalIpAddress(context) ?: "Unknown"
                                withContext(Dispatchers.Main) {
                                    localIpAddress = newIp
                                    status = "Refreshed IP"
                                    debugLogs.add("🔃 [${getFormattedTime()}] Refreshed IP: $localIpAddress")
                                }
                            }
                        }
                    },
                    onConnect = {
                        scope.launch {
                            if (serverIpAddress.isNotEmpty()) {
                                lastConnectionResult = ""
                                SyncClient.connect(serverIpAddress)
                            } else {
                                withContext(Dispatchers.Main) {
                                    status = "Enter server IP"
                                    debugLogs.add("⚠️ [${getFormattedTime()}] No server IP")
                                }
                            }
                        }
                    },
                    isConnecting = SyncClient.isConnecting()
                )
            }

            // Section 1: Network Diagnostics
            item {
                NetworkDiagnosticsSection(
                    deviceRole = deviceRole,
                    serverIpAddress = serverIpAddress,
                    localIpAddress = localIpAddress,
                    onGetNetworkInfo = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Getting network info..."
                            }
                            val info = NetworkUtils.getNetworkInfo(context)
                            withContext(Dispatchers.Main) {
                                debugLogs.add("📊 [${getFormattedTime()}] Network Info:")
                                info.split("\n").forEach { line ->
                                    debugLogs.add("   $line")
                                }
                                status = "Network info retrieved"
                            }
                        }
                    },
                    onTestLocal = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Testing local connectivity..."
                                debugLogs.add("🏓 [${getFormattedTime()}] Testing local...")
                            }

                            val localhostReachable = NetworkUtils.pingAddress("127.0.0.1", 1000)
                            val broadcastReachable = NetworkUtils.pingAddress("255.255.255.255", 1000)

                            withContext(Dispatchers.Main) {
                                debugLogs.add("   ${if (localhostReachable) "✅" else "❌"} Localhost: ${if (localhostReachable) "Reachable" else "Not reachable"}")
                                debugLogs.add("   ${if (broadcastReachable) "✅" else "❌"} Broadcast: ${if (broadcastReachable) "Reachable" else "Not reachable"}")
                                status = if (localhostReachable) "Localhost OK" else "Localhost failed"
                            }
                        }
                    },
                    onPingServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Pinging server..."
                                debugLogs.add("🏓 [${getFormattedTime()}] Pinging: $serverIpAddress")
                            }

                            val reachable = NetworkUtils.pingAddress(serverIpAddress, 3000)

                            withContext(Dispatchers.Main) {
                                if (reachable) {
                                    status = "Server reachable!"
                                    debugLogs.add("✅ [${getFormattedTime()}] Server reachable")
                                } else {
                                    status = "Server not reachable"
                                    debugLogs.add("❌ [${getFormattedTime()}] Server not reachable")
                                }
                            }
                        }
                    },
                    onTestSockets = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Testing sockets..."
                                debugLogs.add("🔌 [${getFormattedTime()}] Socket test")
                            }
                            SocketTest.testBasicSockets()
                        }
                    },
                    onTestUdp = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Running UDP test..."
                                debugLogs.add("🧪 [${getFormattedTime()}] UDP test")
                            }
                            SimpleUdpTest.testLocalUdp()
                        }
                    }
                )
            }

            // Section 2: Network Server/Client Tests
            item {
                NetworkTestsSection(
                    deviceRole = deviceRole,
                    onStartServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Starting server..."
                                debugLogs.add("🚀 [${getFormattedTime()}] Starting UDP server...")
                            }
                            SyncServer.start()
                            withContext(Dispatchers.Main) {
                                serverStatus = "Running"
                                status = "Server running"
                                deviceRole = "Server"
                                debugLogs.add("✅ [${getFormattedTime()}] UDP Server started")
                            }
                        }
                    },
                    onStopServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Stopping server..."
                            }
                            SyncServer.stop()
                            withContext(Dispatchers.Main) {
                                serverStatus = "Stopped"
                                deviceRole = "Client"
                                status = "Server stopped"
                                debugLogs.add("🛑 [${getFormattedTime()}] UDP Server stopped")
                            }
                        }
                    },
                    onStartClient = {
                        scope.launch {
                            if (deviceRole == "Server") {
                                withContext(Dispatchers.Main) {
                                    status = "Stop server first"
                                    debugLogs.add("⚠️ [${getFormattedTime()}] Server running")
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    status = "Searching..."
                                    lastConnectionResult = ""
                                    debugLogs.add("🔗 [${getFormattedTime()}] Starting client...")
                                }
                                SyncClient.connect()
                            }
                        }
                    },
                    onResetClient = {
                        scope.launch {
                            SyncClient.resetConnectionAttempts()
                            withContext(Dispatchers.Main) {
                                clientStatus = "Reset"
                                lastConnectionResult = ""
                                ConnectionManager.updateConnection(isConnected = false, message = "Reset")
                                status = "Client reset"
                                debugLogs.add("🔄 [${getFormattedTime()}] Client reset")
                            }
                        }
                    }
                )
            }

            // Section 3: Time Synchronization Tests
            item {
                TimeSyncSection(
                    serverIpAddress = serverIpAddress,
                    localIpAddress = localIpAddress,
                    onStartTimeServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Starting TimeSync..."
                                debugLogs.add("⏰ [${getFormattedTime()}] Starting TimeSync server...")
                            }
                            TimeSync.startServer()
                            withContext(Dispatchers.Main) {
                                timeSyncStatus = "Server running"
                                status = "TimeSync started"
                                debugLogs.add("✅ [${getFormattedTime()}] TimeSync server started")
                            }
                        }
                    },
                    onStopTimeServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                debugLogs.add("🛑 [${getFormattedTime()}] Stopping TimeSync server...")
                            }
                            TimeSync.stopServer()
                            withContext(Dispatchers.Main) {
                                timeSyncStatus = "Stopped"
                                status = "TimeSync stopped"
                                debugLogs.add("✅ [${getFormattedTime()}] TimeSync server stopped")
                            }
                        }
                    },
                    onTestTimeSync = {
                        // Implementation moved to TimeSyncSection
                        timeSyncStatus = "Syncing..."
                        status = "Starting time sync..."
                    },
                    onTestLocalSync = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                debugLogs.add("🧪 [${getFormattedTime()}] Testing local time sync...")
                                status = "Local sync test started"
                            }
                            TimeSync.testLocalSync()
                        }
                    },
                    debugLogs = debugLogs,
                    onStatusUpdate = { newStatus -> status = newStatus },
                    onTimeSyncStatusUpdate = { newStatus -> timeSyncStatus = newStatus },
                    onConnectionResult = { result -> lastConnectionResult = result }
                )
            }

            // Section 4: File Transfer Tests
// Section 4: File Transfer Tests
            item {
                FileTransferSection(
                    onStartFileServer = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "Starting file server..."
                                fileTransferStatus = "Server starting"
                                debugLogs.add("📡 [${getFormattedTime()}] Starting file server...")
                            }

                            // Now check the collected state
                            val currentSelectedFile = FileSelectionManager.getSelectedFile()
                            currentSelectedFile?.let { uri ->
                                scope.launch(Dispatchers.IO) {
                                    val fileServer = FileTransferServer(
                                        onClientConnected = { clientAddr ->
                                            scope.launch(Dispatchers.Main) {
                                                fileTransferStatus = "Client connected: $clientAddr"
                                                debugLogs.add("🤝 [${getFormattedTime()}] Client connected: $clientAddr")
                                            }
                                        },
                                        onTransferComplete = {
                                            scope.launch(Dispatchers.Main) {
                                                fileTransferStatus = "Transfer completed"
                                                debugLogs.add("✅ [${getFormattedTime()}] File transfer completed")
                                                status = "File transfer completed"
                                            }
                                        },
                                        onError = { error ->
                                            scope.launch(Dispatchers.Main) {
                                                fileTransferStatus = "Error: $error"
                                                debugLogs.add("❌ [${getFormattedTime()}] $error")
                                                status = "Transfer error"
                                            }
                                        }
                                    )
                                    fileServer.startServer(context, uri)
                                }
                            } ?: run {
                                scope.launch(Dispatchers.Main) {
                                    status = "Select file first"
                                    fileTransferStatus = "No file selected"
                                    debugLogs.add("⚠️ [${getFormattedTime()}] Please select a file first")
                                }
                            }
                        }
                    },
                    onTestFileClient = {
                        scope.launch {
                            scope.launch(Dispatchers.Main) {
                                status = "Testing file client..."
                                fileTransferStatus = "Client testing"
                                debugLogs.add("📥 [${getFormattedTime()}] Testing file client...")
                            }

                            val serverIp = ConnectionManager.connectionStatus.value.serverIp
                            if (serverIp.isNotEmpty()) {
                                scope.launch(Dispatchers.IO) {
                                    val client = FileTransferClient(
                                        onProgress = { percent ->
                                            scope.launch(Dispatchers.Main) {
                                                fileTransferStatus = "Downloading: $percent%"
                                                status = "Download: $percent%"
                                            }
                                        },
                                        onCompleted = { file ->
                                            scope.launch(Dispatchers.Main) {
                                                fileTransferStatus = "Download completed: ${file.name}"
                                                status = "Download complete"
                                                debugLogs.add("✅ [${getFormattedTime()}] Download completed: ${file.name}")
                                            }
                                        }
                                    )

                                    try {
                                        client.downloadFile(context, serverIp)
                                    } catch (e: Exception) {
                                        scope.launch(Dispatchers.Main) {
                                            fileTransferStatus = "Download failed: ${e.message}"
                                            status = "Download failed"
                                            debugLogs.add("❌ [${getFormattedTime()}] Download failed: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                scope.launch(Dispatchers.Main) {
                                    fileTransferStatus = "No server connected"
                                    status = "Connect to server first"
                                    debugLogs.add("⚠️ [${getFormattedTime()}] No server connected")
                                }
                            }
                        }
                    },
                    onPickFile = {
                        scope.launch(Dispatchers.Main) {
                            FilePickerHelper.openAudioPicker(activity)
                            status = "Select audio file..."
                            debugLogs.add("📁 [${getFormattedTime()}] Opening file picker...")
                        }
                    },
                    fileTransferStatus = fileTransferStatus
                )
            }
            // Section 5: Wi-Fi Direct Tests
            item {
                WifiDirectSection(
                    activity = activity,
                    debugLogs = debugLogs,
                    onStatusUpdate = { newStatus -> status = newStatus },
                    onWifiDirectStatusUpdate = { newStatus -> wifiDirectStatus = newStatus }
                )
            }

            // Section 6: Audio Player Tests
            item {
                AudioPlayerSection(
                    context = context,
                    scope = scope,
                    onStatusUpdate = { statusMessage ->
                        // Update your UI state and debug logs here
                        status = statusMessage
                        debugLogs.add("🎵 [${getFormattedTime()}] $statusMessage")
                    }
                )
            }

            // Section 7: Debug & Utility Tests
            item {
                DebugSection(
                    debugLogs = debugLogs,
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
                    onSystemInfo = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                status = "System info..."
                                debugLogs.add("📊 [${getFormattedTime()}] System info:")
                                debugLogs.add("   Time: ${getFormattedTime()}")
                                debugLogs.add("   App: SyncAudio v1.0")
                                debugLogs.add("   Role: $deviceRole")
                                debugLogs.add("   IP: $localIpAddress")
                                debugLogs.add("   Connected: ${connectionStatus.isConnected}")
                                if (connectionStatus.isConnected) {
                                    debugLogs.add("   Server: ${connectionStatus.serverIp}:${connectionStatus.serverPort}")
                                }
                                status = "System info logged"
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Compact Debug Logs
        CompactDebugLogsSection(debugLogs = debugLogs)
    }
}

private fun AudioSyncManager.loadAudio(fileUri: Uri) {}

// Helper function for formatted time
fun getFormattedTime(): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date())
}