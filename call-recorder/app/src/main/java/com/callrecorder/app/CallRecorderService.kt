package com.callrecorder.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service that records one call to a .m4a file.
 *
 * It is deliberately visible: while it runs, an ongoing notification tells the user the call is
 * being recorded. Recording is best-effort — since Android 10 the platform blocks access to the
 * far-end call audio for ordinary apps, so on many devices only the local side (microphone /
 * loudspeaker) is captured. The service tries the richer audio sources first and falls back.
 */
class CallRecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_NUMBER))
            ACTION_STOP -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION") // the no-arg MediaRecorder() is the only option below API 31
    private fun start(number: String?) {
        if (recorder != null) return // already recording
        startForeground(NOTIF_ID, buildNotification(number))

        val file = Storage.newFile(this, number)
        outputFile = file
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

        var started = false
        // Try the sources most likely to capture both sides, then fall back to the plain mic.
        for (source in audioSources()) {
            try {
                rec.reset()
                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(96_000)
                rec.setAudioSamplingRate(44_100)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                rec.start()
                started = true
                break
            } catch (_: Exception) {
                // This source is not allowed on this device/Android version — try the next.
            }
        }

        if (started) {
            recorder = rec
            isRecording = true
        } else {
            rec.release()
            file.delete()
            isRecording = false
            stopSelf()
        }
    }

    private fun audioSources(): List<Int> = listOf(
        MediaRecorder.AudioSource.VOICE_CALL,          // both sides, where the OEM still allows it
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, // VoIP-style, echo-cancelled
        MediaRecorder.AudioSource.MIC                  // local side only — the universal fallback
    )

    private fun stopRecorder() {
        val rec = recorder ?: return
        try {
            rec.stop()
        } catch (_: Exception) {
            // Stopped too early to produce a valid file — discard it.
            outputFile?.delete()
        } finally {
            rec.release()
            recorder = null
            isRecording = false
        }
    }

    override fun onDestroy() {
        stopRecorder()
        super.onDestroy()
    }

    private fun buildNotification(number: String?): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            mgr.createNotificationChannel(channel)
        }
        // Show the saved contact name when we can resolve it, otherwise the raw number.
        val display = Contacts.nameFor(this, number) ?: number
        val who = if (display.isNullOrBlank() || display == "unknown")
            getString(R.string.notif_text_generic)
        else getString(R.string.notif_text_with, display)

        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(who)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.callrecorder.app.START"
        const val ACTION_STOP = "com.callrecorder.app.STOP"
        const val EXTRA_NUMBER = "number"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        /** True while a recording is in progress — read by the UI to show the right button state. */
        @Volatile
        var isRecording: Boolean = false
            private set

        fun start(context: Context, number: String?) {
            val i = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NUMBER, number)
            }
            // On Android 12+ a mic foreground service started from the background can be refused;
            // catch it so an auto-record attempt during a call never crashes the phone app.
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {
                isRecording = false
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, CallRecorderService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
