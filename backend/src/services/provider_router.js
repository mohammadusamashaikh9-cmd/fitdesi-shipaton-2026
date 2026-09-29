import { AiProvider } from "./ai_provider.js";
import { ProviderConfigurationError, ProviderInvalidResponseError } from "../errors.js";
import { RemoteCapability } from "../remote_admission/capability_policy.js";
import { ProviderDispatchError } from "./shared_attempt_executor.js";
import { createProviderRoutePolicy } from "./provider_route_policy.js";
import { coachContractDiagnostic } from "./ai_coach_contract.js";
import {
  elapsedMilliseconds, executionMetadata, normalizeProviderUsage,
  sanitizedActualProvider, sanitizedActualModelId, sanitizedFailureClassification, sanitizedProviderDispatchDiagnostic
} from "./provider_execution_metadata.js";

function rejectRoutingOverrides(request, evaluation = false) {
  for (const field of ["provider", "providerAlias", "model", "modelId", "models", "routeProfile", "structuredOutputMode", ...(!evaluation ? ["routeProfileId"] : [])]) {
    if (Object.hasOwn(request, field)) throw new ProviderConfigurationError();
  }
}

/** Execution routing only; commercial, consent and quota authority stay upstream. */
export class ProviderRouter extends AiProvider {
  #routePolicy;
  #openrouterProvider;
  #fireworksEvaluationProvider;

  constructor({ fireworksProvider, openrouterProvider, fireworksEvaluationProvider, config = fireworksProvider?.config ?? {} }) {
    super();
    this.fireworksProvider = fireworksProvider;
    this.#openrouterProvider = openrouterProvider;
    this.#fireworksEvaluationProvider = fireworksEvaluationProvider;
    this.#routePolicy = createProviderRoutePolicy(config);
  }

  resolve(capability) {
    this.#routePolicy.resolveProduction(capability);
    return this.fireworksProvider;
  }

  assertEnabled() {
    this.resolve(RemoteCapability.REMOTE_AI_COACH).assertEnabled();
  }

  async generateStructured(request = {}) {
    rejectRoutingOverrides(request);
    const adapter = this.resolve(request.capability ?? RemoteCapability.REMOTE_AI_COACH);
    const profile = this.#routePolicy.resolveProduction(request.capability ?? RemoteCapability.REMOTE_AI_COACH);
    return this.#execute(adapter, profile, request);
  }

  async generateEvaluation(request = {}) {
    rejectRoutingOverrides(request, true);
    const profile = this.#routePolicy.resolveEvaluation(request.routeProfileId, request.dataClassification);
    let adapter;
    switch (profile.providerAlias) {
      case "openrouter": adapter = this.#openrouterProvider; break;
      case "fireworks": adapter = this.#fireworksEvaluationProvider; break;
      default: throw new ProviderConfigurationError();
    }
    if (!adapter) throw new ProviderConfigurationError();
    return this.#execute(adapter, profile, request);
  }

  async #execute(adapter, profile, request) {
    adapter.assertEnabled(profile);
    const now = request.now ?? (() => performance.now());
    const startedAt = now();
    const executor = adapter.createAttemptExecutor(request, profile);
    const attempts = [];
    let finalFailure = null;
    let validationOutcome = "NOT_VALIDATED";
    try {
      const operation = adapter.prepareCoach(request, profile);
      let material = operation.initial;
      let attemptClassification = "initial";
      while (true) {
        let completion;
        const attemptStart = now();
        const previousDispatches = executor.dispatchCount;
        try {
          completion = await executor.execute(material);
        } catch (error) {
          // Only internal OpenRouter evaluation metadata observes the raw dispatch error.
          // Public mapping and retry decisions continue using their existing authorities.
          const failureDiagnostic = profile.providerAlias === "openrouter" && error instanceof ProviderDispatchError
            ? sanitizedProviderDispatchDiagnostic(error.providerError) : null;
          const mapped = error instanceof ProviderDispatchError ? adapter.mapError(error.providerError) : error;
          if (executor.dispatchCount > previousDispatches) {
            attempts.push({
              attemptNumber: executor.dispatchCount, attemptClassification,
              latencyMs: elapsedMilliseconds(attemptStart, now()), actualProvider: null, actualModelId: null,
              validationOutcome: "NOT_VALIDATED", failureClassification: sanitizedFailureClassification(mapped),
              ...(profile.providerAlias === "openrouter" ? { failureDiagnostic } : {}),
              ...(profile.purpose === "EVALUATION_ONLY" ? { contractDiagnostic: null } : {}),
              usage: normalizeProviderUsage(null)
            });
          }
          if (!(error instanceof ProviderDispatchError)) throw error;
          executor.assertActive();
          if (executor.hasAttemptRemaining && adapter.isRetryable(error.providerError)) {
            material = operation.initial;
            attemptClassification = "retry";
            continue;
          }
          throw mapped;
        }
        const attempt = {
          attemptNumber: executor.dispatchCount, attemptClassification,
          latencyMs: elapsedMilliseconds(attemptStart, now()), actualProvider: sanitizedActualProvider(completion),
          actualModelId: sanitizedActualModelId(completion),
          validationOutcome: "NOT_VALIDATED", failureClassification: null,
          ...(profile.providerAlias === "openrouter" ? { failureDiagnostic: null } : {}),
          ...(profile.purpose === "EVALUATION_ONLY" ? { contractDiagnostic: null } : {}),
          usage: normalizeProviderUsage(completion?.usage)
        };
        attempts.push(attempt);
        // Missing/empty content stays terminal, as in the original provider.
        let content;
        try { content = operation.content(completion); } catch (error) {
          attempt.validationOutcome = "INVALID_RESPONSE";
          attempt.failureClassification = sanitizedFailureClassification(error);
          throw error;
        }
        try {
          const result = operation.result(completion, content);
          executor.assertActive();
          attempt.validationOutcome = "VALIDATED";
          validationOutcome = "VALIDATED";
          return {
            ...result,
            execution: executionMetadata(profile, attempts, elapsedMilliseconds(startedAt, now()), validationOutcome, null)
          };
        } catch (error) {
          attempt.validationOutcome = error instanceof ProviderInvalidResponseError ? "INVALID_RESPONSE" : "NOT_VALIDATED";
          attempt.failureClassification = sanitizedFailureClassification(error);
          const contractDiagnostic = coachContractDiagnostic(error);
          if (profile.purpose === "EVALUATION_ONLY") attempt.contractDiagnostic = contractDiagnostic;
          if (!(error instanceof ProviderInvalidResponseError) || !executor.hasAttemptRemaining) throw error;
          executor.assertActive();
          material = operation.repair(content, contractDiagnostic);
          attemptClassification = "repair";
        }
      }
    } catch (error) {
      finalFailure = sanitizedFailureClassification(error);
      validationOutcome = attempts.at(-1)?.validationOutcome ?? "NOT_VALIDATED";
      throw error;
    } finally {
      executor.close();
      // Observation is non-authoritative; contain failures without logging private data.
      try {
        const observation = request.onExecutionMetadata?.(executionMetadata(profile, attempts, elapsedMilliseconds(startedAt, now()), validationOutcome, finalFailure));
        Promise.resolve(observation).catch(() => {});
      } catch {
        // Preserve the successful result or original provider/admission error.
      }
    }
  }
}
