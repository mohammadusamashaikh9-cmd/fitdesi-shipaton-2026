import assert from "node:assert/strict";
import test from "node:test";
import { FireworksEvaluationProvider } from "../src/services/fireworks_evaluation_provider.js";
import { FireworksProvider } from "../src/services/fireworks_provider.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { FIREWORKS_EVALUATION_PROFILES, createProviderRoutePolicy, requireFireworksEvaluationProfile,
  requireOpenRouterEvaluationProfile, PRODUCTION_OPENROUTER_ROUTE_PROFILES } from "../src/services/provider_route_policy.js";
import { ProviderConfigurationError, ProviderDisabledError, ProviderInvalidResponseError, ProviderTimeoutError,
  ProviderUnauthorizedError, ProviderRateLimitError, RemoteAccountQuotaExhaustedError } from "../src/errors.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

const value = getSyntheticCoachCase("coach-basic");
const completion = (content = JSON.stringify(value.expectedResponse)) => ({
  provider: "Fireworks", model: "accounts/fireworks/models/served", choices: [{ message: { content } }]
});
const models = ["gpt-oss-120b", "glm-5p3-flash", "deepseek-v4p1-flash", "nemotron-lightning-3p5-30b-a3b"];
function fixture(sequence = [completion()], overrides = {}) {
  const events = [], calls = [], clients = [];
  let time = 0, metadata;
  const config = { provider: "mock", remoteAiEnabled: false, fireworksApiKey: "INJECTED_CLIENT_ONLY",
    fireworksTimeoutMs: 8000, fireworksMaxOutputTokens: 1800,
    fireworksEvaluationTimeoutMs: 30000, fireworksEvaluationMaxOutputTokens: 4096, ...overrides };
  const adapter = new FireworksEvaluationProvider({ config, evaluationEnabled: true, clientFactory(options) {
    clients.push(options);
    return { chat: { completions: { async create(body, dispatchOptions) {
      events.push("dispatch"); calls.push({ body, options: dispatchOptions });
      const next = sequence.shift();
      if (typeof next === "function") return next();
      if (next instanceof Error) throw next;
      return next;
    } } } };
  } });
  const router = new ProviderRouter({ fireworksEvaluationProvider: adapter,
    openrouterProvider: { assertEnabled() { assert.fail("wrong evaluation adapter"); } } });
  const request = { routeProfileId: "eval-fireworks-gpt-oss-120b", dataClassification: "SYNTHETIC_ONLY",
    input: value.input, grounding: value.grounding, now: () => time,
    beforeProviderAttempt: async () => { events.push("authorized"); },
    onExecutionMetadata(result) { metadata = result; } };
  return { adapter, router, request, events, calls, clients, config,
    advance(number) { time = number; }, get metadata() { return metadata; } };
}

test("exact Fireworks evaluation profiles are immutable, trusted, synthetic and never production routes", () => {
  const policy = createProviderRoutePolicy();
  assert.equal(FIREWORKS_EVALUATION_PROFILES.length, 4);
  for (const [index, profile] of FIREWORKS_EVALUATION_PROFILES.entries()) {
    assert.equal(profile.routeProfileId, `eval-fireworks-${models[index]}`);
    assert.equal(profile.modelId, `accounts/fireworks/models/${models[index]}`);
    assert.equal(profile.providerAlias, "fireworks");
    assert.equal(profile.purpose, "EVALUATION_ONLY");
    assert.equal(profile.dataClassification, "SYNTHETIC_ONLY");
    assert.equal(profile.structuredOutputMode, "JSON_SCHEMA");
    assert.equal(profile.routePolicyVersion, "stage12f3b6-v1");
    assert.equal(policy.resolveEvaluation(profile.routeProfileId, "SYNTHETIC_ONLY"), profile);
    assert.equal(requireFireworksEvaluationProfile(profile), profile);
    assert.equal(Object.isFrozen(profile), true);
    assert.throws(() => { profile.modelId = "arbitrary/model"; }, TypeError);
    assert.throws(() => requireFireworksEvaluationProfile({ ...profile }));
    assert.throws(() => requireOpenRouterEvaluationProfile(profile));
    assert.throws(() => requireFireworksEvaluationProfile({ ...profile, providerAlias: "unknown" }));
    assert.throws(() => policy.resolveProduction(profile.routeProfileId));
    assert.throws(() => policy.resolveEvaluation(profile.routeProfileId, "USER_DATA"));
    assert.throws(() => policy.resolveEvaluation(profile.modelId, "SYNTHETIC_ONLY"));
  }
  assert.deepEqual(PRODUCTION_OPENROUTER_ROUTE_PROFILES, []);
});

