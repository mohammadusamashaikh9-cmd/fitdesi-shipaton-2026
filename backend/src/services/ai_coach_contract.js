import { ProviderInvalidResponseError } from "../errors.js";
import { reviewSafetyText } from "./safety_filter.js";

const INTENTS = [
  "GENERAL_COACHING", "EXERCISE_QUESTION", "WORKOUT_PLAN", "PAKISTANI_DIET_PLAN",
  "FOOD_QUESTION", "PROGRESS_REVIEW", "YOGA_PLAN", "MEDICAL_ESCALATION", "UNSUPPORTED"
];
const EXERCISE_ROLES = ["primary", "accessory", "warmup"];

// Fixed taxonomy: JSON_PARSE_FAILED includes rejected content bounds/JSON syntax;
// TOP_LEVEL_CONTRACT_INVALID covers root fields; WORKOUT_PLAN_INVALID includes
// workout structure, IDs, names, equipment and requested days; DIET_PLAN_INVALID
// includes diet structure, profile targets, food IDs and nutrition truth;
// SAFETY_CONTRACT_INVALID covers escalation consistency. Non-contract errors have null.
// One authoritative validator; coarse stages include all their existing grounding,
// equipment, nutrition and profile-target checks. No field values are retained.
const contractDiagnostics = new WeakMap();
const validatedCoachResponses = new WeakMap();
function contractFailure(diagnostic) {
  const error = new ProviderInvalidResponseError();
  contractDiagnostics.set(error, diagnostic);
  return error;
}

/** Internal observation only. Forged properties/provider text cannot supply a diagnostic. */
export function coachContractDiagnostic(error) {
  return contractDiagnostics.get(error) ?? null;
}

/** True only for the exact object accepted by the authoritative Coach validator. */
export function isValidatedAiCoachProviderResponse(value) {
  return isObject(value) && validatedCoachResponses.get(value) === JSON.stringify(value);
}

function nullable(schema) {
  return { anyOf: [schema, { type: "null" }] };
}

const boundedString = (maxLength) => ({ type: "string", minLength: 1, maxLength });

const workoutExerciseSchema = {
  type: "object",
  additionalProperties: false,
  required: ["exerciseId", "name", "sets", "repsOrDuration", "restSeconds", "role"],
  properties: {
    exerciseId: boundedString(80),
    name: boundedString(120),
    sets: { type: "integer", minimum: 1, maximum: 10 },
    repsOrDuration: boundedString(40),
    restSeconds: { type: "integer", minimum: 0, maximum: 600 },
    role: { type: "string", enum: EXERCISE_ROLES }
  }
};

const workoutPlanSchema = {
  type: "object",
  additionalProperties: false,
  required: ["planId", "title", "goal", "requestedDays", "generatedDays", "progressionGuidance", "safetyNote"],
  properties: {
    planId: boundedString(100),
    title: boundedString(120),
    goal: boundedString(80),
    requestedDays: { type: "integer", minimum: 2, maximum: 6 },
    generatedDays: {
      type: "array",
      minItems: 2,
      maxItems: 6,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["dayName", "focus", "exercises"],
        properties: {
          dayName: boundedString(60),
          focus: boundedString(100),
          exercises: { type: "array", minItems: 1, maxItems: 8, items: workoutExerciseSchema }
        }
      }
    },
    progressionGuidance: boundedString(600),
    safetyNote: boundedString(600)
  }
};

const nutritionFields = {
  calories: nullable({ type: "number", minimum: 0, maximum: 3000 }),
  proteinGrams: nullable({ type: "number", minimum: 0, maximum: 300 }),
  carbsGrams: nullable({ type: "number", minimum: 0, maximum: 500 }),
  fatGrams: nullable({ type: "number", minimum: 0, maximum: 250 })
};

