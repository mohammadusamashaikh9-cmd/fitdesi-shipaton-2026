package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.AdvancedAnalyticsRange
import com.example.domain.AdvancedDailyPoint
import com.example.domain.AdvancedPeriodComparison
import com.example.domain.AnalyticsMetricComparison
import com.example.domain.LocalCalendarDate
import com.example.domain.NutritionDayTypeSummary
import com.example.ui.theme.FitDesiSpacing
import com.example.viewmodel.ProgressUiState
import kotlin.math.roundToInt

@Composable
internal fun AdvancedTrendsScreen(
    state: ProgressUiState,
    onAdvancedRangeRequested: (AdvancedAnalyticsRange) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.canViewAdvancedAnalytics) {
        AdvancedTrendsLocked(
            onAdvancedRangeRequested = onAdvancedRangeRequested,
            modifier = modifier
        )
        return
    }

    val analytics = state.advancedAnalytics
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("advanced_trends"),
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            AdvancedRangeSelector(
                selectedRange = state.advancedRange,
                onRangeSelected = onAdvancedRangeRequested
            )
        }

        if (state.isAdvancedAnalyticsLoading || analytics == null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(FitDesiSpacing.large),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item { TrainingSummarySection(state = analytics) }
            item { DailyTrendSection(points = analytics.dailyTrend) }
            item { NutritionSummarySection(state = analytics) }
            item { MealDistributionSection(state = analytics) }
            analytics.previousPeriodComparison?.let { comparison ->
                item { PeriodComparisonSection(comparison) }
            }
            analytics.trainingRestComparison?.let { comparison ->
                item {
                    TrainingRestSection(
                        training = comparison.trainingDays,
                        rest = comparison.restDays
                    )
                }
            }
            item { WeeklyReviewSection(state = analytics) }
        }
    }
}

@Composable
private fun AdvancedTrendsLocked(
    onAdvancedRangeRequested: (AdvancedAnalyticsRange) -> Unit,
    modifier: Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("advanced_trends_locked"),
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(FitDesiSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text("Advanced Trends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Plus and Pro add workout and logged-nutrition trends, period comparisons, Training vs Rest Days, and a completed Weekly Review.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { onAdvancedRangeRequested(AdvancedAnalyticsRange.THIRTY_DAYS) },
                        modifier = Modifier.testTag("advanced_unlock_action")
                    ) {
                        Text("Explore Advanced Analytics")
                    }
                }
            }
        }
        item {
            Text("PREMIUM RANGES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(FitDesiSpacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                LockedRangeChip("90D", "advanced_range_90d") {
                    onAdvancedRangeRequested(AdvancedAnalyticsRange.NINETY_DAYS)
                }
                LockedRangeChip("ALL", "advanced_range_all") {
                    onAdvancedRangeRequested(AdvancedAnalyticsRange.ALL)
                }
            }
        }
    }
}

@Composable
private fun LockedRangeChip(label: String, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun AdvancedRangeSelector(
    selectedRange: AdvancedAnalyticsRange,
    onRangeSelected: (AdvancedAnalyticsRange) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        AdvancedAnalyticsRange.entries.forEach { range ->
            val label = when (range) {
                AdvancedAnalyticsRange.SEVEN_DAYS -> "7D"
                AdvancedAnalyticsRange.THIRTY_DAYS -> "30D"
                AdvancedAnalyticsRange.NINETY_DAYS -> "90D"
                AdvancedAnalyticsRange.ALL -> "ALL"
            }
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelected(range) },
                label = { Text(label) },
                modifier = Modifier.testTag("advanced_range_${label.lowercase()}")
            )
        }
    }
}

