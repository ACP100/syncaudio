// File: LiveAudioServer.kt
package com.example.syncaudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Host: streams songs to joined phones as decoded audio, so they never need the file.
 *
 * A phone sends "STREAM", the song position to start at (µs) and the song's name. The host answers
 * MAGIC, sample rate, channels, PCM encoding, length (ms), title and artist, then buffers of
 * [position µs][length][bytes], ending with [-1][-1]. Each phone gets its own decoder, started exactly
 * at the asked position, so seeks, resumes and late joins line up the same way a file does.
 */
class LiveAudioServer(private val context: Context) {
    companion object {
        const val PORT = 5007
        const val MAGIC = 0x53415544 // "SAUD"
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            val socket = try {
                ServerSocket(PORT)
            } catch (e: Exception) {
                SyncLog.add("Host: can't start the live stream: ${e.message}")
                running = false
                return@Thread
            }
            serverSocket = socket
            try {
                while (running && serverSocket === socket) {
                    val client = socket.accept()
                    Thread { serve(client) }.start()
                }
            } catch (e: Exception) {
                // closed by stop()
            } finally {
                socket.close()
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
        serverSocket = null
        socket?.close()
    }

    private fun serve(client: Socket) {
        val who = client.inetAddress.hostAddress ?: "a phone"
        try {
            client.use {
                client.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 32 * 1024))
                if (input.readUTF() != "STREAM") return
                val startUs = input.readLong().coerceAtLeast(0L)
                val song = input.readUTF()

                val uri = AudioSyncManager.findSong(song)
                if (uri == null) {
                    out.writeInt(0)
                    out.flush()
                    SyncLog.add("Host: $who asked for '$song', which isn't in the playlist")
                    return
                }
                val decoder = SongDecoder(context, uri) { !running }
                try {
                    if (startUs > 0) decoder.seekTo(startUs)
                    var chunk = decoder.next()
                    if (chunk == null) {
                        out.writeInt(0)
                        out.flush()
                        return
                    }
                    val format = decoder.outputFormat
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    val frameSize = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val details = AudioSyncManager.songDetails(uri)
                    out.writeInt(MAGIC)
                    out.writeInt(sampleRate)
                    out.writeInt(channels)
                    out.writeInt(encoding)
                    out.writeLong(details?.durationMs ?: 0L)
                    out.writeUTF(details?.title ?: song)
                    out.writeUTF(details?.artist ?: "")
                    SyncLog.add("Host: streaming '$song' to $who from ${startUs / 1000} ms")

                    while (chunk != null) {
                        var pcm = chunk.pcm
                        var pts = chunk.ptsUs
                        val frames = pcm.size / frameSize
                        val endUs = pts + frames * 1_000_000L / sampleRate
                        if (endUs > startUs) {
                            // The decoder starts at the nearest earlier sync point: drop what's before startUs
                            if (pts < startUs) {
                                val skip = ((startUs - pts) * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
                                pcm = pcm.copyOfRange(skip * frameSize, pcm.size)
                                pts = startUs
                            }
                            if (pcm.isNotEmpty()) {
                                out.writeLong(pts)
                                out.writeInt(pcm.size)
                                out.write(pcm)
                                out.flush()
                            }
                        }
                        chunk = decoder.next()
                    }
                    out.writeLong(-1L)
                    out.writeInt(-1)
                    out.flush()
                } finally {
                    decoder.release()
                }
            }
        } catch (e: Exception) {
            // the phone left, seeked (it reconnects) or the host stopped
        }
    }
}
