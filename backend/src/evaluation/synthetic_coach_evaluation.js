import { ProviderConfigurationError, RemoteAdmissionUnavailableError } from "../errors.js";
import { RemoteCapability } from "../remote_admission/capability_policy.js";
import { createProviderRoutePolicy } from "../services/provider_route_policy.js";
import { elapsedMilliseconds, normalizeProviderUsage, sanitizedFailureClassification } from "../services/provider_execution_metadata.js";
import { getSyntheticCoachCase, SYNTHETIC_COACH_CASE_IDS } from "./synthetic_coach_cases.js";

/** In-memory only. Future real runs must supply a separately approved authority. */
export class SyntheticCoachEvaluation {
  #router;
  #admission;
  #now;

  constructor({ router, admission, now = () => performance.now() } = {}) {
    if (typeof router?.generateEvaluation !== "function" || typeof admission?.begin !== "function") {
      throw new RemoteAdmissionUnavailableError();
    }
    this.#router = router;
    this.#admission = admission;
    this.#now = now;
  }

  async run(options = {}) {
    if (Object.keys(options).some((key) => !["routeProfileId", "caseIds"].includes(key))) throw new ProviderConfigurationError();
    const { routeProfileId, caseIds = SYNTHETIC_COACH_CASE_IDS } = options;
    const profile = createProviderRoutePolicy().resolveEvaluation(routeProfileId, "SYNTHETIC_ONLY");
    if (!Array.isArray(caseIds) || !caseIds.length || caseIds.length > SYNTHETIC_COACH_CASE_IDS.length
        || new Set(caseIds).size !== caseIds.length) throw new ProviderConfigurationError();
    // Validate the complete selection before reserving any case or dispatching.
    const cases = caseIds.map(getSyntheticCoachCase);
    const reports = [];
    for (const value of cases) {
      let execution = null;
      let result = null;
      let failureCategory = null;
      const startedAt = this.#now();
      try {
        // The injected environment authority must bind identity/idempotency and
        // durable accounting; this harness invents neither and supplies no bypass.
        const session = await this.#admission.begin({
          syntheticCaseId: value.caseId,
          capability: RemoteCapability.REMOTE_AI_COACH,
          validatedRequest: value.input
        });
        if (!["beforeProviderAttempt", "succeed", "fail"].every((method) => typeof session?.[method] === "function")) {
          throw new RemoteAdmissionUnavailableError();
        }
        try {
          result = await this.#router.generateEvaluation({
            routeProfileId, dataClassification: value.dataClassification,
            feature: "coach", input: value.input, question: value.input.question, grounding: value.grounding,
            now: this.#now,
            beforeProviderAttempt: () => session.beforeProviderAttempt(),
            onExecutionMetadata: (metadata) => { execution = metadata; }
          });
        } catch (error) {
          await session.fail();
          throw error;
        }
        // Success settlement failure is terminal; never retry or fail again.
        await session.succeed();
      } catch (error) {
        failureCategory = sanitizedFailureClassification(error);
      }
      reports.push(Object.freeze({
        syntheticCaseId: value.caseId, routeProfileId,
        configuredModelId: profile.modelId,
        actualModelId: execution?.actualModelId ?? null,
        actualProvider: execution?.actualProvider ?? null,
        contractPass: failureCategory === value.expectedFailure && (execution?.attemptsUsed ?? 0) > 0
          && (value.expectedFailure !== null || execution?.validationOutcome === "VALIDATED"),
        // Successful domain validation does not adjudicate semantic model quality.
        qualityReviewRequired: value.expectedFailure === null,
        qualityResult: null,
        attemptsUsed: execution?.attemptsUsed ?? 0,
        validationResult: execution?.validationOutcome ?? "NOT_VALIDATED",
        latencyMs: elapsedMilliseconds(startedAt, this.#now()),
        usage: execution?.aggregateUsage ?? normalizeProviderUsage(null),
        cost: null,
        failureCategory,
        contractDiagnostic: execution?.contractDiagnostic ?? null,
        failureDiagnostic: execution?.failureDiagnostic ?? null,
        execution: execution ?? result?.execution ?? null
      }));
    }
    return Object.freeze(reports);
  }
}
