import assert from "node:assert/strict";
import test from "node:test";
import { sanitizedActualModelId, sanitizedProviderDispatchDiagnostic } from "../src/services/provider_execution_metadata.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { OpenRouterProvider } from "../src/services/openrouter_provider.js";
import { OPENROUTER_EVALUATION_PROFILES } from "../src/services/provider_route_policy.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

test("dispatch diagnostics retain only integer HTTP status and bounded gateway classification", () => {
  for (const [status, classification] of [
    [400, "BAD_REQUEST"], [401, "UNAUTHORIZED"], [403, "FORBIDDEN"], [404, "NOT_FOUND"],
    [422, "UNPROCESSABLE"], [429, "RATE_LIMITED"], [418, "OTHER_4XX"], [499, "OTHER_4XX"],
    [500, "UPSTREAM_5XX"], [503, "UPSTREAM_5XX"], [599, "UPSTREAM_5XX"]
  ]) {
    const error = Object.assign(new Error("private provider message"), {
      status, name: "APIConnectionError", body: "private response", headers: { authorization: "private credential" },
      request: "private prompt", id: "private opaque id"
    });
    const diagnostic = sanitizedProviderDispatchDiagnostic(error);
    assert.deepEqual(diagnostic, { upstreamHttpStatus: status, classification });
    assert.equal(Object.isFrozen(diagnostic), true);
    assert.equal(JSON.stringify(diagnostic).includes("private"), false);
  }
  for (const status of [undefined, null, "400", 399, 600, 400.5, NaN, Infinity, {}, true]) {
    assert.deepEqual(sanitizedProviderDispatchDiagnostic({ status, message: "private body" }),
      { upstreamHttpStatus: null, classification: "UNKNOWN" });
  }
});

test("connection and timeout diagnostics never retain error names, codes or raw detail", () => {
  for (const [marker, classification] of [
    [{ name: "APIConnectionError" }, "CONNECTION"], [{ code: "ECONNRESET" }, "CONNECTION"],
    [{ name: "APIConnectionTimeoutError" }, "TIMEOUT"], [{ code: "ETIMEDOUT" }, "TIMEOUT"],
    [{ code: "ECONNABORTED" }, "TIMEOUT"], [{ name: "AbortError" }, "TIMEOUT"],
    [{ name: "APIUserAbortError" }, "TIMEOUT"], [{ code: "private unknown code" }, "UNKNOWN"]
  ]) {
    assert.deepEqual(sanitizedProviderDispatchDiagnostic({ ...marker, message: "private response" }),
      { upstreamHttpStatus: null, classification });
  }
});

test("served model sanitation allows bounded IDs and rejects arbitrary/private material", () => {
  for (const model of ["nex-agi/nex-n2.5-pro-served", "GLM-5.2", "accounts/fireworks/models/configured-control"]) {
    assert.equal(sanitizedActualModelId({ model }), model);
  }
  for (const model of [undefined, null, "", [], "private health profile text", "model\nprivate", "x".repeat(201),
    "sk-test-secret", "vendor/sk-test-secret", "acct_v1_synthetic", "idem_v1_synthetic"]) {
    assert.equal(sanitizedActualModelId({ model }), null);
  }
});

test("repair retains per-attempt served IDs separately from configured alias and observer stays non-authoritative", async () => {
  const value = getSyntheticCoachCase("coach-basic");
  const profile = OPENROUTER_EVALUATION_PROFILES[0];
  const responses = [
    { model: "nex-agi/first-served", choices: [{ message: { content: "not-json" } }] },
    { model: "nex-agi/second-served", choices: [{ message: { content: JSON.stringify(value.expectedResponse) } }] }
  ];
  const events = [];
  const router = new ProviderRouter({ openrouterProvider: new OpenRouterProvider({
    config: { openrouterApiKey: "configured-for-injected-mock", openrouterEvaluationTimeoutMs: 100 }, evaluationEnabled: true,
    async fetchImpl() { events.push("dispatch"); return Response.json(responses.shift()); }
  }) });
  const result = await router.generateEvaluation({ input: value.input, grounding: value.grounding,
    now: () => 0, // Metadata assertions do not depend on host scheduling or cold Response initialization.
    routeProfileId: profile.routeProfileId, dataClassification: "SYNTHETIC_ONLY",
    beforeProviderAttempt: async () => { events.push("authorized"); },
    onExecutionMetadata() { throw new Error("private observer detail"); }
  });
  assert.deepEqual(events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.equal(result.execution.configuredModelId, profile.modelId);
  assert.equal(result.execution.actualModelId, "nex-agi/second-served");
  assert.deepEqual(result.execution.attempts.map((attempt) => attempt.actualModelId), ["nex-agi/first-served", "nex-agi/second-served"]);
  assert.equal(Object.hasOwn(result.data, "actualModelId"), false);
  assert.equal(result.execution.contractDiagnostic, null);
  assert.deepEqual(result.execution.attempts.map((attempt) => attempt.contractDiagnostic), ["JSON_PARSE_FAILED", null]);
});
