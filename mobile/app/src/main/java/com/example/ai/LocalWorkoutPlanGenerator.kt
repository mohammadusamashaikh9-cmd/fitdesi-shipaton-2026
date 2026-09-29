package com.example.ai

import com.example.ai.knowledge.ExercisePackRecord
import com.example.domain.TrainingExperience

internal data class LocalWorkoutPlanInput(
    val goal: String?,
    val experience: String?,
    val requestedDays: Int,
    val availableTrainingDays: Int = requestedDays,
    val explicitFrequencyRequest: Boolean = false,
    val equipment: Set<String>,
    val recentWorkoutSummary: String?,
    val limitations: Set<String>,
    val requestedDurationMinutes: Int? = null,
    val explicitlyRequestedTerms: Set<String> = emptySet(),
    val splitPreference: WorkoutSplitPreference = WorkoutSplitPreference.AUTO,
    val frequencyAdjustmentNote: String? = null,
    val exercises: List<ExercisePackRecord>
)

internal enum class WorkoutSplitPreference {
    AUTO,
    PUSH_PULL_LEGS,
    UPPER_LOWER,
    FULL_BODY
}

internal data class LocalWorkoutPlan(
    val title: String,
    val weeklySummary: String,
    val days: List<LocalWorkoutDay>,
    val progressionGuidance: String,
    val recoveryGuidance: String,
    val safetyNote: String,
    val fallbackUsed: Boolean
)

internal data class LocalWorkoutDay(
    val dayName: String,
    val focus: String,
    val exercises: List<LocalWorkoutExercise>
)

internal data class LocalWorkoutExercise(
    val id: String,
    val name: String,
    val movementPattern: MovementPattern,
    val sets: Int,
    val repsOrDuration: String,
    val restSeconds: Int
)

internal enum class MovementPattern {
    HORIZONTAL_PUSH,
    VERTICAL_PUSH,
    HORIZONTAL_PULL,
    VERTICAL_PULL,
    SQUAT,
    HINGE,
    LUNGE,
    ISOLATION_BICEPS,
    ISOLATION_TRICEPS,
    SHOULDER_ISOLATION,
    CORE,
    CARRY,
    CONDITIONING,
    EXPLOSIVE_OR_TECHNICAL,
    OTHER
}

internal data class ClassifiedExercise(
    val record: ExercisePackRecord,
    val pattern: MovementPattern,
    val requiredEquipment: Set<String>
)

internal object ExerciseMovementClassifier {
    fun classify(record: ExercisePackRecord): ClassifiedExercise {
        val name = normalize(record.name)
        val target = normalize(record.target)
        val bodyPart = normalize(record.bodyPart)
        val metadataPattern = normalize(record.movementPattern)
        val pattern = when {
            TECHNICAL_TERMS.any(name::contains) -> MovementPattern.EXPLOSIVE_OR_TECHNICAL
            "burpee" in name || "jump" in name || "clap" in name || "throw" in name ->
                MovementPattern.CONDITIONING
            bodyPart == "cardio" || metadataPattern == "cardio" -> MovementPattern.CONDITIONING
            CARRY_TERMS.any(name::contains) -> MovementPattern.CARRY
            "lunge" in name || "split squat" in name || "step-up" in name || "step up" in name ->
                MovementPattern.LUNGE
            HINGE_TERMS.any(name::contains) -> MovementPattern.HINGE
            "squat" in name || "leg press" in name -> MovementPattern.SQUAT
            "pullover to press" in name -> MovementPattern.HORIZONTAL_PUSH
            VERTICAL_PULL_TERMS.any(name::contains) -> MovementPattern.VERTICAL_PULL
            HORIZONTAL_PULL_TERMS.any(name::contains) -> MovementPattern.HORIZONTAL_PULL
            target.contains("biceps") -> MovementPattern.ISOLATION_BICEPS
            target.contains("triceps") -> MovementPattern.ISOLATION_TRICEPS
            target.contains("delts") && SHOULDER_ISOLATION_TERMS.any(name::contains) ->
                MovementPattern.SHOULDER_ISOLATION
            VERTICAL_PUSH_TERMS.any(name::contains) -> MovementPattern.VERTICAL_PUSH
            HORIZONTAL_PUSH_TERMS.any(name::contains) || target.contains("pectorals") ->
                MovementPattern.HORIZONTAL_PUSH
            target.contains("abs") || bodyPart == "waist" || CORE_TERMS.any(name::contains) ->
                MovementPattern.CORE
            target.contains("delts") -> MovementPattern.SHOULDER_ISOLATION
            bodyPart == "back" -> MovementPattern.HORIZONTAL_PULL
            bodyPart == "upper legs" && target.contains("hamstring") -> MovementPattern.HINGE
            bodyPart == "upper legs" && target.contains("glute") -> MovementPattern.HINGE
            bodyPart == "upper legs" -> MovementPattern.SQUAT
            else -> MovementPattern.OTHER
        }
        return ClassifiedExercise(
            record = record,
            pattern = pattern,
            requiredEquipment = (
                record.equipment.map(::normalizeEquipment) + inferredEquipment(record)
                ).filter(String::isNotBlank).toSet()
        )
    }

