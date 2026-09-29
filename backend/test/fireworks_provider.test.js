import assert from "node:assert/strict";
import test from "node:test";
import {
  RemoteAccountQuotaExhaustedError,
  RemoteAdmissionUnavailableError,
  RemoteGlobalBudgetUnavailableError,
  ProviderConfigurationError,
  ProviderDisabledError,
  ProviderInvalidResponseError,
  ProviderRateLimitError,
  ProviderTimeoutError,
  ProviderUnauthorizedError,
  ProviderUnavailableError
} from "../src/errors.js";
import { FireworksProvider } from "../src/services/fireworks_provider.js";

const EXERCISES = [
  { id: "ex-db-row", name: "Dumbbell Row", equipment: ["dumbbell"] },
  { id: "ex-db-squat", name: "Dumbbell Squat", equipment: ["dumbbell"] },
  { id: "ex-bar-squat", name: "Barbell Squat", equipment: ["barbell"] }
];

const FOODS = [
  {
    id: "food-daal",
    name: "Daal Masoor",
    calories: 400,
    proteinGrams: 24,
    carbsGrams: 60,
    fatGrams: 8
  },
  {
    id: "food-chicken",
    name: "Grilled Chicken",
    calories: 600,
    proteinGrams: 80,
    carbsGrams: 0,
    fatGrams: 20
  }
];

const grounding = {
  exercises: EXERCISES,
  foods: FOODS,
  generalKnowledge: [],
  validExerciseIds: new Set(EXERCISES.map(({ id }) => id)),
  exerciseById: new Map(EXERCISES.map((exercise) => [exercise.id, exercise])),
  validFoodIds: new Set(FOODS.map(({ id }) => id)),
  foodById: new Map(FOODS.map((food) => [food.id, food]))
};

function enabledConfig(overrides = {}) {
  return {
    provider: "fireworks",
    remoteAiEnabled: true,
    fireworksApiKey: "configured-for-injected-mock",
    fireworksModel: "accounts/test/models/test-model",
    fireworksTimeoutMs: 100,
    fireworksMaxOutputTokens: 512,
    fireworksTemperature: 0.2,
    ...overrides
  };
}

function baseCoach(overrides = {}) {
  return {
    summary: "Use a gradual and repeatable plan.",
    recommendedAction: "Start with the first planned session.",
    nutritionNote: "Use measured portions and familiar foods.",
    workoutNote: "Train with controlled technique.",
    safetyDisclaimer: "General fitness education only; consult a professional for medical concerns.",
    escalationRequired: false,
    detectedIntent: "GENERAL_COACHING",
    detectedGoal: null,
    confidence: 0.9,
    warnings: [],
    generatedWorkoutPlan: null,
    generatedDietPlan: null,
    ...overrides
  };
}

function workoutPlan(overrides = {}) {
  const exercise = (id, name) => ({
    exerciseId: id,
    name,
    sets: 3,
    repsOrDuration: "8-12 reps",
    restSeconds: 90,
    role: "primary"
  });
  return {
    planId: "plan-workout-1",
    title: "Four Day Dumbbell Plan",
    goal: "build muscle",
    requestedDays: 4,
    generatedDays: [
      { dayName: "Monday", focus: "Upper", exercises: [exercise("ex-db-row", "Dumbbell Row")] },
      { dayName: "Tuesday", focus: "Lower", exercises: [exercise("ex-db-squat", "Dumbbell Squat")] },
      { dayName: "Thursday", focus: "Upper", exercises: [exercise("ex-db-row", "Dumbbell Row")] },
      { dayName: "Saturday", focus: "Lower", exercises: [exercise("ex-db-squat", "Dumbbell Squat")] }
    ],
    progressionGuidance: "Add repetitions before increasing load.",
    safetyNote: "Stop if technique breaks down or symptoms appear.",
    ...overrides
  };
}

function foodItem(source) {
  return {
    foodRecordId: source.id,
    name: source.name,
    portion: "One measured serving",
    calories: source.calories,
    proteinGrams: source.proteinGrams,
    carbsGrams: source.carbsGrams,
    fatGrams: source.fatGrams,
    nutritionSource: "existing_record"
  };
}

