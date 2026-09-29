package com.example.ai.knowledge

import kotlinx.serialization.Serializable

@Serializable
enum class LicenseStatus {
    VERIFIED,
    RESTRICTED,
    UNKNOWN
}

@Serializable
enum class RedistributionStatus {
    VERIFIED,
    RESTRICTED,
    UNKNOWN
}

@Serializable
data class SourceMetadata(
    val sourceName: String,
    val sourceRepository: String,
    val sourceRecordId: String,
    val licenseIdentifier: String,
    val licenseScope: String,
    val retrievedAt: String,
    val modifiedByFitDesi: Boolean,
    val redistributionStatus: RedistributionStatus,
    val licenseStatus: LicenseStatus,
    val attribution: String
)

@Serializable
data class KnowledgePackManifest(
    val packId: String,
    val packVersion: String,
    val schemaVersion: Int,
    val releaseMode: String,
    val createdDate: String,
    val files: List<KnowledgePackFile>,
    val totalRecords: Int,
    val licenseCounts: LicenseCounts
)

@Serializable
data class KnowledgePackFile(
    val name: String,
    val recordCount: Int,
    val sha256: String
)

@Serializable
data class LicenseCounts(
    val VERIFIED: Int,
    val RESTRICTED: Int,
    val UNKNOWN: Int
)

@Serializable
data class ExercisePackRecord(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val movementPattern: String,
    val category: String,
    val bodyPart: String,
    val target: String?,
    val muscleGroup: String?,
    val secondaryMuscles: List<String>,
    val bodyTargets: List<String>,
    val equipment: List<String>,
    val experienceLevels: List<String>,
    val goals: List<String>,
    val instructions: String,
    val safetyNote: String,
    val source: SourceMetadata
)

@Serializable
data class YogaPosePackRecord(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val bodyTargets: List<String>,
    val equipment: List<String>,
    val goals: List<String>,
    val instructions: String,
    val safetyNote: String,
    val source: SourceMetadata
)

@Serializable
data class NutritionEstimate(
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double
)

@Serializable
data class PakistaniFoodPackRecord(
    val id: String,
    val name: String,
    val category: String,
    val servingDescription: String,
    val aliases: List<String>,
    val dietaryTags: List<String>,
    val nutritionEstimate: NutritionEstimate?,
    val uncertaintyNote: String,
    val goals: List<String>,
    val source: SourceMetadata
)

@Serializable
data class FoodAliasPackRecord(
    val id: String,
    val alias: String,
    val foodId: String,
    val locale: String,
    val source: SourceMetadata
)

@Serializable
data class CoachingRulePackRecord(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val goals: List<String>,
    val experienceLevels: List<String>,
    val equipment: List<String>,
    val guidance: String,
    val priority: Int,
    val source: SourceMetadata
)

@Serializable
data class WorkoutRulePackRecord(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val goals: List<String>,
    val experienceLevels: List<String>,
    val equipment: List<String>,
    val guidance: String,
    val priority: Int,
    val source: SourceMetadata
)

@Serializable
data class ProgressionPackRecord(
    val id: String,
    val fromId: String?,
    val fromLabel: String,
    val toId: String?,
    val toLabel: String,
    val goals: List<String>,
    val equipment: List<String>,
    val guidance: String,
    val source: SourceMetadata
)

@Serializable
data class SubstitutionPackRecord(
    val id: String,
    val fromId: String?,
    val fromLabel: String,
    val toId: String?,
    val toLabel: String,
    val equipment: List<String>,
    val reason: String,
    val source: SourceMetadata
)

@Serializable
data class SafetyRulePackRecord(
    val id: String,
    val title: String,
    val triggerTerms: List<String>,
    val severity: String,
    val guidance: String,
    val priority: Int,
    val source: SourceMetadata
)

@Serializable
data class MedicalEscalationPackRecord(
    val id: String,
    val title: String,
    val triggerTerms: List<String>,
    val urgency: String,
    val action: String,
    val prohibitedClaims: List<String>,
    val priority: Int,
    val source: SourceMetadata
)

data class KnowledgePackCatalogue(
    val manifest: KnowledgePackManifest,
    val exercises: List<ExercisePackRecord>,
    val yogaPoses: List<YogaPosePackRecord>,
    val pakistaniFoods: List<PakistaniFoodPackRecord>,
    val foodAliases: List<FoodAliasPackRecord>,
    val coachingRules: List<CoachingRulePackRecord>,
    val workoutRules: List<WorkoutRulePackRecord>,
    val progressions: List<ProgressionPackRecord>,
    val substitutions: List<SubstitutionPackRecord>,
    val safetyRules: List<SafetyRulePackRecord>,
    val medicalEscalations: List<MedicalEscalationPackRecord>
) {
    val totalRecords: Int
        get() = allRecordIds().size

    fun allRecordIds(): List<String> = buildList {
        addAll(exercises.map(ExercisePackRecord::id))
        addAll(yogaPoses.map(YogaPosePackRecord::id))
        addAll(pakistaniFoods.map(PakistaniFoodPackRecord::id))
        addAll(foodAliases.map(FoodAliasPackRecord::id))
        addAll(coachingRules.map(CoachingRulePackRecord::id))
        addAll(workoutRules.map(WorkoutRulePackRecord::id))
        addAll(progressions.map(ProgressionPackRecord::id))
        addAll(substitutions.map(SubstitutionPackRecord::id))
        addAll(safetyRules.map(SafetyRulePackRecord::id))
        addAll(medicalEscalations.map(MedicalEscalationPackRecord::id))
    }
}
