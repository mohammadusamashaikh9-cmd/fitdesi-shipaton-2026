package com.example.ai.knowledge

enum class KnowledgeDomain {
    EXERCISE,
    WORKOUT,
    NUTRITION,
    PAKISTANI_FOOD,
    SAFETY,
    MEDICAL_ESCALATION
}

data class KnowledgeEntry(
    val id: String,
    val domain: KnowledgeDomain,
    val title: String,
    val content: String,
    val keywords: Set<String> = emptySet(),
    val goals: Set<String> = emptySet(),
    val experienceLevels: Set<String> = emptySet(),
    val equipment: Set<String> = emptySet(),
    val exerciseIds: Set<String> = emptySet(),
    val foodNames: Set<String> = emptySet(),
    val progressions: List<String> = emptyList(),
    val regressions: List<String> = emptyList(),
    val priority: Int = 0,
    val sourceType: KnowledgeSourceType = KnowledgeSourceType.FITDESI_AUTHORED,
    val licenseStatus: RuntimeLicenseStatus = RuntimeLicenseStatus.VERIFIED
)

data class CoachContext(
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
    val activityLevel: String? = null,
    /** Days the user could train; this is not the number of sessions to prescribe. */
    val workoutDays: Int? = null,
    val limitations: Set<String> = emptySet()
)

enum class KnowledgeSourceType {
    VERIFIED_PACK,
    LEGACY_LOCAL,
    FITDESI_AUTHORED
}

enum class RuntimeLicenseStatus {
    VERIFIED,
    RESTRICTED,
    UNKNOWN
}

/**
 * Process-local snapshot populated from existing Room/DataStore flows.
 * It stores no new user data and is never sent to a remote provider.
 */
object LocalCoachContextStore {
    @Volatile
    private var current: CoachContext = CoachContext()

    fun update(context: CoachContext) {
        current = context.sanitized()
    }

    fun snapshot(): CoachContext = current

    fun merge(explicit: CoachContext): CoachContext {
        val fallback = snapshot()
        return CoachContext(
            goal = explicit.goal?.takeIf(String::isNotBlank) ?: fallback.goal,
            experience = explicit.experience?.takeIf(String::isNotBlank) ?: fallback.experience,
            equipment = explicit.equipment.ifEmpty { fallback.equipment },
            recentWorkoutSummary = explicit.recentWorkoutSummary?.takeIf(String::isNotBlank)
                ?: fallback.recentWorkoutSummary,
            calorieTarget = explicit.calorieTarget ?: fallback.calorieTarget,
            proteinTargetGrams = explicit.proteinTargetGrams ?: fallback.proteinTargetGrams,
            carbsTargetGrams = explicit.carbsTargetGrams ?: fallback.carbsTargetGrams,
            fatTargetGrams = explicit.fatTargetGrams ?: fallback.fatTargetGrams,
            dietaryPreference = explicit.dietaryPreference?.takeIf(String::isNotBlank)
                ?: fallback.dietaryPreference,
            mealsPerDay = explicit.mealsPerDay ?: fallback.mealsPerDay,
            activityLevel = explicit.activityLevel?.takeIf(String::isNotBlank) ?: fallback.activityLevel,
            workoutDays = explicit.workoutDays ?: fallback.workoutDays,
            limitations = explicit.limitations.ifEmpty { fallback.limitations }
        ).sanitized()
    }

    private fun CoachContext.sanitized(): CoachContext = copy(
        goal = goal?.trim()?.take(80)?.takeIf(String::isNotEmpty),
        experience = experience?.trim()?.take(80)?.takeIf(String::isNotEmpty),
        equipment = equipment.map(String::trim).filter(String::isNotEmpty).take(12).toSet(),
        recentWorkoutSummary = recentWorkoutSummary?.trim()?.take(500)?.takeIf(String::isNotEmpty),
        calorieTarget = calorieTarget?.takeIf { it in 1200..6000 },
        proteinTargetGrams = proteinTargetGrams?.takeIf { it in 1.0..500.0 },
        carbsTargetGrams = carbsTargetGrams?.takeIf { it in 1.0..1000.0 },
        fatTargetGrams = fatTargetGrams?.takeIf { it in 1.0..300.0 },
        dietaryPreference = dietaryPreference?.trim()?.take(80)?.takeIf(String::isNotEmpty),
        mealsPerDay = mealsPerDay?.coerceIn(2, 6),
        activityLevel = activityLevel?.trim()?.take(40)?.takeIf(String::isNotEmpty),
        workoutDays = workoutDays?.coerceIn(2, 7),
        limitations = limitations.map(String::trim).filter(String::isNotEmpty).take(8).toSet()
    )
}

data class KnowledgeQuery(
    val question: String,
    val context: CoachContext = CoachContext(),
    val limit: Int = 5
)

data class KnowledgeMatch(
    val entry: KnowledgeEntry,
    val score: Int,
    val reasons: Set<String>
)

data class ProjectExerciseRecord(
    val id: String,
    val name: String,
    val bodyPart: String?,
    val target: String?,
    val muscleGroup: String?,
    val equipment: String?
)

data class ProjectFoodRecord(
    val id: String,
    val name: String,
    val category: String,
    val servingSize: String,
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val aliases: List<String> = emptyList(),
    val nutritionBasis: String = "EXISTING_FITDESI_SERVING_ESTIMATE",
    val reviewStatus: String = "EXISTING_FITDESI_LOGGABLE",
    val isNutritionReviewed: Boolean = true,
    val runtimeSource: String = "LEGACY_LOCAL",
    val dietaryClassification: FoodDietaryClassification = FoodDietaryClassification.UNKNOWN
)

enum class FoodDietaryClassification {
    VEGAN,
    VEGETARIAN,
    EGG,
    MEAT,
    UNKNOWN
}
