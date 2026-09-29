import assert from "node:assert/strict";
import test from "node:test";
import { ProviderTimeoutError, ProviderUnavailableError, RemoteAdmissionUnavailableError } from "../src/errors.js";
import { SharedAttemptExecutor } from "../src/services/shared_attempt_executor.js";
import OpenAI from "openai";
import { FireworksProvider } from "../src/services/fireworks_provider.js";
import { FireworksEvaluationProvider } from "../src/services/fireworks_evaluation_provider.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

function fixture(overrides = {}) {
  const events = [];
  let time = 0;
  const executor = new SharedAttemptExecutor({
    operationBudgetMs: 100,
    now: () => time,
    beforeProviderAttempt: async () => { events.push("authorized"); },
    dispatch: async (material, options) => { events.push(material); return options.timeout; },
    ...overrides
  });
  return { executor, events, advance: (value) => { time = value; } };
}

test("all attempt classifications share two dispatch opportunities", async () => {
  const { executor, events } = fixture();
  try {
    await executor.execute("initial");
    await executor.execute("repair");
    await assert.rejects(executor.execute("future fallback"), ProviderUnavailableError);
    assert.deepEqual(events, ["authorized", "initial", "authorized", "repair"]);
  } finally { executor.close(); }
});

test("exhausted deadline prevents authorization and dispatch", async () => {
  const { executor, events, advance } = fixture();
  try {
    advance(100);
    await assert.rejects(executor.execute("initial"), ProviderTimeoutError);
    assert.deepEqual(events, []);
  } finally { executor.close(); }
});

test("deadline expiry during durable authorization preserves authorization without dispatch", async () => {
  let authorized = 0;
  let current;
  current = fixture({ beforeProviderAttempt: async () => { authorized += 1; current.advance(100); } });
  try {
    await assert.rejects(current.executor.execute("initial"), ProviderTimeoutError);
    assert.equal(authorized, 1);
    assert.deepEqual(current.events, []);
    await assert.rejects(current.executor.execute("retry"), ProviderTimeoutError);
    assert.equal(authorized, 1);
  } finally { current.executor.close(); }
});

test("second dispatch receives only remaining operation time including authorization time", async () => {
  let current;
  current = fixture({ beforeProviderAttempt: async () => { current.advance(70); } });
  try {
    assert.equal(await current.executor.execute("initial"), 30);
  } finally { current.executor.close(); }
  const second = fixture();
  try {
    assert.equal(await second.executor.execute("initial"), 100);
    second.advance(65);
    assert.equal(await second.executor.execute("retry"), 35);
  } finally { second.executor.close(); }
});

test("missing authorization fails closed at executor construction", () => {
  assert.throws(() => fixture({ beforeProviderAttempt: undefined }), RemoteAdmissionUnavailableError);
});

test("authorization failures propagate unchanged and never dispatch", async () => {
  const failure = new RemoteAdmissionUnavailableError();
  const { executor, events } = fixture({ beforeProviderAttempt: async () => { throw failure; } });
  try {
    await assert.rejects(executor.execute("initial"), (error) => error === failure);
    assert.deepEqual(events, []);
  } finally { executor.close(); }
});

test("caller cancellation before and during authorization is terminal without dispatch", async () => {
  for (const during of [false, true]) {
    const controller = new AbortController();
    if (!during) controller.abort();
    const { executor, events } = fixture({
      signal: controller.signal,
      beforeProviderAttempt: async () => { events.push("authorized"); controller.abort(); }
    });
    try {
      await assert.rejects(executor.execute("initial"), ProviderTimeoutError);
      assert.deepEqual(events, during ? ["authorized"] : []);
    } finally { executor.close(); }
  }
});

test("operation timeout aborts in-flight work and waits for its settlement", async () => {
  let settled = false;
  const { executor } = fixture({
    operationBudgetMs: 10,
    dispatch: (_material, { signal }) => new Promise((resolve) => {
      signal.addEventListener("abort", () => { settled = true; resolve("late result"); }, { once: true });
    })
  });
  try {
    await assert.rejects(executor.execute("initial"), ProviderTimeoutError);
    assert.equal(settled, true);
  } finally { executor.close(); }
});

