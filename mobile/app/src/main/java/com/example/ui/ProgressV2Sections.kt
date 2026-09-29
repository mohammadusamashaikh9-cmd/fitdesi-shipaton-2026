package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.AnalyticsMetricComparison
import com.example.domain.MealAnalyticsDistribution
import com.example.domain.MuscleWorkloadSummary
import com.example.domain.ProgressAnalyticsV2
import com.example.domain.ProgressChartBucket
import com.example.domain.ProgressDateSelection
import com.example.ui.components.charts.ChartBucket
import com.example.ui.components.charts.ChartPoint
import com.example.ui.components.charts.ChartSemanticSummary
import com.example.ui.components.charts.ChartSeries
import com.example.ui.components.charts.FitDesiComparisonBars
import com.example.ui.components.charts.FitDesiDonutChart
import com.example.ui.components.charts.FitDesiMacroChart
import com.example.ui.components.charts.FitDesiMetricChart
import com.example.ui.theme.FitDesiInfo
import com.example.ui.theme.FitDesiOrange
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.FitDesiWarning
import com.example.viewmodel.ProgressUiState
import kotlin.math.abs
import kotlin.math.roundToInt

private val CaloriesColor = Color(0xFF27B6B1)
private val CarbsColor = Color(0xFF32B9B2)
private val FatColor = Color(0xFFA76AD8)
private val ProteinColor = Color(0xFFFFB13B)
private val WorkloadColors = listOf(
    FitDesiOrange,
    Color(0xFFF3A62D),
    Color(0xFF23A7A3),
    Color(0xFF7786D9),
    Color(0xFFA96FD1),
    Color(0xFFE17E55),
    Color(0xFF8AAE62),
    Color(0xFF8B929D)
)

private enum class TrainingMetric(val label: String) { SESSIONS("Sessions"), TIME("Time"), SETS("Sets"), VOLUME("Volume") }
private enum class NutritionSection(val label: String) { CALORIES("Calories"), MACROS("Macros"), MEALS("Meals") }

