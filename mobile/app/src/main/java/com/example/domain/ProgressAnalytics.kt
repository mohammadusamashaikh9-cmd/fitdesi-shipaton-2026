package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.TimeZone
import kotlin.math.roundToInt

data class ProgressNutritionTotals(
    val calories: Int = 0,
    val loggedProteinGrams: Float = 0f,
    val loggedCarbsGrams: Float = 0f,
    val loggedFatGrams: Float = 0f
)

data class ProgressWorkoutTotals(
    val sessionCount: Int = 0,
    val activeTrainingDays: Int = 0,
    val recentWindowStreak: Int = 0,
    val recordedDurationSeconds: Long = 0L,
    val recordedCompletedSets: Int = 0,
    val recordedLiftingVolumeKg: Double = 0.0
)

data class ProgressOverview(
    val workoutTotals: ProgressWorkoutTotals = ProgressWorkoutTotals(),
    val nutritionTotals: ProgressNutritionTotals = ProgressNutritionTotals(),
    val nutritionLoggedDays: Int = 0,
    val rangeDays: Int = 7
)

enum class JournalDayKind {
    TRAINING_ONLY,
    NUTRITION_ONLY,
    BOTH,
    NEITHER
}

data class JournalMealGroup(
    val mealType: String,
    val entries: List<CalorieLog>
)

data class ProgressJournalDay(
    val date: LocalCalendarDate,
    val kind: JournalDayKind,
    val workoutEntries: List<WorkoutLog>,
    val mealGroups: List<JournalMealGroup>,
    val workoutTotals: ProgressWorkoutTotals,
    val nutritionTotals: ProgressNutritionTotals
)

fun calculateProgressNutritionTotals(logs: List<CalorieLog>): ProgressNutritionTotals =
    ProgressNutritionTotals(
        calories = logs.sumOf(::progressCaloriesForEntry),
        loggedProteinGrams = logs.sumOf { (it.proteinGrams * it.servings).toDouble() }.toFloat(),
        loggedCarbsGrams = logs.sumOf { (it.carbsGrams * it.servings).toDouble() }.toFloat(),
        loggedFatGrams = logs.sumOf { (it.fatGrams * it.servings).toDouble() }.toFloat()
    )

fun progressCaloriesForEntry(log: CalorieLog): Int =
    (log.amount * log.servings).roundToInt()

