import assert from "node:assert/strict";
import { test } from "node:test";
import { Timestamp } from "firebase-admin/firestore";
import { loadConfig } from "../src/config.js";
import {
  RemoteAccountQuotaExhaustedError,
  RemoteAdmissionInvalidRecordError,
  RemoteAdmissionPersistenceTimeoutError,
  RemoteAdmissionPersistenceUnavailableError,
  RemoteGlobalBudgetUnavailableError
} from "../src/errors.js";
import { DEFAULT_QUOTA_POLICY } from "../src/remote_admission/quota_policy.js";
import { FakeTransactionalFirestore } from "./support/fake_transactional_firestore.js";

const REQUEST_COLLECTION = "remote_ai_requests_v1";
const ACCOUNT_COLLECTION = "remote_ai_account_quota_v1";
const GLOBAL_COLLECTION = "remote_ai_global_quota_v1";
const PROJECT_ID = "fitdesi-ai";
const BASE_SECONDS = 2_000_000_000;

async function storeModule() {
  return import("../src/remote_admission/firestore_remote_admission_store.js");
}

class TestClock {
  constructor(seconds = BASE_SECONDS) {
    this.seconds = seconds;
  }

  now = () => new Timestamp(this.seconds, 0);

  advance(seconds) {
    this.seconds += seconds;
  }
}

function accountKey(character = "a") {
  return `acct_v1_${character.repeat(43)}`;
}

function requestIdentity(character = "a", fingerprintCharacter = character) {
  return {
    requestKey: `idem_v1_${character.repeat(43)}`,
    fingerprint: `req_v1_${fingerprintCharacter.repeat(43)}`
  };
}

function indexedRequestIdentity(index) {
  const suffix = index.toString(36);
  return {
    requestKey: `idem_v1_${"k".repeat(43 - suffix.length)}${suffix}`,
    fingerprint: `req_v1_${"f".repeat(43 - suffix.length)}${suffix}`
  };
}

function policy({
  capability = "REMOTE_AI_COACH",
  tier = "PLUS",
  policyVersion = "test-policy-v1",
  successfulLimit = 2,
  successfulWindowSeconds = 3600,
  providerLimit = 3,
  providerWindowSeconds = 300,
  globalLimit = 10,
  globalWindowSeconds = 600
} = {}) {
  return {
    capability,
    tier,
    policyVersion,
    successfulUses: {
      limit: successfulLimit,
      windowSeconds: successfulWindowSeconds
    },
    providerAttempts: {
      limit: providerLimit,
      windowSeconds: providerWindowSeconds
    },
    globalProviderAttempts: {
      limit: globalLimit,
      windowSeconds: globalWindowSeconds
    }
  };
}

async function fixture({ leaseDurationSeconds = 30 } = {}) {
  const { FirestoreRemoteAdmissionStore } = await storeModule();
  const firestore = new FakeTransactionalFirestore();
  const clock = new TestClock();
  const store = new FirestoreRemoteAdmissionStore({
    projectId: PROJECT_ID,
    firestore,
    clock: clock.now,
    leaseDurationSeconds
  });
  return { clock, firestore, store };
}

async function captureRejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  assert.fail("Expected operation to reject.");
}

function reservationInput({
  account = accountKey(),
  identity = requestIdentity(),
  capability = "REMOTE_AI_COACH",
  admittedPolicy = policy({ capability })
} = {}) {
  return {
    accountKey: account,
    requestIdentity: identity,
    capability,
    policy: admittedPolicy
  };
}

function bucket(firestore, bucketType) {
  return firestore.documents(ACCOUNT_COLLECTION)
    .find(({ data }) => data.bucketType === bucketType);
}

function requestRecord(firestore, identity = requestIdentity()) {
  return firestore.getDocument(REQUEST_COLLECTION, identity.requestKey);
}

function durableSnapshot(firestore) {
  return {
    requests: firestore.documents(REQUEST_COLLECTION),
    accountQuota: firestore.documents(ACCOUNT_COLLECTION),
    globalQuota: firestore.documents(GLOBAL_COLLECTION)
  };
}

