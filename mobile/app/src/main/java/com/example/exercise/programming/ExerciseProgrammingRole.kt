package com.example.exercise.programming

import kotlinx.serialization.Serializable

@Serializable
enum class ExerciseProgrammingRole {
    GENERAL_WARMUP,
    DYNAMIC_PREPARATION,
    MOBILITY,
    ACTIVATION,
    MAIN_STRENGTH,
    CONDITIONING,
    STATIC_COOLDOWN,
    NEVER_AUTO_SELECT
}

@Serializable
enum class ExercisePrescriptionMode {
    REPETITIONS,
    DURATION_SECONDS,
    FREE_TEXT
}

@Serializable
enum class ExerciseProgrammingRegion {
    CHEST,
    SHOULDERS,
    TRICEPS,
    BACK,
    LATS,
    BICEPS,
    FOREARMS,
    CORE,
    LOWER_BACK,
    HIPS,
    GLUTES,
    QUADRICEPS,
    HAMSTRINGS,
    CALVES,
    ANKLES,
    FULL_BODY
}

@Serializable
enum class ExerciseProgrammingJoint {
    SHOULDER,
    ELBOW,
    WRIST,
    SPINE,
    HIP,
    KNEE,
    ANKLE
}

@Serializable
enum class ExerciseProgrammingReviewStatus {
    FITDESI_REVIEWED
}
