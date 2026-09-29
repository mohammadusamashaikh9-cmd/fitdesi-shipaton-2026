package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_logs",
    indices = [
        Index(value = ["sessionId"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class WorkoutLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String? = null,
    val exerciseName: String,
    val category: String, // Cardio, Strength, Flexibility
    val durationMinutes: Int,
    @ColumnInfo(defaultValue = "0") val durationSeconds: Int = 0,
    val caloriesBurned: Int,
    @ColumnInfo(defaultValue = "0") val completedSets: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val liftingVolumeKg: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

data class WorkoutTimestampSignal(
    val rowCount: Int,
    val latestTimestamp: Long?
)
