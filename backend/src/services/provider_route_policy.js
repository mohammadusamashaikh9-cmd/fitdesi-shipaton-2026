import { ProviderConfigurationError, ProviderInvalidResponseError } from "../errors.js";
import { RemoteCapability } from "../remote_admission/capability_policy.js";

export const ROUTE_POLICY_VERSION = "stage12f3b6-v1";
export const FIREWORKS_COACH_PROFILE_ID = "coach-fireworks-current";
export const PRODUCTION_OPENROUTER_ROUTE_PROFILES = Object.freeze([]);
export const OPENROUTER_ROUTING_MODE = Object.freeze({
  STRICT_PRIVACY: "STRICT_PRIVACY",
  SYNTHETIC_FREE_TIER: "SYNTHETIC_FREE_TIER"
});
const trustedEvaluationProfiles = new WeakSet();

export const OPENROUTER_EVALUATION_PROFILES = Object.freeze([
  // Retain Nex's historical strict controls; it is outside the next pilot shortlist.
  ["eval-openrouter-nex-n2-5-pro-free", "nex-agi/nex-n2.5-pro:free", OPENROUTER_ROUTING_MODE.STRICT_PRIVACY],
  ["eval-openrouter-glm-5-2-free", "z-ai/glm-5.2:free", OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER],
  ["eval-openrouter-nemotron-3-ultra-free", "nvidia/nemotron-3-ultra-550b-a55b:free", OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER],
  ["eval-openrouter-ling-3-flash-sante-free", "inclusionai/ling-3.0-flash-sante:free", OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER],
  ["eval-openrouter-qwen-3-8-27b-free", "qwen/qwen3.8-27b:free", OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER],
  ["eval-openrouter-ling-3-flash-fin-free", "inclusionai/ling-3.0-flash-fin:free", OPENROUTER_ROUTING_MODE.STRICT_PRIVACY],
  ["eval-openrouter-ling-3-flash-vl-free", "inclusionai/ling-3.0-flash-vl:free", OPENROUTER_ROUTING_MODE.STRICT_PRIVACY],
  ["eval-openrouter-deepseek-v4-flash-0731-free", "deepseek/deepseek-v4-flash-0731:free", OPENROUTER_ROUTING_MODE.STRICT_PRIVACY]
].map(([routeProfileId, modelId, routingMode]) => {
  const profile = Object.freeze({
    routeProfileId, providerAlias: "openrouter", modelId,
    purpose: "EVALUATION_ONLY", dataClassification: "SYNTHETIC_ONLY",
    privacyClassification: routingMode === OPENROUTER_ROUTING_MODE.SYNTHETIC_FREE_TIER
      ? "SYNTHETIC_FREE_TIER_EVALUATION_ONLY" : "UNVERIFIED_EVALUATION_ONLY",
    routingMode,
    // Exact DeepSeek route uses schema mode per manager probe, not Coach quality qualification.
    structuredOutputMode: routeProfileId === "eval-openrouter-deepseek-v4-flash-0731-free" ? "JSON_SCHEMA" : "PROMPT_JSON",
    structuredOutputCapability: "UNVERIFIED",
    routePolicyVersion: ROUTE_POLICY_VERSION
  });
  trustedEvaluationProfiles.add(profile);
  return profile;
}));

export function requireOpenRouterEvaluationProfile(profile) {
  if (!trustedEvaluationProfiles.has(profile)) throw new ProviderConfigurationError();
  return profile;
}

const trustedFireworksEvaluationProfiles = new WeakSet();
export const FIREWORKS_EVALUATION_PROFILES = Object.freeze([
  "gpt-oss-120b", "glm-5p3-flash", "deepseek-v4p1-flash", "nemotron-lightning-3p5-30b-a3b"
].map((model) => {
  const profile = Object.freeze({
    routeProfileId: `eval-fireworks-${model}`, providerAlias: "fireworks",
    modelId: `accounts/fireworks/models/${model}`,
    purpose: "EVALUATION_ONLY", dataClassification: "SYNTHETIC_ONLY",
    privacyClassification: "UNVERIFIED_EVALUATION_ONLY",
    structuredOutputMode: "JSON_SCHEMA", structuredOutputCapability: "UNVERIFIED",
    ...(model === "nemotron-lightning-3p5-30b-a3b" ? { enableThinking: false } : {}),
    routePolicyVersion: ROUTE_POLICY_VERSION
  });
  trustedFireworksEvaluationProfiles.add(profile);
  return profile;
}));

export function requireFireworksEvaluationProfile(profile) {
  if (!trustedFireworksEvaluationProfiles.has(profile)) throw new ProviderConfigurationError();
  return profile;
}

export function createProviderRoutePolicy(config = {}) {
  const current = Object.freeze({
    routeProfileId: FIREWORKS_COACH_PROFILE_ID, providerAlias: "fireworks",
    modelId: typeof config.fireworksModel === "string" ? config.fireworksModel.trim() || null : null,
    purpose: "CURRENT_FIREWORKS_COMPATIBLE", dataClassification: "EXISTING_COACH_BOUNDARY",
    privacyClassification: "EXISTING_FIREWORKS_BOUNDARY",
    structuredOutputMode: "JSON_SCHEMA", structuredOutputCapability: "EXISTING_CONFIGURED",
    routePolicyVersion: ROUTE_POLICY_VERSION
  });
  return Object.freeze({
    resolveProduction(capability) {
      if (capability !== RemoteCapability.REMOTE_AI_COACH) throw new ProviderInvalidResponseError();
      return current;
    },
    resolveEvaluation(routeProfileId, dataClassification) {
      const profile = [...OPENROUTER_EVALUATION_PROFILES, ...FIREWORKS_EVALUATION_PROFILES]
        .find((value) => value.routeProfileId === routeProfileId);
      if (!profile || dataClassification !== "SYNTHETIC_ONLY") throw new ProviderConfigurationError();
      return profile;
    }
  });
}
