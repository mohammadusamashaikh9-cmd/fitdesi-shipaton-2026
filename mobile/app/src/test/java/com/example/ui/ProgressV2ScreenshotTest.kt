package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import com.example.domain.ApprovedMuscleGroup
import com.example.domain.LocalCalendarDate
import com.example.domain.MuscleWorkloadRank
import com.example.domain.MuscleWorkloadSummary
import com.example.domain.ProgressDatePolicy
import com.example.domain.ProgressDatePreset
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionResult
import com.example.domain.buildProgressJournalDays
import com.example.domain.calculateCurrentWorkoutStreak
import com.example.domain.calculateProgressAnalyticsV2
import com.example.domain.calculateProgressNutritionTotals
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.ProgressTargetPresentation
import com.example.viewmodel.ProgressUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ProgressV2ScreenshotTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val originalLocale = Locale.getDefault()
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun fixLocaleAndClock() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(ZONE)
        composeTestRule.mainClock.autoAdvance = false
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun basicTraining() {
        setProgressContent(progressFixture(paid = false), ProgressHubSection.TRAINING)
        capture("basic-training")
    }

    @Test
    fun basicNutrition() {
        setProgressContent(progressFixture(paid = false), ProgressHubSection.NUTRITION)
        capture("basic-nutrition")
    }

    @Test
    fun basicCombined() {
        setProgressContent(progressFixture(paid = false), ProgressHubSection.COMBINED)
        capture("basic-combined")
    }

    @Test
    fun basicJournal() {
        setProgressContent(progressFixture(paid = false), ProgressHubSection.JOURNAL)
        capture("basic-journal")
    }

    @Test
    fun basicPeriodPicker() {
        setPeriodPickerContent(canViewFullHistory = false)
        capture("basic-period-picker")
    }

    @Test
    fun plusTraining() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.TRAINING)
        capture("plus-training-chart")
    }

    @Test
    fun plusNutritionCalories() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.NUTRITION)
        capture("plus-nutrition-calories")
    }

    @Test
    fun plusNutritionMacros() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.NUTRITION)
        composeTestRule.onNodeWithTag("nutrition_macros").performClick()
        capture("plus-nutrition-macros")
    }

    @Test
    fun plusNutritionMeals() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.NUTRITION)
        composeTestRule.onNodeWithTag("nutrition_meals").performClick()
        capture("plus-nutrition-meals")
    }

    @Test
    fun plusCombined() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.COMBINED)
        capture("plus-combined")
    }

    @Test
    fun plusPeriodPicker() {
        setPeriodPickerContent(canViewFullHistory = true)
        capture("plus-period-picker")
    }

    @Test
    fun plusMuscleWorkload() {
        val workload = requireNotNull(progressFixture(paid = true).muscleWorkload)
        composeTestRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                MuscleWorkloadCard(workload, totalWorkouts = workload.totalWorkouts)
            }
        }
        capture("plus-muscle-workload")
    }

    @Test
    fun emptyPartialMixedAndNoNutrition() {
        setProgressContent(progressFixture(paid = false, empty = true), ProgressHubSection.TRAINING)
        capture("state-empty")
    }

    @Test
    fun partialData() {
        setProgressContent(progressFixture(paid = false, partial = true), ProgressHubSection.NUTRITION)
        capture("state-partial")
    }

    @Test
    fun mixedStrengthAndCardio() {
        setProgressContent(progressFixture(paid = true, mixed = true), ProgressHubSection.TRAINING)
        capture("state-mixed-strength-cardio")
    }

    @Test
    fun noNutrition() {
        setProgressContent(progressFixture(paid = false, noNutrition = true), ProgressHubSection.NUTRITION)
        capture("state-no-nutrition")
    }

    @Test
    fun darkModeBaseline() {
        setProgressContent(progressFixture(paid = true), ProgressHubSection.COMBINED)
        capture("dark-mode")
    }

    @Test
    fun enlargedFontScale() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyPersonalTrainerTheme(theme = "Dark") {
                    ProgressFixtureContent(progressFixture(paid = false), ProgressHubSection.JOURNAL)
                }
            }
        }
        capture("font-scale-150")
    }

    private fun setProgressContent(state: ProgressUiState, section: ProgressHubSection) {
        composeTestRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") { ProgressFixtureContent(state, section) }
        }
    }

    private fun setPeriodPickerContent(canViewFullHistory: Boolean) {
        composeTestRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProgressPeriodPickerContent(
                        canViewFullHistory = canViewFullHistory,
                        onPresetSelected = {},
                        onChooseDates = {},
                        onFullHistoryRequired = {}
                    )
                }
            }
        }
    }

    private fun capture(name: String) {
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/progress-v2-$name.png")
    }

    @androidx.compose.runtime.Composable
    private fun ProgressFixtureContent(state: ProgressUiState, section: ProgressHubSection) {
        ProgressHubContent(
            state = state,
            initialSection = section,
            onBack = {},
            onFullHistoryRequired = {},
            onAdvancedAnalyticsRequired = {},
            onManageWorkoutHistory = {},
            onDateSelected = {},
            onShift = { ProgressDateSelectionResult.Invalid("fixture") },
            onPreset = { preset, custom ->
                when (preset) {
                    ProgressDatePreset.ALL_HISTORY -> ProgressDateSelectionResult.Allowed(ProgressDateSelection.AllHistory)
                    ProgressDatePreset.CUSTOM -> custom?.let(ProgressDateSelectionResult::Allowed)
                        ?: ProgressDateSelectionResult.Invalid("fixture")
                    else -> ProgressDateSelectionResult.Invalid("fixture")
                }
            }
        )
    }

    private fun progressFixture(
        paid: Boolean,
        empty: Boolean = false,
        partial: Boolean = false,
        mixed: Boolean = false,
        noNutrition: Boolean = false
    ): ProgressUiState {
        val today = LocalCalendarDate(2026, 9, 4)
        val start = LocalCalendarDate(2026, 8, 6)
        val selection = ProgressDateSelection.Range(start, today)
        val workouts = if (empty) emptyList() else buildList {
            add(workout("strength-fri", "Upper Strength", "Strength", LocalCalendarDate(2026, 9, 4), 7, 1860.0))
            if (!partial) {
                add(workout("strength-thu", "Lower Strength", "Strength", LocalCalendarDate(2026, 9, 3), 9, 2480.0))
                add(workout("strength-wed", "Full Body", "Strength", LocalCalendarDate(2026, 9, 2), 8, 2130.0))
                add(workout("strength-older", "Technique", "Strength", LocalCalendarDate(2026, 8, 29), 5, 980.0))
            }
            if (mixed) add(workout("cardio-1", "Easy run", "Cardio", LocalCalendarDate(2026, 8, 22), 0, 0.0))
        }
        val calories = if (empty || noNutrition) emptyList() else buildList {
            add(calorie("Breakfast oats", "Breakfast", LocalCalendarDate(2026, 9, 4), 420, 24f, 58f, 11f))
            if (!partial) {
                add(calorie("Lunch bowl", "Lunch", LocalCalendarDate(2026, 9, 4), 610, 36f, 74f, 18f))
                add(calorie("Dinner curry", "Dinner", LocalCalendarDate(2026, 8, 29), 720, 42f, 82f, 22f))
                add(calorie("Fruit", "Snack", LocalCalendarDate(2026, 8, 22), 180, 2f, 44f, 0f))
                add(calorie("Late meal", "Unexpected type", LocalCalendarDate(2026, 8, 15), 510, 28f, 55f, 19f))
            }
        }
        val analytics = calculateProgressAnalyticsV2(selection, today, ZONE, workouts, calories)
        val workoutDates = workouts.map { ProgressDatePolicy.localDateAt(it.timestamp, ZONE) }
        val activeWorkoutDates = workoutDates.toSet()
        val currentWeekDates = listOf(
            LocalCalendarDate(2026, 8, 31),
            LocalCalendarDate(2026, 9, 1),
            LocalCalendarDate(2026, 9, 2),
            LocalCalendarDate(2026, 9, 3),
            LocalCalendarDate(2026, 9, 4),
            LocalCalendarDate(2026, 9, 5),
            LocalCalendarDate(2026, 9, 6)
        )
        return ProgressUiState(
            isLoading = false,
            today = today,
            dateSelection = selection,
            analytics = analytics,
            exactWorkoutStreak = calculateCurrentWorkoutStreak(workouts, today, ZONE),
            currentWeekActivity = currentWeekDates.map(activeWorkoutDates::contains),
            workoutsThisWeek = workoutDates.count(currentWeekDates::contains),
            hasWorkoutToday = today in activeWorkoutDates,
            todayNutrition = calculateProgressNutritionTotals(
                calories.filter { ProgressDatePolicy.localDateAt(it.timestamp, ZONE) == today }
            ),
            latestRecentWorkout = workouts.maxByOrNull { it.timestamp },
            journalDays = buildProgressJournalDays(start, today, workouts, calories, today, ZONE),
            selectedJournalDate = today,
            targets = ProgressTargetPresentation(2_200, 160f, 250f, 70f),
            canViewFullHistory = paid,
            canShowMacroTargets = paid,
            canViewAdvancedAnalytics = paid,
            muscleWorkload = if (paid && !empty) MuscleWorkloadSummary(
                ranked = listOf(
                    MuscleWorkloadRank(ApprovedMuscleGroup.CHEST, 7.0, 0.35f),
                    MuscleWorkloadRank(ApprovedMuscleGroup.BACK, 5.0, 0.25f),
                    MuscleWorkloadRank(ApprovedMuscleGroup.QUADRICEPS, 4.0, 0.20f),
                    MuscleWorkloadRank(ApprovedMuscleGroup.SHOULDERS, 2.5, 0.125f),
                    MuscleWorkloadRank(ApprovedMuscleGroup.TRICEPS, 1.5, 0.075f)
                ),
                workoutsWithMuscleData = workouts.count { it.category == "Strength" },
                totalWorkouts = workouts.size,
                unclassifiedSets = if (mixed) 1 else 0
            ) else null
        )
    }

    private fun workout(
        sessionId: String,
        name: String,
        category: String,
        date: LocalCalendarDate,
        sets: Int,
        volume: Double
    ) = WorkoutLog(
        sessionId = sessionId,
        exerciseName = name,
        category = category,
        durationMinutes = 42,
        durationSeconds = 2_520,
        caloriesBurned = 0,
        completedSets = sets,
        liftingVolumeKg = volume,
        timestamp = midday(date)
    )

    private fun calorie(
        description: String,
        meal: String,
        date: LocalCalendarDate,
        amount: Int,
        protein: Float,
        carbs: Float,
        fat: Float
    ) = CalorieLog(
        amount = amount,
        mealType = meal,
        description = description,
        proteinGrams = protein,
        carbsGrams = carbs,
        fatGrams = fat,
        timestamp = midday(date)
    )

    private fun midday(date: LocalCalendarDate): Long =
        ProgressDatePolicy.startOfLocalDateEpochMillis(date, ZONE) + 12L * 60L * 60L * 1_000L

    private companion object {
        val ZONE: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
