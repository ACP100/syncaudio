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
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Plays a song so that it lines up with a shared clock.
 *
 * The song is decoded here and fed to an AudioTrack, rather than played with MediaPlayer, because
 * AudioTrack reports exactly which frame is reaching the speaker at what moment (its output delay
 * included, which differs per phone). Timing is corrected by briefly fading out, inserting silence
 * or skipping a few ms, and fading back in. Playback speed is never changed.
 */
class AudioSyncPlayer(private val context: Context) {

    @Volatile private var source: Uri? = null
    @Volatile private var stopped = false
    @Volatile private var track: AudioTrack? = null

    // Loudness of the song every LEVEL_STEP_MS, filled in as it is decoded (a little ahead of what's
    // heard), for the whole mix and for the bass and vocal ranges. Party mode flashes to it; every
    // phone decodes the same file the same way, so they flash together.
    private val levels = Array(BANDS) { FloatArray(LEVEL_SLOTS) }
    private val peakLevels = FloatArray(BANDS)

    /** Loudness of [band] (BAND_SONG, BAND_BASS, BAND_VOCALS) at [songMs], 0..1 relative to its loudest so far. */
    fun levelAt(songMs: Long, band: Int = BAND_SONG): Float {
        val slot = (songMs / LEVEL_STEP_MS).toInt()
        val peak = peakLevels[band]
        if (slot !in levels[band].indices || peak <= 0f) return 0f
        return (levels[band][slot] / peak).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "AudioSync"
        private const val LEAD_IN_MS = 1200L         // start the output this early, on silence, to measure its delay
        private const val MIN_LEAD_IN_MS = 300L      // the first speaker timings after play() are often off
        private const val STEADY_SPAN_MS = 150L      // timings must advance at the sample rate over this long
        private const val CHECK_EVERY_MS = 150L
        private const val CHECKS_PER_DECISION = 7    // act on the median of ~1 s of checks, not on single noisy ones
        private const val DEAD_ZONE_MS = 4.0         // closer than this: leave it alone
        private const val MIN_CORRECTION_GAP_MS = 2000L
        private const val MAX_INSERT_MS = 3000.0
        private const val FADE_MS = 3                // fade out/in around a correction so it doesn't click
        private const val TIMESTAMP_WAIT_MS = 2000L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val QUIET_DECISIONS_PER_LOG = 5
        private const val RATE_LOG_EVERY_MS = 30_000L
        private const val LEVEL_STEP_MS = 10L
        private const val LEVEL_SLOTS = 120_000 // 20 minutes of song
        const val BAND_SONG = 0
        const val BAND_BASS = 1
        const val BAND_VOCALS = 2
        private const val BANDS = 3
    }

