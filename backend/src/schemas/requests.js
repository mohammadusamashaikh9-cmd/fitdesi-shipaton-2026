import { ValidationError } from "../errors.js";

const LEVELS = ["beginner", "intermediate", "advanced"];
const GOALS = ["gain muscle", "lose body fat", "get stronger", "general fitness", "increase endurance"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const COACH_CONTEXT_ROLES = ["user", "assistant"];
const MAX_COACH_CONTEXT_MESSAGES = 6;
const MAX_COACH_CONTEXT_TEXT = 1000;
const MAX_COACH_CONTEXT_TOTAL = 4000;

function strictObject(body, allowedFields) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ValidationError("Request body must be a JSON object.");
  }
  const unknownFields = Object.keys(body).filter((field) => !allowedFields.includes(field));
  if (unknownFields.length) {
    throw new ValidationError(`Unsupported field: ${unknownFields[0]}.`);
  }
}

function text(value, field, { min = 1, max = 500 } = {}) {
  if (typeof value !== "string" || value.trim().length < min || value.trim().length > max) {
    throw new ValidationError(`${field} must be between ${min} and ${max} characters.`);
  }
  return value.trim();
}

function number(value, field, { min, max, integer = false } = {}) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) {
    throw new ValidationError(`${field} must be ${integer ? "an integer " : ""}between ${min} and ${max}.`);
  }
  return value;
}

function strings(value, field, { min = 0, max = 10, itemMax = 80 } = {}) {
  if (!Array.isArray(value) || value.length < min || value.length > max) {
    throw new ValidationError(`${field} must contain between ${min} and ${max} items.`);
  }
  return value.map((item, index) => text(item, `${field}[${index}]`, { max: itemMax }));
}

function optionalText(value, field, max = 500) {
  return value == null || value === "" ? "" : text(value, field, { max });
}

function optionalBoolean(value, field, fallback) {
  if (value == null) return fallback;
  if (typeof value !== "boolean") throw new ValidationError(`${field} must be true or false.`);
  return value;
}

function enumText(value, field, allowed) {
  const normalized = text(value, field, { max: 80 }).toLowerCase();
  if (!allowed.includes(normalized)) throw new ValidationError(`${field} is not supported.`);
  return normalized;
}

function coachConversationContext(value) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > MAX_COACH_CONTEXT_MESSAGES) {
    throw new ValidationError(`conversationContext must contain between 0 and ${MAX_COACH_CONTEXT_MESSAGES} items.`);
  }
  let totalCharacters = 0;
  const messages = value.map((message, index) => {
    strictObject(message, ["role", "text"]);
    const role = text(message.role, `conversationContext[${index}].role`, { max: 20 }).toLowerCase();
    if (!COACH_CONTEXT_ROLES.includes(role)) {
      throw new ValidationError(`conversationContext[${index}].role is not supported.`);
    }
    const messageText = text(message.text, `conversationContext[${index}].text`, {
      max: MAX_COACH_CONTEXT_TEXT
    });
    totalCharacters += messageText.length;
    return { role, text: messageText };
  });
  if (totalCharacters > MAX_COACH_CONTEXT_TOTAL) {
    throw new ValidationError(`conversationContext must not exceed ${MAX_COACH_CONTEXT_TOTAL} characters.`);
  }
  return messages;
}

export function validateCoachRequest(body) {
  strictObject(body, [
    "question", "goal", "experience", "equipment", "recentWorkoutSummary",
    "calorieTarget", "proteinTargetGrams", "carbsTargetGrams", "fatTargetGrams",
    "dietaryPreference", "mealsPerDay", "workoutDays", "limitations", "conversationContext"
  ]);
  const optionalNumber = (value, field, options) => value == null
    ? null
    : number(value, field, options);
  return {
    question: text(body.question, "question", { max: 1200 }),
    goal: optionalText(body.goal, "goal", 80),
    experience: body.experience == null || body.experience === ""
      ? ""
      : enumText(body.experience, "experience", LEVELS),
    equipment: strings(body.equipment ?? [], "equipment", { max: 12, itemMax: 80 }),
    recentWorkoutSummary: optionalText(body.recentWorkoutSummary, "recentWorkoutSummary", 500),
    calorieTarget: optionalNumber(body.calorieTarget, "calorieTarget", { min: 1200, max: 6000, integer: true }),
    proteinTargetGrams: optionalNumber(body.proteinTargetGrams, "proteinTargetGrams", { min: 0, max: 400 }),
    carbsTargetGrams: optionalNumber(body.carbsTargetGrams, "carbsTargetGrams", { min: 0, max: 800 }),
    fatTargetGrams: optionalNumber(body.fatTargetGrams, "fatTargetGrams", { min: 0, max: 300 }),
    dietaryPreference: optionalText(body.dietaryPreference, "dietaryPreference", 80),
    mealsPerDay: optionalNumber(body.mealsPerDay, "mealsPerDay", { min: 2, max: 6, integer: true }),
    workoutDays: optionalNumber(body.workoutDays, "workoutDays", { min: 2, max: 6, integer: true }),
    limitations: strings(body.limitations ?? [], "limitations", { max: 10, itemMax: 120 }),
    conversationContext: coachConversationContext(body.conversationContext)
  };
}

