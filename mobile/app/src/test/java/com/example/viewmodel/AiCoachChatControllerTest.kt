package com.example.viewmodel

import com.example.ai.AiCoachExecutionState
import com.example.ai.AiCoachOutcome
import com.example.ai.AiCoachRequest
import com.example.ai.AiCoachConversationRole
import com.example.ai.AiCoachResponse
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedDietPlanDay
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import com.example.ai.backend.BackendPublicError
import com.example.ai.backend.BackendResult
import com.example.ai.backend.RemoteAiConsentDecision
import com.example.ai.backend.RemoteAiConsentDecisionStatus
import com.example.ai.backend.RemoteAiConsentNoticeVersions
import com.example.ai.backend.RemoteAiConsentState
import com.example.ai.conversation.AiCoachConversationRepository
import com.example.ai.conversation.AiCoachConversationStorage
import com.example.ai.conversation.AiCoachConversation
import com.example.ai.conversation.AiCoachMessage
import com.example.ai.conversation.AiCoachMessageRole
import com.example.ai.conversation.AiCoachMessageSource
import com.example.ai.conversation.AiCoachConversationOwner
import com.example.data.SavedPlanResult
import com.example.identity.AuthSessionState
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachChatControllerTest {
    @Test
    fun `initializing waits for authenticated ownership without exposing Guest history or draft`() = runTest {
        val repository = AiCoachConversationRepository(FakeStorage())
        repository.upsert(
            AiCoachConversationOwner.Guest,
            ownedConversation(repository, AiCoachConversationOwner.Guest, "guest", "Guest private text")
        ).getOrThrow()
        val account = AiCoachConversationOwner.Account("account-a")
        repository.upsert(account, ownedConversation(repository, account, "account-a", "Account A text"))
            .getOrThrow()
        val auth = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
        val controller = controller(repository, authSession = auth)
        runCurrent()

        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(controller.state.value.conversationHistory.isEmpty())
        controller.updateDraft("Unsent initializing draft")
        auth.value = authenticated("account-a")
        runCurrent()

        assertEquals(listOf("Account A text"), controller.state.value.messages.map { it.text })
        assertEquals("", controller.state.value.question)
        assertFalse(controller.state.value.messages.any { it.text.contains("Guest") })
    }

    @Test
    fun `Guest to authenticated owner transition clears Guest text and unsent draft`() = runTest {
        val repository = AiCoachConversationRepository(FakeStorage())
        repository.upsert(
            AiCoachConversationOwner.Guest,
            ownedConversation(repository, AiCoachConversationOwner.Guest, "guest", "Guest private text")
        ).getOrThrow()
        val account = AiCoachConversationOwner.Account("account-a")
        repository.upsert(account, ownedConversation(repository, account, "account-a", "Account A text"))
            .getOrThrow()
        val auth = MutableStateFlow<AuthSessionState>(AuthSessionState.Guest)
        val controller = controller(repository, authSession = auth)
        runCurrent()
        controller.updateDraft("Guest unsent draft")

        auth.value = authenticated("account-a")
        runCurrent()

        assertEquals(listOf("Account A text"), controller.state.value.messages.map { it.text })
        assertEquals("", controller.state.value.question)
        assertFalse(controller.state.value.messages.any { it.text.contains("Guest") })
    }

    @Test
    fun `account change clears prior account text and unsent draft`() = runTest {
        val repository = AiCoachConversationRepository(FakeStorage())
        val accountA = AiCoachConversationOwner.Account("account-a")
        val accountB = AiCoachConversationOwner.Account("account-b")
        repository.upsert(accountA, ownedConversation(repository, accountA, "a", "Account A private text"))
            .getOrThrow()
        repository.upsert(accountB, ownedConversation(repository, accountB, "b", "Account B text"))
            .getOrThrow()
        val auth = MutableStateFlow<AuthSessionState>(authenticated("account-a"))
        val controller = controller(repository, authSession = auth)
        runCurrent()
        controller.updateDraft("Account A unsent draft")

        auth.value = authenticated("account-b")
        runCurrent()

        assertEquals(listOf("Account B text"), controller.state.value.messages.map { it.text })
        assertEquals("", controller.state.value.question)
        assertFalse(controller.state.value.messages.any { it.text.contains("Account A") })
    }

    @Test
    fun `new chat preserves history and selecting and deleting conversations is owner scoped`() = runTest {
        val repository = AiCoachConversationRepository(FakeStorage())
        val controller = controller(repository)
        runCurrent()

        val firstId = controller.state.value.activeConversationId
        controller.updateDraft("First conversation")
        controller.send()
        runCurrent()
        controller.newChat()
        runCurrent()
        val secondId = controller.state.value.activeConversationId

        assertNotEquals(firstId, secondId)
        assertEquals(2, controller.state.value.conversationHistory.size)
        assertEquals("First conversation", controller.state.value.conversationHistory.last().title)
        controller.selectConversation(firstId!!)
        runCurrent()
        assertEquals("First conversation", controller.state.value.messages.first().text)
        controller.selectConversation(secondId!!)

        controller.deleteConversation(firstId)
        runCurrent()
        assertFalse(controller.state.value.conversationHistory.any { it.conversationId == firstId })
        assertEquals(secondId, controller.state.value.activeConversationId)

        controller.clearHistory()
        runCurrent()
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals(1, controller.state.value.conversationHistory.size)
    }

    @Test
    fun `send persists ordered user and Coach turns and restores after recreation`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val first = controller(repository)
        runCurrent()

        first.updateDraft("How should I train?")
        first.send()
        runCurrent()

        assertEquals(listOf(AiCoachMessageRole.USER, AiCoachMessageRole.COACH), first.state.value.messages.map { it.role })
        assertEquals("", first.state.value.question)

        val recreated = controller(AiCoachConversationRepository(storage))
        runCurrent()
        assertEquals(first.state.value.messages, recreated.state.value.messages)
    }

    @Test
    fun `consent action never replays the blocked request automatically`() = runTest {
        val repository = AiCoachConversationRepository(FakeStorage())
        var requests = 0
        val consent = FakeConsentActions()
        val controller = controller(
            repository = repository,
            responder = CoachQuestionResponder {
                requests += 1
                AiCoachOutcome(
                    response = response("Local fallback"),
                    state = AiCoachExecutionState.LOCAL_FALLBACK,
                    backendFailureState = AiCoachExecutionState.BACKEND_UNAVAILABLE,
                    backendError = BackendPublicError.REMOTE_AI_CONSENT_REQUIRED,
                    message = "A local response was used."
                )
            },
            consentActions = consent
        )
        runCurrent()

        controller.updateDraft("Use remote Coach")
        controller.send()
        runCurrent()
        assertEquals(CoachRemoteAction.CONSENT_REQUIRED, controller.state.value.remoteAction)
        assertEquals(
            AiCoachMessageSource.LOCAL_FALLBACK,
            controller.state.value.messages.last().source
        )

        controller.grantRemoteConsent()
        runCurrent()

        assertEquals(1, requests)
        assertEquals(1, consent.grants)
        assertEquals(2, controller.state.value.messages.size)
        assertEquals(null, controller.state.value.remoteAction)
        assertTrue(controller.state.value.backendNotice.orEmpty().contains("Send again"))
    }

    @Test
    fun `older workout and diet messages save their own plans with independent state`() = runTest {
        val responses = ArrayDeque(listOf(
            workoutResponse("workout-old"),
            dietResponse("diet-old"),
            response("Newest general answer")
        ))
        val savedIds = mutableListOf<String>()
        val controller = controller(
            repository = AiCoachConversationRepository(FakeStorage()),
            responder = CoachQuestionResponder {
                AiCoachOutcome(responses.removeFirst(), AiCoachExecutionState.LOCAL_SUCCESS)
            },
            savePlan = { response ->
                savedIds += response.workoutPlan?.planId ?: response.dietPlan!!.planId
                SavedPlanResult.SAVED
            }
        )
        runCurrent()
        controller.updateDraft("Workout please")
        controller.send()
        runCurrent()
        controller.updateDraft("Diet please")
        controller.send()
        runCurrent()
        controller.updateDraft("One more question")
        controller.send()
        runCurrent()

        val coachMessages = controller.state.value.messages.filter { it.role == AiCoachMessageRole.COACH }
        val oldWorkoutMessage = coachMessages.first()
        val oldDietMessage = coachMessages[1]
        val newestMessage = coachMessages.last()
        controller.saveMessagePlan(oldWorkoutMessage.messageId)
        controller.saveMessagePlan(oldDietMessage.messageId)
        runCurrent()

        assertEquals(listOf("workout-old", "diet-old"), savedIds)
        assertEquals(CoachPlanSaveState.SAVED, controller.state.value.messageSaveStates[oldWorkoutMessage.messageId])
        assertEquals(CoachPlanSaveState.SAVED, controller.state.value.messageSaveStates[oldDietMessage.messageId])
        assertEquals(CoachPlanSaveState.NONE, controller.state.value.messageSaveStates[newestMessage.messageId] ?: CoachPlanSaveState.NONE)
    }

    @Test
    fun `bounded context excludes the current question and contains prior turns`() = runTest {
        val captured = mutableListOf<AiCoachRequest>()
        val controller = controller(
            repository = AiCoachConversationRepository(FakeStorage()),
            responder = CoachQuestionResponder { request ->
                captured += request
                AiCoachOutcome(response("Answer ${captured.size}"), AiCoachExecutionState.LOCAL_SUCCESS)
            }
        )
        runCurrent()
        controller.updateDraft("Question one")
        controller.send()
        runCurrent()
        controller.updateDraft("Question two")
        controller.send()
        runCurrent()

        assertTrue(captured.first().conversationContext.isEmpty())
        assertEquals(
            listOf(AiCoachConversationRole.USER, AiCoachConversationRole.ASSISTANT),
            captured.last().conversationContext.map { it.role }
        )
        assertFalse(captured.last().conversationContext.any { it.text == "Question two" })
    }

    @Test
    fun `unexpected responder exception clears loading and exposes no exception text`() = runTest {
        val controller = controller(
            repository = AiCoachConversationRepository(FakeStorage()),
            responder = CoachQuestionResponder { error("private provider diagnostic") }
        )
        runCurrent()

        controller.updateDraft("Help me train")
        controller.send()
        runCurrent()

        assertFalse(controller.state.value.isLoading)
        assertEquals(AiCoachExecutionState.UNKNOWN_ERROR, controller.state.value.executionState)
        assertEquals(1, controller.state.value.messages.size)
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("could not prepare"))
        assertFalse(controller.state.value.errorMessage.orEmpty().contains("private provider"))
        assertFalse(controller.state.value.backendNotice.orEmpty().contains("private provider"))
    }

    @Test
    fun `repeated new chat on empty conversation does not persist empty duplicates`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val first = controller(repository)
        runCurrent()
        first.updateDraft("Meaningful conversation")
        first.send()
        runCurrent()
        first.newChat()
        runCurrent()
        val intendedEmptyId = first.state.value.activeConversationId

        repeat(4) { first.newChat() }
        runCurrent()
        val recreated = controller(AiCoachConversationRepository(storage))
        runCurrent()

        assertEquals(2, recreated.state.value.conversationHistory.size)
        assertEquals(intendedEmptyId, recreated.state.value.activeConversationId)
        assertEquals(1, recreated.state.value.conversationHistory.count {
            it.title == AiCoachConversationRepository.DEFAULT_TITLE
        })
        assertTrue(recreated.state.value.messages.isEmpty())
    }

    private fun TestScope.controller(
        repository: AiCoachConversationRepository,
        authSession: MutableStateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.Guest),
        responder: CoachQuestionResponder = CoachQuestionResponder {
            AiCoachOutcome(response("Coach answer"), AiCoachExecutionState.LOCAL_SUCCESS)
        },
        consentActions: CoachConsentActions = FakeConsentActions(),
        savePlan: suspend (AiCoachResponse) -> SavedPlanResult = { SavedPlanResult.SAVED }
    ) = AiCoachChatController(
        scope = backgroundScope,
        conversationRepository = repository,
        authSession = authSession,
        responder = responder,
        consentActions = consentActions,
        savePlan = savePlan,
        clock = object {
            var value = 100L
            fun next() = value++
        }::next,
        idFactory = object {
            var value = 0
            fun next() = "id-${value++}"
        }::next
    )

    private fun ownedConversation(
        repository: AiCoachConversationRepository,
        owner: AiCoachConversationOwner,
        id: String,
        text: String
    ) = AiCoachConversation(
        conversationId = id,
        ownerNamespace = repository.ownerNamespace(owner),
        title = text,
        createdAt = 1L,
        updatedAt = 1L,
        messages = listOf(
            AiCoachMessage(
                messageId = "message-$id",
                role = AiCoachMessageRole.USER,
                timestamp = 1L,
                text = text
            )
        )
    )

    private fun authenticated(uid: String) = AuthSessionState.Authenticated(
        uid = uid,
        email = "$uid@example.com",
        emailVerified = true
    )

    private class FakeConsentActions : CoachConsentActions {
        var grants = 0
        override suspend fun fetch(): BackendResult<RemoteAiConsentState> = BackendResult.Success(
            value = consentState(),
            requestId = "consent-request"
        )

        override suspend fun grantStandard(noticeVersion: String): BackendResult<Unit> {
            grants += 1
            return BackendResult.Success(Unit, requestId = "grant-request")
        }

        override suspend fun declineStandard(): BackendResult<Unit> =
            BackendResult.Success(Unit, requestId = "decline-request")
    }

    private class FakeStorage : AiCoachConversationStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun read(ownerNamespace: String): String? = values[ownerNamespace]
        override suspend fun writeAtomically(ownerNamespace: String, value: String) {
            values[ownerNamespace] = value
        }
        override suspend fun delete(ownerNamespace: String) {
            values.remove(ownerNamespace)
        }
    }

    private companion object {
        fun response(summary: String) = AiCoachResponse(
            summary = summary,
            recommendedAction = "Take one practical action.",
            nutritionNote = "Use balanced meals.",
            workoutNote = "Train with control.",
            safetyDisclaimer = "General education only."
        )

        fun workoutResponse(id: String) = response("Workout").copy(
            workoutPlan = GeneratedWorkoutPlan(
                planId = id,
                title = "Two-day plan",
                goal = "General fitness",
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
                sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
                profileContextUsed = false
            )
        )

        fun dietResponse(id: String) = response("Diet").copy(
            dietPlan = GeneratedDietPlan(
                planId = id,
                title = "Meal plan",
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

        fun consentState() = RemoteAiConsentState(
            schemaVersion = 1,
            standardRemoteAi = RemoteAiConsentDecision(
                granted = false,
                current = false,
                noticeVersion = null,
                decidedAt = null,
                status = RemoteAiConsentDecisionStatus.NOT_DECIDED
            ),
            experimentalTraining = RemoteAiConsentDecision(
                granted = false,
                current = false,
                noticeVersion = null,
                decidedAt = null,
                status = RemoteAiConsentDecisionStatus.NOT_DECIDED
            ),
            requiredNoticeVersions = RemoteAiConsentNoticeVersions("standard-v1", "experimental-v1"),
            updatedAt = null
        )
    }
}
