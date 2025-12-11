// File: composables/components/CompactUIComponents.kt
package com.example.syncaudio.composables.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncaudio.ConnectionStatus

// Reusable components from the original file
@Composable
fun CompactTestSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.04f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun DeviceRoleIndicatorCompact(deviceRole: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (deviceRole == "Server")
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.08f),
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = if (deviceRole == "Server") "🎯 SERVER" else "📱 CLIENT",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CompactStatusOverview(
    serverStatus: String,
    clientStatus: String,
    timeSyncStatus: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactStatusChip(
            text = "S:${if (serverStatus == "Running") "✅" else "⭕"}",
            isActive = serverStatus == "Running"
        )

        CompactStatusChip(
            text = "C:${if (clientStatus.contains("Connected")) "✅" else "⭕"}",
            isActive = clientStatus.contains("Connected")
        )

        CompactStatusChip(
            text = "T:${if (timeSyncStatus.contains("Synced")) "✅" else "⭕"}",
            isActive = timeSyncStatus.contains("Synced")
        )
    }
}

@Composable
fun CompactStatusChip(text: String, isActive: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.06f),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 13.sp
        )
    }
}

@Composable
fun CompactConnectionStatus(connectionStatus: ConnectionStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connectionStatus.isConnected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.06f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.04f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (connectionStatus.isConnected) "✅ " else "🔌 ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (connectionStatus.isConnected) "CONNECTED" else "DISCONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                if (connectionStatus.isConnected) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            if (connectionStatus.isConnected) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Server: ${connectionStatus.serverIp}:${connectionStatus.serverPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun CurrentStatusCompact(status: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            fontSize = 14.sp,
            maxLines = 2
        )
    }
}

@Composable
fun CompactDebugLogsSection(debugLogs: List<String>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.04f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    text = "📝 Logs (${debugLogs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 13.sp
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Divider(thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))

                if (debugLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No logs yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.height(140.dp),
                        verticalArrangement = Arrangement.Top,
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        // FIXED: Use the correct items() function for lists
                        items(debugLogs.take(50)) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}