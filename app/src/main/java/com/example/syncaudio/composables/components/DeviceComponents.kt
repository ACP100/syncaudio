// File: composables/components/DeviceComponents.kt
package com.example.syncaudio.composables.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight



@Composable
fun DeviceItem(device: WifiP2pDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.05f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.deviceName ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Status: ${getDeviceStatusText(device.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onConnect,
                modifier = Modifier.height(32.dp),
                enabled = device.status == WifiP2pDevice.AVAILABLE
            ) {
                Text("Connect", fontSize = 12.sp)
            }
        }
    }
}

private fun getDeviceStatusText(status: Int): String {
    return when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}