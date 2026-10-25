package com.example.prayeralarm.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AppLanguage
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.BackgroundFit
import com.example.prayeralarm.data.SideButtonAction
import com.example.prayeralarm.data.ThemeMode
import java.time.DayOfWeek

private val ascendingOptions = listOf(0, 15, 30, 60, 120)
private val snoozeOptions = listOf(5, 10, 15, 20, 30)
private val ringOptions = listOf(1, 3, 5, 10, 15)
private val reminderOptions = listOf(0, 5, 10, 15, 30, 60)
private val wakeCheckOptions = listOf(0, 30, 60, 90, 120)
private val countdownOptions = listOf(0, 15, 30, 45, 60)

private enum class Picker { ASCENDING, SNOOZE, RING, REMINDER, WAKE_CHECK, SIDE_BUTTONS, PRAYER_COUNTDOWN, LANGUAGE, THEME, BACKGROUND, FIT }

/** A titled group of settings rows on one card, rows separated by hairlines. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** The Settings tab. */
@Composable
fun SettingsContent(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<Picker?>(null) }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onChange { it.copy(backgroundUri = uri.toString()) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsGroup(stringResource(R.string.section_general)) {
            SettingRow(
                stringResource(R.string.language), languageLabel(settings.language),
                icon = ImageVector.vectorResource(R.drawable.ic_language),
            ) { picker = Picker.LANGUAGE }
            RowDivider()
            SettingRow(stringResource(R.string.theme), stringResource(settings.theme.nameRes()), icon = Icons.Outlined.Star) {
                picker = Picker.THEME
            }
        }

        SettingsGroup(stringResource(R.string.section_sound)) {
            SoundRow(
                stringResource(R.string.alarm_sound), settings.alarmSoundName,
                stringResource(R.string.sound_phone_default), settings.alarmSoundUri, icon = Icons.Outlined.PlayArrow,
            ) { uri, name -> onChange { it.copy(alarmSoundUri = uri, alarmSoundName = name ?: uri?.let { context.getString(R.string.custom_sound) }) } }
            RowDivider()
            SoundRow(
                stringResource(R.string.adhan_sound), settings.prayerSoundName,
                stringResource(R.string.sound_adhan_default), settings.prayerSoundUri,
                icon = ImageVector.vectorResource(R.drawable.ic_mosque),
            ) { uri, name -> onChange { it.copy(prayerSoundUri = uri, prayerSoundName = name ?: uri?.let { context.getString(R.string.custom_sound) }) } }
            RowDivider()
            SettingRow(
                stringResource(R.string.vibrate), null, icon = Icons.Outlined.Phone,
                trailing = { Switch(checked = settings.vibrate, onCheckedChange = { v -> onChange { it.copy(vibrate = v) } }) },
                onClick = { onChange { it.copy(vibrate = !it.vibrate) } },
            )
            RowDivider()
            SettingRow(stringResource(R.string.ascending_volume), ascendingLabel(settings.ascendingSeconds), icon = Icons.Outlined.KeyboardArrowUp) {
                picker = Picker.ASCENDING
            }
        }

        SettingsGroup(stringResource(R.string.section_ringing)) {
            SettingRow(stringResource(R.string.snooze_length), stringResource(R.string.n_minutes, settings.snoozeMinutes), icon = Icons.Outlined.Refresh) {
                picker = Picker.SNOOZE
            }
            RowDivider()
            SettingRow(stringResource(R.string.ring_duration), stringResource(R.string.n_minutes, settings.ringMinutes), icon = Icons.Outlined.Info) {
                picker = Picker.RING
            }
            RowDivider()
            SettingRow(stringResource(R.string.upcoming_notification), reminderLabel(settings.reminderMinutes), icon = Icons.Outlined.Notifications) {
                picker = Picker.REMINDER
            }
            RowDivider()
            SettingRow(stringResource(R.string.wake_check), wakeCheckLabel(settings.wakeCheckMinutes), icon = Icons.Outlined.Lock) {
                picker = Picker.WAKE_CHECK
            }
            RowDivider()
            SettingRow(stringResource(R.string.side_buttons), stringResource(settings.sideButtons.nameRes()), icon = Icons.Outlined.Phone) {
                picker = Picker.SIDE_BUTTONS
            }
        }

        SettingsGroup(stringResource(R.string.section_prayer)) {
            SettingRow(
                stringResource(R.string.prayer_countdown_setting), countdownLabel(settings.prayerCountdownMinutes),
                icon = ImageVector.vectorResource(R.drawable.ic_mosque),
            ) { picker = Picker.PRAYER_COUNTDOWN }
        }

        SettingsGroup(stringResource(R.string.section_week)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.work_days_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DayCircles(settings.workDays) { day ->
                    onChange { it.copy(workDays = if (day in it.workDays) it.workDays - day else it.workDays + day) }
                }
                Text(
                    stringResource(R.string.work_days_list, dayList(settings.workDays).ifEmpty { "—" }) + "\n" +
                        stringResource(R.string.off_days_list, dayList(DayOfWeek.entries.toSet() - settings.workDays).ifEmpty { "—" }),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SettingsGroup(stringResource(R.string.section_appearance)) {
            SettingRow(
                stringResource(R.string.alarm_background),
                stringResource(if (settings.backgroundUri == null) R.string.background_default else R.string.background_picture),
                icon = Icons.Outlined.AccountBox,
            ) { picker = Picker.BACKGROUND }
            if (settings.backgroundUri != null) {
                RowDivider()
                SettingRow(stringResource(R.string.background_fit), stringResource(settings.backgroundFit.nameRes()), icon = Icons.Outlined.DateRange) {
                    picker = Picker.FIT
                }
            }
        }

        SettingsGroup(stringResource(R.string.section_reliability)) {
            SettingRow(stringResource(R.string.battery_optimization), stringResource(R.string.battery_hint), icon = Icons.Outlined.Warning) {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            RowDivider()
            SettingRow(stringResource(R.string.notification_settings), null, icon = Icons.Outlined.Settings) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
        Spacer(Modifier.height(32.dp))
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
        Picker.SIDE_BUTTONS -> ChoiceDialog(
            stringResource(R.string.side_buttons), SideButtonAction.entries, settings.sideButtons,
            label = { stringResource(it.nameRes()) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(sideButtons = v) }; picker = null }
        Picker.PRAYER_COUNTDOWN -> ChoiceDialog(
            stringResource(R.string.prayer_countdown_setting), countdownOptions, settings.prayerCountdownMinutes,
            label = { countdownLabel(it) }, onDismiss = { picker = null },
        ) { v -> onChange { it.copy(prayerCountdownMinutes = v) }; picker = null }
        Picker.LANGUAGE -> ChoiceDialog(
            stringResource(R.string.language), AppLanguage.entries, settings.language,
            label = { languageLabel(it) }, onDismiss = { picker = null },
        ) { v -> picker = null; onChange { it.copy(language = v) } }
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

// Each language is written in itself, so it can be found whatever the current language is.
@Composable
private fun languageLabel(language: AppLanguage) = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ARABIC -> "العربية"
    AppLanguage.ENGLISH -> "English"
}

@Composable
private fun countdownLabel(minutes: Int) =
    if (minutes == 0) stringResource(R.string.off) else stringResource(R.string.prayer_countdown_value, minutes)

private fun SideButtonAction.nameRes(): Int = when (this) {
    SideButtonAction.NONE -> R.string.side_buttons_none
    SideButtonAction.SNOOZE -> R.string.side_buttons_snooze
    SideButtonAction.DISMISS -> R.string.side_buttons_dismiss
}
