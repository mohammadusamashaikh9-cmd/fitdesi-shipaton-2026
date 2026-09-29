package com.example.ui

internal data class CalorieProgressPresentation(
    val consumed: Int,
    val target: Int?,
    val progress: Float?
)

internal data class MacroProgressPresentation(
    val consumedGrams: Float,
    val targetGrams: Float?,
    val progress: Float?
)

internal data class NutritionProgressPresentation(
    val calories: CalorieProgressPresentation,
    val protein: MacroProgressPresentation,
    val carbs: MacroProgressPresentation,
    val fat: MacroProgressPresentation,
    val canShowMacroTargets: Boolean
)

internal fun nutritionProgressPresentation(
    consumedCalories: Int,
    calorieTarget: Int,
    consumedProteinGrams: Float,
    consumedCarbsGrams: Float,
    consumedFatGrams: Float,
    proteinTargetGrams: Float,
    carbsTargetGrams: Float,
    fatTargetGrams: Float,
    showMacroTargetProgress: Boolean
): NutritionProgressPresentation {
    val availableCalorieTarget = calorieTarget.takeIf { it > 0 }
    return NutritionProgressPresentation(
        calories = CalorieProgressPresentation(
            consumed = consumedCalories,
            target = availableCalorieTarget,
            progress = availableCalorieTarget?.let { target ->
                (consumedCalories.toFloat() / target).coerceIn(0f, 1f)
            }
        ),
        protein = macroProgressPresentation(
            consumedGrams = consumedProteinGrams,
            targetGrams = proteinTargetGrams,
            showTargetProgress = showMacroTargetProgress
        ),
        carbs = macroProgressPresentation(
            consumedGrams = consumedCarbsGrams,
            targetGrams = carbsTargetGrams,
            showTargetProgress = showMacroTargetProgress
        ),
        fat = macroProgressPresentation(
            consumedGrams = consumedFatGrams,
            targetGrams = fatTargetGrams,
            showTargetProgress = showMacroTargetProgress
        ),
        canShowMacroTargets = showMacroTargetProgress
    )
}

private fun macroProgressPresentation(
    consumedGrams: Float,
    targetGrams: Float,
    showTargetProgress: Boolean
): MacroProgressPresentation {
    val availableTarget = targetGrams.takeIf { showTargetProgress && it > 0f }
    return MacroProgressPresentation(
        consumedGrams = consumedGrams,
        targetGrams = availableTarget,
        progress = availableTarget?.let { target ->
            (consumedGrams / target).coerceIn(0f, 1f)
        }
    )
}
