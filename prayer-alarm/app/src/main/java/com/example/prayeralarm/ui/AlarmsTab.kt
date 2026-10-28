package com.example.prayeralarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.prayeralarm.HijriDate
import com.example.prayeralarm.MainViewModel
import com.example.prayeralarm.R
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.Anchor
import com.example.prayeralarm.data.AppSettings
import com.example.prayeralarm.data.Repeat
import com.example.prayeralarm.data.ScheduleContext
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.FormatStyle
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AlarmsTab(groups: List<AlarmGroup>, ctx: ScheduleContext, appSettings: AppSettings, vm: MainViewModel, onEdit: (AlarmGroup) -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DateHeader(now.toLocalDate()) }
        if (groups.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_alarms), textAlign = TextAlign.Center)
            }
        }
        items(groups, key = { it.id }) { group ->
            GroupCard(
                group, ctx, appSettings, now,
                onClick = { onEdit(group) },
                onToggle = { toastNext(context, vm.setEnabled(group, it)) },
                onSkip = { vm.skipNext(group) },
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: AlarmGroup,
    ctx: ScheduleContext,
    appSettings: AppSettings,
    now: ZonedDateTime,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    val next = remember(group, ctx, now) { group.nextOccurrence(now, ctx) }
    // What the session looks like today (or its next day), for the "6:00 – 6:15" range.
    val session = remember(group, ctx, now) {
        (0L..7L).firstNotNullOfOrNull { d ->
            group.session(now.toLocalDate().plusDays(d), now.zone, ctx).takeIf { it.isNotEmpty() }
        }
    }
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(anchorTitle(group.anchor, session), style = MaterialTheme.typography.headlineMedium)
                    if (group.anchor is Anchor.AfterPrayer) {
                        Text(prayerOffsetText(group.anchor), color = MaterialTheme.colorScheme.primary)
                    }
                    if (group.label.isNotBlank()) Text(group.label, style = MaterialTheme.typography.titleMedium)
                }
                Switch(checked = group.enabled, onCheckedChange = onToggle)
            }
            if (group.count > 1) {
                Text(
                    stringResource(
                        R.string.group_summary, group.count, group.intervalMinutes,
                        session?.last()?.format(timeFormat) ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                repeatText(group, appSettings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (group.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (next == null) stringResource(R.string.wont_ring) else stringResource(R.string.next_ring, nextText(next.time, now)),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (next == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (next != null && group.repeat != Repeat.ONCE) {
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.skip_next)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun anchorTitle(anchor: Anchor, session: List<ZonedDateTime>?): String = when (anchor) {
    is Anchor.Fixed -> LocalTime.of(anchor.hour, anchor.minute).format(timeFormat)
    is Anchor.AfterPrayer -> session?.first()?.format(timeFormat) ?: stringResource(anchor.prayer.nameRes())
}

@Composable
fun prayerOffsetText(anchor: Anchor.AfterPrayer): String {
    val prayer = stringResource(anchor.prayer.nameRes())
    return when {
        anchor.offsetMinutes > 0 -> stringResource(R.string.minutes_after_prayer, anchor.offsetMinutes, prayer)
        anchor.offsetMinutes < 0 -> stringResource(R.string.minutes_before_prayer, -anchor.offsetMinutes, prayer)
        else -> stringResource(R.string.at_prayer, prayer)
    }
}

@Composable
fun repeatText(group: AlarmGroup, appSettings: AppSettings): String = when (group.repeat) {
    Repeat.ONCE -> stringResource(R.string.once)
    Repeat.DAILY -> stringResource(R.string.every_day)
    Repeat.WORK_DAYS -> stringResource(R.string.work_days_list, dayList(appSettings.workDays))
    Repeat.OFF_DAYS -> stringResource(
        R.string.off_days_list, dayList(java.time.DayOfWeek.entries.toSet() - appSettings.workDays),
    )
    Repeat.CUSTOM -> if (group.customDays.isEmpty()) stringResource(R.string.no_days) else dayList(group.customDays)
}

@Composable
private fun nextText(time: ZonedDateTime, now: ZonedDateTime): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
    val clock = time.format(timeFormat)
    return when (days) {
        0L -> stringResource(R.string.today_at, clock)
        1L -> stringResource(R.string.tomorrow_at, clock)
        else -> time.format(DateTimeFormatter.ofPattern("EEEE")) + " " + clock
    }
}

/** Today's Gregorian date with the Hijri (Umm al-Qura) date under it. */
@Composable
private fun DateHeader(date: LocalDate) {
    val locale = Locale.getDefault()
    val gregorian = remember(date, locale) { date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }
    val hijri = remember(date, locale) { HijriDate.long(date, locale) }
    AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(gregorian, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(hijri, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
