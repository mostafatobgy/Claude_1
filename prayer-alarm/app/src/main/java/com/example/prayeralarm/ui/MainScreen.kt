package com.example.prayeralarm.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.prayeralarm.MainViewModel
import com.example.prayeralarm.R
import com.example.prayeralarm.alarm.AlarmScheduler
import com.example.prayeralarm.alarm.AlarmService
import com.example.prayeralarm.data.Alarm
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val beforeOptions = listOf(0, 5, 10, 15, 20, 30)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var canExact by remember { mutableStateOf(true) }
    var canFullScreen by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        canExact = AlarmScheduler.canScheduleExact(context)
        canFullScreen = AlarmService.canUseFullScreen(context)
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (tab == 1) FloatingActionButton(onClick = {
                showTimePicker(context, LocalTime.now().hour, LocalTime.now().minute) { h, m ->
                    toastNext(context, vm.addAlarm(h, m))
                }
            }) { Icon(Icons.Default.Add, stringResource(R.string.add_alarm)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WarningCard(stringResource(R.string.warn_exact)) {
                    context.startActivity(packageIntent(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
            if (!canFullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WarningCard(stringResource(R.string.warn_full_screen)) {
                    context.startActivity(packageIntent(context, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.prayer_times)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.alarms)) })
            }
            when (tab) {
                0 -> PrayerTab(settings, vm)
                else -> AlarmsTab(alarms, vm)
            }
        }
    }
}

@Composable
private fun PrayerTab(settings: PrayerSettings, vm: MainViewModel) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }
    val today = now.toLocalDate()
    val times = remember(settings, today) { settings.timesFor(today, ZoneId.systemDefault()) }
    val nextPrayer = times.filterKeys { it != Prayer.SUNRISE }.filterValues { it.isAfter(now) }.minByOrNull { it.value }?.key

    var showManual by remember { mutableStateOf(false) }
    var showMethod by remember { mutableStateOf(false) }
    var showAsr by remember { mutableStateOf(false) }
    var editBefore by remember { mutableStateOf<Prayer?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            Toast.makeText(context, R.string.locating, Toast.LENGTH_SHORT).show()
            fetchLocation(context) { loc ->
                if (loc != null) vm.updateSettings { it.copy(latitude = loc.latitude, longitude = loc.longitude) }
                else Toast.makeText(context, R.string.location_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, R.string.location_denied, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (settings.latitude != null && settings.longitude != null)
                                String.format(Locale.US, "%.4f, %.4f", settings.latitude, settings.longitude)
                            else stringResource(R.string.no_location),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            locationLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                            )
                        }) { Text(stringResource(R.string.use_my_location)) }
                        TextButton(onClick = { showManual = true }) { Text(stringResource(R.string.enter_manually)) }
                    }
                }
            }
        }

        if (!settings.hasLocation) {
            item { Text(stringResource(R.string.set_location_hint), Modifier.padding(8.dp)) }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Prayer.entries.forEachIndexed { i, prayer ->
                            if (i > 0) HorizontalDivider()
                            PrayerRow(
                                prayer = prayer,
                                time = times[prayer],
                                isNext = prayer == nextPrayer,
                                enabled = settings.alarmFor(prayer).enabled,
                                minutesBefore = settings.alarmFor(prayer).minutesBefore,
                                onToggle = { on ->
                                    vm.updateSettings {
                                        it.copy(prayerAlarms = it.prayerAlarms + (prayer to it.alarmFor(prayer).copy(enabled = on)))
                                    }
                                },
                                onEditBefore = { editBefore = prayer },
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(stringResource(R.string.calculation_method), stringResource(settings.method.nameRes())) { showMethod = true }
                    HorizontalDivider()
                    SettingRow(stringResource(R.string.asr_method), stringResource(settings.asrMethod.nameRes())) { showAsr = true }
                }
            }
        }
    }

    if (showManual) ManualLocationDialog(settings, onDismiss = { showManual = false }) { lat, lng ->
        vm.updateSettings { it.copy(latitude = lat, longitude = lng) }
        showManual = false
    }
    if (showMethod) ChoiceDialog(
        stringResource(R.string.calculation_method), CalculationMethod.entries, settings.method,
        label = { stringResource(it.nameRes()) },
        onDismiss = { showMethod = false },
    ) { m -> vm.updateSettings { it.copy(method = m) }; showMethod = false }
    if (showAsr) ChoiceDialog(
        stringResource(R.string.asr_method), AsrMethod.entries, settings.asrMethod,
        label = { stringResource(it.nameRes()) },
        onDismiss = { showAsr = false },
    ) { a -> vm.updateSettings { it.copy(asrMethod = a) }; showAsr = false }
    editBefore?.let { prayer ->
        ChoiceDialog(
            stringResource(R.string.alert_for, stringResource(prayer.nameRes())), beforeOptions,
            settings.alarmFor(prayer).minutesBefore,
            label = { beforeLabel(it) },
            onDismiss = { editBefore = null },
        ) { minutes ->
            vm.updateSettings {
                it.copy(prayerAlarms = it.prayerAlarms + (prayer to it.alarmFor(prayer).copy(minutesBefore = minutes, enabled = true)))
            }
            editBefore = null
        }
    }
}

