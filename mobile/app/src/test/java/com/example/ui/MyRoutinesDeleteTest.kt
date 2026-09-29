package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.theme.MyPersonalTrainerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MyRoutinesDeleteTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cancellationDismissesWithoutDeleting() {
        var dismissals = 0
        var deletions = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                RoutineDeleteConfirmationDialog(
                    routineName = "Opaque strength plan",
                    onDismiss = { dismissals += 1 },
                    onConfirm = { deletions += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("Delete Opaque strength plan?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissals)
        assertEquals(0, deletions)
    }

    @Test
    fun confirmationNamesRoutineAndRequestsOneDeletion() {
        var deletions = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                RoutineDeleteConfirmationDialog(
                    routineName = "Opaque strength plan",
                    onDismiss = {},
                    onConfirm = { deletions += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("Delete Opaque strength plan?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(1, deletions)
    }
}
