package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.domain.CalorieCalculation
import com.example.domain.CalorieCalculationInput
import com.example.domain.CalorieEngine
import com.example.domain.CalorieGoal
import com.example.domain.CalorieSex
import com.example.domain.DietaryPreference
import com.example.domain.LifestyleActivityLevel
import com.example.domain.TrainingExperience

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "profile_prefs")

enum class AutomaticCalorieInput {
    AGE,
    GENDER,
    HEIGHT,
    WEIGHT,
    ACTIVITY_LEVEL,
    FITNESS_GOAL
}

data class UserProfile(
    val name: String = "",
    val age: Int = 0,
    val gender: String = "",
    val heightCm: Float = 0f,
    val weightKg: Float = 0f,
    val weightRange: String = "",
    val dietPreference: String = "",
    val workoutDays: Int = 0,
    val goal: String = "",
    val workoutType: String = "",
    val mealsPerDay: Int = 0,
    val workoutExperience: String = "",
    val hasAllergies: Boolean = false,
    val allergiesSpecified: Boolean = false,
    val allergiesDetails: String = "",
    val activityLevel: String = "",
    val mobileNumber: String = "",
    val isOnboardingCompleted: Boolean = false,
    val theme: String = "System",
    val customCalorieGoal: Int = 0,
    val customCarbGoalGrams: Float = 0f,
    val customProteinGoalGrams: Float = 0f,
    val customFatGoalGrams: Float = 0f,
    val generatedWorkoutPlan: String = ""
) {
    fun getWeightMidpoint(): Float {
        if (weightKg > 0f) return weightKg
        val clean = weightRange.replace("kg", "").trim()
        if (clean.isEmpty()) return weightKg.coerceAtLeast(0f)
        return when {
            clean.contains("-") -> {
                val parts = clean.split("-")
                val low = parts.getOrNull(0)?.trim()?.toFloatOrNull()
                val high = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                if (low != null && high != null) (low + high) / 2f else weightKg.coerceAtLeast(0f)
            }
            clean.contains("+") -> {
                clean.replace("+", "").trim().toFloatOrNull()?.plus(5f)
                    ?: weightKg.coerceAtLeast(0f)
            }
            else -> {
                clean.toFloatOrNull() ?: weightKg
            }
        }
    }

    fun calculateBmr(): Float {
        val sex = CalorieSex.from(gender) ?: return 0f
        return CalorieEngine.estimateBmr(age, sex, heightCm.toDouble(), getWeightMidpoint().toDouble())
            ?.toFloat() ?: 0f
    }

    fun calculateTdee(): Float {
        return calorieCalculation()?.maintenanceCalories?.toFloat() ?: 0f
    }

    fun calculateDailyCalories(): Int {
        if (customCalorieGoal > 0) return customCalorieGoal
        return calorieCalculation()?.roundedDisplayCalories ?: 0
    }

    fun calculateProteinGrams(): Float {
        if (customProteinGoalGrams > 0f) return customProteinGoalGrams
        val calculation = calorieCalculation() ?: return 0f
        return CalorieEngine.macroTargets(calculation, getWeightMidpoint().toDouble()).proteinGrams.toFloat()
    }

    fun calculateFatGrams(): Float {
        if (customFatGoalGrams > 0f) return customFatGoalGrams
        val calculation = calorieCalculation() ?: return 0f
        return CalorieEngine.macroTargets(calculation, getWeightMidpoint().toDouble()).fatGrams.toFloat()
    }

    fun calculateCarbGrams(): Float {
        if (customCarbGoalGrams > 0f) return customCarbGoalGrams
        val calculation = calorieCalculation() ?: return 0f
        return CalorieEngine.macroTargets(calculation, getWeightMidpoint().toDouble()).carbohydrateGrams.toFloat()
    }

    fun getBmi(): Float {
        val weight = getWeightMidpoint()
        val heightM = heightCm / 100f
        return if (heightM > 0 && weight > 0) weight / (heightM * heightM) else 0f
    }

    fun getBmiStatus(): String {
        val bmi = getBmi()
        return when {
            bmi <= 0f -> "Not set"
            bmi < 18.5f -> "Underweight"
            bmi < 25f -> "Healthy"
            bmi < 30f -> "Overweight"
            else -> "Obese"
        }
    }

    fun hasBmiInputs(): Boolean = heightCm > 0f && getWeightMidpoint() > 0f

    fun canCalculateBmr(): Boolean =
        calculateBmr() > 0f

    fun canCalculateDailyCalories(): Boolean =
        customCalorieGoal > 0 || calorieCalculation() != null

    fun automaticCalorieMissingInputs(): List<AutomaticCalorieInput> = buildList {
        if (!CalorieEngine.isSupportedAge(age)) add(AutomaticCalorieInput.AGE)
        if (CalorieSex.from(gender) == null) add(AutomaticCalorieInput.GENDER)
        if (!CalorieEngine.isSupportedHeight(heightCm.toDouble())) add(AutomaticCalorieInput.HEIGHT)
        if (!CalorieEngine.isSupportedWeight(getWeightMidpoint().toDouble())) add(AutomaticCalorieInput.WEIGHT)
        if (LifestyleActivityLevel.from(activityLevel) == null) add(AutomaticCalorieInput.ACTIVITY_LEVEL)
        if (CalorieGoal.from(goal) == null) add(AutomaticCalorieInput.FITNESS_GOAL)
    }

    fun hasCompletePersonalDetails(): Boolean =
        name.isNotBlank() && age > 0 && gender.isNotBlank() && heightCm > 0f && weightKg > 0f

    fun normalizedDietPreference(): DietaryPreference = DietaryPreference.from(dietPreference)

    fun calorieCalculation(): CalorieCalculation? {
        val sex = CalorieSex.from(gender) ?: return null
        val activity = LifestyleActivityLevel.from(activityLevel) ?: return null
        val calorieGoal = CalorieGoal.from(goal) ?: return null
        return CalorieEngine.calculate(
            CalorieCalculationInput(
                ageYears = age,
                sex = sex,
                heightCm = heightCm.toDouble(),
                weightKg = getWeightMidpoint().toDouble(),
                activityLevel = activity,
                goal = calorieGoal,
                experience = TrainingExperience.from(workoutExperience),
                manualTargetCalories = customCalorieGoal.takeIf { it > 0 }
            )
        )
    }
}

