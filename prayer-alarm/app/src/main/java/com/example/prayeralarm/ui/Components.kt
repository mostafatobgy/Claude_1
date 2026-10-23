package com.example.prayeralarm.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.example.prayeralarm.R
import com.example.prayeralarm.data.Occurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// A getter, so a language change in Settings takes effect without restarting the app.
val timeFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Saturday-first in Arabic, matching the week in Egypt and most of the region; Monday-first otherwise. */
fun weekOrder(): List<DayOfWeek> {
    val first = if (Locale.getDefault().language == "ar") DayOfWeek.SATURDAY else DayOfWeek.MONDAY
    return (0L until 7L).map { first.plus(it) }
}

fun dayList(days: Set<DayOfWeek>): String = weekOrder().filter { it in days }
    .joinToString(if (Locale.getDefault().language == "ar") "، " else ", ") {
        it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

fun showTimePicker(context: Context, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h, m) }, hour, minute, DateFormat.is24HourFormat(context)).show()
}

fun toastNext(context: Context, next: Occurrence?) {
    val text = if (next == null) context.getString(R.string.wont_ring)
    else {
        val minutes = Duration.between(ZonedDateTime.now(), next.time).toMinutes() + 1
        if (minutes >= 24 * 60) context.getString(R.string.rings_in_days, minutes / (24 * 60), minutes / 60 % 24)
        else context.getString(R.string.rings_in, minutes / 60, minutes % 60)
    }
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

fun packageIntent(context: Context, action: String) = Intent(action, Uri.parse("package:${context.packageName}"))

fun Context.displayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

@Composable
fun DayCircles(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weekOrder().forEach { day ->
            val on = day in selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** "−  value  +" control. */
@Composable
fun Stepper(text: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onMinus?.invoke() }, enabled = onMinus != null) { Text("−") }
        Text(text, Modifier.width(150.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        FilledTonalIconButton(onClick = { onPlus?.invoke() }, enabled = onPlus != null) { Text("+") }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** Icon in a soft green circle, as in the style guide's settings rows. */
@Composable
fun RowIcon(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
}

@Composable
fun SettingRow(
    title: String,
    value: String?,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) trailing()
        else Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WarningCard(text: String, onClick: () -> Unit) {
    AppCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.allow), color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
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

private enum class SoundSource { DEFAULT, RINGTONE, FILE }

/**
 * A settings row for choosing a sound: the default, one of the phone's tones, or any audio file
 * (e.g. an adhan recording). Calls [onPicked] with (uri, name), or (null, null) for the default.
 */
@Composable
fun SoundRow(
    title: String,
    name: String?,
    defaultName: String,
    uri: String?,
    icon: ImageVector? = null,
    onPicked: (String?, String?) -> Unit,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } ?: return@rememberLauncherForActivityResult
        onPicked(picked.toString(), RingtoneManager.getRingtone(context, picked)?.getTitle(context))
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        // Keep access to the file after the app restarts, so it can still play at alarm time.
        runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        onPicked(picked.toString(), context.displayName(picked))
    }

    SettingRow(title, name ?: defaultName, icon = icon, onClick = { choosing = true })

    if (choosing) ChoiceDialog(
        title, SoundSource.entries, null,
        label = {
            when (it) {
                SoundSource.DEFAULT -> defaultName
                SoundSource.RINGTONE -> stringResource(R.string.sound_phone_tones)
                SoundSource.FILE -> stringResource(R.string.sound_file)
            }
        },
        onDismiss = { choosing = false },
    ) { source ->
        choosing = false
        when (source) {
            SoundSource.DEFAULT -> onPicked(null, null)
            SoundSource.RINGTONE -> ringtoneLauncher.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri?.let(Uri::parse))
            )
            SoundSource.FILE -> fileLauncher.launch(arrayOf("audio/*"))
        }
    }
}