export function validateWorkoutRequest(body) {
  strictObject(body, [
    "age", "gender", "level", "goal", "daysPerWeek", "selectedDays", "split",
    "equipment", "enforceRecovery", "note", "limitations"
  ]);
  const daysPerWeek = number(body.daysPerWeek, "daysPerWeek", { min: 1, max: 7, integer: true });
  const selectedDays = strings(body.selectedDays, "selectedDays", { min: daysPerWeek, max: 7, itemMax: 3 });
  if (selectedDays.some((day) => !DAYS.includes(day)) || new Set(selectedDays).size !== selectedDays.length) {
    throw new ValidationError("selectedDays must contain unique weekday abbreviations.");
  }
  return {
    age: number(body.age, "age", { min: 15, max: 80, integer: true }),
    gender: text(body.gender, "gender", { max: 40 }),
    level: enumText(body.level, "level", LEVELS),
    goal: enumText(body.goal, "goal", GOALS),
    daysPerWeek,
    selectedDays: selectedDays.slice(0, daysPerWeek),
    split: text(body.split, "split", { max: 80 }),
    equipment: strings(body.equipment, "equipment", { min: 1, max: 12 }),
    enforceRecovery: optionalBoolean(body.enforceRecovery, "enforceRecovery", true),
    note: optionalText(body.note, "note", 1000),
    limitations: strings(body.limitations ?? [], "limitations", { max: 10, itemMax: 120 })
  };
}

export function validateFoodRequest(body) {
  strictObject(body, ["foodName", "servingSize"]);
  return {
    foodName: text(body.foodName, "foodName", { max: 120 }),
    servingSize: optionalText(body.servingSize, "servingSize", 80)
  };
}

export function validateDietRequest(body) {
  strictObject(body, [
    "goal", "dailyCalories", "mealsPerDay", "dietPreference", "allergies", "medicalNotes"
  ]);
  return {
    goal: enumText(body.goal, "goal", GOALS),
    dailyCalories: number(body.dailyCalories, "dailyCalories", { min: 1000, max: 6000, integer: true }),
    mealsPerDay: number(body.mealsPerDay, "mealsPerDay", { min: 1, max: 8, integer: true }),
    dietPreference: optionalText(body.dietPreference, "dietPreference", 80),
    allergies: strings(body.allergies ?? [], "allergies", { max: 20, itemMax: 80 }),
    medicalNotes: optionalText(body.medicalNotes, "medicalNotes", 1000)
  };
}

export function validateYogaRequest(body) {
  strictObject(body, ["goal", "level", "durationMinutes", "limitations"]);
  return {
    goal: text(body.goal, "goal", { max: 120 }),
    level: enumText(body.level, "level", LEVELS),
    durationMinutes: number(body.durationMinutes, "durationMinutes", { min: 5, max: 90, integer: true }),
    limitations: strings(body.limitations ?? [], "limitations", { max: 10, itemMax: 120 })
  };
}

export function validateProgressRequest(body) {
  strictObject(body, [
    "workoutsCompleted", "totalDurationMinutes", "trainingVolumeKg", "note"
  ]);
  return {
    workoutsCompleted: number(body.workoutsCompleted, "workoutsCompleted", { min: 0, max: 1000, integer: true }),
    totalDurationMinutes: number(body.totalDurationMinutes, "totalDurationMinutes", { min: 0, max: 100000, integer: true }),
    trainingVolumeKg: body.trainingVolumeKg == null ? 0 : number(body.trainingVolumeKg, "trainingVolumeKg", { min: 0, max: 100000000 }),
    note: optionalText(body.note, "note", 1000)
  };
}
