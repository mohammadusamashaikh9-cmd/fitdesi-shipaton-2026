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
import com.example.ai.PlanValidationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachConversationRepositoryTest {
    @Test
    fun `conversations persist reload in message order and use a hashed account namespace`() = runTest {
        val storage = FakeStorage()
        val first = AiCoachConversationRepository(storage)
        val owner = AiCoachConversationOwner.Account("firebase-user-a")
        val namespace = first.ownerNamespace(owner)
        val conversation = conversation(
            id = "conversation-a",
            updatedAt = 20L,
            messages = listOf(userMessage("u1", 10L), coachMessage("c1", 20L))
        ).copy(ownerNamespace = namespace)

        assertTrue(first.upsert(owner, conversation).isSuccess)
        val restored = AiCoachConversationRepository(storage).load(owner).getOrThrow()

        assertEquals(listOf("u1", "c1"), restored.single().messages.map(AiCoachMessage::messageId))
        assertEquals(namespace, restored.single().ownerNamespace)
        assertFalse(namespace.contains("firebase-user-a"))
        assertTrue(namespace.startsWith("account-"))
    }

    @Test
    fun `conversation and message retention limits keep the newest bounded history`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val owner = AiCoachConversationOwner.Guest

        repeat(AiCoachConversationRepository.MAX_CONVERSATIONS + 3) { conversationIndex ->
            val messages = List(AiCoachConversationRepository.MAX_MESSAGES_PER_CONVERSATION + 5) { messageIndex ->
                userMessage("$conversationIndex-$messageIndex", messageIndex.toLong())
            }
            assertTrue(repository.upsert(
                owner,
                conversation(
                    id = "conversation-$conversationIndex",
                    updatedAt = conversationIndex.toLong(),
                    messages = messages
                ).copy(ownerNamespace = repository.ownerNamespace(owner))
            ).isSuccess)
        }

        val restored = repository.load(owner).getOrThrow()
        assertEquals(AiCoachConversationRepository.MAX_CONVERSATIONS, restored.size)
        assertEquals("conversation-${AiCoachConversationRepository.MAX_CONVERSATIONS + 2}", restored.first().conversationId)
        assertEquals(
            AiCoachConversationRepository.MAX_MESSAGES_PER_CONVERSATION,
            restored.first().messages.size
        )
        assertEquals("${AiCoachConversationRepository.MAX_CONVERSATIONS + 2}-5", restored.first().messages.first().messageId)
    }

    @Test
    fun `guest and authenticated accounts are isolated and delete clear stay owner scoped`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val guest = AiCoachConversationOwner.Guest
        val accountA = AiCoachConversationOwner.Account("account-a")
        val accountB = AiCoachConversationOwner.Account("account-b")

        assertTrue(repository.upsert(guest, ownedConversation(repository, guest, "guest-chat", 1L)).isSuccess)
        assertTrue(repository.upsert(accountA, ownedConversation(repository, accountA, "a-chat", 2L)).isSuccess)
        assertTrue(repository.upsert(accountB, ownedConversation(repository, accountB, "b-chat", 3L)).isSuccess)

        assertEquals(listOf("guest-chat"), repository.load(guest).getOrThrow().map(AiCoachConversation::conversationId))
        assertEquals(listOf("a-chat"), repository.load(accountA).getOrThrow().map(AiCoachConversation::conversationId))
        assertEquals(listOf("b-chat"), repository.load(accountB).getOrThrow().map(AiCoachConversation::conversationId))

        assertTrue(repository.delete(accountA, "a-chat").isSuccess)
        assertTrue(repository.load(accountA).getOrThrow().isEmpty())
        assertEquals(1, repository.load(accountB).getOrThrow().size)
        assertTrue(repository.clear(guest).isSuccess)
        assertTrue(repository.load(guest).getOrThrow().isEmpty())
        assertEquals(1, repository.load(accountB).getOrThrow().size)
    }

    @Test
    fun `corrupt storage fails closed and is not overwritten by an upsert`() = runTest {
        val storage = FakeStorage().apply { values["guest"] = "{not-json" }
        val repository = AiCoachConversationRepository(storage)

        assertTrue(repository.load(AiCoachConversationOwner.Guest).isFailure)
        assertTrue(repository.upsert(
            AiCoachConversationOwner.Guest,
            ownedConversation(repository, AiCoachConversationOwner.Guest, "new-chat", 1L)
        ).isFailure)
        assertEquals("{not-json", storage.values["guest"])
    }

    @Test
    fun `failed atomic replacement retains prior data and exposes no credential fields`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val owner = AiCoachConversationOwner.Account("private-firebase-uid")
        val first = ownedConversation(repository, owner, "first", 1L)
        assertTrue(repository.upsert(owner, first).isSuccess)
        val priorJson = storage.values.getValue(repository.ownerNamespace(owner))

        storage.failWrites = true
        assertTrue(repository.upsert(owner, ownedConversation(repository, owner, "second", 2L)).isFailure)

        assertEquals(priorJson, storage.values[repository.ownerNamespace(owner)])
        for (secretField in listOf(
            "private-firebase-uid", "Authorization", "Idempotency-Key", "idToken",
            "provider", "model", "rawResponse", "chainOfThought", "quota",
            "private-grounding-record", "server-request-private"
        )) {
            assertFalse(priorJson.contains(secretField, ignoreCase = true))
        }
        assertTrue(priorJson.toByteArray().size <= AiCoachConversationRepository.MAX_OWNER_FILE_BYTES)
    }

    @Test
    fun `bounded validation status round trips without upgrading needs adjustment`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val owner = AiCoachConversationOwner.Guest
        val conversation = conversation(
            id = "status-round-trip",
            updatedAt = 3L,
            messages = listOf(
                coachMessage("validated", 1L, workoutResponse()),
                coachMessage("adjustment", 2L, dietResponse(PlanValidationStatus.NEEDS_ADJUSTMENT))
            )
        ).copy(ownerNamespace = repository.ownerNamespace(owner))

        repository.upsert(owner, conversation).getOrThrow()
        val restored = AiCoachConversationRepository(storage).load(owner).getOrThrow().single()

        assertEquals("VALIDATED", restored.messages[0].response?.metadata?.validationStatus)
        assertEquals(
            PlanValidationStatus.VALIDATED,
            restored.messages[0].response?.workoutPlan?.let { PlanValidationStatus.VALIDATED }
        )
        assertEquals("NEEDS_ADJUSTMENT", restored.messages[1].response?.metadata?.validationStatus)
        assertEquals(
            PlanValidationStatus.NEEDS_ADJUSTMENT,
            restored.messages[1].response?.dietPlan?.validationStatus
        )
    }

    @Test
    fun `invalid persisted intent and validation status fail closed as corruption`() = runTest {
        val storage = FakeStorage()
        val repository = AiCoachConversationRepository(storage)
        val owner = AiCoachConversationOwner.Guest
        val conversation = conversation(
            id = "bounded-enums",
            updatedAt = 1L,
            messages = listOf(coachMessage("coach", 1L, workoutResponse()))
        ).copy(ownerNamespace = repository.ownerNamespace(owner))
        repository.upsert(owner, conversation).getOrThrow()
        val namespace = repository.ownerNamespace(owner)
        val valid = storage.values.getValue(namespace)

        storage.values[namespace] = valid.replace(
            "\"intent\":\"GENERAL_COACHING\"",
            "\"intent\":\"INVALID_INTENT\""
        )
        assertTrue(AiCoachConversationRepository(storage).load(owner).isFailure)

        storage.values[namespace] = valid.replace(
            "\"validationStatus\":\"VALIDATED\"",
            "\"validationStatus\":\"UNTRUSTED_STATUS\""
        )
        assertTrue(AiCoachConversationRepository(storage).load(owner).isFailure)
    }

    private fun ownedConversation(
        repository: AiCoachConversationRepository,
        owner: AiCoachConversationOwner,
        id: String,
        updatedAt: Long
    ) = conversation(id, updatedAt, listOf(userMessage("message-$id", updatedAt)))
        .copy(ownerNamespace = repository.ownerNamespace(owner))

    private fun conversation(
        id: String,
        updatedAt: Long,
        messages: List<AiCoachMessage>
    ) = AiCoachConversation(
        conversationId = id,
        ownerNamespace = "placeholder",
        title = "A deterministic local title",
        createdAt = 1L,
        updatedAt = updatedAt,
        messages = messages
    )

    private fun userMessage(id: String, timestamp: Long) = AiCoachMessage(
        messageId = id,
        role = AiCoachMessageRole.USER,
        timestamp = timestamp,
        text = "User message $id"
    )

    private fun coachMessage(
        id: String,
        timestamp: Long,
        response: AiCoachResponse = basicResponse()
    ) = AiCoachMessage(
        messageId = id,
        role = AiCoachMessageRole.COACH,
        timestamp = timestamp,
        text = "Coach message $id",
        source = AiCoachMessageSource.LOCAL,
        response = response
    )

    private fun basicResponse() = AiCoachResponse(
            summary = "Summary",
            recommendedAction = "Action",
            nutritionNote = "Nutrition",
            workoutNote = "Workout",
            safetyDisclaimer = "Safety",
            metadata = AiCoachResponseMetadata(
                sourceType = "LOCAL_KNOWLEDGE",
                knowledgeRecordIds = listOf("private-grounding-record"),
                requestId = "server-request-private",
                backendMode = "remote",
                warnings = listOf("rawResponse provider model token")
            )
        )

    private fun workoutResponse() = basicResponse().copy(
        metadata = basicResponse().metadata.copy(validationStatus = PlanValidationStatus.VALIDATED.name),
        workoutPlan = GeneratedWorkoutPlan(
            planId = "workout-plan",
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

    private fun dietResponse(status: PlanValidationStatus) = basicResponse().copy(
        metadata = basicResponse().metadata.copy(validationStatus = status.name),
        dietPlan = GeneratedDietPlan(
            planId = "diet-plan",
            title = "Two-meal plan",
            goal = "Balanced nutrition",
            calorieTarget = null,
            proteinTargetGrams = null,
            carbsTargetGrams = null,
            fatTargetGrams = null,
            days = listOf(
                GeneratedDietPlanDay(
                    dayName = "Daily",
                    meals = listOf("Breakfast", "Dinner").mapIndexed { index, label ->
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
            profileContextUsed = false,
            validationStatus = status,
            validationFailures = if (status == PlanValidationStatus.NEEDS_ADJUSTMENT) {
                listOf("Needs a bounded adjustment")
            } else {
                emptyList()
            }
        )
    )

    private class FakeStorage : AiCoachConversationStorage {
        val values = mutableMapOf<String, String>()
        var failWrites = false

        override suspend fun read(ownerNamespace: String): String? = values[ownerNamespace]

        override suspend fun writeAtomically(ownerNamespace: String, value: String) {
            if (failWrites) error("Simulated atomic write failure")
            values[ownerNamespace] = value
        }

        override suspend fun delete(ownerNamespace: String) {
            values.remove(ownerNamespace)
        }
    }
}
