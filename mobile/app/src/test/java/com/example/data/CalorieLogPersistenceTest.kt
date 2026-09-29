package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ui.calculateNutritionTotals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalorieLogPersistenceTest {
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
    fun `food log persists serving nutrition and deletion recalculates totals`() = runBlocking {
        dao.insertCalorieLog(
            CalorieLog(
                amount = 240,
                mealType = "Lunch",
                description = "Chicken Biryani (1.5x)",
                proteinGrams = 12f,
                carbsGrams = 38f,
                fatGrams = 8f,
                servings = 1.5f,
                timestamp = 123_456L
            )
        )

        val saved = dao.getAllCalorieLogsList().single()
        assertEquals("Lunch", saved.mealType)
        assertEquals(1.5f, saved.servings, 0.001f)
        assertEquals(360, calculateNutritionTotals(listOf(saved)).calories)

        dao.deleteCalorieLog(saved)
        val remaining = dao.getAllCalorieLogsList()
        assertEquals(0, remaining.size)
        assertEquals(0, calculateNutritionTotals(remaining).calories)
    }
}
