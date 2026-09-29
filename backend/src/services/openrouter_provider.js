import { ProviderConfigurationError, ProviderDisabledError, ProviderInvalidResponseError } from "../errors.js";
import { SharedAttemptExecutor } from "./shared_attempt_executor.js";
import { requireOpenRouterEvaluationProfile, OPENROUTER_ROUTING_MODE } from "./provider_route_policy.js";
import { isRetryableProviderError, mapProviderError, prepareCoachOperation } from "./coach_provider_operation.js";

const OPENROUTER_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions";
export const STRICT_PROVIDER_ROUTING = Object.freeze({
  allow_fallbacks: false, require_parameters: true, data_collection: "deny", zdr: true
});
export const SYNTHETIC_FREE_TIER_ROUTING = Object.freeze({
  allow_fallbacks: false, require_parameters: true
});

function providerRouting(profile) {
  requireOpenRouterEvaluationProfile(profile);
  switch (profile.routingMode) {
    case OPENROUTER_ROUTING_MODE.STRICT_PRIVACY:
      return STRICT_PROVIDER_ROUTING;
    case OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER:
      if (profile.purpose !== "EVALUATION_ONLY" || profile.dataClassification !== "SYNTHETIC_ONLY"
          || profile.privacyClassification !== "SYNTHETIC_FREE_TIER_EVALUATION_ONLY") {
        throw new ProviderConfigurationError();
      }
      // Fixtures only; this mode makes no request-level retention/training guarantee.
      return SYNTHETIC_FREE_TIER_ROUTING;
    default:
      throw new ProviderConfigurationError();
  }
}

function rejectCallerRouting(request) {
  for (const field of ["routingMode", "privacyClassification", "providerRouting", "privacySettings", "provider",
    "zdr", "data_collection", "allow_fallbacks", "require_parameters"]) {
    if (Object.hasOwn(request, field)) throw new ProviderConfigurationError();
  }
}

function normalizedFetchError(error, signal) {
  // Never retain the native error/cause, which may contain credentials or request details.
  const failure = new Error("OpenRouter transport failed.");
  if (signal.aborted || ["AbortError", "APIUserAbortError"].includes(error?.name)) {
    failure.name = "AbortError";
  } else if (error?.name === "APIConnectionTimeoutError"
      || ["ETIMEDOUT", "ECONNABORTED"].includes(error?.code)
      || ["ETIMEDOUT", "ECONNABORTED", "UND_ERR_CONNECT_TIMEOUT"].includes(error?.cause?.code)) {
    failure.name = "APIConnectionTimeoutError";
  } else if (error instanceof TypeError || error?.name === "APIConnectionError" || error?.code === "ECONNRESET") {
    failure.name = "APIConnectionError";
  }
  return failure;
}

/** Evaluation-only single-dispatch adapter. No production/default wiring. */
export class OpenRouterProvider {
  #config;
  #evaluationEnabled;
  #fetchImpl;

  constructor({ config = {}, evaluationEnabled = false, fetchImpl = globalThis.fetch } = {}) {
    this.#config = config;
    this.#evaluationEnabled = evaluationEnabled;
    this.#fetchImpl = fetchImpl;
  }

  assertEnabled(profile) {
    providerRouting(profile);
    if (this.#evaluationEnabled !== true) throw new ProviderDisabledError();
    if (typeof this.#config.openrouterApiKey !== "string" || !this.#config.openrouterApiKey.trim()) {
      throw new ProviderConfigurationError("PROVIDER_KEY_MISSING");
    }
    if (typeof this.#fetchImpl !== "function") throw new ProviderConfigurationError();
  }

  createAttemptExecutor(request, profile) {
    rejectCallerRouting(request);
    this.assertEnabled(profile);
    return new SharedAttemptExecutor({
      beforeProviderAttempt: request.beforeProviderAttempt,
      signal: request.signal, now: request.now,
      operationBudgetMs: this.#config.openrouterEvaluationTimeoutMs ?? 30000,
      dispatch: (body, options) => this.#dispatch(body, options, profile)
    });
  }

  async #dispatch(body, options, profile) {
    const allowed = ["model", "messages", "temperature", "max_tokens", "provider",
      ...(profile.structuredOutputMode === "JSON_SCHEMA" ? ["response_format"] : [])];
    if (body?.model !== profile.modelId || Object.keys(body).some((field) => !allowed.includes(field))) {
      throw new ProviderConfigurationError();
    }
    // Revalidate the trusted mode at the last private seam; body.provider cannot override it.
    const requestBody = JSON.stringify({ ...body, provider: providerRouting(profile) });
    let response;
    try {
      response = await this.#fetchImpl(OPENROUTER_COMPLETIONS_URL, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.#config.openrouterApiKey.trim()}`, "Content-Type": "application/json" },
        body: requestBody,
        signal: options.signal,
        // A redirect must never create a second HTTP invocation under one authorization.
        redirect: "manual"
      });
    } catch (error) {
      throw normalizedFetchError(error, options.signal);
    }
    if (!response.ok) {
      const failure = new Error("OpenRouter HTTP request failed.");
      if (Number.isInteger(response.status) && response.status >= 400 && response.status <= 599) {
        failure.status = response.status;
      }
      if (failure.status === 429) {
        const seconds = Number.parseInt(response.headers.get("retry-after") ?? "", 10);
        failure.headers = Object.freeze({ "retry-after": String(Number.isInteger(seconds) && seconds > 0
          ? Math.min(3600, seconds) : 30) });
      }
      // Release the unread body; never parse, retain or copy provider failure text.
      try { await response.body?.cancel(); } catch { /* No raw cleanup error escapes. */ }
      throw failure;
    }
    try {
      return await response.json();
    } catch (error) {
      if (error instanceof SyntaxError && !options.signal.aborted) throw new ProviderInvalidResponseError();
      throw normalizedFetchError(error, options.signal);
    }
  }

  prepareCoach(request, profile) {
    rejectCallerRouting(request);
    this.assertEnabled(profile);
    const operation = prepareCoachOperation(request, {
      modelId: profile.modelId,
      structuredOutputMode: profile.structuredOutputMode,
      maxOutputTokens: this.#config.openrouterEvaluationMaxOutputTokens ?? 4096,
      providerRouting: providerRouting(profile)
    });
    return operation;
  }

  isRetryable(error) { return isRetryableProviderError(error); }

  mapError(error) { return mapProviderError(error); }
}
