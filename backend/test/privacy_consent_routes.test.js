import assert from "node:assert/strict";
import { createServer, request as httpRequest } from "node:http";
import { connect as netConnect } from "node:net";
import { test } from "node:test";
import { createApp } from "../src/app.js";
import { loadConfig } from "../src/config.js";
import {
  ConsentAuthorityInvalidRecordError,
  ConsentAuthorityTimeoutError,
  ConsentAuthorityUnavailableError,
  ConsentNoticeVersionMismatchError
} from "../src/errors.js";
import { PrivacyConsentAuthority } from "../src/privacy/privacy_consent_authority.js";
import {
  assertNoConsentRequestBody,
  createPrivacyConsentRoutes,
  validateConsentMutationRequest,
  validateConsentStateResponse
} from "../src/routes/privacy_consent.js";

const TEST_CONFIG = loadConfig({
  rateLimitMaxRequests: 1000,
  corsAllowedOrigins: [],
  remoteAiNoticeVersion: "standard-v1",
  experimentalAiNoticeVersion: "experimental-v1"
});
const NORMALIZED_NO_CONSENT_STATE = Object.freeze({
  schemaVersion: 1,
  standardRemoteAi: Object.freeze({
    granted: false,
    current: false,
    noticeVersion: null,
    decidedAt: null
  }),
  experimentalTraining: Object.freeze({
    granted: false,
    current: false,
    noticeVersion: null,
    decidedAt: null
  }),
  requiredNoticeVersions: Object.freeze({
    standardRemoteAi: "standard-v1",
    experimentalTraining: "experimental-v1"
  }),
  updatedAt: null
});
const PRIVACY_PATH = "/api/privacy/consent";

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

function authenticatedVerifier({ emailVerified = false } = {}) {
  return fakeVerifier({
    trusted: {
      status: "verified",
      principal: { uid: "trusted-uid", emailVerified }
    }
  });
}

function recordingAuthority({
  state = NORMALIZED_NO_CONSENT_STATE,
  failures = {},
  implementations = {}
} = {}) {
  const getCalls = [];
  const updateCalls = [];
  const deleteCalls = [];
  return {
    getCalls,
    updateCalls,
    deleteCalls,
    async getConsentState(input) {
      getCalls.push(structuredClone(input));
      if (failures.get) throw failures.get;
      if (implementations.get) return implementations.get(input);
      return state;
    },
    async updateConsent(input) {
      updateCalls.push(structuredClone(input));
      if (failures.put) throw failures.put;
      if (implementations.put) return implementations.put(input);
    },
    async deleteConsent(input) {
      deleteCalls.push(structuredClone(input));
      if (failures.delete) throw failures.delete;
      if (implementations.delete) return implementations.delete(input);
    }
  };
}

async function withServer({
  verifier = authenticatedVerifier(),
  authority = recordingAuthority(),
  logger = () => {},
  config = TEST_CONFIG,
  fireworksProvider
} = {}, operation) {
  const server = createServer(createApp({
    config,
    logger,
    idTokenVerifier: verifier,
    consentAuthority: authority,
    fireworksProvider
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    return await operation(baseUrl, { verifier, authority });
  } finally {
    await new Promise((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()));
  }
}

async function requestJson(baseUrl, path, {
  method = "GET",
  authorization,
  authorizationValues,
  rawHeaders = {},
  body,
  rawBody
} = {}) {
  const serializedBody = rawBody ??
    (body === undefined ? null : JSON.stringify(body));
  const headers = { ...rawHeaders, connection: "close" };
  if (authorization !== undefined) headers.authorization = authorization;
  if (authorizationValues !== undefined) {
    headers.authorization = authorizationValues;
  }
  if (serializedBody !== null) {
    headers["content-type"] ??= "application/json";
    if (
      headers["content-length"] === undefined &&
      headers["transfer-encoding"] === undefined
    ) {
      headers["content-length"] = Buffer.byteLength(serializedBody);
    }
  }

  return new Promise((resolve, reject) => {
    const serverUrl = new URL(baseUrl);
    const request = httpRequest({
      protocol: serverUrl.protocol,
      hostname: serverUrl.hostname,
      port: serverUrl.port,
      path,
      method,
      headers
    }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        resolve({
          response: {
            status: response.statusCode,
            headers: {
              get: (name) => response.headers[name.toLowerCase()] ?? null
            }
          },
          text,
          body: text.length === 0 ? null : JSON.parse(text)
        });
      });
    });
    request.on("error", reject);
    request.end(serializedBody ?? undefined);
  });
}

