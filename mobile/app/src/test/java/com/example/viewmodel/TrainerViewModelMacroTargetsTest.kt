package com.example.viewmodel

import com.example.data.UserProfile
import com.example.domain.calculateMacros
import com.example.domain.standardMacroRatios
import com.example.subscription.SubscriptionCapability
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import com.example.subscription.hasAuthoritativeCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerViewModelMacroTargetsTest {
    @Test
    fun `Basic macro calculation remains available before target persistence`() {
        val result = calculateMacros(2_000, 4, standardMacroRatios.single { it.id == "zone" })

        assertTrue(result.dailyCarbsGrams > 0)
        assertTrue(result.dailyProteinGrams > 0)
        assertTrue(result.dailyFatGrams > 0)
    }

    @Test
    fun `invalid targets stop before capability check and persistence`() = runTest {
        var capabilityChecks = 0
        var persistenceCalls = 0

        val result = saveNutritionTargetsWithCapability(
            calories = 0,
            carbsGrams = 200f,
            proteinGrams = 150f,
            fatGrams = 60f,
            currentProfile = UserProfile(),
            hasMacroTargetsCapability = {
                capabilityChecks += 1
                false
            },
            persistProfile = { persistenceCalls += 1 }
        )

        assertEquals(NutritionTargetSaveResult.INVALID_TARGETS, result)
        assertEquals(0, capabilityChecks)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun `valid Basic target save requires Plus without changing existing targets`() = runTest {
        val existing = UserProfile(
            name = "Existing profile",
            customCalorieGoal = 1_800,
            customCarbGoalGrams = 180f,
            customProteinGoalGrams = 140f,
            customFatGoalGrams = 55f
        )
        var stored = existing
        var persistenceCalls = 0

        val result = saveNutritionTargetsWithCapability(
            calories = 2_200,
            carbsGrams = 240f,
            proteinGrams = 170f,
            fatGrams = 65f,
            currentProfile = existing,
            hasMacroTargetsCapability = { false },
            persistProfile = {
                persistenceCalls += 1
                stored = it
            }
        )

        assertEquals(NutritionTargetSaveResult.REQUIRES_PLUS, result)
        assertEquals(0, persistenceCalls)
        assertEquals(existing, stored)
    }

    @Test
    fun `authoritative Plus and Pro persist macro targets exactly once`() = runTest {
        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            var persisted: UserProfile? = null
            var persistenceCalls = 0
            val current = UserProfile(name = "Preserved", generatedWorkoutPlan = "existing-plan")

            val result = saveNutritionTargetsWithCapability(
                calories = 2_400,
                carbsGrams = 250f,
                proteinGrams = 180f,
                fatGrams = 70f,
                currentProfile = current,
                hasMacroTargetsCapability = {
                    SubscriptionState(tier, SubscriptionStatus.READY, true)
                        .hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS)
                },
                persistProfile = {
                    persistenceCalls += 1
                    persisted = it
                }
            )

            assertEquals(NutritionTargetSaveResult.SAVED, result)
            assertEquals(1, persistenceCalls)
            assertEquals("Preserved", persisted?.name)
            assertEquals("existing-plan", persisted?.generatedWorkoutPlan)
            assertEquals(2_400, persisted?.customCalorieGoal)
            assertEquals(250f, persisted?.customCarbGoalGrams)
            assertEquals(180f, persisted?.customProteinGoalGrams)
            assertEquals(70f, persisted?.customFatGoalGrams)
        }
    }

    @Test
    fun `paid transition alone does not persist and deliberate second save persists once`() = runTest {
        var hasCapability = false
        var persistenceCalls = 0
        val save: suspend () -> NutritionTargetSaveResult = {
            saveNutritionTargetsWithCapability(
                calories = 2_000,
                carbsGrams = 220f,
                proteinGrams = 150f,
                fatGrams = 60f,
                currentProfile = UserProfile(),
                hasMacroTargetsCapability = { hasCapability },
                persistProfile = { persistenceCalls += 1 }
            )
        }

        assertEquals(NutritionTargetSaveResult.REQUIRES_PLUS, save())
        hasCapability = true
        assertEquals(0, persistenceCalls)

        assertEquals(NutritionTargetSaveResult.SAVED, save())
        assertEquals(1, persistenceCalls)
    }

    @Test
    fun `persistence failure remains a typed error`() = runTest {
        val result = saveNutritionTargetsWithCapability(
            calories = 2_000,
            carbsGrams = 220f,
            proteinGrams = 150f,
            fatGrams = 60f,
            currentProfile = UserProfile(),
            hasMacroTargetsCapability = { true },
            persistProfile = { error("Synthetic persistence failure") }
        )

        assertEquals(NutritionTargetSaveResult.ERROR, result)
    }

    @Test
    fun `non-authoritative states cannot grant Generator or macro capabilities`() {
        listOf(SubscriptionStatus.DISABLED, SubscriptionStatus.LOADING, SubscriptionStatus.ERROR).forEach { status ->
            val state = SubscriptionState(SubscriptionTier.PRO, status, true)
            assertFalse(state.hasAuthoritativeCapability(SubscriptionCapability.AI_WORKOUT_GENERATION))
            assertFalse(state.hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS))
        }
        val unresolved = SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.READY, false)
        assertFalse(unresolved.hasAuthoritativeCapability(SubscriptionCapability.AI_WORKOUT_GENERATION))
        assertFalse(unresolved.hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS))
    }

    @Test
    fun `authoritative Basic denies while Plus and Pro grant Generator and macro capabilities`() {
        val basic = SubscriptionState(SubscriptionTier.BASIC, SubscriptionStatus.READY, true)
        assertFalse(basic.hasAuthoritativeCapability(SubscriptionCapability.AI_WORKOUT_GENERATION))
        assertFalse(basic.hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS))

        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            val paid = SubscriptionState(tier, SubscriptionStatus.READY, true)
            assertTrue(paid.hasAuthoritativeCapability(SubscriptionCapability.AI_WORKOUT_GENERATION))
            assertTrue(paid.hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS))
        }
    }
}
