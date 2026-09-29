import * as fs from "node:fs/promises";
import { timingSafeEqual } from "node:crypto";
import { dirname, basename, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  IdempotencyConflictError, RemoteAccountQuotaExhaustedError, RemoteGlobalBudgetUnavailableError,
  RemoteAdmissionInvalidRecordError, RemoteAdmissionPersistenceUnavailableError,
  RemoteAdmissionUnavailableError, RemoteRequestCompletedError, RemoteRequestInProgressError
} from "../errors.js";
import { deriveServerHmac } from "../remote_admission/account_identity.js";
import { AdmissionState as State, canTransitionAdmissionState } from "../remote_admission/admission_contracts.js";
import { resolveIdempotencyRequest } from "../remote_admission/idempotency.js";

export const LOCAL_EVALUATION_POLICY_VERSION = "stage12f3-evaluation-v1";
export const LOCAL_EVALUATION_DIRECTORY = fileURLToPath(new URL("../../.evaluation/", import.meta.url));
const CAPABILITY = "REMOTE_AI_COACH";
const MAX_RECORDS = 256;
const MAX_COUNTER = 1_000_000;
const MAX_BYTES = 1_048_576;
const BUCKETS = ["successfulUses", "providerAttempts", "globalProviderAttempts"];
const STATES = new Set(Object.values(State));
const ACTIVE = new Set([State.RESERVED, State.DISPATCHED]);
const OPAQUE = { account: /^acct_v1_[A-Za-z0-9_-]{43}$/, request: /^idem_v1_[A-Za-z0-9_-]{43}$/,
  fingerprint: /^req_v1_[A-Za-z0-9_-]{43}$/ };

function exact(value, fields) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    && Object.keys(value).sort().join("\0") === [...fields].sort().join("\0");
}
function bounded(value, max = MAX_COUNTER) { return Number.isSafeInteger(value) && value >= 0 && value <= max; }
function timestamp(value) { return bounded(value, 8_640_000_000_000_000); }
function invalidUnless(condition) { if (!condition) throw new RemoteAdmissionInvalidRecordError(); }
function validBuckets(value) {
  return exact(value, BUCKETS) && BUCKETS.every((key) => exact(value[key], ["limit", "windowSeconds"])
    && bounded(value[key].limit) && value[key].limit > 0
    && bounded(value[key].windowSeconds, 31_536_000) && value[key].windowSeconds > 0);
}
function identity(value) {
  if (!exact(value, ["requestKey", "fingerprint"]) || typeof value.requestKey !== "string"
      || typeof value.fingerprint !== "string" || !OPAQUE.request.test(value.requestKey)
      || !OPAQUE.fingerprint.test(value.fingerprint)) throw new RemoteAdmissionUnavailableError();
  return value;
}
function admittedPolicy(policy) {
  if (!exact(policy, ["capability", "tier", "policyVersion", ...BUCKETS])
      || policy.capability !== CAPABILITY || policy.tier !== "BASIC"
      || policy.policyVersion !== LOCAL_EVALUATION_POLICY_VERSION) throw new RemoteAdmissionUnavailableError();
  const result = Object.fromEntries(BUCKETS.map((key) => [key, policy[key]]));
  if (!validBuckets(result)) throw new RemoteAdmissionUnavailableError();
  return structuredClone(result);
}

/** Live callers may select a filename directly in this directory, never another location.
 * The directory argument is an internal isolated-filesystem test seam, not runner input.
 */
export function validateLocalEvaluationLedgerPath(path, directory = LOCAL_EVALUATION_DIRECTORY) {
  if (typeof path !== "string" || !path || path.includes("\0")
      || dirname(resolve(path)) !== resolve(directory)
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,95}\.json$/.test(basename(path))) {
    throw new RemoteAdmissionUnavailableError();
  }
  return resolve(path);
}

