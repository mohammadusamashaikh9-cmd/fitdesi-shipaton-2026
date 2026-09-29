package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.Calendar
import java.util.TimeZone

enum class AdvancedAnalyticsRange(val calendarDays: Int?) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    ALL(null)
}

data class AnalyticsDateWindow(
    val startDate: LocalCalendarDate,
    val endDateInclusive: LocalCalendarDate,
    val calendarDays: Int
)

data class FiniteAnalyticsWindows(
    val current: AnalyticsDateWindow,
    val previous: AnalyticsDateWindow
)

data class AdvancedDailyPoint(
    val date: LocalCalendarDate,
    val workoutSessions: Int,
    val recordedDurationSeconds: Long,
    val recordedCompletedSets: Int,
    val recordedLiftingVolumeKg: Double,
    val nutritionLogged: Boolean,
    val nutritionTotals: ProgressNutritionTotals?,
    val trainingTrendFraction: Float,
    val calorieTrendFraction: Float?
)

data class AdvancedNutritionSummary(
    val loggedDays: Int = 0,
    val eligibleElapsedDays: Int = 0,
    val consistencyRatio: Float = 0f,
    val averageLoggedCalories: Double? = null,
    val averageLoggedProteinGrams: Double? = null,
    val averageLoggedCarbsGrams: Double? = null,
    val averageLoggedFatGrams: Double? = null
)

data class MealAnalyticsDistribution(
    val mealType: String,
    val totals: ProgressNutritionTotals,
    val calorieShare: Float
)

data class AnalyticsMetricComparison(
    val current: Double,
    val previous: Double,
    val absoluteDifference: Double,
    val percentageChange: Double?
)

data class AdvancedPeriodComparison(
    val previousWindow: AnalyticsDateWindow,
    val workoutSessions: AnalyticsMetricComparison,
    val activeTrainingDays: AnalyticsMetricComparison,
    val recordedDurationSeconds: AnalyticsMetricComparison,
    val recordedCompletedSets: AnalyticsMetricComparison,
    val recordedLiftingVolumeKg: AnalyticsMetricComparison,
    val nutritionLoggedDays: AnalyticsMetricComparison,
    val averageLoggedCalories: AnalyticsMetricComparison?,
    val averageLoggedProteinGrams: AnalyticsMetricComparison?,
    val averageLoggedCarbsGrams: AnalyticsMetricComparison?,
    val averageLoggedFatGrams: AnalyticsMetricComparison?
)

data class NutritionDayTypeSummary(
    val nutritionCoverageDays: Int = 0,
    val averageLoggedCalories: Double? = null,
    val averageLoggedProteinGrams: Double? = null,
    val averageLoggedCarbsGrams: Double? = null,
    val averageLoggedFatGrams: Double? = null
)

data class TrainingRestNutritionComparison(
    val trainingDays: NutritionDayTypeSummary,
    val restDays: NutritionDayTypeSummary
)

data class CompletedWeeklyReview(
    val weekStartMonday: LocalCalendarDate,
    val weekEndSunday: LocalCalendarDate,
    val workoutTotals: ProgressWorkoutTotals,
    val nutritionSummary: AdvancedNutritionSummary
)

data class AdvancedProgressAnalytics(
    val range: AdvancedAnalyticsRange,
    val currentWindow: AnalyticsDateWindow,
    val workoutTotals: ProgressWorkoutTotals,
    val activeTrainingDaysPerWeek: Double,
    val nutritionSummary: AdvancedNutritionSummary,
    val dailyTrend: List<AdvancedDailyPoint>,
    val mealDistribution: List<MealAnalyticsDistribution>,
    val previousPeriodComparison: AdvancedPeriodComparison?,
    val trainingRestComparison: TrainingRestNutritionComparison?,
    val weeklyReview: CompletedWeeklyReview
)

