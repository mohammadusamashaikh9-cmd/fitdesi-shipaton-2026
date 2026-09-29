import assert from "node:assert/strict";
import { test } from "node:test";
import {
  ProviderUnavailableError,
  IdempotencyInvalidError,
  IdempotencyRequiredError,
  RemoteAccountQuotaExhaustedError,
  RemoteAdmissionPersistenceUnavailableError,
  RemoteAiConsentRequiredError,
  RemoteCapabilityUnavailableError,
  RemoteGlobalBudgetUnavailableError,
  RemoteRequestCompletedError
} from "../src/errors.js";
import { createAiCoachRoute } from "../src/routes/ai_coach.js";
import { SharedAttemptExecutor } from "../src/services/shared_attempt_executor.js";
import { validateAiCoachProviderResponse } from "../src/services/ai_coach_contract.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

const FIREBASE_PROJECT_ID = "fitdesi-test-project";
const FIREBASE_UID = "private-firebase-uid";
const IDEMPOTENCY_KEY = "opaque-client-key-123456";
const HMAC_SECRET = "server-only-test-secret-material-1234567890";
const CAPABILITY = "REMOTE_AI_COACH";
const TEST_POLICY = Object.freeze({
  capability: CAPABILITY,
  tier: "PLUS",
  policyVersion: "test-policy-v1",
  successfulUses: Object.freeze({ limit: 2, windowSeconds: 3600 }),
  providerAttempts: Object.freeze({ limit: 3, windowSeconds: 300 }),
  globalProviderAttempts: Object.freeze({ limit: 10, windowSeconds: 600 })
});

async function moduleUnderTest() {
  return import("../src/remote_admission/remote_execution_admission.js");
}

function fixture({ recordErrorAt = null, transitionError = null } = {}) {
  const calls = [];
  let attemptCalls = 0;
  const admissionAuthority = {
    async authorize(input) {
      calls.push(["authorize", input]);
      return {
        capability: CAPABILITY,
        accessBasis: "TIER",
        commercialTier: "PLUS"
      };
    }
  };
  const quotaPolicy = {
    getPolicy(input) {
      calls.push(["policy", input]);
      return TEST_POLICY;
    }
  };
  const store = {
    async reserve(input) {
      calls.push(["reserve", input]);
      return { state: "RESERVED" };
    },
    async recordProviderAttempt(input) {
      attemptCalls += 1;
      calls.push(["attempt", input]);
      if (attemptCalls === recordErrorAt) throw new RemoteAccountQuotaExhaustedError();
      return { state: "DISPATCHED" };
    },
    async transition(input) {
      calls.push(["transition", input]);
      if (transitionError) throw transitionError;
      return { state: input.toState };
    }
  };
  return { admissionAuthority, calls, quotaPolicy, store };
}

async function begin(
  fixtureValue,
  validatedRequest = { question: "Private request content" },
  idempotencyKey
) {
  const effectiveIdempotencyKey = arguments.length < 3 ? IDEMPOTENCY_KEY : idempotencyKey;
  const { RemoteExecutionAdmission } = await moduleUnderTest();
  const admission = new RemoteExecutionAdmission({
    admissionAuthority: fixtureValue.admissionAuthority,
    quotaPolicy: fixtureValue.quotaPolicy,
    store: fixtureValue.store,
    firebaseProjectId: FIREBASE_PROJECT_ID,
    hmacSecret: HMAC_SECRET
  });
  return admission.begin({
    firebaseUid: FIREBASE_UID,
    idempotencyKey: effectiveIdempotencyKey,
    capability: CAPABILITY,
    validatedRequest
  });
}

test("begin authorizes once, resolves tier policy, and creates one opaque reservation", async () => {
  const current = fixture();

  const session = await begin(current);

  assert.deepEqual(current.calls.map(([name]) => name), ["authorize", "policy", "reserve"]);
  assert.deepEqual(current.calls[0][1], {
    firebaseUid: FIREBASE_UID,
    capability: CAPABILITY
  });
  assert.deepEqual(current.calls[1][1], {
    capability: CAPABILITY,
    tier: "PLUS"
  });
  const reservation = current.calls[2][1];
  assert.match(reservation.accountKey, /^acct_v1_[A-Za-z0-9_-]{43}$/);
  assert.match(reservation.requestIdentity.requestKey, /^idem_v1_[A-Za-z0-9_-]{43}$/);
  assert.match(reservation.requestIdentity.fingerprint, /^req_v1_[A-Za-z0-9_-]{43}$/);
  assert.equal(reservation.policy, TEST_POLICY);
  assert.deepEqual(Object.keys(session), []);
  assert.equal(JSON.stringify(session), "{}");
  const durableInput = JSON.stringify(reservation);
  for (const privateValue of [
    FIREBASE_PROJECT_ID,
    FIREBASE_UID,
    IDEMPOTENCY_KEY,
    HMAC_SECRET,
    "Private request content"
  ]) {
    assert.equal(durableInput.includes(privateValue), false);
  }
});

