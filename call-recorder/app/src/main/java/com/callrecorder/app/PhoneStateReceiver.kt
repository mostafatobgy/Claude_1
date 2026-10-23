package com.callrecorder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Starts recording when a call becomes active (OFFHOOK) and stops when it ends (IDLE),
 * but only while the user has turned auto-recording on in the app.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs.autoRecord(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        // The number is present for incoming calls when READ_CALL_LOG is held. For outgoing calls
        // the system sends it via NEW_OUTGOING_CALL; here we record under the number when we have
        // it and "unknown" otherwise. The number is later matched to a saved contact name.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastNumber == null) lastNumber = number
                CallRecorderService.start(context, lastNumber)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallRecorderService.stop(context)
                lastNumber = null
            }
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (number != null) lastNumber = number
            }
        }
    }

    companion object {
        // Remembered across the RINGING -> OFFHOOK transition within one call.
        @Volatile
        private var lastNumber: String? = null
    }
}
