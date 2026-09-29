package com.example.ai.knowledge

import com.example.data.UserProfile
import com.example.domain.CalorieEngine

internal fun UserProfile.toLocalCoachContext(
    equipment: Set<String>,
    recentWorkoutSummary: String?
): CoachContext {
    val calculation = calorieCalculation()
    val calculatedMacros = calculation?.let {
        CalorieEngine.macroTargets(it, getWeightMidpoint().toDouble())
    }
    return CoachContext(
        goal = goal.takeIf(String::isNotBlank),
        experience = workoutExperience.takeIf(String::isNotBlank),
        equipment = equipment,
        recentWorkoutSummary = recentWorkoutSummary,
        calorieTarget = calculateDailyCalories().takeIf { it > 0 },
        proteinTargetGrams = customProteinGoalGrams.takeIf { it > 0f }?.toDouble()
            ?: calculatedMacros?.proteinGrams,
        carbsTargetGrams = customCarbGoalGrams.takeIf { it > 0f }?.toDouble()
            ?: calculatedMacros?.carbohydrateGrams,
        fatTargetGrams = customFatGoalGrams.takeIf { it > 0f }?.toDouble()
            ?: calculatedMacros?.fatGrams,
        dietaryPreference = normalizedDietPreference().name,
        mealsPerDay = mealsPerDay.takeIf { it in 2..6 },
        activityLevel = activityLevel.takeIf(String::isNotBlank),
        workoutDays = workoutDays.takeIf { it in 2..7 },
        limitations = if (hasAllergies && allergiesDetails.isNotBlank()) {
            setOf("Food allergies: ${allergiesDetails.take(120)}")
        } else {
            emptySet()
        }
    )
}
