import assert from "node:assert/strict";
import test, { after } from "node:test";
import { OpenRouterProvider } from "../src/services/openrouter_provider.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { SharedAttemptExecutor } from "../src/services/shared_attempt_executor.js";
import { OPENROUTER_EVALUATION_PROFILES } from "../src/services/provider_route_policy.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";
import { prepareCoachOperation } from "../src/services/coach_provider_operation.js";
import {
  ProviderConfigurationError, ProviderDisabledError, ProviderInvalidResponseError,
  ProviderRateLimitError, ProviderTimeoutError, ProviderUnauthorizedError,
  ProviderUnavailableError, RemoteAccountQuotaExhaustedError, RemoteAdmissionUnavailableError
} from "../src/errors.js";

const profile = OPENROUTER_EVALUATION_PROFILES.find((value) => value.routeProfileId === "eval-openrouter-glm-5-2-free");
const value = getSyntheticCoachCase("coach-basic");
const completion = (content = JSON.stringify(value.expectedResponse), usage) => ({
  provider: "DeepInfra", choices: [{ message: { content } }], usage
});
const transient = () => Object.assign(new Error("private upstream body"), { status: 503 });
const originalFetch = globalThis.fetch;
globalThis.fetch = () => assert.fail("real fetch is forbidden in OpenRouter tests");
after(() => { globalThis.fetch = originalFetch; });

function fixture(sequence, { config = {}, evaluationEnabled = true } = {}) {
  const events = [];
  const calls = [];
  let time = 0;
  let metadata;
  const fetchImpl = async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body), options, time }); events.push("dispatch");
    let next = sequence.shift();
    if (typeof next === "function") next = await next(options);
    if (next instanceof Response || next?.json) return next;
    if (next instanceof Error) {
      if (next.status) return new Response("private provider failure body", { status: next.status, headers: next.headers });
      throw next;
    }
    return Response.json(next);
  };
  const adapter = new OpenRouterProvider({
    config: { openrouterApiKey: "configured-for-injected-mock", openrouterEvaluationTimeoutMs: 100,
      fireworksTimeoutMs: 9000, fireworksMaxOutputTokens: 512, ...config }, evaluationEnabled,
    fetchImpl
  });
  const router = new ProviderRouter({ openrouterProvider: adapter });
  return {
    adapter, router, calls, events, advance: (number) => { time = number; },
    get metadata() { return metadata; },
    request: {
      routeProfileId: profile.routeProfileId, dataClassification: "SYNTHETIC_ONLY",
      input: value.input, grounding: value.grounding, now: () => time,
      beforeProviderAttempt: async () => { events.push("authorized"); },
      onExecutionMetadata: (result) => { metadata = result; }
    }
  };
}

