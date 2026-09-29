package com.example.ui

import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.ai.RampUpLoadCue
import com.example.fitdesi.data.Exercise
import com.example.domain.CompletedExerciseSetCount
import java.util.UUID

internal const val TRACK_WORKOUT_REST_SECONDS = 60

enum class TrackedWorkoutPhase(val displayLabel: String) {
    GENERAL_WARMUP("GENERAL WARM-UP"),
    PREPARATION("PREPARATION"),
    MAIN_WORK("MAIN WORK"),
    COOLDOWN("COOLDOWN")
}

enum class TrackedItemType {
    ACTIVITY,
    STRENGTH
}

data class TrackedSet(
    val id: String = UUID.randomUUID().toString(),
    val previous: String,
    val weight: String,
    val reps: String,
    val isDone: Boolean = false,
    val targetReps: String? = null,
    val rampUpLoadCue: RampUpLoadCue? = null,
    val prescribedRestSeconds: Int = 0,
    val isRampUp: Boolean = false
)

data class TrackedExercise(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val sets: List<TrackedSet>,
    val exerciseId: String? = null,
    val phase: TrackedWorkoutPhase? = null,
    val trackingType: TrackedItemType? = null,
    val targetSets: Int = 0,
    val targetText: String? = null,
    val activityPrescription: ActivityPrescription? = null,
    val actualActivityReps: String? = null,
    val actualElapsedSeconds: Int = 0,
    val isActivityDone: Boolean = false,
    val instructions: String? = null,
    val safetyNote: String? = null
)

internal data class RestTimerState(
    val remainingSeconds: Int?,
    val isPaused: Boolean
)

internal data class ActiveWorkoutContext(
    val sourceRoutineId: String? = null,
    val generatedPlanId: String? = null,
    val routineName: String = "",
    val dayIndex: Int = 0,
    val dayName: String = "",
    val dayTitle: String = ""
)

internal data class ActiveWorkoutDraftSeed(
    val exercises: List<TrackedExercise>,
    val context: ActiveWorkoutContext
)

internal data class FreshActiveWorkoutState(
    val seed: ActiveWorkoutDraftSeed,
    val sessionId: String,
    val totalSeconds: Int = 0,
    val isWorkoutTimerPaused: Boolean = false,
    val restTimer: PersistedRestTimerState = PersistedRestTimerState(),
    val activityTimer: ActivityTimerState = ActivityTimerState()
)

internal enum class ActiveDraftRelationship {
    NONE,
    EXACT_PLAN_DAY_MATCH,
    OTHER_STRUCTURED_DRAFT,
    MANUAL_OR_HISTORICAL_DRAFT
}

internal data class PersistedRestTimerState(
    val deadlineEpochMillis: Long? = null,
    val pausedRemainingSeconds: Int? = null,
    val isPaused: Boolean = false
) {
    val isActive: Boolean
        get() = deadlineEpochMillis != null || (pausedRemainingSeconds ?: 0) > 0
}

internal data class ActivityTimerState(
    val activeExerciseId: String? = null,
    val isPaused: Boolean = true
)

internal enum class WorkoutFinishDecision {
    SAVE_COMPLETED_WORKOUT,
    SHOW_INCOMPLETE_OPTIONS,
    SHOW_NO_PROGRESS_OPTIONS
}

internal data class WorkoutSessionMetrics(
    val exerciseCount: Int,
    val completedExerciseCount: Int,
    val completedSets: Int,
    val totalSets: Int,
    val volumeKg: Double,
    val generalWarmupCompleted: Boolean = false,
    val totalGeneralWarmupActivities: Int = 0,
    val completedPreparationActivities: Int = 0,
    val totalPreparationActivities: Int = 0,
    val completedRampUpSets: Int = 0,
    val totalRampUpSets: Int = 0,
    val completedCooldownActivities: Int = 0,
    val totalCooldownActivities: Int = 0,
    val sessionDurationSeconds: Int = 0
) {
    val progress: Float
        get() = if (totalSets > 0) completedSets.toFloat() / totalSets else 0f
}

