// File: ConnectionManager.kt
package com.example.syncaudio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionStatus(
    val isConnected: Boolean = false,
    val serverIp: String = "",
    val serverPort: Int = 0,
    val lastMessage: String = "",
    val timestamp: Long = 0
)

object ConnectionManager {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    fun updateConnection(
        isConnected: Boolean,
        serverIp: String = "",
        serverPort: Int = 0,
        message: String = ""
    ) {
        _connectionStatus.value = ConnectionStatus(
            isConnected = isConnected,
            serverIp = serverIp,
            serverPort = serverPort,
            lastMessage = message,
            timestamp = System.currentTimeMillis()
        )

        if (isConnected) {
            DebugUtils.logSuccess("✅ ConnectionManager: Connected to $serverIp:$serverPort")
        } else {
            DebugUtils.log("🔌 ConnectionManager: Disconnected")
        }
    }

    fun getStatusText(): String {
        val status = _connectionStatus.value
        return if (status.isConnected) {
            "✅ Connected to ${status.serverIp}:${status.serverPort}"
        } else {
            "🔌 Disconnected"
        }
    }

    fun getLastMessage(): String {
        return _connectionStatus.value.lastMessage
    }
}