class ProfileRepository(private val context: Context) {
    companion object {
        private val KEY_NAME = stringPreferencesKey("name")
        private val KEY_AGE = intPreferencesKey("age")
        private val KEY_GENDER = stringPreferencesKey("gender")
        private val KEY_HEIGHT = floatPreferencesKey("height_cm")
        private val KEY_WEIGHT = floatPreferencesKey("weight_kg")
        private val KEY_WEIGHT_RANGE = stringPreferencesKey("weight_range")
        private val KEY_DIET = stringPreferencesKey("diet_preference")
        private val KEY_WORKOUT_DAYS = intPreferencesKey("workout_days")
        private val KEY_GOAL = stringPreferencesKey("goal")
        private val KEY_WORKOUT_TYPE = stringPreferencesKey("workout_type")
        private val KEY_MEALS = intPreferencesKey("meals_per_day")
        private val KEY_EXPERIENCE = stringPreferencesKey("workout_experience")
        private val KEY_HAS_ALLERGIES = booleanPreferencesKey("has_allergies")
        private val KEY_ALLERGIES_DETAILS = stringPreferencesKey("allergies_details")
        private val KEY_ACTIVITY_LEVEL = stringPreferencesKey("activity_level")
        private val KEY_MOBILE = stringPreferencesKey("mobile_number")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_CUSTOM_CALORIE_GOAL = intPreferencesKey("custom_calorie_goal")
        private val KEY_CUSTOM_CARB_GOAL = floatPreferencesKey("custom_carb_goal_grams")
        private val KEY_CUSTOM_PROTEIN_GOAL = floatPreferencesKey("custom_protein_goal_grams")
        private val KEY_CUSTOM_FAT_GOAL = floatPreferencesKey("custom_fat_goal_grams")
        private val KEY_GENERATED_WORKOUT_PLAN = stringPreferencesKey("generated_workout_plan")
        private val KEY_WATER_GLASSES = intPreferencesKey("water_glasses")
        private val KEY_WATER_LAST_DATE = stringPreferencesKey("water_last_date")
        private val KEY_FAVORITE_EXERCISES = stringSetPreferencesKey("favorite_exercises")
        private val KEY_TRAINING_NOTES = stringSetPreferencesKey("training_notes")
    }

