package com.example.ai

import com.google.gson.Gson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredWorkoutContractsTest {
    private val gson = Gson()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `historical Gson routine receives safe empty structured defaults`() {
        val routine = gson.fromJson(historicalRoutineJson(), GeneratedRoutine::class.java)
        val day = routine.days.single()
        val exercise = day.exercises.single()

        assertEquals("", routine.explanation)
        assertEquals("", routine.safetyNote)
        assertEquals("", routine.planId)
        assertEquals("", routine.goal)
        assertEquals("", routine.experienceLevel)
        assertEquals("", routine.progressionGuidance)
        assertEquals(0L, routine.createdAt)
        assertEquals(GeneratedPlanSource.LOCAL_FALLBACK, routine.sourceType)
        assertFalse(routine.profileContextUsed)
        assertNull(day.generalWarmup)
        assertEquals(emptyList<GeneratedExercise>(), day.warmupExercises)
        assertEquals(emptyList<GeneratedExercise>(), day.cooldownExercises)
        assertEquals(emptyList<RampUpSetPrescription>(), exercise.rampUpSets)
        assertNull(exercise.activityPrescription)
        assertEquals("", exercise.safetyNote)
        assertEquals(listOf("0257"), day.exercises.map(GeneratedExercise::exerciseId))
    }

    @Test
    fun `structured Gson routine round trips every phase without mixing main exercises`() {
        val routine = structuredRoutine()

        val restored = gson.fromJson(gson.toJson(routine), GeneratedRoutine::class.java)

        assertEquals(routine, restored)
        val day = restored.days.single()
        assertEquals(listOf("0257"), day.exercises.map(GeneratedExercise::exerciseId))
        assertEquals(listOf("fd-exercise-chair-squat"), day.warmupExercises.map(GeneratedExercise::exerciseId))
        assertEquals(listOf("cooldown-1"), day.cooldownExercises.map(GeneratedExercise::exerciseId))
    }

    @Test
    fun `historical kotlinx workout plan decodes empty phases and tolerates future fields`() {
        val historical = historicalWorkoutPlanJson().replace(
            "\"profileContextUsed\":false",
            "\"profileContextUsed\":false,\"futureField\":\"ignored\""
        )

        val restored = json.decodeFromString(GeneratedWorkoutPlan.serializer(), historical)

        assertNull(restored.days.single().generalWarmup)
        assertTrue(restored.days.single().warmupExercises.isEmpty())
        assertTrue(restored.days.single().cooldownExercises.isEmpty())
        assertTrue(restored.days.single().exercises.single().rampUpSets.isEmpty())
        assertNull(restored.days.single().exercises.single().activityPrescription)
        assertEquals("", restored.days.single().exercises.single().safetyNote)
        assertEquals("0257", restored.days.single().exercises.single().exerciseId)
    }

    @Test
    fun `structured kotlinx plan round trips and conversion preserves routine-supported fields`() {
        val plan = structuredPlan()

        val restored = json.decodeFromString(
            GeneratedWorkoutPlan.serializer(),
            json.encodeToString(GeneratedWorkoutPlan.serializer(), plan)
        )
        val routine = restored.toGeneratedRoutine()

        assertEquals(plan, restored)
        assertEquals(restored.days.single().generalWarmup, routine.days.single().generalWarmup)
        assertEquals(listOf("fd-exercise-chair-squat"), routine.days.single().warmupExercises.map { it.exerciseId })
        assertEquals(listOf("0257"), routine.days.single().exercises.map { it.exerciseId })
        assertEquals(listOf("cooldown-1"), routine.days.single().cooldownExercises.map { it.exerciseId })
        assertEquals(restored.days.single().exercises.single().rampUpSets, routine.days.single().exercises.single().rampUpSets)
        assertEquals(restored.days.single().exercises.single().activityPrescription, routine.days.single().exercises.single().activityPrescription)
        assertEquals("Keep the movement comfortable.", routine.days.single().exercises.single().safetyNote)
    }

    @Test
    fun `main-only plan conversion retains exactly the historical main list`() {
        val plan = historicalPlan()

        val routine = plan.toGeneratedRoutine()

        assertEquals(listOf("0257"), routine.days.single().exercises.map(GeneratedExercise::exerciseId))
        assertNull(routine.days.single().generalWarmup)
        assertTrue(routine.days.single().warmupExercises.isEmpty())
        assertTrue(routine.days.single().cooldownExercises.isEmpty())
    }

    @Test
    fun `structured validation rejects malformed optional prescriptions`() {
        assertFalse(ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = 0).isStructurallyValid())
        assertFalse(ActivityPrescription(ActivityPrescriptionMode.DURATION_SECONDS, durationSeconds = 0).isStructurallyValid())
        assertFalse(ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = " ").isStructurallyValid())
        assertFalse(GeneralWarmupPrescription("Warm up", 0, WarmupIntensity.EASY).isStructurallyValid())
        assertFalse(RampUpSetPrescription(0, RampUpLoadCue.LIGHT, 5, 30).isStructurallyValid())
        assertFalse(RampUpSetPrescription(1, RampUpLoadCue.LIGHT, 0, 30).isStructurallyValid())
        assertFalse(RampUpSetPrescription(1, RampUpLoadCue.LIGHT, 5, -1).isStructurallyValid())
    }

    private fun structuredRoutine() = GeneratedRoutine(
        name = "Structured routine",
        description = "Compatibility fixture",
        splitType = "Full Body",
        frequency = "1 day/week",
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Full Body",
                description = "Structured day",
                exercises = listOf(structuredExercise("0257", "Main squat")),
                generalWarmup = GeneralWarmupPrescription(
                    label = "Easy cycle",
                    durationSeconds = 300,
                    intensityCue = WarmupIntensity.EASY_TO_MODERATE,
                    equipment = "Stationary bike"
                ),
                warmupExercises = listOf(structuredExercise("fd-exercise-chair-squat", "Preparation squat")),
                cooldownExercises = listOf(structuredExercise("cooldown-1", "Static stretch"))
            )
        )
    )

    private fun structuredExercise(id: String, name: String) = GeneratedExercise(
        name = name,
        sets = 2,
        reps = "8",
        targetMuscle = "Legs",
        instructions = "Move with control.",
        exerciseId = id,
        rampUpSets = listOf(RampUpSetPrescription(1, RampUpLoadCue.VERY_LIGHT, 8, 30)),
        activityPrescription = ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = 8, perSide = true),
        safetyNote = "Keep the movement comfortable."
    )

    private fun structuredPlan(): GeneratedWorkoutPlan {
        val routine = structuredRoutine()
        val day = routine.days.single()
        fun GeneratedExercise.asPlanExercise() = GeneratedWorkoutPlanExercise(
            exerciseId = exerciseId,
            name = name,
            movementPattern = targetMuscle,
            sets = sets,
            repsOrDuration = reps,
            restSeconds = restSeconds,
            rampUpSets = rampUpSets,
            activityPrescription = activityPrescription,
            safetyNote = safetyNote
        )
        return historicalPlan().copy(
            days = listOf(
                GeneratedWorkoutPlanDay(
                    dayName = day.dayName,
                    focus = day.focus,
                    exercises = day.exercises.map { it.asPlanExercise() },
                    generalWarmup = day.generalWarmup,
                    warmupExercises = day.warmupExercises.map { it.asPlanExercise() },
                    cooldownExercises = day.cooldownExercises.map { it.asPlanExercise() }
                )
            )
        )
    }

    private fun historicalPlan() = GeneratedWorkoutPlan(
        planId = "historical-plan",
        title = "Historical plan",
        goal = "General fitness",
        experienceLevel = "Beginner",
        days = listOf(
            GeneratedWorkoutPlanDay(
                dayName = "Day 1",
                focus = "Full Body",
                exercises = listOf(
                    GeneratedWorkoutPlanExercise("0257", "Squat", "SQUAT", 3, "8 reps", 60)
                )
            )
        ),
        progressionGuidance = "Progress gradually.",
        recoveryGuidance = "Rest as needed.",
        safetyNote = "General fitness guidance only.",
        createdAt = 1_700_000_000_000,
        sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
        profileContextUsed = false
    )

    private fun historicalRoutineJson(): String = """
        {
          "name":"Historical routine",
          "description":"Before structured phases",
          "splitType":"Full Body",
          "frequency":"1 day/week",
          "days":[{
            "dayName":"Day 1",
            "title":"Full Body",
            "description":"Main work only",
            "exercises":[{
              "name":"Squat","sets":3,"reps":"8","targetMuscle":"Legs",
              "instructions":"Move with control.","exerciseId":"0257"
            }]
          }]
        }
    """.trimIndent()

    private fun historicalWorkoutPlanJson(): String = """
        {
          "planId":"historical-plan","title":"Historical plan","goal":"General fitness",
          "experienceLevel":"Beginner","days":[{
            "dayName":"Day 1","focus":"Full Body","exercises":[{
              "exerciseId":"0257","name":"Squat","movementPattern":"SQUAT",
              "sets":3,"repsOrDuration":"8 reps","restSeconds":60
            }]
          }],
          "progressionGuidance":"Progress gradually.","recoveryGuidance":"Rest as needed.",
          "safetyNote":"General fitness guidance only.","createdAt":1700000000000,
          "sourceType":"LOCAL_KNOWLEDGE","profileContextUsed":false
        }
    """.trimIndent()
}
