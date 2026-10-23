package com.example.prayeralarm.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.prayeralarm.R
import com.example.prayeralarm.data.ThemeMode
import com.example.prayeralarm.prayer.AsrMethod
import com.example.prayeralarm.prayer.CalculationMethod
import com.example.prayeralarm.prayer.Prayer

// Brand palette from the UI style guide: calm health greens, teal for highlights, pink for accents.
object Brand {
    val Primary = Color(0xFF21804B)
    val PrimaryDark = Color(0xFF07543F)
    val Secondary = Color(0xFF00A995)
    val Accent = Color(0xFFEC407A)
    val Mint = Color(0xFFA5D6A7)
    val Background = Color(0xFFF5F7FA)
    val Text = Color(0xFF172B3A)
    val TextMuted = Color(0xFF64748B)
    val Border = Color(0xFFDCE5EC)
    val GreenSoft = Color(0xFFE8F5EE)
    val PinkSoft = Color(0xFFFCE7EF)
    val BlueSoft = Color(0xFFE8F3FC)
}

private val LightColors = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    primaryContainer = Brand.GreenSoft,
    onPrimaryContainer = Brand.PrimaryDark,
    secondary = Brand.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F4F1),
    onSecondaryContainer = Color(0xFF00574C),
    tertiary = Brand.Accent,
    onTertiary = Color.White,
    tertiaryContainer = Brand.PinkSoft,
    onTertiaryContainer = Color(0xFF8A1C45),
    background = Brand.Background,
    onBackground = Brand.Text,
    surface = Color.White,
    onSurface = Brand.Text,
    surfaceVariant = Color(0xFFEEF3F6),
    onSurfaceVariant = Brand.TextMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFC3D0DA),
    outlineVariant = Color(0xFFE4EBF0),
    errorContainer = Brand.PinkSoft,
    onErrorContainer = Color(0xFF8A1C45),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FCF97),
    onPrimary = Color(0xFF07311F),
    primaryContainer = Color(0xFF123D2B),
    onPrimaryContainer = Color(0xFFB9EDCF),
    secondary = Color(0xFF4FD1C0),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF13332F),
    onSecondaryContainer = Color(0xFFBFEFE7),
    tertiary = Color(0xFFF48FB1),
    onTertiary = Color(0xFF4A0F25),
    tertiaryContainer = Color(0xFF4A1A2D),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF0F1A17),
    onBackground = Color(0xFFE6EEF2),
    surface = Color(0xFF172420),
    onSurface = Color(0xFFE6EEF2),
    surfaceVariant = Color(0xFF23332E),
    onSurfaceVariant = Color(0xFF9FB0BA),
    surfaceContainerLowest = Color(0xFF172420),
    surfaceContainerLow = Color(0xFF172420),
    surfaceContainer = Color(0xFF172420),
    surfaceContainerHigh = Color(0xFF1C2B26),
    surfaceContainerHighest = Color(0xFF1C2B26),
    outline = Color(0xFF3A4B46),
    outlineVariant = Color(0xFF2A3934),
    errorContainer = Color(0xFF4A1A2D),
    onErrorContainer = Color(0xFFFFD9E3),
)

// Tajawal for Arabic, Poppins for English, as the style guide suggests.
private val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_medium, FontWeight.SemiBold),
    Font(R.font.tajawal_bold, FontWeight.Bold),
)
private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private fun typography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.f(weight: FontWeight, size: Int? = null) =
        copy(fontFamily = family, fontWeight = weight, fontSize = size?.sp ?: fontSize)
    return Typography(
        displayLarge = base.displayLarge.f(FontWeight.Bold),
        displayMedium = base.displayMedium.f(FontWeight.Bold),
        displaySmall = base.displaySmall.f(FontWeight.SemiBold, 34),
        headlineLarge = base.headlineLarge.f(FontWeight.Bold),
        headlineMedium = base.headlineMedium.f(FontWeight.Bold, 28),
        headlineSmall = base.headlineSmall.f(FontWeight.Bold, 24),
        titleLarge = base.titleLarge.f(FontWeight.Bold, 24),
        titleMedium = base.titleMedium.f(FontWeight.SemiBold, 16),
        titleSmall = base.titleSmall.f(FontWeight.SemiBold, 15),
        bodyLarge = base.bodyLarge.f(FontWeight.Normal, 16),
        bodyMedium = base.bodyMedium.f(FontWeight.Normal, 14),
        bodySmall = base.bodySmall.f(FontWeight.Normal, 13),
        labelLarge = base.labelLarge.f(FontWeight.SemiBold, 15),
        labelMedium = base.labelMedium.f(FontWeight.Medium, 12),
        labelSmall = base.labelSmall.f(FontWeight.Medium, 11),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Rounded text fields (style guide: 22–26 px corners). */
val FieldShape = RoundedCornerShape(24.dp)

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = typography(if (arabic) Tajawal else Poppins),
        shapes = AppShapes,
        content = content,
    )
}

/** White card with 16 dp corners, a hairline border and a very soft shadow. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = color)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) {
        Card(onClick, modifier, shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, border = border, content = content)
    } else {
        Card(modifier, shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, border = border, content = content)
    }
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
