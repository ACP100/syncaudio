// File: composables/sections/NetworkConfigSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.composables.components.CompactTestSection
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun NetworkConfigSection(
    deviceRole: String,
    localIpAddress: String,
    serverIpAddress: String,
    onServerIpChange: (String) -> Unit,
    onRefreshIp: () -> Unit,
    onConnect: () -> Unit,
    isConnecting: Boolean
) {
    CompactTestSection(
        title = "🔗 Network Configuration",
        description = "Configure IP addresses"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Display local IP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your IP:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp
                )
                Text(
                    text = localIpAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Device Role Info
            if (deviceRole == "Server") {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🎯 Server mode - Share IP with clients",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Server IP input (only for clients)
            if (deviceRole != "Server") {
                OutlinedTextField(
                    value = serverIpAddress,
                    onValueChange = onServerIpChange,
                    label = { Text("Server IP", fontSize = 13.sp) },
                    placeholder = { Text("192.168.1.69", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRefreshIp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh IP", fontSize = 15.sp)
                }

                if (deviceRole != "Server") {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        enabled = serverIpAddress.isNotEmpty() && !isConnecting
                    ) {
                        Text(
                            if (isConnecting) "Connecting..." else "Connect",
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}