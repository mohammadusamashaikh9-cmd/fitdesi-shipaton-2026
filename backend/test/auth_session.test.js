import assert from "node:assert/strict";
import { createServer, request as httpRequest } from "node:http";
import { test } from "node:test";
import { createApp } from "../src/app.js";
import { createFirebaseAdminIdTokenVerifier } from "../src/auth/firebase_admin_id_token_verifier.js";
import { loadConfig } from "../src/config.js";

const testConfig = loadConfig({
  rateLimitMaxRequests: 1000,
  corsAllowedOrigins: []
});

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

async function withServer({
  verifier = fakeVerifier(),
  logger = () => {},
  config = testConfig
} = {}, operation) {
  const server = createServer(createApp({
    config,
    logger,
    idTokenVerifier: verifier
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    return await operation(`http://127.0.0.1:${port}`, verifier);
  } finally {
    await new Promise((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()));
  }
}

async function requestJsonWithAuthorizationValues(baseUrl, values) {
  return new Promise((resolve, reject) => {
    const request = httpRequest(`${baseUrl}/api/auth/session`, {
      method: "GET",
      headers: {
        authorization: values,
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
    request.end();
  });
}

async function requestJson(baseUrl, path, {
  method = "GET",
  headers = {},
  body
} = {}) {
  const requestHeaders = { ...headers };
  const serializedBody = body === undefined ? null : JSON.stringify(body);
  if (serializedBody !== null) {
    requestHeaders["content-type"] = "application/json";
    requestHeaders["content-length"] = Buffer.byteLength(serializedBody);
  }
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: method === "GET" || method === "HEAD" ? undefined : serializedBody
  });
  return { response, body: await response.json() };
}

async function getWithJsonBody(baseUrl, path, headers, body) {
  const payload = JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const request = httpRequest(`${baseUrl}${path}`, {
      method: "GET",
      headers: {
        ...headers,
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

test("missing Authorization requires authentication", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    const result = await requestJson(baseUrl, "/api/auth/session");

    assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
    assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    assert.deepEqual(verifier.calls, []);
  });
});

test("malformed Bearer credentials require authentication", async () => {
  const malformed = [
    "Bearer",
    "Bearer ",
    "Basic credential",
    "bearer token",
    "Bearer token extra"
  ];
  await withServer({}, async (baseUrl, verifier) => {
    for (const authorization of malformed) {
      const result = await requestJson(baseUrl, "/api/auth/session", {
        headers: { authorization }
      });
      assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
      assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    }
    assert.deepEqual(verifier.calls, []);
  });
});

test("duplicate Authorization headers require authentication without verification", async () => {
  const verifier = fakeVerifier({
    valid: {
      status: "verified",
      principal: { uid: "server-derived", emailVerified: true }
    }
  });

  await withServer({ verifier }, async (baseUrl) => {
    const result = await requestJsonWithAuthorizationValues(baseUrl, [
      "Bearer valid",
      "Bearer duplicate"
    ]);

    assertAuthError(result, 401, "AUTH_REQUIRED", "Sign in to continue.");
    assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    assert.deepEqual(verifier.calls, []);
  });
});

test("invalid expired revoked disabled deleted and wrong-project tokens share INVALID_SESSION", async () => {
  const invalidTokens = ["invalid", "expired", "revoked", "disabled", "deleted", "wrong-project"];
  const verifier = fakeVerifier(Object.fromEntries(
    invalidTokens.map((token) => [token, { status: "invalid" }])
  ));

  await withServer({ verifier }, async (baseUrl) => {
    for (const token of invalidTokens) {
      const result = await requestJson(baseUrl, "/api/auth/session", {
        headers: { authorization: `Bearer ${token}` }
      });
      assertAuthError(
        result,
        401,
        "INVALID_SESSION",
        "Your session is invalid or expired. Sign in again."
      );
      assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    }
    assert.deepEqual(verifier.calls, invalidTokens);
  });
});

test("verified and unverified Firebase identities both authenticate", async () => {
  const verifier = fakeVerifier({
    verified: {
      status: "verified",
      principal: { uid: "server-derived-one", emailVerified: true }
    },
    unverified: {
      status: "verified",
      principal: { uid: "server-derived-two", emailVerified: false }
    }
  });

  await withServer({ verifier }, async (baseUrl) => {
    for (const [token, emailVerified] of [["verified", true], ["unverified", false]]) {
      const result = await requestJson(baseUrl, "/api/auth/session", {
        headers: { authorization: `Bearer ${token}` }
      });
      assert.equal(result.response.status, 200);
      assert.equal(Object.hasOwn(result.body, "mode"), false);
      assert.deepEqual(result.body.data, { authenticated: true, emailVerified });
      assert.match(result.body.requestId, /^[0-9a-f-]{36}$/);
    }
  });
});

test("client identity fields cannot override the verifier-derived principal", async () => {
  const verifier = fakeVerifier({
    trusted: {
      status: "verified",
      principal: { uid: "trusted-server-uid", emailVerified: false }
    }
  });

  await withServer({ verifier }, async (baseUrl) => {
    const result = await getWithJsonBody(
      baseUrl,
      "/api/auth/session?uid=query-uid&emailVerified=true&email=query@example.com",
      {
        authorization: "Bearer trusted",
        "x-firebase-uid": "header-uid",
        "x-user-id": "legacy-user",
        "x-revenuecat-app-user-id": "fd_header-uid"
      },
      {
        uid: "body-uid",
        email: "body@example.com",
        emailVerified: true
      }
    );

    assert.equal(result.response.status, 200);
    assert.deepEqual(result.body.data, { authenticated: true, emailVerified: false });
    const serialized = JSON.stringify(result.body);
    for (const marker of [
      "trusted-server-uid", "query-uid", "query@example.com", "header-uid",
      "legacy-user", "fd_header-uid", "body-uid", "body@example.com"
    ]) {
      assert.equal(serialized.includes(marker), false);
    }
  });
});

test("verification infrastructure failure is a redacted 503", async () => {
  const sensitiveMarkers = [
    "secret-token-marker",
    "firebase-uid-marker",
    "person@example.com",
    "provider-credential-marker"
  ];
  const verifier = fakeVerifier({
    "secret-token-marker": new Error(sensitiveMarkers.slice(1).join(" "))
  });
  const logEntries = [];

  await withServer({ verifier, logger: (entry) => logEntries.push(entry) }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/auth/session", {
      headers: { authorization: "Bearer secret-token-marker" }
    });
    await new Promise((resolve) => setImmediate(resolve));

    assertAuthError(
      result,
      503,
      "AUTH_VERIFICATION_UNAVAILABLE",
      "Session verification is temporarily unavailable. Please try again."
    );
    assert.equal(logEntries.length, 1);
    assert.deepEqual(Object.keys(logEntries[0]).sort(), [
      "durationMs", "requestId", "route", "status"
    ]);
    const capturedOutput = JSON.stringify({ response: result.body, logs: logEntries });
    for (const marker of sensitiveMarkers) {
      assert.equal(capturedOutput.includes(marker), false);
    }
  });
});

test("authentication verification timeout fails closed as unavailable", async () => {
  const verifier = {
    calls: [],
    verify(token) {
      this.calls.push(token);
      return new Promise(() => {});
    }
  };
  const config = loadConfig({
    rateLimitMaxRequests: 1000,
    requestTimeoutMs: 20,
    fireworksTimeoutMs: 10,
    corsAllowedOrigins: []
  });

  await withServer({ verifier, config }, async (baseUrl) => {
    const result = await requestJson(baseUrl, "/api/auth/session", {
      headers: { authorization: "Bearer valid-but-slow" }
    });

    assertAuthError(
      result,
      503,
      "AUTH_VERIFICATION_UNAVAILABLE",
      "Session verification is temporarily unavailable. Please try again."
    );
    assert.deepEqual(verifier.calls, ["valid-but-slow"]);
  });
});

test("authentication requests use the shared rate limiter before verification", async () => {
  const verifier = fakeVerifier({
    valid: {
      status: "verified",
      principal: { uid: "server-derived", emailVerified: true }
    }
  });
  const config = loadConfig({
    rateLimitMaxRequests: 1,
    corsAllowedOrigins: []
  });

  await withServer({ verifier, config }, async (baseUrl) => {
    const first = await requestJson(baseUrl, "/api/auth/session", {
      headers: { authorization: "Bearer valid" }
    });
    const second = await requestJson(baseUrl, "/api/auth/session", {
      headers: { authorization: "Bearer valid" }
    });

    assert.equal(first.response.status, 200);
    assert.equal(second.response.status, 429);
    assert.equal(second.body.error.code, "RATE_LIMITED");
    assert.deepEqual(verifier.calls, ["valid"]);
  });
});

test("health remains public and does not invoke authentication", async () => {
  await withServer({}, async (baseUrl, verifier) => {
    const health = await requestJson(baseUrl, "/api/health");

    assert.equal(health.response.status, 200);
    assert.deepEqual(verifier.calls, []);
  });
});

test("remote AI remains disabled by default", () => {
  const previous = process.env.REMOTE_AI_ENABLED;
  try {
    delete process.env.REMOTE_AI_ENABLED;
    assert.equal(loadConfig().remoteAiEnabled, false);
  } finally {
    if (previous === undefined) delete process.env.REMOTE_AI_ENABLED;
    else process.env.REMOTE_AI_ENABLED = previous;
  }
});

test("Firebase Admin verifier supports both approved projects with revocation checking", async () => {
  for (const projectId of ["fitdesi-ai", "fitdesi-ai-production"]) {
    const calls = [];
    const verifier = createFirebaseAdminIdTokenVerifier({
      projectId,
      firebaseAuth: {
        async verifyIdToken(token, checkRevoked) {
          calls.push([token, checkRevoked]);
          return {
            uid: "verified-uid",
            email: "person@example.com",
            email_verified: false,
            arbitrary_claim: "must-not-cross"
          };
        }
      }
    });

    const result = await verifier.verify("ephemeral-token");

    assert.deepEqual(calls, [["ephemeral-token", true]], projectId);
    assert.deepEqual(result, {
      status: "verified",
      principal: { uid: "verified-uid", emailVerified: false }
    }, projectId);
  }
});

test("Firebase Admin verifier separates invalid identity from infrastructure failure", async () => {
  const verifierFor = (code) => createFirebaseAdminIdTokenVerifier({
    projectId: "fitdesi-ai",
    firebaseAuth: {
      async verifyIdToken() {
        throw Object.assign(new Error("sensitive provider detail"), { code });
      }
    }
  });

  assert.deepEqual(
    await verifierFor("auth/id-token-revoked").verify("revoked"),
    { status: "invalid" }
  );
  assert.deepEqual(
    await verifierFor("app/invalid-credential").verify("valid-but-unverifiable"),
    { status: "unavailable" }
  );
});

test("Firebase Admin verifier accepts only the two server-approved projects", async () => {
  let calls = 0;
  const verifier = createFirebaseAdminIdTokenVerifier({
    projectId: "client-selected-project",
    firebaseAuth: {
      async verifyIdToken() {
        calls += 1;
        return { uid: "must-not-authenticate" };
      }
    }
  });

  assert.deepEqual(await verifier.verify("token"), { status: "unavailable" });
  assert.equal(calls, 0);
});
