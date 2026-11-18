package com.funsportworld.android.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val dateTime = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val fullDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA)

    fun dateTime(ms: Long): String = if (ms <= 0) "—" else dateTime.format(Date(ms))

    fun fullDateTime(ms: Long): String = if (ms <= 0) "—" else fullDateTime.format(Date(ms))

    fun time(ms: Long): String = if (ms <= 0) "—" else timeOnly.format(Date(ms))

    fun fileStamp(ms: Long = System.currentTimeMillis()): String = fileStamp.format(Date(ms))

    fun distance(meters: Double): String = when {
        meters <= 0 -> "—"
        meters < 1000 -> String.format(Locale.US, "%.0f m", meters)
        else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    fun duration(seconds: Int): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun pace(secondsPerKm: Double): String =
        if (secondsPerKm <= 0) "—" else String.format(Locale.US, "%.0f s/km", secondsPerKm)

    fun paceClock(secondsPerKm: Double): String {
        if (secondsPerKm <= 0) return "—"
        val total = secondsPerKm.toInt()
        return String.format(Locale.US, "%d'%02d\"", total / 60, total % 60)
    }

    fun cadence(value: Double): String =
        if (value <= 0) "—" else String.format(Locale.US, "%.0f spm", value)

    fun windowReason(ok: Boolean, reason: String): String =
        (if (ok) "✓ " else "⚠ ") + reason.ifBlank { if (ok) "在有效时段内" else "时间窗口未知" }

    fun relative(ms: Long): String {
        if (ms <= 0) return "从未"
        val delta = System.currentTimeMillis() - ms
        return when {
            delta < 60_000 -> "刚刚"
            delta < 3_600_000 -> "${delta / 60_000} 分钟前"
            delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
            delta < 7 * 86_400_000L -> "${delta / 86_400_000} 天前"
            else -> dateTime(ms)
        }
    }
}
