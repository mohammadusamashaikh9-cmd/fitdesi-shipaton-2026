import { ProviderConfigurationError, RemoteAdmissionUnavailableError } from "../errors.js";
import { loadConfig } from "../config.js";
import { OpenRouterProvider } from "../services/openrouter_provider.js";
import { FireworksEvaluationProvider } from "../services/fireworks_evaluation_provider.js";
import { ProviderRouter } from "../services/provider_router.js";
import { createProviderRoutePolicy } from "../services/provider_route_policy.js";
import { createDurableEvaluationAdmission, requireEvaluationEnvironment } from "./durable_evaluation_admission.js";
import { getSyntheticCoachCase, SYNTHETIC_COACH_CASE_IDS } from "./synthetic_coach_cases.js";
import { SyntheticCoachEvaluation } from "./synthetic_coach_evaluation.js";

/** Internal runner, never HTTP-wired. Only run() selection is caller input.
 * Trusted server construction supplies separately reviewed environment/budgets.
 * Fetch/clock dependencies are test seams; the live default always constructs durable Stage 12E admission.
 * Import/construction does not dispatch, reserve or create ledger files.
 */
export function createControlledEvaluationRunner({ environment, config = loadConfig(),
  storeFactory, clock, fetchImpl, fireworksClientFactory } = {}) {
  const validatedEnvironment = requireEvaluationEnvironment(environment);
  const safeConfig = loadConfig({ ...config });
  if (safeConfig.provider !== "mock" || safeConfig.remoteAiEnabled !== false) throw new RemoteAdmissionUnavailableError();
  // Snapshot approved server dependencies to prevent post-construction relabeling.
  const policy = validatedEnvironment.quotaPolicy.getPolicy({ capability: "REMOTE_AI_COACH", tier: "BASIC" });
  const approvedEnvironment = Object.freeze({ approved: true, accountingMode: "LOCAL_DURABLE_LEDGER",
    evaluationRunId: validatedEnvironment.evaluationRunId, ledgerPath: validatedEnvironment.ledgerPath,
    hmacSecret: validatedEnvironment.hmacSecret,
    limits: Object.freeze({ successfulUses: policy.successfulUses,
      providerAttempts: policy.providerAttempts, globalProviderAttempts: policy.globalProviderAttempts }) });
  const adapter = new OpenRouterProvider({ config: safeConfig, evaluationEnabled: true,
    ...(fetchImpl ? { fetchImpl } : {}) });
  const fireworksAdapter = new FireworksEvaluationProvider({ config: safeConfig, evaluationEnabled: true,
    ...(fireworksClientFactory ? { clientFactory: fireworksClientFactory } : {}) });
  const router = new ProviderRouter({ openrouterProvider: adapter, fireworksEvaluationProvider: fireworksAdapter });
  const evaluations = new Map();
  let running = false;
  return Object.freeze({ async run(options = {}) {
    if (!options || Object.keys(options).sort().join("\0") !== "caseIds\0routeProfileId") throw new ProviderConfigurationError();
    const { routeProfileId, caseIds } = options;
    createProviderRoutePolicy().resolveEvaluation(routeProfileId, "SYNTHETIC_ONLY");
    if (!Array.isArray(caseIds) || !caseIds.length || caseIds.length > SYNTHETIC_COACH_CASE_IDS.length
        || new Set(caseIds).size !== caseIds.length) throw new ProviderConfigurationError();
    const selectedCases = [...caseIds];
    selectedCases.forEach(getSyntheticCoachCase);
    if (running) throw new RemoteAdmissionUnavailableError();
    running = true;
    try {
      if (!evaluations.has(routeProfileId)) {
        evaluations.set(routeProfileId, new SyntheticCoachEvaluation({ router,
          admission: createDurableEvaluationAdmission({ environment: approvedEnvironment,
            config: safeConfig, routeProfileId, storeFactory, clock }) }));
      }
      return await evaluations.get(routeProfileId).run({ routeProfileId, caseIds: selectedCases });
    } finally { running = false; }
  } });
}
