package com.example.ai.backend

import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface CoachRemoteClient {
    suspend fun submit(request: CoachRequestDto): BackendResult<CoachResponseDto>
}

class AuthenticatedCoachClient(
    private val authRepository: AuthRepository,
    private val backendService: AiBackendService,
    private val idempotencyKeyFactory: () -> String = { UUID.randomUUID().toString() }
) : CoachRemoteClient {
    private val inFlightMutex = Mutex()
    private var inFlight: InFlight? = null

    override suspend fun submit(request: CoachRequestDto): BackendResult<CoachResponseDto> {
        val decision = inFlightMutex.withLock {
            val existing = inFlight
            when {
                existing == null -> SubmissionDecision.Execute(
                    InFlight(
                        request = request,
                        idempotencyKey = idempotencyKeyFactory(),
                        result = CompletableDeferred()
                    ).also { inFlight = it }
                )
                existing.request == request -> SubmissionDecision.Join(existing)
                else -> SubmissionDecision.RejectDifferentRequest
            }
        }

        val current = when (decision) {
            is SubmissionDecision.Join -> return decision.operation.result.await()
            SubmissionDecision.RejectDifferentRequest -> return BackendResult.PublicError(
                BackendPublicError.REMOTE_REQUEST_IN_PROGRESS
            )
            is SubmissionDecision.Execute -> decision.operation
        }

        try {
            val result = execute(current.idempotencyKey, current.request)
            current.result.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            current.result.cancel(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            current.result.completeExceptionally(failure)
            throw failure
        } finally {
            inFlightMutex.withLock {
                if (inFlight === current) inFlight = null
            }
        }
    }

    private suspend fun execute(
        idempotencyKey: String,
        request: CoachRequestDto
    ): BackendResult<CoachResponseDto> {
        if (authRepository.session.value !is AuthSessionState.Authenticated) {
            return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            return BackendResult.PublicError(BackendPublicError.IDEMPOTENCY_INVALID)
        }
        return when (val token = authRepository.backendIdToken()) {
            is AuthIdTokenResult.Success -> backendService.coach(
                idToken = token.token,
                idempotencyKey = idempotencyKey,
                request = request
            )
            AuthIdTokenResult.AuthenticationRequired ->
                BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
            AuthIdTokenResult.InvalidSession ->
                BackendResult.PublicError(BackendPublicError.INVALID_SESSION)
            is AuthIdTokenResult.Failure ->
                BackendResult.PublicError(BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE)
        }
    }

    private class InFlight(
        val request: CoachRequestDto,
        val idempotencyKey: String,
        val result: CompletableDeferred<BackendResult<CoachResponseDto>>
    )

    private sealed interface SubmissionDecision {
        data class Execute(val operation: InFlight) : SubmissionDecision
        data class Join(val operation: InFlight) : SubmissionDecision
        object RejectDifferentRequest : SubmissionDecision
    }

    private companion object {
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._~-]{16,128}")
    }
}
