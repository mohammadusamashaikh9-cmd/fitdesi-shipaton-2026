package com.example.exercise

@JvmInline
value class ExerciseId(val value: String) {
    init {
        require(value.isNotBlank()) { "Exercise ID must not be blank." }
    }

    override fun toString(): String = value
}

data class Exercise(
    val id: ExerciseId,
    val name: String,
    val aliases: List<String>,
    val category: String,
    val movementPattern: String,
    val bodyPart: String,
    val bodyTargets: List<String>,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val equipment: List<String>,
    val goals: List<String>,
    val experienceLevels: List<String>,
    val instructions: String,
    val safetyNote: String
)
