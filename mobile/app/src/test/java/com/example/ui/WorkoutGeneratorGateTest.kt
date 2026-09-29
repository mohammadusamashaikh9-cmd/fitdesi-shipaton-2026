package com.example.ui

import com.example.ai.AiWorkoutRequest
import com.example.boost.BoostAccessState
import com.example.subscription.EffectiveCapabilityPolicy
import com.example.subscription.SubscriptionCapability
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutGeneratorGateTest {
    @Test
    fun `Basic can configure every intermediate Generator step without a capability check`() {
        var advances = 0
        var capabilityChecks = 0
        var paywallRequests = 0
        var generationRequests = 0

        (1..5).forEach { step ->
            handleGeneratorNextAction(
                currentStep = step,
                input = validInput(),
                hasAiWorkoutGenerationCapability = {
                    capabilityChecks += 1
                    false
                },
                onValidationMessage = { assertNull(it) },
                onAdvance = { advances += 1 },
                onRequiresPlus = { paywallRequests += 1 },
                onGenerate = { generationRequests += 1 }
            )
        }

        assertEquals(5, advances)
        assertEquals(0, capabilityChecks)
        assertEquals(0, paywallRequests)
        assertEquals(0, generationRequests)
    }

    @Test
    fun `invalid final input stops before capability and paywall checks`() {
        var validationMessage: String? = null
        var capabilityChecks = 0
        var paywallRequests = 0
        var generationRequests = 0

        handleGeneratorNextAction(
            currentStep = 6,
            input = validInput().copy(equipment = emptyList()),
            hasAiWorkoutGenerationCapability = {
                capabilityChecks += 1
                false
            },
            onValidationMessage = { validationMessage = it },
            onAdvance = { error("Final input must not advance the wizard") },
            onRequiresPlus = { paywallRequests += 1 },
            onGenerate = { generationRequests += 1 }
        )

        assertEquals("Select at least one available equipment option.", validationMessage)
        assertEquals(0, capabilityChecks)
        assertEquals(0, paywallRequests)
        assertEquals(0, generationRequests)
    }

    @Test
    fun `valid Basic final action requests Plus without entering generating or changing draft`() {
        val draft = validInput()
        var currentStage = "WIZARD"
        var validationMessage: String? = "old error"
        var paywallRequests = 0
        var generationRequests = 0

        handleGeneratorNextAction(
            currentStep = 6,
            input = draft,
            hasAiWorkoutGenerationCapability = { false },
            onValidationMessage = { validationMessage = it },
            onAdvance = { error("Final input must not advance the wizard") },
            onRequiresPlus = { paywallRequests += 1 },
            onGenerate = {
                generationRequests += 1
                currentStage = "GENERATING"
            }
        )

        assertNull(validationMessage)
        assertEquals(1, paywallRequests)
        assertEquals(0, generationRequests)
        assertEquals("WIZARD", currentStage)
        assertEquals(validInput(), draft)
    }

    @Test
    fun `Boost activation alone does not generate and deliberate second action generates once`() {
        var boostAccess: BoostAccessState = BoostAccessState.Inactive
        var paywallRequests = 0
        var generationRequests = 0
        val basic = SubscriptionState(
            tier = SubscriptionTier.BASIC,
            status = SubscriptionStatus.READY,
            hasAuthoritativeCustomerInfo = true
        )
        val attempt = {
            handleGeneratorNextAction(
                currentStep = 6,
                input = validInput(),
                hasAiWorkoutGenerationCapability = {
                    EffectiveCapabilityPolicy.hasCapability(
                        basic,
                        boostAccess,
                        SubscriptionCapability.AI_WORKOUT_GENERATION,
                        nowEpochMillis = 1_000L
                    )
                },
                onValidationMessage = {},
                onAdvance = { error("Final input must not advance the wizard") },
                onRequiresPlus = { paywallRequests += 1 },
                onGenerate = { generationRequests += 1 }
            )
        }

        attempt()
        boostAccess = BoostAccessState.Active(expiresAtEpochMillis = 2_000L)
        assertEquals(0, generationRequests)

        attempt()

        assertEquals(1, paywallRequests)
        assertEquals(1, generationRequests)
    }

    private fun validInput() = AiWorkoutRequest(
        generatorType = "Weekly Routine",
        gender = "Male",
        age = 30,
        level = "Intermediate",
        goal = "Gain Muscle",
        daysCount = 3,
        selectedDays = listOf("Mon", "Wed", "Fri"),
        split = "Push / Pull / Legs",
        enforceRecovery = true,
        equipment = listOf("Dumbbells", "Bodyweight")
    )
}
