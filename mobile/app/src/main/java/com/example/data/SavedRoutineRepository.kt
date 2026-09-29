package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.subscription.SubscriptionPolicy
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Origins remain separate so the routine library never misrepresents a locally built plan. */
enum class SavedRoutineOrigin {
    BUILD_ROUTINE,
    AI_WORKOUT_GENERATOR,
    CUSTOM_WORKOUT,
    LEGACY_ACTIVE
}

internal fun SavedRoutineOrigin.isBasicAuthoredRoutineOrigin(): Boolean = when (this) {
    SavedRoutineOrigin.BUILD_ROUTINE,
    SavedRoutineOrigin.CUSTOM_WORKOUT -> true
    SavedRoutineOrigin.AI_WORKOUT_GENERATOR,
    SavedRoutineOrigin.LEGACY_ACTIVE -> false
}

data class SavedRoutine(
    val routineId: String,
    val routine: GeneratedRoutine,
    val origin: SavedRoutineOrigin,
    val createdAt: Long
)

enum class SavedRoutineResult {
    SAVED,
    ALREADY_SAVED,
    LIMIT_REACHED,
    INVALID_ROUTINE,
    ERROR
}

interface SavedRoutineStorage {
    suspend fun readRoutineLibrary(): String?
    suspend fun writeRoutineLibrary(value: String)
    suspend fun readLegacyActiveRoutine(): String?
    suspend fun isLegacyMigrationComplete(): Boolean
    suspend fun markLegacyMigrationComplete()
}

class DataStoreSavedRoutineStorage(private val context: Context) : SavedRoutineStorage {
    override suspend fun readRoutineLibrary(): String? =
        context.dataStore.data.first()[KEY_SAVED_ROUTINE_LIBRARY]

    override suspend fun writeRoutineLibrary(value: String) {
        context.dataStore.edit { preferences -> preferences[KEY_SAVED_ROUTINE_LIBRARY] = value }
    }

    override suspend fun readLegacyActiveRoutine(): String? =
        context.dataStore.data.first()[KEY_LEGACY_ACTIVE_ROUTINE]

    override suspend fun isLegacyMigrationComplete(): Boolean =
        context.dataStore.data.first()[KEY_LEGACY_MIGRATION_COMPLETE] ?: false

    override suspend fun markLegacyMigrationComplete() {
        context.dataStore.edit { preferences -> preferences[KEY_LEGACY_MIGRATION_COMPLETE] = true }
    }

    private companion object {
        val KEY_SAVED_ROUTINE_LIBRARY = stringPreferencesKey("saved_routine_library_v1")
        // This intentionally reads the existing active-plan preference without taking ownership of it.
        val KEY_LEGACY_ACTIVE_ROUTINE = stringPreferencesKey("generated_workout_plan")
        val KEY_LEGACY_MIGRATION_COMPLETE = booleanPreferencesKey("saved_routine_library_v1_migrated")
    }
}

/**
 * Persistent routine library for local builders and the offline workout generator.
 * The legacy profile value remains the single active-plan slot for compatibility.
 */
