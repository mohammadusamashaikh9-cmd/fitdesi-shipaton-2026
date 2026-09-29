package com.example.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun `session starts from the provider's persisted user`() {
        assertEquals(
            AuthSessionState.Guest,
            DefaultAuthRepository(FakeAuthProvider()).session.value
        )

        val account = providerAccount(verified = true)
        assertEquals(
            account.toSessionState(),
            DefaultAuthRepository(FakeAuthProvider(account)).session.value
        )
    }

    @Test
    fun `provider lifecycle updates publish immutable account identity`() {
        val provider = FakeAuthProvider()
        val repository = DefaultAuthRepository(provider)

        provider.emit(providerAccount(verified = false))

        assertEquals(
            AuthSessionState.Authenticated(
                uid = "firebase-uid",
                email = "person@example.com",
                emailVerified = false
            ),
            repository.session.value
        )
    }

    @Test
    fun `create account normalizes only email and requests verification`() = runTest {
        val provider = FakeAuthProvider()
        val repository = DefaultAuthRepository(provider)

        val result = repository.createAccount("  person@example.com  ", " password with spaces ")

        assertEquals(AuthOperationResult.Success, result)
        assertEquals("person@example.com", provider.lastEmail)
        assertEquals(" password with spaces ", provider.lastPassword)
        assertEquals(1, provider.verificationRequests)
        assertEquals(providerAccount(verified = false).toSessionState(), repository.session.value)
    }

    @Test
    fun `created account remains authenticated when verification delivery fails`() = runTest {
        val provider = FakeAuthProvider().apply {
            verificationFailure = AuthProviderException(AuthFailure.NETWORK)
        }
        val repository = DefaultAuthRepository(provider)

        val result = repository.createAccount("person@example.com", "password")

        assertEquals(
            AuthOperationResult.AccountCreatedVerificationDeliveryFailed(AuthFailure.NETWORK),
            result
        )
        assertEquals(1, provider.verificationRequests)
        assertEquals(providerAccount(verified = false).toSessionState(), repository.session.value)
    }

    @Test
    fun `password reset accepts success and invalid user without revealing identity`() = runTest {
        val success = DefaultAuthRepository(FakeAuthProvider())
            .sendPasswordReset("person@example.com")
        val invalidUser = DefaultAuthRepository(
            FakeAuthProvider().apply {
                passwordResetFailure = AuthProviderException(AuthFailure.INVALID_CREDENTIALS)
            }
        ).sendPasswordReset("person@example.com")
        val network = DefaultAuthRepository(
            FakeAuthProvider().apply {
                passwordResetFailure = AuthProviderException(AuthFailure.NETWORK)
            }
        ).sendPasswordReset("person@example.com")

        assertEquals(AuthOperationResult.Success, success)
        assertEquals(success, invalidUser)
        assertEquals(AuthOperationResult.Failure(AuthFailure.NETWORK), network)
    }

    @Test
    fun `reload changes verification only when provider reports it`() = runTest {
        val provider = FakeAuthProvider(providerAccount(verified = false))
        val repository = DefaultAuthRepository(provider)

        provider.reloadAccount = providerAccount(verified = false)
        assertEquals(AuthOperationResult.Success, repository.refreshCurrentUser())
        assertEquals(providerAccount(verified = false).toSessionState(), repository.session.value)

        provider.reloadAccount = providerAccount(verified = true)
        assertEquals(AuthOperationResult.Success, repository.refreshCurrentUser())
        assertEquals(providerAccount(verified = true).toSessionState(), repository.session.value)
    }

    @Test
    fun `resend verification delegates only for an authenticated provider account`() = runTest {
        val provider = FakeAuthProvider(providerAccount(verified = false))
        val repository = DefaultAuthRepository(provider)

        assertEquals(AuthOperationResult.Success, repository.resendVerification())
        assertEquals(1, provider.verificationRequests)

        provider.emit(null)
        assertEquals(
            AuthOperationResult.Failure(AuthFailure.UNAVAILABLE),
            repository.resendVerification()
        )
        assertEquals(1, provider.verificationRequests)
    }

    @Test
    fun `sign out and delete publish Guest from provider state`() = runTest {
        val provider = FakeAuthProvider(providerAccount())
        val repository = DefaultAuthRepository(provider)

        assertEquals(AuthOperationResult.Success, repository.signOut())
        assertEquals(AuthSessionState.Guest, repository.session.value)

        provider.emit(providerAccount())
        assertEquals(AuthOperationResult.Success, repository.deleteAccount())
        assertEquals(AuthSessionState.Guest, repository.session.value)
    }

    @Test
    fun `reauthentication failure cannot delete the account`() = runTest {
        val provider = FakeAuthProvider(providerAccount()).apply {
            reauthenticateFailure = AuthProviderException(AuthFailure.INVALID_CREDENTIALS)
        }
        val repository = DefaultAuthRepository(provider)

        val reauthentication = repository.reauthenticate("wrong password")

        assertEquals(AuthOperationResult.Failure(AuthFailure.INVALID_CREDENTIALS), reauthentication)
        assertEquals(0, provider.deleteRequests)
        assertTrue(repository.session.value is AuthSessionState.Authenticated)
    }

    @Test
    fun `provider cancellation is never converted into an auth failure`() = runTest {
        val cancellation = CancellationException("screen closed")
        val provider = FakeAuthProvider().apply { signInFailure = cancellation }
        val repository = DefaultAuthRepository(provider)

        try {
            repository.signIn("person@example.com", "password")
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `provider exception details never cross the repository boundary`() = runTest {
        val provider = FakeAuthProvider().apply {
            signInFailure = AuthProviderException(
                failure = AuthFailure.INVALID_CREDENTIALS,
                message = "raw provider detail for person@example.com"
            )
        }
        val repository = DefaultAuthRepository(provider)

        val result = repository.signIn("person@example.com", "password")

        assertEquals(AuthOperationResult.Failure(AuthFailure.INVALID_CREDENTIALS), result)
        assertFalse(result.toString().contains("person@example.com"))
        assertFalse(result.toString().contains("raw provider detail"))
    }

    @Test
    fun `guest cannot request a backend ID token`() = runTest {
        val provider = FakeAuthProvider()
        val repository = DefaultAuthRepository(provider)

        val result = repository.backendIdToken()

        assertEquals(AuthIdTokenResult.AuthenticationRequired, result)
        assertEquals(0, provider.idTokenRequests)
    }

    @Test
    fun `authenticated account receives an ephemeral backend ID token without session mutation`() = runTest {
        val provider = FakeAuthProvider(providerAccount()).apply {
            idTokenResult = AuthProviderIdTokenResult.Success("ephemeral-id-token")
        }
        val repository = DefaultAuthRepository(provider)
        val sessionBefore = repository.session.value

        val result = repository.backendIdToken()

        assertEquals(AuthIdTokenResult.Success("ephemeral-id-token"), result)
        assertFalse(result.toString().contains("ephemeral-id-token"))
        assertEquals(1, provider.idTokenRequests)
        assertSame(sessionBefore, repository.session.value)
    }

    @Test
    fun `empty provider session maps to invalid session without session mutation`() = runTest {
        val provider = FakeAuthProvider(providerAccount()).apply {
            idTokenResult = AuthProviderIdTokenResult.InvalidSession
        }
        val repository = DefaultAuthRepository(provider)
        val sessionBefore = repository.session.value

        val result = repository.backendIdToken()

        assertEquals(AuthIdTokenResult.InvalidSession, result)
        assertSame(sessionBefore, repository.session.value)
    }

    @Test
    fun `backend ID token provider failure is bounded and cancellation is rethrown`() = runTest {
        val failureProvider = FakeAuthProvider(providerAccount()).apply {
            idTokenFailure = AuthProviderException(
                AuthFailure.NETWORK,
                "provider detail person@example.com"
            )
        }
        val failureRepository = DefaultAuthRepository(failureProvider)

        val failure = failureRepository.backendIdToken()

        assertEquals(AuthIdTokenResult.Failure(AuthFailure.NETWORK), failure)
        assertFalse(failure.toString().contains("person@example.com"))

        val cancellation = CancellationException("request cancelled")
        val cancellationRepository = DefaultAuthRepository(
            FakeAuthProvider(providerAccount()).apply { idTokenFailure = cancellation }
        )
        try {
            cancellationRepository.backendIdToken()
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `invalid Firebase user during backend token retrieval maps to invalid session`() = runTest {
        val provider = FakeAuthProvider(providerAccount()).apply {
            idTokenFailure = AuthProviderException(AuthFailure.INVALID_CREDENTIALS)
        }
        val repository = DefaultAuthRepository(provider)

        val result = repository.backendIdToken()

        assertEquals(AuthIdTokenResult.InvalidSession, result)
    }

    private class FakeAuthProvider(
        initialAccount: AuthProviderAccount? = null
    ) : AuthProvider {
        private var account: AuthProviderAccount? = initialAccount
        private var listener: ((AuthProviderAccount?) -> Unit)? = null
        var reloadAccount: AuthProviderAccount? = initialAccount
        var signInFailure: Throwable? = null
        var reauthenticateFailure: Throwable? = null
        var passwordResetFailure: Throwable? = null
        var verificationFailure: Throwable? = null
        var idTokenFailure: Throwable? = null
        var idTokenResult: AuthProviderIdTokenResult =
            AuthProviderIdTokenResult.Success("default-id-token")
        var idTokenRequests = 0
        var verificationRequests = 0
        var deleteRequests = 0
        var lastEmail: String? = null
        var lastPassword: String? = null

        override fun currentAccount(): AuthProviderAccount? = account

        override fun addAuthStateListener(listener: (AuthProviderAccount?) -> Unit): AuthListenerRegistration {
            this.listener = listener
            listener(account)
            return AuthListenerRegistration { this.listener = null }
        }

        override suspend fun createAccount(email: String, password: String): AuthProviderAccount {
            lastEmail = email
            lastPassword = password
            account = providerAccount(verified = false)
            listener?.invoke(account)
            return account!!
        }

        override suspend fun signIn(email: String, password: String): AuthProviderAccount {
            signInFailure?.let { throw it }
            lastEmail = email
            lastPassword = password
            account = providerAccount()
            listener?.invoke(account)
            return account!!
        }

        override suspend fun sendPasswordReset(email: String) {
            lastEmail = email
            passwordResetFailure?.let { throw it }
        }

        override suspend fun sendEmailVerification() {
            verificationRequests += 1
            verificationFailure?.let { throw it }
        }

        override suspend fun reloadCurrentAccount(): AuthProviderAccount? {
            account = reloadAccount
            listener?.invoke(account)
            return account
        }

        override fun signOut() {
            account = null
            listener?.invoke(null)
        }

        override suspend fun reauthenticate(password: String) {
            lastPassword = password
            reauthenticateFailure?.let { throw it }
        }

        override suspend fun deleteAccount() {
            deleteRequests += 1
            account = null
            listener?.invoke(null)
        }

        override suspend fun backendIdToken(): AuthProviderIdTokenResult {
            idTokenRequests += 1
            idTokenFailure?.let { throw it }
            return idTokenResult
        }

        fun emit(account: AuthProviderAccount?) {
            this.account = account
            listener?.invoke(account)
        }
    }

    private companion object {
        fun providerAccount(verified: Boolean = true) = AuthProviderAccount(
            uid = "firebase-uid",
            email = "person@example.com",
            emailVerified = verified
        )

        fun AuthProviderAccount.toSessionState() = AuthSessionState.Authenticated(
            uid = uid,
            email = email,
            emailVerified = emailVerified
        )
    }
}
