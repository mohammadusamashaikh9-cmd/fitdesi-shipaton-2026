export class ApiError extends Error {
  constructor(statusCode, code, message, headers = {}) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.publicMessage = message;
    this.headers = headers;
  }
}

export class ValidationError extends ApiError {
  constructor(message) {
    super(400, "VALIDATION_ERROR", message);
  }
}

export class PayloadTooLargeError extends ApiError {
  constructor() {
    super(413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
  }
}

export class RequestTimeoutError extends ApiError {
  constructor() {
    super(408, "REQUEST_TIMEOUT", "The request took too long to process.");
  }
}

export class RateLimitError extends ApiError {
  constructor(retryAfterSeconds) {
    super(
      429,
      "RATE_LIMITED",
      "Too many requests. Please wait and try again.",
      { "retry-after": String(retryAfterSeconds) }
    );
  }
}

export class CorsError extends ApiError {
  constructor() {
    super(403, "CORS_ORIGIN_DENIED", "This request origin is not allowed.");
  }
}

export class ResponseValidationError extends ApiError {
  constructor() {
    super(500, "INVALID_RESPONSE", "The service produced an invalid response.");
  }
}

export class AuthenticationRequiredError extends ApiError {
  constructor() {
    super(401, "AUTH_REQUIRED", "Sign in to continue.", {
      "www-authenticate": "Bearer"
    });
  }
}

export class InvalidSessionError extends ApiError {
  constructor() {
    super(401, "INVALID_SESSION", "Your session is invalid or expired. Sign in again.", {
      "www-authenticate": "Bearer"
    });
  }
}

export class AuthVerificationUnavailableError extends ApiError {
  constructor() {
    super(
      503,
      "AUTH_VERIFICATION_UNAVAILABLE",
      "Session verification is temporarily unavailable. Please try again."
    );
  }
}

export class EmailVerificationRequiredError extends ApiError {
  constructor() {
    super(
      403,
      "EMAIL_VERIFICATION_REQUIRED",
      "Verify your email to use remote AI features."
    );
  }
}

const CONSENT_AUTHORITY_UNAVAILABLE_MESSAGE =
  "Privacy consent is temporarily unavailable. Please try again.";

export class ConsentNoticeVersionMismatchError extends ApiError {
  constructor() {
    super(
      409,
      "CONSENT_NOTICE_VERSION_MISMATCH",
      "The privacy notice has changed. Fetch the current consent state and try again."
    );
  }
}

export class ConsentAuthorityConfigurationError extends ApiError {
  constructor() {
    super(
      503,
      "CONSENT_AUTHORITY_NOT_CONFIGURED",
      CONSENT_AUTHORITY_UNAVAILABLE_MESSAGE
    );
  }
}

export class ConsentAuthorityInvalidRecordError extends ApiError {
  constructor() {
    super(
      503,
      "CONSENT_AUTHORITY_INVALID_RECORD",
      CONSENT_AUTHORITY_UNAVAILABLE_MESSAGE
    );
  }
}

export class ConsentAuthorityTimeoutError extends ApiError {
  constructor() {
    super(504, "CONSENT_AUTHORITY_TIMEOUT", CONSENT_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class ConsentAuthorityUnavailableError extends ApiError {
  constructor() {
    super(
      503,
      "CONSENT_AUTHORITY_UNAVAILABLE",
      CONSENT_AUTHORITY_UNAVAILABLE_MESSAGE
    );
  }
}

export class RemoteAiConsentRequiredError extends ApiError {
  constructor() {
    super(
      403,
      "REMOTE_AI_CONSENT_REQUIRED",
      "Current standard remote AI consent is required."
    );
  }
}

const REMOTE_ADMISSION_UNAVAILABLE_MESSAGE =
  "Remote admission is temporarily unavailable. Please use the local fallback.";

export class RemoteAdmissionUnavailableError extends ApiError {
  constructor() {
    super(503, "REMOTE_ADMISSION_UNAVAILABLE", REMOTE_ADMISSION_UNAVAILABLE_MESSAGE);
  }
}

export class RemoteCapabilityUnavailableError extends ApiError {
  constructor() {
    super(403, "REMOTE_CAPABILITY_UNAVAILABLE", "This remote AI capability is unavailable.");
  }
}

export class RemoteCapabilityNotLaunchedError extends ApiError {
  constructor() {
    super(403, "REMOTE_CAPABILITY_NOT_LAUNCHED", "This remote AI capability is not launched.");
  }
}

export class QuotaPolicyUnavailableError extends ApiError {
  constructor() {
    super(503, "QUOTA_POLICY_UNAVAILABLE", "Remote quota policy is temporarily unavailable.");
  }
}

export class IdempotencyRequiredError extends ApiError {
  constructor() {
    super(400, "IDEMPOTENCY_REQUIRED", "A valid Idempotency-Key is required.");
  }
}

export class IdempotencyInvalidError extends ApiError {
  constructor() {
    super(400, "IDEMPOTENCY_INVALID", "The Idempotency-Key is invalid.");
  }
}

export class IdempotencyConflictError extends ApiError {
  constructor() {
    super(409, "IDEMPOTENCY_CONFLICT", "The Idempotency-Key was used for a different request.");
  }
}

export class RemoteRequestInProgressError extends ApiError {
  constructor() {
    super(409, "REMOTE_REQUEST_IN_PROGRESS", "This remote request is already in progress.");
  }
}

export class RemoteRequestCompletedError extends ApiError {
  constructor() {
    super(409, "REMOTE_REQUEST_COMPLETED", "This remote request has already completed.");
  }
}

export class RemoteAccountQuotaExhaustedError extends ApiError {
  constructor() {
    super(
      429,
      "REMOTE_ACCOUNT_QUOTA_EXHAUSTED",
      "Remote AI quota is unavailable. Please use the local fallback."
    );
  }
}

export class RemoteGlobalBudgetUnavailableError extends ApiError {
  constructor() {
    super(
      503,
      "REMOTE_GLOBAL_BUDGET_UNAVAILABLE",
      "Remote AI is temporarily unavailable. Please use the local fallback."
    );
  }
}

export class RemoteAdmissionInvalidRecordError extends ApiError {
  constructor() {
    super(
      503,
      "REMOTE_ADMISSION_INVALID_RECORD",
      "Remote admission is temporarily unavailable. Please use the local fallback."
    );
  }
}

export class RemoteAdmissionPersistenceTimeoutError extends ApiError {
  constructor() {
    super(
      504,
      "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT",
      "Remote admission timed out. Please use the local fallback."
    );
  }
}

export class RemoteAdmissionPersistenceUnavailableError extends ApiError {
  constructor() {
    super(
      503,
      "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE",
      "Remote admission is temporarily unavailable. Please use the local fallback."
    );
  }
}

export class ExperimentalAiConsentRequiredError extends ApiError {
  constructor() {
    super(
      403,
      "EXPERIMENTAL_AI_CONSENT_REQUIRED",
      "Current standard and experimental consent are required."
    );
  }
}

const COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE =
  "Commercial access verification is temporarily unavailable. Please try again.";

export class CommercialAuthorityConfigurationError extends ApiError {
  constructor() {
    super(503, "COMMERCIAL_AUTHORITY_NOT_CONFIGURED", COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class CommercialAuthorityUnauthorizedError extends ApiError {
  constructor() {
    super(502, "COMMERCIAL_AUTHORITY_UNAUTHORIZED", COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class CommercialAuthorityRateLimitError extends ApiError {
  constructor(retryAfterSeconds = 30) {
    const boundedRetryAfter = Number.isInteger(retryAfterSeconds)
      ? Math.min(3600, Math.max(1, retryAfterSeconds))
      : 30;
    super(
      429,
      "COMMERCIAL_AUTHORITY_RATE_LIMITED",
      COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE,
      { "retry-after": String(boundedRetryAfter) }
    );
  }
}

export class CommercialAuthorityTimeoutError extends ApiError {
  constructor() {
    super(504, "COMMERCIAL_AUTHORITY_TIMEOUT", COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class CommercialAuthorityInvalidResponseError extends ApiError {
  constructor() {
    super(502, "COMMERCIAL_AUTHORITY_INVALID_RESPONSE", COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class CommercialAuthorityUnavailableError extends ApiError {
  constructor() {
    super(503, "COMMERCIAL_AUTHORITY_UNAVAILABLE", COMMERCIAL_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
}

export class ProviderDisabledError extends ApiError {
  constructor() {
    super(503, "PROVIDER_DISABLED", "Remote AI is unavailable. Please use the local fallback.");
  }
}

export class ProviderConfigurationError extends ApiError {
  constructor(code = "PROVIDER_NOT_CONFIGURED") {
    super(503, code, "Remote AI is unavailable. Please use the local fallback.");
  }
}

export class ProviderUnauthorizedError extends ApiError {
  constructor() {
    super(502, "PROVIDER_UNAUTHORIZED", "Remote AI is unavailable. Please use the local fallback.");
  }
}

export class ProviderRateLimitError extends ApiError {
  constructor(retryAfterSeconds = 30) {
    super(429, "PROVIDER_RATE_LIMITED", "Remote AI is busy. Please wait or use the local fallback.", {
      "retry-after": String(Math.min(3600, Math.max(1, retryAfterSeconds)))
    });
  }
}

export class ProviderTimeoutError extends ApiError {
  constructor() {
    super(504, "PROVIDER_TIMEOUT", "Remote AI timed out. Please use the local fallback.");
  }
}

export class ProviderInvalidResponseError extends ApiError {
  constructor() {
    super(502, "PROVIDER_INVALID_RESPONSE", "Remote AI returned an unusable response. Please use the local fallback.");
  }
}

export class ProviderUnavailableError extends ApiError {
  constructor() {
    super(503, "PROVIDER_UNAVAILABLE", "Remote AI is unavailable. Please use the local fallback.");
  }
}

export class CircuitOpenError extends ApiError {
  constructor(retryAfterSeconds) {
    super(503, "PROVIDER_CIRCUIT_OPEN", "Remote AI is temporarily unavailable. Please use the local fallback.", {
      "retry-after": String(Math.max(1, retryAfterSeconds))
    });
  }
}

export class ConcurrencyLimitError extends ApiError {
  constructor() {
    super(429, "PROVIDER_BUSY", "Remote AI is busy. Please wait or use the local fallback.", { "retry-after": "1" });
  }
}
