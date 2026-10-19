package com.example.prayeralarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.prayeralarm.alarm.AlarmScheduler
import com.example.prayeralarm.ui.AppTheme
import com.example.prayeralarm.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        // Cheap, and keeps alarms right after permission grants, travel, or a missed broadcast.
        viewModel.refresh()
        AlarmScheduler.rescheduleAll(this)
    }
}
