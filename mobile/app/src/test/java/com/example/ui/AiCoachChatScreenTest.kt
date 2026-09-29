package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.ai.AiCoachResponse
import com.example.ai.AiRuntimeMode
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedDietPlanDay
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import com.example.ai.conversation.AiCoachMessage
import com.example.ai.conversation.AiCoachMessageRole
import com.example.ai.conversation.AiCoachMessageSource
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.AiCoachConversationSummary
import com.example.viewmodel.AiCoachUiState
import com.example.viewmodel.CoachPlanSaveState
import com.example.viewmodel.CoachRemoteAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiCoachChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `empty conversation shows FitDesi greeting suggestions composer and header actions`() {
        var historyClicks = 0
        var newChatClicks = 0
        composeRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                AiCoachChatContent(
                    state = AiCoachUiState(),
                    runtimeMode = AiRuntimeMode.LOCAL,
                    onBack = {},
                    onQuestionChange = {},
                    onSubmit = {},
                    onRetry = {},
                    onHistory = { historyClicks += 1 },
                    onNewChat = { newChatClicks += 1 },
                    onSaveMessage = {},
                    onGrantConsent = {},
                    onKeepLocal = {},
                    onViewSavedDietPlan = {}
                )
            }
        }

        composeRule.onNodeWithText("How can I help with your fitness today?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("FitDesi").assertIsDisplayed()
        composeRule.onNodeWithText("Build muscle").assertIsDisplayed()
        composeRule.onNodeWithText("Message FitDesi Coach").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_coach_submit").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Conversation history").performClick()
        composeRule.onNodeWithContentDescription("New chat").performClick()
        assertEquals(1, historyClicks)
        assertEquals(1, newChatClicks)
    }

    @Test
    fun `timeline loading workout card save and consent actions are accessible`() {
        var savedMessage = ""
        var consentClicks = 0
        var keepLocalClicks = 0
        val workout = workoutResponse()
        val state = AiCoachUiState(
            question = "Follow-up",
            isLoading = true,
            messages = listOf(
                AiCoachMessage("user-1", AiCoachMessageRole.USER, 1L, "Build me a plan"),
                AiCoachMessage(
                    "coach-1", AiCoachMessageRole.COACH, 2L, workout.summary,
                    AiCoachMessageSource.REMOTE, workout
                )
            ),
            messageSaveStates = mapOf("coach-1" to CoachPlanSaveState.NONE),
            remoteAction = CoachRemoteAction.CONSENT_REQUIRED
        )
        composeRule.setContent {
            MyPersonalTrainerTheme(theme = "Light") {
                AiCoachChatContent(
                    state = state,
                    runtimeMode = AiRuntimeMode.BACKEND_REMOTE,
                    onBack = {},
                    onQuestionChange = {},
                    onSubmit = {},
                    onRetry = {},
                    onHistory = {},
                    onNewChat = {},
                    onSaveMessage = { savedMessage = it },
                    onGrantConsent = { consentClicks += 1 },
                    onKeepLocal = { keepLocalClicks += 1 },
                    onViewSavedDietPlan = {}
                )
            }
        }

        composeRule.onNodeWithTag("ai_coach_timeline")
            .performScrollToNode(hasTestTag("coach_user_user-1"))
        composeRule.onNodeWithTag("coach_user_user-1").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_coach_timeline")
            .performScrollToNode(hasTestTag("coach_message_coach-1"))
        composeRule.onNodeWithTag("coach_message_coach-1").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_coach_submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("save_workout_plan").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_coach_timeline")
            .performScrollToNode(hasTestTag("ai_coach_loading"))
        composeRule.onNodeWithTag("ai_coach_loading").assertIsDisplayed()
        composeRule.onNodeWithText("Preparing your response…").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_coach_timeline")
            .performScrollToNode(hasTestTag("coach_remote_action"))
        composeRule.onNodeWithTag("coach_grant_remote_consent").performClick()
        composeRule.onNodeWithTag("coach_keep_local").performClick()
        assertEquals("coach-1", savedMessage)
        assertEquals(1, consentClicks)
        assertEquals(1, keepLocalClicks)
    }

    @Test
    fun `thinking indicator follows the authoritative loading state`() {
        val state = androidx.compose.runtime.mutableStateOf(
            AiCoachUiState(
                isLoading = true,
                messages = listOf(
                    AiCoachMessage("user-loading", AiCoachMessageRole.USER, 1L, "Help me train")
                )
            )
        )
        composeRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                AiCoachChatContent(
                    state = state.value,
                    runtimeMode = AiRuntimeMode.LOCAL,
                    onBack = {},
                    onQuestionChange = {},
                    onSubmit = {},
                    onRetry = {},
                    onHistory = {},
                    onNewChat = {},
                    onSaveMessage = {},
                    onGrantConsent = {},
                    onKeepLocal = {},
                    onViewSavedDietPlan = {}
                )
            }
        }

        composeRule.onNodeWithTag("ai_coach_loading").assertIsDisplayed()
        composeRule.onNodeWithText("Preparing your response…").assertIsDisplayed()

        composeRule.runOnIdle { state.value = state.value.copy(isLoading = false) }

        composeRule.onNodeWithTag("ai_coach_loading").assertDoesNotExist()
        composeRule.onNodeWithText("Preparing your response…").assertDoesNotExist()
    }

    @Test
    fun `history sheet shows current conversation selection delete and clear actions`() {
        var selected = ""
        var deleted = ""
        var clearClicks = 0
        val state = AiCoachUiState(
            activeConversationId = "chat-1",
            conversationHistory = listOf(
                AiCoachConversationSummary("chat-1", "Build muscle safely", 1_700_000_000_000L, true),
                AiCoachConversationSummary("chat-2", "Pakistani meal ideas", 1_699_000_000_000L, false)
            )
        )
        composeRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                CoachHistorySheet(
                    state = state,
                    onDismiss = {},
                    onSelect = { selected = it },
                    onDelete = { deleted = it },
                    onClear = { clearClicks += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Coach history").assertIsDisplayed()
        composeRule.onNodeWithText("Pakistani meal ideas").performClick()
        assertEquals("chat-2", selected)
        composeRule.onNodeWithContentDescription("Delete Build muscle safely").performClick()
        assertEquals("chat-1", deleted)
        composeRule.onNodeWithText("Clear all").performClick()
        assertEquals(1, clearClicks)
    }

    @Test
    fun `diet response renders its message-specific save action`() {
        val diet = AiCoachResponse(
            summary = "Here is a practical meal plan.",
            recommendedAction = "Prepare breakfast.",
            nutritionNote = "Use measured portions.",
            workoutNote = "Keep training steady.",
            safetyDisclaimer = "General education only.",
            dietPlan = GeneratedDietPlan(
                planId = "diet-plan",
                title = "South Asian meal plan",
                goal = "Balanced nutrition",
                calorieTarget = null,
                proteinTargetGrams = null,
                carbsTargetGrams = null,
                fatTargetGrams = null,
                days = listOf(
                    GeneratedDietPlanDay(
                        "Daily",
                        listOf("Breakfast", "Dinner").mapIndexed { index, label ->
                            GeneratedDietPlanMeal(
                                label, "food-$index", "Food $index", "1 serving", 1.0,
                                null, null, null, null, emptyList()
                            )
                        }
                    )
                ),
                hydrationReminder = "Drink water.",
                disclaimer = "General guidance only.",
                createdAt = 1L,
                sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
                profileContextUsed = false
            )
        )
        composeRule.setContent {
            MyPersonalTrainerTheme(theme = "Dark") {
                AiCoachChatContent(
                    state = AiCoachUiState(
                        messages = listOf(
                            AiCoachMessage(
                                "diet-message", AiCoachMessageRole.COACH, 1L, diet.summary,
                                AiCoachMessageSource.LOCAL, diet
                            )
                        )
                    ),
                    runtimeMode = AiRuntimeMode.LOCAL,
                    onBack = {}, onQuestionChange = {}, onSubmit = {}, onRetry = {},
                    onHistory = {}, onNewChat = {}, onSaveMessage = {},
                    onGrantConsent = {}, onKeepLocal = {}, onViewSavedDietPlan = {}
                )
            }
        }

        composeRule.onNodeWithTag("ai_coach_timeline")
            .performScrollToNode(hasTestTag("coach_message_diet-message"))
        composeRule.onNodeWithTag("save_diet_plan").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    private fun workoutResponse(): AiCoachResponse = AiCoachResponse(
        summary = "Here is a two-day plan.",
        recommendedAction = "Start with Day 1.",
        nutritionNote = "Eat balanced meals.",
        workoutNote = "Use controlled repetitions.",
        safetyDisclaimer = "General education only.",
        workoutPlan = GeneratedWorkoutPlan(
            planId = "workout-plan",
            title = "Two-day strength plan",
            goal = "Build muscle",
            experienceLevel = "Beginner",
            days = listOf("Day 1", "Day 2").map { day ->
                GeneratedWorkoutPlanDay(
                    dayName = day,
                    focus = "Full body",
                    exercises = listOf(
                        GeneratedWorkoutPlanExercise("0257", "Squat", "squat", 3, "8 reps", 60)
                    )
                )
            },
            progressionGuidance = "Progress gradually.",
            recoveryGuidance = "Rest between sessions.",
            safetyNote = "Stop for pain.",
            createdAt = 1L,
            sourceType = GeneratedPlanSource.REMOTE,
            profileContextUsed = false
        )
    )
}
