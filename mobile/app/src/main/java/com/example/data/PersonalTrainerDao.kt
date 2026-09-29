package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalTrainerDao {
    // Calorie Logs
    @Query("SELECT * FROM calorie_logs ORDER BY timestamp DESC")
    fun getAllCalorieLogs(): Flow<List<CalorieLog>>

    @Query(
        "SELECT * FROM calorie_logs " +
            "WHERE timestamp >= :startInclusive AND timestamp < :endExclusive " +
            "ORDER BY timestamp DESC"
    )
    fun getCalorieLogsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<CalorieLog>>

    @Query("SELECT * FROM calorie_logs")
    suspend fun getAllCalorieLogsList(): List<CalorieLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalorieLog(log: CalorieLog)

    @Delete
    suspend fun deleteCalorieLog(log: CalorieLog)

    @Query("DELETE FROM calorie_logs WHERE id = :id")
    suspend fun deleteCalorieLogById(id: Int)

    // Workout Logs
    @Query("SELECT * FROM workout_logs ORDER BY timestamp DESC")
    fun getAllWorkoutLogs(): Flow<List<WorkoutLog>>

    @Query(
        "SELECT * FROM workout_logs " +
            "WHERE timestamp >= :startInclusive AND timestamp < :endExclusive " +
            "ORDER BY timestamp DESC"
    )
    fun getWorkoutLogsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<WorkoutLog>>

    @Query("SELECT * FROM workout_logs")
    suspend fun getAllWorkoutLogsList(): List<WorkoutLog>

    @Query("SELECT COUNT(*) AS rowCount, MAX(timestamp) AS latestTimestamp FROM workout_logs")
    fun observeWorkoutTimestampSignal(): Flow<WorkoutTimestampSignal>

    @Query(
        "SELECT DISTINCT timestamp FROM workout_logs " +
            "WHERE timestamp < :beforeExclusive ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getWorkoutTimestampsBefore(beforeExclusive: Long, limit: Int): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkoutLog(log: WorkoutLog): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutMuscleLoads(rows: List<WorkoutMuscleLoadEntity>)

    @Transaction
    suspend fun insertWorkoutWithMuscleLoads(
        log: WorkoutLog,
        muscleLoads: List<WorkoutMuscleLoadEntity>
    ): Long {
        val insertedId = insertWorkoutLog(log)
        if (insertedId != -1L && muscleLoads.isNotEmpty()) {
            insertWorkoutMuscleLoads(muscleLoads)
        }
        return insertedId
    }

    @Query(
        "SELECT muscle.sessionId, muscle.muscleGroup, muscle.primarySetCredits, " +
            "muscle.secondarySetCredits, workout.timestamp, workout.completedSets FROM workout_muscle_loads AS muscle " +
            "INNER JOIN workout_logs AS workout ON workout.sessionId = muscle.sessionId " +
            "WHERE workout.timestamp >= :startInclusive AND workout.timestamp < :endExclusive " +
            "ORDER BY workout.timestamp DESC, muscle.muscleGroup ASC"
    )
    fun getWorkoutMuscleLoadsInRange(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<WorkoutMuscleLoadWithTimestamp>>

    @Query(
        "SELECT muscle.sessionId, muscle.muscleGroup, muscle.primarySetCredits, " +
            "muscle.secondarySetCredits, workout.timestamp, workout.completedSets FROM workout_muscle_loads AS muscle " +
            "INNER JOIN workout_logs AS workout ON workout.sessionId = muscle.sessionId " +
            "ORDER BY workout.timestamp DESC, muscle.muscleGroup ASC"
    )
    fun getAllWorkoutMuscleLoads(): Flow<List<WorkoutMuscleLoadWithTimestamp>>

    @Delete
    suspend fun deleteWorkoutLog(log: WorkoutLog)

    @Query("DELETE FROM workout_logs")
    suspend fun deleteAllWorkoutLogs()

    // Exercise Cache (Offline First)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ExerciseEntity>)

    @Query("SELECT * FROM exercises")
    suspend fun getAll(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE category LIKE '%' || :region || '%' OR muscles LIKE '%' || :region || '%'")
    suspend fun getByRegion(region: String): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE name LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<ExerciseEntity>
}
