package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String,
    val muscles: String,
    val equipment: String,
    val difficulty: String,
    val instructions: String,
    val videoUrl: String,
    val thumbnail: String,
    val grip: String? = null,
    val mechanic: String? = null,
    val force: String? = null,
    val howToSetup: String? = null,
    val howToPerform: String? = null,
    val properTechnique: String? = null,
    val thingsToAvoid: String? = null
)
