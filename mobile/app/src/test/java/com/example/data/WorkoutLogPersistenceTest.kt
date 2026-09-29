package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutLogPersistenceTest {
    private var database: AppDatabase? = null
    private lateinit var dao: PersonalTrainerDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val createdDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = createdDatabase
        dao = createdDatabase.trainerDao()
    }

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun completedWorkoutPersistsRealSessionMetrics() = runBlocking {
        val rowId = dao.insertWorkoutLog(
            WorkoutLog(
                sessionId = "session-1",
                exerciseName = "Barbell Squat, Bench Press",
                category = "Strength",
                durationMinutes = 42,
                durationSeconds = 2_537,
                caloriesBurned = 96,
                completedSets = 8,
                liftingVolumeKg = 4_320.5,
                timestamp = 123_456L
            )
        )

        assertTrue(rowId > 0)
        val saved = dao.getAllWorkoutLogsList().single()
        assertEquals("session-1", saved.sessionId)
        assertEquals(8, saved.completedSets)
        assertEquals(4_320.5, saved.liftingVolumeKg, 0.001)
        assertEquals(42, saved.durationMinutes)
        assertEquals(2_537, saved.durationSeconds)
    }

    @Test
    fun duplicateSessionIdIsIgnored() = runBlocking {
        val workout = WorkoutLog(
            sessionId = "same-session",
            exerciseName = "Barbell Squat",
            category = "Strength",
            durationMinutes = 15,
            durationSeconds = 905,
            caloriesBurned = 36,
            completedSets = 3,
            liftingVolumeKg = 1_200.0
        )

        assertTrue(dao.insertWorkoutLog(workout) > 0)
        assertEquals(-1L, dao.insertWorkoutLog(workout))
        assertEquals(1, dao.getAllWorkoutLogsList().size)
    }
}
