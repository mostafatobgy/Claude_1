package com.example.prayeralarm.ui

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.prayeralarm.R
import com.example.prayeralarm.data.ThemeMode
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer

private val Green = Color(0xFF1B6B4A)

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8BD6AE))
        else -> lightColorScheme(primary = Green)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@StringRes
fun Prayer.nameRes(): Int = when (this) {
    Prayer.FAJR -> R.string.fajr
    Prayer.SUNRISE -> R.string.sunrise
    Prayer.DHUHR -> R.string.dhuhr
    Prayer.ASR -> R.string.asr
    Prayer.MAGHRIB -> R.string.maghrib
    Prayer.ISHA -> R.string.isha
}

fun Context.prayerName(prayer: Prayer): String = getString(prayer.nameRes())

@StringRes
fun CalculationMethod.nameRes(): Int = when (this) {
    CalculationMethod.EGYPT -> R.string.method_egypt
    CalculationMethod.MWL -> R.string.method_mwl
    CalculationMethod.MAKKAH -> R.string.method_makkah
    CalculationMethod.KARACHI -> R.string.method_karachi
    CalculationMethod.ISNA -> R.string.method_isna
    CalculationMethod.GULF -> R.string.method_gulf
}

@StringRes
fun AsrMethod.nameRes(): Int = when (this) {
    AsrMethod.STANDARD -> R.string.asr_standard
    AsrMethod.HANAFI -> R.string.asr_hanafi
}

@StringRes
fun ThemeMode.nameRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
