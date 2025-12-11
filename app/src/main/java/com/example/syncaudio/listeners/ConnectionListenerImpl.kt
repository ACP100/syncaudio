// File: listeners/ConnectionListenerImpl.kt
package com.example.syncaudio.listeners

import com.example.syncaudio.ConnectionListener
import com.example.syncaudio.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionListenerImpl(
    private val debugLogs: MutableList<String>,
    private val onClientStatusUpdate: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit,
    private val onConnectionResult: (String) -> Unit
) : ConnectionListener {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onConnectionSuccess(serverIp: String, serverPort: Int, message: String) {
        scope.launch {
            onClientStatusUpdate("Connected to $serverIp:$serverPort")
            onStatusUpdate("✅ Connected!")
            onConnectionResult("Success: Connected to $serverIp:$serverPort")
            debugLogs.add("🎉 [${getFormattedTime()}] Connection established!")
            debugLogs.add("   📍 Server: $serverIp:$serverPort")
            debugLogs.add("   📝 Message: $message")

            // Update ConnectionManager
            ConnectionManager.updateConnection(
                isConnected = true,
                serverIp = serverIp,
                serverPort = serverPort,
                message = message
            )
        }
    }

    override fun onConnectionFailure(error: String) {
        scope.launch {
            onClientStatusUpdate("Connection failed")
            onStatusUpdate("❌ Connection failed")
            onConnectionResult("Failed: $error")
            debugLogs.add("❌ [${getFormattedTime()}] Connection failed: $error")
            ConnectionManager.updateConnection(isConnected = false, message = error)
        }
    }

    override fun onConnectionAttempt(attempt: Int) {
        scope.launch {
            onClientStatusUpdate("Connecting... (Attempt $attempt)")
            onStatusUpdate("Attempting connection #$attempt...")
            debugLogs.add("🔄 [${getFormattedTime()}] Connection attempt #$attempt")
        }
    }

    override fun onConnectionStarted() {
        scope.launch {
            onClientStatusUpdate("Starting connection...")
            onStatusUpdate("Starting connection process...")
            debugLogs.add("🔗 [${getFormattedTime()}] Starting connection...")
        }
    }

    override fun onPacketSent(serverIp: String, port: Int, message: String) {
        scope.launch {
            debugLogs.add("📤 [${getFormattedTime()}] Sent packet to $serverIp:$port")
            debugLogs.add("   📝 Message: $message")
        }
    }

    private fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
}