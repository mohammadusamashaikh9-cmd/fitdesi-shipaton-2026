import assert from "node:assert/strict";
import test from "node:test";
import { ProviderInvalidResponseError } from "../src/errors.js";
import { projectValidatedCoachResponse } from "../src/routes/ai_coach.js";
import { validateAiCoachProviderResponse } from "../src/services/ai_coach_contract.js";
import { getSyntheticCoachCase } from "../src/evaluation/synthetic_coach_cases.js";

function validatedCase(caseId) {
  const synthetic = getSyntheticCoachCase(caseId);
  return validateAiCoachProviderResponse(synthetic.expectedResponse, {
    question: synthetic.input.question,
    input: synthetic.input,
    grounding: synthetic.grounding
  });
}

test("validated workout and diet plans project through the provider-neutral public response", () => {
  const workout = validatedCase("canonical-leading-zeroes");
  const diet = validatedCase("pakistani-food");

  const workoutResponse = projectValidatedCoachResponse(workout);
  const dietResponse = projectValidatedCoachResponse(diet);

  assert.deepEqual(workoutResponse.generatedWorkoutPlan, workout.generatedWorkoutPlan);
  assert.deepEqual(dietResponse.generatedDietPlan, diet.generatedDietPlan);
  assert.equal(workoutResponse.validationStatus, "VALIDATED");
  assert.equal(dietResponse.validationStatus, "VALIDATED");
  for (const response of [workoutResponse, dietResponse]) {
    assert.equal(Object.hasOwn(response, "provider"), false);
    assert.equal(Object.hasOwn(response, "model"), false);
    assert.equal(Object.hasOwn(response, "sourceType"), false);
  }
});

test("an unvalidated structured plan cannot be projected", () => {
  const synthetic = getSyntheticCoachCase("canonical-leading-zeroes");
  assert.throws(
    () => projectValidatedCoachResponse(synthetic.expectedResponse),
    ProviderInvalidResponseError
  );

  synthetic.expectedResponse.generatedWorkoutPlan.generatedDays[0].exercises[0].exerciseId = "257";
  assert.throws(() => validateAiCoachProviderResponse(synthetic.expectedResponse, {
    question: synthetic.input.question,
    input: synthetic.input,
    grounding: synthetic.grounding
  }), ProviderInvalidResponseError);
});

test("a structured response changed after validation loses projection authority", () => {
  const workout = validatedCase("canonical-leading-zeroes");
  workout.generatedWorkoutPlan.generatedDays[0].exercises[0].exerciseId = "257";

  assert.throws(() => projectValidatedCoachResponse(workout), ProviderInvalidResponseError);
});

test("validated diet projection accepts two-to-six meals and rejects adjacent counts", () => {
  const fixture = getSyntheticCoachCase("pakistani-food");
  const baseMeals = fixture.expectedResponse.generatedDietPlan.meals;
  const responseWithMeals = (count) => ({
    ...structuredClone(fixture.expectedResponse),
    generatedDietPlan: {
      ...structuredClone(fixture.expectedResponse.generatedDietPlan),
      meals: Array.from({ length: count }, (_, index) => ({
        ...structuredClone(baseMeals[index % baseMeals.length]),
        name: `Meal ${index + 1}`
      }))
    }
  });
  const validate = (value) => validateAiCoachProviderResponse(value, {
    question: fixture.input.question,
    input: fixture.input,
    grounding: fixture.grounding
  });

  assert.doesNotThrow(() => validate(responseWithMeals(2)));
  assert.doesNotThrow(() => validate(responseWithMeals(6)));
  assert.throws(() => validate(responseWithMeals(1)), ProviderInvalidResponseError);
  assert.throws(() => validate(responseWithMeals(7)), ProviderInvalidResponseError);
});
