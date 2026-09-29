import assert from "node:assert/strict";
import { test } from "node:test";
import { loadConfig } from "../src/config.js";
import {
  CommercialAuthorityConfigurationError,
  CommercialAuthorityInvalidResponseError,
  CommercialAuthorityRateLimitError,
  CommercialAuthorityTimeoutError,
  CommercialAuthorityUnauthorizedError,
  CommercialAuthorityUnavailableError
} from "../src/errors.js";
import {
  CommercialTier,
  RevenueCatCommercialAuthority,
  revenueCatCustomerIdForFirebaseUid
} from "../src/commercial/revenuecat_commercial_authority.js";

const TEST_CONFIG = Object.freeze({
  revenueCatV2SecretKey: "test-server-secret",
  revenueCatProjectId: "proj_fitdesi_test",
  revenueCatPlusEntitlementId: "entl_plus_internal",
  revenueCatProEntitlementId: "entl_pro_internal",
  revenueCatBoostEntitlementId: "entl_boost_internal",
  revenueCatTimeoutMs: 100
});

function response(status, body, headers = {}) {
  const normalizedHeaders = Object.fromEntries(
    Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value])
  );
  return {
    status,
    headers: {
      get(name) {
        return normalizedHeaders[name.toLowerCase()] ?? null;
      }
    },
    async json() {
      return body;
    }
  };
}

function activeEntitlements(entitlementIds = []) {
  return {
    object: "list",
    items: entitlementIds.map((entitlementId) => ({
      object: "customer.active_entitlement",
      entitlement_id: entitlementId,
      expires_at: 1_800_000_000_000
    })),
    next_page: null,
    url: "/v2/projects/project/customers/customer/active_entitlements"
  };
}

function recordingFetch(result) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return typeof result === "function" ? result(url, options) : result;
  };
  return { calls, fetchImpl };
}

function authorityWith(result, config = TEST_CONFIG) {
  const recorder = recordingFetch(result);
  return {
    authority: new RevenueCatCommercialAuthority({
      config,
      fetchImpl: recorder.fetchImpl
    }),
    calls: recorder.calls
  };
}

function assertCommercialError(error, {
  type,
  statusCode,
  code,
  retryAfter = null
}) {
  assert.ok(error instanceof type);
  assert.equal(error.statusCode, statusCode);
  assert.equal(error.code, code);
  assert.equal(
    error.publicMessage,
    "Commercial access verification is temporarily unavailable. Please try again."
  );
  assert.equal(error.headers["retry-after"] ?? null, retryAfter);
  return true;
}

test("Firebase UID deterministically maps to the exact fd_ RevenueCat customer identity", () => {
  assert.equal(
    revenueCatCustomerIdForFirebaseUid("FirebaseUid_A-123"),
    "fd_FirebaseUid_A-123"
  );
});

test("request-provided commercial identity and claims cannot override the Firebase UID mapping", async () => {
  const { authority, calls } = authorityWith(response(200, activeEntitlements()));

  const state = await authority.getCommercialState({
    firebaseUid: "trustedFirebaseUid",
    revenueCatCustomerId: "fd_attacker",
    appUserId: "fd_attacker",
    tier: "PRO",
    entitlements: ["entl_pro_internal"]
  });

  assert.deepEqual(state, { tier: CommercialTier.BASIC, boostActive: false });
  assert.equal(calls.length, 1);
  assert.equal(
    calls[0].url,
    "https://api.revenuecat.com/v2/projects/proj_fitdesi_test/customers/fd_trustedFirebaseUid/active_entitlements?limit=100"
  );
  assert.equal(calls[0].url.includes("attacker"), false);
});

test("RevenueCat request uses only the server project and encoded server-derived customer path", async () => {
  const config = { ...TEST_CONFIG, revenueCatProjectId: "proj/fitdesi" };
  const { authority, calls } = authorityWith(response(200, activeEntitlements()), config);

  await authority.getCommercialState({ firebaseUid: "uid/with?reserved" });

  assert.equal(
    calls[0].url,
    "https://api.revenuecat.com/v2/projects/proj%2Ffitdesi/customers/fd_uid%2Fwith%3Freserved/active_entitlements?limit=100"
  );
});

