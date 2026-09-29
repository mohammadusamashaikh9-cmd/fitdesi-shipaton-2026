package com.example.domain

import androidx.test.core.app.ApplicationProvider
import com.example.exercise.ExerciseId
import com.example.exercise.ExerciseRepositoryProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MuscleWorkloadResolverTest {
    @Test
    fun resolverIsDeterministicAcrossAllCanonicalRecordsAndPreservesOpaqueIds() = runTest {
        val repository = ExerciseRepositoryProvider.getRepository(ApplicationProvider.getApplicationContext())
        repository.load()
        val exercises = repository.getAll()

        assertEquals(534, exercises.size)
        exercises.forEach { exercise ->
            val first = MuscleWorkloadResolver.resolve(exercise)
            val second = MuscleWorkloadResolver.resolve(exercise)
            assertEquals(exercise.id.value, first.exerciseId)
            assertEquals(first, second)
            assertTrue(first.primaryGroups.intersect(first.secondaryGroups.toSet()).isEmpty())
            assertFalse(first.primaryGroups.contains(ApprovedMuscleGroup.CARDIOVASCULAR))
            assertFalse(first.secondaryGroups.contains(ApprovedMuscleGroup.CARDIOVASCULAR))
            assertTrue("Expected a classified primary muscle for ${exercise.id.value}", first.primaryGroups.isNotEmpty())
        }

        listOf("0210", "0994", "0257", "0643", "1576", "fd-exercise-chair-squat").forEach { id ->
            assertEquals(id, repository.getById(ExerciseId(id))!!.id.value)
            assertTrue(MuscleWorkloadResolver.resolve(repository.getById(ExerciseId(id))!!).primaryGroups.isNotEmpty())
        }

        val resolved3220 = MuscleWorkloadResolver.resolve(repository.getById(ExerciseId("3220"))!!)
        assertEquals(listOf(ApprovedMuscleGroup.QUADRICEPS), resolved3220.primaryGroups)
        assertEquals(
            listOf(ApprovedMuscleGroup.HAMSTRINGS, ApprovedMuscleGroup.CALVES),
            resolved3220.secondaryGroups
        )
    }

    @Test
    fun formulaStoresRawSecondaryCreditAndAppliesHalfWeightOnlyForDisplay() {
        val result = calculateMuscleWorkload(
            completedExercises = listOf(
                CompletedExerciseSetCount("exercise", 1),
                CompletedExerciseSetCount("", 2)
            ),
            canonicalExercises = mapOf(
                "exercise" to com.example.exercise.Exercise(
                    id = ExerciseId("exercise"),
                    name = "Canonical",
                    aliases = emptyList(),
                    category = "strength",
                    movementPattern = "push",
                    bodyPart = "upper body",
                    bodyTargets = emptyList(),
                    primaryMuscles = listOf("chest"),
                    secondaryMuscles = listOf("triceps", "shoulders"),
                    equipment = emptyList(),
                    goals = emptyList(),
                    experienceLevels = emptyList(),
                    instructions = "",
                    safetyNote = ""
                )
            )
        )

        assertEquals(1, result.classifiedSets)
        assertEquals(2, result.unclassifiedSets)
        assertEquals(1.0, result.credits.single { it.muscleGroup == ApprovedMuscleGroup.CHEST }.primarySetCredits, 0.0)
        assertEquals(0.5, result.credits.single { it.muscleGroup == ApprovedMuscleGroup.TRICEPS }.secondarySetCredits, 0.0)
        assertEquals(1.5, result.credits.sumOf(MuscleSetCredit::workloadPoints), 0.0)
    }

    @Test
    fun unknownIdAndNonMainCountsAreNotGuessed() {
        val result = calculateMuscleWorkload(
            completedExercises = listOf(
                CompletedExerciseSetCount("missing", 2),
                CompletedExerciseSetCount(null, 1),
                CompletedExerciseSetCount("ignored", 0)
            ),
            canonicalExercises = emptyMap()
        )

        assertTrue(result.credits.isEmpty())
        assertEquals(3, result.unclassifiedSets)
    }

    @Test
    fun workloadCoverageRoundsReconstructedPrimaryCredits() {
        val summary = summarizePersistedMuscleWorkload(
            records = listOf(
                PersistedMuscleLoad(
                    sessionId = "fractional-session",
                    muscleGroup = ApprovedMuscleGroup.CHEST.name,
                    primarySetCredits = 0.75,
                    secondarySetCredits = 0.0,
                    completedSets = 1
                )
            ),
            totalWorkouts = 1
        )

        assertEquals(0, summary.unclassifiedSets)
    }
}
