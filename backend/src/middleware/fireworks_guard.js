import { createHash } from "node:crypto";
import {
  CircuitOpenError,
  ConcurrencyLimitError,
  RateLimitError,
  ValidationError
} from "../errors.js";

const INSTALLATION_HEADER = "x-fitdesi-installation-id";
const MIN_INSTALLATION_ID_LENGTH = 16;
const MAX_INSTALLATION_ID_LENGTH = 128;
const INSTALLATION_ID_PATTERN = /^[A-Za-z0-9._:-]+$/;
const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;

function defaultHashIdentifier(value) {
  return createHash("sha256").update(value).digest("hex").slice(0, 32);
}

function positiveInteger(value, name) {
  if (!Number.isInteger(value) || value < 1) {
    throw new TypeError(`${name} must be a positive integer.`);
  }
  return value;
}

function boundedInstallationId(value) {
  if (value == null || value === "") return null;
  if (typeof value !== "string") {
    throw new ValidationError(`${INSTALLATION_HEADER} must be a single string header.`);
  }
  const normalized = value.trim();
  if (normalized.length < MIN_INSTALLATION_ID_LENGTH
      || normalized.length > MAX_INSTALLATION_ID_LENGTH
      || !INSTALLATION_ID_PATTERN.test(normalized)) {
    throw new ValidationError(
      `${INSTALLATION_HEADER} must be ${MIN_INSTALLATION_ID_LENGTH}-${MAX_INSTALLATION_ID_LENGTH} characters using letters, numbers, dot, underscore, colon, or hyphen.`
    );
  }
  return normalized;
}

/**
 * Reads the optional, non-secret installation identifier used only for abuse
 * controls. The raw value must never be logged or retained by the guard.
 */
export function readInstallationId(request) {
  return boundedInstallationId(request.headers[INSTALLATION_HEADER]);
}

function nextBucket(current, timestamp, windowMs) {
  if (!current || timestamp >= current.resetAt) {
    return { count: 1, resetAt: timestamp + windowMs };
  }
  return { count: current.count + 1, resetAt: current.resetAt };
}

function retryAfterSeconds(resetAt, timestamp) {
  return Math.max(1, Math.ceil((resetAt - timestamp) / 1000));
}

/**
 * Creates the in-process Fireworks abuse guard used by the coach route.
 *
 * `acquire` performs atomic minute/day rate checks, circuit checking, and
 * concurrency acquisition. Its returned lease must be completed with
 * `succeed()` or `fail()` and always released from a `finally` block.
 * Identifiers retained in maps are one-way hashes; raw IP/installation values
 * are never returned by snapshots or stored as keys.
 */
