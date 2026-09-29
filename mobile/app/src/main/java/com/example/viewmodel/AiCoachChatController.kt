package com.example.viewmodel

import com.example.ai.AiCoachExecutionState
import com.example.ai.AiCoachOutcome
import com.example.ai.AiCoachRequest
import com.example.ai.AiCoachResponse
import com.example.ai.backend.BackendPublicError
import com.example.ai.backend.BackendResult
import com.example.ai.backend.RemoteAiConsentState
import com.example.ai.conversation.AiCoachConversation
import com.example.ai.conversation.AiCoachConversationContext
import com.example.ai.conversation.AiCoachConversationOwner
import com.example.ai.conversation.AiCoachConversationRepository
import com.example.ai.conversation.AiCoachMessage
import com.example.ai.conversation.AiCoachMessageRole
import com.example.ai.conversation.AiCoachMessageSource
import com.example.data.SavedPlanResult
import com.example.identity.AuthSessionState
import com.example.security.AiSafetyPolicy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun interface CoachQuestionResponder {
    suspend fun ask(request: AiCoachRequest): AiCoachOutcome
}

interface CoachConsentActions {
    suspend fun fetch(): BackendResult<RemoteAiConsentState>
    suspend fun grantStandard(noticeVersion: String): BackendResult<Unit>
    suspend fun declineStandard(): BackendResult<Unit>
}

enum class CoachRemoteAction {
    SIGN_IN,
    VERIFY_EMAIL,
    CONSENT_REQUIRED,
    CONSENT_NOTICE_CHANGED,
    REMOTE_UNAVAILABLE,
    QUOTA_UNAVAILABLE,
    REQUEST_IN_PROGRESS
}

data class AiCoachConversationSummary(
    val conversationId: String,
    val title: String,
    val updatedAt: Long,
    val isCurrent: Boolean
)

