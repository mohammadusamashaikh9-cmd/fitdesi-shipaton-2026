package com.example.ai.knowledge

import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgePackLoaderTest {
    @Test
    fun validManifestAndPackLoad() {
        val fixture = validFixture()

        val catalogue = fixture.load()

        assertEquals(5, catalogue.totalRecords)
        assertEquals("Chair Squat", catalogue.exercises.single().name)
        assertEquals("dal", catalogue.foodAliases.single().alias)
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        val fixture = validFixture()
        fixture.manifest = fixture.manifest.copy(schemaVersion = 2)

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("Unsupported"))
    }

    @Test
    fun missingFileIsRejected() {
        val fixture = validFixture()
        fixture.files.remove("knowledge/v1/yoga_poses.json")

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("missing or unreadable"))
    }

    @Test
    fun duplicateIdAcrossFilesIsRejected() {
        val fixture = validFixture()
        fixture.put(
            "yoga_poses.json",
            listOf(
                yogaRecord().copy(id = exerciseRecord().id)
            ),
            YogaPosePackRecord.serializer()
        )

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("Duplicate"))
    }

    @Test
    fun malformedRecordIsRejected() {
        val fixture = validFixture()
        fixture.putRaw("exercises.json", """[{"id":"broken"}]""", 1)

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("Malformed"))
    }

    @Test
    fun incorrectManifestCountIsRejected() {
        val fixture = validFixture()
        fixture.setDeclaredCount("exercises.json", 99)

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("count mismatch"))
    }

    @Test
    fun restrictedRecordIsExcludedFromReleasePack() {
        val fixture = validFixture()
        fixture.put(
            "exercises.json",
            listOf(
                exerciseRecord().copy(
                    source = verifiedSource().copy(
                        licenseStatus = LicenseStatus.RESTRICTED,
                        redistributionStatus = RedistributionStatus.RESTRICTED
                    )
                )
            ),
            ExercisePackRecord.serializer()
        )

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("not verified"))
    }

    @Test
    fun unknownLicenceRecordIsExcludedFromReleasePack() {
        val fixture = validFixture()
        fixture.put(
            "pakistani_foods.json",
            listOf(
                foodRecord().copy(
                    source = verifiedSource().copy(
                        licenseStatus = LicenseStatus.UNKNOWN,
                        redistributionStatus = RedistributionStatus.UNKNOWN
                    )
                )
            ),
            PakistaniFoodPackRecord.serializer()
        )

        val error = assertThrows(KnowledgePackException::class.java) { fixture.load() }
        assertTrue(error.message.orEmpty().contains("not verified"))
    }

    @Test
    fun adapterSupportsEquipmentGoalAliasAndSafetyRetrieval() {
        val entries = KnowledgePackAdapter.toKnowledgeEntries(validFixture().load())
        val retriever = FitnessKnowledgeRetriever.from(entries)

        val equipmentMatches = retriever.retrieve(
            KnowledgeQuery("squat", CoachContext(equipment = setOf("Bodyweight")))
        )
        val incompatible = retriever.retrieve(
            KnowledgeQuery("squat", CoachContext(equipment = setOf("Barbell")))
        )
        val goalMatches = retriever.retrieve(
            KnowledgeQuery("workout plan", CoachContext(goal = "Get Stronger"))
        )
        val aliasMatches = retriever.retrieve(KnowledgeQuery("Can I eat dal?"))
        val safetyMatches = retriever.retrieve(KnowledgeQuery("I have chest pain and feel faint"))

        assertTrue(equipmentMatches.any { it.entry.id == "exercise-chair-squat" })
        assertTrue(incompatible.none { it.entry.id == "exercise-chair-squat" })
        assertTrue(goalMatches.any { "goal" in it.reasons })
        assertEquals("food-daal", aliasMatches.first().entry.id)
        assertEquals(KnowledgeDomain.MEDICAL_ESCALATION, safetyMatches.first().entry.domain)
    }

    @Test
    fun multipleVerifiedYogaRecordsLoadAndAdaptWithoutCanonicalIdMapping() {
        val fixture = validFixture()
        fixture.put(
            "yoga_poses.json",
            listOf(
                yogaRecord(),
                yogaRecord().copy(
                    id = "fd-yoga-childs-pose",
                    name = "Child's Pose",
                    aliases = listOf("Balasana"),
                    bodyTargets = listOf("hips", "back"),
                    source = verifiedSource().copy(sourceRecordId = "yoga-childs-pose-v1")
                )
            ),
            YogaPosePackRecord.serializer()
        )

        val catalogue = fixture.load()
        val entries = KnowledgePackAdapter.toKnowledgeEntries(catalogue)
            .filter { it.id.startsWith("fd-yoga-") }

        assertEquals(2, catalogue.yogaPoses.size)
        assertEquals(listOf("fd-yoga-cat-cow", "fd-yoga-childs-pose"), entries.map { it.id })
        assertTrue(entries.all { it.sourceType == KnowledgeSourceType.VERIFIED_PACK })
        assertTrue(entries.all { it.exerciseIds == setOf(it.id) })
    }

    @Test
    fun emptyVerifiedPackFallsBackWithoutReturningUnboundedData() {
        val emptyFixture = validFixture(includeRecords = false)
        val catalogue = emptyFixture.load()
        val entries = KnowledgePackAdapter.toKnowledgeEntries(catalogue)
        val fallback = entries.ifEmpty { FitnessKnowledgeCatalog.entries }
        val results = FitnessKnowledgeRetriever.from(fallback).retrieve(
            KnowledgeQuery("fitness plan", limit = 100)
        )

        assertTrue(entries.isEmpty())
        assertTrue(fallback.isNotEmpty())
        assertTrue(results.size <= 10)
    }

    @Test
    fun topResultLimitIsEnforced() {
        val entries = (1..20).map { index ->
            KnowledgeEntry(
                id = "entry-$index",
                domain = KnowledgeDomain.WORKOUT,
                title = "Workout plan $index",
                content = "Repeatable workout plan",
                keywords = setOf("workout", "plan")
            )
        }

        val results = FitnessKnowledgeRetriever.from(entries).retrieve(
            KnowledgeQuery("workout plan", limit = 50)
        )

        assertEquals(10, results.size)
    }

    private fun validFixture(includeRecords: Boolean = true): PackFixture {
        val fixture = PackFixture()
        fixture.put(
            "exercises.json",
            if (includeRecords) listOf(exerciseRecord()) else emptyList(),
            ExercisePackRecord.serializer()
        )
        fixture.put(
            "yoga_poses.json",
            emptyList(),
            YogaPosePackRecord.serializer()
        )
        fixture.put(
            "pakistani_foods.json",
            if (includeRecords) listOf(foodRecord()) else emptyList(),
            PakistaniFoodPackRecord.serializer()
        )
        fixture.put(
            "food_aliases.json",
            if (includeRecords) listOf(aliasRecord()) else emptyList(),
            FoodAliasPackRecord.serializer()
        )
        fixture.put(
            "coaching_rules.json",
            emptyList(),
            CoachingRulePackRecord.serializer()
        )
        fixture.put(
            "workout_rules.json",
            if (includeRecords) listOf(workoutRule()) else emptyList(),
            WorkoutRulePackRecord.serializer()
        )
        fixture.put(
            "progressions.json",
            emptyList(),
            ProgressionPackRecord.serializer()
        )
        fixture.put(
            "substitutions.json",
            emptyList(),
            SubstitutionPackRecord.serializer()
        )
        fixture.put(
            "safety_rules.json",
            emptyList(),
            SafetyRulePackRecord.serializer()
        )
        fixture.put(
            "medical_escalation.json",
            if (includeRecords) listOf(medicalRule()) else emptyList(),
            MedicalEscalationPackRecord.serializer()
        )
        fixture.refreshManifest()
        return fixture
    }

    private fun exerciseRecord() = ExercisePackRecord(
        id = "exercise-chair-squat",
        name = "Chair Squat",
        aliases = listOf("Sit to stand"),
        movementPattern = "squat",
        category = "lower body",
        bodyPart = "upper legs",
        target = "quadriceps",
        muscleGroup = "glutes",
        secondaryMuscles = listOf("hamstrings"),
        bodyTargets = listOf("quadriceps"),
        equipment = listOf("bodyweight", "chair"),
        experienceLevels = listOf("beginner"),
        goals = listOf("get stronger"),
        instructions = "Sit back to a stable chair and stand under control.",
        safetyNote = "Stop if pain or dizziness occurs.",
        source = verifiedSource()
    )

    private fun yogaRecord() = YogaPosePackRecord(
        id = "fd-yoga-cat-cow",
        name = "Cat-Cow",
        aliases = emptyList(),
        bodyTargets = listOf("spine"),
        equipment = listOf("bodyweight"),
        goals = listOf("mobility"),
        instructions = "Move through a comfortable range.",
        safetyNote = "Stop for pain.",
        source = verifiedSource()
    )

    private fun foodRecord() = PakistaniFoodPackRecord(
        id = "food-daal",
        name = "Simple Daal Bowl",
        category = "legumes",
        servingDescription = "One measured bowl",
        aliases = listOf("lentil curry"),
        dietaryTags = listOf("vegetarian"),
        nutritionEstimate = null,
        uncertaintyNote = "Recipe and portion vary.",
        goals = listOf("balanced meals"),
        source = verifiedSource()
    )

    private fun aliasRecord() = FoodAliasPackRecord(
        id = "alias-dal",
        alias = "dal",
        foodId = "food-daal",
        locale = "en-PK",
        source = verifiedSource()
    )

    private fun workoutRule() = WorkoutRulePackRecord(
        id = "workout-plan",
        title = "Repeatable plan",
        keywords = listOf("workout", "plan"),
        goals = listOf("get stronger"),
        experienceLevels = listOf("beginner"),
        equipment = emptyList(),
        guidance = "Use a repeatable weekly plan.",
        priority = 20,
        source = verifiedSource()
    )

    private fun medicalRule() = MedicalEscalationPackRecord(
        id = "medical-chest-pain",
        title = "Urgent symptoms",
        triggerTerms = listOf("chest pain", "faint"),
        urgency = "URGENT",
        action = "Stop exercise and seek urgent medical assessment.",
        prohibitedClaims = listOf("diagnosis"),
        priority = 100,
        source = verifiedSource()
    )

    private fun verifiedSource() = SourceMetadata(
        sourceName = "FitDesi test authors",
        sourceRepository = "https://example.test/fitdesi-original",
        sourceRecordId = "test-record",
        licenseIdentifier = "MIT",
        licenseScope = "Original test knowledge record",
        retrievedAt = "2026-07-16",
        modifiedByFitDesi = false,
        redistributionStatus = RedistributionStatus.VERIFIED,
        licenseStatus = LicenseStatus.VERIFIED,
        attribution = "FitDesi test authors"
    )

    private class PackFixture {
        val files = mutableMapOf<String, String>()
        private val declarations = mutableMapOf<String, KnowledgePackFile>()
        var manifest = KnowledgePackManifest(
            packId = "test-pack",
            packVersion = "1.0.0",
            schemaVersion = 1,
            releaseMode = "MARKET_VERIFIED_ONLY",
            createdDate = "2026-07-16",
            files = emptyList(),
            totalRecords = 0,
            licenseCounts = LicenseCounts(0, 0, 0)
        )

        fun <T> put(
            fileName: String,
            records: List<T>,
            serializer: KSerializer<T>
        ) {
            val text = json.encodeToString(ListSerializer(serializer), records)
            putRaw(fileName, text, records.size)
        }

        fun putRaw(fileName: String, text: String, recordCount: Int) {
            files["knowledge/v1/$fileName"] = text
            declarations[fileName] = KnowledgePackFile(fileName, recordCount, sha256(text))
            refreshManifest()
        }

        fun setDeclaredCount(fileName: String, count: Int) {
            val existing = declarations.getValue(fileName)
            declarations[fileName] = existing.copy(recordCount = count)
            refreshManifest()
        }

        fun refreshManifest() {
            val total = declarations.values.sumOf(KnowledgePackFile::recordCount)
            manifest = manifest.copy(
                files = declarations.values.sortedBy(KnowledgePackFile::name),
                totalRecords = total,
                licenseCounts = LicenseCounts(total, 0, 0)
            )
            files["knowledge/v1/manifest.json"] = json.encodeToString(manifest)
        }

        fun load(): KnowledgePackCatalogue {
            refreshManifest()
            return KnowledgePackLoader(
                source = KnowledgeAssetSource { path ->
                    files[path] ?: throw IllegalArgumentException("Missing $path")
                }
            ).load()
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
