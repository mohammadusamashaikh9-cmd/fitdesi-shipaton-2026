package com.example.identity

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthSessionState {
    data object Initializing : AuthSessionState
    data object Guest : AuthSessionState

    data class Authenticated(
        val uid: String,
        val email: String,
        val emailVerified: Boolean
    ) : AuthSessionState
}

enum class AuthFailure {
    INVALID_INPUT,
    INVALID_CREDENTIALS,
    ACCOUNT_CREATION_REJECTED,
    NETWORK,
    REQUIRES_RECENT_LOGIN,
    TOO_MANY_REQUESTS,
    UNAVAILABLE
}

sealed interface AuthOperationResult {
    data object Success : AuthOperationResult
    data class Failure(val reason: AuthFailure) : AuthOperationResult
    data class AccountCreatedVerificationDeliveryFailed(
        val reason: AuthFailure
    ) : AuthOperationResult
}

sealed interface AuthIdTokenResult {
    data class Success(val token: String) : AuthIdTokenResult {
        override fun toString(): String = "Success(token=<redacted>)"
    }
    data object AuthenticationRequired : AuthIdTokenResult
    data object InvalidSession : AuthIdTokenResult
    data class Failure(val reason: AuthFailure) : AuthIdTokenResult
}

interface AuthRepository {
    val session: StateFlow<AuthSessionState>

    suspend fun createAccount(email: String, password: String): AuthOperationResult
    suspend fun signIn(email: String, password: String): AuthOperationResult
    suspend fun sendPasswordReset(email: String): AuthOperationResult
    suspend fun resendVerification(): AuthOperationResult
    suspend fun refreshCurrentUser(): AuthOperationResult
    suspend fun signOut(): AuthOperationResult
    suspend fun reauthenticate(password: String): AuthOperationResult
    suspend fun deleteAccount(): AuthOperationResult
    suspend fun backendIdToken(): AuthIdTokenResult
}

internal data class AuthProviderAccount(
    val uid: String,
    val email: String,
    val emailVerified: Boolean
)

internal fun interface AuthListenerRegistration {
    fun remove()
}

internal interface AuthProvider {
    fun currentAccount(): AuthProviderAccount?
    fun addAuthStateListener(listener: (AuthProviderAccount?) -> Unit): AuthListenerRegistration
    suspend fun createAccount(email: String, password: String): AuthProviderAccount
    suspend fun signIn(email: String, password: String): AuthProviderAccount
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadCurrentAccount(): AuthProviderAccount?
    fun signOut()
    suspend fun reauthenticate(password: String)
    suspend fun deleteAccount()
    suspend fun backendIdToken(): AuthProviderIdTokenResult
}

internal sealed interface AuthProviderIdTokenResult {
    data class Success(val token: String) : AuthProviderIdTokenResult {
        override fun toString(): String = "Success(token=<redacted>)"
    }
    data object InvalidSession : AuthProviderIdTokenResult
}

internal class AuthProviderException(
    val failure: AuthFailure,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
