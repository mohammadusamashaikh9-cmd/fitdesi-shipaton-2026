import assert from "node:assert/strict";
import test from "node:test";
import * as fs from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { createControlledEvaluationRunner } from "../src/evaluation/controlled_evaluation_runner.js";
import { createDurableEvaluationAdmission } from "../src/evaluation/durable_evaluation_admission.js";
import { LocalEvaluationAdmissionStore, LOCAL_EVALUATION_DIRECTORY } from "../src/evaluation/local_evaluation_admission_store.js";
import { OPENROUTER_EVALUATION_PROFILES, FIREWORKS_EVALUATION_PROFILES, PRODUCTION_OPENROUTER_ROUTE_PROFILES } from "../src/services/provider_route_policy.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";
import { loadConfig } from "../src/config.js";
import { DEFAULT_QUOTA_POLICY } from "../src/remote_admission/quota_policy.js";

const profile = OPENROUTER_EVALUATION_PROFILES.find((value) => value.routeProfileId === "eval-openrouter-glm-5-2-free");
const selection = { routeProfileId: profile.routeProfileId, caseIds: ["coach-basic"] };
const RUN_ID = "00000000-0000-4000-8000-000000000001";
const environment = () => ({ approved: true, accountingMode: "LOCAL_DURABLE_LEDGER", evaluationRunId: RUN_ID,
  hmacSecret: randomBytes(32).toString("hex"), ledgerPath: join(LOCAL_EVALUATION_DIRECTORY, "test-unused.json"),
  limits: { successfulUses: { limit: 14, windowSeconds: 3600 }, providerAttempts: { limit: 28, windowSeconds: 3600 },
    globalProviderAttempts: { limit: 84, windowSeconds: 3600 } } });

test("contract diagnostics reach controlled reports but never the durable ledger", async (t) => {
  const responses = Array.from({ length: 2 }, () => ({
    choices: [{ message: { content: JSON.stringify({ unexpected: "private synthetic model marker" }) } }]
  }));
  const current = await fixture(t, { sequence: responses });
  const [report] = await current.runner.run(selection);
  assert.equal(report.failureCategory, "PROVIDER_INVALID_RESPONSE");
  assert.equal(report.contractDiagnostic, "TOP_LEVEL_CONTRACT_INVALID");
  assert.equal(report.contractPass, false);
  assert.equal(report.qualityResult, null);
  assert.equal(report.attemptsUsed, 2);
  assert.deepEqual(report.execution.attempts.map((attempt) => attempt.attemptClassification), ["initial", "repair"]);
  assert.deepEqual(report.execution.attempts.map((attempt) => attempt.contractDiagnostic),
    ["TOP_LEVEL_CONTRACT_INVALID", "TOP_LEVEL_CONTRACT_INVALID"]);
  assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "DISPATCHED", "dispatch", "FAILED_POST_DISPATCH"]);
  const ledger = await current.read();
  assert.equal(ledger.providerAttemptCount, 2);
  assert.equal(ledger.successfulUseCount, 0);
  const encodedLedger = JSON.stringify(ledger);
  assert.equal(encodedLedger.includes("contractDiagnostic"), false);
  assert.equal(encodedLedger.includes("TOP_LEVEL_CONTRACT_INVALID"), false);
  for (const encoded of [encodedLedger, JSON.stringify(report)]) {
    for (const privateValue of ["private synthetic model marker", "INJECTED_FETCH_ONLY",
      current.environment.hmacSecret, getSyntheticCoachCase("coach-basic").input.question]) {
      assert.equal(encoded.includes(privateValue), false);
    }
  }
});

