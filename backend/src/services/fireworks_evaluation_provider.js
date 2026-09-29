import OpenAI from "openai";
import { ProviderConfigurationError, ProviderDisabledError } from "../errors.js";
import { requireFireworksEvaluationProfile } from "./provider_route_policy.js";
import { SharedAttemptExecutor } from "./shared_attempt_executor.js";
import { AI_COACH_JSON_SCHEMA } from "./ai_coach_contract.js";
import { isRetryableProviderError, mapProviderError, prepareCoachOperation } from "./coach_provider_operation.js";

const BASE_URL = "https://api.fireworks.ai/inference/v1";
function rejectOverrides(request) {
  for (const field of ["model", "modelId", "models", "provider", "providerAlias", "profile", "routeProfile",
    "structuredOutputMode", "providerOptions", "chat_template_kwargs", "enable_thinking", "enableThinking", "thinking"]) {
    if (Object.hasOwn(request, field)) throw new ProviderConfigurationError();
  }
}

/** Isolated evaluation activation; never used by production resolve()/FireworksProvider. */
export class FireworksEvaluationProvider {
  #config;
  #enabled;
  #clientFactory;
  #client;

  constructor({ config = {}, evaluationEnabled = false, clientFactory = (options) => new OpenAI(options) } = {}) {
    this.#config = Object.freeze({ ...config });
    this.#enabled = evaluationEnabled;
    this.#clientFactory = clientFactory;
  }

  assertEnabled(profile) {
    requireFireworksEvaluationProfile(profile);
    if (this.#enabled !== true) throw new ProviderDisabledError();
    if (typeof this.#config.fireworksApiKey !== "string" || !this.#config.fireworksApiKey.trim()) {
      throw new ProviderConfigurationError("PROVIDER_KEY_MISSING");
    }
    for (const [field, fallback, min, max] of [
      ["fireworksEvaluationTimeoutMs", 30000, 1000, 120000],
      ["fireworksEvaluationMaxOutputTokens", 4096, 256, 4096]
    ]) {
      const value = this.#config[field] ?? fallback;
      if (!Number.isInteger(value) || value < min || value > max) throw new ProviderConfigurationError();
    }
  }

  createAttemptExecutor(request, profile) {
    rejectOverrides(request);
    this.assertEnabled(profile);
    return new SharedAttemptExecutor({
      beforeProviderAttempt: request.beforeProviderAttempt, signal: request.signal, now: request.now,
      operationBudgetMs: this.#config.fireworksEvaluationTimeoutMs ?? 30000,
      dispatch: (body, options) => this.#dispatch(body, options, profile)
    });
  }

  #dispatch(body, options, profile) {
    this.assertEnabled(profile);
    const allowed = ["model", "messages", "temperature", "max_tokens", "response_format",
      ...(profile.enableThinking === false ? ["chat_template_kwargs"] : [])];
    if (body?.model !== profile.modelId || Object.keys(body).some((field) => !allowed.includes(field))) {
      throw new ProviderConfigurationError();
    }
    // Final private seam enforces schema/token/thinking policy even if material is tampered with.
    return this.#client.chat.completions.create({ ...body,
      max_tokens: this.#config.fireworksEvaluationMaxOutputTokens ?? 4096,
      response_format: { type: "json_schema", json_schema: { name: "fitdesi_ai_coach", strict: true, schema: AI_COACH_JSON_SCHEMA } },
      ...(profile.enableThinking === false ? { chat_template_kwargs: { enable_thinking: false } } : {})
    }, { ...options, maxRetries: 0 });
  }

  prepareCoach(request, profile) {
    rejectOverrides(request);
    this.assertEnabled(profile);
    const operation = prepareCoachOperation(request, { modelId: profile.modelId, structuredOutputMode: profile.structuredOutputMode,
      maxOutputTokens: this.#config.fireworksEvaluationMaxOutputTokens ?? 4096 });
    if (!this.#client) this.#client = this.#clientFactory({ baseURL: BASE_URL,
      apiKey: this.#config.fireworksApiKey.trim(), maxRetries: 0,
      timeout: this.#config.fireworksEvaluationTimeoutMs ?? 30000 });
    const material = (body) => profile.enableThinking === false
      ? { ...body, chat_template_kwargs: { enable_thinking: false } } : body;
    return { ...operation, initial: material(operation.initial),
      repair: (content, contractDiagnostic) => material(operation.repair(content, contractDiagnostic)) };
  }

  isRetryable(error) { return isRetryableProviderError(error); }
  mapError(error) { return mapProviderError(error); }
}
