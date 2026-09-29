import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { loadConfig } from "../src/config.js";

async function contractsModule() {
  return import("../src/remote_admission/admission_contracts.js");
}

async function quotaModule() {
  return import("../src/remote_admission/quota_policy.js");
}

async function errorsModule() {
  return import("../src/errors.js");
}

async function captureError(operation) {
  try {
    await operation();
  } catch (error) {
    return error;
  }
  assert.fail("Expected operation to fail closed.");
}

test("admission state constants are exact and terminal states cannot transition", async () => {
  const { AdmissionState, canTransitionAdmissionState } = await contractsModule();

  assert.deepEqual(Object.values(AdmissionState), [
    "RESERVED",
    "DISPATCHED",
    "SUCCEEDED",
    "FAILED_PRE_DISPATCH",
    "FAILED_POST_DISPATCH",
    "EXPIRED"
  ]);
  assert.equal(canTransitionAdmissionState("RESERVED", "DISPATCHED"), true);
  assert.equal(canTransitionAdmissionState("RESERVED", "FAILED_PRE_DISPATCH"), true);
  assert.equal(canTransitionAdmissionState("RESERVED", "EXPIRED"), true);
  assert.equal(canTransitionAdmissionState("DISPATCHED", "SUCCEEDED"), true);
  assert.equal(canTransitionAdmissionState("DISPATCHED", "FAILED_POST_DISPATCH"), true);
  assert.equal(canTransitionAdmissionState("DISPATCHED", "EXPIRED"), true);

  for (const terminal of [
    "SUCCEEDED",
    "FAILED_PRE_DISPATCH",
    "FAILED_POST_DISPATCH",
    "EXPIRED"
  ]) {
    for (const next of Object.values(AdmissionState)) {
      assert.equal(canTransitionAdmissionState(terminal, next), false);
    }
  }
});

test("pre-dispatch failure and pre-dispatch expiry release without attempt use", async () => {
  const { admissionTransitionEffect } = await contractsModule();

  for (const toState of ["FAILED_PRE_DISPATCH", "EXPIRED"]) {
    assert.deepEqual(admissionTransitionEffect("RESERVED", toState), {
      successfulUse: "RELEASE",
      providerAttempt: "NONE",
      globalAttempt: "NONE",
      requiresLeaseExpiry: toState === "EXPIRED"
    });
  }
});

test("dispatch consumes attempt accounting and later settlement never erases it", async () => {
  const { admissionTransitionEffect } = await contractsModule();

  assert.deepEqual(admissionTransitionEffect("RESERVED", "DISPATCHED"), {
    successfulUse: "HOLD",
    providerAttempt: "CONSUME",
    globalAttempt: "CONSUME",
    requiresLeaseExpiry: false
  });
  assert.deepEqual(admissionTransitionEffect("DISPATCHED", "SUCCEEDED"), {
    successfulUse: "CONSUME",
    providerAttempt: "PRESERVE",
    globalAttempt: "PRESERVE",
    requiresLeaseExpiry: false
  });
  for (const toState of ["FAILED_POST_DISPATCH", "EXPIRED"]) {
    assert.deepEqual(admissionTransitionEffect("DISPATCHED", toState), {
      successfulUse: "RELEASE",
      providerAttempt: "PRESERVE",
      globalAttempt: "PRESERVE",
      requiresLeaseExpiry: toState === "EXPIRED"
    });
  }
});

test("invalid transitions fail closed and transition outputs have no content fields", async () => {
  const { admissionTransitionEffect } = await contractsModule();

  for (const transition of [
    ["RESERVED", "SUCCEEDED"],
    ["DISPATCHED", "FAILED_PRE_DISPATCH"],
    ["EXPIRED", "DISPATCHED"],
    ["UNKNOWN", "SUCCEEDED"]
  ]) {
    const error = await captureError(() => admissionTransitionEffect(...transition));
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
  }

  const output = admissionTransitionEffect("RESERVED", "DISPATCHED");
  assert.deepEqual(Object.keys(output).sort(), [
    "globalAttempt",
    "providerAttempt",
    "requiresLeaseExpiry",
    "successfulUse"
  ]);
  assert.equal("prompt" in output, false);
  assert.equal("response" in output, false);
  assert.equal("request" in output, false);
});