internal data class SessionCompletionAssessment(
    val isPlanComplete: Boolean,
    val hasAnyProgress: Boolean,
    val hasCompletedMainWorkingSet: Boolean,
    val canPersistWithCurrentWorkoutLog: Boolean
)

internal fun Exercise.toTrackedExercise(): TrackedExercise = TrackedExercise(
    name = name,
    category = category ?: bodyPart ?: "Other",
    sets = listOf(
        TrackedSet(
            previous = "—",
            weight = "",
            reps = "",
            prescribedRestSeconds = TRACK_WORKOUT_REST_SECONDS
        )
    ),
    exerciseId = id,
    phase = TrackedWorkoutPhase.MAIN_WORK,
    trackingType = TrackedItemType.STRENGTH,
    targetSets = 1
)

internal fun GeneratedRoutine.toTrackedExercises(selectedDayIndex: Int): List<TrackedExercise> {
    val day = days.getOrNull(selectedDayIndex) ?: return emptyList()
    return buildList {
        day.generalWarmup?.let { warmup ->
            add(
                TrackedExercise(
                    id = "tracked-general-warmup-0",
                    name = warmup.label,
                    category = day.title,
                    sets = emptyList(),
                    exerciseId = warmup.canonicalExerciseId.orEmpty(),
                    phase = TrackedWorkoutPhase.GENERAL_WARMUP,
                    trackingType = TrackedItemType.ACTIVITY,
                    targetText = buildList {
                        add(readableDuration(warmup.durationSeconds))
                        add(warmup.intensityCue.name.lowercase().replace('_', ' '))
                        warmup.equipment?.takeIf(String::isNotBlank)?.let { add(it) }
                    }.joinToString(" • "),
                    activityPrescription = ActivityPrescription(
                        mode = ActivityPrescriptionMode.DURATION_SECONDS,
                        durationSeconds = warmup.durationSeconds
                    )
                )
            )
        }
        day.warmupExercises.forEachIndexed { index, exercise ->
            add(exercise.toTrackedActivity(day.title, TrackedWorkoutPhase.PREPARATION, index))
        }
        day.exercises.forEachIndexed { index, exercise ->
            add(exercise.toTrackedMain(day.title, index))
        }
        day.cooldownExercises.forEachIndexed { index, exercise ->
            add(exercise.toTrackedActivity(day.title, TrackedWorkoutPhase.COOLDOWN, index))
        }
    }
}

internal fun GeneratedRoutine.toActiveWorkoutContext(
    selectedDayIndex: Int,
    sourceRoutineId: String? = null
): ActiveWorkoutContext? {
    val day = days.getOrNull(selectedDayIndex) ?: return null
    return ActiveWorkoutContext(
        sourceRoutineId = sourceRoutineId?.takeIf(String::isNotBlank),
        generatedPlanId = planId.takeIf(String::isNotBlank),
        routineName = name,
        dayIndex = selectedDayIndex,
        dayName = day.dayName,
        dayTitle = day.title
    )
}

internal fun GeneratedRoutine.toActiveWorkoutDraftSeed(
    selectedDayIndex: Int,
    sourceRoutineId: String? = null
): ActiveWorkoutDraftSeed? {
    val context = toActiveWorkoutContext(selectedDayIndex, sourceRoutineId) ?: return null
    return ActiveWorkoutDraftSeed(
        exercises = toTrackedExercises(selectedDayIndex),
        context = context
    )
}

internal fun GeneratedRoutine.freshActiveWorkoutState(
    selectedDayIndex: Int,
    sourceRoutineId: String?,
    freshSessionId: String
): FreshActiveWorkoutState? {
    val seed = toActiveWorkoutDraftSeed(selectedDayIndex, sourceRoutineId) ?: return null
    return FreshActiveWorkoutState(seed = seed, sessionId = freshSessionId)
}

