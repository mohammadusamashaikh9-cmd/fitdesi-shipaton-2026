package com.example.ai

import com.example.ai.knowledge.CoachContext
import com.example.ai.knowledge.FitnessKnowledgeRetriever
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiCoachProviderTest {
    @Test
    fun `offline response remains structured`() = runTest {
        val response = LocalAiCoachProvider.ask("How should I progress my squat?").getOrThrow()

        assertTrue(response.summary.isNotBlank())
        assertTrue(response.recommendedAction.isNotBlank())
        assertTrue(response.nutritionNote.isNotBlank())
        assertTrue(response.workoutNote.isNotBlank())
        assertTrue(response.safetyDisclaimer.contains("not medical", ignoreCase = true))
    }

    @Test
    fun `urgent red flags are not diagnosed and escalate to professional care`() {
        val response = createOfflineResponse("I have chest pain and feel faint")

        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, response.metadata.intent)
        assertTrue(response.recommendedAction.contains("urgent", ignoreCase = true))
        assertTrue(response.workoutNote.contains("assessed", ignoreCase = true))
    }

    @Test
    fun `blank input returns friendly validation failure`() = runTest {
        val result = LocalAiCoachProvider.ask("   ")

        assertTrue(result.isFailure)
        assertEquals("Enter a fitness question to continue.", result.exceptionOrNull()?.message)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("Exception"))
    }

    @Test
    fun `context aware response uses goal equipment and recent workout`() = runTest {
        val response = LocalAiCoachProvider.ask(
            question = "How should I progress my squat?",
            context = CoachContext(
                goal = "Get Stronger",
                experience = "Beginner",
                equipment = setOf("Barbell"),
                recentWorkoutSummary = "Squats and lunges yesterday"
            )
        ).getOrThrow()

        assertEquals(AiCoachIntent.EXERCISE_QUESTION, response.metadata.intent)
        assertTrue(response.metadata.profileContextUsed)
        assertTrue(response.metadata.recentWorkoutContextUsed)
        assertTrue(response.recommendedAction.contains("Regressions"))
        assertTrue(response.recommendedAction.contains("Progressions"))
        assertTrue(response.safetyDisclaimer.contains("not medical", ignoreCase = true))
    }

    @Test
    fun `empty knowledge catalog falls back to safe structured coaching`() {
        val response = createOfflineResponse(
            question = "Help me build muscle",
            context = CoachContext(goal = "Gain Muscle"),
            retriever = FitnessKnowledgeRetriever.from(emptyList())
        )

        assertTrue(response.summary.contains("Grounded local coaching"))
        assertTrue(response.recommendedAction.isNotBlank())
        assertTrue(response.metadata.fallbackUsed)
        assertTrue(response.safetyDisclaimer.contains("not medical", ignoreCase = true))
    }

    @Test
    fun `fat loss diet request remains non medical and unsavable without complete profile`() {
        val response = createOfflineResponse(
            question = "I want a fat-loss diet",
            context = CoachContext(goal = "Lose Fat")
        )

        assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
        assertFalse(response.metadata.intent == AiCoachIntent.MEDICAL_ESCALATION)
        assertFalse(response.recommendedAction.contains("urgent medical", ignoreCase = true))
        assertEquals(PlanValidationStatus.NEEDS_PROFILE_INPUT.name, response.metadata.validationStatus)
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
    }
}
