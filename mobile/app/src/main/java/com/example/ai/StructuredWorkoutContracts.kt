package com.example.ai

import kotlinx.serialization.Serializable

@Serializable
enum class ActivityPrescriptionMode {
    REPETITIONS,
    DURATION_SECONDS,
    FREE_TEXT
}

@Serializable
data class ActivityPrescription(
    val mode: ActivityPrescriptionMode,
    val repetitions: Int? = null,
    val durationSeconds: Int? = null,
    val freeText: String? = null,
    val perSide: Boolean = false
) {
    fun isStructurallyValid(): Boolean = when (mode) {
        ActivityPrescriptionMode.REPETITIONS -> repetitions?.let { it > 0 } == true
        ActivityPrescriptionMode.DURATION_SECONDS -> durationSeconds?.let { it > 0 } == true
        ActivityPrescriptionMode.FREE_TEXT -> freeText?.isNotBlank() == true
    }
}

@Serializable
enum class WarmupIntensity {
    EASY,
    EASY_TO_MODERATE,
    MODERATE
}

@Serializable
data class GeneralWarmupPrescription(
    val label: String,
    val durationSeconds: Int,
    val intensityCue: WarmupIntensity,
    val canonicalExerciseId: String? = null,
    val equipment: String? = null
) {
    fun isStructurallyValid(): Boolean =
        label.isNotBlank() &&
            durationSeconds > 0 &&
            canonicalExerciseId?.isNotBlank() != false &&
            equipment?.isNotBlank() != false
}

@Serializable
enum class RampUpLoadCue {
    VERY_LIGHT,
    LIGHT,
    MODERATE
}

@Serializable
data class RampUpSetPrescription(
    val ordinal: Int,
    val loadCue: RampUpLoadCue,
    val repetitions: Int,
    val restSeconds: Int
) {
    fun isStructurallyValid(): Boolean =
        ordinal > 0 && repetitions > 0 && restSeconds >= 0
}