async function fixture(t, { sequence, approvedEnvironment = environment(), storeFailure = false,
  secondAttemptFailure = false, configOverrides = {} } = {}) {
  const directory = await fs.mkdtemp(join(tmpdir(), "fitdesi-runner-test-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const ledgerPath = join(directory, "ledger.json");
  const events = [];
  const calls = [];
  const fireworksClients = [];
  let mutations = 0;
  const responses = sequence ?? [{ model: "nex-agi/nex-n2.5-pro-served", provider: "DeepInfra",
    choices: [{ message: { content: JSON.stringify(getSyntheticCoachCase("coach-basic").expectedResponse) } }] }];
  const read = async () => JSON.parse(await fs.readFile(ledgerPath, "utf8")).payload;
  const construct = (approved = approvedEnvironment) => createControlledEvaluationRunner({ environment: approved,
    config: loadConfig({ openrouterApiKey: "INJECTED_FETCH_ONLY", ...configOverrides }),
    storeFactory(options) {
      const store = new LocalEvaluationAdmissionStore({ ...options, directory, ledgerPath,
        fileSystem: { ...fs, async rename(...args) {
          mutations += 1;
          if (storeFailure || (secondAttemptFailure && mutations === 3)) throw new Error("private accounting failure");
          return fs.rename(...args);
        } } });
      return Object.fromEntries(["reserve", "recordProviderAttempt", "transition"].map((method) => [method,
        async (request) => { const result = await store[method](request); events.push(result.state); return result; }]));
    },
    fireworksClientFactory(options) {
      fireworksClients.push(options);
      return { chat: { completions: { async create(body, dispatchOptions) {
        const data = await read();
        assert.equal(data.providerAttemptCount, calls.length + 1);
        assert.equal(events.at(-1), "DISPATCHED");
        events.push("dispatch"); calls.push({ body, options: dispatchOptions, providerAlias: "fireworks" });
        const response = responses.shift();
        if (response instanceof Error) throw response;
        return response;
      } } } };
    },
    async fetchImpl(url, options) {
      const data = await read();
      assert.ok(Object.values(data.requests).some((r) => r.state === "DISPATCHED" && r.attemptCount > 0));
      // No network: verify the persisted charge at the injected fetch boundary.
      assert.equal(data.providerAttemptCount, calls.length + 1);
      assert.equal(events.at(-1), "DISPATCHED");
      events.push("dispatch"); calls.push({ url, body: JSON.parse(options.body), options });
      const response = responses.shift();
      if (response instanceof Error) {
        if (response.status) return new Response("private provider failure body", { status: response.status, headers: response.headers });
        throw response;
      }
      return Response.json(response);
    }
  });
  return { runner: construct(), construct, environment: approvedEnvironment, read, events, calls, ledgerPath, fireworksClients };
}

test("all Fireworks profiles run through durable admission without OpenRouter credentials or production activation", async (t) => {
  for (const candidate of FIREWORKS_EVALUATION_PROFILES) {
    const current = await fixture(t, { configOverrides: { fireworksApiKey: "INJECTED_CLIENT_ONLY", openrouterApiKey: "",
      fireworksEvaluationTimeoutMs: 45000, fireworksEvaluationMaxOutputTokens: 3072 },
      sequence: [{ provider: "Fireworks", model: candidate.modelId,
        choices: [{ message: { content: JSON.stringify(getSyntheticCoachCase("coach-basic").expectedResponse) } }] }] });
    assert.equal(current.fireworksClients.length, 0);
    const [report] = await current.runner.run({ ...selection, routeProfileId: candidate.routeProfileId });
    assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "SUCCEEDED"]);
    assert.equal(current.calls[0].providerAlias, "fireworks");
    assert.equal(current.calls[0].body.model, candidate.modelId);
    assert.equal(current.calls[0].body.max_tokens, 3072);
    assert.equal(current.calls[0].body.response_format.json_schema.strict, true);
    assert.equal(current.fireworksClients[0].timeout, 45000);
    assert.equal(current.fireworksClients[0].maxRetries, 0);
    assert.equal(report.actualProvider, "fireworks");
    assert.equal(report.actualModelId, candidate.modelId);
    assert.equal(report.configuredModelId, candidate.modelId);
    assert.equal(report.contractPass, true);
    assert.equal(report.contractDiagnostic, null);
    assert.equal(report.qualityResult, null);
    assert.equal((await current.read()).successfulUseCount, 1);
  }
});

