package com.callrecorder.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Looks up a saved contact name for a phone number, when the READ_CONTACTS permission is held. */
object Contacts {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns the contact's display name for [number], or null if unknown / not permitted. */
    fun nameFor(context: Context, number: String?): String? {
        if (number.isNullOrBlank() || number == "unknown") return null
        if (!hasPermission(context)) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null // querying contacts can fail on revoked permission / locked device
        }
    }
}
