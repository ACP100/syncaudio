// File: composables/sections/DebugSection.kt
package com.example.syncaudio.composables.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncaudio.composables.components.CompactTestSection
import androidx.compose.ui.unit.sp

@Composable
fun DebugSection(
    debugLogs: MutableList<String>,
    onClearLogs: () -> Unit,
    onAddTestLog: () -> Unit,
    onSystemInfo: () -> Unit
) {
    CompactTestSection(
        title = "🔧 Debug & Utilities",
        description = "Logging and tests"
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
}