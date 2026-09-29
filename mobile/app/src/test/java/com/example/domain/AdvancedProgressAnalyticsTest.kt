package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedProgressAnalyticsTest {
    private val zone = TimeZone.getTimeZone("America/New_York")
    private val today = LocalCalendarDate(2026, 9, 9)

    @Test
    fun `ninety day windows are local-date correct adjacent equal and non-overlapping`() {
        val windows = finiteAnalyticsWindows(AdvancedAnalyticsRange.NINETY_DAYS, today, zone)

        assertEquals(LocalCalendarDate(2026, 6, 12), windows.current.startDate)
        assertEquals(today, windows.current.endDateInclusive)
        assertEquals(90, windows.current.calendarDays)
        assertEquals(90, windows.previous.calendarDays)
        assertEquals(
            windows.current.startDate,
            ProgressDatePolicy.plusCalendarDays(windows.previous.endDateInclusive, 1, zone)
        )
        assertTrue(windows.previous.endDateInclusive < windows.current.startDate)
    }

    @Test
    fun `advanced analytics use recorded workout truth and logged-day nutrition averages`() {
        val trainingDate = LocalCalendarDate(2026, 9, 9)
        val zeroVolumeTrainingDate = LocalCalendarDate(2026, 9, 7)
        val restNutritionDate = LocalCalendarDate(2026, 9, 8)
        val previousDate = LocalCalendarDate(2026, 8, 10)
        val analytics = calculateAdvancedProgressAnalytics(
            range = AdvancedAnalyticsRange.THIRTY_DAYS,
            today = today,
            timeZone = zone,
            workoutLogs = listOf(
                workout(trainingDate, durationSeconds = 3_000, sets = 5, volume = 1_500.0),
                workout(zeroVolumeTrainingDate, durationMinutes = 20, sets = 3, volume = 0.0),
                workout(previousDate)
            ),
            calorieLogs = listOf(
                calorie(trainingDate, calories = 100, protein = 10f),
                calorie(restNutritionDate, calories = 200, carbs = 20f),
                calorie(restNutritionDate, calories = 50, fat = 5f),
                calorie(previousDate, calories = 400)
            )
        )

        assertEquals(2, analytics.workoutTotals.sessionCount)
        assertEquals(2, analytics.workoutTotals.activeTrainingDays)
        assertEquals(4_200L, analytics.workoutTotals.recordedDurationSeconds)
        assertEquals(8, analytics.workoutTotals.recordedCompletedSets)
        assertEquals(1_500.0, analytics.workoutTotals.recordedLiftingVolumeKg, 0.001)
        assertTrue(
            analytics.dailyTrend.single { it.date == trainingDate }.trainingTrendFraction > 0f
        )
        assertTrue(
            analytics.dailyTrend.single { it.date == zeroVolumeTrainingDate }.trainingTrendFraction > 0f
        )
        assertEquals(2, analytics.nutritionSummary.loggedDays)
        assertEquals(30, analytics.nutritionSummary.eligibleElapsedDays)
        assertEquals(2f / 30f, analytics.nutritionSummary.consistencyRatio, 0.0001f)
        assertEquals(175.0, analytics.nutritionSummary.averageLoggedCalories!!, 0.001)
        assertEquals(1, analytics.trainingRestComparison!!.trainingDays.nutritionCoverageDays)
        assertEquals(100.0, analytics.trainingRestComparison!!.trainingDays.averageLoggedCalories!!, 0.001)
        assertEquals(1, analytics.trainingRestComparison!!.restDays.nutritionCoverageDays)
        assertEquals(250.0, analytics.trainingRestComparison!!.restDays.averageLoggedCalories!!, 0.001)
        assertEquals(1.0, analytics.previousPeriodComparison!!.workoutSessions.previous, 0.001)
        assertNull(analytics.previousPeriodComparison!!.recordedLiftingVolumeKg.percentageChange)
    }

    @Test
    fun `manual zero macros remain zero and meal distribution keeps persisted meal type`() {
        val analytics = calculateAdvancedProgressAnalytics(
            range = AdvancedAnalyticsRange.SEVEN_DAYS,
            today = today,
            timeZone = zone,
            workoutLogs = emptyList(),
            calorieLogs = listOf(
                calorie(
                    date = today,
                    calories = 450,
                    mealType = "Late Snack",
                    description = "High protein chicken",
                    protein = 0f,
                    carbs = 0f,
                    fat = 0f
                )
            )
        )

        assertEquals(450.0, analytics.nutritionSummary.averageLoggedCalories!!, 0.001)
        assertEquals(0.0, analytics.nutritionSummary.averageLoggedProteinGrams!!, 0.0)
        assertEquals("Late Snack", analytics.mealDistribution.single().mealType)
        assertEquals(0f, analytics.mealDistribution.single().totals.loggedProteinGrams, 0f)
    }

    @Test
    fun `weekly review uses the most recently completed Monday through Sunday`() {
        val analytics = calculateAdvancedProgressAnalytics(
            range = AdvancedAnalyticsRange.THIRTY_DAYS,
            today = today,
            timeZone = zone,
            workoutLogs = listOf(workout(LocalCalendarDate(2026, 9, 2), durationMinutes = 30)),
            calorieLogs = listOf(calorie(LocalCalendarDate(2026, 9, 6), calories = 700))
        )

        assertEquals(LocalCalendarDate(2026, 8, 31), analytics.weeklyReview.weekStartMonday)
        assertEquals(LocalCalendarDate(2026, 9, 6), analytics.weeklyReview.weekEndSunday)
        assertEquals(1, analytics.weeklyReview.workoutTotals.sessionCount)
        assertEquals(1, analytics.weeklyReview.nutritionSummary.loggedDays)
        assertEquals(7, analytics.weeklyReview.nutritionSummary.eligibleElapsedDays)
        assertEquals(700.0, analytics.weeklyReview.nutritionSummary.averageLoggedCalories!!, 0.001)
    }

    @Test
    fun `ALL uses lifetime persisted history without fabricated previous comparison`() {
        val oldest = LocalCalendarDate(2025, 12, 31)
        val analytics = calculateAdvancedProgressAnalytics(
            range = AdvancedAnalyticsRange.ALL,
            today = today,
            timeZone = zone,
            workoutLogs = listOf(workout(oldest), workout(today)),
            calorieLogs = listOf(calorie(LocalCalendarDate(2026, 1, 2), calories = 300))
        )

        assertEquals(oldest, analytics.currentWindow.startDate)
        assertEquals(2, analytics.workoutTotals.sessionCount)
        assertNull(analytics.previousPeriodComparison)
        assertNull(analytics.trainingRestComparison)
        assertEquals(3, analytics.dailyTrend.size)
    }

    private fun workout(
        date: LocalCalendarDate,
        durationSeconds: Int = 0,
        durationMinutes: Int = 0,
        sets: Int = 0,
        volume: Double = 0.0
    ) = WorkoutLog(
        exerciseName = "Persisted workout",
        category = "Strength",
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        caloriesBurned = 0,
        completedSets = sets,
        liftingVolumeKg = volume,
        timestamp = midday(date)
    )

    private fun calorie(
        date: LocalCalendarDate,
        calories: Int,
        mealType: String = "Dinner",
        description: String = "Persisted meal",
        protein: Float = 0f,
        carbs: Float = 0f,
        fat: Float = 0f
    ) = CalorieLog(
        amount = calories,
        mealType = mealType,
        description = description,
        proteinGrams = protein,
        carbsGrams = carbs,
        fatGrams = fat,
        servings = 1f,
        timestamp = midday(date)
    )

    private fun midday(date: LocalCalendarDate): Long = Calendar.getInstance(zone).run {
        clear()
        set(date.year, date.month - 1, date.dayOfMonth, 12, 0, 0)
        timeInMillis
    }
}