test("OpenRouter performs one authorized invocation with exact server-owned routing for each profile", async () => {
  for (const candidate of OPENROUTER_EVALUATION_PROFILES) {
    const current = fixture([completion()], { config: { openrouterBaseUrl: "https://untrusted.invalid" } });
    // Close the inspection instance; normal execution owns its own single instance.
    const inspection = current.adapter.createAttemptExecutor(current.request, candidate);
    assert.ok(inspection instanceof SharedAttemptExecutor);
    inspection.close();
    const result = await current.router.generateEvaluation({ ...current.request, routeProfileId: candidate.routeProfileId });
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.equal(current.calls.length, 1);
    assert.equal(current.calls[0].url, "https://openrouter.ai/api/v1/chat/completions");
    assert.equal(current.calls[0].options.method, "POST");
    assert.equal(current.calls[0].options.headers.Authorization, "Bearer configured-for-injected-mock");
    assert.equal(current.calls[0].options.headers["Content-Type"], "application/json");
    assert.equal(current.calls[0].options.redirect, "manual");
    assert.ok(current.calls[0].options.signal instanceof AbortSignal);
    assert.equal(Object.hasOwn(current.calls[0].options, "timeout"), false);
    assert.equal(Object.hasOwn(current.calls[0].options, "maxRetries"), false);
    assert.equal(current.calls[0].body.model, candidate.modelId);
    assert.deepEqual(current.calls[0].body, prepareCoachOperation(current.request, {
      modelId: candidate.modelId, structuredOutputMode: candidate.structuredOutputMode,
      maxOutputTokens: 4096, providerRouting: current.calls[0].body.provider
    }).initial);
    assert.deepEqual(current.calls[0].body.provider, candidate.routingMode === "STRICT_PRIVACY"
      ? { allow_fallbacks: false, require_parameters: true, data_collection: "deny", zdr: true }
      : { allow_fallbacks: false, require_parameters: true });
    for (const field of ["models", "plugins", "tools", "tool_choice"]) {
      assert.equal(Object.hasOwn(current.calls[0].body, field), false);
    }
    if (candidate.structuredOutputMode === "JSON_SCHEMA") {
      assert.equal(current.calls[0].body.response_format.json_schema.strict, true);
      assert.equal(current.calls[0].body.response_format.json_schema.schema.additionalProperties, false);
    } else {
      assert.equal(Object.hasOwn(current.calls[0].body, "response_format"), false);
      assert.ok(current.calls[0].body.messages[0].content.includes('"additionalProperties":false'));
    }
    assert.equal(result.execution.actualProvider, "deepinfra");
    assert.equal(result.execution.routePolicyVersion, "stage12f3b6-v1");
    assert.equal(result.execution.failureDiagnostic, null);
    assert.equal(result.execution.attempts[0].failureDiagnostic, null);
    assert.equal(current.adapter.requestCompletion, undefined);
    assert.equal(current.adapter.providerClient, undefined);
  }
});

test("JSON_SCHEMA and PROMPT_JSON material both use strict local validation", () => {
  for (const mode of ["JSON_SCHEMA", "PROMPT_JSON"]) {
    const operation = prepareCoachOperation({ input: value.input, grounding: value.grounding }, { modelId: profile.modelId, structuredOutputMode: mode });
    assert.equal(Object.hasOwn(operation.initial, "response_format"), mode === "JSON_SCHEMA");
    if (mode === "JSON_SCHEMA") assert.equal(operation.initial.response_format.json_schema.strict, true);
    assert.throws(() => operation.result(completion("{}"), "{}"), ProviderInvalidResponseError);
  }
});

test("private dispatch seam rejects model/tools/plugins overrides and enforces routing controls", async () => {
  for (const extra of [{ model: "openrouter/free" }, { models: [profile.modelId] }, { tools: [] }, { plugins: [] }]) {
    const current = fixture([completion()]);
    const executor = current.adapter.createAttemptExecutor(current.request, profile);
    const operation = current.adapter.prepareCoach(current.request, profile);
    try {
      await assert.rejects(executor.execute({ ...operation.initial, ...extra }));
      assert.equal(current.calls.length, 0);
    } finally { executor.close(); }
  }
  const current = fixture([completion()]);
  const executor = current.adapter.createAttemptExecutor(current.request, profile);
  const operation = current.adapter.prepareCoach(current.request, profile);
  try {
    await executor.execute({ ...operation.initial, provider: { allow_fallbacks: true, data_collection: "allow", zdr: false } });
    assert.deepEqual(current.calls[0].body.provider, { allow_fallbacks: false, require_parameters: true });
  } finally { executor.close(); }
});

test("routing objects are immutable and free-tier routing makes no request-level privacy claim", async () => {
  const { STRICT_PROVIDER_ROUTING, SYNTHETIC_FREE_TIER_ROUTING } = await import("../src/services/openrouter_provider.js");
  assert.deepEqual(STRICT_PROVIDER_ROUTING, { allow_fallbacks: false, require_parameters: true, data_collection: "deny", zdr: true });
  assert.deepEqual(SYNTHETIC_FREE_TIER_ROUTING, { allow_fallbacks: false, require_parameters: true });
  for (const controls of [STRICT_PROVIDER_ROUTING, SYNTHETIC_FREE_TIER_ROUTING]) {
    assert.equal(Object.isFrozen(controls), true);
    assert.throws(() => { controls.allow_fallbacks = true; }, TypeError);
  }
  assert.equal(Object.hasOwn(SYNTHETIC_FREE_TIER_ROUTING, "zdr"), false);
  assert.equal(Object.hasOwn(SYNTHETIC_FREE_TIER_ROUTING, "data_collection"), false);
});

