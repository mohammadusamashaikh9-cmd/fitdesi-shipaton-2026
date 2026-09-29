package com.example.ai.backend

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class RetrofitAiBackendService private constructor(
    private val api: AiBackendApi,
    private val moshi: Moshi
) : FitDesiBackendService {

    override suspend fun authSession(idToken: String): BackendSessionResult {
        if (idToken.isBlank()) return BackendSessionResult.InvalidSession(null)
        return try {
            val response = api.authSession("Bearer $idToken")
            if (!response.isSuccessful) return mapAuthSessionError(response)

            val envelope = response.body() ?: return BackendSessionResult.InvalidResponse
            if (!envelope.success ||
                envelope.requestId.isBlank() ||
                !envelope.data.authenticated
            ) {
                return BackendSessionResult.InvalidResponse
            }
            BackendSessionResult.Success(
                authenticated = true,
                emailVerified = envelope.data.emailVerified,
                requestId = envelope.requestId
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            BackendSessionResult.InvalidResponse
        } catch (_: JsonEncodingException) {
            BackendSessionResult.InvalidResponse
        } catch (_: InterruptedIOException) {
            BackendSessionResult.BackendUnavailable
        } catch (_: IOException) {
            BackendSessionResult.BackendUnavailable
        } catch (_: Exception) {
            BackendSessionResult.BackendUnavailable
        }
    }

    override suspend fun health(): BackendResult<HealthResponseDto> =
        execute({ api.health() }, ::validHealth, COACH_MODES)

    override suspend fun coach(
        idToken: String,
        idempotencyKey: String,
        request: CoachRequestDto
    ): BackendResult<CoachResponseDto> {
        if (idToken.isBlank()) {
            return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            return BackendResult.PublicError(BackendPublicError.IDEMPOTENCY_INVALID)
        }
        return execute(
            call = { api.coach("Bearer $idToken", idempotencyKey, request) },
            validateData = ::validCoach,
            acceptedModes = COACH_MODES
        )
    }

    override suspend fun consentState(idToken: String): BackendResult<RemoteAiConsentState> {
        if (idToken.isBlank()) return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        return try {
            val response = api.consentState("Bearer $idToken")
            if (!response.isSuccessful) return mapHttpError(response)
            val envelope = response.body()
                ?: return BackendResult.InvalidResponse("The backend returned an empty response.")
            val state = envelope.data.toRemoteAiConsentState()
                ?: return BackendResult.InvalidResponse(
                    "The backend response did not match the expected schema."
                )
            if (!envelope.success || envelope.requestId.isBlank()) {
                return BackendResult.InvalidResponse(
                    "The backend response did not match the expected schema."
                )
            }
            BackendResult.Success(state, envelope.requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            BackendResult.InvalidResponse("The backend response could not be validated.")
        } catch (_: JsonEncodingException) {
            BackendResult.InvalidResponse("The backend returned malformed JSON.")
        } catch (_: InterruptedIOException) {
            BackendResult.Timeout("The backend request timed out.")
        } catch (_: IOException) {
            BackendResult.BackendUnavailable("The FitDesi backend could not be reached.")
        } catch (_: Exception) {
            BackendResult.BackendUnavailable("The FitDesi backend is unavailable.")
        }
    }

    override suspend fun updateStandardConsent(
        idToken: String,
        granted: Boolean,
        noticeVersion: String?
    ): BackendResult<Unit> {
        if (idToken.isBlank()) return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        val request = RemoteAiConsentMutationDto(
            standardRemoteAi = RemoteAiStandardConsentDecisionDto(
                granted = granted,
                noticeVersion = noticeVersion
            )
        )
        return executeNoContent { api.updateConsent("Bearer $idToken", request) }
    }

    override suspend fun deleteConsent(idToken: String): BackendResult<Unit> {
        if (idToken.isBlank()) return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        return executeNoContent { api.deleteConsent("Bearer $idToken") }
    }

    override suspend fun workoutPlan(
        request: WorkoutPlanRequestDto
    ): BackendResult<WorkoutPlanResponseDto> =
        execute({ api.workoutPlan(request) }, ::validWorkoutResponse)

    override suspend fun foodAnalyze(
        request: FoodAnalyzeRequestDto
    ): BackendResult<FoodAnalyzeResponseDto> =
        execute({ api.foodAnalyze(request) }, ::validFoodResponse)

    override suspend fun dietPlan(
        request: DietPlanRequestDto
    ): BackendResult<DietPlanResponseDto> =
        execute({ api.dietPlan(request) }, ::validDietResponse)

    override suspend fun yogaPlan(
        request: YogaPlanRequestDto
    ): BackendResult<YogaPlanResponseDto> =
        execute({ api.yogaPlan(request) }, ::validYogaResponse)

    override suspend fun progressReview(
        request: ProgressReviewRequestDto
    ): BackendResult<ProgressReviewResponseDto> =
        execute({ api.progressReview(request) }, ::validProgressResponse)

    private suspend fun <T> execute(
        call: suspend () -> Response<BackendSuccessEnvelope<T>>,
        validateData: (T) -> Boolean,
        acceptedModes: Set<String> = MOCK_ONLY_MODE
    ): BackendResult<T> {
        return try {
            val response = call()
            if (!response.isSuccessful) return mapHttpError(response)

            val envelope = response.body()
                ?: return BackendResult.InvalidResponse("The backend returned an empty response.")
            if (!envelope.success ||
                envelope.mode !in acceptedModes ||
                envelope.requestId.isBlank() ||
                !validateData(envelope.data)
            ) {
                return BackendResult.InvalidResponse("The backend response did not match the expected schema.")
            }
            BackendResult.Success(
                value = envelope.data,
                requestId = envelope.requestId,
                mode = when (envelope.mode) {
                    MODE_MOCK -> BackendExecutionMode.MOCK
                    else -> BackendExecutionMode.REMOTE
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonDataException) {
            BackendResult.InvalidResponse("The backend response could not be validated.")
        } catch (_: JsonEncodingException) {
            BackendResult.InvalidResponse("The backend returned malformed JSON.")
        } catch (_: InterruptedIOException) {
            BackendResult.Timeout("The backend request timed out.")
        } catch (_: ConnectException) {
            BackendResult.BackendUnavailable("The FitDesi backend is unavailable.")
        } catch (_: UnknownHostException) {
            BackendResult.BackendUnavailable("The FitDesi backend is unavailable.")
        } catch (_: IOException) {
            BackendResult.BackendUnavailable("The FitDesi backend could not be reached.")
        } catch (_: Exception) {
            BackendResult.UnknownError("The backend request could not be completed.")
        }
    }

    private suspend fun executeNoContent(
        call: suspend () -> Response<Unit>
    ): BackendResult<Unit> = try {
        val response = call()
        when {
            !response.isSuccessful -> mapHttpError(response)
            response.code() == 204 -> BackendResult.NoContent
            else -> BackendResult.InvalidResponse("The backend response did not match the expected schema.")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: InterruptedIOException) {
        BackendResult.Timeout("The backend request timed out.")
    } catch (_: IOException) {
        BackendResult.BackendUnavailable("The FitDesi backend could not be reached.")
    } catch (_: Exception) {
        BackendResult.BackendUnavailable("The FitDesi backend is unavailable.")
    }

    private fun <T> mapHttpError(response: Response<T>): BackendResult<Nothing> {
        val parsed = response.errorBody()?.string()?.let { body ->
            runCatching {
                moshi.adapter(BackendErrorEnvelope::class.java)
                    .failOnUnknown()
                    .fromJson(body)
            }.getOrNull()
        }?.takeIf {
            !it.success &&
                it.error.code.isNotBlank() &&
                it.error.message.isNotBlank() &&
                it.error.requestId.isNotBlank()
        }
        val errorCode = parsed?.error?.code
        val requestId = parsed?.error?.requestId
        val classification = errorCode?.toBackendPublicError() ?: when (response.code()) {
            400 -> BackendPublicError.VALIDATION_ERROR
            408 -> BackendPublicError.REQUEST_TIMEOUT
            413 -> BackendPublicError.PAYLOAD_TOO_LARGE
            429 -> BackendPublicError.RATE_LIMITED
            in 500..599 -> BackendPublicError.INTERNAL_ERROR
            else -> BackendPublicError.REMOTE_UNAVAILABLE
        }
        return BackendResult.PublicError(
            code = classification,
            retryAfterSeconds = response.headers()["Retry-After"]
                ?.toLongOrNull()
                ?.coerceIn(MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS),
            requestId = requestId
        )
    }

    private fun <T> mapAuthSessionError(response: Response<T>): BackendSessionResult {
        val parsed = response.errorBody()?.string()?.let { body ->
            runCatching {
                moshi.adapter(BackendErrorEnvelope::class.java)
                    .failOnUnknown()
                    .fromJson(body)
            }.getOrNull()
        }?.takeIf {
            !it.success &&
                it.error.code.isNotBlank() &&
                it.error.message.isNotBlank() &&
                it.error.requestId.isNotBlank()
        }
        val code = parsed?.error?.code
        val requestId = parsed?.error?.requestId
        return when {
            response.code() == 401 && code == "AUTH_REQUIRED" ->
                BackendSessionResult.AuthenticationRequired
            response.code() == 401 && code == "INVALID_SESSION" ->
                BackendSessionResult.InvalidSession(requestId)
            response.code() == 503 && code == "AUTH_VERIFICATION_UNAVAILABLE" ->
                BackendSessionResult.AuthVerificationUnavailable(requestId)
            response.code() in 500..599 -> BackendSessionResult.BackendUnavailable
            else -> BackendSessionResult.InvalidResponse
        }
    }

    companion object {
        private val LOCAL_CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
        private val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._~-]{16,128}")
        private const val MODE_MOCK = "mock"
        private val MOCK_ONLY_MODE = setOf("mock")
        private val COACH_MODES = setOf("mock", "fireworks")
        private val ALLOWED_SOURCES = setOf(
            "LOCAL_KNOWLEDGE", "LOCAL_FALLBACK", "BACKEND_MOCK", "FIREWORKS"
        )
        private val ALLOWED_INTENTS = setOf(
            "GENERAL_COACHING", "EXERCISE_QUESTION", "WORKOUT_PLAN",
            "PAKISTANI_DIET_PLAN", "FOOD_QUESTION", "PROGRESS_REVIEW", "YOGA_PLAN",
            "MEDICAL_ESCALATION", "UNSUPPORTED"
        )
        private const val NETWORK_TIMEOUT_MILLISECONDS = 10_000L
        private const val MIN_RETRY_AFTER_SECONDS = 1L
        private const val MAX_RETRY_AFTER_SECONDS = 3_600L

        private fun String.toBackendPublicError(): BackendPublicError = when (this) {
            "AUTH_REQUIRED" -> BackendPublicError.AUTH_REQUIRED
            "INVALID_SESSION" -> BackendPublicError.INVALID_SESSION
            "AUTH_VERIFICATION_UNAVAILABLE" -> BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE
            "EMAIL_VERIFICATION_REQUIRED" -> BackendPublicError.EMAIL_VERIFICATION_REQUIRED
            "REMOTE_AI_CONSENT_REQUIRED" -> BackendPublicError.REMOTE_AI_CONSENT_REQUIRED
            "CONSENT_NOTICE_VERSION_MISMATCH" -> BackendPublicError.CONSENT_NOTICE_VERSION_MISMATCH
            "CONSENT_AUTHORITY_NOT_CONFIGURED",
            "CONSENT_AUTHORITY_INVALID_RECORD",
            "CONSENT_AUTHORITY_TIMEOUT",
            "CONSENT_AUTHORITY_UNAVAILABLE" -> BackendPublicError.CONSENT_AUTHORITY_UNAVAILABLE
            "REMOTE_CAPABILITY_UNAVAILABLE" -> BackendPublicError.REMOTE_CAPABILITY_UNAVAILABLE
            "REMOTE_CAPABILITY_NOT_LAUNCHED" -> BackendPublicError.REMOTE_CAPABILITY_NOT_LAUNCHED
            "QUOTA_POLICY_UNAVAILABLE" -> BackendPublicError.QUOTA_POLICY_UNAVAILABLE
            "REMOTE_ACCOUNT_QUOTA_EXHAUSTED" -> BackendPublicError.REMOTE_ACCOUNT_QUOTA_EXHAUSTED
            "REMOTE_GLOBAL_BUDGET_UNAVAILABLE" -> BackendPublicError.REMOTE_GLOBAL_BUDGET_UNAVAILABLE
            "IDEMPOTENCY_REQUIRED" -> BackendPublicError.IDEMPOTENCY_REQUIRED
            "IDEMPOTENCY_INVALID" -> BackendPublicError.IDEMPOTENCY_INVALID
            "IDEMPOTENCY_CONFLICT" -> BackendPublicError.IDEMPOTENCY_CONFLICT
            "REMOTE_REQUEST_IN_PROGRESS" -> BackendPublicError.REMOTE_REQUEST_IN_PROGRESS
            "REMOTE_REQUEST_COMPLETED" -> BackendPublicError.REMOTE_REQUEST_COMPLETED
            "REMOTE_ADMISSION_UNAVAILABLE",
            "REMOTE_ADMISSION_INVALID_RECORD",
            "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT",
            "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE" -> BackendPublicError.REMOTE_ADMISSION_UNAVAILABLE
            "PROVIDER_RATE_LIMITED" -> BackendPublicError.PROVIDER_RATE_LIMITED
            "PROVIDER_TIMEOUT" -> BackendPublicError.PROVIDER_TIMEOUT
            "PROVIDER_INVALID_RESPONSE" -> BackendPublicError.PROVIDER_INVALID_RESPONSE
            "PROVIDER_BUSY",
            "PROVIDER_CIRCUIT_OPEN" -> BackendPublicError.PROVIDER_BUSY
            "PROVIDER_DISABLED",
            "PROVIDER_NOT_CONFIGURED",
            "PROVIDER_KEY_MISSING",
            "PROVIDER_MODEL_MISSING",
            "PROVIDER_UNAUTHORIZED",
            "PROVIDER_UNAVAILABLE" -> BackendPublicError.PROVIDER_UNAVAILABLE
            "COMMERCIAL_AUTHORITY_NOT_CONFIGURED",
            "COMMERCIAL_AUTHORITY_UNAUTHORIZED",
            "COMMERCIAL_AUTHORITY_RATE_LIMITED",
            "COMMERCIAL_AUTHORITY_TIMEOUT",
            "COMMERCIAL_AUTHORITY_INVALID_RESPONSE",
            "COMMERCIAL_AUTHORITY_UNAVAILABLE" -> BackendPublicError.COMMERCIAL_AUTHORITY_UNAVAILABLE
            "RATE_LIMITED" -> BackendPublicError.RATE_LIMITED
            "REQUEST_TIMEOUT" -> BackendPublicError.REQUEST_TIMEOUT
            "VALIDATION_ERROR" -> BackendPublicError.VALIDATION_ERROR
            "PAYLOAD_TOO_LARGE" -> BackendPublicError.PAYLOAD_TOO_LARGE
            "INVALID_RESPONSE" -> BackendPublicError.INVALID_RESPONSE
            "INTERNAL_ERROR" -> BackendPublicError.INTERNAL_ERROR
            else -> BackendPublicError.REMOTE_UNAVAILABLE
        }

        fun create(
            baseUrl: String,
            networkTimeoutMilliseconds: Long = NETWORK_TIMEOUT_MILLISECONDS
        ): FitDesiBackendService {
            val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
            val localCleartext = parsedBaseUrl?.scheme == "http" &&
                parsedBaseUrl.host in LOCAL_CLEARTEXT_HOSTS
            require(parsedBaseUrl?.isHttps == true || localCleartext) {
                "Backend base URL must use HTTPS except for an approved local development host."
            }
            require(baseUrl.endsWith("/")) { "Backend base URL must end with '/'." }
            require(networkTimeoutMilliseconds > 0) { "Network timeout must be positive." }

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(networkTimeoutMilliseconds, TimeUnit.MILLISECONDS)
                .readTimeout(networkTimeoutMilliseconds, TimeUnit.MILLISECONDS)
                .writeTimeout(networkTimeoutMilliseconds, TimeUnit.MILLISECONDS)
                // Intentionally no HTTP logging interceptor: requests may contain health-adjacent data.
                .build()
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi).failOnUnknown())
                .build()
                .create(AiBackendApi::class.java)
            return RetrofitAiBackendService(api, moshi)
        }

        private fun validHealth(value: HealthResponseDto): Boolean =
            value.status == "ok" &&
                value.service == "fitdesi-ai-backend" &&
                value.providerMode in COACH_MODES &&
                value.timestamp.isNotBlank()

        private fun validCoach(value: CoachResponseDto): Boolean =
            value.summary.isNotBlank() && value.summary.length <= 1_000 &&
                value.recommendedAction.isNotBlank() && value.recommendedAction.length <= 1_200 &&
                value.nutritionNote.isNotBlank() && value.nutritionNote.length <= 1_200 &&
                value.workoutNote.isNotBlank() && value.workoutNote.length <= 4_000 &&
                value.safetyDisclaimer.isNotBlank() && value.safetyDisclaimer.length <= 800 &&
                value.sourceType?.let(ALLOWED_SOURCES::contains) != false &&
                value.detectedIntent?.let(ALLOWED_INTENTS::contains) != false &&
                value.confidence?.let { it in 0.0..1.0 } != false &&
                value.requestId?.isNotBlank() != false &&
                value.warnings.size <= 10 && value.warnings.all { it.length <= 300 } &&
                value.knowledgeRecordIds.size <= 30

        private fun validWorkoutResponse(value: WorkoutPlanResponseDto): Boolean =
            when (value.status) {
                "ready" -> value.message == null && value.plan?.let(::validWorkoutPlan) == true
                "needs_professional_review" -> !value.message.isNullOrBlank() && value.plan == null
                else -> false
            }

        private fun validWorkoutPlan(value: WorkoutPlanDto): Boolean =
            value.routineName.isNotBlank() &&
                value.daysPerWeek in 1..7 &&
                value.splitType.isNotBlank() &&
                value.workoutDays.size == value.daysPerWeek &&
                value.workoutDays.all { day ->
                    day.day.isNotBlank() &&
                        day.title.isNotBlank() &&
                        day.exercises.isNotEmpty() &&
                        day.exercises.all {
                            it.name.isNotBlank() &&
                                it.sets > 0 &&
                                it.reps.isNotBlank() &&
                                it.restSeconds > 0
                        }
                } &&
                value.explanation.isNotBlank() &&
                value.safetyNote.isNotBlank()

        private fun validFoodResponse(value: FoodAnalyzeResponseDto): Boolean =
            value.foodName.isNotBlank() &&
                value.servingSize.isNotBlank() &&
                value.estimateStatus == "needs_portion_and_recipe_confirmation" &&
                value.calorieEstimate == null &&
                value.macroEstimate == null &&
                value.note.isNotBlank() &&
                value.safetyNote.isNotBlank()

        private fun validDietResponse(value: DietPlanResponseDto): Boolean =
            when (value.status) {
                "ready" -> value.message == null && value.plan?.let { plan ->
                    plan.goal.isNotBlank() &&
                        plan.dailyCalories > 0 &&
                        plan.mealsPerDay > 0 &&
                        plan.dietPreference.isNotBlank() &&
                        plan.meals.size == plan.mealsPerDay &&
                        plan.meals.all {
                            it.mealNumber > 0 && it.targetCalories > 0 && it.template.isNotBlank()
                        } &&
                        plan.note.isNotBlank() &&
                        plan.safetyNote.isNotBlank()
                } == true
                "needs_professional_review" -> !value.message.isNullOrBlank() && value.plan == null
                else -> false
            }

        private fun validYogaResponse(value: YogaPlanResponseDto): Boolean =
            when (value.status) {
                "ready" -> value.message == null && value.plan?.let { plan ->
                    plan.name.isNotBlank() &&
                        plan.level.isNotBlank() &&
                        plan.durationMinutes > 0 &&
                        plan.sequence.isNotEmpty() &&
                        plan.sequence.all { it.movement.isNotBlank() && it.minutes > 0 } &&
                        plan.note.isNotBlank() &&
                        plan.safetyNote.isNotBlank()
                } == true
                "needs_professional_review" -> !value.message.isNullOrBlank() && value.plan == null
                else -> false
            }

        private fun validProgressResponse(value: ProgressReviewResponseDto): Boolean =
            when (value.status) {
                "ready" -> value.message == null && value.review?.let { review ->
                    review.workoutsCompleted >= 0 &&
                        review.averageDurationMinutes >= 0 &&
                        review.trainingVolumeKg >= 0 &&
                        review.summary.isNotBlank() &&
                        review.recommendedAction.isNotBlank() &&
                        review.safetyNote.isNotBlank()
                } == true
                "needs_professional_review" -> !value.message.isNullOrBlank() && value.review == null
                else -> false
            }
    }
}
