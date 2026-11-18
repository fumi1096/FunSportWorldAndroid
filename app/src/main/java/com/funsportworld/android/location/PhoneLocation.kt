package com.funsportworld.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Thin [LocationManager] wrapper used to pre-fill the campus anchor.
 *
 * No Google Play Services dependency: we first accept a recent last-known fix,
 * then request fresh updates from every enabled provider with an 8 s timeout.
 * Location is used only to fill the editable latitude/longitude fields; it is
 * never uploaded by the app itself.
 */
object PhoneLocation {
    val requestedPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasPermission(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun locate(
        context: Context,
        callback: (latitude: Double?, longitude: Double?, error: String?) -> Unit,
    ) {
        if (!hasPermission(context)) {
            callback(null, null, "尚未授予定位权限，可手动填写经纬度")
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            callback(null, null, "本机不支持定位，可手动填写经纬度")
            return
        }

        val providerCandidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).distinct().filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        val lastKnown = providerCandidates.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        val recentEnough = lastKnown != null &&
            System.currentTimeMillis() - lastKnown.time <= 10 * 60 * 1000L
        if (recentEnough && lastKnown != null) {
            callback(lastKnown.latitude, lastKnown.longitude, null)
            return
        }
        if (providerCandidates.isEmpty()) {
            if (lastKnown != null) {
                callback(lastKnown.latitude, lastKnown.longitude, null)
            } else {
                callback(null, null, "手机定位未开启，请打开系统定位或手动填写经纬度")
            }
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var listener: LocationListener? = null

        fun finish(latitude: Double?, longitude: Double?, error: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            listener?.let { active -> runCatching { manager.removeUpdates(active) } }
            callback(latitude, longitude, error)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(location.latitude, location.longitude, null)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        val activeListener = listener ?: return
        providerCandidates.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, activeListener, Looper.getMainLooper())
            }
        }
        handler.postDelayed({
            if (!finished) {
                if (lastKnown != null) {
                    finish(lastKnown.latitude, lastKnown.longitude, null)
                } else {
                    finish(null, null, "定位超时，请到空旷处重试或手动填写经纬度")
                }
            }
        }, 8000L)
    }
}
