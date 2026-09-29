package com.example.data

import kotlinx.serialization.Serializable

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val bodyPart: String? = null,      // Chest, Back, Legs, etc.
    val equipment: String? = null,      // Barbell, Dumbbell, Bodyweight
    val targetMuscle: String? = null,   // Target muscle group
    val gifUrl: String? = null,         // Thumbnail/Animation URL
    val instructions: List<String>? = null,
    val isOffline: Boolean = false      // True if from local JSON, false if from API
)