async function requestRawTarget(baseUrl, requestTarget) {
  const serverUrl = new URL(baseUrl);
  return new Promise((resolve, reject) => {
    const socket = netConnect({
      host: serverUrl.hostname,
      port: Number(serverUrl.port)
    });
    const chunks = [];
    socket.on("connect", () => {
      socket.write(
        `GET ${requestTarget} HTTP/1.1\r\n` +
        `Host: ${serverUrl.host}\r\n` +
        "Connection: close\r\n\r\n"
      );
    });
    socket.on("data", (chunk) => chunks.push(chunk));
    socket.on("error", reject);
    socket.on("end", () => {
      const rawResponse = Buffer.concat(chunks).toString("utf8");
      const separator = rawResponse.indexOf("\r\n\r\n");
      const headerLines = rawResponse.slice(0, separator).split("\r\n");
      const status = Number(headerLines[0].split(" ")[1]);
      const headers = Object.fromEntries(headerLines.slice(1).map((line) => {
        const colon = line.indexOf(":");
        return [
          line.slice(0, colon).toLowerCase(),
          line.slice(colon + 1).trim()
        ];
      }));
      const text = rawResponse.slice(separator + 4);
      resolve({
        response: {
          status,
          headers: { get: (name) => headers[name.toLowerCase()] ?? null }
        },
        text,
        body: text.length === 0 ? null : JSON.parse(text)
      });
    });
  });
}

function assertError(result, status, code) {
  assert.equal(result.response.status, status);
  assert.equal(result.body.success, false);
  assert.equal(result.body.error.code, code);
  assert.equal(typeof result.body.error.message, "string");
  assert.match(result.body.error.requestId, /^[0-9a-f-]{36}$/);
}

function assertNoAuthorityCalls(authority) {
  assert.deepEqual(authority.getCalls, []);
  assert.deepEqual(authority.updateCalls, []);
  assert.deepEqual(authority.deleteCalls, []);
}

function coachRequestBody() {
  return { question: "How can I build muscle safely?" };
}

test("privacy route helpers are immutable and validate their public contracts", async () => {
  const authority = recordingAuthority();
  const routes = createPrivacyConsentRoutes({ authority });

  assert.equal(Object.isFrozen(routes), true);
  assert.deepEqual(validateConsentMutationRequest({
    standardRemoteAi: { granted: false, noticeVersion: "standard-v0" }
  }), {
    standardRemoteAi: { granted: false, noticeVersion: "standard-v0" }
  });
  assert.equal(validateConsentStateResponse(NORMALIZED_NO_CONSENT_STATE), true);
  assert.equal(
    assertNoConsentRequestBody({ headers: { "content-length": "0" } }),
    undefined
  );
  assert.deepEqual(
    await routes.get({ firebaseUid: "trusted-uid" }),
    NORMALIZED_NO_CONSENT_STATE
  );
});

test("normalized consent response rejects impossible or non-derived currentness", () => {
  const currentState = {
    schemaVersion: 1,
    standardRemoteAi: {
      granted: true,
      current: true,
      noticeVersion: "standard-v1",
      decidedAt: "2026-09-14T10:00:00.000Z"
    },
    experimentalTraining: {
      granted: false,
      current: false,
      noticeVersion: "experimental-v1",
      decidedAt: "2026-09-14T10:01:00.000Z"
    },
    requiredNoticeVersions: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    },
    updatedAt: "2026-09-14T10:02:00.000Z"
  };
  assert.equal(validateConsentStateResponse(currentState), true);

  const invalidStates = [
    {
      ...currentState,
      standardRemoteAi: { ...currentState.standardRemoteAi, current: false }
    },
    {
      ...currentState,
      standardRemoteAi: {
        ...currentState.standardRemoteAi,
        noticeVersion: "standard-v0"
      }
    },
    {
      ...currentState,
      standardRemoteAi: {
        ...currentState.standardRemoteAi,
        granted: false,
        current: false
      },
      experimentalTraining: {
        ...currentState.experimentalTraining,
        granted: true
      }
    }
  ];
  for (const state of invalidStates) {
    assert.equal(validateConsentStateResponse(state), false);
  }
});

