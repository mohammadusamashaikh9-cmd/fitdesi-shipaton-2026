import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.js";
import { validateCoachRequest } from "../src/schemas/requests.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { FireworksProvider } from "../src/services/fireworks_provider.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";
import {
  createProviderRoutePolicy, OPENROUTER_EVALUATION_PROFILES,
  PRODUCTION_OPENROUTER_ROUTE_PROFILES, requireOpenRouterEvaluationProfile
} from "../src/services/provider_route_policy.js";

const candidates = [
  ["eval-openrouter-nex-n2-5-pro-free", "nex-agi/nex-n2.5-pro:free", "STRICT_PRIVACY", "UNVERIFIED_EVALUATION_ONLY"],
  ["eval-openrouter-glm-5-2-free", "z-ai/glm-5.2:free", "SYNTHETIC_FREE_TIER", "SYNTHETIC_FREE_TIER_EVALUATION_ONLY"],
  ["eval-openrouter-nemotron-3-ultra-free", "nvidia/nemotron-3-ultra-550b-a55b:free", "SYNTHETIC_FREE_TIER", "SYNTHETIC_FREE_TIER_EVALUATION_ONLY"],
  ["eval-openrouter-ling-3-flash-sante-free", "inclusionai/ling-3.0-flash-sante:free", "SYNTHETIC_FREE_TIER", "SYNTHETIC_FREE_TIER_EVALUATION_ONLY"],
  ["eval-openrouter-qwen-3-8-27b-free", "qwen/qwen3.8-27b:free", "SYNTHETIC_FREE_TIER", "SYNTHETIC_FREE_TIER_EVALUATION_ONLY"],
  ["eval-openrouter-ling-3-flash-fin-free", "inclusionai/ling-3.0-flash-fin:free", "STRICT_PRIVACY", "UNVERIFIED_EVALUATION_ONLY"],
  ["eval-openrouter-ling-3-flash-vl-free", "inclusionai/ling-3.0-flash-vl:free", "STRICT_PRIVACY", "UNVERIFIED_EVALUATION_ONLY"],
  ["eval-openrouter-deepseek-v4-flash-0731-free", "deepseek/deepseek-v4-flash-0731:free", "STRICT_PRIVACY", "UNVERIFIED_EVALUATION_ONLY"]
];
const models = candidates.map(([, modelId]) => modelId);

test("only the exact approved evaluation shortlist is registered with explicit immutable modes", () => {
  assert.deepEqual(OPENROUTER_EVALUATION_PROFILES.map((profile) => profile.modelId), models);
  const policy = createProviderRoutePolicy({ fireworksModel: "configured-fireworks-model" });
  for (const [index, profile] of OPENROUTER_EVALUATION_PROFILES.entries()) {
    assert.equal(policy.resolveEvaluation(profile.routeProfileId, "SYNTHETIC_ONLY"), profile);
    assert.equal(profile.purpose, "EVALUATION_ONLY");
    assert.equal(profile.dataClassification, "SYNTHETIC_ONLY");
    assert.equal(profile.routeProfileId, candidates[index][0]);
    assert.equal(profile.routingMode, candidates[index][2]);
    assert.equal(profile.privacyClassification, candidates[index][3]);
    assert.equal(profile.structuredOutputMode, profile.routeProfileId === "eval-openrouter-deepseek-v4-flash-0731-free" ? "JSON_SCHEMA" : "PROMPT_JSON");
    assert.equal(profile.structuredOutputCapability, "UNVERIFIED");
    assert.equal(profile.routePolicyVersion, "stage12f3b6-v1");
    assert.equal(Object.isFrozen(profile), true);
    assert.throws(() => { profile.routingMode = "UNKNOWN"; }, TypeError);
  }
});