test("all evaluation models use schema and independent SDK budgets without enabling production", async () => {
  for (const profile of FIREWORKS_EVALUATION_PROFILES) {
    const current = fixture();
    assert.equal(current.clients.length, 0);
    assert.equal(current.calls.length, 0);
    const result = await current.router.generateEvaluation({ ...current.request, routeProfileId: profile.routeProfileId });
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.equal(current.clients[0].baseURL, "https://api.fireworks.ai/inference/v1");
    assert.equal(current.clients[0].timeout, 30000);
    assert.equal(current.clients[0].maxRetries, 0);
    assert.equal(current.calls[0].options.maxRetries, 0);
    assert.equal(current.calls[0].options.timeout, 30000);
    assert.equal(current.calls[0].body.model, profile.modelId);
    assert.equal(current.calls[0].body.max_tokens, 4096);
    assert.equal(current.calls[0].body.response_format.json_schema.strict, true);
    assert.deepEqual(current.calls[0].body.chat_template_kwargs,
      profile.enableThinking === false ? { enable_thinking: false } : undefined);
    assert.equal(result.execution.actualProvider, "fireworks");
    assert.equal(result.execution.contractDiagnostic, null);
    assert.equal(Object.hasOwn(result.data, "contractDiagnostic"), false);
    assert.throws(() => new FireworksProvider({ config: current.config }).assertEnabled(), ProviderDisabledError);
    await assert.rejects(current.router.generateStructured(current.request));
  }
});

test("activation and selected credential requirements fail closed without SDK or dispatch", () => {
  const profile = FIREWORKS_EVALUATION_PROFILES[0];
  for (const options of [{}, { evaluationEnabled: true },
    { evaluationEnabled: false, config: { fireworksApiKey: "INJECTED_CLIENT_ONLY" } }]) {
    const adapter = new FireworksEvaluationProvider({ ...options, clientFactory() { assert.fail("must stay lazy"); } });
    assert.throws(() => adapter.assertEnabled(profile));
  }
  const current = fixture();
  assert.throws(() => current.adapter.assertEnabled(createProviderRoutePolicy().resolveProduction("REMOTE_AI_COACH")));
  assert.throws(() => current.adapter.assertEnabled({ ...profile, modelId: "arbitrary/model" }));
  assert.equal(current.clients.length, 0);
});

test("caller model, profile, template and thinking options never authorize or dispatch", async () => {
  for (const field of ["modelId", "provider", "chat_template_kwargs", "enable_thinking", "enableThinking",
    "thinking", "providerOptions", "structuredOutputMode", "profile"]) {
    const current = fixture();
    await assert.rejects(current.router.generateEvaluation({ ...current.request, [field]: false }), ProviderConfigurationError);
    assert.deepEqual(current.events, []);
  }
});

test("private seam rejects arbitrary model/options and enforces trusted schema/no-thinking policy", async () => {
  const profile = FIREWORKS_EVALUATION_PROFILES.at(-1);
  for (const extra of [{ model: "arbitrary/model" }, { tools: [] }, { provider: {} }]) {
    const current = fixture();
    const executor = current.adapter.createAttemptExecutor(current.request, profile);
    try {
      const material = current.adapter.prepareCoach(current.request, profile).initial;
      await assert.rejects(executor.execute({ ...material, ...extra }));
      assert.equal(current.calls.length, 0);
    } finally { executor.close(); }
  }
  const current = fixture();
  const executor = current.adapter.createAttemptExecutor(current.request, profile);
  try {
    const material = current.adapter.prepareCoach(current.request, profile).initial;
    await executor.execute({ ...material, chat_template_kwargs: { enable_thinking: true }, response_format: {} });
    assert.deepEqual(current.calls[0].body.chat_template_kwargs, { enable_thinking: false });
    assert.equal(current.calls[0].body.response_format.json_schema.strict, true);
  } finally { executor.close(); }
});

