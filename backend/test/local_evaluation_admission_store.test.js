import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { LocalEvaluationAdmissionStore, LOCAL_EVALUATION_POLICY_VERSION,
  validateLocalEvaluationLedgerPath } from "../src/evaluation/local_evaluation_admission_store.js";
import { deriveServerHmac } from "../src/remote_admission/account_identity.js";
import { createQuotaPolicy } from "../src/remote_admission/quota_policy.js";
import { AdmissionState as State } from "../src/remote_admission/admission_contracts.js";

async function fixture(t, limits = {}) {
  const directory = await fs.mkdtemp(join(tmpdir(), "fitdesi-ledger-test-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const ledgerPath = join(directory, "ledger.json");
  const hmacSecret = randomBytes(32).toString("hex");
  let now = Date.now();
  const options = { directory, ledgerPath, hmacSecret, leaseDurationSeconds: 30, clock: () => now };
  const policy = createQuotaPolicy({ policyVersion: LOCAL_EVALUATION_POLICY_VERSION,
    globalProviderAttempts: { limit: limits.global ?? 8, windowSeconds: 3600 },
    rules: [{ capability: "REMOTE_AI_COACH", tier: "BASIC",
      successfulUses: { limit: limits.success ?? 4, windowSeconds: 3600 },
      providerAttempts: { limit: limits.attempts ?? 8, windowSeconds: 3600 } }]
  }).getPolicy({ capability: "REMOTE_AI_COACH", tier: "BASIC" });
  const identity = (number = 1) => ({ requestKey: `idem_v1_${deriveServerHmac({ hmacSecret,
    purpose: "test-request", value: String(number) })}`, fingerprint: `req_v1_${deriveServerHmac({
      hmacSecret, purpose: "test-fingerprint", value: String(number) })}` });
  const account = (number = 1) => `acct_v1_${deriveServerHmac({ hmacSecret,
    purpose: "test-account", value: String(number) })}`;
  const reserve = (store, number = 1, accountNumber = 1) => store.reserve({ accountKey: account(accountNumber),
    requestIdentity: identity(number), capability: "REMOTE_AI_COACH", policy });
  return { options, policy, identity, reserve, account, ledgerPath, hmacSecret,
    store: new LocalEvaluationAdmissionStore(options), advance: (ms) => { now += ms; },
    read: async () => JSON.parse(await fs.readFile(ledgerPath, "utf8")),
    async rewrite(payload) { await fs.writeFile(ledgerPath, JSON.stringify({ payload, integrity:
      deriveServerHmac({ hmacSecret, purpose: "evaluation-ledger", value: JSON.stringify(payload) }) })); } };
}

for (const state of [State.SUCCEEDED, State.FAILED_PRE_DISPATCH, State.FAILED_POST_DISPATCH]) {
  test(`durable ${state} settlement survives reconstruction and blocks replay`, async (t) => {
    const f = await fixture(t);
    assert.deepEqual(await f.reserve(f.store), { state: State.RESERVED });
    if (state !== State.FAILED_PRE_DISPATCH) {
      assert.deepEqual(await f.store.recordProviderAttempt({ requestIdentity: f.identity() }), { state: State.DISPATCHED });
      assert.equal((await f.read()).payload.providerAttemptCount, 1);
    }
    await f.store.transition({ requestIdentity: f.identity(), toState: state });
    const data = (await f.read()).payload;
    assert.equal(data.requests[f.identity().requestKey].state, state);
    assert.equal(data.successfulUseCount, state === State.SUCCEEDED ? 1 : 0);
    await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options)), { code: "REMOTE_REQUEST_COMPLETED" });
  });
}

test("a fresh Node process reads the settled ledger and cannot reserve a replay", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  await f.store.transition({ requestIdentity: f.identity(), toState: State.FAILED_PRE_DISPATCH });
  const moduleUrl = new URL("../src/evaluation/local_evaluation_admission_store.js", import.meta.url).href;
  const script = `import { LocalEvaluationAdmissionStore } from ${JSON.stringify(moduleUrl)};
    const data = JSON.parse(process.env.FITDESI_LEDGER_TEST);
    try { await new LocalEvaluationAdmissionStore(data.options).reserve(data.request); process.exitCode = 1; }
    catch (error) { if (error.code !== "REMOTE_REQUEST_COMPLETED") process.exitCode = 2; }`;
  const child = spawn(process.execPath, ["--input-type=module", "-e", script], { stdio: "ignore",
    env: { ...process.env, FITDESI_LEDGER_TEST: JSON.stringify({ options: { ...f.options, clock: undefined },
      request: { accountKey: f.account(), requestIdentity: f.identity(), capability: "REMOTE_AI_COACH", policy: f.policy } }) } });
  const code = await new Promise((resolve, reject) => { child.once("error", reject); child.once("exit", resolve); });
  assert.equal(code, 0);
});

