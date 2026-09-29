package com.example.data.api

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Types
import com.example.R
import com.example.security.SafeLog

@JsonClass(generateAdapter = true)
data class FallbackContainer(
    val exercises: List<ExerciseDBExercise>
)

object LocalExerciseProvider {
    private fun createExercise(
        id: Int,
        name: String,
        category: String,
        muscles: List<String>,
        equipment: String,
        difficulty: String,
        instructions: List<String>,
        videoUrl: String = "",
        thumbnail: String = ""
    ): ExerciseDBExercise {
        return ExerciseDBExercise(
            id = id.toString(),
            name = name,
            category = category,
            bodyPart = category,
            muscles = muscles,
            muscleGroups = muscles,
            target = muscles.firstOrNull(),
            equipment = equipment,
            difficulty = difficulty,
            instructions = instructions,
            steps = instructions,
            videoUrl = videoUrl,
            video = videoUrl,
            gifUrl = thumbnail,
            thumbnail = thumbnail,
            image = thumbnail
        )
    }

    // Static fallback list in case JSON read fails
    val exercises: List<ExerciseDBExercise> by lazy {
        listOf(
            createExercise(1, "Barbell Bench Press", "Chest", listOf("Chest", "Triceps", "Shoulders"), "Barbell", "Intermediate", listOf("Lie flat on a bench.", "Grip the barbell slightly wider than shoulder width.", "Lower the bar to your chest.", "Push back up until arms are extended.")),
            createExercise(2, "Dumbbell Bench Press", "Chest", listOf("Chest", "Triceps", "Shoulders"), "Dumbbells", "Intermediate", listOf("Lie on a flat bench with a dumbbell in each hand.", "Lower dumbbells to sides of chest.", "Press dumbbells up until arms are extended.")),
            createExercise(3, "Incline Dumbbell Press", "Chest", listOf("Upper Chest", "Front Deltoids", "Triceps"), "Dumbbells", "Intermediate", listOf("Lie on an incline bench at 30-45 degrees.", "Hold dumbbells at chest height.", "Press weights straight up overhead.", "Lower with control.")),
            createExercise(6, "Chest Flyes", "Chest", listOf("Chest", "Shoulders"), "Dumbbells", "Beginner", listOf("Lie flat on a bench holding dumbbells above chest.", "Lower dumbbells in a wide arc to sides.", "Squeeze chest to return to top.")),
            createExercise(8, "Standard Push-Up", "Chest", listOf("Chest", "Triceps", "Shoulders", "Core"), "Bodyweight", "Beginner", listOf("Get into high plank position.", "Lower chest to the floor.", "Push yourself back up.")),
            createExercise(11, "Pull-Up", "Back", listOf("Lats", "Upper Back", "Biceps"), "Pull-up Bar", "Advanced", listOf("Hang from bar with hands wider than shoulders.", "Pull chest up to the bar.", "Lower down slowly.")),
            createExercise(14, "Barbell Row", "Back", listOf("Upper Back", "Lats", "Biceps"), "Barbell", "Intermediate", listOf("Bend at hips with flat back.", "Pull bar to lower chest.", "Lower under control.")),
            createExercise(15, "Lat Pulldown", "Back", listOf("Lats", "Upper Back", "Biceps"), "Cable Machine", "Beginner", listOf("Sit at pulldown station.", "Pull bar down to upper chest.", "Return slowly under control.")),
            createExercise(19, "Barbell Deadlift", "Back", listOf("Lower Back", "Hamstrings", "Glutes", "Traps"), "Barbell", "Advanced", listOf("Stand with mid-foot under bar.", "Hinge down, grab bar, and pull chest up.", "Stand up by extending hips and knees.")),
            createExercise(21, "Overhead Press", "Shoulders", listOf("Shoulders", "Triceps"), "Barbell", "Intermediate", listOf("Hold bar at shoulder level.", "Press bar straight overhead.", "Lower with control.")),
            createExercise(22, "Dumbbell Shoulder Press", "Shoulders", listOf("Shoulders", "Triceps"), "Dumbbells", "Intermediate", listOf("Sit on a bench with dumbbells at ears.", "Press weights straight up overhead.", "Lower with control.")),
            createExercise(23, "Lateral Raise", "Shoulders", listOf("Side Deltoids", "Traps"), "Dumbbells", "Beginner", listOf("Stand holding dumbbells at sides.", "Raise weights outward to shoulder level keeping arms straight.", "Lower under control.")),
            createExercise(24, "Front Raise", "Shoulders", listOf("Front Deltoids"), "Dumbbells", "Beginner", listOf("Stand holding dumbbells in front of thighs.", "Raise weights forward to shoulder height.", "Lower slowly.")),
            createExercise(31, "Barbell Squat", "Legs", listOf("Quads", "Glutes", "Hamstrings", "Core"), "Barbell", "Intermediate", listOf("Rest bar on upper back.", "Squat down by hinging hips and knees.", "Drive back up to stand.")),
            createExercise(32, "Leg Press", "Legs", listOf("Quads", "Glutes", "Hamstrings"), "Leg Press Machine", "Beginner", listOf("Place feet on platform.", "Lower platform by bending knees.", "Push platform away, do not lock knees.")),
            createExercise(33, "Romanian Deadlift", "Legs", listOf("Hamstrings", "Glutes", "Lower Back"), "Barbell", "Intermediate", listOf("Hold bar in front of thighs.", "Hinge at hips, sliding bar down legs.", "Stand up by squeezing glutes.")),
            createExercise(34, "Lunges", "Legs", listOf("Quads", "Glutes", "Hamstrings"), "Bodyweight", "Beginner", listOf("Take a big step forward.", "Lower hips until front thigh is parallel to floor and back knee is near floor.", "Push back to starting position.")),
            createExercise(35, "Calf Raises", "Legs", listOf("Calves"), "Bodyweight", "Beginner", listOf("Stand on flat ground or edge of a step.", "Push up through toes to lift heels.", "Lower with control.")),
            createExercise(41, "Barbell Bicep Curl", "Arms", listOf("Biceps", "Forearms"), "Barbell", "Beginner", listOf("Stand holding barbell with underhand grip.", "Curl bar up keeping elbows locked at sides.", "Lower slowly.")),
            createExercise(42, "Dumbbell Bicep Curl", "Arms", listOf("Biceps", "Forearms"), "Dumbbells", "Beginner", listOf("Stand with dumbbells at sides.", "Curl weights up while rotating wrists outwards.", "Lower slowly.")),
            createExercise(43, "Hammer Curl", "Arms", listOf("Biceps", "Brachialis", "Forearms"), "Dumbbells", "Beginner", listOf("Stand holding dumbbells with palms facing each other.", "Curl weights up keeping palms neutral.", "Lower slowly.")),
            createExercise(46, "Tricep Rope Pushdown", "Arms", listOf("Triceps"), "Cable Machine", "Beginner", listOf("Grip rope attachment at chest height.", "Push rope down, flaring ends out.", "Return slowly to chest.")),
            createExercise(47, "Overhead Dumbbell Tricep Extension", "Arms", listOf("Triceps"), "Dumbbells", "Beginner", listOf("Hold dumbbell overhead with both hands.", "Lower dumbbell behind head by bending elbows.", "Extend arms to return to start.")),
            createExercise(50, "Hanging Knee Raise", "Core", listOf("Abs", "Obliques"), "Pull-up Bar", "Intermediate", listOf("Hang from bar with straight legs.", "Lower knees toward chest.", "Lower under control.")),
            createExercise(51, "Plank", "Core", listOf("Abs", "Lower Back", "Shoulders"), "Bodyweight", "Beginner", listOf("Rest on forearms and toes, keeping body in a straight line.", "Hold position while engaging core.", "Do not let hips sag.")),
            createExercise(52, "Russian Twist", "Core", listOf("Obliques", "Abs"), "Bodyweight", "Beginner", listOf("Sit with knees bent, feet slightly off floor.", "Twist torso from side to side.", "Touch hands to floor on each side.")),
            createExercise(53, "Bicycle Crunches", "Core", listOf("Abs", "Obliques"), "Bodyweight", "Beginner", listOf("Lie flat on back.", "Bring opposite elbow to opposite knee while pedaling legs.", "Alternate sides under control.")),
            createExercise(54, "Dumbbell Shrugs", "Back", listOf("Traps", "Shoulders"), "Dumbbells", "Beginner", listOf("Stand holding dumbbells at sides.", "Shrug shoulders as high as possible.", "Hold briefly and lower slowly."))
        )
    }

    fun getFallbackExercises(context: Context): List<ExerciseDBExercise> {
        return try {
            val inputStream = context.resources.openRawResource(R.raw.exercises_fallback)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val type = Types.newParameterizedType(List::class.java, FallbackContainer::class.java)
            val adapter = ExerciseDBApiClient.moshi.adapter<List<FallbackContainer>>(type)
            val list = adapter.fromJson(jsonString)
            val fallbackList = list?.firstOrNull()?.exercises
            if (!fallbackList.isNullOrEmpty()) {
                fallbackList
            } else {
                exercises
            }
        } catch (e: Exception) {
            SafeLog.error("LocalExerciseProvider", "Bundled exercise fallback load failed", e)
            exercises
        }
    }
}
