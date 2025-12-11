package com.example.syncaudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager?,
    private val channel: WifiP2pManager.Channel?,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit = {},
    private val onConnectionChanged: (Boolean, android.net.wifi.p2p.WifiP2pInfo?) -> Unit = { _, _ -> },
    private val onStateChanged: (Boolean) -> Unit = {}
) : BroadcastReceiver() {

    private var deviceListListener = WifiP2pManager.PeerListListener { peerList ->
        DebugUtils.log("📱 Found ${peerList.deviceList.size} Wi-Fi Direct devices")

        val devices = peerList.deviceList.toList()
        onPeersChanged(devices)

        devices.forEach { device ->
            DebugUtils.log("📱 Device: ${device.deviceName} (${device.deviceAddress})")
            DebugUtils.log("   🔧 Status: ${getDeviceStatusText(device.status)}")
        }
    }

    private var connectionListener = WifiP2pManager.ConnectionInfoListener { info ->
        DebugUtils.log("🔗 Connection info received")
        val isConnected = info != null && (info.groupFormed || info.groupOwnerAddress != null)
        if (isConnected) {
            DebugUtils.logSuccess("✅ Connected to: ${info?.groupOwnerAddress?.hostAddress}")
            DebugUtils.log("📡 Group Owner: ${info?.isGroupOwner}")
        } else {
            DebugUtils.logWarning("⚠️ Disconnected or connection info is null")
        }
        onConnectionChanged(isConnected, info)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                DebugUtils.log("📶 Wi-Fi Direct ${if (isEnabled) "enabled" else "disabled"}")
                onStateChanged(isEnabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                DebugUtils.log("👥 Peers changed - requesting new list")
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager?.requestPeers(channel, deviceListListener)
                } else {
                    DebugUtils.logError("❌ Permission denied: ACCESS_FINE_LOCATION required for requestPeers")
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                DebugUtils.log("🔗 Connection changed - requesting info")
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager?.requestConnectionInfo(channel, connectionListener)
                } else {
                    DebugUtils.logError("❌ Permission denied: ACCESS_FINE_LOCATION required for requestConnectionInfo")
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                DebugUtils.log("📱 This device: ${device?.deviceName} (${device?.deviceAddress})")
            }
        }
    }

    private fun getDeviceStatusText(status: Int): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "AVAILABLE"
            WifiP2pDevice.INVITED -> "INVITED"
            WifiP2pDevice.CONNECTED -> "CONNECTED"
            WifiP2pDevice.FAILED -> "FAILED"
            WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN ($status)"
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        DebugUtils.log("🔍 Starting Wi-Fi Direct peer discovery")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Wi-Fi Direct discovery started")
            }

            override fun onFailure(reason: Int) {
                DebugUtils.logError("❌ Wi-Fi Direct discovery failed: $reason")
            }
        })
    }
    
    fun stopPeerDiscovery() {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }
        
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Peer discovery stopped")
            }
            override fun onFailure(reason: Int) {
                DebugUtils.logError("❌ Failed to stop peer discovery: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        DebugUtils.log("🤝 Connecting to device: $deviceAddress")
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = 0 // WpsInfo.PBC is 0, using literal to avoid resolution issues with deprecated class
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Connection initiated to $deviceAddress")
            }

            override fun onFailure(reason: Int) {
                DebugUtils.logError("❌ Connection failed: $reason")
            }
        })
    }

    fun disconnect() {
        if (manager == null || channel == null) {
            DebugUtils.logError("❌ Wi-Fi Direct manager or channel is null")
            return
        }

        DebugUtils.log("🔌 Disconnecting Wi-Fi Direct")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                DebugUtils.logSuccess("✅ Disconnected successfully")
            }

            override fun onFailure(reason: Int) {
                DebugUtils.logError("❌ Disconnect failed: $reason")
            }
        })
    }
}