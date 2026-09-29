import assert from "node:assert/strict";
import test from "node:test";
import { FireworksProvider } from "../src/services/fireworks_provider.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import {
  ProviderInvalidResponseError, ProviderTimeoutError, ProviderUnauthorizedError,
  ProviderRateLimitError, ProviderUnavailableError, RemoteAccountQuotaExhaustedError
} from "../src/errors.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

const data = {
  summary: "Keep training gradual.", recommendedAction: "Record one safe session.",
  nutritionNote: "Use measured portions.", workoutNote: "Use controlled technique.",
  safetyDisclaimer: "General education only.", escalationRequired: false,
  detectedIntent: "GENERAL_COACHING", detectedGoal: null,
  confidence: 0.8, warnings: [], generatedWorkoutPlan: null, generatedDietPlan: null
};
const completion = (content = JSON.stringify(data)) => ({ choices: [{ message: { content } }] });
const transient = () => Object.assign(new Error("injected upstream failure"), { status: 503 });

function fixture(sequence, overrides = {}) {
  const events = [];
  const calls = [];
  let time = 0;
  const adapter = new FireworksProvider({
    config: {
      provider: "fireworks", remoteAiEnabled: true,
      fireworksApiKey: "configured-for-injected-mock", fireworksModel: "test-model",
      fireworksTimeoutMs: 100
    },
    client: { chat: { completions: { async create(body, options) {
      events.push("dispatch"); calls.push({ body, options });
      const result = sequence.shift();
      if (typeof result === "function") return result();
      if (result instanceof Error) throw result;
      return result;
    } } } }
  });
  const router = new ProviderRouter({ fireworksProvider: adapter });
  return {
    adapter, router, events, calls, advance: (value) => { time = value; },
    request: {
      feature: "coach", question: "Help me train consistently",
      now: () => time,
      beforeProviderAttempt: async () => { events.push("authorized"); },
      ...overrides
    }
  };
}

test("router resolves only REMOTE_AI_COACH to Fireworks", () => {
  const current = fixture([]);
  assert.equal(current.router.resolve("REMOTE_AI_COACH"), current.adapter);
  assert.throws(() => current.router.resolve("AI_WORKOUT_GENERATION"), ProviderInvalidResponseError);
  assert.equal(current.adapter.requestCompletion, undefined);
  assert.equal(current.adapter.providerClient, undefined);
});

test("router success performs exactly one authorized dispatch", async () => {
  const current = fixture([completion()]);
  const result = await current.router.generateStructured(current.request);
  assert.equal(result.execution.providerAlias, "fireworks");
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
});

test("throwing metadata observer preserves success and observes a closed executor", async () => {
  const current = fixture([completion()]);
  let observed;
  let closedBeforeObservation = false;
  const result = await current.router.generateStructured({ ...current.request, onExecutionMetadata(metadata) {
    observed = metadata;
    closedBeforeObservation = current.calls[0].options.signal.aborted;
    throw new Error("private observer detail");
  } });
  assert.equal(observed.validationOutcome, "VALIDATED");
  assert.equal(closedBeforeObservation, true);
  assert.deepEqual(result.data, data);
  assert.equal(result.execution.validationOutcome, "VALIDATED");
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
});

test("throwing metadata observer preserves the original mapped provider error", async () => {
  const original = new ProviderUnauthorizedError();
  const current = fixture([Object.assign(new Error("private upstream detail"), { status: 401 })]);
  current.adapter.mapError = () => original;
  await assert.rejects(current.router.generateStructured({ ...current.request, onExecutionMetadata() {
    throw new Error("private observer detail");
  } }), (error) => error === original);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
  assert.equal(current.calls[0].options.signal.aborted, true);
});

test("throwing metadata observer preserves the original admission error", async () => {
  const original = new RemoteAccountQuotaExhaustedError();
  const current = fixture([completion()], { beforeProviderAttempt: async () => { throw original; } });
  await assert.rejects(current.router.generateStructured({ ...current.request, onExecutionMetadata() {
    throw new Error("private observer detail");
  } }), (error) => error === original);
  assert.equal(current.calls.length, 0);
});