test("Nemotron repair uses trusted no-thinking policy and remaining shared deadline", async () => {
  const current = fixture([() => { current.advance(10000); return completion("not-json"); }, completion()]);
  const result = await current.router.generateEvaluation({ ...current.request, routeProfileId: FIREWORKS_EVALUATION_PROFILES.at(-1).routeProfileId });
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.deepEqual(current.calls.map((call) => call.options.timeout), [30000, 20000]);
  assert.deepEqual(current.calls.map((call) => call.body.chat_template_kwargs), [{ enable_thinking: false }, { enable_thinking: false }]);
  assert.equal(current.calls[0].options.signal, current.calls[1].options.signal);
  assert.equal(result.execution.contractDiagnostic, null);
  assert.deepEqual(result.execution.attempts.map((attempt) => attempt.contractDiagnostic), ["JSON_PARSE_FAILED", null]);
});

test("failed validation/repair stays at two authorized calls and emits only bounded diagnostics", async () => {
  const current = fixture([completion("{}"), completion("{}"), completion()]);
  await assert.rejects(current.router.generateEvaluation(current.request), ProviderInvalidResponseError);
  assert.equal(current.calls.length, 2);
  assert.equal(current.metadata.contractDiagnostic, "TOP_LEVEL_CONTRACT_INVALID");
  assert.equal(JSON.stringify(current.metadata).includes("INJECTED_CLIENT_ONLY"), false);
  assert.equal(JSON.stringify(current.metadata).includes(value.input.question), false);
});

test("transient retry cannot stack repair or call a different provider", async () => {
  const current = fixture([Object.assign(new Error("synthetic detail"), { status: 503 }), completion("not-json"), completion()]);
  await assert.rejects(current.router.generateEvaluation(current.request), ProviderInvalidResponseError);
  assert.equal(current.calls.length, 2);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.deepEqual(current.metadata.attempts.map((attempt) => attempt.attemptClassification), ["initial", "retry"]);
});

test("first/second authorization failure and expired authorization cannot cause unaccounted dispatch", async () => {
  for (const failureAt of [1, 2]) {
    const current = fixture([completion("not-json"), completion()]);
    let authorizations = 0;
    await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => {
      if (++authorizations === failureAt) throw new RemoteAccountQuotaExhaustedError();
      current.events.push("authorized");
    } }), RemoteAccountQuotaExhaustedError);
    assert.equal(current.calls.length, failureAt - 1);
    assert.equal(current.metadata.contractDiagnostic, null);
  }
  const current = fixture();
  await assert.rejects(current.router.generateEvaluation({ ...current.request,
    beforeProviderAttempt: async () => current.advance(30000) }), ProviderTimeoutError);
  assert.equal(current.calls.length, 0);
});

test("401/403/429 remain terminal with stable public mapping and no hidden SDK retry", async () => {
  for (const status of [401, 403, 429]) {
    const current = fixture([Object.assign(new Error("synthetic private error detail"), { status })]);
    await assert.rejects(current.router.generateEvaluation(current.request), status === 429 ? ProviderRateLimitError : ProviderUnauthorizedError);
    assert.equal(current.calls.length, 1);
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.equal(current.metadata.contractDiagnostic, null);
    assert.equal(JSON.stringify(current.metadata).includes("synthetic private error detail"), false);
  }
});

test("caller cancellation before or during SDK dispatch cannot authorize an extra attempt", async () => {
  const controller = new AbortController();
  controller.abort();
  const before = fixture();
  await assert.rejects(before.router.generateEvaluation({ ...before.request, signal: controller.signal }), ProviderTimeoutError);
  assert.deepEqual(before.events, []);
  const inFlight = new AbortController();
  const during = fixture([() => {
    inFlight.abort();
    throw Object.assign(new Error("synthetic private abort detail"), { name: "APIUserAbortError" });
  }]);
  await assert.rejects(during.router.generateEvaluation({ ...during.request, signal: inFlight.signal }), ProviderTimeoutError);
  assert.deepEqual(during.events, ["authorized", "dispatch"]);
});
