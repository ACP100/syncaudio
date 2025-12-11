// File: WifiDirectManager.kt (updated with better error handling)
package com.example.syncaudio

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class WifiDirectManager(context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, context.mainLooper, null)
    private var isDiscovering = false

    init {
        DebugUtils.log("🔧 Wi-Fi Direct Manager initialized")
        DebugUtils.log("📡 Manager instance: $manager")
        DebugUtils.log("📡 Channel instance: $channel")

        if (manager == null) {
            DebugUtils.logError("❌ Wi-Fi Direct not supported on this device")
        }
        if (channel == null) {
            DebugUtils.logError("❌ Failed to initialize Wi-Fi Direct channel")
        }
    }

    fun discoverPeers() {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        if (isDiscovering) {
            DebugUtils.logWarning("⚠️ Wi-Fi Direct discovery already in progress")
            return
        }

        DebugUtils.logNetwork("🔍 Starting Wi-Fi Direct peer discovery")
        DebugUtils.log("📡 Scanning for nearby devices...")

        isDiscovering = true
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Wi-Fi Direct discovery started successfully")
                DebugUtils.logNetwork("👁️ Listening for peer discovery responses...")
                isDiscovering = false
            }

            override fun onFailure(reason: Int) {
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
                    else -> "UNKNOWN ($reason)"
                }
                DebugUtils.logError("❌ Wi-Fi Direct discovery failed: $reasonText")
                isDiscovering = false
            }
        })
    }

    fun connect(device: WifiP2pDevice) {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        DebugUtils.logNetwork("🤝 Attempting Wi-Fi Direct connection")
        DebugUtils.log("📱 Device: ${device.deviceName} (${device.deviceAddress})")
        DebugUtils.log("🔧 Device status: ${getDeviceStatusText(device.status)}")

        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        DebugUtils.log("🔗 Configuration: address=${config.deviceAddress}")

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Connection initiated to ${device.deviceName}")
                DebugUtils.logNetwork("⏳ Waiting for connection establishment...")
            }

            override fun onFailure(reason: Int) {
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    WifiP2pManager.ERROR -> "ERROR"
                    else -> "UNKNOWN ($reason)"
                }
                DebugUtils.logError("❌ Connection failed to ${device.deviceName}: $reasonText")
            }
        })
    }

    fun stopPeerDiscovery() {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        DebugUtils.log("🛑 Stopping Wi-Fi Direct peer discovery")
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Peer discovery stopped")
                isDiscovering = false
            }
            override fun onFailure(reason: Int) {
                DebugUtils.logError("❌ Failed to stop peer discovery: $reason")
            }
        })
    }

    private fun getDeviceStatusText(status: Int): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "AVAILABLE"
            WifiP2pDevice.INVITED -> "INVITED"
            WifiP2pDevice.CONNECTED -> "CONNECTED"
            WifiP2pDevice.FAILED -> "FAILED"
            WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN"
        }
    }

    fun isSupported(): Boolean {
        return manager != null && channel != null
    }
}