    fun normalizeEquipment(value: String): String = when (normalize(value)) {
        "body weight", "bodyweight", "bodyweight only" -> "bodyweight"
        "dumbbell", "dumbbells" -> "dumbbell"
        "barbell", "barbell and rack" -> "barbell"
        "resistance band", "resistance bands", "band", "bands" -> "band"
        "kettlebell", "kettlebells" -> "kettlebell"
        "cable", "cables" -> "cable"
        "pull up bar", "pullup bar" -> "pull_up_bar"
        "exercise ball", "stability ball" -> "stability_ball"
        else -> normalize(value).replace(' ', '_')
    }

    private fun inferredEquipment(record: ExercisePackRecord): Set<String> = buildSet {
        val name = normalize(record.name)
        val instructions = normalize(record.instructions)
        if (
            "bench" in name || "seated press" in name ||
            "incline" in name || "decline" in name ||
            "bench" in instructions
        ) add("bench")
        if (
            "stability ball" in name || "exercise ball" in name || "bosu" in name ||
            "stability ball" in instructions || "exercise ball" in instructions
        ) add("stability_ball")
        if ("box jump" in name || "box squat" in name) add("box")
        if ("chair" in name) add("chair")
        if ("pull up" in name || "pull-up" in name || "chin up" in name || "chin-up" in name) {
            add("pull_up_bar")
        }
        if ("dip" in name) add("dip_station")
    }

    private fun normalize(value: String?): String =
        value.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val TECHNICAL_TERMS = listOf(
        "clean",
        "snatch",
        "jerk",
        "overhead squat",
        "turkish get up",
        "pistol squat",
        "one leg squat",
        "single leg deadlift",
        "bosu",
        "stability ball"
    )
    private val CARRY_TERMS = listOf("farmer carry", "suitcase carry", "loaded carry")
    private val HINGE_TERMS = listOf(
        "deadlift",
        "romanian",
        "good morning",
        "hip thrust",
        "glute bridge",
        "pull through"
    )
    private val VERTICAL_PULL_TERMS =
        listOf("pulldown", "pull-up", "pull up", "chin-up", "chin up")
    private val HORIZONTAL_PULL_TERMS =
        listOf("bent over row", "seated row", "one arm row", "chest supported row", "reverse fly")
    private val VERTICAL_PUSH_TERMS =
        listOf("overhead press", "shoulder press", "arnold press", "military press")
    private val HORIZONTAL_PUSH_TERMS =
        listOf("bench press", "chest press", "floor press", "push-up", "push up", "close grip press")
    private val SHOULDER_ISOLATION_TERMS =
        listOf("lateral raise", "front raise", "rear delt", "reverse fly", "upright row", "cuban")
    private val CORE_TERMS = listOf("plank", "crunch", "sit-up", "sit up", "leg raise", "dead bug")
}

