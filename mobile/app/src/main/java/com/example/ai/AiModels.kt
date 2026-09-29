package com.example.ai

import com.example.ai.knowledge.CoachContext

enum class AiCoachConversationRole { USER, ASSISTANT }

data class AiCoachConversationTurn(
    val role: AiCoachConversationRole,
    val text: String
)

data class AiCoachRequest(
    val question: String,
    val goal: String? = null,
    val experience: String? = null,
    val equipment: Set<String> = emptySet(),
    val recentWorkoutSummary: String? = null,
    val calorieTarget: Int? = null,
    val proteinTargetGrams: Double? = null,
    val carbsTargetGrams: Double? = null,
    val fatTargetGrams: Double? = null,
    val dietaryPreference: String? = null,
    val mealsPerDay: Int? = null,
    val workoutDays: Int? = null,
    val limitations: Set<String> = emptySet(),
    val conversationContext: List<AiCoachConversationTurn> = emptyList()
) {
    fun coachContext(): CoachContext = CoachContext(
        goal = goal,
        experience = experience,
        equipment = equipment,
        recentWorkoutSummary = recentWorkoutSummary,
        calorieTarget = calorieTarget,
        proteinTargetGrams = proteinTargetGrams,
        carbsTargetGrams = carbsTargetGrams,
        fatTargetGrams = fatTargetGrams,
        dietaryPreference = dietaryPreference,
        mealsPerDay = mealsPerDay,
        workoutDays = workoutDays,
        limitations = limitations
    )
}

data class AiWorkoutRequest(
    val generatorType: String,
    val gender: String,
    val age: Int,
    val level: String,
    val goal: String,
    val daysCount: Int,
    val selectedDays: List<String>,
    val split: String,
    val enforceRecovery: Boolean,
    val equipment: List<String>
)

data class GeneratedRoutine(
    val name: String,
    val description: String,
    val splitType: String,
    val frequency: String,
    val days: List<GeneratedDay>,
    val explanation: String = "",
    val safetyNote: String = "",
    val planId: String = "",
    val goal: String = "",
    val experienceLevel: String = "",
    val progressionGuidance: String = "",
    val createdAt: Long = 0L,
    val sourceType: GeneratedPlanSource = GeneratedPlanSource.LOCAL_FALLBACK,
    val profileContextUsed: Boolean = false
) {
    /** Ensures omitted top-level fields receive their semantic defaults during historical Gson reads. */
    constructor() : this(
        name = "",
        description = "",
        splitType = "",
        frequency = "",
        days = emptyList(),
        explanation = "",
        safetyNote = "",
        planId = "",
        goal = "",
        experienceLevel = "",
        progressionGuidance = "",
        createdAt = 0L,
        sourceType = GeneratedPlanSource.LOCAL_FALLBACK,
        profileContextUsed = false
    )
}

data class GeneratedDay(
    val dayName: String,
    val title: String,
    val description: String,
    val exercises: List<GeneratedExercise>,
    val focus: String = title,
    val generalWarmup: GeneralWarmupPrescription? = null,
    val warmupExercises: List<GeneratedExercise> = emptyList(),
    val cooldownExercises: List<GeneratedExercise> = emptyList()
) {
    /** Ensures omitted structured fields receive safe values during historical Gson reads. */
    constructor() : this(
        dayName = "",
        title = "",
        description = "",
        exercises = emptyList(),
        focus = "",
        generalWarmup = null,
        warmupExercises = emptyList(),
        cooldownExercises = emptyList()
    )
}

data class GeneratedExercise(
    val name: String,
    val sets: Int,
    val reps: String,
    val targetMuscle: String,
    val instructions: String,
    val restSeconds: Int = 0,
    val exerciseId: String = "",
    val rampUpSets: List<RampUpSetPrescription> = emptyList(),
    val activityPrescription: ActivityPrescription? = null,
    val safetyNote: String = ""
) {
    /** Compatibility constructor for historical Gson snapshots with omitted additive fields. */
    constructor() : this(
        name = "",
        sets = 0,
        reps = "",
        targetMuscle = "",
        instructions = "",
        restSeconds = 0,
        exerciseId = "",
        rampUpSets = emptyList(),
        activityPrescription = null,
        safetyNote = ""
    )
}
