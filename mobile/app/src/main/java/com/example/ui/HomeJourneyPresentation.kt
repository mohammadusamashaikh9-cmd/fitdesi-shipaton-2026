package com.example.ui

import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutine
import com.example.data.WorkoutLog
import com.google.gson.Gson

internal data class HomeWorkoutFacts(
    val sessionCount: Int,
    val durationSeconds: Int,
    val completedSets: Int,
)

internal sealed interface HomeWorkoutPlanPresentation {
    val workoutFacts: HomeWorkoutFacts

    data class NoActivePlan(
        override val workoutFacts: HomeWorkoutFacts,
    ) : HomeWorkoutPlanPresentation

    data class ActivePlan(
        val planName: String,
        val frequency: String,
        val dayCount: Int,
        override val workoutFacts: HomeWorkoutFacts,
    ) : HomeWorkoutPlanPresentation
}

internal fun homeWorkoutFacts(workoutLogs: List<WorkoutLog>): HomeWorkoutFacts = HomeWorkoutFacts(
    sessionCount = workoutLogs.size,
    durationSeconds = workoutLogs.sumOf { it.recordedDurationSeconds() },
    completedSets = workoutLogs.sumOf { it.completedSets.coerceAtLeast(0) },
)

internal fun parseActiveWorkoutPlan(activePlanJson: String): GeneratedRoutine? {
    if (activePlanJson.isBlank()) return null
    return runCatching {
        val routine = Gson().fromJson(activePlanJson, GeneratedRoutine::class.java)
            ?: return@runCatching null
        routine.name.length
        routine.description.length
        routine.splitType.length
        routine.frequency.length
        routine.days.forEach { day ->
            day.dayName.length
            day.title.length
            day.description.length
            day.exercises.forEach { exercise ->
                exercise.name.length
                exercise.reps.length
                exercise.targetMuscle.length
                exercise.instructions.length
            }
        }
        routine.takeIf { routine.name.isNotBlank() || routine.days.isNotEmpty() }
    }.getOrNull()
}

internal fun activePlanIsBackedBySavedRoutine(
    activePlan: GeneratedRoutine,
    savedRoutines: List<SavedRoutine>,
): Boolean = activePlan.planId.isNotBlank() && savedRoutines.any { savedRoutine ->
    savedRoutine.routineId == activePlan.planId && savedRoutine.routine == activePlan
}

internal fun homeWorkoutPlanPresentation(
    activePlanJson: String,
    todayWorkouts: List<WorkoutLog>,
): HomeWorkoutPlanPresentation {
    val facts = homeWorkoutFacts(todayWorkouts)
    val activePlan = parseActiveWorkoutPlan(activePlanJson)
        ?: return HomeWorkoutPlanPresentation.NoActivePlan(facts)
    return HomeWorkoutPlanPresentation.ActivePlan(
        planName = activePlan.name.trim().ifBlank { "Current workout plan" },
        frequency = activePlan.frequency.trim(),
        dayCount = activePlan.days.size,
        workoutFacts = facts,
    )
}

internal fun homeWorkoutRowDetails(log: WorkoutLog): String =
    "${log.category} • ${log.formattedRecordedDuration()} • ${log.completedSets.coerceAtLeast(0)} completed sets"