test("caller routing/privacy settings and unknown modes fail before authorization/dispatch", async () => {
  for (const routing of [{ routingMode: "UNKNOWN" }, { routingMode: "SYNTHETIC_FREE_TIER" },
    { providerRouting: { zdr: false } }, { privacyClassification: "USER_DATA" },
    { privacySettings: {} }, { zdr: false }, { data_collection: "allow" }]) {
    const current = fixture([completion()]);
    await assert.rejects(current.router.generateEvaluation({ ...current.request, ...routing }), ProviderConfigurationError);
    assert.deepEqual(current.events, []);
    assert.deepEqual(current.calls, []);
  }
  const current = fixture([completion()]);
  for (const routingMode of ["UNKNOWN", "STRICT_PRIVACY", "SYNTHETIC_FREE_TIER"]) {
    assert.throws(() => current.adapter.createAttemptExecutor(current.request, { ...profile, routingMode }), ProviderConfigurationError);
  }
});

test("strict Nex profile cannot be downgraded by caller request, forged profile or dispatch body", async () => {
  const nex = OPENROUTER_EVALUATION_PROFILES.find((candidate) => candidate.routeProfileId === "eval-openrouter-nex-n2-5-pro-free");
  const current = fixture([completion()]);
  await assert.rejects(current.router.generateEvaluation({ ...current.request, routeProfileId: nex.routeProfileId,
    routingMode: "SYNTHETIC_FREE_TIER" }), ProviderConfigurationError);
  assert.throws(() => current.adapter.prepareCoach(current.request, { ...nex, routingMode: "SYNTHETIC_FREE_TIER" }), ProviderConfigurationError);
  assert.throws(() => { nex.routingMode = "SYNTHETIC_FREE_TIER"; }, TypeError);
  const executor = current.adapter.createAttemptExecutor(current.request, nex);
  const operation = current.adapter.prepareCoach(current.request, nex);
  try {
    await executor.execute({ ...operation.initial, provider: { allow_fallbacks: true, require_parameters: false } });
    assert.deepEqual(current.calls[0].body.provider, { allow_fallbacks: false, require_parameters: true, data_collection: "deny", zdr: true });
  } finally { executor.close(); }
});

test("key alone never enables evaluation; missing key and forged profiles never dispatch", async () => {
  for (const [options, error] of [
    [{ evaluationEnabled: false }, ProviderDisabledError],
    [{ config: { openrouterApiKey: "" } }, ProviderConfigurationError]
  ]) {
    const current = fixture([completion()], options);
    await assert.rejects(current.router.generateEvaluation(current.request), error);
    assert.deepEqual(current.calls, []);
  }
  const current = fixture([completion()]);
  assert.throws(() => current.adapter.prepareCoach(current.request, { ...profile }), ProviderConfigurationError);
  await assert.rejects(current.router.generateStructured({ ...current.request }), ProviderConfigurationError);
  assert.deepEqual(current.calls, []);
});

test("missing and failed attempt authorization prevent OpenRouter network invocation", async () => {
  const current = fixture([completion()]);
  await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: undefined }), RemoteAdmissionUnavailableError);
  const failure = new RemoteAccountQuotaExhaustedError();
  await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => { throw failure; } }), (error) => error === failure);
  assert.deepEqual(current.calls, []);
});

test("second authorization failure prevents a repair dispatch", async () => {
  const current = fixture([completion("not-json"), completion()]);
  let count = 0;
  await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => {
    if (++count === 2) throw new RemoteAccountQuotaExhaustedError();
    current.events.push("authorized");
  } }), RemoteAccountQuotaExhaustedError);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
  assert.equal(current.metadata.attemptsUsed, 1);
});