test("unknown models, auto routes, presets and model arrays fail closed", () => {
  const policy = createProviderRoutePolicy({});
  for (const value of ["openrouter/free", "openrouter/auto", "@preset/example", "unknown/model", ...models, models, null]) {
    assert.throws(() => policy.resolveEvaluation(value, "SYNTHETIC_ONLY"));
  }
  for (const profile of OPENROUTER_EVALUATION_PROFILES) {
    assert.throws(() => requireOpenRouterEvaluationProfile({ ...profile }));
    assert.throws(() => requireOpenRouterEvaluationProfile({ ...profile, modelId: models }));
    for (const routingMode of ["UNKNOWN", "STRICT_PRIVACY", "SYNTHETIC_FREE_TIER"]) {
      assert.throws(() => requireOpenRouterEvaluationProfile({ ...profile, routingMode }));
    }
  }
});

test("production route remains Fireworks and cannot resolve an evaluation candidate", () => {
  const policy = createProviderRoutePolicy({ fireworksModel: "configured-fireworks-model", openrouterApiKey: "configured-for-injected-mock" });
  const profile = policy.resolveProduction("REMOTE_AI_COACH");
  assert.equal(profile.providerAlias, "fireworks");
  assert.equal(profile.routePolicyVersion, "stage12f3b6-v1");
  assert.equal(profile.modelId, "configured-fireworks-model");
  assert.deepEqual(PRODUCTION_OPENROUTER_ROUTE_PROFILES, []);
  assert.equal(Object.isFrozen(PRODUCTION_OPENROUTER_ROUTE_PROFILES), true);
  for (const candidate of OPENROUTER_EVALUATION_PROFILES) {
    assert.throws(() => policy.resolveProduction(candidate.routeProfileId));
    assert.throws(() => policy.resolveProduction(candidate.modelId));
    assert.throws(() => policy.resolveEvaluation(candidate.routeProfileId, "USER_DATA"));
  }
});

test("Coach input cannot choose any infrastructure routing field", () => {
  for (const field of ["provider", "providerAlias", "model", "modelId", "models", "routeProfile", "routeProfileId", "structuredOutputMode",
    "routingMode", "privacyClassification", "providerRouting", "privacySettings", "zdr", "data_collection"]) {
    assert.throws(() => validateCoachRequest({ question: "Help me train", [field]: models[0] }));
  }
});

test("OpenRouter credentials never change default provider or launch remote AI", () => {
  const config = loadConfig({ openrouterApiKey: "configured-for-injected-mock" });
  assert.equal(config.provider, "mock");
  assert.equal(config.remoteAiEnabled, false);
  assert.throws(() => loadConfig({ provider: "openrouter" }));
});

test("normal Coach routing uses Fireworks even with an injected OpenRouter adapter and credential", async () => {
  let fireworksCalls = 0;
  let openrouterCalls = 0;
  const value = getSyntheticCoachCase("coach-basic");
  const fireworksProvider = new FireworksProvider({
    config: {
      provider: "fireworks", remoteAiEnabled: true, fireworksApiKey: "configured-for-injected-mock",
      fireworksModel: "configured-fireworks-model", fireworksTimeoutMs: 100, openrouterApiKey: "configured-for-injected-mock"
    },
    client: { chat: { completions: { async create() {
      fireworksCalls += 1;
      return { choices: [{ message: { content: JSON.stringify(value.expectedResponse) } }] };
    } } } }
  });
  const router = new ProviderRouter({ fireworksProvider, openrouterProvider: {
    assertEnabled() { openrouterCalls += 1; assert.fail("production cannot inspect the evaluation adapter"); }
  } });
  const result = await router.generateStructured({ input: value.input, grounding: value.grounding, beforeProviderAttempt: async () => {} });
  assert.equal(fireworksCalls, 1);
  assert.equal(openrouterCalls, 0);
  assert.equal(result.execution.providerAlias, "fireworks");
  assert.equal(result.execution.configuredModelId, "configured-fireworks-model");
  assert.equal(result.execution.routePolicyVersion, "stage12f3b6-v1");
});
