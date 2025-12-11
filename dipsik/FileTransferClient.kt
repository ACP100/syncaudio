// File: FileTransferClient.kt (updated with more debug messages)
package com.example.syncaudio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.net.Socket

class FileTransferClient(
    private val port: Int = 5003,
    private val onProgress: (percent: Int) -> Unit = {},
    private val onCompleted: (File) -> Unit = {}
) {

    suspend fun downloadFile(context: Context, serverIp: String) {
        withContext(Dispatchers.IO) {
            DebugUtils.logNetwork("📥 Starting file download from $serverIp:$port")

            try {
                DebugUtils.log("🔄 Creating socket connection to $serverIp:$port")
                val socket = Socket(serverIp, port)
                DebugUtils.logSuccess("✅ Socket connected to server")

                val input = DataInputStream(socket.getInputStream())
                DebugUtils.logFile("📋 Reading file header...")

                // Header
                val nameLength = input.readInt()
                DebugUtils.logFile("📏 File name length: $nameLength bytes")

                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)

                val fileName = String(nameBytes)
                val fileSize = input.readLong()

                DebugUtils.logFile("📄 File Info: name='$fileName', size=$fileSize bytes")
                DebugUtils.logFile("📁 Creating output file in cache directory")

                val outputFile = File(context.cacheDir, fileName)
                DebugUtils.logFile("📂 Output path: ${outputFile.absolutePath}")

                val output = outputFile.outputStream()

                // Receive file bytes
                val buffer = ByteArray(4096)
                var bytesRead = 0
                var totalRead = 0L
                var chunkCount = 0

                DebugUtils.logNetwork("📥 Starting file data transfer...")

                while (totalRead < fileSize &&
                    input.read(buffer).also { bytesRead = it } > 0
                ) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    chunkCount++

                    // Progress %
                    val percent = ((totalRead * 100) / fileSize).toInt()
                    onProgress(percent)

                    // Log every 10% progress
                    if (percent % 10 == 0 && percent > 0) {
                        DebugUtils.logNetwork("📊 Download progress: $percent% ($totalRead/$fileSize bytes)")
                    }

                    // Log every 100 chunks
                    if (chunkCount % 100 == 0) {
                        DebugUtils.logFile("📦 Received $chunkCount chunks, $totalRead bytes total")
                    }
                }

                output.flush()
                output.close()
                socket.close()

                DebugUtils.logSuccess("✅ Download complete!")
                DebugUtils.logFile("📁 File saved: ${outputFile.absolutePath}")
                DebugUtils.logFile("📊 Total chunks: $chunkCount, Total bytes: $totalRead")

                onCompleted(outputFile)

            } catch (e: Exception) {
                DebugUtils.logError("❌ Error downloading file from $serverIp:$port", e)
            }
        }
    }
}