@Composable
private fun TrainingSummarySection(state: com.example.domain.AdvancedProgressAnalytics) {
    SectionCard(title = "TRAINING") {
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
            AdvancedMetric("Logged workouts", state.workoutTotals.sessionCount.toString(), Modifier.weight(1f))
            AdvancedMetric("Active days", state.workoutTotals.activeTrainingDays.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(FitDesiSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
            AdvancedMetric("Recorded time", formatAdvancedDuration(state.workoutTotals.recordedDurationSeconds), Modifier.weight(1f))
            AdvancedMetric("Active days / week", formatOneDecimal(state.activeTrainingDaysPerWeek), Modifier.weight(1f))
        }
        Spacer(Modifier.height(FitDesiSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
            AdvancedMetric("Recorded sets", state.workoutTotals.recordedCompletedSets.toString(), Modifier.weight(1f))
            AdvancedMetric("Recorded volume", "${state.workoutTotals.recordedLiftingVolumeKg.roundToInt()} kg", Modifier.weight(1f))
        }
    }
}

@Composable
private fun DailyTrendSection(points: List<AdvancedDailyPoint>) {
    SectionCard(title = "DAILY TRENDS") {
        if (points.isEmpty()) {
            Text("No persisted workout or nutrition logs in this range.")
        } else {
            Text(
                "Training bars show logged workout sessions. Recorded duration, sets and volume are shown when available. Nutrition bars show logged calories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(FitDesiSpacing.small))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                items(points, key = { "${it.date.year}-${it.date.month}-${it.date.dayOfMonth}" }) { point ->
                    DailyTrendPoint(point)
                }
            }
        }
    }
}

@Composable
private fun DailyTrendPoint(point: AdvancedDailyPoint) {
    Card(
        modifier = Modifier.width(128.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(formatAdvancedDate(point.date), fontWeight = FontWeight.Bold)
            Text("Training", style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(progress = { point.trainingTrendFraction }, modifier = Modifier.fillMaxWidth())
            Text("${point.workoutSessions} sessions", style = MaterialTheme.typography.bodySmall)
            if (
                point.recordedDurationSeconds > 0L ||
                point.recordedCompletedSets > 0 ||
                point.recordedLiftingVolumeKg > 0.0
            ) {
                Text(
                    "${formatAdvancedDuration(point.recordedDurationSeconds)}  •  " +
                        "${point.recordedCompletedSets} sets  •  " +
                        "${point.recordedLiftingVolumeKg.roundToInt()} kg",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text("Logged calories", style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(
                progress = { point.calorieTrendFraction ?: 0f },
                modifier = Modifier.fillMaxWidth()
            )
            val nutrition = point.nutritionTotals
            Text(
                nutrition?.let { "${it.calories} kcal" } ?: "Not logged",
                style = MaterialTheme.typography.bodySmall
            )
            nutrition?.let {
                Text(
                    "Logged protein ${it.loggedProteinGrams.roundToInt()} g\n" +
                        "Logged carbs ${it.loggedCarbsGrams.roundToInt()} g\n" +
                        "Logged fat ${it.loggedFatGrams.roundToInt()} g",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun NutritionSummarySection(state: com.example.domain.AdvancedProgressAnalytics) {
    val nutrition = state.nutritionSummary
    SectionCard(title = "LOGGED NUTRITION") {
        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
            AdvancedMetric(
                "Logging consistency",
                "${(nutrition.consistencyRatio * 100).roundToInt()}%",
                Modifier.weight(1f)
            )
            AdvancedMetric(
                "Logged dates",
                "${nutrition.loggedDays}/${nutrition.eligibleElapsedDays}",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(FitDesiSpacing.small))
        if (nutrition.loggedDays == 0) {
            Text("No nutrition was logged in this range. Unlogged dates are not treated as zero intake.")
        } else {
            AdvancedMetric("Average logged calories", "${nutrition.averageLoggedCalories?.roundToInt()} kcal")
            Spacer(Modifier.height(FitDesiSpacing.small))
            Text(
                "Average logged protein ${nutrition.averageLoggedProteinGrams?.roundToInt()} g  •  " +
                    "Average logged carbs ${nutrition.averageLoggedCarbsGrams?.roundToInt()} g  •  " +
                    "Average logged fat ${nutrition.averageLoggedFatGrams?.roundToInt()} g",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Averages include only dates with persisted nutrition logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MealDistributionSection(state: com.example.domain.AdvancedProgressAnalytics) {
    SectionCard(title = "MEAL DISTRIBUTION") {
        if (state.mealDistribution.isEmpty()) {
            Text("No meal entries were logged in this range.")
        } else {
            state.mealDistribution.forEach { meal ->
                Text(meal.mealType.ifBlank { "Unspecified meal" }, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { meal.calorieShare }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${meal.totals.calories} kcal  •  Logged protein ${meal.totals.loggedProteinGrams.roundToInt()} g  •  " +
                        "Logged carbs ${meal.totals.loggedCarbsGrams.roundToInt()} g  •  " +
                        "Logged fat ${meal.totals.loggedFatGrams.roundToInt()} g",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(FitDesiSpacing.small))
            }
        }
    }
}

@Composable
private fun PeriodComparisonSection(comparison: AdvancedPeriodComparison) {
    SectionCard(title = "PREVIOUS PERIOD") {
        Text(
            "Compared with ${formatAdvancedDate(comparison.previousWindow.startDate)}–" +
                formatAdvancedDate(comparison.previousWindow.endDateInclusive),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(FitDesiSpacing.small))
        Text(comparisonLine("Logged workouts", comparison.workoutSessions))
        Text(comparisonLine("Active training days", comparison.activeTrainingDays))
        Text(comparisonLine("Recorded training time", comparison.recordedDurationSeconds, suffix = " sec"))
        Text(comparisonLine("Recorded sets", comparison.recordedCompletedSets))
        Text(comparisonLine("Recorded volume", comparison.recordedLiftingVolumeKg, suffix = " kg"))
        Text(comparisonLine("Nutrition logged dates", comparison.nutritionLoggedDays))
        comparison.averageLoggedCalories?.let {
            Text(comparisonLine("Average logged calories", it, suffix = " kcal"))
        }
        comparison.averageLoggedProteinGrams?.let {
            Text(comparisonLine("Average logged protein", it, suffix = " g"))
        }
        comparison.averageLoggedCarbsGrams?.let {
            Text(comparisonLine("Average logged carbs", it, suffix = " g"))
        }
        comparison.averageLoggedFatGrams?.let {
            Text(comparisonLine("Average logged fat", it, suffix = " g"))
        }
    }
}

@Composable
private fun TrainingRestSection(
    training: NutritionDayTypeSummary,
    rest: NutritionDayTypeSummary
) {
    SectionCard(title = "TRAINING VS REST DAYS") {
        Text(
            "Descriptive logged nutrition only. Rest dates are elapsed dates without a persisted workout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(FitDesiSpacing.small))
        DayTypeSummary("Training dates", training)
        Spacer(Modifier.height(FitDesiSpacing.medium))
        DayTypeSummary("Rest dates", rest)
    }
}

@Composable
private fun DayTypeSummary(label: String, summary: NutritionDayTypeSummary) {
    Text(label, fontWeight = FontWeight.Bold)
    if (summary.nutritionCoverageDays == 0) {
        Text("No logged nutrition coverage", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Nutrition coverage: ${summary.nutritionCoverageDays} dates")
        Text(
            "Average logged calories ${summary.averageLoggedCalories?.roundToInt()} kcal  •  " +
                "Logged protein ${summary.averageLoggedProteinGrams?.roundToInt()} g  •  " +
                "Logged carbs ${summary.averageLoggedCarbsGrams?.roundToInt()} g  •  " +
                "Logged fat ${summary.averageLoggedFatGrams?.roundToInt()} g",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun WeeklyReviewSection(state: com.example.domain.AdvancedProgressAnalytics) {
    val review = state.weeklyReview
    SectionCard(title = "COMPLETED WEEKLY REVIEW") {
        Text(
            "${formatAdvancedDate(review.weekStartMonday)}–${formatAdvancedDate(review.weekEndSunday)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(FitDesiSpacing.small))
        Text("Logged workouts: ${review.workoutTotals.sessionCount}")
        Text("Active training days: ${review.workoutTotals.activeTrainingDays}")
        Text("Recorded training time: ${formatAdvancedDuration(review.workoutTotals.recordedDurationSeconds)}")
        Text("Recorded sets: ${review.workoutTotals.recordedCompletedSets}")
        Text("Recorded volume: ${review.workoutTotals.recordedLiftingVolumeKg.roundToInt()} kg")
        Text("Nutrition logged: ${review.nutritionSummary.loggedDays}/7 dates")
        if (review.nutritionSummary.loggedDays > 0) {
            Text("Average logged calories: ${review.nutritionSummary.averageLoggedCalories?.roundToInt()} kcal")
            Text(
                "Average logged protein ${review.nutritionSummary.averageLoggedProteinGrams?.roundToInt()} g  •  " +
                    "Average logged carbs ${review.nutritionSummary.averageLoggedCarbsGrams?.roundToInt()} g  •  " +
                    "Average logged fat ${review.nutritionSummary.averageLoggedFatGrams?.roundToInt()} g",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FitDesiSpacing.medium),
            content = {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(FitDesiSpacing.small))
                content()
            }
        )
    }
}

@Composable
private fun AdvancedMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(FitDesiSpacing.small)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun comparisonLine(
    label: String,
    comparison: AnalyticsMetricComparison,
    suffix: String = ""
): String {
    val difference = comparison.absoluteDifference.roundToInt()
    val sign = if (difference > 0) "+" else ""
    val percentage = comparison.percentageChange?.let { " (${if (it > 0) "+" else ""}${it.roundToInt()}%)" }.orEmpty()
    return "$label: $sign$difference$suffix$percentage vs previous period"
}

private fun formatAdvancedDate(date: LocalCalendarDate): String =
    "${date.month}/${date.dayOfMonth}/${date.year}"

private fun formatAdvancedDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3_600L
    val minutes = (safeSeconds % 3_600L) / 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatOneDecimal(value: Double): String =
    ((value * 10.0).roundToInt() / 10.0).toString()
