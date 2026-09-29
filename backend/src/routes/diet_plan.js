import { validateDietRequest } from "../schemas/requests.js";
import { createDietPlan } from "../services/nutrition_engine.js";
import { reviewSafetyText } from "../services/safety_filter.js";

export function dietPlanRoute(body) {
  const input = validateDietRequest(body);
  const safety = reviewSafetyText(input.medicalNotes, input.allergies);
  if (safety.requiresEscalation) {
    return { status: "needs_professional_review", message: safety.escalationMessage, plan: null };
  }
  return { status: "ready", plan: createDietPlan(input) };
}
