package com.example.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PlannerExercise(
    val name: String,
    val sets: Int,
    val reps: String,
    val muscleGroup: String,
    val equipment: String,
    val instructions: String,
    val videoUrl: String = "",
    val gifUrl: String = "",
    val grip: String? = null,
    val mechanic: String? = null,
    val force: String? = null,
    val howToSetup: List<String>? = null,
    val howToPerform: List<String>? = null,
    val properTechnique: List<String>? = null,
    val thingsToAvoid: List<String>? = null
)

val PlannerExercise.safeName: String
    get() = (name as? String) ?: ""

val PlannerExercise.safeReps: String
    get() = (reps as? String) ?: "8-12"

val PlannerExercise.safeSets: Int
    get() = (sets as? Int) ?: 3

val PlannerExercise.safeMuscleGroup: String
    get() = (muscleGroup as? String) ?: ""

val PlannerExercise.safeEquipment: String
    get() = (equipment as? String) ?: ""

val PlannerExercise.safeInstructions: String
    get() = (instructions as? String) ?: ""

val PlannerExercise.safeGifUrl: String
    get() = (gifUrl as? String) ?: ""


data class WorkoutSplit(
    val name: String,
    val description: String,
    val estimatedTime: String,
    val exercises: List<PlannerExercise>
)

