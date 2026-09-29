package com.example.ai.conversation

import android.content.Context
import android.util.AtomicFile
import com.example.ai.AiCoachIntent
import com.example.ai.AiCoachResponse
import com.example.ai.AiCoachResponseMetadata
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.PlanValidationStatus
import com.example.ai.hasValidStructuredContracts
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface AiCoachConversationOwner {
    data object Guest : AiCoachConversationOwner
    data class Account(val firebaseUid: String) : AiCoachConversationOwner
}

enum class AiCoachMessageRole { USER, COACH }

enum class AiCoachMessageSource { LOCAL, REMOTE, LOCAL_FALLBACK, BACKEND_MOCK }

data class AiCoachMessage(
    val messageId: String,
    val role: AiCoachMessageRole,
    val timestamp: Long,
    val text: String,
    val source: AiCoachMessageSource? = null,
    val response: AiCoachResponse? = null
)

data class AiCoachConversation(
    val conversationId: String,
    val ownerNamespace: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<AiCoachMessage>
)

interface AiCoachConversationStorage {
    suspend fun read(ownerNamespace: String): String?
    suspend fun writeAtomically(ownerNamespace: String, value: String)
    suspend fun delete(ownerNamespace: String)
}

class AppPrivateAiCoachConversationStorage(
    private val rootDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AiCoachConversationStorage {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME)
    )

    override suspend fun read(ownerNamespace: String): String? = withContext(ioDispatcher) {
        val atomicFile = atomicFile(ownerNamespace)
        if (!atomicFile.baseFile.exists()) return@withContext null
        atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override suspend fun writeAtomically(ownerNamespace: String, value: String) = withContext(ioDispatcher) {
        rootDirectory.mkdirs()
        check(rootDirectory.isDirectory) { "Coach conversation directory is unavailable" }
        val atomicFile = atomicFile(ownerNamespace)
        val output = atomicFile.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    override suspend fun delete(ownerNamespace: String) = withContext(ioDispatcher) {
        atomicFile(ownerNamespace).delete()
    }

    private fun atomicFile(ownerNamespace: String): AtomicFile {
        require(OWNER_NAMESPACE_PATTERN.matches(ownerNamespace))
        return AtomicFile(File(rootDirectory, "$ownerNamespace.json"))
    }

    private companion object {
        const val DIRECTORY_NAME = "ai_coach_conversations_v1"
        val OWNER_NAMESPACE_PATTERN = Regex("[a-z0-9-]{5,80}")
    }
}

class AiCoachConversationRepository(
    private val storage: AiCoachConversationStorage,
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }
) {
    constructor(context: Context) : this(AppPrivateAiCoachConversationStorage(context))

    private val mutex = Mutex()

    fun ownerNamespace(owner: AiCoachConversationOwner): String = when (owner) {
        AiCoachConversationOwner.Guest -> "guest"
        is AiCoachConversationOwner.Account -> {
            require(owner.firebaseUid.isNotBlank())
            "account-${sha256(owner.firebaseUid).take(32)}"
        }
    }

    suspend fun load(owner: AiCoachConversationOwner): Result<List<AiCoachConversation>> =
        mutex.withLock { loadLocked(ownerNamespace(owner)) }

    suspend fun upsert(
        owner: AiCoachConversationOwner,
        conversation: AiCoachConversation
    ): Result<List<AiCoachConversation>> = mutex.withLock {
        val namespace = ownerNamespace(owner)
        if (conversation.ownerNamespace != namespace) {
            return@withLock Result.failure(IllegalArgumentException("Conversation owner mismatch"))
        }
        val existing = loadLocked(namespace).getOrElse { return@withLock Result.failure(it) }
        val updated = existing.filterNot { it.conversationId == conversation.conversationId } + conversation
        persistLocked(namespace, updated)
    }

    suspend fun delete(
        owner: AiCoachConversationOwner,
        conversationId: String
    ): Result<List<AiCoachConversation>> = mutex.withLock {
        val namespace = ownerNamespace(owner)
        val existing = loadLocked(namespace).getOrElse { return@withLock Result.failure(it) }
        persistLocked(namespace, existing.filterNot { it.conversationId == conversationId })
    }

    suspend fun clear(owner: AiCoachConversationOwner): Result<Unit> = mutex.withLock {
        runCatching { storage.delete(ownerNamespace(owner)) }
    }

    private suspend fun loadLocked(namespace: String): Result<List<AiCoachConversation>> = runCatching {
        val raw = storage.read(namespace)
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        check(raw.toByteArray(Charsets.UTF_8).size <= MAX_OWNER_FILE_BYTES) {
            "Coach history exceeds its local retention bound"
        }
        val envelope = json.decodeFromString(PersistedConversationEnvelope.serializer(), raw)
        check(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported Coach history version" }
        check(envelope.conversations.size <= MAX_CONVERSATIONS)
        check(envelope.conversations.all { conversation ->
            conversation.title.length <= MAX_TITLE_CHARACTERS &&
                conversation.messages.size <= MAX_MESSAGES_PER_CONVERSATION &&
                conversation.messages.all { it.text.length <= MAX_MESSAGE_CHARACTERS }
        })
        envelope.conversations.map { it.toDomain(namespace) }
            .sortedByDescending(AiCoachConversation::updatedAt)
    }

    private suspend fun persistLocked(
        namespace: String,
        conversations: List<AiCoachConversation>
    ): Result<List<AiCoachConversation>> = runCatching {
        val bounded = enforceFileBound(
            conversations
                .map { it.sanitized(namespace) }
                .sortedByDescending(AiCoachConversation::updatedAt)
                .take(MAX_CONVERSATIONS)
        )
        val encoded = encode(bounded)
        check(encoded.toByteArray(Charsets.UTF_8).size <= MAX_OWNER_FILE_BYTES)
        storage.writeAtomically(namespace, encoded)
        bounded
    }

    private fun enforceFileBound(input: List<AiCoachConversation>): List<AiCoachConversation> {
        val bounded = input.toMutableList()
        while (bounded.isNotEmpty() && encode(bounded).toByteArray(Charsets.UTF_8).size > MAX_OWNER_FILE_BYTES) {
            val oldestIndex = bounded.lastIndex
            val oldest = bounded[oldestIndex]
            if (oldest.messages.size > 2) {
                bounded[oldestIndex] = oldest.copy(messages = oldest.messages.drop(2))
            } else if (bounded.size > 1) {
                bounded.removeAt(oldestIndex)
            } else {
                bounded[oldestIndex] = oldest.copy(messages = emptyList())
                break
            }
        }
        return bounded
    }

    private fun encode(conversations: List<AiCoachConversation>): String = json.encodeToString(
        PersistedConversationEnvelope.serializer(),
        PersistedConversationEnvelope(
            schemaVersion = SCHEMA_VERSION,
            conversations = conversations.map(PersistedConversation::fromDomain)
        )
    )

    private fun AiCoachConversation.sanitized(namespace: String): AiCoachConversation = copy(
        ownerNamespace = namespace,
        title = title.trim().take(MAX_TITLE_CHARACTERS).ifBlank { DEFAULT_TITLE },
        createdAt = createdAt.coerceAtLeast(1L),
        updatedAt = updatedAt.coerceAtLeast(createdAt.coerceAtLeast(1L)),
        messages = messages
            .sortedBy(AiCoachMessage::timestamp)
            .takeLast(MAX_MESSAGES_PER_CONVERSATION)
            .map { it.sanitized() }
    )

    private fun AiCoachMessage.sanitized(): AiCoachMessage = copy(
        text = text.take(MAX_MESSAGE_CHARACTERS),
        source = source.takeIf { role == AiCoachMessageRole.COACH },
        response = response?.sanitized().takeIf { role == AiCoachMessageRole.COACH }
    )

    private fun AiCoachResponse.sanitized(): AiCoachResponse {
        val validWorkout = workoutPlan?.takeIf {
            it.isValid() && it.hasValidStructuredContracts()
        }
        val validDiet = dietPlan?.takeIf(GeneratedDietPlan::isSavable)
        val hasExactlyOnePlan = (workoutPlan == null) != (dietPlan == null)
        val safeValidationStatus = when {
            hasExactlyOnePlan && validDiet != null -> validDiet.validationStatus.name
            hasExactlyOnePlan && validWorkout != null -> PlanValidationStatus.VALIDATED.name
            metadata.validationStatus == null -> null
            else -> PlanValidationStatus.entries
                .firstOrNull { it.name == metadata.validationStatus }
                ?.name
        }
        return copy(
            summary = summary.take(1_000),
            recommendedAction = recommendedAction.take(1_200),
            nutritionNote = nutritionNote.take(1_200),
            workoutNote = workoutNote.take(4_000),
            safetyDisclaimer = safetyDisclaimer.take(800),
            metadata = AiCoachResponseMetadata(
                sourceType = metadata.sourceType.toProviderNeutralSource(),
                fallbackUsed = metadata.fallbackUsed,
                intent = metadata.intent,
                escalationRequired = metadata.escalationRequired,
                validationStatus = safeValidationStatus
            ),
            workoutPlan = validWorkout.takeIf { hasExactlyOnePlan },
            dietPlan = validDiet.takeIf { hasExactlyOnePlan }
        )
    }

    private fun String.toProviderNeutralSource(): String = when (uppercase()) {
        "REMOTE", "FIREWORKS" -> "REMOTE"
        "BACKEND_MOCK" -> "BACKEND_MOCK"
        "LOCAL_FALLBACK", "LOCAL_SAFETY_RULES" -> "LOCAL_FALLBACK"
        else -> "LOCAL_KNOWLEDGE"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val MAX_CONVERSATIONS = 20
        const val MAX_MESSAGES_PER_CONVERSATION = 40
        const val MAX_MESSAGE_CHARACTERS = 8_000
        const val MAX_TITLE_CHARACTERS = 80
        const val MAX_OWNER_FILE_BYTES = 1_000_000
        const val DEFAULT_TITLE = "New fitness conversation"
        private const val SCHEMA_VERSION = 1
    }
}

@Serializable
private data class PersistedConversationEnvelope(
    val schemaVersion: Int,
    val conversations: List<PersistedConversation>
)

@Serializable
private data class PersistedConversation(
    val conversationId: String,
    val ownerNamespace: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<PersistedMessage>
) {
    fun toDomain(expectedNamespace: String): AiCoachConversation {
        check(ownerNamespace == expectedNamespace)
        check(conversationId.isNotBlank() && conversationId.length <= 128)
        check(title.isNotBlank() && title.length <= AiCoachConversationRepository.MAX_TITLE_CHARACTERS)
        check(createdAt >= 0L && updatedAt >= createdAt)
        val domainMessages = messages.map(PersistedMessage::toDomain)
        check(domainMessages.zipWithNext().all { (first, second) ->
            first.timestamp <= second.timestamp
        })
        return AiCoachConversation(
            conversationId = conversationId,
            ownerNamespace = ownerNamespace,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = domainMessages
        )
    }

    companion object {
        fun fromDomain(value: AiCoachConversation) = PersistedConversation(
            conversationId = value.conversationId,
            ownerNamespace = value.ownerNamespace,
            title = value.title,
            createdAt = value.createdAt,
            updatedAt = value.updatedAt,
            messages = value.messages.map(PersistedMessage::fromDomain)
        )
    }
}

@Serializable
private data class PersistedMessage(
    val messageId: String,
    val role: String,
    val timestamp: Long,
    val text: String,
    val source: String? = null,
    val response: PersistedResponse? = null
) {
    fun toDomain(): AiCoachMessage {
        check(messageId.isNotBlank() && messageId.length <= 128)
        check(timestamp >= 0L)
        check(text.isNotBlank() && text.length <= AiCoachConversationRepository.MAX_MESSAGE_CHARACTERS)
        val domainRole = AiCoachMessageRole.valueOf(role)
        val domainSource = source?.let(AiCoachMessageSource::valueOf)
        check(domainRole == AiCoachMessageRole.COACH || (domainSource == null && response == null))
        return AiCoachMessage(
            messageId = messageId,
            role = domainRole,
            timestamp = timestamp,
            text = text,
            source = domainSource,
            response = response?.toDomain(domainSource)
        )
    }

    companion object {
        fun fromDomain(value: AiCoachMessage) = PersistedMessage(
            messageId = value.messageId,
            role = value.role.name,
            timestamp = value.timestamp,
            text = value.text,
            source = value.source?.name,
            response = value.response?.let(PersistedResponse::fromDomain)
        )
    }
}

@Serializable
private data class PersistedResponse(
    val summary: String,
    val recommendedAction: String,
    val nutritionNote: String,
    val workoutNote: String,
    val safetyDisclaimer: String,
    val intent: String,
    val escalationRequired: Boolean,
    val validationStatus: String? = null,
    val workoutPlan: GeneratedWorkoutPlan? = null,
    val dietPlan: GeneratedDietPlan? = null
) {
    fun toDomain(source: AiCoachMessageSource?): AiCoachResponse {
        check(summary.isNotBlank() && summary.length <= 1_000)
        check(recommendedAction.length <= 1_200)
        check(nutritionNote.length <= 1_200)
        check(workoutNote.length <= 4_000)
        check(safetyDisclaimer.length <= 800)
        check(workoutPlan?.let { plan -> plan.isValid() && plan.hasValidStructuredContracts() } != false)
        check(dietPlan?.isSavable() != false)
        check(workoutPlan == null || dietPlan == null)
        val domainIntent = AiCoachIntent.valueOf(intent)
        val domainValidationStatus = validationStatus?.let { PlanValidationStatus.valueOf(it) }
        check(workoutPlan == null || domainValidationStatus == PlanValidationStatus.VALIDATED)
        check(dietPlan == null || domainValidationStatus == dietPlan.validationStatus)
        return AiCoachResponse(
            summary = summary,
            recommendedAction = recommendedAction,
            nutritionNote = nutritionNote,
            workoutNote = workoutNote,
            safetyDisclaimer = safetyDisclaimer,
            metadata = AiCoachResponseMetadata(
                sourceType = when (source) {
                    AiCoachMessageSource.REMOTE -> "REMOTE"
                    AiCoachMessageSource.BACKEND_MOCK -> "BACKEND_MOCK"
                    AiCoachMessageSource.LOCAL_FALLBACK -> "LOCAL_FALLBACK"
                    else -> "LOCAL_KNOWLEDGE"
                },
                fallbackUsed = source == AiCoachMessageSource.LOCAL_FALLBACK,
                intent = domainIntent,
                escalationRequired = escalationRequired,
                validationStatus = domainValidationStatus?.name
            ),
            workoutPlan = workoutPlan,
            dietPlan = dietPlan
        )
    }

    companion object {
        fun fromDomain(value: AiCoachResponse) = PersistedResponse(
            summary = value.summary,
            recommendedAction = value.recommendedAction,
            nutritionNote = value.nutritionNote,
            workoutNote = value.workoutNote,
            safetyDisclaimer = value.safetyDisclaimer,
            intent = value.metadata.intent.name,
            escalationRequired = value.metadata.escalationRequired,
            validationStatus = when {
                value.dietPlan != null -> value.dietPlan.validationStatus.name
                value.workoutPlan != null -> PlanValidationStatus.VALIDATED.name
                value.metadata.validationStatus == null -> null
                else -> PlanValidationStatus.valueOf(value.metadata.validationStatus).name
            },
            workoutPlan = value.workoutPlan,
            dietPlan = value.dietPlan
        )
    }
}
