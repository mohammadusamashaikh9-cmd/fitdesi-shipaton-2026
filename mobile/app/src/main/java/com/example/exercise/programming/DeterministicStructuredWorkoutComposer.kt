package com.example.exercise.programming

import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneralWarmupPrescription
import com.example.ai.GeneratedExercise
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.WarmupIntensity
import com.example.exercise.CanonicalEquipmentCapabilities
import com.example.exercise.Exercise
import com.example.exercise.canonicalRoutineTargetSnapshot
import java.util.Locale

internal data class StructuredWorkoutCompositionRequest(
    val dayFocus: String,
    val goal: String,
    val experienceLevel: String,
    val availableEquipment: Set<String>,
    val mainExercises: List<GeneratedExercise>,
    val canonicalExercises: List<Exercise>,
    val programmingRecords: List<ExerciseProgrammingMetadata>
)

internal data class StructuredWorkoutComposition(
    val generalWarmup: GeneralWarmupPrescription?,
    val preparationExercises: List<GeneratedExercise>,
    val mainExercises: List<GeneratedExercise>,
    val cooldownExercises: List<GeneratedExercise>
)

internal class DeterministicStructuredWorkoutComposer {
    fun compose(request: StructuredWorkoutCompositionRequest): StructuredWorkoutComposition {
        if (request.programmingRecords.isEmpty()) {
            return StructuredWorkoutComposition(
                generalWarmup = null,
                preparationExercises = emptyList(),
                mainExercises = request.mainExercises,
                cooldownExercises = emptyList()
            )
        }

        val canonicalById = request.canonicalExercises.associateBy { it.id.value }
        val programmingById = request.programmingRecords.associateBy { it.exerciseId }
        val demand = dayDemand(request.mainExercises, canonicalById, programmingById)
        val usedIds = request.mainExercises.mapTo(linkedSetOf(), GeneratedExercise::exerciseId)
        val usedFamilies = request.mainExercises.mapNotNullTo(linkedSetOf()) { main ->
            programmingById[main.exerciseId]?.normalizedFamily()
        }

        val generalRecord = selectGeneralWarmup(request, demand, canonicalById, usedIds, usedFamilies)
        val generalWarmup = generalRecord?.let { record ->
            val canonical = canonicalById.getValue(record.exerciseId)
            usedIds += record.exerciseId
            record.normalizedFamily()?.let(usedFamilies::add)
            GeneralWarmupPrescription(
                label = canonical.name,
                durationSeconds = requireNotNull(record.defaultDurationSeconds),
                intensityCue = if (request.experienceLevel.isBeginnerOrNovice()) {
                    WarmupIntensity.EASY
                } else {
                    WarmupIntensity.EASY_TO_MODERATE
                },
                canonicalExerciseId = record.exerciseId,
                equipment = CanonicalEquipmentCapabilities.meaningfulEquipment(record.equipment)
            )
        }

        val preparation = selectPreparation(
            request = request,
            demand = demand,
            canonicalById = canonicalById,
            usedIds = usedIds,
            usedFamilies = usedFamilies
        )
        val enrichedMains = request.mainExercises.map { main ->
            val canonicalSafetyNote = canonicalById[main.exerciseId]
                ?.safetyNote
                ?.takeIf(String::isNotBlank)
            main.copy(
                rampUpSets = rampUpSets(
                    main = main,
                    canonical = canonicalById[main.exerciseId],
                    programming = programmingById[main.exerciseId],
                    goal = request.goal,
                    experienceLevel = request.experienceLevel
                ),
                safetyNote = canonicalSafetyNote ?: main.safetyNote
            )
        }
        val cooldown = selectCooldown(
            request = request,
            demand = demand,
            canonicalById = canonicalById,
            usedIds = usedIds,
            usedFamilies = usedFamilies
        )

        return StructuredWorkoutComposition(generalWarmup, preparation, enrichedMains, cooldown)
    }

    private fun selectGeneralWarmup(
        request: StructuredWorkoutCompositionRequest,
        demand: DayDemand,
        canonicalById: Map<String, Exercise>,
        usedIds: Set<String>,
        usedFamilies: Set<String>
    ): ExerciseProgrammingMetadata? = request.programmingRecords.asSequence()
        .filter { it.autoSelectApproved }
        .filter { ExerciseProgrammingRole.GENERAL_WARMUP in it.roles }
        .filterNot { ExerciseProgrammingRole.NEVER_AUTO_SELECT in it.roles }
        .filter { it.defaultDurationSeconds?.let { seconds -> seconds > 0 } == true }
        .filter { it.exerciseId !in usedIds && canonicalById.containsKey(it.exerciseId) }
        .filter { it.normalizedFamily()?.let { family -> family !in usedFamilies } != false }
        .filter { CanonicalEquipmentCapabilities.isCompatible(it.equipment, request.availableEquipment) }
        .filter { !request.experienceLevel.isBeginnerOrNovice() || it.beginnerSuitable }
        .sortedWith(
            compareBy<ExerciseProgrammingMetadata>(
                { -CanonicalEquipmentCapabilities.compatibilityStrength(it.equipment, request.availableEquipment) },
                { if (ExerciseProgrammingRegion.FULL_BODY in it.programmingRegions) 0 else 1 },
                { -it.joints.count(demand.joints::contains) },
                { if (it.beginnerSuitable) 0 else 1 },
                ExerciseProgrammingMetadata::exerciseId
            )
        )
        .firstOrNull()

