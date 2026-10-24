//Audiosyncplayer
package com.example.syncaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Plays a song so that it lines up with a shared clock.
 *
 * The song is decoded here and fed to an AudioTrack, rather than played with MediaPlayer, because
 * AudioTrack reports exactly which frame is reaching the speaker at what moment (its output delay
 * included, which differs per phone), and lets us skip or insert frames to correct timing. That
 * works on every phone, unlike MediaPlayer speed changes, which many phones ignore.
 */
class AudioSyncPlayer(private val context: Context) {

    @Volatile private var source: Uri? = null
    @Volatile private var stopped = false
    @Volatile private var track: AudioTrack? = null

    companion object {
        private const val TAG = "AudioSync"
        private const val LEAD_IN_MS = 600L          // start the output this early, on silence, to measure its delay
        private const val CHECK_EVERY_MS = 150L
        private const val CHECKS_PER_DECISION = 5    // act on the median of 5 checks, not on single noisy ones
        private const val DEAD_ZONE_MS = 2.0         // closer than this: leave it alone
        private const val MAX_INSERT_MS = 3000.0
        private const val TIMESTAMP_WAIT_MS = 2000L
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    /** Checks that the file has an audio track we can decode. Returns false if it cannot be opened. */
    fun loadAudio(uri: Uri): Boolean {
        release()
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            if (findAudioTrack(extractor) < 0) {
                false
            } else {
                source = uri
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $uri", e)
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * Plays the song so that its start is heard when the server clock reads [targetTime].
     * Returns immediately; playback runs on its own thread.
     *
     * [offset] is the server-minus-local clock difference, as returned by TimeSync
     * (server time = local time + offset). Pass 0 when this phone is the clock reference itself.
     * [currentOffset] is re-read during playback so the estimate can improve; [extraDelayMs] is the
     * fine-tune (positive plays later); [onDrift] gets the measured gap in ms (positive = ahead).
     */
    fun playAtTimestamp(
        targetTime: Long,
        offset: Long,
        currentOffset: () -> Long = { offset },
        extraDelayMs: () -> Long = { 0L },
        onDrift: (Long) -> Unit = {}
    ) {
        val uri = source ?: return
        stopped = false
        Thread {
            try {
                play(uri, targetTime, currentOffset, extraDelayMs, onDrift)
            } catch (e: Exception) {
                if (!stopped) DebugUtils.logError("❌ Playback failed", e)
            }
        }.start()
    }

    fun release() {
        stop()
        source = null
    }

    fun stop() {
        stopped = true
        try {
            track?.pause() // makes a blocking write() return
            track?.flush()
        } catch (e: IllegalStateException) {
            // already released by the playback thread
        }
    }

    private fun play(
        uri: Uri,
        targetTime: Long,
        currentOffset: () -> Long,
        extraDelayMs: () -> Long,
        onDrift: (Long) -> Unit
    ) {
        val decoder = Decoder(uri)
        var audioTrack: AudioTrack? = null
        try {
            val first = decoder.next() ?: return
            val format = decoder.outputFormat
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> throw IllegalArgumentException("Unsupported channel count $channels")
            }
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val writer = Writer(sampleRate, channels * bytesPerSample)

            // Wait until shortly before the start, so the lead-in is short
            val localStart = targetTime - currentOffset() + extraDelayMs()
            val sleepMs = localStart - LEAD_IN_MS - System.currentTimeMillis()
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (stopped) return

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audioTrack
            writer.track = audioTrack
            audioTrack.play()

            // Play silence until the output reports when its frames reach the speaker
            val ts = AudioTimestamp()
            val giveUpAt = System.currentTimeMillis() + TIMESTAMP_WAIT_MS
            var haveTimestamp = false
            while (!stopped && System.currentTimeMillis() < giveUpAt) {
                writer.writeLeadIn((sampleRate / 100).toLong()) // 10 ms
                if (audioTrack.getTimestamp(ts) && ts.framePosition > 0) {
                    haveTimestamp = true
                    break
                }
            }
            if (stopped) return
            if (!haveTimestamp) {
                DebugUtils.logWarning("⚠️ This phone doesn't report audio output timing; sync will be rough")
                ts.framePosition = audioTrack.playbackHeadPosition.toLong()
                ts.nanoTime = System.nanoTime()
            }

            // Line up the first decoded frame with its moment on the shared clock
            val firstSongFrame = first.ptsUs * sampleRate / 1_000_000
            writer.nextSongFrame = firstSongFrame
            val heardAt = wallClockAt(ts.nanoTime)
            val wantedAt = targetTime - currentOffset() + extraDelayMs() + firstSongFrame * 1000.0 / sampleRate
            val wantedWriteIndex = ts.framePosition + ((wantedAt - heardAt) * sampleRate / 1000).roundToLong()
            val gap = wantedWriteIndex - writer.written
            if (gap > 0) writer.writeLeadIn(gap) else writer.drop(-gap)
            writer.writeSong(first.pcm)

            // Stream the rest, checking timing as we go
            val errors = ArrayList<Double>()
            var lastCheck = 0L
            while (!stopped) {
                val chunk = decoder.next() ?: break
                writer.writeSong(chunk.pcm)

                val now = System.currentTimeMillis()
                if (now - lastCheck < CHECK_EVERY_MS) continue
                lastCheck = now
                if (!audioTrack.getTimestamp(ts)) continue

                val presented = writer.songFrameAt(ts.framePosition)
                if (presented == null) {
                    errors.clear() // still in lead-in, or a correction is still being heard
                    continue
                }
                val expected = (wallClockAt(ts.nanoTime) + currentOffset() - targetTime - extraDelayMs()) *
                    sampleRate / 1000.0
                errors.add((presented - expected) * 1000.0 / sampleRate)
                if (errors.size < CHECKS_PER_DECISION) continue

                val aheadMs = errors.sorted()[errors.size / 2]
                errors.clear()
                onDrift(aheadMs.roundToLong())
                if (abs(aheadMs) > DEAD_ZONE_MS) {
                    Log.d(TAG, "Off by ${"%.1f".format(aheadMs)} ms, correcting")
                    val frames = (abs(aheadMs) * sampleRate / 1000).roundToLong()
                    if (aheadMs > 0) {
                        writer.insertSilence(min(frames, (MAX_INSERT_MS * sampleRate / 1000).toLong()))
                    } else {
                        writer.drop(frames)
                    }
                }
            }

            // Let what's queued play out. Wait by time: some phones reset the play position on stop()
            if (!stopped) {
                val queuedMs = (writer.written - audioTrack.playbackHeadPosition) * 1000 / sampleRate
                audioTrack.stop()
                val doneAt = System.currentTimeMillis() + queuedMs + 200
                while (!stopped && System.currentTimeMillis() < doneAt) Thread.sleep(50)
            }
        } finally {
            track = null
            audioTrack?.release()
            decoder.release()
        }
    }

    /** Converts a System.nanoTime() instant to wall-clock milliseconds. */
    private fun wallClockAt(nanoTime: Long): Double =
        System.currentTimeMillis() - (System.nanoTime() - nanoTime) / 1_000_000.0

    private class Chunk(val pcm: ByteArray, val ptsUs: Long)

    /**
     * Writes frames to the AudioTrack and remembers which song frame each written frame holds,
     * so a reported output position can be turned back into a song position.
     */
    private inner class Writer(private val sampleRate: Int, private val frameSize: Int) {
        var track: AudioTrack? = null
        var written = 0L          // frames written to the track
        var nextSongFrame = 0L    // song frame of the next decoded frame
        private var pendingDrop = 0L
        // Measurements are meaningless until the last correction has reached the speaker
        private var correctionHeardAt = Long.MAX_VALUE
        private val segments = ArrayDeque<LongArray>() // [write index, song frame, length]
        private val silence = ByteArray(frameSize * sampleRate / 100) // 10 ms

        fun writeLeadIn(frames: Long) {
            writeSilence(frames)
            correctionHeardAt = written
        }

        fun insertSilence(frames: Long) {
            segments.addLast(longArrayOf(written, nextSongFrame - frames, frames))
            writeSilence(frames)
            correctionHeardAt = written
        }

        fun drop(frames: Long) {
            if (frames <= 0) return
            pendingDrop += frames
            correctionHeardAt = Long.MAX_VALUE
        }

        fun writeSong(pcm: ByteArray) {
            val frames = pcm.size / frameSize
            val skip = min(pendingDrop, frames.toLong()).toInt()
            nextSongFrame += skip
            if (pendingDrop > 0) {
                pendingDrop -= skip
                if (pendingDrop == 0L) correctionHeardAt = written
            }
            val keep = frames - skip
            if (keep <= 0) return
            segments.addLast(longArrayOf(written, nextSongFrame, keep.toLong()))
            write(pcm, skip * frameSize, keep * frameSize)
            nextSongFrame += keep
            written += keep
        }

        /** Song frame being heard at output position [position], or null if we can't tell yet. */
        fun songFrameAt(position: Long): Long? {
            if (position < correctionHeardAt) return null
            while (segments.size > 1 && segments[1][0] <= position) segments.removeFirst()
            val segment = segments.firstOrNull() ?: return null
            if (position < segment[0]) return null
            return segment[1] + (position - segment[0])
        }

        private fun writeSilence(frames: Long) {
            var left = frames * frameSize
            while (left > 0 && !stopped) {
                val n = min(left, silence.size.toLong()).toInt()
                write(silence, 0, n)
                left -= n
            }
            written += frames
        }

        private fun write(bytes: ByteArray, offset: Int, length: Int) {
            val t = track ?: return
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining() && !stopped) {
                if (t.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING) <= 0) break
            }
        }
    }

    /** Decodes the song to PCM, one codec buffer at a time. */
    private inner class Decoder(uri: Uri) {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        var outputFormat: MediaFormat

        init {
            extractor.setDataSource(context, uri, null)
            val index = findAudioTrack(extractor)
            require(index >= 0) { "No audio track in $uri" }
            extractor.selectTrack(index)
            outputFormat = extractor.getTrackFormat(index)
            codec = MediaCodec.createDecoderByType(outputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(outputFormat, null, null, 0)
            codec.start()
        }

        /** Next decoded buffer, or null at the end of the song. */
        fun next(): Chunk? {
            while (!outputDone && !stopped) {
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
                    var chunk: Chunk? = null
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        chunk = Chunk(ByteArray(info.size).also { out.get(it) }, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (chunk != null) return chunk
                }
            }
            return null
        }

        fun release() {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }
}