    /** Checks that the file has an audio track we can decode. Returns false if it cannot be opened. */
    fun loadAudio(uri: Uri): Boolean {
        release()
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            if (findAudioTrack(extractor) < 0) {
                SyncLog.add("Player: no audio track in the file")
                false
            } else {
                source = uri
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $uri", e)
            SyncLog.add("Player: cannot open file: ${e.message}")
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
                if (!stopped) {
                    SyncLog.add("Player: playback failed: ${e.javaClass.simpleName}: ${e.message}")
                    DebugUtils.logError("❌ Playback failed", e)
                }
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
            // Starting mid-song (resume, seek, joining late): jump to about where the song will be once
            // the output is ready; the start alignment then skips the rest exactly
            val positionAtStartMs = System.currentTimeMillis() + LEAD_IN_MS - (targetTime - currentOffset() + extraDelayMs())
            if (positionAtStartMs > 1000) {
                decoder.seekTo((positionAtStartMs - 500) * 1000)
                SyncLog.add("Player: starting ${positionAtStartMs / 1000} s into the song")
            }
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
            SyncLog.add(
                "Player: ${decoder.mime}, $sampleRate Hz, $channels ch, " +
                    (if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "float" else "${bytesPerSample * 8}-bit") +
                    ", first frame at ${first.ptsUs / 1000} ms"
            )
            val writer = Writer(sampleRate, channels, bytesPerSample, encoding == AudioFormat.ENCODING_PCM_FLOAT)

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
            SyncLog.add("Player: output buffer ${audioTrack.bufferSizeInFrames * 1000L / sampleRate} ms")
            val playStartedAt = System.currentTimeMillis()
            audioTrack.play()

            // Play silence until the output reports steady timing for when its frames reach the speaker.
            // The first reports after play() can be tens of ms off, so wait until two reports
            // STEADY_SPAN_MS apart advance at the sample rate.
            val ts = AudioTimestamp()
            val probe = AudioTimestamp()
            val giveUpAt = System.currentTimeMillis() + TIMESTAMP_WAIT_MS
            var haveTimestamp = false
            var steady = false
            var baseFrame = -1L
            var baseNs = 0L
            while (!stopped && System.currentTimeMillis() < giveUpAt) {
                writer.writeLeadIn((sampleRate / 100).toLong()) // 10 ms
                if (!audioTrack.getTimestamp(probe) || probe.framePosition <= 0) continue
                ts.framePosition = probe.framePosition
                ts.nanoTime = probe.nanoTime
                haveTimestamp = true
                if (baseFrame < 0) {
                    baseFrame = probe.framePosition
                    baseNs = probe.nanoTime
                    continue
                }
                val spanNs = probe.nanoTime - baseNs
                if (spanNs < STEADY_SPAN_MS * 1_000_000) continue
                val rate = (probe.framePosition - baseFrame) * 1e9 / spanNs
                if (abs(rate - sampleRate) < sampleRate * 0.01 &&
                    System.currentTimeMillis() - playStartedAt >= MIN_LEAD_IN_MS
                ) {
                    steady = true
                    break
                }
                baseFrame = probe.framePosition // not steady yet; measure again from here
                baseNs = probe.nanoTime
            }
            if (stopped) return
            if (haveTimestamp) {
                val latencyMs = (writer.written - ts.framePosition) * 1000 / sampleRate -
                    (System.nanoTime() - ts.nanoTime) / 1_000_000
                SyncLog.add(
                    "Player: speaker timing ${if (steady) "steady" else "NOT steady"} after " +
                        "${System.currentTimeMillis() - playStartedAt} ms, output delay ≈ $latencyMs ms"
                )
            } else {
                SyncLog.add("Player: WARNING this phone doesn't report speaker timing; sync will be rough")
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
            SyncLog.add(
                "Player: start " + (if (gap >= 0) "on time, ${gap * 1000 / sampleRate} ms more silence"
                else "${-gap * 1000 / sampleRate} ms late, skipping ahead") +
                    " (clock offset ${currentOffset()} ms, fine-tune ${extraDelayMs()} ms)"
            )
            if (gap > 0) writer.writeLeadIn(gap) else writer.skipAtStart(-gap)
            writer.writeSong(first.pcm)

            // Stream the rest, checking timing as we go
            val errors = ArrayList<Double>()
            var lastCheck = 0L
            var lastCorrection = 0L
            var quietDecisions = 0
            var rateBase: LongArray? = null // [framePosition, nanoTime] when we started measuring the output rate
            var lastRateLog = 0L
            while (!stopped) {
                val chunk = decoder.next() ?: break
                writer.writeSong(chunk.pcm)

                val now = System.currentTimeMillis()
                if (now - lastCheck < CHECK_EVERY_MS) continue
                lastCheck = now
                if (!audioTrack.getTimestamp(ts)) continue

                // How fast the speaker really consumes frames, compared with the file's sample rate
                val base = rateBase
                if (base == null) {
                    rateBase = longArrayOf(ts.framePosition, ts.nanoTime)
                    lastRateLog = now
                } else if (now - lastRateLog >= RATE_LOG_EVERY_MS) {
                    lastRateLog = now
                    val seconds = (ts.nanoTime - base[1]) / 1e9
                    if (seconds > 0) {
                        SyncLog.add("Player: speaker runs at ${"%.1f".format((ts.framePosition - base[0]) / seconds)} Hz (file $sampleRate Hz)")
                    }
                }

                val presented = writer.songFrameAt(ts.framePosition)
                if (presented == null) {
                    errors.clear() // still in lead-in, or a correction is still being heard
                    continue
                }
                val expected = (wallClockAt(ts.nanoTime) + currentOffset() - targetTime - extraDelayMs()) *
                    sampleRate / 1000.0
                errors.add((presented - expected) * 1000.0 / sampleRate)
                if (errors.size < CHECKS_PER_DECISION) continue

                val sorted = errors.sorted()
                val aheadMs = sorted[sorted.size / 2]
                errors.clear()
                onDrift(aheadMs.roundToLong())

                val describe = "drift ${"%+.1f".format(aheadMs)} ms " +
                    "(checks ${"%+.0f".format(sorted.first())}..${"%+.0f".format(sorted.last())}, offset ${currentOffset()} ms)"
                val waitedEnough = lastCorrection == 0L || now - lastCorrection >= MIN_CORRECTION_GAP_MS
                if (abs(aheadMs) > DEAD_ZONE_MS && waitedEnough) {
                    val frames = (abs(aheadMs) * sampleRate / 1000).roundToLong()
                    if (aheadMs > 0) {
                        val insert = min(frames, (MAX_INSERT_MS * sampleRate / 1000).toLong())
                        writer.delayBy(insert)
                        SyncLog.add("Player: $describe → wait ${insert * 1000 / sampleRate} ms")
                    } else {
                        writer.skipAhead(frames)
                        SyncLog.add("Player: $describe → skip ${frames * 1000 / sampleRate} ms")
                    }
                    lastCorrection = now
                    quietDecisions = 0
                } else if (++quietDecisions >= QUIET_DECISIONS_PER_LOG) {
                    quietDecisions = 0
                    SyncLog.add("Player: $describe, ok")
                }
            }

            // Let what's queued play out. Wait by time: some phones reset the play position on stop()
            if (!stopped) {
                SyncLog.add("Player: song finished")
                val queuedMs = (writer.written - audioTrack.playbackHeadPosition) * 1000 / sampleRate
                audioTrack.stop()
                val doneAt = System.currentTimeMillis() + queuedMs + 200
                while (!stopped && System.currentTimeMillis() < doneAt) Thread.sleep(50)
            } else {
                SyncLog.add("Player: stopped")
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
     * so a reported output position can be turned back into a song position. Corrections are
     * applied at the next buffer: fade out, insert silence or skip frames, fade back in.
     */
    private inner class Writer(
        private val sampleRate: Int,
        private val channels: Int,
        private val bytesPerSample: Int,
        private val isFloat: Boolean
    ) {
        private val frameSize = channels * bytesPerSample
        private val fadeFrames = sampleRate * FADE_MS / 1000
        var track: AudioTrack? = null
        var written = 0L          // frames written to the track
        var nextSongFrame = 0L    // song frame of the next decoded frame
        private var pendingInsert = 0L
        private var pendingDrop = 0L
        private var dropping = false
        private var fadeInDone = fadeFrames // < fadeFrames while a fade-in is in progress
        // Measurements are meaningless until the last correction has reached the speaker
        private var correctionHeardAt = Long.MAX_VALUE
        private val segments = ArrayDeque<LongArray>() // [write index, song frame, length]
        private val silence = ByteArray(frameSize * sampleRate / 100) // 10 ms

        fun writeLeadIn(frames: Long) {
            writeSilence(frames)
            correctionHeardAt = written
        }

        /** Skip frames at the very start, when starting late; nothing has been heard yet, so no fade-out. */
        fun skipAtStart(frames: Long) {
            if (frames <= 0) return
            pendingDrop = frames
            dropping = true
            correctionHeardAt = Long.MAX_VALUE
        }

        /** Play [frames] later: fade out, silence, fade back in. */
        fun delayBy(frames: Long) {
            pendingInsert = frames
            correctionHeardAt = Long.MAX_VALUE
        }

        /** Jump [frames] ahead: fade out, skip, fade back in. */
        fun skipAhead(frames: Long) {
            pendingDrop = frames
            correctionHeardAt = Long.MAX_VALUE
        }

        fun writeSong(pcm: ByteArray) {
            val frames = pcm.size / frameSize
            recordLevel(pcm, nextSongFrame, frames)
            applySpeakerSide(pcm, frames)
            var pos = 0
            if ((pendingInsert > 0 || pendingDrop > 0) && !dropping) {
                val fade = min(fadeFrames, frames)
                ramp(pcm, 0, fade, 1f, 0f)
                emit(pcm, 0, fade)
                pos = fade
                if (pendingInsert > 0) {
                    segments.addLast(longArrayOf(written, nextSongFrame - pendingInsert, pendingInsert))
                    writeSilence(pendingInsert)
                    pendingInsert = 0
                    startFadeIn()
                } else {
                    dropping = true
                }
            }
            if (dropping) {
                val skip = min(pendingDrop, (frames - pos).toLong()).toInt()
                nextSongFrame += skip
                pos += skip
                pendingDrop -= skip
                if (pendingDrop == 0L) {
                    dropping = false
                    startFadeIn()
                }
            }
            if (pos >= frames) return
            if (fadeInDone < fadeFrames) {
                val fade = min(fadeFrames - fadeInDone, frames - pos)
                ramp(pcm, pos, fade, fadeInDone.toFloat() / fadeFrames, (fadeInDone + fade).toFloat() / fadeFrames)
                fadeInDone += fade
            }
            emit(pcm, pos, frames - pos)
        }

        /** Song frame being heard at output position [position], or null if we can't tell yet. */
        fun songFrameAt(position: Long): Long? {
            if (position < correctionHeardAt) return null
            while (segments.size > 1 && segments[1][0] <= position) segments.removeFirst()
            val segment = segments.firstOrNull() ?: return null
            if (position < segment[0]) return null
            return segment[1] + (position - segment[0])
        }

        private fun startFadeIn() {
            fadeInDone = 0
            correctionHeardAt = written
        }

        private fun emit(pcm: ByteArray, startFrame: Int, count: Int) {
            if (count <= 0) return
            segments.addLast(longArrayOf(written, nextSongFrame, count.toLong()))
            write(pcm, startFrame * frameSize, count * frameSize)
            nextSongFrame += count
            written += count
        }

        /**
         * Stereo mode: a Left phone plays the left channel on both of its outputs, a Right phone the
         * right one. Read per buffer, so a change is heard after what's already queued (a fraction of a second).
         */
        private fun applySpeakerSide(pcm: ByteArray, frames: Int) {
            val side = AudioSyncManager.speakerSide.value
            if (channels != 2 || side == AudioSyncManager.SIDE_BOTH || (bytesPerSample != 2 && !isFloat)) return
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
            val from = if (side == AudioSyncManager.SIDE_LEFT) 0 else 1
            for (f in 0 until frames) {
                val src = (f * 2 + from) * bytesPerSample
                val dst = (f * 2 + 1 - from) * bytesPerSample
                if (isFloat) buffer.putFloat(dst, buffer.getFloat(src)) else buffer.putShort(dst, buffer.getShort(src))
            }
        }

        // Simple one-pole filters, kept across buffers: below ~150 Hz for bass, ~300 Hz-3 kHz for vocals
        private var lowBass = 0.0
        private var lowVocal = 0.0
        private var highVocal = 0.0
        private val bassCoef = 1 - kotlin.math.exp(-2 * Math.PI * 150 / sampleRate)
        private val vocalLowCoef = 1 - kotlin.math.exp(-2 * Math.PI * 300 / sampleRate)
        private val vocalHighCoef = 1 - kotlin.math.exp(-2 * Math.PI * 3000 / sampleRate)
        private val framesPerSlot = (sampleRate * LEVEL_STEP_MS / 1000).toInt().coerceAtLeast(1)

        /** Note this buffer's loudness, per LEVEL_STEP_MS and per band, in [levels] for the song time it covers. */
        private fun recordLevel(pcm: ByteArray, firstFrame: Long, frames: Int) {
            if (bytesPerSample != 2 && !isFloat) return
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
            val sums = DoubleArray(BANDS)
            var count = 0
            for (f in 0 until frames) {
                var mono = 0.0
                for (c in 0 until channels) {
                    val at = (f * channels + c) * bytesPerSample
                    mono += if (isFloat) buffer.getFloat(at).toDouble() else buffer.getShort(at) / 32768.0
                }
                mono /= channels
                lowBass += bassCoef * (mono - lowBass)
                lowVocal += vocalLowCoef * (mono - lowVocal)
                highVocal += vocalHighCoef * (mono - highVocal)
                val vocal = highVocal - lowVocal
                sums[BAND_SONG] += mono * mono
                sums[BAND_BASS] += lowBass * lowBass
                sums[BAND_VOCALS] += vocal * vocal
                count++
                val frame = firstFrame + f
                if ((frame + 1) % framesPerSlot == 0L || f == frames - 1) {
                    val slot = (frame / framesPerSlot).toInt()
                    for (band in 0 until BANDS) {
                        val rms = sqrt(sums[band] / count).toFloat()
                        if (slot in levels[band].indices) levels[band][slot] = rms
                        if (rms > peakLevels[band]) peakLevels[band] = rms
                        sums[band] = 0.0
                    }
                    count = 0
                }
            }
        }

        /** Scales [count] frames from [startFrame] by a gain going linearly from [from] to [to]. */
        private fun ramp(pcm: ByteArray, startFrame: Int, count: Int, from: Float, to: Float) {
            if (count <= 0 || (bytesPerSample != 2 && !isFloat)) return
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                val gain = from + (to - from) * i / count
                for (c in 0 until channels) {
                    val at = ((startFrame + i) * channels + c) * bytesPerSample
                    if (isFloat) {
                        buffer.putFloat(at, buffer.getFloat(at) * gain)
                    } else {
                        buffer.putShort(at, (buffer.getShort(at) * gain).toInt().toShort())
                    }
                }
            }
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
        val mime: String
        var outputFormat: MediaFormat

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

        /** Before the first [next]: start decoding near [us] into the song. */
        fun seekTo(us: Long) {
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
