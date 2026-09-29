import assert from "node:assert/strict";
import { test } from "node:test";
import { createFireworksGuard, readInstallationId } from "../src/middleware/fireworks_guard.js";

function guard(overrides = {}) {
  return createFireworksGuard({
    ipRequestsPerMinute: 2,
    installationRequestsPerMinute: 2,
    installationRequestsPerDay: 3,
    maxConcurrentRequests: 2,
    maxConcurrentPerInstallation: 1,
    circuitFailureThreshold: 2,
    circuitResetMs: 1000,
    hashIdentifier: (value) => `hash:${value}`,
    ...overrides
  });
}

test("Fireworks guard enforces installation and IP rate limits", () => {
  let now = 0;
  const subject = guard({ now: () => now });
  for (let index = 0; index < 2; index += 1) {
    const lease = subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" });
    lease.succeed();
    lease.release();
  }
  assert.throws(
    () => subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" }),
    (error) => error.code === "RATE_LIMITED"
  );
  now = 60_001;
  const lease = subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" });
  lease.succeed();
  lease.release();
  assert.throws(
    () => subject.acquire({ ipAddress: "127.0.0.2", installationId: "installation-0001" }),
    (error) => error.code === "RATE_LIMITED"
  );
});

test("Fireworks guard enforces per-installation concurrency", () => {
  const subject = guard();
  const lease = subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" });
  assert.throws(
    () => subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" }),
    (error) => error.code === "PROVIDER_BUSY"
  );
  lease.release();
});

test("Fireworks guard enforces global concurrency", () => {
  const subject = guard({ maxConcurrentRequests: 1, maxConcurrentPerInstallation: 1 });
  const lease = subject.acquire({ ipAddress: "127.0.0.1", installationId: "installation-0001" });
  assert.throws(
    () => subject.acquire({ ipAddress: "127.0.0.2", installationId: "installation-0002" }),
    (error) => error.code === "PROVIDER_BUSY"
  );
  lease.release();
});

test("Fireworks circuit opens only after counted failures and resets", () => {
  let now = 0;
  const subject = guard({ now: () => now, ipRequestsPerMinute: 20, installationRequestsPerMinute: 20 });
  for (let index = 0; index < 2; index += 1) {
    const lease = subject.acquire({ ipAddress: `127.0.0.${index}`, installationId: `installation-000${index}` });
    lease.fail({ countsTowardCircuit: true });
    lease.release();
  }
  assert.throws(
    () => subject.acquire({ ipAddress: "127.0.0.9", installationId: "installation-0009" }),
    (error) => error.code === "PROVIDER_CIRCUIT_OPEN"
  );
  now = 1001;
  const recovered = subject.acquire({ ipAddress: "127.0.0.9", installationId: "installation-0009" });
  recovered.succeed();
  recovered.release();
});

test("installation header is bounded and never required for legacy clients", () => {
  assert.equal(readInstallationId({ headers: {} }), null);
  assert.equal(
    readInstallationId({ headers: { "x-fitdesi-installation-id": "installation-0001" } }),
    "installation-0001"
  );
  assert.throws(
    () => readInstallationId({ headers: { "x-fitdesi-installation-id": "short" } }),
    (error) => error.code === "VALIDATION_ERROR"
  );
});