function dietPlan(overrides = {}) {
  return {
    planId: "plan-diet-1",
    title: "Pakistani Balanced Day",
    goal: "general fitness",
    calorieTarget: null,
    calorieTargetSource: "unavailable",
    macroTargets: null,
    macroTargetSource: "unavailable",
    meals: [
      { name: "Lunch", foods: [foodItem(FOODS[0])] },
      { name: "Dinner", foods: [foodItem(FOODS[1])] },
      { name: "Snack", foods: [foodItem(FOODS[1])] }
    ],
    alternatives: ["Swap meal order if preferred."],
    disclaimer: "Nutrition values use stored servings and remain estimates.",
    ...overrides
  };
}

function completion(value) {
  return {
    choices: [{ message: { content: typeof value === "string" ? value : JSON.stringify(value) } }],
    usage: { prompt_tokens: 20, completion_tokens: 30, total_tokens: 50 }
  };
}

function fakeClient(sequence) {
  const calls = [];
  return {
    calls,
    client: {
      chat: {
        completions: {
          async create(body, options) {
            calls.push({ body, options });
            const next = sequence.shift();
            if (next instanceof Error) throw next;
            return next;
          }
        }
      }
    }
  };
}

function providerFor(sequence, config = enabledConfig()) {
  const fake = fakeClient(sequence);
  return {
    provider: new FireworksProvider({
      config,
      client: fake.client,
      clientFactory: () => {
        throw new Error("External client construction is prohibited in tests.");
      }
    }),
    calls: fake.calls
  };
}

function request(question, input = {}) {
  return {
    feature: "coach",
    question,
    input: { question, calorieTarget: null, ...input },
    grounding,
    beforeProviderAttempt: async () => {}
  };
}

async function rejectsInvalid(value, requestValue = request("Give me coaching advice")) {
  const { provider, calls } = providerFor([completion(value), completion(value)]);
  await assert.rejects(provider.generateStructured(requestValue), ProviderInvalidResponseError);
  assert.equal(calls.length, 2, "one bounded repair attempt is expected");
}

test("Fireworks remains disabled unless provider and remote execution are enabled", async () => {
  let factoryCalls = 0;
  const provider = new FireworksProvider({
    config: enabledConfig({ provider: "mock", remoteAiEnabled: false }),
    clientFactory: () => {
      factoryCalls += 1;
      throw new Error("must not construct client");
    }
  });
  await assert.rejects(provider.generateStructured(request("Help me train")), ProviderDisabledError);
  assert.equal(factoryCalls, 0);
});

test("Fireworks fails closed when API key is missing", async () => {
  const { provider, calls } = providerFor([], enabledConfig({ fireworksApiKey: "" }));
  await assert.rejects(
    provider.generateStructured(request("Help me train")),
    (error) => error instanceof ProviderConfigurationError && error.code === "PROVIDER_KEY_MISSING"
  );
  assert.equal(calls.length, 0);
});

test("Fireworks fails closed when model is missing", async () => {
  const { provider, calls } = providerFor([], enabledConfig({ fireworksModel: "" }));
  await assert.rejects(
    provider.generateStructured(request("Help me train")),
    (error) => error instanceof ProviderConfigurationError && error.code === "PROVIDER_MODEL_MISSING"
  );
  assert.equal(calls.length, 0);
});

test("valid Coach response uses fixed server-side model and structured schema", async () => {
  const { provider, calls } = providerFor([completion(baseCoach())]);
  const result = await provider.generateStructured(request("Help me train consistently"));
  assert.equal(result.data.summary, "Use a gradual and repeatable plan.");
  assert.deepEqual(result.usage, { promptTokens: 20, completionTokens: 30, totalTokens: 50 });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.model, "accounts/test/models/test-model");
  assert.equal(calls[0].body.max_tokens, 512);
  assert.equal(calls[0].body.response_format.type, "json_schema");
  assert.equal(calls[0].options.signal instanceof AbortSignal, true);
});

