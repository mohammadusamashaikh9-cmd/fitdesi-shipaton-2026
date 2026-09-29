package com.example.viewmodel

import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutineRepository
import com.example.data.SavedRoutineStorage
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRoutineDeletionTest {
    private val gson = Gson()
    private val identityRepository = SavedRoutineRepository(IdentityOnlyStorage())

    @Test
    fun `deleting active routine clears active slot and refreshes saved list`() = runTest {
        val active = routine("active-plan", "Active plan")
        val savedIds = mutableListOf("active-plan", "other-plan")
        var activeJson = gson.toJson(active)
        var displayedIds = savedIds.toList()

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "active-plan",
            deletedRoutine = active,
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { id -> savedIds.remove(id) },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = { displayedIds = savedIds.toList() }
        )

        assertTrue(deleted)
        assertEquals("", activeJson)
        assertEquals(listOf("other-plan"), displayedIds)
    }

    @Test
    fun `deleting non-active routine preserves exact active plan JSON`() = runTest {
        val originalActiveJson = gson.toJson(routine("active-plan", "Active plan"))
        val savedIds = mutableListOf("active-plan", "other-plan")
        var activeJson = originalActiveJson
        var displayedIds = savedIds.toList()

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "other-plan",
            deletedRoutine = routine("other-plan", "Other plan"),
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { id -> savedIds.remove(id) },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = { displayedIds = savedIds.toList() }
        )

        assertTrue(deleted)
        assertEquals(originalActiveJson, activeJson)
        assertEquals(listOf("active-plan"), displayedIds)
    }

    @Test
    fun `unknown routine changes neither active slot nor displayed list`() = runTest {
        val originalActiveJson = gson.toJson(routine("active-plan", "Active plan"))
        var activeJson = originalActiveJson
        var refreshCount = 0

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "unknown-plan",
            deletedRoutine = routine("unknown-plan", "Unknown plan"),
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { false },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = { refreshCount += 1 }
        )

        assertFalse(deleted)
        assertEquals(originalActiveJson, activeJson)
        assertEquals(0, refreshCount)
    }

    @Test
    fun `historical blank plan ID is compared through stable identity`() = runTest {
        val historicalActive = routine("", "Historical active plan")
        val stableId = identityRepository.stableRoutineId(historicalActive)
        var activeJson = gson.toJson(historicalActive)

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = stableId,
            deletedRoutine = historicalActive.copy(planId = stableId),
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { true },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = {}
        )

        assertTrue(deleted)
        assertEquals("", activeJson)
    }

    @Test
    fun `historical wrapper ID clears active plan through inner stable identity`() = runTest {
        val historicalActive = routine("historical-plan-id", "Historical active plan")
        var activeJson = gson.toJson(historicalActive)

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "historical-wrapper-id",
            deletedRoutine = historicalActive,
            deletedRoutineStableId = "historical-plan-id",
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { id -> id == "historical-wrapper-id" },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = {}
        )

        assertTrue(deleted)
        assertEquals("", activeJson)
    }

    @Test
    fun `same ID different content deletion preserves active plan`() = runTest {
        val active = routine("shared-plan", "Active Coach plan")
        val saved = routine("shared-plan", "Different library plan")
        val originalActiveJson = gson.toJson(active)
        var activeJson = originalActiveJson

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "shared-plan",
            deletedRoutine = saved,
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { true },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = {}
        )

        assertTrue(deleted)
        assertEquals(originalActiveJson, activeJson)
    }

    @Test
    fun `matching content with different createdAt clears active plan`() = runTest {
        val active = routine("shared-plan", "Coach plan").copy(createdAt = 1_800_000_000_000L)
        val saved = active.copy(createdAt = 1_700_000_000_000L)
        var activeJson = gson.toJson(active)

        val deleted = deleteSavedRoutineAndCoordinateActivePlan(
            routineId = "shared-plan",
            deletedRoutine = saved,
            activePlanJson = activeJson,
            stableRoutineId = identityRepository::stableRoutineId,
            deleteRoutine = { true },
            clearActivePlan = { activeJson = "" },
            refreshSavedRoutines = {}
        )

        assertTrue(deleted)
        assertEquals("", activeJson)
    }

    private fun routine(id: String, name: String) = GeneratedRoutine(
        name = name,
        description = "Local routine",
        splitType = "Full Body",
        frequency = "2 days/week",
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Full Body",
                description = "Controlled training",
                exercises = listOf(
                    GeneratedExercise(
                        name = "Chair Squat",
                        sets = 3,
                        reps = "8-12",
                        targetMuscle = "Legs",
                        instructions = "Move with control.",
                        exerciseId = "fd-exercise-chair-squat"
                    )
                )
            )
        ),
        planId = id,
        createdAt = 1_700_000_000_000L
    )

    private class IdentityOnlyStorage : SavedRoutineStorage {
        override suspend fun readRoutineLibrary(): String? = null
        override suspend fun writeRoutineLibrary(value: String) = Unit
        override suspend fun readLegacyActiveRoutine(): String? = null
        override suspend fun isLegacyMigrationComplete(): Boolean = true
        override suspend fun markLegacyMigrationComplete() = Unit
    }
}
