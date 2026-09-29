package com.example.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DefaultAuthRepository(
    private val provider: AuthProvider
) : AuthRepository {
    private val _session = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
    override val session: StateFlow<AuthSessionState> = _session.asStateFlow()

    @Suppress("unused")
    private val listenerRegistration = provider.addAuthStateListener(::publish)

    init {
        publish(provider.currentAccount())
    }

    override suspend fun createAccount(email: String, password: String): AuthOperationResult {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isEmpty() || password.isEmpty()) {
            return AuthOperationResult.Failure(AuthFailure.INVALID_INPUT)
        }
        when (val creation = execute {
            publish(provider.createAccount(normalizedEmail, password))
        }) {
            AuthOperationResult.Success -> Unit
            is AuthOperationResult.Failure -> return creation
            is AuthOperationResult.AccountCreatedVerificationDeliveryFailed -> return creation
        }

        return when (val verification = execute {
            provider.sendEmailVerification()
            publish(provider.currentAccount())
        }) {
            AuthOperationResult.Success -> AuthOperationResult.Success
            is AuthOperationResult.Failure ->
                AuthOperationResult.AccountCreatedVerificationDeliveryFailed(verification.reason)
            is AuthOperationResult.AccountCreatedVerificationDeliveryFailed -> verification
        }
    }

    override suspend fun signIn(email: String, password: String): AuthOperationResult {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isEmpty() || password.isEmpty()) {
            return AuthOperationResult.Failure(AuthFailure.INVALID_INPUT)
        }
        return execute {
            provider.signIn(normalizedEmail, password)
            publish(provider.currentAccount())
        }
    }

    override suspend fun sendPasswordReset(email: String): AuthOperationResult {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isEmpty()) {
            return AuthOperationResult.Failure(AuthFailure.INVALID_INPUT)
        }
        return when (val result = execute { provider.sendPasswordReset(normalizedEmail) }) {
            AuthOperationResult.Success -> AuthOperationResult.Success
            is AuthOperationResult.Failure -> if (result.reason == AuthFailure.INVALID_CREDENTIALS) {
                AuthOperationResult.Success
            } else {
                result
            }
            is AuthOperationResult.AccountCreatedVerificationDeliveryFailed -> result
        }
    }

    override suspend fun resendVerification(): AuthOperationResult = execute {
        if (provider.currentAccount() == null) {
            throw AuthProviderException(AuthFailure.UNAVAILABLE)
        }
        provider.sendEmailVerification()
        publish(provider.currentAccount())
    }

    override suspend fun refreshCurrentUser(): AuthOperationResult = execute {
        publish(provider.reloadCurrentAccount())
    }

    override suspend fun signOut(): AuthOperationResult = execute {
        provider.signOut()
        publish(provider.currentAccount())
    }

    override suspend fun reauthenticate(password: String): AuthOperationResult {
        if (password.isEmpty()) {
            return AuthOperationResult.Failure(AuthFailure.INVALID_INPUT)
        }
        return execute { provider.reauthenticate(password) }
    }

    override suspend fun deleteAccount(): AuthOperationResult = execute {
        provider.deleteAccount()
        publish(provider.currentAccount())
    }

    override suspend fun backendIdToken(): AuthIdTokenResult {
        if (session.value !is AuthSessionState.Authenticated) {
            return AuthIdTokenResult.AuthenticationRequired
        }
        return try {
            when (val result = provider.backendIdToken()) {
                is AuthProviderIdTokenResult.Success -> if (result.token.isBlank()) {
                    AuthIdTokenResult.InvalidSession
                } else {
                    AuthIdTokenResult.Success(result.token)
                }
                AuthProviderIdTokenResult.InvalidSession -> AuthIdTokenResult.InvalidSession
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AuthProviderException) {
            if (failure.failure == AuthFailure.INVALID_CREDENTIALS) {
                AuthIdTokenResult.InvalidSession
            } else {
                AuthIdTokenResult.Failure(failure.failure)
            }
        } catch (_: Exception) {
            AuthIdTokenResult.Failure(AuthFailure.UNAVAILABLE)
        }
    }

    private suspend fun execute(operation: suspend () -> Unit): AuthOperationResult = try {
        operation()
        AuthOperationResult.Success
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: AuthProviderException) {
        AuthOperationResult.Failure(failure.failure)
    } catch (_: Exception) {
        AuthOperationResult.Failure(AuthFailure.UNAVAILABLE)
    }

    private fun publish(account: AuthProviderAccount?) {
        _session.value = account?.let {
            AuthSessionState.Authenticated(
                uid = it.uid,
                email = it.email,
                emailVerified = it.emailVerified
            )
        } ?: AuthSessionState.Guest
    }
}

fun createFirebaseAuthRepository(): AuthRepository =
    DefaultAuthRepository(FirebaseAuthProvider())