test("GET uses only verifier-derived UID and exposes exact normalized state without UID", async () => {
  const authority = recordingAuthority();
  const verifier = authenticatedVerifier();
  const logs = [];

  await withServer({
    verifier,
    authority,
    logger: (entry) => logs.push(entry)
  }, async (baseUrl) => {
    const result = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer trusted",
      rawHeaders: {
        "x-firebase-uid": "attacker-uid",
        "x-user-id": "attacker-user"
      }
    });
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(result.response.status, 200);
    assert.equal(result.response.headers.get("cache-control"), "no-store");
    assert.deepEqual(result.body, {
      success: true,
      requestId: result.body.requestId,
      data: NORMALIZED_NO_CONSENT_STATE
    });
    assert.equal(Object.hasOwn(result.body, "mode"), false);
    assert.deepEqual(authority.getCalls, [{ firebaseUid: "trusted-uid" }]);
    assert.deepEqual(authority.updateCalls, []);
    assert.deepEqual(authority.deleteCalls, []);
    const captured = JSON.stringify({ response: result.body, logs });
    for (const marker of ["trusted-uid", "attacker-uid", "attacker-user"]) {
      assert.equal(captured.includes(marker), false);
    }
  });
});

test("PUT grants standard consent and returns 204 only after authority resolves", async () => {
  let releaseMutation;
  let mutationStarted;
  const started = new Promise((resolve) => {
    mutationStarted = resolve;
  });
  const authority = recordingAuthority({
    implementations: {
      put() {
        mutationStarted();
        return new Promise((resolve) => {
          releaseMutation = resolve;
        });
      }
    }
  });

  await withServer({ authority }, async (baseUrl) => {
    let responseSettled = false;
    const pending = requestJson(baseUrl, PRIVACY_PATH, {
      method: "PUT",
      authorization: "Bearer trusted",
      body: {
        standardRemoteAi: { granted: true, noticeVersion: "standard-v1" }
      }
    }).then((result) => {
      responseSettled = true;
      return result;
    });

    await started;
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(responseSettled, false);
    releaseMutation();

    const result = await pending;
    assert.equal(result.response.status, 204);
    assert.equal(result.text, "");
    assert.equal(result.response.headers.get("cache-control"), "no-store");
    assert.deepEqual(authority.updateCalls, [{
      firebaseUid: "trusted-uid",
      mutation: {
        standardRemoteAi: { granted: true, noticeVersion: "standard-v1" }
      }
    }]);
  });
});

test("PUT withdrawal accepts unverified authenticated users and no notice echo", async () => {
  const authority = recordingAuthority();

  await withServer({ authority }, async (baseUrl) => {
    const result = await requestJson(baseUrl, PRIVACY_PATH, {
      method: "PUT",
      authorization: "Bearer trusted",
      body: { standardRemoteAi: { granted: false } }
    });

    assert.equal(result.response.status, 204);
    assert.equal(result.text, "");
    assert.deepEqual(authority.updateCalls, [{
      firebaseUid: "trusted-uid",
      mutation: { standardRemoteAi: { granted: false } }
    }]);
  });
});

test("unverified authenticated user may GET grant withdraw and DELETE", async () => {
  const authority = recordingAuthority();
  const verifier = authenticatedVerifier({ emailVerified: false });

  await withServer({ verifier, authority }, async (baseUrl) => {
    const requests = [
      requestJson(baseUrl, PRIVACY_PATH, {
        authorization: "Bearer trusted"
      }),
      requestJson(baseUrl, PRIVACY_PATH, {
        method: "PUT",
        authorization: "Bearer trusted",
        body: {
          experimentalTraining: {
            granted: true,
            noticeVersion: "experimental-v1"
          }
        }
      }),
      requestJson(baseUrl, PRIVACY_PATH, {
        method: "PUT",
        authorization: "Bearer trusted",
        body: { experimentalTraining: { granted: false } }
      }),
      requestJson(baseUrl, PRIVACY_PATH, {
        method: "DELETE",
        authorization: "Bearer trusted"
      })
    ];
    const results = [];
    for (const request of requests) results.push(await request);

    assert.deepEqual(results.map((result) => result.response.status), [
      200, 204, 204, 204
    ]);
    assert.deepEqual(verifier.calls, Array(4).fill("trusted"));
    assert.equal(authority.getCalls.length, 1);
    assert.equal(authority.updateCalls.length, 2);
    assert.equal(authority.deleteCalls.length, 1);
  });
});

