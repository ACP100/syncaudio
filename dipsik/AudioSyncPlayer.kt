package com.example.syncaudio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.util.Log

class AudioSyncPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun loadAudio(uri: Uri) {
        player?.release()
        player = MediaPlayer.create(context, uri)
    }

    /**
     * Plays audio at the exact timestamp (serverTime + offset)
     *
     * @param targetTime  Server-specified future timestamp in ms.
     * @param offset      Client's clock offset relative to server.
     */
    fun playAtTimestamp(targetTime: Long, offset: Long) {
        val correctedTarget = targetTime + offset
        val delay = correctedTarget - System.currentTimeMillis()

        Log.d("AudioSync", "Scheduled playback in $delay ms (offset=$offset)")

        if (delay > 0) SystemClock.sleep(delay)
        player?.start()
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null
    }
}