internal class LocalWorkoutPlanGenerator {
    fun generate(input: LocalWorkoutPlanInput): LocalWorkoutPlan {
        val days = input.requestedDays.coerceIn(2, 6)
        val requestedEquipment = input.equipment
            .map(ExerciseMovementClassifier::normalizeEquipment)
            .filter(String::isNotBlank)
            .toSet()
        val effectiveEquipment = requestedEquipment.ifEmpty { setOf("bodyweight") }
        val fallbackUsed = requestedEquipment.isEmpty()
        val recentWorkout = input.recentWorkoutSummary.orEmpty().lowercase()
        val allowTechnical = input.explicitlyRequestedTerms.any { requested ->
            TECHNICAL_REQUEST_TERMS.any { technical -> technical in requested.lowercase() }
        }
        val experience = normalizedExperience(input.experience)
        val allCompatible = input.exercises.asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .filter(::isPrimaryStrengthExercise)
            .map(ExerciseMovementClassifier::classify)
            .filter { exercise ->
                exercise.requiredEquipment.isNotEmpty() &&
                    exercise.requiredEquipment.all(effectiveEquipment::contains)
            }
            .filter { exercise ->
                isExperienceCompatible(exercise, experience, allowTechnical)
            }
            .distinctBy { it.record.id }
            .sortedWith(
                compareBy<ClassifiedExercise> { wasRecentlyUsed(it.record, recentWorkout) }
                    .thenBy { it.record.id }
            )
            .toList()

        if (allCompatible.isEmpty()) {
            return LocalWorkoutPlan(
                title = "Local workout plan unavailable",
                weeklySummary = "No verified exercises matched the available equipment.",
                days = emptyList(),
                progressionGuidance = "Add available equipment in Profile or use Build Routine.",
                recoveryGuidance = "Keep activity comfortable while completing your setup.",
                safetyNote = STANDARD_PLAN_SAFETY,
                fallbackUsed = true
            )
        }

        val schedule = schedule(days)
        val planningCandidates = boundedCandidates(allCompatible)
        val usageCounts = mutableMapOf<String, Int>()
        val exerciseLimit = exerciseLimit(experience, input.requestedDurationMinutes)
        val focusCycle = focusCycle(days, input.splitPreference)
        val generatedDays = focusCycle.mapIndexed { index, focus ->
            val focusVariation = focusCycle.take(index).count { previousFocus ->
                if (focus.startsWith("Full Body")) {
                    previousFocus.startsWith("Full Body")
                } else {
                    previousFocus == focus
                }
            }
            val selected = selectForFocus(
                candidates = planningCandidates,
                focus = focus,
                usageCounts = usageCounts,
                limit = exerciseLimit,
                variationIndex = focusVariation,
                includeTechnical = allowTechnical && index == 0
            )
            selected.forEach { exercise ->
                usageCounts[exercise.record.id] = usageCounts.getOrDefault(exercise.record.id, 0) + 1
            }
            LocalWorkoutDay(
                dayName = schedule[index],
                focus = focus,
                exercises = selected.map { exercise ->
                    val prescription = ExercisePrescriptionPolicy.forExercise(
                        exercise.pattern,
                        input.goal,
                        experience.name
                    )
                    LocalWorkoutExercise(
                        id = exercise.record.id,
                        name = exercise.record.name.replace(Regex("\\s*\\((?:male|female)\\)\\s*$", RegexOption.IGNORE_CASE), ""),
                        movementPattern = exercise.pattern,
                        sets = prescription.sets,
                        repsOrDuration = prescription.reps,
                        restSeconds = prescription.restSeconds
                    )
                }
            )
        }
        val goal = input.goal?.takeIf(String::isNotBlank) ?: "general fitness"
        val experienceLabel = input.experience?.takeIf(String::isNotBlank) ?: "setup not provided"
        val recent = if (input.recentWorkoutSummary.isNullOrBlank()) {
            "No recent workout summary was available."
        } else {
            "Recent training was considered when spacing demanding sessions."
        }
        val limitations = input.limitations.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " Recorded limitations: ", limit = 3)
            .orEmpty()
        val availableDays = input.availableTrainingDays.coerceIn(days, 7)
        val recoveryDays = 7 - days
        val frequencyReason = input.frequencyAdjustmentNote ?: if (input.explicitFrequencyRequest) {
            "The requested $days-session frequency was kept because it is within the supported 2-6 day range."
        } else {
            "The profile allows $availableDays days, but $days programmed sessions balance muscle-gain work with recovery."
        }
        val splitLabel = splitLabel(input.splitPreference, days)
        return LocalWorkoutPlan(
            title = "FitDesi $days-Day $splitLabel ${goal.replaceFirstChar { it.uppercase() }} Plan",
            weeklySummary =
                "Available training days: $availableDays. Programmed sessions: $days. Recovery days: $recoveryDays. " +
                    "$frequencyReason Split: $splitLabel. Goal: $goal; experience: $experienceLabel. $recent$limitations",
            days = generatedDays,
            progressionGuidance =
                "When every prescribed set is completed with controlled technique, add 1-2 reps within the range or a small load increase next time.",
            recoveryGuidance =
                "Keep at least one lower-demand or rest day after two consecutive sessions, sleep consistently, hydrate, and reduce volume when recovery declines.",
            safetyNote = STANDARD_PLAN_SAFETY,
            fallbackUsed = fallbackUsed || !WorkoutPlanValidator.isValid(generatedDays)
        )
    }

    private fun selectForFocus(
        candidates: List<ClassifiedExercise>,
        focus: String,
        usageCounts: Map<String, Int>,
        limit: Int,
        variationIndex: Int,
        includeTechnical: Boolean
    ): List<ClassifiedExercise> {
        val available = candidates.filter { usageCounts.getOrDefault(it.record.id, 0) == 0 }
        val patternOrder = (
            if (includeTechnical) listOf(MovementPattern.EXPLOSIVE_OR_TECHNICAL) else emptyList()
            ) + patternOrder(focus, variationIndex)
        val selected = if (focus.startsWith("Full Body")) {
            selectFullBodyRequiredCoverage(
                candidates = candidates,
                usageCounts = usageCounts,
                limit = limit,
                variationIndex = variationIndex
            )
        } else {
            mutableListOf<ClassifiedExercise>()
        }
        val fullBodyTechnicalBonusAllowed =
            focus.startsWith("Full Body") &&
                includeTechnical &&
                selected.size == fullBodyCoverageGroups(variationIndex).size &&
                selected.size == limit &&
                available.any { it.pattern == MovementPattern.EXPLOSIVE_OR_TECHNICAL }
        val selectionLimit = if (fullBodyTechnicalBonusAllowed) limit + 1 else limit
        patternOrder.forEach { pattern ->
            if (selected.size >= selectionLimit) return@forEach
            if (focus.startsWith("Full Body") && selected.any { it.pattern == pattern }) return@forEach
            val unusedMatch = available.firstOrNull { candidate ->
                candidate.pattern == pattern &&
                    candidate.record.id !in selected.map { it.record.id } &&
                    respectsUpperAccessoryCaps(focus, candidate.pattern, selected)
            }
            unusedMatch?.let(selected::add)
        }
        val initialCoverageValid = WorkoutPlanValidator.isDayValid(
            focus,
            selected.map(ClassifiedExercise::pattern)
        )
        if (
            !initialCoverageValid ||
            selected.size < minOf(MIN_STANDARD_EXERCISES, limit)
        ) {
            patternOrder.forEach { pattern ->
                if (selected.size >= selectionLimit || pattern !in ALLOWED_REPEAT_PATTERNS) return@forEach
                if (!initialCoverageValid && selected.any { it.pattern == pattern }) return@forEach
                candidates.asSequence()
                    .filter { candidate ->
                        candidate.pattern == pattern &&
                            candidate.record.id !in selected.map { it.record.id } &&
                            usageCounts.getOrDefault(candidate.record.id, 0) < 2 &&
                            respectsUpperAccessoryCaps(focus, candidate.pattern, selected)
                    }
                    .minByOrNull { candidate -> usageCounts.getOrDefault(candidate.record.id, 0) }
                    ?.let(selected::add)
            }
        }
        return selected.take(selectionLimit)
    }

    private fun selectFullBodyRequiredCoverage(
        candidates: List<ClassifiedExercise>,
        usageCounts: Map<String, Int>,
        limit: Int,
        variationIndex: Int
    ): MutableList<ClassifiedExercise> {
        val selected = mutableListOf<ClassifiedExercise>()
        fullBodyCoverageGroups(variationIndex).forEach { preferredPatterns ->
            if (selected.size >= limit) return@forEach
            selectRequiredCoverageCandidate(
                candidates = candidates,
                preferredPatterns = preferredPatterns,
                usageCounts = usageCounts,
                selected = selected
            )?.let(selected::add)
        }
        return selected
    }

    private fun selectRequiredCoverageCandidate(
        candidates: List<ClassifiedExercise>,
        preferredPatterns: List<MovementPattern>,
        usageCounts: Map<String, Int>,
        selected: List<ClassifiedExercise>
    ): ClassifiedExercise? {
        preferredPatterns.forEach { pattern ->
            candidates.firstOrNull { candidate ->
                candidate.pattern == pattern &&
                    candidate.record.id !in selected.map { it.record.id } &&
                    usageCounts.getOrDefault(candidate.record.id, 0) == 0
            }?.let { return it }
        }

        val eligible = candidates.filter { candidate ->
            candidate.pattern in preferredPatterns &&
                candidate.record.id !in selected.map { it.record.id } &&
                usageCounts.getOrDefault(candidate.record.id, 0) < 2
        }
        val lowestUsage = eligible.minOfOrNull { candidate ->
            usageCounts.getOrDefault(candidate.record.id, 0)
        } ?: return null
        preferredPatterns.forEach { pattern ->
            eligible.firstOrNull { candidate ->
                candidate.pattern == pattern &&
                    usageCounts.getOrDefault(candidate.record.id, 0) == lowestUsage
            }?.let { return it }
        }
        return null
    }

    private fun fullBodyCoverageGroups(variationIndex: Int): List<List<MovementPattern>> =
        when (variationIndex % 3) {
            0 -> listOf(
                listOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE),
                listOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH),
                listOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL),
                listOf(MovementPattern.CORE, MovementPattern.CARRY)
            )
            1 -> listOf(
                listOf(MovementPattern.HINGE, MovementPattern.LUNGE, MovementPattern.SQUAT),
                listOf(MovementPattern.VERTICAL_PUSH, MovementPattern.HORIZONTAL_PUSH),
                listOf(MovementPattern.VERTICAL_PULL, MovementPattern.HORIZONTAL_PULL),
                listOf(MovementPattern.CARRY, MovementPattern.CORE)
            )
            else -> listOf(
                listOf(MovementPattern.LUNGE, MovementPattern.SQUAT, MovementPattern.HINGE),
                listOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH),
                listOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL),
                listOf(MovementPattern.CORE, MovementPattern.CARRY)
            )
        }

    private fun respectsUpperAccessoryCaps(
        focus: String,
        candidate: MovementPattern,
        selected: List<ClassifiedExercise>
    ): Boolean {
        if (focus != "Upper Body") return true
        val selectedPatterns = selected.map(ClassifiedExercise::pattern)
        if (
            candidate == MovementPattern.SHOULDER_ISOLATION &&
            MovementPattern.VERTICAL_PUSH in selectedPatterns
        ) return false
        if (
            candidate == MovementPattern.VERTICAL_PUSH &&
            MovementPattern.SHOULDER_ISOLATION in selectedPatterns
        ) return false
        val optionalShoulderOrTriceps =
            setOf(MovementPattern.SHOULDER_ISOLATION, MovementPattern.ISOLATION_TRICEPS)
        return candidate !in optionalShoulderOrTriceps ||
            selectedPatterns.none(optionalShoulderOrTriceps::contains)
    }

    private fun boundedCandidates(
        compatible: List<ClassifiedExercise>
    ): List<ClassifiedExercise> {
        val firstPerPattern = MovementPattern.entries.mapNotNull { pattern ->
            compatible.firstOrNull { it.pattern == pattern }
        }
        val secondPerPattern = MovementPattern.entries.mapNotNull { pattern ->
            compatible.filter { it.pattern == pattern }.drop(1).firstOrNull()
        }
        return (firstPerPattern + secondPerPattern + compatible)
            .distinctBy { it.record.id }
            .take(MAX_CANDIDATES)
    }

    private fun patternOrder(focus: String, variationIndex: Int): List<MovementPattern> = when (focus) {
        "Upper Body" -> if (variationIndex % 2 == 0) {
            listOf(
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.VERTICAL_PUSH,
                MovementPattern.VERTICAL_PULL,
                MovementPattern.ISOLATION_BICEPS,
                MovementPattern.ISOLATION_TRICEPS,
                MovementPattern.SHOULDER_ISOLATION,
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.HORIZONTAL_PULL
            )
        } else {
            listOf(
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.VERTICAL_PULL,
                MovementPattern.VERTICAL_PUSH,
                MovementPattern.ISOLATION_TRICEPS,
                MovementPattern.ISOLATION_BICEPS,
                MovementPattern.SHOULDER_ISOLATION,
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.HORIZONTAL_PUSH
            )
        }
        "Lower Body" -> listOf(
            MovementPattern.SQUAT,
            MovementPattern.HINGE,
            MovementPattern.LUNGE,
            MovementPattern.CARRY,
            MovementPattern.SQUAT,
            MovementPattern.HINGE
        )
        "Push" -> listOf(
            MovementPattern.HORIZONTAL_PUSH,
            MovementPattern.VERTICAL_PUSH,
            MovementPattern.ISOLATION_TRICEPS,
            MovementPattern.SHOULDER_ISOLATION,
            MovementPattern.HORIZONTAL_PUSH
        )
        "Pull" -> listOf(
            MovementPattern.HORIZONTAL_PULL,
            MovementPattern.VERTICAL_PULL,
            MovementPattern.ISOLATION_BICEPS,
            MovementPattern.HORIZONTAL_PULL
        )
        "Legs & Core" -> listOf(
            MovementPattern.SQUAT,
            MovementPattern.HINGE,
            MovementPattern.LUNGE,
            MovementPattern.CORE,
            MovementPattern.CARRY,
            MovementPattern.SQUAT,
            MovementPattern.HINGE
        )
        else -> when (variationIndex % 3) {
            0 -> listOf(
                MovementPattern.SQUAT,
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.CORE,
                MovementPattern.HINGE,
                MovementPattern.VERTICAL_PUSH,
                MovementPattern.VERTICAL_PULL,
                MovementPattern.CARRY
            )
            1 -> listOf(
                MovementPattern.HINGE,
                MovementPattern.VERTICAL_PUSH,
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.CARRY,
                MovementPattern.LUNGE,
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.VERTICAL_PULL,
                MovementPattern.CORE
            )
            else -> listOf(
                MovementPattern.LUNGE,
                MovementPattern.HORIZONTAL_PUSH,
                MovementPattern.HORIZONTAL_PULL,
                MovementPattern.CORE,
                MovementPattern.SQUAT,
                MovementPattern.VERTICAL_PUSH,
                MovementPattern.VERTICAL_PULL,
                MovementPattern.CARRY
            )
        }
    }

    private fun isExperienceCompatible(
        exercise: ClassifiedExercise,
        experience: TrainingExperience,
        allowTechnical: Boolean
    ): Boolean {
        if (exercise.pattern == MovementPattern.EXPLOSIVE_OR_TECHNICAL && !allowTechnical) return false
        if (experience == TrainingExperience.BEGINNER &&
            exercise.pattern == MovementPattern.CONDITIONING &&
            !allowTechnical
        ) return false
        val levels = exercise.record.experienceLevels.map(String::lowercase)
        return levels.isEmpty() || experience.name.lowercase() in levels ||
            experience == TrainingExperience.ADVANCED
    }

    private fun isPrimaryStrengthExercise(record: ExercisePackRecord): Boolean {
        val descriptor = listOf(record.name, record.category, record.movementPattern)
            .joinToString(" ")
            .lowercase()
        val instructions = record.instructions.lowercase()
        return NON_PRIMARY_TERMS.none(descriptor::contains) &&
            !instructions.startsWith("stretch") &&
            "hold the stretch" !in instructions
    }

    private fun exerciseLimit(experience: TrainingExperience, durationMinutes: Int?): Int {
        val experienceLimit = when (experience) {
            TrainingExperience.BEGINNER -> 4
            TrainingExperience.INTERMEDIATE -> 5
            TrainingExperience.ADVANCED -> 6
        }
        val durationLimit = when {
            durationMinutes == null -> experienceLimit
            durationMinutes <= 30 -> 3
            durationMinutes <= 50 -> 4
            durationMinutes <= 70 -> 5
            else -> 6
        }
        return minOf(experienceLimit, durationLimit)
    }

    private fun normalizedExperience(value: String?): TrainingExperience =
        TrainingExperience.from(value)

    private fun wasRecentlyUsed(exercise: ExercisePackRecord, recentWorkout: String): Boolean {
        if (recentWorkout.isBlank()) return false
        return listOfNotNull(exercise.name, exercise.target, exercise.muscleGroup)
            .any { value -> value.length >= 3 && value.lowercase() in recentWorkout }
    }

    private fun focusCycle(days: Int, splitPreference: WorkoutSplitPreference): List<String> = when (splitPreference) {
        WorkoutSplitPreference.PUSH_PULL_LEGS ->
            List(days) { index -> listOf("Push", "Pull", "Legs & Core")[index % 3] }
        WorkoutSplitPreference.UPPER_LOWER ->
            List(days) { index -> if (index % 2 == 0) "Upper Body" else "Lower Body" }
        WorkoutSplitPreference.FULL_BODY ->
            List(days) { index -> "Full Body ${('A'.code + index).toChar()}" }
        WorkoutSplitPreference.AUTO -> when (days) {
            2 -> listOf("Full Body A", "Full Body B")
            3 -> listOf("Full Body A", "Full Body B", "Full Body C")
            4 -> listOf("Upper Body", "Lower Body", "Upper Body", "Lower Body")
            5 -> listOf("Push", "Pull", "Legs & Core", "Upper Body", "Lower Body")
            else -> listOf("Push", "Pull", "Legs & Core", "Full Body", "Upper Body", "Lower Body")
        }
    }

    private fun splitLabel(splitPreference: WorkoutSplitPreference, days: Int): String = when (splitPreference) {
        WorkoutSplitPreference.PUSH_PULL_LEGS -> "Push Pull Legs"
        WorkoutSplitPreference.UPPER_LOWER -> "Upper Lower"
        WorkoutSplitPreference.FULL_BODY -> "Full Body"
        WorkoutSplitPreference.AUTO -> when (days) {
            2, 3 -> "Full Body"
            4 -> "Upper Lower"
            else -> "Balanced Split"
        }
    }

    private fun schedule(days: Int): List<String> = when (days) {
        2 -> listOf("Monday", "Thursday")
        3 -> listOf("Monday", "Wednesday", "Friday")
        4 -> listOf("Monday", "Tuesday", "Thursday", "Saturday")
        5 -> listOf("Monday", "Tuesday", "Wednesday", "Friday", "Saturday")
        else -> listOf("Monday", "Tuesday", "Wednesday", "Friday", "Saturday", "Sunday")
    }

    companion object {
        private const val MAX_CANDIDATES = 20
        private const val MIN_STANDARD_EXERCISES = 4
        private val ALLOWED_REPEAT_PATTERNS = setOf(
            MovementPattern.HORIZONTAL_PUSH,
            MovementPattern.VERTICAL_PUSH,
            MovementPattern.HORIZONTAL_PULL,
            MovementPattern.VERTICAL_PULL,
            MovementPattern.SQUAT,
            MovementPattern.HINGE,
            MovementPattern.LUNGE,
            MovementPattern.CORE,
            MovementPattern.CARRY,
            MovementPattern.ISOLATION_BICEPS,
            MovementPattern.ISOLATION_TRICEPS,
            MovementPattern.SHOULDER_ISOLATION
        )
        private val NON_PRIMARY_TERMS = listOf(
            "stretch", "yoga", "mobility", "warm up", "warm-up", "balance board",
            "toe touch", "heel touch", "foam roll", "breathing drill", " pose", "air bike"
        )
        private val TECHNICAL_REQUEST_TERMS =
            listOf("clean", "snatch", "jerk", "burpee", "jump", "explosive", "olympic")
        private const val STANDARD_PLAN_SAFETY =
            "General fitness guidance only. Use controlled technique and stop for chest pain, fainting, severe breathing difficulty, sudden severe pain, or loss of consciousness. Seek qualified care for injuries or medical concerns."
    }
}

