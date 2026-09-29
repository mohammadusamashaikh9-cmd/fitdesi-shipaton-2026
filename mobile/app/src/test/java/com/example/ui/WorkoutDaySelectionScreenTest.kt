package com.example.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.ui.theme.MyPersonalTrainerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkoutDaySelectionScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `selector starts with no day and cannot execute before explicit selection`() {
        var executedDay: Int? = null
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutDaySelectionScreen(
                    routine = routine(),
                    sourceRoutineId = null,
                    hasDraft = false,
                    restoredContext = null,
                    onBack = {},
                    onExecuteSelectedDay = { executedDay = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("workout_day_option_0").assertIsNotSelected()
        composeTestRule.onNodeWithTag("workout_day_option_1").assertIsNotSelected()
        composeTestRule.onNodeWithTag("workout_day_execute").assertIsNotEnabled().performClick()
        assertEquals(null, executedDay)

        composeTestRule.onNodeWithTag("workout_day_option_1").performClick().assertIsSelected()
        composeTestRule.onNodeWithTag("workout_day_execute")
            .assertIsEnabled()
            .assertTextContains("Start selected day")
            .performClick()
        assertEquals(1, executedDay)
    }

    @Test
    fun `exact selected plan day draft is labelled continue`() {
        val routine = routine()
        val context = requireNotNull(routine.toActiveWorkoutContext(selectedDayIndex = 1))
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutDaySelectionScreen(
                    routine = routine,
                    sourceRoutineId = null,
                    hasDraft = true,
                    restoredContext = context,
                    onBack = {},
                    onExecuteSelectedDay = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("workout_day_option_1").performClick()
        composeTestRule.onNodeWithTag("workout_day_execute")
            .assertIsEnabled()
            .assertTextContains("Continue selected day")
    }

    @Test
    fun `zero-day plan fails safely and cannot start`() {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutDaySelectionScreen(
                    routine = routine().copy(days = emptyList()),
                    sourceRoutineId = null,
                    hasDraft = false,
                    restoredContext = null,
                    onBack = {},
                    onExecuteSelectedDay = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("workout_day_empty").assertExists()
        composeTestRule.onNodeWithTag("workout_day_execute").assertIsNotEnabled()
    }

    @Test
    fun `selector back returns through its deterministic Workout callback`() {
        var backCalls = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutDaySelectionScreen(
                    routine = routine(),
                    sourceRoutineId = null,
                    hasDraft = false,
                    restoredContext = null,
                    onBack = { backCalls++ },
                    onExecuteSelectedDay = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back to Workout").performClick()
        assertEquals(1, backCalls)
    }

    private fun routine() = GeneratedRoutine(
        name = "Two day selector fixture",
        description = "Test",
        splitType = "Upper lower",
        frequency = "2 days",
        planId = "selector-plan-0257",
        days = listOf(
            day("Day 1", "Lower strength", "Legs", "0643"),
            day("Day 2", "Upper strength", "Upper body", "1576")
        )
    )

    private fun day(dayName: String, title: String, focus: String, exerciseId: String) = GeneratedDay(
        dayName = dayName,
        title = title,
        description = "Test day",
        focus = focus,
        exercises = listOf(
            GeneratedExercise(
                name = "Exercise $exerciseId",
                sets = 2,
                reps = "8",
                targetMuscle = focus,
                instructions = "Use controlled technique.",
                exerciseId = exerciseId
            )
        )
    )
}
