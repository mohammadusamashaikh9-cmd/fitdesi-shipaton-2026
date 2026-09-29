package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressAnalyticsV2Test {
    private val zone = TimeZone.getTimeZone("UTC")

    @Test
    fun weeklyNutritionUsesAveragePerLoggedDayAndPreservesMissingBuckets() {
        val today = LocalCalendarDate(2026, 8, 10)
        val selection = ProgressDateSelection.Range(LocalCalendarDate(2026, 6, 1), today)
        val logs = listOf(
            calorie(LocalCalendarDate(2026, 6, 1), 100),
            calorie(LocalCalendarDate(2026, 6, 2), 300),
            calorie(LocalCalendarDate(2026, 6, 15), 500)
        )

        val result = calculateProgressAnalyticsV2(selection, today, zone, emptyList(), logs)

        assertEquals(ProgressBucketGranularity.WEEK, result.granularity)
        assertEquals(200.0, result.buckets.first().averageLoggedCalories!!, 0.001)
        assertEquals(2, result.buckets.first().loggedDays)
        assertEquals(7, result.buckets.first().eligibleDays)
        assertNull(result.buckets[1].averageLoggedCalories)
    }

    @Test
    fun finiteCustomRangeGetsEqualAdjacentPreviousComparison() {
        val today = LocalCalendarDate(2026, 9, 4)
        val selection = ProgressDateSelection.Range(LocalCalendarDate(2026, 8, 29), today)
        val workouts = listOf(
            workout(LocalCalendarDate(2026, 9, 1)),
            workout(LocalCalendarDate(2026, 8, 28)),
            workout(LocalCalendarDate(2026, 8, 27))
        )

        val result = calculateProgressAnalyticsV2(selection, today, zone, workouts, emptyList())

        assertEquals(1.0, result.previousPeriodComparison!!.workoutSessions.current, 0.0)
        assertEquals(2.0, result.previousPeriodComparison.workoutSessions.previous, 0.0)
        assertEquals(LocalCalendarDate(2026, 8, 22), result.previousPeriodComparison.previousWindow.startDate)
        assertEquals(LocalCalendarDate(2026, 8, 28), result.previousPeriodComparison.previousWindow.endDateInclusive)
    }

    @Test
    fun allHistoryNutritionCoverageStartsAtFirstCalorieDateAndTrainingRestIsFiniteOnly() {
        val today = LocalCalendarDate(2026, 4, 30)
        val workouts = listOf(workout(LocalCalendarDate(2026, 1, 2)))
        val calories = listOf(
            calorie(LocalCalendarDate(2026, 4, 1), 100),
            calorie(today, 300)
        )

        val result = calculateProgressAnalyticsV2(
            ProgressDateSelection.AllHistory,
            today,
            zone,
            workouts,
            calories
        )

        assertEquals(2, result.nutritionSummary.loggedDays)
        assertEquals(30, result.nutritionSummary.eligibleElapsedDays)
        assertEquals(200.0, result.nutritionSummary.averageLoggedCalories!!, 0.001)
        assertNull(result.trainingRestComparison)

        val withoutNutrition = calculateProgressAnalyticsV2(
            ProgressDateSelection.AllHistory,
            today,
            zone,
            workouts,
            emptyList()
        )
        assertEquals(0, withoutNutrition.nutritionSummary.eligibleElapsedDays)
        assertEquals(0, withoutNutrition.nutritionSummary.loggedDays)
        assertNull(withoutNutrition.nutritionSummary.averageLoggedCalories)
    }

    @Test
    fun allHistoryMonthlyBucketsPreserveEmptyCalendarMonths() {
        val today = LocalCalendarDate(2026, 4, 30)
        val result = calculateProgressAnalyticsV2(
            ProgressDateSelection.AllHistory,
            today,
            zone,
            listOf(
                workout(LocalCalendarDate(2026, 1, 10)),
                workout(LocalCalendarDate(2026, 4, 10))
            ),
            listOf(
                calorie(LocalCalendarDate(2026, 1, 10), 200),
                calorie(LocalCalendarDate(2026, 4, 10), 400)
            )
        )

        assertEquals(listOf("1/2026", "2/2026", "3/2026", "4/2026"), result.buckets.map { it.label })
        assertEquals(listOf(1, 0, 0, 1), result.buckets.map { it.workoutSessions })
        assertEquals(listOf(200.0, null, null, 400.0), result.buckets.map { it.averageLoggedCalories })
    }

    private fun millis(date: LocalCalendarDate): Long =
        ProgressDatePolicy.startOfLocalDateEpochMillis(date, zone) + 12 * 60 * 60 * 1000L

    private fun calorie(date: LocalCalendarDate, amount: Int) = CalorieLog(
        amount = amount,
        mealType = "Lunch",
        description = "Saved meal",
        timestamp = millis(date)
    )

    private fun workout(date: LocalCalendarDate) = WorkoutLog(
        exerciseName = "Saved workout",
        category = "Strength",
        durationMinutes = 30,
        caloriesBurned = 0,
        timestamp = millis(date)
    )
}