test("configured quota policy defaults are configured and fail closed", async () => {
  const { DEFAULT_QUOTA_POLICY } = await quotaModule();

  const error = await captureError(() => DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "AI_WORKOUT_GENERATION",
    tier: "PLUS"
  }));
  assert.equal(error.statusCode, 503);
  assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
  assert.equal(JSON.stringify(DEFAULT_QUOTA_POLICY), "{}");
  assert.equal("policyVersion" in DEFAULT_QUOTA_POLICY, false);
  assert.equal("globalProviderAttempts" in DEFAULT_QUOTA_POLICY, false);
});

test("stage12g3-remote-coach-v1 policy is enabled by default with correct limits", async () => {
  const { DEFAULT_QUOTA_POLICY } = await quotaModule();

  const plusPolicy = DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "REMOTE_AI_COACH",
    tier: "PLUS"
  });
  assert.equal(plusPolicy.capability, "REMOTE_AI_COACH");
  assert.equal(plusPolicy.tier, "PLUS");
  assert.deepEqual(plusPolicy.successfulUses, { limit: 3, windowSeconds: 86400 });
  assert.deepEqual(plusPolicy.providerAttempts, { limit: 9, windowSeconds: 86400 });
  assert.equal(plusPolicy.policyVersion, "stage12g3-remote-coach-v1");
  assert.deepEqual(plusPolicy.globalProviderAttempts, { limit: 1000, windowSeconds: 86400 });

  const proPolicy = DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "REMOTE_AI_COACH",
    tier: "PRO"
  });
  assert.equal(proPolicy.capability, "REMOTE_AI_COACH");
  assert.equal(proPolicy.tier, "PRO");
  assert.deepEqual(proPolicy.successfulUses, { limit: 12, windowSeconds: 86400 });
  assert.deepEqual(proPolicy.providerAttempts, { limit: 36, windowSeconds: 86400 });
  assert.equal(proPolicy.policyVersion, "stage12g3-remote-coach-v1");
  assert.deepEqual(proPolicy.globalProviderAttempts, { limit: 1000, windowSeconds: 86400 });

  assert.equal(Object.isFrozen(plusPolicy), true);
  assert.equal(Object.isFrozen(plusPolicy.successfulUses), true);
  assert.equal(Object.isFrozen(plusPolicy.providerAttempts), true);
  assert.equal(Object.isFrozen(plusPolicy.globalProviderAttempts), true);

  assert.equal(Object.isFrozen(proPolicy), true);
  assert.equal(Object.isFrozen(proPolicy.successfulUses), true);
  assert.equal(Object.isFrozen(proPolicy.providerAttempts), true);
  assert.equal(Object.isFrozen(proPolicy.globalProviderAttempts), true);

  const captureMutationAttempt = () => {
    const mutationResult = {};
    try {
      plusPolicy.successfulUses.limit = 999;
      mutationResult.attempted = true;
    } catch (e) {
      mutationResult.error = e;
    }
    return mutationResult;
  };

  const mutationResult = captureMutationAttempt();
  assert.equal(mutationResult.attempted !== true || mutationResult.error !== undefined, true);

  const unchangedPlus = DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "REMOTE_AI_COACH",
    tier: "PLUS"
  });
  assert.equal(unchangedPlus.successfulUses.limit, 3);
  assert.equal(unchangedPlus.providerAttempts.limit, 9);
  assert.equal(unchangedPlus.globalProviderAttempts.limit, 1000);

  const unchangedPro = DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "REMOTE_AI_COACH",
    tier: "PRO"
  });
  assert.equal(unchangedPro.successfulUses.limit, 12);
  assert.equal(unchangedPro.providerAttempts.limit, 36);
  assert.equal(unchangedPro.globalProviderAttempts.limit, 1000);
});

test("BASIC tier has no Remote Coach quota and fails closed", async () => {
  const { DEFAULT_QUOTA_POLICY } = await quotaModule();

  const error = await captureError(() => DEFAULT_QUOTA_POLICY.getPolicy({
    capability: "REMOTE_AI_COACH",
    tier: "BASIC"
  }));
  assert.equal(error.statusCode, 503);
  assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
});

