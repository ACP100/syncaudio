// File: composables/sections/WifiDirectSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncaudio.MainActivity

import com.example.syncaudio.composables.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import android.net.wifi.p2p.WifiP2pDevice
@Composable
fun WifiDirectSection(
    activity: MainActivity,
    debugLogs: MutableList<String>,
    onStatusUpdate: (String) -> Unit,
    onWifiDirectStatusUpdate: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isDiscovering by remember { mutableStateOf(false) }

    // GET WI-FI DIRECT STATE DIRECTLY FROM ACTIVITY
    val discoveredDevicesState = activity.discoveredDevices
    val wifiDirectEnabled = activity.isWifiDirectEnabled
    val isConnected = activity.isWifiDirectConnected
    val connectionInfo = activity.currentConnectionInfo

    // Get the manager instance
    val wifiDirectReceiver = activity.getWifiDirectManager()

    CompactTestSection(
        title = "📶 Wi-Fi Direct Tests",
        description = "Peer-to-peer connectivity"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Check if Wi-Fi Direct is available
            if (wifiDirectReceiver == null) {
                Text(
                    text = "⚠️ Wi-Fi Direct not initialized",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp
                )
            } else {
                // Status indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactStatusChip(
                        text = if (wifiDirectEnabled) "Enabled" else "Disabled",
                        isActive = wifiDirectEnabled
                    )
                    CompactStatusChip(
                        text = if (isConnected) "Connected" else "Disconnected",
                        isActive = isConnected
                    )
                }

                if (connectionInfo != null) {
                    Text(
                        text = connectionInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Discovery button
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                isDiscovering = true
                                onStatusUpdate("Discovering Wi-Fi Direct peers...")
                                onWifiDirectStatusUpdate("Discovering...")
                                debugLogs.add("🔍 [${getFormattedTime()}] Starting Wi-Fi Direct discovery")
                            }

                            // Use the non-nullable instance (checked above)
                            wifiDirectReceiver.discoverPeers()

                            // Auto-stop discovery after 15 seconds
                            withContext(Dispatchers.IO) {
                                kotlinx.coroutines.delay(15000) // 15 seconds
                                withContext(Dispatchers.Main) {
                                    isDiscovering = false
                                    onWifiDirectStatusUpdate("Discovery completed")
                                    debugLogs.add("✅ [${getFormattedTime()}] Wi-Fi Direct discovery completed")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = wifiDirectEnabled && !isDiscovering
                ) {
                    Text(
                        if (isDiscovering) "Discovering..." else "Discover Peers",
                        fontSize = 15.sp
                    )
                }

                // Stop discovery button
                if (isDiscovering) {
                    Button(
                        onClick = {
                            wifiDirectReceiver.stopPeerDiscovery()
                            isDiscovering = false
                            onWifiDirectStatusUpdate("Stopped")
                            debugLogs.add("🛑 [${getFormattedTime()}] Stopped discovery")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Stop Discovery", fontSize = 15.sp)
                    }
                }

                // Show discovered devices
                if (discoveredDevicesState.isNotEmpty()) {
                    Text(
                        text = "Found ${discoveredDevicesState.size} device(s):",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    LazyColumn(
                        modifier = Modifier.height(120.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(discoveredDevicesState) { device ->
                            DeviceItem(
                                device = device,
                                onConnect = {
                                    wifiDirectReceiver.connect(device.deviceAddress)
                                    debugLogs.add("🤝 [${getFormattedTime()}] Connecting to ${device.deviceName}")
                                }
                            )
                        }
                    }
                } else if (!isDiscovering && wifiDirectEnabled) {
                    Text(
                        text = "No devices found yet",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Disconnect button
                if (isConnected) {
                    Button(
                        onClick = {
                            wifiDirectReceiver.disconnect()
                            // State update happens via the BroadcastReceiver's callback
                            debugLogs.add("🔌 [${getFormattedTime()}] Disconnected from Wi-Fi Direct")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Disconnect", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}