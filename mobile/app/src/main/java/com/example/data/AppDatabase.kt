package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.security.BuildWeekRuntimeConfig

@Database(
    entities = [CalorieLog::class, WorkoutLog::class, ExerciseEntity::class, WorkoutMuscleLoadEntity::class],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trainerDao(): PersonalTrainerDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_logs ADD COLUMN sessionId TEXT")
                db.execSQL("ALTER TABLE workout_logs ADD COLUMN completedSets INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workout_logs ADD COLUMN liftingVolumeKg REAL NOT NULL DEFAULT 0.0")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_workout_logs_sessionId " +
                        "ON workout_logs(sessionId)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_logs ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE workout_logs SET durationSeconds = durationMinutes * 60")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS workout_muscle_loads (" +
                        "sessionId TEXT NOT NULL, " +
                        "muscleGroup TEXT NOT NULL, " +
                        "primarySetCredits REAL NOT NULL DEFAULT 0.0, " +
                        "secondarySetCredits REAL NOT NULL DEFAULT 0.0, " +
                        "PRIMARY KEY(sessionId, muscleGroup), " +
                        "FOREIGN KEY(sessionId) REFERENCES workout_logs(sessionId) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workout_muscle_loads_sessionId " +
                        "ON workout_muscle_loads(sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workout_logs_timestamp ON workout_logs(timestamp)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_calorie_logs_timestamp ON calorie_logs(timestamp)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trainer_database"
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // TODO: Replace this opt-in compatibility escape hatch with
                // explicit, tested migrations for every released schema.
                if (BuildWeekRuntimeConfig.destructiveMigrationAllowed) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
