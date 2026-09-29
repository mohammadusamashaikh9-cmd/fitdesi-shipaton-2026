package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.hasValidStructuredContracts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class SavedPlanResult {
    SAVED,
    ALREADY_SAVED,
    INVALID_PLAN,
    ERROR
}

interface SavedPlanStorage {
    suspend fun readWorkoutPlans(): String?
    suspend fun writeWorkoutPlans(value: String)
    suspend fun readDietPlans(): String?
    suspend fun writeDietPlans(value: String)
}

class DataStoreSavedPlanStorage(private val context: Context) : SavedPlanStorage {
    override suspend fun readWorkoutPlans(): String? =
        context.dataStore.data.first()[KEY_SAVED_WORKOUT_PLANS]

    override suspend fun writeWorkoutPlans(value: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SAVED_WORKOUT_PLANS] = value
        }
    }

    override suspend fun readDietPlans(): String? =
        context.dataStore.data.first()[KEY_SAVED_DIET_PLANS]

    override suspend fun writeDietPlans(value: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SAVED_DIET_PLANS] = value
        }
    }

    private companion object {
        val KEY_SAVED_WORKOUT_PLANS = stringPreferencesKey("saved_generated_workout_plans_v1")
        val KEY_SAVED_DIET_PLANS = stringPreferencesKey("saved_generated_diet_plans_v1")
    }
}

class SavedPlanRepository(
    private val storage: SavedPlanStorage,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    constructor(context: Context) : this(DataStoreSavedPlanStorage(context.applicationContext))

    private val mutex = Mutex()
    private val workoutSerializer = ListSerializer(GeneratedWorkoutPlan.serializer())
    private val dietSerializer = ListSerializer(GeneratedDietPlan.serializer())

    suspend fun saveWorkoutPlan(plan: GeneratedWorkoutPlan): SavedPlanResult = mutex.withLock {
        if (!plan.isValid()) return@withLock SavedPlanResult.INVALID_PLAN
        val plans = decodeWorkoutPlans(storage.readWorkoutPlans())
            ?: return@withLock SavedPlanResult.ERROR
        if (plans.any { it.planId == plan.planId }) {
            return@withLock SavedPlanResult.ALREADY_SAVED
        }
        runCatching {
            storage.writeWorkoutPlans(
                json.encodeToString(workoutSerializer, (plans + plan).sortedByDescending { it.createdAt })
            )
        }.fold(
            onSuccess = { SavedPlanResult.SAVED },
            onFailure = { SavedPlanResult.ERROR }
        )
    }

    suspend fun listWorkoutPlans(): List<GeneratedWorkoutPlan> =
        decodeWorkoutPlans(storage.readWorkoutPlans()).orEmpty()

    suspend fun getWorkoutPlan(planId: String): GeneratedWorkoutPlan? =
        listWorkoutPlans().firstOrNull { it.planId == planId }

    suspend fun deleteWorkoutPlan(planId: String): Boolean = mutex.withLock {
        val plans = decodeWorkoutPlans(storage.readWorkoutPlans()) ?: return@withLock false
        val remaining = plans.filterNot { it.planId == planId }
        if (remaining.size == plans.size) return@withLock false
        runCatching {
            storage.writeWorkoutPlans(json.encodeToString(workoutSerializer, remaining))
        }.isSuccess
    }

    suspend fun saveDietPlan(plan: GeneratedDietPlan): SavedPlanResult = mutex.withLock {
        if (!plan.isSavable()) return@withLock SavedPlanResult.INVALID_PLAN
        val plans = decodeDietPlans(storage.readDietPlans())
            ?: return@withLock SavedPlanResult.ERROR
        if (plans.any { it.planId == plan.planId }) {
            return@withLock SavedPlanResult.ALREADY_SAVED
        }
        runCatching {
            storage.writeDietPlans(
                json.encodeToString(dietSerializer, (plans + plan).sortedByDescending { it.createdAt })
            )
            val persistedPlans = decodeDietPlans(storage.readDietPlans())
                ?: error("Saved diet plans could not be validated")
            check(persistedPlans.any { it.planId == plan.planId })
        }.fold(
            onSuccess = { SavedPlanResult.SAVED },
            onFailure = { SavedPlanResult.ERROR }
        )
    }

    suspend fun loadDietPlans(): Result<List<GeneratedDietPlan>> = runCatching {
        decodeDietPlans(storage.readDietPlans())
            ?: error("Saved diet plans are malformed")
    }

    suspend fun listDietPlans(): List<GeneratedDietPlan> =
        loadDietPlans().getOrElse { emptyList() }

    suspend fun getDietPlan(planId: String): GeneratedDietPlan? =
        listDietPlans().firstOrNull { it.planId == planId }

    suspend fun deleteDietPlan(planId: String): Boolean = mutex.withLock {
        val plans = decodeDietPlans(storage.readDietPlans()) ?: return@withLock false
        val remaining = plans.filterNot { it.planId == planId }
        if (remaining.size == plans.size) return@withLock false
        runCatching {
            storage.writeDietPlans(json.encodeToString(dietSerializer, remaining))
        }.isSuccess
    }

    private fun decodeWorkoutPlans(value: String?): List<GeneratedWorkoutPlan>? =
        decodeList(value, workoutSerializer)
            ?.takeIf { plans -> plans.all { it.hasValidStructuredContracts() } }

    private fun decodeDietPlans(value: String?): List<GeneratedDietPlan>? =
        decodeList(value, dietSerializer)

    private fun <T> decodeList(
        value: String?,
        serializer: kotlinx.serialization.KSerializer<List<T>>
    ): List<T>? {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, value) }.getOrNull()
    }
}
