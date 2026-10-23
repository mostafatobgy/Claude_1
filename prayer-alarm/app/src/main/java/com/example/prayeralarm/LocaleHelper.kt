package com.example.prayeralarm

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.prayeralarm.data.AppLanguage
import com.example.prayeralarm.data.Store
import java.util.Locale

/**
 * Applies the language chosen in Settings, whatever the phone's language is.
 * Activities follow AppCompat's per-app locale; services and receivers (alarm labels,
 * notifications) get a context wrapped in the same locale via [wrap].
 */
object LocaleHelper {
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        )
        setDefault(language)
    }

    /** Makes dates, times and day names use the chosen language too. */
    fun setDefault(language: AppLanguage) {
        Locale.setDefault(language.tag?.let { Locale(it) } ?: Resources.getSystem().configuration.locales[0])
    }

    fun wrap(base: Context): Context {
        val tag = Store(base).appSettings.language.tag ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale(tag)) }
        return base.createConfigurationContext(config)
    }
}
