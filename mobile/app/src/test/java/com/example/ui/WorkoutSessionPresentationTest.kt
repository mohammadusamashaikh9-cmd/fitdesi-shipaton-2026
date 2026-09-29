package com.example.ui

import com.example.data.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionPresentationTest {

    @Test
    fun metrics_useOnlyCompletedValidSets() {
        val exercises = listOf(
            TrackedExercise(
                id = "squat",
                name = "Squat",
                category = "Strength",
                sets = listOf(
                    TrackedSet(id = "1", previous = "", weight = "60", reps = "5", isDone = true),
                    TrackedSet(id = "2", previous = "", weight = "60", reps = "5", isDone = false),
                    TrackedSet(id = "3", previous = "", weight = "", reps = "5", isDone = true)
                )
            ),
            TrackedExercise(
                id = "row",
                name = "Row",
                category = "Strength",
                sets = listOf(
                    TrackedSet(id = "4", previous = "", weight = "40", reps = "10", isDone = true)
                )
            )
        )

        val metrics = calculateWorkoutSessionMetrics(exercises, weightsAreKg = true)

        assertEquals(2, metrics.exerciseCount)
        assertEquals(1, metrics.completedExerciseCount)
        assertEquals(3, metrics.completedSets)
        assertEquals(4, metrics.totalSets)
        assertEquals(700.0, metrics.volumeKg, 0.001)
        assertEquals(0.75f, metrics.progress, 0.001f)
    }

    @Test
    fun metrics_convertPoundsToKilogramsOnce() {
        val exercises = listOf(
            TrackedExercise(
                id = "press",
                name = "Press",
                category = "Strength",
                sets = listOf(
                    TrackedSet(id = "1", previous = "", weight = "220.462", reps = "2", isDone = true)
                )
            )
        )

        val metrics = calculateWorkoutSessionMetrics(exercises, weightsAreKg = false)

        assertEquals(200.0, metrics.volumeKg, 0.01)
    }

    @Test
    fun durationFormatting_supportsShortAndLongSessions() {
        assertEquals("05:09", formatSessionDuration(309))
        assertEquals("1:02:03", formatSessionDuration(3723))
        assertTrue(formatSessionDuration(0).isNotBlank())
    }

    @Test
    fun setStateVolumeAndRestTimerBehaviorRemainUnchanged() {
        val completedSet = TrackedSet(
            id = "completed-set",
            previous = "55 kg",
            weight = "60",
            reps = "5",
            isDone = true
        )
        val exercise = TrackedExercise(
            id = "tracked-exercise",
            name = "Canonical Exercise",
            category = "Strength",
            sets = listOf(completedSet)
        )

        val metrics = calculateWorkoutSessionMetrics(listOf(exercise), weightsAreKg = true)
        val startedRest = restTimerAfterSetCompletion(
            isCompleted = true,
            currentRemainingSeconds = 12,
            isCurrentlyPaused = true
        )
        val unchangedRest = restTimerAfterSetCompletion(
            isCompleted = false,
            currentRemainingSeconds = 12,
            isCurrentlyPaused = true
        )

        assertEquals("55 kg", completedSet.previous)
        assertEquals("60", completedSet.weight)
        assertEquals("5", completedSet.reps)
        assertTrue(completedSet.isDone)
        assertEquals(1, metrics.completedSets)
        assertEquals(300.0, metrics.volumeKg, 0.001)
        assertEquals(TRACK_WORKOUT_REST_SECONDS, startedRest.remainingSeconds ?: -1)
        assertFalse(startedRest.isPaused)
        assertEquals(12, unchangedRest.remainingSeconds ?: -1)
        assertTrue(unchangedRest.isPaused)
    }

    @Test
    fun workoutCompletionSaveGateRemainsIdempotent() {
        assertTrue(canAttemptWorkoutSave(isSavingWorkout = false, hasSavedWorkout = false))
        assertFalse(canAttemptWorkoutSave(isSavingWorkout = true, hasSavedWorkout = false))
        assertFalse(canAttemptWorkoutSave(isSavingWorkout = false, hasSavedWorkout = true))
        assertFalse(canAttemptWorkoutSave(isSavingWorkout = true, hasSavedWorkout = true))
    }

    @Test
    fun rootBottomNavigationIsHiddenOnlyDuringActiveTraining() {
        assertTrue(shouldShowRootBottomNavigation(isTrainingMode = false))
        assertFalse(shouldShowRootBottomNavigation(isTrainingMode = true))
    }

    @Test
    fun compactWidthsAndLargeTextUseStackedSetCards() {
        assertTrue(shouldUseStackedSetCards(screenWidthDp = 360, fontScale = 1f))
        assertTrue(shouldUseStackedSetCards(screenWidthDp = 420, fontScale = 1.5f))
        assertFalse(shouldUseStackedSetCards(screenWidthDp = 420, fontScale = 1f))
    }

    @Test
    fun existingMinuteOnlyHistoryRecordRemainsReadable() {
        val legacyHistory = WorkoutLog(
            exerciseName = "Legacy workout",
            category = "Strength",
            durationMinutes = 12,
            caloriesBurned = 0
        )

        assertEquals("Legacy workout", legacyHistory.exerciseName)
        assertEquals("Strength", legacyHistory.category)
        assertEquals(720, legacyHistory.recordedDurationSeconds())
        assertEquals(0, legacyHistory.completedSets)
        assertEquals(0.0, legacyHistory.liftingVolumeKg, 0.0)
    }
}
