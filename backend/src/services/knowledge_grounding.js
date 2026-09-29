import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const KNOWLEDGE_ROOT = new URL("../../../mobile/app/src/main/assets/knowledge/v1/", import.meta.url);
// Public-safe verified nutrition projection only (39 commercially verified records).
// The South Asian discovery layer is intentionally excluded from AI grounding: it carries
// no nutrition values, and discovery items must never reach an LLM with fabricated or
// zeroed calories/macros.
const PUBLIC_VERIFIED_FOODS = new URL("../../../mobile/app/src/main/assets/pakistani_food_catalogue_public.json", import.meta.url);
const GENERAL_FILES = ["workout_rules.json", "safety_rules.json", "coaching_rules.json", "progressions.json"];

let cachedKnowledge;

async function readJson(url) {
  return JSON.parse(await readFile(fileURLToPath(url), "utf8"));
}

function terms(value) {
  return new Set(String(value).toLowerCase().match(/[a-z0-9]+/g) ?? []);
}

function searchableText(record) {
  return [
    record.name,
    record.title,
    record.category,
    record.bodyPart,
    record.target,
    record.muscleGroup,
    record.guidance,
    record.instructions,
    ...(record.aliases ?? []),
    ...(record.goals ?? []),
    ...(record.equipment ?? []),
    ...(record.keywords ?? [])
  ].filter(Boolean).join(" ");
}

function scoreRecord(record, queryTerms) {
  const recordTerms = terms(searchableText(record));
  let score = Number(record.priority ?? 0) / 100;
  for (const term of queryTerms) if (recordTerms.has(term)) score += 1;
  if (queryTerms.has("protein") && record.isLoggable && Number(record.proteinGrams) >= 10) score += 2;
  return score;
}

function rank(records, question, limit) {
  const queryTerms = terms(question);
  return records
    .map((record, index) => ({ record, index, score: scoreRecord(record, queryTerms) }))
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .slice(0, limit)
    .map(({ record }) => record);
}

function sanitizeExercise(record) {
  return {
    id: String(record.id),
    name: record.name,
    bodyPart: record.bodyPart,
    target: record.target,
    muscleGroup: record.muscleGroup,
    equipment: Array.isArray(record.equipment) ? record.equipment.slice(0, 6) : [],
    instructions: record.instructions,
    safetyNote: record.safetyNote
  };
}

function sanitizeFood(record) {
  const reviewed = record.isLoggable === true;
  return {
    id: String(record.id),
    name: record.name,
    category: record.category,
    aliases: Array.isArray(record.aliases) ? record.aliases.slice(0, 8) : [],
    servingDescription: reviewed ? record.servingSize : "Serving basis under review",
    calories: reviewed && Number.isFinite(record.calories) ? record.calories : null,
    proteinGrams: reviewed && Number.isFinite(record.proteinGrams) ? record.proteinGrams : null,
    carbsGrams: reviewed && Number.isFinite(record.carbsGrams) ? record.carbsGrams : null,
    fatGrams: reviewed && Number.isFinite(record.fatGrams) ? record.fatGrams : null,
    nutritionBasis: record.nutritionBasis,
    reviewStatus: record.fitDesiReviewStatus,
    isLoggable: reviewed,
    sourceType: record.runtimeSource,
    licenseStatus: record.licenceStatus ?? "UNKNOWN"
  };
}

function sanitizeRule(record) {
  return {
    id: String(record.id),
    title: record.title,
    guidance: record.guidance,
    severity: record.severity ?? null
  };
}

async function loadKnowledge() {
  const [exercises, mergedFoodFile, ...ruleSets] = await Promise.all([
    readJson(new URL("exercises.json", KNOWLEDGE_ROOT)),
    readJson(PUBLIC_VERIFIED_FOODS),
    ...GENERAL_FILES.map((name) => readJson(new URL(name, KNOWLEDGE_ROOT)))
  ]);
  const foods = mergedFoodFile.records;
  if (!Array.isArray(exercises) || !Array.isArray(foods)) {
    throw new Error("FitDesi knowledge assets are malformed.");
  }
  return Object.freeze({
    exercises,
    foods,
    rules: ruleSets.flat()
  });
}

export async function getKnowledgeStore() {
  cachedKnowledge ??= loadKnowledge();
  return cachedKnowledge;
}

export function resetKnowledgeCacheForTests() {
  cachedKnowledge = undefined;
}

function normalizedEquipment(values) {
  const aliases = new Map([
    ["dumbbells", "dumbbell"], ["barbells", "barbell"], ["bodyweight", "body weight"],
    ["body-weight", "body weight"], ["resistance bands", "band"], ["bands", "band"]
  ]);
  return new Set((values ?? []).map((value) => {
    const normalized = String(value).trim().toLowerCase();
    return aliases.get(normalized) ?? normalized;
  }).filter(Boolean));
}

function equipmentCompatible(record, available) {
  if (available.size === 0) return true;
  const required = (record.equipment ?? []).map((value) => String(value).trim().toLowerCase());
  if (/\bbench\b/i.test(record.name ?? "") && !available.has("bench")) return false;
  return required.length === 0 || required.every((value) => available.has(value));
}

export async function buildCoachGrounding(input, { store } = {}) {
  const knowledge = store ?? await getKnowledgeStore();
  const question = typeof input === "string" ? input : input.question;
  const equipment = normalizedEquipment(typeof input === "string" ? [] : input.equipment);
  const exercisePool = knowledge.exercises.filter((record) => equipmentCompatible(record, equipment));
  const relevantExercises = rank(exercisePool, question, 20).map(sanitizeExercise);
  const allFoods = knowledge.foods.map(sanitizeFood);
  const foods = rank(allFoods, question, 10);
  const exerciseById = new Map(relevantExercises.map((record) => [record.id, record]));
  const foodById = new Map(foods.map((record) => [record.id, record]));
  return {
    exercises: relevantExercises,
    foods,
    generalKnowledge: rank(knowledge.rules, question, 10).map(sanitizeRule),
    validExerciseIds: new Set(exerciseById.keys()),
    exerciseById,
    validFoodIds: new Set(foodById.keys()),
    foodById
  };
}
