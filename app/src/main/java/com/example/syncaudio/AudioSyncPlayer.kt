//Audiosyncplayer
package com.example.syncaudio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlin.math.abs

class AudioSyncPlayer(private val context: Context) {

    @Volatile private var player: MediaPlayer? = null

    companion object {
        private const val CHECK_INTERVAL_MS = 200L
        private const val CHECKS_PER_DECISION = 5  // act on the median of 5 checks, not on single noisy ones
        private const val SEEK_THRESHOLD_MS = 150L // further off than this: jump
        private const val NUDGE_THRESHOLD_MS = 3L  // closer than this: leave it alone
        private const val SMALL_NUDGE = 0.02f      // play 2% fast or slow to close small gaps
        private const val BIG_NUDGE = 0.05f        // 5% for gaps over BIG_NUDGE_FROM_MS
        private const val BIG_NUDGE_FROM_MS = 40L
        private const val SETTLE_AFTER_SEEK_MS = 500L
    }

    /** Prepares the file so that start() has no decoding delay later. Returns false if it cannot be opened. */
    fun loadAudio(uri: Uri): Boolean {
        release()
        player = MediaPlayer.create(context, uri)
        return player != null
    }

    /**
     * Plays audio at the moment the server clock reads [targetTime].
     *
     * [offset] is the server-minus-local clock difference, as returned by TimeSync
     * (server time = local time + offset), so the local start instant is targetTime - offset.
     * Pass 0 when this phone is the clock reference itself.
     */
    fun playAtTimestamp(
        targetTime: Long,
        offset: Long,
        currentOffset: () -> Long = { offset },
        extraDelayMs: () -> Long = { 0L },
        onDrift: (Long) -> Unit = {}
    ) {
        val localTarget = targetTime - offset
        Log.d("AudioSync", "Scheduled playback in ${localTarget - System.currentTimeMillis()} ms (offset=$offset)")

        waitUntil(localTarget)
        player?.start()
        Thread { keepInSync(targetTime, currentOffset, extraDelayMs, onDrift) }.start()
    }

    /**
     * start() only asks for playback; each phone's audio output adds its own delay before sound comes
     * out, often 50-200 ms and different per model. So while playing, compare the position actually
     * reaching the speaker (MediaPlayer timestamp) with where the shared host clock says the song should
     * be, then jump when far off and briefly play faster or slower when slightly off.
     */
    private fun keepInSync(
        targetTime: Long,
        currentOffset: () -> Long,
        extraDelayMs: () -> Long,
        onDrift: (Long) -> Unit
    ) {
        val recent = ArrayList<Long>()
        var busyUntil = 0L // while a nudge or seek is in progress, measurements are meaningless
        try {
            while (true) {
                Thread.sleep(CHECK_INTERVAL_MS)
                val mp = player ?: return
                if (!mp.isPlaying) {
                    if (System.currentTimeMillis() - targetTime + currentOffset() > mp.duration) return
                    continue
                }

                val now = System.currentTimeMillis()
                if (busyUntil != 0L) {
                    if (now < busyUntil) continue
                    setSpeed(mp, 1f)
                    busyUntil = 0L
                    recent.clear()
                    continue // let the speed change settle before measuring again
                }

                val ts = mp.timestamp ?: continue
                if (ts.mediaClockRate == 0f) continue
                @Suppress("DEPRECATION")
                val anchorNs = ts.anchorSytemNanoTime
                val actualMs = (ts.anchorMediaTimeUs + (System.nanoTime() - anchorNs) / 1000 * ts.mediaClockRate) / 1000
                val expectedMs = now + currentOffset() - targetTime - extraDelayMs()
                recent.add((actualMs - expectedMs).toLong())
                if (recent.size < CHECKS_PER_DECISION) continue

                val aheadMs = recent.sorted()[recent.size / 2]
                recent.clear()
                onDrift(aheadMs)

                when {
                    abs(aheadMs) > SEEK_THRESHOLD_MS -> {
                        Log.d("AudioSync", "Off by $aheadMs ms, seeking")
                        seekTo(mp, mp.currentPosition - aheadMs)
                        busyUntil = now + SETTLE_AFTER_SEEK_MS
                    }
                    abs(aheadMs) > NUDGE_THRESHOLD_MS -> {
                        val nudge = if (abs(aheadMs) > BIG_NUDGE_FROM_MS) BIG_NUDGE else SMALL_NUDGE
                        Log.d("AudioSync", "Off by $aheadMs ms, nudging speed by $nudge")
                        setSpeed(mp, if (aheadMs > 0) 1f - nudge else 1f + nudge)
                        busyUntil = now + (abs(aheadMs) / nudge).toLong()
                    }
                }
            }
        } catch (e: InterruptedException) {
        } catch (e: IllegalStateException) {
            // Player was stopped or released while we were checking
        }
    }

    private fun setSpeed(mp: MediaPlayer, speed: Float) {
        mp.playbackParams = PlaybackParams().setSpeed(speed)
    }

    private fun seekTo(mp: MediaPlayer, positionMs: Long) {
        val target = positionMs.coerceIn(0L, mp.duration.toLong())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(target.toInt())
        }
    }

    fun release() {
        player?.release()
        player = null
    }

    fun stop() {
        player?.stop()
        release()
    }

    // Sleep for the bulk of the wait, then spin for the last millisecond or so.
    // Thread.sleep alone is only accurate to a few ms, which is audible between two phones.
    private fun waitUntil(wallClockMs: Long) {
        val deadlineNs = System.nanoTime() + (wallClockMs - System.currentTimeMillis()) * 1_000_000L
        while (true) {
            val leftNs = deadlineNs - System.nanoTime()
            if (leftNs <= 0) break
            if (leftNs > 2_000_000L) {
                Thread.sleep((leftNs - 1_000_000L) / 1_000_000L)
            } else {
                Thread.yield() // Thread.onSpinWait() needs API 34; minSdk is 24
            }
        }
    }
}
