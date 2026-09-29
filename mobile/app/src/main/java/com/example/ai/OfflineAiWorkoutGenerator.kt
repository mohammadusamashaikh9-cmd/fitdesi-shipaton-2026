package com.example.ai

import com.example.exercise.CanonicalRoutineCatalogueResult
import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.CanonicalRoutineSelectionException
import com.example.exercise.CanonicalRoutineSelectionRequest
import com.example.exercise.CanonicalRoutineSelectionResult
import com.example.exercise.Exercise
import com.example.exercise.canonicalRoutineTargetSnapshot
import com.example.exercise.programming.DeterministicStructuredWorkoutComposer
import com.example.exercise.programming.ExerciseProgrammingCatalogue
import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingState
import com.example.exercise.programming.StructuredWorkoutCompositionRequest
import kotlinx.coroutines.CancellationException

internal data class ProfileContext(val gender: String, val age: Int)
internal data class GoalPrescription(val sets: Int, val reps: String, val restSeconds: Int, val explanation: String)
internal data class EquipmentContext(val available: Set<String>)
internal data class SafetyReview(val isSafe: Boolean, val message: String)

internal class ProfileAgent {
    fun evaluate(input: AiWorkoutRequest): Result<ProfileContext> = when {
        input.gender !in setOf("Male", "Female") -> Result.failure(
            IllegalArgumentException("Choose a profile gender before generating your routine.")
        )
        input.age !in 15..80 -> Result.failure(
            IllegalArgumentException("Add an age between 15 and 80 before generating your routine.")
        )
        else -> Result.success(ProfileContext(input.gender, input.age))
    }
}

internal class GoalAgent {
    fun prescribe(goal: String): Result<GoalPrescription> = when (goal) {
        "Gain Muscle" -> Result.success(GoalPrescription(3, "8-12", 90, "Moderate repetitions support muscle-building practice."))
        "Lose Body Fat" -> Result.success(GoalPrescription(3, "10-15", 60, "Full-body work and measured rest support an active weekly routine."))
        "Get Stronger" -> Result.success(GoalPrescription(4, "4-6", 150, "Lower repetitions and longer rest support strength practice."))
        else -> Result.failure(IllegalArgumentException("Choose a fitness goal before generating your routine."))
    }
}

internal class ExperienceAgent {
    fun adjust(prescription: GoalPrescription, level: String): Result<GoalPrescription> = when (level) {
        "Beginner" -> Result.success(prescription.copy(sets = prescription.sets.coerceAtMost(3)))
        "Intermediate" -> Result.success(prescription)
        "Advanced" -> Result.success(prescription.copy(sets = (prescription.sets + 1).coerceAtMost(5)))
        else -> Result.failure(IllegalArgumentException("Choose your training experience before generating your routine."))
    }
}

internal class EquipmentAgent {
    fun evaluate(equipment: List<String>): Result<EquipmentContext> {
        val available = equipment.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (available.isEmpty()) {
            Result.failure(IllegalArgumentException("Select at least one available equipment option."))
        } else {
            Result.success(EquipmentContext(available))
        }
    }
}

internal class SafetyCheckAgent {
    private val dayOrder = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun review(input: AiWorkoutRequest): SafetyReview {
        val expectedDays = if (input.generatorType == "Single Workout") 1 else input.daysCount
        if (expectedDays !in 1..7) return SafetyReview(false, "Choose between 1 and 7 workout days.")
        if (input.selectedDays.distinct().size < expectedDays) {
            return SafetyReview(false, "Select $expectedDays workout day${if (expectedDays == 1) "" else "s"}.")
        }
        if (input.selectedDays.any { it !in dayOrder }) return SafetyReview(false, "Choose valid workout days.")
        return SafetyReview(
            true,
            "General fitness guidance only. Start conservatively, use controlled form, allow recovery, and stop for pain, dizziness, chest pain, fainting, or unusual symptoms. Consult a qualified professional for injuries or medical concerns."
        )
    }
}

