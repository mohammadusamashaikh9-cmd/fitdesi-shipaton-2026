package com.example.exercise.programming

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.CanonicalRoutineSelectionRequest
import com.example.exercise.CanonicalRoutineSelectionResult
import com.example.exercise.ExerciseId
import com.example.exercise.ExerciseRepository
import com.example.exercise.ExerciseRepositoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExerciseProgrammingRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `approved overlay contains exactly 86 unique canonical IDs`() = runBlocking {
        val canonical = ExerciseRepository.fromAssets(context)
        val repository = repository(canonical = canonical)
        val records = repository.getAll()

        assertEquals(86, records.size)
        assertEquals(86, records.map(ExerciseProgrammingMetadata::exerciseId).toSet().size)
        assertTrue(records.all { canonical.getById(ExerciseId(it.exerciseId)) != null })
        assertEquals("0257", repository.getByExerciseId("0257")?.exerciseId)
        assertNull(repository.getByExerciseId("not-a-canonical-id"))
        assertTrue(EXCLUDED_CANDIDATE_IDS.none { repository.getByExerciseId(it) != null })
        assertTrue(APPROVED_B3B_IDS.all { repository.getByExerciseId(it) != null })
        assertNull(repository.getByExerciseId("fd-exercise-overhead-triceps-stretch"))
        assertNull(repository.getByExerciseId("fd-exercise-supine-hamstring-stretch"))
    }

    @Test
    fun `role counts include reviewed activation and strength expansion`() = runBlocking {
        val coverage = repository().coverage()

        assertEquals(2, coverage.countByRole.getValue(ExerciseProgrammingRole.GENERAL_WARMUP))
        assertEquals(7, coverage.countByRole.getValue(ExerciseProgrammingRole.DYNAMIC_PREPARATION))
        assertEquals(6, coverage.countByRole.getValue(ExerciseProgrammingRole.MOBILITY))
        assertEquals(3, coverage.countByRole.getValue(ExerciseProgrammingRole.ACTIVATION))
        assertEquals(47, coverage.countByRole.getValue(ExerciseProgrammingRole.MAIN_STRENGTH))
        assertEquals(2, coverage.countByRole.getValue(ExerciseProgrammingRole.CONDITIONING))
        assertEquals(16, coverage.countByRole.getValue(ExerciseProgrammingRole.NEVER_AUTO_SELECT))
        assertEquals(20, coverage.countByRole.getValue(ExerciseProgrammingRole.STATIC_COOLDOWN))
        assertEquals(86, coverage.totalOverlayRecords)

        assertEquals(
            listOf(
                ExerciseProgrammingRegion.TRICEPS,
                ExerciseProgrammingRegion.LATS,
                ExerciseProgrammingRegion.BICEPS,
                ExerciseProgrammingRegion.FOREARMS,
                ExerciseProgrammingRegion.FULL_BODY
            ),
            coverage.regionsWithNoReviewedPreparationOption
        )
        assertEquals(
            listOf(
                ExerciseProgrammingRegion.CORE,
                ExerciseProgrammingRegion.ANKLES,
                ExerciseProgrammingRegion.FULL_BODY
            ),
            coverage.regionsWithNoReviewedCooldownOption
        )
    }

    @Test
    fun `new records preserve exact reviewed roles regions and joints`() = runBlocking {
        val repository = repository()

        EXPECTED_NEW_RECORDS.forEach { (exerciseId, expected) ->
            val record = repository.getByExerciseId(exerciseId)

            assertNotNull(exerciseId, record)
            assertEquals(exerciseId, expected.roles, record?.roles?.toSet())
            assertEquals(exerciseId, expected.regions, record?.programmingRegions?.toSet())
            assertEquals(exerciseId, expected.joints, record?.joints?.toSet())
            assertEquals(exerciseId, ExerciseProgrammingReviewStatus.FITDESI_REVIEWED, record?.reviewStatus)
            assertTrue(exerciseId, record?.equipmentOverride?.isEmpty() == true)
        }
    }

    @Test
    fun `activation prescriptions and canonical equipment are exact`() = runBlocking {
        val deadBug = repository().getByExerciseId("0276")
        val bandHipLift = repository().getByExerciseId("1408")

        assertEquals(ExercisePrescriptionMode.REPETITIONS, deadBug?.prescriptionMode)
        assertEquals(6, deadBug?.defaultRepetitions)
        assertTrue(deadBug?.perSide == true)
        assertFalse(deadBug?.beginnerSuitable == true)
        assertTrue(deadBug?.autoSelectApproved == true)
        assertNull(deadBug?.defaultDurationSeconds)
        assertNull(deadBug?.freeTextPrescription)

        assertEquals(ExercisePrescriptionMode.REPETITIONS, bandHipLift?.prescriptionMode)
        assertEquals(10, bandHipLift?.defaultRepetitions)
        assertFalse(bandHipLift?.perSide == true)
        assertTrue(bandHipLift?.beginnerSuitable == true)
        assertTrue(bandHipLift?.autoSelectApproved == true)
        assertEquals(listOf("band"), bandHipLift?.equipment)
        assertNull(bandHipLift?.defaultDurationSeconds)
        assertNull(bandHipLift?.freeTextPrescription)
    }

    @Test
    fun `main strength prescriptions and auto selection policy are exact`() = runBlocking {
        val strengthRecords = repository().getByRole(ExerciseProgrammingRole.MAIN_STRENGTH)

        assertEquals(EXPECTED_MAIN_STRENGTH_IDS, strengthRecords.map { it.exerciseId })
        assertTrue(strengthRecords.all { ExerciseProgrammingRole.MAIN_STRENGTH in it.roles })
        assertTrue(strengthRecords.all { it.prescriptionMode == ExercisePrescriptionMode.FREE_TEXT })
        assertTrue(strengthRecords.all { it.freeTextPrescription == MAIN_STRENGTH_PRESCRIPTION })
        assertTrue(strengthRecords.all { it.defaultRepetitions == null })
        assertTrue(strengthRecords.all { it.defaultDurationSeconds == null })
        assertTrue(strengthRecords.all { !it.perSide })
        assertTrue(strengthRecords.none { it.beginnerSuitable })
        assertEquals(32, strengthRecords.count { it.autoSelectApproved })

        val alternateSidePress = strengthRecords.single { it.exerciseId == "0286" }
        assertFalse(alternateSidePress.autoSelectApproved)
        assertEquals(NEVER_AUTO_SELECT_REASON, alternateSidePress.neverAutoSelectReason)
        assertTrue(strengthRecords.filterNot {
            it.exerciseId == "0286" || it.exerciseId in SUPPORT_EQUIPMENT_CONFLICT_IDS
        }.all { it.autoSelectApproved })
        assertTrue(strengthRecords.filterNot {
            it.exerciseId == "0286" || it.exerciseId in SUPPORT_EQUIPMENT_CONFLICT_IDS
        }.all {
            it.neverAutoSelectReason == null
        })
    }

    @Test
    fun `unrepresented support equipment prevents automatic main selection`() = runBlocking {
        val repository = repository()

        SUPPORT_EQUIPMENT_CONFLICT_IDS.forEach { exerciseId ->
            val record = repository.getByExerciseId(exerciseId)
            assertNotNull(exerciseId, record)
            assertTrue(exerciseId, ExerciseProgrammingRole.MAIN_STRENGTH in record!!.roles)
            assertTrue(exerciseId, ExerciseProgrammingRole.NEVER_AUTO_SELECT in record.roles)
            assertFalse(exerciseId, record.autoSelectApproved)
            assertFalse(exerciseId, record.neverAutoSelectReason.isNullOrBlank())
        }
    }

    @Test
    fun `asset backed capability matrix distinguishes supported and unsupported configurations`() = runBlocking {
        val canonical = ExerciseRepository.fromAssets(context)
        val exercises = canonical.getAll()
        val programming = repository(canonical = canonical).getAll()
        val programmingById = programming.associateBy(ExerciseProgrammingMetadata::exerciseId)
        val selector = CanonicalRoutineExerciseSelector(canonical)
        val equipmentChoices = listOf("Bodyweight", "Dumbbells", "Resistance bands", "Full gym")
        val experienceLevels = listOf("Beginner", "Intermediate", "Advanced")
        val scenarios = listOf(
            "Full Body" to "Full Body",
            "Upper / Lower" to "Upper Body",
            "Lower" to "Lower Body",
            "Push" to "Push",
            "Pull" to "Pull",
            "Legs" to "Legs",
            "Push / Pull / Legs" to "Push"
        )
        val failures = mutableListOf<String>()
        val dumbbellRelevance = mutableListOf<Int>()
        val bandRelevance = mutableListOf<Int>()
        val coverageFailures = mutableListOf<String>()
        var scenarioCount = 0
        var supportedCount = 0
        var unsupportedCount = 0
        var coverageFailureCount = 0

        equipmentChoices.forEach { equipment ->
            experienceLevels.forEach { experience ->
                scenarios.forEach scenario@{ (split, focus) ->
                    scenarioCount++
                    val request = CanonicalRoutineSelectionRequest(
                        equipment = setOf(equipment),
                        goal = "General Fitness",
                        experienceLevel = experience,
                        split = split,
                        dayFocus = focus
                    )
                    val forwardResult = selector.selectDayResult(exercises, request, programming)
                    val reversedResult = selector.selectDayResult(exercises.reversed(), request, programming.reversed())
                    if (forwardResult::class != reversedResult::class) {
                        failures += "$equipment/$experience/$split/$focus changed capability classification"
                        return@scenario
                    }
                    if (forwardResult is CanonicalRoutineSelectionResult.Unsupported) {
                        unsupportedCount++
                        val expectedUnsupported = equipment in setOf("Bodyweight", "Resistance bands") &&
                            split in setOf("Full Body", "Upper / Lower", "Pull", "Push / Pull / Legs")
                        if (!expectedUnsupported) {
                            failures += "$equipment/$experience/$split/$focus unexpectedly unsupported"
                        }
                        return@scenario
                    }
                    if (forwardResult !is CanonicalRoutineSelectionResult.Supported ||
                        reversedResult !is CanonicalRoutineSelectionResult.Supported
                    ) {
                        coverageFailureCount++
                        coverageFailures += "$equipment/$experience/$split/$focus"
                        return@scenario
                    }
                    supportedCount++
                    val forward = forwardResult.exercises
                    val reversed = reversedResult.exercises
                    if (forward.size != 4 || forward.map { it.id.value } != reversed.map { it.id.value }) {
                        failures += "$equipment/$experience/$split/$focus=${forward.map { it.id.value }}"
                    }
                    if (
                        equipment == "Bodyweight" && experience == "Beginner" &&
                        forward.any { it.id.value in BEGINNER_REJECTED_PHONE_IDS }
                    ) {
                        failures += "$equipment/$experience/$focus contains rejected phone-QA exercise"
                    }
                    val selectedEquipment = when (equipment) {
                        "Dumbbells" -> "dumbbell"
                        "Resistance bands" -> "band"
                        else -> null
                    }
                    if (selectedEquipment != null) {
                        val selectedEquipmentMains = forward.count { exercise ->
                            (programmingById[exercise.id.value]?.equipment ?: exercise.equipment)
                                .any { it.equals(selectedEquipment, ignoreCase = true) }
                        }
                        when (equipment) {
                            "Dumbbells" -> dumbbellRelevance += selectedEquipmentMains
                            "Resistance bands" -> bandRelevance += selectedEquipmentMains
                        }
                    }
                    if (forward.any {
                        it.id.value in SUPPORT_EQUIPMENT_CONFLICT_IDS ||
                            it.id.value in FINAL_SUPPORT_TRUTH_EXCLUSION_IDS
                    }) {
                        failures += "$equipment/$experience/$focus contains a support-equipment conflict"
                    }
                    if (
                        experience == "Beginner" &&
                        forward.any { programmingById[it.id.value]?.beginnerSuitable == false }
                    ) {
                        failures += "$equipment/$experience/$focus contains reviewed Beginner-unsuitable main"
                    }
                }
            }
        }

        assertEquals(84, scenarioCount)
        assertEquals(51, supportedCount)
        assertEquals(24, unsupportedCount)
        assertEquals(9, coverageFailureCount)
        assertEquals(
            setOf(
                "Bodyweight/Beginner/Lower/Lower Body",
                "Bodyweight/Beginner/Legs/Legs",
                "Bodyweight/Intermediate/Lower/Lower Body",
                "Bodyweight/Intermediate/Legs/Legs",
                "Bodyweight/Advanced/Lower/Lower Body",
                "Bodyweight/Advanced/Legs/Legs",
                "Dumbbells/Beginner/Full Body/Full Body",
                "Dumbbells/Beginner/Lower/Lower Body",
                "Dumbbells/Beginner/Legs/Legs"
            ),
            coverageFailures.toSet()
        )
        assertEquals(emptyList<String>(), failures)
        assertEquals(2, dumbbellRelevance.minOrNull())
        assertEquals(2, bandRelevance.minOrNull())
    }

    @Test
    fun `phone QA records have deliberate reviewed selection policy`() = runBlocking {
        val repository = repository()

        assertEquals(listOf(ExerciseProgrammingRole.NEVER_AUTO_SELECT), repository.getByExerciseId("0001")?.roles)
        assertEquals(listOf(ExerciseProgrammingRole.ACTIVATION), repository.getByExerciseId("0020")?.roles)
        assertEquals(listOf(ExerciseProgrammingRole.MOBILITY), repository.getByExerciseId("3212")?.roles)
        assertEquals(
            listOf(ExerciseProgrammingRole.DYNAMIC_PREPARATION),
            repository.getByExerciseId("3214")?.roles
        )
        assertEquals(listOf(ExerciseProgrammingRole.CONDITIONING), repository.getByExerciseId("1473")?.roles)
        assertEquals(listOf(ExerciseProgrammingRole.CONDITIONING), repository.getByExerciseId("3543")?.roles)
        assertEquals(listOf(ExerciseProgrammingRole.NEVER_AUTO_SELECT), repository.getByExerciseId("3639")?.roles)
        assertTrue(listOf("0129", "0137", "1399", "1770", "3019", "3293").all {
            repository.getByExerciseId(it)?.beginnerSuitable == false
        })
        assertEquals(listOf("dumbbell"), repository.getByExerciseId("1770")?.equipment)
        assertTrue(SUPPORT_EQUIPMENT_CONFLICT_IDS.all {
            ExerciseProgrammingRole.NEVER_AUTO_SELECT in repository.getByExerciseId(it)!!.roles
        })
    }

    @Test
    fun `new duplicate families are exact and existing family is unchanged`() = runBlocking {
        val repository = repository()

        assertEquals("barbell-full-squat", repository.getByExerciseId("0043")?.duplicateFamilyKey)
        assertEquals(
            listOf("0043"),
            repository.getByDuplicateFamily("barbell-full-squat").map { it.exerciseId }
        )
        assertEquals("cable-standard-lat-pulldown", repository.getByExerciseId("0198")?.duplicateFamilyKey)
        assertEquals(
            listOf("0198"),
            repository.getByDuplicateFamily("cable-standard-lat-pulldown").map { it.exerciseId }
        )
        assertEquals(
            listOf("1709", "1710"),
            repository.getByDuplicateFamily("lying-glute-piriformis-stretch").map { it.exerciseId }
        )
        assertTrue(
            EXPECTED_NEW_RECORDS.keys
                .filterNot { it in setOf("0043", "0198", "3543", "3639") }
                .all { repository.getByExerciseId(it)?.duplicateFamilyKey == null }
        )
    }

    @Test
    fun `near duplicate glute stretches share one deterministic family`() = runBlocking {
        val repository = repository()
        val first = repository.getByExerciseId("1709")
        val second = repository.getByExerciseId("1710")

        assertEquals("lying-glute-piriformis-stretch", first?.duplicateFamilyKey)
        assertEquals(first?.duplicateFamilyKey, second?.duplicateFamilyKey)
        assertEquals(
            listOf("1709", "1710"),
            repository.getByDuplicateFamily("  LYING-GLUTE-PIRIFORMIS-STRETCH  ")
                .map(ExerciseProgrammingMetadata::exerciseId)
        )
    }

    @Test
    fun `indexes are deterministic exact and immutable`() = runBlocking {
        val repository = repository()
        val expectedCooldownIds = listOf(
            "0643", "1259", "1271", "1377", "1378", "1405", "1407", "1494", "1512",
            "1548", "1576", "1708", "1709", "1710", "1712", "1713", "1714",
            "fd-exercise-childs-pose-lat-back-stretch", "fd-exercise-seated-biceps-stretch",
            "fd-exercise-wrist-flexor-extensor-stretch"
        )
        val first = repository.getByRole(ExerciseProgrammingRole.STATIC_COOLDOWN)
        val second = repository.getByRole(ExerciseProgrammingRole.STATIC_COOLDOWN)

        assertEquals(expectedCooldownIds, first.map(ExerciseProgrammingMetadata::exerciseId))
        assertEquals(listOf("0020", "0276", "1408"), repository.getByRole(ExerciseProgrammingRole.ACTIVATION)
            .map(ExerciseProgrammingMetadata::exerciseId))
        assertEquals(EXPECTED_MAIN_STRENGTH_IDS, repository.getByRole(ExerciseProgrammingRole.MAIN_STRENGTH)
            .map(ExerciseProgrammingMetadata::exerciseId))
        assertEquals(first, second)
        assertSame(first, second)
        assertTrue(repository.getByEquipment(" BODY   WEIGHT ").isNotEmpty())
        assertEquals(listOf("1548"), repository.getByEquipment("chair").map { it.exerciseId })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first as MutableList<ExerciseProgrammingMetadata>).clear()
        }
        Unit
    }

    @Test
    fun `unlisted canonical exercises never gain roles from names or instructions`() = runBlocking {
        val canonical = ExerciseRepository.fromAssets(context)
        val repository = repository()

        assertNotNull(canonical.getById(ExerciseId("0085")))
        assertNotNull(canonical.getById(ExerciseId("1272")))
        assertNull(repository.getByExerciseId("0085")) // Romanian deadlift instructions mention stretch.
        assertNull(repository.getByExerciseId("1272")) // Exact canonical name contains "stretch".
        assertFalse(repository.getAll().any { it.exerciseId == "fd-exercise-chair-squat" })
    }

    @Test
    fun `duplicate unknown and blank IDs fail with one safe message`() = runBlocking {
        assertSafeFailure(pack(validRecord("3672"), validRecord("3672")))
        assertSafeFailure(pack(validRecord("not-a-canonical-id")))
        assertSafeFailure(pack(validRecord("")))
    }

    @Test
    fun `invalid role values fail with one safe message`() = runBlocking {
        assertSafeFailure(pack(validRecord("3672", roles = "[\"NOT_A_ROLE\"]")))
        assertSafeFailure(pack(validRecord("3672", roles = "[]")))
        assertSafeFailure(pack(validRecord("3672", roles = "[\"NEVER_AUTO_SELECT\"]")))
    }

    @Test
    fun `invalid prescriptions fail with one safe message`() = runBlocking {
        assertSafeFailure(
            pack(
                validRecord(
                    "3672",
                    prescriptionMode = "DURATION_SECONDS",
                    prescriptionFields = "\"defaultDurationSeconds\": 0"
                )
            )
        )
        assertSafeFailure(
            pack(
                validRecord(
                    "3672",
                    prescriptionMode = "REPETITIONS",
                    prescriptionFields = "\"defaultRepetitions\": 10, \"defaultDurationSeconds\": 20"
                )
            )
        )
        assertSafeFailure(
            pack(
                validRecord(
                    "3672",
                    prescriptionMode = "FREE_TEXT",
                    prescriptionFields = "\"freeTextPrescription\": \"\""
                )
            )
        )
    }

    @Test
    fun `successful repository loads and parses its asset once`() = runBlocking {
        val text = assetText()
        var reads = 0
        val repository = ExerciseProgrammingRepository(
            source = {
                reads += 1
                text
            },
            canonicalCatalogue = ExerciseRepository.fromAssets(context)
        )

        val first = repository.getAll()
        val second = repository.getAll()
        repository.load()
        repository.coverage()

        assertEquals(1, reads)
        assertSame(first, second)
    }

    @Test
    fun `failed repository load also reads its asset once`() = runBlocking {
        var reads = 0
        val repository = ExerciseProgrammingRepository(
            source = {
                reads += 1
                "not-json"
            },
            canonicalCatalogue = ExerciseRepository.fromAssets(context)
        )

        val first = repository.load()
        val second = repository.load()

        assertEquals(1, reads)
        assertSame(first, second)
        assertTrue(first is ExerciseProgrammingState.Error)
    }

    @Test
    fun `application provider reuses one programming and canonical repository`() = runBlocking {
        val first = ExerciseProgrammingRepositoryProvider.getRepository(context)
        val second = ExerciseProgrammingRepositoryProvider.getRepository(context.applicationContext)

        assertSame(first, second)
        assertSame(
            ExerciseRepositoryProvider.getRepository(context),
            ExerciseRepositoryProvider.getRepository(context.applicationContext)
        )
        assertSame(first.getAll(), second.getAll())
    }

    @Test
    fun `canonical asset remains 534 unique opaque IDs`() = runBlocking {
        val canonical = ExerciseRepository.fromAssets(context).getAll()

        assertEquals(534, canonical.size)
        assertEquals(534, canonical.map { it.id.value }.toSet().size)
        assertNotNull(canonical.singleOrNull { it.id.value == "0001" })
        assertNotNull(canonical.singleOrNull { it.id.value == "fd-exercise-chair-squat" })
    }

    private fun repository(
        text: String = assetText(),
        canonical: ExerciseRepository = ExerciseRepository.fromAssets(context)
    ): ExerciseProgrammingRepository = ExerciseProgrammingRepository(
        source = { text },
        canonicalCatalogue = canonical
    )

    private suspend fun assertSafeFailure(text: String) {
        val repository = repository(text)
        val result = repository.load()

        assertTrue(result is ExerciseProgrammingState.Error)
        val error = result as ExerciseProgrammingState.Error
        assertEquals(ExerciseProgrammingRepository.LOAD_ERROR, error.message)
        assertEquals(error, repository.state.value)
        assertFalse(error.message.contains("json", ignoreCase = true))
        assertFalse(error.message.contains("asset", ignoreCase = true))
        assertFalse(error.message.contains("canonical", ignoreCase = true))
        assertFalse(error.message.contains("not-json", ignoreCase = true))
    }

    private fun assetText(): String = context.assets.open(ExerciseProgrammingRepository.ASSET_PATH)
        .bufferedReader()
        .use { it.readText() }

    private fun pack(vararg records: String): String =
        """{"schemaVersion":1,"records":[${records.joinToString(",")}]}"""

    private fun validRecord(
        id: String,
        roles: String = "[\"GENERAL_WARMUP\"]",
        prescriptionMode: String = "DURATION_SECONDS",
        prescriptionFields: String = "\"defaultDurationSeconds\": 300"
    ): String = """
        {
          "exerciseId":"$id",
          "roles":$roles,
          "programmingRegions":["FULL_BODY"],
          "prescriptionMode":"$prescriptionMode",
          $prescriptionFields,
          "perSide":false,
          "beginnerSuitable":true,
          "autoSelectApproved":true,
          "reviewStatus":"FITDESI_REVIEWED"
        }
    """.trimIndent()

    private data class ExpectedProgrammingRecord(
        val roles: Set<ExerciseProgrammingRole>,
        val regions: Set<ExerciseProgrammingRegion>,
        val joints: Set<ExerciseProgrammingJoint>
    )

    private companion object {
        const val MAIN_STRENGTH_PRESCRIPTION = "Follow the routine's working-set prescription."
        const val NEVER_AUTO_SELECT_REASON =
            "Alternating unilateral ramp-up prescription is ambiguous in the current contract."

        val EXPECTED_MAIN_STRENGTH_IDS = listOf(
            "0003", "0027", "0032", "0043", "0129", "0137", "0157", "0198", "0219", "0262",
            "0284", "0286", "0291", "0293", "0294", "0299", "0300", "0305", "0310", "0311",
            "0313", "0968", "0974", "0975", "0977", "0980", "0988", "0993",
            "0997", "1004", "1008", "1009", "1013", "1254", "1373", "1399", "1457", "1760", "1770", "1771",
            "2136", "2398", "3019", "3165", "3293", "3635", "3769"
        )

        val EXCLUDED_CANDIDATE_IDS = setOf(
            "0130", "0216", "0235", "0984", "0996", "3220", "3360", "1272", "1716", "0025", "0289",
            "0151", "0189"
        )

        val BEGINNER_REJECTED_PHONE_IDS = setOf(
            "0001", "0020", "0129", "0137", "1399", "1473", "1770", "3212", "3214", "3293"
        )

        val SUPPORT_EQUIPMENT_CONFLICT_IDS = setOf(
            "0129", "0137", "0291", "0305", "0974", "0980", "0988", "0993", "1008", "1013",
            "1254", "1399", "1770", "3019"
        )
        val FINAL_SUPPORT_TRUTH_EXCLUSION_IDS = setOf(
            "0279", "0284", "1000", "1277", "1373", "1649", "1650", "2403", "3165"
        )

        val APPROVED_B3B_IDS = setOf(
            "fd-exercise-shoulder-rolls",
            "fd-exercise-arm-circles",
            "fd-exercise-open-book-thoracic-rotation",
            "fd-exercise-standing-hip-circles",
            "fd-exercise-front-back-leg-swings",
            "fd-exercise-lateral-leg-swings",
            "fd-exercise-seated-biceps-stretch",
            "fd-exercise-wrist-flexor-extensor-stretch",
            "fd-exercise-childs-pose-lat-back-stretch",
            "0643",
            "1576"
        )

        val EXPECTED_NEW_RECORDS = mapOf(
            "0276" to expected(
                ExerciseProgrammingRole.ACTIVATION,
                regions = setOf(ExerciseProgrammingRegion.CORE, ExerciseProgrammingRegion.LOWER_BACK),
                joints = setOf(
                    ExerciseProgrammingJoint.SPINE,
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE
                )
            ),
            "1408" to expected(
                ExerciseProgrammingRole.ACTIVATION,
                regions = setOf(ExerciseProgrammingRegion.GLUTES, ExerciseProgrammingRegion.HIPS),
                joints = setOf(
                    ExerciseProgrammingJoint.SPINE,
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE
                )
            ),
            "0027" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.BACK,
                    ExerciseProgrammingRegion.BICEPS,
                    ExerciseProgrammingRegion.FOREARMS
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.SHOULDER,
                    ExerciseProgrammingJoint.ELBOW,
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "0032" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.HAMSTRINGS,
                    ExerciseProgrammingRegion.LOWER_BACK
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "0043" to strength(
                regions = setOf(ExerciseProgrammingRegion.GLUTES, ExerciseProgrammingRegion.QUADRICEPS),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.ANKLE,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "1457" to strength(
                regions = setOf(ExerciseProgrammingRegion.SHOULDERS, ExerciseProgrammingRegion.TRICEPS),
                joints = shoulderPressJoints()
            ),
            "0286" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.SHOULDERS,
                    ExerciseProgrammingRegion.TRICEPS,
                    ExerciseProgrammingRegion.CORE
                ),
                joints = shoulderPressJoints()
            ),
            "0293" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.BACK,
                    ExerciseProgrammingRegion.BICEPS,
                    ExerciseProgrammingRegion.FOREARMS
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.SHOULDER,
                    ExerciseProgrammingJoint.ELBOW,
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "0300" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.HAMSTRINGS,
                    ExerciseProgrammingRegion.LOWER_BACK
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "1760" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.QUADRICEPS,
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.HAMSTRINGS
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.ANKLE,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "0157" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.HAMSTRINGS,
                    ExerciseProgrammingRegion.QUADRICEPS,
                    ExerciseProgrammingRegion.LOWER_BACK
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.SPINE
                )
            ),
            "0198" to strength(
                regions = setOf(
                    ExerciseProgrammingRegion.LATS,
                    ExerciseProgrammingRegion.BACK,
                    ExerciseProgrammingRegion.BICEPS,
                    ExerciseProgrammingRegion.FOREARMS
                ),
                joints = setOf(ExerciseProgrammingJoint.SHOULDER, ExerciseProgrammingJoint.ELBOW)
            ),
            "0219" to strength(
                regions = setOf(ExerciseProgrammingRegion.SHOULDERS, ExerciseProgrammingRegion.TRICEPS),
                joints = shoulderPressJoints()
            ),
            "3543" to expected(
                ExerciseProgrammingRole.CONDITIONING,
                regions = setOf(
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.QUADRICEPS,
                    ExerciseProgrammingRegion.HAMSTRINGS,
                    ExerciseProgrammingRegion.CALVES
                ),
                joints = setOf(
                    ExerciseProgrammingJoint.HIP,
                    ExerciseProgrammingJoint.KNEE,
                    ExerciseProgrammingJoint.ANKLE
                )
            ),
            "3639" to expected(
                ExerciseProgrammingRole.NEVER_AUTO_SELECT,
                regions = setOf(
                    ExerciseProgrammingRegion.GLUTES,
                    ExerciseProgrammingRegion.CORE,
                    ExerciseProgrammingRegion.HIPS
                ),
                joints = setOf(ExerciseProgrammingJoint.HIP, ExerciseProgrammingJoint.SPINE)
            )
        )

        private fun expected(
            role: ExerciseProgrammingRole,
            regions: Set<ExerciseProgrammingRegion>,
            joints: Set<ExerciseProgrammingJoint>
        ) = ExpectedProgrammingRecord(setOf(role), regions, joints)

        private fun strength(
            regions: Set<ExerciseProgrammingRegion>,
            joints: Set<ExerciseProgrammingJoint>
        ) = expected(ExerciseProgrammingRole.MAIN_STRENGTH, regions, joints)

        private fun shoulderPressJoints() = setOf(
            ExerciseProgrammingJoint.SHOULDER,
            ExerciseProgrammingJoint.ELBOW,
            ExerciseProgrammingJoint.SPINE
        )
    }
}
