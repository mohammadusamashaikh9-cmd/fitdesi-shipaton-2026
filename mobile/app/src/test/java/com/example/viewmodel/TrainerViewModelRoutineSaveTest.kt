package com.example.viewmodel

import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutine
import com.example.data.SavedRoutineOrigin
import com.example.data.SavedRoutineResult
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerViewModelRoutineSaveTest {
    @Test
    fun `only authoritative ready Plus or Pro state grants unlimited authored routines`() {
        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            assertTrue(
                SubscriptionState(tier, SubscriptionStatus.READY, true)
                    .hasUnlimitedAuthoredRoutineCapability()
            )
        }
        assertFalse(
            SubscriptionState(SubscriptionTier.BASIC, SubscriptionStatus.READY, true)
                .hasUnlimitedAuthoredRoutineCapability()
        )
    }

    @Test
    fun `disabled loading error and unresolved subscription states remain Basic safe`() {
        listOf(SubscriptionStatus.DISABLED, SubscriptionStatus.LOADING, SubscriptionStatus.ERROR).forEach { status ->
            assertFalse(
                SubscriptionState(SubscriptionTier.PLUS, status, true)
                    .hasUnlimitedAuthoredRoutineCapability()
            )
        }
        assertFalse(
            SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.READY, false)
                .hasUnlimitedAuthoredRoutineCapability()
        )
    }

    @Test
    fun `limit reached returns before active plan lookup persistence or refresh`() = runTest {
        var routineLookupCount = 0
        var activePlanWriteCount = 0
        var refreshCount = 0

        val result = saveRoutineAndCoordinateActivePlan(
            routine = routine("blocked"),
            origin = SavedRoutineOrigin.BUILD_ROUTINE,
            saveRoutine = { _, _ -> SavedRoutineResult.LIMIT_REACHED },
            stableRoutineId = { it.planId },
            getRoutine = {
                routineLookupCount += 1
                error("Blocked save must not look up a stored routine")
            },
            setActiveRoutine = {
                activePlanWriteCount += 1
            },
            refreshSavedRoutines = {
                refreshCount += 1
            }
        )

        assertEquals(SavedRoutineResult.LIMIT_REACHED, result)
        assertEquals(0, routineLookupCount)
        assertEquals(0, activePlanWriteCount)
        assertEquals(0, refreshCount)
    }

    @Test
    fun `saved and already saved retain active plan and refresh behavior`() = runTest {
        listOf(SavedRoutineResult.SAVED, SavedRoutineResult.ALREADY_SAVED).forEach { saveResult ->
            val incoming = routine("stable-id")
            val stored = SavedRoutine(
                routineId = "stable-id",
                routine = incoming,
                origin = SavedRoutineOrigin.CUSTOM_WORKOUT,
                createdAt = incoming.createdAt
            )
            var receivedOrigin: SavedRoutineOrigin? = null
            var activeRoutine: GeneratedRoutine? = null
            var refreshCount = 0

            val result = saveRoutineAndCoordinateActivePlan(
                routine = incoming,
                origin = SavedRoutineOrigin.CUSTOM_WORKOUT,
                saveRoutine = { _, origin ->
                    receivedOrigin = origin
                    saveResult
                },
                stableRoutineId = { it.planId },
                getRoutine = { stored },
                setActiveRoutine = { activeRoutine = it },
                refreshSavedRoutines = { refreshCount += 1 }
            )

            assertEquals(saveResult, result)
            assertEquals(SavedRoutineOrigin.CUSTOM_WORKOUT, receivedOrigin)
            assertEquals(stored.routine, activeRoutine)
            assertEquals(1, refreshCount)
        }
    }

    private fun routine(id: String) = GeneratedRoutine(
        name = "Routine $id",
        description = "Local routine",
        splitType = "Full Body",
        frequency = "1 day/week",
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Full Body",
                description = "Controlled training",
                exercises = listOf(
                    GeneratedExercise(
                        name = "Goblet Squat",
                        sets = 3,
                        reps = "8",
                        targetMuscle = "Legs",
                        instructions = "Use controlled form"
                    )
                )
            )
        ),
        planId = id,
        createdAt = 1_700_000_000_000L
    )
}