function validateSnapshot(payload) {
  invalidUnless(exact(payload, ["schemaVersion", "policyVersion", "policy", "revision", "updatedAt",
    "requests", "successfulUseCount", "providerAttemptCount"])
    && payload.schemaVersion === 1 && payload.policyVersion === LOCAL_EVALUATION_POLICY_VERSION
    && validBuckets(payload.policy) && bounded(payload.revision) && payload.revision > 0
    && timestamp(payload.updatedAt) && bounded(payload.successfulUseCount) && bounded(payload.providerAttemptCount)
    && payload.requests !== null && typeof payload.requests === "object" && !Array.isArray(payload.requests)
    && Object.keys(payload.requests).length <= MAX_RECORDS);
  let uses = 0;
  let attempts = 0;
  for (const [key, record] of Object.entries(payload.requests)) {
    invalidUnless(OPAQUE.request.test(key) && exact(record, ["accountKey", "fingerprint", "capability", "state",
      "createdAt", "updatedAt", "leaseExpiresAt", "attemptCount", "attemptTimestamps"])
      && typeof record.accountKey === "string" && typeof record.fingerprint === "string"
      && OPAQUE.account.test(record.accountKey) && OPAQUE.fingerprint.test(record.fingerprint)
      && record.capability === CAPABILITY && STATES.has(record.state)
      && timestamp(record.createdAt) && timestamp(record.updatedAt) && timestamp(record.leaseExpiresAt)
      && record.createdAt <= record.updatedAt && record.updatedAt <= payload.updatedAt
      && record.leaseExpiresAt > record.createdAt && record.leaseExpiresAt - record.createdAt <= 600_000
      && bounded(record.attemptCount, 64) && Array.isArray(record.attemptTimestamps)
      && record.attemptTimestamps.length === record.attemptCount);
    let previous = record.createdAt;
    for (const time of record.attemptTimestamps) {
      invalidUnless(timestamp(time) && time >= previous && time <= record.updatedAt && time < record.leaseExpiresAt);
      previous = time;
    }
    invalidUnless(record.state === State.EXPIRED
      ? record.updatedAt >= record.leaseExpiresAt
      : record.updatedAt < record.leaseExpiresAt);
    invalidUnless([State.RESERVED, State.FAILED_PRE_DISPATCH].includes(record.state) ? record.attemptCount === 0
      : record.state === State.EXPIRED || record.attemptCount > 0);
    uses += record.state === State.SUCCEEDED ? 1 : 0;
    attempts += record.attemptCount;
  }
  invalidUnless(uses === payload.successfulUseCount && attempts === payload.providerAttemptCount);
  return payload;
}
function sameWindow(time, now, bucket) {
  return Math.floor(time / (bucket.windowSeconds * 1000)) === Math.floor(now / (bucket.windowSeconds * 1000));
}
function stableError(error) {
  return [IdempotencyConflictError, RemoteAccountQuotaExhaustedError, RemoteGlobalBudgetUnavailableError,
    RemoteAdmissionInvalidRecordError, RemoteAdmissionPersistenceUnavailableError, RemoteAdmissionUnavailableError,
    RemoteRequestCompletedError, RemoteRequestInProgressError].some((Type) => error instanceof Type);
}

/** Evaluation-only single-machine accounting. No cloud clients or user content.
 * Each mutation acquires its own atomic lock and rereads the durable snapshot.
 * Failed/uncertain writes retain the lock; operator inspection is required.
 */
export class LocalEvaluationAdmissionStore {
  #fs;
  #path;
  #directory;
  #secret;
  #clock;
  #leaseMs;

  constructor({ ledgerPath, hmacSecret, leaseDurationSeconds, clock = Date.now,
    directory = LOCAL_EVALUATION_DIRECTORY, fileSystem = fs } = {}) {
    this.#path = validateLocalEvaluationLedgerPath(ledgerPath, directory);
    if (typeof clock !== "function" || !Number.isSafeInteger(leaseDurationSeconds)
        || leaseDurationSeconds < 1 || leaseDurationSeconds > 600) throw new RemoteAdmissionUnavailableError();
    deriveServerHmac({ hmacSecret, purpose: "evaluation-ledger", value: "validation" });
    this.#directory = resolve(directory);
    this.#secret = hmacSecret;
    this.#fs = fileSystem;
    this.#clock = clock;
    this.#leaseMs = leaseDurationSeconds * 1000;
  }