test("Fireworks operation deadline and output limit ignore OpenRouter evaluation settings", async () => {
  for (const evaluation of [
    { openrouterEvaluationTimeoutMs: 1000, openrouterEvaluationMaxOutputTokens: 256 },
    { openrouterEvaluationTimeoutMs: 120000, openrouterEvaluationMaxOutputTokens: 4096 }
  ]) {
    const { provider, calls } = providerFor([completion(baseCoach())], enabledConfig(evaluation));
    await provider.generateStructured({ ...request("Help me train consistently"), now: () => 0 });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].body.max_tokens, 512);
    assert.equal(calls[0].options.timeout, 100);
    assert.equal(calls[0].options.maxRetries, 0);
  }
});

test("provider-attempt authorization hook is mandatory before any network request", async (t) => {
  for (const [name, hook] of [
    ["missing", undefined],
    ["null", null],
    ["non-function", "not-a-function"]
  ]) {
    await t.test(name, async () => {
      const { provider, calls } = providerFor([completion(baseCoach())]);
      const providerRequest = request("Help me train consistently");
      if (hook === undefined) delete providerRequest.beforeProviderAttempt;
      else providerRequest.beforeProviderAttempt = hook;

      await assert.rejects(
        provider.generateStructured(providerRequest),
        RemoteAdmissionUnavailableError
      );
      assert.equal(calls.length, 0);
    });
  }
});

test("beforeProviderAttempt runs immediately before every real provider request", async () => {
  const events = [];
  const responses = [completion("not-json"), completion(baseCoach())];
  const provider = new FireworksProvider({
    config: enabledConfig(),
    client: {
      chat: {
        completions: {
          async create() {
            events.push("request");
            return responses.shift();
          }
        }
      }
    }
  });

  await provider.generateStructured({
    ...request("Help me train consistently"),
    beforeProviderAttempt: async () => events.push("hook")
  });

  assert.deepEqual(events, ["hook", "request", "hook", "request"]);
});

test("first admission-hook failure is propagated unchanged without a provider request", async () => {
  const admissionError = new RemoteAccountQuotaExhaustedError();
  const { provider, calls } = providerFor([completion(baseCoach())]);

  await assert.rejects(provider.generateStructured({
    ...request("Help me train"),
    beforeProviderAttempt: async () => { throw admissionError; }
  }), (error) => error === admissionError);
  assert.equal(calls.length, 0);
});

test("second admission-hook failure prevents repair request and is not provider-mapped", async () => {
  const admissionError = new RemoteGlobalBudgetUnavailableError();
  const { provider, calls } = providerFor([
    completion("not-json"),
    completion(baseCoach())
  ]);
  let authorizedAttempts = 0;

  await assert.rejects(provider.generateStructured({
    ...request("Help me train"),
    beforeProviderAttempt: async () => {
      authorizedAttempts += 1;
      if (authorizedAttempts === 2) throw admissionError;
    }
  }), (error) => error === admissionError);
  assert.equal(calls.length, 1);
});

test("valid grounded workout plan is accepted", async () => {
  const response = baseCoach({
    detectedIntent: "WORKOUT_PLAN",
    detectedGoal: "build muscle",
    generatedWorkoutPlan: workoutPlan()
  });
  const { provider } = providerFor([completion(response)]);
  const result = await provider.generateStructured(request(
    "Give me a four-day dumbbell-only workout",
    { workoutDays: 4, equipment: ["dumbbell"] }
  ));
  assert.equal(result.data.generatedWorkoutPlan.generatedDays.length, 4);
});

test("valid grounded Pakistani diet plan is accepted", async () => {
  const response = baseCoach({
    detectedIntent: "PAKISTANI_DIET_PLAN",
    generatedDietPlan: dietPlan()
  });
  const { provider } = providerFor([completion(response)]);
  const result = await provider.generateStructured(request("Generate a Pakistani diet plan"));
  assert.equal(result.data.generatedDietPlan.meals.length, 3);
});

test("malformed JSON is rejected after one repair attempt", () => rejectsInvalid("not-json"));

test("unknown output property is rejected", () => rejectsInvalid(baseCoach({ unexpected: true })));

