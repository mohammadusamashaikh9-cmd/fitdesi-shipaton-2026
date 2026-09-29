package com.example.exercise

import com.example.fitdesi.data.Exercise as BrowserExercise
import com.example.fitdesi.data.Instructions

object ExerciseBrowserAdapter {
    fun toBrowserExercise(exercise: Exercise): BrowserExercise = BrowserExercise(
        id = exercise.id.value,
        name = exercise.name,
        category = exercise.category,
        bodyPart = exercise.bodyPart,
        equipment = exercise.equipment.joinToString(),
        instructions = Instructions(en = exercise.instructions),
        muscleGroup = exercise.primaryMuscles.getOrNull(1),
        secondaryMuscles = exercise.secondaryMuscles,
        target = exercise.primaryMuscles.firstOrNull()
    )

    fun toBrowserExercises(exercises: List<Exercise>): List<BrowserExercise> =
        exercises.map(::toBrowserExercise)
}
