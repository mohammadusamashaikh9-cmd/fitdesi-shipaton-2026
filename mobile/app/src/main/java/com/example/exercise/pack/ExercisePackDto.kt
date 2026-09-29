package com.example.exercise.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ExercisePackDto(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val movementPattern: String,
    val category: String,
    val bodyPart: String,
    val target: String?,
    val muscleGroup: String?,
    val secondaryMuscles: List<String>,
    val bodyTargets: List<String>,
    val equipment: List<String>,
    val experienceLevels: List<String>,
    val goals: List<String>,
    val instructions: String,
    val safetyNote: String,
    val source: JsonObject
)
