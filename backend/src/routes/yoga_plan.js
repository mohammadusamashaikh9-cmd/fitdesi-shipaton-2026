import { validateYogaRequest } from "../schemas/requests.js";
import { SAFETY_DISCLAIMER } from "../schemas/responses.js";
import { reviewSafetyText } from "../services/safety_filter.js";

export function yogaPlanRoute(body) {
  const input = validateYogaRequest(body);
  const safety = reviewSafetyText(input.limitations);
  if (safety.requiresEscalation) {
    return { status: "needs_professional_review", message: safety.escalationMessage, plan: null };
  }
  return {
    status: "ready",
    plan: {
      name: `FitDesi ${input.goal} Mobility Session`,
      level: input.level,
      durationMinutes: input.durationMinutes,
      sequence: [
        { movement: "Easy breathing and joint circles", minutes: 3 },
        { movement: "Cat-Cow", minutes: 3 },
        { movement: "Supported low lunge", minutes: 4 },
        { movement: "Child's pose or comfortable rest", minutes: 3 }
      ],
      note: "Adjust time in each movement to match the requested duration and remain in a comfortable range.",
      safetyNote: SAFETY_DISCLAIMER
    }
  };
}
