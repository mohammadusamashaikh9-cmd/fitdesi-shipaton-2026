package com.example.domain

import com.example.exercise.Exercise
import java.util.Locale
import kotlin.math.roundToInt

enum class ApprovedMuscleGroup(val displayLabel: String) {
    CHEST("Chest"),
    BACK("Back"),
    SHOULDERS("Shoulders"),
    BICEPS("Biceps"),
    TRICEPS("Triceps"),
    FOREARMS("Forearms"),
    CORE("Core"),
    QUADRICEPS("Quadriceps"),
    HAMSTRINGS("Hamstrings"),
    GLUTES("Glutes"),
    CALVES("Calves"),
    ADDUCTORS("Adductors"),
    HIP_FLEXORS("Hip flexors"),
    OTHER("Other"),
    CARDIOVASCULAR("Cardiovascular")
}

data class CompletedExerciseSetCount(
    val exerciseId: String?,
    val completedMainWorkingSets: Int
)

data class ResolvedExerciseMuscles(
    val exerciseId: String,
    val primaryGroups: List<ApprovedMuscleGroup>,
    val secondaryGroups: List<ApprovedMuscleGroup>
)

data class MuscleSetCredit(
    val muscleGroup: ApprovedMuscleGroup,
    val primarySetCredits: Double,
    val secondarySetCredits: Double
) {
    fun workloadPoints(): Double = primarySetCredits + 0.5 * secondarySetCredits
}

data class MuscleWorkloadComputation(
    val credits: List<MuscleSetCredit>,
    val classifiedSets: Int,
    val unclassifiedSets: Int
)

data class PersistedMuscleLoad(
    val sessionId: String,
    val muscleGroup: String,
    val primarySetCredits: Double,
    val secondarySetCredits: Double,
    val completedSets: Int
)

data class MuscleWorkloadRank(
    val muscleGroup: ApprovedMuscleGroup,
    val workloadPoints: Double,
    val fraction: Float
)

data class MuscleWorkloadSummary(
    val ranked: List<MuscleWorkloadRank> = emptyList(),
    val workoutsWithMuscleData: Int = 0,
    val totalWorkouts: Int = 0,
    val unclassifiedSets: Int = 0
)

object MuscleWorkloadResolver {
    fun resolve(exercise: Exercise): ResolvedExerciseMuscles {
        val rawPrimary = exercise.primaryMuscles.normalizedDistinct()
        val rawSecondary = exercise.secondaryMuscles.normalizedDistinct()
        val secondaryLabels = rawSecondary.toSet()
        val primary = rawPrimary.filterNot(secondaryLabels::contains)
            .mapNotNull(::muscleGroupFor)
            .distinct()
            .filterNot { it == ApprovedMuscleGroup.CARDIOVASCULAR }
            .ifEmpty {
                rawPrimary.asSequence()
                    .mapNotNull(::muscleGroupFor)
                    .firstOrNull { it != ApprovedMuscleGroup.CARDIOVASCULAR }
                    ?.let(::listOf)
                    .orEmpty()
            }
        val secondary = rawSecondary.mapNotNull(::muscleGroupFor).distinct()
            .filterNot { it == ApprovedMuscleGroup.CARDIOVASCULAR || it in primary }
        return ResolvedExerciseMuscles(exercise.id.value, primary, secondary)
    }

    private fun muscleGroupFor(label: String): ApprovedMuscleGroup? = when (label) {
        "chest", "pectorals", "upper chest" -> ApprovedMuscleGroup.CHEST
        "back", "latissimus dorsi", "lats", "lower back", "upper back", "rhomboids", "trapezius", "traps" -> ApprovedMuscleGroup.BACK
        "shoulders", "deltoids", "delts", "rear deltoids", "rotator cuff" -> ApprovedMuscleGroup.SHOULDERS
        "biceps", "brachialis" -> ApprovedMuscleGroup.BICEPS
        "triceps" -> ApprovedMuscleGroup.TRICEPS
        "forearms", "lower arms", "wrist extensors", "wrist flexors", "wrists" -> ApprovedMuscleGroup.FOREARMS
        "abs", "core", "obliques", "spine", "waist" -> ApprovedMuscleGroup.CORE
        "quadriceps", "quads" -> ApprovedMuscleGroup.QUADRICEPS
        "hamstrings" -> ApprovedMuscleGroup.HAMSTRINGS
        "glutes" -> ApprovedMuscleGroup.GLUTES
        "calves", "lower legs" -> ApprovedMuscleGroup.CALVES
        "adductors", "groin" -> ApprovedMuscleGroup.ADDUCTORS
        "hip flexors" -> ApprovedMuscleGroup.HIP_FLEXORS
        "ankle stabilizers", "ankles", "feet", "serratus anterior" -> ApprovedMuscleGroup.OTHER
        "cardio", "cardiovascular system" -> ApprovedMuscleGroup.CARDIOVASCULAR
        else -> null
    }

