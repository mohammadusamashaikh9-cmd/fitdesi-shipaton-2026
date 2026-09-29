import { validateWorkoutRequest } from "../schemas/requests.js";
import { createWorkoutPlan } from "../services/workout_engine.js";
import { reviewSafetyText } from "../services/safety_filter.js";

export function workoutPlanRoute(body) {
  const input = validateWorkoutRequest(body);
  const safety = reviewSafetyText(input.note, input.limitations);
  if (safety.requiresEscalation) {
    return {
      status: "needs_professional_review",
      message: safety.escalationMessage,
      plan: null
    };
  }
  return { status: "ready", plan: createWorkoutPlan(input) };
}