test("production app graph remains unlaunched with empty capability configuration", () => {
  const appSource = readFileSync(new URL("../src/app.js", import.meta.url), "utf8");
  assert.match(
    appSource,
    /createRemoteCapabilityPolicy\(\s*\{ launchedCapabilities: \[\]\s*\}\s*\)/,
    "app.js must keep empty launched capability configuration"
  );
});

test("one versioned global quota applies independently of caller tier", async () => {
  const { createQuotaPolicy } = await quotaModule();
  const policy = createQuotaPolicy({
    policyVersion: "test-policy-v1",
    globalProviderAttempts: { limit: 20, windowSeconds: 60 },
    rules: [
      {
        capability: "REMOTE_AI_COACH",
        tier: "PLUS",
        successfulUses: { limit: 2, windowSeconds: 3600 },
        providerAttempts: { limit: 3, windowSeconds: 3600 }
      },
      {
        capability: "REMOTE_AI_COACH",
        tier: "PRO",
        successfulUses: { limit: 4, windowSeconds: 3600 },
        providerAttempts: { limit: 5, windowSeconds: 3600 }
      }
    ]
  });

  const plus = policy.getPolicy({ capability: "REMOTE_AI_COACH", tier: "PLUS" });
  const pro = policy.getPolicy({ capability: "REMOTE_AI_COACH", tier: "PRO" });
  assert.equal(plus.capability, "REMOTE_AI_COACH");
  assert.equal(plus.tier, "PLUS");
  assert.deepEqual(plus.successfulUses, { limit: 2, windowSeconds: 3600 });
  assert.deepEqual(plus.providerAttempts, { limit: 3, windowSeconds: 3600 });
  assert.deepEqual(plus.globalProviderAttempts, { limit: 20, windowSeconds: 60 });
  assert.deepEqual(pro.globalProviderAttempts, plus.globalProviderAttempts);
  assert.deepEqual(pro.successfulUses, { limit: 4, windowSeconds: 3600 });
  assert.equal(Object.isFrozen(plus), true);
  assert.equal(Object.isFrozen(plus.successfulUses), true);
  assert.equal(Object.isFrozen(plus.globalProviderAttempts), true);
});

test("invalid or duplicate quota rules fail closed", async () => {
  const { createQuotaPolicy } = await quotaModule();
  const validRule = {
    capability: "REMOTE_AI_COACH",
    tier: "PLUS",
    successfulUses: { limit: 2, windowSeconds: 3600 },
    providerAttempts: { limit: 3, windowSeconds: 3600 }
  };

  for (const rules of [
    [{ ...validRule, capability: "UNKNOWN" }],
    [{ ...validRule, tier: "VIP" }],
    [{ ...validRule, successfulUses: { limit: 0, windowSeconds: 3600 } }],
    [validRule, validRule]
  ]) {
    const error = await captureError(() => createQuotaPolicy({
      policyVersion: "test-policy-v1",
      globalProviderAttempts: { limit: 20, windowSeconds: 60 },
      rules
    }));
    assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
  }
});

test("usable quota policy requires a strict bounded server policyVersion", async () => {
  const { createQuotaPolicy } = await quotaModule();
  const options = {
    globalProviderAttempts: { limit: 20, windowSeconds: 60 },
    rules: [{
      capability: "REMOTE_AI_COACH",
      tier: "PLUS",
      successfulUses: { limit: 2, windowSeconds: 3600 },
      providerAttempts: { limit: 3, windowSeconds: 3600 }
    }]
  };

  for (const policyVersion of [undefined, null, "", "   ", "bad/version", "v".repeat(65)]) {
    const error = await captureError(() => createQuotaPolicy({
      ...options,
      policyVersion
    }));
    assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
  }
});

test("returned quota policy includes the exact injected policyVersion", async () => {
  const { createQuotaPolicy } = await quotaModule();
  const policy = createQuotaPolicy({
    policyVersion: "test-policy-v1",
    globalProviderAttempts: { limit: 20, windowSeconds: 60 },
    rules: [{
      capability: "REMOTE_AI_COACH",
      tier: "PLUS",
      successfulUses: { limit: 2, windowSeconds: 3600 },
      providerAttempts: { limit: 3, windowSeconds: 3600 }
    }]
  });

  assert.equal(
    policy.getPolicy({ capability: "REMOTE_AI_COACH", tier: "PLUS" }).policyVersion,
    "test-policy-v1"
  );
});