  #mac(payload) {
    return deriveServerHmac({ hmacSecret: this.#secret, purpose: "evaluation-ledger", value: JSON.stringify(payload) });
  }
  #marker() {
    return deriveServerHmac({ hmacSecret: this.#secret, purpose: "evaluation-ledger-initialization", value: "schema-1" });
  }
  async #exists(path) {
    try { return await this.#fs.lstat(path); } catch (error) { if (error?.code === "ENOENT") return null; throw error; }
  }
  async #readRegular(path, maxBytes) {
    const stat = await this.#exists(path);
    if (stat === null) return null;
    invalidUnless(stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1 && stat.size <= maxBytes);
    return this.#fs.readFile(path, "utf8");
  }
  async #load(policy, now) {
    const marker = await this.#readRegular(`${this.#path}.initialized`, 43);
    const bytes = await this.#readRegular(this.#path, MAX_BYTES);
    if (marker === null && bytes === null) {
      if (!policy) throw new RemoteAdmissionInvalidRecordError();
      return { fresh: true, payload: { schemaVersion: 1, policyVersion: LOCAL_EVALUATION_POLICY_VERSION,
        policy, revision: 0, updatedAt: now, requests: {}, successfulUseCount: 0, providerAttemptCount: 0 } };
    }
    invalidUnless(marker === this.#marker() && bytes !== null);
    let envelope;
    try { envelope = JSON.parse(bytes); } catch { throw new RemoteAdmissionInvalidRecordError(); }
    invalidUnless(exact(envelope, ["payload", "integrity"]) && typeof envelope.integrity === "string"
      && /^[A-Za-z0-9_-]{43}$/.test(envelope.integrity));
    const payload = validateSnapshot(envelope.payload);
    invalidUnless(timingSafeEqual(Buffer.from(envelope.integrity), Buffer.from(this.#mac(payload)))
      && now >= payload.updatedAt
      && (!policy || BUCKETS.every((key) => policy[key].limit === payload.policy[key].limit
        && policy[key].windowSeconds === payload.policy[key].windowSeconds)));
    return { fresh: false, payload };
  }
  async #writeFile(path, bytes) {
    const handle = await this.#fs.open(path, "wx", 0o600);
    try { await handle.writeFile(bytes, "utf8"); await handle.sync(); } finally { await handle.close(); }
  }
  async #persist(payload, fresh, now) {
    payload.revision += 1;
    payload.updatedAt = now;
    const records = Object.values(payload.requests);
    payload.successfulUseCount = records.filter((r) => r.state === State.SUCCEEDED).length;
    payload.providerAttemptCount = records.reduce((sum, r) => sum + r.attemptCount, 0);
    validateSnapshot(payload);
    const bytes = JSON.stringify({ payload, integrity: this.#mac(payload) });
    invalidUnless(Buffer.byteLength(bytes, "utf8") <= MAX_BYTES);
    await this.#writeFile(`${this.#path}.tmp`, bytes);
    // The separate marker prevents a deleted initialized ledger becoming a new empty store.
    if (fresh) await this.#writeFile(`${this.#path}.initialized`, this.#marker());
    await this.#fs.rename(`${this.#path}.tmp`, this.#path);
  }
  async #transaction(policy, operation) {
    let ownsLock = false;
    let uncertainWrite = false;
    try {
      try { await this.#fs.mkdir(this.#directory); } catch (error) { if (error?.code !== "EEXIST") throw error; }
      const directory = await this.#fs.lstat(this.#directory);
      invalidUnless(directory.isDirectory() && !directory.isSymbolicLink()
        && resolve(await this.#fs.realpath(this.#directory)) === this.#directory);
      const lock = await this.#fs.open(`${this.#path}.lock`, "wx", 0o600);
      ownsLock = true;
      await lock.close();
      // Orphan temporary files are uncertain accounting, never silently overwritten.
      if (await this.#exists(`${this.#path}.tmp`)) throw new RemoteAdmissionInvalidRecordError();
      const now = this.#clock();
      if (!timestamp(now) || !timestamp(now + this.#leaseMs)) throw new RemoteAdmissionUnavailableError();
      const { payload, fresh } = await this.#load(policy, now);
      let dirty = fresh;
      for (const record of Object.values(payload.requests)) {
        if (ACTIVE.has(record.state) && record.leaseExpiresAt <= now) {
          record.state = State.EXPIRED;
          record.updatedAt = now;
          dirty = true;
        }
      }
      let result;
      let failure;
      try { result = operation(payload, now); dirty = true; } catch (error) { failure = error; }
      if (dirty) {
        uncertainWrite = true;
        await this.#persist(payload, fresh, now);
        uncertainWrite = false;
      }
      if (failure) throw failure;
      return result;
    } catch (error) {
      throw stableError(error) ? error : new RemoteAdmissionPersistenceUnavailableError();
    } finally {
      if (ownsLock && !uncertainWrite) {
        try { await this.#fs.unlink(`${this.#path}.lock`); } catch { throw new RemoteAdmissionPersistenceUnavailableError(); }
      }
    }
  }
  #record(payload, requestIdentity) {
    const record = payload.requests[requestIdentity.requestKey];
    if (!record) throw new RemoteAdmissionUnavailableError();
    if (record.fingerprint !== requestIdentity.fingerprint) throw new IdempotencyConflictError();
    if (record.state !== State.EXPIRED && !ACTIVE.has(record.state)) {
      resolveIdempotencyRequest({ existingRequest: { ...requestIdentity, state: record.state }, requestIdentity });
    }
    return record;
  }

  async reserve({ accountKey, requestIdentity, capability, policy } = {}) {
    if (typeof accountKey !== "string" || !OPAQUE.account.test(accountKey)
        || capability !== CAPABILITY) throw new RemoteAdmissionUnavailableError();
    const key = identity(requestIdentity);
    const buckets = admittedPolicy(policy);
    return this.#transaction(buckets, (payload, now) => {
      const existing = payload.requests[key.requestKey];
      if (existing) {
        invalidUnless(existing.accountKey === accountKey);
        resolveIdempotencyRequest({ existingRequest: { requestKey: key.requestKey, fingerprint: existing.fingerprint,
          state: existing.state }, requestIdentity: key });
      }
      if (Object.keys(payload.requests).length >= MAX_RECORDS) throw new RemoteAdmissionUnavailableError();
      const uses = Object.values(payload.requests).filter((r) => r.accountKey === accountKey
        && (r.state === State.SUCCEEDED || ACTIVE.has(r.state))
        && sameWindow(r.createdAt, now, buckets.successfulUses)).length;
      if (uses >= buckets.successfulUses.limit) throw new RemoteAccountQuotaExhaustedError();
      payload.requests[key.requestKey] = { accountKey, fingerprint: key.fingerprint, capability,
        state: State.RESERVED, createdAt: now, updatedAt: now, leaseExpiresAt: now + this.#leaseMs,
        attemptCount: 0, attemptTimestamps: [] };
      return Object.freeze({ state: State.RESERVED });
    });
  }
  async recordProviderAttempt({ requestIdentity } = {}) {
    const key = identity(requestIdentity);
    return this.#transaction(null, (payload, now) => {
      const record = this.#record(payload, key);
      if (record.state === State.EXPIRED) return Object.freeze({ state: State.EXPIRED });
      let accountAttempts = 0;
      let globalAttempts = 0;
      for (const r of Object.values(payload.requests)) {
        for (const time of r.attemptTimestamps) {
          if (r.accountKey === record.accountKey && sameWindow(time, now, payload.policy.providerAttempts)) accountAttempts += 1;
          if (sameWindow(time, now, payload.policy.globalProviderAttempts)) globalAttempts += 1;
        }
      }
      if (accountAttempts >= payload.policy.providerAttempts.limit) throw new RemoteAccountQuotaExhaustedError();
      if (globalAttempts >= payload.policy.globalProviderAttempts.limit) throw new RemoteGlobalBudgetUnavailableError();
      if (record.attemptCount >= 64) throw new RemoteAdmissionUnavailableError();
      record.attemptCount += 1;
      record.attemptTimestamps.push(now);
      record.state = State.DISPATCHED;
      record.updatedAt = now;
      return Object.freeze({ state: State.DISPATCHED });
    });
  }
  async transition({ requestIdentity, toState } = {}) {
    const key = identity(requestIdentity);
    // DISPATCHED is only reachable through charged recordProviderAttempt().
    if (![State.SUCCEEDED, State.FAILED_PRE_DISPATCH, State.FAILED_POST_DISPATCH].includes(toState)) {
      throw new RemoteAdmissionUnavailableError();
    }
    return this.#transaction(null, (payload, now) => {
      const record = this.#record(payload, key);
      if (record.state === State.EXPIRED) return Object.freeze({ state: State.EXPIRED });
      if (!canTransitionAdmissionState(record.state, toState)) throw new RemoteAdmissionUnavailableError();
      record.state = toState;
      record.updatedAt = now;
      return Object.freeze({ state: toState });
    });
  }
}