test("Fireworks controlled repair is bounded, sanitized and absent from durable accounting", async (t) => {
  const current = await fixture(t, { configOverrides: { fireworksApiKey: "INJECTED_CLIENT_ONLY" },
    sequence: Array.from({ length: 3 }, () => ({ choices: [{ message: { content: "not-json" } }] })) });
  const [report] = await current.runner.run({ ...selection, routeProfileId: FIREWORKS_EVALUATION_PROFILES.at(-1).routeProfileId });
  assert.equal(current.calls.length, 2);
  assert.equal(report.attemptsUsed, 2);
  assert.equal(report.failureCategory, "PROVIDER_INVALID_RESPONSE");
  assert.equal(report.contractDiagnostic, "JSON_PARSE_FAILED");
  assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "DISPATCHED", "dispatch", "FAILED_POST_DISPATCH"]);
  for (const call of current.calls) assert.deepEqual(call.body.chat_template_kwargs, { enable_thinking: false });
  const durable = await current.read();
  assert.equal(durable.providerAttemptCount, 2);
  assert.equal(durable.successfulUseCount, 0);
  for (const value of ["contractDiagnostic", "JSON_PARSE_FAILED", "chat_template_kwargs"]) {
    assert.equal(JSON.stringify(durable).includes(value), false);
  }
  for (const encoded of [JSON.stringify(report), JSON.stringify(durable)]) {
    for (const value of ["not-json", "INJECTED_CLIENT_ONLY", current.environment.hmacSecret,
      getSyntheticCoachCase("coach-basic").input.question]) assert.equal(encoded.includes(value), false);
  }
});

