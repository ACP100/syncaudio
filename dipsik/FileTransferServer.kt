package com.example.syncaudio

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class FileTransferServer(
    private val port: Int = 5003,
    private val onClientConnected: (String) -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null
    private var running = false

    suspend fun startServer(context: android.content.Context, uri: Uri) {
        running = true

        withContext(Dispatchers.IO) {
            serverSocket = ServerSocket(port)
            Log.d("FileServer", "Server started on port $port")

            val inputStream = context.contentResolver.openInputStream(uri)
            val fileName = getFileName(uri)
            val fileSize = inputStream?.available()?.toLong() ?: 0L

            while (running) {
                val client = serverSocket?.accept() ?: break
                onClientConnected(client.inetAddress.hostAddress ?: "Unknown")

                handleClient(client, inputStream!!, fileName, fileSize)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment ?: "audio_file"
    }

    private fun handleClient(client: Socket, inputStream: InputStream, fileName: String, fileSize: Long) {
        Thread {
            try {
                val out = DataOutputStream(client.getOutputStream())

                // Send header
                val nameBytes = fileName.toByteArray()
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                out.writeLong(fileSize)

                // Send file bytes
                val buffer = ByteArray(4096)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }

                out.flush()
                client.close()
                Log.d("FileServer", "File sent successfully")

            } catch (e: Exception) {
                Log.e("FileServer", "Error sending file: ${e.message}")
            }
        }.start()
    }
}