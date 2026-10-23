package com.example.prayeralarm

import android.app.Application
import com.example.prayeralarm.data.Store

class PrayerAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.setDefault(Store(this).appSettings.language)
    }
}
