// File: PcmSource.kt
package com.example.syncaudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** One buffer of decoded audio, and where it starts in the song. */
class PcmChunk(val pcm: ByteArray, val ptsUs: Long)

/** Where the player gets decoded audio from: a song file on this phone, or the host's live stream. */
interface PcmSource {
    val mime: String

    /** Sample rate, channel count and PCM encoding; valid once [next] has returned a chunk. */
    val outputFormat: MediaFormat

    /** Before the first [next]: start near [us] into the song. */
    fun seekTo(us: Long)

    /** Next buffer, or null at the end of the song. */
    fun next(): PcmChunk?

    fun release()
}

fun findAudioTrack(extractor: MediaExtractor): Int {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) return i
    }
    return -1
}

/** Decodes a song file to PCM, one codec buffer at a time. */
class SongDecoder(context: Context, uri: Uri, private val isStopped: () -> Boolean) : PcmSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    override val mime: String
    override var outputFormat: MediaFormat

    init {
        extractor.setDataSource(context, uri, null)
        val index = findAudioTrack(extractor)
        require(index >= 0) { "No audio track in $uri" }
        extractor.selectTrack(index)
        outputFormat = extractor.getTrackFormat(index)
        mime = outputFormat.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(outputFormat, null, null, 0)
        codec.start()
    }

    override fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    override fun next(): PcmChunk? {
        while (!outputDone && !isStopped()) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
            } else if (outIndex >= 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                var chunk: PcmChunk? = null
                if (info.size > 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    chunk = PcmChunk(ByteArray(info.size).also { out.get(it) }, info.presentationTimeUs)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (chunk != null) return chunk
            }
        }
        return null
    }

    override fun release() {
        try { codec.stop() } catch (e: Exception) {}
        codec.release()
        extractor.release()
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

/**
 * The host's live stream of [song] (see LiveAudioServer): decoded audio, each buffer tagged with its
 * place in the song, so the player lines it up exactly as it does a file. If the connection drops it
 * reconnects and asks for the song from where it left off.
 */
class NetworkPcmSource(
    private val hostIp: String,
    private val song: String,
    private val onInfo: (String, String, Long) -> Unit,
    private val isStopped: () -> Boolean
) : PcmSource {
    override val mime = "audio/raw"
    override var outputFormat: MediaFormat = MediaFormat()

    private var startUs = 0L
    private var resumeUs = -1L // where to continue after a dropped connection
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var sampleRate = 44_100
    private var frameSize = 4
    private var infoSent = false
    private var ended = false

    override fun seekTo(us: Long) {
        startUs = us
    }

    override fun next(): PcmChunk? {
        var attempts = 0
        while (!isStopped() && !ended) {
            try {
                val stream = input ?: connect(if (resumeUs >= 0) resumeUs else startUs)
                val pts = stream.readLong()
                val length = stream.readInt()
                if (length < 0) {
                    ended = true
                    return null
                }
                val bytes = ByteArray(length)
                stream.readFully(bytes)
                resumeUs = pts + (length / frameSize) * 1_000_000L / sampleRate
                return PcmChunk(bytes, pts)
            } catch (e: Exception) {
                close()
                if (isStopped()) return null
                if (++attempts > 3) {
                    SyncLog.add("Stream: gave up on '$song' from $hostIp: ${e.javaClass.simpleName}: ${e.message}")
                    return null
                }
                SyncLog.add("Stream: connection to host lost (${e.message}), reconnecting")
                Thread.sleep(300)
            }
        }
        return null
    }

    private fun connect(fromUs: Long): DataInputStream {
        val s = Socket()
        s.connect(InetSocketAddress(hostIp, LiveAudioServer.PORT), 3000)
        s.soTimeout = 5000
        s.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out.writeUTF("STREAM")
        out.writeLong(fromUs)
        out.writeUTF(song)
        out.flush()

        val stream = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
        if (stream.readInt() != LiveAudioServer.MAGIC) {
            s.close()
            throw IOException("host can't stream '$song'")
        }
        sampleRate = stream.readInt()
        val channels = stream.readInt()
        val encoding = stream.readInt()
        val durationMs = stream.readLong()
        val title = stream.readUTF()
        val artist = stream.readUTF()
        outputFormat = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
        }
        frameSize = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        if (!infoSent) {
            infoSent = true
            onInfo(title, artist, durationMs)
        }
        socket = s
        input = stream
        return stream
    }

    private fun close() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        input = null
    }

    override fun release() = close()
}