fun finiteAnalyticsWindows(
    range: AdvancedAnalyticsRange,
    today: LocalCalendarDate,
    timeZone: TimeZone
): FiniteAnalyticsWindows {
    val days = requireNotNull(range.calendarDays) { "ALL does not have a finite comparison window" }
    val currentStart = ProgressDatePolicy.minusCalendarDays(today, days - 1, timeZone)
    val previousEnd = ProgressDatePolicy.previousLocalDate(currentStart, timeZone)
    val previousStart = ProgressDatePolicy.minusCalendarDays(previousEnd, days - 1, timeZone)
    return FiniteAnalyticsWindows(
        current = AnalyticsDateWindow(currentStart, today, days),
        previous = AnalyticsDateWindow(previousStart, previousEnd, days)
    )
}

fun mostRecentlyCompletedWeek(
    today: LocalCalendarDate,
    timeZone: TimeZone
): AnalyticsDateWindow {
    val calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = ProgressDatePolicy.startOfLocalDateEpochMillis(today, timeZone)
    }
    val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val currentWeekMonday = ProgressDatePolicy.minusCalendarDays(today, daysSinceMonday, timeZone)
    val previousMonday = ProgressDatePolicy.minusCalendarDays(currentWeekMonday, 7, timeZone)
    return AnalyticsDateWindow(
        startDate = previousMonday,
        endDateInclusive = ProgressDatePolicy.previousLocalDate(currentWeekMonday, timeZone),
        calendarDays = 7
    )
}

fun advancedFiniteQueryRange(
    range: AdvancedAnalyticsRange,
    today: LocalCalendarDate,
    timeZone: TimeZone
): LocalDateEpochRange {
    val windows = finiteAnalyticsWindows(range, today, timeZone)
    val weeklyReview = mostRecentlyCompletedWeek(today, timeZone)
    return ProgressDatePolicy.inclusiveDateRange(
        startDate = minOf(windows.previous.startDate, weeklyReview.startDate),
        endDateInclusive = today,
        timeZone = timeZone
    )
}

