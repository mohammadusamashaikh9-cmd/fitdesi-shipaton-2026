package com.example.ai

/** Structured coaching content shared by the UI and future provider adapters. */
data class AiCoachResponse(
    val summary: String,
    val recommendedAction: String,
    val nutritionNote: String,
    val workoutNote: String,
    val safetyDisclaimer: String,
    val metadata: AiCoachResponseMetadata = AiCoachResponseMetadata(),
    val workoutPlan: GeneratedWorkoutPlan? = null,
    val dietPlan: GeneratedDietPlan? = null
)

enum class AiCoachIntent {
    GENERAL_COACHING,
    EXERCISE_QUESTION,
    WORKOUT_PLAN,
    PAKISTANI_DIET_PLAN,
    FOOD_QUESTION,
    PROGRESS_REVIEW,
    YOGA_PLAN,
    MEDICAL_ESCALATION,
    UNSUPPORTED
}

data class AiCoachResponseMetadata(
    val sourceType: String = "LOCAL_FALLBACK",
    val knowledgeRecordIds: List<String> = emptyList(),
    val fallbackUsed: Boolean = false,
    val profileContextUsed: Boolean = false,
    val recentWorkoutContextUsed: Boolean = false,
    val intent: AiCoachIntent = AiCoachIntent.GENERAL_COACHING,
    val confidence: Double = 0.0,
    val requestId: String? = null,
    val backendMode: String? = null,
    val warnings: List<String> = emptyList(),
    val escalationRequired: Boolean = false,
    val detectedGoal: String? = null,
    val validationStatus: String? = null,
    val requestedWorkoutDays: Int? = null,
    val generatedWorkoutDays: Int? = null,
    val calorieTargetSource: String? = null,
    val macroTargetSource: String? = null
)