async function seedActiveHolds({ firestore, store, count }) {
  const originalIdentity = requestIdentity();
  await store.reserve(reservationInput({
    identity: originalIdentity,
    admittedPolicy: policy({ successfulLimit: 200 })
  }));
  const originalRequest = requestRecord(firestore, originalIdentity);
  const success = bucket(firestore, "successful-use");
  const activeHolds = {};
  for (let index = 0; index < count; index += 1) {
    const identity = indexedRequestIdentity(index);
    const request = {
      ...originalRequest,
      fingerprint: identity.fingerprint
    };
    firestore.setDocument(REQUEST_COLLECTION, identity.requestKey, request);
    activeHolds[identity.requestKey] = request.leaseExpiresAt;
  }
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, {
    ...success.data,
    activeHolds
  });
  return success.id;
}

test("clean first reservation creates one RESERVED request and successful-use hold", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();

  assert.deepEqual(await store.reserve(reservationInput({ identity })), {
    state: "RESERVED"
  });
  assert.equal(firestore.documents(REQUEST_COLLECTION).length, 1);
  assert.equal(requestRecord(firestore, identity).state, "RESERVED");
  assert.deepEqual(Object.keys(bucket(firestore, "successful-use").data.activeHolds), [
    identity.requestKey
  ]);
});

test("request document ID and strict record contain operational metadata only", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));

  const documents = firestore.documents(REQUEST_COLLECTION);
  assert.equal(documents[0].id, identity.requestKey);
  assert.deepEqual(Object.keys(documents[0].data).sort(), [
    "accountKey",
    "capability",
    "createdAt",
    "fingerprint",
    "globalAttempt",
    "leaseExpiresAt",
    "policyVersion",
    "providerAttempt",
    "schemaVersion",
    "state",
    "successfulUse",
    "updatedAt"
  ]);
  assert.deepEqual(Object.keys(documents[0].data.successfulUse).sort(), [
    "limit",
    "windowEnd",
    "windowSeconds",
    "windowStart"
  ]);
  assert.deepEqual(Object.keys(documents[0].data.providerAttempt).sort(), [
    "limit",
    "windowSeconds"
  ]);
  assert.deepEqual(Object.keys(documents[0].data.globalAttempt).sort(), [
    "limit",
    "windowSeconds"
  ]);
});

test("raw identity idempotency and fitness content are never persisted", async () => {
  const { firestore, store } = await fixture();
  const rawValues = [
    "raw-firebase-uid",
    "fitdesi-ai",
    "raw-idempotency-key",
    "private-prompt",
    "private-response",
    "private@example.com",
    "bearer-token",
    "device-installation-id"
  ];
  await store.reserve({
    ...reservationInput(),
    firebaseUid: rawValues[0],
    firebaseProjectId: rawValues[1],
    idempotencyKey: rawValues[2],
    prompt: rawValues[3],
    response: rawValues[4],
    email: rawValues[5],
    authToken: rawValues[6],
    installationId: rawValues[7]
  });

  const serialized = JSON.stringify([
    ...firestore.documents(REQUEST_COLLECTION),
    ...firestore.documents(ACCOUNT_COLLECTION),
    ...firestore.documents(GLOBAL_COLLECTION)
  ]);
  for (const rawValue of rawValues) assert.equal(serialized.includes(rawValue), false);
  assert.equal(serialized.includes("fd_"), false);
});

test("caller-supplied time fields cannot influence server windows or lease", async () => {
  const { firestore, store } = await fixture();
  await store.reserve({
    ...reservationInput(),
    currentTime: new Timestamp(1, 0),
    windowStart: 0,
    windowEnd: 1,
    leaseExpiresAt: new Timestamp(2, 0),
    resetTime: new Timestamp(3, 0)
  });

  const record = requestRecord(firestore);
  assert.equal(record.createdAt.seconds, BASE_SECONDS);
  assert.equal(record.leaseExpiresAt.seconds, BASE_SECONDS + 30);
  assert.notEqual(record.successfulUse.windowStart, 0);
});

test("same active request identity fails as already in progress", async () => {
  const { store } = await fixture();
  const input = reservationInput();
  await store.reserve(input);

  const error = await captureRejection(store.reserve(input));
  assert.equal(error.code, "REMOTE_REQUEST_IN_PROGRESS");
});

test("same request key with a different fingerprint is an idempotency conflict", async () => {
  const { store } = await fixture();
  await store.reserve(reservationInput());

  const error = await captureRejection(store.reserve(reservationInput({
    identity: requestIdentity("a", "b")
  })));
  assert.equal(error.code, "IDEMPOTENCY_CONFLICT");
});