test("missing selected Fireworks credential and second authorization failure never cause an extra SDK call", async (t) => {
  const selected = { ...selection, routeProfileId: FIREWORKS_EVALUATION_PROFILES[0].routeProfileId };
  const missing = await fixture(t, { configOverrides: { fireworksApiKey: "" } });
  const [failure] = await missing.runner.run(selected);
  assert.equal(failure.failureCategory, "PROVIDER_KEY_MISSING");
  assert.equal(missing.calls.length, 0);
  assert.equal(missing.fireworksClients.length, 0);
  const failed = await fixture(t, { secondAttemptFailure: true,
    configOverrides: { fireworksApiKey: "INJECTED_CLIENT_ONLY" },
    sequence: [{ choices: [{ message: { content: "not-json" } }] }] });
  const [report] = await failed.runner.run(selected);
  assert.equal(failed.calls.length, 1);
  assert.equal(report.failureCategory, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal(report.contractDiagnostic, null);
  assert.equal((await failed.read()).providerAttemptCount, 1);
});

test("Fireworks lease validation selects its budget, keeps the margin and fails before store construction", () => {
  let stores = 0;
  const construct = (remoteAdmissionLeaseSeconds, fireworksEvaluationTimeoutMs) => createDurableEvaluationAdmission({
    environment: environment(), routeProfileId: FIREWORKS_EVALUATION_PROFILES[0].routeProfileId,
    config: { remoteAdmissionLeaseSeconds, fireworksEvaluationTimeoutMs, openrouterEvaluationTimeoutMs: 120000 },
    storeFactory() {
      stores += 1;
      return Object.fromEntries(["reserve", "recordProviderAttempt", "transition"].map((method) =>
        [method, async () => assert.fail("lease validation must not perform storage operations")]));
    }
  });
  assert.throws(() => construct(44, 30000));
  assert.equal(stores, 0);
  assert.doesNotThrow(() => construct(45, 30000));
  assert.throws(() => construct(45, 30001));
  assert.throws(() => construct(134, 120000));
  assert.doesNotThrow(() => construct(135, 120000));
  for (const timeout of [undefined, null, "30000", 0, 999, 120001, 30000.5]) assert.throws(() => construct(600, timeout));
  assert.equal(stores, 2);
});

test("global attempt budget spans OpenRouter and Fireworks candidates in the same ledger", async (t) => {
  const approvedEnvironment = environment();
  approvedEnvironment.limits.globalProviderAttempts.limit = 1;
  const current = await fixture(t, { approvedEnvironment, configOverrides: { fireworksApiKey: "INJECTED_CLIENT_ONLY" } });
  const [first] = await current.runner.run(selection);
  assert.equal(first.contractPass, true);
  const [second] = await current.runner.run({ ...selection, routeProfileId: FIREWORKS_EVALUATION_PROFILES[0].routeProfileId });
  assert.equal(second.failureCategory, "REMOTE_GLOBAL_BUDGET_UNAVAILABLE");
  assert.equal(current.calls.length, 1);
  assert.equal((await current.read()).providerAttemptCount, 1);
});

test("runner rejects arbitrary prompts/models/identities/run configuration before dependency work", async (t) => {
  const current = await fixture(t);
  for (const options of [null, { ...selection, prompt: "private profile" }, { ...selection, input: {} },
    { ...selection, modelId: profile.modelId }, { ...selection, models: [profile.modelId] },
    { ...selection, routingMode: "SYNTHETIC_FREE_TIER" }, { ...selection, privacySettings: {} },
    { ...selection, providerRouting: { zdr: false } }, { ...selection, provider: "openrouter" },
    { ...selection, chat_template_kwargs: { enable_thinking: true } }, { ...selection, enable_thinking: true },
    { ...selection, firebaseUid: "private identity" }, { ...selection, evaluationRunId: RUN_ID },
    { ...selection, ledgerPath: current.ledgerPath }, { ...selection, hmacSecret: current.environment.hmacSecret },
    { ...selection, limits: current.environment.limits }, { ...selection, caseIds: ["unknown"] },
    { ...selection, caseIds: [] }, { ...selection, caseIds: ["coach-basic", "coach-basic"] },
    { routeProfileId: profile.routeProfileId },
    ...["openrouter/free", "openrouter/auto", "@preset/test", profile.modelId, "coach-fireworks-current"]
      .map((routeProfileId) => ({ ...selection, routeProfileId }))
  ]) await assert.rejects(current.runner.run(options));
  assert.deepEqual(current.events, []);
  assert.equal(current.calls.length, 0);
});

test("construction requires exact local environment, stable UUID, HMAC, explicit valid limits and path", () => {
  const valid = environment();
  for (const value of [undefined, {}, { ...valid, approved: false }, { ...valid, accountingMode: "FIRESTORE" },
    ...[undefined, "", "not-a-uuid", "../identity", "00000000-0000-0000-0000-000000000000"]
      .map((evaluationRunId) => ({ ...valid, evaluationRunId })),
    { ...valid, projectId: "fitdesi-ai" }, { ...valid, databaseId: "fitdesi-evaluation" },
    { ...valid, hmacSecret: "" }, { ...valid, limits: undefined }, { ...valid, ledgerPath: undefined },
    { ...valid, limits: { ...valid.limits, providerAttempts: { limit: 0, windowSeconds: 3600 } } },
    { ...valid, limits: { ...valid.limits, globalProviderAttempts: { limit: 1, windowSeconds: 0 } } },
    { ...valid, limits: { ...valid.limits, unlimited: true } }
  ]) assert.throws(() => createControlledEvaluationRunner({ environment: value }));
});

test("test store injection cannot weaken the live path validator", () => {
  for (const ledgerPath of ["", join(tmpdir(), "arbitrary.json"), join(LOCAL_EVALUATION_DIRECTORY, "..", "outside.json"),
    join(LOCAL_EVALUATION_DIRECTORY, "nested", "ledger.json")]) {
    let called = false;
    assert.throws(() => createControlledEvaluationRunner({ environment: { ...environment(), ledgerPath },
      storeFactory() { called = true; } }));
    assert.equal(called, false);
  }
});

test("construction/import is lazy and requires no cloud or provider request", async (t) => {
  const current = await fixture(t);
  assert.deepEqual(current.events, []);
  assert.equal(current.calls.length, 0);
  await assert.rejects(fs.stat(current.ledgerPath), { code: "ENOENT" });
  for (const file of ["controlled_evaluation_runner.js", "durable_evaluation_admission.js", "local_evaluation_admission_store.js"]) {
    const source = await fs.readFile(new URL(`../src/evaluation/${file}`, import.meta.url), "utf8");
    assert.equal(/firebase-admin|firebase_admin_app|firestore_remote_admission_store|getFirestore|randomUUID/.test(source), false);
  }
});

test("durable reservation/attempt precede fake dispatch and success consumes one use", async (t) => {
  const current = await fixture(t);
  const [report] = await current.runner.run(selection);
  assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "SUCCEEDED"]);
  assert.equal(current.calls.length, 1);
  assert.equal(report.configuredModelId, profile.modelId);
  assert.equal(report.actualModelId, "nex-agi/nex-n2.5-pro-served");
  assert.equal(report.actualProvider, "deepinfra");
  assert.equal(report.contractPass, true);
  assert.equal(report.qualityReviewRequired, true);
  assert.equal(report.qualityResult, null);
  const data = await current.read();
  assert.equal(data.successfulUseCount, 1);
  assert.equal(data.providerAttemptCount, 1);
  assert.equal(Object.values(data.requests)[0].state, "SUCCEEDED");
  assert.equal(current.calls[0].url, "https://openrouter.ai/api/v1/chat/completions");
  assert.equal(current.calls[0].options.method, "POST");
  assert.deepEqual(current.calls[0].body.provider, { allow_fallbacks: false, require_parameters: true });
});

