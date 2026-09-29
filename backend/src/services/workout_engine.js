import { SAFETY_DISCLAIMER } from "../schemas/responses.js";

const MOVEMENTS = {
  bodyweight: ["Bodyweight Squat", "Push-Up", "Reverse Lunge", "Front Plank"],
  dumbbells: ["Goblet Squat", "Dumbbell Press", "One-Arm Row", "Dumbbell Romanian Deadlift"],
  default: ["Squat Pattern", "Push Pattern", "Pull Pattern", "Hip Hinge"]
};

export function createWorkoutPlan(input) {
  const equipmentText = input.equipment.join(" ").toLowerCase();
  const movements = equipmentText.includes("bodyweight")
    ? MOVEMENTS.bodyweight
    : equipmentText.includes("dumbbell")
      ? MOVEMENTS.dumbbells
      : MOVEMENTS.default;
  const prescription = input.goal === "get stronger"
    ? { sets: 4, reps: "4-6", restSeconds: 150 }
    : input.goal === "lose body fat"
      ? { sets: 3, reps: "10-15", restSeconds: 60 }
      : { sets: input.level === "beginner" ? 2 : 3, reps: "8-12", restSeconds: 90 };

  return {
    routineName: `FitDesi ${input.goal.replace(/\b\w/g, (letter) => letter.toUpperCase())} Plan`,
    daysPerWeek: input.daysPerWeek,
    splitType: input.split,
    workoutDays: input.selectedDays.map((day, index) => ({
      day,
      title: `${input.split} Session ${index + 1}`,
      exercises: movements.map((name) => ({ name, ...prescription }))
    })),
    explanation: `A deterministic ${input.level} plan using the selected equipment and goal.`,
    safetyNote: SAFETY_DISCLAIMER
  };
}