test("active replay and fingerprint conflicts fail closed without changing counters", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options)), { code: "REMOTE_REQUEST_IN_PROGRESS" });
  await assert.rejects(f.store.recordProviderAttempt({ requestIdentity: { ...f.identity(), fingerprint: f.identity(2).fingerprint } }),
    { code: "IDEMPOTENCY_CONFLICT" });
  assert.equal((await f.read()).payload.providerAttemptCount, 0);
});

test("successful-use holds and settled consumption enforce the explicit quota", async (t) => {
  const f = await fixture(t, { success: 1 });
  await f.reserve(f.store);
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED" });
  await f.store.recordProviderAttempt({ requestIdentity: f.identity() });
  await f.store.transition({ requestIdentity: f.identity(), toState: State.SUCCEEDED });
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED" });
});

test("account attempt quota charges each authorization and survives failure/reconstruction", async (t) => {
  const f = await fixture(t, { attempts: 1 });
  await f.reserve(f.store);
  await f.store.recordProviderAttempt({ requestIdentity: f.identity() });
  await assert.rejects(f.store.recordProviderAttempt({ requestIdentity: f.identity() }), { code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED" });
  await f.store.transition({ requestIdentity: f.identity(), toState: State.FAILED_POST_DISPATCH });
  const restarted = new LocalEvaluationAdmissionStore(f.options);
  await f.reserve(restarted, 2);
  await assert.rejects(restarted.recordProviderAttempt({ requestIdentity: f.identity(2) }), { code: "REMOTE_ACCOUNT_QUOTA_EXHAUSTED" });
  assert.equal((await f.read()).payload.providerAttemptCount, 1);
});

test("global attempt budget spans opaque accounts", async (t) => {
  const f = await fixture(t, { global: 1 });
  await f.reserve(f.store);
  await f.store.recordProviderAttempt({ requestIdentity: f.identity() });
  await f.reserve(f.store, 2, 2);
  await assert.rejects(f.store.recordProviderAttempt({ requestIdentity: f.identity(2) }), { code: "REMOTE_GLOBAL_BUDGET_UNAVAILABLE" });
});

test("quota windows advance without deleting old replay protection or charges", async (t) => {
  const f = await fixture(t, { success: 1, attempts: 1, global: 1 });
  await f.reserve(f.store);
  await f.store.recordProviderAttempt({ requestIdentity: f.identity() });
  await f.store.transition({ requestIdentity: f.identity(), toState: State.SUCCEEDED });
  f.advance(3_600_000);
  await f.reserve(f.store, 2);
  await f.store.recordProviderAttempt({ requestIdentity: f.identity(2) });
  assert.equal((await f.read()).payload.providerAttemptCount, 2);
  await assert.rejects(f.reserve(f.store), { code: "REMOTE_REQUEST_COMPLETED" });
});

for (const dispatched of [false, true]) {
  test(`expired ${dispatched ? "dispatched" : "reserved"} lease is terminal with charges retained`, async (t) => {
    const f = await fixture(t, { success: 1 });
    await f.reserve(f.store);
    if (dispatched) await f.store.recordProviderAttempt({ requestIdentity: f.identity() });
    f.advance(30_000);
    assert.deepEqual(await f.store.recordProviderAttempt({ requestIdentity: f.identity() }), { state: State.EXPIRED });
    const data = (await f.read()).payload;
    assert.equal(data.providerAttemptCount, dispatched ? 1 : 0);
    assert.equal(data.successfulUseCount, 0);
    await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options)), { code: "REMOTE_REQUEST_COMPLETED" });
    await f.reserve(f.store, 2); // Only the old successful-use hold is released.
  });
}

