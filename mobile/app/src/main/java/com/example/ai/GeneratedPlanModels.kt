package com.example.ai

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class GeneratedPlanSource {
    LOCAL_KNOWLEDGE,
    REMOTE,
    FIREWORKS,
    LOCAL_FALLBACK
}

@Serializable
enum class PlanValidationStatus {
    VALIDATED,
    NEEDS_ADJUSTMENT,
    NEEDS_PROFILE_INPUT
}

fun planSourceDisplayLabel(value: String): String = when (value.uppercase()) {
    "LOCAL_KNOWLEDGE", "VERIFIED_LOCAL_KNOWLEDGE" -> "Local knowledge"
    "LOCAL_FALLBACK" -> "Local fallback"
    "BACKEND_MOCK" -> "Backend mock"
    "REMOTE", "FIREWORKS" -> "Remote Coach"
    "LEGACY_LOCAL_UNKNOWN" -> "Legacy local"
    else -> value.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

fun planValidationDisplayLabel(value: String): String =
    value.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

@Serializable
data class GeneratedWorkoutPlan(
    val planId: String,
    val title: String,
    val goal: String,
    val experienceLevel: String,
    val days: List<GeneratedWorkoutPlanDay>,
    val progressionGuidance: String,
    val recoveryGuidance: String,
    val safetyNote: String,
    val createdAt: Long,
    val sourceType: GeneratedPlanSource,
    val profileContextUsed: Boolean
) {
    val numberOfDays: Int
        get() = days.size

    fun isValid(): Boolean =
        planId.isNotBlank() &&
            title.isNotBlank() &&
            days.isNotEmpty() &&
            days.size in 2..6 &&
            days.all(GeneratedWorkoutPlanDay::isValid) &&
            progressionGuidance.isNotBlank() &&
            safetyNote.isNotBlank() &&
            createdAt > 0
}

@Serializable
data class GeneratedWorkoutPlanDay(
    val dayName: String,
    val focus: String,
    val exercises: List<GeneratedWorkoutPlanExercise>,
    val generalWarmup: GeneralWarmupPrescription? = null,
    val warmupExercises: List<GeneratedWorkoutPlanExercise> = emptyList(),
    val cooldownExercises: List<GeneratedWorkoutPlanExercise> = emptyList()
) {
    fun isValid(): Boolean =
        dayName.isNotBlank() &&
            focus.isNotBlank() &&
            exercises.isNotEmpty() &&
            exercises.all(GeneratedWorkoutPlanExercise::isValid) &&
            hasValidStructuredContracts()

    fun hasValidStructuredContracts(): Boolean =
        generalWarmup?.isStructurallyValid() != false &&
            exercises.all(GeneratedWorkoutPlanExercise::hasValidStructuredContracts) &&
            warmupExercises.all(GeneratedWorkoutPlanExercise::isValid) &&
            cooldownExercises.all(GeneratedWorkoutPlanExercise::isValid)
}

@Serializable
data class GeneratedWorkoutPlanExercise(
    val exerciseId: String,
    val name: String,
    val movementPattern: String,
    val sets: Int,
    val repsOrDuration: String,
    val restSeconds: Int,
    val rampUpSets: List<RampUpSetPrescription> = emptyList(),
    val activityPrescription: ActivityPrescription? = null,
    val safetyNote: String = ""
) {
    fun isValid(): Boolean =
        exerciseId.isNotBlank() &&
            name.isNotBlank() &&
            sets in 1..10 &&
            repsOrDuration.isNotBlank() &&
            restSeconds in 0..600 &&
            hasValidStructuredContracts()

    fun hasValidStructuredContracts(): Boolean =
        rampUpSets.all(RampUpSetPrescription::isStructurallyValid) &&
            activityPrescription?.isStructurallyValid() != false
}

fun GeneratedWorkoutPlan.hasValidStructuredContracts(): Boolean =
    days.all(GeneratedWorkoutPlanDay::hasValidStructuredContracts)

@Serializable
data class GeneratedDietPlan(
    val planId: String,
    val title: String,
    val goal: String,
    val calorieTarget: Int?,
    val proteinTargetGrams: Double?,
    val carbsTargetGrams: Double?,
    val fatTargetGrams: Double?,
    val days: List<GeneratedDietPlanDay>,
    val hydrationReminder: String,
    val disclaimer: String,
    val createdAt: Long,
    val sourceType: GeneratedPlanSource,
    val profileContextUsed: Boolean,
    val validationStatus: PlanValidationStatus = PlanValidationStatus.VALIDATED,
    val validationFailures: List<String> = emptyList()
) {
    val mealCount: Int
        get() = days.sumOf { it.meals.size }

    fun isStructurallyValid(): Boolean =
        planId.isNotBlank() &&
            title.isNotBlank() &&
            days.isNotEmpty() &&
            days.all(GeneratedDietPlanDay::isValid) &&
            mealCount in 2..6 &&
            disclaimer.isNotBlank() &&
            createdAt > 0

    /** A plan needing adjustment is still a truthful, reopenable local draft. */
    fun isSavable(): Boolean =
        isStructurallyValid() && validationStatus != PlanValidationStatus.NEEDS_PROFILE_INPUT

    fun isValid(): Boolean =
        isStructurallyValid() && validationStatus == PlanValidationStatus.VALIDATED
}

@Serializable
data class GeneratedDietPlanDay(
    val dayName: String,
    val meals: List<GeneratedDietPlanMeal>
) {
    fun isValid(): Boolean =
        dayName.isNotBlank() &&
            meals.isNotEmpty() &&
            meals.all(GeneratedDietPlanMeal::isValid)
}

@Serializable
data class GeneratedDietPlanMeal(
    val label: String,
    val foodRecordId: String,
    val foodName: String,
    val storedServing: String,
    val portionMultiplier: Double,
    val estimatedCalories: Int?,
    val estimatedProteinGrams: Double?,
    val estimatedCarbsGrams: Double?,
    val estimatedFatGrams: Double?,
    val alternatives: List<GeneratedDietPlanAlternative>,
    val portionDescription: String = "",
    val additionalFoods: List<GeneratedDietPlanComponent> = emptyList()
) {
    fun isValid(): Boolean =
        label.isNotBlank() &&
            hasValidFoodReference() &&
            foodName.isNotBlank() &&
            storedServing.isNotBlank() &&
            portionMultiplier in 0.1..5.0 &&
            estimatedCalories?.let { it >= 0 } != false &&
            estimatedProteinGrams?.let { it >= 0.0 } != false &&
            estimatedCarbsGrams?.let { it >= 0.0 } != false &&
            estimatedFatGrams?.let { it >= 0.0 } != false &&
            additionalFoods.all(GeneratedDietPlanComponent::isValid)

    private fun hasValidFoodReference(): Boolean =
        foodRecordId.isNotBlank() || listOf(
            estimatedCalories,
            estimatedProteinGrams,
            estimatedCarbsGrams,
            estimatedFatGrams
        ).all { it == null }
}

@Serializable
data class GeneratedDietPlanComponent(
    val foodRecordId: String,
    val foodName: String,
    val portionDescription: String,
    val portionMultiplier: Double,
    val estimatedCalories: Int?,
    val estimatedProteinGrams: Double?,
    val estimatedCarbsGrams: Double?,
    val estimatedFatGrams: Double?
) {
    fun isValid(): Boolean =
        (foodRecordId.isNotBlank() || listOf(
            estimatedCalories,
            estimatedProteinGrams,
            estimatedCarbsGrams,
            estimatedFatGrams
        ).all { it == null }) && foodName.isNotBlank() && portionDescription.isNotBlank() &&
            portionMultiplier in 0.1..5.0 &&
            estimatedCalories?.let { it >= 0 } != false
}

@Serializable
data class GeneratedDietPlanAlternative(
    val foodRecordId: String,
    val foodName: String,
    val portionDescription: String = "",
    val portionMultiplier: Double = 1.0,
    val estimatedCalories: Int? = null,
    val estimatedProteinGrams: Double? = null,
    val estimatedCarbsGrams: Double? = null,
    val estimatedFatGrams: Double? = null,
    val dietaryCompatibilityStatus: String = "NOT_RECORDED"
)

fun AiCoachResponse.hasSavableGeneratedPlan(): Boolean = when {
    workoutPlan != null && dietPlan == null -> workoutPlan.isValid()
    dietPlan != null && workoutPlan == null -> dietPlan.isSavable()
    else -> false
}

fun GeneratedWorkoutPlan.toGeneratedRoutine(): GeneratedRoutine = GeneratedRoutine(
    name = title,
    description = "$goal plan for $experienceLevel experience.",
    splitType = days.joinToString(" / ") { it.focus }.take(120),
    frequency = "$numberOfDays days per week",
    days = days.map { day ->
        GeneratedDay(
            dayName = day.dayName,
            title = day.focus,
            description = "Generated from structured $sourceType plan data.",
            exercises = day.exercises.map(GeneratedWorkoutPlanExercise::toGeneratedExercise),
            focus = day.focus,
            generalWarmup = day.generalWarmup,
            warmupExercises = day.warmupExercises.map(GeneratedWorkoutPlanExercise::toGeneratedExercise),
            cooldownExercises = day.cooldownExercises.map(GeneratedWorkoutPlanExercise::toGeneratedExercise)
        )
    },
    explanation = progressionGuidance,
    safetyNote = safetyNote,
    planId = planId,
    goal = goal,
    experienceLevel = experienceLevel,
    progressionGuidance = progressionGuidance,
    createdAt = createdAt,
    sourceType = sourceType,
    profileContextUsed = profileContextUsed
)

private fun GeneratedWorkoutPlanExercise.toGeneratedExercise(): GeneratedExercise = GeneratedExercise(
    name = name,
    sets = sets,
    reps = repsOrDuration,
    targetMuscle = movementPattern,
    instructions = "Use controlled technique.",
    restSeconds = restSeconds,
    exerciseId = exerciseId,
    rampUpSets = rampUpSets,
    activityPrescription = activityPrescription,
    safetyNote = safetyNote
)

internal fun generatedPlanId(prefix: String, canonicalContent: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalContent.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "$prefix-$digest"
}