test("server secret is confined to outbound Bearer authorization and normalized output", async () => {
  const logs = [];
  const originalLog = console.log;
  console.log = (...values) => logs.push(values);
  try {
    const { authority, calls } = authorityWith(response(200, activeEntitlements()));
    const state = await authority.getCommercialState({ firebaseUid: "privateUid" });

    assert.deepEqual(state, { tier: CommercialTier.BASIC, boostActive: false });
    assert.deepEqual(calls[0].options.headers, {
      accept: "application/json",
      authorization: "Bearer test-server-secret"
    });
    assert.equal(calls[0].url.includes(TEST_CONFIG.revenueCatV2SecretKey), false);
    assert.equal(JSON.stringify(state).includes(TEST_CONFIG.revenueCatV2SecretKey), false);
    assert.equal(JSON.stringify(authority).includes(TEST_CONFIG.revenueCatV2SecretKey), false);
    assert.deepEqual(logs, []);
  } finally {
    console.log = originalLog;
  }
});

test("no active configured entitlement returns normalized BASIC without raw provider data", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    "entl_unrelated_internal"
  ])));

  const state = await authority.getCommercialState({ firebaseUid: "basicUid" });

  assert.deepEqual(state, { tier: CommercialTier.BASIC, boostActive: false });
  assert.deepEqual(Object.keys(state).sort(), ["boostActive", "tier"]);
});

test("active Plus entitlement returns PLUS", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatPlusEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "plusUid" }),
    { tier: CommercialTier.PLUS, boostActive: false }
  );
});

test("active Pro entitlement returns PRO", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatProEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "proUid" }),
    { tier: CommercialTier.PRO, boostActive: false }
  );
});

test("active Boost remains BASIC and sets boostActive separately", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatBoostEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "boostUid" }),
    { tier: CommercialTier.BASIC, boostActive: true }
  );
});

test("simultaneous Plus and Boost remains PLUS with Boost active", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatPlusEntitlementId,
    TEST_CONFIG.revenueCatBoostEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "plusBoostUid" }),
    { tier: CommercialTier.PLUS, boostActive: true }
  );
});

test("PRO wins permanent-tier precedence over PLUS", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatPlusEntitlementId,
    TEST_CONFIG.revenueCatProEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "tierPrecedenceUid" }),
    { tier: CommercialTier.PRO, boostActive: false }
  );
});

test("RevenueCat customer-not-found returns BASIC without creating a customer", async () => {
  const { authority, calls } = authorityWith(response(404, {
    object: "error",
    type: "resource_missing",
    message: "sensitive provider detail"
  }));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "newCustomerUid" }),
    { tier: CommercialTier.BASIC, boostActive: false }
  );
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.method, "GET");
  assert.equal(calls[0].url.includes("/actions/"), false);
});

test("missing RevenueCat server configuration fails closed before network access", async (t) => {
  for (const [missingKey, missingValue] of [
    ["revenueCatV2SecretKey", ""],
    ["revenueCatProjectId", ""],
    ["revenueCatPlusEntitlementId", ""],
    ["revenueCatProEntitlementId", ""],
    ["revenueCatBoostEntitlementId", ""],
    ["revenueCatPlusEntitlementId", "   "]
  ]) {
    await t.test(`${missingKey}=${JSON.stringify(missingValue)}`, async () => {
      const config = { ...TEST_CONFIG, [missingKey]: missingValue };
      const { authority, calls } = authorityWith(response(200, activeEntitlements()), config);

      await assert.rejects(
        authority.getCommercialState({ firebaseUid: "configuredUid" }),
        (error) => assertCommercialError(error, {
          type: CommercialAuthorityConfigurationError,
          statusCode: 503,
          code: "COMMERCIAL_AUTHORITY_NOT_CONFIGURED"
        })
      );
      assert.deepEqual(calls, []);
    });
  }
});

for (const [name, duplicateConfig] of [
  ["Plus and Pro", {
    revenueCatProEntitlementId: TEST_CONFIG.revenueCatPlusEntitlementId
  }],
  ["Plus and Boost", {
    revenueCatBoostEntitlementId: TEST_CONFIG.revenueCatPlusEntitlementId
  }],
  ["Pro and Boost", {
    revenueCatBoostEntitlementId: TEST_CONFIG.revenueCatProEntitlementId
  }]
]) {
  test(`${name} entitlement ID collision fails configuration closed before network access`, async () => {
    const config = { ...TEST_CONFIG, ...duplicateConfig };
    const { authority, calls } = authorityWith(response(200, activeEntitlements()), config);

    await assert.rejects(
      authority.getCommercialState({ firebaseUid: "collisionUid" }),
      (error) => assertCommercialError(error, {
        type: CommercialAuthorityConfigurationError,
        statusCode: 503,
        code: "COMMERCIAL_AUTHORITY_NOT_CONFIGURED"
      })
    );
    assert.deepEqual(calls, []);
  });
}

