package com.example.prayeralarm.alarm

import android.annotation.SuppressLint
import android.app.Notification
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
import com.example.prayeralarm.LocaleHelper
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What is ringing. [groupId], [sessionEnd] and [remaining] describe the rest of an alarm group,
 * so the ring screen can offer "I'm awake, stop the rest".
 */
data class RingingAlarm(
    val label: String,
    val isPrayer: Boolean,
    val soundUri: String? = null,
    val groupId: Int = -1,
    val sessionEnd: Long = 0L,
    val remaining: Int = 0,
)

/** Foreground service that plays the alarm sound until dismissed, snoozed, or it times out. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRinging() }
    private var volume = 1f
    private val volumeStep = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = (volume + rampIncrement).coerceAtMost(1f)
            runCatching { p.setVolume(volume, volume) }
            if (volume < 1f) handler.postDelayed(this, RAMP_TICK_MILLIS)
        }
    }
    private var rampIncrement = 1f

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopRinging()
            ACTION_SNOOZE -> {
                _ringing.value?.let { AlarmScheduler.scheduleSnooze(this, it, Store(this).appSettings.snoozeMinutes) }
                stopRinging()
            }
            ACTION_STOP_GROUP -> {
                _ringing.value?.takeIf { it.groupId >= 0 }?.let {
                    AlarmScheduler.skipSession(this, it.groupId, it.sessionEnd)
                }
                stopRinging()
            }
            else -> {
                if (intent == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRinging(
                    RingingAlarm(
                        label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty(),
                        isPrayer = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_PRAYER, false),
                        soundUri = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND),
                        groupId = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1),
                        sessionEnd = intent.getLongExtra(AlarmScheduler.EXTRA_SESSION_END, 0L),
                        remaining = intent.getIntExtra(EXTRA_REMAINING, 0),
                    )
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(alarm: RingingAlarm) {
        val settings = Store(this).appSettings
        _ringing.value = alarm
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(alarm),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        playSound(alarm, settings)
        if (settings.vibrate) vibrate() else vibrator?.cancel()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, settings.ringMinutes * 60_000L)
    }

    @SuppressLint("DiscouragedApi")
    private fun playSound(alarm: RingingAlarm, settings: AppSettings) {
        releasePlayer()
        val systemAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        // Candidates in order of preference, each with whether it should repeat.
        val candidates = buildList {
            alarm.soundUri?.let { add(Uri.parse(it) to !alarm.isPrayer) }
            if (alarm.isPrayer) {
                settings.prayerSoundUri?.let { add(Uri.parse(it) to false) }
                // An adhan.mp3 in res/raw is used when no adhan file was picked in settings.
                val adhanId = resources.getIdentifier("adhan", "raw", packageName)
                if (adhanId != 0) add(Uri.parse("android.resource://$packageName/$adhanId") to false)
            } else {
                settings.alarmSoundUri?.let { add(Uri.parse(it) to true) }
            }
            systemAlarm?.let { add(it to true) }
        }
        for ((uri, loop) in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmService, uri)
                    isLooping = loop
                    if (!loop) setOnCompletionListener { stopRinging() }
                    prepare()
                }
                startVolumeRamp(settings.ascendingSeconds)
                player?.start()
                return
            } catch (e: Exception) {
                releasePlayer()
            }
        }
    }

    private fun startVolumeRamp(seconds: Int) {
        handler.removeCallbacks(volumeStep)
        if (seconds <= 0) {
            volume = 1f
            player?.setVolume(1f, 1f)
            return
        }
        volume = MIN_VOLUME
        rampIncrement = (1f - MIN_VOLUME) / (seconds * 1000f / RAMP_TICK_MILLIS)
        player?.setVolume(volume, volume)
        handler.postDelayed(volumeStep, RAMP_TICK_MILLIS)
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
        handler.removeCallbacks(volumeStep)
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
        return NotificationCompat.Builder(this, Notifications.ALARM_CHANNEL)
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
        const val ACTION_STOP_GROUP = "com.example.prayeralarm.STOP_GROUP"
        private const val EXTRA_REMAINING = "remaining"
        private const val NOTIFICATION_ID = 1
        private const val MIN_VOLUME = 0.05f
        private const val RAMP_TICK_MILLIS = 250L

        private val _ringing = MutableStateFlow<RingingAlarm?>(null)
        val ringing: StateFlow<RingingAlarm?> = _ringing

        fun start(context: Context, alarm: RingingAlarm) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java)
                    .putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
                    .putExtra(AlarmScheduler.EXTRA_IS_PRAYER, alarm.isPrayer)
                    .putExtra(AlarmScheduler.EXTRA_SOUND, alarm.soundUri)
                    .putExtra(AlarmScheduler.EXTRA_ID, alarm.groupId)
                    .putExtra(AlarmScheduler.EXTRA_SESSION_END, alarm.sessionEnd)
                    .putExtra(EXTRA_REMAINING, alarm.remaining),
            )
        }

        fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
