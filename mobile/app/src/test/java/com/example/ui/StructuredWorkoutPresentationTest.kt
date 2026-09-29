package com.example.ui

import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneralWarmupPrescription
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.WarmupIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredWorkoutPresentationTest {
    @Test
    fun `historical main-only day uses legacy display with no empty phase sections`() {
        val main = exercise("0257", "Main")

        val presentation = structuredWorkoutDayPresentation(day(main = listOf(main)))

        assertTrue(presentation.isLegacyDisplay)
        assertEquals(listOf(StructuredWorkoutPhase.MAIN_WORKOUT), presentation.sections.map { it.phase })
        assertSame(main, presentation.sections.single().exercises.single())
        assertTrue(presentation.sections.none { it.generalWarmup != null || it.exercises.isEmpty() })
    }

    @Test
    fun `structured phases retain required order and strict exercise separation`() {
        val preparation = exercise("prep", "Preparation")
        val main = exercise("main", "Main")
        val cooldown = exercise("cooldown", "Cooldown")
        val presentation = structuredWorkoutDayPresentation(
            day(
                generalWarmup = GeneralWarmupPrescription("Easy walk", 120, WarmupIntensity.EASY),
                preparation = listOf(preparation),
                main = listOf(main),
                cooldown = listOf(cooldown)
            )
        )

        assertFalse(presentation.isLegacyDisplay)
        assertEquals(
            listOf(
                StructuredWorkoutPhase.GENERAL_WARM_UP,
                StructuredWorkoutPhase.PREPARATION,
                StructuredWorkoutPhase.MAIN_WORKOUT,
                StructuredWorkoutPhase.COOL_DOWN
            ),
            presentation.sections.map { it.phase }
        )
        assertEquals(listOf("prep"), presentation.section(StructuredWorkoutPhase.PREPARATION).exercises.map { it.exerciseId })
        assertEquals(listOf("main"), presentation.section(StructuredWorkoutPhase.MAIN_WORKOUT).exercises.map { it.exerciseId })
        assertEquals(listOf("cooldown"), presentation.section(StructuredWorkoutPhase.COOL_DOWN).exercises.map { it.exerciseId })
    }

    @Test
    fun `ramp-up sets stay on their main exercise without changing working prescription`() {
        val firstRamp = RampUpSetPrescription(1, RampUpLoadCue.VERY_LIGHT, 8, 30)
        val secondRamp = RampUpSetPrescription(2, RampUpLoadCue.LIGHT, 5, 0)
        val main = exercise("main", "Main", sets = 3, reps = "8 reps", rampUpSets = listOf(firstRamp, secondRamp))

        val displayed = structuredWorkoutDayPresentation(day(main = listOf(main)))
            .section(StructuredWorkoutPhase.MAIN_WORKOUT)
            .exercises
            .single()

        assertSame(main, displayed)
        assertEquals(listOf(firstRamp, secondRamp), displayed.rampUpSets)
        assertEquals(3, displayed.sets)
        assertEquals("8 reps", displayed.reps)
    }

    @Test
    fun `repetition prescriptions omit invalid values and format per-side`() {
        assertEquals(
            "10 repetitions",
            formatActivityPrescription(ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = 10))
        )
        assertEquals(
            "8 repetitions per side",
            formatActivityPrescription(ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = 8, perSide = true))
        )
        assertNull(formatActivityPrescription(ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = 0)))
    }

    @Test
    fun `duration prescriptions use readable seconds and minutes with per-side`() {
        assertEquals("30 seconds", formatStructuredDuration(30))
        assertEquals("2 minutes", formatStructuredDuration(120))
        assertEquals("1 minute 30 seconds", formatStructuredDuration(90))
        assertEquals(
            "45 seconds per side",
            formatActivityPrescription(ActivityPrescription(ActivityPrescriptionMode.DURATION_SECONDS, durationSeconds = 45, perSide = true))
        )
        assertNull(formatStructuredDuration(0))
    }

    @Test
    fun `free-text prescriptions omit blanks and avoid repeated per-side text`() {
        assertEquals(
            "Move slowly through a comfortable range",
            formatActivityPrescription(
                ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = " Move slowly through a comfortable range ")
            )
        )
        assertEquals(
            "Hold gently per side",
            formatActivityPrescription(
                ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = "Hold gently", perSide = true)
            )
        )
        assertEquals(
            "Move per side",
            formatActivityPrescription(
                ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = "Move per side", perSide = true)
            )
        )
        assertNull(formatActivityPrescription(ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = "   ")))
    }

    @Test
    fun `warm-up intensities and ramp-up load cues never expose enum identifiers`() {
        assertEquals(listOf("Easy", "Easy to moderate", "Moderate"), WarmupIntensity.entries.map(::warmupIntensityLabel))
        assertEquals(listOf("Very light", "Light", "Moderate"), RampUpLoadCue.entries.map(::rampUpLoadCueLabel))
    }

    @Test
    fun `ramp-up rows preserve ordinal order and omit nonpositive rest`() {
        val rows = listOf(
            RampUpSetPrescription(1, RampUpLoadCue.VERY_LIGHT, 8, 30),
            RampUpSetPrescription(2, RampUpLoadCue.LIGHT, 5, 0)
        ).mapNotNull(::formatRampUpSetRow)

        assertEquals(listOf("Set 1: Very light, 8 repetitions, 30 seconds rest", "Set 2: Light, 5 repetitions"), rows)
    }

    @Test
    fun `blank safety notes are omitted and nonblank notes are retained`() {
        assertNull(displayedSafetyNote(exercise("blank", "Blank", safetyNote = "   ")))
        assertEquals("Keep the movement comfortable.", displayedSafetyNote(exercise("safe", "Safe", safetyNote = "Keep the movement comfortable.")))
    }

    @Test
    fun `leading-zero opaque IDs and main order remain exact`() {
        val leadingZero = exercise("0257", "Leading zero")
        val opaque = exercise("fd-exercise-chair-squat", "Chair squat")

        val displayed = structuredWorkoutDayPresentation(
            day(
                main = listOf(
                    leadingZero.copy(safetyNote = "Keep control."),
                    opaque
                )
            )
        ).section(StructuredWorkoutPhase.MAIN_WORKOUT).exercises

        assertEquals(listOf("0257", "fd-exercise-chair-squat"), displayed.map { it.exerciseId })
        assertEquals(listOf("Leading zero", "Chair squat"), displayed.map { it.name })
    }

    private fun StructuredWorkoutDayPresentation.section(phase: StructuredWorkoutPhase): StructuredWorkoutSection =
        sections.single { it.phase == phase }

    private fun day(
        generalWarmup: GeneralWarmupPrescription? = null,
        preparation: List<GeneratedExercise> = emptyList(),
        main: List<GeneratedExercise> = emptyList(),
        cooldown: List<GeneratedExercise> = emptyList()
    ) = GeneratedDay(
        dayName = "Monday",
        title = "Full body",
        description = "Workout",
        exercises = main,
        generalWarmup = generalWarmup,
        warmupExercises = preparation,
        cooldownExercises = cooldown
    )

    private fun exercise(
        id: String,
        name: String,
        sets: Int = 3,
        reps: String = "10 reps",
        rampUpSets: List<RampUpSetPrescription> = emptyList(),
        safetyNote: String = ""
    ) = GeneratedExercise(
        exerciseId = id,
        name = name,
        sets = sets,
        reps = reps,
        targetMuscle = "Full body",
        instructions = "Move with control.",
        rampUpSets = rampUpSets,
        safetyNote = safetyNote
    )
}