test("same terminal request identity fails as already completed", async () => {
  const { store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  await store.transition({ requestIdentity: identity, toState: "SUCCEEDED" });

  const error = await captureRejection(store.reserve(reservationInput({ identity })));
  assert.equal(error.code, "REMOTE_REQUEST_COMPLETED");
});

test("two concurrent reservations competing for limit one admit exactly one", async () => {
  const { store } = await fixture();
  const admittedPolicy = policy({ successfulLimit: 1 });
  const results = await Promise.allSettled([
    store.reserve(reservationInput({ identity: requestIdentity("a"), admittedPolicy })),
    store.reserve(reservationInput({ identity: requestIdentity("b"), admittedPolicy }))
  ]);

  assert.equal(results.filter(({ status }) => status === "fulfilled").length, 1);
  const rejection = results.find(({ status }) => status === "rejected");
  assert.equal(rejection.reason.code, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED");
});

test("commercial tier is absent from per-account usage bucket identity and records", async () => {
  const { firestore, store } = await fixture();
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({
    identity: first,
    admittedPolicy: policy({ tier: "PLUS" })
  }));
  await store.transition({ requestIdentity: first, toState: "FAILED_PRE_DISPATCH" });
  await store.reserve(reservationInput({
    identity: second,
    admittedPolicy: policy({ tier: "PRO" })
  }));

  const successBuckets = firestore.documents(ACCOUNT_COLLECTION)
    .filter(({ data }) => data.bucketType === "successful-use");
  assert.equal(successBuckets.length, 1);
  assert.equal("tier" in successBuckets[0].data, false);
  assert.equal(JSON.stringify(successBuckets).includes("PLUS"), false);
  assert.equal(JSON.stringify(successBuckets).includes("PRO"), false);
});

test("tier changes cannot reset consumed account usage", async () => {
  const { firestore, store } = await fixture();
  const first = requestIdentity("a");
  await store.reserve(reservationInput({
    identity: first,
    admittedPolicy: policy({ successfulLimit: 1, tier: "PLUS" })
  }));
  await store.transition({ requestIdentity: first, toState: "DISPATCHED" });
  await store.transition({ requestIdentity: first, toState: "SUCCEEDED" });

  await store.reserve(reservationInput({
    identity: requestIdentity("b"),
    admittedPolicy: policy({ successfulLimit: 2, tier: "PRO" })
  }));
  const error = await captureRejection(store.reserve(reservationInput({
    identity: requestIdentity("c"),
    admittedPolicy: policy({ successfulLimit: 2, tier: "PRO" })
  })));
  assert.equal(error.code, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED");
  assert.equal(bucket(firestore, "successful-use").data.consumed, 1);
  assert.equal(Object.keys(bucket(firestore, "successful-use").data.activeHolds).length, 1);
});

test("successful-use and account-attempt windows are computed independently", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({
    identity,
    admittedPolicy: policy({
      successfulWindowSeconds: 60,
      providerWindowSeconds: 300
    })
  }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const record = requestRecord(firestore);
  const accountAttempt = bucket(firestore, "provider-attempt").data;

  assert.equal(record.successfulUse.windowSeconds, 60);
  assert.equal(record.successfulUse.windowStart, Math.floor(BASE_SECONDS / 60) * 60);
  assert.equal(record.providerAttempt.windowSeconds, 300);
  assert.equal(accountAttempt.windowStart, Math.floor(BASE_SECONDS / 300) * 300);
  assert.notEqual(accountAttempt.windowStart, record.successfulUse.windowStart);
});

test("global-attempt window may differ from account-attempt window", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({
    identity,
    admittedPolicy: policy({ providerWindowSeconds: 300, globalWindowSeconds: 700 })
  }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const record = requestRecord(firestore);
  const accountAttempt = bucket(firestore, "provider-attempt").data;
  const globalAttempt = firestore.documents(GLOBAL_COLLECTION)[0].data;

  assert.equal(record.providerAttempt.windowSeconds, 300);
  assert.equal(record.globalAttempt.windowSeconds, 700);
  assert.equal(accountAttempt.windowStart, Math.floor(BASE_SECONDS / 300) * 300);
  assert.equal(globalAttempt.windowStart, Math.floor(BASE_SECONDS / 700) * 700);
  assert.notEqual(globalAttempt.windowStart, accountAttempt.windowStart);
});

test("dispatch atomically consumes account and global provider attempts", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));

  assert.deepEqual(await store.transition({ requestIdentity: identity, toState: "DISPATCHED" }), {
    state: "DISPATCHED"
  });
  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 1);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
  assert.equal(requestRecord(firestore, identity).state, "DISPATCHED");
});