    private fun selectPreparation(
        request: StructuredWorkoutCompositionRequest,
        demand: DayDemand,
        canonicalById: Map<String, Exercise>,
        usedIds: MutableSet<String>,
        usedFamilies: MutableSet<String>
    ): List<GeneratedExercise> {
        val selected = mutableListOf<GeneratedExercise>()
        PREPARATION_ROLE_ORDER.forEach { role ->
            if (selected.size == MAX_PHASE_EXERCISES) return@forEach
            val record = request.programmingRecords.asSequence()
                .filter { it.autoSelectApproved && role in it.roles }
                .filterNot {
                    ExerciseProgrammingRole.STATIC_COOLDOWN in it.roles ||
                        ExerciseProgrammingRole.NEVER_AUTO_SELECT in it.roles
                }
                .filter { it.exerciseId !in usedIds && canonicalById.containsKey(it.exerciseId) }
                .filter { it.normalizedFamily()?.let { family -> family !in usedFamilies } != false }
                .filter { CanonicalEquipmentCapabilities.isCompatible(it.equipment, request.availableEquipment) }
                .filter { !request.experienceLevel.isBeginnerOrNovice() || it.beginnerSuitable }
                .filter {
                    it.programmingRegions.any(demand.regions::contains) ||
                        it.joints.any(demand.joints::contains)
                }
                .filter { it.toActivityPrescription() != null }
                .sortedWith(
                    compareBy<ExerciseProgrammingMetadata>(
                        { -it.programmingRegions.count(demand.regions::contains) },
                        { -it.joints.count(demand.joints::contains) },
                        { -it.movementPatterns.map(::normalize).count(demand.movementPatterns::contains) },
                        { if (it.beginnerSuitable) 0 else 1 },
                        ExerciseProgrammingMetadata::exerciseId
                    )
                )
                .firstOrNull()
                ?: return@forEach

            val canonical = canonicalById.getValue(record.exerciseId)
            selected += record.toPhaseExercise(canonical)
            usedIds += record.exerciseId
            record.normalizedFamily()?.let(usedFamilies::add)
        }
        return selected
    }

    private fun selectCooldown(
        request: StructuredWorkoutCompositionRequest,
        demand: DayDemand,
        canonicalById: Map<String, Exercise>,
        usedIds: MutableSet<String>,
        usedFamilies: MutableSet<String>
    ): List<GeneratedExercise> {
        val selected = mutableListOf<GeneratedExercise>()
        val uncoveredRegions = demand.regions.toMutableSet()
        val uncoveredJoints = demand.joints.toMutableSet()

        while (selected.size < MAX_PHASE_EXERCISES) {
            val candidate = request.programmingRecords.asSequence()
                .filter { it.autoSelectApproved }
                .filter { ExerciseProgrammingRole.STATIC_COOLDOWN in it.roles }
                .filterNot { ExerciseProgrammingRole.NEVER_AUTO_SELECT in it.roles }
                .filter { it.exerciseId !in usedIds && canonicalById.containsKey(it.exerciseId) }
                .filter { it.normalizedFamily()?.let { family -> family !in usedFamilies } != false }
                .filter { CanonicalEquipmentCapabilities.isCompatible(it.equipment, request.availableEquipment) }
                .filter { !request.experienceLevel.isBeginnerOrNovice() || it.beginnerSuitable }
                .filter {
                    it.programmingRegions.any(demand.regions::contains) ||
                        it.joints.any(demand.joints::contains)
                }
                .filter {
                    it.programmingRegions.any(uncoveredRegions::contains) ||
                        it.joints.any(uncoveredJoints::contains)
                }
                .filter { it.toActivityPrescription() != null }
                .sortedWith(
                    compareBy<ExerciseProgrammingMetadata>(
                        { -it.programmingRegions.count(uncoveredRegions::contains) },
                        { -it.joints.count(demand.joints::contains) },
                        { if (it.beginnerSuitable) 0 else 1 },
                        ExerciseProgrammingMetadata::exerciseId
                    )
                )
                .firstOrNull()
                ?: break

            selected += candidate.toPhaseExercise(canonicalById.getValue(candidate.exerciseId))
            usedIds += candidate.exerciseId
            candidate.normalizedFamily()?.let(usedFamilies::add)
            uncoveredRegions.removeAll(candidate.programmingRegions.toSet())
            uncoveredJoints.removeAll(candidate.joints.toSet())
        }
        return selected
    }

