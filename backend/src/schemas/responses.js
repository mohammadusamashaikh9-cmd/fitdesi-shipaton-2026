export const SAFETY_DISCLAIMER =
  "General fitness and nutrition education only, not medical diagnosis or treatment. Consult a qualified professional for injury, disease, severe pain, pregnancy, medication concerns, or other medical issues.";

export function successResponse(requestId, data, mode = "mock") {
  return { success: true, requestId, mode, data };
}

export function errorResponse(requestId, code, message) {
  return { success: false, error: { code, message, requestId } };
}

export function coachResponse({ summary, recommendedAction, nutritionNote, workoutNote, safetyDisclaimer = SAFETY_DISCLAIMER }) {
  return { summary, recommendedAction, nutritionNote, workoutNote, safetyDisclaimer };
}
