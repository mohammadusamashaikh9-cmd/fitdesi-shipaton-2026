package com.example.ai

import com.example.ai.knowledge.CoachContext
import com.example.ai.knowledge.FitnessKnowledgeRetriever
import com.example.ai.knowledge.KnowledgePackRegistry
import com.example.ai.knowledge.LocalCoachContextStore
import com.example.security.AiSafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Deterministic offline coaching provider used while Build Week safe mode is enabled.
 * It performs no network, Firebase, or third-party provider calls.
 */
object LocalAiCoachProvider : AiCoachRepository {
    private const val MOCK_RESPONSE_DELAY_MS = 350L

    override suspend fun ask(question: String): Result<AiCoachResponse> {
        return ask(question, CoachContext())
    }

    override suspend fun ask(question: String, context: CoachContext): Result<AiCoachResponse> {
        val normalizedQuestion = question.trim()
        if (!AiSafetyPolicy.isAcceptableInput(normalizedQuestion)) {
            return Result.failure(
                IllegalArgumentException(
                    if (normalizedQuestion.isEmpty()) {
                        "Enter a fitness question to continue."
                    } else {
                        "Keep your question under ${AiSafetyPolicy.MAX_INPUT_CHARACTERS} characters."
                    }
                )
            )
        }

        delay(MOCK_RESPONSE_DELAY_MS)
        return Result.success(withContext(Dispatchers.IO) {
            createOfflineResponse(normalizedQuestion, LocalCoachContextStore.merge(context))
        })
    }
}

internal fun createOfflineResponse(question: String): AiCoachResponse {
    return createOfflineResponse(question, CoachContext())
}

internal fun createOfflineResponse(
    question: String,
    context: CoachContext,
    retriever: FitnessKnowledgeRetriever = KnowledgePackRegistry.retriever()
): AiCoachResponse {
    return LocalCoachOrchestrator().respond(question, context, retriever)
}
