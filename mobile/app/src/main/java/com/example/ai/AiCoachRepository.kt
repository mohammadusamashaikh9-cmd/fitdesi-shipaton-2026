package com.example.ai

import com.example.ai.knowledge.CoachContext

interface AiCoachRepository {
    suspend fun ask(question: String): Result<AiCoachResponse>

    suspend fun ask(question: String, context: CoachContext): Result<AiCoachResponse> =
        ask(question)
}
