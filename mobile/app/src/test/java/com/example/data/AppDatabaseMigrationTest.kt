package com.example.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate6To7PreservesRowsAddsValidatedSchemaAndEnforcesForeignKeys() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO workout_logs " +
                    "(id, sessionId, exerciseName, category, durationMinutes, durationSeconds, caloriesBurned, completedSets, liftingVolumeKg, timestamp) " +
                    "VALUES (1, 'session-one', 'Saved workout', 'Strength', 30, 1800, 0, 2, 1200.0, 1780000000000)"
            )
            execSQL(
                "INSERT INTO workout_logs " +
                    "(id, sessionId, exerciseName, category, durationMinutes, durationSeconds, caloriesBurned, completedSets, liftingVolumeKg, timestamp) " +
                    "VALUES (2, NULL, 'Legacy workout', 'Strength', 20, 1200, 0, 0, 0.0, 1770000000000)"
            )
            execSQL(
                "INSERT INTO calorie_logs " +
                    "(id, amount, mealType, description, proteinGrams, carbsGrams, fatGrams, servings, timestamp) " +
                    "VALUES (1, 500, 'Dinner', 'Saved meal', 20.0, 50.0, 10.0, 1.0, 1780000000000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        assertEquals(1, migrated.longForQuery("PRAGMA foreign_keys"))
        assertEquals(2, migrated.longForQuery("SELECT COUNT(*) FROM workout_logs"))
        assertEquals(1, migrated.longForQuery("SELECT COUNT(*) FROM workout_logs WHERE sessionId IS NULL"))
        assertEquals(1, migrated.longForQuery("SELECT COUNT(*) FROM calorie_logs"))
        migrated.execSQL(
            "INSERT INTO workout_muscle_loads " +
                "(sessionId, muscleGroup, primarySetCredits, secondarySetCredits) " +
                "VALUES ('session-one', 'CHEST', 2.0, 0.0)"
        )
        assertEquals(1, migrated.longForQuery("SELECT COUNT(*) FROM workout_muscle_loads"))
        assertTrue(runCatching {
            migrated.execSQL(
                "INSERT INTO workout_muscle_loads " +
                    "(sessionId, muscleGroup, primarySetCredits, secondarySetCredits) " +
                    "VALUES ('session-one', 'CHEST', 1.0, 0.0)"
            )
        }.isFailure)
        migrated.execSQL("DELETE FROM workout_logs WHERE sessionId = 'session-one'")
        assertEquals(0, migrated.longForQuery("SELECT COUNT(*) FROM workout_muscle_loads"))
        migrated.close()
    }

    private fun SupportSQLiteDatabase.longForQuery(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private companion object {
        const val TEST_DB = "progress-v2-migration"
    }
}
