import assert from "node:assert/strict";
import { test } from "node:test";

const TEST_HMAC_SECRET = "unit-test-material-not-for-production-0123456789";
const TEST_FIREBASE_PROJECT_ID = "fitdesi-ai";

async function identityModule() {
  return import("../src/remote_admission/account_identity.js");
}

async function idempotencyModule() {
  return import("../src/remote_admission/idempotency.js");
}

async function captureError(operation) {
  try {
    await operation();
  } catch (error) {
    return error;
  }
  assert.fail("Expected operation to fail closed.");
}

test("account-key derivation is deterministic, versioned, opaque, and redacted", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const firebaseUid = "firebase-uid-private-marker";

  const first = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });
  const second = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });
  const different = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "different-private-marker",
    hmacSecret: TEST_HMAC_SECRET
  });

  assert.equal(first, second);
  assert.notEqual(first, different);
  assert.match(first, /^acct_v1_[A-Za-z0-9_-]{43}$/);
  assert.equal(first.includes(firebaseUid), false);
  assert.equal(first.includes("fd_"), false);
  assert.equal(first, "acct_v1_0Ug9XtLXwPQb1y7CsTAhhuNBOrUvHE_Bytvhfa000oE");
});

test("existing internal HMAC purposes retain their byte-identical outputs", async () => {
  const { deriveServerHmac } = await identityModule();
  const expected = new Map([
    ["account-key", "kYLzPOaBQXk32M5XlixFoy2MiVE37dOk6YpIhMXdABg"],
    ["idempotency-key", "m7PcykqHfIzjxjyv4eYBtskXved78cbrRBm0dKBJlHI"],
    ["request-fingerprint", "9GY95kSUYOud8z8QVTqf5McVZNg7CX_6f8Hh2vGlM3U"]
  ]);

  for (const [purpose, output] of expected) {
    assert.equal(deriveServerHmac({
      hmacSecret: TEST_HMAC_SECRET,
      purpose,
      value: "stable-purpose-input"
    }), output);
  }
});

test("internal HMAC purpose rejects malformed and oversized identifiers without disclosure", async () => {
  const { deriveServerHmac } = await identityModule();
  const privateValue = "private-purpose-value";
  const invalidPurposes = [
    undefined,
    null,
    "",
    "Account-key",
    "-account-key",
    "account_key",
    "account:key",
    "account/key",
    "account.key",
    "account key",
    "account\0key",
    "account\nkey",
    "a".repeat(65)
  ];

  for (const purpose of invalidPurposes) {
    const error = await captureError(() => deriveServerHmac({
      hmacSecret: TEST_HMAC_SECRET,
      purpose,
      value: privateValue
    }));
    assert.equal(error.statusCode, 503);
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
    const serialized = JSON.stringify(error);
    assert.equal(serialized.includes(privateValue), false);
    assert.equal(serialized.includes(TEST_HMAC_SECRET), false);
    if (typeof purpose === "string" && purpose.length > 0) {
      assert.equal(serialized.includes(purpose), false);
    }
  }
});

test("account-key derivation binds the trusted Firebase project boundary", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const firebaseUid = "same-private-uid";
  const first = deriveOpaqueAccountKey({
    firebaseProjectId: "fitdesi-ai",
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });
  const repeated = deriveOpaqueAccountKey({
    firebaseProjectId: "fitdesi-ai",
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });
  const otherProject = deriveOpaqueAccountKey({
    firebaseProjectId: "fitdesi-ai-production",
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });

  assert.equal(first, repeated);
  assert.notEqual(first, otherProject);
  for (const forbidden of [firebaseUid, "fitdesi-ai", "fitdesi-ai-production", TEST_HMAC_SECRET]) {
    assert.equal(first.includes(forbidden), false);
    assert.equal(otherProject.includes(forbidden), false);
  }
});

test("account-key derivation preserves the existing 128-character Firebase UID boundary", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();

  assert.match(
    deriveOpaqueAccountKey({
      firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
      firebaseUid: "ü".repeat(128),
      hmacSecret: TEST_HMAC_SECRET
    }),
    /^acct_v1_[A-Za-z0-9_-]{43}$/
  );
  const error = await captureError(() => deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "a".repeat(129),
    hmacSecret: TEST_HMAC_SECRET
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
});

test("missing or invalid account-key configuration fails only at derivation", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();

  for (const hmacSecret of [undefined, "", "short", ` ${TEST_HMAC_SECRET}`]) {
    const error = await captureError(() => deriveOpaqueAccountKey({
      firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
      firebaseUid: "private-uid",
      hmacSecret
    }));
    assert.equal(error.statusCode, 503);
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
    assert.equal(
      error.publicMessage,
      "Remote admission is temporarily unavailable. Please use the local fallback."
    );
    assert.equal(JSON.stringify(error).includes("private-uid"), false);
    if (typeof hmacSecret === "string" && hmacSecret.length > 0) {
      assert.equal(JSON.stringify(error).includes(hmacSecret), false);
    }
  }
});

