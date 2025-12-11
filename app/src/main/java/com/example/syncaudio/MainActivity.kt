// File: MainActivity.kt (simplified)
package com.example.syncaudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pDevice
import android.content.IntentFilter
import com.example.syncaudio.composables.SyncAudioTestUI


class MainActivity : ComponentActivity() {
    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var wifiManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var wifiDirectReceiver: WifiDirectBroadcastReceiver? = null
    private lateinit var intentFilter: IntentFilter
    private var fileTransferServer: FileTransferServer? = null


    var isWifiDirectEnabled by mutableStateOf(false)
    var discoveredDevices by mutableStateOf<List<WifiP2pDevice>>(emptyList())
        private set

    var isWifiDirectConnected by mutableStateOf(false)
        private set

    var currentConnectionInfo by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PermissionHelper.checkPermissions(this)) {
            PermissionHelper.requestPermissions(this, PERMISSION_REQUEST_CODE)
        }
        initWifiDirect()

        // Initialize SyncClient with context
        SyncClient.setContext(this)

        // Check network permissions
        checkNetworkPermissions()

        DebugUtils.log("🚀 MainActivity created")
        Toast.makeText(this, "Sync Audio Testing App Started!", Toast.LENGTH_SHORT).show()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncAudioTestUI()
                }
            }
        }
    }

    private fun initWifiDirect() {
        wifiManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiManager.initialize(this, mainLooper, null)

        // Create intent filter
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        DebugUtils.log("📡 Wi-Fi Direct initialized")
    }

    override fun onResume() {
        super.onResume()
        // Register the broadcast receiver
        wifiDirectReceiver = WifiDirectBroadcastReceiver(wifiManager, channel,
            onPeersChanged = { devices ->
                discoveredDevices = devices
                DebugUtils.log("📱 Found ${devices.size} Wi-Fi Direct devices")
            },
            onConnectionChanged = { isConnected, info ->
                isWifiDirectConnected = isConnected
                currentConnectionInfo = if (isConnected) {
                    "GO: ${info?.isGroupOwner}, IP: ${info?.groupOwnerAddress?.hostAddress}"
                } else null

                DebugUtils.log(if (isConnected) "✅ Wi-Fi Direct connected" else "🔌 Wi-Fi Direct disconnected")
            },
            onStateChanged = { isEnabled ->
                isWifiDirectEnabled = isEnabled
                DebugUtils.log("📶 Wi-Fi Direct ${if (isEnabled) "enabled" else "disabled"}")
            }
        )
        registerReceiver(wifiDirectReceiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        // Unregister the broadcast receiver
        unregisterReceiver(wifiDirectReceiver)
    }

    fun getWifiDirectManager(): WifiDirectBroadcastReceiver? {
        return wifiDirectReceiver
    }

    private fun checkNetworkPermissions() {
        val permissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )

        permissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            DebugUtils.log("${if (granted) "✅" else "❌"} $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = PermissionHelper.onRequestPermissionsResult(
                requestCode, permissions, grantResults
            )

            if (allGranted) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
                DebugUtils.logSuccess("✅ All permissions granted")
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
                DebugUtils.logWarning("⚠️ Some permissions denied")
            }

            // Re-check permissions after request
            checkNetworkPermissions()
        }
    }
    // Add this method to your MainActivity (with proper override)
    // In your MainActivity.kt
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        FilePickerHelper.handleResult(
            context = this,
            requestCode = requestCode,
            resultCode = resultCode,
            data = data,
            onFilePicked = { uri ->
                DebugUtils.log("✅ File selected: $uri")
                // **Correctly store in FileSelectionManager**
                FileSelectionManager.setSelectedFile(uri)
            }
        )
    }


    // Create this companion object/class to manage the selected file
    object SelectedFileManager {
        var selectedUri: android.net.Uri? = null
            set(value) {
                field = value
                DebugUtils.log("Stored selected URI: $value")
            }
    }
}