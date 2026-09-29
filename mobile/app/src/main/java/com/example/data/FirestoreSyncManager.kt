package com.example.data

import android.content.Context

/**
 * Compatibility surface for the retired Build Week Firestore prototype.
 *
 * Stage 11A enables Firebase Authentication only. Fitness data remains local to
 * this installation, so every legacy sync entry point is an unconditional no-op.
 */
@Suppress("UNUSED_PARAMETER")
object FirestoreSyncManager {
    suspend fun saveWeightPreference(context: Context, isKgSelected: Boolean) = Unit

    suspend fun fetchWeightPreference(context: Context): Boolean? = null

    suspend fun syncCalorieLog(context: Context, log: CalorieLog) = Unit

    suspend fun deleteCalorieLog(context: Context, log: CalorieLog) = Unit

    suspend fun syncWorkoutLog(context: Context, log: WorkoutLog) = Unit

    suspend fun deleteWorkoutLog(context: Context, log: WorkoutLog) = Unit

    suspend fun deleteAllWorkoutLogs(context: Context) = Unit

    suspend fun performStartupSync(context: Context, repository: TrainerRepository) = Unit
}