test("tier rules cannot define or conflict over global provider-attempt limits", async () => {
  const { createQuotaPolicy } = await quotaModule();
  const error = await captureError(() => createQuotaPolicy({
    policyVersion: "test-policy-v1",
    globalProviderAttempts: { limit: 20, windowSeconds: 60 },
    rules: [
      {
        capability: "REMOTE_AI_COACH",
        tier: "PLUS",
        successfulUses: { limit: 2, windowSeconds: 3600 },
        providerAttempts: { limit: 3, windowSeconds: 3600 },
        globalProviderAttempts: { limit: 100, windowSeconds: 60 }
      },
      {
        capability: "REMOTE_AI_COACH",
        tier: "PRO",
        successfulUses: { limit: 4, windowSeconds: 3600 },
        providerAttempts: { limit: 5, windowSeconds: 3600 },
        globalProviderAttempts: { limit: 200, windowSeconds: 60 }
      }
    ]
  }));

  assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
});

function quotaPolicyOptions() {
  return {
    policyVersion: "test-policy-v1",
    globalProviderAttempts: { limit: 20, windowSeconds: 60 },
    rules: [{
      capability: "REMOTE_AI_COACH",
      tier: "PLUS",
      successfulUses: { limit: 2, windowSeconds: 3600 },
      providerAttempts: { limit: 3, windowSeconds: 3600 }
    }]
  };
}

for (const bucketName of [
  "successfulUses",
  "providerAttempts",
  "globalProviderAttempts"
]) {
  test(`${bucketName} quota bucket rejects missing or extra fields`, async () => {
    const { createQuotaPolicy } = await quotaModule();
    const missing = quotaPolicyOptions();
    const extra = quotaPolicyOptions();
    const missingBucket = bucketName === "globalProviderAttempts"
      ? missing.globalProviderAttempts
      : missing.rules[0][bucketName];
    const extraBucket = bucketName === "globalProviderAttempts"
      ? extra.globalProviderAttempts
      : extra.rules[0][bucketName];
    delete missingBucket.windowSeconds;
    extraBucket.unapprovedField = 1;

    for (const options of [missing, extra]) {
      const error = await captureError(() => createQuotaPolicy(options));
      assert.equal(error.statusCode, 503);
      assert.equal(error.code, "QUOTA_POLICY_UNAVAILABLE");
    }
  });
}

test("Stage 12E public errors are bounded, stable, and redacted", async () => {
  const errors = await errorsModule();
  const cases = [
    [errors.RemoteCapabilityUnavailableError, 403, "REMOTE_CAPABILITY_UNAVAILABLE"],
    [errors.RemoteCapabilityNotLaunchedError, 403, "REMOTE_CAPABILITY_NOT_LAUNCHED"],
    [errors.QuotaPolicyUnavailableError, 503, "QUOTA_POLICY_UNAVAILABLE"],
    [errors.IdempotencyRequiredError, 400, "IDEMPOTENCY_REQUIRED"],
    [errors.IdempotencyInvalidError, 400, "IDEMPOTENCY_INVALID"],
    [errors.IdempotencyConflictError, 409, "IDEMPOTENCY_CONFLICT"],
    [errors.RemoteRequestInProgressError, 409, "REMOTE_REQUEST_IN_PROGRESS"],
    [errors.RemoteRequestCompletedError, 409, "REMOTE_REQUEST_COMPLETED"],
    [errors.RemoteAdmissionUnavailableError, 503, "REMOTE_ADMISSION_UNAVAILABLE"]
  ];

  for (const [ErrorType, statusCode, code] of cases) {
    const error = new ErrorType("raw-uid-or-key-must-be-ignored");
    assert.equal(error.statusCode, statusCode);
    assert.equal(error.code, code);
    assert.ok(error.publicMessage.length > 0 && error.publicMessage.length <= 100);
    const serialized = JSON.stringify(error);
    assert.equal(serialized.includes("raw-uid-or-key-must-be-ignored"), false);
    assert.equal(serialized.includes("firebase"), false);
    assert.equal(serialized.includes("fingerprint"), false);
    assert.equal(serialized.includes("Firestore"), false);
  }
});

