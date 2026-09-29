package com.example.ai.backend

import com.example.identity.AuthFailure
import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthOperationResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendSessionClientTest {
    @Test
    fun `initializing and guest sessions do not request a token or HTTP call`() = runTest {
        for (state in listOf(AuthSessionState.Initializing, AuthSessionState.Guest)) {
            val auth = FakeAuthRepository(state, AuthIdTokenResult.Success("must-not-be-read"))
            val backend = FakeBackendService()

            val result = BackendSessionClient(auth, backend).getSession()

            assertEquals(BackendSessionResult.AuthenticationRequired, result)
            assertEquals(0, auth.tokenRequests)
            assertEquals(0, backend.sessionRequests)
        }
    }

    @Test
    fun `authenticated session obtains one ephemeral token and makes one backend call`() = runTest {
        val auth = FakeAuthRepository(
            authenticatedSession(),
            AuthIdTokenResult.Success("ephemeral-token")
        )
        val expected = BackendSessionResult.Success(
            authenticated = true,
            emailVerified = false,
            requestId = "request-id"
        )
        val backend = FakeBackendService(expected)

        val result = BackendSessionClient(auth, backend).getSession()

        assertEquals(expected, result)
        assertEquals(1, auth.tokenRequests)
        assertEquals(1, backend.sessionRequests)
        assertEquals("ephemeral-token", backend.lastIdToken)
    }

    @Test
    fun `token failure sends no HTTP request and preserves the bounded auth failure`() = runTest {
        val auth = FakeAuthRepository(
            authenticatedSession(),
            AuthIdTokenResult.Failure(AuthFailure.NETWORK)
        )
        val backend = FakeBackendService()

        val result = BackendSessionClient(auth, backend).getSession()

        assertEquals(BackendSessionResult.TokenFailure(AuthFailure.NETWORK), result)
        assertEquals(0, backend.sessionRequests)
    }

    private class FakeAuthRepository(
        initialSession: AuthSessionState,
        private val tokenResult: AuthIdTokenResult
    ) : AuthRepository {
        override val session: StateFlow<AuthSessionState> = MutableStateFlow(initialSession)
        var tokenRequests = 0

        override suspend fun backendIdToken(): AuthIdTokenResult {
            tokenRequests += 1
            return tokenResult
        }

        override suspend fun createAccount(email: String, password: String) = unsupported()
        override suspend fun signIn(email: String, password: String) = unsupported()
        override suspend fun sendPasswordReset(email: String) = unsupported()
        override suspend fun resendVerification() = unsupported()
        override suspend fun refreshCurrentUser() = unsupported()
        override suspend fun signOut() = unsupported()
        override suspend fun reauthenticate(password: String) = unsupported()
        override suspend fun deleteAccount() = unsupported()

        private fun unsupported(): AuthOperationResult =
            AuthOperationResult.Failure(AuthFailure.UNAVAILABLE)
    }

    private class FakeBackendService(
        private val sessionResult: BackendSessionResult = BackendSessionResult.InvalidSession(null)
    ) : AiBackendService {
        var sessionRequests = 0
        var lastIdToken: String? = null

        override suspend fun authSession(idToken: String): BackendSessionResult {
            sessionRequests += 1
            lastIdToken = idToken
            return sessionResult
        }

        override suspend fun health() = error("Not used")
        override suspend fun coach(
            idToken: String,
            idempotencyKey: String,
            request: CoachRequestDto
        ) = error("Not used")
        override suspend fun workoutPlan(request: WorkoutPlanRequestDto) = error("Not used")
        override suspend fun foodAnalyze(request: FoodAnalyzeRequestDto) = error("Not used")
        override suspend fun dietPlan(request: DietPlanRequestDto) = error("Not used")
        override suspend fun yogaPlan(request: YogaPlanRequestDto) = error("Not used")
        override suspend fun progressReview(request: ProgressReviewRequestDto) = error("Not used")
    }

    private companion object {
        fun authenticatedSession() = AuthSessionState.Authenticated(
            uid = "firebase-uid",
            email = "person@example.com",
            emailVerified = false
        )
    }
}
