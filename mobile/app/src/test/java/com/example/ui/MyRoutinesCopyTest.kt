package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.data.SavedRoutineOrigin
import com.example.ui.theme.MyPersonalTrainerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MyRoutinesCopyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `AI generated routine uses provider-neutral origin label`() {
        assertEquals("Generated plan", routineOriginLabel(SavedRoutineOrigin.AI_WORKOUT_GENERATOR))
    }

    @Test
    fun `empty library invites neutral generated workout saving`() {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                MyRoutinesScreen(
                    viewModel = null,
                    onBack = {},
                    onChooseWorkoutDay = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Build a routine or save a generated workout to keep it here."
        ).assertIsDisplayed()
    }
}
