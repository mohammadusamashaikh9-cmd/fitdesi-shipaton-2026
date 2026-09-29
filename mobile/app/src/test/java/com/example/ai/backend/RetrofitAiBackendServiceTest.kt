package com.example.ai.backend

import com.example.ai.AiCoachRepository
import com.example.ai.AiCoachRequest
import com.example.ai.AiCoachResponse
import com.example.ai.AiRepository
import com.example.ai.AiRuntimeMode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitAiBackendServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AiBackendService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = RetrofitAiBackendService.create(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulHealthCheck_mapsStrictMockEnvelope() = runTest {
        server.enqueue(jsonResponse(HEALTH_SUCCESS))

        val result = service.health()

        assertTrue(result is BackendResult.Success)
        assertEquals(
            "mock",
            (result as BackendResult.Success<HealthResponseDto>).value.providerMode
        )
        val request = server.takeRequest()
        assertEquals("/api/health", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun authSession_sendsExactlyOnePerCallBearerHeaderAndMapsMinimalSuccess() = runTest {
        server.enqueue(jsonResponse(AUTH_SESSION_SUCCESS))

        val result = service.authSession("ephemeral-id-token")

        assertEquals(
            BackendSessionResult.Success(
                authenticated = true,
                emailVerified = false,
                requestId = "auth-request"
            ),
            result
        )
        val request = server.takeRequest()
        assertEquals("/api/auth/session", request.path)
        assertEquals(
            listOf("Bearer ephemeral-id-token"),
            request.headers.values("Authorization")
        )
    }

    @Test
    fun authSession_keepsInvalidSessionAndVerificationUnavailableDistinct() = runTest {
        server.enqueue(jsonResponse(AUTH_INVALID_SESSION, statusCode = 401))
        server.enqueue(jsonResponse(AUTH_VERIFICATION_UNAVAILABLE, statusCode = 503))

        val invalid = service.authSession("expired-token")
        val unavailable = service.authSession("valid-token")

        assertEquals(BackendSessionResult.InvalidSession("invalid-request"), invalid)
        assertEquals(
            BackendSessionResult.AuthVerificationUnavailable("unavailable-request"),
            unavailable
        )
    }

    @Test
    fun backendBaseUrl_allowsHttpsAndLocalEmulatorCleartextOnly() {
        RetrofitAiBackendService.create("https://api.fitdesi.example/")
        RetrofitAiBackendService.create("http://10.0.2.2:3000/")

        assertThrows(IllegalArgumentException::class.java) {
            RetrofitAiBackendService.create("http://api.fitdesi.example/")
        }
    }

    @Test
    fun successfulCoachResponse_mapsStructuredSections() = runTest {
        server.enqueue(jsonResponse(COACH_SUCCESS))

        val result = service.coach(
            TOKEN,
            IDEMPOTENCY_KEY,
            CoachRequestDto(
                question = "How can I build muscle safely?",
                dietaryPreference = "Vegetarian",
                mealsPerDay = 4,
                workoutDays = 3,
                limitations = listOf("Knee discomfort"),
                conversationContext = listOf(
                    CoachConversationContextDto("user", "Prior question"),
                    CoachConversationContextDto("assistant", "Prior answer")
                )
            )
        )

        assertTrue(result is BackendResult.Success)
        assertEquals(
            "Structured summary",
            (result as BackendResult.Success<CoachResponseDto>).value.summary
        )
        val request = server.takeRequest()
        assertEquals("/api/ai/coach", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals(IDEMPOTENCY_KEY, request.getHeader("Idempotency-Key"))
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"question\""))
        assertTrue(requestBody.contains("\"conversationContext\""))
        assertTrue(requestBody.contains("\"role\":\"assistant\""))
        assertTrue(requestBody.contains("\"text\":\"Prior answer\""))
        assertTrue(requestBody.contains("\"dietaryPreference\":\"Vegetarian\""))
        assertTrue(requestBody.contains("\"mealsPerDay\":4"))
        assertTrue(requestBody.contains("\"workoutDays\":3"))
        assertTrue(requestBody.contains("\"limitations\":[\"Knee discomfort\"]"))
    }

    @Test
    fun coachRequest_omitsAbsentNullableProfileValues() = runTest {
        server.enqueue(jsonResponse(COACH_SUCCESS))

        service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("Question"))

        val requestBody = server.takeRequest().body.readUtf8()
        assertFalse(requestBody.contains("\"dietaryPreference\""))
        assertFalse(requestBody.contains("\"mealsPerDay\""))
        assertFalse(requestBody.contains("\"workoutDays\""))
        assertTrue(requestBody.contains("\"limitations\":[]"))
    }

    @Test
    fun legacyRemoteCoachResponse_mapsModeToProviderNeutralTransportState() = runTest {
        server.enqueue(jsonResponse(FIREWORKS_COACH_SUCCESS))

        val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("Give me a plan"))

        assertTrue(result is BackendResult.Success)
        val success = result as BackendResult.Success<CoachResponseDto>
        assertEquals(BackendExecutionMode.REMOTE, success.mode)
        assertEquals("FIREWORKS", success.value.sourceType)
        assertEquals(listOf("Keep the plan gradual."), success.value.warnings)
    }

    @Test
    fun unknownCoachProperty_isRejectedByStrictMoshiContract() = runTest {
        server.enqueue(jsonResponse(COACH_SUCCESS.replace(
            "\"summary\": \"Structured summary\"",
            "\"summary\": \"Structured summary\", \"unexpected\": true"
        )))

        assertTrue(
            service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("Question"))
                is BackendResult.InvalidResponse
        )
    }

    @Test
    fun http400_mapsValidationError() = runTest {
        server.enqueue(jsonResponse(ERROR_400, statusCode = 400))

        val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto(""))

        assertEquals(
            BackendResult.PublicError(
                BackendPublicError.VALIDATION_ERROR,
                requestId = "request-400"
            ),
            result
        )
    }

    @Test
    fun http413_mapsPayloadTooLarge() = runTest {
        server.enqueue(jsonResponse(ERROR_413, statusCode = 413))

        val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("question"))

        assertEquals(
            BackendResult.PublicError(
                BackendPublicError.PAYLOAD_TOO_LARGE,
                requestId = "request-413"
            ),
            result
        )
    }

    @Test
    fun http429_mapsRateLimitAndRetryAfter() = runTest {
        server.enqueue(
            jsonResponse(ERROR_429, statusCode = 429)
                .setHeader("Retry-After", "12")
        )

        val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("question"))

        assertEquals(
            BackendResult.PublicError(
                BackendPublicError.RATE_LIMITED,
                retryAfterSeconds = 12L,
                requestId = "request-429"
            ),
            result
        )
    }

    @Test
    fun publicErrorCodes_mapToBoundedProviderNeutralClassifications() = runTest {
        val cases = listOf(
            Triple(401, "AUTH_REQUIRED", BackendPublicError.AUTH_REQUIRED),
            Triple(401, "INVALID_SESSION", BackendPublicError.INVALID_SESSION),
            Triple(503, "AUTH_VERIFICATION_UNAVAILABLE", BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE),
            Triple(403, "EMAIL_VERIFICATION_REQUIRED", BackendPublicError.EMAIL_VERIFICATION_REQUIRED),
            Triple(403, "REMOTE_AI_CONSENT_REQUIRED", BackendPublicError.REMOTE_AI_CONSENT_REQUIRED),
            Triple(409, "CONSENT_NOTICE_VERSION_MISMATCH", BackendPublicError.CONSENT_NOTICE_VERSION_MISMATCH),
            Triple(503, "CONSENT_AUTHORITY_TIMEOUT", BackendPublicError.CONSENT_AUTHORITY_UNAVAILABLE),
            Triple(403, "REMOTE_CAPABILITY_UNAVAILABLE", BackendPublicError.REMOTE_CAPABILITY_UNAVAILABLE),
            Triple(403, "REMOTE_CAPABILITY_NOT_LAUNCHED", BackendPublicError.REMOTE_CAPABILITY_NOT_LAUNCHED),
            Triple(503, "QUOTA_POLICY_UNAVAILABLE", BackendPublicError.QUOTA_POLICY_UNAVAILABLE),
            Triple(429, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED", BackendPublicError.REMOTE_ACCOUNT_QUOTA_EXHAUSTED),
            Triple(503, "REMOTE_GLOBAL_BUDGET_UNAVAILABLE", BackendPublicError.REMOTE_GLOBAL_BUDGET_UNAVAILABLE),
            Triple(400, "IDEMPOTENCY_REQUIRED", BackendPublicError.IDEMPOTENCY_REQUIRED),
            Triple(400, "IDEMPOTENCY_INVALID", BackendPublicError.IDEMPOTENCY_INVALID),
            Triple(409, "IDEMPOTENCY_CONFLICT", BackendPublicError.IDEMPOTENCY_CONFLICT),
            Triple(409, "REMOTE_REQUEST_IN_PROGRESS", BackendPublicError.REMOTE_REQUEST_IN_PROGRESS),
            Triple(409, "REMOTE_REQUEST_COMPLETED", BackendPublicError.REMOTE_REQUEST_COMPLETED),
            Triple(504, "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT", BackendPublicError.REMOTE_ADMISSION_UNAVAILABLE),
            Triple(503, "PROVIDER_UNAVAILABLE", BackendPublicError.PROVIDER_UNAVAILABLE),
            Triple(429, "PROVIDER_RATE_LIMITED", BackendPublicError.PROVIDER_RATE_LIMITED),
            Triple(504, "PROVIDER_TIMEOUT", BackendPublicError.PROVIDER_TIMEOUT),
            Triple(502, "PROVIDER_INVALID_RESPONSE", BackendPublicError.PROVIDER_INVALID_RESPONSE),
            Triple(503, "PROVIDER_CIRCUIT_OPEN", BackendPublicError.PROVIDER_BUSY),
            Triple(503, "COMMERCIAL_AUTHORITY_UNAVAILABLE", BackendPublicError.COMMERCIAL_AUTHORITY_UNAVAILABLE),
            Triple(408, "REQUEST_TIMEOUT", BackendPublicError.REQUEST_TIMEOUT),
            Triple(500, "INVALID_RESPONSE", BackendPublicError.INVALID_RESPONSE),
            Triple(500, "INTERNAL_ERROR", BackendPublicError.INTERNAL_ERROR),
            Triple(418, "UNRECOGNIZED_INTERNAL_CODE", BackendPublicError.REMOTE_UNAVAILABLE)
        )

        cases.forEachIndexed { index, (status, code, expected) ->
            server.enqueue(
                jsonResponse(
                    errorEnvelope(code, "raw provider detail $index", index),
                    statusCode = status
                ).setHeader("Retry-After", "99999")
            )

            val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("question"))

            assertEquals(
                BackendResult.PublicError(
                    code = expected,
                    retryAfterSeconds = 3_600L,
                    requestId = "request-$index"
                ),
                result
            )
            assertTrue(!result.toString().contains("raw provider detail"))
        }
    }

    @Test
    fun invalidCredentialsFailBeforeCoachHttpDispatch() = runTest {
        assertEquals(
            BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED),
            service.coach("", IDEMPOTENCY_KEY, CoachRequestDto("question"))
        )
        assertEquals(
            BackendResult.PublicError(BackendPublicError.IDEMPOTENCY_INVALID),
            service.coach(TOKEN, "short", CoachRequestDto("question"))
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun timeout_mapsTimeoutState() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutService = RetrofitAiBackendService.create(
            server.url("/").toString(),
            networkTimeoutMilliseconds = 100
        )

        val result = shortTimeoutService.health()

        assertTrue(result is BackendResult.Timeout)
    }

    @Test
    fun malformedResponse_mapsInvalidResponse() = runTest {
        server.enqueue(jsonResponse("""{"success":true,"requestId":"id","mode":"mock","data":{"summary":42}}"""))

        val result = service.coach(TOKEN, IDEMPOTENCY_KEY, CoachRequestDto("question"))

        assertTrue(result is BackendResult.InvalidResponse)
    }

    @Test
    fun unavailableBackend_mapsBackendUnavailable() = runTest {
        val unavailableUrl = server.url("/").toString()
        server.shutdown()
        server = MockWebServer()
        val unavailableService = RetrofitAiBackendService.create(
            unavailableUrl,
            networkTimeoutMilliseconds = 200
        )

        val result = unavailableService.health()

        assertTrue(result is BackendResult.BackendUnavailable)
        server = MockWebServer()
        server.start()
    }

    @Test
    fun backendHttpFailure_activatesLocalCoachFallback() = runTest {
        server.enqueue(jsonResponse(ERROR_503, statusCode = 503))
        val local = object : AiCoachRepository {
            override suspend fun ask(question: String): Result<AiCoachResponse> =
                Result.success(
                    AiCoachResponse(
                        summary = "Local response",
                        recommendedAction = "Local action",
                        nutritionNote = "Local nutrition",
                        workoutNote = "Local workout",
                        safetyDisclaimer = "Local safety"
                    )
                )
        }
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = service,
            coachClient = CoachRemoteClient { request ->
                service.coach(TOKEN, IDEMPOTENCY_KEY, request)
            },
            localCoach = local
        )

        val result = repository.askCoach(AiCoachRequest("Use fallback")).getOrThrow()

        assertEquals("Local response", result.summary)
        assertEquals("/api/ai/coach", server.takeRequest().path)
    }

    private fun jsonResponse(body: String, statusCode: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun errorEnvelope(code: String, message: String, index: Int = 0): String = """
        {
          "success": false,
          "error": {
            "code": "$code",
            "message": "$message",
            "requestId": "request-$index"
          }
        }
    """.trimIndent()

    companion object {
        private const val TOKEN = "ephemeral-id-token"
        private const val IDEMPOTENCY_KEY = "operation-key-123456"
        private const val HEALTH_SUCCESS = """
            {
              "success": true,
              "requestId": "health-request",
              "mode": "mock",
              "data": {
                "status": "ok",
                "service": "fitdesi-ai-backend",
                "providerMode": "mock",
                "timestamp": "2026-07-16T12:00:00.000Z"
              }
            }
        """

        private const val AUTH_SESSION_SUCCESS = """
            {
              "success": true,
              "requestId": "auth-request",
              "data": {
                "authenticated": true,
                "emailVerified": false
              }
            }
        """

        private const val AUTH_INVALID_SESSION = """
            {
              "success": false,
              "error": {
                "code": "INVALID_SESSION",
                "message": "Your session is invalid or expired. Sign in again.",
                "requestId": "invalid-request"
              }
            }
        """

        private const val AUTH_VERIFICATION_UNAVAILABLE = """
            {
              "success": false,
              "error": {
                "code": "AUTH_VERIFICATION_UNAVAILABLE",
                "message": "Session verification is temporarily unavailable. Please try again.",
                "requestId": "unavailable-request"
              }
            }
        """

        private const val COACH_SUCCESS = """
            {
              "success": true,
              "requestId": "coach-request",
              "mode": "mock",
              "data": {
                "summary": "Structured summary",
                "recommendedAction": "Take one action",
                "nutritionNote": "Use balanced meals",
                "workoutNote": "Progress gradually",
                "safetyDisclaimer": "General fitness guidance only"
              }
            }
        """

        private const val FIREWORKS_COACH_SUCCESS = """
            {
              "success": true,
              "requestId": "fireworks-request",
              "mode": "fireworks",
              "data": {
                "summary": "Structured summary",
                "recommendedAction": "Take one action",
                "nutritionNote": "Use balanced meals",
                "workoutNote": "Progress gradually",
                "safetyDisclaimer": "General fitness guidance only",
                "sourceType": "FIREWORKS",
                "confidence": 0.8,
                "warnings": ["Keep the plan gradual."]
              }
            }
        """

        private const val ERROR_400 = """
            {
              "success": false,
              "error": {
                "code": "VALIDATION_ERROR",
                "message": "Question is required.",
                "requestId": "request-400"
              }
            }
        """

        private const val ERROR_413 = """
            {
              "success": false,
              "error": {
                "code": "PAYLOAD_TOO_LARGE",
                "message": "Request body is too large.",
                "requestId": "request-413"
              }
            }
        """

        private const val ERROR_429 = """
            {
              "success": false,
              "error": {
                "code": "RATE_LIMITED",
                "message": "Please wait.",
                "requestId": "request-429"
              }
            }
        """

        private const val ERROR_503 = """
            {
              "success": false,
              "error": {
                "code": "INTERNAL_ERROR",
                "message": "Temporarily unavailable.",
                "requestId": "request-503"
              }
            }
        """
    }
}
