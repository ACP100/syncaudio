// File: SyncClient.kt (enhanced with detailed debugging and connection callbacks)
package com.example.syncaudio

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// Connection listener interface
interface ConnectionListener {
    fun onConnectionSuccess(serverIp: String, serverPort: Int, message: String)
    fun onConnectionFailure(error: String)
    fun onConnectionAttempt(attempt: Int)
    fun onConnectionStarted()
    fun onPacketSent(serverIp: String, port: Int, message: String)
}

object SyncClient {
    private const val PORT = 5001
    private var isConnecting = false
    private var connectionAttempts = 0
    private var appContext: Context? = null
    private var connectionListener: ConnectionListener? = null

    fun setContext(context: Context) {
        appContext = context
        DebugUtils.log("📱 SyncClient context set")
    }

    fun setConnectionListener(listener: ConnectionListener?) {
        connectionListener = listener
    }

    fun connect(serverIp: String? = null) {
        if (isConnecting) {
            DebugUtils.logWarning("⚠️ Already connecting to server (attempt #${connectionAttempts + 1})")
            connectionListener?.onConnectionAttempt(connectionAttempts + 1)
            return
        }

        DebugUtils.logNetwork("🔗 Attempting to connect to server...")
        DebugUtils.log("📊 Current connection attempts: $connectionAttempts")

        connectionListener?.onConnectionStarted()
        connectionListener?.onConnectionAttempt(connectionAttempts + 1)

        isConnecting = true
        connectionAttempts++

        Thread {
            var socket: DatagramSocket? = null
            var success = false
            var serverAddress = ""
            var serverPort = 0
            var responseMessage = ""

            try {
                socket = DatagramSocket()
                socket.broadcast = true // Enable broadcast
                socket.soTimeout = 5000 // 5 second timeout
                DebugUtils.logNetwork("📡 Client socket created")
                DebugUtils.log("⚙️ Socket timeout: ${socket.soTimeout}ms")
                DebugUtils.log("⚙️ Socket broadcast: ${socket.broadcast}")
                DebugUtils.log("⚙️ Socket bound to: ${socket.localAddress}:${socket.localPort}")

                // If server IP is provided, connect to it directly
                if (!serverIp.isNullOrEmpty()) {
                    DebugUtils.logNetwork("🎯 Direct connection to server: $serverIp")
                    try {
                        val serverAddressObj = InetAddress.getByName(serverIp)
                        DebugUtils.log("📍 Server address resolved: $serverAddressObj")
                        success = sendAndReceive(socket, serverAddressObj, serverIp)
                        if (success) {
                            serverAddress = serverIp
                            serverPort = PORT
                        }
                    } catch (e: Exception) {
                        DebugUtils.logError("❌ Direct connection failed: ${e.message}")
                        connectionListener?.onConnectionFailure(e.message ?: "Direct connection failed")
                    }
                } else {
                    // Try broadcast addresses
                    DebugUtils.logNetwork("🔍 Searching for server via broadcast...")

                    val addresses = mutableListOf<String>()

                    // Add common broadcast addresses
                    addresses.add("255.255.255.255")

                    // Try to determine local broadcast address
                    val localIp = appContext?.let { NetworkUtils.getLocalIpAddress(it) }
                    DebugUtils.log("📱 Local IP: $localIp")

                    if (localIp != null) {
                        val broadcast = NetworkUtils.getBroadcastAddress(localIp)
                        if (broadcast != null) {
                            addresses.add(broadcast)
                            DebugUtils.log("📡 Using calculated broadcast: $broadcast from IP: $localIp")
                        }
                    }

                    // Common network broadcast addresses
                    addresses.add("192.168.1.255")
                    addresses.add("192.168.0.255")
                    addresses.add("10.0.0.255")
                    addresses.add("10.0.1.255")

                    DebugUtils.log("🔍 Testing ${addresses.size} broadcast addresses")

                    for ((index, address) in addresses.withIndex()) {
                        try {
                            DebugUtils.logNetwork("📡 Attempt ${index + 1}: Trying broadcast: $address")
                            val serverAddressObj = InetAddress.getByName(address)
                            DebugUtils.log("📍 Address resolved to: $serverAddressObj")

                            if (sendAndReceive(socket, serverAddressObj, address)) {
                                DebugUtils.logSuccess("✅ Connection successful with $address")
                                serverAddress = address
                                serverPort = PORT
                                success = true
                                break // Stop if successful
                            }

                        } catch (e: Exception) {
                            DebugUtils.logWarning("❌ Failed with $address: ${e.message}")
                            if (e is java.net.SocketTimeoutException) {
                                DebugUtils.log("⏰ Socket timeout for $address")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                DebugUtils.logError("❌ Connection error: ${e.message}", e)
                connectionListener?.onConnectionFailure(e.message ?: "Unknown error")
            } // In the connect() function, update the finally block:
            finally {
                socket?.close()
                DebugUtils.log("🔌 Client socket closed")
                isConnecting = false
                DebugUtils.log("🔄 Connection attempt completed")
                DebugUtils.log("📊 Total connection attempts: $connectionAttempts")

                if (success) {
                    connectionListener?.onConnectionSuccess(serverAddress, serverPort, responseMessage)
                    ConnectionManager.updateConnection(
                        isConnected = true,
                        serverIp = serverAddress,
                        serverPort = serverPort,
                        message = responseMessage
                    )
                } else if (serverIp.isNullOrEmpty()) {
                    connectionListener?.onConnectionFailure("No server found via broadcast")
                    ConnectionManager.updateConnection(isConnected = false, message = "No server found")
                } else {
                    ConnectionManager.updateConnection(isConnected = false, message = "Connection failed")
                }
            }
        }.start()
    }

    private fun sendAndReceive(socket: DatagramSocket, serverAddress: InetAddress, addressName: String): Boolean {
        val message = "Hello Server from SyncAudio - Attempt $connectionAttempts".toByteArray()
        val packet = DatagramPacket(message, message.size, serverAddress, PORT)

        DebugUtils.logNetwork("📤 Sending to $addressName:$PORT")
        DebugUtils.log("📤 Packet size: ${message.size} bytes")
        DebugUtils.log("📤 Message: '${String(message)}'")

        // Notify listener about packet sent
        connectionListener?.onPacketSent(addressName, PORT, String(message))

        socket.send(packet)
        DebugUtils.logSuccess("✅ Packet sent successfully")

        // Wait for response
        val buffer = ByteArray(1024)
        val responsePacket = DatagramPacket(buffer, buffer.size)

        try {
            DebugUtils.logNetwork("⏳ Waiting for server response (${socket.soTimeout}ms timeout)...")
            socket.receive(responsePacket)

            val response = String(responsePacket.data, 0, responsePacket.length)
            val responseAddress = responsePacket.address.hostAddress ?: "Unknown"
            val responsePort = responsePacket.port

            DebugUtils.logSuccess("📥 Received response from $responseAddress:$responsePort")
            DebugUtils.log("📝 Response: '$response' (${response.length} bytes)")
            DebugUtils.log("📍 Response details: ${responsePacket.address}:$responsePort")

            if (response.contains("ACK")) {
                DebugUtils.logSuccess("✅ Server connection established successfully!")
                return true
            }

        } catch (e: java.net.SocketTimeoutException) {
            DebugUtils.logWarning("⏰ Timeout waiting for response from $addressName")
            throw e // Re-throw to try next address
        }

        return false
    }

    fun resetConnectionAttempts() {
        connectionAttempts = 0
        DebugUtils.log("🔄 Reset connection attempts counter")
    }

    fun getConnectionAttempts(): Int {
        return connectionAttempts
    }

    fun isConnecting(): Boolean {
        return isConnecting
    }
}