const dietPlanSchema = {
  type: "object",
  additionalProperties: false,
  required: ["planId", "title", "goal", "calorieTarget", "calorieTargetSource", "macroTargets", "macroTargetSource", "meals", "alternatives", "disclaimer"],
  properties: {
    planId: boundedString(100),
    title: boundedString(120),
    goal: boundedString(80),
    calorieTarget: nullable({ type: "integer", minimum: 1200, maximum: 6000 }),
    calorieTargetSource: { type: "string", enum: ["profile", "user_request", "unavailable"] },
    macroTargets: nullable({
      type: "object",
      additionalProperties: false,
      required: ["proteinGrams", "carbsGrams", "fatGrams"],
      properties: {
        proteinGrams: { type: "number", minimum: 0, maximum: 400 },
        carbsGrams: { type: "number", minimum: 0, maximum: 800 },
        fatGrams: { type: "number", minimum: 0, maximum: 300 }
      }
    }),
    macroTargetSource: { type: "string", enum: ["profile", "user_request", "unavailable"] },
    meals: {
      type: "array",
      minItems: 2,
      maxItems: 6,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["name", "foods"],
        properties: {
          name: boundedString(60),
          foods: {
            type: "array",
            minItems: 1,
            maxItems: 6,
            items: {
              type: "object",
              additionalProperties: false,
              required: ["foodRecordId", "name", "portion", "calories", "proteinGrams", "carbsGrams", "fatGrams", "nutritionSource"],
              properties: {
                foodRecordId: nullable(boundedString(80)),
                name: boundedString(120),
                portion: boundedString(120),
                ...nutritionFields,
                nutritionSource: { type: "string", enum: ["existing_record", "estimated", "unavailable"] }
              }
            }
          }
        }
      }
    },
    alternatives: { type: "array", maxItems: 10, items: boundedString(200) },
    disclaimer: boundedString(600)
  }
};

export const AI_COACH_JSON_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: [
    "summary", "recommendedAction", "nutritionNote", "workoutNote", "safetyDisclaimer",
    "escalationRequired", "detectedIntent", "detectedGoal", "confidence",
    "warnings", "generatedWorkoutPlan", "generatedDietPlan"
  ],
  properties: {
    summary: boundedString(1000),
    recommendedAction: boundedString(1200),
    nutritionNote: boundedString(1200),
    workoutNote: boundedString(4000),
    safetyDisclaimer: boundedString(800),
    escalationRequired: { type: "boolean" },
    detectedIntent: { type: "string", enum: INTENTS },
    detectedGoal: nullable(boundedString(80)),
    confidence: { type: "number", minimum: 0, maximum: 1 },
    warnings: { type: "array", maxItems: 10, items: boundedString(300) },
    generatedWorkoutPlan: nullable(workoutPlanSchema),
    generatedDietPlan: nullable(dietPlanSchema)
  }
};

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value, allowed, required = allowed) {
  return isObject(value)
    && Object.keys(value).every((key) => allowed.includes(key))
    && required.every((key) => Object.hasOwn(value, key));
}

function text(value, max) {
  return typeof value === "string" && value.length > 0 && value.length <= max;
}

function requestedDays(question) {
  const words = { two: 2, three: 3, four: 4, five: 5, six: 6 };
  const normalized = question.toLowerCase();
  for (const [word, count] of Object.entries(words)) {
    if (new RegExp(`\\b${word}[- ]day\\b`).test(normalized)) return count;
  }
  const match = normalized.match(/\b([2-6])[- ]day\b/);
  return match ? Number(match[1]) : null;
}

function equipmentRequested(question) {
  const normalized = question.toLowerCase();
  if (normalized.includes("dumbbell-only") || normalized.includes("dumbbell only")) return new Set(["dumbbell"]);
  if (normalized.includes("bodyweight-only") || normalized.includes("bodyweight only")) return new Set(["body weight", "bodyweight"]);
  return null;
}

function normalizedEquipment(values) {
  const aliases = new Map([
    ["dumbbells", "dumbbell"], ["barbells", "barbell"], ["bodyweight", "body weight"],
    ["body-weight", "body weight"], ["resistance bands", "band"], ["bands", "band"]
  ]);
  return new Set((values ?? []).map((value) => {
    const normalized = String(value).trim().toLowerCase();
    return aliases.get(normalized) ?? normalized;
  }));
}