test("OpenRouter 401, 403 and 429 are terminal and client-safe", async (t) => {
  for (const status of [401, 403, 429]) {
    await t.test(String(status), async () => {
      const failure = Object.assign(new Error("private provider response credential detail"), { status, headers: { "retry-after": "17" } });
      const current = fixture([failure, completion()]);
      await assert.rejects(current.router.generateEvaluation(current.request), status === 429 ? ProviderRateLimitError : ProviderUnauthorizedError);
      assert.deepEqual(current.events, ["authorized", "dispatch"]);
      assert.equal(JSON.stringify(current.metadata).includes("private"), false);
      assert.equal(current.metadata.attempts[0].usage.totalTokens, null);
    });
  }
});

test("gateway diagnostics preserve public errors and existing retry counts without raw details", async (t) => {
  for (const [status, classification, ErrorClass, dispatches] of [
    [400, "BAD_REQUEST", ProviderUnavailableError, 1],
    [404, "NOT_FOUND", ProviderUnavailableError, 1],
    [422, "UNPROCESSABLE", ProviderUnavailableError, 1],
    [401, "UNAUTHORIZED", ProviderUnauthorizedError, 1],
    [403, "FORBIDDEN", ProviderUnauthorizedError, 1],
    [429, "RATE_LIMITED", ProviderRateLimitError, 1],
    [418, "OTHER_4XX", ProviderUnavailableError, 1],
    [503, "UPSTREAM_5XX", ProviderUnavailableError, 2]
  ]) {
    await t.test(String(status), async () => {
      const failure = Object.assign(new Error("private gateway message"), {
        status, body: "private provider body", headers: { "retry-after": "17", authorization: "private credential" },
        request: "private prompt", id: "private opaque id"
      });
      const current = fixture([failure, failure, completion()]);
      const originalMapError = current.adapter.mapError.bind(current.adapter);
      current.adapter.mapError = (error) => {
        assert.equal(error.status, status);
        assert.notEqual(error, failure);
        assert.equal(Object.hasOwn(error, "body"), false);
        assert.equal(Object.hasOwn(error, "cause"), false);
        assert.equal(error.message.includes("private"), false);
        assert.deepEqual(Object.keys(error.headers ?? {}), status === 429 ? ["retry-after"] : []);
        // Mapping remains authoritative for public errors, independent of diagnostics.
        return originalMapError(error);
      };
      await assert.rejects(current.router.generateEvaluation(current.request), (error) => {
        assert.ok(error instanceof ErrorClass);
        assert.equal(Object.hasOwn(error, "failureDiagnostic"), false);
        assert.equal(Object.hasOwn(error, "upstreamHttpStatus"), false);
        if (status === 429) assert.equal(error.headers["retry-after"], "17");
        assert.equal(JSON.stringify(error).includes("private"), false);
        return true;
      });
      assert.deepEqual(current.events, Array.from({ length: dispatches }, () => ["authorized", "dispatch"]).flat());
      assert.equal(current.calls.length, dispatches);
      assert.equal(current.metadata.failureClassification, originalMapError(failure).code);
      assert.deepEqual(current.metadata.failureDiagnostic, { upstreamHttpStatus: status, classification });
      assert.ok(current.metadata.attempts.every((attempt) =>
        attempt.failureDiagnostic.upstreamHttpStatus === status && attempt.failureDiagnostic.classification === classification));
      assert.equal(JSON.stringify(current.metadata).includes("private"), false);
    });
  }
});

test("connection and early timeout diagnostics keep the authorized two-dispatch retry policy", async () => {
  for (const [marker, classification, ErrorClass] of [
    [{ name: "APIConnectionError" }, "CONNECTION", ProviderUnavailableError],
    [{ code: "ETIMEDOUT" }, "TIMEOUT", ProviderTimeoutError]
  ]) {
    const failure = Object.assign(new Error("private upstream detail"), marker);
    const current = fixture([failure, failure, completion()]);
    await assert.rejects(current.router.generateEvaluation(current.request), ErrorClass);
    assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
    assert.deepEqual(current.metadata.failureDiagnostic, { upstreamHttpStatus: null, classification });
    assert.equal(JSON.stringify(current.metadata).includes("private"), false);
  }
});