test("DELETE uses only verifier-derived UID is idempotent and returns 204", async () => {
  const authority = recordingAuthority();

  await withServer({ authority }, async (baseUrl) => {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const result = await requestJson(baseUrl, PRIVACY_PATH, {
        method: "DELETE",
        authorization: "Bearer trusted",
        rawHeaders: {
          "x-firebase-uid": "attacker-uid",
          "x-user-id": "attacker-user"
        }
      });
      assert.equal(result.response.status, 204);
      assert.equal(result.text, "");
    }

    assert.deepEqual(authority.deleteCalls, [
      { firebaseUid: "trusted-uid" },
      { firebaseUid: "trusted-uid" }
    ]);
    assert.deepEqual(authority.getCalls, []);
    assert.deepEqual(authority.updateCalls, []);
  });
});

test("DELETE returns 204 only after authority deletion resolves", async () => {
  let releaseDelete;
  let deleteStarted;
  const started = new Promise((resolve) => {
    deleteStarted = resolve;
  });
  const authority = recordingAuthority({
    implementations: {
      delete() {
        deleteStarted();
        return new Promise((resolve) => {
          releaseDelete = resolve;
        });
      }
    }
  });

  await withServer({ authority }, async (baseUrl) => {
    let responseSettled = false;
    const pending = requestJson(baseUrl, PRIVACY_PATH, {
      method: "DELETE",
      authorization: "Bearer trusted"
    }).then((result) => {
      responseSettled = true;
      return result;
    });

    await started;
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(responseSettled, false);
    releaseDelete();

    const result = await pending;
    assert.equal(result.response.status, 204);
    assert.equal(result.text, "");
    assert.deepEqual(authority.deleteCalls, [{ firebaseUid: "trusted-uid" }]);
  });
});

test("all privacy methods require strict Firebase authentication", async () => {
  const unavailable = new Error("raw-auth-marker");
  const verifier = fakeVerifier({
    invalid: { status: "invalid" },
    unavailable
  });
  const authority = recordingAuthority();

  await withServer({ verifier, authority }, async (baseUrl) => {
    for (const method of ["GET", "PUT", "DELETE"]) {
      const result = await requestJson(baseUrl, PRIVACY_PATH, {
        method,
        body: method === "PUT"
          ? { standardRemoteAi: { granted: false } }
          : undefined
      });
      assertError(result, 401, "AUTH_REQUIRED");
      assert.equal(result.response.headers.get("www-authenticate"), "Bearer");
    }

    assertError(
      await requestJson(baseUrl, PRIVACY_PATH, {
        method: "PUT",
        authorization: "Bearer token extra",
        body: { standardRemoteAi: { granted: false } }
      }),
      401,
      "AUTH_REQUIRED"
    );
    assertError(
      await requestJson(baseUrl, PRIVACY_PATH, {
        method: "DELETE",
        authorizationValues: ["Bearer first", "Bearer second"]
      }),
      401,
      "AUTH_REQUIRED"
    );
    assertError(
      await requestJson(baseUrl, PRIVACY_PATH, {
        authorization: "Bearer invalid"
      }),
      401,
      "INVALID_SESSION"
    );
    const unavailableResult = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer unavailable"
    });
    assertError(
      unavailableResult,
      503,
      "AUTH_VERIFICATION_UNAVAILABLE"
    );
    assert.equal(JSON.stringify(unavailableResult.body).includes("raw-auth-marker"), false);
    assert.deepEqual(verifier.calls, ["invalid", "unavailable"]);
    assertNoAuthorityCalls(authority);
  });
});

