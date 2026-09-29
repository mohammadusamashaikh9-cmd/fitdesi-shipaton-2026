import assert from "node:assert/strict";
import test from "node:test";
import { SyntheticCoachEvaluation } from "../src/evaluation/synthetic_coach_evaluation.js";
import { SYNTHETIC_COACH_CASE_IDS, getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";
import { OPENROUTER_EVALUATION_PROFILES } from "../src/services/provider_route_policy.js";
import { ProviderRouter } from "../src/services/provider_router.js";
import { OpenRouterProvider } from "../src/services/openrouter_provider.js";
import { RemoteAdmissionUnavailableError, RemoteAccountQuotaExhaustedError } from "../src/errors.js";

function fixture({ admissionMissing = false, incompleteSession = false, authorizationError = false, settlementError = false } = {}) {
  let activeCase;
  const events = [];
  const fetchImpl = async () => {
    events.push("dispatch");
    if (activeCase.scenario === "TIMEOUT") throw Object.assign(new Error("synthetic secret detail"), { code: "ETIMEDOUT" });
    if (activeCase.scenario === "UNAVAILABLE") return new Response("synthetic secret body", { status: 503 });
    return Response.json({ choices: [{ message: { content: activeCase.scenario === "INVALID_JSON" ? "not-json" : JSON.stringify(activeCase.expectedResponse) } }] });
  };
  const router = new ProviderRouter({ openrouterProvider: new OpenRouterProvider({
    config: { openrouterApiKey: "configured-for-injected-mock", openrouterEvaluationTimeoutMs: 100 }, evaluationEnabled: true, fetchImpl
  }) });
  const admission = { async begin({ syntheticCaseId, capability, validatedRequest }) {
    assert.equal(capability, "REMOTE_AI_COACH");
    activeCase = getSyntheticCoachCase(syntheticCaseId);
    assert.deepEqual(validatedRequest, activeCase.input);
    events.push("begin");
    if (incompleteSession) return {};
    return {
      async beforeProviderAttempt() {
        if (authorizationError) throw new RemoteAccountQuotaExhaustedError();
        events.push("authorized");
      },
      async succeed() {
        events.push("succeed");
        if (settlementError) throw new RemoteAdmissionUnavailableError();
      },
      async fail() { events.push("fail"); }
    };
  } };
  return { router, events, now: () => 0, admission: admissionMissing ? undefined : admission };
}

test("missing evaluation authority fails before any dispatch", () => {
  const current = fixture({ admissionMissing: true });
  assert.throws(() => new SyntheticCoachEvaluation(current), RemoteAdmissionUnavailableError);
  assert.deepEqual(current.events, []);
});

test("invalid evaluation session and attempt authorization failure never dispatch", async () => {
  for (const option of [{ incompleteSession: true }, { authorizationError: true }]) {
    const current = fixture(option);
    const reports = await new SyntheticCoachEvaluation(current).run({ routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId, caseIds: ["coach-basic"] });
    assert.equal(reports[0].contractPass, false);
    assert.equal(reports[0].attemptsUsed, 0);
    assert.equal(current.events.includes("dispatch"), false);
  }
});

test("small synthetic suite executes through admission/router/executor with sanitized reports only", async () => {
  const current = fixture();
  const reports = await new SyntheticCoachEvaluation(current).run({ routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId });
  assert.equal(reports.length, 14);
  assert.ok(reports.every((report) => report.contractPass));
  assert.ok(reports.every((report) => report.qualityResult === null && !Object.hasOwn(report, "pass")));
  for (const report of reports) {
    assert.equal(report.qualityReviewRequired, getSyntheticCoachCase(report.syntheticCaseId).expectedFailure === null);
  }
  assert.ok(reports.every((report) => report.attemptsUsed >= 1 && report.attemptsUsed <= 2));
  for (let index = 0; index < current.events.length; index += 1) {
    if (current.events[index] === "dispatch") assert.equal(current.events[index - 1], "authorized");
  }
  const encoded = JSON.stringify(reports);
  for (const caseId of SYNTHETIC_COACH_CASE_IDS) {
    const value = getSyntheticCoachCase(caseId);
    assert.equal(encoded.includes(value.input.question), false);
    assert.equal(encoded.includes(value.expectedResponse.summary), false);
  }
  for (const value of ["configured-for-injected-mock", "synthetic secret", "not-json", "foodById", "SYNTHETIC_FIXTURE_EVIDENCE"]) {
    assert.equal(encoded.includes(value), false);
  }
  assert.ok(reports.every((report) => report.cost === null && report.usage.totalTokens === null));
  for (const report of reports) {
    assert.equal(report.contractDiagnostic, report.failureCategory === "PROVIDER_INVALID_RESPONSE" ? "JSON_PARSE_FAILED" : null);
    for (const attempt of report.execution.attempts) {
      assert.equal(attempt.contractDiagnostic, attempt.validationOutcome === "INVALID_RESPONSE" ? "JSON_PARSE_FAILED" : null);
    }
  }
});

test("evaluation only accepts registered synthetic cases and evaluation profile IDs", async () => {
  const current = fixture();
  const evaluation = new SyntheticCoachEvaluation(current);
  await assert.rejects(evaluation.run({ routeProfileId: "coach-fireworks-current" }));
  await assert.rejects(evaluation.run({ routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId, caseIds: ["untrusted-case"] }));
  await assert.rejects(evaluation.run({ routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId, caseIds: ["coach-basic"], input: { question: "untrusted profile" } }));
  assert.deepEqual(current.events, []);
});

test("success settlement failure does not retry or add contradictory failure settlement", async () => {
  const current = fixture({ settlementError: true });
  const reports = await new SyntheticCoachEvaluation(current).run({ routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId, caseIds: ["coach-basic"] });
  assert.equal(reports[0].contractPass, false);
  assert.equal(reports[0].failureCategory, "REMOTE_ADMISSION_UNAVAILABLE");
  assert.equal(reports[0].attemptsUsed, 1);
  assert.deepEqual(current.events, ["begin", "authorized", "dispatch", "succeed"]);
});

test("schema-valid injection output passes only the contract and still requires quality review", async () => {
  const current = fixture();
  const [report] = await new SyntheticCoachEvaluation(current).run({
    routeProfileId: OPENROUTER_EVALUATION_PROFILES[0].routeProfileId, caseIds: ["prompt-injection"]
  });
  assert.equal(report.validationResult, "VALIDATED");
  assert.equal(report.contractPass, true);
  assert.equal(report.qualityReviewRequired, true);
  assert.equal(report.qualityResult, null);
  assert.equal(Object.hasOwn(report, "pass"), false);
});
