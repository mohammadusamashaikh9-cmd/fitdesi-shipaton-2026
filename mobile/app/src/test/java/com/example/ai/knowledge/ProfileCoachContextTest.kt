package com.example.ai.knowledge

import com.example.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCoachContextTest {
    @Test
    fun `verified profile reaches both local generators through one bounded snapshot`() {
        val profile = UserProfile(
            name = "Usama",
            age = 24,
            gender = "Male",
            heightCm = 159f,
            weightKg = 60f,
            weightRange = "51-60kg",
            dietPreference = "Eggetarian",
            workoutDays = 7,
            goal = "Gain Muscle",
            workoutType = "Both",
            mealsPerDay = 4,
            workoutExperience = "Intermediate, 6-12 months",
            activityLevel = "Moderate"
        )

        val context = profile.toLocalCoachContext(
            equipment = setOf("Dumbbells", "Barbell"),
            recentWorkoutSummary = "Two recent sessions"
        )

        assertEquals("EGGITARIAN", context.dietaryPreference)
        assertEquals("Gain Muscle", context.goal)
        assertEquals("Intermediate, 6-12 months", context.experience)
        assertEquals("Moderate", context.activityLevel)
        assertEquals(7, context.workoutDays)
        assertEquals(4, context.mealsPerDay)
        assertEquals(2464, context.calorieTarget)
        assertEquals(108.0, context.proteinTargetGrams!!, 0.01)
        assertTrue(context.equipment.contains("Dumbbells"))
        assertEquals("Two recent sessions", context.recentWorkoutSummary)
    }
}