function validateWorkoutPlan(plan, question, input, grounding) {
  const keys = ["planId", "title", "goal", "requestedDays", "generatedDays", "progressionGuidance", "safetyNote"];
  if (!exactKeys(plan, keys) || !text(plan.planId, 100) || !text(plan.title, 120)
      || !text(plan.goal, 80) || !Number.isInteger(plan.requestedDays)
      || plan.requestedDays < 2 || plan.requestedDays > 6
      || !Array.isArray(plan.generatedDays) || plan.generatedDays.length !== plan.requestedDays
      || !text(plan.progressionGuidance, 600) || !text(plan.safetyNote, 600)) return false;
  const explicitDays = requestedDays(question);
  if (explicitDays !== null && explicitDays !== plan.requestedDays) return false;
  if (input.workoutDays !== null && input.workoutDays !== undefined
      && input.workoutDays !== plan.requestedDays) return false;
  const dayNames = new Set();
  const requestedEquipment = equipmentRequested(question);
  const allowedEquipment = requestedEquipment ?? normalizedEquipment(input.equipment);
  for (const day of plan.generatedDays) {
    if (!exactKeys(day, ["dayName", "focus", "exercises"]) || !text(day.dayName, 60)
        || !text(day.focus, 100) || dayNames.has(day.dayName.toLowerCase())
        || !Array.isArray(day.exercises) || day.exercises.length < 1 || day.exercises.length > 8) return false;
    dayNames.add(day.dayName.toLowerCase());
    for (const exercise of day.exercises) {
      if (!exactKeys(exercise, ["exerciseId", "name", "sets", "repsOrDuration", "restSeconds", "role"])
          || !grounding.validExerciseIds.has(exercise.exerciseId)
          || !text(exercise.name, 120) || !Number.isInteger(exercise.sets) || exercise.sets < 1 || exercise.sets > 10
          || !text(exercise.repsOrDuration, 40) || !Number.isInteger(exercise.restSeconds)
          || exercise.restSeconds < 0 || exercise.restSeconds > 600 || !EXERCISE_ROLES.includes(exercise.role)) return false;
      const source = grounding.exerciseById.get(exercise.exerciseId);
      if (!source || source.name !== exercise.name) return false;
      if (exercise.role === "primary" && /warm[ -]?up/i.test(source.name)) return false;
      if (allowedEquipment.size > 0) {
        const equipment = (source.equipment ?? []).map((item) => String(item).toLowerCase());
        if (equipment.length && !equipment.every((item) => allowedEquipment.has(item))) return false;
        if (/\bbench\b/i.test(source.name) && !allowedEquipment.has("bench")) return false;
      }
    }
  }
  return true;
}

function sameNullableNumber(left, right) {
  return left === null ? right === null : typeof right === "number" && Math.abs(left - right) < 0.001;
}

function validateDietPlan(plan, input, grounding) {
  const keys = ["planId", "title", "goal", "calorieTarget", "calorieTargetSource", "macroTargets", "macroTargetSource", "meals", "alternatives", "disclaimer"];
  if (!exactKeys(plan, keys) || !text(plan.planId, 100) || !text(plan.title, 120) || !text(plan.goal, 80)
      || (plan.calorieTarget !== null && (!Number.isInteger(plan.calorieTarget) || plan.calorieTarget < 1200 || plan.calorieTarget > 6000))
      || !["profile", "user_request", "unavailable"].includes(plan.calorieTargetSource)
      || !["profile", "user_request", "unavailable"].includes(plan.macroTargetSource)
      || !Array.isArray(plan.meals) || plan.meals.length < 2 || plan.meals.length > 6
      || !Array.isArray(plan.alternatives) || plan.alternatives.length > 10
      || !plan.alternatives.every((item) => text(item, 200)) || !text(plan.disclaimer, 600)) return false;
  if (plan.macroTargets !== null) {
    if (!exactKeys(plan.macroTargets, ["proteinGrams", "carbsGrams", "fatGrams"])
        || typeof plan.macroTargets.proteinGrams !== "number" || plan.macroTargets.proteinGrams < 0 || plan.macroTargets.proteinGrams > 400
        || typeof plan.macroTargets.carbsGrams !== "number" || plan.macroTargets.carbsGrams < 0 || plan.macroTargets.carbsGrams > 800
        || typeof plan.macroTargets.fatGrams !== "number" || plan.macroTargets.fatGrams < 0 || plan.macroTargets.fatGrams > 300) return false;
  }
  if (input.calorieTarget == null) {
    if (plan.calorieTarget !== null || plan.calorieTargetSource !== "unavailable") return false;
  } else if (plan.calorieTarget !== input.calorieTarget || plan.calorieTargetSource === "unavailable") {
    return false;
  }
  const inputMacros = [input.proteinTargetGrams, input.carbsTargetGrams, input.fatTargetGrams];
  const hasInputMacros = inputMacros.some((value) => value !== null && value !== undefined);
  if (!hasInputMacros) {
    if (plan.macroTargets !== null || plan.macroTargetSource !== "unavailable") return false;
  } else if (plan.macroTargets === null || plan.macroTargetSource === "unavailable"
      || !sameNullableNumber(input.proteinTargetGrams ?? null, plan.macroTargets.proteinGrams)
      || !sameNullableNumber(input.carbsTargetGrams ?? null, plan.macroTargets.carbsGrams)
      || !sameNullableNumber(input.fatTargetGrams ?? null, plan.macroTargets.fatGrams)) {
    return false;
  }
  let calories = 0;
  let completeCalories = true;
  for (const meal of plan.meals) {
    if (!exactKeys(meal, ["name", "foods"]) || !text(meal.name, 60)
        || !Array.isArray(meal.foods) || meal.foods.length < 1 || meal.foods.length > 6) return false;
    for (const food of meal.foods) {
      const foodKeys = ["foodRecordId", "name", "portion", "calories", "proteinGrams", "carbsGrams", "fatGrams", "nutritionSource"];
      if (!exactKeys(food, foodKeys) || !text(food.name, 120) || !text(food.portion, 120)
          || !["existing_record", "estimated", "unavailable"].includes(food.nutritionSource)) return false;
      const values = [food.calories, food.proteinGrams, food.carbsGrams, food.fatGrams];
      if (values.some((value) => value !== null && (typeof value !== "number" || !Number.isFinite(value) || value < 0))) return false;
      if (food.nutritionSource === "unavailable" && values.some((value) => value !== null)) return false;
      if (food.foodRecordId === null) {
        if (food.nutritionSource !== "unavailable" || values.some((value) => value !== null)) return false;
      } else {
        if (!grounding.validFoodIds.has(food.foodRecordId)) return false;
        const source = grounding.foodById.get(food.foodRecordId);
        if (!source || source.name !== food.name) return false;
        const sourceValues = [source.calories, source.proteinGrams, source.carbsGrams, source.fatGrams];
        if (sourceValues.some((value, index) => !sameNullableNumber(value, values[index]))) return false;
        const expectedSource = sourceValues.every((value) => value === null) ? "unavailable" : "existing_record";
        if (food.nutritionSource !== expectedSource) return false;
      }
      if (food.calories === null) completeCalories = false; else calories += food.calories;
    }
  }
  if (plan.calorieTarget !== null && completeCalories) {
    const deviation = Math.abs(calories - plan.calorieTarget) / plan.calorieTarget;
    if (deviation > 0.2) return false;
  }
  if (plan.calorieTarget === null && completeCalories && (calories < 1200 || calories > 4500)) return false;
  return true;
}

