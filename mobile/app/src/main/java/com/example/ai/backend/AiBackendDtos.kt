package com.example.ai.backend

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackendSuccessEnvelope<T>(
    val success: Boolean,
    val requestId: String,
    val mode: String,
    val data: T
)

@JsonClass(generateAdapter = true)
data class BackendErrorEnvelope(
    val success: Boolean,
    val error: BackendErrorDto
)

@JsonClass(generateAdapter = true)
data class BackendErrorDto(
    val code: String,
    val message: String,
    val requestId: String
)

@JsonClass(generateAdapter = true)
data class BackendSessionSuccessEnvelope(
    val success: Boolean,
    val requestId: String,
    val data: BackendSessionDataDto
)

@JsonClass(generateAdapter = true)
data class BackendSessionDataDto(
    val authenticated: Boolean,
    val emailVerified: Boolean
)

@JsonClass(generateAdapter = true)
data class RemoteAiConsentSuccessEnvelope(
    val success: Boolean,
    val requestId: String,
    val data: RemoteAiConsentStateDto
)

@JsonClass(generateAdapter = true)
data class RemoteAiConsentStateDto(
    val schemaVersion: Int,
    val standardRemoteAi: RemoteAiConsentDecisionDto,
    val experimentalTraining: RemoteAiConsentDecisionDto,
    val requiredNoticeVersions: RemoteAiConsentNoticeVersionsDto,
    val updatedAt: String?
)

@JsonClass(generateAdapter = true)
data class RemoteAiConsentDecisionDto(
    val granted: Boolean,
    val current: Boolean,
    val noticeVersion: String?,
    val decidedAt: String?
)

@JsonClass(generateAdapter = true)
data class RemoteAiConsentNoticeVersionsDto(
    val standardRemoteAi: String,
    val experimentalTraining: String
)

@JsonClass(generateAdapter = true)
data class RemoteAiConsentMutationDto(
    val standardRemoteAi: RemoteAiStandardConsentDecisionDto
)

@JsonClass(generateAdapter = true)
data class RemoteAiStandardConsentDecisionDto(
    val granted: Boolean,
    val noticeVersion: String? = null
)

@JsonClass(generateAdapter = true)
data class HealthResponseDto(
    val status: String,
    val service: String,
    val providerMode: String,
    val timestamp: String
)

