package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Locale-independent size, duration and rate text for user-facing log lines.
 * Same units and precision as the existing transfer progress messages.
 */
internal object HumanReadable {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(count: Long): String {
        val safe = count.coerceAtLeast(0L)
        var value = safe.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$safe B" else String.format(Locale.US, "%.2f %s", value, UNITS[unit])
    }

    fun duration(ms: Long): String {
        if (ms < 0L) return "unknown"
        if (ms < 1_000L) return "$ms ms"
        val totalSeconds = ms / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun rate(bytesPerSecond: Double): String =
        bytes(bytesPerSecond.toLong().coerceAtLeast(0L)) + "/s"
}