test("successful retry clears final failure diagnostics but retains sanitized attempt history", async () => {
  const current = fixture([transient(), completion()]);
  const result = await current.router.generateEvaluation(current.request);
  assert.equal(result.execution.failureDiagnostic, null);
  assert.equal(current.metadata.failureDiagnostic, null);
  assert.deepEqual(result.execution.attempts[0].failureDiagnostic,
    { upstreamHttpStatus: 503, classification: "UPSTREAM_5XX" });
  assert.equal(result.execution.attempts[1].failureDiagnostic, null);
});

test("native TypeError connection failures retry twice at most without retaining raw causes", async () => {
  const failure = Object.assign(new TypeError("private fetch failure"), {
    cause: { code: "ECONNREFUSED", message: "private credential and endpoint" }
  });
  const current = fixture([failure, failure, completion()]);
  await assert.rejects(current.router.generateEvaluation(current.request), ProviderUnavailableError);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.deepEqual(current.metadata.failureDiagnostic, { upstreamHttpStatus: null, classification: "CONNECTION" });
  assert.equal(JSON.stringify(current.metadata).includes("private"), false);
});

test("native connection timeouts preserve early-timeout mapping and the two-dispatch ceiling", async () => {
  for (const code of ["UND_ERR_CONNECT_TIMEOUT", "ETIMEDOUT", "ECONNABORTED"]) {
    const failure = Object.assign(new TypeError("private fetch timeout"), { cause: { code, message: "private timeout detail" } });
    const current = fixture([failure, failure, completion()]);
    await assert.rejects(current.router.generateEvaluation(current.request), ProviderTimeoutError);
    assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
    assert.deepEqual(current.metadata.failureDiagnostic, { upstreamHttpStatus: null, classification: "TIMEOUT" });
    assert.equal(JSON.stringify(current.metadata).includes("private"), false);
  }
});

test("unknown native failures stay terminal and never retain private error details", async () => {
  const current = fixture([new Error("private unknown transport detail"), completion()]);
  await assert.rejects(current.router.generateEvaluation(current.request), ProviderUnavailableError);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
  assert.deepEqual(current.metadata.failureDiagnostic, { upstreamHttpStatus: null, classification: "UNKNOWN" });
  assert.equal(JSON.stringify(current.metadata).includes("private"), false);
});

test("native abort is terminal, including caller cancellation during fetch", async () => {
  for (const cancelCaller of [false, true]) {
    const controller = new AbortController();
    const current = fixture([() => {
      if (cancelCaller) controller.abort();
      throw new DOMException("private abort detail", "AbortError");
    }, completion()]);
    await assert.rejects(current.router.generateEvaluation({ ...current.request, signal: controller.signal }), ProviderTimeoutError);
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.equal(current.calls[0].options.signal.aborted, true);
    assert.equal(JSON.stringify(current.metadata).includes("private"), false);
    if (!cancelCaller) assert.equal(current.metadata.failureDiagnostic.classification, "TIMEOUT");
  }
});

test("operation deadline aborts pending fetch and response parsing without another authorization", async () => {
  for (const duringJson of [false, true]) {
    const pending = (signal) => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => reject(new DOMException("private deadline detail", "AbortError")), { once: true });
    });
    const current = fixture([(options) => duringJson
      ? { ok: true, json: () => pending(options.signal) }
      : pending(options.signal)], { config: { openrouterEvaluationTimeoutMs: 30 } });
    await assert.rejects(current.router.generateEvaluation(current.request), ProviderTimeoutError);
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.equal(current.calls[0].options.signal.aborted, true);
  }
});

