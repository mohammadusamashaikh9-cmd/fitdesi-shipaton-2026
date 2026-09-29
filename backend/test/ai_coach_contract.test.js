import assert from "node:assert/strict";
import test from "node:test";
import { AI_COACH_JSON_SCHEMA, parseProviderJson, validateAiCoachProviderResponse, coachContractDiagnostic } from "../src/services/ai_coach_contract.js";
import { ProviderInvalidResponseError } from "../src/errors.js";
import { mapProviderError } from "../src/services/coach_provider_operation.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";
import { prepareCoachOperation } from "../src/services/coach_provider_operation.js";

const businessData = {
  summary: "Keep training gradual.", recommendedAction: "Record one safe session.",
  nutritionNote: "Use measured portions.", workoutNote: "Use controlled technique.",
  safetyDisclaimer: "General education only.", escalationRequired: false,
  detectedIntent: "GENERAL_COACHING", detectedGoal: null, confidence: 0.8,
  warnings: [], generatedWorkoutPlan: null, generatedDietPlan: null
};

function rejectsWithDiagnostic(action, expected) {
  assert.throws(action, (error) => {
    assert.ok(error instanceof ProviderInvalidResponseError);
    assert.equal(mapProviderError(error), error);
    assert.equal(error.code, "PROVIDER_INVALID_RESPONSE");
    const original = new ProviderInvalidResponseError();
    assert.equal(error.statusCode, original.statusCode);
    assert.equal(error.publicMessage, original.publicMessage);
    assert.deepEqual(error.headers, original.headers);
    assert.equal(JSON.stringify(error).includes(expected), false);
    assert.equal(coachContractDiagnostic(error), expected);
    // Diagnostics are privately associated, never added to the public error.
    assert.equal(Object.hasOwn(error, "contractDiagnostic"), false);
    assert.deepEqual(Object.keys(error), Object.keys(new ProviderInvalidResponseError()));
    return true;
  });
}

test("authoritative parse failures carry only the fixed JSON diagnostic", () => {
  for (const content of ["not-json", "{", "", null, "x".repeat(30001)]) {
    rejectsWithDiagnostic(() => parseProviderJson(content), "JSON_PARSE_FAILED");
  }
  assert.equal(coachContractDiagnostic({ contractDiagnostic: "private model text" }), null);
  assert.equal(coachContractDiagnostic(new ProviderInvalidResponseError()), null);
});

test("top-level and safety failures remain authoritative with coarse static diagnostics", () => {
  for (const value of [null, [], {}, { ...businessData, confidence: 2 }, { ...businessData, unexpected: "private text" }]) {
    rejectsWithDiagnostic(() => validateAiCoachProviderResponse(value, { question: "Help me train" }), "TOP_LEVEL_CONTRACT_INVALID");
  }
  const value = getSyntheticCoachCase("safety-red-flags");
  value.expectedResponse.escalationRequired = false;
  rejectsWithDiagnostic(() => validateAiCoachProviderResponse(value.expectedResponse, {
    question: value.input.question, input: value.input, grounding: value.grounding
  }), "SAFETY_CONTRACT_INVALID");
});

test("workout and nutrition grounding failures retain exact-ID and source-truth checks", () => {
  for (const [caseId, diagnostic, mutate] of [
    ["canonical-leading-zeroes", "WORKOUT_PLAN_INVALID", (value) => { value.generatedWorkoutPlan.generatedDays[0].exercises[0].exerciseId = "257"; }],
    ["nutrition-null", "DIET_PLAN_INVALID", (value) => { value.generatedDietPlan.meals[0].foods[0].calories = 10; }]
  ]) {
    const value = getSyntheticCoachCase(caseId);
    mutate(value.expectedResponse);
    rejectsWithDiagnostic(() => validateAiCoachProviderResponse(value.expectedResponse, {
      question: value.input.question, input: value.input, grounding: value.grounding
    }), diagnostic);
  }
});

test("Coach model contract accepts business data without execution sourceType", () => {
  assert.equal(validateAiCoachProviderResponse(businessData, { question: "Help me train" }), businessData);
  assert.equal(AI_COACH_JSON_SCHEMA.required.includes("sourceType"), false);
  assert.equal(Object.hasOwn(AI_COACH_JSON_SCHEMA.properties, "sourceType"), false);
});

test("model output cannot claim provider/model/route execution provenance", () => {
  for (const field of ["sourceType", "providerAlias", "configuredModelId", "routeProfileId", "execution"]) {
    assert.throws(() => validateAiCoachProviderResponse({ ...businessData, [field]: "FIREWORKS" }, { question: "Help me train" }));
  }
});

test("grounded food sourceType survives prompt preparation and nutritionSource stays authoritative", () => {
  const value = getSyntheticCoachCase("pakistani-food");
  const operation = prepareCoachOperation({ input: value.input, grounding: value.grounding }, { modelId: "configured-model", structuredOutputMode: "JSON_SCHEMA" });
  const prompt = JSON.parse(operation.initial.messages[1].content);
  assert.equal(prompt.knowledge.foods[0].sourceType, value.grounding.foods[0].sourceType);
  assert.equal(validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding }), value.expectedResponse);
  value.expectedResponse.generatedDietPlan.meals[0].foods[0].nutritionSource = "estimated";
  assert.throws(() => validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding }));
});

test("shared Coach instructions require intent-bounded plans and authoritative diet grounding", () => {
  const value = getSyntheticCoachCase("pakistani-food");
  const operation = prepareCoachOperation({ input: value.input, grounding: value.grounding }, {
    modelId: "configured-model", structuredOutputMode: "JSON_SCHEMA"
  });
  const instructions = operation.initial.messages[0].content;
  for (const rule of [
    /generatedWorkoutPlan.*structured workout plan/i,
    /generatedDietPlan.*structured diet\/meal plan/i,
    /otherwise.*null/i,
    /exact record ID.*exact name/i,
    /calories\/macros exactly/i,
    /nutritionSource/i,
    /no calorie target.*calorieTarget.*null.*source.*unavailable/i,
    /no macro targets.*macroTargets.*null.*source.*unavailable/i,
    /1200.*4500/i
  ]) assert.match(instructions, rule);
});

test("missing nutrition evidence cannot acquire fabricated values after provenance refactor", () => {
  const value = getSyntheticCoachCase("nutrition-null");
  validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding });
  value.expectedResponse.generatedDietPlan.meals[0].foods[0].calories = 10;
  assert.throws(() => validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding }));
});

test("canonical IDs retain exact strings and leading zeroes in Coach domain validation", () => {
  const value = getSyntheticCoachCase("canonical-leading-zeroes");
  validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding });
  assert.deepEqual(value.expectedResponse.generatedWorkoutPlan.generatedDays[0].exercises.map((exercise) => exercise.exerciseId),
    ["0257", "0643", "1576", "fd-exercise-chair-squat"]);
  value.expectedResponse.generatedWorkoutPlan.generatedDays[0].exercises[0].exerciseId = "257";
  assert.throws(() => validateAiCoachProviderResponse(value.expectedResponse, { question: value.input.question, input: value.input, grounding: value.grounding }));
});
