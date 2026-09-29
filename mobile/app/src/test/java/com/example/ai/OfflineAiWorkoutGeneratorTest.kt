package com.example.ai

import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.PULL_CAPABILITY_UNAVAILABLE_MESSAGE
import com.example.exercise.Exercise
import com.example.exercise.ExerciseCatalogue
import com.example.exercise.ExerciseCatalogueState
import com.example.exercise.ExerciseFilter
import com.example.exercise.ExerciseId
import com.example.exercise.programming.ExerciseProgrammingCatalogue
import com.example.exercise.programming.ExerciseProgrammingCoverage
import com.example.exercise.programming.ExerciseProgrammingJoint
import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingReviewStatus
import com.example.exercise.programming.ExerciseProgrammingRegion
import com.example.exercise.programming.ExerciseProgrammingRole
import com.example.exercise.programming.ExerciseProgrammingState
import com.example.exercise.programming.ExercisePrescriptionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAiWorkoutGeneratorTest {
    private val catalogue = canonicalExercises()

    private fun validInput() = AiWorkoutRequest(
        generatorType = "Weekly Routine",
        gender = "Male",
        age = 30,
        level = "Intermediate",
        goal = "Gain Muscle",
        daysCount = 3,
        selectedDays = listOf("Mon", "Wed", "Fri"),
        split = "Push / Pull / Legs",
        enforceRecovery = true,
        equipment = listOf("Dumbbells", "Bodyweight")
    )

    @Test
    fun generate_buildsCanonicalStructuredOfflineRoutine() {
        val routine = generator().generateLocally(validInput(), catalogue).getOrThrow()

        assertEquals("3 days/week", routine.frequency)
        assertEquals("Push / Pull / Legs", routine.splitType)
        assertEquals(listOf("Mon", "Wed", "Fri"), routine.days.map { it.dayName })
        assertTrue(routine.days.all { it.exercises.size == 4 })
        assertTrue(routine.days.flatMap { it.exercises }.all {
            it.exerciseId.isNotBlank() && it.sets == 3 && it.reps == "8-12" && it.restSeconds == 90
        })
        assertTrue(routine.explanation.isNotBlank())
        assertTrue(routine.safetyNote.contains("General fitness guidance only"))
    }

    @Test
    fun generate_rejectsMissingProfileWithoutAssumptions() {
        val result = generator().generateLocally(
            validInput().copy(gender = "", age = 0),
            catalogue
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Choose a profile gender"))
    }

    @Test
    fun generate_rejectsUnsupportedBodyweightPullSplitWithSharedMessage() {
        val result = generator().generateLocally(
            validInput().copy(split = "Full Body", equipment = listOf("Bodyweight")),
            catalogue
        )

        assertTrue(result.isFailure)
        assertEquals(PULL_CAPABILITY_UNAVAILABLE_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun generate_programmingFailureFallsBackToCanonicalMainOnly() = runTest {
        val result = generator(programmingState = ExerciseProgrammingState.Error("raw detail"))
            .generate(validInput())
            .getOrThrow()

        assertTrue(result.days.all {
            it.exercises.size == 4 && it.generalWarmup == null &&
                it.warmupExercises.isEmpty() && it.cooldownExercises.isEmpty() &&
                it.exercises.all { exercise -> exercise.rampUpSets.isEmpty() }
        })
    }

    @Test
    fun generate_excludesExact1512FromMainWorkWhenReviewedAsStaticCooldown() {
        val stretch = exercise("1512", "all fours squad stretch", "upper legs", "quadriceps")
        val records = listOf(programming("1512", ExerciseProgrammingRole.STATIC_COOLDOWN))

        val routine = generator().generateLocally(
            validInput(),
            listOf(stretch) + catalogue,
            records
        ).getOrThrow()
        val degraded = generator().generateLocally(
            validInput(),
            listOf(stretch) + catalogue,
            programmingRecords = emptyList()
        ).getOrThrow()

        assertTrue(routine.days.flatMap { it.exercises }.none { it.exerciseId == "1512" })
        assertTrue(routine.days.all { it.exercises.size == 4 })
        assertTrue(degraded.days.flatMap { it.exercises }.none { it.exerciseId == "1512" })
        assertTrue(degraded.days.all { it.exercises.size == 4 })
    }

    @Test
    fun generate_beginnerBodyweightPullSplitIsUnsupportedInsteadOfUsingBadFillers() {
        val archerPullUp = exercise("3293", "archer pull up", "back", "lats")
        val concentrationCurl = exercise("1770", "biceps leg concentration curl", "upper arms", "biceps")
        val records = listOf(
            programming(
                "3293",
                ExerciseProgrammingRole.MAIN_STRENGTH,
                beginnerSuitable = false
            ),
            programming(
                "1770",
                ExerciseProgrammingRole.MAIN_STRENGTH,
                equipment = listOf("dumbbell")
            )
        )

        val result = generator().generateLocally(
            validInput().copy(level = "Beginner", equipment = listOf("Bodyweight")),
            listOf(archerPullUp, concentrationCurl) + catalogue,
            records
        )

        assertTrue(result.isFailure)
        assertEquals(PULL_CAPABILITY_UNAVAILABLE_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun generate_canonicalRepositoryFailureRemainsFailure() = runTest {
        val result = generator(canonicalState = ExerciseCatalogueState.Error("raw detail")).generate(validInput())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Exercise library could not be loaded"))
    }

    @Test
    fun validateStep_requiresSelectedWorkoutDays() {
        val message = validateGeneratorStep(4, validInput().copy(selectedDays = emptyList()))

        assertEquals("Select 3 workout days.", message)
    }

    private fun generator(
        canonicalState: ExerciseCatalogueState = ExerciseCatalogueState.Ready(catalogue),
        programmingState: ExerciseProgrammingState = readyProgramming(emptyList())
    ): OfflineAiWorkoutGenerator {
        val canonical = FakeCatalogue(canonicalState)
        return OfflineAiWorkoutGenerator(
            canonicalSelector = CanonicalRoutineExerciseSelector(canonical),
            programmingCatalogue = FakeProgrammingCatalogue(programmingState)
        )
    }

    private fun canonicalExercises(): List<Exercise> = buildList {
        repeat(4) { index ->
            add(exercise("push-${index + 1}", if (index == 0) "Push-Up" else "Push ${index + 1}", "chest", "pectorals"))
            add(exercise("pull-${index + 1}", "Pull ${index + 1}", "back", "lats"))
            add(exercise("lower-${index + 1}", if (index == 0) "Bodyweight Squat" else "Lower ${index + 1}", "upper legs", "glutes"))
            add(exercise("core-${index + 1}", "Core ${index + 1}", "waist", "abs"))
        }
    }

    private fun exercise(id: String, name: String, bodyPart: String, muscle: String) = Exercise(
        id = ExerciseId(id),
        name = name,
        aliases = emptyList(),
        category = "strength",
        movementPattern = bodyPart,
        bodyPart = bodyPart,
        bodyTargets = listOf(bodyPart),
        primaryMuscles = listOf(muscle),
        secondaryMuscles = emptyList(),
        equipment = listOf("body weight"),
        goals = emptyList(),
        experienceLevels = emptyList(),
        instructions = "Repeat this normal main movement for the desired repetitions.",
        safetyNote = ""
    )

    private fun programming(
        id: String,
        role: ExerciseProgrammingRole,
        beginnerSuitable: Boolean = true,
        equipment: List<String> = listOf("body weight")
    ) = ExerciseProgrammingMetadata(
        exerciseId = id,
        roles = listOf(role),
        programmingRegions = listOf(ExerciseProgrammingRegion.QUADRICEPS),
        joints = listOf(ExerciseProgrammingJoint.KNEE),
        movementPatterns = emptyList(),
        prescriptionMode = ExercisePrescriptionMode.DURATION_SECONDS,
        defaultRepetitions = null,
        defaultDurationSeconds = 20,
        freeTextPrescription = null,
        perSide = true,
        equipmentOverride = emptyList(),
        equipment = equipment,
        duplicateFamilyKey = "all-fours-squad-stretch",
        beginnerSuitable = beginnerSuitable,
        autoSelectApproved = true,
        reviewStatus = ExerciseProgrammingReviewStatus.FITDESI_REVIEWED,
        neverAutoSelectReason = null
    )

    private class FakeCatalogue(private val loadResult: ExerciseCatalogueState) : ExerciseCatalogue {
        private val mutableState = MutableStateFlow<ExerciseCatalogueState>(ExerciseCatalogueState.NotLoaded)
        override val state: StateFlow<ExerciseCatalogueState> = mutableState
        override suspend fun load(): ExerciseCatalogueState = loadResult.also { mutableState.value = it }
        override suspend fun getAll(): List<Exercise> = (loadResult as? ExerciseCatalogueState.Ready)?.exercises.orEmpty()
        override suspend fun getById(id: ExerciseId): Exercise? = getAll().firstOrNull { it.id == id }
        override suspend fun getByExactName(name: String): List<Exercise> = getAll().filter { it.name == name }
        override suspend fun getByAlias(alias: String): List<Exercise> = emptyList()
        override suspend fun search(query: String): List<Exercise> = emptyList()
        override suspend fun filter(filter: ExerciseFilter): List<Exercise> = emptyList()
    }

    private class FakeProgrammingCatalogue(private val loadResult: ExerciseProgrammingState) : ExerciseProgrammingCatalogue {
        private val mutableState = MutableStateFlow<ExerciseProgrammingState>(ExerciseProgrammingState.NotLoaded)
        override val state: StateFlow<ExerciseProgrammingState> = mutableState
        override suspend fun load(): ExerciseProgrammingState = loadResult.also { mutableState.value = it }
        override suspend fun getAll(): List<ExerciseProgrammingMetadata> =
            (loadResult as? ExerciseProgrammingState.Ready)?.records.orEmpty()
        override suspend fun getByExerciseId(exerciseId: String): ExerciseProgrammingMetadata? =
            getAll().firstOrNull { it.exerciseId == exerciseId }
        override suspend fun getByRole(role: ExerciseProgrammingRole) = getAll().filter { role in it.roles }
        override suspend fun getByProgrammingRegion(region: ExerciseProgrammingRegion) =
            getAll().filter { region in it.programmingRegions }
        override suspend fun getByEquipment(equipment: String) = emptyList<ExerciseProgrammingMetadata>()
        override suspend fun getByDuplicateFamily(familyKey: String) = emptyList<ExerciseProgrammingMetadata>()
        override suspend fun coverage(): ExerciseProgrammingCoverage =
            (loadResult as ExerciseProgrammingState.Ready).coverage
    }

    private companion object {
        fun readyProgramming(records: List<ExerciseProgrammingMetadata>) = ExerciseProgrammingState.Ready(
            records,
            ExerciseProgrammingCoverage(
                totalOverlayRecords = records.size,
                countByRole = emptyMap(),
                countByProgrammingRegion = emptyMap(),
                countByEquipment = emptyMap(),
                regionsWithNoReviewedPreparationOption = emptyList(),
                regionsWithNoReviewedCooldownOption = emptyList()
            )
        )
    }
}
