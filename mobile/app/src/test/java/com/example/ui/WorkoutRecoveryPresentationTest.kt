package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRecoveryPresentationTest {
    @Test
    fun `recovery preference states that selected days are not rearranged`() {
        val presentation = recoverySpacingPreferencePresentation()

        assertEquals("Recovery spacing preference", presentation.title)
        assertTrue(presentation.supportingText.contains("selected workout days stay unchanged", ignoreCase = true))
        assertTrue(presentation.supportingText.contains("does not rearrange", ignoreCase = true))
        assertFalse(presentation.title.contains("enforce", ignoreCase = true))
        assertFalse(presentation.supportingText.contains("guarantee", ignoreCase = true))
    }

    @Test
    fun `logged date retains logged workout classification`() {
        assertEquals(
            WorkoutCalendarDayClassification.LOGGED_WORKOUT,
            classifyWorkoutCalendarDay(
                date = WorkoutCalendarDate(2026, 8, 30),
                today = WorkoutCalendarDate(2026, 8, 24),
                hasWorkoutLog = true,
            ),
        )
    }

    @Test
    fun `past today and future unlogged dates use neutral classifications`() {
        val today = WorkoutCalendarDate(2026, 8, 24)

        assertEquals(
            WorkoutCalendarDayClassification.PAST_NO_WORKOUT_LOGGED,
            classifyWorkoutCalendarDay(WorkoutCalendarDate(2026, 8, 23), today, false),
        )
        assertEquals(
            WorkoutCalendarDayClassification.TODAY_NO_WORKOUT_LOGGED,
            classifyWorkoutCalendarDay(today, today, false),
        )
        assertEquals(
            WorkoutCalendarDayClassification.FUTURE_DATE,
            classifyWorkoutCalendarDay(WorkoutCalendarDate(2026, 8, 25), today, false),
        )
    }

    @Test
    fun `date classification remains correct across month and year boundaries`() {
        assertEquals(
            WorkoutCalendarDayClassification.PAST_NO_WORKOUT_LOGGED,
            classifyWorkoutCalendarDay(
                WorkoutCalendarDate(2025, 12, 31),
                WorkoutCalendarDate(2026, 1, 1),
                false,
            ),
        )
        assertEquals(
            WorkoutCalendarDayClassification.FUTURE_DATE,
            classifyWorkoutCalendarDay(
                WorkoutCalendarDate(2026, 1, 1),
                WorkoutCalendarDate(2025, 12, 31),
                false,
            ),
        )
    }

    @Test
    fun `calendar messages never infer a rest day from an absent log`() {
        val month = "August"
        val messages = listOf(
            workoutCalendarDayMessage(WorkoutCalendarDayClassification.LOGGED_WORKOUT, month, 24),
            workoutCalendarDayMessage(WorkoutCalendarDayClassification.PAST_NO_WORKOUT_LOGGED, month, 23),
            workoutCalendarDayMessage(WorkoutCalendarDayClassification.TODAY_NO_WORKOUT_LOGGED, month, 24),
            workoutCalendarDayMessage(WorkoutCalendarDayClassification.FUTURE_DATE, month, 25),
        )

        assertEquals("Worked out on August 24! Keep moving! ⚡", messages[0])
        assertEquals("August 23 - No workout logged", messages[1])
        assertEquals("August 24 - No workout logged yet", messages[2])
        assertEquals("August 25 - Future date", messages[3])
        assertTrue(messages.none { it.contains("rest day", ignoreCase = true) })
    }
}