test("recordProviderAttempt charges every authorized provider request without a second hold", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));

  assert.deepEqual(await store.recordProviderAttempt({ requestIdentity: identity }), {
    state: "DISPATCHED"
  });
  assert.deepEqual(await store.recordProviderAttempt({ requestIdentity: identity }), {
    state: "DISPATCHED"
  });

  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 2);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 2);
  assert.equal(Object.keys(bucket(firestore, "successful-use").data.activeHolds).length, 1);
  assert.equal(requestRecord(firestore, identity).state, "DISPATCHED");
});

test("generic transition cannot weaken the state machine with DISPATCHED to DISPATCHED", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "DISPATCHED"
  }));

  assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 1);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
});

test("provider retry crossing attempt windows charges new account and global buckets", async () => {
  const { clock, firestore, store } = await fixture({ leaseDurationSeconds: 30 });
  const identity = requestIdentity();
  const admittedPolicy = policy({
    providerWindowSeconds: 10,
    globalWindowSeconds: 20
  });
  await store.reserve(reservationInput({ identity, admittedPolicy }));
  await store.recordProviderAttempt({ requestIdentity: identity });
  clock.advance(21);

  await store.recordProviderAttempt({ requestIdentity: identity });

  const accountAttempts = firestore.documents(ACCOUNT_COLLECTION)
    .filter(({ data }) => data.bucketType === "provider-attempt")
    .map(({ data }) => [data.windowStart, data.consumed]);
  const globalAttempts = firestore.documents(GLOBAL_COLLECTION)
    .map(({ data }) => [data.windowStart, data.consumed]);
  assert.deepEqual(accountAttempts, [[BASE_SECONDS, 1], [BASE_SECONDS + 20, 1]]);
  assert.deepEqual(globalAttempts, [[BASE_SECONDS, 1], [BASE_SECONDS + 20, 1]]);
  assert.equal(Object.keys(bucket(firestore, "successful-use").data.activeHolds).length, 1);
});

test("dispatch rejects a missing valid-lease success hold as corrupt without attempts", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  const success = bucket(firestore, "successful-use");
  success.data.activeHolds = {};
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, success.data);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "DISPATCHED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.equal(firestore.documents(ACCOUNT_COLLECTION)
    .filter(({ data }) => data.bucketType === "provider-attempt").length, 0);
  assert.equal(firestore.documents(GLOBAL_COLLECTION).length, 0);
});

test("per-account attempt exhaustion consumes neither account nor global again", async () => {
  const { firestore, store } = await fixture();
  const admittedPolicy = policy({ providerLimit: 1 });
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({ identity: first, admittedPolicy }));
  await store.reserve(reservationInput({ identity: second, admittedPolicy }));
  await store.transition({ requestIdentity: first, toState: "DISPATCHED" });

  const error = await captureRejection(store.transition({
    requestIdentity: second,
    toState: "DISPATCHED"
  }));
  assert.equal(error.code, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED");
  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 1);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
});

test("global limit one admits one dispatch across two different accounts", async () => {
  const { firestore, store } = await fixture();
  const admittedPolicy = policy({ globalLimit: 1 });
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({ account: accountKey("a"), identity: first, admittedPolicy }));
  await store.reserve(reservationInput({ account: accountKey("b"), identity: second, admittedPolicy }));

  const results = await Promise.allSettled([
    store.transition({ requestIdentity: first, toState: "DISPATCHED" }),
    store.transition({ requestIdentity: second, toState: "DISPATCHED" })
  ]);
  assert.equal(results.filter(({ status }) => status === "fulfilled").length, 1);
  assert.equal(results.find(({ status }) => status === "rejected").reason.code,
    "REMOTE_GLOBAL_BUDGET_UNAVAILABLE");
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
  assert.equal(firestore.documents(ACCOUNT_COLLECTION)
    .filter(({ data }) => data.bucketType === "provider-attempt").length, 1);
});