    private fun rampUpSets(
        main: GeneratedExercise,
        canonical: Exercise?,
        programming: ExerciseProgrammingMetadata?,
        goal: String,
        experienceLevel: String
    ): List<RampUpSetPrescription> {
        if (main.exerciseId == RAMP_UP_EXCLUDED_ID || canonical == null || programming == null) return emptyList()
        if (ExerciseProgrammingRole.MAIN_STRENGTH !in programming.roles) return emptyList()
        if (!programming.autoSelectApproved || ExerciseProgrammingRole.NEVER_AUTO_SELECT in programming.roles) {
            return emptyList()
        }
        if (!CanonicalEquipmentCapabilities.hasMeaningfulExternalResistance(programming.equipment)) return emptyList()
        if (main.sets < 2) return emptyList()

        return buildList {
            add(RampUpSetPrescription(1, RampUpLoadCue.VERY_LIGHT, 8, 45))
            if (main.sets >= 4) add(RampUpSetPrescription(2, RampUpLoadCue.LIGHT, 5, 60))
            if (main.sets >= 4 && normalize(goal) == "strength" && normalize(experienceLevel) == "advanced") {
                add(RampUpSetPrescription(3, RampUpLoadCue.MODERATE, 3, 75))
            }
        }
    }

    private fun dayDemand(
        mains: List<GeneratedExercise>,
        canonicalById: Map<String, Exercise>,
        programmingById: Map<String, ExerciseProgrammingMetadata>
    ): DayDemand {
        val regions = linkedSetOf<ExerciseProgrammingRegion>()
        val joints = linkedSetOf<ExerciseProgrammingJoint>()
        val patterns = linkedSetOf<String>()
        mains.forEach { main ->
            val canonical = canonicalById[main.exerciseId] ?: return@forEach
            val programming = programmingById[main.exerciseId]
            if (programming != null) {
                val programmedRegions = programming.programmingRegions
                regions += programmedRegions.ifEmpty { canonical.fallbackRegions().toList() }
                joints += programming.joints.ifEmpty {
                    programmedRegions.flatMap(::fallbackJoints)
                }
                patterns += programming.movementPatterns.map(::normalize).filter(String::isNotBlank).ifEmpty {
                    listOfNotNull(normalize(canonical.movementPattern).takeIf(String::isNotBlank))
                }
            } else {
                val fallbackRegions = canonical.fallbackRegions()
                regions += fallbackRegions
                joints += fallbackRegions.flatMap(::fallbackJoints)
                normalize(canonical.movementPattern).takeIf(String::isNotBlank)?.let(patterns::add)
            }
        }
        return DayDemand(regions, joints, patterns)
    }

    private fun ExerciseProgrammingMetadata.toPhaseExercise(canonical: Exercise): GeneratedExercise {
        val prescription = requireNotNull(toActivityPrescription())
        return GeneratedExercise(
            name = canonical.name,
            sets = 1,
            reps = prescription.readablePrescription(),
            targetMuscle = canonical.canonicalRoutineTargetSnapshot(),
            instructions = canonical.instructions,
            restSeconds = 0,
            exerciseId = exerciseId,
            activityPrescription = prescription,
            safetyNote = canonical.safetyNote.takeIf(String::isNotBlank).orEmpty()
        )
    }

    private fun ExerciseProgrammingMetadata.toActivityPrescription(): ActivityPrescription? = when (prescriptionMode) {
        ExercisePrescriptionMode.REPETITIONS -> defaultRepetitions?.takeIf { it > 0 }?.let {
            ActivityPrescription(ActivityPrescriptionMode.REPETITIONS, repetitions = it, perSide = perSide)
        }
        ExercisePrescriptionMode.DURATION_SECONDS -> defaultDurationSeconds?.takeIf { it > 0 }?.let {
            ActivityPrescription(ActivityPrescriptionMode.DURATION_SECONDS, durationSeconds = it, perSide = perSide)
        }
        ExercisePrescriptionMode.FREE_TEXT -> freeTextPrescription?.takeIf(String::isNotBlank)?.let {
            ActivityPrescription(ActivityPrescriptionMode.FREE_TEXT, freeText = it, perSide = perSide)
        }
    }