    val waterIntakeFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        val lastDate = prefs[KEY_WATER_LAST_DATE] ?: ""
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        if (lastDate == todayStr) {
            prefs[KEY_WATER_GLASSES] ?: 0
        } else {
            0
        }
    }

    suspend fun saveWaterIntake(glasses: Int) {
        context.dataStore.edit { prefs ->
            val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
            prefs[KEY_WATER_GLASSES] = glasses
            prefs[KEY_WATER_LAST_DATE] = todayStr
        }
    }

    val favoriteExercisesFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAVORITE_EXERCISES].orEmpty().sorted()
    }

    val trainingNotesFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRAINING_NOTES].orEmpty().sorted()
    }

    suspend fun saveFavoriteExercises(exercises: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAVORITE_EXERCISES] = exercises.filter { it.isNotBlank() }.toSet()
        }
    }

    suspend fun saveTrainingNotes(notes: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TRAINING_NOTES] = notes.filter { it.isNotBlank() }.toSet()
        }
    }

    val userProfileFlow: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        UserProfile(
            name = prefs[KEY_NAME] ?: "",
            age = prefs[KEY_AGE] ?: 0,
            gender = prefs[KEY_GENDER] ?: "",
            heightCm = prefs[KEY_HEIGHT] ?: 0f,
            weightKg = prefs[KEY_WEIGHT] ?: 0f,
            weightRange = prefs[KEY_WEIGHT_RANGE] ?: "",
            dietPreference = prefs[KEY_DIET] ?: "",
            workoutDays = prefs[KEY_WORKOUT_DAYS] ?: 0,
            goal = prefs[KEY_GOAL] ?: "",
            workoutType = prefs[KEY_WORKOUT_TYPE] ?: "",
            mealsPerDay = prefs[KEY_MEALS] ?: 0,
            workoutExperience = prefs[KEY_EXPERIENCE] ?: "",
            hasAllergies = prefs[KEY_HAS_ALLERGIES] ?: false,
            allergiesSpecified = prefs.contains(KEY_HAS_ALLERGIES),
            allergiesDetails = prefs[KEY_ALLERGIES_DETAILS] ?: "",
            activityLevel = prefs[KEY_ACTIVITY_LEVEL] ?: "",
            mobileNumber = prefs[KEY_MOBILE] ?: "",
            isOnboardingCompleted = prefs[KEY_ONBOARDING_COMPLETE] ?: false,
            theme = prefs[KEY_THEME] ?: "System",
            customCalorieGoal = prefs[KEY_CUSTOM_CALORIE_GOAL] ?: 0,
            customCarbGoalGrams = prefs[KEY_CUSTOM_CARB_GOAL] ?: 0f,
            customProteinGoalGrams = prefs[KEY_CUSTOM_PROTEIN_GOAL] ?: 0f,
            customFatGoalGrams = prefs[KEY_CUSTOM_FAT_GOAL] ?: 0f,
            generatedWorkoutPlan = prefs[KEY_GENERATED_WORKOUT_PLAN] ?: ""
        )
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NAME] = profile.name
            prefs[KEY_AGE] = profile.age
            prefs[KEY_GENDER] = profile.gender
            prefs[KEY_HEIGHT] = profile.heightCm
            prefs[KEY_WEIGHT] = profile.weightKg
            prefs[KEY_WEIGHT_RANGE] = profile.weightRange
            prefs[KEY_DIET] = profile.dietPreference
            prefs[KEY_WORKOUT_DAYS] = profile.workoutDays
            prefs[KEY_GOAL] = profile.goal
            prefs[KEY_WORKOUT_TYPE] = profile.workoutType
            prefs[KEY_MEALS] = profile.mealsPerDay
            prefs[KEY_EXPERIENCE] = profile.workoutExperience
            if (profile.allergiesSpecified) {
                prefs[KEY_HAS_ALLERGIES] = profile.hasAllergies
                prefs[KEY_ALLERGIES_DETAILS] = profile.allergiesDetails
            } else {
                prefs.remove(KEY_HAS_ALLERGIES)
                prefs.remove(KEY_ALLERGIES_DETAILS)
            }
            prefs[KEY_ACTIVITY_LEVEL] = profile.activityLevel
            prefs[KEY_MOBILE] = profile.mobileNumber
            prefs[KEY_ONBOARDING_COMPLETE] = profile.isOnboardingCompleted
            prefs[KEY_THEME] = profile.theme
            prefs[KEY_CUSTOM_CALORIE_GOAL] = profile.customCalorieGoal
            prefs[KEY_CUSTOM_CARB_GOAL] = profile.customCarbGoalGrams
            prefs[KEY_CUSTOM_PROTEIN_GOAL] = profile.customProteinGoalGrams
            prefs[KEY_CUSTOM_FAT_GOAL] = profile.customFatGoalGrams
            prefs[KEY_GENERATED_WORKOUT_PLAN] = profile.generatedWorkoutPlan
        }
    }

    suspend fun saveGeneratedWorkoutPlan(planJson: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GENERATED_WORKOUT_PLAN] = planJson
        }
    }

    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme
        }
    }

    suspend fun clearProfile() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