test("missing or invalid Firebase project identity fails remote derivation closed", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();

  for (const firebaseProjectId of [
    undefined,
    null,
    "",
    "   ",
    " leading-project",
    "project/with/path",
    "p".repeat(129)
  ]) {
    const error = await captureError(() => deriveOpaqueAccountKey({
      firebaseProjectId,
      firebaseUid: "private-uid",
      hmacSecret: TEST_HMAC_SECRET
    }));
    assert.equal(error.statusCode, 503);
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
    const serialized = JSON.stringify(error);
    assert.equal(serialized.includes("private-uid"), false);
    if (typeof firebaseProjectId === "string" && firebaseProjectId.length > 0) {
      assert.equal(serialized.includes(firebaseProjectId), false);
    }
    assert.equal(serialized.includes(TEST_HMAC_SECRET), false);
  }
});

test("Idempotency-Key is required and bounded to a strict opaque ASCII form", async () => {
  const {
    IDEMPOTENCY_KEY_MAX_LENGTH,
    IDEMPOTENCY_KEY_MIN_LENGTH,
    normalizeIdempotencyKey
  } = await idempotencyModule();

  assert.equal(IDEMPOTENCY_KEY_MIN_LENGTH, 16);
  assert.equal(IDEMPOTENCY_KEY_MAX_LENGTH, 128);
  assert.equal(normalizeIdempotencyKey("a".repeat(16)), "a".repeat(16));
  assert.equal(normalizeIdempotencyKey("Z".repeat(128)), "Z".repeat(128));
  assert.equal(
    normalizeIdempotencyKey("018f47bb-52c2-7b4a-a419-9c3a3c3123f2"),
    "018f47bb-52c2-7b4a-a419-9c3a3c3123f2"
  );

  for (const value of [undefined, null, ""]) {
    const error = await captureError(() => normalizeIdempotencyKey(value));
    assert.equal(error.statusCode, 400);
    assert.equal(error.code, "IDEMPOTENCY_REQUIRED");
  }

  for (const value of [
    "a".repeat(15),
    "a".repeat(129),
    " leading-space-key",
    "trailing-space-key ",
    "comma,key-is-bad",
    "unicode-key-should-fail-ß",
    ["duplicate-key-a", "duplicate-key-b"],
    1234567890123456
  ]) {
    const error = await captureError(() => normalizeIdempotencyKey(value));
    assert.equal(error.statusCode, 400);
    assert.equal(error.code, "IDEMPOTENCY_INVALID");
    assert.equal(JSON.stringify(error).includes(String(value)), false);
  }
});

test("same account, key, capability, and canonical request produce one request identity", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const { deriveRequestIdentity } = await idempotencyModule();
  const accountKey = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "private-user-marker",
    hmacSecret: TEST_HMAC_SECRET
  });
  const input = {
    accountKey,
    idempotencyKey: "same-logical-operation-0001",
    capability: "REMOTE_AI_COACH",
    validatedRequest: {
      goal: "strength",
      profile: { level: "beginner", equipment: ["bands", "mat"] }
    },
    hmacSecret: TEST_HMAC_SECRET
  };

  const first = deriveRequestIdentity(input);
  const second = deriveRequestIdentity({
    ...input,
    validatedRequest: {
      profile: { equipment: ["bands", "mat"], level: "beginner" },
      goal: "strength"
    }
  });

  assert.deepEqual(first, second);
  assert.deepEqual(Object.keys(first).sort(), ["fingerprint", "requestKey"]);
  assert.match(first.requestKey, /^idem_v1_[A-Za-z0-9_-]{43}$/);
  assert.match(first.fingerprint, /^req_v1_[A-Za-z0-9_-]{43}$/);
  assert.equal(Object.isFrozen(first), true);

  const differentCapability = deriveRequestIdentity({
    ...input,
    capability: "AI_PROGRESS_SUMMARIES"
  });
  assert.equal(differentCapability.requestKey, first.requestKey);
  assert.notEqual(differentCapability.fingerprint, first.fingerprint);

  const differentAccount = deriveRequestIdentity({
    ...input,
    accountKey: deriveOpaqueAccountKey({
      firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
      firebaseUid: "second-private-user-marker",
      hmacSecret: TEST_HMAC_SECRET
    })
  });
  assert.notEqual(differentAccount.requestKey, first.requestKey);
  assert.notEqual(differentAccount.fingerprint, first.fingerprint);
});

