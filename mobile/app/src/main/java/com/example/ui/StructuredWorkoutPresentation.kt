package com.example.ui

import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneralWarmupPrescription
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.WarmupIntensity

internal enum class StructuredWorkoutPhase(val label: String) {
    GENERAL_WARM_UP("GENERAL WARM-UP"),
    PREPARATION("PREPARATION"),
    MAIN_WORKOUT("MAIN WORKOUT"),
    COOL_DOWN("COOL-DOWN")
}

internal data class StructuredWorkoutSection(
    val phase: StructuredWorkoutPhase,
    val generalWarmup: GeneralWarmupPrescription? = null,
    val exercises: List<GeneratedExercise> = emptyList()
)

internal data class StructuredWorkoutDayPresentation(
    val isLegacyDisplay: Boolean,
    val sections: List<StructuredWorkoutSection>
)

/** Partitions a day for display without copying or changing any exercise data. */
internal fun structuredWorkoutDayPresentation(day: GeneratedDay): StructuredWorkoutDayPresentation {
    val isLegacyDisplay = day.generalWarmup == null &&
        day.warmupExercises.isEmpty() &&
        day.cooldownExercises.isEmpty() &&
        day.exercises.all { exercise ->
            exercise.rampUpSets.isEmpty() &&
                exercise.activityPrescription == null &&
                exercise.safetyNote.isBlank()
        }

    return StructuredWorkoutDayPresentation(
        isLegacyDisplay = isLegacyDisplay,
        sections = buildList {
            day.generalWarmup?.let {
                add(StructuredWorkoutSection(StructuredWorkoutPhase.GENERAL_WARM_UP, generalWarmup = it))
            }
            if (day.warmupExercises.isNotEmpty()) {
                add(StructuredWorkoutSection(StructuredWorkoutPhase.PREPARATION, exercises = day.warmupExercises))
            }
            if (day.exercises.isNotEmpty()) {
                add(StructuredWorkoutSection(StructuredWorkoutPhase.MAIN_WORKOUT, exercises = day.exercises))
            }
            if (day.cooldownExercises.isNotEmpty()) {
                add(StructuredWorkoutSection(StructuredWorkoutPhase.COOL_DOWN, exercises = day.cooldownExercises))
            }
        }
    )
}

internal fun formatStructuredDuration(totalSeconds: Int): String? {
    if (totalSeconds <= 0) return null
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (minutes > 0) add("$minutes ${if (minutes == 1) "minute" else "minutes"}")
        if (seconds > 0) add("$seconds ${if (seconds == 1) "second" else "seconds"}")
    }.joinToString(" ")
}

internal fun formatActivityPrescription(prescription: ActivityPrescription?): String? {
    prescription ?: return null
    val base = when (prescription.mode) {
        ActivityPrescriptionMode.REPETITIONS -> prescription.repetitions
            ?.takeIf { it > 0 }
            ?.let { "$it ${if (it == 1) "repetition" else "repetitions"}" }
        ActivityPrescriptionMode.DURATION_SECONDS -> prescription.durationSeconds
            ?.let(::formatStructuredDuration)
        ActivityPrescriptionMode.FREE_TEXT -> prescription.freeText
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    } ?: return null

    return if (prescription.perSide && !base.contains("per side", ignoreCase = true)) {
        "$base per side"
    } else {
        base
    }
}

internal fun warmupIntensityLabel(intensity: WarmupIntensity): String = when (intensity) {
    WarmupIntensity.EASY -> "Easy"
    WarmupIntensity.EASY_TO_MODERATE -> "Easy to moderate"
    WarmupIntensity.MODERATE -> "Moderate"
}

internal fun rampUpLoadCueLabel(loadCue: RampUpLoadCue): String = when (loadCue) {
    RampUpLoadCue.VERY_LIGHT -> "Very light"
    RampUpLoadCue.LIGHT -> "Light"
    RampUpLoadCue.MODERATE -> "Moderate"
}

internal fun formatRampUpSetRow(set: RampUpSetPrescription): String? {
    if (set.ordinal <= 0 || set.repetitions <= 0) return null
    return buildString {
        append("Set ${set.ordinal}: ${rampUpLoadCueLabel(set.loadCue)}, ${set.repetitions} ")
        append(if (set.repetitions == 1) "repetition" else "repetitions")
        if (set.restSeconds > 0) append(", ${set.restSeconds} seconds rest")
    }
}

internal fun displayedSafetyNote(exercise: GeneratedExercise): String? =
    exercise.safetyNote.takeIf(String::isNotBlank)