test("privacy routes apply rate limiting before authentication", async () => {
  const verifier = authenticatedVerifier();
  const authority = recordingAuthority();
  const config = loadConfig({
    ...TEST_CONFIG,
    rateLimitMaxRequests: 1,
    rateLimitWindowMs: 60000
  });

  await withServer({ verifier, authority, config }, async (baseUrl) => {
    const first = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer trusted"
    });
    const second = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer trusted"
    });

    assert.equal(first.response.status, 200);
    assertError(second, 429, "RATE_LIMITED");
    assert.deepEqual(verifier.calls, ["trusted"]);
    assert.equal(authority.getCalls.length, 1);
  });
});

test("privacy routes reject all query selectors before authentication", async () => {
  const verifier = authenticatedVerifier();
  const authority = recordingAuthority();

  await withServer({ verifier, authority }, async (baseUrl) => {
    for (const selector of [
      "?",
      "?uid=attacker",
      "?userId=attacker",
      "?path=anything"
    ]) {
      const result = selector === "?"
        ? await requestRawTarget(baseUrl, `${PRIVACY_PATH}?`)
        : await requestJson(baseUrl, `${PRIVACY_PATH}${selector}`);
      assertError(
        result,
        400,
        "VALIDATION_ERROR"
      );
    }
    assertError(
      await requestJson(baseUrl, `${PRIVACY_PATH}/attacker-uid`),
      404,
      "NOT_FOUND"
    );
    assert.deepEqual(verifier.calls, []);
    assertNoAuthorityCalls(authority);
  });
});

test("GET and DELETE reject request bodies without consuming them", async () => {
  const verifier = authenticatedVerifier();
  const authority = recordingAuthority();

  await withServer({ verifier, authority }, async (baseUrl) => {
    const getResult = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer trusted",
      rawBody: JSON.stringify({ uid: "attacker-uid" })
    });
    const deleteResult = await requestJson(baseUrl, PRIVACY_PATH, {
      method: "DELETE",
      authorization: "Bearer trusted",
      rawHeaders: { "transfer-encoding": "chunked" },
      rawBody: JSON.stringify({ path: "attacker-path" })
    });

    assertError(getResult, 400, "VALIDATION_ERROR");
    assertError(deleteResult, 400, "VALIDATION_ERROR");
    assert.deepEqual(verifier.calls, ["trusted", "trusted"]);
    assertNoAuthorityCalls(authority);
  });
});

test("PUT rejects UID path schema timestamp policy and provider fields before authority", async () => {
  const authority = recordingAuthority();
  const rejectedFields = [
    ["uid", "attacker-uid"],
    ["firebaseUid", "attacker-uid"],
    ["userId", "attacker-user"],
    ["accountId", "attacker-account"],
    ["documentPath", "remote_ai_consents_v1/attacker-uid"],
    ["path", "remote_ai_consents_v1/attacker-uid"],
    ["collection", "attacker-collection"],
    ["schemaVersion", 1],
    ["decidedAt", "2026-09-14T00:00:00.000Z"],
    ["updatedAt", "2026-09-14T00:00:00.000Z"],
    ["privacyClass", "P0"],
    ["emailVerified", true],
    ["provider", "provider-marker"],
    ["commercialTier", "PRO"],
    ["revenueCat", { entitlement: "PRO" }]
  ];

  await withServer({ authority }, async (baseUrl) => {
    for (const [field, value] of rejectedFields) {
      const result = await requestJson(baseUrl, PRIVACY_PATH, {
        method: "PUT",
        authorization: "Bearer trusted",
        body: {
          standardRemoteAi: { granted: false },
          [field]: value
        }
      });
      assertError(result, 400, "VALIDATION_ERROR");
    }
    assert.deepEqual(authority.updateCalls, []);
  });
});

