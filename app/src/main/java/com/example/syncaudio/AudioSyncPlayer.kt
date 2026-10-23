//Audiosyncplayer
package com.example.syncaudio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

class AudioSyncPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

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
    fun playAtTimestamp(targetTime: Long, offset: Long) {
        val localTarget = targetTime - offset
        Log.d("AudioSync", "Scheduled playback in ${localTarget - System.currentTimeMillis()} ms (offset=$offset)")

        waitUntil(localTarget)
        player?.start()
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
                Thread.onSpinWait()
            }
        }
    }
}
