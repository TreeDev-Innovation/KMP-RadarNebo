package com.gusak.lab.radarnebo.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

fun formatTime(instant: Instant): String {
    val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** Local wall-clock time with seconds, e.g. `23:14:05`, for an epoch-millisecond timestamp. */
fun formatClock(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return listOf(t.hour, t.minute, t.second).joinToString(":") { it.toString().padStart(2, '0') }
}

fun formatAgo(instant: Instant): String {
    val minutes = (Clock.System.now() - instant).inWholeMinutes
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ${minutes % 60} min ago"
    }
}

fun formatCoord(value: Double): String {
    val scaled = (value * 10_000).roundToLong()
    val whole = scaled / 10_000
    val fraction = (kotlin.math.abs(scaled) % 10_000).toString().padStart(4, '0')
    return "$whole.$fraction"
}
