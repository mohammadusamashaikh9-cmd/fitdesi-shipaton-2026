import assert from "node:assert/strict";
import { createServer, request as httpRequest } from "node:http";
import { test } from "node:test";
import { createApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import {
  responseValidators,
  validateSuccessEnvelope
} from "../src/services/response_validator.js";

const VERIFIED_TOKEN = "verified-token";
const defaultConfig = loadConfig({
  rateLimitMaxRequests: 1000,
  corsAllowedOrigins: []
});

const validRequests = [
  ["/api/ai/coach", { question: "How can I build muscle safely?" }, "coach"],
  ["/api/ai/workout-plan", {
    age: 25,
    gender: "not specified",
    level: "beginner",
    goal: "gain muscle",
    daysPerWeek: 3,
    selectedDays: ["Mon", "Wed", "Fri"],
    split: "Full Body",
    equipment: ["Dumbbells"]
  }, "workout"],
  ["/api/ai/food-analyze", {
    foodName: "Chicken karahi",
    servingSize: "1 bowl"
  }, "food"],
  ["/api/ai/diet-plan", {
    goal: "lose body fat",
    dailyCalories: 2000,
    mealsPerDay: 3,
    dietPreference: "Pakistani home food",
    allergies: []
  }, "diet"],
  ["/api/ai/yoga-plan", {
    goal: "mobility",
    level: "beginner",
    durationMinutes: 20,
    limitations: []
  }, "yoga"],
  ["/api/ai/progress-review", {
    workoutsCompleted: 3,
    totalDurationMinutes: 90,
    trainingVolumeKg: 504,
    note: "Consistent week"
  }, "progress"]
];

function fakeVerifier(outcomes = {}) {
  const calls = [];
  return {
    calls,
    async verify(token) {
      calls.push(token);
      const outcome = outcomes[token];
      if (outcome instanceof Error) throw outcome;
      return outcome ?? { status: "invalid" };
    }
  };
}

function verifiedVerifier() {
  return fakeVerifier({
    [VERIFIED_TOKEN]: {
      status: "verified",
      principal: { uid: "server-derived-verified-user", emailVerified: true }
    }
  });
}

async function withServer({
  verifier = verifiedVerifier(),
  logger = () => {},
  config = defaultConfig,
  fireworksProvider
} = {}, operation) {
  const server = createServer(createApp({
    config,
    logger,
    idTokenVerifier: verifier,
    fireworksProvider
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    return await operation(baseUrl, verifier);
  } finally {
    await new Promise((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()));
  }
}

async function requestJson(baseUrl, path, {
  method = "POST",
  headers = {},
  body,
  rawBody
} = {}) {
  const serializedBody = rawBody ?? (body === undefined ? null : JSON.stringify(body));
  const requestHeaders = { ...headers };
  if (serializedBody !== null) {
    requestHeaders["content-type"] ??= "application/json";
    requestHeaders["content-length"] = Buffer.byteLength(serializedBody);
  }
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: method === "GET" || method === "HEAD" ? undefined : serializedBody
  });
  return { response, body: await response.json() };
}

async function requestWithAuthorizationValues(baseUrl, path, values, body) {
  const payload = JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const request = httpRequest(`${baseUrl}${path}`, {
      method: "POST",
      headers: {
        authorization: values,
        "content-type": "application/json",
        "content-length": Buffer.byteLength(payload),
        connection: "close"
      }
    }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => resolve({
        response: {
          status: response.statusCode,
          headers: { get: (name) => response.headers[name.toLowerCase()] ?? null }
        },
        body: JSON.parse(Buffer.concat(chunks).toString("utf8"))
      }));
    });
    request.on("error", reject);
    request.end(payload);
  });
}

function assertAuthError(result, status, code, message) {
  assert.equal(result.response.status, status);
  assert.equal(result.body.success, false);
  assert.equal(result.body.error.code, code);
  assert.equal(result.body.error.message, message);
  assert.match(result.body.error.requestId, /^[0-9a-f-]{36}$/);
}

