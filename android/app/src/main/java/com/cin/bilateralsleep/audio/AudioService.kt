package com.cin.bilateralsleep.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.cin.bilateralsleep.MainActivity
import com.cin.bilateralsleep.R

/**
 * Foreground service that owns the audio thread so playback survives screen-off
 * and runs for hours. The notification is deliberately low-importance, silent,
 * and non-vibrating — the single OS-mandated exception to "no notifications".
 */
class AudioService : Service() {

    @Volatile private var running = false
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var timerEndAt = 0L
    private var fadeStarted = false
    private val ticker = object : Runnable {
        override fun run() {
            val remaining = timerEndAt - SystemClock.elapsedRealtime()
            SleepController.updateRemaining(if (remaining > 0) remaining else 0L)
            if (!fadeStarted && remaining <= SLEEP_FADE_MILLIS) {
                fadeStarted = true
                SleepController.params.fadeSeconds = SLEEP_FADE_SECONDS
                SleepController.params.targetEnvelope = 0.0  // begin slow fade-out
            }
            if (remaining > 0) handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> beginStopFade()
            else -> startPlayback()
        }
        // Deliberately NOT sticky: a sleep app must never silently resurrect
        // itself (with default settings) after an OS kill.
        return START_NOT_STICKY
    }

    private fun startPlayback() {
        if (running) return
        createChannel()
        startForegroundCompat()
        acquireWakeLock()

        SleepController.params.targetEnvelope = 1.0
        running = true
        audioThread = Thread({ audioLoop() }, "bilateral-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        setupTimer()
    }

    private fun setupTimer() {
        fadeStarted = false
        val minutes = SleepController.timerMinutes.value
        if (minutes > 0) {
            timerEndAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            handler.post(ticker)
        } else {
            SleepController.updateRemaining(0L)
        }
    }

    private fun beginStopFade() {
        // gentle fade; the audio loop ends itself once the envelope reaches zero
        SleepController.params.fadeSeconds = 1.5
        SleepController.params.targetEnvelope = 0.0
        handler.removeCallbacks(ticker)
    }

    private fun audioLoop() {
        // Raise the render thread into Android's audio scheduling class — Java
        // Thread.MAX_PRIORITY alone does not grant it, leaving us open to
        // scheduler-induced underruns over a multi-hour run.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sr = SAMPLE_RATE
        val frames = BLOCK_FRAMES
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        // 8 blocks of headroom (~170 ms) so screen-off / Doze scheduling stalls
        // don't drain the buffer. Latency is irrelevant for sleep audio.
        val bufBytes = maxOf(minBuf, frames * 2 /*ch*/ * 4 /*float*/ * 8 /*blocks*/)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val engine = BilateralEngine(SleepController.params, sr)
        val out = FloatArray(frames * 2)
        track.play()
        try {
            while (running) {
                val cont = engine.render(out, frames)
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (!cont) break  // fade-to-silence finished
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            handler.post { finishService() }
        }
    }

    private fun finishService() {
        running = false
        handler.removeCallbacks(ticker)
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        SleepController.onServiceStopped()
        stopSelf()
    }

    // --- notification (low-importance, silent, ongoing) ---
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_sleep)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bilateralsleep:audio")
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(ticker)
        audioThread?.let { runCatching { it.join(500) } }
        releaseWakeLock()
        SleepController.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.cin.bilateralsleep.START"
        const val ACTION_STOP = "com.cin.bilateralsleep.STOP"
        const val CHANNEL_ID = "bilateral_sleep_playback"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 48000
        const val BLOCK_FRAMES = 1024
        const val SLEEP_FADE_SECONDS = 60.0
        const val SLEEP_FADE_MILLIS = (SLEEP_FADE_SECONDS * 1000).toLong()
    }
}
