import { AI_COACH_JSON_SCHEMA } from "./ai_coach_contract.js";

function object(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value, keys) {
  return object(value)
    && Object.keys(value).length === keys.length
    && keys.every((key) => Object.hasOwn(value, key));
}

function text(value) {
  return typeof value === "string" && value.length > 0;
}

function finiteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function nullableNumber(value) {
  return value === null || finiteNumber(value);
}

function textArray(value) {
  return Array.isArray(value) && value.every(text);
}

function schemaValue(value, schema) {
  if (schema.anyOf) return schema.anyOf.some((candidate) => schemaValue(value, candidate));
  if (schema.enum && !schema.enum.includes(value)) return false;
  switch (schema.type) {
    case "null":
      return value === null;
    case "boolean":
      return typeof value === "boolean";
    case "string":
      return typeof value === "string"
        && (schema.minLength === undefined || value.length >= schema.minLength)
        && (schema.maxLength === undefined || value.length <= schema.maxLength);
    case "number":
      return finiteNumber(value)
        && (schema.minimum === undefined || value >= schema.minimum)
        && (schema.maximum === undefined || value <= schema.maximum);
    case "integer":
      return Number.isInteger(value)
        && (schema.minimum === undefined || value >= schema.minimum)
        && (schema.maximum === undefined || value <= schema.maximum);
    case "array":
      return Array.isArray(value)
        && (schema.minItems === undefined || value.length >= schema.minItems)
        && (schema.maxItems === undefined || value.length <= schema.maxItems)
        && value.every((item) => schemaValue(item, schema.items));
    case "object": {
      if (!object(value)) return false;
      const properties = schema.properties ?? {};
      if (schema.additionalProperties === false
          && Object.keys(value).some((key) => !Object.hasOwn(properties, key))) return false;
      if ((schema.required ?? []).some((key) => !Object.hasOwn(value, key))) return false;
      return Object.entries(value).every(([key, item]) =>
        !Object.hasOwn(properties, key) || schemaValue(item, properties[key]));
    }
    default:
      return false;
  }
}

function remoteCoach(value) {
  const providerKeys = Object.keys(AI_COACH_JSON_SCHEMA.properties);
  return exactKeys(value, [...providerKeys, "validationStatus"])
    && value.validationStatus === "VALIDATED"
    && providerKeys.every((key) => schemaValue(value[key], AI_COACH_JSON_SCHEMA.properties[key]));
}

function safetyPlanResponse(value, planValidator) {
  if (!exactKeys(value, ["status", "plan"])) {
    if (!exactKeys(value, ["status", "message", "plan"])) return false;
    return value.status === "needs_professional_review"
      && text(value.message)
      && value.plan === null;
  }
  return value.status === "ready" && planValidator(value.plan);
}

function exercise(value) {
  return exactKeys(value, ["name", "sets", "reps", "restSeconds"])
    && text(value.name)
    && Number.isInteger(value.sets)
    && value.sets > 0
    && text(value.reps)
    && Number.isInteger(value.restSeconds)
    && value.restSeconds > 0;
}

function workoutDay(value) {
  return exactKeys(value, ["day", "title", "exercises"])
    && text(value.day)
    && text(value.title)
    && Array.isArray(value.exercises)
    && value.exercises.length > 0
    && value.exercises.every(exercise);
}

function workoutPlan(value) {
  return exactKeys(value, [
    "routineName", "daysPerWeek", "splitType", "workoutDays", "explanation", "safetyNote"
  ])
    && text(value.routineName)
    && Number.isInteger(value.daysPerWeek)
    && value.daysPerWeek >= 1
    && value.daysPerWeek <= 7
    && text(value.splitType)
    && Array.isArray(value.workoutDays)
    && value.workoutDays.length === value.daysPerWeek
    && value.workoutDays.every(workoutDay)
    && text(value.explanation)
    && text(value.safetyNote);
}

function dietMeal(value) {
  return exactKeys(value, ["mealNumber", "targetCalories", "template"])
    && Number.isInteger(value.mealNumber)
    && value.mealNumber > 0
    && Number.isInteger(value.targetCalories)
    && value.targetCalories > 0
    && text(value.template);
}

