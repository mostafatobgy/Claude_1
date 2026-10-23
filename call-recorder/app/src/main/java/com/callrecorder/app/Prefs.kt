package com.callrecorder.app

import android.content.Context

/** Tiny wrapper over SharedPreferences for the two flags the app keeps. */
object Prefs {
    private const val NAME = "callrecorder"
    private const val KEY_AUTO = "auto_record"
    private const val KEY_CONSENT = "consent_accepted"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoRecord(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)
    fun setAutoRecord(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()

    fun consentAccepted(context: Context): Boolean = prefs(context).getBoolean(KEY_CONSENT, false)
    fun setConsentAccepted(context: Context) =
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
}