internal fun activeDraftRelationship(
    hasDraft: Boolean,
    restoredContext: ActiveWorkoutContext?,
    selectedContext: ActiveWorkoutContext
): ActiveDraftRelationship {
    if (!hasDraft) return ActiveDraftRelationship.NONE
    val restored = restoredContext
        ?: return ActiveDraftRelationship.MANUAL_OR_HISTORICAL_DRAFT
    if (!restored.hasStablePlanIdentity()) {
        return ActiveDraftRelationship.MANUAL_OR_HISTORICAL_DRAFT
    }
    if (!selectedContext.hasStablePlanIdentity()) {
        return ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT
    }

    val sourceMatches = selectedContext.sourceRoutineId.isNullOrBlank() ||
        restored.sourceRoutineId == selectedContext.sourceRoutineId
    val generatedPlanMatches = selectedContext.generatedPlanId.isNullOrBlank() ||
        restored.generatedPlanId == selectedContext.generatedPlanId
    val dayMatches = restored.dayIndex == selectedContext.dayIndex &&
        restored.dayName == selectedContext.dayName &&
        restored.dayTitle == selectedContext.dayTitle

    return if (sourceMatches && generatedPlanMatches && dayMatches) {
        ActiveDraftRelationship.EXACT_PLAN_DAY_MATCH
    } else {
        ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT
    }
}

internal fun List<TrackedExercise>.normalizeTrackedExercises(): List<TrackedExercise> =
    mapIndexed { index, exercise -> exercise.normalized("tracked-restored-$index") }

internal fun resolveStrengthRestSeconds(prescribedSeconds: Int?): Int =
    prescribedSeconds?.takeIf { it > 0 } ?: TRACK_WORKOUT_REST_SECONDS

internal fun TrackedExercise.withAdditionalBlankWorkingSet(previous: String): TrackedExercise {
    val template = sets.lastOrNull { !it.isRampUp }
    return copy(
        sets = sets + TrackedSet(
            previous = previous,
            weight = "",
            reps = "",
            targetReps = template?.targetReps.orEmpty(),
            prescribedRestSeconds = resolveStrengthRestSeconds(template?.prescribedRestSeconds)
        )
    )
}

internal fun automaticRestSeconds(
    exercise: TrackedExercise,
    set: TrackedSet
): Int? = if (
    exercise.resolvedPhase() == TrackedWorkoutPhase.MAIN_WORK &&
    exercise.resolvedTrackingType() == TrackedItemType.STRENGTH
) {
    resolveStrengthRestSeconds(set.prescribedRestSeconds)
} else {
    null
}

internal fun restTimerAfterSetCompletion(
    isCompleted: Boolean,
    currentRemainingSeconds: Int?,
    isCurrentlyPaused: Boolean,
    prescribedRestSeconds: Int? = TRACK_WORKOUT_REST_SECONDS
): RestTimerState =
    if (isCompleted && prescribedRestSeconds != null) {
        RestTimerState(resolveStrengthRestSeconds(prescribedRestSeconds), isPaused = false)
    } else {
        RestTimerState(currentRemainingSeconds, isCurrentlyPaused)
    }

internal fun startPersistedRestTimer(
    durationSeconds: Int,
    nowEpochMillis: Long
): PersistedRestTimerState {
    val seconds = resolveStrengthRestSeconds(durationSeconds).coerceAtMost(MAX_PERSISTED_REST_SECONDS)
    return PersistedRestTimerState(
        deadlineEpochMillis = nowEpochMillis.coerceAtLeast(0L) + seconds * 1_000L,
        pausedRemainingSeconds = null,
        isPaused = false
    )
}

internal fun remainingRestSeconds(
    state: PersistedRestTimerState,
    nowEpochMillis: Long
): Int {
    if (state.isPaused) {
        return state.pausedRemainingSeconds.orZeroRestSeconds()
    }
    val deadline = state.deadlineEpochMillis ?: return 0
    val remainingMillis = deadline - nowEpochMillis.coerceAtLeast(0L)
    if (remainingMillis <= 0L) return 0
    return ((remainingMillis + 999L) / 1_000L)
        .coerceAtMost(MAX_PERSISTED_REST_SECONDS.toLong())
        .toInt()
}