object LocalWorkoutPlanProvider {
    private val jsonString = """
    [
      {
        "name": "Push",
        "description": "Chest, Shoulders, Triceps",
        "estimatedTime": "45-60 mins",
        "exercises": [
          {
            "name": "Barbell Bench Press",
            "sets": 4,
            "reps": "8-12",
            "muscleGroup": "Chest",
            "equipment": "Barbell",
            "instructions": "Lie flat on the bench with your feet flat on the floor. Grip the barbell with hands slightly wider than shoulder-width. Unrack the bar and lower it slowly to your mid-chest. Push the bar back up explosively until your arms are fully extended.",
            "videoUrl": ""
          },
          {
            "name": "Incline Dumbbell Press",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Chest",
            "equipment": "Dumbbells",
            "instructions": "Lie on an incline bench set at 30-45 degrees. Hold dumbbells at chest level and press them straight up until your arms are fully locked. Lower under control.",
            "videoUrl": ""
          },
          {
            "name": "Dumbbell Shoulder Press",
            "sets": 4,
            "reps": "8-12",
            "muscleGroup": "Shoulders",
            "equipment": "Dumbbells",
            "instructions": "Sit on a bench with back support. Hold dumbbells at shoulder height and press them overhead until arms are straight. Avoid locking elbows fully.",
            "videoUrl": ""
          },
          {
            "name": "Lateral Raises",
            "sets": 3,
            "reps": "12-15",
            "muscleGroup": "Shoulders",
            "equipment": "Dumbbells",
            "instructions": "Stand tall holding dumbbells at your sides. Raise your arms out to the sides until they are parallel to the floor, then lower them slowly back down.",
            "videoUrl": ""
          },
          {
            "name": "Tricep Pushdowns",
            "sets": 3,
            "reps": "12-15",
            "muscleGroup": "Triceps",
            "equipment": "Cable Machine",
            "instructions": "Stand facing a cable machine. Grab the bar or rope attachment, keep your elbows tucked tightly to your sides, and press down until arms are fully extended.",
            "videoUrl": ""
          },
          {
            "name": "Overhead Tricep Extension",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Triceps",
            "equipment": "Dumbbell",
            "instructions": "Hold a dumbbell overhead with both hands. Keeping your elbows tucked in close to your ears, lower the weight slowly behind your head, then extend arms back up.",
            "videoUrl": ""
          }
        ]
      },
      {
        "name": "Pull",
        "description": "Back, Biceps, Rear Delts",
        "estimatedTime": "45-60 mins",
        "exercises": [
          {
            "name": "Pull-Ups",
            "sets": 4,
            "reps": "6-10",
            "muscleGroup": "Back",
            "equipment": "Pull-up Bar",
            "instructions": "Grasp the pull-up bar with hands wider than shoulder-width, palms facing away. Hang with arms fully extended. Pull your chest up to the bar by driving your elbows down. Slowly lower yourself back to the starting position.",
            "videoUrl": ""
          },
          {
            "name": "Barbell Rows",
            "sets": 4,
            "reps": "8-12",
            "muscleGroup": "Back",
            "equipment": "Barbell",
            "instructions": "Stand holding a barbell with an overhand grip. Hinge forward at the hips, keeping your back flat. Pull the bar up toward your lower chest, keeping elbows tucked. Lower the bar back down slowly under control.",
            "videoUrl": ""
          },
          {
            "name": "Seated Cable Rows",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Back",
            "equipment": "Cable Machine",
            "instructions": "Sit at the cable row station with knees slightly bent. Pull the handle toward your lower abdomen while squeezing your shoulder blades together. Release under control.",
            "videoUrl": ""
          },
          {
            "name": "Face Pulls",
            "sets": 3,
            "reps": "15-20",
            "muscleGroup": "Rear Delts",
            "equipment": "Cable Machine",
            "instructions": "Attach a rope to the cable machine at upper chest height. Pull the rope attachment towards your forehead, keeping elbows high and pulling outward.",
            "videoUrl": ""
          },
          {
            "name": "Barbell Curls",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Biceps",
            "equipment": "Barbell",
            "instructions": "Stand straight with your feet shoulder-width apart holding a barbell. Keep your elbows close to your torso. Curl the bar forward while contracting your biceps. Slowly lower the bar back to the starting position.",
            "videoUrl": ""
          },
          {
            "name": "Hammer Curls",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Biceps",
            "equipment": "Dumbbells",
            "instructions": "Hold dumbbells with a neutral grip (palms facing each other) at your sides. Curl the weights forward while keeping your elbows stable and close to your sides.",
            "videoUrl": ""
          }
        ]
      },
      {
        "name": "Legs",
        "description": "Quads, Hamstrings, Glutes, Calves",
        "estimatedTime": "50-65 mins",
        "exercises": [
          {
            "name": "Barbell Squats",
            "sets": 4,
            "reps": "8-12",
            "muscleGroup": "Quads",
            "equipment": "Barbell",
            "instructions": "Rest the barbell on your upper back/traps. Set your feet shoulder-width apart, toes slightly outward. Lower your hips back and down like sitting in a chair. Drive back up to the standing position through your heels.",
            "videoUrl": ""
          },
          {
            "name": "Romanian Deadlifts",
            "sets": 4,
            "reps": "10-12",
            "muscleGroup": "Hamstrings",
            "equipment": "Barbell",
            "instructions": "Stand tall holding a barbell in front. Hinge at your hips, pushing them backward while keeping knees slightly bent. Lower the weight down the front of your legs until you feel a stretch in your hamstrings, then contract glutes to stand upright.",
            "videoUrl": ""
          },
          {
            "name": "Leg Press",
            "sets": 3,
            "reps": "12-15",
            "muscleGroup": "Quads",
            "equipment": "Leg Press Machine",
            "instructions": "Sit in the leg press machine and place feet shoulder-width apart on the platform. Lower the platform slowly by bending your knees to a 90-degree angle, then push it back up through your heels.",
            "videoUrl": ""
          },
          {
            "name": "Leg Curls",
            "sets": 3,
            "reps": "12-15",
            "muscleGroup": "Hamstrings",
            "equipment": "Leg Curl Machine",
            "instructions": "Lie face down or sit on the leg curl machine. Flex your knees to pull the roller pad toward your glutes. Hold for a second, then return slowly to the starting position.",
            "videoUrl": ""
          },
          {
            "name": "Standing Calf Raises",
            "sets": 4,
            "reps": "12-15",
            "muscleGroup": "Calves",
            "equipment": "Calf Raise Machine",
            "instructions": "Stand with balls of your feet on a raised platform. Lower your heels below the platform level, then raise up as high as possible on your toes. Squeeze calves and lower slowly.",
            "videoUrl": ""
          },
          {
            "name": "Plank",
            "sets": 3,
            "reps": "45-60 sec",
            "muscleGroup": "Core",
            "equipment": "Bodyweight",
            "instructions": "Rest your forearms and toes on the floor. Keep your body in a straight line from head to heels, engage your glutes, and squeeze your core tight. Hold and breathe steadily.",
            "videoUrl": ""
          }
        ]
      },
      {
        "name": "Full Body",
        "description": "All Major Muscles",
        "estimatedTime": "60-75 mins",
        "exercises": [
          {
            "name": "Barbell Squats",
            "sets": 3,
            "reps": "8-10",
            "muscleGroup": "Quads & Glutes",
            "equipment": "Barbell",
            "instructions": "Perform standard barbell squats under control. Keep your chest up and core engaged throughout.",
            "videoUrl": ""
          },
          {
            "name": "Barbell Bench Press",
            "sets": 3,
            "reps": "8-12",
            "muscleGroup": "Chest",
            "equipment": "Barbell",
            "instructions": "Bench press on a flat bench to engage pectorals, triceps, and anterior deltoids.",
            "videoUrl": ""
          },
          {
            "name": "Pull-Ups",
            "sets": 3,
            "reps": "8-10",
            "muscleGroup": "Back",
            "equipment": "Pull-up Bar",
            "instructions": "Hang from a pull-up bar and pull your chest to the bar to work the lats and upper back.",
            "videoUrl": ""
          },
          {
            "name": "Dumbbell Shoulder Press",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Shoulders",
            "equipment": "Dumbbells",
            "instructions": "Press dumbbells overhead while seated or standing to work your deltoids.",
            "videoUrl": ""
          },
          {
            "name": "Dumbbell Romanian Deadlifts",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Hamstrings",
            "equipment": "Dumbbells",
            "instructions": "Perform hip hinges with dumbbells to engage hamstrings and lower back.",
            "videoUrl": ""
          },
          {
            "name": "Plank",
            "sets": 3,
            "reps": "45-60 sec",
            "muscleGroup": "Core",
            "equipment": "Bodyweight",
            "instructions": "Maintain a strict push-up plank position on your forearms to engage your entire core.",
            "videoUrl": ""
          }
        ]
      },
      {
        "name": "Upper/Lower",
        "description": "Alternating Splits",
        "estimatedTime": "45-60 mins",
        "exercises": [
          {
            "name": "Barbell Bench Press",
            "sets": 3,
            "reps": "8-12",
            "muscleGroup": "Chest",
            "equipment": "Barbell",
            "instructions": "Flat barbell bench press focusing on chest activation.",
            "videoUrl": ""
          },
          {
            "name": "Barbell Rows",
            "sets": 3,
            "reps": "8-12",
            "muscleGroup": "Back",
            "equipment": "Barbell",
            "instructions": "Bent-over barbell rows focusing on upper back thickness.",
            "videoUrl": ""
          },
          {
            "name": "Dumbbell Shoulder Press",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Shoulders",
            "equipment": "Dumbbells",
            "instructions": "Overhead dumbbell presses to target the shoulders.",
            "videoUrl": ""
          },
          {
            "name": "Barbell Squats",
            "sets": 3,
            "reps": "8-12",
            "muscleGroup": "Quads & Glutes",
            "equipment": "Barbell",
            "instructions": "Focus on deep, controlled barbell squats.",
            "videoUrl": ""
          },
          {
            "name": "Romanian Deadlifts",
            "sets": 3,
            "reps": "10-12",
            "muscleGroup": "Hamstrings",
            "equipment": "Barbell",
            "instructions": "Controlled hip hinges to target the posterior chain.",
            "videoUrl": ""
          },
          {
            "name": "Standing Calf Raises",
            "sets": 3,
            "reps": "12-15",
            "muscleGroup": "Calves",
            "equipment": "Calf Raise Machine",
            "instructions": "High intensity calf raises focusing on the stretch and peak contraction.",
            "videoUrl": ""
          }
        ]
      }
    ]
    """.trimIndent()

    val splits: List<WorkoutSplit> by lazy {
        val gson = Gson()
        val type = object : TypeToken<List<WorkoutSplit>>() {}.type
        gson.fromJson(jsonString, type)
    }
}
