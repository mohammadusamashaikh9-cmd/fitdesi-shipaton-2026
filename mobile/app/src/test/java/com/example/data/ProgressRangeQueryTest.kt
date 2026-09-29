package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import com.example.viewmodel.progressAccessFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgressRangeQueryTest {
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
    fun `range queries are inclusive start exclusive end and timestamp descending`() = runBlocking {
        listOf(999L, 1_000L, 1_999L, 2_000L).forEachIndexed { index, timestamp ->
            dao.insertCalorieLog(
                CalorieLog(
                    amount = 100 + index,
                    mealType = "Lunch",
                    description = "Food $index",
                    timestamp = timestamp
                )
            )
            dao.insertWorkoutLog(
                WorkoutLog(
                    sessionId = "session-$index",
                    exerciseName = "Workout $index",
                    category = "Strength",
                    durationMinutes = 10,
                    caloriesBurned = 0,
                    timestamp = timestamp
                )
            )
        }

        val calories = dao.getCalorieLogsInRange(1_000L, 2_000L).first()
        val workouts = dao.getWorkoutLogsInRange(1_000L, 2_000L).first()

        assertEquals(listOf(1_999L, 1_000L), calories.map { it.timestamp })
        assertEquals(listOf(1_999L, 1_000L), workouts.map { it.timestamp })
    }

    @Test
    fun `entitlement changes do not delete persisted logs`() = runBlocking {
        dao.insertCalorieLog(
            CalorieLog(amount = 200, mealType = "Dinner", description = "Persisted meal")
        )
        dao.insertWorkoutLog(
            WorkoutLog(
                sessionId = "persisted-session",
                exerciseName = "Persisted workout",
                category = "Strength",
                durationMinutes = 20,
                caloriesBurned = 0
            )
        )

        listOf(SubscriptionTier.PLUS, SubscriptionTier.BASIC, SubscriptionTier.PRO).forEach { tier ->
            progressAccessFor(
                SubscriptionState(
                    tier = tier,
                    status = SubscriptionStatus.READY,
                    hasAuthoritativeCustomerInfo = true
                )
            )
        }

        assertEquals(1, dao.getAllCalorieLogsList().size)
        assertEquals(1, dao.getAllWorkoutLogsList().size)
    }
}