fun calculateProgressOverview(
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>,
    startDate: LocalCalendarDate,
    today: LocalCalendarDate,
    rangeDays: Int,
    timeZone: TimeZone
): ProgressOverview {
    val workoutsInRange = workoutLogs.filter {
        ProgressDatePolicy.localDateAt(it.timestamp, timeZone) in startDate..today
    }
    val caloriesInRange = calorieLogs.filter {
        ProgressDatePolicy.localDateAt(it.timestamp, timeZone) in startDate..today
    }
    return ProgressOverview(
        workoutTotals = calculateProgressWorkoutTotals(workoutsInRange, today, timeZone).copy(
            recentWindowStreak = calculateCurrentWorkoutStreak(workoutLogs, today, timeZone)
        ),
        nutritionTotals = calculateProgressNutritionTotals(caloriesInRange),
        nutritionLoggedDays = caloriesInRange.asSequence()
            .map { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
            .distinct()
            .count(),
        rangeDays = rangeDays
    )
}

fun calculateProgressWorkoutTotals(
    logs: List<WorkoutLog>,
    today: LocalCalendarDate,
    timeZone: TimeZone
): ProgressWorkoutTotals {
    val currentLogs = logs.filter {
        ProgressDatePolicy.localDateAt(it.timestamp, timeZone) <= today
    }
    val activeDates = currentLogs.mapTo(linkedSetOf()) {
        ProgressDatePolicy.localDateAt(it.timestamp, timeZone)
    }
    return ProgressWorkoutTotals(
        sessionCount = currentLogs.size,
        activeTrainingDays = activeDates.size,
        recentWindowStreak = calculateCurrentWorkoutStreak(currentLogs, today, timeZone),
        recordedDurationSeconds = currentLogs.sumOf { workoutRecordedDurationSeconds(it) },
        recordedCompletedSets = currentLogs.sumOf { it.completedSets.coerceAtLeast(0) },
        recordedLiftingVolumeKg = currentLogs.sumOf { it.liftingVolumeKg.coerceAtLeast(0.0) }
    )
}

fun calculateCurrentWorkoutStreak(
    logs: List<WorkoutLog>,
    today: LocalCalendarDate,
    timeZone: TimeZone
): Int {
    val activeDates = logs.asSequence()
        .map { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .filter { it <= today }
        .toSet()
    if (activeDates.isEmpty()) return 0

    var date = when {
        today in activeDates -> today
        ProgressDatePolicy.previousLocalDate(today, timeZone) in activeDates ->
            ProgressDatePolicy.previousLocalDate(today, timeZone)
        else -> return 0
    }
    var streak = 0
    while (date in activeDates) {
        streak += 1
        date = ProgressDatePolicy.previousLocalDate(date, timeZone)
    }
    return streak
}

fun calculateCurrentWorkoutStreakFromTimestamps(
    timestamps: List<Long>,
    today: LocalCalendarDate,
    timeZone: TimeZone
): Int {
    val activeDates = timestamps.asSequence()
        .map { ProgressDatePolicy.localDateAt(it, timeZone) }
        .filter { it <= today }
        .toSet()
    if (activeDates.isEmpty()) return 0
    val yesterday = ProgressDatePolicy.previousLocalDate(today, timeZone)
    var date = when {
        today in activeDates -> today
        yesterday in activeDates -> yesterday
        else -> return 0
    }
    var streak = 0
    while (date in activeDates) {
        streak += 1
        date = ProgressDatePolicy.previousLocalDate(date, timeZone)
    }
    return streak
}

fun workoutRecordedDurationSeconds(log: WorkoutLog): Long = when {
    log.durationSeconds > 0 -> log.durationSeconds.toLong()
    else -> log.durationMinutes.coerceAtLeast(0).toLong() * 60L
}

fun buildProgressJournalDays(
    startDate: LocalCalendarDate,
    endDateInclusive: LocalCalendarDate,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>,
    today: LocalCalendarDate,
    timeZone: TimeZone
): List<ProgressJournalDay> {
    if (startDate > endDateInclusive || startDate > today) return emptyList()
    val actualEndDate = minOf(endDateInclusive, today)
    val workoutsByDate = workoutLogs
        .filter { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) <= today }
        .groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val caloriesByDate = calorieLogs
        .filter { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) <= today }
        .groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }

    val days = mutableListOf<ProgressJournalDay>()
    var date = actualEndDate
    while (date >= startDate) {
        val dayWorkouts = workoutsByDate[date].orEmpty()
        val dayCalories = caloriesByDate[date].orEmpty()
        val mealGroups = linkedMapOf<String, MutableList<CalorieLog>>()
        dayCalories.forEach { log -> mealGroups.getOrPut(log.mealType) { mutableListOf() }.add(log) }
        days += ProgressJournalDay(
            date = date,
            kind = when {
                dayWorkouts.isNotEmpty() && dayCalories.isNotEmpty() -> JournalDayKind.BOTH
                dayWorkouts.isNotEmpty() -> JournalDayKind.TRAINING_ONLY
                dayCalories.isNotEmpty() -> JournalDayKind.NUTRITION_ONLY
                else -> JournalDayKind.NEITHER
            },
            workoutEntries = dayWorkouts,
            mealGroups = mealGroups.map { (mealType, entries) -> JournalMealGroup(mealType, entries) },
            workoutTotals = calculateProgressWorkoutTotals(dayWorkouts, today, timeZone),
            nutritionTotals = calculateProgressNutritionTotals(dayCalories)
        )
        if (date == startDate) break
        date = ProgressDatePolicy.previousLocalDate(date, timeZone)
    }
    return days
}
