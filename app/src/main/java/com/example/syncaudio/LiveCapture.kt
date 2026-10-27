// File: LiveCapture.kt
package com.example.syncaudio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * The host phone's own sound (YouTube or any other app that allows it), kept as a rolling buffer so
 * LiveAudioServer can stream it to joined phones like a song that never ends. Frame 0 is the first
 * captured frame; [startHostMs] is when it was captured, on this phone's clock.
 */
object LiveCapture {
    /** The name the live sound goes by on the network and on screen. */
    const val SONG = "Phone sound"
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val FRAME_SIZE = 4 // 16-bit stereo
    private const val CAPACITY_FRAMES = SAMPLE_RATE * 12 // 12 s

    private val ring = ByteArray(CAPACITY_FRAMES * FRAME_SIZE)
    private val lock = ReentrantLock()
    private val moreFrames = lock.newCondition()
    private var written = 0L

    @Volatile var active = false
        private set

    fun start() = lock.withLock {
        written = 0L
        active = true
    }

    fun stop() = lock.withLock {
        active = false
        moreFrames.signalAll()
    }

    fun append(data: ByteArray, length: Int) = lock.withLock {
        var frames = length / FRAME_SIZE
        var from = 0
        while (frames > 0) {
            val at = (written % CAPACITY_FRAMES).toInt()
            val n = min(frames, CAPACITY_FRAMES - at)
            System.arraycopy(data, from, ring, at * FRAME_SIZE, n * FRAME_SIZE)
            from += n * FRAME_SIZE
            written += n
            frames -= n
        }
        moreFrames.signalAll()
    }

    /**
     * Up to [maxFrames] frames starting at [fromFrame] (moved up to the oldest still kept), waiting up
     * to [timeoutMs] for them to be captured. Returns the first frame's number and the bytes, or null.
     */
    fun read(fromFrame: Long, maxFrames: Int, timeoutMs: Long): Pair<Long, ByteArray>? {
        lock.withLock {
            var waitNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (active && written <= fromFrame) {
                if (waitNs <= 0) return null
                waitNs = moreFrames.awaitNanos(waitNs)
            }
            if (written <= fromFrame) return null
            // Keep a second's margin from the part being overwritten
            val start = maxOf(fromFrame, written - CAPACITY_FRAMES + SAMPLE_RATE)
            val count = min((written - start).toInt(), maxFrames)
            val out = ByteArray(count * FRAME_SIZE)
            var copied = 0
            while (copied < count) {
                val at = ((start + copied) % CAPACITY_FRAMES).toInt()
                val n = min(count - copied, CAPACITY_FRAMES - at)
                System.arraycopy(ring, at * FRAME_SIZE, out, copied * FRAME_SIZE, n * FRAME_SIZE)
                copied += n
            }
            return start to out
        }
    }
}

/**
 * Holds Android's screen-capture permission (needed to capture other apps' sound) and records that
 * sound into [LiveCapture]. Android requires this to run as a foreground service, with a notification.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class LiveCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "phone_sound"
        private const val NOTIFICATION_ID = 7

        fun stop(context: Context) {
            context.startService(Intent(context, LiveCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be a foreground service of type mediaProjection before the projection is taken
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(resultCode, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
            startCapture(mp)
        } catch (e: Exception) {
            SyncLog.add("Phone sound: can't start capture: ${e.javaClass.simpleName}: ${e.message}")
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is asked for before this service starts
    private fun startCapture(mp: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(LiveCapture.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(LiveCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = recorder
        LiveCapture.start()
        recorder.startRecording()
        capturing = true
        SyncLog.add("Phone sound: capturing")

        Thread {
            val buffer = ByteArray(LiveCapture.SAMPLE_RATE / 50 * 4) // 20 ms
            val ts = AudioTimestamp()
            val startedAt = System.currentTimeMillis()
            var framesRead = 0L
            var announced = false
            while (capturing) {
                val n = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                LiveCapture.append(buffer, n)
                framesRead += n / 4

                // After half a second, work out when frame 0 was captured and start every phone
                if (!announced && System.currentTimeMillis() - startedAt >= 500) {
                    announced = true
                    val startMs = if (recorder.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val heardAt = System.currentTimeMillis() - (System.nanoTime() - ts.nanoTime) / 1_000_000.0
                        heardAt - ts.framePosition * 1000.0 / LiveCapture.SAMPLE_RATE
                    } else {
                        System.currentTimeMillis() - framesRead * 1000.0 / LiveCapture.SAMPLE_RATE
                    }
                    AudioSyncManager.startLive(startMs.toLong())
                }
            }
        }.start()
    }

    private fun stopCapture() {
        capturing = false
        LiveCapture.stop()
        try {
            record?.stop()
        } catch (e: Exception) {
        }
        record?.release()
        record = null
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
        AudioSyncManager.onLiveStopped()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone sound", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing this phone's sound")
            .setContentText("Playing on every joined phone")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
