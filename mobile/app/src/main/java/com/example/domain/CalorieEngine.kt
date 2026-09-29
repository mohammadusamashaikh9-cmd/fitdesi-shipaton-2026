package com.example.domain

import kotlin.math.roundToInt

enum class CalorieSex {
    MALE,
    FEMALE;

    companion object {
        fun from(value: String?): CalorieSex? = when (value?.trim()?.lowercase()) {
            "male", "man" -> MALE
            "female", "woman" -> FEMALE
            else -> null
        }
    }
}

enum class LifestyleActivityLevel(val displayName: String, val multiplier: Double) {
    SEDENTARY("Sedentary", 1.20),
    LIGHT("Light", 1.375),
    MODERATE("Moderate", 1.55),
    VERY_ACTIVE("Very Active", 1.725),
    EXTREMELY_ACTIVE("Extremely Active", 1.90);

    companion object {
        fun from(value: String?): LifestyleActivityLevel? = when (value?.trim()?.lowercase()) {
            "sedentary" -> SEDENTARY
            "light", "lightly active" -> LIGHT
            "moderate", "moderately active" -> MODERATE
            "active" -> VERY_ACTIVE
            "very active", "extremely active", "extra active" -> EXTREMELY_ACTIVE
            else -> null
        }
    }
}

enum class CalorieGoal {
    MAINTAIN,
    LOSE_FAT,
    GAIN_MUSCLE,
    BUILD_STRENGTH,
    ENDURANCE;

    companion object {
        fun from(value: String?): CalorieGoal? = when (value?.trim()?.lowercase()) {
            "maintain", "maintain weight", "maintenance" -> MAINTAIN
            "lose fat", "fat loss", "lose weight" -> LOSE_FAT
            "gain muscle", "muscle gain", "build muscle" -> GAIN_MUSCLE
            "build strength", "strength" -> BUILD_STRENGTH
            "increase endurance", "endurance" -> ENDURANCE
            else -> null
        }
    }
}

enum class TrainingExperience {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED;

    companion object {
        fun from(value: String?): TrainingExperience = when {
            value.orEmpty().contains("advanced", ignoreCase = true) -> ADVANCED
            value.orEmpty().contains("intermediate", ignoreCase = true) -> INTERMEDIATE
            else -> BEGINNER
        }
    }
}

enum class CalorieTargetMode { AUTO, MANUAL }

data class CalorieCalculationInput(
    val ageYears: Int,
    val sex: CalorieSex,
    val heightCm: Double,
    val weightKg: Double,
    val activityLevel: LifestyleActivityLevel,
    val goal: CalorieGoal,
    val experience: TrainingExperience,
    val manualTargetCalories: Int? = null
)

data class CalorieCalculation(
    val formulaId: String,
    val formulaDisplayName: String,
    val bmrEstimate: Double,
    val activityLevel: LifestyleActivityLevel,
    val activityMultiplier: Double,
    val maintenanceCalories: Double,
    val goal: CalorieGoal,
    val goalAdjustmentType: String,
    val goalAdjustmentPercent: Double,
    val goalAdjustmentCalories: Double,
    val finalCalorieTarget: Double,
    val roundedDisplayCalories: Int,
    val targetMode: CalorieTargetMode,
    val calculatedAtEpochMillis: Long,
    val calculationVersion: String,
    val calibration: CalorieCalibration = CalorieCalibration()
)

data class CalorieCalibration(
    val observedAverageBodyWeightKg: Double? = null,
    val observationPeriodDays: Int? = null,
    val estimatedWeeklyChangeKg: Double? = null,
    val recommendedAdjustmentCalories: Int? = null
)

data class MacroTargets(
    val proteinGrams: Double,
    val carbohydrateGrams: Double,
    val fatGrams: Double
)

object CalorieEngine {
    const val FORMULA_ID = "MIFFLIN_ST_JEOR_ADULT_V1"
    const val FORMULA_DISPLAY_NAME = "Mifflin-St Jeor estimate"
    const val CALCULATION_VERSION = "2026.07"

