import { RemoteAdmissionUnavailableError } from "../errors.js";
import { deriveOpaqueAccountKey, deriveServerHmac } from "../remote_admission/account_identity.js";
import { RemoteCapability } from "../remote_admission/capability_policy.js";
import { createQuotaPolicy } from "../remote_admission/quota_policy.js";
import { RemoteExecutionAdmission } from "../remote_admission/remote_execution_admission.js";
import { createProviderRoutePolicy } from "../services/provider_route_policy.js";
import { getSyntheticCoachCase } from "./synthetic_coach_cases.js";
import { LocalEvaluationAdmissionStore, LOCAL_EVALUATION_POLICY_VERSION,
  validateLocalEvaluationLedgerPath } from "./local_evaluation_admission_store.js";

const EVALUATION_NAMESPACE = "fitdesi-local-evaluation";
const CAPABILITY = RemoteCapability.REMOTE_AI_COACH;

/** Server-only local accounting configuration. No defaults or production fallback. */
export function requireEvaluationEnvironment(environment) {
  if (!environment || Object.keys(environment).sort().join("\0") !==
      ["approved", "accountingMode", "evaluationRunId", "hmacSecret", "ledgerPath", "limits"].sort().join("\0")
      || environment.approved !== true || environment.accountingMode !== "LOCAL_DURABLE_LEDGER"
      || typeof environment.evaluationRunId !== "string"
      || !/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(environment.evaluationRunId)) {
    throw new RemoteAdmissionUnavailableError();
  }
  const ledgerPath = validateLocalEvaluationLedgerPath(environment.ledgerPath);
  // Validate key material before constructing any SDK/dependency.
  deriveOpaqueAccountKey({ firebaseProjectId: EVALUATION_NAMESPACE,
    firebaseUid: "synthetic-evaluation-validation", hmacSecret: environment.hmacSecret });
  const limits = environment.limits;
  if (!limits || Object.keys(limits).sort().join("\0") !==
      ["successfulUses", "providerAttempts", "globalProviderAttempts"].sort().join("\0")) {
    throw new RemoteAdmissionUnavailableError();
  }
  // BASIC is only a schema-compatible bookkeeping label in the isolated ledger.
  // This policy grants no user entitlement and never modifies DEFAULT_QUOTA_POLICY.
  const quotaPolicy = createQuotaPolicy({
    policyVersion: LOCAL_EVALUATION_POLICY_VERSION, globalProviderAttempts: limits.globalProviderAttempts,
    rules: [{ capability: CAPABILITY, tier: "BASIC",
      successfulUses: limits.successfulUses, providerAttempts: limits.providerAttempts }]
  });
  // Storage bounds are validation ceilings, not default budgets.
  for (const bucket of Object.values(limits)) {
    if (bucket.limit > 1_000_000 || bucket.windowSeconds > 31_536_000) throw new RemoteAdmissionUnavailableError();
  }
  return Object.freeze({ hmacSecret: environment.hmacSecret, quotaPolicy, ledgerPath,
    evaluationRunId: environment.evaluationRunId });
}

/** Reuse unchanged Stage 12E sessions with evaluation-only local durable accounting. */
export function createDurableEvaluationAdmission({ environment, config, routeProfileId,
  storeFactory = (options) => new LocalEvaluationAdmissionStore(options), clock } = {}) {
  const { hmacSecret, quotaPolicy, ledgerPath, evaluationRunId } = requireEvaluationEnvironment(environment);
  const profile = createProviderRoutePolicy().resolveEvaluation(routeProfileId, "SYNTHETIC_ONLY");
  const operationBudgetMs = profile.providerAlias === "fireworks" ? config?.fireworksEvaluationTimeoutMs
    : profile.providerAlias === "openrouter" ? config?.openrouterEvaluationTimeoutMs : null;
  if (!Number.isSafeInteger(config?.remoteAdmissionLeaseSeconds)
      || config.remoteAdmissionLeaseSeconds < 30 || config.remoteAdmissionLeaseSeconds > 600
      || !Number.isSafeInteger(operationBudgetMs)
      || operationBudgetMs < 1000 || operationBudgetMs > 120000
      || config.remoteAdmissionLeaseSeconds * 1000 < operationBudgetMs + 15000) {
    throw new RemoteAdmissionUnavailableError();
  }
  const syntheticIdentity = `synthetic-evaluation-${routeProfileId}`;
  const store = storeFactory({ ledgerPath, hmacSecret, leaseDurationSeconds: config.remoteAdmissionLeaseSeconds,
    ...(clock ? { clock } : {}) });
  const admission = new RemoteExecutionAdmission({
    // This is only the opaque identity namespace expected by Stage 12E, never a cloud project.
    firebaseProjectId: EVALUATION_NAMESPACE, hmacSecret, quotaPolicy, store,
    admissionAuthority: { async authorize({ firebaseUid, capability }) {
      if (firebaseUid !== syntheticIdentity || capability !== CAPABILITY) throw new RemoteAdmissionUnavailableError();
      return Object.freeze({ capability: CAPABILITY, accessBasis: "SYNTHETIC_EVALUATION", commercialTier: "BASIC" });
    } }
  });
  return Object.freeze({ async begin(request = {}) {
    if (Object.keys(request).sort().join("\0") !==
        ["syntheticCaseId", "capability", "validatedRequest"].sort().join("\0")
        || request.capability !== CAPABILITY) throw new RemoteAdmissionUnavailableError();
    const value = getSyntheticCoachCase(request.syntheticCaseId);
    if (JSON.stringify(request.validatedRequest) !== JSON.stringify(value.input)) throw new RemoteAdmissionUnavailableError();
    const idempotencyKey = `eval_${deriveServerHmac({ hmacSecret, purpose: "evaluation-request",
      value: `${evaluationRunId}\0${routeProfileId}\0${value.caseId}` })}`;
    return admission.begin({ firebaseUid: syntheticIdentity, idempotencyKey,
      capability: CAPABILITY, validatedRequest: value.input });
  } });
}
