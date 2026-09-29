package com.example.ai.backend

import com.example.identity.AuthFailure
import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthOperationResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteAiConsentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: FakeAuthRepository
    private lateinit var repository: RemoteAiConsentRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth = FakeAuthRepository()
        repository = RemoteAiConsentRepository(
            authRepository = auth,
            service = RetrofitAiBackendService.create(server.url("/").toString())
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET sends ephemeral bearer and decodes current and stale server state`() = runTest {
        server.enqueue(jsonResponse(CONSENT_STATE_STALE))

        val result = repository.fetch()

        assertTrue(result is BackendResult.Success)
        val state = (result as BackendResult.Success<RemoteAiConsentState>).value
        assertEquals(RemoteAiConsentDecisionStatus.GRANTED_STALE, state.standardRemoteAi.status)
        assertEquals(RemoteAiConsentDecisionStatus.DECLINED, state.experimentalTraining.status)
        assertEquals("standard-v2", state.requiredNoticeVersions.standardRemoteAi)
        assertEquals(1, auth.tokenRequests)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/privacy/consent", request.path)
        assertEquals("Bearer ephemeral-consent-token", request.getHeader("Authorization"))
        assertFalse(result.toString().contains("ephemeral-consent-token"))
    }

    @Test
    fun `GET represents a current standard grant from server-derived currentness`() = runTest {
        server.enqueue(jsonResponse(CONSENT_STATE_CURRENT))

        val result = repository.fetch()

        assertTrue(result is BackendResult.Success)
        val state = (result as BackendResult.Success<RemoteAiConsentState>).value
        assertEquals(RemoteAiConsentDecisionStatus.GRANTED_CURRENT, state.standardRemoteAi.status)
        assertTrue(state.standardRemoteAi.current)
    }

    @Test
    fun `GET rejects impossible consent currentness instead of creating local truth`() = runTest {
        server.enqueue(jsonResponse(CONSENT_STATE_CURRENT.replace("\"current\": true", "\"current\": false")))

        val result = repository.fetch()

        assertTrue(result is BackendResult.InvalidResponse)
    }

    @Test
    fun `PUT standard grant uses exact server notice version without experimental mutation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.grantStandard("standard-v2")

        assertEquals(BackendResult.NoContent, result)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("Bearer ephemeral-consent-token", request.getHeader("Authorization"))
        assertEquals(
            "{\"standardRemoteAi\":{\"granted\":true,\"noticeVersion\":\"standard-v2\"}}",
            request.body.readUtf8()
        )
    }

    @Test
    fun `PUT standard decline sends no notice or experimental fields`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.declineStandard()

        assertEquals(BackendResult.NoContent, result)
        assertEquals(
            "{\"standardRemoteAi\":{\"granted\":false}}",
            server.takeRequest().body.readUtf8()
        )
    }

    @Test
    fun `DELETE withdraws server record with bearer and no request body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.withdraw()

        assertEquals(BackendResult.NoContent, result)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("Bearer ephemeral-consent-token", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `notice mismatch and authority failure remain bounded without raw messages`() = runTest {
        server.enqueue(errorResponse(409, "CONSENT_NOTICE_VERSION_MISMATCH", "raw stale notice detail"))
        server.enqueue(errorResponse(503, "CONSENT_AUTHORITY_INVALID_RECORD", "raw store record detail"))

        val mismatch = repository.grantStandard("standard-v1")
        val unavailable = repository.fetch()

        assertEquals(
            BackendResult.PublicError(
                BackendPublicError.CONSENT_NOTICE_VERSION_MISMATCH,
                requestId = "request-error"
            ),
            mismatch
        )
        assertEquals(
            BackendResult.PublicError(
                BackendPublicError.CONSENT_AUTHORITY_UNAVAILABLE,
                requestId = "request-error"
            ),
            unavailable
        )
        assertFalse(mismatch.toString().contains("raw stale notice detail"))
        assertFalse(unavailable.toString().contains("raw store record detail"))
    }

    @Test
    fun `guest and token failure dispatch no consent HTTP`() = runTest {
        val guest = FakeAuthRepository(state = AuthSessionState.Guest)
        val guestRepository = RemoteAiConsentRepository(
            guest,
            RetrofitAiBackendService.create(server.url("/").toString())
        )
        val tokenFailure = FakeAuthRepository(
            tokenResult = AuthIdTokenResult.Failure(AuthFailure.NETWORK)
        )
        val failureRepository = RemoteAiConsentRepository(
            tokenFailure,
            RetrofitAiBackendService.create(server.url("/").toString())
        )

        assertEquals(
            BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED),
            guestRepository.fetch()
        )
        assertEquals(
            BackendResult.PublicError(BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE),
            failureRepository.fetch()
        )
        assertEquals(0, server.requestCount)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun errorResponse(status: Int, code: String, message: String): MockResponse =
        jsonResponse(
            """
            {
              "success": false,
              "error": {
                "code": "$code",
                "message": "$message",
                "requestId": "request-error"
              }
            }
            """.trimIndent()
        ).setResponseCode(status)

    private class FakeAuthRepository(
        state: AuthSessionState = AUTHENTICATED,
        private val tokenResult: AuthIdTokenResult =
            AuthIdTokenResult.Success("ephemeral-consent-token")
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

    private companion object {
        val AUTHENTICATED = AuthSessionState.Authenticated(
            uid = "firebase-uid",
            email = "person@example.com",
            emailVerified = false
        )
        const val CONSENT_STATE_STALE = """
            {
              "success": true,
              "requestId": "consent-request",
              "data": {
                "schemaVersion": 1,
                "standardRemoteAi": {
                  "granted": true,
                  "current": false,
                  "noticeVersion": "standard-v1",
                  "decidedAt": "2026-09-14T10:00:00.000Z"
                },
                "experimentalTraining": {
                  "granted": false,
                  "current": false,
                  "noticeVersion": "experimental-v1",
                  "decidedAt": "2026-09-14T10:00:00.000Z"
                },
                "requiredNoticeVersions": {
                  "standardRemoteAi": "standard-v2",
                  "experimentalTraining": "experimental-v1"
                },
                "updatedAt": "2026-09-14T10:00:00.000Z"
              }
            }
        """
        const val CONSENT_STATE_CURRENT = """
            {
              "success": true,
              "requestId": "consent-current",
              "data": {
                "schemaVersion": 1,
                "standardRemoteAi": {
                  "granted": true,
                  "current": true,
                  "noticeVersion": "standard-v2",
                  "decidedAt": "2026-09-14T10:00:00.000Z"
                },
                "experimentalTraining": {
                  "granted": false,
                  "current": false,
                  "noticeVersion": "experimental-v1",
                  "decidedAt": "2026-09-14T10:00:00.000Z"
                },
                "requiredNoticeVersions": {
                  "standardRemoteAi": "standard-v2",
                  "experimentalTraining": "experimental-v1"
                },
                "updatedAt": "2026-09-14T10:00:00.000Z"
              }
            }
        """
    }
}
