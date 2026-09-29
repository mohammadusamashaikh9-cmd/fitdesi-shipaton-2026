import OpenAI from "openai";
import { ProviderConfigurationError, ProviderDisabledError } from "../errors.js";
import { AiProvider } from "./ai_provider.js";
import { ProviderRouter } from "./provider_router.js";
import { SharedAttemptExecutor } from "./shared_attempt_executor.js";
import { isRetryableProviderError, mapProviderError, prepareCoachOperation } from "./coach_provider_operation.js";

const FIREWORKS_BASE_URL = "https://api.fireworks.ai/inference/v1";

export class FireworksProvider extends AiProvider {
  #client;
  #clientFactory;
  constructor({
    config,
    client,
    clientFactory = (options) => new OpenAI(options)
  } = {}) {
    super();
    this.config = config ?? {};
    this.#client = client;
    this.#clientFactory = clientFactory;
  }

  assertEnabled() {
    if (this.config.provider !== "fireworks" || this.config.remoteAiEnabled !== true) {
      throw new ProviderDisabledError();
    }
    if (typeof this.config.fireworksApiKey !== "string" || !this.config.fireworksApiKey.trim()) {
      throw new ProviderConfigurationError("PROVIDER_KEY_MISSING");
    }
    if (typeof this.config.fireworksModel !== "string" || !this.config.fireworksModel.trim()) {
      throw new ProviderConfigurationError("PROVIDER_MODEL_MISSING");
    }
  }

  #providerClient() {
    if (!this.#client) {
      this.#client = this.#clientFactory({
        baseURL: this.config.fireworksBaseUrl ?? FIREWORKS_BASE_URL,
        apiKey: this.config.fireworksApiKey.trim(),
        timeout: this.config.fireworksTimeoutMs ?? 8000,
        maxRetries: 0
      });
    }
    return this.#client;
  }

  createAttemptExecutor({ beforeProviderAttempt, signal, now } = {}) {
    this.assertEnabled();
    return new SharedAttemptExecutor({
      beforeProviderAttempt,
      signal,
      now,
      operationBudgetMs: this.config.fireworksTimeoutMs ?? 8000,
      dispatch: (body, options) => this.#dispatch(body, options)
    });
  }

  // The only inference seam is private and handed directly to the executor.
  #dispatch(body, options) {
    return this.#client.chat.completions.create(body, { ...options, maxRetries: 0 });
  }

  isRetryable(error) { return isRetryableProviderError(error); }

  mapError(error) { return mapProviderError(error); }

  prepareCoach(request, profile) {
    this.assertEnabled();
    if (profile && (profile.providerAlias !== "fireworks" || profile.modelId !== this.config.fireworksModel.trim())) {
      throw new ProviderConfigurationError();
    }
    const operation = prepareCoachOperation(request, {
      modelId: this.config.fireworksModel.trim(),
      structuredOutputMode: "JSON_SCHEMA",
      temperature: this.config.fireworksTemperature ?? 0.2,
      maxOutputTokens: this.config.fireworksMaxOutputTokens ?? 1800
    });
    this.#providerClient();
    return operation;
  }

  // Compatibility entry point also traverses the guarded router/executor.
  async generateStructured(request) {
    return new ProviderRouter({ fireworksProvider: this }).generateStructured(request);
  }
}
