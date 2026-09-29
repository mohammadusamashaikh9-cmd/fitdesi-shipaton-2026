package com.example.domain

data class MacroRatio(
    val id: String,
    val name: String,
    val carbPercent: Int,
    val proteinPercent: Int,
    val fatPercent: Int
) {
    val summary: String = "$carbPercent/$proteinPercent/$fatPercent"
    val isValid: Boolean =
        carbPercent > 0 && proteinPercent > 0 && fatPercent > 0 &&
            carbPercent + proteinPercent + fatPercent == 100
}

val standardMacroRatios = listOf(
    MacroRatio("high_carb", "High Carb", 60, 25, 15),
    MacroRatio("moderate", "Moderate", 50, 30, 20),
    MacroRatio("zone", "Zone Diet", 40, 30, 30),
    MacroRatio("low_carb", "Low Carb", 25, 45, 30)
)

data class MacroCalculation(
    val calories: Int,
    val mealsPerDay: Int,
    val ratio: MacroRatio,
    val dailyCarbsGrams: Double,
    val dailyProteinGrams: Double,
    val dailyFatGrams: Double
) {
    val carbsPerMealGrams: Double = dailyCarbsGrams / mealsPerDay
    val proteinPerMealGrams: Double = dailyProteinGrams / mealsPerDay
    val fatPerMealGrams: Double = dailyFatGrams / mealsPerDay
}

fun calculateMacros(calories: Int, mealsPerDay: Int, ratio: MacroRatio): MacroCalculation {
    require(calories > 0) { "Calories must be greater than zero" }
    require(mealsPerDay >= 1) { "Meals per day must be at least one" }
    require(ratio.isValid) { "Macro percentages must total 100" }

    return MacroCalculation(
        calories = calories,
        mealsPerDay = mealsPerDay,
        ratio = ratio,
        dailyCarbsGrams = calories * (ratio.carbPercent / 100.0) / 4.0,
        dailyProteinGrams = calories * (ratio.proteinPercent / 100.0) / 4.0,
        dailyFatGrams = calories * (ratio.fatPercent / 100.0) / 9.0
    )
}

enum class WeightUnit(val label: String) {
    KG("KG"),
    LBS("LBS")
}

data class TrainingLoad(
    val percent: Int,
    val focus: String,
    val weight: Double
)

data class OneRepMaxCalculation(
    val estimatedOneRepMax: Double,
    val unit: WeightUnit,
    val reps: Int,
    val trainingLoads: List<TrainingLoad>
) {
    val isHighRepEstimate: Boolean = reps > 10
}

private val trainingPercentages = listOf(
    50 to "Warm up",
    55 to "Warm up",
    60 to "Endurance",
    65 to "Endurance",
    70 to "Hypertrophy",
    75 to "Hypertrophy",
    80 to "Strength",
    85 to "Strength",
    90 to "Power",
    95 to "Power"
)

fun calculateOneRepMax(weight: Double, reps: Int, unit: WeightUnit): OneRepMaxCalculation {
    require(weight.isFinite() && weight > 0.0) { "Weight must be a finite value greater than zero" }
    require(reps >= 1) { "Repetitions must be at least one" }

    val estimate = if (reps == 1) weight else weight * (1.0 + reps / 30.0)
    require(estimate.isFinite()) { "Estimated one rep max is outside the supported range" }
    return OneRepMaxCalculation(
        estimatedOneRepMax = estimate,
        unit = unit,
        reps = reps,
        trainingLoads = trainingPercentages.map { (percent, focus) ->
            TrainingLoad(percent, focus, estimate * percent / 100.0)
        }
    )
}

fun convertWeight(value: Double, from: WeightUnit, to: WeightUnit): Double = when {
    from == to -> value
    from == WeightUnit.KG -> value * 2.2046226218
    else -> value / 2.2046226218
}