test("invalid transitions and clock rollback cannot change state", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  for (const toState of [State.SUCCEEDED, State.FAILED_POST_DISPATCH, State.EXPIRED, "UNKNOWN"]) {
    await assert.rejects(f.store.transition({ requestIdentity: f.identity(), toState }));
  }
  f.advance(-1);
  await assert.rejects(f.store.recordProviderAttempt({ requestIdentity: f.identity() }), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
});

const corruptions = {
  "unknown schema": (p) => { p.schemaVersion = 2; },
  "extra field": (p) => { p.prompt = "forbidden"; },
  "unknown policy": (p) => { p.policyVersion = "unexpected"; },
  "unknown state": (p, r) => { r.state = "UNKNOWN"; },
  "unknown capability": (p, r) => { r.capability = "OTHER"; },
  "non-string opaque account": (p, r) => { r.accountKey = [r.accountKey]; },
  "non-string fingerprint": (p, r) => { r.fingerprint = [r.fingerprint]; },
  "negative counter": (p) => { p.providerAttemptCount = -1; },
  "overflow counter": (p) => { p.revision = Number.MAX_SAFE_INTEGER; },
  "inconsistent counter": (p) => { p.providerAttemptCount = 1; },
  "invalid timestamps": (p, r) => { r.updatedAt = r.createdAt - 1; },
  "invalid lease": (p, r) => { r.leaseExpiresAt = r.createdAt; },
  "unbounded attempt history": (p, r) => { r.attemptTimestamps = Array(65).fill(r.createdAt); r.attemptCount = 65; },
  "extra record field": (p, r) => { r.response = "forbidden"; },
  "invalid bucket": (p) => { p.policy.successfulUses.limit = 0; },
  "unknown bucket field": (p) => { p.policy.providerAttempts.unlimited = true; },
  "inconsistent state/attempt count": (p, r) => { r.state = State.SUCCEEDED; }
};
for (const [name, mutate] of Object.entries(corruptions)) {
  test(`${name} fails closed even with a matching integrity signature`, async (t) => {
    const f = await fixture(t);
    await f.reserve(f.store);
    const { payload } = await f.read();
    mutate(payload, payload.requests[f.identity().requestKey]);
    await f.rewrite(payload);
    await assert.rejects(f.store.recordProviderAttempt({ requestIdentity: f.identity() }), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  });
}
for (const bytes of ["{", "not-json", "", '{"payload":{},"integrity":"invalid"}']) {
  test(`malformed/truncated ledger (${JSON.stringify(bytes)}) cannot reset accounting`, async (t) => {
    const f = await fixture(t);
    await f.reserve(f.store);
    await fs.writeFile(f.ledgerPath, bytes);
    await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
    assert.equal(await fs.readFile(f.ledgerPath, "utf8"), bytes);
  });
}

test("tampered integrity, wrong HMAC and missing initialized ledger fail closed", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  const original = await fs.readFile(f.ledgerPath, "utf8");
  const data = JSON.parse(original);
  data.integrity = "A".repeat(43);
  await fs.writeFile(f.ledgerPath, JSON.stringify(data));
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  await fs.writeFile(f.ledgerPath, original);
  await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore({ ...f.options, hmacSecret: randomBytes(32).toString("hex") }), 2),
    { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  await fs.unlink(f.ledgerPath);
  await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options), 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
});

for (const phase of ["writeFile", "sync", "rename"]) {
  test(`${phase} failure preserves the previous snapshot and leaves an uncertain lock`, async (t) => {
    const f = await fixture(t);
    await f.reserve(f.store);
    const before = await fs.readFile(f.ledgerPath, "utf8");
    const fileSystem = { ...fs, async open(path, ...args) {
      const handle = await fs.open(path, ...args);
      if (!path.endsWith(".tmp") || phase === "rename") return handle;
      return { writeFile: phase === "writeFile" ? async () => { throw new Error("private filesystem error"); } : handle.writeFile.bind(handle),
        sync: phase === "sync" ? async () => { throw new Error("private filesystem error"); } : handle.sync.bind(handle),
        close: handle.close.bind(handle) };
    }, rename: phase === "rename" ? async () => { throw new Error("private filesystem error"); } : fs.rename };
    const failing = new LocalEvaluationAdmissionStore({ ...f.options, fileSystem });
    await assert.rejects(failing.recordProviderAttempt({ requestIdentity: f.identity() }), { code: "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE" });
    assert.equal(await fs.readFile(f.ledgerPath, "utf8"), before);
    assert.equal((await fs.stat(`${f.ledgerPath}.lock`)).isFile(), true);
    await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options), 2), { code: "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE" });
  });
}

