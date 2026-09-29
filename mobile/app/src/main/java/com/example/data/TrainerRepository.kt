package com.example.data

import com.example.data.api.ExerciseDBApiClient
import com.example.data.api.ExerciseDBExercise
import com.example.security.SafeLog
import kotlinx.coroutines.flow.Flow

class TrainerRepository(private val dao: PersonalTrainerDao) {
    val allCalorieLogs: Flow<List<CalorieLog>> = dao.getAllCalorieLogs()
    val allWorkoutLogs: Flow<List<WorkoutLog>> = dao.getAllWorkoutLogs()
    val allWorkoutMuscleLoads: Flow<List<WorkoutMuscleLoadWithTimestamp>> = dao.getAllWorkoutMuscleLoads()
    val workoutTimestampSignal: Flow<WorkoutTimestampSignal> = dao.observeWorkoutTimestampSignal()

    fun getCalorieLogsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<CalorieLog>> = dao.getCalorieLogsInRange(startInclusive, endExclusive)

    fun getWorkoutLogsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<WorkoutLog>> = dao.getWorkoutLogsInRange(startInclusive, endExclusive)

    fun getWorkoutMuscleLoadsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<WorkoutMuscleLoadWithTimestamp>> =
        dao.getWorkoutMuscleLoadsInRange(startInclusive, endExclusive)

    suspend fun getAllCalorieLogsList(): List<CalorieLog> = dao.getAllCalorieLogsList()
    suspend fun getAllWorkoutLogsList(): List<WorkoutLog> = dao.getAllWorkoutLogsList()

    suspend fun getAllWorkoutTimestampsDescending(pageSize: Int = 256): List<Long> {
        require(pageSize > 0) { "pageSize must be positive" }
        val timestamps = mutableListOf<Long>()
        var beforeExclusive = Long.MAX_VALUE
        while (true) {
            val page = dao.getWorkoutTimestampsBefore(beforeExclusive, pageSize)
            if (page.isEmpty()) break
            timestamps += page
            if (page.size < pageSize) break
            beforeExclusive = page.last()
        }
        return timestamps
    }

    suspend fun insertCalorieLog(log: CalorieLog) = dao.insertCalorieLog(log)
    suspend fun deleteCalorieLog(log: CalorieLog) = dao.deleteCalorieLog(log)
    suspend fun deleteCalorieLogById(id: Int) = dao.deleteCalorieLogById(id)

    suspend fun insertWorkoutLog(log: WorkoutLog) = dao.insertWorkoutLog(log)
    suspend fun insertWorkoutWithMuscleLoads(
        log: WorkoutLog,
        muscleLoads: List<WorkoutMuscleLoadEntity>
    ) = dao.insertWorkoutWithMuscleLoads(log, muscleLoads)
    suspend fun deleteWorkoutLog(log: WorkoutLog) = dao.deleteWorkoutLog(log)
    suspend fun deleteAllWorkoutLogs() = dao.deleteAllWorkoutLogs()

    suspend fun getExerciseDBExercises(): List<ExerciseDBExercise> {
        return runCatching {
            val response = ExerciseDBApiClient.service.getExercises()
            response.data ?: emptyList()
        }.onFailure { e ->
            SafeLog.error("ExerciseRepository", "Remote exercise fetch failed", e)
        }.getOrThrow()
    }

    suspend fun insertAllExercises(list: List<ExerciseEntity>) = dao.insertAll(list)
    suspend fun getAllCachedExercises(): List<ExerciseEntity> = dao.getAll()
    suspend fun getExercisesByRegion(region: String): List<ExerciseEntity> = dao.getByRegion(region)
    suspend fun searchExercises(query: String): List<ExerciseEntity> = dao.search(query)
}