test("malformed successful responses fail closed without repair or leaking raw text", async () => {
  for (const [response, diagnostic] of [
    [new Response("private non-JSON body"), { upstreamHttpStatus: null, classification: "UNKNOWN" }],
    [Response.json(null), null], [Response.json({}), null]
  ]) {
    const current = fixture([response, completion()]);
    await assert.rejects(current.router.generateEvaluation(current.request), ProviderInvalidResponseError);
    assert.deepEqual(current.events, ["authorized", "dispatch"]);
    assert.deepEqual(current.metadata.failureDiagnostic, diagnostic);
    assert.equal(JSON.stringify(current.metadata).includes("private"), false);
  }
});

test("429 retry-after is bounded and HTTP failure bodies are cancelled without reading", async () => {
  for (const [header, expected] of [["17", "17"], ["999999", "3600"], ["0", "30"],
    ["-1", "30"], ["private header text", "30"], [null, "30"]]) {
    let cancelled = false;
    const current = fixture([{ ok: false, status: 429,
      headers: { get(name) { assert.equal(name, "retry-after"); return header; } },
      body: { async cancel() { cancelled = true; } },
      json() { assert.fail("failure body must never be parsed"); },
      text() { assert.fail("failure body must never be read"); }
    }, completion()]);
    await assert.rejects(current.router.generateEvaluation(current.request), (error) => {
      assert.ok(error instanceof ProviderRateLimitError);
      assert.equal(error.headers["retry-after"], expected);
      assert.equal(JSON.stringify(error).includes("private"), false);
      return true;
    });
    assert.equal(cancelled, true);
    assert.equal(current.calls.length, 1);
    assert.equal(current.metadata.failureDiagnostic.classification, "RATE_LIMITED");
  }
});

test("redirect responses never follow another endpoint or trigger a retry", async () => {
  const current = fixture([new Response("private redirect body", {
    status: 307, headers: { location: "https://untrusted.invalid" }
  }), completion()]);
  await assert.rejects(current.router.generateEvaluation(current.request), ProviderUnavailableError);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
  assert.equal(current.metadata.failureDiagnostic.classification, "UNKNOWN");
});

test("native success preserves completion metadata, content and normalized usage", async () => {
  const response = { ...completion(undefined, { prompt_tokens: 4, completion_tokens: 6, total_tokens: 10 }),
    model: profile.modelId, provider: "NVIDIA" };
  const current = fixture([Response.json(response)]);
  const result = await current.router.generateEvaluation(current.request);
  assert.deepEqual(result.data, value.expectedResponse);
  assert.equal(result.execution.actualModelId, profile.modelId);
  assert.equal(result.execution.actualProvider, "nvidia");
  assert.deepEqual(result.usage, { promptTokens: 4, completionTokens: 6, totalTokens: 10 });
  assert.equal(JSON.stringify(result).includes("configured-for-injected-mock"), false);
});

test("repair/transient combinations and repeated transient errors cannot exceed two dispatches", async (t) => {
  for (const [sequence, error] of [
    [[transient(), transient(), completion()], ProviderUnavailableError],
    [[transient(), completion("not-json"), completion()], ProviderInvalidResponseError],
    [[completion("not-json"), transient(), completion()], ProviderUnavailableError]
  ]) {
    await t.test(error.name, async () => {
      const current = fixture(sequence);
      await assert.rejects(current.router.generateEvaluation(current.request), error);
      assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
      assert.equal(current.metadata.attemptsUsed, 2);
    });
  }
});

test("OpenRouter invocations share the executor signal and do not create per-attempt deadlines", async () => {
  const current = fixture([() => { current.advance(65); return transient(); }, completion()]);
  await current.router.generateEvaluation(current.request);
  assert.deepEqual(current.calls.map(({ time }) => time), [0, 65]);
  assert.equal(current.calls[0].options.signal, current.calls[1].options.signal);
  assert.ok(current.calls.every(({ options }) => !Object.hasOwn(options, "timeout")));
});

test("shared deadline exhausted during second authorization blocks the second fetch invocation", async () => {
  const current = fixture([transient(), completion()]);
  let authorizations = 0;
  await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => {
    current.events.push("authorized");
    if (++authorizations === 2) current.advance(100);
  } }), ProviderTimeoutError);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized"]);
  assert.equal(current.calls.length, 1);
});

