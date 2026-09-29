package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileIntegrityTest {
    @Test
    fun freshProfileContainsNoSampleAthleteData() {
        val profile = UserProfile()

        assertEquals("", profile.name)
        assertEquals(0, profile.age)
        assertEquals("", profile.gender)
        assertEquals(0f, profile.heightCm)
        assertEquals(0f, profile.weightKg)
        assertEquals("", profile.weightRange)
        assertEquals("", profile.dietPreference)
        assertEquals(0, profile.workoutDays)
        assertEquals("", profile.goal)
        assertEquals("", profile.workoutType)
        assertEquals(0, profile.mealsPerDay)
        assertEquals("", profile.workoutExperience)
        assertFalse(profile.allergiesSpecified)
        assertEquals("", profile.activityLevel)
        assertEquals("System", profile.theme)
        assertEquals(0f, profile.customCarbGoalGrams)
        assertEquals(0f, profile.customProteinGoalGrams)
        assertEquals(0f, profile.customFatGoalGrams)
    }

    @Test
    fun missingProfileInputsDoNotProduceHealthTargets() {
        val profile = UserProfile()

        assertEquals(0f, profile.getBmi())
        assertEquals("Not set", profile.getBmiStatus())
        assertEquals(0f, profile.calculateBmr())
        assertEquals(0, profile.calculateDailyCalories())
        assertEquals(0f, profile.calculateProteinGrams())
        assertEquals(0f, profile.calculateCarbGrams())
        assertEquals(0f, profile.calculateFatGrams())
        assertFalse(profile.hasBmiInputs())
        assertFalse(profile.canCalculateDailyCalories())
    }

    @Test
    fun realStoredProfileStillCalculatesNormally() {
        val profile = UserProfile(
            name = "User",
            age = 30,
            gender = "Male",
            heightCm = 180f,
            weightKg = 80f,
            weightRange = "",
            goal = "Maintain Weight",
            activityLevel = "Moderate"
        )

        assertTrue(profile.hasBmiInputs())
        assertTrue(profile.canCalculateBmr())
        assertTrue(profile.canCalculateDailyCalories())
        assertEquals(24.69f, profile.getBmi(), 0.01f)
        assertEquals(1_780f, profile.calculateBmr(), 0.01f)
        assertEquals(2_759, profile.calculateDailyCalories())
    }

    @Test
    fun finishedMacroTargetsOverrideCalculatedNutritionTargets() {
        val profile = UserProfile(
            customCalorieGoal = 3_000,
            customCarbGoalGrams = 300f,
            customProteinGoalGrams = 225f,
            customFatGoalGrams = 100f
        )

        assertEquals(3_000, profile.calculateDailyCalories())
        assertEquals(300f, profile.calculateCarbGrams())
        assertEquals(225f, profile.calculateProteinGrams())
        assertEquals(100f, profile.calculateFatGrams())
    }

    @Test
    fun personalDetailsCompletenessDoesNotHideAutomaticCalorieRequirements() {
        val profile = UserProfile(
            name = "User",
            age = 30,
            gender = "Male",
            heightCm = 180f,
            weightKg = 80f
        )

        assertTrue(profile.hasCompletePersonalDetails())
        assertEquals(
            listOf(
                AutomaticCalorieInput.ACTIVITY_LEVEL,
                AutomaticCalorieInput.FITNESS_GOAL
            ),
            profile.automaticCalorieMissingInputs()
        )
        assertFalse(profile.canCalculateDailyCalories())
    }

    @Test
    fun automaticCalorieReadinessUsesTheEngineBoundsAndEffectiveWeight() {
        val profile = UserProfile(
            age = 30,
            gender = "Female",
            heightCm = 165f,
            weightKg = 0f,
            weightRange = "61-70kg",
            goal = "Lose Fat",
            activityLevel = "Light"
        )

        assertFalse(profile.hasCompletePersonalDetails())
        assertTrue(profile.automaticCalorieMissingInputs().isEmpty())
        assertTrue(profile.canCalculateDailyCalories())
        assertTrue(profile.calculateDailyCalories() > 0)

        val invalid = profile.copy(age = 17, heightCm = 119f, weightRange = "20-29kg")
        assertEquals(
            listOf(
                AutomaticCalorieInput.AGE,
                AutomaticCalorieInput.HEIGHT,
                AutomaticCalorieInput.WEIGHT
            ),
            invalid.automaticCalorieMissingInputs()
        )
        assertFalse(invalid.canCalculateDailyCalories())
    }

    @Test
    fun automaticMacrosResolveWhenCaloriesResolveAndGoalChangesTheTargets() {
        val maintain = UserProfile(
            age = 30,
            gender = "Male",
            heightCm = 180f,
            weightKg = 80f,
            goal = "Maintain Weight",
            activityLevel = "Moderate"
        )
        val fatLoss = maintain.copy(goal = "Lose Fat")

        assertTrue(maintain.calculateProteinGrams() > 0f)
        assertTrue(maintain.calculateFatGrams() > 0f)
        assertTrue(maintain.calculateCarbGrams() > 0f)
        assertTrue(fatLoss.calculateDailyCalories() < maintain.calculateDailyCalories())
        assertTrue(fatLoss.calculateCarbGrams() < maintain.calculateCarbGrams())
    }

    @Test
    fun exactWeightTakesPrecedenceOverLegacyWeightRange() {
        val profile = UserProfile(
            age = 24,
            gender = "Male",
            heightCm = 159f,
            weightKg = 60f,
            weightRange = "51-60kg",
            goal = "Gain Muscle",
            activityLevel = "Moderate",
            workoutExperience = "Intermediate"
        )

        assertEquals(60f, profile.getWeightMidpoint(), 0f)
        assertEquals(23.73f, profile.getBmi(), 0.01f)
        assertEquals(1_478.75f, profile.calculateBmr(), 0.01f)
        assertEquals(2_464, profile.calculateDailyCalories())
    }

    @Test
    fun legacyAndCorrectEggitarianSpellingsNormalizeIdentically() {
        assertEquals("EGGITARIAN", UserProfile(dietPreference = "Eggetarian").normalizedDietPreference().name)
        assertEquals("EGGITARIAN", UserProfile(dietPreference = "Eggitarian").normalizedDietPreference().name)
        assertEquals("Eggitarian", UserProfile(dietPreference = "Eggetarian").normalizedDietPreference().displayName)
    }
}