internal data class ExercisePrescription(val sets: Int, val reps: String, val restSeconds: Int)

internal object ExercisePrescriptionPolicy {
    fun forExercise(
        pattern: MovementPattern,
        goal: String?,
        experience: String?
    ): ExercisePrescription {
        val normalizedGoal = goal.orEmpty().lowercase()
        val base = when (pattern) {
            MovementPattern.SQUAT,
            MovementPattern.HINGE,
            MovementPattern.HORIZONTAL_PUSH,
            MovementPattern.VERTICAL_PUSH,
            MovementPattern.HORIZONTAL_PULL,
            MovementPattern.VERTICAL_PULL -> when {
                "strength" in normalizedGoal || "stronger" in normalizedGoal -> ExercisePrescription(4, "5-8 reps", 180)
                "fat" in normalizedGoal || "endurance" in normalizedGoal -> ExercisePrescription(3, "8-12 reps", 120)
                else -> ExercisePrescription(4, "6-10 reps", 150)
            }
            MovementPattern.LUNGE, MovementPattern.CARRY -> ExercisePrescription(3, "8-12 reps", 90)
            MovementPattern.ISOLATION_BICEPS,
            MovementPattern.ISOLATION_TRICEPS,
            MovementPattern.SHOULDER_ISOLATION -> ExercisePrescription(3, "10-15 reps", 75)
            MovementPattern.CORE -> ExercisePrescription(3, "10-15 controlled reps or 30-45 seconds", 60)
            else -> when {
            "strength" in normalizedGoal || "stronger" in normalizedGoal ->
                ExercisePrescription(4, "4-6 reps", 150)
            "fat" in normalizedGoal || "endurance" in normalizedGoal ->
                ExercisePrescription(3, "10-15 reps", 60)
            else -> ExercisePrescription(3, "8-12 reps", 90)
            }
        }
        return when (TrainingExperience.from(experience)) {
            TrainingExperience.BEGINNER -> base.copy(sets = base.sets.coerceAtMost(3))
            TrainingExperience.INTERMEDIATE -> base
            TrainingExperience.ADVANCED -> base.copy(sets = (base.sets + 1).coerceAtMost(5))
        }
    }
}

