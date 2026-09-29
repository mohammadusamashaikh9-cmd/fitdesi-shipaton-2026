package com.example.ui

import com.example.data.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutDurationFormattingTest {
    @Test
    fun exactSecondsAreUsedForShortWorkout() {
        val workout = workoutLog(durationMinutes = 0, durationSeconds = 37)

        assertEquals(37, workout.recordedDurationSeconds())
        assertEquals("37s", workout.formattedRecordedDuration())
    }

    @Test
    fun legacyMinuteOnlyWorkoutRemainsReadable() {
        val workout = workoutLog(durationMinutes = 2, durationSeconds = 0)

        assertEquals(120, workout.recordedDurationSeconds())
        assertEquals("2m 0s", workout.formattedRecordedDuration())
    }

    private fun workoutLog(durationMinutes: Int, durationSeconds: Int) = WorkoutLog(
        exerciseName = "Test workout",
        category = "Strength",
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        caloriesBurned = 0
    )
}