test("RevenueCat 401 and 403 fail closed as authority authentication failures", async (t) => {
  for (const status of [401, 403]) {
    await t.test(String(status), async () => {
      const { authority } = authorityWith(response(status, {
        type: "authorization_error",
        message: "secret provider details"
      }));

      await assert.rejects(
        authority.getCommercialState({ firebaseUid: "unauthorizedUid" }),
        (error) => assertCommercialError(error, {
          type: CommercialAuthorityUnauthorizedError,
          statusCode: 502,
          code: "COMMERCIAL_AUTHORITY_UNAUTHORIZED"
        })
      );
    });
  }
});

test("RevenueCat 429 fails closed with bounded retryable semantics", async () => {
  const { authority } = authorityWith(response(429, {
    type: "rate_limit_error",
    retryable: true,
    backoff_ms: 999_999_999
  }, { "retry-after": "999999" }));

  await assert.rejects(
    authority.getCommercialState({ firebaseUid: "rateLimitedUid" }),
    (error) => assertCommercialError(error, {
      type: CommercialAuthorityRateLimitError,
      statusCode: 429,
      code: "COMMERCIAL_AUTHORITY_RATE_LIMITED",
      retryAfter: "3600"
    })
  );
});

test("RevenueCat timeout aborts the single request and fails closed", async () => {
  const config = { ...TEST_CONFIG, revenueCatTimeoutMs: 10 };
  const recorder = recordingFetch((_url, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener("abort", () => {
      reject(new DOMException("sensitive timeout detail", "AbortError"));
    }, { once: true });
  }));
  const authority = new RevenueCatCommercialAuthority({
    config,
    fetchImpl: recorder.fetchImpl
  });

  await assert.rejects(
    authority.getCommercialState({ firebaseUid: "timeoutUid" }),
    (error) => assertCommercialError(error, {
      type: CommercialAuthorityTimeoutError,
      statusCode: 504,
      code: "COMMERCIAL_AUTHORITY_TIMEOUT"
    })
  );
  assert.equal(recorder.calls.length, 1);
  assert.equal(recorder.calls[0].options.signal.aborted, true);
});

test("RevenueCat 5xx responses fail closed without retries", async (t) => {
  for (const status of [500, 502, 503, 504]) {
    await t.test(String(status), async () => {
      const { authority, calls } = authorityWith(response(status, {
        type: "server_error",
        message: "sensitive upstream response"
      }));

      await assert.rejects(
        authority.getCommercialState({ firebaseUid: "unavailableUid" }),
        (error) => assertCommercialError(error, {
          type: CommercialAuthorityUnavailableError,
          statusCode: 503,
          code: "COMMERCIAL_AUTHORITY_UNAVAILABLE"
        })
      );
      assert.equal(calls.length, 1);
    });
  }
});

test("complete RevenueCat first page is accepted when next_page is null", async () => {
  const { authority } = authorityWith(response(200, activeEntitlements([
    TEST_CONFIG.revenueCatPlusEntitlementId
  ])));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "nullNextPageUid" }),
    { tier: CommercialTier.PLUS, boostActive: false }
  );
});

test("complete RevenueCat first page is accepted when next_page is omitted", async () => {
  const { next_page: _omitted, ...payloadWithoutNextPage } = activeEntitlements([
    TEST_CONFIG.revenueCatPlusEntitlementId
  ]);
  const { authority } = authorityWith(response(200, payloadWithoutNextPage));

  assert.deepEqual(
    await authority.getCommercialState({ firebaseUid: "omittedNextPageUid" }),
    { tier: CommercialTier.PLUS, boostActive: false }
  );
});

