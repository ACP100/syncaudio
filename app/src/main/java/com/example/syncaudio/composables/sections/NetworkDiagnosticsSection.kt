// File: composables/sections/NetworkDiagnosticsSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncaudio.composables.components.CompactTestSection
import androidx.compose.ui.unit.sp

@Composable
fun NetworkDiagnosticsSection(
    deviceRole: String,
    serverIpAddress: String,
    localIpAddress: String,
    onGetNetworkInfo: () -> Unit,
    onTestLocal: () -> Unit,
    onPingServer: () -> Unit,
    onTestSockets: () -> Unit,
    onTestUdp: () -> Unit
) {
    CompactTestSection(
        title = "🔄 Network Diagnostics",
        description = "Test connectivity"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onGetNetworkInfo,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Network Info", fontSize = 15.sp)
                }

                Button(
                    onClick = onTestLocal,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Local", fontSize = 15.sp)
                }
            }

            if (deviceRole != "Server" && serverIpAddress.isNotEmpty()) {
                Button(
                    onClick = onPingServer,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = serverIpAddress.isNotEmpty()
                ) {
                    Text("Ping Server", fontSize = 15.sp)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestSockets,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Sockets", fontSize = 15.sp)
                }

                Button(
                    onClick = onTestUdp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test UDP", fontSize = 15.sp)
                }
            }
        }
    }
}