test("fractional post-authorization remaining time is floored without extending the deadline", async () => {
  for (const elapsed of [1.27, 1.01, 1.99, 29999]) {
    let current;
    current = fixture({ operationBudgetMs: 30000,
      beforeProviderAttempt: async () => { current.events.push("authorized"); current.advance(elapsed); } });
    try {
      const timeout = await current.executor.execute("initial");
      assert.equal(timeout, Math.floor(30000 - elapsed));
      assert.equal(Number.isInteger(timeout), true);
      assert.ok(timeout > 0 && timeout <= 30000 - elapsed);
      assert.deepEqual(current.events, ["authorized", "initial"]);
    } finally { current.executor.close(); }
  }
});

test("sub-millisecond post-authorization budget is terminal without dispatch or another authorization", async () => {
  let current;
  current = fixture({ beforeProviderAttempt: async () => { current.events.push("authorized"); current.advance(99.27); } });
  try {
    await assert.rejects(current.executor.execute("initial"), ProviderTimeoutError);
    assert.equal(current.executor.dispatchCount, 0);
    assert.deepEqual(current.events, ["authorized"]);
    // Durable authorization stays charged; no dispatch/refund or fresh attempt follows.
    await assert.rejects(current.executor.execute("retry"), ProviderTimeoutError);
    assert.deepEqual(current.events, ["authorized"]);
  } finally { current.executor.close(); }
});

test("fractional initial and repair/retry budgets share the same absolute deadline and two-call ceiling", async () => {
  for (const classification of ["repair", "retry"]) {
    const current = fixture();
    try {
      current.advance(1.27);
      assert.equal(await current.executor.execute("initial"), 98);
      current.advance(65.41);
      assert.equal(await current.executor.execute(classification), 34);
      await assert.rejects(current.executor.execute("future fallback"), ProviderUnavailableError);
      assert.deepEqual(current.events, ["authorized", "initial", "authorized", classification]);
      assert.equal(current.executor.dispatchCount, 2);
    } finally { current.executor.close(); }
  }
});

test("production and evaluation Fireworks pass fractional clocks through the actual SDK with fake fetch only", async () => {
  const value = getSyntheticCoachCase("coach-basic");
  for (const evaluation of [false, true]) {
    let time = 0, fetchCalls = 0;
    const events = [], timeouts = [], signals = [];
    const budget = evaluation ? 30000 : 8000;
    const config = { provider: evaluation ? "mock" : "fireworks", remoteAiEnabled: !evaluation,
      fireworksApiKey: "INJECTED_FETCH_ONLY", fireworksModel: "configured-offline-model",
      fireworksTimeoutMs: 8000, fireworksEvaluationTimeoutMs: 30000 };
    const clientFactory = (options) => {
      assert.equal(options.maxRetries, 0);
      const client = new OpenAI({ ...options, fetch: async () => {
        events.push("fetch"); fetchCalls += 1;
        const content = fetchCalls === 1 ? "not-json" : JSON.stringify(value.expectedResponse);
        time += 20.14;
        return Response.json({ choices: [{ message: { content } }] });
      } });
      const create = client.chat.completions.create.bind(client.chat.completions);
      return { chat: { completions: { create(body, requestOptions) {
        assert.equal(Number.isInteger(requestOptions.timeout), true);
        assert.equal(requestOptions.timeout, Math.floor(budget - time));
        assert.ok(requestOptions.timeout > 0 && requestOptions.timeout <= budget - time);
        assert.equal(requestOptions.maxRetries, 0);
        timeouts.push(requestOptions.timeout); signals.push(requestOptions.signal);
        return create(body, requestOptions);
      } } } };
    };
    const adapter = evaluation ? new FireworksEvaluationProvider({ config, evaluationEnabled: true, clientFactory })
      : new FireworksProvider({ config, clientFactory });
    const router = new ProviderRouter(evaluation ? { fireworksEvaluationProvider: adapter } : { fireworksProvider: adapter });
    const request = { input: value.input, grounding: value.grounding, now: () => time,
      beforeProviderAttempt: async () => { events.push("authorized"); time += 1.27; } };
    const result = evaluation ? await router.generateEvaluation({ ...request,
      routeProfileId: "eval-fireworks-gpt-oss-120b", dataClassification: "SYNTHETIC_ONLY" })
      : await router.generateStructured(request);
    assert.deepEqual(events, ["authorized", "fetch", "authorized", "fetch"]);
    assert.equal(fetchCalls, 2);
    assert.equal(result.execution.attemptsUsed, 2);
    assert.deepEqual(timeouts, [budget - 2, budget - 23]);
    assert.equal(signals[0], signals[1]);
    assert.equal(result.execution.validationOutcome, "VALIDATED");
  }
});