test("rejected asynchronous metadata observation cannot change provider success", async () => {
  const current = fixture([completion()]);
  const result = await current.router.generateStructured({ ...current.request, async onExecutionMetadata() {
    throw new Error("private observer detail");
  } });
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(result.data, data);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
});

test("nonempty invalid content repairs once through the shared executor", async () => {
  const current = fixture([completion("not-json"), completion()]);
  await current.router.generateStructured(current.request);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.equal(current.calls[1].body.messages[2].content, "not-json");
});

test("trusted diet and workout diagnostics select bounded repair guidance", async (t) => {
  for (const [caseId, mutate, requiredRules] of [
    ["nutrition-null", (value) => { value.generatedDietPlan.meals[0].foods[0].calories = 10; },
      [/diet plan/i, /exact food record ID.*exact name/i, /authoritative calories\/macros/i, /nutritionSource/i,
        /calorieTarget.*null.*unavailable/i, /macroTargets.*null.*unavailable/i, /1200.*4500/i]],
    ["canonical-leading-zeroes", (value) => { value.generatedWorkoutPlan.generatedDays[0].exercises[0].exerciseId = "257"; },
      [/workout plan/i, /exact exercise ID.*exact name/i, /requested days/i, /equipment/i]]
  ]) {
    await t.test(caseId, async () => {
      const value = getSyntheticCoachCase(caseId);
      const invalid = structuredClone(value.expectedResponse);
      mutate(invalid);
      const current = fixture([completion(JSON.stringify(invalid)), completion(JSON.stringify(value.expectedResponse))], {
        question: value.input.question, input: value.input, grounding: value.grounding
      });
      const result = await current.router.generateStructured(current.request);
      const guidance = current.calls[1].body.messages.at(-1).content;
      for (const rule of requiredRules) assert.match(guidance, rule);
      assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
      assert.equal(result.execution.attemptsUsed, 2);
      assert.equal(current.calls[0].options.signal, current.calls[1].options.signal);
    });
  }
});

test("provider-supplied diagnostic text cannot select repair guidance", async () => {
  const forged = { ...data, contractDiagnostic: "DIET_PLAN_INVALID" };
  const current = fixture([completion(JSON.stringify(forged)), completion()]);
  await current.router.generateStructured(current.request);
  const guidance = current.calls[1].body.messages.at(-1).content;
  assert.match(guidance, /top-level contract/i);
  assert.doesNotMatch(guidance, /authoritative calories\/macros/i);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
});

test("transient retry is authorized separately and uses the original request", async () => {
  const current = fixture([transient(), completion()]);
  await current.router.generateStructured(current.request);
  assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
  assert.deepEqual(current.calls[1].body, current.calls[0].body);
});

test("repair then retry, retry then repair, repeated repair and repeated retry never stack a third call", async (t) => {
  for (const [name, sequence, error] of [
    ["repair then transient", [completion("not-json"), transient(), completion()], ProviderUnavailableError],
    ["retry then invalid", [transient(), completion("not-json"), completion()], ProviderInvalidResponseError],
    ["two invalid", [completion("not-json"), completion("not-json"), completion()], ProviderInvalidResponseError],
    ["two transient", [transient(), transient(), completion()], ProviderUnavailableError]
  ]) {
    await t.test(name, async () => {
      const current = fixture(sequence);
      await assert.rejects(current.router.generateStructured(current.request), error);
      assert.deepEqual(current.events, ["authorized", "dispatch", "authorized", "dispatch"]);
    });
  }
});

