package com.example.prayeralarm.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.prayeralarm.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RingingAlarm(val label: String, val isPrayer: Boolean)

/** Foreground service that plays the alarm sound until dismissed, snoozed, or it times out. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopRinging()
            ACTION_SNOOZE -> {
                _ringing.value?.let { AlarmScheduler.scheduleSnooze(this, it.label, it.isPrayer, SNOOZE_MINUTES) }
                stopRinging()
            }
            else -> startRinging(
                RingingAlarm(
                    intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty(),
                    intent?.getBooleanExtra(EXTRA_IS_PRAYER, false) ?: false,
                )
            )
        }
        return START_NOT_STICKY
    }

    private fun startRinging(alarm: RingingAlarm) {
        _ringing.value = alarm
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(alarm),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        playSound(alarm.isPrayer)
        vibrate()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MILLIS)
    }

    @SuppressLint("DiscouragedApi")
    private fun playSound(isPrayer: Boolean) {
        releasePlayer()
        // Drop an adhan.mp3 into res/raw to use it for prayer alarms; otherwise the phone's alarm tone plays.
        val adhanId = if (isPrayer) resources.getIdentifier("adhan", "raw", packageName) else 0
        val adhan = if (adhanId != 0) Uri.parse("android.resource://$packageName/$adhanId") else null
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        for (uri in listOfNotNull(adhan, fallback)) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    // The adhan plays once; the alarm tone repeats until dismissed.
                    isLooping = uri != adhan
                    if (uri == adhan) setOnCompletionListener { stopRinging() }
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                releasePlayer()
            }
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun stopRinging() {
        handler.removeCallbacks(autoStop)
        releasePlayer()
        vibrator?.cancel()
        _ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        releasePlayer()
        vibrator?.cancel()
        _ringing.value = null
        super.onDestroy()
    }

    private fun buildNotification(alarm: RingingAlarm): Notification {
        val ring = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(if (alarm.isPrayer) R.string.prayer_time else R.string.alarm))
            .setContentText(alarm.label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(ring)
            .setFullScreenIntent(ring, true)
            .addAction(0, getString(R.string.snooze), actionIntent(this, ACTION_SNOOZE))
            .addAction(0, getString(R.string.dismiss), actionIntent(this, ACTION_DISMISS))
            .build()
    }

    companion object {
        const val ACTION_DISMISS = "com.example.prayeralarm.DISMISS"
        const val ACTION_SNOOZE = "com.example.prayeralarm.SNOOZE"
        private const val EXTRA_IS_PRAYER = "is_prayer"
        private const val CHANNEL_ID = "alarms"
        private const val NOTIFICATION_ID = 1
        const val SNOOZE_MINUTES = 10L
        private const val AUTO_STOP_MILLIS = 5 * 60 * 1000L

        private val _ringing = MutableStateFlow<RingingAlarm?>(null)
        val ringing: StateFlow<RingingAlarm?> = _ringing

        fun start(context: Context, label: String, isPrayer: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java)
                    .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                    .putExtra(EXTRA_IS_PRAYER, isPrayer),
            )
        }

        fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

        fun canUseFullScreen(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        fun ensureChannel(context: Context) {
            // The service plays the sound itself, so the channel stays silent.
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.alarms), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