test("global limit one admits one dispatch across two different capabilities", async () => {
  const { firestore, store } = await fixture();
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({
    account: accountKey("a"),
    identity: first,
    capability: "REMOTE_AI_COACH",
    admittedPolicy: policy({ capability: "REMOTE_AI_COACH", globalLimit: 1 })
  }));
  await store.reserve(reservationInput({
    account: accountKey("b"),
    identity: second,
    capability: "AI_WORKOUT_GENERATION",
    admittedPolicy: policy({ capability: "AI_WORKOUT_GENERATION", globalLimit: 1 })
  }));

  const results = await Promise.allSettled([
    store.transition({ requestIdentity: first, toState: "DISPATCHED" }),
    store.transition({ requestIdentity: second, toState: "DISPATCHED" })
  ]);
  assert.equal(results.filter(({ status }) => status === "fulfilled").length, 1);
  assert.equal(results.find(({ status }) => status === "rejected").reason.code,
    "REMOTE_GLOBAL_BUDGET_UNAVAILABLE");
  assert.equal(firestore.documents(GLOBAL_COLLECTION).length, 1);
});

test("global bucket is shared across commercial tiers", async () => {
  const { firestore, store } = await fixture();
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({
    account: accountKey("a"),
    identity: first,
    admittedPolicy: policy({ tier: "PLUS", globalLimit: 1 })
  }));
  await store.reserve(reservationInput({
    account: accountKey("b"),
    identity: second,
    admittedPolicy: policy({ tier: "PRO", globalLimit: 1 })
  }));
  await store.transition({ requestIdentity: first, toState: "DISPATCHED" });

  const error = await captureRejection(store.transition({
    requestIdentity: second,
    toState: "DISPATCHED"
  }));
  assert.equal(error.code, "REMOTE_GLOBAL_BUDGET_UNAVAILABLE");
  assert.equal(firestore.documents(GLOBAL_COLLECTION).length, 1);
  assert.equal("tier" in firestore.documents(GLOBAL_COLLECTION)[0].data, false);
});

test("pre-dispatch failure releases the successful-use hold", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "FAILED_PRE_DISPATCH" });

  const success = bucket(firestore, "successful-use").data;
  assert.deepEqual(success.activeHolds, {});
  assert.equal(success.consumed, 0);
  assert.equal(requestRecord(firestore, identity).state, "FAILED_PRE_DISPATCH");
});

test("post-dispatch failure releases success hold and preserves attempts", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  await store.transition({ requestIdentity: identity, toState: "FAILED_POST_DISPATCH" });

  assert.deepEqual(bucket(firestore, "successful-use").data.activeHolds, {});
  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 1);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
});

test("success converts the active hold into one consumed successful use", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  await store.transition({ requestIdentity: identity, toState: "SUCCEEDED" });

  const success = bucket(firestore, "successful-use").data;
  assert.deepEqual(success.activeHolds, {});
  assert.equal(success.consumed, 1);
  assert.equal(requestRecord(firestore, identity).state, "SUCCEEDED");
});

