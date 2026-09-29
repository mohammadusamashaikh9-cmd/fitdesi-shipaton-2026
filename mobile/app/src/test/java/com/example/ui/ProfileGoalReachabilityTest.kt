package com.example.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Flag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.AutomaticCalorieInput
import com.example.ui.theme.MyPersonalTrainerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileGoalReachabilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun profileGoalItemDisplaysStoredValueAndRoutesClickToExistingEditor() {
        var requestedField: String? = null

        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProfileGoalBiometricItem(
                    goal = "Lose Fat",
                    onEditField = { requestedField = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_goal_edit")
            .assertTextContains("Lose Fat")
            .assertTextContains("Tap to edit")
            .performClick()

        assertEquals("goal", requestedField)
    }

    @Test
    fun automaticCalorieGuidanceNamesTheActualMissingInputs() {
        assertEquals(
            "Set fitness goal",
            automaticCalorieReadinessGuidance(
                listOf(AutomaticCalorieInput.FITNESS_GOAL)
            )
        )
        assertEquals(
            "Set age (18-100), activity level, and fitness goal",
            automaticCalorieReadinessGuidance(
                listOf(
                    AutomaticCalorieInput.AGE,
                    AutomaticCalorieInput.ACTIVITY_LEVEL,
                    AutomaticCalorieInput.FITNESS_GOAL
                )
            )
        )
    }

    @Test
    fun profileMetricRowMatchesSiblingHeightsWithoutAFixedMaximum() {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProfileEqualHeightRow(horizontalSpacing = 10.dp) {
                    BiometricInfoItem(
                        icon = Icons.Default.Cake,
                        label = "AGE",
                        value = "30 yrs",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("plain_metric")
                    )
                    BiometricInfoItem(
                        icon = Icons.Default.Flag,
                        label = "GOAL",
                        value = "Build muscle",
                        supportingText = "Tap to edit",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("supported_metric")
                    )
                }
            }
        }

        val plainHeight = composeTestRule.onNodeWithTag("plain_metric")
            .fetchSemanticsNode().boundsInRoot.height
        val supportedHeight = composeTestRule.onNodeWithTag("supported_metric")
            .fetchSemanticsNode().boundsInRoot.height

        assertEquals(plainHeight, supportedHeight, 0.01f)
    }
}
