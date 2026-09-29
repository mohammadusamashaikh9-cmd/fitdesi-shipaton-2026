package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.example.data.WorkoutLog
import com.example.domain.LocalCalendarDate
import com.example.domain.ProgressDatePreset
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionResult
import com.example.domain.calculateProgressAnalyticsV2
import com.example.ui.components.charts.sparseXAxisIndexes
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.ProgressUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgressHubScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalCalendarDate(2026, 9, 4)

    @Test
    fun `Basic Journal does not auto-open paywall and older action is deliberate`() {
        var fullHistoryActions = 0
        var state by mutableStateOf(progressState(canViewFullHistory = false))

        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProgressHubContent(
                    state = state,
                    initialSection = ProgressHubSection.JOURNAL,
                    onBack = {},
                    onFullHistoryRequired = { fullHistoryActions += 1 },
                    onAdvancedAnalyticsRequired = {},
                    onManageWorkoutHistory = {},
                    onDateSelected = {},
                    onShift = { ProgressDateSelectionResult.RequiresFullHistory },
                    onPreset = { _, _ -> ProgressDateSelectionResult.Invalid("unused") }
                )
            }
        }

        composeTestRule.onNodeWithTag("fitness_journal").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, fullHistoryActions) }

        composeTestRule.runOnIdle { state = progressState(canViewFullHistory = true) }
        composeTestRule.runOnIdle { assertEquals(0, fullHistoryActions) }

        composeTestRule.onNodeWithTag("progress_period_previous").performClick()
        composeTestRule.runOnIdle { assertEquals(1, fullHistoryActions) }
    }

    @Test
    fun `V2 full history discovery requires an explicit click and closes its picker`() {
        var fullHistoryActions = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProgressHubContent(
                    state = progressState(canViewFullHistory = false),
                    initialSection = ProgressHubSection.TRAINING,
                    onBack = {},
                    onFullHistoryRequired = { fullHistoryActions += 1 },
                    onAdvancedAnalyticsRequired = {},
                    onManageWorkoutHistory = {},
                    onDateSelected = {},
                    onShift = { ProgressDateSelectionResult.Invalid("unused") },
                    onPreset = { preset, _ ->
                        if (preset == ProgressDatePreset.ALL_HISTORY) ProgressDateSelectionResult.RequiresFullHistory
                        else ProgressDateSelectionResult.Invalid("unused")
                    }
                )
            }
        }

        composeTestRule.runOnIdle { assertEquals(0, fullHistoryActions) }
        composeTestRule.onNodeWithTag("progress_period_picker").performClick()
        composeTestRule.onNodeWithTag("progress_all_history").performClick()
        composeTestRule.runOnIdle { assertEquals(1, fullHistoryActions) }
        composeTestRule.onNodeWithText("Choose progress period").assertDoesNotExist()
    }

    @Test
    fun `V2 preset RequiresFullHistory closes its picker before deliberate paywall`() {
        var fullHistoryActions = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProgressHubContent(
                    state = progressState(canViewFullHistory = false),
                    initialSection = ProgressHubSection.TRAINING,
                    onBack = {},
                    onFullHistoryRequired = { fullHistoryActions += 1 },
                    onAdvancedAnalyticsRequired = {},
                    onManageWorkoutHistory = {},
                    onDateSelected = {},
                    onShift = { ProgressDateSelectionResult.Invalid("unused") },
                    onPreset = { preset, _ ->
                        if (preset == ProgressDatePreset.THIS_MONTH) ProgressDateSelectionResult.RequiresFullHistory
                        else ProgressDateSelectionResult.Invalid("unused")
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("progress_period_picker").performClick()
        composeTestRule.onNodeWithText("This month").performClick()
        composeTestRule.runOnIdle { assertEquals(1, fullHistoryActions) }
        composeTestRule.onNodeWithText("Choose progress period").assertDoesNotExist()
    }

    @Test
    fun `Basic V2 Training is discoverable and advanced paywall action is deliberate`() {
        var advancedActions = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProgressHubContent(
                    state = progressState(canViewFullHistory = false),
                    initialSection = ProgressHubSection.TRAINING,
                    onBack = {},
                    onFullHistoryRequired = {},
                    onAdvancedAnalyticsRequired = { advancedActions += 1 },
                    onManageWorkoutHistory = {},
                    onDateSelected = {},
                    onShift = { ProgressDateSelectionResult.Invalid("unused") },
                    onPreset = { _, _ -> ProgressDateSelectionResult.Invalid("unused") }
                )
            }
        }

        composeTestRule.onNodeWithTag("progress_training")
            .performScrollToNode(hasTestTag("advanced_analytics_locked"))
        composeTestRule.onNodeWithTag("advanced_analytics_locked").assertIsDisplayed()
        composeTestRule.onNodeWithText("Previous period").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Monday, no workout logged").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, advancedActions) }
        composeTestRule.onNodeWithTag("progress_training")
            .performScrollToNode(hasTestTag("advanced_analytics_action"))
        composeTestRule.onNodeWithTag("advanced_analytics_action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, advancedActions)
        }
    }

    @Test
    fun `native charts show every short label and sparse long labels`() {
        assertEquals((0..6).toList(), sparseXAxisIndexes(7))
        assertEquals(listOf(0, 15, 29), sparseXAxisIndexes(30))
    }

    @Test
    fun `150 percent font scale keeps the selected Journal tab visible`() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyPersonalTrainerTheme {
                    ProgressHubContent(
                        state = progressState(canViewFullHistory = false),
                        initialSection = ProgressHubSection.JOURNAL,
                        onBack = {},
                        onFullHistoryRequired = {},
                        onAdvancedAnalyticsRequired = {},
                        onManageWorkoutHistory = {},
                        onDateSelected = {},
                        onShift = { ProgressDateSelectionResult.Invalid("unused") },
                        onPreset = { _, _ -> ProgressDateSelectionResult.Invalid("unused") }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("progress_section_tabs").assertIsDisplayed()
        composeTestRule.onNodeWithTag("progress_tab_journal").assertIsDisplayed()
    }

    @Test
    fun `Weekly Review uses the consumer progress range formatter`() {
        assertEquals(
            "Aug 24 – Aug 30, 2026",
            progressSelectionLabel(
                ProgressDateSelection.Range(
                    LocalCalendarDate(2026, 8, 24),
                    LocalCalendarDate(2026, 8, 30)
                )
            )
        )
    }

    @Test
    fun `detailed workout management row shows persisted workout detail`() {
        val workout = managementWorkout()
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutHistoryRow(
                    workout = workout,
                    showDetailedHistory = true,
                    onRemove = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Private session name").assertIsDisplayed()
        composeTestRule.onNodeWithText("STRENGTH").assertIsDisplayed()
        composeTestRule.onNodeWithText("4 completed sets", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("900 kg volume", substring = true).assertIsDisplayed()
    }

    @Test
    fun `restricted workout management row hides detail and remains deletable`() {
        val workout = managementWorkout()
        var removals = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutHistoryRow(
                    workout = workout,
                    showDetailedHistory = false,
                    onRemove = { removals += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("Workout record").assertIsDisplayed()
        composeTestRule.onNodeWithText("Private session name").assertDoesNotExist()
        composeTestRule.onNodeWithText("STRENGTH").assertDoesNotExist()
        composeTestRule.onNodeWithText("4 completed sets", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("900 kg volume", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Delete workout record").performClick()
        composeTestRule.runOnIdle { assertEquals(1, removals) }
    }

    private fun progressState(canViewFullHistory: Boolean): ProgressUiState {
        val selection = ProgressDateSelection.Range(LocalCalendarDate(2026, 8, 6), today)
        return ProgressUiState(
            isLoading = false,
            today = today,
            dateSelection = selection,
            analytics = calculateProgressAnalyticsV2(
                selection,
                today,
                TimeZone.getTimeZone("UTC"),
                emptyList(),
                emptyList()
            ),
            journalWindowStart = LocalCalendarDate(2026, 8, 6),
            journalWindowEnd = today,
            canViewFullHistory = canViewFullHistory
        )
    }

    private fun managementWorkout() = WorkoutLog(
        id = 42,
        exerciseName = "Private session name",
        category = "Strength",
        durationMinutes = 25,
        caloriesBurned = 0,
        completedSets = 4,
        liftingVolumeKg = 900.0,
        timestamp = 1_788_502_400_000L
    )
}
