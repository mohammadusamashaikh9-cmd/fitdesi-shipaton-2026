package com.example.ui

import com.example.data.WorkoutLog

internal fun WorkoutLog.recordedDurationSeconds(): Int =
    durationSeconds.takeIf { it > 0 } ?: durationMinutes.coerceAtLeast(0) * 60

internal fun WorkoutLog.formattedRecordedDuration(): String {
    val totalSeconds = recordedDurationSeconds()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
