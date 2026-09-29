package com.example.fitdesi.data.repository

import android.content.Context
import com.example.fitdesi.data.Exercise
import com.example.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class OfflineExerciseRepository(private val context: Context) {

    private var cachedExercises: List<Exercise>? = null

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun getExercises(): List<Exercise> = withContext(Dispatchers.IO) {
        cachedExercises?.let { return@withContext it }
        try {
            val jsonString = context.assets.open("exercises/exercises.json").bufferedReader().use { it.readText() }
            val exercises = json.decodeFromString<List<Exercise>>(jsonString)
            cachedExercises = exercises
            exercises
        } catch (e: Exception) {
            SafeLog.error("OfflineExerciseRepo", "Bundled exercise catalogue load failed", e)
            emptyList()
        }
    }

    suspend fun getAllExercises(): List<Exercise> {
        return getExercises()
    }

    suspend fun getExerciseById(id: String): Exercise? {
        return getExercises().find { it.id == id }
    }

    suspend fun getExercisesByBodyPart(bodyPart: String): List<Exercise> {
        return getExercises().filter { it.bodyPart?.equals(bodyPart, ignoreCase = true) == true }
    }

    suspend fun getExercisesByEquipment(equipment: String): List<Exercise> {
        return getExercises().filter { it.equipment?.equals(equipment, ignoreCase = true) == true }
    }

    suspend fun getExercisesByTarget(target: String): List<Exercise> {
        return getExercises().filter { it.target?.equals(target, ignoreCase = true) == true }
    }

    suspend fun searchExercises(query: String): List<Exercise> {
        if (query.isBlank()) return getExercises()
        val lowerQuery = query.trim().lowercase()
        return getExercises().filter {
            it.name.lowercase().contains(lowerQuery) ||
                    it.bodyPart?.lowercase()?.contains(lowerQuery) == true ||
                    it.target?.lowercase()?.contains(lowerQuery) == true ||
                    it.muscleGroup?.lowercase()?.contains(lowerQuery) == true
        }
    }

}

object OfflineExerciseRepositoryProvider {
    fun getRepository(context: Context): OfflineExerciseRepository {
        return OfflineExerciseRepository(context)
    }
}