    private fun ActivityPrescription.readablePrescription(): String {
        val base = when (mode) {
            ActivityPrescriptionMode.REPETITIONS -> "${requireNotNull(repetitions)} reps"
            ActivityPrescriptionMode.DURATION_SECONDS -> "${requireNotNull(durationSeconds)} seconds"
            ActivityPrescriptionMode.FREE_TEXT -> requireNotNull(freeText)
        }
        return if (perSide) "$base per side" else base
    }

    private fun Exercise.fallbackRegions(): Set<ExerciseProgrammingRegion> {
        val facets = buildList {
            add(bodyPart)
            addAll(bodyTargets)
            addAll(primaryMuscles)
            addAll(secondaryMuscles)
            add(movementPattern)
        }.map(::normalize)
        return ExerciseProgrammingRegion.entries.filterTo(linkedSetOf()) { region ->
            REGION_TERMS.getValue(region).any { term -> facets.any { facet -> facet == term || facet.contains(term) } }
        }
    }

    private fun fallbackJoints(region: ExerciseProgrammingRegion): Set<ExerciseProgrammingJoint> = when (region) {
        ExerciseProgrammingRegion.CHEST,
        ExerciseProgrammingRegion.SHOULDERS,
        ExerciseProgrammingRegion.BACK,
        ExerciseProgrammingRegion.LATS -> setOf(ExerciseProgrammingJoint.SHOULDER)
        ExerciseProgrammingRegion.TRICEPS,
        ExerciseProgrammingRegion.BICEPS -> setOf(ExerciseProgrammingJoint.ELBOW)
        ExerciseProgrammingRegion.FOREARMS -> setOf(ExerciseProgrammingJoint.ELBOW, ExerciseProgrammingJoint.WRIST)
        ExerciseProgrammingRegion.CORE,
        ExerciseProgrammingRegion.LOWER_BACK -> setOf(ExerciseProgrammingJoint.SPINE)
        ExerciseProgrammingRegion.HIPS,
        ExerciseProgrammingRegion.GLUTES,
        ExerciseProgrammingRegion.HAMSTRINGS -> setOf(ExerciseProgrammingJoint.HIP)
        ExerciseProgrammingRegion.QUADRICEPS -> setOf(ExerciseProgrammingJoint.HIP, ExerciseProgrammingJoint.KNEE)
        ExerciseProgrammingRegion.CALVES,
        ExerciseProgrammingRegion.ANKLES -> setOf(ExerciseProgrammingJoint.ANKLE)
        ExerciseProgrammingRegion.FULL_BODY -> emptySet()
    }

    private fun ExerciseProgrammingMetadata.normalizedFamily(): String? =
        duplicateFamilyKey?.let(::normalize)?.takeIf(String::isNotBlank)

    private fun String.isBeginnerOrNovice(): Boolean = normalize(this) in setOf("beginner", "novice")

    private data class DayDemand(
        val regions: Set<ExerciseProgrammingRegion>,
        val joints: Set<ExerciseProgrammingJoint>,
        val movementPatterns: Set<String>
    )

    private companion object {
        const val MAX_PHASE_EXERCISES = 3
        const val RAMP_UP_EXCLUDED_ID = "0286"
        val PREPARATION_ROLE_ORDER = listOf(
            ExerciseProgrammingRole.DYNAMIC_PREPARATION,
            ExerciseProgrammingRole.MOBILITY,
            ExerciseProgrammingRole.ACTIVATION
        )
        val REGION_TERMS = mapOf(
            ExerciseProgrammingRegion.CHEST to setOf("chest", "pectorals"),
            ExerciseProgrammingRegion.SHOULDERS to setOf("shoulder", "deltoid", "delts"),
            ExerciseProgrammingRegion.TRICEPS to setOf("triceps"),
            ExerciseProgrammingRegion.BACK to setOf("back", "rhomboid", "trapezius", "traps"),
            ExerciseProgrammingRegion.LATS to setOf("lats", "latissimus"),
            ExerciseProgrammingRegion.BICEPS to setOf("biceps"),
            ExerciseProgrammingRegion.FOREARMS to setOf("forearm"),
            ExerciseProgrammingRegion.CORE to setOf("core", "waist", "abs", "oblique"),
            ExerciseProgrammingRegion.LOWER_BACK to setOf("lower back"),
            ExerciseProgrammingRegion.HIPS to setOf("hip", "adductor"),
            ExerciseProgrammingRegion.GLUTES to setOf("glute"),
            ExerciseProgrammingRegion.QUADRICEPS to setOf("quadriceps", "quads", "upper legs", "squat"),
            ExerciseProgrammingRegion.HAMSTRINGS to setOf("hamstring", "hinge"),
            ExerciseProgrammingRegion.CALVES to setOf("calf", "calves", "lower legs"),
            ExerciseProgrammingRegion.ANKLES to setOf("ankle"),
            ExerciseProgrammingRegion.FULL_BODY to setOf("full body")
        )

        fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}
