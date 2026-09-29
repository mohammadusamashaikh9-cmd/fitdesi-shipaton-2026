package com.example.ui

import com.example.ai.GeneratedExercise
import com.example.fitdesi.data.Exercise
import com.example.fitdesi.data.Instructions
import com.example.viewmodel.ExerciseUiState
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildWorkoutScreenTest {
    @Test
    fun `blank query and All return every canonical browser exercise`() {
        assertEquals(exercises, filterBuildWorkoutExercises(exercises, "   ", "All"))
    }

    @Test
    fun `search matches name target and body part without case sensitivity`() {
        assertEquals(
            listOf("0001"),
            filterBuildWorkoutExercises(exercises, "LEADING ZERO", "All").map { it.id }
        )
        assertEquals(
            listOf("fd-exercise-chair-squat"),
            filterBuildWorkoutExercises(exercises, "QUADRICEPS", "All").map { it.id }
        )
        assertEquals(
            listOf("0088", "1371"),
            filterBuildWorkoutExercises(exercises, "LOWER LEGS", "All").map { it.id }
        )
    }

    @Test
    fun `category and query are combined locally`() {
        assertEquals(
            listOf("0088", "1371"),
            filterBuildWorkoutExercises(exercises, "calf", "lower legs").map { it.id }
        )
        assertTrue(filterBuildWorkoutExercises(exercises, "chair", "lower legs").isEmpty())
    }

    @Test
    fun `loading empty catalogue and no matches remain distinct`() {
        assertEquals(
            BuildWorkoutCatalogueContent.Loading,
            buildWorkoutCatalogueContent(ExerciseUiState.Loading, "", "All")
        )
        assertEquals(
            BuildWorkoutCatalogueContent.EmptyCatalogue,
            buildWorkoutCatalogueContent(ExerciseUiState.Success(emptyList()), "", "All")
        )
        assertEquals(
            BuildWorkoutCatalogueContent.NoMatches,
            buildWorkoutCatalogueContent(ExerciseUiState.Success(exercises), "not-present", "All")
        )
    }

    @Test
    fun `repository error uses a safe message and exposes retry`() {
        val content = buildWorkoutCatalogueContent(
            ExerciseUiState.Error("raw repository detail must not be shown"),
            "",
            "All"
        ) as BuildWorkoutCatalogueContent.Error

        assertEquals(BUILD_WORKOUT_EXERCISE_LOAD_ERROR, content.message)
        assertTrue(content.retryAvailable)
        assertFalse(content.message.contains("raw repository detail"))
    }

    @Test
    fun `duplicate names are selected and removed independently by exact ID`() {
        val selected = mutableListOf<SelectedExerciseConfig>()
        val firstDuplicate = exercises.first { it.id == "0088" }
        val secondDuplicate = exercises.first { it.id == "1371" }

        selected.toggleBuildWorkoutExercise(firstDuplicate)
        selected.toggleBuildWorkoutExercise(secondDuplicate)
        assertEquals(listOf("0088", "1371"), selected.map { it.exercise.id })

        selected.toggleBuildWorkoutExercise(firstDuplicate)
        assertEquals(listOf("1371"), selected.map { it.exercise.id })
    }

    @Test
    fun `sets and reps updates preserve the selected canonical ID`() {
        val selected = mutableListOf(
            SelectedExerciseConfig(exercises.first { it.id == "0001" })
        )

        selected.updateBuildWorkoutExercise("0001") {
            it.copy(sets = 5, reps = "8")
        }

        assertEquals("0001", selected.single().exercise.id)
        assertEquals(5, selected.single().sets)
        assertEquals("8", selected.single().reps)
    }

    @Test
    fun `routine conversion and Gson round trip preserve canonical exercise ID`() {
        val selected = SelectedExerciseConfig(
            exercise = exercises.first { it.id == "fd-exercise-chair-squat" },
            sets = 4,
            reps = "10"
        )

        val generated = selected.toGeneratedExercise()
        val restored = Gson().fromJson(Gson().toJson(generated), GeneratedExercise::class.java)

        assertEquals("fd-exercise-chair-squat", generated.exerciseId)
        assertEquals("fd-exercise-chair-squat", restored.exerciseId)
        assertEquals(4, restored.sets)
        assertEquals("10", restored.reps)
    }

    @Test
    fun `legacy GeneratedExercise with empty ID remains readable`() {
        val legacy = GeneratedExercise(
            name = "Legacy Exercise",
            sets = 3,
            reps = "12",
            targetMuscle = "General",
            instructions = "Legacy instructions"
        )

        val restored = Gson().fromJson(Gson().toJson(legacy), GeneratedExercise::class.java)

        assertEquals("Legacy Exercise", restored.name)
        assertEquals("", restored.exerciseId)
    }

    companion object {
        private val exercises = listOf(
            exercise(
                id = "0001",
                name = "Leading Zero Press",
                category = "chest",
                bodyPart = "chest",
                target = "pectorals"
            ),
            exercise(
                id = "fd-exercise-chair-squat",
                name = "Chair Squat",
                category = "strength",
                bodyPart = "upper legs",
                target = "quadriceps"
            ),
            exercise(
                id = "0088",
                name = "Barbell Seated Calf Raise",
                category = "strength",
                bodyPart = "lower legs",
                target = "calves"
            ),
            exercise(
                id = "1371",
                name = "Barbell Seated Calf Raise",
                category = "strength",
                bodyPart = "lower legs",
                target = "calves"
            )
        )

        private fun exercise(
            id: String,
            name: String,
            category: String,
            bodyPart: String,
            target: String
        ) = Exercise(
            id = id,
            name = name,
            category = category,
            bodyPart = bodyPart,
            equipment = "body weight",
            instructions = Instructions(en = "Test instructions"),
            target = target
        )
    }
}