@JsonClass(generateAdapter = true)
data class CoachRequestDto(
    val question: String,
    val goal: String? = null,
    val experience: String? = null,
    val equipment: List<String> = emptyList(),
    val recentWorkoutSummary: String? = null,
    val calorieTarget: Int? = null,
    val proteinTargetGrams: Double? = null,
    val carbsTargetGrams: Double? = null,
    val fatTargetGrams: Double? = null,
    val dietaryPreference: String? = null,
    val mealsPerDay: Int? = null,
    val workoutDays: Int? = null,
    val limitations: List<String> = emptyList(),
    val conversationContext: List<CoachConversationContextDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CoachConversationContextDto(
    val role: String,
    val text: String
)

@JsonClass(generateAdapter = true)
data class CoachResponseDto(
    val summary: String,
    val recommendedAction: String,
    val nutritionNote: String,
    val workoutNote: String,
    val safetyDisclaimer: String,
    val escalationRequired: Boolean = false,
    val detectedIntent: String? = null,
    val detectedGoal: String? = null,
    val sourceType: String? = null,
    val confidence: Double? = null,
    val warnings: List<String> = emptyList(),
    val requestId: String? = null,
    val knowledgeRecordIds: List<String> = emptyList(),
    val fallbackUsed: Boolean = false,
    val profileContextUsed: Boolean = false,
    val recentWorkoutContextUsed: Boolean = false,
    val validationStatus: String? = null,
    val generatedWorkoutPlan: BackendGeneratedWorkoutPlanDto? = null,
    val generatedDietPlan: BackendGeneratedDietPlanDto? = null
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedWorkoutPlanDto(
    val planId: String,
    val title: String,
    val goal: String,
    val requestedDays: Int,
    val generatedDays: List<BackendGeneratedWorkoutDayDto>,
    val progressionGuidance: String,
    val safetyNote: String
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedWorkoutDayDto(
    val dayName: String,
    val focus: String,
    val exercises: List<BackendGeneratedWorkoutExerciseDto>
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedWorkoutExerciseDto(
    val exerciseId: String,
    val name: String,
    val sets: Int,
    val repsOrDuration: String,
    val restSeconds: Int,
    val role: String
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedDietPlanDto(
    val planId: String,
    val title: String,
    val goal: String,
    val calorieTarget: Int?,
    val calorieTargetSource: String?,
    val macroTargets: BackendMacroTargetsDto?,
    val macroTargetSource: String?,
    val meals: List<BackendGeneratedMealDto>,
    val alternatives: List<String>,
    val disclaimer: String
)

@JsonClass(generateAdapter = true)
data class BackendMacroTargetsDto(
    val proteinGrams: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedMealDto(
    val name: String,
    val foods: List<BackendGeneratedFoodDto>
)

@JsonClass(generateAdapter = true)
data class BackendGeneratedFoodDto(
    val foodRecordId: String?,
    val name: String,
    val portion: String,
    val calories: Double?,
    val proteinGrams: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?,
    val nutritionSource: String
)

@JsonClass(generateAdapter = true)
data class WorkoutPlanRequestDto(
    val age: Int,
    val gender: String,
    val level: String,
    val goal: String,
    val daysPerWeek: Int,
    val selectedDays: List<String>,
    val split: String,
    val equipment: List<String>,
    val enforceRecovery: Boolean = true,
    val note: String = "",
    val limitations: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WorkoutPlanResponseDto(
    val status: String,
    val message: String? = null,
    val plan: WorkoutPlanDto? = null
)

@JsonClass(generateAdapter = true)
data class WorkoutPlanDto(
    val routineName: String,
    val daysPerWeek: Int,
    val splitType: String,
    val workoutDays: List<WorkoutDayDto>,
    val explanation: String,
    val safetyNote: String
)

@JsonClass(generateAdapter = true)
data class WorkoutDayDto(
    val day: String,
    val title: String,
    val exercises: List<WorkoutExerciseDto>
)

@JsonClass(generateAdapter = true)
data class WorkoutExerciseDto(
    val name: String,
    val sets: Int,
    val reps: String,
    val restSeconds: Int
)

@JsonClass(generateAdapter = true)
data class FoodAnalyzeRequestDto(
    val foodName: String,
    val servingSize: String = ""
)

@JsonClass(generateAdapter = true)
data class FoodAnalyzeResponseDto(
    val foodName: String,
    val servingSize: String,
    val estimateStatus: String,
    val calorieEstimate: Double?,
    val macroEstimate: MacroEstimateDto?,
    val note: String,
    val safetyNote: String
)

@JsonClass(generateAdapter = true)
data class MacroEstimateDto(
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double
)

@JsonClass(generateAdapter = true)
data class DietPlanRequestDto(
    val goal: String,
    val dailyCalories: Int,
    val mealsPerDay: Int,
    val dietPreference: String = "",
    val allergies: List<String> = emptyList(),
    val medicalNotes: String = ""
)

@JsonClass(generateAdapter = true)
data class DietPlanResponseDto(
    val status: String,
    val message: String? = null,
    val plan: DietPlanDto? = null
)

@JsonClass(generateAdapter = true)
data class DietPlanDto(
    val goal: String,
    val dailyCalories: Int,
    val mealsPerDay: Int,
    val dietPreference: String,
    val allergiesExcluded: List<String>,
    val meals: List<DietMealDto>,
    val note: String,
    val safetyNote: String
)

@JsonClass(generateAdapter = true)
data class DietMealDto(
    val mealNumber: Int,
    val targetCalories: Int,
    val template: String
)

@JsonClass(generateAdapter = true)
data class YogaPlanRequestDto(
    val goal: String,
    val level: String,
    val durationMinutes: Int,
    val limitations: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class YogaPlanResponseDto(
    val status: String,
    val message: String? = null,
    val plan: YogaPlanDto? = null
)

@JsonClass(generateAdapter = true)
data class YogaPlanDto(
    val name: String,
    val level: String,
    val durationMinutes: Int,
    val sequence: List<YogaMovementDto>,
    val note: String,
    val safetyNote: String
)

@JsonClass(generateAdapter = true)
data class YogaMovementDto(
    val movement: String,
    val minutes: Int
)

@JsonClass(generateAdapter = true)
data class ProgressReviewRequestDto(
    val workoutsCompleted: Int,
    val totalDurationMinutes: Int,
    val trainingVolumeKg: Double = 0.0,
    val note: String = ""
)

@JsonClass(generateAdapter = true)
data class ProgressReviewResponseDto(
    val status: String,
    val message: String? = null,
    val review: ProgressReviewDto? = null
)

@JsonClass(generateAdapter = true)
data class ProgressReviewDto(
    val workoutsCompleted: Int,
    val averageDurationMinutes: Double,
    val trainingVolumeKg: Double,
    val summary: String,
    val recommendedAction: String,
    val safetyNote: String
)
