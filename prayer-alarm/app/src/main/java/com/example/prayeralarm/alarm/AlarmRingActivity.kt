package com.example.prayeralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.prayeralarm.R
import com.example.prayeralarm.data.BackgroundFit
import com.example.prayeralarm.data.SideButtonAction
import com.example.prayeralarm.data.Store
import androidx.core.content.ContextCompat
import com.example.prayeralarm.ui.AppTheme
import com.example.prayeralarm.ui.Brand
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Full-screen alarm shown over the lock screen while [AlarmService] is ringing. */
class AlarmRingActivity : AppCompatActivity() {
    private var sideButtons = SideButtonAction.NONE
    private var receiverRegistered = false
    private var handledSideButton = false

    // The power button turns the screen off; the screen stays on otherwise while this is shown.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onSideButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val settings = Store(this).appSettings
        sideButtons = settings.sideButtons
        if (sideButtons != SideButtonAction.NONE) {
            ContextCompat.registerReceiver(
                this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        setContent {
            val alarm by AlarmService.ringing.collectAsStateWithLifecycle()
            // Close when the alarm is stopped from anywhere, e.g. the notification or the timeout.
            LaunchedEffect(alarm) { if (alarm == null) finish() }
            val background = remember { settings.backgroundUri?.let { loadBackground(Uri.parse(it)) } }
            AppTheme(settings.theme) {
                alarm?.let {
                    RingScreen(
                        it,
                        settings.snoozeMinutes,
                        background,
                        settings.backgroundFit,
                        onSnooze = { send(AlarmService.ACTION_SNOOZE) },
                        onDismiss = { send(AlarmService.ACTION_DISMISS) },
                        onStopGroup = { send(AlarmService.ACTION_STOP_GROUP) },
                    )
                }
            }
        }
    }

    private fun send(action: String) {
        AlarmService.actionIntent(this, action).send()
        finish()
    }

    /** Volume up/down or power pressed while ringing: snooze or dismiss, as chosen in Settings. */
    private fun onSideButton() {
        if (sideButtons == SideButtonAction.NONE || handledSideButton || AlarmService.ringing.value == null) return
        handledSideButton = true
        send(if (sideButtons == SideButtonAction.SNOOZE) AlarmService.ACTION_SNOOZE else AlarmService.ACTION_DISMISS)
    }

    private fun isSideKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA

    // Catch the keys before the screen's views or the system volume do.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sideButtons != SideButtonAction.NONE && isSideKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onSideButton()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    /**
     * Decodes the chosen picture scaled down to roughly screen size, turned the way it was taken
     * (camera photos store their rotation separately in EXIF); null if it can't be read.
     */
    private fun loadBackground(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return@runCatching bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull()
}

@Composable
private fun RingScreen(
    alarm: RingingAlarm,
    snoozeMinutes: Int,
    background: Bitmap?,
    fit: BackgroundFit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    onStopGroup: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Brand.PrimaryDark) {
        // Brand gradient (dark green → green) when no picture is chosen; text is white either way.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.PrimaryDark, Brand.Primary)))) {
            val textColor = Color.White
            if (background != null) {
                if (fit == BackgroundFit.FIT) Box(Modifier.fillMaxSize().background(Color.Black))
                Image(
                    background.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = if (fit == BackgroundFit.FIT) ContentScale.Fit else ContentScale.Crop,
                )
                // Darken the picture so the text stays readable on any image.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                    style = MaterialTheme.typography.displayLarge,
                    color = textColor,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (alarm.isPrayer) R.string.prayer_time else R.string.alarm),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                )
                Text(
                    alarm.label,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = textColor,
                )
                Spacer(Modifier.height(48.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.weight(1f).height(54.dp),
                        border = BorderStroke(2.dp, Color.White),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text(stringResource(R.string.snooze_minutes, snoozeMinutes)) }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.PrimaryDark),
                    ) { Text(stringResource(R.string.dismiss)) }
                }
                if (alarm.remaining > 0) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onStopGroup,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent, contentColor = Color.White),
                    ) { Text(LocalContext.current.resources.getQuantityString(R.plurals.stop_remaining, alarm.remaining, alarm.remaining)) }
                }
            }
        }
    }
}
