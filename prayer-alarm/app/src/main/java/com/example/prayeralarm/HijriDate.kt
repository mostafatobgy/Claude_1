package com.example.prayeralarm

import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Hijri (Umm al-Qura) dates, formatted by ICU so month names are right in Arabic and English. */
object HijriDate {
    private fun calendar(date: LocalDate, uLocale: ULocale) = IslamicCalendar(uLocale).apply {
        calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        timeInMillis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun uLocale(locale: Locale) = ULocale.forLocale(locale).setKeywordValue("calendar", "islamic-umalqura")

    /** Long form in [locale], e.g. "٤ جمادى الأولى ١٤٤٨ هـ" or "Jumada I 4, 1448 AH". */
    fun long(date: LocalDate, locale: Locale): String {
        val u = uLocale(locale)
        val cal = calendar(date, u)
        return DateFormat.getDateInstance(cal, DateFormat.LONG, u).format(cal.time)
    }

    /** Arabic with Western digits and no era, e.g. "14 جمادى الأولى 1448". */
    fun arabicShort(date: LocalDate): String {
        val u = uLocale(Locale("ar")).setKeywordValue("numbers", "latn")
        val cal = calendar(date, u)
        return SimpleDateFormat("d MMMM y", u).apply { calendar = cal }.format(cal.time)
    }
}
