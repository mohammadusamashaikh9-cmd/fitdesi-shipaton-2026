package com.example.ai.conversation

import com.example.ai.AiCoachResponse
import com.example.ai.AiCoachResponseMetadata
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedDietPlanDay
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachConversationContextTest {
    @Test
    fun `context keeps only six recent prior messages with strict roles and bounds`() {
        val messages = List(9) { index ->
            AiCoachMessage(
                messageId = "message-$index",
                role = if (index % 2 == 0) AiCoachMessageRole.USER else AiCoachMessageRole.COACH,
                timestamp = index.toLong(),
                text = "Message $index " + "x".repeat(600)
            )
        }

        val context = AiCoachConversationContext.build(messages)

        assertEquals(6, context.size)
        assertEquals(
            listOf("assistant", "user", "assistant", "user", "assistant", "user"),
            context.map { it.role.name.lowercase() }
        )
        assertTrue(context.all { it.text.length <= AiCoachConversationContext.MAX_MESSAGE_CHARACTERS })
        assertTrue(context.sumOf { it.text.length } <= AiCoachConversationContext.MAX_AGGREGATE_CHARACTERS)
        assertEquals(
            AiCoachConversationContext.MAX_MESSAGE_CHARACTERS,
            AiCoachConversationContext.build(
                listOf(messages.last().copy(text = "x".repeat(2_000)))
            ).single().text.length
        )
    }

    @Test
    fun `validated structured diet is represented compactly without provider or raw content`() {
        val response = AiCoachResponse(
            summary = "A practical meal plan.",
            recommendedAction = "Prepare breakfast.",
            nutritionNote = "Use measured portions.",
            workoutNote = "Keep training steady.",
            safetyDisclaimer = "General education only.",
            metadata = AiCoachResponseMetadata(
                sourceType = "FIREWORKS",
                warnings = listOf("rawResponse provider model token")
            ),
            dietPlan = dietPlan()
        )
        val message = AiCoachMessage(
            messageId = "coach-plan",
            role = AiCoachMessageRole.COACH,
            timestamp = 1L,
            text = response.summary,
            source = AiCoachMessageSource.REMOTE,
            response = response
        )

        val text = AiCoachConversationContext.build(listOf(message)).single().text

        assertTrue(text.contains("Diet plan: Pakistani meal plan"))
        assertTrue(text.contains("Breakfast: Anda (food-0257)"))
        for (forbidden in listOf("FIREWORKS", "provider", "model", "token", "rawResponse")) {
            assertFalse(text.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `invalid structured plan is not included in conversational context`() {
        val invalid = dietPlan().copy(days = emptyList())
        val response = AiCoachResponse(
            summary = "Use balanced meals.",
            recommendedAction = "Plan one meal.",
            nutritionNote = "Nutrition guidance.",
            workoutNote = "Training guidance.",
            safetyDisclaimer = "General education only.",
            dietPlan = invalid
        )

        val text = AiCoachConversationContext.build(
            listOf(
                AiCoachMessage(
                    "coach-invalid", AiCoachMessageRole.COACH, 1L, response.summary,
                    AiCoachMessageSource.REMOTE, response
                )
            )
        ).single().text

        assertFalse(text.contains("Diet plan:"))
    }

    @Test
    fun `validated workout context preserves exact canonical ids names and prescriptions`() {
        val response = AiCoachResponse(
            summary = "Here is your plan.",
            recommendedAction = "Start with Day 1.",
            nutritionNote = "Eat balanced meals.",
            workoutNote = "Use controlled technique.",
            safetyDisclaimer = "General education only.",
            workoutPlan = GeneratedWorkoutPlan(
                planId = "workout-plan",
                title = "Two-day strength",
                goal = "Build muscle",
                experienceLevel = "Beginner",
                days = listOf(
                    GeneratedWorkoutPlanDay(
                        "Day 1", "Lower body",
                        listOf(GeneratedWorkoutPlanExercise("0257", "Exercise 0257", "squat", 3, "8 reps", 60))
                    ),
                    GeneratedWorkoutPlanDay(
                        "Day 2", "Upper body",
                        listOf(GeneratedWorkoutPlanExercise("0643", "Exercise 0643", "push", 2, "10 reps", 45))
                    )
                ),
                progressionGuidance = "Progress gradually.",
                recoveryGuidance = "Rest between sessions.",
                safetyNote = "Stop for pain.",
                createdAt = 1L,
                sourceType = GeneratedPlanSource.REMOTE,
                profileContextUsed = false
            )
        )

        val text = AiCoachConversationContext.build(listOf(
            AiCoachMessage(
                "coach-workout", AiCoachMessageRole.COACH, 1L, response.summary,
                AiCoachMessageSource.REMOTE, response
            )
        )).single().text

        assertTrue(text.contains("Exercise 0257 (0257), 3 x 8 reps, 60s rest"))
        assertTrue(text.contains("Exercise 0643 (0643), 2 x 10 reps, 45s rest"))
    }

    private fun dietPlan() = GeneratedDietPlan(
        planId = "diet-plan",
        title = "Pakistani meal plan",
        goal = "Balanced nutrition",
        calorieTarget = null,
        proteinTargetGrams = null,
        carbsTargetGrams = null,
        fatTargetGrams = null,
        days = listOf(
            GeneratedDietPlanDay(
                dayName = "Daily plan",
                meals = listOf(
                    GeneratedDietPlanMeal(
                        label = "Breakfast",
                        foodRecordId = "food-0257",
                        foodName = "Anda",
                        storedServing = "2 eggs",
                        portionMultiplier = 1.0,
                        estimatedCalories = null,
                        estimatedProteinGrams = null,
                        estimatedCarbsGrams = null,
                        estimatedFatGrams = null,
                        alternatives = emptyList()
                    ),
                    GeneratedDietPlanMeal(
                        label = "Dinner",
                        foodRecordId = "food-0643",
                        foodName = "Daal",
                        storedServing = "1 bowl",
                        portionMultiplier = 1.0,
                        estimatedCalories = null,
                        estimatedProteinGrams = null,
                        estimatedCarbsGrams = null,
                        estimatedFatGrams = null,
                        alternatives = emptyList()
                    )
                )
            )
        ),
        hydrationReminder = "Drink water.",
        disclaimer = "General nutrition guidance only.",
        createdAt = 1L,
        sourceType = GeneratedPlanSource.REMOTE,
        profileContextUsed = false
    )
}
