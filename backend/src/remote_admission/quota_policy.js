import { CommercialTier } from "../commercial/revenuecat_commercial_authority.js";
import { QuotaPolicyUnavailableError } from "../errors.js";
import { RemoteCapability } from "./capability_policy.js";

const KNOWN_CAPABILITIES = new Set(Object.values(RemoteCapability));
const KNOWN_TIERS = new Set(Object.values(CommercialTier));
const MAX_POLICY_VERSION_LENGTH = 64;

function policyVersion(value) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > MAX_POLICY_VERSION_LENGTH ||
    value.trim() !== value ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)
  ) {
    throw new QuotaPolicyUnavailableError();
  }
  return value;
}

function quotaBucket(value) {
  if (
    value === null ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).sort().join("\0") !== "limit\0windowSeconds" ||
    !Number.isSafeInteger(value.limit) ||
    value.limit < 1 ||
    !Number.isSafeInteger(value.windowSeconds) ||
    value.windowSeconds < 1
  ) {
    throw new QuotaPolicyUnavailableError();
  }
  return Object.freeze({ limit: value.limit, windowSeconds: value.windowSeconds });
}

function normalizedRule(rule) {
  if (
    rule === null ||
    typeof rule !== "object" ||
    Object.keys(rule).sort().join("\0") !==
      ["capability", "providerAttempts", "successfulUses", "tier"].sort().join("\0") ||
    !KNOWN_CAPABILITIES.has(rule.capability) ||
    !KNOWN_TIERS.has(rule.tier)
  ) {
    throw new QuotaPolicyUnavailableError();
  }
  return Object.freeze({
    capability: rule.capability,
    tier: rule.tier,
    successfulUses: quotaBucket(rule.successfulUses),
    providerAttempts: quotaBucket(rule.providerAttempts)
  });
}

function disabledQuotaPolicy() {
  return Object.freeze({
    getPolicy() {
      throw new QuotaPolicyUnavailableError();
    }
  });
}

export function createQuotaPolicy(options) {
  if (options === undefined) return disabledQuotaPolicy();
  const {
    policyVersion: rawPolicyVersion,
    globalProviderAttempts,
    rules
  } = options;
  if (!Array.isArray(rules) || rules.length === 0) {
    throw new QuotaPolicyUnavailableError();
  }
  const version = policyVersion(rawPolicyVersion);
  const globalPolicy = quotaBucket(globalProviderAttempts);
  const policies = new Map();
  for (const rawRule of rules) {
    const rule = normalizedRule(rawRule);
    const key = `${rule.capability}\0${rule.tier}`;
    if (policies.has(key)) throw new QuotaPolicyUnavailableError();
    policies.set(key, rule);
  }

  return Object.freeze({
    getPolicy({ capability, tier } = {}) {
      const policy = policies.get(`${capability}\0${tier}`);
      if (!policy) throw new QuotaPolicyUnavailableError();
      return Object.freeze({
        ...policy,
        policyVersion: version,
        globalProviderAttempts: globalPolicy
      });
    }
  });
}

export const DEFAULT_QUOTA_POLICY = createQuotaPolicy({
  policyVersion: "stage12g3-remote-coach-v1",
  globalProviderAttempts: { limit: 1000, windowSeconds: 86400 },
  rules: [
    {
      capability: RemoteCapability.REMOTE_AI_COACH,
      tier: CommercialTier.PLUS,
      successfulUses: { limit: 3, windowSeconds: 86400 },
      providerAttempts: { limit: 9, windowSeconds: 86400 }
    },
    {
      capability: RemoteCapability.REMOTE_AI_COACH,
      tier: CommercialTier.PRO,
      successfulUses: { limit: 12, windowSeconds: 86400 },
      providerAttempts: { limit: 36, windowSeconds: 86400 }
    }
  ]
});