test("unavailable durable persistence fails before any fake inference", async (t) => {
  const current = await fixture(t, { storeFailure: true });
  const [report] = await current.runner.run(selection);
  assert.equal(current.calls.length, 0);
  assert.equal(report.failureCategory, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal(report.actualModelId, null);
  assert.equal(report.contractPass, false);
});

test("stable run/profile/case replay cannot dispatch after runner reconstruction", async (t) => {
  const current = await fixture(t);
  await current.runner.run(selection);
  for (const runner of [current.runner, current.construct()]) {
    const [report] = await runner.run(selection);
    assert.equal(report.failureCategory, "REMOTE_REQUEST_COMPLETED");
  }
  assert.equal(current.calls.length, 1);
  assert.equal(Object.keys((await current.read()).requests).length, 1);
});

test("corrupt or missing initialized ledger blocks a reconstructed runner before dispatch", async (t) => {
  for (const corrupt of [true, false]) {
    const current = await fixture(t);
    await current.runner.run(selection);
    if (corrupt) await fs.writeFile(current.ledgerPath, "{");
    else await fs.unlink(current.ledgerPath);
    const [report] = await current.construct().run({ ...selection, caseIds: ["multilingual"] });
    assert.equal(report.failureCategory, "REMOTE_ADMISSION_INVALID_RECORD");
    assert.equal(current.calls.length, 1);
  }
});

test("deliberately new stable run ID permits a new campaign within unchanged budgets", async (t) => {
  const output = { choices: [{ message: { content: JSON.stringify(getSyntheticCoachCase("coach-basic").expectedResponse) } }] };
  const current = await fixture(t, { sequence: [output, output] });
  await current.runner.run(selection);
  const [report] = await current.construct({ ...current.environment,
    evaluationRunId: "00000000-0000-4000-8000-000000000002" }).run(selection);
  assert.equal(report.contractPass, true);
  assert.equal(current.calls.length, 2);
  assert.equal(Object.keys((await current.read()).requests).length, 2);
});

test("explicit global budget is shared across candidate profiles", async (t) => {
  const approvedEnvironment = environment();
  approvedEnvironment.limits.globalProviderAttempts.limit = 1;
  const current = await fixture(t, { approvedEnvironment });
  await current.runner.run(selection);
  const [report] = await current.runner.run({ ...selection, routeProfileId: "eval-openrouter-nemotron-3-ultra-free" });
  assert.equal(current.calls.length, 1);
  assert.equal(report.failureCategory, "REMOTE_GLOBAL_BUDGET_UNAVAILABLE");
  assert.equal((await current.read()).providerAttemptCount, 1);
});

test("account attempt budget blocks repair before its fake SDK invocation", async (t) => {
  const approvedEnvironment = environment();
  approvedEnvironment.limits.providerAttempts.limit = 1;
  const current = await fixture(t, { approvedEnvironment, sequence: [{ choices: [{ message: { content: "not-json" } }] }] });
  const [report] = await current.runner.run(selection);
  assert.equal(current.calls.length, 1);
  assert.equal(report.failureCategory, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED");
  assert.equal((await current.read()).providerAttemptCount, 1);
});

test("successful-use budget blocks a new case reservation", async (t) => {
  const approvedEnvironment = environment();
  approvedEnvironment.limits.successfulUses.limit = 1;
  const current = await fixture(t, { approvedEnvironment });
  await current.runner.run(selection);
  const [report] = await current.runner.run({ ...selection, caseIds: ["multilingual"] });
  assert.equal(current.calls.length, 1);
  assert.equal(report.failureCategory, "REMOTE_ACCOUNT_QUOTA_EXHAUSTED");
  assert.equal(Object.keys((await current.read()).requests).length, 1);
});

test("repair/retry commit second authorization and cannot stack a third dispatch", async (t) => {
  const invalid = { choices: [{ message: { content: "not-json" } }] };
  for (const sequence of [[invalid, invalid, invalid], [Object.assign(new Error("private provider body"), { status: 503 }), invalid, invalid]]) {
    const current = await fixture(t, { sequence });
    const [report] = await current.runner.run(selection);
    assert.equal(current.calls.length, 2);
    assert.equal(report.attemptsUsed, 2);
    assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "DISPATCHED", "dispatch", "FAILED_POST_DISPATCH"]);
    assert.equal(report.failureCategory, "PROVIDER_INVALID_RESPONSE");
    assert.equal((await current.read()).providerAttemptCount, 2);
    assert.equal((await current.read()).successfulUseCount, 0);
  }
});

