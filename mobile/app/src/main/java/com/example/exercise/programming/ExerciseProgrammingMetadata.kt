package com.example.exercise.programming

import java.util.Collections
import kotlinx.serialization.Serializable

data class ExerciseProgrammingMetadata(
    val exerciseId: String,
    val roles: List<ExerciseProgrammingRole>,
    val programmingRegions: List<ExerciseProgrammingRegion>,
    val joints: List<ExerciseProgrammingJoint>,
    val movementPatterns: List<String>,
    val prescriptionMode: ExercisePrescriptionMode,
    val defaultRepetitions: Int?,
    val defaultDurationSeconds: Int?,
    val freeTextPrescription: String?,
    val perSide: Boolean,
    val equipmentOverride: List<String>,
    val equipment: List<String>,
    val duplicateFamilyKey: String?,
    val beginnerSuitable: Boolean,
    val autoSelectApproved: Boolean,
    val reviewStatus: ExerciseProgrammingReviewStatus,
    val neverAutoSelectReason: String?
)

data class ExerciseProgrammingCoverage(
    val totalOverlayRecords: Int,
    val countByRole: Map<ExerciseProgrammingRole, Int>,
    val countByProgrammingRegion: Map<ExerciseProgrammingRegion, Int>,
    val countByEquipment: Map<String, Int>,
    val regionsWithNoReviewedPreparationOption: List<ExerciseProgrammingRegion>,
    val regionsWithNoReviewedCooldownOption: List<ExerciseProgrammingRegion>
)

@Serializable
internal data class ExerciseProgrammingPackDto(
    val schemaVersion: Int,
    val records: List<ExerciseProgrammingRecordDto>
)

@Serializable
internal data class ExerciseProgrammingRecordDto(
    val exerciseId: String,
    val roles: List<ExerciseProgrammingRole>,
    val programmingRegions: List<ExerciseProgrammingRegion>,
    val joints: List<ExerciseProgrammingJoint> = emptyList(),
    val movementPatterns: List<String> = emptyList(),
    val prescriptionMode: ExercisePrescriptionMode,
    val defaultRepetitions: Int? = null,
    val defaultDurationSeconds: Int? = null,
    val freeTextPrescription: String? = null,
    val perSide: Boolean,
    val equipmentOverride: List<String> = emptyList(),
    val duplicateFamilyKey: String? = null,
    val beginnerSuitable: Boolean,
    val autoSelectApproved: Boolean,
    val reviewStatus: ExerciseProgrammingReviewStatus,
    val neverAutoSelectReason: String? = null
)

internal fun <T> List<T>.immutableProgrammingList(): List<T> =
    Collections.unmodifiableList(toList())

internal fun <K, V> Map<K, V>.immutableProgrammingMap(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))