    fun calculate(
        input: CalorieCalculationInput,
        calculatedAtEpochMillis: Long = System.currentTimeMillis()
    ): CalorieCalculation? {
        if (!isPlausible(input)) return null
        val bmr = estimateBmr(input.ageYears, input.sex, input.heightCm, input.weightKg) ?: return null
        val maintenance = bmr * input.activityLevel.multiplier
        val autoAdjustmentPercent = adjustmentPercent(input.goal, input.experience)
        val manualTarget = input.manualTargetCalories?.takeIf { it in MIN_TARGET_CALORIES..MAX_TARGET_CALORIES }
        val mode = if (manualTarget != null) CalorieTargetMode.MANUAL else CalorieTargetMode.AUTO
        val target = manualTarget?.toDouble() ?: maintenance * (1.0 + autoAdjustmentPercent)
        val adjustmentCalories = target - maintenance
        return CalorieCalculation(
            formulaId = FORMULA_ID,
            formulaDisplayName = FORMULA_DISPLAY_NAME,
            bmrEstimate = bmr,
            activityLevel = input.activityLevel,
            activityMultiplier = input.activityLevel.multiplier,
            maintenanceCalories = maintenance,
            goal = input.goal,
            goalAdjustmentType = if (mode == CalorieTargetMode.MANUAL) "MANUAL_OVERRIDE" else "PERCENTAGE",
            goalAdjustmentPercent = if (mode == CalorieTargetMode.MANUAL) 0.0 else autoAdjustmentPercent,
            goalAdjustmentCalories = adjustmentCalories,
            finalCalorieTarget = target,
            roundedDisplayCalories = target.roundToInt(),
            targetMode = mode,
            calculatedAtEpochMillis = calculatedAtEpochMillis,
            calculationVersion = CALCULATION_VERSION
        )
    }

    fun estimateBmr(ageYears: Int, sex: CalorieSex, heightCm: Double, weightKg: Double): Double? {
        if (!isSupportedAge(ageYears) ||
            !isSupportedHeight(heightCm) ||
            !isSupportedWeight(weightKg)
        ) return null
        val sexConstant = if (sex == CalorieSex.MALE) 5.0 else -161.0
        return (10.0 * weightKg) + (6.25 * heightCm) - (5.0 * ageYears) + sexConstant
    }

    fun isSupportedAge(ageYears: Int): Boolean =
        ageYears in MIN_SUPPORTED_AGE..MAX_SUPPORTED_AGE

    fun isSupportedHeight(heightCm: Double): Boolean =
        heightCm in MIN_SUPPORTED_HEIGHT_CM..MAX_SUPPORTED_HEIGHT_CM

    fun isSupportedWeight(weightKg: Double): Boolean =
        weightKg in MIN_SUPPORTED_WEIGHT_KG..MAX_SUPPORTED_WEIGHT_KG

    fun macroTargets(calculation: CalorieCalculation, weightKg: Double): MacroTargets {
        val protein = (weightKg * 1.8).coerceAtLeast(0.0)
        val fat = calculation.finalCalorieTarget * 0.25 / 9.0
        val carbohydrate = ((calculation.finalCalorieTarget - protein * 4.0 - fat * 9.0) / 4.0)
            .coerceAtLeast(0.0)
        return MacroTargets(protein, carbohydrate, fat)
    }

    fun metricFromImperial(weightLb: Double, heightInches: Double): Pair<Double, Double> =
        (weightLb * 0.45359237) to (heightInches * 2.54)

    fun adjustmentPercent(goal: CalorieGoal, experience: TrainingExperience): Double = when (goal) {
        CalorieGoal.MAINTAIN, CalorieGoal.ENDURANCE -> 0.0
        CalorieGoal.LOSE_FAT -> -0.15
        CalorieGoal.BUILD_STRENGTH -> 0.05
        CalorieGoal.GAIN_MUSCLE -> when (experience) {
            TrainingExperience.BEGINNER -> 0.10
            TrainingExperience.INTERMEDIATE -> 0.075
            TrainingExperience.ADVANCED -> 0.05
        }
    }

    private fun isPlausible(input: CalorieCalculationInput): Boolean =
        isSupportedAge(input.ageYears) &&
            isSupportedHeight(input.heightCm) &&
            isSupportedWeight(input.weightKg)

    private const val MIN_SUPPORTED_AGE = 18
    private const val MAX_SUPPORTED_AGE = 100
    private const val MIN_SUPPORTED_HEIGHT_CM = 120.0
    private const val MAX_SUPPORTED_HEIGHT_CM = 230.0
    private const val MIN_SUPPORTED_WEIGHT_KG = 30.0
    private const val MAX_SUPPORTED_WEIGHT_KG = 350.0
    private const val MIN_TARGET_CALORIES = 1_000
    private const val MAX_TARGET_CALORIES = 6_000
}