fun calculateAdvancedProgressAnalytics(
    range: AdvancedAnalyticsRange,
    today: LocalCalendarDate,
    timeZone: TimeZone,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>
): AdvancedProgressAnalytics {
    val datedWorkouts = workoutLogs.map { it to ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .filter { (_, date) -> date <= today }
    val datedCalories = calorieLogs.map { it to ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .filter { (_, date) -> date <= today }
    val finiteWindows = range.calendarDays?.let { finiteAnalyticsWindows(range, today, timeZone) }
    val earliestPersistedDate = listOfNotNull(
        datedWorkouts.minOfOrNull { it.second },
        datedCalories.minOfOrNull { it.second }
    ).minOrNull() ?: today
    val currentWindow = finiteWindows?.current ?: AnalyticsDateWindow(
        startDate = earliestPersistedDate,
        endDateInclusive = today,
        calendarDays = calendarDateCount(earliestPersistedDate, today, timeZone)
    )
    val currentWorkouts = datedWorkouts.filter { (_, date) -> date in currentWindow.startDate..today }
        .map { it.first }
    val currentCalories = datedCalories.filter { (_, date) -> date in currentWindow.startDate..today }
        .map { it.first }
    val workoutTotals = calculateProgressWorkoutTotals(currentWorkouts, today, timeZone)
    val nutritionEligibleDays = if (range == AdvancedAnalyticsRange.ALL) {
        datedCalories.minOfOrNull { it.second }
            ?.let { calendarDateCount(it, today, timeZone) }
            ?: 0
    } else {
        currentWindow.calendarDays
    }
    val nutritionSummary = calculateAdvancedNutritionSummary(
        logs = currentCalories,
        eligibleElapsedDays = nutritionEligibleDays,
        timeZone = timeZone
    )
    val weekWindow = mostRecentlyCompletedWeek(today, timeZone)
    val weeklyWorkouts = datedWorkouts.filter { (_, date) -> date in weekWindow.startDate..weekWindow.endDateInclusive }
        .map { it.first }
    val weeklyCalories = datedCalories.filter { (_, date) -> date in weekWindow.startDate..weekWindow.endDateInclusive }
        .map { it.first }

    return AdvancedProgressAnalytics(
        range = range,
        currentWindow = currentWindow,
        workoutTotals = workoutTotals,
        activeTrainingDaysPerWeek = if (currentWindow.calendarDays > 0) {
            workoutTotals.activeTrainingDays.toDouble() * 7.0 / currentWindow.calendarDays
        } else {
            0.0
        },
        nutritionSummary = nutritionSummary,
        dailyTrend = buildAdvancedDailyTrend(
            currentWindow = currentWindow,
            workoutLogs = currentWorkouts,
            calorieLogs = currentCalories,
            includeEmptyDates = range != AdvancedAnalyticsRange.ALL,
            timeZone = timeZone
        ),
        mealDistribution = buildMealDistribution(currentCalories),
        previousPeriodComparison = finiteWindows?.let { windows ->
            buildPeriodComparison(
                currentWorkoutTotals = workoutTotals,
                currentNutritionSummary = nutritionSummary,
                previousWindow = windows.previous,
                datedWorkouts = datedWorkouts,
                datedCalories = datedCalories,
                timeZone = timeZone
            )
        },
        trainingRestComparison = finiteWindows?.let {
            buildTrainingRestComparison(
                window = currentWindow,
                workoutLogs = currentWorkouts,
                calorieLogs = currentCalories,
                timeZone = timeZone
            )
        },
        weeklyReview = CompletedWeeklyReview(
            weekStartMonday = weekWindow.startDate,
            weekEndSunday = weekWindow.endDateInclusive,
            workoutTotals = calculateProgressWorkoutTotals(
                weeklyWorkouts,
                weekWindow.endDateInclusive,
                timeZone
            ),
            nutritionSummary = calculateAdvancedNutritionSummary(
                logs = weeklyCalories,
                eligibleElapsedDays = 7,
                timeZone = timeZone
            )
        )
    )
}

private fun buildPeriodComparison(
    currentWorkoutTotals: ProgressWorkoutTotals,
    currentNutritionSummary: AdvancedNutritionSummary,
    previousWindow: AnalyticsDateWindow,
    datedWorkouts: List<Pair<WorkoutLog, LocalCalendarDate>>,
    datedCalories: List<Pair<CalorieLog, LocalCalendarDate>>,
    timeZone: TimeZone
): AdvancedPeriodComparison {
    val previousWorkouts = datedWorkouts.filter { (_, date) -> date in previousWindow.startDate..previousWindow.endDateInclusive }
        .map { it.first }
    val previousCalories = datedCalories.filter { (_, date) -> date in previousWindow.startDate..previousWindow.endDateInclusive }
        .map { it.first }
    val previousWorkoutTotals = calculateProgressWorkoutTotals(
        previousWorkouts,
        previousWindow.endDateInclusive,
        timeZone
    )
    val previousNutrition = calculateAdvancedNutritionSummary(
        previousCalories,
        previousWindow.calendarDays,
        timeZone
    )
    return AdvancedPeriodComparison(
        previousWindow = previousWindow,
        workoutSessions = metricComparison(
            currentWorkoutTotals.sessionCount.toDouble(),
            previousWorkoutTotals.sessionCount.toDouble()
        ),
        activeTrainingDays = metricComparison(
            currentWorkoutTotals.activeTrainingDays.toDouble(),
            previousWorkoutTotals.activeTrainingDays.toDouble()
        ),
        recordedDurationSeconds = metricComparison(
            currentWorkoutTotals.recordedDurationSeconds.toDouble(),
            previousWorkoutTotals.recordedDurationSeconds.toDouble()
        ),
        recordedCompletedSets = metricComparison(
            currentWorkoutTotals.recordedCompletedSets.toDouble(),
            previousWorkoutTotals.recordedCompletedSets.toDouble()
        ),
        recordedLiftingVolumeKg = metricComparison(
            currentWorkoutTotals.recordedLiftingVolumeKg,
            previousWorkoutTotals.recordedLiftingVolumeKg
        ),
        nutritionLoggedDays = metricComparison(
            currentNutritionSummary.loggedDays.toDouble(),
            previousNutrition.loggedDays.toDouble()
        ),
        averageLoggedCalories = nullableMetricComparison(
            currentNutritionSummary.averageLoggedCalories,
            previousNutrition.averageLoggedCalories
        ),
        averageLoggedProteinGrams = nullableMetricComparison(
            currentNutritionSummary.averageLoggedProteinGrams,
            previousNutrition.averageLoggedProteinGrams
        ),
        averageLoggedCarbsGrams = nullableMetricComparison(
            currentNutritionSummary.averageLoggedCarbsGrams,
            previousNutrition.averageLoggedCarbsGrams
        ),
        averageLoggedFatGrams = nullableMetricComparison(
            currentNutritionSummary.averageLoggedFatGrams,
            previousNutrition.averageLoggedFatGrams
        )
    )
}

private fun calculateAdvancedNutritionSummary(
    logs: List<CalorieLog>,
    eligibleElapsedDays: Int,
    timeZone: TimeZone
): AdvancedNutritionSummary {
    val loggedDates = logs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    if (loggedDates.isEmpty()) {
        return AdvancedNutritionSummary(eligibleElapsedDays = eligibleElapsedDays)
    }
    val dailyTotals = loggedDates.values.map(::calculateProgressNutritionTotals)
    return AdvancedNutritionSummary(
        loggedDays = dailyTotals.size,
        eligibleElapsedDays = eligibleElapsedDays,
        consistencyRatio = if (eligibleElapsedDays > 0) {
            (dailyTotals.size.toFloat() / eligibleElapsedDays).coerceIn(0f, 1f)
        } else {
            0f
        },
        averageLoggedCalories = dailyTotals.map { it.calories }.average(),
        averageLoggedProteinGrams = dailyTotals.map { it.loggedProteinGrams.toDouble() }.average(),
        averageLoggedCarbsGrams = dailyTotals.map { it.loggedCarbsGrams.toDouble() }.average(),
        averageLoggedFatGrams = dailyTotals.map { it.loggedFatGrams.toDouble() }.average()
    )
}

private fun buildTrainingRestComparison(
    window: AnalyticsDateWindow,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>,
    timeZone: TimeZone
): TrainingRestNutritionComparison {
    val trainingDates = workoutLogs.mapTo(mutableSetOf()) {
        ProgressDatePolicy.localDateAt(it.timestamp, timeZone)
    }
    val nutritionByDate = calorieLogs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val elapsedDates = localDates(window.startDate, window.endDateInclusive, timeZone)
    val trainingNutrition = elapsedDates.filter { it in trainingDates }.flatMap { nutritionByDate[it].orEmpty() }
    val restNutrition = elapsedDates.filter { it !in trainingDates }.flatMap { nutritionByDate[it].orEmpty() }
    return TrainingRestNutritionComparison(
        trainingDays = dayTypeSummary(trainingNutrition, timeZone),
        restDays = dayTypeSummary(restNutrition, timeZone)
    )
}

private fun dayTypeSummary(logs: List<CalorieLog>, timeZone: TimeZone): NutritionDayTypeSummary {
    val summary = calculateAdvancedNutritionSummary(logs, eligibleElapsedDays = 0, timeZone = timeZone)
    return NutritionDayTypeSummary(
        nutritionCoverageDays = summary.loggedDays,
        averageLoggedCalories = summary.averageLoggedCalories,
        averageLoggedProteinGrams = summary.averageLoggedProteinGrams,
        averageLoggedCarbsGrams = summary.averageLoggedCarbsGrams,
        averageLoggedFatGrams = summary.averageLoggedFatGrams
    )
}

private fun buildAdvancedDailyTrend(
    currentWindow: AnalyticsDateWindow,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>,
    includeEmptyDates: Boolean,
    timeZone: TimeZone
): List<AdvancedDailyPoint> {
    val workoutsByDate = workoutLogs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val caloriesByDate = calorieLogs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val dates = if (includeEmptyDates) {
        localDates(currentWindow.startDate, currentWindow.endDateInclusive, timeZone)
    } else {
        (workoutsByDate.keys + caloriesByDate.keys).distinct().sorted()
    }
    val rawPoints = dates.map { date ->
        val workouts = workoutsByDate[date].orEmpty()
        val calories = caloriesByDate[date].orEmpty()
        val workoutTotals = calculateProgressWorkoutTotals(workouts, date, timeZone)
        RawDailyPoint(
            date = date,
            workoutTotals = workoutTotals,
            nutritionTotals = calories.takeIf { it.isNotEmpty() }?.let(::calculateProgressNutritionTotals)
        )
    }
    val maximumWorkoutSessions = rawPoints.maxOfOrNull {
        it.workoutTotals.sessionCount
    } ?: 0
    val maximumCalories = rawPoints.mapNotNull { it.nutritionTotals?.calories }.maxOrNull() ?: 0
    return rawPoints.map { point ->
        AdvancedDailyPoint(
            date = point.date,
            workoutSessions = point.workoutTotals.sessionCount,
            recordedDurationSeconds = point.workoutTotals.recordedDurationSeconds,
            recordedCompletedSets = point.workoutTotals.recordedCompletedSets,
            recordedLiftingVolumeKg = point.workoutTotals.recordedLiftingVolumeKg,
            nutritionLogged = point.nutritionTotals != null,
            nutritionTotals = point.nutritionTotals,
            trainingTrendFraction = normalizedFraction(
                point.workoutTotals.sessionCount.toDouble(),
                maximumWorkoutSessions.toDouble()
            ),
            calorieTrendFraction = point.nutritionTotals?.let {
                normalizedFraction(it.calories.toDouble(), maximumCalories.toDouble())
            }
        )
    }
}

private fun buildMealDistribution(logs: List<CalorieLog>): List<MealAnalyticsDistribution> {
    val totalCalories = calculateProgressNutritionTotals(logs).calories
    return logs.groupBy { it.mealType }
        .map { (mealType, mealLogs) ->
            val totals = calculateProgressNutritionTotals(mealLogs)
            MealAnalyticsDistribution(
                mealType = mealType,
                totals = totals,
                calorieShare = if (totalCalories > 0) {
                    (totals.calories.toFloat() / totalCalories).coerceIn(0f, 1f)
                } else {
                    0f
                }
            )
        }
        .sortedByDescending { it.totals.calories }
}

private fun metricComparison(current: Double, previous: Double): AnalyticsMetricComparison =
    AnalyticsMetricComparison(
        current = current,
        previous = previous,
        absoluteDifference = current - previous,
        percentageChange = if (previous != 0.0) ((current - previous) / previous) * 100.0 else null
    )

private fun nullableMetricComparison(
    current: Double?,
    previous: Double?
): AnalyticsMetricComparison? = if (current != null && previous != null) {
    metricComparison(current, previous)
} else {
    null
}

private fun normalizedFraction(value: Double, maximum: Double): Float =
    if (maximum > 0.0) (value / maximum).toFloat().coerceIn(0f, 1f) else 0f

private fun calendarDateCount(
    startDate: LocalCalendarDate,
    endDateInclusive: LocalCalendarDate,
    timeZone: TimeZone
): Int {
    if (startDate > endDateInclusive) return 0
    var count = 0
    var date = startDate
    while (date <= endDateInclusive) {
        count += 1
        if (date == endDateInclusive) break
        date = ProgressDatePolicy.plusCalendarDays(date, 1, timeZone)
    }
    return count
}

private fun localDates(
    startDate: LocalCalendarDate,
    endDateInclusive: LocalCalendarDate,
    timeZone: TimeZone
): List<LocalCalendarDate> {
    if (startDate > endDateInclusive) return emptyList()
    val dates = mutableListOf<LocalCalendarDate>()
    var date = startDate
    while (date <= endDateInclusive) {
        dates += date
        if (date == endDateInclusive) break
        date = ProgressDatePolicy.plusCalendarDays(date, 1, timeZone)
    }
    return dates
}

private data class RawDailyPoint(
    val date: LocalCalendarDate,
    val workoutTotals: ProgressWorkoutTotals,
    val nutritionTotals: ProgressNutritionTotals?
)
