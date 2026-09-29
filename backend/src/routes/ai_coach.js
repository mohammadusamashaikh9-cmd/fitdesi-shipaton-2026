import { validateCoachRequest } from "../schemas/requests.js";
import { coachResponse } from "../schemas/responses.js";
import { ProviderInvalidResponseError, RemoteAdmissionUnavailableError } from "../errors.js";
import { RemoteCapability } from "../remote_admission/capability_policy.js";
import { buildCoachGrounding } from "../services/knowledge_grounding.js";
import { reviewSafetyText } from "../services/safety_filter.js";
import { isValidatedAiCoachProviderResponse } from "../services/ai_coach_contract.js";

function deterministicCoach(input) {
  const safety = reviewSafetyText(input.question, input.limitations);
  if (safety.requiresEscalation) {
    return coachResponse({
      summary: "FitDesi can provide general fitness education but cannot diagnose or treat medical conditions.",
      recommendedAction: safety.escalationMessage,
      nutritionNote: "Avoid aggressive diet or supplement changes for medical concerns without professional guidance.",
      workoutNote: "Do not train through severe or worsening symptoms."
    });
  }
  return coachResponse({
    summary: "A repeatable plan built around training, balanced meals, and recovery is a strong starting point.",
    recommendedAction: "Choose one measurable action for this week and record the result in FitDesi.",
    nutritionNote: "Use familiar Pakistani foods with a protein source, vegetables or fruit, and measured starch portions.",
    workoutNote: "Progress gradually and stop sets when technique breaks down."
  });
}

export function projectValidatedCoachResponse(providerResponse) {
  const containsStructuredPlan = providerResponse?.generatedWorkoutPlan != null
    || providerResponse?.generatedDietPlan != null;
  if (containsStructuredPlan && !isValidatedAiCoachProviderResponse(providerResponse)) {
    throw new ProviderInvalidResponseError();
  }
  return {
    summary: providerResponse.summary,
    recommendedAction: providerResponse.recommendedAction,
    nutritionNote: providerResponse.nutritionNote,
    workoutNote: providerResponse.workoutNote,
    safetyDisclaimer: providerResponse.safetyDisclaimer,
    escalationRequired: providerResponse.escalationRequired,
    detectedIntent: providerResponse.detectedIntent,
    detectedGoal: providerResponse.detectedGoal,
    confidence: providerResponse.confidence,
    warnings: providerResponse.warnings,
    validationStatus: "VALIDATED",
    generatedWorkoutPlan: providerResponse.generatedWorkoutPlan,
    generatedDietPlan: providerResponse.generatedDietPlan
  };
}

function countsTowardCircuit(error) {
  return [
    "PROVIDER_UNAUTHORIZED", "PROVIDER_TIMEOUT", "PROVIDER_INVALID_RESPONSE",
    "PROVIDER_UNAVAILABLE"
  ].includes(error?.code);
}

export function createAiCoachRoute({
  config,
  provider,
  guard,
  remoteExecutionAdmission,
  groundingBuilder = buildCoachGrounding
}) {
  return async function aiCoachRoute(body, context = {}) {
    const input = validateCoachRequest(body);
    context.telemetry ??= {};
    context.telemetry.inputCharacterCount = JSON.stringify(input).length;

    if (config.provider === "mock") {
      context.telemetry.providerAlias = "mock";
      context.telemetry.validationResult = "mock_validated";
      context.telemetry.fallbackStatus = "not_required";
      return { data: deterministicCoach(input), mode: "mock" };
    }

    // Genuine red flags are handled deterministically before any paid provider
    // call. This path does not disclose health-adjacent text to Fireworks.
    const safety = reviewSafetyText(input.question, input.limitations);
    if (safety.requiresEscalation) {
      context.telemetry.providerAlias = "deterministic_safety";
      context.telemetry.validationResult = "medical_escalation";
      context.telemetry.fallbackStatus = "not_required";
      return { data: deterministicCoach(input), mode: "mock" };
    }

    provider.assertEnabled();
    if (typeof remoteExecutionAdmission?.begin !== "function") {
      throw new RemoteAdmissionUnavailableError();
    }
    context.telemetry.providerAlias = "fireworks";
    context.telemetry.fallbackStatus = "client_local_on_failure";
    const session = await remoteExecutionAdmission.begin({
      firebaseUid: context.firebaseUid,
      idempotencyKey: context.idempotencyKey,
      capability: RemoteCapability.REMOTE_AI_COACH,
      validatedRequest: input
    });
    let grounding;
    try {
      grounding = await groundingBuilder(input);
    } catch (error) {
      await session.fail();
      throw error;
    }
    let lease;
    try {
      lease = guard.acquire({
        ipAddress: context.ipAddress,
        installationId: context.installationId
      });
    } catch (error) {
      await session.fail();
      throw error;
    }

    let result;
    let projectedResponse;
    try {
      result = await provider.generateStructured({
        feature: "coach",
        input,
        question: input.question,
        grounding,
        beforeProviderAttempt: () => session.beforeProviderAttempt(),
        signal: context.signal
      });
      projectedResponse = projectValidatedCoachResponse(result.data);
    } catch (error) {
      lease.fail({ countsTowardCircuit: countsTowardCircuit(error) });
      context.telemetry.validationResult = error?.code ?? "provider_error";
      try {
        await session.fail();
      } finally {
        lease.release();
      }
      throw error;
    }

    lease.succeed();
    try {
      await session.succeed();
    } finally {
      lease.release();
    }
    context.telemetry.validationResult = "accepted";
    context.telemetry.tokenUsage = result.usage?.totalTokens ?? null;
    context.telemetry.fallbackStatus = "not_required";
    return { data: projectedResponse, mode: "fireworks" };
  };
}

// Retained for focused deterministic tests and direct local use.
export function aiCoachRoute(body) {
  return deterministicCoach(validateCoachRequest(body));
}