internal fun pausePersistedRestTimer(
    state: PersistedRestTimerState,
    nowEpochMillis: Long
): PersistedRestTimerState {
    val remaining = remainingRestSeconds(state, nowEpochMillis)
    return if (remaining > 0) {
        PersistedRestTimerState(
            deadlineEpochMillis = null,
            pausedRemainingSeconds = remaining,
            isPaused = true
        )
    } else {
        PersistedRestTimerState()
    }
}

internal fun resumePersistedRestTimer(
    state: PersistedRestTimerState,
    nowEpochMillis: Long
): PersistedRestTimerState {
    val remaining = remainingRestSeconds(state, nowEpochMillis)
    return if (remaining > 0) {
        startPersistedRestTimer(remaining, nowEpochMillis)
    } else {
        PersistedRestTimerState()
    }
}

internal fun normalizePersistedRestTimer(
    state: PersistedRestTimerState?,
    nowEpochMillis: Long
): PersistedRestTimerState {
    val restored = state ?: return PersistedRestTimerState()
    val remaining = remainingRestSeconds(restored, nowEpochMillis)
    if (remaining <= 0) return PersistedRestTimerState()
    return if (restored.isPaused) {
        PersistedRestTimerState(
            pausedRemainingSeconds = remaining,
            isPaused = true
        )
    } else {
        startPersistedRestTimer(remaining, nowEpochMillis)
    }
}

internal fun skipPersistedRestTimer(): PersistedRestTimerState = PersistedRestTimerState()

internal fun toggleActivityTimer(
    state: ActivityTimerState,
    exerciseId: String
): ActivityTimerState = if (state.activeExerciseId == exerciseId && !state.isPaused) {
    state.copy(isPaused = true)
} else {
    ActivityTimerState(activeExerciseId = exerciseId, isPaused = false)
}

internal fun clearActivityTimer(): ActivityTimerState = ActivityTimerState()

internal fun isCompletedWorkingSet(set: TrackedSet): Boolean =
    !set.isRampUp && set.isDone && set.reps.toIntOrNull()?.let { it > 0 } == true

internal fun completedMainWorkingSetCounts(
    exercises: List<TrackedExercise>
): List<CompletedExerciseSetCount> = exercises
    .filter {
        it.resolvedPhase() == TrackedWorkoutPhase.MAIN_WORK &&
            it.resolvedTrackingType() == TrackedItemType.STRENGTH
    }
    .mapNotNull { exercise ->
        val count = exercise.sets.count(::isCompletedWorkingSet)
        count.takeIf { it > 0 }?.let {
            CompletedExerciseSetCount(exercise.exerciseId, count)
        }
    }

private fun isCompletedRampUpSet(set: TrackedSet): Boolean =
    set.isRampUp && set.isDone && set.reps.toIntOrNull()?.let { it > 0 } == true

internal fun calculateWorkoutSessionMetrics(
    exercises: List<TrackedExercise>,
    weightsAreKg: Boolean,
    sessionDurationSeconds: Int = 0
): WorkoutSessionMetrics {
    val mainExercises = exercises.filter {
        it.resolvedPhase() == TrackedWorkoutPhase.MAIN_WORK &&
            it.resolvedTrackingType() == TrackedItemType.STRENGTH
    }
    val workingSets = mainExercises.flatMap { exercise ->
        exercise.sets.filterNot(TrackedSet::isRampUp).map { exercise.id to it }
    }
    val completedWorkingSets = workingSets.filter { (_, set) -> isCompletedWorkingSet(set) }
    val rampUpSets = mainExercises.flatMap { it.sets.filter(TrackedSet::isRampUp) }

    val volumeKg = completedWorkingSets.sumOf { (_, set) ->
        val weight = set.weight.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@sumOf 0.0
        val reps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: return@sumOf 0.0
        val normalizedWeight = if (weightsAreKg) weight else weight / 2.20462
        normalizedWeight * reps
    }

    val preparation = exercises.filter {
        it.resolvedPhase() == TrackedWorkoutPhase.PREPARATION &&
            it.resolvedTrackingType() == TrackedItemType.ACTIVITY
    }
    val cooldown = exercises.filter {
        it.resolvedPhase() == TrackedWorkoutPhase.COOLDOWN &&
            it.resolvedTrackingType() == TrackedItemType.ACTIVITY
    }
    val generalWarmup = exercises.firstOrNull {
        it.resolvedPhase() == TrackedWorkoutPhase.GENERAL_WARMUP &&
            it.resolvedTrackingType() == TrackedItemType.ACTIVITY
    }

    return WorkoutSessionMetrics(
        exerciseCount = mainExercises.size,
        completedExerciseCount = mainExercises.count { exercise ->
            val sets = exercise.sets.filterNot(TrackedSet::isRampUp)
            sets.isNotEmpty() && sets.all(::isCompletedWorkingSet)
        },
        completedSets = completedWorkingSets.size,
        totalSets = workingSets.size,
        volumeKg = volumeKg,
        generalWarmupCompleted = generalWarmup?.isActivityDone == true,
        totalGeneralWarmupActivities = if (generalWarmup == null) 0 else 1,
        completedPreparationActivities = preparation.count(TrackedExercise::isActivityDone),
        totalPreparationActivities = preparation.size,
        completedRampUpSets = rampUpSets.count(::isCompletedRampUpSet),
        totalRampUpSets = rampUpSets.size,
        completedCooldownActivities = cooldown.count(TrackedExercise::isActivityDone),
        totalCooldownActivities = cooldown.size,
        sessionDurationSeconds = sessionDurationSeconds.coerceAtLeast(0)
    )
}

