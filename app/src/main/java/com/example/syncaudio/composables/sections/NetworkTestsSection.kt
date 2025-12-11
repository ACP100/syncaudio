// File: composables/sections/NetworkTestsSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncaudio.composables.components.CompactTestSection
import androidx.compose.ui.unit.sp

@Composable
fun NetworkTestsSection(
    deviceRole: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onStartClient: () -> Unit,
    onResetClient: () -> Unit
) {
    CompactTestSection(
        title = "🌐 Network Tests",
        description = "UDP Server & Client"
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
}