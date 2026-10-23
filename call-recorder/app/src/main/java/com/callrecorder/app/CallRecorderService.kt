package com.callrecorder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground service that records one call to a .m4a file.
 *
 * It is deliberately visible: while it runs, an ongoing notification tells the user the call is
 * being recorded. Recording is best-effort — since Android 10 the platform blocks access to the
 * far-end call audio for ordinary apps, so on many devices only the local side (microphone /
 * loudspeaker) is captured. The service tries the richer audio sources first and falls back.
 *
 * Any failure is reported — both as an in-app broadcast (so the screen can show it) and as a
 * notification (so it is visible even when the failure happens during a call) — rather than the
 * service dying silently.
 */
class CallRecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail(getString(R.string.err_no_mic_permission))
            return
        }

        // Enter the foreground as a microphone service before touching the mic (required 29+).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIF_ID, buildNotification(number),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification(number))
            }
        } catch (e: Exception) {
            fail(getString(R.string.err_service, e.javaClass.simpleName))
            return
        }

        val file = Storage.newFile(this, number)
        outputFile = file
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

        var lastError: String? = null
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
                recorder = rec
                startedAt = SystemClock.elapsedRealtime()
                isRecording = true
                broadcastState(recording = true, error = null)
                return
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        // Every audio source was refused by the OS.
        rec.release()
        file.delete()
        outputFile = null
        fail(getString(R.string.err_record_failed, lastError ?: "unknown"))
    }

    private fun audioSources(): List<Int> = listOf(
        MediaRecorder.AudioSource.VOICE_CALL,          // both sides, where the OEM still allows it
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, // VoIP-style, echo-cancelled
        MediaRecorder.AudioSource.MIC                  // local side only — the universal fallback
    )

    /** Report a start failure to the UI and as a notification, then stop. */
    private fun fail(message: String) {
        isRecording = false
        broadcastState(recording = false, error = message)
        notifyError(message)
        stopSelf()
    }

    private fun stopRecorder() {
        val rec = recorder ?: return
        // A recording under ~1s often has no frames and MediaRecorder.stop() throws; guard it.
        val tooShort = SystemClock.elapsedRealtime() - startedAt < 1000
        try {
            if (tooShort) throw IllegalStateException("too short")
            rec.stop()
        } catch (_: Exception) {
            outputFile?.delete() // discard an unfinished/empty file
        } finally {
            rec.release()
            recorder = null
            isRecording = false
        }
    }

    override fun onDestroy() {
        val wasRecording = recorder != null
        stopRecorder()
        if (wasRecording) broadcastState(recording = false, error = null)
        super.onDestroy()
    }

    private fun broadcastState(recording: Boolean, error: String?) {
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_RECORDING, recording)
                .putExtra(EXTRA_ERROR, error)
        )
    }

    private fun channel(): NotificationManager {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            mgr.createNotificationChannel(c)
        }
        return mgr
    }

    private fun notifyError(message: String) {
        channel().notify(
            NOTIF_ERR,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.err_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_mic)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    private fun buildNotification(number: String?): Notification {
        channel()
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
        const val ACTION_STATE = "com.callrecorder.app.STATE"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val NOTIF_ERR = 2

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
                ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {
                isRecording = false
                context.sendBroadcast(
                    Intent(ACTION_STATE).setPackage(context.packageName)
                        .putExtra(EXTRA_RECORDING, false)
                        .putExtra(EXTRA_ERROR, context.getString(R.string.err_background_start))
                )
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, CallRecorderService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
