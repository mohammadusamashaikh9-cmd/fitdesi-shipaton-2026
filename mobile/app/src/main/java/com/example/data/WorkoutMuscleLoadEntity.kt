package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(
    tableName = "workout_muscle_loads",
    primaryKeys = ["sessionId", "muscleGroup"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutLog::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class WorkoutMuscleLoadEntity(
    val sessionId: String,
    val muscleGroup: String,
    @ColumnInfo(defaultValue = "0.0") val primarySetCredits: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val secondarySetCredits: Double = 0.0
)

data class WorkoutMuscleLoadWithTimestamp(
    val sessionId: String,
    val muscleGroup: String,
    val primarySetCredits: Double,
    val secondarySetCredits: Double,
    val timestamp: Long,
    val completedSets: Int
)