class SavedRoutineRepository(
    private val storage: SavedRoutineStorage,
    private val gson: Gson = Gson(),
    private val hasUnlimitedAuthoredRoutineCapability: () -> Boolean = { false }
) {
    constructor(
        context: Context,
        hasUnlimitedAuthoredRoutineCapability: () -> Boolean = { false }
    ) : this(
        DataStoreSavedRoutineStorage(context.applicationContext),
        Gson(),
        hasUnlimitedAuthoredRoutineCapability
    )

    private val mutex = Mutex()
    private val collectionType = object : TypeToken<List<SavedRoutine>>() {}.type

    suspend fun saveRoutine(
        routine: GeneratedRoutine,
        origin: SavedRoutineOrigin
    ): SavedRoutineResult = mutex.withLock {
        if (ensureLegacyMigrationLocked().isFailure) return@withLock SavedRoutineResult.ERROR
        val normalized = normalize(routine)
        if (!normalized.routine.isPersistable()) return@withLock SavedRoutineResult.INVALID_ROUTINE
        val routines = decode(storage.readRoutineLibrary()) ?: return@withLock SavedRoutineResult.ERROR
        if (routines.any { it.routineId == normalized.routineId }) {
            return@withLock SavedRoutineResult.ALREADY_SAVED
        }
        if (origin.isBasicAuthoredRoutineOrigin()) {
            val authoredRoutineCount = routines.count { it.origin.isBasicAuthoredRoutineOrigin() }
            if (authoredRoutineCount >= SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT) {
                val hasUnlimitedRoutines = runCatching(hasUnlimitedAuthoredRoutineCapability)
                    .getOrDefault(false)
                if (!hasUnlimitedRoutines) return@withLock SavedRoutineResult.LIMIT_REACHED
            }
        }
        val updated = (routines + normalized.copy(origin = origin))
            .sortedByDescending(SavedRoutine::createdAt)
        runCatching {
            storage.writeRoutineLibrary(gson.toJson(updated))
        }.fold(
            onSuccess = { SavedRoutineResult.SAVED },
            onFailure = { SavedRoutineResult.ERROR }
        )
    }

    suspend fun loadRoutines(): Result<List<SavedRoutine>> = mutex.withLock {
        ensureLegacyMigrationLocked().mapCatching {
            decode(storage.readRoutineLibrary()) ?: error("Saved routine library is malformed")
        }.map { it.sortedByDescending(SavedRoutine::createdAt) }
    }

    suspend fun getRoutine(routineId: String): SavedRoutine? =
        loadRoutines().getOrNull()?.firstOrNull { it.routineId == routineId }

    suspend fun deleteRoutine(routineId: String): Boolean = mutex.withLock {
        if (ensureLegacyMigrationLocked().isFailure) return@withLock false
        val routines = decode(storage.readRoutineLibrary()) ?: return@withLock false
        val remaining = routines.filterNot { it.routineId == routineId }
        if (remaining.size == routines.size) return@withLock false
        runCatching { storage.writeRoutineLibrary(gson.toJson(remaining)) }.isSuccess
    }

    fun stableRoutineId(routine: GeneratedRoutine): String =
        routine.planId.takeIf(String::isNotBlank) ?: "routine-" + sha256(
            stableRoutineCanonicalJson(routine)
        ).take(24)

    private suspend fun ensureLegacyMigrationLocked(): Result<Unit> {
        if (storage.isLegacyMigrationComplete()) return Result.success(Unit)
        val existing = decode(storage.readRoutineLibrary())
            ?: return Result.failure(IllegalStateException("Saved routine library is malformed"))
        val legacyJson = storage.readLegacyActiveRoutine()
        val migrated = if (legacyJson.isNullOrBlank()) {
            existing
        } else {
            val legacyRoutine = runCatching {
                gson.fromJson(legacyJson, GeneratedRoutine::class.java)
            }.getOrNull()
                ?: return Result.failure(IllegalStateException("Legacy active routine could not be read"))
            val normalized = runCatching { normalize(legacyRoutine) }.getOrElse {
                return Result.failure(IllegalStateException("Legacy active routine is malformed"))
            }
            if (!normalized.routine.isPersistable() || existing.any { it.routineId == normalized.routineId }) {
                existing
            } else {
                existing + normalized.copy(origin = SavedRoutineOrigin.LEGACY_ACTIVE)
            }
        }
        return runCatching {
            storage.writeRoutineLibrary(gson.toJson(migrated.sortedByDescending(SavedRoutine::createdAt)))
            // Mark only after the legacy plan has been safely written or confirmed duplicate.
            storage.markLegacyMigrationComplete()
        }
    }

    private fun normalize(routine: GeneratedRoutine): SavedRoutine {
        val routineId = stableRoutineId(routine)
        val createdAt = routine.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        return SavedRoutine(
            routineId = routineId,
            routine = routine.copy(planId = routineId, createdAt = createdAt),
            origin = SavedRoutineOrigin.LEGACY_ACTIVE,
            createdAt = createdAt
        )
    }

    private fun decode(value: String?): List<SavedRoutine>? {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val routines = gson.fromJson<List<SavedRoutine>>(value, collectionType) ?: return@runCatching null
            routines.takeIf { decoded -> decoded.all { it.routine.hasSafeStructuredContracts() } }
        }.getOrNull()
    }

    private fun GeneratedRoutine.isPersistable(): Boolean =
        name.isNotBlank() &&
            days.isNotEmpty() &&
            days.all { day ->
                day.dayName.isNotBlank() &&
                    day.title.isNotBlank() &&
                    day.exercises.isNotEmpty() &&
                    day.exercises.all { exercise ->
                        exercise.name.isNotBlank() && exercise.sets > 0 && exercise.reps.isNotBlank() &&
                            exercise.hasValidStructuredContracts()
                    } &&
                    day.hasValidStructuredContracts()
            }

    private fun GeneratedRoutine.hasSafeStructuredContracts(): Boolean = runCatching {
        days.all { it.hasValidStructuredContracts() }
    }.getOrDefault(false)

    private fun GeneratedDay.hasValidStructuredContracts(): Boolean =
        generalWarmup?.isStructurallyValid() != false &&
            exercises.all { it.hasValidStructuredContracts() } &&
            warmupExercises.all { it.isValidStructuredExercise() } &&
            cooldownExercises.all { it.isValidStructuredExercise() }

    private fun GeneratedExercise.isValidStructuredExercise(): Boolean =
        name.isNotBlank() && sets > 0 && reps.isNotBlank() && hasValidStructuredContracts()

    private fun GeneratedExercise.hasValidStructuredContracts(): Boolean =
        rampUpSets.all { it.isStructurallyValid() } &&
            activityPrescription?.isStructurallyValid() != false

    private fun stableRoutineCanonicalJson(routine: GeneratedRoutine): String {
        val normalized = routine.copy(planId = "", createdAt = 0L)
        val tree = gson.toJsonTree(normalized).asJsonObject
        if (normalized.hasStructuredContent()) return gson.toJson(tree)

        tree["days"].asJsonArray.forEach { dayElement ->
            val day = dayElement.asJsonObject
            day.remove("generalWarmup")
            day.remove("warmupExercises")
            day.remove("cooldownExercises")
            day["exercises"].asJsonArray.forEach { exerciseElement ->
                exerciseElement.asJsonObject.removeStructuredExerciseFields()
            }
        }
        return gson.toJson(tree)
    }

    private fun GeneratedRoutine.hasStructuredContent(): Boolean = days.any { day ->
        day.generalWarmup != null || day.warmupExercises.isNotEmpty() || day.cooldownExercises.isNotEmpty() ||
            day.exercises.any { exercise ->
                exercise.rampUpSets.isNotEmpty() || exercise.activityPrescription != null ||
                    exercise.safetyNote.isNotEmpty()
            }
    }

    private fun JsonObject.removeStructuredExerciseFields() {
        remove("rampUpSets")
        remove("activityPrescription")
        remove("safetyNote")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
