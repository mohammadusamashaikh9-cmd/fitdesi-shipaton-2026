package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "calorie_logs", indices = [Index(value = ["timestamp"])])
data class CalorieLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val amount: Int,
    val mealType: String, // Breakfast, Lunch, Dinner, Snack
    val description: String,
    val proteinGrams: Float = 0f,
    val carbsGrams: Float = 0f,
    val fatGrams: Float = 0f,
    val servings: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)
