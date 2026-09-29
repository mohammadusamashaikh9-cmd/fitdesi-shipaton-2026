const HIGH_RISK_TERMS = [
  "chest pain", "fainting", "feel faint", "severe breathing difficulty",
  "cannot breathe", "sudden severe pain", "loss of consciousness",
  "unconscious", "emergency symptoms", "suicidal", "suicide"
];

export function reviewSafetyText(...values) {
  const combined = values.flat(Infinity).filter(Boolean).join(" ").toLowerCase();
  const matches = HIGH_RISK_TERMS.filter((term) => combined.includes(term));
  return {
    requiresEscalation: matches.length > 0,
    categories: matches,
    escalationMessage: matches.length
      ? "Pause exercise or diet changes and consult a qualified professional. Seek urgent care for severe, sudden, or emergency symptoms."
      : ""
  };
}
