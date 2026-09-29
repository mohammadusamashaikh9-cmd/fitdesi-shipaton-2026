package com.example.ai.backend

import com.example.identity.AuthFailure
import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState

sealed interface BackendSessionResult {
    data class Success(
        val authenticated: Boolean,
        val emailVerified: Boolean,
        val requestId: String
    ) : BackendSessionResult

    data object AuthenticationRequired : BackendSessionResult
    data class InvalidSession(val requestId: String?) : BackendSessionResult
    data class AuthVerificationUnavailable(val requestId: String?) : BackendSessionResult
    data class TokenFailure(val reason: AuthFailure) : BackendSessionResult
    data object BackendUnavailable : BackendSessionResult
    data object InvalidResponse : BackendSessionResult
}

class BackendSessionClient(
    private val authRepository: AuthRepository,
    private val backendService: AiBackendService
) {
    suspend fun getSession(): BackendSessionResult {
        if (authRepository.session.value !is AuthSessionState.Authenticated) {
            return BackendSessionResult.AuthenticationRequired
        }
        return when (val token = authRepository.backendIdToken()) {
            is AuthIdTokenResult.Success -> backendService.authSession(token.token)
            AuthIdTokenResult.AuthenticationRequired -> BackendSessionResult.AuthenticationRequired
            AuthIdTokenResult.InvalidSession -> BackendSessionResult.InvalidSession(null)
            is AuthIdTokenResult.Failure -> BackendSessionResult.TokenFailure(token.reason)
        }
    }
}