test("session authorizes every provider attempt then settles success once", async () => {
  const current = fixture();
  const session = await begin(current);

  await session.beforeProviderAttempt();
  await session.beforeProviderAttempt();
  await session.succeed();

  assert.deepEqual(current.calls.map(([name]) => name), [
    "authorize", "policy", "reserve", "attempt", "attempt", "transition"
  ]);
  assert.equal(current.calls.at(-1)[1].toState, "SUCCEEDED");
});

test("first provider-attempt authorization failure settles pre-dispatch", async () => {
  const current = fixture({ recordErrorAt: 1 });
  const session = await begin(current);

  await assert.rejects(session.beforeProviderAttempt(), {
    code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED"
  });
  await session.fail();

  assert.equal(current.calls.at(-1)[1].toState, "FAILED_PRE_DISPATCH");
});

test("later provider-attempt authorization failure settles post-dispatch", async () => {
  const current = fixture({ recordErrorAt: 2 });
  const session = await begin(current);

  await session.beforeProviderAttempt();
  await assert.rejects(session.beforeProviderAttempt(), {
    code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED"
  });
  await session.fail();

  assert.equal(current.calls.at(-1)[1].toState, "FAILED_POST_DISPATCH");
});

test("shared executor retains durable attempt consumption when authorization outlasts deadline", async () => {
  const current = fixture();
  const session = await begin(current);
  let time = 0;
  let dispatches = 0;
  const executor = new SharedAttemptExecutor({
    operationBudgetMs: 100,
    now: () => time,
    beforeProviderAttempt: async () => {
      await session.beforeProviderAttempt();
      time = 100;
    },
    dispatch: async () => { dispatches += 1; }
  });
  try {
    await assert.rejects(executor.execute({}), { code: "PROVIDER_TIMEOUT" });
    await session.fail();
    assert.equal(dispatches, 0);
    assert.equal(current.calls.filter(([name]) => name === "attempt").length, 1);
    assert.equal(current.calls.at(-1)[1].toState, "FAILED_POST_DISPATCH");
  } finally { executor.close(); }
});

test("success settlement failure is surfaced without contradictory failure settlement", async () => {
  const settlementError = new RemoteAdmissionPersistenceUnavailableError();
  const current = fixture({ transitionError: settlementError });
  const session = await begin(current);
  await session.beforeProviderAttempt();

  await assert.rejects(session.succeed(), (error) => error === settlementError);

  assert.deepEqual(current.calls.filter(([name]) => name === "transition").map(([, input]) =>
    input.toState), ["SUCCEEDED"]);
});

test("missing or invalid idempotency fails before reservation", async () => {
  for (const idempotencyKey of [undefined, "too-short", "invalid key with spaces"]) {
    const current = fixture();
    await assert.rejects(begin(current, { question: "Safe question" }, idempotencyKey), (error) =>
      ["IDEMPOTENCY_REQUIRED", "IDEMPOTENCY_INVALID"].includes(error.code));
    assert.equal(current.calls.filter(([name]) => name === "reserve").length, 0);
  }
});

function coachResult() {
  return {
    data: {
      summary: "Grounded summary",
      recommendedAction: "Take one safe action.",
      nutritionNote: "Use measured portions.",
      workoutNote: "Progress gradually.",
      safetyDisclaimer: "General education only."
    },
    usage: { totalTokens: 50 }
  };
}

function validatedStructuredCoachResult() {
  const synthetic = getSyntheticCoachCase("canonical-leading-zeroes");
  return {
    data: validateAiCoachProviderResponse(synthetic.expectedResponse, {
      question: synthetic.input.question,
      input: synthetic.input,
      grounding: synthetic.grounding
    }),
    usage: { totalTokens: 50 }
  };
}

