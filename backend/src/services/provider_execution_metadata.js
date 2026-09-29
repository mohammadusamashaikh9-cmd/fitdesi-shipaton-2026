const USAGE_FIELDS = ["promptTokens", "completionTokens", "totalTokens", "cachedTokens", "reasoningTokens"];
const SAFE_FAILURES = new Set([
  "PROVIDER_UNAUTHORIZED", "PROVIDER_RATE_LIMITED", "PROVIDER_TIMEOUT", "PROVIDER_INVALID_RESPONSE",
  "PROVIDER_UNAVAILABLE", "PROVIDER_DISABLED", "PROVIDER_NOT_CONFIGURED", "PROVIDER_KEY_MISSING",
  "PROVIDER_MODEL_MISSING", "REMOTE_ADMISSION_UNAVAILABLE", "REMOTE_ACCOUNT_QUOTA_EXHAUSTED",
  "REMOTE_GLOBAL_BUDGET_UNAVAILABLE", "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT",
  "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE", "REMOTE_ADMISSION_INVALID_RECORD", "REMOTE_REQUEST_COMPLETED",
  "REMOTE_REQUEST_IN_PROGRESS", "REMOTE_AI_CONSENT_REQUIRED", "REMOTE_CAPABILITY_NOT_LAUNCHED",
  "REMOTE_CAPABILITY_UNAVAILABLE", "QUOTA_POLICY_UNAVAILABLE", "IDEMPOTENCY_REQUIRED",
  "IDEMPOTENCY_INVALID", "IDEMPOTENCY_CONFLICT", "COMMERCIAL_AUTHORITY_NOT_CONFIGURED"
]);
const PROVIDER_LABELS = new Map([
  ["Fireworks", "fireworks"], ["fireworks", "fireworks"],
  ["DeepInfra", "deepinfra"], ["deepinfra", "deepinfra"],
  ["Together", "together"], ["together", "together"],
  ["NVIDIA", "nvidia"], ["nvidia", "nvidia"],
  ["Nex AGI", "nex-agi"], ["nex-agi", "nex-agi"],
  ["Z.AI", "z-ai"], ["z-ai", "z-ai"]
]);

function tokenCount(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

export function normalizeProviderUsage(usage) {
  return Object.freeze({
    promptTokens: tokenCount(usage?.prompt_tokens),
    completionTokens: tokenCount(usage?.completion_tokens),
    totalTokens: tokenCount(usage?.total_tokens),
    cachedTokens: tokenCount(usage?.prompt_tokens_details?.cached_tokens),
    reasoningTokens: tokenCount(usage?.completion_tokens_details?.reasoning_tokens)
  });
}

export function aggregateProviderUsage(attempts) {
  return Object.freeze(Object.fromEntries(USAGE_FIELDS.map((field) => {
    const values = attempts.map((attempt) => attempt.usage[field]);
    const sum = values.reduce((total, value) => total + (value ?? 0), 0);
    return [field, values.length && values.every((value) => value !== null) && Number.isSafeInteger(sum) ? sum : null];
  })));
}

export function sanitizedFailureClassification(error) {
  return SAFE_FAILURES.has(error?.code) ? error.code : "INTERNAL_ERROR";
}

/** Raw dispatch error in, bounded diagnostics out. Never copy provider text/metadata. */
export function sanitizedProviderDispatchDiagnostic(error) {
  const upstreamHttpStatus = Number.isInteger(error?.status) && error.status >= 400 && error.status <= 599
    ? error.status : null;
  let classification = "UNKNOWN";
  if (upstreamHttpStatus !== null) {
    classification = ({
      400: "BAD_REQUEST", 401: "UNAUTHORIZED", 403: "FORBIDDEN", 404: "NOT_FOUND",
      422: "UNPROCESSABLE", 429: "RATE_LIMITED"
    })[upstreamHttpStatus] ?? (upstreamHttpStatus >= 500 ? "UPSTREAM_5XX" : "OTHER_4XX");
  } else if (["APIConnectionTimeoutError", "APIUserAbortError", "AbortError"].includes(error?.name)
      || ["ETIMEDOUT", "ECONNABORTED"].includes(error?.code)) {
    classification = "TIMEOUT";
  } else if (error?.name === "APIConnectionError" || error?.code === "ECONNRESET") {
    classification = "CONNECTION";
  }
  return Object.freeze({ upstreamHttpStatus, classification });
}

export function sanitizedActualProvider(completion) {
  // Do not copy arbitrary provider text into telemetry/reports. Unknown stays null.
  return PROVIDER_LABELS.get(completion?.provider) ?? null;
}

export function sanitizedActualModelId(completion) {
  const value = completion?.model;
  // Only bounded identifier syntax, never arbitrary response/prose or secret-like material.
  return typeof value === "string" && value.length <= 200
    && /^[A-Za-z0-9][A-Za-z0-9._:-]*(?:\/[A-Za-z0-9][A-Za-z0-9._:-]*)*$/.test(value)
    && !/(?:^|\/)(?:sk-|fw_|AIza|acct_v1_|idem_v1_|req_v1_)/.test(value) ? value : null;
}

export function elapsedMilliseconds(start, end) {
  return Number.isFinite(start) && Number.isFinite(end) && end >= start ? end - start : null;
}

export function executionMetadata(profile, attempts, latencyMs, validationOutcome, failureClassification) {
  return Object.freeze({
    routePolicyVersion: profile.routePolicyVersion,
    routeProfileId: profile.routeProfileId,
    providerAlias: profile.providerAlias,
    configuredModelId: profile.modelId,
    actualModelId: attempts.at(-1)?.actualModelId ?? null,
    actualProvider: attempts.at(-1)?.actualProvider ?? null,
    attemptsUsed: attempts.length,
    latencyMs, validationOutcome, failureClassification,
    ...(profile.purpose === "EVALUATION_ONLY" ? {
      contractDiagnostic: failureClassification === "PROVIDER_INVALID_RESPONSE"
        && failureClassification === attempts.at(-1)?.failureClassification
        ? attempts.at(-1)?.contractDiagnostic ?? null : null
    } : {}),
    ...(profile.providerAlias === "openrouter" ? {
      failureDiagnostic: failureClassification !== null && failureClassification === attempts.at(-1)?.failureClassification
        ? attempts.at(-1)?.failureDiagnostic ?? null : null
    } : {}),
    attempts: Object.freeze(attempts.map((attempt) => Object.freeze({ ...attempt }))),
    aggregateUsage: aggregateProviderUsage(attempts)
  });
}
