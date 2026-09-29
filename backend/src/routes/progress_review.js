import { validateProgressRequest } from "../schemas/requests.js";
import { SAFETY_DISCLAIMER } from "../schemas/responses.js";
import { reviewSafetyText } from "../services/safety_filter.js";

export function progressReviewRoute(body) {
  const input = validateProgressRequest(body);
  const safety = reviewSafetyText(input.note);
  if (safety.requiresEscalation) {
    return { status: "needs_professional_review", message: safety.escalationMessage, review: null };
  }
  const averageMinutes = input.workoutsCompleted
    ? Math.round(input.totalDurationMinutes / input.workoutsCompleted)
    : 0;
  return {
    status: "ready",
    review: {
      workoutsCompleted: input.workoutsCompleted,
      averageDurationMinutes: averageMinutes,
      trainingVolumeKg: input.trainingVolumeKg,
      summary: input.workoutsCompleted === 0
        ? "No completed workouts were supplied for this review."
        : "Your review is based only on the completed workout totals supplied in this request.",
      recommendedAction: input.workoutsCompleted === 0
        ? "Complete and save a workout before requesting a progress review."
        : "Compare the next week using the same real metrics rather than increasing everything at once.",
      safetyNote: SAFETY_DISCLAIMER
    }
  };
}