function routeFixture({
  providerMode = "fireworks",
  beginError = null,
  groundingError = null,
  guardError = null,
  providerError = null,
  beforeAttemptError = null,
  successError = null,
  failError = null,
  providerResult = coachResult()
} = {}) {
  const calls = [];
  const leaseFailures = [];
  const session = {
    async beforeProviderAttempt() {
      calls.push("beforeProviderAttempt");
      if (beforeAttemptError) throw beforeAttemptError;
    },
    async succeed() {
      calls.push("session.succeed");
      if (successError) throw successError;
    },
    async fail() {
      calls.push("session.fail");
      if (failError) throw failError;
    }
  };
  const remoteExecutionAdmission = {
    async begin(input) {
      calls.push(["begin", input]);
      if (beginError) throw beginError;
      return session;
    }
  };
  const provider = {
    assertEnabled() {
      calls.push("assertEnabled");
    },
    async generateStructured(input) {
      calls.push("generateStructured");
      await input.beforeProviderAttempt();
      calls.push("requestCompletion");
      if (providerError) throw providerError;
      return providerResult;
    }
  };
  const lease = {
    succeed() { calls.push("guard.succeed"); },
    fail(input) {
      calls.push("guard.fail");
      leaseFailures.push(input);
    },
    release() { calls.push("guard.release"); }
  };
  const guard = {
    acquire() {
      calls.push("guard.acquire");
      if (guardError) throw guardError;
      return lease;
    }
  };
  const groundingBuilder = async () => {
    calls.push("grounding");
    if (groundingError) throw groundingError;
    return {};
  };
  const route = createAiCoachRoute({
    config: { provider: providerMode },
    provider,
    guard,
    remoteExecutionAdmission,
    groundingBuilder
  });
  return { calls, leaseFailures, route };
}

function remoteContext() {
  return {
    firebaseUid: FIREBASE_UID,
    idempotencyKey: IDEMPOTENCY_KEY,
    ipAddress: "test-ip",
    installationId: "test-installation",
    telemetry: {}
  };
}

test("mock and deterministic safety Coach paths require no admission or idempotency", async () => {
  const mock = routeFixture({ providerMode: "mock" });
  const mockResult = await mock.route({ question: "How should I train?" }, { telemetry: {} });
  assert.equal(mockResult.mode, "mock");
  assert.equal(mock.calls.length, 0);

  const safety = routeFixture();
  const safetyResult = await safety.route(
    { question: "I have chest pain and feel faint" },
    { telemetry: {} }
  );
  assert.equal(safetyResult.mode, "mock");
  assert.equal(safety.calls.length, 0);
});

test("remote authorization, consent, quota, and idempotency failures never call provider", async () => {
  for (const beginError of [
    new RemoteCapabilityUnavailableError(),
    new RemoteAiConsentRequiredError(),
    new RemoteAccountQuotaExhaustedError(),
    new RemoteGlobalBudgetUnavailableError(),
    new IdempotencyRequiredError(),
    new IdempotencyInvalidError()
  ]) {
    const current = routeFixture({ beginError });
    await assert.rejects(current.route({ question: "How should I train?" }, remoteContext()),
      (error) => error === beginError);
    assert.equal(current.calls.includes("generateStructured"), false);
    assert.equal(current.calls.includes("requestCompletion"), false);
  }
});

test("remote Coach success authorizes attempt and settles SUCCEEDED", async () => {
  const current = routeFixture();

  const result = await current.route({ question: "How should I train?" }, remoteContext());

  assert.equal(result.mode, "fireworks");
  assert.deepEqual(current.calls.map((entry) => Array.isArray(entry) ? entry[0] : entry), [
    "assertEnabled", "begin", "grounding", "guard.acquire", "generateStructured",
    "beforeProviderAttempt", "requestCompletion", "guard.succeed", "session.succeed",
    "guard.release"
  ]);
});