internal fun validateGeneratorStep(step: Int, input: AiWorkoutRequest): String? = when (step) {
    1 -> ProfileAgent().evaluate(input).exceptionOrNull()?.message
    2 -> ExperienceAgent().adjust(GoalPrescription(3, "8-12", 75, ""), input.level).exceptionOrNull()?.message
    3 -> GoalAgent().prescribe(input.goal).exceptionOrNull()?.message
    4 -> SafetyCheckAgent().review(input).takeUnless { it.isSafe }?.message
    5 -> if (input.split in setOf("Full Body", "Upper / Lower", "Push / Pull / Legs", "I don't know")) null else "Choose a training split before continuing."
    6 -> EquipmentAgent().evaluate(input.equipment).exceptionOrNull()?.message
    else -> null
}

internal class RoutineFormatterAgent {
    private val dayOrder = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun format(
        input: AiWorkoutRequest,
        profile: ProfileContext,
        prescription: GoalPrescription,
        equipment: EquipmentContext,
        safetyNote: String,
        canonicalExercises: List<Exercise>,
        programmingRecords: List<ExerciseProgrammingMetadata>,
        selector: CanonicalRoutineExerciseSelector,
        composer: DeterministicStructuredWorkoutComposer
    ): GeneratedRoutine {
        val count = if (input.generatorType == "Single Workout") 1 else input.daysCount
        val days = input.selectedDays.distinct().sortedBy(dayOrder::indexOf).take(count)
        val resolvedSplit = if (input.split == "I don't know") "Full Body" else input.split
        val focusCycle = when (resolvedSplit) {
            "Full Body" -> listOf("Full Body")
            "Upper / Lower" -> listOf("Upper Body", "Lower Body")
            "Push / Pull / Legs" -> listOf("Push", "Pull", "Legs")
            else -> listOf("Full Body")
        }
        val routineDays = days.mapIndexed { index, day ->
            val focus = focusCycle[index % focusCycle.size]
            val selection = selector.selectDayResult(
                canonicalExercises,
                CanonicalRoutineSelectionRequest(
                    equipment = equipment.available,
                    goal = input.goal.toCanonicalGoal(),
                    experienceLevel = input.level,
                    split = resolvedSplit,
                    dayFocus = focus
                ),
                programmingRecords
            )
            val selectedExercises = when (selection) {
                is CanonicalRoutineSelectionResult.Supported -> selection.exercises
                is CanonicalRoutineSelectionResult.Unsupported -> throw CanonicalRoutineSelectionException(selection.message)
                is CanonicalRoutineSelectionResult.CoverageFailure -> throw CanonicalRoutineSelectionException(selection.message)
            }
            val mainOnlyDay = GeneratedDay(
                dayName = day,
                title = focus,
                description = "$focus session for ${input.goal.lowercase()} using selected equipment.",
                exercises = selectedExercises.map { exercise ->
                    GeneratedExercise(
                        name = exercise.name,
                        sets = prescription.sets,
                        reps = prescription.reps,
                        targetMuscle = exercise.canonicalRoutineTargetSnapshot(),
                        instructions = exercise.instructions,
                        restSeconds = prescription.restSeconds,
                        exerciseId = exercise.id.value
                    )
                }
            )
            val composition = composer.compose(
                StructuredWorkoutCompositionRequest(
                    dayFocus = focus,
                    goal = input.goal.toCanonicalGoal(),
                    experienceLevel = input.level,
                    availableEquipment = equipment.available,
                    mainExercises = mainOnlyDay.exercises,
                    canonicalExercises = canonicalExercises,
                    programmingRecords = programmingRecords
                )
            )
            mainOnlyDay.copy(
                exercises = composition.mainExercises,
                generalWarmup = composition.generalWarmup,
                warmupExercises = composition.preparationExercises,
                cooldownExercises = composition.cooldownExercises
            )
        }
        val recoverySummary = if (input.enforceRecovery) "Recovery spacing was requested." else "Recovery spacing was not enforced."
        val profileSummary = "Plan inputs: age ${profile.age}, ${input.level.lowercase()} level, ${input.goal.lowercase()} goal. $recoverySummary"
        return GeneratedRoutine(
            name = if (input.generatorType == "Single Workout") "FitDesi Offline Workout" else "FitDesi Offline ${input.goal} Week",
            description = profileSummary,
            splitType = resolvedSplit,
            frequency = "$count day${if (count == 1) "" else "s"}/week",
            days = routineDays,
            explanation = prescription.explanation,
            safetyNote = safetyNote
        )
    }

