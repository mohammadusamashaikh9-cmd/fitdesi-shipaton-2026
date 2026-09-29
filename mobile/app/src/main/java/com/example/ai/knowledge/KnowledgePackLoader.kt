package com.example.ai.knowledge

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

fun interface KnowledgeAssetSource {
    fun read(path: String): String
}

class AndroidKnowledgeAssetSource(context: Context) : KnowledgeAssetSource {
    private val assets = context.applicationContext.assets

    override fun read(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }
}

class KnowledgePackException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class KnowledgePackLoader(
    private val source: KnowledgeAssetSource,
    private val basePath: String = DEFAULT_BASE_PATH
) {
    @Volatile
    private var cached: KnowledgePackCatalogue? = null

    fun load(): KnowledgePackCatalogue {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadAndValidate().also { cached = it }
        }
    }

    private fun loadAndValidate(): KnowledgePackCatalogue {
        val manifestText = readRequired(MANIFEST_FILE)
        val manifest = decode(manifestText, KnowledgePackManifest.serializer(), MANIFEST_FILE)
        validateManifest(manifest)

        val exercises = readRecords(EXERCISES, ExercisePackRecord.serializer(), manifest)
        val yoga = readRecords(YOGA, YogaPosePackRecord.serializer(), manifest)
        val foods = readRecords(FOODS, PakistaniFoodPackRecord.serializer(), manifest)
        val aliases = readRecords(FOOD_ALIASES, FoodAliasPackRecord.serializer(), manifest)
        val coaching = readRecords(COACHING_RULES, CoachingRulePackRecord.serializer(), manifest)
        val workouts = readRecords(WORKOUT_RULES, WorkoutRulePackRecord.serializer(), manifest)
        val progressions = readRecords(PROGRESSIONS, ProgressionPackRecord.serializer(), manifest)
        val substitutions = readRecords(SUBSTITUTIONS, SubstitutionPackRecord.serializer(), manifest)
        val safety = readRecords(SAFETY_RULES, SafetyRulePackRecord.serializer(), manifest)
        val medical = readRecords(MEDICAL_ESCALATION, MedicalEscalationPackRecord.serializer(), manifest)

        val catalogue = KnowledgePackCatalogue(
            manifest = manifest,
            exercises = exercises,
            yogaPoses = yoga,
            pakistaniFoods = foods,
            foodAliases = aliases,
            coachingRules = coaching,
            workoutRules = workouts,
            progressions = progressions,
            substitutions = substitutions,
            safetyRules = safety,
            medicalEscalations = medical
        )
        validateCatalogue(catalogue)
        return catalogue
    }

    private fun validateManifest(manifest: KnowledgePackManifest) {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw KnowledgePackException(
                "Unsupported knowledge-pack schema ${manifest.schemaVersion}; expected $SUPPORTED_SCHEMA_VERSION."
            )
        }
        if (manifest.releaseMode != RELEASE_MODE) {
            throw KnowledgePackException("Knowledge pack is not a verified-only market pack.")
        }
        val names = manifest.files.map(KnowledgePackFile::name)
        if (names.toSet().size != names.size) {
            throw KnowledgePackException("Knowledge-pack manifest contains duplicate file declarations.")
        }
        val missing = REQUIRED_FILES - names.toSet()
        if (missing.isNotEmpty()) {
            throw KnowledgePackException("Knowledge-pack manifest is missing: ${missing.sorted().joinToString()}.")
        }
        val unexpected = names.toSet() - REQUIRED_FILES
        if (unexpected.isNotEmpty()) {
            throw KnowledgePackException(
                "Knowledge-pack manifest declares unsupported files: ${unexpected.sorted().joinToString()}."
            )
        }
        if (manifest.packId.isBlank() || manifest.packVersion.isBlank() ||
            manifest.createdDate.isBlank() || manifest.totalRecords < 0
        ) {
            throw KnowledgePackException("Knowledge-pack manifest contains invalid required metadata.")
        }
        if (manifest.files.any {
                it.recordCount < 0 || !it.sha256.matches(Regex("[a-f0-9]{64}"))
            }
        ) {
            throw KnowledgePackException("Knowledge-pack manifest contains invalid file metadata.")
        }
        if (manifest.licenseCounts.RESTRICTED != 0 || manifest.licenseCounts.UNKNOWN != 0) {
            throw KnowledgePackException("Market knowledge pack declares restricted or unknown records.")
        }
    }

    private fun <T> readRecords(
        fileName: String,
        itemSerializer: KSerializer<T>,
        manifest: KnowledgePackManifest
    ): List<T> {
        val declaration = manifest.files.singleOrNull { it.name == fileName }
            ?: throw KnowledgePackException("Knowledge-pack manifest does not declare $fileName.")
        val text = readRequired(fileName)
        if (sha256(text) != declaration.sha256) {
            throw KnowledgePackException("Knowledge-pack checksum mismatch for $fileName.")
        }
        val records = decode(text, ListSerializer(itemSerializer), fileName)
        if (records.size != declaration.recordCount) {
            throw KnowledgePackException(
                "Knowledge-pack count mismatch for $fileName: expected ${declaration.recordCount}, found ${records.size}."
            )
        }
        return records.toList()
    }

    private fun validateCatalogue(catalogue: KnowledgePackCatalogue) {
        val ids = catalogue.allRecordIds()
        val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw KnowledgePackException("Duplicate knowledge record ID: $duplicate.")
        if (ids.size != catalogue.manifest.totalRecords) {
            throw KnowledgePackException(
                "Knowledge-pack total mismatch: expected ${catalogue.manifest.totalRecords}, found ${ids.size}."
            )
        }
        if (catalogue.manifest.licenseCounts.VERIFIED != ids.size) {
            throw KnowledgePackException("Verified licence count does not match the knowledge-pack total.")
        }

        catalogue.exercises.forEach { record ->
            requireRecord(record.id, record.name, record.instructions, record.safetyNote, record.source)
        }
        catalogue.yogaPoses.forEach { record ->
            requireRecord(record.id, record.name, record.instructions, record.safetyNote, record.source)
        }
        catalogue.pakistaniFoods.forEach { record ->
            requireRecord(record.id, record.name, record.servingDescription, record.uncertaintyNote, record.source)
            record.nutritionEstimate?.let {
                if (it.calories < 0 || it.proteinGrams < 0 || it.carbsGrams < 0 || it.fatGrams < 0) {
                    throw KnowledgePackException("Food ${record.id} contains negative nutrition values.")
                }
            }
        }
        catalogue.foodAliases.forEach { requireRecord(it.id, it.alias, it.foodId, it.locale, it.source) }
        catalogue.coachingRules.forEach { requireRule(it.id, it.title, it.guidance, it.priority, it.source) }
        catalogue.workoutRules.forEach { requireRule(it.id, it.title, it.guidance, it.priority, it.source) }
        catalogue.progressions.forEach {
            requireRecord(it.id, it.fromLabel, it.toLabel, it.guidance, it.source)
        }
        catalogue.substitutions.forEach {
            requireRecord(it.id, it.fromLabel, it.toLabel, it.reason, it.source)
        }
        catalogue.safetyRules.forEach { requireRule(it.id, it.title, it.guidance, it.priority, it.source) }
        catalogue.medicalEscalations.forEach { requireRule(it.id, it.title, it.action, it.priority, it.source) }

        val foodIds = catalogue.pakistaniFoods.map(PakistaniFoodPackRecord::id).toSet()
        val missingFood = catalogue.foodAliases.firstOrNull { it.foodId !in foodIds }
        if (missingFood != null) {
            throw KnowledgePackException("Food alias ${missingFood.id} references missing food ${missingFood.foodId}.")
        }
    }

    private fun requireRecord(
        id: String,
        first: String,
        second: String,
        third: String,
        source: SourceMetadata
    ) {
        if (listOf(id, first, second, third).any(String::isBlank)) {
            throw KnowledgePackException("Knowledge record has a blank required field: $id.")
        }
        requireVerifiedSource(id, source)
    }

    private fun requireRule(
        id: String,
        title: String,
        guidance: String,
        priority: Int,
        source: SourceMetadata
    ) {
        requireRecord(id, title, guidance, priority.toString(), source)
        if (priority !in 0..100) throw KnowledgePackException("Knowledge rule $id has invalid priority.")
    }

    private fun requireVerifiedSource(id: String, source: SourceMetadata) {
        if (listOf(
                source.sourceName,
                source.sourceRepository,
                source.sourceRecordId,
                source.licenseIdentifier,
                source.licenseScope,
                source.retrievedAt,
                source.attribution
            ).any(String::isBlank)
        ) {
            throw KnowledgePackException("Knowledge record $id has incomplete source metadata.")
        }
        if (!source.retrievedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            throw KnowledgePackException("Knowledge record $id has an invalid retrieval date.")
        }
        if (source.licenseStatus != LicenseStatus.VERIFIED ||
            source.redistributionStatus != RedistributionStatus.VERIFIED
        ) {
            throw KnowledgePackException("Knowledge record $id is not verified for redistribution.")
        }
    }

    private fun readRequired(fileName: String): String = try {
        source.read("$basePath/$fileName")
    } catch (error: Exception) {
        throw KnowledgePackException("Required knowledge-pack file is missing or unreadable: $fileName.", error)
    }

    private fun <T> decode(
        text: String,
        serializer: KSerializer<T>,
        fileName: String
    ): T = try {
        json.decodeFromString(serializer, text)
    } catch (error: SerializationException) {
        throw KnowledgePackException("Malformed knowledge-pack file: $fileName.", error)
    } catch (error: IllegalArgumentException) {
        throw KnowledgePackException("Invalid knowledge-pack data in $fileName.", error)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_BASE_PATH = "knowledge/v1"
        const val SUPPORTED_SCHEMA_VERSION = 1
        private const val MANIFEST_FILE = "manifest.json"
        private const val RELEASE_MODE = "MARKET_VERIFIED_ONLY"
        private const val EXERCISES = "exercises.json"
        private const val YOGA = "yoga_poses.json"
        private const val FOODS = "pakistani_foods.json"
        private const val FOOD_ALIASES = "food_aliases.json"
        private const val COACHING_RULES = "coaching_rules.json"
        private const val WORKOUT_RULES = "workout_rules.json"
        private const val PROGRESSIONS = "progressions.json"
        private const val SUBSTITUTIONS = "substitutions.json"
        private const val SAFETY_RULES = "safety_rules.json"
        private const val MEDICAL_ESCALATION = "medical_escalation.json"
        val REQUIRED_FILES: Set<String> = setOf(
            EXERCISES,
            YOGA,
            FOODS,
            FOOD_ALIASES,
            COACHING_RULES,
            WORKOUT_RULES,
            PROGRESSIONS,
            SUBSTITUTIONS,
            SAFETY_RULES,
            MEDICAL_ESCALATION
        )

        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        fun fromAssets(context: Context): KnowledgePackLoader =
            KnowledgePackLoader(AndroidKnowledgeAssetSource(context))
    }
}
