// File: composables/sections/FileTransferSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncaudio.composables.components.CompactTestSection
import androidx.compose.ui.unit.sp
// NOTE: You must add the import for FileSelectionManager here
// For example:
import com.example.syncaudio.FileSelectionManager

@Composable
fun FileTransferSection(
    onStartFileServer: () -> Unit,
    onTestFileClient: () -> Unit,
    onPickFile: () -> Unit,
    fileTransferStatus: String
) {
    CompactTestSection(
        title = "📁 File Transfer Tests",
        description = "TCP file sending"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 1. Logic to determine the status text
            // Update the status in the card based on file selection
            val currentFileStatus = if (FileSelectionManager.getSelectedFile() != null) {
                "File selected: Ready to start server"
            } else {
                fileTransferStatus
            }

            // 2. The Card component now uses the determined status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Status: $currentFileStatus", // <-- Uses currentFileStatus
                    modifier = Modifier.padding(8.dp),
                    fontSize = 12.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pick File", fontSize = 15.sp)
                }
                Button(
                    onClick = onStartFileServer,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Server", fontSize = 15.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestFileClient,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Client", fontSize = 15.sp)
                }
            }
        }
    }
}