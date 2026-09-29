package com.example.ui

import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutineResult
import com.example.exercise.CanonicalRoutineCatalogueResult
import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.CanonicalRoutineSelectionException
import com.example.exercise.CanonicalRoutineSelectionRequest
import com.example.exercise.CanonicalRoutineSelectionResult
import com.example.exercise.PULL_CAPABILITY_UNAVAILABLE_MESSAGE
import com.example.exercise.Exercise
import com.example.exercise.ExerciseCatalogue
import com.example.exercise.ExerciseCatalogueState
import com.example.exercise.ExerciseFilter
import com.example.exercise.ExerciseId
import com.example.exercise.programming.ExercisePrescriptionMode
import com.example.exercise.programming.ExerciseProgrammingCoverage
import com.example.exercise.programming.ExerciseProgrammingCatalogue
import com.example.exercise.programming.ExerciseProgrammingJoint
import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingRegion
import com.example.exercise.programming.ExerciseProgrammingReviewStatus
import com.example.exercise.programming.ExerciseProgrammingRole
import com.example.exercise.programming.ExerciseProgrammingState
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRoutineScreenTest {
    private val catalogue = canonicalCatalogue()
    private val selector = CanonicalRoutineExerciseSelector(FakeCatalogue(catalogue))

    @Test
    fun `authored routine result maps limit to paywall without completing builder`() {
        assertEquals(
            AuthoredRoutineSaveAction.OPEN_ROUTINE_LIMIT_PAYWALL,
            SavedRoutineResult.LIMIT_REACHED.toAuthoredRoutineSaveAction()
        )
        assertEquals(AuthoredRoutineSaveAction.COMPLETE, SavedRoutineResult.SAVED.toAuthoredRoutineSaveAction())
        assertEquals(AuthoredRoutineSaveAction.COMPLETE, SavedRoutineResult.ALREADY_SAVED.toAuthoredRoutineSaveAction())
        assertEquals(AuthoredRoutineSaveAction.SHOW_INVALID_ERROR, SavedRoutineResult.INVALID_ROUTINE.toAuthoredRoutineSaveAction())
        assertEquals(AuthoredRoutineSaveAction.SHOW_STORAGE_ERROR, SavedRoutineResult.ERROR.toAuthoredRoutineSaveAction())
    }

    @Test
    fun `Build Routine exposes shared unsupported bodyweight pull capability message`() {
        val error = assertThrows(CanonicalRoutineSelectionException::class.java) {
            createLocalRoutine(
                input(split = "Push / Pull / Legs", equipment = setOf("Bodyweight"), days = 3),
                catalogue,
                selector
            )
        }

        assertEquals(PULL_CAPABILITY_UNAVAILABLE_MESSAGE, error.message)
    }

    @Test
    fun `local routine uses canonical snapshots with selected schedule and prescription`() {
        val routine = createLocalRoutine(
            input(split = "Upper / Lower", equipment = setOf("Dumbbells"), days = 4),
            catalogue,
            selector
        )

        assertEquals("QA Strength Routine", routine.name)
        assertEquals("4 days/week", routine.frequency)
        assertEquals(listOf("Upper Body", "Lower Body", "Upper Body", "Lower Body"), routine.days.map { it.title })
        assertTrue(routine.days.all { it.exercises.size == 4 })
        assertTrue(routine.days.flatMap { it.exercises }.all { it.exerciseId.isNotBlank() })
        assertTrue(routine.days.flatMap { it.exercises }.all { it.sets == 4 && it.reps == "4-6" })
        val canonicalById = catalogue.associateBy { it.id.value }
        assertTrue(routine.days.flatMap { it.exercises }.all { generated ->
            val canonical = canonicalById.getValue(generated.exerciseId)
            generated.name == canonical.name &&
                generated.targetMuscle == canonical.primaryMuscles.first() &&
                generated.instructions == canonical.instructions
        })
    }

    @Test
    fun `structured composition preserves the four selected main IDs and prescription`() {
        val expandedCatalogue = catalogue + listOf(
            exercise("warmup", "Z Warmup", "cardio", "cardiovascular system"),
            exercise("prep", "Z Preparation", "upper legs", "quadriceps"),
            exercise("cool", "Z Cooldown", "upper legs", "quadriceps")
        )
        val expandedSelector = CanonicalRoutineExerciseSelector(FakeCatalogue(expandedCatalogue))
        val builderInput = input(goal = "Strength", equipment = setOf("Dumbbells"))
        val records = listOf(
            programming("lower-1", ExerciseProgrammingRole.MAIN_STRENGTH, listOf(ExerciseProgrammingRegion.QUADRICEPS)),
            programming("warmup", ExerciseProgrammingRole.GENERAL_WARMUP, listOf(ExerciseProgrammingRegion.FULL_BODY), duration = 300),
            programming("prep", ExerciseProgrammingRole.DYNAMIC_PREPARATION, listOf(ExerciseProgrammingRegion.QUADRICEPS), repetitions = 10),
            programming("cool", ExerciseProgrammingRole.STATIC_COOLDOWN, listOf(ExerciseProgrammingRegion.QUADRICEPS), duration = 20)
        )
        val expectedSelection = expandedSelector.selectDayResult(
            expandedCatalogue,
            CanonicalRoutineSelectionRequest(
                equipment = builderInput.equipment,
                goal = requireNotNull(builderInput.goal),
                experienceLevel = requireNotNull(builderInput.level),
                split = requireNotNull(builderInput.split),
                dayFocus = "Full Body"
            ),
            records
        )
        assertTrue(expectedSelection is CanonicalRoutineSelectionResult.Supported)
        val expectedMains = (expectedSelection as CanonicalRoutineSelectionResult.Supported).exercises

        val structured = createLocalRoutine(builderInput, expandedCatalogue, expandedSelector, records)

        structured.days.forEach { day ->
            assertEquals(expectedMains.map { it.id.value }, day.exercises.map { it.exerciseId })
            day.exercises.zip(expectedMains).forEach { (generated, selected) ->
                assertEquals(selected.name, generated.name)
                assertEquals(4, generated.sets)
                assertEquals("4-6", generated.reps)
                assertEquals(0, generated.restSeconds)
                assertEquals(selected.instructions, generated.instructions)
            }
        }
        assertTrue(structured.days.all { it.exercises.size == 4 })
        assertTrue(structured.days.flatMap { it.exercises }.all { it.sets == 4 && it.reps == "4-6" })
        assertTrue(structured.days.all {
            it.generalWarmup != null && it.warmupExercises.isNotEmpty() && it.cooldownExercises.isNotEmpty()
        })
    }

    @Test
    fun `Build Routine prefers meaningful dumbbell mains when compatible coverage exists`() {
        val mixedCatalogue = buildList {
            listOf(
                "upper legs" to "glutes",
                "chest" to "pectorals",
                "back" to "lats",
                "waist" to "abs"
            ).forEachIndexed { index, (bodyPart, target) ->
                add(exercise("body-${index + 1}", "A Body ${index + 1}", bodyPart, target))
                add(
                    exercise(
                        "dumbbell-${index + 1}",
                        "Z Dumbbell ${index + 1}",
                        bodyPart,
                        target,
                        equipment = "dumbbell"
                    )
                )
            }
        }
        val mixedSelector = CanonicalRoutineExerciseSelector(FakeCatalogue(mixedCatalogue))

        val routine = createLocalRoutine(
            input(split = "Full Body", equipment = setOf("Dumbbells")),
            mixedCatalogue,
            mixedSelector
        )

        assertTrue(routine.days.all { day ->
            day.exercises.size == 4 && day.exercises.all { generated ->
                mixedCatalogue.first { it.id.value == generated.exerciseId }.equipment == listOf("dumbbell")
            }
        })
    }

    @Test
    fun `Build Routine excludes exact 1512 from main work when reviewed as static cooldown`() {
        val stretch = exercise("1512", "all fours squad stretch", "upper legs", "quadriceps")
        val expandedCatalogue = listOf(stretch) + catalogue
        val expandedSelector = CanonicalRoutineExerciseSelector(FakeCatalogue(expandedCatalogue))
        val records = listOf(
            programming(
                "1512",
                ExerciseProgrammingRole.STATIC_COOLDOWN,
                listOf(ExerciseProgrammingRegion.QUADRICEPS),
                duration = 20
            )
        )

        val routine = createLocalRoutine(
            input(split = "Push / Pull / Legs", equipment = setOf("Dumbbells"), days = 3),
            expandedCatalogue,
            expandedSelector,
            records
        )
        val degraded = createLocalRoutine(
            input(split = "Push / Pull / Legs", equipment = setOf("Dumbbells"), days = 3),
            expandedCatalogue,
            expandedSelector,
            programmingRecords = emptyList()
        )

        assertTrue(routine.days.flatMap { it.exercises }.none { it.exerciseId == "1512" })
        assertTrue(routine.days.all { it.exercises.size == 4 })
        assertTrue(degraded.days.flatMap { it.exercises }.none { it.exerciseId == "1512" })
        assertTrue(degraded.days.all { it.exercises.size == 4 })
    }

    @Test
    fun `canonical ready with programming not loaded keeps generation blocked`() {
        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.NotLoaded
        )

        assertEquals(RoutineGenerationReadiness.Blocked, readiness)
        assertFalse(readiness.canGenerate)
    }

    @Test
    fun `canonical ready with programming loading keeps generation blocked`() {
        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Loading
        )

        assertEquals(RoutineGenerationReadiness.Blocked, readiness)
        assertFalse(readiness.canGenerate)
    }

    @Test
    fun `canonical and programming ready allow structured generation with exact records`() {
        val records = listOf(
            programming(
                id = "0257",
                role = ExerciseProgrammingRole.MOBILITY,
                regions = listOf(ExerciseProgrammingRegion.QUADRICEPS),
                repetitions = 8
            )
        )

        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Ready(
                records = records,
                coverage = programmingCoverage(records)
            )
        )

        assertTrue(readiness is RoutineGenerationReadiness.Structured)
        assertTrue(readiness.canGenerate)
        assertEquals(records, readiness.programmingRecords)
        assertEquals("0257", readiness.programmingRecords.single().exerciseId)
    }

    @Test
    fun `canonical ready with empty programming records uses explained degraded fallback`() {
        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Ready(
                records = emptyList(),
                coverage = programmingCoverage(emptyList())
            )
        )

        assertTrue(readiness is RoutineGenerationReadiness.Degraded)
        assertTrue(readiness.canGenerate)
        assertTrue(readiness.programmingRecords.isEmpty())
        assertEquals(
            "Enhanced workout programming is temporarily unavailable. Generate to use the basic main-exercise workout.",
            readiness.degradedMessage
        )
    }

    @Test
    fun `terminal programming error allows explained main-only fallback`() {
        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Error("internal load detail")
        )

        assertTrue(readiness is RoutineGenerationReadiness.Degraded)
        assertTrue(readiness.canGenerate)
        assertTrue(readiness.programmingRecords.isEmpty())
        assertEquals(
            "Enhanced workout programming is temporarily unavailable. Generate to use the basic main-exercise workout.",
            readiness.degradedMessage
        )
        assertFalse(readiness.degradedMessage.orEmpty().contains("internal load detail"))
    }

    @Test
    fun `retry loading transition blocks generation until programming becomes ready`() {
        val records = listOf(
            programming(
                id = "1576",
                role = ExerciseProgrammingRole.MOBILITY,
                regions = listOf(ExerciseProgrammingRegion.HAMSTRINGS),
                repetitions = 8
            )
        )
        val terminalFailure = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Error("unavailable")
        )
        val retryLoading = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Loading
        )
        val retryReady = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Ready(
                records = records,
                coverage = programmingCoverage(records)
            )
        )

        assertTrue(terminalFailure.canGenerate)
        assertFalse(retryLoading.canGenerate)
        assertTrue(retryReady.canGenerate)
        assertTrue(retryReady is RoutineGenerationReadiness.Structured)
    }

    @Test
    fun `canonical error does not invoke programming metadata loading`() = runTest {
        val programmingCatalogue = FakeProgrammingCatalogue(
            ExerciseProgrammingState.Error("must not be reached")
        )

        val result = loadProgrammingStateAfterCanonical(
            canonicalResult = CanonicalRoutineCatalogueResult.Error(),
            programmingCatalogue = programmingCatalogue
        )

        assertEquals(ExerciseProgrammingState.NotLoaded, result)
        assertEquals(0, programmingCatalogue.loadCount)
    }

    @Test
    fun `programming loader preserves not-loaded loading ready and error states`() = runTest {
        val records = listOf(
            programming(
                id = "0643",
                role = ExerciseProgrammingRole.MOBILITY,
                regions = listOf(ExerciseProgrammingRegion.TRICEPS),
                repetitions = 8
            )
        )
        val states = listOf(
            ExerciseProgrammingState.NotLoaded,
            ExerciseProgrammingState.Loading,
            ExerciseProgrammingState.Ready(records, programmingCoverage(records)),
            ExerciseProgrammingState.Error("safe terminal error")
        )

        states.forEach { expected ->
            assertEquals(expected, loadProgrammingState(FakeProgrammingCatalogue(expected)))
        }
    }

    @Test
    fun `terminal programming failure keeps exact canonical mains in fallback`() {
        val readiness = routineGenerationReadiness(
            canonicalReady = true,
            programmingState = ExerciseProgrammingState.Error("unavailable")
        )
        val expected = createLocalRoutine(input(), catalogue, selector)

        val degraded = createLocalRoutine(
            input(),
            catalogue,
            selector,
            readiness.programmingRecords
        )

        assertEquals(
            expected.days.map { day -> day.exercises.map { it.exerciseId } },
            degraded.days.map { day -> day.exercises.map { it.exerciseId } }
        )
        assertTrue(degraded.days.all { day ->
            day.generalWarmup == null &&
                day.warmupExercises.isEmpty() &&
                day.cooldownExercises.isEmpty() &&
                day.exercises.all { it.rampUpSets.isEmpty() }
        })
    }

    @Test
    fun `individual equipment choices toggle independently`() {
        assertEquals(
            listOf("Bodyweight", "Dumbbells", "Barbell and rack", "Resistance bands", "Full gym"),
            ROUTINE_EQUIPMENT_OPTIONS
        )
        val dumbbells = toggleRoutineEquipment(emptySet(), "Dumbbells")
        val combined = toggleRoutineEquipment(dumbbells, "Resistance bands")
        val bandsOnly = toggleRoutineEquipment(combined, "Dumbbells")

        assertEquals(setOf("Dumbbells", "Resistance bands"), combined)
        assertEquals(setOf("Resistance bands"), bandsOnly)
    }

    @Test
    fun `full gym and individual equipment obey exclusive preset rules`() {
        val individuals = setOf("Bodyweight", "Dumbbells")
        val fullGym = toggleRoutineEquipment(individuals, "Full gym")
        val individualAfterFullGym = toggleRoutineEquipment(fullGym, "Barbell and rack")

        assertEquals(setOf("Full gym"), fullGym)
        assertEquals(setOf("Barbell and rack"), individualAfterFullGym)
    }

    @Test
    fun `empty equipment selection prevents generation`() {
        val emptyInput = input(equipment = emptySet())

        assertEquals("Choose available equipment.", validateRoutineStep(6, emptyInput))
        assertThrows(IllegalArgumentException::class.java) {
            createLocalRoutine(emptyInput, catalogue, selector)
        }
    }

    @Test
    fun `all split choices preserve stable day and exercise ordering`() {
        val cases = listOf(
            "Full Body" to listOf("Full Body", "Full Body", "Full Body", "Full Body"),
            "Upper / Lower" to listOf("Upper Body", "Lower Body", "Upper Body", "Lower Body"),
            "Push / Pull / Legs" to listOf("Push", "Pull", "Legs", "Push"),
            "Body Part Split" to listOf("Chest & Triceps", "Back & Biceps", "Legs", "Shoulders & Core")
        )

        cases.forEach { (split, expectedDays) ->
            val first = createLocalRoutine(input(split = split, days = 4), catalogue, selector)
            val second = createLocalRoutine(input(split = split, days = 4), catalogue.reversed(), selector)

            assertEquals(split, expectedDays, first.days.map { it.title })
            assertEquals(
                split,
                first.days.map { day -> day.exercises.map { it.exerciseId } },
                second.days.map { day -> day.exercises.map { it.exerciseId } }
            )
        }
    }

    @Test
    fun `sets and reps rules remain unchanged`() {
        val cases = listOf(
            Triple("Beginner", "Strength", 3 to "4-6"),
            Triple("Advanced", "Strength", 5 to "4-6"),
            Triple("Intermediate", "Muscle Gain", 3 to "8-12"),
            Triple("Intermediate", "Fat Loss", 3 to "12-15"),
            Triple("Intermediate", "Endurance", 3 to "15-20")
        )

        cases.forEach { (level, goal, prescription) ->
            val routine = createLocalRoutine(input(level = level, goal = goal), catalogue, selector)
            assertTrue(
                "$level - $goal",
                routine.days.flatMap { it.exercises }.all {
                    it.sets == prescription.first && it.reps == prescription.second
                }
            )
        }
    }

    @Test
    fun `all supported splits levels and goals still generate four canonical exercises per day`() {
        listOf("Full Body", "Upper / Lower", "Push / Pull / Legs", "Body Part Split").forEach { split ->
            val routine = createLocalRoutine(input(split = split), catalogue, selector)
            assertTrue(split, routine.days.all { it.exercises.size == 4 && it.exercises.all { exercise -> exercise.exerciseId.isNotBlank() } })
        }
        listOf("Beginner", "Intermediate", "Advanced").forEach { level ->
            val routine = createLocalRoutine(input(level = level), catalogue, selector)
            assertTrue(level, routine.days.all { it.exercises.size == 4 })
        }
        listOf("Strength", "Muscle Gain", "Fat Loss", "Endurance", "General Fitness").forEach { goal ->
            val routine = createLocalRoutine(input(goal = goal), catalogue, selector)
            assertTrue(goal, routine.days.all { it.exercises.size == 4 })
        }
    }

    @Test
    fun `six-day body part split preserves the complete focus cycle`() {
        val routine = createLocalRoutine(
            input(split = "Body Part Split", days = 6),
            catalogue,
            selector
        )

        assertEquals(
            listOf("Chest & Triceps", "Back & Biceps", "Legs", "Shoulders & Core", "Full Body", "Conditioning"),
            routine.days.map { it.title }
        )
        assertTrue(routine.days.all { it.exercises.size == 4 })
    }

    @Test
    fun `regeneration keeps canonical order and creates a new nonblank plan ID`() {
        val first = createLocalRoutine(input(), catalogue, selector)
        val regenerated = createLocalRoutine(input(), catalogue, selector)

        assertTrue(first.planId.startsWith("build-routine-") && first.planId.isNotBlank())
        assertTrue(regenerated.planId.startsWith("build-routine-") && regenerated.planId.isNotBlank())
        assertNotEquals(first.planId, regenerated.planId)
        assertEquals(
            first.days.map { day -> day.exercises.map { it.exerciseId } },
            regenerated.days.map { day -> day.exercises.map { it.exerciseId } }
        )
    }

    @Test
    fun `editing back to equipment preserves the selected set and deterministic summary`() {
        val selected = setOf("Resistance bands", "Dumbbells")
        val builderInput = input(equipment = selected)
        val routine = createLocalRoutine(builderInput, catalogue, selector)
        val destination = previousRoutineDestination(currentStep = 6, showingResult = true)

        assertEquals(6, destination.step)
        assertEquals(selected, builderInput.equipment)
        assertTrue(routine.description.contains("using Dumbbells, Resistance bands,"))
        assertEquals("Dumbbells, Resistance bands", routineEquipmentSummary(selected))
    }

    @Test
    fun `Gson round trip and active day removal retain IDs snapshots and order`() {
        val routine = createLocalRoutine(input(days = 4), catalogue, selector)
        val activeAfterRemoval = routine.copy(days = routine.days.filterIndexed { index, _ -> index != 1 })
        val restored = Gson().fromJson(Gson().toJson(activeAfterRemoval), GeneratedRoutine::class.java)

        assertEquals(listOf("Day 1", "Day 3", "Day 4"), restored.days.map { it.dayName })
        assertEquals(
            activeAfterRemoval.days.map { day -> day.exercises.map { it.exerciseId } },
            restored.days.map { day -> day.exercises.map { it.exerciseId } }
        )
        assertTrue(restored.days.flatMap { it.exercises }.none { it.name.isBlank() || it.exerciseId.isBlank() })
        assertTrue(restored.days.flatMap { it.exercises }.none { it.sets == 0 && it.reps.isBlank() })
    }

    @Test
    fun backFromResultReturnsToEquipmentStep() {
        val destination = previousRoutineDestination(currentStep = 6, showingResult = true)

        assertEquals(6, destination.step)
        assertFalse(destination.showResult)
        assertFalse(destination.exitBuilder)
    }

    @Test
    fun backMovesOneStepUntilFirstStepExits() {
        assertEquals(3, previousRoutineDestination(currentStep = 4, showingResult = false).step)
        assertFalse(previousRoutineDestination(currentStep = 4, showingResult = false).exitBuilder)
        assertTrue(previousRoutineDestination(currentStep = 1, showingResult = false).exitBuilder)
    }

    private fun input(
        level: String = "Intermediate",
        goal: String = "Strength",
        split: String = "Full Body",
        equipment: Set<String> = setOf("Dumbbells"),
        days: Int = 2
    ) = RoutineBuilderInput(
        name = "QA Strength Routine",
        notes = "",
        sessionMinutes = 45,
        level = level,
        goal = goal,
        daysPerWeek = days,
        split = split,
        equipment = equipment
    )

    private fun canonicalCatalogue(): List<Exercise> = buildList {
        repeat(4) { index ->
            val number = index + 1
            add(exercise("lower-$number", "Lower $number", "upper legs", "glutes"))
            add(exercise("push-$number", "Push $number", "chest", "pectorals"))
            add(exercise("pull-$number", "Pull $number", "back", "lats"))
            add(exercise("core-$number", "Core $number", "waist", "abs"))
        }
        add(exercise("conditioning-1", "Conditioning 1", "cardio", "cardiovascular system"))
    }

    private fun exercise(
        id: String,
        name: String,
        bodyPart: String,
        target: String,
        equipment: String = "body weight"
    ) = Exercise(
        id = ExerciseId(id),
        name = name,
        aliases = emptyList(),
        category = if (bodyPart == "cardio") "cardio" else "strength",
        movementPattern = bodyPart,
        bodyPart = bodyPart,
        bodyTargets = listOf(bodyPart),
        primaryMuscles = listOf(target),
        secondaryMuscles = emptyList(),
        equipment = listOf(equipment),
        goals = emptyList(),
        experienceLevels = emptyList(),
        instructions = "Repeat this normal main movement for the desired repetitions.",
        safetyNote = ""
    )

    private fun programming(
        id: String,
        role: ExerciseProgrammingRole,
        regions: List<ExerciseProgrammingRegion>,
        repetitions: Int? = null,
        duration: Int? = null
    ) = ExerciseProgrammingMetadata(
        exerciseId = id,
        roles = listOf(role),
        programmingRegions = regions,
        joints = listOf(ExerciseProgrammingJoint.KNEE),
        movementPatterns = emptyList(),
        prescriptionMode = if (duration != null) {
            ExercisePrescriptionMode.DURATION_SECONDS
        } else if (repetitions != null) {
            ExercisePrescriptionMode.REPETITIONS
        } else {
            ExercisePrescriptionMode.FREE_TEXT
        },
        defaultRepetitions = repetitions,
        defaultDurationSeconds = duration,
        freeTextPrescription = if (duration == null && repetitions == null) "Working sets" else null,
        perSide = false,
        equipmentOverride = emptyList(),
        equipment = listOf("body weight"),
        duplicateFamilyKey = null,
        beginnerSuitable = true,
        autoSelectApproved = true,
        reviewStatus = ExerciseProgrammingReviewStatus.FITDESI_REVIEWED,
        neverAutoSelectReason = null
    )

    private fun programmingCoverage(
        records: List<ExerciseProgrammingMetadata>
    ) = ExerciseProgrammingCoverage(
        totalOverlayRecords = records.size,
        countByRole = emptyMap(),
        countByProgrammingRegion = emptyMap(),
        countByEquipment = emptyMap(),
        regionsWithNoReviewedPreparationOption = emptyList(),
        regionsWithNoReviewedCooldownOption = emptyList()
    )

    private class FakeCatalogue(exercises: List<Exercise>) : ExerciseCatalogue {
        private val ready = ExerciseCatalogueState.Ready(exercises)
        private val mutableState = MutableStateFlow<ExerciseCatalogueState>(ready)
        override val state: StateFlow<ExerciseCatalogueState> = mutableState

        override suspend fun load(): ExerciseCatalogueState = ready
        override suspend fun getAll(): List<Exercise> = ready.exercises
        override suspend fun getById(id: ExerciseId): Exercise? = ready.exercises.firstOrNull { it.id == id }
        override suspend fun getByExactName(name: String): List<Exercise> = ready.exercises.filter { it.name == name }
        override suspend fun getByAlias(alias: String): List<Exercise> = emptyList()
        override suspend fun search(query: String): List<Exercise> = emptyList()
        override suspend fun filter(filter: ExerciseFilter): List<Exercise> = emptyList()
    }

    private inner class FakeProgrammingCatalogue(
        private val loadResult: ExerciseProgrammingState
    ) : ExerciseProgrammingCatalogue {
        private val mutableState = MutableStateFlow<ExerciseProgrammingState>(ExerciseProgrammingState.NotLoaded)
        override val state: StateFlow<ExerciseProgrammingState> = mutableState
        var loadCount: Int = 0
            private set

        override suspend fun load(): ExerciseProgrammingState {
            loadCount += 1
            return loadResult.also { mutableState.value = it }
        }
        override suspend fun getAll(): List<ExerciseProgrammingMetadata> =
            (loadResult as? ExerciseProgrammingState.Ready)?.records.orEmpty()
        override suspend fun getByExerciseId(exerciseId: String): ExerciseProgrammingMetadata? =
            getAll().firstOrNull { it.exerciseId == exerciseId }
        override suspend fun getByRole(role: ExerciseProgrammingRole): List<ExerciseProgrammingMetadata> =
            getAll().filter { role in it.roles }
        override suspend fun getByProgrammingRegion(region: ExerciseProgrammingRegion): List<ExerciseProgrammingMetadata> =
            getAll().filter { region in it.programmingRegions }
        override suspend fun getByEquipment(equipment: String): List<ExerciseProgrammingMetadata> =
            getAll().filter { equipment in it.equipment }
        override suspend fun getByDuplicateFamily(familyKey: String): List<ExerciseProgrammingMetadata> =
            getAll().filter { it.duplicateFamilyKey == familyKey }
        override suspend fun coverage(): ExerciseProgrammingCoverage =
            (loadResult as? ExerciseProgrammingState.Ready)?.coverage ?: programmingCoverage(emptyList())
    }
}
