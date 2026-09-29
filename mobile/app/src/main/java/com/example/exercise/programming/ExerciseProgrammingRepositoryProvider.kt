package com.example.exercise.programming

import android.content.Context
import com.example.exercise.ExerciseRepositoryProvider

object ExerciseProgrammingRepositoryProvider {
    @Volatile
    private var instance: ExerciseProgrammingRepository? = null

    fun getRepository(context: Context): ExerciseProgrammingRepository =
        instance ?: synchronized(this) {
            instance ?: ExerciseProgrammingRepository(
                source = AndroidExerciseProgrammingAssetSource(context.applicationContext),
                canonicalCatalogue = ExerciseRepositoryProvider.getRepository(context.applicationContext)
            ).also { instance = it }
        }
}
