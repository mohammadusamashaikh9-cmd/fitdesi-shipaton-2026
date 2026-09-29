import { createHash } from "node:crypto";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import {
  IdempotencyConflictError,
  RemoteAccountQuotaExhaustedError,
  RemoteAdmissionInvalidRecordError,
  RemoteAdmissionPersistenceTimeoutError,
  RemoteAdmissionPersistenceUnavailableError,
  RemoteAdmissionUnavailableError,
  RemoteGlobalBudgetUnavailableError,
  RemoteRequestCompletedError,
  RemoteRequestInProgressError
} from "../errors.js";
import { firebaseAdminAppForProcess } from "../firebase/firebase_admin_app.js";
import {
  AdmissionState,
  canTransitionAdmissionState
} from "./admission_contracts.js";
import { RemoteCapability } from "./capability_policy.js";
import { resolveIdempotencyRequest } from "./idempotency.js";

const REQUEST_COLLECTION = "remote_ai_requests_v1";
const ACCOUNT_QUOTA_COLLECTION = "remote_ai_account_quota_v1";
const GLOBAL_QUOTA_COLLECTION = "remote_ai_global_quota_v1";
const SCHEMA_VERSION = 1;
const MAX_ACTIVE_HOLDS_PER_BUCKET = 128;
const ACCOUNT_KEY_PATTERN = /^acct_v1_[A-Za-z0-9_-]{43}$/;
const REQUEST_KEY_PATTERN = /^idem_v1_[A-Za-z0-9_-]{43}$/;
const FINGERPRINT_PATTERN = /^req_v1_[A-Za-z0-9_-]{43}$/;
const POLICY_VERSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const KNOWN_CAPABILITIES = new Set(Object.values(RemoteCapability));
const KNOWN_STATES = new Set(Object.values(AdmissionState));
const KNOWN_TIERS = new Set(["BASIC", "PLUS", "PRO"]);
const FIRESTORE_DEADLINE_CODES = new Set([
  4,
  "4",
  "DEADLINE_EXCEEDED",
  "deadline-exceeded"
]);

const REQUEST_FIELDS = [
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
];
const SUCCESS_BUCKET_FIELDS = [
  "accountKey",
  "activeHolds",
  "bucketType",
  "capability",
  "consumed",
  "policyVersion",
  "schemaVersion",
  "updatedAt",
  "windowEnd",
  "windowStart"
];
const ACCOUNT_ATTEMPT_FIELDS = [
  "accountKey",
  "bucketType",
  "capability",
  "consumed",
  "policyVersion",
  "schemaVersion",
  "updatedAt",
  "windowEnd",
  "windowStart"
];
const GLOBAL_ATTEMPT_FIELDS = [
  "bucketType",
  "consumed",
  "policyVersion",
  "schemaVersion",
  "updatedAt",
  "windowEnd",
  "windowStart"
];

function exactFields(value, fields) {
  return value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).sort().join("\0") === [...fields].sort().join("\0");
}

function safeNonnegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function validTimestamp(value) {
  return value instanceof Timestamp &&
    Number.isSafeInteger(value.seconds) &&
    value.seconds >= 0 &&
    Number.isInteger(value.nanoseconds) &&
    value.nanoseconds >= 0 &&
    value.nanoseconds < 1_000_000_000;
}

function compareTimestamp(left, right) {
  if (left.seconds !== right.seconds) return left.seconds - right.seconds;
  return left.nanoseconds - right.nanoseconds;
}

function sameTimestamp(left, right) {
  return compareTimestamp(left, right) === 0;
}

function requireTimestamp(value) {
  if (!validTimestamp(value)) throw new RemoteAdmissionUnavailableError();
  return value;
}

function leaseExpiry(now, leaseDurationSeconds) {
  const seconds = now.seconds + leaseDurationSeconds;
  if (!Number.isSafeInteger(seconds)) throw new RemoteAdmissionUnavailableError();
  try {
    return new Timestamp(seconds, now.nanoseconds);
  } catch {
    throw new RemoteAdmissionUnavailableError();
  }
}

