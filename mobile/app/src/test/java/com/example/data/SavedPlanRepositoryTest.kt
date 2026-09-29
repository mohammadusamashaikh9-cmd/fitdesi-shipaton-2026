package com.example.data

import com.example.ai.GeneratedDietPlan
import com.example.ai.AiCoachResponse
import com.example.ai.GeneratedDietPlanAlternative
import com.example.ai.GeneratedDietPlanDay
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.GeneratedPlanSource
import com.example.ai.PlanValidationStatus
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.toGeneratedRoutine
import com.example.ai.hasSavableGeneratedPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlanRepositoryTest {
    @Test
    fun `generated workout saves and retrieves with stable exercise IDs`() = runTest {
        val storage = FakeSavedPlanStorage()
        val repository = SavedPlanRepository(storage)
        val plan = workoutPlan()

        assertEquals(SavedPlanResult.SAVED, repository.saveWorkoutPlan(plan))
        val restored = repository.getWorkoutPlan(plan.planId)

        assertNotNull(restored)
        assertEquals("exercise-123", restored!!.days.first().exercises.first().exerciseId)
        assertEquals(
            "exercise-123",
            restored.toGeneratedRoutine().days.first().exercises.first().exerciseId
        )
        assertTrue(repository.listWorkoutPlans().any { it.planId == plan.planId })
    }

    @Test
    fun `historical saved workout plan collection remains readable with empty phases`() = runTest {
        val historicalJson = """[{"planId":"historical-workout","title":"Historical workout","goal":"General fitness","experienceLevel":"Beginner","days":[{"dayName":"Monday","focus":"Full Body","exercises":[{"exerciseId":"fd-exercise-chair-squat","name":"Chair Squat","movementPattern":"SQUAT","sets":3,"repsOrDuration":"8 reps","restSeconds":60}]}],"progressionGuidance":"Progress gradually.","recoveryGuidance":"Rest as needed.","safetyNote":"General guidance only.","createdAt":1700000000000,"sourceType":"LOCAL_KNOWLEDGE","profileContextUsed":false}]"""
        val storage = FakeSavedPlanStorage(workoutPlans = historicalJson)

        val restored = SavedPlanRepository(storage).listWorkoutPlans().single()

        assertEquals("fd-exercise-chair-squat", restored.days.single().exercises.single().exerciseId)
        assertTrue(restored.days.single().warmupExercises.isEmpty())
        assertTrue(restored.days.single().cooldownExercises.isEmpty())
        assertTrue(restored.days.single().exercises.single().rampUpSets.isEmpty())
        assertEquals(historicalJson, storage.workoutPlans)
    }

    @Test
    fun `repeated workout save does not create duplicate`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val plan = workoutPlan()

        assertEquals(SavedPlanResult.SAVED, repository.saveWorkoutPlan(plan))
        assertEquals(SavedPlanResult.ALREADY_SAVED, repository.saveWorkoutPlan(plan))
        assertEquals(1, repository.listWorkoutPlans().size)
    }

    @Test
    fun `saved workout and diet survive repository recreation and diet deletes`() = runTest {
        val storage = FakeSavedPlanStorage()
        val firstRepository = SavedPlanRepository(storage)
        val workout = workoutPlan()
        val diet = dietPlan()

        assertEquals(SavedPlanResult.SAVED, firstRepository.saveWorkoutPlan(workout))
        assertEquals(SavedPlanResult.SAVED, firstRepository.saveDietPlan(diet))

        val restartedRepository = SavedPlanRepository(storage)
        assertEquals(workout, restartedRepository.getWorkoutPlan(workout.planId))
        assertEquals(diet, restartedRepository.getDietPlan(diet.planId))
        assertTrue(restartedRepository.deleteDietPlan(diet.planId))
        assertNull(restartedRepository.getDietPlan(diet.planId))
        assertEquals(workout, restartedRepository.getWorkoutPlan(workout.planId))
    }

    @Test
    fun `planned meals do not alter consumed logs or unrelated local data`() = runTest {
        val storage = FakeSavedPlanStorage(
            unrelatedValues = mutableMapOf(
                "profile" to "existing-profile",
                "workout_logs" to "existing-workouts",
                "calorie_logs" to "existing-consumed-food"
            )
        )
        val repository = SavedPlanRepository(storage)

        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(dietPlan()))

        assertEquals("existing-profile", storage.unrelatedValues["profile"])
        assertEquals("existing-workouts", storage.unrelatedValues["workout_logs"])
        assertEquals("existing-consumed-food", storage.unrelatedValues["calorie_logs"])
    }

    @Test
    fun `malformed plans fail closed`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())

        assertEquals(
            SavedPlanResult.INVALID_PLAN,
            repository.saveWorkoutPlan(workoutPlan().copy(days = emptyList()))
        )
        assertEquals(
            SavedPlanResult.INVALID_PLAN,
            repository.saveDietPlan(dietPlan().copy(days = emptyList()))
        )
        assertTrue(repository.listWorkoutPlans().isEmpty())
        assertTrue(repository.listDietPlans().isEmpty())
    }

    @Test
    fun `future Fireworks plans use the same persistence models without provider activation`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val futurePlan = workoutPlan().copy(
            planId = "workout-fireworks-validated",
            sourceType = GeneratedPlanSource.FIREWORKS
        )

        assertEquals(SavedPlanResult.SAVED, repository.saveWorkoutPlan(futurePlan))
        assertEquals(
            GeneratedPlanSource.FIREWORKS,
            repository.getWorkoutPlan(futurePlan.planId)?.sourceType
        )
        val futureDiet = dietPlan().copy(
            planId = "diet-fireworks-validated",
            sourceType = GeneratedPlanSource.FIREWORKS
        )
        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(futureDiet))
        assertEquals(
            GeneratedPlanSource.FIREWORKS,
            repository.getDietPlan(futureDiet.planId)?.sourceType
        )
    }

    @Test
    fun `corrupt stored collections are not silently overwritten`() = runTest {
        val storage = FakeSavedPlanStorage(workoutPlans = "{malformed")
        val repository = SavedPlanRepository(storage)

        assertEquals(SavedPlanResult.ERROR, repository.saveWorkoutPlan(workoutPlan()))
        assertEquals("{malformed", storage.workoutPlans)
    }

    @Test
    fun `malformed optional structured workout data does not overwrite saved collection`() = runTest {
        val invalidMainExercises = listOf(
            workoutPlan().days.first().exercises.first().copy(
                activityPrescription = ActivityPrescription(
                    ActivityPrescriptionMode.DURATION_SECONDS,
                    durationSeconds = 0
                )
            ),
            workoutPlan().days.first().exercises.first().copy(
                rampUpSets = listOf(
                    RampUpSetPrescription(
                        ordinal = 0,
                        loadCue = RampUpLoadCue.LIGHT,
                        repetitions = 5,
                        restSeconds = 30
                    )
                )
            )
        )

        invalidMainExercises.forEachIndexed { index, invalidExercise ->
            val malformedPlan = workoutPlan().copy(
                days = workoutPlan().days.mapIndexed { dayIndex, day ->
                    if (dayIndex == 0) day.copy(exercises = listOf(invalidExercise)) else day
                }
            )
            val encoded = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GeneratedWorkoutPlan.serializer()),
                listOf(malformedPlan)
            )
            val storage = FakeSavedPlanStorage(workoutPlans = encoded)

            val result = SavedPlanRepository(storage).saveWorkoutPlan(
                workoutPlan().copy(planId = "new-plan-$index")
            )

            assertEquals(SavedPlanResult.ERROR, result)
            assertEquals(encoded, storage.workoutPlans)
        }
    }

    @Test
    fun `two diet plans remain independently readable and deleting one preserves the other`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val first = dietPlan()
        val second = dietPlan().copy(
            planId = "diet-local-456",
            title = "FitDesi Pakistani Muscle Gain Plan",
            goal = "Build muscle",
            createdAt = first.createdAt + 1
        )

        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(first))
        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(second))
        assertEquals(listOf(second, first), repository.loadDietPlans().getOrThrow())
        assertEquals(second, repository.getDietPlan(second.planId))
        assertTrue(repository.deleteDietPlan(first.planId))
        assertNull(repository.getDietPlan(first.planId))
        assertEquals(second, repository.getDietPlan(second.planId))
    }

    @Test
    fun `repeated diet save reports already saved without a duplicate`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val plan = dietPlan()

        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(plan))
        assertEquals(SavedPlanResult.ALREADY_SAVED, repository.saveDietPlan(plan))
        assertEquals(listOf(plan), repository.listDietPlans())
    }

    @Test
    fun `diet save failure never reports saved`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage(failDietWrites = true))

        assertEquals(SavedPlanResult.ERROR, repository.saveDietPlan(dietPlan()))
        assertTrue(repository.listDietPlans().isEmpty())
    }

    @Test
    fun `malformed diet collection reports a load failure`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage(dietPlans = "{malformed"))

        assertTrue(repository.loadDietPlans().isFailure)
        assertTrue(repository.listDietPlans().isEmpty())
    }

    @Test
    fun `saved diet plan retains validation metadata`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val plan = dietPlan()

        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(plan))
        assertEquals(
            PlanValidationStatus.VALIDATED,
            repository.getDietPlan(plan.planId)?.validationStatus
        )
    }

    @Test
    fun `needs adjustment diet plan remains truthfully savable and reopenable`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val plan = dietPlan().copy(
            validationStatus = PlanValidationStatus.NEEDS_ADJUSTMENT,
            validationFailures = listOf("protein 147 vs target 100")
        )
        val response = AiCoachResponse(
            summary = "Plan needs an adjustment.",
            recommendedAction = "Adjust protein before following this plan.",
            nutritionNote = "Estimates only.",
            workoutNote = "Keep training comfortable.",
            safetyDisclaimer = "General education only.",
            dietPlan = plan
        )

        assertTrue(plan.isSavable())
        assertFalse(plan.isValid())
        assertTrue(response.hasSavableGeneratedPlan())
        assertEquals(SavedPlanResult.SAVED, repository.saveDietPlan(plan))
        assertEquals(SavedPlanResult.ALREADY_SAVED, repository.saveDietPlan(plan))
        assertEquals(plan, repository.getDietPlan(plan.planId))
        assertEquals(PlanValidationStatus.NEEDS_ADJUSTMENT, repository.getDietPlan(plan.planId)?.validationStatus)
    }

    @Test
    fun `needs profile input diet plan cannot expose a save action or persist`() = runTest {
        val repository = SavedPlanRepository(FakeSavedPlanStorage())
        val plan = dietPlan().copy(validationStatus = PlanValidationStatus.NEEDS_PROFILE_INPUT)
        val response = AiCoachResponse(
            summary = "Profile input is needed.",
            recommendedAction = "Complete your profile.",
            nutritionNote = "",
            workoutNote = "",
            safetyDisclaimer = "General education only.",
            dietPlan = plan
        )

        assertFalse(plan.isSavable())
        assertFalse(response.hasSavableGeneratedPlan())
        assertEquals(SavedPlanResult.INVALID_PLAN, repository.saveDietPlan(plan))
    }

    @Test
    fun `valid workout response remains savable through the shared Coach action`() {
        assertTrue(
            AiCoachResponse(
                summary = "Workout plan",
                recommendedAction = "Follow the plan.",
                nutritionNote = "Eat regularly.",
                workoutNote = "Train with control.",
                safetyDisclaimer = "General education only.",
                workoutPlan = workoutPlan()
            ).hasSavableGeneratedPlan()
        )
    }

    private fun workoutPlan() = GeneratedWorkoutPlan(
        planId = "workout-local-123",
        title = "FitDesi Strength Plan",
        goal = "Build strength",
        experienceLevel = "Beginner",
        days = listOf(
            GeneratedWorkoutPlanDay(
                dayName = "Monday",
                focus = "Full Body",
                exercises = listOf(
                    GeneratedWorkoutPlanExercise(
                        exerciseId = "exercise-123",
                        name = "Dumbbell Goblet Squat",
                        movementPattern = "SQUAT",
                        sets = 3,
                        repsOrDuration = "8-12 reps",
                        restSeconds = 90
                    )
                )
            ),
            GeneratedWorkoutPlanDay(
                dayName = "Thursday",
                focus = "Full Body",
                exercises = listOf(
                    GeneratedWorkoutPlanExercise(
                        exerciseId = "exercise-456",
                        name = "Dumbbell Row",
                        movementPattern = "HORIZONTAL_PULL",
                        sets = 3,
                        repsOrDuration = "8-12 reps",
                        restSeconds = 90
                    )
                )
            )
        ),
        progressionGuidance = "Add repetitions before load.",
        recoveryGuidance = "Keep a rest day between sessions.",
        safetyNote = "General fitness guidance only.",
        createdAt = 1_700_000_000_000,
        sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
        profileContextUsed = true
    )

    private fun dietPlan() = GeneratedDietPlan(
        planId = "diet-local-123",
        title = "FitDesi Pakistani Fat Loss Plan",
        goal = "Lose Fat",
        calorieTarget = 1800,
        proteinTargetGrams = 120.0,
        carbsTargetGrams = null,
        fatTargetGrams = null,
        days = listOf(
            GeneratedDietPlanDay(
                dayName = "Daily plan",
                meals = listOf(
                    meal("Breakfast", "food-1", "Anda"),
                    meal("Lunch", "food-2", "Daal"),
                    meal("Dinner", "food-3", "Chicken Karahi")
                )
            )
        ),
        hydrationReminder = "Drink water regularly.",
        disclaimer = "Nutrition values are estimates.",
        createdAt = 1_700_000_000_000,
        sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
        profileContextUsed = true
    )

    private fun meal(label: String, id: String, name: String) = GeneratedDietPlanMeal(
        label = label,
        foodRecordId = id,
        foodName = name,
        storedServing = "1 serving",
        portionMultiplier = 1.0,
        estimatedCalories = 350,
        estimatedProteinGrams = 20.0,
        estimatedCarbsGrams = 30.0,
        estimatedFatGrams = 10.0,
        alternatives = listOf(GeneratedDietPlanAlternative("alt-$id", "Alternative $name"))
    )

    private class FakeSavedPlanStorage(
        var workoutPlans: String? = null,
        var dietPlans: String? = null,
        val unrelatedValues: MutableMap<String, String> = mutableMapOf(),
        private val failDietWrites: Boolean = false
    ) : SavedPlanStorage {
        override suspend fun readWorkoutPlans(): String? = workoutPlans

        override suspend fun writeWorkoutPlans(value: String) {
            workoutPlans = value
        }

        override suspend fun readDietPlans(): String? = dietPlans

        override suspend fun writeDietPlans(value: String) {
            if (failDietWrites) error("Simulated DataStore write failure")
            dietPlans = value
        }
    }
}
