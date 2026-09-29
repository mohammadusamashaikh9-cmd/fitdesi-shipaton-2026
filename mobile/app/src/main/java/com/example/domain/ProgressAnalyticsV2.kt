package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.Calendar
import java.util.TimeZone

data class ProgressChartBucket(
    val startDate: LocalCalendarDate,
    val endDateInclusive: LocalCalendarDate,
    val label: String,
    val workoutSessions: Int,
    val recordedDurationSeconds: Long,
    val recordedCompletedSets: Int,
    val recordedLiftingVolumeKg: Double,
    val loggedDays: Int,
    val eligibleDays: Int,
    val averageLoggedCalories: Double?,
    val averageLoggedProteinGrams: Double?,
    val averageLoggedCarbsGrams: Double?,
    val averageLoggedFatGrams: Double?
)

data class ProgressAnalyticsV2(
    val selection: ProgressDateSelection,
    val currentWindow: AnalyticsDateWindow,
    val granularity: ProgressBucketGranularity,
    val workoutTotals: ProgressWorkoutTotals,
    val nutritionSummary: AdvancedNutritionSummary,
    val buckets: List<ProgressChartBucket>,
    val mealDistribution: List<MealAnalyticsDistribution>,
    val previousPeriodComparison: AdvancedPeriodComparison?,
    val trainingRestComparison: TrainingRestNutritionComparison?,
    val weeklyReview: CompletedWeeklyReview
)

fun analyticsQueryWindows(
    selection: ProgressDateSelection,
    today: LocalCalendarDate,
    timeZone: TimeZone
): List<ProgressDateSelection.Range> = when (selection) {
    ProgressDateSelection.AllHistory -> emptyList()
    is ProgressDateSelection.Range -> listOf(
        selection,
        ProgressDateSelectionPolicy.previousPeriod(selection, timeZone),
        mostRecentlyCompletedWeek(today, timeZone).let {
            ProgressDateSelection.Range(it.startDate, it.endDateInclusive)
        }
    )
}