test("second authorization persistence failure blocks the second fake SDK call", async (t) => {
  const current = await fixture(t, { sequence: [{ choices: [{ message: { content: "not-json" } }] }], secondAttemptFailure: true });
  const [report] = await current.runner.run(selection);
  assert.equal(current.calls.length, 1);
  assert.equal(report.attemptsUsed, 1);
  assert.equal(report.failureCategory, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal((await current.read()).providerAttemptCount, 1);
  const [replay] = await current.construct().run(selection);
  assert.equal(replay.failureCategory, "REMOTE_ADMISSION_PERSISTENCE_UNAVAILABLE");
  assert.equal(current.calls.length, 1);
});

test("missing provider configuration persists pre-dispatch failure and blocks replay", async (t) => {
  const current = await fixture(t, { configOverrides: { openrouterApiKey: "" } });
  await current.runner.run(selection);
  assert.equal(current.calls.length, 0);
  assert.equal(Object.values((await current.read()).requests)[0].state, "FAILED_PRE_DISPATCH");
  const [replay] = await current.construct().run(selection);
  assert.equal(replay.failureCategory, "REMOTE_REQUEST_COMPLETED");
});

test("approved profiles retain separate configured/actual model IDs; absent actual ID is null", async (t) => {
  for (const candidate of OPENROUTER_EVALUATION_PROFILES) {
    const current = await fixture(t, { sequence: [{ choices: [{ message: { content: JSON.stringify(getSyntheticCoachCase("coach-basic").expectedResponse) } }] }] });
    const [report] = await current.runner.run({ ...selection, routeProfileId: candidate.routeProfileId });
    assert.equal(current.calls[0].body.model, candidate.modelId);
    assert.equal(report.configuredModelId, candidate.modelId);
    assert.equal(report.actualModelId, null);
    assert.deepEqual(current.calls[0].body.provider, candidate.routingMode === "STRICT_PRIVACY"
      ? { allow_fallbacks: false, require_parameters: true, data_collection: "deny", zdr: true }
      : { allow_fallbacks: false, require_parameters: true });
  }
});

test("controlled evaluation rejects a production provider or enabled remote AI before SDK work", () => {
  for (const overrides of [{ provider: "fireworks" }, { remoteAiEnabled: true }]) {
    let called = false;
    assert.throws(() => createControlledEvaluationRunner({ environment: environment(), config: loadConfig(overrides),
      fetchImpl() { called = true; assert.fail("must remain construction-only"); },
      fireworksClientFactory() { called = true; assert.fail("must remain construction-only"); } }));
    assert.equal(called, false);
  }
});

test("reports/ledger omit fixtures/secrets/raw identities; metadata stays out of accounting", async (t) => {
  const current = await fixture(t);
  const reports = await current.runner.run(selection);
  const durable = await current.read();
  const serialized = JSON.stringify({ reports, durable });
  for (const value of [getSyntheticCoachCase("coach-basic").input.question,
    getSyntheticCoachCase("coach-basic").expectedResponse.summary, current.environment.hmacSecret,
    "INJECTED_FETCH_ONLY", "synthetic-evaluation-", "firebaseUid", "idempotencyKey", "evaluationRunId", RUN_ID]) {
    assert.equal(serialized.includes(value), false);
  }
  for (const field of ["actualModelId", "configuredModelId", "execution", "grounding", "prompt", "response"]) {
    assert.equal(JSON.stringify(durable).includes(field), false);
  }
});

test("schema-valid injection remains unadjudicated quality", async (t) => {
  const current = await fixture(t);
  const [report] = await current.runner.run({ ...selection, caseIds: ["prompt-injection"] });
  assert.equal(report.contractPass, true);
  assert.equal(report.qualityReviewRequired, true);
  assert.equal(report.qualityResult, null);
  assert.equal(Object.hasOwn(report, "pass"), false);
});

test("durably authorized gateway failure reports only safe diagnostics and leaves the ledger unchanged", async (t) => {
  const failure = Object.assign(new Error("private gateway message"), {
    status: 422, body: "private provider response", headers: { authorization: "private credential" },
    request: "private prompt", id: "private opaque id"
  });
  const current = await fixture(t, { sequence: [failure] });
  const [report] = await current.runner.run(selection);
  assert.deepEqual(current.events, ["RESERVED", "DISPATCHED", "dispatch", "FAILED_POST_DISPATCH"]);
  assert.equal(current.calls.length, 1);
  assert.equal(report.failureCategory, "PROVIDER_UNAVAILABLE");
  assert.deepEqual(report.failureDiagnostic, { upstreamHttpStatus: 422, classification: "UNPROCESSABLE" });
  assert.deepEqual(report.execution.attempts[0].failureDiagnostic, report.failureDiagnostic);
  assert.equal(report.qualityResult, null);
  assert.equal(JSON.stringify(report).includes("private"), false);
  const durable = await current.read();
  assert.equal(durable.providerAttemptCount, 1);
  for (const field of ["failureDiagnostic", "upstreamHttpStatus", "UNPROCESSABLE", "private"]) {
    assert.equal(JSON.stringify(durable).includes(field), false);
  }
});

test("successful and pre-dispatch admission-failure reports contain no gateway failure diagnostic", async (t) => {
  const success = await fixture(t);
  const [report] = await success.runner.run(selection);
  assert.equal(report.failureDiagnostic, null);
  const failed = await fixture(t, { storeFailure: true });
  const [failure] = await failed.runner.run(selection);
  assert.equal(failure.failureDiagnostic, null);
  assert.equal(failed.calls.length, 0);
});

test("fixture admission rejects arbitrary request data and retains lease timeout margin", async () => {
  const admitted = createDurableEvaluationAdmission({ environment: environment(), config: loadConfig(),
    routeProfileId: profile.routeProfileId });
  await assert.rejects(admitted.begin({ syntheticCaseId: "coach-basic", capability: "REMOTE_AI_COACH",
    validatedRequest: { question: "arbitrary" } }));
  assert.throws(() => createDurableEvaluationAdmission({ environment: environment(), routeProfileId: profile.routeProfileId,
    config: { remoteAdmissionLeaseSeconds: 30, openrouterEvaluationTimeoutMs: 20_000 } }));
});

test("evaluation admission requires its own operation budget plus 15 seconds regardless of Fireworks", () => {
  for (const fireworksTimeoutMs of [1000, 30000, 120000]) {
    let stores = 0;
    const construct = (remoteAdmissionLeaseSeconds, openrouterEvaluationTimeoutMs) => createDurableEvaluationAdmission({
      environment: environment(), routeProfileId: profile.routeProfileId,
      config: { remoteAdmissionLeaseSeconds, openrouterEvaluationTimeoutMs, fireworksTimeoutMs },
      storeFactory() {
        stores += 1;
        return Object.fromEntries(["reserve", "recordProviderAttempt", "transition"].map((method) =>
          [method, async () => assert.fail("lease validation must not perform storage operations")]));
      }
    });
    assert.throws(() => construct(44, 30000));
    assert.equal(stores, 0);
    assert.doesNotThrow(() => construct(45, 30000));
    assert.throws(() => construct(45, 30001));
    assert.doesNotThrow(() => construct(135, 120000));
    for (const timeout of [undefined, null, "30000", 0, 999, 120001, 30000.5]) {
      assert.throws(() => construct(600, timeout));
    }
    assert.equal(stores, 2);
  }
});

test("evaluation is absent from production HTTP and defaults/policies stay disabled", async () => {
  for (const file of ["../src/app.js", "../src/routes/ai_coach.js"]) {
    const source = await fs.readFile(new URL(file, import.meta.url), "utf8");
    assert.equal(/controlled_evaluation|generateEvaluation|SyntheticCoachEvaluation|LocalEvaluationAdmissionStore/.test(source), false);
  }
  const config = loadConfig();
  assert.equal(config.provider, "mock");
  assert.equal(config.remoteAiEnabled, false);
  assert.deepEqual(PRODUCTION_OPENROUTER_ROUTE_PROFILES, []);
  assert.throws(() => DEFAULT_QUOTA_POLICY.getPolicy({ capability: "REMOTE_AI_COACH", tier: "BASIC" }));
});