test("atomic lock prevents a simultaneous second writer and never deletes a pre-existing lock", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  let release;
  let entered;
  const blocked = new Promise((resolve) => { release = resolve; });
  const waiting = new Promise((resolve) => { entered = resolve; });
  const first = new LocalEvaluationAdmissionStore({ ...f.options, fileSystem: { ...fs,
    async rename(...args) { entered(); await blocked; return fs.rename(...args); } } });
  const writing = first.recordProviderAttempt({ requestIdentity: f.identity() });
  await waiting;
  try {
    await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options), 2), { code: "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE" });
    assert.equal((await f.read()).payload.providerAttemptCount, 0);
    assert.equal((await fs.stat(`${f.ledgerPath}.lock`)).isFile(), true);
  } finally { release(); }
  await writing;
  await fs.writeFile(`${f.ledgerPath}.lock`, "operator-inspection-required");
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE" });
  assert.equal(await fs.readFile(`${f.ledgerPath}.lock`, "utf8"), "operator-inspection-required");
});

test("live path validation rejects arbitrary locations and traversal", () => {
  for (const path of [undefined, "", ".", "backend/ledger.json", "backend/.evaluation/../ledger.json",
    "backend/.evaluation/nested/ledger.json", join(tmpdir(), "ledger.json"), "backend/.evaluation/ledger.json:stream"]) {
    assert.throws(() => validateLocalEvaluationLedgerPath(path));
  }
  assert.equal(validateLocalEvaluationLedgerPath(fileURLToPath(new URL("../.evaluation/pilot.json", import.meta.url)))
    .endsWith("pilot.json"), true);
});

test("policy replacement is rejected instead of resetting quotas", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  await assert.rejects(f.store.reserve({ accountKey: f.account(), requestIdentity: f.identity(2), capability: "REMOTE_AI_COACH",
    policy: { ...f.policy, providerAttempts: { ...f.policy.providerAttempts, limit: 999 } } }), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
});

test("record capacity is bounded without evicting replay protection", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  const { payload } = await f.read();
  const record = { ...Object.values(payload.requests)[0], state: State.FAILED_PRE_DISPATCH };
  payload.requests = Object.fromEntries(Array.from({ length: 256 }, (_, i) => [f.identity(i).requestKey,
    { ...record, fingerprint: f.identity(i).fingerprint }]));
  await f.rewrite(payload);
  await assert.rejects(f.reserve(f.store, 300), { code: "REMOTE_ADMISSION_UNAVAILABLE" });
  assert.equal(Object.keys((await f.read()).payload.requests).length, 256);
  payload.requests[f.identity(300).requestKey] = { ...record, fingerprint: f.identity(300).fingerprint };
  await f.rewrite(payload);
  await assert.rejects(f.reserve(f.store, 301), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
});

test("oversized ledger and missing initialization marker fail closed", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  const original = await fs.readFile(f.ledgerPath, "utf8");
  await fs.writeFile(f.ledgerPath, " ".repeat(1_048_577));
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  await fs.writeFile(f.ledgerPath, original);
  await fs.unlink(`${f.ledgerPath}.initialized`);
  await assert.rejects(f.reserve(new LocalEvaluationAdmissionStore(f.options), 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
});

test("linked ledgers and redirected evaluation directories fail closed", async (t) => {
  const f = await fixture(t);
  await f.reserve(f.store);
  const directory = f.options.directory;
  await fs.link(f.ledgerPath, join(directory, "linked.json"));
  await assert.rejects(f.reserve(f.store, 2), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  const actual = join(directory, "actual");
  const redirect = join(directory, "redirect");
  await fs.mkdir(actual);
  await fs.symlink(actual, redirect, process.platform === "win32" ? "junction" : "dir");
  const redirected = new LocalEvaluationAdmissionStore({ ...f.options, directory: redirect, ledgerPath: join(redirect, "ledger.json") });
  await assert.rejects(f.reserve(redirected), { code: "REMOTE_ADMISSION_INVALID_RECORD" });
  await assert.rejects(fs.stat(join(actual, "ledger.json")), { code: "ENOENT" });
});
