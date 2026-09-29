import { SAFETY_DISCLAIMER } from "../schemas/responses.js";

export function analyzeFood(input) {
  return {
    foodName: input.foodName,
    servingSize: input.servingSize || "User portion not specified",
    estimateStatus: "needs_portion_and_recipe_confirmation",
    calorieEstimate: null,
    macroEstimate: null,
    note: "Pakistani mixed-dish nutrition varies by recipe, oil, and serving size. No value is fabricated when details are missing.",
    safetyNote: SAFETY_DISCLAIMER
  };
}

export function createDietPlan(input) {
  const mealCalories = Math.round(input.dailyCalories / input.mealsPerDay);
  return {
    goal: input.goal,
    dailyCalories: input.dailyCalories,
    mealsPerDay: input.mealsPerDay,
    dietPreference: input.dietPreference || "Not specified",
    allergiesExcluded: input.allergies,
    meals: Array.from({ length: input.mealsPerDay }, (_, index) => ({
      mealNumber: index + 1,
      targetCalories: mealCalories,
      template: "Protein source + sabzi or fruit + measured roti or rice portion"
    })),
    note: "Targets are planning estimates; recipes and household portions vary.",
    safetyNote: SAFETY_DISCLAIMER
  };
}
