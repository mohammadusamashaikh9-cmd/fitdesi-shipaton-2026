package com.example.exercise

import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingRole
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal const val ROUTINE_EXERCISE_LOAD_ERROR =
    "Exercise library could not be loaded. Try again."

internal sealed interface CanonicalRoutineCatalogueResult {
    data object Loading : CanonicalRoutineCatalogueResult
    data class Ready(val exercises: List<Exercise>) : CanonicalRoutineCatalogueResult
    data class Error(val message: String = ROUTINE_EXERCISE_LOAD_ERROR) : CanonicalRoutineCatalogueResult
}

internal data class CanonicalRoutineSelectionRequest(
    val equipment: Set<String>,
    val goal: String,
    val experienceLevel: String,
    val split: String,
    val dayFocus: String,
    val limit: Int = 4
)

internal const val PULL_CAPABILITY_UNAVAILABLE_MESSAGE =
    "Your current equipment cannot provide the pulling movements required for this split. " +
        "Choose Dumbbells, Barbell and rack, Full Gym, or a different split."

internal const val ROUTINE_COVERAGE_UNAVAILABLE_MESSAGE =
    "A safe four-exercise routine is not available for this equipment and split. Try another configuration."

internal sealed interface CanonicalRoutineSelectionResult {
    data class Supported(val exercises: List<Exercise>) : CanonicalRoutineSelectionResult
    data class Unsupported(val message: String) : CanonicalRoutineSelectionResult
    data class CoverageFailure(val message: String) : CanonicalRoutineSelectionResult
}

internal class CanonicalRoutineSelectionException(message: String) : IllegalStateException(message)

