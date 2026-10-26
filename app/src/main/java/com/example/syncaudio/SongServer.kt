// File: SongServer.kt
package com.example.syncaudio

import android.content.Context
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves a song of the host's to joining phones, so only the host has to pick it: [song] says which
 * (the current one, or the next one so joined phones can fetch it early).
 * Speaks the same format FileTransferClient reads: name length, name, size, bytes.
 */
class SongServer(private val port: Int, private val song: () -> android.net.Uri?) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext

        Thread {
            try {
                serverSocket = ServerSocket(port)
                DebugUtils.logSuccess("✅ Song server listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread { serve(appContext, client) }.start()
                }
            } catch (e: Exception) {
                if (running) DebugUtils.logError("❌ Song server error", e)
            } finally {
                running = false
                serverSocket?.close()
            }
        }.start()
    }

    fun stop() {
        running = false
        serverSocket?.close()
    }

    private fun serve(context: Context, client: Socket) {
        try {
            client.use {
                val uri = song() ?: return
                val bytes = context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() } ?: return
                val name = AudioSyncManager.trackName(context, uri).toByteArray()

                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(name.size)
                out.write(name)
                out.writeLong(bytes.size.toLong())
                out.write(bytes)
                out.flush()
                DebugUtils.logSuccess("✅ Sent song to ${client.inetAddress.hostAddress} (${bytes.size} bytes)")
            }
        } catch (e: Exception) {
            DebugUtils.logError("❌ Failed to send song to ${client.inetAddress.hostAddress}", e)
        }
    }
}