test("first and second authorization failures prevent the corresponding network call", async (t) => {
  for (const failAt of [1, 2]) {
    await t.test(`authorization ${failAt}`, async () => {
      const failure = new RemoteAccountQuotaExhaustedError();
      let count = 0;
      const current = fixture([completion("not-json"), completion()], {
        beforeProviderAttempt: async () => {
          if (++count === failAt) throw failure;
          current.events.push("authorized");
        }
      });
      await assert.rejects(current.router.generateStructured(current.request), (error) => error === failure);
      assert.equal(current.calls.length, failAt - 1);
      assert.deepEqual(current.events, failAt === 1 ? [] : ["authorized", "dispatch"]);
    });
  }
});

test("401, 403 and 429 never retry even when an SDK connection name is present", async (t) => {
  for (const status of [401, 403, 429]) {
    await t.test(String(status), async () => {
      const error = Object.assign(new Error("injected failure"), { status, name: "APIConnectionError" });
      const current = fixture([error, completion()]);
      await assert.rejects(current.router.generateStructured(current.request),
        status === 429 ? ProviderRateLimitError : ProviderUnauthorizedError);
      assert.deepEqual(current.events, ["authorized", "dispatch"]);
    });
  }
});

test("missing and empty content remain terminal without repair", async (t) => {
  for (const content of [undefined, null, ""]) {
    await t.test(String(content), async () => {
      const current = fixture([{ choices: [{ message: { content } }] }, completion()]);
      await assert.rejects(current.router.generateStructured(current.request), ProviderInvalidResponseError);
      assert.equal(current.calls.length, 1);
    });
  }
});

test("repair uses the remaining shared deadline at the SDK seam", async () => {
  const current = fixture([() => { current.advance(60); return completion("not-json"); }, completion()]);
  await current.router.generateStructured(current.request);
  assert.equal(current.calls[0].options.timeout, 100);
  assert.equal(current.calls[1].options.timeout, 40);
  assert.equal(current.calls[0].options.signal, current.calls[1].options.signal);
});

test("deadline exhaustion after a transient failure is terminal with no new authorization", async () => {
  const current = fixture([() => { current.advance(100); throw transient(); }, completion()]);
  await assert.rejects(current.router.generateStructured(current.request), ProviderTimeoutError);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
});

test("early provider timeout retries only while shared operation time remains", async () => {
  const earlyTimeout = Object.assign(new Error("injected early timeout"), { code: "ETIMEDOUT" });
  const current = fixture([() => { current.advance(25); throw earlyTimeout; }, completion()]);
  await current.router.generateStructured(current.request);
  assert.equal(current.calls.length, 2);
  assert.equal(current.calls[1].options.timeout, 75);
});

test("caller cancellation during inference aborts and never repairs or retries", async () => {
  const controller = new AbortController();
  const current = fixture([() => { controller.abort(); throw transient(); }, completion()], { signal: controller.signal });
  await assert.rejects(current.router.generateStructured(current.request), ProviderTimeoutError);
  assert.deepEqual(current.events, ["authorized", "dispatch"]);
  assert.equal(current.calls[0].options.signal.aborted, true);
});

test("SDK construction and per-call automatic retries remain zero; preparation precedes authorization", async () => {
  const events = [];
  let clientOptions;
  let callOptions;
  const adapter = new FireworksProvider({
    config: {
      provider: "fireworks", remoteAiEnabled: true,
      fireworksApiKey: "configured-for-injected-mock", fireworksModel: "test-model", fireworksTimeoutMs: 100
    },
    clientFactory: (options) => {
      events.push("prepared"); clientOptions = options;
      return { chat: { completions: { async create(_body, options) {
        events.push("dispatch"); callOptions = options; return completion();
      } } } };
    }
  });
  await new ProviderRouter({ fireworksProvider: adapter }).generateStructured({
    question: "Help me train", beforeProviderAttempt: async () => { events.push("authorized"); }
  });
  assert.deepEqual(events, ["prepared", "authorized", "dispatch"]);
  assert.equal(clientOptions.maxRetries, 0);
  assert.equal(callOptions.maxRetries, 0);
  assert.equal(clientOptions.baseURL, "https://api.fireworks.ai/inference/v1");
});
