package com.example.exercise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExerciseRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `verified knowledge pack loads exactly 534 unique opaque IDs`() = runBlocking {
        val exercises = repository().getAll()

        assertEquals(534, exercises.size)
        assertEquals(534, exercises.map { it.id.value }.toSet().size)
        assertEquals("0001", repository().getById(ExerciseId("0001"))?.id?.value)
    }

    @Test
    fun `B3B catalogue contains only approved authored and upstream movements`() = runBlocking {
        val exerciseRepository = repository()
        val approved = setOf(
            "fd-exercise-shoulder-rolls",
            "fd-exercise-arm-circles",
            "fd-exercise-open-book-thoracic-rotation",
            "fd-exercise-standing-hip-circles",
            "fd-exercise-front-back-leg-swings",
            "fd-exercise-lateral-leg-swings",
            "fd-exercise-seated-biceps-stretch",
            "fd-exercise-wrist-flexor-extensor-stretch",
            "fd-exercise-childs-pose-lat-back-stretch",
            "0643",
            "1576"
        )

        approved.forEach { id -> assertEquals(id, exerciseRepository.getById(ExerciseId(id))?.id?.value) }
        assertNull(exerciseRepository.getById(ExerciseId("fd-exercise-overhead-triceps-stretch")))
        assertNull(exerciseRepository.getById(ExerciseId("fd-exercise-supine-hamstring-stretch")))
    }

    @Test
    fun `0316 retains its ID with corrected pinned upstream muscles`() = runBlocking {
        val exercise = repository().getById(ExerciseId("0316"))

        assertEquals("0316", exercise?.id?.value)
        assertEquals(listOf("pectorals", "shoulders"), exercise?.primaryMuscles)
        assertEquals(listOf("shoulders", "triceps"), exercise?.secondaryMuscles)
    }

    @Test
    fun `chair squat is retrievable by its stable string ID`() = runBlocking {
        val exercise = repository().getById(ExerciseId("fd-exercise-chair-squat"))

        assertEquals("Chair Squat", exercise?.name)
        assertEquals("fd-exercise-chair-squat", exercise?.id?.value)
    }

    @Test
    fun `duplicate normalized names return every deterministic match`() = runBlocking {
        val matches = repository().getByExactName("  BARBELL   SEATED CALF RAISE ")

        assertEquals(listOf("0088", "1371"), matches.map { it.id.value })
    }

    @Test
    fun `search ordering is stable across calls`() = runBlocking {
        val exerciseRepository = repository()

        val first = exerciseRepository.search("barbell seated calf raise").map { it.id.value }
        val second = exerciseRepository.search("barbell seated calf raise").map { it.id.value }

        assertEquals(listOf("0088", "1371"), first)
        assertEquals(first, second)
    }

    @Test
    fun `equipment and muscle indexes filter without duplicating records`() = runBlocking {
        val exerciseRepository = repository()

        val chairExercises = exerciseRepository.filter(ExerciseFilter(equipment = "CHAIR"))
        val quadricepsExercises = exerciseRepository.filter(ExerciseFilter(primaryMuscle = "quadriceps"))
        val lowerBackExercises = exerciseRepository.filter(ExerciseFilter(secondaryMuscle = "lower back"))

        assertTrue(chairExercises.any { it.id.value == "fd-exercise-chair-squat" })
        assertEquals(chairExercises.map { it.id }.toSet().size, chairExercises.size)
        assertTrue(quadricepsExercises.isNotEmpty())
        assertTrue(quadricepsExercises.all { exercise ->
            exercise.primaryMuscles.any { it.equals("quadriceps", ignoreCase = true) }
        })
        assertTrue(lowerBackExercises.isNotEmpty())
        assertTrue(lowerBackExercises.all { exercise ->
            exercise.secondaryMuscles.any { it.equals("lower back", ignoreCase = true) }
        })
    }

    @Test
    fun `one repository reads and caches the pack only once`() = runBlocking {
        val text = context.assets.open(ExerciseRepository.ASSET_PATH)
            .bufferedReader()
            .use { it.readText() }
        var reads = 0
        val exerciseRepository = ExerciseRepository {
            reads += 1
            text
        }

        val first = exerciseRepository.getAll()
        val second = exerciseRepository.getAll()
        exerciseRepository.load()

        assertEquals(1, reads)
        assertSame(first, second)
    }

    @Test
    fun `application provider returns one shared repository`() {
        val first = ExerciseRepositoryProvider.getRepository(context)
        val second = ExerciseRepositoryProvider.getRepository(context.applicationContext)

        assertSame(first, second)
        assertNotSame(first, repository())
    }

    @Test
    fun `load failures remain explicit and never become an empty ready catalogue`() = runBlocking {
        val exerciseRepository = ExerciseRepository {
            throw IllegalStateException("test-only source failure")
        }

        val result = exerciseRepository.load()

        assertTrue(result is ExerciseCatalogueState.Error)
        assertEquals(ExerciseRepository.LOAD_ERROR, (result as ExerciseCatalogueState.Error).message)
        assertTrue(exerciseRepository.state.value is ExerciseCatalogueState.Error)
    }

    private fun repository(): ExerciseRepository = ExerciseRepository.fromAssets(context)
}
