// File: SongServer.kt
package com.example.syncaudio

import android.content.Context
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the host's selected song to joining phones, so only the host has to pick it.
 * Speaks the same format FileTransferClient reads: name length, name, size, bytes.
 */
object SongServer {
    private const val PORT = 5003

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext

        Thread {
            var socket: ServerSocket? = null
            try {
                socket = ServerSocket(PORT)
                serverSocket = socket
                SyncLog.add("Host: song server listening on port $PORT")
                while (running && serverSocket === socket) {
                    val client = socket.accept()
                    Thread { serve(appContext, client) }.start()
                }
            } catch (e: Exception) {
                if (running && serverSocket === socket) {
                    SyncLog.add("Host: song server failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                socket?.close()
                // Only reset if a newer start() hasn't replaced this server meanwhile; closing the
                // shared field here used to kill the new server after Leave + Host
                if (serverSocket === socket) {
                    serverSocket = null
                    running = false
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        val socket = serverSocket
        serverSocket = null // so the old thread's cleanup can't mistake a newer server for its own
        socket?.close()
    }

    private fun serve(context: Context, client: Socket) {
        try {
            client.use {
                val uri = FileSelectionManager.getSelectedFile()
                if (uri == null) {
                    SyncLog.add("Host: ${client.inetAddress.hostAddress} asked for the song, but none is picked")
                    return
                }
                SyncLog.add("Host: sending song to ${client.inetAddress.hostAddress}")
                val bytes = context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() } ?: return
                val name = AudioSyncManager.trackName(context, uri).toByteArray()

                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(name.size)
                out.write(name)
                out.writeLong(bytes.size.toLong())
                out.write(bytes)
                out.flush()
                SyncLog.add("Host: sent song to ${client.inetAddress.hostAddress} (${bytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            SyncLog.add("Host: failed to send song to ${client.inetAddress.hostAddress}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
