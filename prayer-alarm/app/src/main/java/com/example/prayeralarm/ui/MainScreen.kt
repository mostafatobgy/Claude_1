package com.example.prayeralarm.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.prayeralarm.MainViewModel
import com.example.prayeralarm.R
import com.example.prayeralarm.alarm.AlarmScheduler
import com.example.prayeralarm.alarm.Notifications
import com.example.prayeralarm.data.AlarmGroup
import com.example.prayeralarm.data.PrayerSettings
import com.example.prayeralarm.data.ScheduleContext
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

private val beforeOptions = listOf(0, 5, 10, 15, 20, 30)

private enum class MainTab { ALARMS, PRAYERS, SETTINGS }

private sealed interface Screen {
    data object Main : Screen
    data class Edit(val group: AlarmGroup, val isNew: Boolean) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val ctx = remember(settings, appSettings) { ScheduleContext(appSettings.workDays, settings) }
    // Alarms open first and sit on the left in both Arabic (RTL) and English.
    var tab by rememberSaveable { mutableStateOf(MainTab.ALARMS) }
    val ltrOrder = listOf(MainTab.ALARMS, MainTab.PRAYERS, MainTab.SETTINGS)
    val tabs = if (LocalLayoutDirection.current == LayoutDirection.Rtl) ltrOrder.reversed() else ltrOrder
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }

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
        canFullScreen = Notifications.canUseFullScreen(context)
        onPauseOrDispose {}
    }

    (screen as? Screen.Edit)?.let { s ->
        GroupEditor(
            s.group, s.isNew, ctx, appSettings,
            onSave = { group ->
                toastNext(context, vm.saveGroup(group))
                screen = Screen.Main
            },
            onDelete = {
                vm.deleteGroup(s.group)
                screen = Screen.Main
            },
            onClose = { screen = Screen.Main },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (tab) {
                                MainTab.ALARMS -> R.string.app_name
                                MainTab.PRAYERS -> R.string.prayer_times
                                MainTab.SETTINGS -> R.string.settings
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    MainTab.ALARMS -> ImageVector.vectorResource(R.drawable.ic_alarm)
                                    MainTab.PRAYERS -> ImageVector.vectorResource(R.drawable.ic_mosque)
                                    MainTab.SETTINGS -> Icons.Outlined.Settings
                                },
                                null,
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (t) {
                                        MainTab.ALARMS -> R.string.alarms
                                        MainTab.PRAYERS -> R.string.prayer_times
                                        MainTab.SETTINGS -> R.string.settings
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == MainTab.ALARMS) ExtendedFloatingActionButton(
                onClick = { screen = Screen.Edit(AlarmGroup(vm.newGroupId()), isNew = true) },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.add_alarm)) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
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
            when (tab) {
                MainTab.ALARMS -> AlarmsTab(groups, ctx, appSettings, vm) { screen = Screen.Edit(it, isNew = false) }
                MainTab.PRAYERS -> PrayerTab(settings, vm)
                MainTab.SETTINGS -> SettingsContent(appSettings, onChange = vm::updateAppSettings)
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
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Outlined.LocationOn)
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
                AppCard(Modifier.fillMaxWidth()) {
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
            AppCard(Modifier.fillMaxWidth()) {
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
    val highlight = if (isNext) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
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
                    singleLine = true, keyboardOptions = decimal, shape = FieldShape, isError = lat.isNotBlank() && latValue == null)
                OutlinedTextField(lng, { lng = it }, label = { Text(stringResource(R.string.longitude)) },
                    singleLine = true, keyboardOptions = decimal, shape = FieldShape, isError = lng.isNotBlank() && lngValue == null)
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