    private fun List<String>.normalizedDistinct(): List<String> {
        val seen = mutableSetOf<String>()
        return map { it.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() && seen.add(it) }
    }
}

fun calculateMuscleWorkload(
    completedExercises: List<CompletedExerciseSetCount>,
    canonicalExercises: Map<String, Exercise>
): MuscleWorkloadComputation {
    val credits = linkedMapOf<ApprovedMuscleGroup, Pair<Double, Double>>()
    var classified = 0
    var unclassified = 0
    completedExercises.filter { it.completedMainWorkingSets > 0 }.forEach { completed ->
        val setCount = completed.completedMainWorkingSets
        val exercise = completed.exerciseId?.let(canonicalExercises::get)
        val resolved = exercise?.let(MuscleWorkloadResolver::resolve)
        if (resolved == null || resolved.primaryGroups.isEmpty()) {
            unclassified += setCount
            return@forEach
        }
        classified += setCount
        val primaryPerGroup = setCount.toDouble() / resolved.primaryGroups.size
        val secondaryPerGroup = if (resolved.secondaryGroups.isEmpty()) 0.0 else {
            setCount.toDouble() / resolved.secondaryGroups.size
        }
        resolved.primaryGroups.forEach { group ->
            val old = credits[group] ?: (0.0 to 0.0)
            credits[group] = old.first + primaryPerGroup to old.second
        }
        resolved.secondaryGroups.forEach { group ->
            val old = credits[group] ?: (0.0 to 0.0)
            credits[group] = old.first to old.second + secondaryPerGroup
        }
    }
    return MuscleWorkloadComputation(
        credits = credits.map { (group, values) -> MuscleSetCredit(group, values.first, values.second) },
        classifiedSets = classified,
        unclassifiedSets = unclassified
    )
}

fun summarizePersistedMuscleWorkload(
    records: List<PersistedMuscleLoad>,
    totalWorkouts: Int
): MuscleWorkloadSummary {
    val valid = records.mapNotNull { record ->
        val group = runCatching { ApprovedMuscleGroup.valueOf(record.muscleGroup) }.getOrNull()
            ?.takeUnless { it == ApprovedMuscleGroup.CARDIOVASCULAR }
            ?: return@mapNotNull null
        record to group
    }
    val pointsByGroup = valid.groupBy({ it.second }, { it.first })
        .mapValues { (_, rows) -> rows.sumOf { it.primarySetCredits + 0.5 * it.secondarySetCredits } }
        .filterValues { it > 0.0 }
    val totalPoints = pointsByGroup.values.sum()
    val completedBySession = valid.groupBy { it.first.sessionId }
        .mapValues { (_, rows) -> rows.maxOf { it.first.completedSets.coerceAtLeast(0) } }
    val classifiedBySession = valid.groupBy { it.first.sessionId }
        .mapValues { (_, rows) -> rows.sumOf { it.first.primarySetCredits }.roundToInt() }
    return MuscleWorkloadSummary(
        ranked = pointsByGroup.entries
            .sortedWith(compareByDescending<Map.Entry<ApprovedMuscleGroup, Double>> { it.value }.thenBy { it.key.name })
            .map { (group, points) ->
                MuscleWorkloadRank(
                    muscleGroup = group,
                    workloadPoints = points,
                    fraction = if (totalPoints > 0.0) (points / totalPoints).toFloat() else 0f
                )
            },
        workoutsWithMuscleData = valid.map { it.first.sessionId }.distinct().size,
        totalWorkouts = totalWorkouts,
        unclassifiedSets = completedBySession.entries.sumOf { (sessionId, completed) ->
            (completed - (classifiedBySession[sessionId] ?: 0)).coerceAtLeast(0)
        }
    )
}