test("incorrect workout day count is rejected", () => {
  const plan = workoutPlan({ generatedDays: workoutPlan().generatedDays.slice(0, 3) });
  return rejectsInvalid(
    baseCoach({ detectedIntent: "WORKOUT_PLAN", generatedWorkoutPlan: plan }),
    request("Give me a four-day workout plan", { workoutDays: 4 })
  );
});

test("unknown exercise ID is rejected", () => {
  const plan = workoutPlan();
  plan.generatedDays[0].exercises[0] = {
    ...plan.generatedDays[0].exercises[0], exerciseId: "invented-id", name: "Invented Exercise"
  };
  return rejectsInvalid(baseCoach({ detectedIntent: "WORKOUT_PLAN", generatedWorkoutPlan: plan }));
});

test("equipment mismatch is rejected", () => {
  const plan = workoutPlan();
  plan.generatedDays[0].exercises[0] = {
    ...plan.generatedDays[0].exercises[0], exerciseId: "ex-bar-squat", name: "Barbell Squat"
  };
  return rejectsInvalid(
    baseCoach({ detectedIntent: "WORKOUT_PLAN", generatedWorkoutPlan: plan }),
    request("Give me a four-day dumbbell-only workout", { workoutDays: 4, equipment: ["dumbbell"] })
  );
});

test("unsafe calorie result is rejected", () => {
  const plan = dietPlan({ calorieTarget: 1200, calorieTargetSource: "user_request" });
  return rejectsInvalid(
    baseCoach({ detectedIntent: "PAKISTANI_DIET_PLAN", generatedDietPlan: plan }),
    request("Give me a 1200 calorie Pakistani diet plan", { calorieTarget: 1200 })
  );
});

test("genuine medical red flags require and accept escalation", async () => {
  const response = baseCoach({
    summary: "These symptoms require urgent professional assessment.",
    recommendedAction: "Stop training and seek urgent medical care.",
    escalationRequired: true,
    detectedIntent: "MEDICAL_ESCALATION",
    warnings: ["Chest pain and fainting are urgent red flags."]
  });
  const { provider } = providerFor([completion(response)]);
  const result = await provider.generateStructured(request("I have chest pain and feel faint"));
  assert.equal(result.data.escalationRequired, true);
});

test("timeout is retried once and mapped to a controlled timeout", async () => {
  const first = Object.assign(new Error("timeout detail"), { code: "ETIMEDOUT" });
  const second = Object.assign(new Error("aborted at shared deadline"), { name: "APIUserAbortError" });
  const { provider, calls } = providerFor([first, second]);
  await assert.rejects(provider.generateStructured(request("Help me train")), ProviderTimeoutError);
  assert.equal(calls.length, 2);
});

test("provider 401 is mapped without exposing provider body", async () => {
  const error = Object.assign(new Error("secret provider response"), { status: 401 });
  const { provider } = providerFor([error]);
  await assert.rejects(provider.generateStructured(request("Help me train")), ProviderUnauthorizedError);
});

test("provider 429 preserves bounded Retry-After", async () => {
  const error = Object.assign(new Error("rate body"), {
    status: 429,
    headers: { "retry-after": "17" }
  });
  const { provider } = providerFor([error]);
  await assert.rejects(
    provider.generateStructured(request("Help me train")),
    (mapped) => mapped instanceof ProviderRateLimitError && mapped.headers["retry-after"] === "17"
  );
});

test("provider 5xx is retried once and mapped to unavailable", async () => {
  const first = Object.assign(new Error("provider body"), { status: 503 });
  const second = Object.assign(new Error("provider body"), { status: 503 });
  const { provider, calls } = providerFor([first, second]);
  await assert.rejects(provider.generateStructured(request("Help me train")), ProviderUnavailableError);
  assert.equal(calls.length, 2);
});

test("tests use only the injected client and never construct an external client", async () => {
  let factoryCalls = 0;
  const fake = fakeClient([completion(baseCoach())]);
  const provider = new FireworksProvider({
    config: enabledConfig(),
    client: fake.client,
    clientFactory: () => {
      factoryCalls += 1;
      throw new Error("external request attempted");
    }
  });
  await provider.generateStructured(request("Help me train"));
  assert.equal(factoryCalls, 0);
  assert.equal(fake.calls.length, 1);
});
