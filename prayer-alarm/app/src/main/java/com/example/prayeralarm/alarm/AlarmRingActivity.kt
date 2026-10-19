package com.example.prayeralarm.alarm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.prayeralarm.R
import com.example.prayeralarm.data.Store
import com.example.prayeralarm.ui.AppTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Full-screen alarm shown over the lock screen while [AlarmService] is ringing. */
class AlarmRingActivity : ComponentActivity() {
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

    /** Decodes the chosen picture scaled down to roughly screen size; null if it can't be read. */
    private fun loadBackground(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
}

@Composable
private fun RingScreen(
    alarm: RingingAlarm,
    snoozeMinutes: Int,
    background: Bitmap?,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    onStopGroup: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.fillMaxSize()) {
            val textColor = if (background != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            if (background != null) {
                Image(background.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                    ) { Text(stringResource(R.string.snooze_minutes, snoozeMinutes)) }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
                if (alarm.remaining > 0) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onStopGroup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text(LocalContext.current.resources.getQuantityString(R.plurals.stop_remaining, alarm.remaining, alarm.remaining)) }
                }
            }
        }
    }
}