internal fun assessWorkoutCompletion(exercises: List<TrackedExercise>): SessionCompletionAssessment {
    val activities = exercises.filter { it.resolvedTrackingType() == TrackedItemType.ACTIVITY }
    val strengthSets = exercises
        .filter {
            it.resolvedPhase() == TrackedWorkoutPhase.MAIN_WORK &&
                it.resolvedTrackingType() == TrackedItemType.STRENGTH
        }
        .flatMap(TrackedExercise::sets)
    val plannedCount = activities.size + strengthSets.size
    val completedCount = activities.count(TrackedExercise::isActivityDone) +
        strengthSets.count { set ->
            if (set.isRampUp) isCompletedRampUpSet(set) else isCompletedWorkingSet(set)
        }
    val hasCompletedMainWorkingSet = strengthSets.any(::isCompletedWorkingSet)

    return SessionCompletionAssessment(
        isPlanComplete = plannedCount > 0 && completedCount == plannedCount,
        hasAnyProgress = completedCount > 0,
        hasCompletedMainWorkingSet = hasCompletedMainWorkingSet,
        canPersistWithCurrentWorkoutLog = hasCompletedMainWorkingSet
    )
}

internal fun workoutFinishDecision(
    assessment: SessionCompletionAssessment
): WorkoutFinishDecision = when {
    assessment.isPlanComplete && assessment.canPersistWithCurrentWorkoutLog ->
        WorkoutFinishDecision.SAVE_COMPLETED_WORKOUT
    assessment.hasAnyProgress -> WorkoutFinishDecision.SHOW_INCOMPLETE_OPTIONS
    else -> WorkoutFinishDecision.SHOW_NO_PROGRESS_OPTIONS
}

private fun GeneratedExercise.toTrackedActivity(
    dayTitle: String,
    phase: TrackedWorkoutPhase,
    index: Int
): TrackedExercise = TrackedExercise(
    id = "tracked-${phase.name.lowercase()}-$index",
    name = name,
    category = dayTitle,
    sets = emptyList(),
    exerciseId = exerciseId,
    phase = phase,
    trackingType = TrackedItemType.ACTIVITY,
    targetSets = sets.coerceAtLeast(0),
    targetText = reps,
    activityPrescription = activityPrescription,
    instructions = instructions,
    safetyNote = safetyNote.takeIf(String::isNotBlank)
)

