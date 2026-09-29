package com.example.exercise.programming

import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneratedExercise
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.WarmupIntensity
import com.example.exercise.Exercise
import com.example.exercise.ExerciseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicStructuredWorkoutComposerTest {
    private val composer = DeterministicStructuredWorkoutComposer()

    @Test
    fun `reversed source order produces identical separated phases with exact opaque IDs`() {
        val canonical = listOf(
            exercise("main-back", bodyPart = "back", muscle = "lats"),
            exercise("fd-exercise-chair-squat", bodyPart = "upper legs", muscle = "glutes"),
            exercise("2331", bodyPart = "cardio", muscle = "cardiovascular system"),
            exercise("3672", bodyPart = "cardio", muscle = "cardiovascular system"),
            exercise("0257", bodyPart = "lower legs", muscle = "calves", safety = "Move within a comfortable range."),
            exercise("0276", bodyPart = "waist", muscle = "abs"),
            exercise("1709", bodyPart = "upper legs", muscle = "glutes"),
            exercise("1710", bodyPart = "upper legs", muscle = "glutes")
        )
        val records = listOf(
            metadata("main-back", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), joints = listOf(ExerciseProgrammingJoint.SHOULDER)),
            metadata("fd-exercise-chair-squat", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.GLUTES), joints = listOf(ExerciseProgrammingJoint.HIP)),
            metadata("2331", role = ExerciseProgrammingRole.GENERAL_WARMUP, regions = listOf(ExerciseProgrammingRegion.FULL_BODY), joints = listOf(ExerciseProgrammingJoint.SHOULDER), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 300),
            metadata("3672", role = ExerciseProgrammingRole.GENERAL_WARMUP, regions = listOf(ExerciseProgrammingRegion.FULL_BODY), joints = listOf(ExerciseProgrammingJoint.ANKLE), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 300),
            metadata("0257", roles = listOf(ExerciseProgrammingRole.DYNAMIC_PREPARATION, ExerciseProgrammingRole.MOBILITY), regions = listOf(ExerciseProgrammingRegion.GLUTES), joints = listOf(ExerciseProgrammingJoint.HIP), repetitions = 10, perSide = true),
            metadata("0276", role = ExerciseProgrammingRole.ACTIVATION, regions = listOf(ExerciseProgrammingRegion.BACK), joints = listOf(ExerciseProgrammingJoint.SHOULDER), repetitions = 6),
            metadata("1709", role = ExerciseProgrammingRole.STATIC_COOLDOWN, regions = listOf(ExerciseProgrammingRegion.GLUTES), joints = listOf(ExerciseProgrammingJoint.HIP), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 20, family = "glute-stretch"),
            metadata("1710", role = ExerciseProgrammingRole.STATIC_COOLDOWN, regions = listOf(ExerciseProgrammingRegion.GLUTES), joints = listOf(ExerciseProgrammingJoint.HIP), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 20, family = " GLUTE-STRETCH ")
        )
        val mains = listOf(
            main("main-back", sets = 3, reps = "8-12", rest = 90),
            main("fd-exercise-chair-squat", sets = 3, reps = "8-12", rest = 90)
        )

        val first = compose(canonical, records, mains)
        val second = compose(canonical.reversed(), records.reversed(), mains)

        assertEquals(first, second)
        assertEquals("2331", first.generalWarmup?.canonicalExerciseId)
        assertEquals(listOf("0257", "0276"), first.preparationExercises.map { it.exerciseId })
        assertEquals(listOf("main-back", "fd-exercise-chair-squat"), first.mainExercises.map { it.exerciseId })
        assertEquals(listOf("1709"), first.cooldownExercises.map { it.exerciseId })
        assertEquals("10 reps per side", first.preparationExercises.first().reps)
        assertEquals("Move within a comfortable range.", first.preparationExercises.first().safetyNote)
        assertTrue((first.preparationExercises + first.mainExercises + first.cooldownExercises)
            .map { it.exerciseId }.plus(first.generalWarmup?.canonicalExerciseId).filterNotNull()
            .let { it.size == it.distinct().size })
    }

    @Test
    fun `general warmup prefers explicit compatible equipment and uses beginner intensity`() {
        val canonical = listOf(
            exercise("main", bodyPart = "upper legs", muscle = "quadriceps"),
            exercise("bodyweight-warmup", bodyPart = "cardio", muscle = "cardiovascular system"),
            exercise("dumbbell-warmup", equipment = "dumbbell", bodyPart = "cardio", muscle = "cardiovascular system"),
            exercise("invalid-warmup", equipment = "dumbbell", bodyPart = "cardio", muscle = "cardiovascular system")
        )
        val records = listOf(
            metadata("bodyweight-warmup", role = ExerciseProgrammingRole.GENERAL_WARMUP, regions = listOf(ExerciseProgrammingRegion.FULL_BODY), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 180),
            metadata("dumbbell-warmup", role = ExerciseProgrammingRole.GENERAL_WARMUP, regions = listOf(ExerciseProgrammingRegion.FULL_BODY), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 240, equipment = listOf("dumbbell")),
            metadata("invalid-warmup", role = ExerciseProgrammingRole.GENERAL_WARMUP, regions = listOf(ExerciseProgrammingRegion.FULL_BODY), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 0, equipment = listOf("dumbbell"))
        )

        val selected = compose(canonical, records, listOf(main("main")), level = "Beginner", equipment = setOf("Dumbbells"))
            .generalWarmup

        assertEquals("dumbbell-warmup", selected?.canonicalExerciseId)
        assertEquals(240, selected?.durationSeconds)
        assertEquals("dumbbell", selected?.equipment)
        assertEquals(WarmupIntensity.EASY, selected?.intensityCue)
        assertEquals(
            WarmupIntensity.EASY_TO_MODERATE,
            compose(canonical, records, listOf(main("main")), level = "Intermediate", equipment = setOf("Dumbbells"))
                .generalWarmup
                ?.intensityCue
        )
        assertNull(compose(canonical, records, listOf(main("main")), level = "Beginner", equipment = setOf("Kettlebells")).generalWarmup)
    }

    @Test
    fun `preparation uses fixed role order with at most one exercise per role`() {
        val canonical = listOf(
            exercise("main", bodyPart = "waist", muscle = "abs"),
            exercise("dynamic-a", bodyPart = "waist", muscle = "abs"),
            exercise("dynamic-b", bodyPart = "waist", muscle = "abs"),
            exercise("mobility", bodyPart = "waist", muscle = "abs"),
            exercise("activation", bodyPart = "waist", muscle = "abs")
        )
        val records = listOf(
            metadata("dynamic-b", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE)),
            metadata("activation", role = ExerciseProgrammingRole.ACTIVATION, regions = listOf(ExerciseProgrammingRegion.CORE)),
            metadata("mobility", role = ExerciseProgrammingRole.MOBILITY, regions = listOf(ExerciseProgrammingRegion.CORE)),
            metadata("dynamic-a", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE))
        )

        val result = compose(canonical, records, listOf(main("main")))

        assertEquals(listOf("dynamic-a", "mobility", "activation"), result.preparationExercises.map { it.exerciseId })
    }

    @Test
    fun `static cooldown never enters preparation and preparation never enters main`() {
        val canonical = listOf(
            exercise("main", bodyPart = "upper legs", muscle = "quadriceps"),
            exercise("static", bodyPart = "upper legs", muscle = "quadriceps"),
            exercise("dynamic", bodyPart = "upper legs", muscle = "quadriceps")
        )
        val records = listOf(
            metadata("static", roles = listOf(ExerciseProgrammingRole.DYNAMIC_PREPARATION, ExerciseProgrammingRole.STATIC_COOLDOWN), regions = listOf(ExerciseProgrammingRegion.QUADRICEPS), repetitions = 10),
            metadata("dynamic", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.QUADRICEPS), repetitions = 10)
        )

        val result = compose(canonical, records, listOf(main("main")))

        assertEquals(listOf("dynamic"), result.preparationExercises.map { it.exerciseId })
        assertEquals(listOf("main"), result.mainExercises.map { it.exerciseId })
        assertEquals(listOf("static"), result.cooldownExercises.map { it.exerciseId })
    }

    @Test
    fun `duplicate family and incompatible equipment are suppressed`() {
        val canonical = listOf(
            exercise("main", bodyPart = "upper legs", muscle = "glutes"),
            exercise("dynamic", equipment = "body weight", bodyPart = "upper legs", muscle = "glutes"),
            exercise("activation-same-family", equipment = "body weight", bodyPart = "upper legs", muscle = "glutes"),
            exercise("activation-dumbbell", equipment = "dumbbell", bodyPart = "upper legs", muscle = "glutes")
        )
        val records = listOf(
            metadata("dynamic", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.GLUTES), repetitions = 8, family = "family"),
            metadata("activation-same-family", role = ExerciseProgrammingRole.ACTIVATION, regions = listOf(ExerciseProgrammingRegion.GLUTES), repetitions = 8, family = "FAMILY"),
            metadata("activation-dumbbell", role = ExerciseProgrammingRole.ACTIVATION, regions = listOf(ExerciseProgrammingRegion.GLUTES), repetitions = 8, equipment = listOf("dumbbell"))
        )

        val result = compose(canonical, records, listOf(main("main")), equipment = setOf("Bodyweight"))

        assertEquals(listOf("dynamic"), result.preparationExercises.map { it.exerciseId })
    }

    @Test
    fun `region and joint relevance select only meaningful cooldown coverage`() {
        val canonical = listOf(
            exercise("main", bodyPart = "back", muscle = "lats"),
            exercise("joint-only", bodyPart = "chest", muscle = "pectorals"),
            exercise("irrelevant", bodyPart = "lower legs", muscle = "calves")
        )
        val records = listOf(
            metadata("main", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), joints = listOf(ExerciseProgrammingJoint.SHOULDER)),
            metadata("joint-only", role = ExerciseProgrammingRole.STATIC_COOLDOWN, regions = listOf(ExerciseProgrammingRegion.CHEST), joints = listOf(ExerciseProgrammingJoint.SHOULDER), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 20),
            metadata("irrelevant", role = ExerciseProgrammingRole.STATIC_COOLDOWN, regions = listOf(ExerciseProgrammingRegion.CALVES), joints = listOf(ExerciseProgrammingJoint.ANKLE), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 20)
        )

        val result = compose(canonical, records, listOf(main("main")))

        assertEquals(listOf("joint-only"), result.cooldownExercises.map { it.exerciseId })
    }

    @Test
    fun `activity prescriptions support repetitions duration free text and per side`() {
        val cases = listOf(
            metadata("reps", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE), repetitions = 6) to ActivityPrescriptionMode.REPETITIONS,
            metadata("duration", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE), mode = ExercisePrescriptionMode.DURATION_SECONDS, duration = 30) to ActivityPrescriptionMode.DURATION_SECONDS,
            metadata("text", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE), mode = ExercisePrescriptionMode.FREE_TEXT, freeText = "Move slowly for one controlled round.") to ActivityPrescriptionMode.FREE_TEXT,
            metadata("per-side", role = ExerciseProgrammingRole.DYNAMIC_PREPARATION, regions = listOf(ExerciseProgrammingRegion.CORE), repetitions = 8, perSide = true) to ActivityPrescriptionMode.REPETITIONS
        )

        cases.forEach { (record, expectedMode) ->
            val canonical = listOf(
                exercise("main", bodyPart = "waist", muscle = "abs"),
                exercise(record.exerciseId, bodyPart = "waist", muscle = "abs", safety = if (record.exerciseId == "reps") "" else "Safety")
            )
            val selected = compose(canonical, listOf(record), listOf(main("main"))).preparationExercises.single()
            assertEquals(expectedMode, selected.activityPrescription?.mode)
            assertTrue(selected.reps.isNotBlank())
            if (record.perSide) assertTrue(selected.reps.endsWith("per side"))
            if (record.exerciseId == "reps") assertEquals("", selected.safetyNote)
        }
    }

    @Test
    fun `eligible main gets deterministic ramp ups while 0286 always gets none`() {
        val canonical = listOf(
            exercise("0027", equipment = "barbell", bodyPart = "back", muscle = "lats"),
            exercise("0286", equipment = "dumbbell", bodyPart = "shoulders", muscle = "deltoids")
        )
        val records = listOf(
            metadata("0027", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), mode = ExercisePrescriptionMode.FREE_TEXT, freeText = "Working sets", equipment = listOf("barbell")),
            metadata("0286", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.SHOULDERS), mode = ExercisePrescriptionMode.FREE_TEXT, freeText = "Working sets", equipment = listOf("dumbbell"))
        )
        val mains = listOf(main("0027", sets = 5, reps = "4-6", rest = 150), main("0286", sets = 5, reps = "4-6", rest = 150))

        val result = compose(canonical, records, mains, goal = "Strength", level = "Advanced", equipment = setOf("Full gym"))

        assertEquals(listOf(RampUpLoadCue.VERY_LIGHT, RampUpLoadCue.LIGHT, RampUpLoadCue.MODERATE), result.mainExercises[0].rampUpSets.map { it.loadCue })
        assertEquals(5, result.mainExercises[0].sets)
        assertEquals("4-6", result.mainExercises[0].reps)
        assertEquals(150, result.mainExercises[0].restSeconds)
        assertTrue(result.mainExercises[1].rampUpSets.isEmpty())
    }

    @Test
    fun `ramp ups require every approval resistance role and working set gate`() {
        val ids = listOf("eligible", "wrong-role", "unapproved", "bodyweight", "one-set", "never")
        val canonical = ids.map { id -> exercise(id, equipment = if (id == "bodyweight") "body weight" else "barbell", bodyPart = "back", muscle = "lats") }
        val records = listOf(
            metadata("eligible", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), equipment = listOf("barbell")),
            metadata("wrong-role", role = ExerciseProgrammingRole.ACTIVATION, regions = listOf(ExerciseProgrammingRegion.BACK), equipment = listOf("barbell")),
            metadata("unapproved", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), equipment = listOf("barbell"), approved = false),
            metadata("bodyweight", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK)),
            metadata("one-set", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK), equipment = listOf("barbell")),
            metadata("never", roles = listOf(ExerciseProgrammingRole.MAIN_STRENGTH, ExerciseProgrammingRole.NEVER_AUTO_SELECT), regions = listOf(ExerciseProgrammingRegion.BACK), equipment = listOf("barbell"))
        )
        val mains = ids.map { id -> main(id, sets = if (id == "one-set") 1 else 3) }

        val result = compose(canonical, records, mains, equipment = setOf("Full gym"))

        assertEquals(listOf("eligible"), result.mainExercises.filter { it.rampUpSets.isNotEmpty() }.map { it.exerciseId })
    }

    @Test
    fun `available programming copies exact canonical main safety notes and preserves blanks`() {
        val canonical = listOf(
            exercise("safe", bodyPart = "back", muscle = "lats", safety = "Keep the spine controlled."),
            exercise("blank", bodyPart = "back", muscle = "lats")
        )
        val records = listOf(
            metadata("safe", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK)),
            metadata("blank", role = ExerciseProgrammingRole.MAIN_STRENGTH, regions = listOf(ExerciseProgrammingRegion.BACK))
        )

        val result = compose(canonical, records, listOf(main("safe"), main("blank")))

        assertEquals("Keep the spine controlled.", result.mainExercises[0].safetyNote)
        assertEquals("", result.mainExercises[1].safetyNote)
    }

    @Test
    fun `empty programming metadata preserves main values and exact leading zero ID`() {
        val canonical = listOf(exercise("0257", bodyPart = "lower legs", muscle = "calves"))
        val original = main("0257", sets = 4, reps = "12-15", rest = 75).copy(
            instructions = "Exact instructions",
            rampUpSets = listOf(RampUpSetPrescription(1, RampUpLoadCue.LIGHT, 5, 60)),
            safetyNote = "Existing note"
        )

        val result = compose(canonical, emptyList(), listOf(original))

        assertNull(result.generalWarmup)
        assertTrue(result.preparationExercises.isEmpty())
        assertTrue(result.cooldownExercises.isEmpty())
        assertEquals(original, result.mainExercises.single())
        assertEquals("0257", result.mainExercises.single().exerciseId)
    }

    private fun compose(
        canonical: List<Exercise>,
        records: List<ExerciseProgrammingMetadata>,
        mains: List<GeneratedExercise>,
        goal: String = "General Fitness",
        level: String = "Intermediate",
        equipment: Set<String> = setOf("Bodyweight")
    ) = composer.compose(
        StructuredWorkoutCompositionRequest(
            dayFocus = "Full Body",
            goal = goal,
            experienceLevel = level,
            availableEquipment = equipment,
            mainExercises = mains,
            canonicalExercises = canonical,
            programmingRecords = records
        )
    )

    private fun main(id: String, sets: Int = 3, reps: String = "8-12", rest: Int = 60) = GeneratedExercise(
        name = "Main $id",
        sets = sets,
        reps = reps,
        targetMuscle = "Target",
        instructions = "Main instructions $id",
        restSeconds = rest,
        exerciseId = id
    )

    private fun exercise(
        id: String,
        equipment: String = "body weight",
        bodyPart: String,
        muscle: String,
        safety: String = ""
    ) = Exercise(
        id = ExerciseId(id),
        name = "Canonical $id",
        aliases = emptyList(),
        category = "strength",
        movementPattern = bodyPart,
        bodyPart = bodyPart,
        bodyTargets = listOf(bodyPart),
        primaryMuscles = listOf(muscle),
        secondaryMuscles = emptyList(),
        equipment = listOf(equipment),
        goals = emptyList(),
        experienceLevels = emptyList(),
        instructions = "Canonical instructions $id",
        safetyNote = safety
    )

    private fun metadata(
        id: String,
        role: ExerciseProgrammingRole? = null,
        roles: List<ExerciseProgrammingRole> = role?.let { listOf(it) }.orEmpty(),
        regions: List<ExerciseProgrammingRegion>,
        joints: List<ExerciseProgrammingJoint> = emptyList(),
        mode: ExercisePrescriptionMode = ExercisePrescriptionMode.REPETITIONS,
        repetitions: Int? = if (mode == ExercisePrescriptionMode.REPETITIONS) 10 else null,
        duration: Int? = null,
        freeText: String? = null,
        perSide: Boolean = false,
        equipment: List<String> = listOf("body weight"),
        family: String? = null,
        beginnerSuitable: Boolean = true,
        approved: Boolean = true
    ) = ExerciseProgrammingMetadata(
        exerciseId = id,
        roles = roles,
        programmingRegions = regions,
        joints = joints,
        movementPatterns = emptyList(),
        prescriptionMode = mode,
        defaultRepetitions = repetitions,
        defaultDurationSeconds = duration,
        freeTextPrescription = freeText,
        perSide = perSide,
        equipmentOverride = emptyList(),
        equipment = equipment,
        duplicateFamilyKey = family,
        beginnerSuitable = beginnerSuitable,
        autoSelectApproved = approved,
        reviewStatus = ExerciseProgrammingReviewStatus.FITDESI_REVIEWED,
        neverAutoSelectReason = null
    )
}