test("empty admission HMAC configuration does not enable or break local defaults", () => {
  const previousSecret = process.env.REMOTE_ADMISSION_HMAC_SECRET;
  const previousRemote = process.env.REMOTE_AI_ENABLED;
  try {
    delete process.env.REMOTE_ADMISSION_HMAC_SECRET;
    delete process.env.REMOTE_AI_ENABLED;
    const config = loadConfig();
    assert.equal(config.remoteAdmissionHmacSecret, "");
    assert.equal(config.remoteAiEnabled, false);
    assert.equal(config.provider, "mock");
  } finally {
    if (previousSecret === undefined) delete process.env.REMOTE_ADMISSION_HMAC_SECRET;
    else process.env.REMOTE_ADMISSION_HMAC_SECRET = previousSecret;
    if (previousRemote === undefined) delete process.env.REMOTE_AI_ENABLED;
    else process.env.REMOTE_AI_ENABLED = previousRemote;
  }
});

test("remote admission lease defaults to 60 seconds independently of request timeout", () => {
  const previousLease = process.env.REMOTE_ADMISSION_LEASE_SECONDS;
  try {
    delete process.env.REMOTE_ADMISSION_LEASE_SECONDS;
    const shortRequest = loadConfig({
      requestTimeoutMs: 2000,
      fireworksTimeoutMs: 1000
    });
    const longRequest = loadConfig({
      requestTimeoutMs: 120000,
      fireworksTimeoutMs: 30000
    });

    assert.equal(shortRequest.remoteAdmissionLeaseSeconds, 60);
    assert.equal(longRequest.remoteAdmissionLeaseSeconds, 60);
  } finally {
    if (previousLease === undefined) delete process.env.REMOTE_ADMISSION_LEASE_SECONDS;
    else process.env.REMOTE_ADMISSION_LEASE_SECONDS = previousLease;
  }
});

test("remote admission lease rejects values below its infrastructure minimum", () => {
  const previousLease = process.env.REMOTE_ADMISSION_LEASE_SECONDS;
  try {
    process.env.REMOTE_ADMISSION_LEASE_SECONDS = "29";
    assert.throws(
      () => loadConfig(),
      /REMOTE_ADMISSION_LEASE_SECONDS must be an integer between 30 and 600/
    );
  } finally {
    if (previousLease === undefined) delete process.env.REMOTE_ADMISSION_LEASE_SECONDS;
    else process.env.REMOTE_ADMISSION_LEASE_SECONDS = previousLease;
  }
});

test("remote admission lease requires a fifteen-second provider timeout margin", () => {
  assert.throws(
    () => loadConfig({
      remoteAdmissionLeaseSeconds: 30,
      fireworksTimeoutMs: 16000,
      requestTimeoutMs: 17000
    }),
    /REMOTE_ADMISSION_LEASE_SECONDS must exceed FIREWORKS_TIMEOUT_MS by at least 15000ms/
  );
});

test("remote admission lease accepts the exact provider timeout margin boundary", () => {
  const previousLease = process.env.REMOTE_ADMISSION_LEASE_SECONDS;
  try {
    process.env.REMOTE_ADMISSION_LEASE_SECONDS = "30";
    const config = loadConfig({
      fireworksTimeoutMs: 15000,
      requestTimeoutMs: 16000
    });

    assert.equal(config.remoteAdmissionLeaseSeconds, 30);
    assert.equal(config.fireworksTimeoutMs, 15000);
  } finally {
    if (previousLease === undefined) delete process.env.REMOTE_ADMISSION_LEASE_SECONDS;
    else process.env.REMOTE_ADMISSION_LEASE_SECONDS = previousLease;
  }
});

test("default Stage 12E store uses dedicated lease configuration", () => {
  const appSource = readFileSync(new URL("../src/app.js", import.meta.url), "utf8");

  assert.match(
    appSource,
    /leaseDurationSeconds:\s*config\.remoteAdmissionLeaseSeconds/
  );
  assert.doesNotMatch(
    appSource,
    /leaseDurationSeconds:\s*Math\.ceil\(config\.requestTimeoutMs\s*\/\s*1000\)/
  );
});