test("DISPATCHED to SUCCEEDED fails closed when account attempt accounting is missing", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const accountAttempt = bucket(firestore, "provider-attempt");
  firestore.deleteDocument(ACCOUNT_COLLECTION, accountAttempt.id);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "SUCCEEDED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("DISPATCHED to SUCCEEDED fails closed when global attempt accounting is missing", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const globalAttempt = firestore.documents(GLOBAL_COLLECTION)[0];
  firestore.deleteDocument(GLOBAL_COLLECTION, globalAttempt.id);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "SUCCEEDED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("DISPATCHED to FAILED_POST_DISPATCH fails closed when attempt accounting is missing", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const accountAttempt = bucket(firestore, "provider-attempt");
  firestore.deleteDocument(ACCOUNT_COLLECTION, accountAttempt.id);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "FAILED_POST_DISPATCH"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("valid dispatch accounting remains unchanged through terminal transitions", async () => {
  for (const [index, toState] of ["SUCCEEDED", "FAILED_POST_DISPATCH", "EXPIRED"].entries()) {
    const { clock, firestore, store } = await fixture();
    const identity = indexedRequestIdentity(300 + index);
    await store.reserve(reservationInput({ identity }));
    await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
    const accountAttempt = bucket(firestore, "provider-attempt");
    const globalAttempt = firestore.documents(GLOBAL_COLLECTION)[0];
    const accountBefore = accountAttempt.data;
    const globalBefore = globalAttempt.data;
    if (toState === "EXPIRED") clock.advance(701);

    await store.transition({ requestIdentity: identity, toState });

    assert.deepEqual(firestore.getDocument(ACCOUNT_COLLECTION, accountAttempt.id), accountBefore);
    assert.deepEqual(firestore.getDocument(GLOBAL_COLLECTION, globalAttempt.id), globalBefore);
  }
});

test("expired RESERVED hold is reclaimed by later transactional activity", async () => {
  const { clock, firestore, store } = await fixture();
  const stale = requestIdentity("a");
  const current = requestIdentity("b");
  await store.reserve(reservationInput({ identity: stale }));
  clock.advance(31);
  await store.reserve(reservationInput({ identity: current }));

  assert.deepEqual(Object.keys(bucket(firestore, "successful-use").data.activeHolds), [
    current.requestKey
  ]);
  assert.equal(requestRecord(firestore, stale).state, "EXPIRED");
});

test("expired DISPATCHED hold is reclaimed without erasing attempt counters", async () => {
  const { clock, firestore, store } = await fixture();
  const stale = requestIdentity("a");
  await store.reserve(reservationInput({ identity: stale }));
  await store.transition({ requestIdentity: stale, toState: "DISPATCHED" });
  clock.advance(31);
  await store.reserve(reservationInput({ identity: requestIdentity("b") }));

  assert.equal(requestRecord(firestore, stale).state, "EXPIRED");
  assert.equal(bucket(firestore, "provider-attempt").data.consumed, 1);
  assert.equal(firestore.documents(GLOBAL_COLLECTION)[0].data.consumed, 1);
});

test("expired DISPATCHED lazy recovery fails closed when attempt accounting is missing", async () => {
  const { clock, firestore, store } = await fixture();
  const stale = requestIdentity("a");
  await store.reserve(reservationInput({ identity: stale }));
  await store.transition({ requestIdentity: stale, toState: "DISPATCHED" });
  const globalAttempt = firestore.documents(GLOBAL_COLLECTION)[0];
  firestore.deleteDocument(GLOBAL_COLLECTION, globalAttempt.id);
  clock.advance(31);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.reserve(reservationInput({
    identity: requestIdentity("b")
  })));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("stale active request cannot later transition to SUCCEEDED", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  clock.advance(31);

  assert.deepEqual(await store.transition({ requestIdentity: identity, toState: "SUCCEEDED" }), {
    state: "EXPIRED"
  });
  assert.equal(requestRecord(firestore, identity).state, "EXPIRED");
  assert.equal(bucket(firestore, "successful-use").data.consumed, 0);
});

test("repeated expiry cleanup cannot decrement or underflow counters", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  clock.advance(31);
  await store.transition({ requestIdentity: identity, toState: "EXPIRED" });

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "EXPIRED"
  }));
  assert.equal(error.code, "REMOTE_REQUEST_COMPLETED");
  assert.equal(bucket(firestore, "successful-use").data.consumed, 0);
  assert.deepEqual(bucket(firestore, "successful-use").data.activeHolds, {});
});

test("retrying an expired active request commits EXPIRED then reports completed", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  const input = reservationInput({ identity });
  await store.reserve(input);
  clock.advance(31);

  const error = await captureRejection(store.reserve(input));
  assert.equal(error.code, "REMOTE_REQUEST_COMPLETED");
  assert.equal(requestRecord(firestore, identity).state, "EXPIRED");
  assert.deepEqual(bucket(firestore, "successful-use").data.activeHolds, {});
});

