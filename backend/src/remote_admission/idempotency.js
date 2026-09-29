import {
  IdempotencyConflictError,
  IdempotencyInvalidError,
  IdempotencyRequiredError,
  RemoteAdmissionUnavailableError,
  RemoteRequestCompletedError,
  RemoteRequestInProgressError
} from "../errors.js";
import { deriveServerHmac } from "./account_identity.js";
import { AdmissionState } from "./admission_contracts.js";
import { RemoteCapability } from "./capability_policy.js";

export const IDEMPOTENCY_KEY_MIN_LENGTH = 16;
export const IDEMPOTENCY_KEY_MAX_LENGTH = 128;

const ACCOUNT_KEY_PATTERN = /^acct_v1_[A-Za-z0-9_-]{43}$/;
const REQUEST_KEY_PATTERN = /^idem_v1_[A-Za-z0-9_-]{43}$/;
const FINGERPRINT_PATTERN = /^req_v1_[A-Za-z0-9_-]{43}$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._~-]+$/;
const KNOWN_CAPABILITIES = new Set(Object.values(RemoteCapability));

export function normalizeIdempotencyKey(rawKey) {
  if (rawKey === undefined || rawKey === null || rawKey === "") {
    throw new IdempotencyRequiredError();
  }
  if (
    typeof rawKey !== "string" ||
    rawKey.length < IDEMPOTENCY_KEY_MIN_LENGTH ||
    rawKey.length > IDEMPOTENCY_KEY_MAX_LENGTH ||
    !IDEMPOTENCY_KEY_PATTERN.test(rawKey)
  ) {
    throw new IdempotencyInvalidError();
  }
  return rawKey;
}

function canonicalJson(value, ancestors = new Set()) {
  if (value === null) return "null";
  if (typeof value === "string" || typeof value === "boolean") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new RemoteAdmissionUnavailableError();
    return JSON.stringify(value);
  }
  if (typeof value !== "object") throw new RemoteAdmissionUnavailableError();
  if (ancestors.has(value)) throw new RemoteAdmissionUnavailableError();

  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      return `[${value.map((item) => canonicalJson(item, ancestors)).join(",")}]`;
    }
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new RemoteAdmissionUnavailableError();
    }
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key], ancestors)}`)
      .join(",")}}`;
  } finally {
    ancestors.delete(value);
  }
}

export function deriveRequestIdentity({
  accountKey,
  idempotencyKey,
  capability,
  validatedRequest,
  hmacSecret
} = {}) {
  if (
    typeof accountKey !== "string" ||
    !ACCOUNT_KEY_PATTERN.test(accountKey) ||
    !KNOWN_CAPABILITIES.has(capability)
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  const normalizedKey = normalizeIdempotencyKey(idempotencyKey);
  const fingerprintInput = canonicalJson({
    accountKey,
    capability,
    request: validatedRequest
  });
  const requestKey = deriveServerHmac({
    hmacSecret,
    purpose: "idempotency-key",
    value: `${accountKey}\0${normalizedKey}`
  });
  const fingerprint = deriveServerHmac({
    hmacSecret,
    purpose: "request-fingerprint",
    value: fingerprintInput
  });

  return Object.freeze({
    requestKey: `idem_v1_${requestKey}`,
    fingerprint: `req_v1_${fingerprint}`
  });
}

function validRequestIdentity(value) {
  return value !== null &&
    typeof value === "object" &&
    REQUEST_KEY_PATTERN.test(value.requestKey) &&
    FINGERPRINT_PATTERN.test(value.fingerprint);
}

export function resolveIdempotencyRequest({ existingRequest, requestIdentity } = {}) {
  if (!validRequestIdentity(requestIdentity)) {
    throw new RemoteAdmissionUnavailableError();
  }
  if (existingRequest === null) return Object.freeze({ outcome: "NEW" });
  if (
    !validRequestIdentity(existingRequest) ||
    existingRequest.requestKey !== requestIdentity.requestKey
  ) {
    throw new RemoteAdmissionUnavailableError();
  }
  if (existingRequest.fingerprint !== requestIdentity.fingerprint) {
    throw new IdempotencyConflictError();
  }
  if (
    existingRequest.state === AdmissionState.RESERVED ||
    existingRequest.state === AdmissionState.DISPATCHED
  ) {
    throw new RemoteRequestInProgressError();
  }
  if ([
    AdmissionState.SUCCEEDED,
    AdmissionState.FAILED_PRE_DISPATCH,
    AdmissionState.FAILED_POST_DISPATCH,
    AdmissionState.EXPIRED
  ].includes(existingRequest.state)) {
    throw new RemoteRequestCompletedError();
  }
  throw new RemoteAdmissionUnavailableError();
}
