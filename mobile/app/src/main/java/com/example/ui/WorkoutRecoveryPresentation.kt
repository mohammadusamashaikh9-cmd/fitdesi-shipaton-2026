package com.example.ui

internal data class RecoverySpacingPreferencePresentation(
    val title: String,
    val supportingText: String,
)

internal fun recoverySpacingPreferencePresentation() = RecoverySpacingPreferencePresentation(
    title = "Recovery spacing preference",
    supportingText = "Your selected workout days stay unchanged; this preference guides the plan summary and does not rearrange schedule days.",
)

internal data class WorkoutCalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<WorkoutCalendarDate> {
    override fun compareTo(other: WorkoutCalendarDate): Int = comparisonKey().compareTo(other.comparisonKey())

    private fun comparisonKey(): Int = year * 10_000 + month * 100 + day
}

internal enum class WorkoutCalendarDayClassification {
    LOGGED_WORKOUT,
    PAST_NO_WORKOUT_LOGGED,
    TODAY_NO_WORKOUT_LOGGED,
    FUTURE_DATE,
}

internal fun classifyWorkoutCalendarDay(
    date: WorkoutCalendarDate,
    today: WorkoutCalendarDate,
    hasWorkoutLog: Boolean,
): WorkoutCalendarDayClassification = when {
    hasWorkoutLog -> WorkoutCalendarDayClassification.LOGGED_WORKOUT
    date < today -> WorkoutCalendarDayClassification.PAST_NO_WORKOUT_LOGGED
    date == today -> WorkoutCalendarDayClassification.TODAY_NO_WORKOUT_LOGGED
    else -> WorkoutCalendarDayClassification.FUTURE_DATE
}

internal fun workoutCalendarDayMessage(
    classification: WorkoutCalendarDayClassification,
    monthName: String,
    day: Int,
): String = when (classification) {
    WorkoutCalendarDayClassification.LOGGED_WORKOUT -> "Worked out on $monthName $day! Keep moving! ⚡"
    WorkoutCalendarDayClassification.PAST_NO_WORKOUT_LOGGED -> "$monthName $day - No workout logged"
    WorkoutCalendarDayClassification.TODAY_NO_WORKOUT_LOGGED -> "$monthName $day - No workout logged yet"
    WorkoutCalendarDayClassification.FUTURE_DATE -> "$monthName $day - Future date"
}
