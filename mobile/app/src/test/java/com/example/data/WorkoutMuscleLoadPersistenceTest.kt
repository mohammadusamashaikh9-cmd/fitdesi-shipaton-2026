package com.example.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutMuscleLoadPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PersonalTrainerDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.trainerDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun parentAndChildrenAreAtomicAndDuplicateSessionCreatesNoNewChildren() = runTest {
        val parent = workout("session-one")
        val firstId = dao.insertWorkoutWithMuscleLoads(
            parent,
            listOf(load("session-one", "CHEST", 2.0, 0.0))
        )
        val duplicateId = dao.insertWorkoutWithMuscleLoads(
            parent,
            listOf(load("session-one", "TRICEPS", 0.0, 2.0))
        )

        assertTrue(firstId > 0)
        assertEquals(-1L, duplicateId)
        assertEquals(listOf("CHEST"), dao.getAllWorkoutMuscleLoads().first().map { it.muscleGroup })
    }

    @Test
    fun childFailureRollsBackNewParent() = runTest {
        runCatching {
            dao.insertWorkoutWithMuscleLoads(
                workout("parent-session"),
                listOf(load("different-session", "CHEST", 1.0, 0.0))
            )
        }

        assertTrue(dao.getAllWorkoutLogs().first().none { it.sessionId == "parent-session" })
    }

    @Test
    fun deletingOneOrAllParentsCascadesWorkloadRows() = runTest {
        val first = workout("first")
        val firstId = dao.insertWorkoutWithMuscleLoads(first, listOf(load("first", "CHEST", 1.0, 0.0)))
        dao.insertWorkoutWithMuscleLoads(workout("second"), listOf(load("second", "BACK", 1.0, 0.0)))

        dao.deleteWorkoutLog(first.copy(id = firstId.toInt()))
        assertEquals(listOf("second"), dao.getAllWorkoutMuscleLoads().first().map { it.sessionId }.distinct())

        dao.deleteAllWorkoutLogs()
        assertTrue(dao.getAllWorkoutMuscleLoads().first().isEmpty())
    }

    private fun workout(sessionId: String) = WorkoutLog(
        sessionId = sessionId,
        exerciseName = "Saved workout",
        category = "Strength",
        durationMinutes = 30,
        caloriesBurned = 0,
        completedSets = 2,
        timestamp = 1_780_000_000_000L
    )

    private fun load(
        sessionId: String,
        group: String,
        primary: Double,
        secondary: Double
    ) = WorkoutMuscleLoadEntity(sessionId, group, primary, secondary)
}