export function createFireworksGuard({
  ipRequestsPerMinute,
  installationRequestsPerMinute,
  installationRequestsPerDay,
  maxConcurrentRequests,
  maxConcurrentPerInstallation,
  circuitFailureThreshold,
  circuitResetMs,
  now = Date.now,
  hashIdentifier = defaultHashIdentifier
}) {
  const limits = {
    ipRequestsPerMinute: positiveInteger(ipRequestsPerMinute, "ipRequestsPerMinute"),
    installationRequestsPerMinute: positiveInteger(
      installationRequestsPerMinute,
      "installationRequestsPerMinute"
    ),
    installationRequestsPerDay: positiveInteger(
      installationRequestsPerDay,
      "installationRequestsPerDay"
    ),
    maxConcurrentRequests: positiveInteger(maxConcurrentRequests, "maxConcurrentRequests"),
    maxConcurrentPerInstallation: positiveInteger(
      maxConcurrentPerInstallation,
      "maxConcurrentPerInstallation"
    ),
    circuitFailureThreshold: positiveInteger(circuitFailureThreshold, "circuitFailureThreshold"),
    circuitResetMs: positiveInteger(circuitResetMs, "circuitResetMs")
  };

  const ipMinuteBuckets = new Map();
  const installationMinuteBuckets = new Map();
  const installationDayBuckets = new Map();
  const installationConcurrency = new Map();
  let concurrentRequests = 0;
  let circuitFailures = 0;
  let circuitOpenUntil = 0;

  function hashed(value) {
    const result = hashIdentifier(value);
    if (typeof result !== "string" || !result) {
      throw new TypeError("hashIdentifier must return a non-empty string.");
    }
    return result;
  }

  function prune(timestamp = now()) {
    for (const buckets of [ipMinuteBuckets, installationMinuteBuckets, installationDayBuckets]) {
      for (const [key, bucket] of buckets) {
        if (timestamp >= bucket.resetAt) buckets.delete(key);
      }
    }
    for (const [key, count] of installationConcurrency) {
      if (count <= 0) installationConcurrency.delete(key);
    }
  }

  function assertCircuitAvailable(timestamp) {
    if (circuitOpenUntil > timestamp) {
      throw new CircuitOpenError(retryAfterSeconds(circuitOpenUntil, timestamp));
    }
    if (circuitOpenUntil !== 0) {
      circuitOpenUntil = 0;
      circuitFailures = 0;
    }
  }

  function acquire({ ipAddress, installationId = null }) {
    const timestamp = now();
    prune(timestamp);
    assertCircuitAvailable(timestamp);

    const safeIp = typeof ipAddress === "string" && ipAddress.trim()
      ? ipAddress.trim()
      : "unknown";
    const safeInstallation = boundedInstallationId(installationId);
    const ipKey = hashed(`ip:${safeIp}`);
    // Older clients do not send this optional header. Combining the IP with a
    // fixed fallback bucket preserves both controls without inventing an ID.
    const installationKey = hashed(
      safeInstallation ? `installation:${safeInstallation}` : `installation-fallback:${safeIp}`
    );

    const nextIpMinute = nextBucket(ipMinuteBuckets.get(ipKey), timestamp, MINUTE_MS);
    const nextInstallationMinute = nextBucket(
      installationMinuteBuckets.get(installationKey),
      timestamp,
      MINUTE_MS
    );
    const nextInstallationDay = nextBucket(
      installationDayBuckets.get(installationKey),
      timestamp,
      DAY_MS
    );

    // Check every bucket before committing any counter so a rejection by one
    // policy does not partially consume another policy's allowance.
    const exceeded = [
      [nextIpMinute, limits.ipRequestsPerMinute],
      [nextInstallationMinute, limits.installationRequestsPerMinute],
      [nextInstallationDay, limits.installationRequestsPerDay]
    ].find(([bucket, maximum]) => bucket.count > maximum);
    if (exceeded) throw new RateLimitError(retryAfterSeconds(exceeded[0].resetAt, timestamp));

    if (concurrentRequests >= limits.maxConcurrentRequests
        || (installationConcurrency.get(installationKey) ?? 0)
          >= limits.maxConcurrentPerInstallation) {
      throw new ConcurrencyLimitError();
    }

    ipMinuteBuckets.set(ipKey, nextIpMinute);
    installationMinuteBuckets.set(installationKey, nextInstallationMinute);
    installationDayBuckets.set(installationKey, nextInstallationDay);
    concurrentRequests += 1;
    installationConcurrency.set(
      installationKey,
      (installationConcurrency.get(installationKey) ?? 0) + 1
    );

    let completed = false;
    let released = false;

    function succeed() {
      if (completed) return;
      completed = true;
      circuitFailures = 0;
      circuitOpenUntil = 0;
    }

    function fail({ countsTowardCircuit = true } = {}) {
      if (completed) return;
      completed = true;
      if (!countsTowardCircuit) return;
      circuitFailures += 1;
      if (circuitFailures >= limits.circuitFailureThreshold) {
        circuitOpenUntil = now() + limits.circuitResetMs;
      }
    }

    function release() {
      if (released) return;
      released = true;
      concurrentRequests = Math.max(0, concurrentRequests - 1);
      const current = installationConcurrency.get(installationKey) ?? 0;
      if (current <= 1) installationConcurrency.delete(installationKey);
      else installationConcurrency.set(installationKey, current - 1);
    }

    return Object.freeze({ succeed, fail, release });
  }

  function reset() {
    ipMinuteBuckets.clear();
    installationMinuteBuckets.clear();
    installationDayBuckets.clear();
    installationConcurrency.clear();
    concurrentRequests = 0;
    circuitFailures = 0;
    circuitOpenUntil = 0;
  }

  // Aggregate-only state is intentionally exposed for deterministic tests.
  // It contains neither raw nor hashed client identifiers.
  function snapshot() {
    return Object.freeze({
      ipMinuteBucketCount: ipMinuteBuckets.size,
      installationMinuteBucketCount: installationMinuteBuckets.size,
      installationDayBucketCount: installationDayBuckets.size,
      concurrentRequests,
      concurrentInstallationCount: installationConcurrency.size,
      circuitFailures,
      circuitOpenUntil
    });
  }

  return Object.freeze({ acquire, prune, reset, snapshot });
}