test("expired RESERVED retry rejects a missing hold without automatic repair", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  const input = reservationInput({ identity });
  await store.reserve(input);
  const success = bucket(firestore, "successful-use");
  success.data.activeHolds = {};
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, success.data);
  clock.advance(31);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.reserve(input));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("expired RESERVED retry rejects a mismatched hold without automatic repair", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  const input = reservationInput({ identity });
  await store.reserve(input);
  const success = bucket(firestore, "successful-use");
  success.data.activeHolds[identity.requestKey] = new Timestamp(BASE_SECONDS + 29, 0);
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, success.data);
  clock.advance(31);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.reserve(input));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("expired DISPATCHED transition rejects a missing hold without automatic repair", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const success = bucket(firestore, "successful-use");
  success.data.activeHolds = {};
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, success.data);
  clock.advance(31);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "EXPIRED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("expired DISPATCHED transition rejects a mismatched hold without automatic repair", async () => {
  const { clock, firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  await store.transition({ requestIdentity: identity, toState: "DISPATCHED" });
  const success = bucket(firestore, "successful-use");
  success.data.activeHolds[identity.requestKey] = new Timestamp(BASE_SECONDS + 29, 0);
  firestore.setDocument(ACCOUNT_COLLECTION, success.id, success.data);
  clock.advance(31);
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.transition({
    requestIdentity: identity,
    toState: "EXPIRED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("malformed request record fails closed and is not overwritten", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  const malformed = requestRecord(firestore, identity);
  malformed.unknownField = "corrupt-marker";
  firestore.setDocument(REQUEST_COLLECTION, identity.requestKey, malformed);

  const error = await captureRejection(store.reserve(reservationInput({ identity })));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.equal(requestRecord(firestore, identity).unknownField, "corrupt-marker");
});

test("malformed account bucket fails closed and is not overwritten", async () => {
  const { firestore, store } = await fixture();
  await store.reserve(reservationInput());
  const existing = bucket(firestore, "successful-use");
  existing.data.unknownField = "corrupt-marker";
  firestore.setDocument(ACCOUNT_COLLECTION, existing.id, existing.data);

  const error = await captureRejection(store.reserve(reservationInput({
    identity: requestIdentity("b")
  })));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.equal(firestore.getDocument(ACCOUNT_COLLECTION, existing.id).unknownField,
    "corrupt-marker");
});

test("exactly 128 valid active holds are structurally accepted but block another hold", async () => {
  const { firestore, store } = await fixture();
  const successId = await seedActiveHolds({ firestore, store, count: 128 });
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.reserve(reservationInput({
    identity: indexedRequestIdentity(200),
    admittedPolicy: policy({ successfulLimit: 200 })
  })));
  assert.equal(error.code, "REMOTE_ADMISSION_UNAVAILABLE");
  assert.deepEqual(durableSnapshot(firestore), before);
  assert.equal(Object.keys(firestore.getDocument(ACCOUNT_COLLECTION, successId).activeHolds).length,
    128);
  assert.equal(firestore.getDocument(ACCOUNT_COLLECTION, successId).consumed, 0);
});

test("129 durable active holds fail structural validation without writes", async () => {
  const { firestore, store } = await fixture();
  await seedActiveHolds({ firestore, store, count: 129 });
  const before = durableSnapshot(firestore);

  const error = await captureRejection(store.reserve(reservationInput({
    identity: indexedRequestIdentity(200),
    admittedPolicy: policy({ successfulLimit: 200 })
  })));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.deepEqual(durableSnapshot(firestore), before);
});

test("success hold whose active request points at another account fails closed", async () => {
  const { firestore, store } = await fixture();
  const identity = requestIdentity();
  await store.reserve(reservationInput({ identity }));
  const malformed = requestRecord(firestore, identity);
  malformed.accountKey = accountKey("b");
  firestore.setDocument(REQUEST_COLLECTION, identity.requestKey, malformed);

  const error = await captureRejection(store.reserve(reservationInput({
    identity: requestIdentity("b")
  })));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.equal(requestRecord(firestore, identity).accountKey, accountKey("b"));
});

test("malformed global bucket fails closed and is not overwritten", async () => {
  const { firestore, store } = await fixture();
  const first = requestIdentity("a");
  const second = requestIdentity("b");
  await store.reserve(reservationInput({ identity: first }));
  await store.reserve(reservationInput({
    account: accountKey("b"),
    identity: second
  }));
  await store.transition({ requestIdentity: first, toState: "DISPATCHED" });
  const global = firestore.documents(GLOBAL_COLLECTION)[0];
  global.data.unknownField = "corrupt-marker";
  firestore.setDocument(GLOBAL_COLLECTION, global.id, global.data);

  const error = await captureRejection(store.transition({
    requestIdentity: second,
    toState: "DISPATCHED"
  }));
  assert.equal(error.code, "REMOTE_ADMISSION_INVALID_RECORD");
  assert.equal(firestore.getDocument(GLOBAL_COLLECTION, global.id).unknownField,
    "corrupt-marker");
});