@Composable
internal fun ProgressMomentumHeader(state: ProgressUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = FitDesiSpacing.medium, vertical = FitDesiSpacing.small),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FitDesiSpacing.medium).animateContentSize(tween(250)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MOMENTUM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.exactWorkoutStreak == 1) "1 day streak" else "${state.exactWorkoutStreak} day streak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(
                    "Monday" to "M",
                    "Tuesday" to "T",
                    "Wednesday" to "W",
                    "Thursday" to "T",
                    "Friday" to "F",
                    "Saturday" to "S",
                    "Sunday" to "S"
                ).forEachIndexed { index, (dayName, label) ->
                    val isActive = state.currentWeekActivity.getOrElse(index) { false }
                    Column(
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "$dayName, ${if (isActive) "workout logged" else "no workout logged"}"
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier
                                .size(10.dp)
                                .then(
                                    if (isActive) Modifier.background(FitDesiOrange, CircleShape)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                        )
                    }
                }
            }
            Text(
                "${state.workoutsThisWeek} ${if (state.workoutsThisWeek == 1) "workout" else "workouts"} this week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
internal fun ProgressTrainingScreen(
    state: ProgressUiState,
    onAdvancedAnalyticsRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val analytics = state.analytics
    if (analytics == null) {
        ProgressEmptyContent("Training is getting ready.", modifier)
        return
    }
    var metric by remember { mutableStateOf(TrainingMetric.SESSIONS) }
    LaunchedEffect(state.canViewAdvancedAnalytics) {
        if (!state.canViewAdvancedAnalytics) metric = TrainingMetric.SESSIONS
    }
    val points = analytics.buckets.map { bucket -> trainingPoint(bucket, metric) }
    val series = ChartSeries(metric.label, FitDesiOrange, points)
    val rangeLabel = progressSelectionLabel(state.dateSelection)
    val totals = analytics.workoutTotals
    val comparison = if (state.canViewAdvancedAnalytics) {
        analytics.previousPeriodComparison?.let { comparisonFor(it, metric) }
    } else null

    LazyColumn(
        modifier = modifier.testTag("progress_training"),
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("TRAINING", style = MaterialTheme.typography.labelLarge, color = FitDesiOrange, fontWeight = FontWeight.Bold)
            Text("${totals.sessionCount} logged workouts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (state.canViewAdvancedAnalytics) {
                    "${totals.activeTrainingDays} active days  •  ${formatProgressDuration(totals.recordedDurationSeconds)}"
                } else {
                    "${totals.activeTrainingDays} active training days"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.canViewAdvancedAnalytics) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactMetric("Completed sets", totals.recordedCompletedSets.toString(), Modifier.weight(1f))
                    CompactMetric("Lifting volume", "${totals.recordedLiftingVolumeKg.roundToInt()} kg", Modifier.weight(1f))
                }
            }
        }
        item {
            AnalyticsCard {
                Text("${metric.label} trend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FitDesiMetricChart(
                    series = series,
                    semantics = chartSummary("${metric.label} trend", rangeLabel, points),
                    useLine = metric == TrainingMetric.VOLUME
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val metrics = if (state.canViewAdvancedAnalytics) TrainingMetric.entries else listOf(TrainingMetric.SESSIONS)
                    metrics.forEach { candidate ->
                        FilterChip(
                            selected = metric == candidate,
                            onClick = { metric = candidate },
                            label = { Text(candidate.label, maxLines = 1) },
                            modifier = Modifier.weight(1f).testTag("training_metric_${candidate.name.lowercase()}")
                        )
                    }
                }
            }
        }
        comparison?.let { currentComparison ->
            item {
                AnalyticsCard {
                    Text("Previous period", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(comparisonLabel(currentComparison), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FitDesiComparisonBars(
                        title = metric.label,
                        currentLabel = "Selected",
                        current = currentComparison.current,
                        previousLabel = "Previous",
                        previous = currentComparison.previous,
                        color = FitDesiOrange
                    )
                }
            }
        }
        if (!state.canViewAdvancedAnalytics) {
            item(key = "advanced-analytics-discovery") {
                AnalyticsCard(modifier = Modifier.testTag("advanced_analytics_locked")) {
                    Text("More ways to understand your training", fontWeight = FontWeight.Bold)
                    Text(
                        "Plus adds time, sets, volume, previous-period comparisons, and muscle workload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onAdvancedAnalyticsRequired,
                        modifier = Modifier.testTag("advanced_analytics_action")
                    ) { Text("Explore advanced analytics") }
                }
            }
        }
        item { Text("Recent workouts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        val workouts = state.journalDays.flatMap { it.workoutEntries }.sortedByDescending { it.timestamp }.take(5)
        if (workouts.isEmpty()) {
            item { ProgressEmptyCard("No workouts in this period.") }
        } else {
            items(workouts, key = { "progress-workout-${it.id}-${it.timestamp}" }) { workout ->
                AnalyticsCard {
                    Text(workout.exerciseName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatProgressDuration(if (workout.durationSeconds > 0) workout.durationSeconds.toLong() else workout.durationMinutes * 60L)}" +
                            if (workout.completedSets > 0) "  •  ${workout.completedSets} sets" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.canViewAdvancedAnalytics) {
            item { MuscleWorkloadCard(state.muscleWorkload ?: MuscleWorkloadSummary(), analytics.workoutTotals.sessionCount) }
        }
    }
}

@Composable
internal fun ProgressNutritionScreen(state: ProgressUiState, modifier: Modifier = Modifier) {
    val analytics = state.analytics
    if (analytics == null) {
        ProgressEmptyContent("Nutrition is getting ready.", modifier)
        return
    }
    var section by remember { mutableStateOf(NutritionSection.CALORIES) }
    Column(modifier.testTag("progress_nutrition")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NutritionSection.entries.forEach { candidate ->
                FilterChip(
                    selected = section == candidate,
                    onClick = { section = candidate },
                    label = { Text(candidate.label) },
                    modifier = Modifier.weight(1f).testTag("nutrition_${candidate.name.lowercase()}")
                )
            }
        }
        AnimatedContent(
            modifier = Modifier.weight(1f),
            targetState = section,
            transitionSpec = { fadeProgressContent() },
            label = "nutrition-section"
        ) { selected ->
            when (selected) {
                NutritionSection.CALORIES -> NutritionCalories(analytics, state)
                NutritionSection.MACROS -> NutritionMacros(analytics, state)
                NutritionSection.MEALS -> NutritionMeals(analytics)
            }
        }
    }
}

@Composable
private fun NutritionCalories(analytics: ProgressAnalyticsV2, state: ProgressUiState) {
    val summary = analytics.nutritionSummary
    val points = analytics.buckets.map { bucket ->
        ChartPoint(bucket.label, bucket.averageLoggedCalories, bucket.averageLoggedCalories?.let { "${it.roundToInt()} kcal" } ?: "Not logged")
    }
    LazyColumn(
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("CALORIES", style = MaterialTheme.typography.labelLarge, color = CaloriesColor, fontWeight = FontWeight.Bold)
            Text(
                summary.averageLoggedCalories?.let { "${it.roundToInt()} kcal average" } ?: "No calories logged",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text("Logged ${summary.loggedDays} of ${summary.eligibleElapsedDays} days", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            AnalyticsCard {
                Text("Calorie trend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FitDesiMetricChart(
                    ChartSeries("Average logged calories", CaloriesColor, points),
                    chartSummary("Calorie trend", progressSelectionLabel(state.dateSelection), points),
                    useLine = true
                )
            }
        }
        state.targets.calorieTarget?.let { target ->
            item {
                AnalyticsCard {
                    Text("CURRENT GOAL REFERENCE", style = MaterialTheme.typography.labelLarge, color = FitDesiInfo)
                    Text("$target kcal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Uses your current goal for reference; past goals may have been different.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NutritionMacros(analytics: ProgressAnalyticsV2, state: ProgressUiState) {
    val summary = analytics.nutritionSummary
    val buckets = analytics.buckets.map { bucket ->
        ChartBucket(bucket.label, listOf(bucket.averageLoggedCarbsGrams, bucket.averageLoggedFatGrams, bucket.averageLoggedProteinGrams))
    }
    val rows = analytics.buckets.map { bucket ->
        "${bucket.label}: carbs ${bucket.averageLoggedCarbsGrams?.roundToInt() ?: "not logged"}, " +
            "fat ${bucket.averageLoggedFatGrams?.roundToInt() ?: "not logged"}, " +
            "protein ${bucket.averageLoggedProteinGrams?.roundToInt() ?: "not logged"}"
    }
    LazyColumn(
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("LOGGED MACROS", style = MaterialTheme.typography.labelLarge, color = ProteinColor, fontWeight = FontWeight.Bold)
            Text("Averages use logged days only", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${summary.loggedDays} of ${summary.eligibleElapsedDays} days include nutrition", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            AnalyticsCard {
                FitDesiMacroChart(
                    buckets = buckets,
                    colors = listOf(CarbsColor, FatColor, ProteinColor),
                    semantics = ChartSemanticSummary(
                        "Logged macros",
                        progressSelectionLabel(state.dateSelection),
                        "Carbs, fat, and protein averages per logged day",
                        rows
                    )
                )
                MacroLegend("Carbs", summary.averageLoggedCarbsGrams, CarbsColor)
                MacroLegend("Fat", summary.averageLoggedFatGrams, FatColor)
                MacroLegend("Protein", summary.averageLoggedProteinGrams, ProteinColor)
            }
        }
        if (state.canShowMacroTargets) {
            item {
                AnalyticsCard {
                    Text("CURRENT GOAL", style = MaterialTheme.typography.labelLarge, color = FitDesiInfo)
                    Text(
                        listOfNotNull(
                            state.targets.proteinTargetGrams?.let { "Protein ${it.roundToInt()} g" },
                            state.targets.carbsTargetGrams?.let { "Carbs ${it.roundToInt()} g" },
                            state.targets.fatTargetGrams?.let { "Fat ${it.roundToInt()} g" }
                        ).joinToString("  •  ")
                    )
                }
            }
        }
    }
}

@Composable
private fun NutritionMeals(analytics: ProgressAnalyticsV2) {
    val meals = analytics.mealDistribution
    LazyColumn(
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("MEALS", style = MaterialTheme.typography.labelLarge, color = CaloriesColor, fontWeight = FontWeight.Bold)
            Text("Calorie distribution", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        if (meals.isEmpty()) {
            item { ProgressEmptyCard("No meals were logged in this period.") }
        } else {
            item { MealDistributionCard(meals) }
        }
    }
}

@Composable
internal fun ProgressCombinedScreen(state: ProgressUiState, modifier: Modifier = Modifier) {
    val analytics = state.analytics
    if (analytics == null) {
        ProgressEmptyContent("Combined progress is getting ready.", modifier)
        return
    }
    LazyColumn(
        modifier = modifier.testTag("progress_combined"),
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("CURRENT GOAL PROGRESS", style = MaterialTheme.typography.labelLarge, color = FitDesiOrange, fontWeight = FontWeight.Bold)
            AnalyticsCard {
                val target = state.targets.calorieTarget
                Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (target != null) "${state.todayNutrition.calories} / $target kcal" else "${state.todayNutrition.calories} kcal logged",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (target != null && target > 0) {
                    CurrentGoalProgressBar(
                        actual = state.todayNutrition.calories.toFloat(),
                        target = target.toFloat(),
                        color = CaloriesColor,
                        animationLabel = "current calorie goal progress"
                    )
                }
                Text(
                    if (state.hasWorkoutToday) "Workout today\nCompleted" else "No workout logged today",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.canShowMacroTargets) {
                    state.targets.proteinTargetGrams?.let { target ->
                        Text("Protein ${state.todayNutrition.loggedProteinGrams.roundToInt()} / ${target.roundToInt()} g", style = MaterialTheme.typography.bodySmall)
                        if (target > 0f) {
                            CurrentGoalProgressBar(state.todayNutrition.loggedProteinGrams, target, ProteinColor, "current protein goal progress")
                        }
                    }
                    state.targets.carbsTargetGrams?.let { target ->
                        Text("Carbs ${state.todayNutrition.loggedCarbsGrams.roundToInt()} / ${target.roundToInt()} g", style = MaterialTheme.typography.bodySmall)
                        if (target > 0f) {
                            CurrentGoalProgressBar(state.todayNutrition.loggedCarbsGrams, target, CarbsColor, "current carbs goal progress")
                        }
                    }
                    state.targets.fatTargetGrams?.let { target ->
                        Text("Fat ${state.todayNutrition.loggedFatGrams.roundToInt()} / ${target.roundToInt()} g", style = MaterialTheme.typography.bodySmall)
                        if (target > 0f) {
                            CurrentGoalProgressBar(state.todayNutrition.loggedFatGrams, target, FatColor, "current fat goal progress")
                        }
                    }
                }
            }
        }
        item {
            Text("COMBINED TIMELINE", style = MaterialTheme.typography.labelLarge, color = CaloriesColor, fontWeight = FontWeight.Bold)
            AnalyticsCard {
                val training = analytics.buckets.map { ChartPoint(it.label, it.workoutSessions.toDouble(), "${it.workoutSessions} workouts") }
                val calories = analytics.buckets.map { ChartPoint(it.label, it.averageLoggedCalories, it.averageLoggedCalories?.let { value -> "${value.roundToInt()} kcal" } ?: "Not logged") }
                Text("Training", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FitDesiMetricChart(
                    ChartSeries("Workout sessions", FitDesiOrange, training),
                    chartSummary("Training timeline", progressSelectionLabel(state.dateSelection), training)
                )
                Text("Logged calories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FitDesiMetricChart(
                    ChartSeries("Average logged calories", CaloriesColor, calories),
                    chartSummary("Calorie timeline", progressSelectionLabel(state.dateSelection), calories),
                    useLine = true
                )
            }
        }
        if (state.canViewAdvancedAnalytics) {
            analytics.trainingRestComparison?.let { comparison ->
                item {
                    Text("TRAINING VS REST", style = MaterialTheme.typography.labelLarge, color = FitDesiWarning, fontWeight = FontWeight.Bold)
                    AnalyticsCard {
                        Text("Logged nutrition averages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Training days: ${comparison.trainingDays.nutritionCoverageDays} logged  •  " +
                                "Rest days: ${comparison.restDays.nutritionCoverageDays} logged",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FitDesiComparisonBars(
                            "Calories",
                            "Training",
                            comparison.trainingDays.averageLoggedCalories,
                            "Rest",
                            comparison.restDays.averageLoggedCalories,
                            CaloriesColor
                        )
                        FitDesiComparisonBars(
                            "Protein (g)",
                            "Training",
                            comparison.trainingDays.averageLoggedProteinGrams,
                            "Rest",
                            comparison.restDays.averageLoggedProteinGrams,
                            ProteinColor
                        )
                        FitDesiComparisonBars("Carbs (g)", "Training", comparison.trainingDays.averageLoggedCarbsGrams, "Rest", comparison.restDays.averageLoggedCarbsGrams, CarbsColor)
                        FitDesiComparisonBars("Fat (g)", "Training", comparison.trainingDays.averageLoggedFatGrams, "Rest", comparison.restDays.averageLoggedFatGrams, FatColor)
                        Text("This comparison describes logged records only; it does not imply cause or health impact.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val review = analytics.weeklyReview
                Text("WEEKLY REVIEW", style = MaterialTheme.typography.labelLarge, color = FitDesiOrange, fontWeight = FontWeight.Bold)
                AnalyticsCard {
                    Text(
                        progressSelectionLabel(ProgressDateSelection.Range(review.weekStartMonday, review.weekEndSunday)),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${review.workoutTotals.sessionCount} workouts  •  ${review.workoutTotals.activeTrainingDays} active days")
                    Text("${formatProgressDuration(review.workoutTotals.recordedDurationSeconds)}  •  ${review.workoutTotals.recordedCompletedSets} sets")
                    Text("${review.workoutTotals.recordedLiftingVolumeKg.roundToInt()} kg recorded volume")
                    Text("Nutrition logged ${review.nutritionSummary.loggedDays} of 7 days")
                    Text(review.nutritionSummary.averageLoggedCalories?.let { "${it.roundToInt()} kcal average" } ?: "Calories not logged")
                    Text(
                        "Protein ${review.nutritionSummary.averageLoggedProteinGrams?.roundToInt() ?: "not logged"}  •  " +
                            "Carbs ${review.nutritionSummary.averageLoggedCarbsGrams?.roundToInt() ?: "not logged"}  •  " +
                            "Fat ${review.nutritionSummary.averageLoggedFatGrams?.roundToInt() ?: "not logged"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentGoalProgressBar(
    actual: Float,
    target: Float,
    color: Color,
    animationLabel: String
) {
    val progress by animateFloatAsState(
        targetValue = (actual / target).coerceIn(0f, 1f),
        animationSpec = tween(280),
        label = animationLabel
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
internal fun MuscleWorkloadCard(workload: MuscleWorkloadSummary, totalWorkouts: Int) {
    AnalyticsCard(modifier = Modifier.testTag("muscle_workload_card")) {
        Text("MUSCLE WORKLOAD", style = MaterialTheme.typography.labelLarge, color = FitDesiOrange, fontWeight = FontWeight.Bold)
        if (workload.ranked.isEmpty()) {
            Text("Muscle workload is available for workouts recorded after this update.")
            return@AnalyticsCard
        }
        val points = workload.ranked.map { rank ->
            ChartPoint(rank.muscleGroup.displayLabel, rank.workloadPoints, "${(rank.fraction * 100).roundToInt()} percent")
        }
        FitDesiDonutChart(
            ChartSeries("Muscle workload", FitDesiOrange, points),
            chartSummary(
                title = "Muscle workload",
                range = "Selected period",
                points = points,
                populatedSummary = "${points.size} muscle ${if (points.size == 1) "group" else "groups"}"
            ),
            colors = WorkloadColors
        )
        workload.ranked.forEachIndexed { index, rank ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(WorkloadColors[index % WorkloadColors.size], CircleShape))
                Text(rank.muscleGroup.displayLabel, Modifier.padding(start = 10.dp).weight(1f))
                Text("${(rank.fraction * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            }
        }
        Text("Based on ${workload.workoutsWithMuscleData} of $totalWorkouts workouts with muscle data.", style = MaterialTheme.typography.bodySmall)
        if (workload.unclassifiedSets > 0) Text("Some completed sets could not be classified.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MealDistributionCard(meals: List<MealAnalyticsDistribution>) {
    AnalyticsCard {
        val points = meals.map { ChartPoint(it.mealType, it.totals.calories.toDouble(), "${(it.calorieShare * 100).roundToInt()} percent") }
        FitDesiDonutChart(
            ChartSeries("Meal calories", CaloriesColor, points),
            chartSummary(
                title = "Meal calorie distribution",
                range = "Selected period",
                points = points,
                populatedSummary = "${points.size} meal ${if (points.size == 1) "group" else "groups"}"
            ),
            colors = listOf(CaloriesColor, FitDesiInfo, ProteinColor, FatColor, Color(0xFF8B929D))
        )
        meals.forEachIndexed { index, meal ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(listOf(CaloriesColor, FitDesiInfo, ProteinColor, FatColor, Color(0xFF8B929D))[index % 5], CircleShape))
                Text(meal.mealType, Modifier.padding(start = 10.dp).weight(1f))
                Text("${meal.totals.calories} kcal  •  ${(meal.calorieShare * 100).roundToInt()}%")
            }
        }
    }
}

@Composable
private fun MacroLegend(label: String, value: Double?, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, Modifier.padding(start = 10.dp).weight(1f))
        Text(value?.let { "${it.roundToInt()} g avg" } ?: "Not logged")
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AnalyticsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun ProgressEmptyContent(message: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(message) }
}

@Composable
internal fun ProgressEmptyCard(message: String) {
    AnalyticsCard { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun trainingPoint(bucket: ProgressChartBucket, metric: TrainingMetric): ChartPoint = when (metric) {
    TrainingMetric.SESSIONS -> ChartPoint(bucket.label, bucket.workoutSessions.toDouble(), "${bucket.workoutSessions} workouts")
    TrainingMetric.TIME -> ChartPoint(bucket.label, bucket.recordedDurationSeconds / 60.0, "${bucket.recordedDurationSeconds / 60} min")
    TrainingMetric.SETS -> ChartPoint(bucket.label, bucket.recordedCompletedSets.toDouble(), "${bucket.recordedCompletedSets} sets")
    TrainingMetric.VOLUME -> ChartPoint(bucket.label, bucket.recordedLiftingVolumeKg, "${bucket.recordedLiftingVolumeKg.roundToInt()} kg")
}

private fun comparisonFor(comparison: com.example.domain.AdvancedPeriodComparison, metric: TrainingMetric): AnalyticsMetricComparison = when (metric) {
    TrainingMetric.SESSIONS -> comparison.workoutSessions
    TrainingMetric.TIME -> comparison.recordedDurationSeconds.copy(
        current = comparison.recordedDurationSeconds.current / 60.0,
        previous = comparison.recordedDurationSeconds.previous / 60.0,
        absoluteDifference = comparison.recordedDurationSeconds.absoluteDifference / 60.0
    )
    TrainingMetric.SETS -> comparison.recordedCompletedSets
    TrainingMetric.VOLUME -> comparison.recordedLiftingVolumeKg
}

private fun comparisonLabel(comparison: AnalyticsMetricComparison): String {
    val percent = comparison.percentageChange ?: return if (comparison.current == comparison.previous) "No change" else "Previous period had no recorded value"
    val arrow = if (percent >= 0) "↑" else "↓"
    return "$arrow ${if (percent >= 0) "+" else "-"}${abs(percent).roundToInt()}%"
}

private fun chartSummary(
    title: String,
    range: String,
    points: List<ChartPoint>,
    populatedSummary: String = if (points.size == 30) "30-day view" else "Selected period"
) = ChartSemanticSummary(
    title = title,
    dateRange = range,
    summary = if (points.any { it.value != null }) populatedSummary else "No logged values",
    metricRows = points.map { "${it.label}: ${it.valueLabel}" }
)

internal fun formatProgressDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun fadeProgressContent() = fadeIn(tween(220)) togetherWith fadeOut(tween(220))