test("same idempotency key with a different request has a distinguishable fingerprint", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const { deriveRequestIdentity } = await idempotencyModule();
  const accountKey = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "private-user-marker",
    hmacSecret: TEST_HMAC_SECRET
  });
  const common = {
    accountKey,
    idempotencyKey: "reused-logical-operation-key",
    capability: "REMOTE_AI_COACH",
    hmacSecret: TEST_HMAC_SECRET
  };

  const first = deriveRequestIdentity({
    ...common,
    validatedRequest: { goal: "strength" }
  });
  const second = deriveRequestIdentity({
    ...common,
    validatedRequest: { goal: "mobility" }
  });

  assert.equal(first.requestKey, second.requestKey);
  assert.notEqual(first.fingerprint, second.fingerprint);
});

test("durable request identity contains no raw identity, key, or request content", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const { deriveRequestIdentity } = await idempotencyModule();
  const firebaseUid = "raw-firebase-uid-marker";
  const idempotencyKey = "raw-idempotency-marker-0001";
  const privateFitnessContext = "private-fitness-context-marker";
  const accountKey = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid,
    hmacSecret: TEST_HMAC_SECRET
  });

  const output = deriveRequestIdentity({
    accountKey,
    idempotencyKey,
    capability: "REMOTE_AI_COACH",
    validatedRequest: {
      context: privateFitnessContext,
      prompt: "must-not-persist",
      response: "must-not-persist"
    },
    hmacSecret: TEST_HMAC_SECRET
  });
  const serialized = JSON.stringify(output);

  for (const forbidden of [
    firebaseUid,
    `fd_${firebaseUid}`,
    idempotencyKey,
    privateFitnessContext,
    "must-not-persist",
    TEST_HMAC_SECRET
  ]) {
    assert.equal(serialized.includes(forbidden), false);
  }
  assert.equal("prompt" in output, false);
  assert.equal("response" in output, false);
});

test("non-canonical request values fail closed without exposing request content", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const { deriveRequestIdentity } = await idempotencyModule();
  const accountKey = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "private-user-marker",
    hmacSecret: TEST_HMAC_SECRET
  });

  for (const validatedRequest of [
    { invalid: undefined },
    { invalid: Number.NaN },
    { invalid: new Date(0) },
    { invalid: () => "private-function-marker" }
  ]) {
    const error = await captureError(() => deriveRequestIdentity({
      accountKey,
      idempotencyKey: "canonical-validation-key-01",
      capability: "REMOTE_AI_COACH",
      validatedRequest,
      hmacSecret: TEST_HMAC_SECRET
    }));
    assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
    assert.equal(JSON.stringify(error).includes("private-function-marker"), false);
  }
});

test("idempotency resolution permits only a new request identity to start", async () => {
  const { deriveOpaqueAccountKey } = await identityModule();
  const { deriveRequestIdentity, resolveIdempotencyRequest } = await idempotencyModule();
  const accountKey = deriveOpaqueAccountKey({
    firebaseProjectId: TEST_FIREBASE_PROJECT_ID,
    firebaseUid: "private-user-marker",
    hmacSecret: TEST_HMAC_SECRET
  });
  const requestIdentity = deriveRequestIdentity({
    accountKey,
    idempotencyKey: "logical-operation-key-0001",
    capability: "REMOTE_AI_COACH",
    validatedRequest: { goal: "strength" },
    hmacSecret: TEST_HMAC_SECRET
  });

  assert.deepEqual(
    resolveIdempotencyRequest({ existingRequest: null, requestIdentity }),
    { outcome: "NEW" }
  );

  for (const state of ["RESERVED", "DISPATCHED"]) {
    const error = await captureError(() => resolveIdempotencyRequest({
      existingRequest: { ...requestIdentity, state },
      requestIdentity
    }));
    assert.equal(error.statusCode, 409);
    assert.equal(error.code, "REMOTE_REQUEST_IN_PROGRESS");
  }

  for (const state of [
    "SUCCEEDED",
    "FAILED_PRE_DISPATCH",
    "FAILED_POST_DISPATCH",
    "EXPIRED"
  ]) {
    const error = await captureError(() => resolveIdempotencyRequest({
      existingRequest: { ...requestIdentity, state },
      requestIdentity
    }));
    assert.equal(error.statusCode, 409);
    assert.equal(error.code, "REMOTE_REQUEST_COMPLETED");
  }
});

test("same request key with a different fingerprint is an idempotency conflict", async () => {
  const { resolveIdempotencyRequest } = await idempotencyModule();
  const requestIdentity = {
    requestKey: `idem_v1_${"a".repeat(43)}`,
    fingerprint: `req_v1_${"b".repeat(43)}`
  };

  const error = await captureError(() => resolveIdempotencyRequest({
    existingRequest: {
      requestKey: requestIdentity.requestKey,
      fingerprint: `req_v1_${"c".repeat(43)}`,
      state: "RESERVED"
    },
    requestIdentity
  }));

  assert.equal(error.statusCode, 409);
  assert.equal(error.code, "IDEMPOTENCY_CONFLICT");
  assert.equal(JSON.stringify(error).includes(requestIdentity.requestKey), false);
  assert.equal(JSON.stringify(error).includes(requestIdentity.fingerprint), false);
});