test("Firestore deadline maps to a bounded remote-admission timeout", async () => {
  const { firestore, store } = await fixture();
  firestore.failNextTransaction(Object.assign(new Error("private deadline detail"), { code: 4 }));

  const error = await captureRejection(store.reserve(reservationInput()));
  assert.equal(error.statusCode, 504);
  assert.equal(error.code, "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT");
  assert.equal(JSON.stringify(error).includes("private deadline detail"), false);
});

test("Firestore unavailable maps to a bounded persistence-unavailable error", async () => {
  const { firestore, store } = await fixture();
  firestore.failNextTransaction(Object.assign(new Error("private firestore detail"), { code: 14 }));

  const error = await captureRejection(store.reserve(reservationInput()));
  assert.equal(error.statusCode, 503);
  assert.equal(error.code, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal(JSON.stringify(error).includes("private firestore detail"), false);
});

test("Part-2 public errors are stable bounded and redact constructor input", () => {
  const cases = [
    [RemoteAccountQuotaExhaustedError, 429, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED"],
    [RemoteGlobalBudgetUnavailableError, 503, "REMOTE_GLOBAL_BUDGET_UNAVAILABLE"],
    [RemoteAdmissionInvalidRecordError, 503, "REMOTE_ADMISSION_INVALID_RECORD"],
    [RemoteAdmissionPersistenceTimeoutError, 504, "REMOTE_ADMISSION_PERSISTENCE_TIMEOUT"],
    [
      RemoteAdmissionPersistenceUnavailableError,
      503,
      "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE"
    ]
  ];
  const privateMarker = "private-document-id-account-key-limit";

  for (const [ErrorType, statusCode, code] of cases) {
    const error = new ErrorType(privateMarker);
    assert.equal(error.statusCode, statusCode);
    assert.equal(error.code, code);
    assert.ok(error.publicMessage.length > 0 && error.publicMessage.length <= 100);
    assert.equal(JSON.stringify(error).includes(privateMarker), false);
    assert.equal(String(error).includes(privateMarker), false);
  }
});

test("failed transaction commit leaves no partial durable writes", async () => {
  const { firestore, store } = await fixture();
  firestore.failNextCommit(Object.assign(new Error("private commit detail"), { code: 14 }));

  const error = await captureRejection(store.reserve(reservationInput()));
  assert.equal(error.code, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal(firestore.documents(REQUEST_COLLECTION).length, 0);
  assert.equal(firestore.documents(ACCOUNT_COLLECTION).length, 0);
  assert.equal(firestore.documents(GLOBAL_COLLECTION).length, 0);
});

test("transaction callback retry remains idempotent and commits one reservation", async () => {
  const { firestore, store } = await fixture();
  firestore.retryNextTransaction();

  await store.reserve(reservationInput());
  assert.equal(firestore.documents(REQUEST_COLLECTION).length, 1);
  assert.equal(firestore.documents(ACCOUNT_COLLECTION).length, 1);
  assert.equal(Object.keys(bucket(firestore, "successful-use").data.activeHolds).length, 1);
});

test("committed defaults keep remote execution disabled while stage12g3 quota policy is configured", async () => {
  const previousRemote = process.env.REMOTE_AI_ENABLED;
  const previousProvider = process.env.AI_PROVIDER;
  try {
    delete process.env.REMOTE_AI_ENABLED;
    delete process.env.AI_PROVIDER;
    const config = loadConfig();
    assert.equal(config.remoteAiEnabled, false);
    assert.equal(config.provider, "mock");

    const policy = DEFAULT_QUOTA_POLICY.getPolicy({
      capability: "REMOTE_AI_COACH",
      tier: "PLUS"
    });
    assert.equal(policy.capability, "REMOTE_AI_COACH");
    assert.equal(policy.tier, "PLUS");
    assert.deepEqual(policy.successfulUses, { limit: 3, windowSeconds: 86400 });
    assert.deepEqual(policy.providerAttempts, { limit: 9, windowSeconds: 86400 });
    assert.equal(policy.policyVersion, "stage12g3-remote-coach-v1");
    assert.deepEqual(policy.globalProviderAttempts, { limit: 1000, windowSeconds: 86400 });
  } finally {
    if (previousRemote === undefined) delete process.env.REMOTE_AI_ENABLED;
    else process.env.REMOTE_AI_ENABLED = previousRemote;
    if (previousProvider === undefined) delete process.env.AI_PROVIDER;
    else process.env.AI_PROVIDER = previousProvider;
  }
});
