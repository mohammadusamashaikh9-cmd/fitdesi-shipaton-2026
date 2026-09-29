package com.example.ui

import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutine
import com.example.data.SavedRoutineOrigin
import com.example.data.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardScreenTest {
    @Test
    fun `library backed active plan is eligible for My Routines continuity action`() {
        val activePlan = generatedRoutine(id = "routine-library")
        val savedRoutine = SavedRoutine(
            routineId = "routine-library",
            routine = activePlan,
            origin = SavedRoutineOrigin.BUILD_ROUTINE,
            createdAt = 1_700_000_000_000L,
        )

        assertTrue(activePlanIsBackedBySavedRoutine(activePlan, listOf(savedRoutine)))
    }

    @Test
    fun `active plan without exact saved routine ownership is not eligible for library action`() {
        val savedPlan = generatedRoutine(id = "routine-library")
        val savedRoutine = SavedRoutine(
            routineId = "routine-library",
            routine = savedPlan,
            origin = SavedRoutineOrigin.BUILD_ROUTINE,
            createdAt = 1_700_000_000_000L,
        )
        val coachOnlyPlan = generatedRoutine(id = "coach-plan")
        val modifiedCurrentPlan = savedPlan.copy(description = "Current plan was modified outside the library")

        assertFalse(activePlanIsBackedBySavedRoutine(coachOnlyPlan, listOf(savedRoutine)))
        assertFalse(activePlanIsBackedBySavedRoutine(modifiedCurrentPlan, listOf(savedRoutine)))
        assertFalse(activePlanIsBackedBySavedRoutine(savedPlan.copy(planId = ""), listOf(savedRoutine)))
    }

    @Test
    fun `workout facts use sessions duration and completed sets without calorie estimates`() {
        val logs = listOf(
            workoutLog(
                name = "Morning strength",
                durationMinutes = 10,
                durationSeconds = 45,
                completedSets = 3,
                caloriesBurned = 700,
            ),
            workoutLog(
                name = "Mobility",
                durationMinutes = 2,
                completedSets = 4,
                caloriesBurned = 900,
            ),
        )

        val facts = homeWorkoutFacts(logs)
        val rowDetails = homeWorkoutRowDetails(logs.first())

        assertEquals(2, facts.sessionCount)
        assertEquals(165, facts.durationSeconds)
        assertEquals(7, facts.completedSets)
        assertEquals("Strength • 45s • 3 completed sets", rowDetails)
        assertFalse(rowDetails.contains("kcal", ignoreCase = true))
        assertFalse(rowDetails.contains("700"))
    }

    @Test
    fun `active plan presentation uses persisted identity and real completed facts`() {
        val planJson = """
            {
              "name": "Strength Foundation",
              "description": "A persisted plan",
              "splitType": "Full Body",
              "frequency": "3 days per week",
              "days": []
            }
        """.trimIndent()

        val presentation = homeWorkoutPlanPresentation(
            activePlanJson = planJson,
            todayWorkouts = listOf(workoutLog(completedSets = 5)),
        )

        assertTrue(presentation is HomeWorkoutPlanPresentation.ActivePlan)
        presentation as HomeWorkoutPlanPresentation.ActivePlan
        assertEquals("Strength Foundation", presentation.planName)
        assertEquals("3 days per week", presentation.frequency)
        assertEquals(0, presentation.dayCount)
        assertEquals(1, presentation.workoutFacts.sessionCount)
        assertEquals(5, presentation.workoutFacts.completedSets)
    }

    @Test
    fun `blank and malformed active plans fail safely to no active plan`() {
        listOf(
            "",
            "not-json",
            "{\"name\":null,\"days\":null}",
            "{\"name\":\"Broken plan\",\"days\":null}",
        ).forEach { planJson ->
            val presentation = homeWorkoutPlanPresentation(planJson, emptyList())

            assertTrue(planJson, presentation is HomeWorkoutPlanPresentation.NoActivePlan)
            assertEquals(0, presentation.workoutFacts.sessionCount)
        }
    }

    private fun workoutLog(
        name: String = "Tracked workout",
        durationMinutes: Int = 20,
        durationSeconds: Int = 0,
        completedSets: Int = 0,
        caloriesBurned: Int = 0,
    ) = WorkoutLog(
        exerciseName = name,
        category = "Strength",
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        caloriesBurned = caloriesBurned,
        completedSets = completedSets,
    )

    private fun generatedRoutine(id: String) = GeneratedRoutine(
        name = "Strength Foundation",
        description = "Persisted routine",
        splitType = "Full Body",
        frequency = "3 days per week",
        days = listOf(
            GeneratedDay(
                dayName = "Monday",
                title = "Full Body",
                description = "Tracked strength session",
                exercises = listOf(
                    GeneratedExercise(
                        name = "Chair Squat",
                        sets = 3,
                        reps = "8-10",
                        targetMuscle = "Upper legs",
                        instructions = "Use a controlled range.",
                        exerciseId = "test-exercise-1",
                    ),
                ),
            ),
        ),
        planId = id,
        createdAt = 1_700_000_000_000L,
    )
}