fun calculateProgressAnalyticsV2(
    selection: ProgressDateSelection,
    today: LocalCalendarDate,
    timeZone: TimeZone,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>
): ProgressAnalyticsV2 {
    val datedWorkouts = workoutLogs.asSequence()
        .map { it to ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .filter { (_, date) -> date <= today }
        .toList()
    val datedCalories = calorieLogs.asSequence()
        .map { it to ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .filter { (_, date) -> date <= today }
        .toList()
    val earliestWorkoutDate = datedWorkouts.minOfOrNull { it.second }
    val earliestCalorieDate = datedCalories.minOfOrNull { it.second }
    val earliest = listOfNotNull(earliestWorkoutDate, earliestCalorieDate).minOrNull() ?: today
    val currentRange = when (selection) {
        ProgressDateSelection.AllHistory -> ProgressDateSelection.Range(earliest, today)
        is ProgressDateSelection.Range -> selection
    }
    val currentWindow = AnalyticsDateWindow(
        currentRange.startDate,
        currentRange.endDateInclusive,
        ProgressDateSelectionPolicy.inclusiveDayCount(currentRange, timeZone)
    )
    val currentWorkouts = datedWorkouts.filter { (_, date) -> date in currentRange.startDate..currentRange.endDateInclusive }
        .map { it.first }
    val currentCalories = datedCalories.filter { (_, date) -> date in currentRange.startDate..currentRange.endDateInclusive }
        .map { it.first }
    val workoutTotals = calculateProgressWorkoutTotals(currentWorkouts, currentRange.endDateInclusive, timeZone)
    val nutritionEligibleDays = when (selection) {
        ProgressDateSelection.AllHistory -> earliestCalorieDate?.let { firstCalorieDate ->
            ProgressDateSelectionPolicy.inclusiveDayCount(
                ProgressDateSelection.Range(firstCalorieDate, today),
                timeZone
            )
        } ?: 0
        is ProgressDateSelection.Range -> currentWindow.calendarDays
    }
    val nutritionSummary = progressNutritionSummary(currentCalories, nutritionEligibleDays, timeZone)
    val previousRange = (selection as? ProgressDateSelection.Range)?.let {
        ProgressDateSelectionPolicy.previousPeriod(it, timeZone)
    }
    val previousComparison = previousRange?.let { range ->
        val previousWorkouts = datedWorkouts.filter { (_, date) -> date in range.startDate..range.endDateInclusive }
            .map { it.first }
        val previousCalories = datedCalories.filter { (_, date) -> date in range.startDate..range.endDateInclusive }
            .map { it.first }
        val previousTotals = calculateProgressWorkoutTotals(previousWorkouts, range.endDateInclusive, timeZone)
        val previousNutrition = progressNutritionSummary(
            previousCalories,
            ProgressDateSelectionPolicy.inclusiveDayCount(range, timeZone),
            timeZone
        )
        AdvancedPeriodComparison(
            previousWindow = AnalyticsDateWindow(
                range.startDate,
                range.endDateInclusive,
                ProgressDateSelectionPolicy.inclusiveDayCount(range, timeZone)
            ),
            workoutSessions = comparison(workoutTotals.sessionCount.toDouble(), previousTotals.sessionCount.toDouble()),
            activeTrainingDays = comparison(workoutTotals.activeTrainingDays.toDouble(), previousTotals.activeTrainingDays.toDouble()),
            recordedDurationSeconds = comparison(workoutTotals.recordedDurationSeconds.toDouble(), previousTotals.recordedDurationSeconds.toDouble()),
            recordedCompletedSets = comparison(workoutTotals.recordedCompletedSets.toDouble(), previousTotals.recordedCompletedSets.toDouble()),
            recordedLiftingVolumeKg = comparison(workoutTotals.recordedLiftingVolumeKg, previousTotals.recordedLiftingVolumeKg),
            nutritionLoggedDays = comparison(nutritionSummary.loggedDays.toDouble(), previousNutrition.loggedDays.toDouble()),
            averageLoggedCalories = nullableComparison(nutritionSummary.averageLoggedCalories, previousNutrition.averageLoggedCalories),
            averageLoggedProteinGrams = nullableComparison(nutritionSummary.averageLoggedProteinGrams, previousNutrition.averageLoggedProteinGrams),
            averageLoggedCarbsGrams = nullableComparison(nutritionSummary.averageLoggedCarbsGrams, previousNutrition.averageLoggedCarbsGrams),
            averageLoggedFatGrams = nullableComparison(nutritionSummary.averageLoggedFatGrams, previousNutrition.averageLoggedFatGrams)
        )
    }
    val completedWeek = mostRecentlyCompletedWeek(today, timeZone)
    val weeklyWorkouts = datedWorkouts.filter { (_, date) -> date in completedWeek.startDate..completedWeek.endDateInclusive }
        .map { it.first }
    val weeklyCalories = datedCalories.filter { (_, date) -> date in completedWeek.startDate..completedWeek.endDateInclusive }
        .map { it.first }

    return ProgressAnalyticsV2(
        selection = selection,
        currentWindow = currentWindow,
        granularity = ProgressDateSelectionPolicy.bucketGranularity(selection, timeZone),
        workoutTotals = workoutTotals,
        nutritionSummary = nutritionSummary,
        buckets = buildProgressBuckets(
            selection = selection,
            currentRange = currentRange,
            workoutLogs = currentWorkouts,
            calorieLogs = currentCalories,
            timeZone = timeZone
        ),
        mealDistribution = progressMealDistribution(currentCalories),
        previousPeriodComparison = previousComparison,
        trainingRestComparison = when (selection) {
            ProgressDateSelection.AllHistory -> null
            is ProgressDateSelection.Range -> buildProgressTrainingRest(
                currentRange,
                currentWorkouts,
                currentCalories,
                timeZone
            )
        },
        weeklyReview = CompletedWeeklyReview(
            weekStartMonday = completedWeek.startDate,
            weekEndSunday = completedWeek.endDateInclusive,
            workoutTotals = calculateProgressWorkoutTotals(weeklyWorkouts, completedWeek.endDateInclusive, timeZone),
            nutritionSummary = progressNutritionSummary(weeklyCalories, 7, timeZone)
        )
    )
}

private fun buildProgressBuckets(
    selection: ProgressDateSelection,
    currentRange: ProgressDateSelection.Range,
    workoutLogs: List<WorkoutLog>,
    calorieLogs: List<CalorieLog>,
    timeZone: TimeZone
): List<ProgressChartBucket> {
    val granularity = ProgressDateSelectionPolicy.bucketGranularity(selection, timeZone)
    val workoutsByDate = workoutLogs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val caloriesByDate = calorieLogs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val bucketRanges = when (granularity) {
        ProgressBucketGranularity.DAY -> localDateSequence(currentRange, timeZone).map {
            ProgressDateSelection.Range(it, it)
        }
        ProgressBucketGranularity.WEEK -> groupedRanges(currentRange, timeZone, ProgressBucketGranularity.WEEK)
        ProgressBucketGranularity.MONTH -> groupedRanges(currentRange, timeZone, ProgressBucketGranularity.MONTH)
    }
    return bucketRanges.map { range ->
        val workouts = workoutsByDate.filterKeys { it in range.startDate..range.endDateInclusive }.values.flatten()
        val calories = caloriesByDate.filterKeys { it in range.startDate..range.endDateInclusive }.values.flatten()
        val totals = calculateProgressWorkoutTotals(workouts, range.endDateInclusive, timeZone)
        val eligibleDays = ProgressDateSelectionPolicy.inclusiveDayCount(range, timeZone)
        val nutrition = progressNutritionSummary(calories, eligibleDays, timeZone)
        ProgressChartBucket(
            startDate = range.startDate,
            endDateInclusive = range.endDateInclusive,
            label = bucketLabel(range, granularity),
            workoutSessions = totals.sessionCount,
            recordedDurationSeconds = totals.recordedDurationSeconds,
            recordedCompletedSets = totals.recordedCompletedSets,
            recordedLiftingVolumeKg = totals.recordedLiftingVolumeKg,
            loggedDays = nutrition.loggedDays,
            eligibleDays = eligibleDays,
            averageLoggedCalories = nutrition.averageLoggedCalories,
            averageLoggedProteinGrams = nutrition.averageLoggedProteinGrams,
            averageLoggedCarbsGrams = nutrition.averageLoggedCarbsGrams,
            averageLoggedFatGrams = nutrition.averageLoggedFatGrams
        )
    }
}

private fun groupedRanges(
    selection: ProgressDateSelection.Range,
    timeZone: TimeZone,
    granularity: ProgressBucketGranularity
): List<ProgressDateSelection.Range> {
    val groups = localDateSequence(selection, timeZone).groupBy { date ->
        when (granularity) {
            ProgressBucketGranularity.WEEK -> startOfWeek(date, timeZone)
            ProgressBucketGranularity.MONTH -> LocalCalendarDate(date.year, date.month, 1)
            else -> date
        }
    }
    return groups.values.map { dates -> ProgressDateSelection.Range(dates.first(), dates.last()) }
}

private fun localDateSequence(
    range: ProgressDateSelection.Range,
    timeZone: TimeZone
): List<LocalCalendarDate> {
    val dates = ArrayList<LocalCalendarDate>()
    var cursor = range.startDate
    while (cursor <= range.endDateInclusive) {
        dates += cursor
        if (cursor == range.endDateInclusive) break
        cursor = ProgressDatePolicy.plusCalendarDays(cursor, 1, timeZone)
    }
    return dates
}

private fun startOfWeek(date: LocalCalendarDate, timeZone: TimeZone): LocalCalendarDate {
    val calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = ProgressDatePolicy.startOfLocalDateEpochMillis(date, timeZone)
    }
    val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    return ProgressDatePolicy.minusCalendarDays(date, daysSinceMonday, timeZone)
}

private fun bucketLabel(range: ProgressDateSelection.Range, granularity: ProgressBucketGranularity): String =
    when (granularity) {
        ProgressBucketGranularity.DAY -> "${range.startDate.month}/${range.startDate.dayOfMonth}"
        ProgressBucketGranularity.WEEK -> "${range.startDate.month}/${range.startDate.dayOfMonth}"
        ProgressBucketGranularity.MONTH -> "${range.startDate.month}/${range.startDate.year}"
    }

private fun progressNutritionSummary(
    logs: List<CalorieLog>,
    eligibleDays: Int,
    timeZone: TimeZone
): AdvancedNutritionSummary {
    val dailyTotals = logs.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
        .values
        .map(::calculateProgressNutritionTotals)
    return AdvancedNutritionSummary(
        loggedDays = dailyTotals.size,
        eligibleElapsedDays = eligibleDays,
        consistencyRatio = if (eligibleDays > 0) (dailyTotals.size.toFloat() / eligibleDays).coerceIn(0f, 1f) else 0f,
        averageLoggedCalories = dailyTotals.takeIf { it.isNotEmpty() }?.map { it.calories }?.average(),
        averageLoggedProteinGrams = dailyTotals.takeIf { it.isNotEmpty() }?.map { it.loggedProteinGrams.toDouble() }?.average(),
        averageLoggedCarbsGrams = dailyTotals.takeIf { it.isNotEmpty() }?.map { it.loggedCarbsGrams.toDouble() }?.average(),
        averageLoggedFatGrams = dailyTotals.takeIf { it.isNotEmpty() }?.map { it.loggedFatGrams.toDouble() }?.average()
    )
}

private fun progressMealDistribution(logs: List<CalorieLog>): List<MealAnalyticsDistribution> {
    val totalCalories = calculateProgressNutritionTotals(logs).calories
    return logs.groupBy { normalizeMealType(it.mealType) }
        .map { (mealType, entries) ->
            val totals = calculateProgressNutritionTotals(entries)
            MealAnalyticsDistribution(
                mealType = mealType,
                totals = totals,
                calorieShare = if (totalCalories > 0) (totals.calories.toFloat() / totalCalories).coerceIn(0f, 1f) else 0f
            )
        }
        .sortedByDescending { it.totals.calories }
}

private fun normalizeMealType(value: String): String = when (value.trim().lowercase()) {
    "breakfast" -> "Breakfast"
    "lunch" -> "Lunch"
    "dinner" -> "Dinner"
    "snack", "snacks" -> "Snack"
    else -> "Other"
}

private fun buildProgressTrainingRest(
    range: ProgressDateSelection.Range,
    workouts: List<WorkoutLog>,
    calories: List<CalorieLog>,
    timeZone: TimeZone
): TrainingRestNutritionComparison {
    val trainingDates = workouts.mapTo(mutableSetOf()) { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val byDate = calories.groupBy { ProgressDatePolicy.localDateAt(it.timestamp, timeZone) }
    val dates = localDateSequence(range, timeZone)
    fun summary(selected: List<LocalCalendarDate>): NutritionDayTypeSummary {
        val nutrition = progressNutritionSummary(selected.flatMap { byDate[it].orEmpty() }, selected.size, timeZone)
        return NutritionDayTypeSummary(
            nutritionCoverageDays = nutrition.loggedDays,
            averageLoggedCalories = nutrition.averageLoggedCalories,
            averageLoggedProteinGrams = nutrition.averageLoggedProteinGrams,
            averageLoggedCarbsGrams = nutrition.averageLoggedCarbsGrams,
            averageLoggedFatGrams = nutrition.averageLoggedFatGrams
        )
    }
    return TrainingRestNutritionComparison(
        trainingDays = summary(dates.filter { it in trainingDates }),
        restDays = summary(dates.filter { it !in trainingDates })
    )
}

private fun comparison(current: Double, previous: Double): AnalyticsMetricComparison =
    AnalyticsMetricComparison(
        current = current,
        previous = previous,
        absoluteDifference = current - previous,
        percentageChange = if (previous == 0.0) null else (current - previous) / previous * 100.0
    )

private fun nullableComparison(current: Double?, previous: Double?): AnalyticsMetricComparison? =
    if (current != null && previous != null) comparison(current, previous) else null