test("OpenRouter operation deadline and output limit use only dedicated evaluation settings", async () => {
  for (const fireworks of [
    { fireworksTimeoutMs: 1000, fireworksMaxOutputTokens: 256 },
    { fireworksTimeoutMs: 9000, fireworksMaxOutputTokens: 1800 }
  ]) {
    const current = fixture([completion()], { config: { ...fireworks,
      openrouterEvaluationTimeoutMs: 75, openrouterEvaluationMaxOutputTokens: 3072 } });
    await current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => {
      current.events.push("authorized"); current.advance(74);
    } });
    assert.equal(current.calls.length, 1);
    assert.equal(current.calls[0].body.max_tokens, 3072);
    const expired = fixture([completion()], { config: { ...fireworks,
      openrouterEvaluationTimeoutMs: 75, openrouterEvaluationMaxOutputTokens: 3072 } });
    await assert.rejects(expired.router.generateEvaluation({ ...expired.request, beforeProviderAttempt: async () => {
      expired.events.push("authorized"); expired.advance(75);
    } }), ProviderTimeoutError);
    assert.equal(expired.calls.length, 0);
  }
});

test("expired authorization and caller cancellation cause no OpenRouter dispatch", async () => {
  const current = fixture([completion()]);
  await assert.rejects(current.router.generateEvaluation({ ...current.request, beforeProviderAttempt: async () => { current.advance(100); } }), ProviderTimeoutError);
  assert.equal(current.calls.length, 0);
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(current.router.generateEvaluation({ ...current.request, signal: controller.signal }), ProviderTimeoutError);
  assert.equal(current.calls.length, 0);
});

test("repair preserves first-attempt usage and reports server-derived metadata", async () => {
  const initialUsage = { prompt_tokens: 20, completion_tokens: 10, total_tokens: 30,
    prompt_tokens_details: { cached_tokens: 3 }, completion_tokens_details: { reasoning_tokens: 4 } };
  const secondUsage = { prompt_tokens: 25, completion_tokens: 15, total_tokens: 40,
    prompt_tokens_details: { cached_tokens: 2 }, completion_tokens_details: { reasoning_tokens: 6 } };
  const current = fixture([completion("not-json", initialUsage), completion(undefined, secondUsage)]);
  const result = await current.router.generateEvaluation(current.request);
  assert.deepEqual(result.execution.attempts.map(({ attemptClassification, validationOutcome }) => [attemptClassification, validationOutcome]),
    [["initial", "INVALID_RESPONSE"], ["repair", "VALIDATED"]]);
  assert.deepEqual(result.execution.aggregateUsage, { promptTokens: 45, completionTokens: 25, totalTokens: 70, cachedTokens: 5, reasoningTokens: 10 });
  assert.equal(result.execution.attempts[0].usage.totalTokens, 30);
  assert.equal(result.execution.routeProfileId, profile.routeProfileId);
  assert.equal(result.execution.configuredModelId, profile.modelId);
  assert.equal(result.execution.providerAlias, "openrouter");
  assert.equal(Object.hasOwn(result.data, "sourceType"), false);
  for (const text of [value.input.question, value.expectedResponse.summary, "not-json", "configured-for-injected-mock"]) {
    assert.equal(JSON.stringify(result.execution).includes(text), false);
  }
});

test("unknown usage and unsafe upstream labels remain null, including aggregate usage after retry", async () => {
  const current = fixture([transient(), { ...completion(), provider: "private profile arbitrary label", usage: {
    prompt_tokens: -1, completion_tokens: 2, total_tokens: 2, prompt_tokens_details: { cached_tokens: "0" }
  } }]);
  const result = await current.router.generateEvaluation(current.request);
  assert.equal(result.execution.actualProvider, null);
  assert.equal(result.execution.attempts[0].usage.totalTokens, null);
  assert.equal(result.execution.attempts[1].usage.promptTokens, null);
  assert.deepEqual(result.execution.aggregateUsage, { promptTokens: null, completionTokens: null, totalTokens: null, cachedTokens: null, reasoningTokens: null });
});
