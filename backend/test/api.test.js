import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createServer } from "node:http";
import { createApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import {
  responseValidators,
  validateErrorEnvelope,
  validateSuccessEnvelope
} from "../src/services/response_validator.js";

const quietLogger = () => {};
const defaultConfig = loadConfig({
  rateLimitMaxRequests: 1000,
  corsAllowedOrigins: ["https://fitdesi.example"]
});
const verifiedIdTokenVerifier = Object.freeze({
  async verify(token) {
    assert.equal(token, "test-verified-token");
    return {
      status: "verified",
      principal: { uid: "server-derived-test-user", emailVerified: true }
    };
  }
});

let server;
let baseUrl;

async function startServer(config = defaultConfig) {
  const instance = createServer(createApp({
    config,
    logger: quietLogger,
    idTokenVerifier: verifiedIdTokenVerifier
  }));
  await new Promise((resolve) => instance.listen(0, "127.0.0.1", resolve));
  const { port } = instance.address();
  return {
    instance,
    url: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve, reject) =>
      instance.close((error) => error ? reject(error) : resolve()))
  };
}

before(async () => {
  const started = await startServer();
  server = started;
  baseUrl = started.url;
});

after(async () => {
  await server.close();
});

async function get(path, { url = baseUrl, headers = {} } = {}) {
  const response = await fetch(`${url}${path}`, { headers });
  return { response, body: await response.json() };
}

async function post(path, body, { url = baseUrl, headers = {} } = {}) {
  const response = await fetch(`${url}${path}`, {
    method: "POST",
    headers: {
      authorization: "Bearer test-verified-token",
      "content-type": "application/json",
      ...headers
    },
    body: JSON.stringify(body)
  });
  return { response, body: await response.json() };
}

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

test("health endpoint reports the deterministic mock provider", async () => {
  const { response, body } = await get("/api/health");
  assert.equal(response.status, 200);
  assert.equal(validateSuccessEnvelope(body), true);
  assert.equal(responseValidators.health(body.data), true);
  assert.equal(body.mode, "mock");
  assert.equal(body.data.providerMode, "mock");
  assert.match(body.requestId, /^[0-9a-f-]{36}$/);
});

test("all current AI routes pass strict response validation", async () => {
  for (const [path, request, responseType] of validRequests) {
    const { response, body } = await post(path, request);
    assert.equal(response.status, 200, path);
    assert.equal(validateSuccessEnvelope(body), true, path);
    assert.equal(responseValidators[responseType](body.data), true, path);
    assert.equal(body.mode, "mock", path);
  }
});

test("strict request validation rejects invalid and unknown fields", async () => {
  const invalidCases = [
    ["/api/ai/coach", { question: "" }],
    ["/api/ai/food-analyze", { foodName: "Daal", unexpectedProfile: "private" }],
    ["/api/ai/workout-plan", {
      age: 25,
      gender: "not specified",
      level: "beginner",
      goal: "gain muscle",
      daysPerWeek: 2,
      selectedDays: ["Mon"],
      split: "Full Body",
      equipment: ["Bodyweight"]
    }]
  ];

  for (const [path, request] of invalidCases) {
    const { response, body } = await post(path, request);
    assert.equal(response.status, 400, path);
    assert.equal(validateErrorEnvelope(body), true, path);
    assert.equal(body.error.code, "VALIDATION_ERROR", path);
    assert.equal("stack" in body.error, false, path);
  }
});

test("oversized JSON receives a stable 413 error", async () => {
  const limited = await startServer(loadConfig({
    requestBodyLimitBytes: 128,
    rateLimitMaxRequests: 1000
  }));
  try {
    const { response, body } = await post(
      "/api/ai/coach",
      { question: "x".repeat(500) },
      { url: limited.url }
    );
    assert.equal(response.status, 413);
    assert.equal(validateErrorEnvelope(body), true);
    assert.equal(body.error.code, "PAYLOAD_TOO_LARGE");
  } finally {
    await limited.close();
  }
});

test("rate limiting returns a stable 429 response and retry hint", async () => {
  const limited = await startServer(loadConfig({
    rateLimitMaxRequests: 2,
    rateLimitWindowMs: 60000
  }));
  try {
    await post("/api/ai/coach", { question: "First request" }, { url: limited.url });
    await post("/api/ai/coach", { question: "Second request" }, { url: limited.url });
    const { response, body } = await post(
      "/api/ai/coach",
      { question: "Third request" },
      { url: limited.url }
    );
    assert.equal(response.status, 429);
    assert.equal(response.headers.has("retry-after"), true);
    assert.equal(validateErrorEnvelope(body), true);
    assert.equal(body.error.code, "RATE_LIMITED");
  } finally {
    await limited.close();
  }
});

test("restricted CORS allows configured origins and rejects other browser origins", async () => {
  const allowed = await get("/api/health", {
    headers: { origin: "https://fitdesi.example" }
  });
  assert.equal(allowed.response.status, 200);
  assert.equal(
    allowed.response.headers.get("access-control-allow-origin"),
    "https://fitdesi.example"
  );

  const denied = await get("/api/health", {
    headers: { origin: "https://untrusted.example" }
  });
  assert.equal(denied.response.status, 403);
  assert.equal(validateErrorEnvelope(denied.body), true);
  assert.equal(denied.body.error.code, "CORS_ORIGIN_DENIED");
});

test("error response schema rejects extra internal fields", () => {
  assert.equal(validateErrorEnvelope({
    success: false,
    error: {
      code: "INTERNAL_ERROR",
      message: "Friendly message",
      requestId: "request-id",
      stack: "must not cross boundary"
    }
  }), false);
});

test("response validators reject unexpected output fields", () => {
  assert.equal(responseValidators.coach({
    summary: "Summary",
    recommendedAction: "Action",
    nutritionNote: "Nutrition",
    workoutNote: "Workout",
    safetyDisclaimer: "Safety",
    rawProviderBody: "must not cross boundary"
  }), false);
});

test("privacy-safe logging records metadata only", async () => {
  const entries = [];
  const loggingServer = createServer(createApp({
    config: loadConfig({ rateLimitMaxRequests: 1000 }),
    logger: (entry) => entries.push(entry),
    idTokenVerifier: verifiedIdTokenVerifier
  }));
  await new Promise((resolve) => loggingServer.listen(0, "127.0.0.1", resolve));
  const { port } = loggingServer.address();
  try {
    await post(
      "/api/ai/coach",
      { question: "Private prompt text must not be logged" },
      { url: `http://127.0.0.1:${port}` }
    );
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(entries.length, 1);
    assert.deepEqual(Object.keys(entries[0]).sort(), [
      "durationMs", "fallbackStatus", "inputCharacterCount", "providerAlias",
      "requestId", "route", "status", "validationResult"
    ]);
    assert.equal(entries[0].route, "/api/ai/coach");
    assert.equal(entries[0].status, 200);
    assert.equal(JSON.stringify(entries).includes("Private prompt"), false);
  } finally {
    await new Promise((resolve, reject) =>
      loggingServer.close((error) => error ? reject(error) : resolve()));
  }
});
