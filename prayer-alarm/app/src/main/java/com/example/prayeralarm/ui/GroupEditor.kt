package com.example.prayeralarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.Anchor
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.Repeat
import com.example.prayeralarm.data.ScheduleContext
import com.example.prayeralarm.prayer.Prayer
import java.time.LocalTime
import java.time.ZonedDateTime

private val intervalOptions = listOf(1, 2, 3, 5, 10, 15, 20, 30)
private const val MAX_ALARMS = 10
private const val OFFSET_STEP = 5
private val offsetRange = -60..180

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditor(
    initial: AlarmGroup,
    isNew: Boolean,
    ctx: ScheduleContext,
    appSettings: AppSettings,
    onSave: (AlarmGroup) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var choosingPrayer by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.new_alarm else R.string.edit_alarm)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Button(
                        onClick = { onSave(draft.copy(enabled = true, skipUntil = 0L)) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                draft.label, { draft = draft.copy(label = it) },
                label = { Text(stringResource(R.string.label)) },
                placeholder = { Text(stringResource(R.string.label_hint)) },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            // Start time: fixed, or relative to a prayer.
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.start_time), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.anchor is Anchor.Fixed,
                            onClick = { if (draft.anchor !is Anchor.Fixed) draft = draft.copy(anchor = Anchor.Fixed(7, 0)) },
                            label = { Text(stringResource(R.string.fixed_time)) },
                        )
                        FilterChip(
                            selected = draft.anchor is Anchor.AfterPrayer,
                            onClick = {
                                if (draft.anchor !is Anchor.AfterPrayer) draft = draft.copy(anchor = Anchor.AfterPrayer(Prayer.FAJR, 5))
                            },
                            label = { Text(stringResource(R.string.prayer_based)) },
                        )
                    }
                    when (val anchor = draft.anchor) {
                        is Anchor.Fixed -> Text(
                            LocalTime.of(anchor.hour, anchor.minute).format(timeFormat),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                showTimePicker(context, anchor.hour, anchor.minute) { h, m ->
                                    draft = draft.copy(anchor = Anchor.Fixed(h, m))
                                }
                            },
                        )
                        is Anchor.AfterPrayer -> {
                            OutlinedButton(onClick = { choosingPrayer = true }) { Text(stringResource(anchor.prayer.nameRes())) }
                            Stepper(
                                prayerOffsetText(anchor),
                                onMinus = (anchor.offsetMinutes - OFFSET_STEP).takeIf { it in offsetRange }?.let { v ->
                                    { draft = draft.copy(anchor = anchor.copy(offsetMinutes = v)) }
                                },
                                onPlus = (anchor.offsetMinutes + OFFSET_STEP).takeIf { it in offsetRange }?.let { v ->
                                    { draft = draft.copy(anchor = anchor.copy(offsetMinutes = v)) }
                                },
                            )
                            if (!ctx.prayers.hasLocation) {
                                Text(stringResource(R.string.needs_location), color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(stringResource(R.string.moves_with_prayer), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // How many alarms, and how far apart.
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.number_of_alarms), style = MaterialTheme.typography.titleSmall)
                    Stepper(
                        LocalContext.current.resources.getQuantityString(R.plurals.alarm_count, draft.count, draft.count),
                        onMinus = if (draft.count > 1) ({ draft = draft.copy(count = draft.count - 1) }) else null,
                        onPlus = if (draft.count < MAX_ALARMS) ({ draft = draft.copy(count = draft.count + 1) }) else null,
                    )
                    if (draft.count > 1) {
                        Text(stringResource(R.string.interval), style = MaterialTheme.typography.titleSmall)
                        val i = intervalOptions.indexOf(draft.intervalMinutes).coerceAtLeast(0)
                        Stepper(
                            stringResource(R.string.every_n_minutes, intervalOptions[i]),
                            onMinus = intervalOptions.getOrNull(i - 1)?.let { v -> { draft = draft.copy(intervalMinutes = v) } },
                            onPlus = intervalOptions.getOrNull(i + 1)?.let { v -> { draft = draft.copy(intervalMinutes = v) } },
                        )
                        val preview = remember(draft, ctx) { previewSession(draft, ctx) }
                        if (preview.isNotEmpty()) {
                            Text(
                                preview.joinToString("  ·  ") { it.format(timeFormat) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(stringResource(R.string.group_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Repeat.
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.repeat), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Repeat.entries.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().selectable(draft.repeat == r, onClick = { draft = draft.copy(repeat = r) })
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = draft.repeat == r, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(stringResource(r.nameRes()))
                                if (r == Repeat.WORK_DAYS || r == Repeat.OFF_DAYS) {
                                    Text(
                                        repeatText(draft.copy(repeat = r), appSettings),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (draft.repeat == Repeat.CUSTOM) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            DayCircles(draft.customDays) { day ->
                                draft = draft.copy(customDays = if (day in draft.customDays) draft.customDays - day else draft.customDays + day)
                            }
                        }
                    }
                    if (draft.repeat == Repeat.WORK_DAYS || draft.repeat == Repeat.OFF_DAYS) {
                        Text(
                            stringResource(R.string.work_days_in_settings), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Sound.
            AppCard(Modifier.fillMaxWidth()) {
                SoundRow(
                    title = stringResource(R.string.sound),
                    name = draft.soundName,
                    defaultName = stringResource(R.string.sound_default_from_settings),
                    uri = draft.soundUri,
                ) { uri, name -> draft = draft.copy(soundUri = uri, soundName = name ?: uri?.let { context.getString(R.string.custom_sound) }) }
            }

            if (!isNew) {
                HorizontalDivider()
                Button(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) { Text(stringResource(R.string.delete)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (choosingPrayer) {
        val anchor = draft.anchor as? Anchor.AfterPrayer
        ChoiceDialog(
            stringResource(R.string.prayer), Prayer.entries, anchor?.prayer,
            label = { stringResource(it.nameRes()) },
            onDismiss = { choosingPrayer = false },
        ) { p ->
            draft = draft.copy(anchor = Anchor.AfterPrayer(p, anchor?.offsetMinutes ?: 0))
            choosingPrayer = false
        }
    }
}

/** The next run of the group's alarms (ignoring on/off and skips), for the preview line. */
private fun previewSession(group: AlarmGroup, ctx: ScheduleContext): List<ZonedDateTime> {
    val now = ZonedDateTime.now()
    val g = group.copy(repeat = Repeat.DAILY)
    return (0L..1L).firstNotNullOfOrNull { d ->
        g.session(now.toLocalDate().plusDays(d), now.zone, ctx).takeIf { it.isNotEmpty() && it.last().isAfter(now) }
    } ?: g.session(now.toLocalDate(), now.zone, ctx)
}

fun Repeat.nameRes(): Int = when (this) {
    Repeat.ONCE -> R.string.once
    Repeat.DAILY -> R.string.every_day
    Repeat.WORK_DAYS -> R.string.work_days
    Repeat.OFF_DAYS -> R.string.off_days
    Repeat.CUSTOM -> R.string.custom_days
}
