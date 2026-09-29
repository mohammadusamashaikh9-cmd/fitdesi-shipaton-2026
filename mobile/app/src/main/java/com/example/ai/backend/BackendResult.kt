package com.example.ai.backend

enum class BackendExecutionMode {
    MOCK,
    REMOTE
}

enum class BackendPublicError {
    AUTH_REQUIRED,
    INVALID_SESSION,
    AUTH_VERIFICATION_UNAVAILABLE,
    EMAIL_VERIFICATION_REQUIRED,
    REMOTE_AI_CONSENT_REQUIRED,
    CONSENT_NOTICE_VERSION_MISMATCH,
    CONSENT_AUTHORITY_UNAVAILABLE,
    REMOTE_CAPABILITY_UNAVAILABLE,
    REMOTE_CAPABILITY_NOT_LAUNCHED,
    QUOTA_POLICY_UNAVAILABLE,
    REMOTE_ACCOUNT_QUOTA_EXHAUSTED,
    REMOTE_GLOBAL_BUDGET_UNAVAILABLE,
    IDEMPOTENCY_REQUIRED,
    IDEMPOTENCY_INVALID,
    IDEMPOTENCY_CONFLICT,
    REMOTE_REQUEST_IN_PROGRESS,
    REMOTE_REQUEST_COMPLETED,
    REMOTE_ADMISSION_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    PROVIDER_BUSY,
    PROVIDER_RATE_LIMITED,
    PROVIDER_TIMEOUT,
    PROVIDER_INVALID_RESPONSE,
    COMMERCIAL_AUTHORITY_UNAVAILABLE,
    RATE_LIMITED,
    REQUEST_TIMEOUT,
    VALIDATION_ERROR,
    PAYLOAD_TOO_LARGE,
    INVALID_RESPONSE,
    INTERNAL_ERROR,
    REMOTE_UNAVAILABLE
}

sealed interface BackendResult<out T> {
    data object Loading : BackendResult<Nothing>
    data object NoContent : BackendResult<Nothing>
    data class Success<T>(
        val value: T,
        val requestId: String,
        val mode: BackendExecutionMode = BackendExecutionMode.MOCK
    ) : BackendResult<T>
    data class PublicError(
        val code: BackendPublicError,
        val retryAfterSeconds: Long? = null,
        val requestId: String? = null
    ) : BackendResult<Nothing>
    data class ValidationError(val message: String, val requestId: String?) : BackendResult<Nothing>
    data class PayloadTooLarge(val message: String, val requestId: String?) : BackendResult<Nothing>
    data class RateLimited(
        val message: String,
        val retryAfterSeconds: Long?,
        val requestId: String?
    ) : BackendResult<Nothing>
    data class Timeout(val message: String) : BackendResult<Nothing>
    data class BackendUnavailable(val message: String) : BackendResult<Nothing>
    data class InvalidResponse(val message: String) : BackendResult<Nothing>
    data class UnknownError(val message: String, val requestId: String? = null) : BackendResult<Nothing>
}
