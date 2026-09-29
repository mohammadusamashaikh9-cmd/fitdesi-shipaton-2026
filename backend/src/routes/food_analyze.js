import { validateFoodRequest } from "../schemas/requests.js";
import { analyzeFood } from "../services/nutrition_engine.js";

export function foodAnalyzeRoute(body) {
  return analyzeFood(validateFoodRequest(body));
}
