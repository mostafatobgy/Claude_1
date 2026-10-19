package com.example.prayeralarm.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.prayeralarm.R
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

        setContent {
            val alarm by AlarmService.ringing.collectAsStateWithLifecycle()
            // Close when the alarm is stopped from anywhere, e.g. the notification or the timeout.
            LaunchedEffect(alarm) { if (alarm == null) finish() }
            AppTheme {
                alarm?.let {
                    RingScreen(
                        it,
                        onSnooze = { send(AlarmService.ACTION_SNOOZE) },
                        onDismiss = { send(AlarmService.ACTION_DISMISS) },
                    )
                }
            }
        }
    }

    private fun send(action: String) {
        AlarmService.actionIntent(this, action).send()
        finish()
    }
}

@Composable
private fun RingScreen(alarm: RingingAlarm, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (alarm.isPrayer) R.string.prayer_time else R.string.alarm),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(alarm.label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(48.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.snooze_minutes, AlarmService.SNOOZE_MINUTES.toInt()))
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}
