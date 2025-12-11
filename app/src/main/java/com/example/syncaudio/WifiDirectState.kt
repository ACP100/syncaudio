// File: WifiDirectState.kt
package com.example.syncaudio

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Global state holder for Wi-Fi Direct information to be observed by the Composable UI.
 */
object WifiDirectState {
    data class State(
        val isEnabled: Boolean = false,
        val isConnected: Boolean = false,
        val connectionInfo: WifiP2pInfo? = null,
        val discoveredDevices: List<WifiP2pDevice> = emptyList(),
        val isDiscovering: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun updateEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        DebugUtils.log("📶 Wi-Fi Direct State updated: isEnabled=$enabled")
    }

    fun updateConnection(connected: Boolean, info: WifiP2pInfo?) {
        _state.update {
            it.copy(
                isConnected = connected,
                connectionInfo = info,
                // Automatically clear discovered peers on connection/disconnection change
                discoveredDevices = if (connected) it.discoveredDevices else emptyList()
            )
        }
        val statusText = if (connected) "CONNECTED" else "DISCONNECTED"
        DebugUtils.log("🔗 Wi-Fi Direct State updated: $statusText, GroupOwner=${info?.isGroupOwner}")
    }

    fun updatePeers(devices: List<WifiP2pDevice>) {
        _state.update { it.copy(discoveredDevices = devices) }
        DebugUtils.log("👥 Wi-Fi Direct State updated: Peers found=${devices.size}")
    }

    fun updateDiscovering(discovering: Boolean) {
        _state.update { it.copy(isDiscovering = discovering) }
        DebugUtils.log("🔍 Wi-Fi Direct State updated: isDiscovering=$discovering")
    }
}