export function validateAiCoachProviderResponse(value, { question, input = {}, grounding }) {
  const keys = [
    "summary", "recommendedAction", "nutritionNote", "workoutNote", "safetyDisclaimer",
    "escalationRequired", "detectedIntent", "detectedGoal", "confidence",
    "warnings", "generatedWorkoutPlan", "generatedDietPlan"
  ];
  const valid = exactKeys(value, keys)
    && text(value.summary, 1000) && text(value.recommendedAction, 1200)
    && text(value.nutritionNote, 1200) && text(value.workoutNote, 4000)
    && text(value.safetyDisclaimer, 800) && typeof value.escalationRequired === "boolean"
    && INTENTS.includes(value.detectedIntent)
    && (value.detectedGoal === null || text(value.detectedGoal, 80))
    && typeof value.confidence === "number" && value.confidence >= 0 && value.confidence <= 1
    && Array.isArray(value.warnings) && value.warnings.length <= 10 && value.warnings.every((warning) => text(warning, 300));
  if (!valid) throw contractFailure("TOP_LEVEL_CONTRACT_INVALID");
  if (value.generatedWorkoutPlan !== null && !validateWorkoutPlan(value.generatedWorkoutPlan, question, input, grounding)) {
    throw contractFailure("WORKOUT_PLAN_INVALID");
  }
  if (value.generatedDietPlan !== null && !validateDietPlan(value.generatedDietPlan, input, grounding)) {
    throw contractFailure("DIET_PLAN_INVALID");
  }
  const safety = reviewSafetyText(question);
  if ((safety.requiresEscalation && (!value.escalationRequired || value.detectedIntent !== "MEDICAL_ESCALATION"))
      || (!safety.requiresEscalation && value.escalationRequired)) {
    throw contractFailure("SAFETY_CONTRACT_INVALID");
  }
  validatedCoachResponses.set(value, JSON.stringify(value));
  return value;
}

export function parseProviderJson(content) {
  if (typeof content !== "string" || content.length === 0 || content.length > 30000) {
    throw contractFailure("JSON_PARSE_FAILED");
  }
  try {
    return JSON.parse(content);
  } catch {
    throw contractFailure("JSON_PARSE_FAILED");
  }
}