function remoteConfig(overrides = {}) {
  return loadConfig({
    provider: "fireworks",
    remoteAiEnabled: true,
    fireworksApiKey: "configured-for-injected-test-provider",
    fireworksModel: "test-model",
    rateLimitMaxRequests: 1000,
    corsAllowedOrigins: [],
    ...overrides
  });
}

function recordingProvider() {
  return {
    calls: 0,
    assertEnabled() {},
    async generateStructured() {
      this.calls += 1;
      return {
        data: {
          summary: "Grounded summary",
          recommendedAction: "Take one safe action.",
          nutritionNote: "Use measured portions.",
          workoutNote: "Progress gradually.",
          safetyDisclaimer: "General education only.",
          escalationRequired: false,
          detectedIntent: "GENERAL_COACHING",
          detectedGoal: null,
          confidence: 0.8,
          warnings: [],
          generatedWorkoutPlan: null,
          generatedDietPlan: null
        },
        usage: {}
      };
    }
  };
}

test("all six AI POST routes require Authorization", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    for (const [path, body] of validRequests) {
      const result = await requestJson(baseUrl, path, { body });
      assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
      assert.equal(result.response.headers.get("www-authenticate"), "Bearer", path);
    }
    assert.deepEqual(verifier.calls, []);
  });
});

test("malformed and duplicate Bearer credentials fail before AI provider execution", async () => {
  const provider = recordingProvider();
  await withServer({
    config: remoteConfig(),
    fireworksProvider: provider
  }, async (baseUrl, verifier) => {
    const malformed = await requestJson(baseUrl, "/api/ai/coach", {
      headers: { authorization: "Bearer token extra" },
      body: { question: "How should I train?" }
    });
    const duplicate = await requestWithAuthorizationValues(
      baseUrl,
      "/api/ai/coach",
      ["Bearer first", "Bearer second"],
      { question: "How should I train?" }
    );

    for (const result of [malformed, duplicate]) {
      assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
      assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    }
    assert.deepEqual(verifier.calls, []);
    assert.equal(provider.calls, 0);
  });
});

test("invalid AI session returns INVALID_SESSION before provider execution", async () => {
  const verifier = fakeVerifier({ invalid: { status: "invalid" } });
  const provider = recordingProvider();
  await withServer({
    verifier,
    config: remoteConfig(),
    fireworksProvider: provider
  }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/ai/coach", {
      headers: { authorization: "Bearer invalid" },
      body: { question: "How should I train?" }
    });

    assertAuthError(
      result,
      401,
      "INVALID_SESSION",
      "Your session is invalid or expired. Sign in again."
    );
    assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    assert.deepEqual(verifier.calls, ["invalid"]);
    assert.equal(provider.calls, 0);
  });
});

test("unverified email returns EMAIL_VERIFICATION_REQUIRED before body parsing or provider execution", async () => {
  const verifier = fakeVerifier({
    unverified: {
      status: "verified",
      principal: {
        uid: "sensitive-unverified-uid",
        emailVerified: false,
        arbitraryClaim: "sensitive-claim"
      }
    }
  });
  const provider = recordingProvider();
  await withServer({
    verifier,
    config: remoteConfig(),
    fireworksProvider: provider
  }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/ai/coach", {
      headers: { authorization: "Bearer unverified" },
      rawBody: "{not-json"
    });

    assertAuthError(
      result,
      403,
      "EMAIL_VERIFICATION_REQUIRED",
      "Verify your email to use remote AI features."
    );
    const serialized = JSON.stringify(result.body);
    assert.equal(serialized.includes("sensitive-unverified-uid"), false);
    assert.equal(serialized.includes("sensitive-claim"), false);
    assert.deepEqual(verifier.calls, ["unverified"]);
    assert.equal(provider.calls, 0);
  });
});