@Composable
private fun PrayerRow(
    prayer: Prayer,
    time: ZonedDateTime?,
    isNext: Boolean,
    enabled: Boolean,
    minutesBefore: Int,
    onToggle: (Boolean) -> Unit,
    onEditBefore: () -> Unit,
) {
    val highlight = if (isNext) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        Modifier.fillMaxWidth().background(highlight).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onEditBefore)) {
            Text(
                stringResource(prayer.nameRes()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isNext) FontWeight.Bold else null,
            )
            Text(
                if (enabled) beforeLabel(minutesBefore) else stringResource(R.string.alarm_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            time?.format(timeFormat) ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (isNext) FontWeight.Bold else null,
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun beforeLabel(minutes: Int) =
    if (minutes == 0) stringResource(R.string.at_prayer_time) else stringResource(R.string.minutes_before, minutes)

@Composable
private fun AlarmsTab(alarms: List<Alarm>, vm: MainViewModel) {
    val context = LocalContext.current
    var editingLabel by remember { mutableStateOf<Alarm?>(null) }

    if (alarms.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_alarms))
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm,
                    onChange = { updated -> vm.updateAlarm(updated)?.let { toastNext(context, it) } },
                    onEditTime = {
                        showTimePicker(context, alarm.hour, alarm.minute) { h, m ->
                            vm.updateAlarm(alarm.copy(hour = h, minute = m, enabled = true))?.let { toastNext(context, it) }
                        }
                    },
                    onEditLabel = { editingLabel = alarm },
                    onDelete = { vm.deleteAlarm(alarm) },
                )
            }
        }
    }

    editingLabel?.let { alarm ->
        var text by remember(alarm.id) { mutableStateOf(alarm.label) }
        AlertDialog(
            onDismissRequest = { editingLabel = null },
            title = { Text(stringResource(R.string.label)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.updateAlarm(alarm.copy(label = text.trim())); editingLabel = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { editingLabel = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onChange: (Alarm) -> Unit,
    onEditTime: () -> Unit,
    onEditLabel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        LocalTime.of(alarm.hour, alarm.minute).format(timeFormat),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.clickable(onClick = onEditTime),
                    )
                    Text(
                        alarm.label.ifBlank { stringResource(R.string.add_label) },
                        color = if (alarm.label.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable(onClick = onEditLabel),
                    )
                }
                Switch(checked = alarm.enabled, onCheckedChange = { onChange(alarm.copy(enabled = it)) })
            }
            Spacer(Modifier.padding(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    weekOrder().forEach { day ->
                        val selected = day in alarm.days
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    onChange(alarm.copy(days = if (selected) alarm.days - day else alarm.days + day, enabled = true))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
            Text(
                when (alarm.days.size) {
                    0 -> stringResource(R.string.once)
                    7 -> stringResource(R.string.every_day)
                    else -> weekOrder().filter { it in alarm.days }
                        .joinToString(if (Locale.getDefault().language == "ar") "، " else ", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Saturday-first in Arabic, matching the week in Egypt and most of the region; Monday-first otherwise. */
private fun weekOrder(): List<DayOfWeek> {
    val first = if (Locale.getDefault().language == "ar") DayOfWeek.SATURDAY else DayOfWeek.MONDAY
    return (0L until 7L).map { first.plus(it) }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WarningCard(text: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(stringResource(R.string.allow)) }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(option == selected, onClick = { onSelect(option) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ManualLocationDialog(settings: PrayerSettings, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var lat by remember { mutableStateOf(settings.latitude?.toString().orEmpty()) }
    var lng by remember { mutableStateOf(settings.longitude?.toString().orEmpty()) }
    val latValue = lat.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lngValue = lng.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_manually)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manual_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.latitude)) },
                    singleLine = true, keyboardOptions = decimal, isError = lat.isNotBlank() && latValue == null)
                OutlinedTextField(lng, { lng = it }, label = { Text(stringResource(R.string.longitude)) },
                    singleLine = true, keyboardOptions = decimal, isError = lng.isNotBlank() && lngValue == null)
            }
        },
        confirmButton = {
            TextButton(enabled = latValue != null && lngValue != null, onClick = { onSave(latValue!!, lngValue!!) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun showTimePicker(context: Context, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h, m) }, hour, minute, DateFormat.is24HourFormat(context)).show()
}

private fun toastNext(context: Context, at: ZonedDateTime) {
    val minutes = Duration.between(ZonedDateTime.now(), at).toMinutes() + 1
    val text = context.getString(R.string.rings_in, minutes / 60, minutes % 60)
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun packageIntent(context: Context, action: String) =
    Intent(action, Uri.parse("package:${context.packageName}"))

@SuppressLint("MissingPermission")
private fun fetchLocation(context: Context, onResult: (Location?) -> Unit) {
    val lm = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null) return onResult(last)
    val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return onResult(null)
    try {
        LocationManagerCompat.getCurrentLocation(
            lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(context),
        ) { onResult(it) }
    } catch (e: SecurityException) {
        onResult(null)
    }
}