test("PUT rejects missing decisions unknown nested fields and non-boolean grants", async () => {
  const authority = recordingAuthority();
  const invalidBodies = [
    {},
    [],
    { standardRemoteAi: null },
    { standardRemoteAi: [] },
    { standardRemoteAi: {} },
    { standardRemoteAi: { granted: "true" } },
    { standardRemoteAi: { granted: 1 } },
    {
      standardRemoteAi: {
        granted: false,
        uid: "attacker-uid"
      }
    },
    {
      experimentalTraining: {
        granted: false,
        decidedAt: "2026-09-14T00:00:00.000Z"
      }
    },
    {
      standardRemoteAi: { granted: false },
      experimentalTraining: {
        granted: true,
        noticeVersion: "experimental-v1"
      }
    }
  ];

  await withServer({ authority }, async (baseUrl) => {
    for (const body of invalidBodies) {
      assertError(
        await requestJson(baseUrl, PRIVACY_PATH, {
          method: "PUT",
          authorization: "Bearer trusted",
          body
        }),
        400,
        "VALIDATION_ERROR"
      );
    }
    assert.deepEqual(authority.updateCalls, []);
  });
});

test("PUT applies exact noticeVersion syntax to grants and withdrawals", async () => {
  const authority = recordingAuthority();
  const invalidVersions = [
    null,
    1,
    false,
    {},
    [],
    " standard-v1",
    "standard-v1 ",
    "",
    `A${"x".repeat(128)}`,
    ".standard-v1",
    "_standard-v1",
    "-standard-v1",
    "standard v1",
    "standard/v1",
    "standard@v1"
  ];

  await withServer({ authority }, async (baseUrl) => {
    for (const noticeVersion of invalidVersions) {
      for (const granted of [true, false]) {
        assertError(
          await requestJson(baseUrl, PRIVACY_PATH, {
            method: "PUT",
            authorization: "Bearer trusted",
            body: {
              standardRemoteAi: { granted, noticeVersion }
            }
          }),
          400,
          "VALIDATION_ERROR"
        );
      }
    }
    assert.deepEqual(authority.updateCalls, []);

    for (const decision of [
      { granted: true, noticeVersion: "standard-v1" },
      { granted: false, noticeVersion: "standard-v0" },
      { granted: false }
    ]) {
      const result = await requestJson(baseUrl, PRIVACY_PATH, {
        method: "PUT",
        authorization: "Bearer trusted",
        body: { standardRemoteAi: decision }
      });
      assert.equal(result.response.status, 204);
    }
    assert.equal(authority.updateCalls.length, 3);
  });
});

test("grant notice mismatch is a redacted 409 and makes no durable mutation", async () => {
  const store = {
    mutateCalls: [],
    async mutate(input) {
      this.mutateCalls.push(input);
    }
  };
  const authority = new PrivacyConsentAuthority({
    store,
    policy: {
      standardRemoteAi: "standard-v1",
      experimentalTraining: "experimental-v1"
    }
  });

  await withServer({ authority }, async (baseUrl) => {
    const result = await requestJson(baseUrl, PRIVACY_PATH, {
      method: "PUT",
      authorization: "Bearer trusted",
      body: {
        standardRemoteAi: {
          granted: true,
          noticeVersion: "standard-v0"
        }
      }
    });

    assertError(result, 409, "CONSENT_NOTICE_VERSION_MISMATCH");
    assert.equal(
      result.body.error.message,
      "The privacy notice has changed. Fetch the current consent state and try again."
    );
    assert.deepEqual(store.mutateCalls, []);
    assert.equal(JSON.stringify(result.body).includes("standard-v0"), false);
  });
});

test("authority invalid unavailable and timeout failures use stable redacted envelopes", async () => {
  const cases = [
    [new ConsentAuthorityInvalidRecordError(), 503, "CONSENT_AUTHORITY_INVALID_RECORD"],
    [new ConsentAuthorityUnavailableError(), 503, "CONSENT_AUTHORITY_UNAVAILABLE"],
    [new ConsentAuthorityTimeoutError(), 504, "CONSENT_AUTHORITY_TIMEOUT"]
  ];

  for (const [failure, status, code] of cases) {
    const authority = recordingAuthority({ failures: { get: failure } });
    await withServer({ authority }, async (baseUrl) => {
      const result = await requestJson(baseUrl, PRIVACY_PATH, {
        authorization: "Bearer trusted"
      });
      assertError(result, status, code);
      const serialized = JSON.stringify(result.body);
      for (const marker of [
        "trusted-uid",
        "raw-firestore-marker",
        "remote_ai_consents_v1/trusted-uid"
      ]) {
        assert.equal(serialized.includes(marker), false);
      }
    });
  }
});