test("verified email reaches the pre-existing behavior on all six AI routes", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    for (const [path, body, responseType] of validRequests) {
      const result = await requestJson(baseUrl, path, {
        headers: { authorization: `Bearer ${VERIFIED_TOKEN}` },
        body
      });
      assert.equal(result.response.status, 200, path);
      assert.equal(validateSuccessEnvelope(result.body), true, path);
      assert.equal(responseValidators[responseType](result.body.data), true, path);
      assert.equal(result.body.mode, "mock", path);
    }
    assert.deepEqual(verifier.calls, Array(validRequests.length).fill(VERIFIED_TOKEN));
  });
});

test("AI authentication infrastructure failure is redacted and prevents provider execution", async () => {
  const sensitiveMarkers = [
    "sensitive-auth-token",
    "sensitive-firebase-uid",
    "sensitive-decoded-claim"
  ];
  const verifier = fakeVerifier({
    "sensitive-auth-token": new Error(sensitiveMarkers.slice(1).join(" "))
  });
  const provider = recordingProvider();
  const logs = [];
  await withServer({
    verifier,
    config: remoteConfig(),
    fireworksProvider: provider,
    logger: (entry) => logs.push(entry)
  }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/ai/coach", {
      headers: { authorization: "Bearer sensitive-auth-token" },
      body: { question: "Private fitness content" }
    });
    await new Promise((resolve) => setImmediate(resolve));

    assertAuthError(
      result,
      503,
      "AUTH_VERIFICATION_UNAVAILABLE",
      "Session verification is temporarily unavailable. Please try again."
    );
    assert.equal(provider.calls, 0);
    assert.equal(logs.length, 1);
    assert.deepEqual(Object.keys(logs[0]).sort(), [
      "durationMs", "requestId", "route", "status"
    ]);
    const capturedOutput = JSON.stringify({ response: result.body, logs });
    for (const marker of [...sensitiveMarkers, "Private fitness content"]) {
      assert.equal(capturedOutput.includes(marker), false);
    }
  });
});

test("AI authentication verification timeout fails closed before provider execution", async () => {
  const verifier = {
    calls: [],
    verify(token) {
      this.calls.push(token);
      return new Promise(() => {});
    }
  };
  const provider = recordingProvider();
  const config = remoteConfig({
    requestTimeoutMs: 20,
    fireworksTimeoutMs: 10
  });
  await withServer({ verifier, config, fireworksProvider: provider }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/ai/coach", {
      headers: { authorization: "Bearer slow-token" },
      body: { question: "How should I train?" }
    });

    assertAuthError(
      result,
      503,
      "AUTH_VERIFICATION_UNAVAILABLE",
      "Session verification is temporarily unavailable. Please try again."
    );
    assert.deepEqual(verifier.calls, ["slow-token"]);
    assert.equal(provider.calls, 0);
  });
});

test("AI routes use the coarse IP rate limiter before token verification", async () => {
  const verifier = verifiedVerifier();
  const config = loadConfig({
    rateLimitMaxRequests: 1,
    rateLimitWindowMs: 60000,
    corsAllowedOrigins: []
  });
  await withServer({ verifier, config }, async (baseUrl) => {
    const request = {
      headers: { authorization: `Bearer ${VERIFIED_TOKEN}` },
      body: { foodName: "Chicken karahi", servingSize: "1 bowl" }
    };
    const first = await requestJson(baseUrl, "/api/ai/food-analyze", request);
    const second = await requestJson(baseUrl, "/api/ai/food-analyze", request);

    assert.equal(first.response.status, 200);
    assert.equal(second.response.status, 429);
    assert.equal(second.body.error.code, "RATE_LIMITED");
    assert.deepEqual(verifier.calls, [VERIFIED_TOKEN]);
  });
});

test("AI authentication runs before bounded JSON body parsing", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    const result = await requestJson(baseUrl, "/api/ai/food-analyze", {
      rawBody: "{not-json"
    });

    assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
    assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    assert.deepEqual(verifier.calls, []);
  });
});

test("health remains public and does not invoke token verification", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    const result = await requestJson(baseUrl, "/api/health", { method: "GET" });

    assert.equal(result.response.status, 200);
    assert.equal(validateSuccessEnvelope(result.body), true);
    assert.deepEqual(verifier.calls, []);
  });
});