test("remote Coach projection authority is checked before success accounting", async () => {
  const successful = routeFixture({ providerResult: validatedStructuredCoachResult() });
  await successful.route({ question: "How should I train?" }, remoteContext());

  assert.equal(successful.calls.filter((entry) => entry === "guard.succeed").length, 1);
  assert.equal(successful.calls.filter((entry) => entry === "session.succeed").length, 1);
  assert.equal(successful.calls.filter((entry) => entry === "guard.release").length, 1);
  assert.equal(successful.calls.includes("guard.fail"), false);
  assert.equal(successful.calls.includes("session.fail"), false);

  const invalidProjection = routeFixture({
    providerResult: {
      data: {
        ...coachResult().data,
        generatedWorkoutPlan: { unvalidated: true }
      },
      usage: { totalTokens: 50 }
    }
  });

  await assert.rejects(
    invalidProjection.route({ question: "How should I train?" }, remoteContext()),
    (error) => error?.code === "PROVIDER_INVALID_RESPONSE"
  );
  assert.equal(invalidProjection.calls.includes("guard.succeed"), false);
  assert.equal(invalidProjection.calls.includes("session.succeed"), false);
  assert.equal(invalidProjection.calls.filter((entry) => entry === "guard.fail").length, 1);
  assert.deepEqual(invalidProjection.leaseFailures, [{ countsTowardCircuit: true }]);
  assert.equal(invalidProjection.calls.filter((entry) => entry === "session.fail").length, 1);
  assert.equal(invalidProjection.calls.filter((entry) => entry === "guard.release").length, 1);
  assert.deepEqual(invalidProjection.calls.slice(-4), [
    "requestCompletion", "guard.fail", "session.fail", "guard.release"
  ]);
});

test("grounding and guard failures settle FAILED_PRE_DISPATCH", async () => {
  for (const options of [
    { groundingError: new Error("grounding failed") },
    { guardError: new Error("guard rejected") }
  ]) {
    const current = routeFixture(options);
    await assert.rejects(current.route({ question: "How should I train?" }, remoteContext()));
    assert.equal(current.calls.filter((entry) => entry === "session.fail").length, 1);
    assert.equal(current.calls.includes("requestCompletion"), false);
  }
});

test("provider failure after authorized attempt settles FAILED_POST_DISPATCH", async () => {
  const providerError = new ProviderUnavailableError();
  const current = routeFixture({ providerError });

  await assert.rejects(current.route({ question: "How should I train?" }, remoteContext()),
    (error) => error === providerError);

  assert.equal(current.calls.includes("beforeProviderAttempt"), true);
  assert.equal(current.calls.includes("requestCompletion"), true);
  assert.equal(current.calls.includes("guard.fail"), true);
  assert.equal(current.calls.includes("session.fail"), true);
  assert.equal(current.calls.at(-1), "guard.release");
});

test("attempt authorization failure prevents provider call and settles reservation", async () => {
  const admissionError = new RemoteAccountQuotaExhaustedError();
  const current = routeFixture({ beforeAttemptError: admissionError });

  await assert.rejects(current.route({ question: "How should I train?" }, remoteContext()),
    (error) => error === admissionError);

  assert.equal(current.calls.includes("requestCompletion"), false);
  assert.equal(current.calls.includes("session.fail"), true);
  assert.equal(current.calls.includes("guard.release"), true);
});

test("success settlement failure is not converted to post-dispatch failure", async () => {
  const settlementError = new RemoteAdmissionPersistenceUnavailableError();
  const current = routeFixture({ successError: settlementError });

  await assert.rejects(current.route({ question: "How should I train?" }, remoteContext()),
    (error) => error === settlementError);

  assert.equal(current.calls.filter((entry) => entry === "requestCompletion").length, 1);
  assert.equal(current.calls.includes("session.succeed"), true);
  assert.equal(current.calls.includes("session.fail"), false);
});

test("completed idempotent replay makes no second provider call", async () => {
  let beginCalls = 0;
  let providerCalls = 0;
  const remoteExecutionAdmission = {
    async begin() {
      beginCalls += 1;
      if (beginCalls === 2) throw new RemoteRequestCompletedError();
      return {
        async beforeProviderAttempt() {},
        async succeed() {},
        async fail() {}
      };
    }
  };
  const provider = {
    assertEnabled() {},
    async generateStructured() {
      providerCalls += 1;
      return coachResult();
    }
  };
  const route = createAiCoachRoute({
    config: { provider: "fireworks" },
    provider,
    remoteExecutionAdmission,
    groundingBuilder: async () => ({}),
    guard: {
      acquire() {
        return { succeed() {}, fail() {}, release() {} };
      }
    }
  });

  await route({ question: "How should I train?" }, remoteContext());
  await assert.rejects(route({ question: "How should I train?" }, remoteContext()), {
    code: "REMOTE_REQUEST_COMPLETED"
  });

  assert.equal(providerCalls, 1);
});
