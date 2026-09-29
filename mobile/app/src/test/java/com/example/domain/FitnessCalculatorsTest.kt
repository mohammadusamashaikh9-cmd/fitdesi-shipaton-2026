package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessCalculatorsTest {
    @Test
    fun zoneRatioForThreeThousandCaloriesProducesExpectedTargets() {
        val zone = standardMacroRatios.single { it.id == "zone" }
        val result = calculateMacros(calories = 3_000, mealsPerDay = 3, ratio = zone)

        assertEquals(300.0, result.dailyCarbsGrams, 0.01)
        assertEquals(225.0, result.dailyProteinGrams, 0.01)
        assertEquals(100.0, result.dailyFatGrams, 0.01)
        assertEquals(100.0, result.carbsPerMealGrams, 0.01)
        assertEquals(75.0, result.proteinPerMealGrams, 0.01)
        assertEquals(33.33, result.fatPerMealGrams, 0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun macrosRejectZeroCalories() {
        calculateMacros(0, 3, standardMacroRatios.first())
    }

    @Test
    fun epleyEstimateAndTrainingLoadsUseSelectedUnit() {
        val result = calculateOneRepMax(weight = 20.0, reps = 10, unit = WeightUnit.KG)

        assertEquals(26.67, result.estimatedOneRepMax, 0.01)
        assertEquals(13.33, result.trainingLoads.single { it.percent == 50 }.weight, 0.01)
        assertEquals(25.33, result.trainingLoads.single { it.percent == 95 }.weight, 0.01)
        assertEquals(WeightUnit.KG, result.unit)
        assertFalse(result.isHighRepEstimate)
        assertEquals(
            listOf("Warm up", "Warm up", "Endurance", "Endurance", "Hypertrophy", "Hypertrophy", "Strength", "Strength", "Power", "Power"),
            result.trainingLoads.map { it.focus }
        )
    }

    @Test
    fun oneRepUsesEnteredWeightAndHighRepsAreFlagged() {
        assertEquals(100.0, calculateOneRepMax(100.0, 1, WeightUnit.LBS).estimatedOneRepMax, 0.0)
        assertTrue(calculateOneRepMax(100.0, 15, WeightUnit.LBS).isHighRepEstimate)
    }

    @Test
    fun unitConversionRoundTrips() {
        val pounds = convertWeight(20.0, WeightUnit.KG, WeightUnit.LBS)
        assertEquals(44.09, pounds, 0.01)
        assertEquals(20.0, convertWeight(pounds, WeightUnit.LBS, WeightUnit.KG), 0.01)
    }
}
