package com.example.prayeralarm.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.BackgroundFit
import com.example.prayeralarm.data.ThemeMode
import java.time.DayOfWeek

private val ascendingOptions = listOf(0, 15, 30, 60, 120)
private val snoozeOptions = listOf(5, 10, 15, 20, 30)
private val ringOptions = listOf(1, 3, 5, 10, 15)
private val reminderOptions = listOf(0, 5, 10, 15, 30, 60)
private val wakeCheckOptions = listOf(0, 30, 60, 90, 120)

private enum class Picker { ASCENDING, SNOOZE, RING, REMINDER, WAKE_CHECK, THEME, BACKGROUND, FIT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<Picker?>(null) }
    BackHandler(onBack = onClose)

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onChange { it.copy(backgroundUri = uri.toString()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.section_sound))
            SoundRow(
                stringResource(R.string.alarm_sound), settings.alarmSoundName,
                stringResource(R.string.sound_phone_default), settings.alarmSoundUri,
            ) { uri, name -> onChange { it.copy(alarmSoundUri = uri, alarmSoundName = name ?: uri?.let { context.getString(R.string.custom_sound) }) } }
            SoundRow(
                stringResource(R.string.adhan_sound), settings.prayerSoundName,
                stringResource(R.string.sound_adhan_default), settings.prayerSoundUri,
            ) { uri, name -> onChange { it.copy(prayerSoundUri = uri, prayerSoundName = name ?: uri?.let { context.getString(R.string.custom_sound) }) } }
            SettingRow(
                stringResource(R.string.vibrate), null,
                trailing = { Switch(checked = settings.vibrate, onCheckedChange = { v -> onChange { it.copy(vibrate = v) } }) },
                onClick = { onChange { it.copy(vibrate = !it.vibrate) } },
            )
            SettingRow(stringResource(R.string.ascending_volume), ascendingLabel(settings.ascendingSeconds)) { picker = Picker.ASCENDING }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.section_ringing))
            SettingRow(stringResource(R.string.snooze_length), stringResource(R.string.n_minutes, settings.snoozeMinutes)) { picker = Picker.SNOOZE }
            SettingRow(stringResource(R.string.ring_duration), stringResource(R.string.n_minutes, settings.ringMinutes)) { picker = Picker.RING }
            SettingRow(stringResource(R.string.upcoming_notification), reminderLabel(settings.reminderMinutes)) { picker = Picker.REMINDER }
            SettingRow(stringResource(R.string.wake_check), wakeCheckLabel(settings.wakeCheckMinutes)) { picker = Picker.WAKE_CHECK }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.section_week))
            Text(
                stringResource(R.string.work_days_hint),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Column(Modifier.padding(16.dp)) {
                DayCircles(settings.workDays) { day ->
                    onChange { it.copy(workDays = if (day in it.workDays) it.workDays - day else it.workDays + day) }
                }
            }
            Text(
                stringResource(R.string.work_days_list, dayList(settings.workDays).ifEmpty { "—" }) + "\n" +
                    stringResource(R.string.off_days_list, dayList(DayOfWeek.entries.toSet() - settings.workDays).ifEmpty { "—" }),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(Modifier.padding(top = 16.dp))
            SectionTitle(stringResource(R.string.section_appearance))
            SettingRow(stringResource(R.string.theme), stringResource(settings.theme.nameRes())) { picker = Picker.THEME }
            SettingRow(
                stringResource(R.string.alarm_background),
                stringResource(if (settings.backgroundUri == null) R.string.background_default else R.string.background_picture),
            ) { picker = Picker.BACKGROUND }
            if (settings.backgroundUri != null) {
                SettingRow(stringResource(R.string.background_fit), stringResource(settings.backgroundFit.nameRes())) { picker = Picker.FIT }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.section_reliability))
            SettingRow(stringResource(R.string.battery_optimization), stringResource(R.string.battery_hint)) {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            SettingRow(stringResource(R.string.notification_settings), null) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    when (picker) {
        Picker.ASCENDING -> ChoiceDialog(
            stringResource(R.string.ascending_volume), ascendingOptions, settings.ascendingSeconds,
            label = { ascendingLabel(it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(ascendingSeconds = v) }; picker = null }
        Picker.SNOOZE -> ChoiceDialog(
            stringResource(R.string.snooze_length), snoozeOptions, settings.snoozeMinutes,
            label = { stringResource(R.string.n_minutes, it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(snoozeMinutes = v) }; picker = null }
        Picker.RING -> ChoiceDialog(
            stringResource(R.string.ring_duration), ringOptions, settings.ringMinutes,
            label = { stringResource(R.string.n_minutes, it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(ringMinutes = v) }; picker = null }
        Picker.REMINDER -> ChoiceDialog(
            stringResource(R.string.upcoming_notification), reminderOptions, settings.reminderMinutes,
            label = { reminderLabel(it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(reminderMinutes = v) }; picker = null }
        Picker.WAKE_CHECK -> ChoiceDialog(
            stringResource(R.string.wake_check), wakeCheckOptions, settings.wakeCheckMinutes,
            label = { wakeCheckLabel(it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(wakeCheckMinutes = v) }; picker = null }
        Picker.FIT -> ChoiceDialog(
            stringResource(R.string.background_fit), BackgroundFit.entries, settings.backgroundFit,
            label = { stringResource(it.nameRes()) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(backgroundFit = v) }; picker = null }
        Picker.THEME -> ChoiceDialog(
            stringResource(R.string.theme), ThemeMode.entries, settings.theme,
            label = { stringResource(it.nameRes()) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(theme = v) }; picker = null }
        Picker.BACKGROUND -> ChoiceDialog(
            stringResource(R.string.alarm_background), listOf(false, true), settings.backgroundUri != null,
            label = { stringResource(if (it) R.string.background_pick else R.string.background_default) },
            onDismiss = { picker = null },
        ) { pick ->
            picker = null
            if (pick) imageLauncher.launch(arrayOf("image/*")) else onChange { it.copy(backgroundUri = null) }
        }
        null -> {}
    }
}

@Composable
private fun ascendingLabel(seconds: Int) = when {
    seconds == 0 -> stringResource(R.string.off)
    seconds < 60 -> stringResource(R.string.over_n_seconds, seconds)
    else -> stringResource(R.string.over_n_minutes, seconds / 60)
}

@Composable
private fun reminderLabel(minutes: Int) =
    if (minutes == 0) stringResource(R.string.off) else stringResource(R.string.n_minutes_before, minutes)

@Composable
private fun wakeCheckLabel(minutes: Int) =
    if (minutes == 0) stringResource(R.string.off) else stringResource(R.string.within_n_minutes_before, minutes)

private fun BackgroundFit.nameRes(): Int = when (this) {
    BackgroundFit.FILL -> R.string.fit_fill
    BackgroundFit.FIT -> R.string.fit_whole
}
