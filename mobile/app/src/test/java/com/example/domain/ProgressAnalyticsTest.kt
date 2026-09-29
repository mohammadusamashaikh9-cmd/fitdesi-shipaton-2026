package com.example.domain

import com.example.data.CalorieLog
import com.example.data.WorkoutLog
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAnalyticsTest {
    private val zone = TimeZone.getTimeZone("America/New_York")

    @Test
    fun `nutrition totals round calories per entry and multiply logged macros by servings`() {
        val logs = listOf(
            calorie(amount = 101, servings = 0.5f, protein = 3f, carbs = 4f, fat = 1f),
            calorie(amount = 101, servings = 0.5f, protein = 0f, carbs = 0f, fat = 0f)
        )

        val totals = calculateProgressNutritionTotals(logs)

        assertEquals(102, totals.calories)
        assertEquals(1.5f, totals.loggedProteinGrams, 0.001f)
        assertEquals(2f, totals.loggedCarbsGrams, 0.001f)
        assertEquals(0.5f, totals.loggedFatGrams, 0.001f)
    }

    @Test
    fun `manual zero macro entry remains zero and is never inferred from description`() {
        val totals = calculateProgressNutritionTotals(
            listOf(
                calorie(
                    amount = 450,
                    description = "High protein meal with chicken",
                    protein = 0f,
                    carbs = 0f,
                    fat = 0f
                )
            )
        )

        assertEquals(450, totals.calories)
        assertEquals(0f, totals.loggedProteinGrams, 0f)
        assertEquals(0f, totals.loggedCarbsGrams, 0f)
        assertEquals(0f, totals.loggedFatGrams, 0f)
    }

    @Test
    fun `workout totals use recorded values duration fallback active dates and local date streak`() {
        val today = LocalCalendarDate(2026, 3, 9)
        val logs = listOf(
            workout(today, durationSeconds = 3_000, durationMinutes = 99, sets = 5, volume = 2_000.0),
            workout(LocalCalendarDate(2026, 3, 8), durationSeconds = 0, durationMinutes = 30, sets = 3, volume = 1_000.0),
            workout(LocalCalendarDate(2026, 3, 6), durationSeconds = -10, durationMinutes = -4, sets = -2, volume = -20.0)
        )

        val totals = calculateProgressWorkoutTotals(logs, today, zone)

        assertEquals(3, totals.sessionCount)
        assertEquals(3, totals.activeTrainingDays)
        assertEquals(2, totals.recentWindowStreak)
        assertEquals(4_800L, totals.recordedDurationSeconds)
        assertEquals(8, totals.recordedCompletedSets)
        assertEquals(3_000.0, totals.recordedLiftingVolumeKg, 0.001)
    }

    @Test
    fun `streak remains local-date based across spring and fall DST changes`() {
        val spring = listOf(
            workout(LocalCalendarDate(2026, 3, 9)),
            workout(LocalCalendarDate(2026, 3, 8)),
            workout(LocalCalendarDate(2026, 3, 7))
        )
        val fall = listOf(
            workout(LocalCalendarDate(2026, 11, 2)),
            workout(LocalCalendarDate(2026, 11, 1)),
            workout(LocalCalendarDate(2026, 10, 31))
        )

        assertEquals(3, calculateCurrentWorkoutStreak(spring, LocalCalendarDate(2026, 3, 9), zone))
        assertEquals(3, calculateCurrentWorkoutStreak(fall, LocalCalendarDate(2026, 11, 2), zone))
    }

    @Test
    fun `seven day overview keeps current streak from the full recent history window`() {
        val today = LocalCalendarDate(2026, 9, 8)
        val logs = (0..7).map { daysAgo ->
            workout(ProgressDatePolicy.minusCalendarDays(today, daysAgo, zone))
        }

        val overview = calculateProgressOverview(
            workoutLogs = logs,
            calorieLogs = emptyList(),
            startDate = ProgressDatePolicy.minusCalendarDays(today, 6, zone),
            today = today,
            rangeDays = 7,
            timeZone = zone
        )

        assertEquals(7, overview.workoutTotals.sessionCount)
        assertEquals(8, overview.workoutTotals.recentWindowStreak)
    }

    @Test
    fun `streak model remains explicitly bounded when persisted history continues beyond 30 dates`() {
        val today = LocalCalendarDate(2026, 9, 30)
        val persistedLogs = (0..30).map { daysAgo ->
            workout(ProgressDatePolicy.minusCalendarDays(today, daysAgo, zone))
        }
        val oldestRecentDate = ProgressDatePolicy.minusCalendarDays(today, 29, zone)
        val recentLogs = persistedLogs.filter {
            ProgressDatePolicy.localDateAt(it.timestamp, zone) >= oldestRecentDate
        }

        val overview = calculateProgressOverview(
            workoutLogs = recentLogs,
            calorieLogs = emptyList(),
            startDate = oldestRecentDate,
            today = today,
            rangeDays = 30,
            timeZone = zone
        )

        assertEquals(30, overview.workoutTotals.recentWindowStreak)
    }

    @Test
    fun `journal represents workout-only nutrition-only combined and empty local dates`() {
        val start = LocalCalendarDate(2026, 9, 1)
        val end = LocalCalendarDate(2026, 9, 4)
        val workoutOnly = workout(LocalCalendarDate(2026, 9, 1))
        val nutritionOnly = calorie(timestamp = midday(LocalCalendarDate(2026, 9, 2)), mealType = "Breakfast")
        val bothWorkout = workout(LocalCalendarDate(2026, 9, 3))
        val bothNutrition = calorie(timestamp = midday(LocalCalendarDate(2026, 9, 3)), mealType = "Dinner")

        val days = buildProgressJournalDays(
            startDate = start,
            endDateInclusive = end,
            workoutLogs = listOf(workoutOnly, bothWorkout),
            calorieLogs = listOf(nutritionOnly, bothNutrition),
            today = end,
            timeZone = zone
        )

        assertEquals(listOf(end, LocalCalendarDate(2026, 9, 3), LocalCalendarDate(2026, 9, 2), start), days.map { it.date })
        assertEquals(JournalDayKind.NEITHER, days[0].kind)
        assertEquals(JournalDayKind.BOTH, days[1].kind)
        assertEquals(JournalDayKind.NUTRITION_ONLY, days[2].kind)
        assertEquals(JournalDayKind.TRAINING_ONLY, days[3].kind)
    }

    @Test
    fun `journal groups nutrition by exact persisted meal type and empty day has zero totals`() {
        val date = LocalCalendarDate(2026, 9, 4)
        val logs = listOf(
            calorie(timestamp = midday(date), mealType = "Breakfast"),
            calorie(timestamp = midday(date) + 1, mealType = "breakfast"),
            calorie(timestamp = midday(date) + 2, mealType = "Snack")
        )

        val populated = buildProgressJournalDays(date, date, emptyList(), logs, date, zone).single()
        val empty = buildProgressJournalDays(date, date, emptyList(), emptyList(), date, zone).single()

        assertEquals(listOf("Breakfast", "breakfast", "Snack"), populated.mealGroups.map { it.mealType })
        assertTrue(empty.workoutEntries.isEmpty())
        assertTrue(empty.mealGroups.isEmpty())
        assertEquals(ProgressNutritionTotals(), empty.nutritionTotals)
    }

    @Test
    fun `timestamp only history produces exact streak without exposing workout details`() {
        val today = LocalCalendarDate(2026, 9, 4)
        val timestamps = listOf(
            midday(today),
            midday(LocalCalendarDate(2026, 9, 3)),
            midday(LocalCalendarDate(2026, 9, 2)),
            midday(LocalCalendarDate(2026, 8, 20))
        )

        assertEquals(3, calculateCurrentWorkoutStreakFromTimestamps(timestamps, today, zone))
    }

    private fun calorie(
        amount: Int = 100,
        servings: Float = 1f,
        description: String = "Persisted food",
        mealType: String = "Lunch",
        protein: Float = 0f,
        carbs: Float = 0f,
        fat: Float = 0f,
        timestamp: Long = midday(LocalCalendarDate(2026, 9, 4))
    ) = CalorieLog(
        amount = amount,
        mealType = mealType,
        description = description,
        proteinGrams = protein,
        carbsGrams = carbs,
        fatGrams = fat,
        servings = servings,
        timestamp = timestamp
    )

    private fun workout(
        date: LocalCalendarDate,
        durationSeconds: Int = 0,
        durationMinutes: Int = 0,
        sets: Int = 0,
        volume: Double = 0.0
    ) = WorkoutLog(
        exerciseName = "Recorded session",
        category = "Strength",
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        caloriesBurned = 0,
        completedSets = sets,
        liftingVolumeKg = volume,
        timestamp = midday(date)
    )

    private fun midday(date: LocalCalendarDate): Long = Calendar.getInstance(zone).run {
        clear()
        set(date.year, date.month - 1, date.dayOfMonth, 12, 0, 0)
        timeInMillis
    }
}
