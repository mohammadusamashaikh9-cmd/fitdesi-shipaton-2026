package com.example.viewmodel

import com.example.ai.AiCoachResponse
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import com.example.ai.toGeneratedRoutine
import com.example.data.SavedPlanRepository
import com.example.data.SavedPlanResult
import com.example.data.SavedPlanStorage
import com.example.data.SavedRoutine
import com.example.data.SavedRoutineOrigin
import com.example.data.SavedRoutineRepository
import com.example.data.SavedRoutineStorage
import com.example.ui.coachPlanSaveErrorMessage
import com.example.ui.coachPlanSaveSuccessMessage
import com.example.ui.toActiveWorkoutContext
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerViewModelCoachSaveTest {
    private val gson = Gson()

    @Test
    fun `new Coach workout Save creates one archive and one exact current routine without changing siblings`() = runTest {
        val harness = SaveHarness()
        val siblingPlan = workoutPlan(id = "existing-plan", createdAt = 1_699_999_000_000L)
        harness.seedArchive(siblingPlan)
        harness.seedRoutine(siblingPlan)
        val exactSiblingArchive = harness.planRepository.getWorkoutPlan(siblingPlan.planId)
        val exactSiblingRoutine = harness.routineRepository.getRoutine(siblingPlan.planId)
        harness.activeJson = gson.toJson(siblingPlan.toGeneratedRoutine())
        harness.resetEvidence()
        val incoming = workoutPlan()

        val result = harness.save(incoming)

        assertEquals(SavedPlanResult.SAVED, result)
        val storedPlans = harness.planRepository.listWorkoutPlans()
        assertEquals(setOf("existing-plan", "coach-plan"), storedPlans.map { it.planId }.toSet())
        assertEquals(exactSiblingArchive, storedPlans.single { it.planId == "existing-plan" })
        val storedRoutines = harness.routineRepository.loadRoutines().getOrThrow()
        assertEquals(setOf("existing-plan", "coach-plan"), storedRoutines.map { it.routineId }.toSet())
        assertEquals(exactSiblingRoutine, storedRoutines.single { it.routineId == "existing-plan" })
        val storedArchive = storedPlans.single { it.planId == "coach-plan" }
        val storedCoach = storedRoutines.single { it.routineId == "coach-plan" }
        assertEquals(SavedRoutineOrigin.AI_WORKOUT_GENERATOR, storedCoach.origin)
        assertEquals(gson.toJson(storedCoach.routine), harness.activeJson)
        assertEquals("coach-plan", storedCoach.routine.planId)
        assertEquals(
            listOf("0257", "0643", "0286", "1576", "fd-exercise-chair-squat"),
            storedCoach.routine.days.flatMap { day -> day.exercises.map { it.exerciseId } }
        )
        assertEquals(
            listOf("0257", "0643", "0286", "1576", "fd-exercise-chair-squat"),
            storedArchive.days.flatMap { day -> day.exercises.map { it.exerciseId } }
        )
        assertTrue(
            storedCoach.routine.days.flatMap { it.exercises }
                .single { it.exerciseId == "0286" }
                .rampUpSets.isEmpty()
        )
        assertEquals(
            listOf("1512"),
            storedCoach.routine.days.flatMap { day -> day.cooldownExercises.map { it.exerciseId } }
        )
        assertTrue(storedCoach.routine.days.flatMap { it.exercises }.none { it.exerciseId == "1512" })
        assertEquals(listOf("archive", "routine", "refresh", "active"), harness.events)
        val selectedDayContext = storedCoach.routine.toActiveWorkoutContext(
            selectedDayIndex = 1,
            sourceRoutineId = storedCoach.routineId
        )
        assertEquals("coach-plan", selectedDayContext?.sourceRoutineId)
        assertEquals("coach-plan", selectedDayContext?.generatedPlanId)
        assertEquals(1, selectedDayContext?.dayIndex)
    }

    @Test
    fun `same ID equivalent repeat ignores createdAt and reuses exact stored routine`() = runTest {
        val harness = SaveHarness()
        val first = workoutPlan(createdAt = 1_700_000_000_000L)
        assertEquals(SavedPlanResult.SAVED, harness.save(first))
        val exactStoredRoutine = harness.routineRepository.getRoutine(first.planId)
        assertNotNull(exactStoredRoutine)
        harness.resetEvidence()

        val result = harness.save(first.copy(createdAt = 1_800_000_000_000L))

        assertEquals(SavedPlanResult.ALREADY_SAVED, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(1, harness.planRepository.listWorkoutPlans().size)
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
        assertEquals(gson.toJson(exactStoredRoutine?.routine), harness.activeJson)
        assertEquals(listOf("refresh", "active"), harness.events)
    }

    @Test
    fun `historical wrapper and inner IDs with equivalent plan remains one compatible routine`() = runTest {
        val harness = SaveHarness()
        val archived = workoutPlan(createdAt = 1_700_000_000_000L)
        harness.seedArchive(archived)
        harness.seedHistoricalWrappedRoutine(
            wrapperId = "historical-coach-wrapper",
            plan = archived.copy(planId = "historical-coach-inner-plan")
        )
        val exactHistoricalRoutine = harness.routineRepository.loadRoutines().getOrThrow().single()
        harness.resetEvidence()

        val result = harness.save(archived.copy(createdAt = 1_800_000_000_000L))

        val storedRoutines = harness.routineRepository.loadRoutines().getOrThrow()
        assertEquals(SavedPlanResult.ALREADY_SAVED, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(1, storedRoutines.size)
        assertEquals("historical-coach-wrapper", storedRoutines.single().routineId)
        assertEquals(gson.toJson(exactHistoricalRoutine.routine), harness.activeJson)
        assertEquals(listOf("refresh", "active"), harness.events)
    }

    @Test
    fun `historical wrapper with divergent content for the same inner plan ID fails before writes`() = runTest {
        val harness = SaveHarness()
        val incoming = workoutPlan(createdAt = 1_700_000_000_000L)
        harness.seedHistoricalWrappedRoutine(
            wrapperId = "historical-coach-wrapper",
            plan = incoming.copy(title = "Divergent historical workout")
        )
        harness.resetEvidence()

        val result = harness.save(incoming)

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
        assertTrue(harness.planRepository.listWorkoutPlans().isEmpty())
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `same title with different plan identity and content remains distinct`() = runTest {
        val harness = SaveHarness()
        val first = workoutPlan(id = "coach-plan-a", createdAt = 1_700_000_000_000L)
        val second = workoutPlan(id = "coach-plan-b", createdAt = 1_800_000_000_000L).copy(
            days = first.days.mapIndexed { index, day ->
                if (index == 0) {
                    day.copy(
                        exercises = day.exercises.mapIndexed { exerciseIndex, exercise ->
                            if (exerciseIndex == 0) exercise.copy(sets = exercise.sets + 1) else exercise
                        }
                    )
                } else {
                    day
                }
            }
        )
        assertEquals(first.title, second.title)

        assertEquals(SavedPlanResult.SAVED, harness.save(first))
        assertEquals(SavedPlanResult.SAVED, harness.save(second))

        val storedRoutines = harness.routineRepository.loadRoutines().getOrThrow()
        assertEquals(setOf("coach-plan-a", "coach-plan-b"), storedRoutines.map { it.routineId }.toSet())
        assertEquals(2, storedRoutines.size)
        assertEquals(first.title, storedRoutines.map { it.routine.name }.distinct().single())
        assertTrue(storedRoutines.map { it.routine.days }.distinct().size == 2)
    }

    @Test
    fun `same ID divergent archive fails before any Stage 8A write`() = runTest {
        val harness = SaveHarness()
        harness.seedArchive(workoutPlan().copy(title = "Different archived workout"))
        harness.resetEvidence()

        val result = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
        assertTrue(harness.routineRepository.loadRoutines().getOrThrow().isEmpty())
    }

    @Test
    fun `same ID divergent routine fails before archive write`() = runTest {
        val harness = SaveHarness()
        harness.seedRoutine(workoutPlan().toGeneratedRoutine().copy(name = "Different library workout"))
        harness.resetEvidence()

        val result = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
        assertTrue(harness.planRepository.listWorkoutPlans().isEmpty())
    }

    @Test
    fun `archive write failure stops dependents and retry completes one archive and routine`() = runTest {
        val harness = SaveHarness()
        harness.planStorage.failWrites = true

        val result = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
        assertTrue(harness.routineRepository.loadRoutines().getOrThrow().isEmpty())

        harness.planStorage.failWrites = false
        harness.resetEvidence()

        assertEquals(SavedPlanResult.SAVED, harness.save(workoutPlan()))
        assertEquals(1, harness.planRepository.listWorkoutPlans().size)
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
        assertEquals(1, harness.activeWriteCount)
    }

    @Test
    fun `routine write failure retains archive and retry completes without duplicate archive`() = runTest {
        val harness = SaveHarness()
        harness.routineStorage.failWrites = true

        val result = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(listOf("coach-plan"), harness.planRepository.listWorkoutPlans().map { it.planId })
        assertTrue(harness.routineRepository.loadRoutines().getOrThrow().isEmpty())
        assertEquals(0, harness.activeWriteCount)

        harness.routineStorage.failWrites = false
        harness.resetEvidence()

        assertEquals(SavedPlanResult.SAVED, harness.save(workoutPlan()))
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(1, harness.routineStorage.writeCount)
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
        assertEquals(1, harness.activeWriteCount)
    }

    @Test
    fun `routine preflight exception fails closed before any write`() = runTest {
        val harness = SaveHarness()
        harness.routineStorage.failMigrationStatusRead = true

        val result = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
    }

    @Test
    fun `routine storage exception after archive remains retryable without duplicates`() = runTest {
        val harness = SaveHarness()
        harness.routineStorage.failLibraryReadOnCall = 2

        val firstResult = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.ERROR, firstResult)
        assertEquals(1, harness.planRepository.listWorkoutPlans().size)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)

        harness.routineStorage.failLibraryReadOnCall = null
        harness.resetEvidence()

        val retryResult = harness.save(workoutPlan())

        assertEquals(SavedPlanResult.SAVED, retryResult)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(1, harness.routineStorage.writeCount)
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
        assertEquals(1, harness.activeWriteCount)
    }

    @Test
    fun `retry after active write failure completes without duplicate persistence`() = runTest {
        val harness = SaveHarness()
        val plan = workoutPlan()
        harness.failActiveWrite = true

        val firstResult = harness.save(plan)

        assertEquals(SavedPlanResult.ERROR, firstResult)
        assertEquals(1, harness.planRepository.listWorkoutPlans().size)
        assertEquals(1, harness.routineRepository.loadRoutines().getOrThrow().size)
        assertEquals("", harness.activeJson)

        harness.failActiveWrite = false
        harness.resetEvidence()
        val retryResult = harness.save(plan.copy(createdAt = plan.createdAt + 5_000L))

        val exactStoredRoutine = harness.routineRepository.getRoutine(plan.planId)
        assertEquals(SavedPlanResult.ALREADY_SAVED, retryResult)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(gson.toJson(exactStoredRoutine?.routine), harness.activeJson)
        assertEquals(listOf("refresh", "active"), harness.events)
    }

    @Test
    fun `invalid workout returns INVALID_PLAN before persistence`() = runTest {
        val harness = SaveHarness()

        val result = harness.save(workoutPlan().copy(days = emptyList()))

        assertEquals(SavedPlanResult.INVALID_PLAN, result)
        assertEquals(0, harness.planStorage.writeCount)
        assertEquals(0, harness.routineStorage.writeCount)
        assertEquals(0, harness.activeWriteCount)
    }

    @Test
    fun `save result applies only to the exact captured Coach response`() {
        val captured = response(workoutPlan())
        val later = captured.copy()
        assertEquals(captured, later)
        assertNotSame(captured, later)

        val staleResult = applyCoachSaveResultIfCurrent(
            current = AiCoachUiState(response = later),
            capturedResponse = captured,
            result = SavedPlanResult.SAVED
        )
        val matchingResult = applyCoachSaveResultIfCurrent(
            current = AiCoachUiState(response = captured),
            capturedResponse = captured,
            result = SavedPlanResult.SAVED
        )

        assertEquals(CoachPlanSaveState.NONE, staleResult.saveState)
        assertEquals(CoachPlanSaveState.SAVED, matchingResult.saveState)
    }

    @Test
    fun `editing question during active Save preserves SAVING while allowing text changes`() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val capturedResponse = response(workoutPlan())
        val saveJob = createCoachPlanSaveJobIfIdle(currentJob = null, scope = this) {
            releaseSave.await()
        }
        assertNotNull(saveJob)
        val current = AiCoachUiState(
            question = "Original question",
            response = capturedResponse,
            errorMessage = "Old error",
            saveState = CoachPlanSaveState.SAVING,
            backendNotice = "Old notice"
        )

        val whileReserved = current.withUpdatedCoachQuestion(
            question = "Edited before start",
            currentSaveJob = saveJob
        )
        saveJob?.start()
        runCurrent()
        val whileActive = whileReserved.withUpdatedCoachQuestion(
            question = "Edited while saving",
            currentSaveJob = saveJob
        )

        assertEquals("Edited before start", whileReserved.question)
        assertEquals(CoachPlanSaveState.SAVING, whileReserved.saveState)
        assertEquals("Edited while saving", whileActive.question)
        assertEquals(CoachPlanSaveState.SAVING, whileActive.saveState)
        assertSame(capturedResponse, whileActive.response)
        assertNull(whileActive.errorMessage)
        assertNull(whileActive.backendNotice)

        releaseSave.complete(Unit)
        advanceUntilIdle()

        val afterCompletion = whileActive.withUpdatedCoachQuestion(
            question = "Edited after save",
            currentSaveJob = saveJob
        )
        assertEquals("Edited after save", afterCompletion.question)
        assertEquals(CoachPlanSaveState.NONE, afterCompletion.saveState)
    }

    @Test
    fun `editing a newer response does not inherit SAVING from an older in-flight Save`() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val responseA = response(workoutPlan(id = "coach-plan-a"))
        val responseB = response(workoutPlan(id = "coach-plan-b"))
        var current = AiCoachUiState(
            question = "Question A",
            response = responseA,
            saveState = CoachPlanSaveState.SAVING
        )
        val saveJob = createCoachPlanSaveJobIfIdle(currentJob = null, scope = this) {
            releaseSave.await()
            current = applyCoachSaveResultIfCurrent(
                current = current,
                capturedResponse = responseA,
                result = SavedPlanResult.SAVED
            )
        }
        assertNotNull(saveJob)
        saveJob?.start()
        runCurrent()

        current = current.copy(
            question = "Question B",
            response = null,
            isLoading = true,
            saveState = CoachPlanSaveState.NONE
        )
        current = current.copy(
            response = responseB,
            isLoading = false
        )
        current = current.withUpdatedCoachQuestion(
            question = "Edited question B",
            currentSaveJob = saveJob
        )

        assertEquals("Edited question B", current.question)
        assertSame(responseB, current.response)
        assertEquals(CoachPlanSaveState.NONE, current.saveState)

        releaseSave.complete(Unit)
        advanceUntilIdle()

        assertSame(responseB, current.response)
        assertEquals(CoachPlanSaveState.NONE, current.saveState)
    }

    @Test
    fun `workout Save copy names My Routines and partial retry while diet copy remains unchanged`() {
        assertEquals(
            "Saved to My Routines and set as current",
            coachPlanSaveSuccessMessage(hasWorkoutPlan = true, state = CoachPlanSaveState.SAVED)
        )
        assertEquals(
            "Already in My Routines and set as current",
            coachPlanSaveSuccessMessage(hasWorkoutPlan = true, state = CoachPlanSaveState.ALREADY_SAVED)
        )
        assertEquals(
            "The workout could not be fully saved. Any completed save steps were kept; try again to finish.",
            coachPlanSaveErrorMessage(hasWorkoutPlan = true)
        )
        assertEquals(
            "Plan saved",
            coachPlanSaveSuccessMessage(hasWorkoutPlan = false, state = CoachPlanSaveState.SAVED)
        )
        assertEquals(
            "Already saved",
            coachPlanSaveSuccessMessage(hasWorkoutPlan = false, state = CoachPlanSaveState.ALREADY_SAVED)
        )
        assertEquals("The plan could not be saved. Try again.", coachPlanSaveErrorMessage(hasWorkoutPlan = false))
    }

    @Test
    fun `second Coach Save cannot start while the first persistence job is active`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var startCount = 0
        val first = createCoachPlanSaveJobIfIdle(currentJob = null, scope = this) {
            startCount += 1
            releaseFirst.await()
        }
        assertNotNull(first)
        val whileReserved = createCoachPlanSaveJobIfIdle(currentJob = first, scope = this) {
            startCount += 1
        }
        assertNull(whileReserved)
        first?.start()
        runCurrent()

        val second = createCoachPlanSaveJobIfIdle(currentJob = first, scope = this) {
            startCount += 1
        }

        assertNull(second)
        assertEquals(1, startCount)
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertFalse(first?.isActive == true)
    }

    private fun response(plan: GeneratedWorkoutPlan) = AiCoachResponse(
        summary = "Workout ready",
        recommendedAction = "Save and train",
        nutritionNote = "Stay hydrated.",
        workoutNote = "Use controlled form.",
        safetyDisclaimer = "General guidance only.",
        workoutPlan = plan
    )

    private fun workoutPlan(
        id: String = "coach-plan",
        createdAt: Long = 1_700_000_000_000L
    ) = GeneratedWorkoutPlan(
        planId = id,
        title = "Coach strength plan",
        goal = "General fitness",
        experienceLevel = "Beginner",
        days = listOf(
            GeneratedWorkoutPlanDay(
                dayName = "Day 1",
                focus = "Lower body",
                exercises = listOf(
                    exercise("0257", "Squat"),
                    exercise("0643", "Row"),
                    exercise("0286", "Alternating Press")
                )
            ),
            GeneratedWorkoutPlanDay(
                dayName = "Day 2",
                focus = "Full body",
                exercises = listOf(
                    exercise("1576", "Press"),
                    exercise("fd-exercise-chair-squat", "Chair Squat")
                ),
                cooldownExercises = listOf(exercise("1512", "All Fours Squad Stretch"))
            )
        ),
        progressionGuidance = "Progress gradually.",
        recoveryGuidance = "Rest between sessions.",
        safetyNote = "Stop if you feel pain.",
        createdAt = createdAt,
        sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
        profileContextUsed = true
    )

    private fun exercise(id: String, name: String) = GeneratedWorkoutPlanExercise(
        exerciseId = id,
        name = name,
        movementPattern = "strength",
        sets = 3,
        repsOrDuration = "8-12 reps",
        restSeconds = 60
    )

    private inner class SaveHarness {
        val events = mutableListOf<String>()
        val planStorage = FakeSavedPlanStorage(events)
        val routineStorage = FakeSavedRoutineStorage(events)
        val planRepository = SavedPlanRepository(planStorage)
        val routineRepository = SavedRoutineRepository(routineStorage)
        var activeJson: String = ""
        var activeWriteCount: Int = 0
        var failActiveWrite: Boolean = false
        var failRefresh: Boolean = false

        suspend fun save(plan: GeneratedWorkoutPlan): SavedPlanResult = saveCoachWorkoutPlan(
            incomingPlan = plan,
            savedPlanRepository = planRepository,
            savedRoutineRepository = routineRepository,
            refreshSavedRoutines = {
                val refreshed = !failRefresh && routineRepository.loadRoutines().isSuccess
                if (refreshed) events += "refresh"
                refreshed
            },
            setActiveRoutine = { routine ->
                if (failActiveWrite) error("active write failed")
                activeWriteCount += 1
                activeJson = gson.toJson(routine)
                events += "active"
            }
        )

        suspend fun seedArchive(plan: GeneratedWorkoutPlan) {
            assertEquals(SavedPlanResult.SAVED, planRepository.saveWorkoutPlan(plan))
        }

        suspend fun seedRoutine(plan: GeneratedWorkoutPlan) {
            seedRoutine(plan.toGeneratedRoutine())
        }

        suspend fun seedRoutine(routine: com.example.ai.GeneratedRoutine) {
            assertEquals(
                com.example.data.SavedRoutineResult.SAVED,
                routineRepository.saveRoutine(routine, SavedRoutineOrigin.BUILD_ROUTINE)
            )
        }

        fun seedHistoricalWrappedRoutine(wrapperId: String, plan: GeneratedWorkoutPlan) {
            val routine = plan.toGeneratedRoutine()
            routineStorage.routineLibrary = gson.toJson(
                listOf(
                    SavedRoutine(
                        routineId = wrapperId,
                        routine = routine,
                        origin = SavedRoutineOrigin.AI_WORKOUT_GENERATOR,
                        createdAt = routine.createdAt
                    )
                )
            )
        }

        fun resetEvidence() {
            events.clear()
            planStorage.writeCount = 0
            routineStorage.writeCount = 0
            activeWriteCount = 0
        }
    }

    private class FakeSavedPlanStorage(
        private val events: MutableList<String>
    ) : SavedPlanStorage {
        var workoutPlans: String? = null
        var writeCount: Int = 0
        var failWrites: Boolean = false

        override suspend fun readWorkoutPlans(): String? = workoutPlans

        override suspend fun writeWorkoutPlans(value: String) {
            if (failWrites) error("archive write failed")
            writeCount += 1
            workoutPlans = value
            events += "archive"
        }

        override suspend fun readDietPlans(): String? = null
        override suspend fun writeDietPlans(value: String) = Unit
    }

    private class FakeSavedRoutineStorage(
        private val events: MutableList<String>
    ) : SavedRoutineStorage {
        var routineLibrary: String? = null
        var writeCount: Int = 0
        var failWrites: Boolean = false
        var libraryReadCount: Int = 0
        var failLibraryReadOnCall: Int? = null
        var failMigrationStatusRead: Boolean = false

        override suspend fun readRoutineLibrary(): String? {
            libraryReadCount += 1
            if (failLibraryReadOnCall == libraryReadCount) error("routine read failed")
            return routineLibrary
        }

        override suspend fun writeRoutineLibrary(value: String) {
            if (failWrites) error("routine write failed")
            writeCount += 1
            routineLibrary = value
            events += "routine"
        }

        override suspend fun readLegacyActiveRoutine(): String? = null
        override suspend fun isLegacyMigrationComplete(): Boolean {
            if (failMigrationStatusRead) error("migration status read failed")
            return true
        }
        override suspend fun markLegacyMigrationComplete() = Unit
    }
}