internal object WorkoutPlanValidator {
    fun isValid(days: List<LocalWorkoutDay>): Boolean =
        days.isNotEmpty() &&
            days.all { day -> isDayValid(day.focus, day.exercises.map(LocalWorkoutExercise::movementPattern)) } &&
            hasAcceptableRedundancy(days.flatMap { day -> day.exercises.map(LocalWorkoutExercise::id) }) &&
            hasLowerBodyRecovery(days.map { it.dayName to it.focus })

    fun isValidGenerated(days: List<GeneratedWorkoutPlanDay>): Boolean =
        days.isNotEmpty() &&
            days.all { day ->
                val patterns = day.exercises.mapNotNull { exercise ->
                    runCatching { MovementPattern.valueOf(exercise.movementPattern) }.getOrNull()
                }
                patterns.size == day.exercises.size && isDayValid(day.focus, patterns)
            } &&
            hasAcceptableRedundancy(days.flatMap { day -> day.exercises.map(GeneratedWorkoutPlanExercise::exerciseId) }) &&
            hasLowerBodyRecovery(days.map { it.dayName to it.focus })

    internal fun isDayValid(focus: String, patterns: List<MovementPattern>): Boolean {
        val primary = patterns.filterNot { it in NON_PRIMARY_PATTERNS }
        val push = primary.any { it == MovementPattern.HORIZONTAL_PUSH || it == MovementPattern.VERTICAL_PUSH }
        val pull = primary.any { it == MovementPattern.HORIZONTAL_PULL || it == MovementPattern.VERTICAL_PULL }
        val knee = primary.any { it == MovementPattern.SQUAT || it == MovementPattern.LUNGE }
        val hinge = MovementPattern.HINGE in primary
        val lowerCount = primary.count { it in LOWER_PATTERNS }
        if (primary.count { it == MovementPattern.CORE } > 1) return false
        if (primary.count { it == MovementPattern.SQUAT } > 2) return false
        val normalizedFocus = focus.lowercase()
        return when {
            normalizedFocus == "push" -> push
            normalizedFocus == "pull" -> pull
            normalizedFocus == "legs & core" -> knee && hinge && lowerCount >= 3 && MovementPattern.CORE in primary
            normalizedFocus == "upper body" -> push && pull
            normalizedFocus == "lower body" -> knee && hinge && lowerCount >= 3
            normalizedFocus.startsWith("full body") ->
                (knee || hinge) && push && pull &&
                    (MovementPattern.CORE in primary || MovementPattern.CARRY in primary)
            else -> false
        }
    }