test("malformed, unexpected, or incomplete RevenueCat responses fail closed", async (t) => {
  const malformedBodies = [
    null,
    {},
    { object: "customer", items: [], next_page: null },
    { object: "list", items: "not-an-array", next_page: null },
    { object: "list", items: [null], next_page: null },
    { object: "list", items: [{ object: "wrong", entitlement_id: "entl" }], next_page: null },
    { object: "list", items: [{ object: "customer.active_entitlement" }], next_page: null },
    { ...activeEntitlements(), next_page: "/v2/projects/next-page" }
  ];
  for (const [index, body] of malformedBodies.entries()) {
    await t.test(String(index), async () => {
      const { authority } = authorityWith(response(200, body));

      await assert.rejects(
        authority.getCommercialState({ firebaseUid: "malformedUid" }),
        (error) => assertCommercialError(error, {
          type: CommercialAuthorityInvalidResponseError,
          statusCode: 502,
          code: "COMMERCIAL_AUTHORITY_INVALID_RESPONSE"
        })
      );
    });
  }
});

test("provider failures expose no secret, UID, derived identity, or raw response details", async () => {
  const sensitiveConfig = {
    ...TEST_CONFIG,
    revenueCatV2SecretKey: "sensitive-secret-key"
  };
  const firebaseUid = "sensitiveFirebaseUid";
  const derivedCustomerId = `fd_${firebaseUid}`;
  const rawProviderDetail = "sensitive raw RevenueCat response";
  const logs = [];
  const originalLog = console.log;
  console.log = (...values) => logs.push(values);
  try {
    const { authority } = authorityWith(response(403, {
      type: "authorization_error",
      message: rawProviderDetail,
      customer_id: derivedCustomerId
    }), sensitiveConfig);

    const error = await authority.getCommercialState({ firebaseUid })
      .then(() => null, (caught) => caught);
    const publicSurface = JSON.stringify({
      statusCode: error.statusCode,
      code: error.code,
      message: error.publicMessage,
      headers: error.headers,
      string: String(error)
    });
    for (const marker of [
      sensitiveConfig.revenueCatV2SecretKey,
      firebaseUid,
      derivedCustomerId,
      rawProviderDetail
    ]) {
      assert.equal(publicSurface.includes(marker), false, marker);
      assert.equal(JSON.stringify(logs).includes(marker), false, marker);
    }
    assert.deepEqual(logs, []);
  } finally {
    console.log = originalLog;
  }
});

test("commercial authority performs one read-only active-entitlements request", async () => {
  const { authority, calls } = authorityWith(response(200, activeEntitlements()));

  await authority.getCommercialState({ firebaseUid: "readOnlyUid" });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.method, "GET");
  assert.equal(calls[0].options.body, undefined);
  assert.match(calls[0].url, /\/active_entitlements\?limit=100$/);
  assert.equal(/grant|revoke|restore|transfer|delete|create|actions/.test(calls[0].url), false);
});

test("loadConfig reads only server-side RevenueCat placeholders and timeout", () => {
  const names = [
    "REVENUECAT_V2_SECRET_KEY",
    "REVENUECAT_PROJECT_ID",
    "REVENUECAT_PLUS_ENTITLEMENT_ID",
    "REVENUECAT_PRO_ENTITLEMENT_ID",
    "REVENUECAT_BOOST_ENTITLEMENT_ID",
    "REVENUECAT_TIMEOUT_MS"
  ];
  const previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    REVENUECAT_V2_SECRET_KEY: "environment-secret",
    REVENUECAT_PROJECT_ID: "proj_environment",
    REVENUECAT_PLUS_ENTITLEMENT_ID: "entl_environment_plus",
    REVENUECAT_PRO_ENTITLEMENT_ID: "entl_environment_pro",
    REVENUECAT_BOOST_ENTITLEMENT_ID: "entl_environment_boost",
    REVENUECAT_TIMEOUT_MS: "4500"
  });
  try {
    const config = loadConfig();
    assert.equal(config.revenueCatV2SecretKey, "environment-secret");
    assert.equal(config.revenueCatProjectId, "proj_environment");
    assert.equal(config.revenueCatPlusEntitlementId, "entl_environment_plus");
    assert.equal(config.revenueCatProEntitlementId, "entl_environment_pro");
    assert.equal(config.revenueCatBoostEntitlementId, "entl_environment_boost");
    assert.equal(config.revenueCatTimeoutMs, 4500);
  } finally {
    for (const name of names) {
      if (previous[name] === undefined) delete process.env[name];
      else process.env[name] = previous[name];
    }
  }
});