private fun GeneratedExercise.toTrackedMain(dayTitle: String, index: Int): TrackedExercise {
    val trackedId = "tracked-main-$index"
    val rampUps = if (exerciseId == RAMP_UP_TRACKING_EXCLUDED_ID) {
        emptyList()
    } else {
        rampUpSets.mapIndexed { rampIndex, ramp ->
            TrackedSet(
                id = "$trackedId-ramp-$rampIndex",
                previous = "—",
                weight = "",
                reps = "",
                targetReps = ramp.repetitions.toString(),
                rampUpLoadCue = ramp.loadCue,
                prescribedRestSeconds = resolveStrengthRestSeconds(ramp.restSeconds),
                isRampUp = true
            )
        }
    }
    val workingSets = List(sets.coerceAtLeast(0)) { setIndex ->
        TrackedSet(
            id = "$trackedId-working-$setIndex",
            previous = "—",
            weight = "",
            reps = "",
            targetReps = reps,
            prescribedRestSeconds = resolveStrengthRestSeconds(restSeconds)
        )
    }
    return TrackedExercise(
        id = trackedId,
        name = name,
        category = dayTitle,
        sets = rampUps + workingSets,
        exerciseId = exerciseId,
        phase = TrackedWorkoutPhase.MAIN_WORK,
        trackingType = TrackedItemType.STRENGTH,
        targetSets = sets.coerceAtLeast(0),
        instructions = instructions,
        safetyNote = safetyNote.takeIf(String::isNotBlank)
    )
}

private fun TrackedExercise.normalized(fallbackId: String): TrackedExercise {
    val normalizedPhase = phase ?: TrackedWorkoutPhase.MAIN_WORK
    val normalizedType = trackingType ?: if (normalizedPhase == TrackedWorkoutPhase.MAIN_WORK) {
        TrackedItemType.STRENGTH
    } else {
        TrackedItemType.ACTIVITY
    }
    val normalizedSets = sets.orEmpty().mapIndexed { index, set ->
        set.copy(
            id = set.id.orEmpty().ifBlank { "$fallbackId-set-$index" },
            previous = set.previous.orEmpty().ifBlank { "—" },
            weight = set.weight.orEmpty(),
            reps = set.reps.orEmpty(),
            targetReps = set.targetReps.orEmpty(),
            prescribedRestSeconds = if (normalizedType == TrackedItemType.STRENGTH) {
                resolveStrengthRestSeconds(set.prescribedRestSeconds)
            } else {
                0
            }
        )
    }
    return copy(
        id = id.orEmpty().ifBlank { fallbackId },
        name = name.orEmpty(),
        category = category.orEmpty().ifBlank { "Other" },
        sets = normalizedSets,
        exerciseId = exerciseId.orEmpty(),
        phase = normalizedPhase,
        trackingType = normalizedType,
        targetSets = targetSets.takeIf { it > 0 }
            ?: normalizedSets.count { !it.isRampUp },
        targetText = targetText.orEmpty(),
        actualActivityReps = actualActivityReps.orEmpty(),
        actualElapsedSeconds = actualElapsedSeconds.coerceAtLeast(0),
        instructions = instructions.orEmpty(),
        safetyNote = safetyNote.orEmpty()
    )
}

private fun TrackedExercise.resolvedPhase(): TrackedWorkoutPhase =
    phase ?: TrackedWorkoutPhase.MAIN_WORK

private fun TrackedExercise.resolvedTrackingType(): TrackedItemType =
    trackingType ?: if (resolvedPhase() == TrackedWorkoutPhase.MAIN_WORK) {
        TrackedItemType.STRENGTH
    } else {
        TrackedItemType.ACTIVITY
    }

private fun readableDuration(seconds: Int): String = when {
    seconds > 0 && seconds % 60 == 0 -> "${seconds / 60} min"
    seconds > 0 -> "$seconds sec"
    else -> "Duration not specified"
}

private fun ActiveWorkoutContext.hasStablePlanIdentity(): Boolean =
    !sourceRoutineId.isNullOrBlank() || !generatedPlanId.isNullOrBlank()

private fun Int?.orZeroRestSeconds(): Int =
    (this ?: 0).coerceIn(0, MAX_PERSISTED_REST_SECONDS)

private const val RAMP_UP_TRACKING_EXCLUDED_ID = "0286"
private const val MAX_PERSISTED_REST_SECONDS = 24 * 60 * 60