function dietPlan(value) {
  return exactKeys(value, [
    "goal", "dailyCalories", "mealsPerDay", "dietPreference", "allergiesExcluded",
    "meals", "note", "safetyNote"
  ])
    && text(value.goal)
    && Number.isInteger(value.dailyCalories)
    && Number.isInteger(value.mealsPerDay)
    && text(value.dietPreference)
    && textArray(value.allergiesExcluded)
    && Array.isArray(value.meals)
    && value.meals.length === value.mealsPerDay
    && value.meals.every(dietMeal)
    && text(value.note)
    && text(value.safetyNote);
}

function yogaPlan(value) {
  return exactKeys(value, [
    "name", "level", "durationMinutes", "sequence", "note", "safetyNote"
  ])
    && text(value.name)
    && text(value.level)
    && Number.isInteger(value.durationMinutes)
    && Array.isArray(value.sequence)
    && value.sequence.length > 0
    && value.sequence.every((item) =>
      exactKeys(item, ["movement", "minutes"])
      && text(item.movement)
      && Number.isInteger(item.minutes)
      && item.minutes > 0)
    && text(value.note)
    && text(value.safetyNote);
}

function progressReview(value) {
  return exactKeys(value, [
    "workoutsCompleted", "averageDurationMinutes", "trainingVolumeKg", "summary",
    "recommendedAction", "safetyNote"
  ])
    && Number.isInteger(value.workoutsCompleted)
    && finiteNumber(value.averageDurationMinutes)
    && finiteNumber(value.trainingVolumeKg)
    && text(value.summary)
    && text(value.recommendedAction)
    && text(value.safetyNote);
}

export const responseValidators = Object.freeze({
  health(value) {
    return exactKeys(value, ["status", "service", "providerMode", "timestamp"])
      && value.status === "ok"
      && value.service === "fitdesi-ai-backend"
      && ["mock", "disabled", "fireworks"].includes(value.providerMode)
      && text(value.timestamp)
      && !Number.isNaN(Date.parse(value.timestamp));
  },
  coach(value) {
    const legacyCoach = exactKeys(value, [
      "summary", "recommendedAction", "nutritionNote", "workoutNote", "safetyDisclaimer"
    ])
      && text(value.summary)
      && text(value.recommendedAction)
      && text(value.nutritionNote)
      && text(value.workoutNote)
      && text(value.safetyDisclaimer);
    return legacyCoach || remoteCoach(value);
  },
  workout(value) {
    return safetyPlanResponse(value, workoutPlan);
  },
  food(value) {
    return exactKeys(value, [
      "foodName", "servingSize", "estimateStatus", "calorieEstimate",
      "macroEstimate", "note", "safetyNote"
    ])
      && text(value.foodName)
      && text(value.servingSize)
      && value.estimateStatus === "needs_portion_and_recipe_confirmation"
      && nullableNumber(value.calorieEstimate)
      && value.macroEstimate === null
      && text(value.note)
      && text(value.safetyNote);
  },
  diet(value) {
    return safetyPlanResponse(value, dietPlan);
  },
  yoga(value) {
    return safetyPlanResponse(value, yogaPlan);
  },
  progress(value) {
    if (!exactKeys(value, ["status", "review"])) {
      if (!exactKeys(value, ["status", "message", "review"])) return false;
      return value.status === "needs_professional_review"
        && text(value.message)
        && value.review === null;
    }
    return value.status === "ready" && progressReview(value.review);
  }
});

export function validateSuccessEnvelope(value) {
  return exactKeys(value, ["success", "requestId", "mode", "data"])
    && value.success === true
    && text(value.requestId)
    && ["mock", "fireworks"].includes(value.mode)
    && object(value.data);
}

export function validateErrorEnvelope(value) {
  return exactKeys(value, ["success", "error"])
    && value.success === false
    && exactKeys(value.error, ["code", "message", "requestId"])
    && text(value.error.code)
    && text(value.error.message)
    && text(value.error.requestId);
}
