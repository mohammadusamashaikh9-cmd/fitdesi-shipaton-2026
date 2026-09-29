package com.example.ui

import com.example.subscription.SubscriptionCapability
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import com.example.subscription.hasAuthoritativeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionProgressPresentationTest {
    @Test
    fun `Basic keeps calorie progress and consumed macros while stored macro targets stay hidden`() {
        val basicState = SubscriptionState(
            tier = SubscriptionTier.BASIC,
            status = SubscriptionStatus.READY,
            hasAuthoritativeCustomerInfo = true
        )

        val presentation = nutritionProgressPresentation(
            consumedCalories = 650,
            calorieTarget = 2_000,
            consumedProteinGrams = 42f,
            consumedCarbsGrams = 96f,
            consumedFatGrams = 28f,
            proteinTargetGrams = 120f,
            carbsTargetGrams = 240f,
            fatTargetGrams = 70f,
            showMacroTargetProgress = basicState.hasAuthoritativeCapability(
                SubscriptionCapability.MACRO_TARGETS
            )
        )

        assertEquals(650, presentation.calories.consumed)
        assertEquals(2_000, presentation.calories.target ?: -1)
        assertEquals(0.325f, presentation.calories.progress ?: -1f, 0.0001f)
        assertEquals(42f, presentation.protein.consumedGrams, 0.0001f)
        assertEquals(96f, presentation.carbs.consumedGrams, 0.0001f)
        assertEquals(28f, presentation.fat.consumedGrams, 0.0001f)
        assertNull(presentation.protein.targetGrams)
        assertNull(presentation.carbs.targetGrams)
        assertNull(presentation.fat.targetGrams)
        assertNull(presentation.protein.progress)
        assertNull(presentation.carbs.progress)
        assertNull(presentation.fat.progress)
        assertFalse(presentation.canShowMacroTargets)
    }

    @Test
    fun `authoritative Plus and Pro retain target based macro presentation`() {
        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            val paidState = SubscriptionState(
                tier = tier,
                status = SubscriptionStatus.READY,
                hasAuthoritativeCustomerInfo = true
            )

            val presentation = nutritionProgressPresentation(
                consumedCalories = 650,
                calorieTarget = 2_000,
                consumedProteinGrams = 60f,
                consumedCarbsGrams = 60f,
                consumedFatGrams = 35f,
                proteinTargetGrams = 120f,
                carbsTargetGrams = 240f,
                fatTargetGrams = 70f,
                showMacroTargetProgress = paidState.hasAuthoritativeCapability(
                    SubscriptionCapability.MACRO_TARGETS
                )
            )

            assertTrue(presentation.canShowMacroTargets)
            assertEquals(120f, presentation.protein.targetGrams ?: -1f, 0.0001f)
            assertEquals(240f, presentation.carbs.targetGrams ?: -1f, 0.0001f)
            assertEquals(70f, presentation.fat.targetGrams ?: -1f, 0.0001f)
            assertEquals(0.5f, presentation.protein.progress ?: -1f, 0.0001f)
            assertEquals(0.25f, presentation.carbs.progress ?: -1f, 0.0001f)
            assertEquals(0.5f, presentation.fat.progress ?: -1f, 0.0001f)
        }
    }

    @Test
    fun `paid looking but non authoritative state remains Basic safe`() {
        val nonAuthoritativeStates = listOf(
            SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.DISABLED, true),
            SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.LOADING, true),
            SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.ERROR, true),
            SubscriptionState(SubscriptionTier.PRO, SubscriptionStatus.READY, false)
        )

        nonAuthoritativeStates.forEach { state ->
            val presentation = nutritionProgressPresentation(
                consumedCalories = 400,
                calorieTarget = 1_800,
                consumedProteinGrams = 30f,
                consumedCarbsGrams = 75f,
                consumedFatGrams = 20f,
                proteinTargetGrams = 100f,
                carbsTargetGrams = 200f,
                fatTargetGrams = 60f,
                showMacroTargetProgress = state.hasAuthoritativeCapability(
                    SubscriptionCapability.MACRO_TARGETS
                )
            )

            assertFalse(presentation.canShowMacroTargets)
            assertEquals(30f, presentation.protein.consumedGrams, 0.0001f)
            assertNull(presentation.protein.targetGrams)
            assertNull(presentation.protein.progress)
            assertEquals(1_800, presentation.calories.target ?: -1)
        }
    }

    @Test
    fun `paid macro presentation omits unavailable targets without hiding consumed grams`() {
        val presentation = nutritionProgressPresentation(
            consumedCalories = 500,
            calorieTarget = 0,
            consumedProteinGrams = 35f,
            consumedCarbsGrams = 80f,
            consumedFatGrams = 22f,
            proteinTargetGrams = 0f,
            carbsTargetGrams = 0f,
            fatTargetGrams = 0f,
            showMacroTargetProgress = true
        )

        assertEquals(35f, presentation.protein.consumedGrams, 0.0001f)
        assertEquals(80f, presentation.carbs.consumedGrams, 0.0001f)
        assertEquals(22f, presentation.fat.consumedGrams, 0.0001f)
        assertNull(presentation.protein.targetGrams)
        assertNull(presentation.protein.progress)
        assertNull(presentation.calories.target)
        assertNull(presentation.calories.progress)
    }
}