    private fun hasAcceptableRedundancy(exerciseIds: List<String>): Boolean =
        exerciseIds.filter(String::isNotBlank).groupingBy { it }.eachCount().values.all { it <= 2 }

    private fun hasLowerBodyRecovery(schedule: List<Pair<String, String>>): Boolean {
        val demanding = schedule.mapNotNull { (dayName, focus) ->
            if (focus.lowercase().let { "lower" in it || "legs" in it || "full body" in it }) {
                DAY_INDEX[dayName.lowercase()]
            } else {
                null
            }
        }.sorted()
        return demanding.zipWithNext().all { (first, second) -> second - first >= 2 }
    }

    private val LOWER_PATTERNS = setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE)
    private val NON_PRIMARY_PATTERNS = setOf(
        MovementPattern.CONDITIONING,
        MovementPattern.EXPLOSIVE_OR_TECHNICAL,
        MovementPattern.OTHER
    )
    private val DAY_INDEX = mapOf(
        "monday" to 0,
        "tuesday" to 1,
        "wednesday" to 2,
        "thursday" to 3,
        "friday" to 4,
        "saturday" to 5,
        "sunday" to 6
    )
}

internal fun LocalWorkoutPlan.asMultilineText(): String = buildString {
    appendLine(title)
    appendLine(weeklySummary)
    days.forEach { day ->
        appendLine()
        appendLine("${day.dayName} - ${day.focus}")
        day.exercises.forEach { exercise ->
            appendLine(
                "- ${exercise.name} [${exercise.id}]: ${exercise.sets} sets x " +
                    "${exercise.repsOrDuration}, rest ${exercise.restSeconds}s"
            )
        }
    }
    appendLine()
    appendLine("Progression: $progressionGuidance")
    appendLine("Recovery: $recoveryGuidance")
}.trim()
