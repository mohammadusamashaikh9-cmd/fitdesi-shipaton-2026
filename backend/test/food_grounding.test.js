import assert from "node:assert/strict";
import test from "node:test";
import { buildCoachGrounding, getKnowledgeStore, resetKnowledgeCacheForTests } from "../src/services/knowledge_grounding.js";

test("Coach grounding uses the public-safe verified nutrition projection and stays bounded", async () => {
  resetKnowledgeCacheForTests();
  const store = await getKnowledgeStore();
  assert.equal(store.foods.length, 39);
  assert.equal(store.foods.filter((food) => food.isLoggable === true).length, 39);
  assert.equal(store.foods.filter((food) => food.isLoggable === false).length, 0);

  const verifiedRuntimeSources = new Set([
    "USDA_FOUNDATION_VERIFIED",
    "USDA_FNDDS_VERIFIED",
    "USDA_SR_LEGACY_VERIFIED",
    "USDA_BRANDED_LABEL_VERIFIED"
  ]);
  assert.ok(store.foods.every((food) => verifiedRuntimeSources.has(food.runtimeSource)));
  assert.deepEqual(
    Object.fromEntries([...verifiedRuntimeSources].map((runtimeSource) => [
      runtimeSource,
      store.foods.filter((food) => food.runtimeSource === runtimeSource).length
    ])),
    {
      USDA_FOUNDATION_VERIFIED: 15,
      USDA_FNDDS_VERIFIED: 11,
      USDA_SR_LEGACY_VERIFIED: 12,
      USDA_BRANDED_LABEL_VERIFIED: 1
    }
  );
  assert.ok(store.foods.every((food) => food.fitDesiReviewStatus === "FITDESI_NUTRITION_VERIFIED"));
  assert.ok(store.foods.every((food) => food.nutritionBasis === "VERIFIED_PER_100G"));
  assert.ok(store.foods.every((food) => food.licenceStatus === "VERIFIED_COMMERCIAL"));
  assert.ok(store.foods.every((food) => food.isLoggable === true));

  const grounding = await buildCoachGrounding({ question: "Pakistani protein foods", equipment: [] });
  assert.ok(grounding.foods.length > 0);
  assert.ok(grounding.foods.length <= 10);
});

test("no unresolved legacy or Nourish nutrition reaches AI grounding", async () => {
  resetKnowledgeCacheForTests();
  const store = await getKnowledgeStore();
  assert.equal(store.foods.filter((food) => food.runtimeSource === "IMPORTED_REVIEW_REQUIRED").length, 0);
  assert.equal(store.foods.filter((food) => food.licenceStatus === "UNKNOWN").length, 0);
  assert.equal(store.foods.filter((food) => food.fitDesiReviewStatus === "NEEDS_FITDESI_NUTRITION_REVIEW").length, 0);
  assert.equal(store.foods.filter((food) => food.nutritionBasis === "EXISTING_FITDESI_SERVING_ESTIMATE").length, 0);
  assert.ok(store.foods.every((food) => String(food.id).startsWith("fd-food-usda-")));
});

test("South Asian discovery records are never emitted to grounding with inferred or zeroed nutrition", async () => {
  resetKnowledgeCacheForTests();
  const store = await getKnowledgeStore();
  // Discovery layer is intentionally excluded from AI grounding entirely.
  assert.equal(store.foods.filter((food) => String(food.id).startsWith("fd-discovery-")).length, 0);

  const grounding = await buildCoachGrounding({ question: "chicken biryani calories", equipment: [] });
  assert.equal(grounding.foods.filter((food) => String(food.id).startsWith("fd-discovery-")).length, 0);
  // Every emitted food that is loggable carries real finite nutrition, never a fabricated zero.
  for (const food of grounding.foods) {
    if (food.isLoggable) {
      assert.ok(Number.isFinite(food.calories) && food.calories > 0);
    } else {
      assert.equal(food.calories, null);
    }
  }
});

test("review-required food grounding never exposes unclear nutrition as serving values", async () => {
  const store = {
    exercises: [],
    foods: [{
      id: "fd-food-nourish-test",
      name: "Review Food",
      aliases: ["test food"],
      category: "Protein & Main Dishes",
      servingSize: "Serving basis under review",
      calories: 999,
      proteinGrams: 99,
      carbsGrams: 99,
      fatGrams: 99,
      nutritionBasis: "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G",
      fitDesiReviewStatus: "NEEDS_FITDESI_NUTRITION_REVIEW",
      isLoggable: false,
      runtimeSource: "IMPORTED_REVIEW_REQUIRED",
      licenceStatus: "UNKNOWN"
    }],
    rules: []
  };
  const grounding = await buildCoachGrounding({ question: "review food", equipment: [] }, { store });
  assert.equal(grounding.foods.length, 1);
  assert.equal(grounding.foods[0].calories, null);
  assert.equal(grounding.foods[0].proteinGrams, null);
  assert.equal(grounding.foods[0].servingDescription, "Serving basis under review");
  assert.equal(grounding.foods[0].isLoggable, false);
  assert.equal(grounding.foods[0].sourceType, "IMPORTED_REVIEW_REQUIRED");
});
