package com.example.domain

import com.example.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieEngineTest {
    private val verifiedInput = CalorieCalculationInput(
        ageYears = 24,
        sex = CalorieSex.MALE,
        heightCm = 159.0,
        weightKg = 60.0,
        activityLevel = LifestyleActivityLevel.MODERATE,
        goal = CalorieGoal.GAIN_MUSCLE,
        experience = TrainingExperience.INTERMEDIATE
    )

    @Test
    fun `verified profile uses exact Mifflin values and one final rounding boundary`() {
        val result = CalorieEngine.calculate(verifiedInput, calculatedAtEpochMillis = 1L)!!

        assertEquals(1478.75, result.bmrEstimate, 0.0001)
        assertEquals(1479, result.bmrEstimate.roundToDisplay())
        assertEquals(2292.0625, result.maintenanceCalories, 0.0001)
        assertEquals(0.075, result.goalAdjustmentPercent, 0.0001)
        assertEquals(2463.9671875, result.finalCalorieTarget, 0.0001)
        assertEquals(2464, result.roundedDisplayCalories)
    }

    @Test
    fun `female equation uses the female constant`() {
        val result = CalorieEngine.calculate(verifiedInput.copy(sex = CalorieSex.FEMALE))!!
        assertEquals(1312.75, result.bmrEstimate, 0.0001)
    }

    @Test
    fun `imperial conversion occurs once and produces metric equivalent`() {
        val (kg, cm) = CalorieEngine.metricFromImperial(132.277, 62.5984)
        val converted = CalorieEngine.calculate(verifiedInput.copy(weightKg = kg, heightCm = cm))!!
        assertEquals(1478.75, converted.bmrEstimate, 0.1)
    }

    @Test
    fun `invalid adult inputs are rejected`() {
        assertNull(CalorieEngine.calculate(verifiedInput.copy(ageYears = 0)))
        assertNull(CalorieEngine.calculate(verifiedInput.copy(heightCm = 40.0)))
        assertNull(CalorieEngine.calculate(verifiedInput.copy(weightKg = -1.0)))
    }

    @Test
    fun `numeric support boundaries match calculation acceptance`() {
        assertFalse(CalorieEngine.isSupportedAge(17))
        assertTrue(CalorieEngine.isSupportedAge(18))
        assertTrue(CalorieEngine.isSupportedAge(100))
        assertFalse(CalorieEngine.isSupportedAge(101))

        assertFalse(CalorieEngine.isSupportedHeight(119.99))
        assertTrue(CalorieEngine.isSupportedHeight(120.0))
        assertTrue(CalorieEngine.isSupportedHeight(230.0))
        assertFalse(CalorieEngine.isSupportedHeight(230.01))

        assertFalse(CalorieEngine.isSupportedWeight(29.99))
        assertTrue(CalorieEngine.isSupportedWeight(30.0))
        assertTrue(CalorieEngine.isSupportedWeight(350.0))
        assertFalse(CalorieEngine.isSupportedWeight(350.01))

        assertNotNull(
            CalorieEngine.calculate(
                verifiedInput.copy(ageYears = 18, heightCm = 120.0, weightKg = 30.0)
            )
        )
        assertNotNull(
            CalorieEngine.calculate(
                verifiedInput.copy(ageYears = 100, heightCm = 230.0, weightKg = 350.0)
            )
        )
        assertNotNull(CalorieEngine.estimateBmr(18, CalorieSex.MALE, 120.0, 30.0))
        assertNotNull(CalorieEngine.estimateBmr(100, CalorieSex.FEMALE, 230.0, 350.0))
        assertNull(CalorieEngine.calculate(verifiedInput.copy(ageYears = 17)))
        assertNull(CalorieEngine.calculate(verifiedInput.copy(heightCm = 230.01)))
        assertNull(CalorieEngine.calculate(verifiedInput.copy(weightKg = 350.01)))
        assertNull(CalorieEngine.estimateBmr(17, CalorieSex.MALE, 159.0, 60.0))
        assertNull(CalorieEngine.estimateBmr(24, CalorieSex.MALE, 230.01, 60.0))
        assertNull(CalorieEngine.estimateBmr(24, CalorieSex.MALE, 159.0, 350.01))
    }

    @Test
    fun `manual target survives refresh while Auto target responds to profile changes`() {
        val manual = CalorieEngine.calculate(verifiedInput.copy(manualTargetCalories = 3000))!!
        val manualAfterWeightChange = CalorieEngine.calculate(
            verifiedInput.copy(weightKg = 65.0, manualTargetCalories = 3000)
        )!!
        val autoAfterWeightChange = CalorieEngine.calculate(verifiedInput.copy(weightKg = 65.0))!!

        assertEquals(CalorieTargetMode.MANUAL, manual.targetMode)
        assertEquals(3000, manual.roundedDisplayCalories)
        assertEquals(3000, manualAfterWeightChange.roundedDisplayCalories)
        assertNotEquals(2464, autoAfterWeightChange.roundedDisplayCalories)
    }

    @Test
    fun `activity is independent of training-day availability and exercise is not added twice`() {
        val result = CalorieEngine.calculate(verifiedInput)!!
        val profileWithSevenAvailableDays = UserProfile(
            age = 24,
            gender = "Male",
            heightCm = 159f,
            weightKg = 60f,
            weightRange = "51-60kg",
            activityLevel = "Moderate",
            workoutDays = 7,
            goal = "Gain Muscle",
            workoutExperience = "Intermediate"
        )

        assertEquals(1.55, result.activityMultiplier, 0.0)
        assertEquals(result.roundedDisplayCalories, profileWithSevenAvailableDays.calculateDailyCalories())
        assertEquals(60f, profileWithSevenAvailableDays.getWeightMidpoint(), 0f)
    }

    @Test
    fun `higher external maintenance can be explained by a higher activity factor`() {
        val bmr = CalorieEngine.calculate(verifiedInput)!!.bmrEstimate
        val impliedMultiplier = 2745.0 / bmr
        assertTrue(impliedMultiplier > LifestyleActivityLevel.VERY_ACTIVE.multiplier)
        assertTrue(impliedMultiplier < LifestyleActivityLevel.EXTREMELY_ACTIVE.multiplier)
    }

    private fun Double.roundToDisplay(): Int = kotlin.math.round(this).toInt()
}