internal class CanonicalRoutineExerciseSelector(
    private val repository: ExerciseCatalogue
) {
    suspend fun loadCatalogue(): CanonicalRoutineCatalogueResult = try {
        when (val state = repository.load()) {
            is ExerciseCatalogueState.Ready -> if (state.exercises.isEmpty()) {
                CanonicalRoutineCatalogueResult.Error()
            } else {
                CanonicalRoutineCatalogueResult.Ready(state.exercises)
            }
            is ExerciseCatalogueState.Error,
            ExerciseCatalogueState.Loading,
            ExerciseCatalogueState.NotLoaded -> CanonicalRoutineCatalogueResult.Error()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CanonicalRoutineCatalogueResult.Error()
    }

    fun selectDayResult(
        exercises: List<Exercise>,
        request: CanonicalRoutineSelectionRequest,
        programmingRecords: List<ExerciseProgrammingMetadata> = emptyList()
    ): CanonicalRoutineSelectionResult {
        unsupportedCapability(request)?.let { return it }
        val selected = selectDay(exercises, request, programmingRecords)
        return if (isValidFinalSelection(selected, request, programmingRecords)) {
            CanonicalRoutineSelectionResult.Supported(selected)
        } else {
            CanonicalRoutineSelectionResult.CoverageFailure(ROUTINE_COVERAGE_UNAVAILABLE_MESSAGE)
        }
    }

    fun selectDay(
        exercises: List<Exercise>,
        request: CanonicalRoutineSelectionRequest,
        programmingRecords: List<ExerciseProgrammingMetadata> = emptyList()
    ): List<Exercise> {
        if (request.limit !in 1..MAX_EXERCISES_PER_DAY) return emptyList()

        val programmingByExerciseId = programmingRecords.associateBy(ExerciseProgrammingMetadata::exerciseId)
        val candidates = exercises.asSequence()
            .filter { it.id.value.isNotBlank() && it.name.isNotBlank() }
            .mapNotNull { exercise ->
                val metadata = programmingByExerciseId[exercise.id.value]
                if (!supportsEquipment(exercise, metadata, request.equipment)) return@mapNotNull null
                if (!supportsExperience(exercise, metadata, request)) return@mapNotNull null
                if (!isEligibleMainExercise(exercise, metadata, request)) return@mapNotNull null
                MainCandidate(
                    exercise = exercise,
                    metadata = metadata,
                    region = exercise.region(),
                    qualityTier = qualityTier(exercise, metadata, request),
                    familyKey = exercise.mainMovementFamily(metadata),
                    facets = exercise.selectionFacets(metadata)
                )
            }
            .distinctBy { it.exercise.id.value }
            .sortedWith(candidateComparator(request))
            .toList()

        val selected = mutableListOf<MainCandidate>()
        val selectedIds = mutableSetOf<String>()
        val selectedFamilies = mutableSetOf<String>()

        fun selectFirst(
            slot: MainSlot,
            requireExactSlotFit: Boolean,
            requireNewFamily: Boolean
        ): Boolean {
            val candidate = candidates.firstOrNull { candidate ->
                candidate.region == slot.region &&
                    (!requireExactSlotFit || slot.matches(candidate)) &&
                    candidate.exercise.id.value !in selectedIds &&
                    (!requireNewFamily || candidate.familyKey !in selectedFamilies)
            } ?: return false
            selected += candidate
            selectedIds += candidate.exercise.id.value
            selectedFamilies += candidate.familyKey
            return true
        }

        requestedSlots(request).forEach { slot ->
            if (selected.size >= request.limit) return@forEach
            if (selectFirst(slot, requireExactSlotFit = true, requireNewFamily = true)) return@forEach
            if (selectFirst(slot, requireExactSlotFit = false, requireNewFamily = true)) return@forEach
            if (selectFirst(slot, requireExactSlotFit = true, requireNewFamily = false)) return@forEach
            selectFirst(slot, requireExactSlotFit = false, requireNewFamily = false)
        }

        return selected
            .take(request.limit)
            .takeIf { it.size == request.limit }
            ?.map(MainCandidate::exercise)
            .orEmpty()
    }

    private fun isEligibleMainExercise(
        exercise: Exercise,
        metadata: ExerciseProgrammingMetadata?,
        request: CanonicalRoutineSelectionRequest
    ): Boolean {
        if (exercise.id.value in REVIEWED_SUPPORT_EQUIPMENT_EXCLUSIONS) return false
        if (metadata == null) {
            return !exercise.hasUnreviewedActivitySignal() &&
                !exercise.hasUnsupportedSupportRequirement(request.equipment) &&
                !exercise.hasUnreviewedEquipmentEvidenceMismatch()
        }

        val roles = metadata.roles.toSet()
        if (ExerciseProgrammingRole.NEVER_AUTO_SELECT in roles) return false
        if (normalize(request.experienceLevel) == "beginner" && !metadata.beginnerSuitable) return false
        if (ExerciseProgrammingRole.MAIN_STRENGTH in roles) return true
        return ExerciseProgrammingRole.CONDITIONING in roles && request.isConditioningDay()
    }

    private fun Exercise.hasUnreviewedActivitySignal(): Boolean =
        sequenceOf(name).plus(aliases.asSequence()).any { value ->
            normalize(value)
                .split(Regex("[^a-z0-9]+"))
                .any { token -> token in UNREVIEWED_ACTIVITY_TOKENS }
        }

    private fun Exercise.hasUnsupportedSupportRequirement(selectedEquipment: Set<String>): Boolean {
        if (selectedEquipment.any { normalize(it) == "full gym" }) return false
        val evidence = normalize("$name $instructions")
        return UNREPRESENTED_SUPPORT_PHRASES.any { phrase ->
            Regex("(^|[^a-z0-9])${Regex.escape(phrase)}([^a-z0-9]|$)").containsMatchIn(evidence)
        }
    }

    private fun Exercise.hasUnreviewedEquipmentEvidenceMismatch(): Boolean {
        val evidence = normalize("$name $instructions")
        val required = equipment
            .map(CanonicalEquipmentCapabilities::normalizeEquipment)
            .filterNot { it.isBlank() || it == "bodyweight" || it == "none" }
        return required.any { equipmentName ->
            val evidenceToken = when (equipmentName) {
                "leverage machine" -> "machine"
                else -> equipmentName
            }
            !Regex("(^|[^a-z0-9])${Regex.escape(evidenceToken)}([^a-z0-9]|$)").containsMatchIn(evidence)
        }
    }

    private fun Exercise.hasConservativeBeginnerEvidence(request: CanonicalRoutineSelectionRequest): Boolean {
        val levels = experienceLevels.map(::normalize).filter(String::isNotBlank)
        if ("beginner" in levels) return true
        if (levels.isNotEmpty()) return false
        val evidence = normalize("$name $instructions")
        return region() in BEGINNER_MAIN_REGIONS &&
            (primaryMuscles.any(String::isNotBlank) || bodyTargets.any(String::isNotBlank)) &&
            instructions.isNotBlank() &&
            Regex("\\brepeat(?:s|ed|ing)?\\b|\\brepetitions?\\b").containsMatchIn(evidence) &&
            !hasUnreviewedActivitySignal() &&
            !hasUnsupportedSupportRequirement(request.equipment) &&
            !hasUnreviewedEquipmentEvidenceMismatch() &&
            BEGINNER_COMPLEXITY_PHRASES.none(evidence::contains)
    }

    private fun CanonicalRoutineSelectionRequest.isConditioningDay(): Boolean =
        normalize(dayFocus) == "conditioning"

    private fun candidateComparator(
        request: CanonicalRoutineSelectionRequest
    ): Comparator<MainCandidate> = compareBy<MainCandidate>(
        MainCandidate::qualityTier,
        { equipmentPreferenceRank(it, request.equipment) },
        { goalRank(it.exercise, request.goal) },
        { experienceRank(it.exercise, request.experienceLevel) },
        { normalize(it.exercise.name) },
        { it.exercise.id.value }
    )

    private fun supportsEquipment(
        exercise: Exercise,
        metadata: ExerciseProgrammingMetadata?,
        selectedEquipment: Set<String>
    ): Boolean = CanonicalEquipmentCapabilities.isCompatible(
        metadata?.equipment?.takeIf(List<String>::isNotEmpty) ?: exercise.equipment,
        selectedEquipment
    )

    private fun equipmentPreferenceRank(candidate: MainCandidate, selectedEquipment: Set<String>): Int =
        if (
            CanonicalEquipmentCapabilities.compatibilityStrength(
                candidate.metadata?.equipment?.takeIf(List<String>::isNotEmpty)
                    ?: candidate.exercise.equipment,
                selectedEquipment
            ) > 0
        ) 0 else 1

    private fun qualityTier(
        exercise: Exercise,
        metadata: ExerciseProgrammingMetadata?,
        request: CanonicalRoutineSelectionRequest
    ): Int = when {
        metadata != null -> REVIEWED_MAIN_TIER
        exercise.experienceLevels.any { normalize(it) == normalize(request.experienceLevel) } ->
            EXPLICIT_CANONICAL_EXPERIENCE_TIER
        else -> CONSERVATIVE_CANONICAL_FALLBACK_TIER
    }

    private fun supportsExperience(
        exercise: Exercise,
        metadata: ExerciseProgrammingMetadata?,
        request: CanonicalRoutineSelectionRequest
    ): Boolean {
        val levels = exercise.experienceLevels.map(::normalize).filter(String::isNotBlank).toSet()
        return when (normalize(request.experienceLevel)) {
            "beginner" -> when {
                metadata != null -> metadata.beginnerSuitable
                levels.isNotEmpty() -> "beginner" in levels
                else -> exercise.hasConservativeBeginnerEvidence(request)
            }
            "intermediate" -> levels.isEmpty() || levels.any { it == "beginner" || it == "intermediate" }
            "advanced" -> true
            else -> false
        }
    }

    private fun goalRank(exercise: Exercise, selectedGoal: String): Int {
        val goals = exercise.goals.map(::normalize).toSet()
        if (goals.isEmpty()) return 1
        return if (goalMetadata(selectedGoal) in goals) 0 else 2
    }

    private fun experienceRank(exercise: Exercise, selectedLevel: String): Int {
        val levels = exercise.experienceLevels.map(::normalize).toSet()
        if (levels.isEmpty()) return 1
        return if (normalize(selectedLevel) in levels) 0 else 2
    }

    private fun requestedSlots(request: CanonicalRoutineSelectionRequest): List<MainSlot> {
        val focus = normalize(request.dayFocus)
        return when {
            focus == "upper body" -> listOf(PUSH_CHEST_SLOT, PULL_BACK_SLOT, PUSH_SHOULDER_SLOT, PULL_BICEPS_SLOT)
            focus == "lower body" || focus == "legs" -> listOf(LOWER_KNEE_SLOT, LOWER_HIP_SLOT, LOWER_SECONDARY_SLOT, LOWER_ACCESSORY_SLOT)
            focus == "push" || focus.startsWith("chest") -> listOf(PUSH_CHEST_SLOT, PUSH_SHOULDER_SLOT, PUSH_GENERAL_SLOT, PUSH_TRICEPS_SLOT)
            focus == "pull" || focus.startsWith("back") -> listOf(PULL_BACK_SLOT, PULL_GENERAL_SLOT, PULL_REAR_SLOT, PULL_BICEPS_SLOT)
            focus.startsWith("shoulders") -> listOf(PUSH_SHOULDER_SLOT, PULL_REAR_SLOT, CORE_SLOT, CORE_SLOT)
            focus == "conditioning" -> listOf(CONDITIONING_SLOT, LOWER_SECONDARY_SLOT, PUSH_GENERAL_SLOT, CORE_SLOT)
            else -> listOf(LOWER_KNEE_SLOT, PUSH_CHEST_SLOT, PULL_BACK_SLOT, CORE_SLOT)
        }.take(request.limit)
    }

    private fun unsupportedCapability(
        request: CanonicalRoutineSelectionRequest
    ): CanonicalRoutineSelectionResult.Unsupported? {
        if (!request.requiresPullCapability()) return null
        val available = CanonicalEquipmentCapabilities.availableEquipment(request.equipment)
        return if (available.any(PULL_CAPABLE_EQUIPMENT::contains)) null
        else CanonicalRoutineSelectionResult.Unsupported(PULL_CAPABILITY_UNAVAILABLE_MESSAGE)
    }

    private fun CanonicalRoutineSelectionRequest.requiresPullCapability(): Boolean {
        val focus = normalize(dayFocus)
        val normalizedSplit = normalize(split)
        return focus == "pull" || focus.startsWith("back") || focus == "upper body" || focus == "full body" ||
            normalizedSplit in PULL_DEPENDENT_SPLITS
    }

    private fun isValidFinalSelection(
        selected: List<Exercise>,
        request: CanonicalRoutineSelectionRequest,
        programmingRecords: List<ExerciseProgrammingMetadata>
    ): Boolean {
        if (selected.size != request.limit || selected.map { it.id.value }.distinct().size != request.limit) return false
        val programmingById = programmingRecords.associateBy(ExerciseProgrammingMetadata::exerciseId)
        val slots = requestedSlots(request)
        return selected.zip(slots).all { (exercise, slot) ->
            val metadata = programmingById[exercise.id.value]
            exercise.region() == slot.region &&
                supportsEquipment(exercise, metadata, request.equipment) &&
                supportsExperience(exercise, metadata, request) &&
                isEligibleMainExercise(exercise, metadata, request)
        }
    }

    private fun Exercise.mainMovementFamily(metadata: ExerciseProgrammingMetadata?): String {
        metadata?.duplicateFamilyKey
            ?.takeIf(String::isNotBlank)
            ?.let { return "reviewed:${normalize(it)}" }
        metadata?.movementPatterns
            ?.firstOrNull(String::isNotBlank)
            ?.let { return "movement:${normalize(it)}" }

        val equipmentKey = (metadata?.equipment?.takeIf(List<String>::isNotEmpty) ?: equipment)
            .map(CanonicalEquipmentCapabilities::normalizeEquipment)
            .filter(String::isNotBlank)
            .sorted()
            .joinToString("+")
        val targetKey = primaryMuscles.firstOrNull(String::isNotBlank)
            ?: bodyTargets.firstOrNull(String::isNotBlank)
            ?: bodyPart
        return "canonical:$equipmentKey:${region().name}:${normalize(targetKey)}"
    }

    private fun Exercise.selectionFacets(metadata: ExerciseProgrammingMetadata?): Set<String> = buildSet {
        metadata?.programmingRegions?.forEach { add(normalize(it.name.replace('_', ' '))) }
        metadata?.movementPatterns?.forEach { add(normalize(it)) }
        add(normalize(category))
        add(normalize(movementPattern))
        add(normalize(bodyPart))
        bodyTargets.forEach { add(normalize(it)) }
        primaryMuscles.forEach { add(normalize(it)) }
    }

    private fun Exercise.region(): ExerciseRegion {
        val primaryFacets = buildSet {
            add(normalize(category))
            add(normalize(movementPattern))
            add(normalize(bodyPart))
            primaryMuscles.firstOrNull(String::isNotBlank)?.let { add(normalize(it)) }
        }
        regionFromFacets(primaryFacets)?.let { return it }

        val fallbackFacets = buildSet {
            bodyTargets.forEach { add(normalize(it)) }
            primaryMuscles.forEach { add(normalize(it)) }
            secondaryMuscles.forEach { add(normalize(it)) }
        }
        return regionFromFacets(fallbackFacets) ?: ExerciseRegion.OTHER
    }

    private fun regionFromFacets(facets: Set<String>): ExerciseRegion? {
        return when {
            facets.any { it in CONDITIONING_FACETS } -> ExerciseRegion.CONDITIONING
            facets.any { it in CORE_FACETS } -> ExerciseRegion.CORE
            facets.any { it in LOWER_FACETS } -> ExerciseRegion.LOWER
            facets.any { it in PULL_FACETS } -> ExerciseRegion.PULL
            facets.any { it in PUSH_FACETS } -> ExerciseRegion.PUSH
            else -> null
        }
    }

    private fun goalMetadata(goal: String): String = when (normalize(goal)) {
        "strength" -> "get stronger"
        "muscle gain" -> "build muscle"
        else -> "general fitness"
    }

    private enum class ExerciseRegion {
        PUSH,
        PULL,
        LOWER,
        CORE,
        CONDITIONING,
        OTHER
    }

    private data class MainCandidate(
        val exercise: Exercise,
        val metadata: ExerciseProgrammingMetadata?,
        val region: ExerciseRegion,
        val qualityTier: Int,
        val familyKey: String,
        val facets: Set<String>
    )

    private data class MainSlot(
        val region: ExerciseRegion,
        val preferredFacets: Set<String> = emptySet()
    ) {
        fun matches(candidate: MainCandidate): Boolean =
            preferredFacets.isEmpty() || candidate.facets.any(preferredFacets::contains)
    }

    private companion object {
        const val MAX_EXERCISES_PER_DAY = 4
        const val REVIEWED_MAIN_TIER = 0
        const val EXPLICIT_CANONICAL_EXPERIENCE_TIER = 1
        const val CONSERVATIVE_CANONICAL_FALLBACK_TIER = 2

        val PUSH_FACETS = setOf("chest", "pectorals", "shoulders", "delts", "deltoids", "triceps")
        val PULL_FACETS = setOf("back", "lats", "upper back", "biceps", "forearms", "rhomboids", "traps", "trapezius")
        val LOWER_FACETS = setOf(
            "upper legs",
            "lower legs",
            "quadriceps",
            "quads",
            "hamstrings",
            "glutes",
            "calves",
            "adductors",
            "ankles",
            "ankle stabilizers",
            "squat"
        )
        val CORE_FACETS = setOf("waist", "abs", "obliques", "core", "hip flexors", "spine", "lower back")
        val CONDITIONING_FACETS = setOf("cardio", "cardiovascular system")
        val UNREVIEWED_ACTIVITY_TOKENS = setOf("stretch", "mobility")
        val BEGINNER_MAIN_REGIONS = setOf(ExerciseRegion.PUSH, ExerciseRegion.PULL, ExerciseRegion.LOWER, ExerciseRegion.CORE)
        val BEGINNER_COMPLEXITY_PHRASES = setOf(
            "archer", "back lever", "clap push", "drop jump", "explosively", "jump off",
            "one leg stabilization", "catch the dumbbells", "jump up", "stabilization"
        )
        val UNREPRESENTED_SUPPORT_PHRASES = setOf(
            "pull-up bar", "pull up bar", "parallel bars", "dip bars", "dip-pull-up cage",
            "bench", "box or platform", "box", "platform", "towel", "suspension trainer",
            "bar or handles", "sturdy object", "anchor point", "fixed point", "sturdy beam",
            "wall or stable surface", "stable chair"
        )
        val PULL_CAPABLE_EQUIPMENT = setOf("dumbbell", "barbell", "cable", "machine", "leverage machine", "assisted")
        val PULL_DEPENDENT_SPLITS = setOf("full body", "upper / lower", "push / pull / legs", "body part split")
        val REVIEWED_SUPPORT_EQUIPMENT_EXCLUSIONS = setOf(
            "0129", "0137", "0291", "0305", "0974", "0980", "0988", "0993", "1008", "1013",
            "1254", "1399", "1770", "3019", "0279", "0284", "1000", "1277", "1373", "1649",
            "1650", "2403", "3165"
        )

        val PUSH_CHEST_SLOT = MainSlot(ExerciseRegion.PUSH, setOf("chest", "pectorals", "horizontal push"))
        val PUSH_SHOULDER_SLOT = MainSlot(ExerciseRegion.PUSH, setOf("shoulders", "delts", "deltoids", "vertical push"))
        val PUSH_TRICEPS_SLOT = MainSlot(ExerciseRegion.PUSH, setOf("triceps", "elbow extension"))
        val PUSH_GENERAL_SLOT = MainSlot(ExerciseRegion.PUSH)
        val PULL_BACK_SLOT = MainSlot(ExerciseRegion.PULL, setOf("back", "lats", "upper back", "horizontal pull", "vertical pull"))
        val PULL_REAR_SLOT = MainSlot(ExerciseRegion.PULL, setOf("upper back", "rear shoulder", "rhomboids", "traps", "trapezius"))
        val PULL_BICEPS_SLOT = MainSlot(ExerciseRegion.PULL, setOf("biceps", "elbow flexion"))
        val PULL_GENERAL_SLOT = MainSlot(ExerciseRegion.PULL)
        val LOWER_KNEE_SLOT = MainSlot(ExerciseRegion.LOWER, setOf("quadriceps", "quads", "squat", "lunge"))
        val LOWER_HIP_SLOT = MainSlot(ExerciseRegion.LOWER, setOf("glutes", "hamstrings", "hip hinge", "hip extension", "deadlift"))
        val LOWER_SECONDARY_SLOT = MainSlot(ExerciseRegion.LOWER)
        val LOWER_ACCESSORY_SLOT = MainSlot(ExerciseRegion.LOWER, setOf("calves", "ankles", "adductors"))
        val CORE_SLOT = MainSlot(ExerciseRegion.CORE)
        val CONDITIONING_SLOT = MainSlot(ExerciseRegion.CONDITIONING)

        fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}

internal fun Exercise.canonicalRoutineTargetSnapshot(): String =
    primaryMuscles.firstOrNull()
        ?: bodyTargets.firstOrNull()
        ?: bodyPart.takeIf(String::isNotBlank)
        ?: category
