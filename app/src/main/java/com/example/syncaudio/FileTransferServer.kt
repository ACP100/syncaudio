// File: FileTransferServer.kt (updated with proper logging and error handling)
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
    private val onClientConnected: (String) -> Unit = {},
    private val onTransferComplete: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null
    private var running = false

    suspend fun startServer(context: android.content.Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                DebugUtils.logNetwork("📡 Starting File Transfer Server on port $port")

                serverSocket = ServerSocket(port)
                running = true
                DebugUtils.logSuccess("✅ File Transfer Server started successfully")

                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    val errorMsg = "❌ Could not open input stream for URI: $uri"
                    DebugUtils.logError(errorMsg)
                    onError(errorMsg)
                    return@withContext
                }

                val fileName = getFileName(uri)
                val fileSize = getFileSize(context, uri) // Better way to get file size

                DebugUtils.logFile("📄 Preparing to serve file: $fileName ($fileSize bytes)")

                var clientConnected = false
                while (running && !clientConnected) {
                    try {
                        DebugUtils.logNetwork("👂 Waiting for client connection...")
                        val client = serverSocket?.accept() ?: break

                        val clientAddress = client.inetAddress.hostAddress ?: "Unknown"
                        DebugUtils.logSuccess("🤝 Client connected: $clientAddress")
                        onClientConnected(clientAddress)
                        clientConnected = true

                        handleClient(client, inputStream, fileName, fileSize)

                        // Close server after one file transfer (for now)
                        break
                    } catch (e: Exception) {
                        if (running) {
                            DebugUtils.logError("❌ Error accepting client connection: ${e.message}")
                        }
                    }
                }

                serverSocket?.close()
                DebugUtils.log("🔌 File server closed")

            } catch (e: Exception) {
                val errorMsg = "❌ Error starting file server: ${e.message}"
                DebugUtils.logError(errorMsg, e)
                onError(errorMsg)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment ?: "unknown_file"
    }

    private fun getFileSize(context: android.content.Context, uri: Uri): Long {
        return try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.Files.FileColumns.SIZE),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.MediaStore.Files.FileColumns.SIZE)
                    if (sizeIndex >= 0) {
                        it.getLong(sizeIndex)
                    } else {
                        // Fallback: try reading available bytes
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.available().toLong()
                        } ?: 0L
                    }
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            DebugUtils.logError("Error getting file size: ${e.message}")
            0L
        }
    }

    private fun handleClient(client: Socket, inputStream: InputStream, fileName: String, fileSize: Long) {
        Thread {
            try {
                DebugUtils.logFile("📤 Sending file: $fileName ($fileSize bytes)")
                val out = DataOutputStream(client.getOutputStream())

                // Send header: filename length, filename, file size
                val nameBytes = fileName.toByteArray()
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                out.writeLong(fileSize)
                DebugUtils.log("📊 Header sent: ${nameBytes.size} bytes name, $fileSize bytes size")

                // Send file bytes
                val buffer = ByteArray(4096)
                var bytesRead: Int
                var totalSent = 0L
                var chunkCount = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    chunkCount++

                    // Log progress
                    if (chunkCount % 50 == 0) {  // Every 50 chunks
                        val progress = ((totalSent * 100) / fileSize).toInt()
                        DebugUtils.logNetwork("📊 Progress: $progress% ($totalSent/$fileSize bytes)")
                    }
                }

                out.flush()
                client.close()
                inputStream.close()

                DebugUtils.logSuccess("✅ File transfer completed! Sent $totalSent bytes in $chunkCount chunks")
                onTransferComplete()

            } catch (e: Exception) {
                val errorMsg = "❌ Error sending file: ${e.message}"
                DebugUtils.logError(errorMsg, e)
                onError(errorMsg)
            }
        }.start()
    }

    fun stopServer() {
        running = false
        serverSocket?.close()
        DebugUtils.log("🛑 File transfer server stopped")
    }
}