test("privacy response and captured logs contain no UID path token email raw error or fitness marker", async () => {
  const markers = [
    "trusted-uid",
    "attacker-uid",
    "remote_ai_consents_v1/trusted-uid",
    "token-marker",
    "person@example.com",
    "claims-marker",
    "raw-error-marker",
    "private-fitness-marker",
    "provider-marker"
  ];
  const badState = {
    ...NORMALIZED_NO_CONSENT_STATE,
    uid: markers[0],
    attackerUid: markers[1],
    documentPath: markers[2],
    token: markers[3],
    email: markers[4],
    claims: markers[5],
    rawError: markers[6],
    fitness: markers[7],
    provider: markers[8]
  };
  const authority = recordingAuthority({ state: badState });
  const logs = [];

  await withServer({
    authority,
    logger: (entry) => logs.push(entry)
  }, async (baseUrl) => {
    const result = await requestJson(baseUrl, PRIVACY_PATH, {
      authorization: "Bearer trusted",
      rawHeaders: { "x-firebase-uid": "attacker-uid" }
    });
    await new Promise((resolve) => setImmediate(resolve));

    assertError(result, 500, "INVALID_RESPONSE");
    assert.deepEqual(Object.keys(logs[0]).sort(), [
      "durationMs", "requestId", "route", "status"
    ]);
    const captured = JSON.stringify({ response: result.body, logs });
    for (const marker of markers) {
      assert.equal(captured.includes(marker), false);
    }
  });
});

test("health and deterministic mock AI routes do not call consent authority", async () => {
  const authority = recordingAuthority({
    failures: {
      get: new Error("consent authority must not be called"),
      put: new Error("consent authority must not be called"),
      delete: new Error("consent authority must not be called")
    }
  });
  const verifier = authenticatedVerifier({ emailVerified: true });

  await withServer({ verifier, authority }, async (baseUrl) => {
    const health = await requestJson(baseUrl, "/api/health");
    const coach = await requestJson(baseUrl, "/api/ai/coach", {
      method: "POST",
      authorization: "Bearer trusted",
      body: coachRequestBody()
    });

    assert.equal(health.response.status, 200);
    assert.equal(coach.response.status, 200);
    assert.equal(coach.body.mode, "mock");
    assertNoAuthorityCalls(authority);
  });
});

test("default consent authority construction stays lazy for health and mock AI", async () => {
  const verifier = authenticatedVerifier({ emailVerified: true });
  const server = createServer(createApp({
    config: TEST_CONFIG,
    logger: () => {},
    idTokenVerifier: verifier
  }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await requestJson(baseUrl, "/api/health");
    const coach = await requestJson(baseUrl, "/api/ai/coach", {
      method: "POST",
      authorization: "Bearer trusted",
      body: coachRequestBody()
    });
    assert.equal(health.response.status, 200);
    assert.equal(coach.response.status, 200);
    assert.equal(coach.body.mode, "mock");
  } finally {
    await new Promise((resolve, reject) =>
      server.close((error) => error ? reject(error) : resolve()));
  }
});

test("positive consent for unverified user does not bypass AI EMAIL_VERIFICATION_REQUIRED", async () => {
  const verifier = authenticatedVerifier({ emailVerified: false });
  const authority = recordingAuthority();

  await withServer({ verifier, authority }, async (baseUrl) => {
    const consent = await requestJson(baseUrl, PRIVACY_PATH, {
      method: "PUT",
      authorization: "Bearer trusted",
      body: {
        standardRemoteAi: {
          granted: true,
          noticeVersion: "standard-v1"
        }
      }
    });
    const ai = await requestJson(baseUrl, "/api/ai/coach", {
      method: "POST",
      authorization: "Bearer trusted",
      body: coachRequestBody()
    });

    assert.equal(consent.response.status, 204);
    assertError(ai, 403, "EMAIL_VERIFICATION_REQUIRED");
    assert.equal(authority.updateCalls.length, 1);
    assert.deepEqual(authority.getCalls, []);
  });
});
