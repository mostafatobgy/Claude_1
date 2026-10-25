package com.example.prayeralarm

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlin.concurrent.thread

/** Finds the Arabic city name for coordinates. Needs internet; calls back with null if it can't. */
object CityLookup {
    fun find(context: Context, latitude: Double, longitude: Double, onResult: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        fun deliver(addresses: List<Address>?) {
            val a = addresses?.firstOrNull()
            val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            main.post { onResult(name) }
        }
        if (!Geocoder.isPresent()) return onResult(null)
        val geocoder = Geocoder(context, Locale("ar"))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = deliver(addresses)
                    override fun onError(errorMessage: String?) = deliver(null)
                })
            } else {
                thread {
                    @Suppress("DEPRECATION")
                    deliver(runCatching { geocoder.getFromLocation(latitude, longitude, 1) }.getOrNull())
                }
            }
        } catch (e: Exception) {
            onResult(null)
        }
    }
}