    private fun String.toCanonicalGoal(): String = when (this) {
        "Get Stronger" -> "Strength"
        "Gain Muscle" -> "Muscle Gain"
        "Lose Body Fat" -> "Fat Loss"
        else -> this
    }

    private companion object {
        const val MAIN_EXERCISES_PER_DAY = 4
    }
}

internal class OfflineAiWorkoutGenerator(
    private val canonicalSelector: CanonicalRoutineExerciseSelector,
    private val programmingCatalogue: ExerciseProgrammingCatalogue,
    private val profileAgent: ProfileAgent = ProfileAgent(),
    private val goalAgent: GoalAgent = GoalAgent(),
    private val experienceAgent: ExperienceAgent = ExperienceAgent(),
    private val equipmentAgent: EquipmentAgent = EquipmentAgent(),
    private val safetyCheckAgent: SafetyCheckAgent = SafetyCheckAgent(),
    private val routineFormatterAgent: RoutineFormatterAgent = RoutineFormatterAgent(),
    private val composer: DeterministicStructuredWorkoutComposer = DeterministicStructuredWorkoutComposer()
) : AiWorkoutProvider {
    override suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine> = try {
        val canonicalExercises = when (val result = canonicalSelector.loadCatalogue()) {
            is CanonicalRoutineCatalogueResult.Ready -> result.exercises
            is CanonicalRoutineCatalogueResult.Error,
            CanonicalRoutineCatalogueResult.Loading -> return Result.failure(
                IllegalStateException("Exercise library could not be loaded. Try again.")
            )
        }
        val programmingRecords = loadProgrammingRecords()
        generateLocally(request, canonicalExercises, programmingRecords)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    internal fun generateLocally(
        input: AiWorkoutRequest,
        canonicalExercises: List<Exercise>,
        programmingRecords: List<ExerciseProgrammingMetadata> = emptyList()
    ): Result<GeneratedRoutine> = runCatching {
        val profile = profileAgent.evaluate(input).getOrThrow()
        val goal = goalAgent.prescribe(input.goal).getOrThrow()
        val prescription = experienceAgent.adjust(goal, input.level).getOrThrow()
        val equipment = equipmentAgent.evaluate(input.equipment).getOrThrow()
        val safety = safetyCheckAgent.review(input)
        require(safety.isSafe) { safety.message }
        routineFormatterAgent.format(
            input = input,
            profile = profile,
            prescription = prescription,
            equipment = equipment,
            safetyNote = safety.message,
            canonicalExercises = canonicalExercises,
            programmingRecords = programmingRecords,
            selector = canonicalSelector,
            composer = composer
        )
    }

    private suspend fun loadProgrammingRecords(): List<ExerciseProgrammingMetadata> = try {
        when (val state = programmingCatalogue.load()) {
            is ExerciseProgrammingState.Ready -> state.records
            is ExerciseProgrammingState.Error,
            ExerciseProgrammingState.Loading,
            ExerciseProgrammingState.NotLoaded -> emptyList()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

object LocalAiWorkoutProvider : AiWorkoutProvider {
    override suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine> = Result.failure(
        IllegalStateException("The canonical exercise provider has not been injected.")
    )
}
