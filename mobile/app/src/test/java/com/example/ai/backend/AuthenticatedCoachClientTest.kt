package com.example.ai.backend

import com.example.identity.AuthFailure
import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthOperationResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedCoachClientTest {
    @Test
    fun `guest does not request a token or dispatch Coach HTTP`() = runTest {
        val auth = FakeAuthRepository(AuthSessionState.Guest)
        val backend = FakeBackendService()

        val result = AuthenticatedCoachClient(auth, backend).submit(REQUEST)

        assertEquals(
            BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED),
            result
        )
        assertEquals(0, auth.tokenRequests)
        assertEquals(0, backend.coachCalls)
    }

    @Test
    fun `authenticated submission obtains one ephemeral token and sends valid headers`() = runTest {
        val auth = FakeAuthRepository(
            AUTHENTICATED,
            AuthIdTokenResult.Success("ephemeral-secret-token")
        )
        val backend = FakeBackendService()

        val result = AuthenticatedCoachClient(auth, backend).submit(REQUEST)

        assertTrue(result is BackendResult.Success)
        assertEquals(1, auth.tokenRequests)
        assertEquals(1, backend.coachCalls)
        assertEquals("ephemeral-secret-token", backend.idTokens.single())
        assertTrue(IDEMPOTENCY_PATTERN.matches(backend.idempotencyKeys.single()))
        assertEquals(listOf("Knee discomfort"), backend.requests.single().limitations)
        assertEquals("Vegetarian", backend.requests.single().dietaryPreference)
        assertEquals(4, backend.requests.single().workoutDays)
        assertEquals(3, backend.requests.single().mealsPerDay)
        assertFalse(result.toString().contains("ephemeral-secret-token"))
    }

    @Test
    fun `token failure prevents remote HTTP dispatch and remains bounded`() = runTest {
        val auth = FakeAuthRepository(
            AUTHENTICATED,
            AuthIdTokenResult.Failure(AuthFailure.NETWORK)
        )
        val backend = FakeBackendService()

        val result = AuthenticatedCoachClient(auth, backend).submit(REQUEST)

        assertEquals(
            BackendResult.PublicError(BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE),
            result
        )
        assertEquals(0, backend.coachCalls)
    }

    @Test
    fun `identical concurrent request shares one in-flight operation result and key`() = runTest {
        val auth = FakeAuthRepository(AUTHENTICATED)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val backend = FakeBackendService(beforeResponse = {
            entered.complete(Unit)
            release.await()
        })
        val client = AuthenticatedCoachClient(
            authRepository = auth,
            backendService = backend,
            idempotencyKeyFactory = { "operation-key-123456" }
        )

        val first = async { client.submit(REQUEST) }
        entered.await()
        val duplicate = async { client.submit(REQUEST) }
        advanceUntilIdle()

        assertEquals(1, backend.coachCalls)
        assertEquals(listOf("operation-key-123456"), backend.idempotencyKeys)
        assertEquals(listOf(REQUEST), backend.requests)
        release.complete(Unit)
        assertEquals(first.await(), duplicate.await())
        assertEquals(1, auth.tokenRequests)
    }

    @Test
    fun `distinct concurrent request is rejected without joining or second dispatch`() = runTest {
        val auth = FakeAuthRepository(AUTHENTICATED)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val backend = FakeBackendService(beforeResponse = {
            entered.complete(Unit)
            release.await()
        })
        val client = AuthenticatedCoachClient(
            authRepository = auth,
            backendService = backend,
            idempotencyKeyFactory = { "operation-key-123456" }
        )

        val first = async { client.submit(REQUEST) }
        entered.await()

        val distinctResult = client.submit(DISTINCT_REQUEST)

        assertEquals(
            BackendResult.PublicError(BackendPublicError.REMOTE_REQUEST_IN_PROGRESS),
            distinctResult
        )
        assertEquals(1, backend.coachCalls)
        assertEquals(listOf(REQUEST), backend.requests)
        assertEquals(listOf("operation-key-123456"), backend.idempotencyKeys)
        assertEquals(1, auth.tokenRequests)

        release.complete(Unit)
        val firstResult = first.await()
        assertTrue(firstResult is BackendResult.Success)
        assertNotEquals(firstResult, distinctResult)
    }

    @Test
    fun `explicit later retry creates a new idempotency key`() = runTest {
        val auth = FakeAuthRepository(AUTHENTICATED)
        val backend = FakeBackendService()
        val keys = ArrayDeque(listOf("operation-key-123456", "operation-key-654321"))
        val client = AuthenticatedCoachClient(
            authRepository = auth,
            backendService = backend,
            idempotencyKeyFactory = { keys.removeFirst() }
        )

        client.submit(REQUEST)
        client.submit(DISTINCT_REQUEST)

        assertEquals(2, backend.coachCalls)
        assertEquals(2, auth.tokenRequests)
        assertEquals(2, backend.idempotencyKeys.distinct().size)
        assertNotEquals(backend.idempotencyKeys[0], backend.idempotencyKeys[1])
        assertEquals(listOf(REQUEST, DISTINCT_REQUEST), backend.requests)
    }

    private class FakeAuthRepository(
        state: AuthSessionState,
        private val tokenResult: AuthIdTokenResult = AuthIdTokenResult.Success("ephemeral-token")
    ) : AuthRepository {
        override val session: StateFlow<AuthSessionState> = MutableStateFlow(state)
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
        private val beforeResponse: suspend () -> Unit = {}
    ) : AiBackendService {
        var coachCalls = 0
        val idTokens = mutableListOf<String>()
        val idempotencyKeys = mutableListOf<String>()
        val requests = mutableListOf<CoachRequestDto>()

        override suspend fun authSession(idToken: String) = BackendSessionResult.BackendUnavailable
        override suspend fun health() = BackendResult.BackendUnavailable("Not used")

        override suspend fun coach(
            idToken: String,
            idempotencyKey: String,
            request: CoachRequestDto
        ): BackendResult<CoachResponseDto> {
            coachCalls += 1
            idTokens += idToken
            idempotencyKeys += idempotencyKey
            requests += request
            beforeResponse()
            return BackendResult.Success(
                value = RESPONSE,
                requestId = "request-id",
                mode = BackendExecutionMode.REMOTE
            )
        }

        override suspend fun workoutPlan(request: WorkoutPlanRequestDto) = error("Not used")
        override suspend fun foodAnalyze(request: FoodAnalyzeRequestDto) = error("Not used")
        override suspend fun dietPlan(request: DietPlanRequestDto) = error("Not used")
        override suspend fun yogaPlan(request: YogaPlanRequestDto) = error("Not used")
        override suspend fun progressReview(request: ProgressReviewRequestDto) = error("Not used")
    }

    private companion object {
        val AUTHENTICATED = AuthSessionState.Authenticated(
            uid = "firebase-uid",
            email = "person@example.com",
            emailVerified = true
        )
        val REQUEST = CoachRequestDto(
            question = "How can I train safely?",
            dietaryPreference = "Vegetarian",
            mealsPerDay = 3,
            workoutDays = 4,
            limitations = listOf("Knee discomfort")
        )
        val DISTINCT_REQUEST = CoachRequestDto("Give me a different training plan.")
        val RESPONSE = CoachResponseDto(
            summary = "Summary",
            recommendedAction = "Action",
            nutritionNote = "Nutrition",
            workoutNote = "Workout",
            safetyDisclaimer = "Safety"
        )
        val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9._~-]{16,128}")
    }
}