class AiCoachChatController(
    private val scope: CoroutineScope,
    private val conversationRepository: AiCoachConversationRepository,
    private val authSession: StateFlow<AuthSessionState>,
    private val responder: CoachQuestionResponder,
    private val consentActions: CoachConsentActions,
    private val savePlan: suspend (AiCoachResponse) -> SavedPlanResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutableState = MutableStateFlow(AiCoachUiState())
    val state: StateFlow<AiCoachUiState> = mutableState

    private var owner: AiCoachConversationOwner? = null
    private var ownerGeneration = 0L
    private var conversations: List<AiCoachConversation> = emptyList()
    private var sendJob: Job? = null
    private var consentJob: Job? = null
    private val saveJobs = mutableMapOf<String, Job>()
    private var messageSaveStates: Map<String, CoachPlanSaveState> = emptyMap()

    init {
        scope.launch {
            authSession.collectLatest { session ->
                val nextOwner = session.toConversationOwnerOrNull()
                if (nextOwner == owner) return@collectLatest
                ownerGeneration += 1
                sendJob?.cancel()
                sendJob = null
                consentJob?.cancel()
                consentJob = null
                saveJobs.values.forEach { it.cancel() }
                saveJobs.clear()
                messageSaveStates = emptyMap()
                conversations = emptyList()
                owner = nextOwner
                mutableState.value = AiCoachUiState()
                nextOwner?.let { loadOwner(it, ownerGeneration) }
            }
        }
    }

    fun updateDraft(value: String) {
        if (value.length > AiSafetyPolicy.MAX_INPUT_CHARACTERS) return
        mutableState.value = mutableState.value.copy(
            question = value,
            errorMessage = null,
            backendNotice = null
        )
    }

    fun send() {
        val snapshot = mutableState.value
        val question = snapshot.question.trim()
        if (snapshot.isLoading) return
        if (!AiSafetyPolicy.isAcceptableInput(question)) {
            mutableState.value = snapshot.copy(
                errorMessage = if (question.isEmpty()) {
                    "Enter a fitness question or choose a quick topic."
                } else {
                    "Keep your message under ${AiSafetyPolicy.MAX_INPUT_CHARACTERS} characters."
                }
            )
            return
        }
        val active = activeConversation() ?: return
        val requestOwner = owner ?: return
        val generation = ownerGeneration
        val priorMessages = active.messages
        val now = clock()
        val userMessage = AiCoachMessage(
            messageId = idFactory(),
            role = AiCoachMessageRole.USER,
            timestamp = now,
            text = question
        )
        val updated = active.copy(
            title = if (active.messages.isEmpty()) deterministicTitle(question) else active.title,
            updatedAt = now,
            messages = active.messages + userMessage
        )
        replaceConversation(updated)
        mutableState.value = stateForActive(updated).copy(
            question = "",
            isLoading = true,
            response = null,
            errorMessage = null,
            saveState = CoachPlanSaveState.NONE,
            backendNotice = null,
            remoteAction = null
        )

        sendJob = scope.launch {
            persist(requestOwner, updated)
            val outcome = try {
                responder.ask(
                    AiCoachRequest(
                        question = question,
                        conversationContext = AiCoachConversationContext.build(priorMessages)
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == ownerGeneration && requestOwner == owner) {
                    val current = conversations.firstOrNull { it.conversationId == updated.conversationId }
                    if (current != null) {
                        mutableState.value = stateForActive(current).copy(
                            isLoading = false,
                            errorMessage = "The Coach could not prepare a response. Try again.",
                            executionState = AiCoachExecutionState.UNKNOWN_ERROR,
                            backendFailureState = AiCoachExecutionState.UNKNOWN_ERROR,
                            backendNotice = "Local Coach remains available for a new message.",
                            remoteAction = null
                        )
                    }
                }
                return@launch
            }
            if (generation != ownerGeneration || requestOwner != owner) return@launch
            applyOutcome(requestOwner, updated.conversationId, outcome)
        }
    }

    fun retryLast() {
        val lastQuestion = mutableState.value.messages.lastOrNull { it.role == AiCoachMessageRole.USER }?.text
            ?: return
        updateDraft(lastQuestion)
        send()
    }

    fun newChat() {
        if (mutableState.value.isLoading) return
        val requestOwner = owner ?: return
        val current = activeConversation()
        if (current?.messages?.isEmpty() == true) {
            mutableState.value = stateForActive(current).copy(
                question = "",
                errorMessage = null,
                historyError = null,
                backendNotice = null,
                remoteAction = null
            )
            return
        }
        val conversation = newConversation(requestOwner)
        conversations = (listOf(conversation) + conversations.filter { it.messages.isNotEmpty() })
            .take(AiCoachConversationRepository.MAX_CONVERSATIONS)
        mutableState.value = stateForActive(conversation).copy(
            question = "",
            errorMessage = null,
            backendNotice = null,
            remoteAction = null
        )
        scope.launch { persist(requestOwner, conversation) }
    }

    fun selectConversation(conversationId: String) {
        if (mutableState.value.isLoading) return
        val selected = conversations.firstOrNull { it.conversationId == conversationId } ?: return
        mutableState.value = stateForActive(selected).copy(
            question = "",
            errorMessage = null,
            backendNotice = null,
            remoteAction = null
        )
    }

    fun deleteConversation(conversationId: String) {
        if (mutableState.value.isLoading) return
        val requestOwner = owner ?: return
        val generation = ownerGeneration
        val currentConversationId = mutableState.value.activeConversationId
        val removedMessageIds = conversations
            .firstOrNull { it.conversationId == conversationId }
            ?.messages
            .orEmpty()
            .map(AiCoachMessage::messageId)
        scope.launch {
            val remaining = conversationRepository.delete(requestOwner, conversationId).getOrElse {
                setHistoryError("Conversation could not be deleted.")
                return@launch
            }
            if (generation != ownerGeneration || requestOwner != owner) return@launch
            removedMessageIds.forEach { messageId ->
                saveJobs.remove(messageId)?.cancel()
                messageSaveStates = messageSaveStates - messageId
            }
            conversations = remaining
            val selected = if (currentConversationId == conversationId) {
                conversations.firstOrNull()
            } else {
                conversations.firstOrNull { it.conversationId == currentConversationId }
                    ?: conversations.firstOrNull()
            }
            if (selected == null) {
                val fresh = newConversation(requestOwner)
                conversations = listOf(fresh)
                persist(requestOwner, fresh)
                mutableState.value = stateForActive(fresh)
            } else {
                mutableState.value = stateForActive(selected)
            }
        }
    }

    fun clearHistory() {
        if (mutableState.value.isLoading) return
        val requestOwner = owner ?: return
        val generation = ownerGeneration
        scope.launch {
            conversationRepository.clear(requestOwner).getOrElse {
                setHistoryError("Conversation history could not be cleared.")
                return@launch
            }
            if (generation != ownerGeneration || requestOwner != owner) return@launch
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            messageSaveStates = emptyMap()
            val fresh = newConversation(requestOwner)
            conversations = listOf(fresh)
            persist(requestOwner, fresh)
            mutableState.value = stateForActive(fresh)
        }
    }

    fun saveMessagePlan(messageId: String) {
        if (saveJobs[messageId]?.isActive == true) return
        val response = mutableState.value.messages
            .firstOrNull { it.messageId == messageId }
            ?.response
            ?.takeIf { it.hasSavablePlan() }
            ?: return
        val generation = ownerGeneration
        messageSaveStates = messageSaveStates + (messageId to CoachPlanSaveState.SAVING)
        mutableState.value = mutableState.value.copy(
            messageSaveStates = visibleMessageSaveStates(),
            saveState = CoachPlanSaveState.SAVING
        )
        saveJobs[messageId] = scope.launch {
            val result = try {
                savePlan(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SavedPlanResult.ERROR
            }
            if (generation != ownerGeneration) return@launch
            val state = when (result) {
                SavedPlanResult.SAVED -> CoachPlanSaveState.SAVED
                SavedPlanResult.ALREADY_SAVED -> CoachPlanSaveState.ALREADY_SAVED
                SavedPlanResult.INVALID_PLAN,
                SavedPlanResult.ERROR -> CoachPlanSaveState.ERROR
            }
            messageSaveStates = messageSaveStates + (messageId to state)
            val visible = mutableState.value.messages.any { it.messageId == messageId }
            mutableState.value = mutableState.value.copy(
                messageSaveStates = visibleMessageSaveStates(),
                saveState = if (visible) state else mutableState.value.saveState
            )
        }
    }

    fun grantRemoteConsent() {
        if (mutableState.value.isConsentUpdating) return
        val requestOwner = owner ?: return
        val generation = ownerGeneration
        mutableState.value = mutableState.value.copy(isConsentUpdating = true)
        consentJob = scope.launch {
            val result = when (val fetched = consentActions.fetch()) {
                is BackendResult.Success -> consentActions.grantStandard(
                    fetched.value.requiredNoticeVersions.standardRemoteAi
                )
                is BackendResult.PublicError -> fetched
                else -> BackendResult.PublicError(BackendPublicError.CONSENT_AUTHORITY_UNAVAILABLE)
            }
            if (generation != ownerGeneration || requestOwner != owner) return@launch
            mutableState.value = if (result is BackendResult.Success) {
                mutableState.value.copy(
                    isConsentUpdating = false,
                    remoteAction = null,
                    backendNotice = "Remote Coach consent was updated. Send again when you are ready."
                )
            } else {
                mutableState.value.copy(
                    isConsentUpdating = false,
                    backendNotice = "Remote Coach consent is unavailable. Local Coach remains available."
                )
            }
        }
    }

    fun keepUsingLocalCoach() {
        if (mutableState.value.isConsentUpdating) return
        val requestOwner = owner ?: return
        val generation = ownerGeneration
        mutableState.value = mutableState.value.copy(isConsentUpdating = true)
        consentJob = scope.launch {
            val result = consentActions.declineStandard()
            if (generation != ownerGeneration || requestOwner != owner) return@launch
            mutableState.value = if (result is BackendResult.Success) {
                mutableState.value.copy(
                    isConsentUpdating = false,
                    remoteAction = null,
                    backendNotice = "Local Coach will continue to be used."
                )
            } else {
                mutableState.value.copy(
                    isConsentUpdating = false,
                    remoteAction = null,
                    backendNotice = "Local Coach will continue. The remote-consent authority is unavailable."
                )
            }
        }
    }

    private suspend fun loadOwner(requestedOwner: AiCoachConversationOwner, generation: Long) {
        val loaded = conversationRepository.load(requestedOwner).getOrElse {
            if (generation == ownerGeneration && requestedOwner == owner) {
                conversations = emptyList()
                val fresh = newConversation(requestedOwner)
                conversations = listOf(fresh)
                mutableState.value = stateForActive(fresh).copy(
                    historyError = "Local Coach history could not be read. It was not overwritten."
                )
            }
            return
        }
        if (generation != ownerGeneration || requestedOwner != owner) return
        conversations = loaded
        val active = conversations.firstOrNull() ?: newConversation(requestedOwner).also {
            conversations = listOf(it)
        }
        mutableState.value = stateForActive(active)
    }

    private suspend fun applyOutcome(
        requestedOwner: AiCoachConversationOwner,
        conversationId: String,
        outcome: AiCoachOutcome
    ) {
        val active = conversations.firstOrNull { it.conversationId == conversationId } ?: return
        val response = outcome.response
        if (response == null) {
            mutableState.value = stateForActive(active).copy(
                isLoading = false,
                errorMessage = outcome.message ?: "The Coach could not prepare a response. Try again.",
                executionState = outcome.state,
                backendFailureState = outcome.backendFailureState,
                retryAfterSeconds = outcome.retryAfterSeconds,
                backendNotice = outcome.message,
                remoteAction = outcome.backendError.toRemoteAction()
            )
            return
        }
        val now = clock()
        val messageSource = outcome.state.toMessageSource()
        val coachMessage = AiCoachMessage(
            messageId = idFactory(),
            role = AiCoachMessageRole.COACH,
            timestamp = now,
            text = response.summary,
            source = messageSource,
            response = response.toProviderNeutralResponse(messageSource)
        )
        val updated = active.copy(updatedAt = now, messages = active.messages + coachMessage)
        replaceConversation(updated)
        persist(requestedOwner, updated)
        mutableState.value = stateForActive(updated).copy(
            isLoading = false,
            response = coachMessage.response,
            executionState = outcome.state,
            backendFailureState = outcome.backendFailureState,
            retryAfterSeconds = outcome.retryAfterSeconds,
            backendNotice = outcome.message,
            remoteAction = outcome.backendError.toRemoteAction()
        )
    }

    private suspend fun persist(
        requestedOwner: AiCoachConversationOwner,
        conversation: AiCoachConversation
    ) {
        conversationRepository.upsert(requestedOwner, conversation).onFailure {
            if (requestedOwner == owner) {
                setHistoryError("This conversation could not be saved locally.")
            }
        }
    }

    private fun replaceConversation(conversation: AiCoachConversation) {
        conversations = (conversations.filterNot { it.conversationId == conversation.conversationId } + conversation)
            .sortedByDescending(AiCoachConversation::updatedAt)
            .take(AiCoachConversationRepository.MAX_CONVERSATIONS)
    }

    private fun activeConversation(): AiCoachConversation? {
        val id = mutableState.value.activeConversationId
        return conversations.firstOrNull { it.conversationId == id }
    }

    private fun newConversation(requestedOwner: AiCoachConversationOwner): AiCoachConversation {
        val now = clock()
        return AiCoachConversation(
            conversationId = idFactory(),
            ownerNamespace = conversationRepository.ownerNamespace(requestedOwner),
            title = AiCoachConversationRepository.DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
    }

    private fun stateForActive(active: AiCoachConversation): AiCoachUiState {
        val latestCoach = active.messages.lastOrNull { it.role == AiCoachMessageRole.COACH }
        return AiCoachUiState(
            question = mutableState.value.question,
            response = latestCoach?.response,
            activeConversationId = active.conversationId,
            conversationHistory = conversations
                .sortedByDescending(AiCoachConversation::updatedAt)
                .map {
                    AiCoachConversationSummary(
                        conversationId = it.conversationId,
                        title = it.title,
                        updatedAt = it.updatedAt,
                        isCurrent = it.conversationId == active.conversationId
                    )
                },
            messages = active.messages,
            messageSaveStates = messageSaveStates.filterKeys { messageId ->
                active.messages.any { it.messageId == messageId }
            },
            saveState = active.messages
                .asReversed()
                .firstNotNullOfOrNull { messageSaveStates[it.messageId] }
                ?: CoachPlanSaveState.NONE
        )
    }

    private fun visibleMessageSaveStates(): Map<String, CoachPlanSaveState> {
        val visibleIds = mutableState.value.messages.mapTo(mutableSetOf()) { it.messageId }
        return messageSaveStates.filterKeys(visibleIds::contains)
    }

    private fun setHistoryError(message: String) {
        mutableState.value = mutableState.value.copy(historyError = message)
    }

    private fun deterministicTitle(question: String): String = question
        .trim()
        .replace(Regex("\\s+"), " ")
        .split(' ')
        .take(8)
        .joinToString(" ")
        .take(AiCoachConversationRepository.MAX_TITLE_CHARACTERS)
        .ifBlank { AiCoachConversationRepository.DEFAULT_TITLE }

    private fun AiCoachResponse.hasSavablePlan(): Boolean =
        (workoutPlan != null && workoutPlan.isValid() && dietPlan == null) ||
            (dietPlan != null && dietPlan.isSavable() && workoutPlan == null)

    private fun AiCoachResponse.toProviderNeutralResponse(
        source: AiCoachMessageSource
    ): AiCoachResponse = copy(
        metadata = metadata.copy(
            sourceType = when (source) {
                AiCoachMessageSource.REMOTE -> "REMOTE"
                AiCoachMessageSource.BACKEND_MOCK -> "BACKEND_MOCK"
                AiCoachMessageSource.LOCAL_FALLBACK -> "LOCAL_FALLBACK"
                AiCoachMessageSource.LOCAL -> "LOCAL_KNOWLEDGE"
            },
            backendMode = metadata.backendMode?.let { if (it == "mock") "mock" else "remote" }
        )
    )

    private fun AiCoachExecutionState.toMessageSource(): AiCoachMessageSource = when (this) {
        AiCoachExecutionState.REMOTE_SUCCESS -> AiCoachMessageSource.REMOTE
        AiCoachExecutionState.BACKEND_MOCK_SUCCESS -> AiCoachMessageSource.BACKEND_MOCK
        AiCoachExecutionState.LOCAL_FALLBACK -> AiCoachMessageSource.LOCAL_FALLBACK
        else -> AiCoachMessageSource.LOCAL
    }

    private fun BackendPublicError?.toRemoteAction(): CoachRemoteAction? = when (this) {
        BackendPublicError.AUTH_REQUIRED,
        BackendPublicError.INVALID_SESSION -> CoachRemoteAction.SIGN_IN
        BackendPublicError.EMAIL_VERIFICATION_REQUIRED -> CoachRemoteAction.VERIFY_EMAIL
        BackendPublicError.REMOTE_AI_CONSENT_REQUIRED -> CoachRemoteAction.CONSENT_REQUIRED
        BackendPublicError.CONSENT_NOTICE_VERSION_MISMATCH -> CoachRemoteAction.CONSENT_NOTICE_CHANGED
        BackendPublicError.REMOTE_ACCOUNT_QUOTA_EXHAUSTED,
        BackendPublicError.REMOTE_GLOBAL_BUDGET_UNAVAILABLE,
        BackendPublicError.QUOTA_POLICY_UNAVAILABLE -> CoachRemoteAction.QUOTA_UNAVAILABLE
        BackendPublicError.REMOTE_REQUEST_IN_PROGRESS -> CoachRemoteAction.REQUEST_IN_PROGRESS
        null -> null
        else -> CoachRemoteAction.REMOTE_UNAVAILABLE
    }

    private fun AuthSessionState.toConversationOwnerOrNull(): AiCoachConversationOwner? = when (this) {
        is AuthSessionState.Authenticated -> AiCoachConversationOwner.Account(uid)
        AuthSessionState.Guest -> AiCoachConversationOwner.Guest
        AuthSessionState.Initializing -> null
    }
}
