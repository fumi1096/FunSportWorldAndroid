package com.funsportworld.android.location

import android.content.Context

/**
 * Remembers whether the one-time "use phone location as the campus anchor"
 * setup already ran. Manual edits in Settings are never overwritten later.
 */
object LocationSetup {
    private const val PREFS = "funsport_location"
    private const val KEY_APPLIED = "initial_location_applied"

    fun wasApplied(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APPLIED, false)

    fun markApplied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APPLIED, true)
            .apply()
    }

    /** True when the anchor is still the desktop default and may be replaced by GPS. */
    fun isDesktopDefault(latitude: String, longitude: String): Boolean {
        val lat = latitude.trim()
        val lon = longitude.trim()
        if (lat.isBlank() || lon.isBlank()) return true
        return runCatching {
            val latValue = lat.toDouble()
            val lonValue = lon.toDouble()
            kotlin.math.abs(latValue - 38.901678) < 0.000001 &&
                kotlin.math.abs(lonValue - 121.540241) < 0.000001
        }.getOrDefault(false)
    }
}