function quotaWindow(now, windowSeconds) {
  const windowStart = Math.floor(now.seconds / windowSeconds) * windowSeconds;
  const windowEnd = windowStart + windowSeconds;
  if (!Number.isSafeInteger(windowStart) || !Number.isSafeInteger(windowEnd)) {
    throw new RemoteAdmissionUnavailableError();
  }
  return Object.freeze({ windowStart, windowEnd });
}

function validWindow(windowStart, windowEnd) {
  return safeNonnegativeInteger(windowStart) &&
    Number.isSafeInteger(windowEnd) &&
    windowEnd > windowStart;
}

function policyBucket(value) {
  if (
    !exactFields(value, ["limit", "windowSeconds"]) ||
    !Number.isSafeInteger(value.limit) ||
    value.limit < 1 ||
    !Number.isSafeInteger(value.windowSeconds) ||
    value.windowSeconds < 1
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return Object.freeze({ limit: value.limit, windowSeconds: value.windowSeconds });
}

function admittedPolicy(value, capability) {
  if (
    !exactFields(value, [
      "capability",
      "globalProviderAttempts",
      "policyVersion",
      "providerAttempts",
      "successfulUses",
      "tier"
    ]) ||
    value.capability !== capability ||
    !KNOWN_CAPABILITIES.has(value.capability) ||
    !KNOWN_TIERS.has(value.tier) ||
    !POLICY_VERSION_PATTERN.test(value.policyVersion)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return Object.freeze({
    capability: value.capability,
    policyVersion: value.policyVersion,
    successfulUses: policyBucket(value.successfulUses),
    providerAttempts: policyBucket(value.providerAttempts),
    globalProviderAttempts: policyBucket(value.globalProviderAttempts)
  });
}

function requireRequestIdentity(value) {
  if (
    !exactFields(value, ["fingerprint", "requestKey"]) ||
    !REQUEST_KEY_PATTERN.test(value.requestKey) ||
    !FINGERPRINT_PATTERN.test(value.fingerprint)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  return value;
}

function opaqueId(prefix, parts) {
  const digest = createHash("sha256")
    .update(parts.join("\0"), "utf8")
    .digest("base64url");
  return `${prefix}_${digest}`;
}

function accountBucketId({ accountKey, capability, policyVersion, bucketType, windowStart }) {
  return opaqueId("aq_v1", [
    accountKey,
    capability,
    policyVersion,
    bucketType,
    String(windowStart)
  ]);
}

function globalBucketId({ policyVersion, windowStart }) {
  return opaqueId("gq_v1", [policyVersion, "global-provider-attempt", String(windowStart)]);
}

function snapshotData(snapshot) {
  try {
    return snapshot.data();
  } catch {
    throw new RemoteAdmissionInvalidRecordError();
  }
}

function policySnapshot(value) {
  return { limit: value.limit, windowSeconds: value.windowSeconds };
}

function successfulPolicySnapshot(value, window) {
  return {
    limit: value.limit,
    windowSeconds: value.windowSeconds,
    windowStart: window.windowStart,
    windowEnd: window.windowEnd
  };
}

function validPolicySnapshot(value) {
  return exactFields(value, ["limit", "windowSeconds"]) &&
    Number.isSafeInteger(value.limit) &&
    value.limit >= 1 &&
    Number.isSafeInteger(value.windowSeconds) &&
    value.windowSeconds >= 1;
}

function validSuccessfulPolicySnapshot(value) {
  return exactFields(value, ["limit", "windowEnd", "windowSeconds", "windowStart"]) &&
    Number.isSafeInteger(value.limit) &&
    value.limit >= 1 &&
    Number.isSafeInteger(value.windowSeconds) &&
    value.windowSeconds >= 1 &&
    validWindow(value.windowStart, value.windowEnd) &&
    value.windowStart % value.windowSeconds === 0 &&
    value.windowEnd === value.windowStart + value.windowSeconds;
}

function validateRequestRecord(value, requestKey) {
  if (
    !exactFields(value, REQUEST_FIELDS) ||
    value.schemaVersion !== SCHEMA_VERSION ||
    !REQUEST_KEY_PATTERN.test(requestKey) ||
    !FINGERPRINT_PATTERN.test(value.fingerprint) ||
    !ACCOUNT_KEY_PATTERN.test(value.accountKey) ||
    !KNOWN_CAPABILITIES.has(value.capability) ||
    !POLICY_VERSION_PATTERN.test(value.policyVersion) ||
    !KNOWN_STATES.has(value.state) ||
    !validSuccessfulPolicySnapshot(value.successfulUse) ||
    !validPolicySnapshot(value.providerAttempt) ||
    !validPolicySnapshot(value.globalAttempt) ||
    !validTimestamp(value.leaseExpiresAt) ||
    !validTimestamp(value.createdAt) ||
    !validTimestamp(value.updatedAt) ||
    compareTimestamp(value.createdAt, value.updatedAt) > 0 ||
    compareTimestamp(value.createdAt, value.leaseExpiresAt) >= 0
  ) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  return value;
}

function validHolds(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const entries = Object.entries(value);
  if (entries.length > MAX_ACTIVE_HOLDS_PER_BUCKET) return false;
  for (const [requestKey, expiresAt] of entries) {
    if (!REQUEST_KEY_PATTERN.test(requestKey) || !validTimestamp(expiresAt)) return false;
  }
  return true;
}

function requireConsistentSuccessHold(success, requestKey, request) {
  if (!Object.hasOwn(success.activeHolds, requestKey)) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  const heldUntil = success.activeHolds[requestKey];
  if (!validTimestamp(heldUntil) || !sameTimestamp(heldUntil, request.leaseExpiresAt)) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  return heldUntil;
}

function validateSuccessBucket(value, expected) {
  if (
    !exactFields(value, SUCCESS_BUCKET_FIELDS) ||
    value.schemaVersion !== SCHEMA_VERSION ||
    value.bucketType !== "successful-use" ||
    value.accountKey !== expected.accountKey ||
    value.capability !== expected.capability ||
    value.policyVersion !== expected.policyVersion ||
    value.windowStart !== expected.windowStart ||
    value.windowEnd !== expected.windowEnd ||
    !validWindow(value.windowStart, value.windowEnd) ||
    !safeNonnegativeInteger(value.consumed) ||
    !validHolds(value.activeHolds) ||
    !Number.isSafeInteger(value.consumed + Object.keys(value.activeHolds).length) ||
    !validTimestamp(value.updatedAt)
  ) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  return value;
}

function validateAccountAttemptBucket(value, expected) {
  if (
    !exactFields(value, ACCOUNT_ATTEMPT_FIELDS) ||
    value.schemaVersion !== SCHEMA_VERSION ||
    value.bucketType !== "provider-attempt" ||
    value.accountKey !== expected.accountKey ||
    value.capability !== expected.capability ||
    value.policyVersion !== expected.policyVersion ||
    value.windowStart !== expected.windowStart ||
    value.windowEnd !== expected.windowEnd ||
    !validWindow(value.windowStart, value.windowEnd) ||
    !safeNonnegativeInteger(value.consumed) ||
    !validTimestamp(value.updatedAt)
  ) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  return value;
}

function validateGlobalAttemptBucket(value, expected) {
  if (
    !exactFields(value, GLOBAL_ATTEMPT_FIELDS) ||
    value.schemaVersion !== SCHEMA_VERSION ||
    value.bucketType !== "global-provider-attempt" ||
    value.policyVersion !== expected.policyVersion ||
    value.windowStart !== expected.windowStart ||
    value.windowEnd !== expected.windowEnd ||
    !validWindow(value.windowStart, value.windowEnd) ||
    !safeNonnegativeInteger(value.consumed) ||
    !validTimestamp(value.updatedAt)
  ) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  return value;
}

function newSuccessBucket(expected, now) {
  return {
    schemaVersion: SCHEMA_VERSION,
    bucketType: "successful-use",
    accountKey: expected.accountKey,
    capability: expected.capability,
    policyVersion: expected.policyVersion,
    windowStart: expected.windowStart,
    windowEnd: expected.windowEnd,
    consumed: 0,
    activeHolds: {},
    updatedAt: now
  };
}

function newAccountAttemptBucket(expected, now) {
  return {
    schemaVersion: SCHEMA_VERSION,
    bucketType: "provider-attempt",
    accountKey: expected.accountKey,
    capability: expected.capability,
    policyVersion: expected.policyVersion,
    windowStart: expected.windowStart,
    windowEnd: expected.windowEnd,
    consumed: 0,
    updatedAt: now
  };
}

function newGlobalAttemptBucket(expected, now) {
  return {
    schemaVersion: SCHEMA_VERSION,
    bucketType: "global-provider-attempt",
    policyVersion: expected.policyVersion,
    windowStart: expected.windowStart,
    windowEnd: expected.windowEnd,
    consumed: 0,
    updatedAt: now
  };
}

async function verifyDispatchedAccounting({ firestore, request, transaction }) {
  const accountWindow = quotaWindow(request.updatedAt, request.providerAttempt.windowSeconds);
  const globalWindow = quotaWindow(request.updatedAt, request.globalAttempt.windowSeconds);
  const accountExpected = {
    accountKey: request.accountKey,
    capability: request.capability,
    policyVersion: request.policyVersion,
    ...accountWindow
  };
  const globalExpected = {
    policyVersion: request.policyVersion,
    ...globalWindow
  };
  const accountReference = firestore.collection(ACCOUNT_QUOTA_COLLECTION).doc(accountBucketId({
    ...accountExpected,
    bucketType: "provider-attempt"
  }));
  const globalReference = firestore.collection(GLOBAL_QUOTA_COLLECTION).doc(
    globalBucketId(globalExpected)
  );
  const accountSnapshot = await transaction.get(accountReference);
  const globalSnapshot = await transaction.get(globalReference);
  if (accountSnapshot.exists === false || globalSnapshot.exists === false) {
    throw new RemoteAdmissionInvalidRecordError();
  }
  const account = validateAccountAttemptBucket(snapshotData(accountSnapshot), accountExpected);
  const global = validateGlobalAttemptBucket(snapshotData(globalSnapshot), globalExpected);
  if (account.consumed < 1 || global.consumed < 1) {
    throw new RemoteAdmissionInvalidRecordError();
  }
}

async function reclaimExpiredHolds({ bucket, expected, firestore, now, transaction }) {
  const activeHolds = { ...bucket.activeHolds };
  const expiredRequests = [];
  let changed = false;
  for (const [requestKey, expiresAt] of Object.entries(activeHolds)) {
    const reference = firestore.collection(REQUEST_COLLECTION).doc(requestKey);
    const snapshot = await transaction.get(reference);
    if (snapshot.exists === false) throw new RemoteAdmissionInvalidRecordError();
    const request = validateRequestRecord(snapshotData(snapshot), requestKey);
    if (
      request.accountKey !== expected.accountKey ||
      request.capability !== expected.capability ||
      request.policyVersion !== expected.policyVersion ||
      request.successfulUse.windowStart !== expected.windowStart ||
      request.successfulUse.windowEnd !== expected.windowEnd ||
      (request.state !== AdmissionState.RESERVED &&
        request.state !== AdmissionState.DISPATCHED)
    ) {
      throw new RemoteAdmissionInvalidRecordError();
    }
    requireConsistentSuccessHold(bucket, requestKey, request);
    if (compareTimestamp(expiresAt, now) <= 0) {
      if (request.state === AdmissionState.DISPATCHED) {
        await verifyDispatchedAccounting({ firestore, request, transaction });
      }
      delete activeHolds[requestKey];
      expiredRequests.push({ reference, request });
      changed = true;
    }
  }
  return { activeHolds, changed, expiredRequests };
}

function stageExpiredRequests(transaction, expiredRequests, now) {
  for (const { reference, request } of expiredRequests) {
    transaction.set(reference, {
      ...request,
      state: AdmissionState.EXPIRED,
      updatedAt: now
    });
  }
}

function isStableStoreError(error) {
  return error instanceof IdempotencyConflictError ||
    error instanceof RemoteRequestInProgressError ||
    error instanceof RemoteRequestCompletedError ||
    error instanceof RemoteAccountQuotaExhaustedError ||
    error instanceof RemoteGlobalBudgetUnavailableError ||
    error instanceof RemoteAdmissionInvalidRecordError ||
    error instanceof RemoteAdmissionPersistenceTimeoutError ||
    error instanceof RemoteAdmissionPersistenceUnavailableError ||
    error instanceof RemoteAdmissionUnavailableError;
}

function mapPersistenceError(error) {
  if (isStableStoreError(error)) return error;
  try {
    if (FIRESTORE_DEADLINE_CODES.has(error?.code)) {
      return new RemoteAdmissionPersistenceTimeoutError();
    }
  } catch {
    return new RemoteAdmissionPersistenceUnavailableError();
  }
  return new RemoteAdmissionPersistenceUnavailableError();
}

async function persistenceOperation(operation) {
  try {
    return await operation();
  } catch (error) {
    throw mapPersistenceError(error);
  }
}

export class FirestoreRemoteAdmissionStore {
  #clock;
  #firebaseAdminAppProvider;
  #firestore;
  #getFirestoreImpl;
  #leaseDurationSeconds;
  #projectId;

  constructor({
    projectId,
    firestore = null,
    firebaseAdminAppProvider = firebaseAdminAppForProcess,
    getFirestoreImpl = getFirestore,
    clock = () => Timestamp.now(),
    leaseDurationSeconds
  } = {}) {
    if (
      typeof projectId !== "string" ||
      projectId.length === 0 ||
      typeof clock !== "function" ||
      !Number.isSafeInteger(leaseDurationSeconds) ||
      leaseDurationSeconds < 1 ||
      (firestore !== null && (
        typeof firestore !== "object" ||
        typeof firestore.collection !== "function" ||
        typeof firestore.runTransaction !== "function"
      ))
    ) {
      throw new RemoteAdmissionUnavailableError();
    }
    this.#projectId = projectId;
    this.#firestore = firestore;
    this.#firebaseAdminAppProvider = firebaseAdminAppProvider;
    this.#getFirestoreImpl = getFirestoreImpl;
    this.#clock = clock;
    this.#leaseDurationSeconds = leaseDurationSeconds;
  }

  #firestoreForOperation() {
    if (this.#firestore !== null) return this.#firestore;
    const app = this.#firebaseAdminAppProvider.getApp({ projectId: this.#projectId });
    const firestore = this.#getFirestoreImpl(app);
    if (
      !firestore ||
      typeof firestore.collection !== "function" ||
      typeof firestore.runTransaction !== "function"
    ) {
      throw new RemoteAdmissionPersistenceUnavailableError();
    }
    this.#firestore = firestore;
    return firestore;
  }

  #now() {
    return requireTimestamp(this.#clock());
  }

  #requestReference(firestore, requestKey) {
    return firestore.collection(REQUEST_COLLECTION).doc(requestKey);
  }

  #successReference(firestore, expected) {
    return firestore.collection(ACCOUNT_QUOTA_COLLECTION).doc(accountBucketId({
      ...expected,
      bucketType: "successful-use"
    }));
  }

  #accountAttemptReference(firestore, expected) {
    return firestore.collection(ACCOUNT_QUOTA_COLLECTION).doc(accountBucketId({
      ...expected,
      bucketType: "provider-attempt"
    }));
  }

  #globalAttemptReference(firestore, expected) {
    return firestore.collection(GLOBAL_QUOTA_COLLECTION).doc(globalBucketId(expected));
  }

  async reserve({ accountKey, requestIdentity, capability, policy } = {}) {
    return persistenceOperation(async () => {
      if (!ACCOUNT_KEY_PATTERN.test(accountKey) || !KNOWN_CAPABILITIES.has(capability)) {
        throw new RemoteAdmissionUnavailableError();
      }
      const identity = requireRequestIdentity(requestIdentity);
      const normalizedPolicy = admittedPolicy(policy, capability);
      const firestore = this.#firestoreForOperation();
      const outcome = await firestore.runTransaction(async (transaction) => {
        const requestReference = this.#requestReference(firestore, identity.requestKey);
        const requestSnapshot = await transaction.get(requestReference);
        const now = this.#now();

        if (requestSnapshot.exists !== false) {
          const existing = validateRequestRecord(snapshotData(requestSnapshot), identity.requestKey);
          if (existing.fingerprint !== identity.fingerprint) {
            throw new IdempotencyConflictError();
          }
          if (
            (existing.state === AdmissionState.RESERVED ||
              existing.state === AdmissionState.DISPATCHED) &&
            compareTimestamp(existing.leaseExpiresAt, now) <= 0
          ) {
            const expected = {
              accountKey: existing.accountKey,
              capability: existing.capability,
              policyVersion: existing.policyVersion,
              windowStart: existing.successfulUse.windowStart,
              windowEnd: existing.successfulUse.windowEnd
            };
            const successReference = this.#successReference(firestore, expected);
            const successSnapshot = await transaction.get(successReference);
            if (successSnapshot.exists === false) {
              throw new RemoteAdmissionInvalidRecordError();
            }
            const success = validateSuccessBucket(snapshotData(successSnapshot), expected);
            requireConsistentSuccessHold(success, identity.requestKey, existing);
            if (existing.state === AdmissionState.DISPATCHED) {
              await verifyDispatchedAccounting({ firestore, request: existing, transaction });
            }
            const activeHolds = { ...success.activeHolds };
            delete activeHolds[identity.requestKey];
            transaction.set(successReference, { ...success, activeHolds, updatedAt: now });
            transaction.set(requestReference, {
              ...existing,
              state: AdmissionState.EXPIRED,
              updatedAt: now
            });
            return { deferredError: new RemoteRequestCompletedError() };
          }
          resolveIdempotencyRequest({
            existingRequest: {
              requestKey: identity.requestKey,
              fingerprint: existing.fingerprint,
              state: existing.state
            },
            requestIdentity: identity
          });
          throw new RemoteAdmissionUnavailableError();
        }

        const window = quotaWindow(now, normalizedPolicy.successfulUses.windowSeconds);
        const expected = {
          accountKey,
          capability,
          policyVersion: normalizedPolicy.policyVersion,
          windowStart: window.windowStart,
          windowEnd: window.windowEnd
        };
        const successReference = this.#successReference(firestore, expected);
        const successSnapshot = await transaction.get(successReference);
        const success = successSnapshot.exists === false
          ? newSuccessBucket(expected, now)
          : validateSuccessBucket(snapshotData(successSnapshot), expected);
        const reclaimed = await reclaimExpiredHolds({
          bucket: success,
          expected,
          firestore,
          now,
          transaction
        });
        const currentUsage = success.consumed + Object.keys(reclaimed.activeHolds).length;
        if (!Number.isSafeInteger(currentUsage)) {
          throw new RemoteAdmissionInvalidRecordError();
        }
        if (currentUsage >= normalizedPolicy.successfulUses.limit) {
          if (reclaimed.changed) {
            stageExpiredRequests(transaction, reclaimed.expiredRequests, now);
            transaction.set(successReference, {
              ...success,
              activeHolds: reclaimed.activeHolds,
              updatedAt: now
            });
          }
          return { deferredError: new RemoteAccountQuotaExhaustedError() };
        }
        if (Object.keys(reclaimed.activeHolds).length >= MAX_ACTIVE_HOLDS_PER_BUCKET) {
          return { deferredError: new RemoteAdmissionUnavailableError() };
        }

        const expiresAt = leaseExpiry(now, this.#leaseDurationSeconds);
        const activeHolds = {
          ...reclaimed.activeHolds,
          [identity.requestKey]: expiresAt
        };
        const request = {
          schemaVersion: SCHEMA_VERSION,
          fingerprint: identity.fingerprint,
          accountKey,
          capability,
          policyVersion: normalizedPolicy.policyVersion,
          state: AdmissionState.RESERVED,
          successfulUse: successfulPolicySnapshot(normalizedPolicy.successfulUses, window),
          providerAttempt: policySnapshot(normalizedPolicy.providerAttempts),
          globalAttempt: policySnapshot(normalizedPolicy.globalProviderAttempts),
          leaseExpiresAt: expiresAt,
          createdAt: now,
          updatedAt: now
        };
        stageExpiredRequests(transaction, reclaimed.expiredRequests, now);
        transaction.set(successReference, { ...success, activeHolds, updatedAt: now });
        transaction.set(requestReference, request);
        return { result: Object.freeze({ state: AdmissionState.RESERVED }) };
      });
      if (outcome.deferredError) throw outcome.deferredError;
      return outcome.result;
    });
  }

  async recordProviderAttempt({ requestIdentity } = {}) {
    return this.#recordProviderAttempt(requestIdentity, true);
  }

  async #recordProviderAttempt(requestIdentity, allowAlreadyDispatched) {
    return persistenceOperation(async () => {
      const identity = requireRequestIdentity(requestIdentity);
      const firestore = this.#firestoreForOperation();
      const outcome = await firestore.runTransaction(async (transaction) => {
        const requestReference = this.#requestReference(firestore, identity.requestKey);
        const requestSnapshot = await transaction.get(requestReference);
        if (requestSnapshot.exists === false) throw new RemoteAdmissionUnavailableError();
        const request = validateRequestRecord(snapshotData(requestSnapshot), identity.requestKey);
        if (request.fingerprint !== identity.fingerprint) throw new IdempotencyConflictError();
        if (
          request.state !== AdmissionState.RESERVED &&
          request.state !== AdmissionState.DISPATCHED
        ) {
          resolveIdempotencyRequest({
            existingRequest: {
              requestKey: identity.requestKey,
              fingerprint: request.fingerprint,
              state: request.state
            },
            requestIdentity: identity
          });
        }
        if (!allowAlreadyDispatched && request.state === AdmissionState.DISPATCHED) {
          throw new RemoteAdmissionUnavailableError();
        }

        const now = this.#now();
        const successExpected = {
          accountKey: request.accountKey,
          capability: request.capability,
          policyVersion: request.policyVersion,
          windowStart: request.successfulUse.windowStart,
          windowEnd: request.successfulUse.windowEnd
        };
        const successReference = this.#successReference(firestore, successExpected);
        const successSnapshot = await transaction.get(successReference);
        if (successSnapshot.exists === false) throw new RemoteAdmissionInvalidRecordError();
        const success = validateSuccessBucket(snapshotData(successSnapshot), successExpected);
        requireConsistentSuccessHold(success, identity.requestKey, request);

        if (compareTimestamp(request.leaseExpiresAt, now) <= 0) {
          if (request.state === AdmissionState.DISPATCHED) {
            await verifyDispatchedAccounting({ firestore, request, transaction });
          }
          const activeHolds = { ...success.activeHolds };
          delete activeHolds[identity.requestKey];
          transaction.set(successReference, { ...success, activeHolds, updatedAt: now });
          transaction.set(requestReference, {
            ...request,
            state: AdmissionState.EXPIRED,
            updatedAt: now
          });
          return { result: Object.freeze({ state: AdmissionState.EXPIRED }) };
        }

        const accountWindow = quotaWindow(now, request.providerAttempt.windowSeconds);
        const globalWindow = quotaWindow(now, request.globalAttempt.windowSeconds);
        const accountExpected = {
          accountKey: request.accountKey,
          capability: request.capability,
          policyVersion: request.policyVersion,
          ...accountWindow
        };
        const globalExpected = {
          policyVersion: request.policyVersion,
          ...globalWindow
        };
        const accountReference = this.#accountAttemptReference(firestore, accountExpected);
        const globalReference = this.#globalAttemptReference(firestore, globalExpected);
        const accountSnapshot = await transaction.get(accountReference);
        const globalSnapshot = await transaction.get(globalReference);
        const account = accountSnapshot.exists === false
          ? newAccountAttemptBucket(accountExpected, now)
          : validateAccountAttemptBucket(snapshotData(accountSnapshot), accountExpected);
        const global = globalSnapshot.exists === false
          ? newGlobalAttemptBucket(globalExpected, now)
          : validateGlobalAttemptBucket(snapshotData(globalSnapshot), globalExpected);
        if (account.consumed >= request.providerAttempt.limit) {
          return { deferredError: new RemoteAccountQuotaExhaustedError() };
        }
        if (global.consumed >= request.globalAttempt.limit) {
          return { deferredError: new RemoteGlobalBudgetUnavailableError() };
        }
        if (
          !Number.isSafeInteger(account.consumed + 1) ||
          !Number.isSafeInteger(global.consumed + 1)
        ) {
          throw new RemoteAdmissionInvalidRecordError();
        }
        transaction.set(accountReference, {
          ...account,
          consumed: account.consumed + 1,
          updatedAt: now
        });
        transaction.set(globalReference, {
          ...global,
          consumed: global.consumed + 1,
          updatedAt: now
        });
        transaction.set(requestReference, {
          ...request,
          state: AdmissionState.DISPATCHED,
          updatedAt: now
        });
        return { result: Object.freeze({ state: AdmissionState.DISPATCHED }) };
      });
      if (outcome.deferredError) throw outcome.deferredError;
      return outcome.result;
    });
  }

  async transition({ requestIdentity, toState } = {}) {
    if (toState === AdmissionState.DISPATCHED) {
      return this.#recordProviderAttempt(requestIdentity, false);
    }
    return persistenceOperation(async () => {
      const identity = requireRequestIdentity(requestIdentity);
      if (!KNOWN_STATES.has(toState)) throw new RemoteAdmissionUnavailableError();
      const firestore = this.#firestoreForOperation();
      const outcome = await firestore.runTransaction(async (transaction) => {
        const requestReference = this.#requestReference(firestore, identity.requestKey);
        const requestSnapshot = await transaction.get(requestReference);
        if (requestSnapshot.exists === false) throw new RemoteAdmissionUnavailableError();
        const request = validateRequestRecord(snapshotData(requestSnapshot), identity.requestKey);
        if (request.fingerprint !== identity.fingerprint) throw new IdempotencyConflictError();

        if (
          request.state !== AdmissionState.RESERVED &&
          request.state !== AdmissionState.DISPATCHED
        ) {
          resolveIdempotencyRequest({
            existingRequest: {
              requestKey: identity.requestKey,
              fingerprint: request.fingerprint,
              state: request.state
            },
            requestIdentity: identity
          });
        }

        const now = this.#now();
        const leaseExpired = compareTimestamp(request.leaseExpiresAt, now) <= 0;
        if (leaseExpired) {
          const expected = {
            accountKey: request.accountKey,
            capability: request.capability,
            policyVersion: request.policyVersion,
            windowStart: request.successfulUse.windowStart,
            windowEnd: request.successfulUse.windowEnd
          };
          const successReference = this.#successReference(firestore, expected);
          const successSnapshot = await transaction.get(successReference);
          if (successSnapshot.exists === false) throw new RemoteAdmissionInvalidRecordError();
          const success = validateSuccessBucket(snapshotData(successSnapshot), expected);
          requireConsistentSuccessHold(success, identity.requestKey, request);
          if (request.state === AdmissionState.DISPATCHED) {
            await verifyDispatchedAccounting({ firestore, request, transaction });
          }
          const activeHolds = { ...success.activeHolds };
          delete activeHolds[identity.requestKey];
          transaction.set(successReference, { ...success, activeHolds, updatedAt: now });
          transaction.set(requestReference, {
            ...request,
            state: AdmissionState.EXPIRED,
            updatedAt: now
          });
          return { result: Object.freeze({ state: AdmissionState.EXPIRED }) };
        }

        if (!canTransitionAdmissionState(request.state, toState)) {
          throw new RemoteAdmissionUnavailableError();
        }
        if (toState === AdmissionState.EXPIRED) {
          throw new RemoteAdmissionUnavailableError();
        }

        const expected = {
          accountKey: request.accountKey,
          capability: request.capability,
          policyVersion: request.policyVersion,
          windowStart: request.successfulUse.windowStart,
          windowEnd: request.successfulUse.windowEnd
        };
        const successReference = this.#successReference(firestore, expected);
        const successSnapshot = await transaction.get(successReference);
        if (successSnapshot.exists === false) throw new RemoteAdmissionInvalidRecordError();
        const success = validateSuccessBucket(snapshotData(successSnapshot), expected);
        requireConsistentSuccessHold(success, identity.requestKey, request);
        if (request.state === AdmissionState.DISPATCHED) {
          await verifyDispatchedAccounting({ firestore, request, transaction });
        }
        const activeHolds = { ...success.activeHolds };
        delete activeHolds[identity.requestKey];
        const consumed = toState === AdmissionState.SUCCEEDED
          ? success.consumed + 1
          : success.consumed;
        if (!Number.isSafeInteger(consumed)) throw new RemoteAdmissionInvalidRecordError();
        transaction.set(successReference, {
          ...success,
          consumed,
          activeHolds,
          updatedAt: now
        });
        transaction.set(requestReference, { ...request, state: toState, updatedAt: now });
        return { result: Object.freeze({ state: toState }) };
      });
      if (outcome.deferredError) throw outcome.deferredError;
      return outcome.result;
    });
  }
}
