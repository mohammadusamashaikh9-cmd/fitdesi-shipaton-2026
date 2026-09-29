package com.example.data

import android.content.Context
import com.example.security.SafeLog
import com.example.ui.DiscoveryFoodItem
import com.example.ui.FoodItem
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

class FoodRepository(private val context: Context) {
    // Public-safe runtime nutrition catalogue: only records that satisfy
    // FoodCommercialReleaseEligibilityPolicy are projected into this asset.
    private val records: List<PakistaniFoodRecord> by lazy {
        try {
            val json = context.assets.open(PUBLIC_NUTRITION_CATALOGUE).bufferedReader().use { it.readText() }
            FoodCatalogueParser.parse(json).records
        } catch (cause: Exception) {
            SafeLog.error(TAG, "Bundled food catalogue validation failed", cause)
            throw IllegalStateException("Packaged public-safe Pakistani food catalogue is invalid.", cause)
        }
    }

    // FitDesi-authored cultural food identities with no nutrition values. Kept in a
    // separate model and asset so a discovery record can never become loggable.
    private val discoveryRecords: List<SouthAsianFoodDiscoveryRecord> by lazy {
        try {
            val json = context.assets.open(DISCOVERY_CATALOGUE).bufferedReader().use { it.readText() }
            SouthAsianFoodDiscoveryParser.parse(json).records
        } catch (cause: Exception) {
            SafeLog.error(TAG, "Bundled South Asian discovery catalogue validation failed", cause)
            throw IllegalStateException("Packaged South Asian food discovery catalogue is invalid.", cause)
        }
    }

    fun getPakistaniFoods(): List<FoodItem> = records.map { record ->
        FoodItem(
            id = record.id,
            name = record.name,
            aliases = record.aliases,
            category = record.category,
            caloriesPerServing = record.calories,
            proteinGrams = record.proteinGrams.toFloat(),
            carbsGrams = record.carbsGrams.toFloat(),
            fatGrams = record.fatGrams.toFloat(),
            servingUnit = record.servingSize,
            nutritionDisplayLabel = record.nutritionDisplayLabel,
            reviewStatus = record.fitDesiReviewStatus,
            isLoggable = record.isLoggable,
            runtimeSource = record.runtimeSource,
            loggingEligibility = FoodLoggingEligibilityPolicy.evaluate(record)
        )
    }

    fun getPakistaniFoodRecords(): List<PakistaniFoodRecord> = records

    fun getSouthAsianFoods(): List<DiscoveryFoodItem> = discoveryRecords.map { record ->
        DiscoveryFoodItem(
            id = record.id,
            name = record.name,
            category = record.category,
            cuisine = record.cuisine,
            nutritionStatus = record.nutritionStatus
        )
    }

    fun getSouthAsianFoodDiscoveryRecords(): List<SouthAsianFoodDiscoveryRecord> = discoveryRecords

    private companion object {
        const val PUBLIC_NUTRITION_CATALOGUE = "pakistani_food_catalogue_public.json"
        const val DISCOVERY_CATALOGUE = "south_asian_food_discovery.json"
        const val TAG = "FoodRepository"
    }
}

@Serializable
data class PakistaniFoodCatalogue(
    val schemaVersion: Int,
    val catalogueId: String,
    val generatedAt: String,
    val recordCount: Int,
    val reviewedLoggableCount: Int,
    val reviewRequiredCount: Int,
    val sourceCandidateCount: Int,
    val categoryTotals: Map<String, Int>,
    val ambiguousAliases: Map<String, List<String>>,
    val records: List<PakistaniFoodRecord>
)

@Serializable
data class PakistaniFoodRecord(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val category: String,
    val originalCategory: String,
    val servingSize: String,
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val nutritionBasis: String,
    val nutritionDisplayLabel: String,
    val fitDesiReviewStatus: String,
    val isLoggable: Boolean,
    val runtimeSource: String,
    val dietaryClassification: String,
    val licenceStatus: String,
    val importedProvenance: List<FoodSourceProvenance>,
    val recipeEvidence: RecipeYieldEvidence? = null
)

@Serializable
data class RecipeYieldEvidence(
    val evidenceSchemaVersion: Int,
    val recipe: RecipeEvidenceIdentity,
    val ingredients: List<RecipeIngredientEvidence>,
    @SerialName("yield") val finalYield: RecipeFinalYieldEvidence,
    val nutrition: RecipeNutritionEvidence,
    val roundingPolicy: RecipeRoundingPolicyEvidence,
    val provenance: RecipeProvenanceEvidence,
    val review: RecipeReviewEvidence,
    val commercialEligibility: RecipeCommercialEligibilityEvidence,
    val evidenceSha256: String
)

@Serializable
data class RecipeEvidenceIdentity(
    val id: String,
    val schemaVersion: Int,
    val recipeVersion: Int,
    val calculationVersion: String
)

@Serializable
data class RecipeIngredientEvidence(
    val type: String,
    val foodId: String? = null,
    val processId: String? = null,
    val processType: String? = null,
    val grams: String,
    val evidenceFingerprint: String? = null
)

@Serializable
data class RecipeFinalYieldEvidence(
    val finalYieldGrams: String,
    val evidence: RecipeYieldSourceEvidence
)

@Serializable
data class RecipeYieldSourceEvidence(
    val method: String,
    val referenceIdentifier: String
)

@Serializable
data class RecipeNutritionEvidence(
    val rawTotalsExact: RecipeExactNutritionEvidence,
    val publishedPer100g: RecipePublishedNutritionEvidence
)

@Serializable
data class RecipeExactNutritionEvidence(
    val energyKcal: String,
    val proteinGrams: String,
    val carbsGrams: String,
    val fatGrams: String
)

@Serializable
data class RecipePublishedNutritionEvidence(
    val energyKcal: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double
)

@Serializable
data class RecipeRoundingPolicyEvidence(
    val mode: String,
    val boundary: String,
    val energyDecimalPlaces: Int,
    val macroDecimalPlaces: Int
)

@Serializable
data class RecipeProvenanceEvidence(
    val authorship: RecipeAuthorshipEvidence,
    val references: List<RecipeSourceReferenceEvidence>
)

@Serializable
data class RecipeAuthorshipEvidence(
    val authorName: String,
    val specificationType: String,
    val sourceIdentifier: String
)

@Serializable
data class RecipeSourceReferenceEvidence(
    val sourceName: String,
    val sourceIdentifier: String,
    val licenseIdentifier: String,
    val usageClassification: String
)

@Serializable
data class RecipeReviewEvidence(
    val disposition: String,
    val reviewer: String,
    val reviewedAt: String
)

@Serializable
data class RecipeCommercialEligibilityEvidence(
    val eligible: Boolean,
    val derivedFrom: List<String>
)

@Serializable
data class FoodSourceProvenance(
    val sourceRepository: String,
    val sourceFile: String,
    val originalSourceId: String,
    val originalRepositoryRecordId: String,
    val repositoryLicense: String,
    val licenceStatus: String,
    val commercialVerificationStatus: String,
    val provenanceWarning: String,
    val confidence: Double,
    val macroValidationState: String?,
    val nutritionBasis: String,
    val fitDesiReviewStatus: String,
    val importClassification: String,
    val sourceProvider: String? = null,
    val dataType: String? = null,
    val release: String? = null,
    val sourceDescription: String? = null,
    val sourceUrl: String? = null,
    val sourceFileHashes: Map<String, String> = emptyMap(),
    val retrievalDate: String? = null,
    val foodState: String? = null,
    val ingredientInterpretation: String? = null,
    val transformationHistory: String? = null,
    val fitDesiReviewDisposition: String? = null,
    val sourceCaloriesKcal: Double? = null,
    val sourceProteinGrams: Double? = null,
    val sourceCarbsGrams: Double? = null,
    val sourceFatGrams: Double? = null,
    val sourceFiberGrams: Double? = null,
    val sourceSodiumMilligrams: Double? = null,
    val sourceSugarsGrams: Double? = null,
    val calorieNutrientId: String? = null,
    val proteinNutrientId: String? = null,
    val carbohydrateNutrientId: String? = null,
    val fatNutrientId: String? = null,
    val evidenceClass: String? = null,
    val sourcePublicationDate: String? = null,
    val sourceRecordSha256: String? = null,
    val brandOwner: String? = null,
    val brandName: String? = null,
    val ingredientStatement: String? = null,
    val sourceServingSize: Double? = null,
    val sourceServingUnit: String? = null,
    val nutrientDerivationCodes: Map<String, String> = emptyMap()
)

private object UsdaFoundationEvidence {
    const val RUNTIME_SOURCE = "USDA_FOUNDATION_VERIFIED"
    const val SOURCE_PROVIDER = "USDA FoodData Central"
    const val DATA_TYPE = "Foundation Foods"
    const val RELEASE = "April 2026"
    const val RETRIEVAL_DATE = "2026-08-28"
    const val LICENCE = "CC0-1.0"
    const val REVIEW_DISPOSITION = "ACCEPTED_VERIFIED"
    const val CALORIE_NUTRIENT_ID = "2048"
    const val PROTEIN_NUTRIENT_ID = "1003"
    const val FAT_NUTRIENT_ID = "1004"
    const val CARBOHYDRATE_NUTRIENT_ID = "1005"

    val sourceFileHashes = mapOf(
        "food.csv" to "C1C9A97D5BB3319DACB69D5B3183BEF95BFE4C90455FE92484CF5CA72566AC2A",
        "food_nutrient.csv" to "2B8EEB9D1F2104F09641041A025B7AB58BE1653F4C10A52E047F2024EF42C480",
        "nutrient.csv" to "226BA937D1A73E8C87BD7FDCDD577524699BEF4025C9232EE474B7D3083498FF",
        "food_portion.csv" to "7AF765126593F2BC3CA7BEFFF30AF23C5B4921714C51848B9424150C52F68BAB",
        "measure_unit.csv" to "62CDE43EDE7257A7748BB03D1B62F6905FAB3EE0014EFD41C9A040AC404DA4D6",
        "foundation_food.csv" to "5666629A4D06BF1D6A16114E1E3890CFA7D56523EC99134545A24BA216B521CB",
        "food_category.csv" to "A8E05C18CF010D44303F2A02765637A21F9AD621F90B4F811AB4A4A436CFEDD9",
        "food_calorie_conversion_factor.csv" to "EB2390F0C47EB0361CA1E0D3DBEFB11C2F34A191A3ADD3174B50284099845C3B"
    )
}

private object DirectUsdaFdcEvidence {
    const val SOURCE_PROVIDER = "USDA FoodData Central"
    const val SOURCE_URL = "https://fdc.nal.usda.gov/"
    const val LICENCE = "CC0-1.0"
    const val REVIEW_DISPOSITION = "ACCEPTED_VERIFIED"
    const val RETRIEVAL_DATE = "2026-08-31"
    const val CALORIE_NUTRIENT_ID = "1008"
    const val PROTEIN_NUTRIENT_ID = "1003"
    const val CARBOHYDRATE_NUTRIENT_ID = "1005"
    const val FAT_NUTRIENT_ID = "1004"
    const val SELECTION_EVIDENCE_SHA256 = "874e5da49762103fe8821828296477e1f3ad178bc4b0f0d21871c3878e962138"

    data class Policy(
        val runtimeSource: String,
        val evidenceClass: String,
        val dataType: String,
        val release: String,
        val archiveFilename: String,
        val archiveSha256: String,
        val nutritionDisplayLabel: String,
        val importClassification: String,
        val provenanceWarning: String
    )

    val policies = listOf(
        Policy(
            runtimeSource = "USDA_FNDDS_VERIFIED",
            evidenceClass = "FNDDS",
            dataType = "Survey (FNDDS)",
            release = "FNDDS 2021-2023 / October 2024",
            archiveFilename = "FoodData_Central_survey_food_json_2024-10-31.zip",
            archiveSha256 = "DFB06AE7DDC397CCD570B91C14B75438AB2BA39F64F22D321F61D4A52A77F3EB",
            nutritionDisplayLabel = "USDA FNDDS compiled dietary food • per 100 g",
            importClassification = "IMPORTED_VERIFIED_FNDDS_COMPILED",
            provenanceWarning = "COMPILED_DIETARY_PROFILE_NOT_FOUNDATION_ANALYSIS"
        ),
        Policy(
            runtimeSource = "USDA_SR_LEGACY_VERIFIED",
            evidenceClass = "SR_LEGACY",
            dataType = "SR Legacy",
            release = "April 2018 final release",
            archiveFilename = "FoodData_Central_sr_legacy_food_json_2018-04.zip",
            archiveSha256 = "0FE8AE486A2C8EB42CB96413F058DEB51863A46C8FB8EEB4B1FB45006DD338EF",
            nutritionDisplayLabel = "USDA SR Legacy historical composition • per 100 g",
            importClassification = "IMPORTED_VERIFIED_SR_LEGACY",
            provenanceWarning = "HISTORICAL_SR_COMPOSITION_NOT_CURRENT_FOUNDATION_ANALYSIS"
        ),
        Policy(
            runtimeSource = "USDA_BRANDED_LABEL_VERIFIED",
            evidenceClass = "BRANDED_LABEL",
            dataType = "Branded",
            release = "April 2026",
            archiveFilename = "FoodData_Central_branded_food_json_2026-04-30.zip",
            archiveSha256 = "57B0F122E61CF2840F03C11E9520275D0D2018DC036E16273FCD3CD370DB2256",
            nutritionDisplayLabel = "USDA FDC Branded manufacturer-label-derived food • per 100 g",
            importClassification = "IMPORTED_VERIFIED_BRANDED_LABEL",
            provenanceWarning = "MANUFACTURER_LABEL_DERIVED_NOT_USDA_ANALYTICAL_MEASUREMENT"
        )
    ).associateBy(Policy::runtimeSource)

    val acceptedRuntimeSources = policies.keys + UsdaFoundationEvidence.RUNTIME_SOURCE
    val sourceRecordSha256ByFdcId = mapOf(
        "169967" to "a343a02f318e22f3f9b33128590128bbf0a3982ee59a560be8e987e0ece67052",
        "170440" to "b500356385b1c98e389e8f2118b7a090670e964be5cd1edc13d97fdaa1ae33dc",
        "170899" to "19f526ddac8203f0a59eddc6848461aade7cca71f4deb901e9920e762eec3977",
        "171477" to "504b9d72078a0e3dea46aad9db5c2c4ce2296d2676d992050c8943543f526cc3",
        "171986" to "5160a258c9ea6bb99f9531921d8e8b35b571a5678efa094786cd179c5164c300",
        "171998" to "247f80359cd8b7f8763da8bf3f146bed8871b75188665016eb6ee1f5ca2f779a",
        "172421" to "33da9946a3bb81932d624299b3641485eeb1ae6d0bfaaa80f4c0eb24cb17df05",
        "172470" to "d2aa7ae5c7e7a454b628fdd30ba4884b128321e8ffc7bd762341543c849d0d0f",
        "173424" to "197d39b427afe51d7d987d61415ac9608bd07a78dee269fd5f01f819d406ca4c",
        "173735" to "b5782b901549ab730a1e7f7518a22279946691bb705c155a86a9d3ee8b83204f",
        "173740" to "fb3ae3fdf6e3651403085819ec9e4ed4aed67251b2b8e533c5cfeec44594da27",
        "173757" to "df10bdc551a6cef6df8660b67d1c6bbfdf2bb5594f7b85f5967c001cf4bc22ab",
        "1886335" to "db183b52b3bd4086eae73ec7882807781dc18f5c6d18b3ccf03fa1cb253ffd4a",
        "2705418" to "6811adb2983121ad32c9f11580349c26c17717c2153c437856000812548ea9c6",
        "2705422" to "23ef45987c47b01e6db5b770da82d4d823e1e62945f3acca88fb845f7b5e5337",
        "2706920" to "cc2ffe9ff27e8fda4d966521bd4ce2a0479752b1d818cd96b803d533c37559e6",
        "2707389" to "b02b2e567845b24205236299c80b2479bee7d840bc18723a9c03597dab260b2b",
        "2707709" to "a363f2cfd8ffd7e389b2be16a3668fab33612220989925ba53809363cde5affc",
        "2708352" to "1047164f655de4cad2625bafd9a08fe02bea322d1942364813ed8f26ca903b64",
        "2708356" to "de2f218665899add4a36a95c7c7c9342418e1d7ff434a4aa7914cebffe6fa9c9",
        "2708357" to "cd52687d33ccecac5c1d1eb60514f3fe2bb5109f1ba119dbade22b9e42678820",
        "2708614" to "5c60268a0955d54739674557efd6ab732a805c8f4680b10ab6cfdb3f455ad7e1",
        "2709456" to "c7f192a865cd19478643867caa93535cbba7f60fd27ad21bfe472c65af17bdd2",
        "2709615" to "4e966ffc300a0c435047be1a60e3efdee365ee21f04e90dbb8922d499c982b22"
    )
}

enum class FoodLoggingStatus {
    VERIFIED,
    LEGACY_ESTIMATE,
    REVIEW_REQUIRED,
    MORE_DATA_NEEDED
}

data class FoodLoggingEligibility(
    val status: FoodLoggingStatus,
    val canLog: Boolean,
    val commercialReleaseEligible: Boolean,
    val explanation: String
)

object FoodCommercialEvidence {
    const val VERIFIED_LICENCE_STATUS = "VERIFIED_COMMERCIAL"
    const val VERIFIED_NUTRITION_REVIEW_STATUS = "FITDESI_NUTRITION_VERIFIED"
    const val VERIFIED_PER_SERVING_BASIS = "VERIFIED_PER_SERVING"
    const val VERIFIED_PER_100G_BASIS = "VERIFIED_PER_100G"
    const val VERIFIED_RECIPE_YIELD_BASIS = "VERIFIED_RECIPE_YIELD"

    internal val acceptedNutritionBases = setOf(
        VERIFIED_PER_SERVING_BASIS,
        VERIFIED_PER_100G_BASIS,
        VERIFIED_RECIPE_YIELD_BASIS
    )
}

private object RecipeYieldEvidencePolicy {
    private const val EVIDENCE_SCHEMA_VERSION = 1
    private const val RECIPE_SCHEMA_VERSION = 1
    private const val CALCULATION_VERSION = "FITDESI_RECIPE_YIELD_V1"
    private const val RUNTIME_SOURCE = "FITDESI_RECIPE_YIELD_VERIFIED"
    private const val REVIEW_DISPOSITION = "APPROVED"
    private const val AUTHORSHIP_TYPE = "FITDESI_AUTHORED_RECIPE"
    private const val ROUNDING_MODE = "ROUND_HALF_UP_NON_NEGATIVE"
    private const val ROUNDING_BOUNDARY = "FINAL_PUBLICATION_ONLY"
    private val sha256 = Regex("^[a-f0-9]{64}$")
    private val ingredientSha256 = Regex("^sha256:[a-f0-9]{64}$")
    private val inputDecimal = Regex("^\\d+(?:\\.\\d{1,6})?$")
    private val exactNutritionDecimal = Regex("^\\d+(?:\\.\\d{1,14})?$")
    private val yieldMethods = setOf("WEIGHED_FINAL_YIELD", "MEASURED_FINAL_YIELD")
    private val referenceUsages = setOf("REFERENCE_ONLY", "FITDESI_AUTHORED_DERIVATION")
    private val requiredCommercialEvidence = setOf(
        "VERIFIED_PER_100G_INGREDIENTS",
        "VERIFIED_COMMERCIAL_INGREDIENT_EVIDENCE",
        "EXPLICIT_GRAM_QUANTITIES",
        "EXPLICIT_YIELD_EVIDENCE",
        "APPROVED_RECIPE_REVIEW"
    )

    fun isValidFor(record: PakistaniFoodRecord): Boolean {
        val evidence = record.recipeEvidence ?: return false
        val nutritiveIngredients = evidence.ingredients.filter { it.type == "NUTRITIVE" }
        val processIngredients = evidence.ingredients.filter { it.type == "PROCESS" }
        val ingredientIds = nutritiveIngredients.mapNotNull { it.foodId?.takeIf(String::isNotBlank) }
        val processIds = processIngredients.mapNotNull { it.processId?.takeIf(String::isNotBlank) }
        val published = evidence.nutrition.publishedPer100g
        val rawTotals = evidence.nutrition.rawTotalsExact

        return evidence.evidenceSchemaVersion == EVIDENCE_SCHEMA_VERSION &&
            evidence.recipe.schemaVersion == RECIPE_SCHEMA_VERSION &&
            evidence.recipe.recipeVersion > 0 &&
            evidence.recipe.calculationVersion == CALCULATION_VERSION &&
            evidence.recipe.id.isNotBlank() && evidence.recipe.id == record.id &&
            record.runtimeSource == RUNTIME_SOURCE &&
            evidence.ingredients.isNotEmpty() &&
            nutritiveIngredients.isNotEmpty() &&
            nutritiveIngredients.size + processIngredients.size == evidence.ingredients.size &&
            ingredientIds.size == nutritiveIngredients.size &&
            ingredientIds.distinct().size == ingredientIds.size &&
            processIds.size == processIngredients.size &&
            processIds.distinct().size == processIds.size &&
            nutritiveIngredients.all(::isValidNutritiveIngredient) &&
            processIngredients.all(::isValidProcessIngredient) &&
            isPositiveInputDecimal(evidence.finalYield.finalYieldGrams) &&
            evidence.finalYield.evidence.method in yieldMethods &&
            evidence.finalYield.evidence.referenceIdentifier.isNotBlank() &&
            listOf(rawTotals.energyKcal, rawTotals.proteinGrams, rawTotals.carbsGrams, rawTotals.fatGrams)
                .all(::isValidExactNutrition) &&
            isValidPublishedNutrition(published) &&
            evidence.roundingPolicy.mode == ROUNDING_MODE &&
            evidence.roundingPolicy.boundary == ROUNDING_BOUNDARY &&
            evidence.roundingPolicy.energyDecimalPlaces == 0 &&
            evidence.roundingPolicy.macroDecimalPlaces == 2 &&
            evidence.provenance.authorship.authorName.isNotBlank() &&
            evidence.provenance.authorship.specificationType == AUTHORSHIP_TYPE &&
            evidence.provenance.authorship.sourceIdentifier.isNotBlank() &&
            evidence.provenance.references.isNotEmpty() &&
            evidence.provenance.references.all(::isValidReference) &&
            evidence.review.disposition == REVIEW_DISPOSITION &&
            evidence.review.reviewer.isNotBlank() &&
            evidence.review.reviewedAt.isNotBlank() &&
            evidence.commercialEligibility.eligible &&
            evidence.commercialEligibility.derivedFrom.toSet().containsAll(requiredCommercialEvidence) &&
            sha256.matches(evidence.evidenceSha256) &&
            record.servingSize == "100 g" &&
            published.energyKcal == record.calories.toDouble() &&
            published.proteinGrams == record.proteinGrams &&
            published.carbsGrams == record.carbsGrams &&
            published.fatGrams == record.fatGrams
    }

    private fun isValidNutritiveIngredient(ingredient: RecipeIngredientEvidence): Boolean =
        ingredient.foodId?.isNotBlank() == true &&
            ingredient.processId == null &&
            ingredient.processType == null &&
            isPositiveInputDecimal(ingredient.grams) &&
            ingredient.evidenceFingerprint?.let(ingredientSha256::matches) == true

    private fun isValidProcessIngredient(ingredient: RecipeIngredientEvidence): Boolean =
        ingredient.foodId == null &&
            ingredient.processId == "water" &&
            ingredient.processType == "WATER" &&
            isPositiveInputDecimal(ingredient.grams) &&
            ingredient.evidenceFingerprint == null

    private fun isPositiveInputDecimal(value: String): Boolean =
        inputDecimal.matches(value) && runCatching { BigDecimal(value).signum() > 0 }.getOrDefault(false)

    private fun isValidExactNutrition(value: String): Boolean =
        exactNutritionDecimal.matches(value) && runCatching { BigDecimal(value).signum() >= 0 }.getOrDefault(false)

    private fun isValidPublishedNutrition(nutrition: RecipePublishedNutritionEvidence): Boolean =
        nutrition.energyKcal.isFinite() && nutrition.energyKcal >= 0.0 && hasAtMostDecimalPlaces(nutrition.energyKcal, 0) &&
            nutrition.proteinGrams.isFinite() && nutrition.proteinGrams >= 0.0 && hasAtMostDecimalPlaces(nutrition.proteinGrams, 2) &&
            nutrition.carbsGrams.isFinite() && nutrition.carbsGrams >= 0.0 && hasAtMostDecimalPlaces(nutrition.carbsGrams, 2) &&
            nutrition.fatGrams.isFinite() && nutrition.fatGrams >= 0.0 && hasAtMostDecimalPlaces(nutrition.fatGrams, 2)

    private fun hasAtMostDecimalPlaces(value: Double, places: Int): Boolean =
        BigDecimal.valueOf(value).stripTrailingZeros().scale() <= places

    private fun isValidReference(reference: RecipeSourceReferenceEvidence): Boolean =
        reference.sourceName.isNotBlank() &&
            reference.sourceIdentifier.isNotBlank() &&
            reference.licenseIdentifier.isNotBlank() &&
            reference.usageClassification in referenceUsages
}

object FoodCommercialReleaseEligibilityPolicy {
    fun isEligible(record: PakistaniFoodRecord): Boolean =
        record.isLoggable &&
            record.licenceStatus == FoodCommercialEvidence.VERIFIED_LICENCE_STATUS &&
            record.fitDesiReviewStatus == FoodCommercialEvidence.VERIFIED_NUTRITION_REVIEW_STATUS &&
            record.nutritionBasis in FoodCommercialEvidence.acceptedNutritionBases &&
            (record.nutritionBasis != FoodCommercialEvidence.VERIFIED_RECIPE_YIELD_BASIS ||
                RecipeYieldEvidencePolicy.isValidFor(record)) &&
            record.calories in 1..3_000 &&
            record.proteinGrams.isFinite() && record.proteinGrams in 0.0..300.0 &&
            record.carbsGrams.isFinite() && record.carbsGrams in 0.0..500.0 &&
            record.fatGrams.isFinite() && record.fatGrams in 0.0..250.0
}

object FoodLoggingEligibilityPolicy {
    fun evaluate(record: PakistaniFoodRecord): FoodLoggingEligibility {
        if (!record.isLoggable) {
            return FoodLoggingEligibility(
                status = FoodLoggingStatus.REVIEW_REQUIRED,
                canLog = false,
                commercialReleaseEligible = false,
                explanation = "Nutrition review is required before logging becomes available."
            )
        }

        if (record.nutritionBasis == FoodCommercialEvidence.VERIFIED_RECIPE_YIELD_BASIS &&
            !RecipeYieldEvidencePolicy.isValidFor(record)
        ) {
            return FoodLoggingEligibility(
                status = FoodLoggingStatus.MORE_DATA_NEEDED,
                canLog = false,
                commercialReleaseEligible = false,
                explanation = "Verified recipe evidence is incomplete or inconsistent."
            )
        }

        val macrosAreUsable = listOf(record.proteinGrams, record.carbsGrams, record.fatGrams)
            .all { value -> value.isFinite() && value >= 0.0 }
        val missingReason = when {
            record.calories <= 0 -> "A positive calorie value is required before this food can be logged."
            !macrosAreUsable -> "Valid protein, carbohydrate, and fat values are required before logging."
            record.nutritionBasis.isBlank() -> "A nutrition reference basis is required before this food can be logged."
            else -> null
        }
        if (missingReason != null) {
            return FoodLoggingEligibility(
                status = FoodLoggingStatus.MORE_DATA_NEEDED,
                canLog = false,
                commercialReleaseEligible = false,
                explanation = missingReason
            )
        }

        val commercialReleaseEligible = FoodCommercialReleaseEligibilityPolicy.isEligible(record)
        return if (commercialReleaseEligible) {
            FoodLoggingEligibility(
                status = FoodLoggingStatus.VERIFIED,
                canLog = true,
                commercialReleaseEligible = true,
                explanation = "Verified nutrition is available."
            )
        } else {
            FoodLoggingEligibility(
                status = FoodLoggingStatus.LEGACY_ESTIMATE,
                canLog = true,
                commercialReleaseEligible = false,
                explanation = "FitDesi estimate; nutrition source review is pending."
            )
        }
    }
}

object FoodCatalogueParser {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(value: String): PakistaniFoodCatalogue {
        val catalogue = json.decodeFromString<PakistaniFoodCatalogue>(value)
        require(catalogue.schemaVersion == 1) { "Unsupported Pakistani food catalogue schema." }
        require(catalogue.recordCount == catalogue.records.size) { "Food catalogue count mismatch." }
        require(catalogue.records.map(PakistaniFoodRecord::id).distinct().size == catalogue.records.size) {
            "Food catalogue contains duplicate IDs."
        }
        require(catalogue.reviewedLoggableCount == catalogue.records.count(PakistaniFoodRecord::isLoggable)) {
            "Loggable food count mismatch."
        }
        require(catalogue.reviewRequiredCount == catalogue.records.count { !it.isLoggable }) {
            "Review-required food count mismatch."
        }
        catalogue.records.forEach(::validateRecord)
        return catalogue
    }

    private fun validateRecord(record: PakistaniFoodRecord) {
        require(record.id.isNotBlank() && record.name.isNotBlank()) { "Food ID and name are required." }
        require(record.category in FoodCatalogueSearch.TRACKER_CATEGORIES) { "Unsupported food category." }
        require(record.servingSize.isNotBlank() && record.nutritionBasis.isNotBlank()) {
            "Food nutrition basis is required."
        }
        require(record.calories in 0..3_000) { "Invalid food calories." }
        require(record.proteinGrams in 0.0..300.0) { "Invalid food protein." }
        require(record.carbsGrams in 0.0..500.0) { "Invalid food carbohydrate." }
        require(record.fatGrams in 0.0..250.0) { "Invalid food fat." }
        require(record.aliases.all(String::isNotBlank)) { "Malformed food alias." }
        require(record.dietaryClassification in setOf("VEGAN", "VEGETARIAN", "EGG", "MEAT", "UNKNOWN")) {
            "Invalid dietary classification."
        }
        if (!record.isLoggable) {
            require(record.fitDesiReviewStatus == "NEEDS_FITDESI_NUTRITION_REVIEW") {
                "Unreviewed food status is invalid."
            }
            require(record.runtimeSource == "IMPORTED_REVIEW_REQUIRED") {
                "Unreviewed food source is invalid."
            }
            require(record.importedProvenance.isNotEmpty()) { "Unreviewed food provenance is required." }
        }
        record.importedProvenance.forEach { source ->
            require(source.sourceRepository.isNotBlank() && source.sourceFile.isNotBlank()) {
                "Food source metadata is incomplete."
            }
            require(source.confidence in 0.0..1.0) { "Food source confidence is invalid." }
            require(source.nutritionBasis.isNotBlank()) { "Source nutrition basis is required." }
        }
        if (record.runtimeSource.startsWith("USDA_") && record.runtimeSource !in DirectUsdaFdcEvidence.acceptedRuntimeSources) {
            require(false) { "Unsupported USDA runtime source." }
        }
        if (record.runtimeSource == UsdaFoundationEvidence.RUNTIME_SOURCE) {
            validateUsdaFoundationRecord(record)
        }
        DirectUsdaFdcEvidence.policies[record.runtimeSource]?.let { policy ->
            validateDirectUsdaFdcRecord(record, policy)
        }
        if (record.nutritionBasis == FoodCommercialEvidence.VERIFIED_RECIPE_YIELD_BASIS) {
            require(RecipeYieldEvidencePolicy.isValidFor(record)) {
                "Verified recipe-yield evidence is incomplete or inconsistent."
            }
        } else {
            require(record.recipeEvidence == null) { "Recipe evidence requires the verified recipe-yield nutrition basis." }
        }
    }

    private fun validateUsdaFoundationRecord(record: PakistaniFoodRecord) {
        require(FoodCommercialReleaseEligibilityPolicy.isEligible(record)) {
            "USDA Foundation Food commercial evidence is incomplete."
        }
        require(record.servingSize == "100 g" && record.nutritionBasis == FoodCommercialEvidence.VERIFIED_PER_100G_BASIS) {
            "USDA Foundation Food basis must be verified per 100 g."
        }
        require(record.importedProvenance.size == 1) { "USDA Foundation Food requires one exact provenance record." }
        val source = record.importedProvenance.single()
        require(source.sourceProvider == UsdaFoundationEvidence.SOURCE_PROVIDER) { "USDA source provider is incomplete." }
        require(source.dataType == UsdaFoundationEvidence.DATA_TYPE) { "USDA data type is incomplete." }
        require(source.release == UsdaFoundationEvidence.RELEASE) { "USDA release is incomplete." }
        require(source.sourceDescription?.isNotBlank() == true) { "USDA source description is incomplete." }
        require(source.sourceUrl == "https://fdc.nal.usda.gov/") { "USDA source URL is incomplete." }
        require(source.sourceFileHashes == UsdaFoundationEvidence.sourceFileHashes) { "USDA source hashes are incomplete." }
        require(source.retrievalDate == UsdaFoundationEvidence.RETRIEVAL_DATE) { "USDA retrieval date is incomplete." }
        require(source.foodState?.isNotBlank() == true) { "USDA food state is incomplete." }
        require(source.transformationHistory?.isNotBlank() == true) { "USDA transformation history is incomplete." }
        require(source.fitDesiReviewDisposition == UsdaFoundationEvidence.REVIEW_DISPOSITION) {
            "USDA FitDesi review disposition is incomplete."
        }
        require(source.repositoryLicense == UsdaFoundationEvidence.LICENCE) { "USDA public-domain status is incomplete." }
        require(source.licenceStatus == record.licenceStatus) { "USDA licence status does not match the food record." }
        require(source.fitDesiReviewStatus == record.fitDesiReviewStatus) { "USDA review status does not match the food record." }
        require(source.nutritionBasis == record.nutritionBasis) { "USDA nutrition basis does not match the food record." }
        require(source.originalSourceId.isNotBlank() && record.id == "fd-food-usda-${source.originalSourceId}") {
            "USDA FDC identity does not match the FitDesi ID."
        }
        require(source.calorieNutrientId == UsdaFoundationEvidence.CALORIE_NUTRIENT_ID) { "USDA calorie nutrient ID is invalid." }
        require(source.proteinNutrientId == UsdaFoundationEvidence.PROTEIN_NUTRIENT_ID) { "USDA protein nutrient ID is invalid." }
        require(source.fatNutrientId == UsdaFoundationEvidence.FAT_NUTRIENT_ID) { "USDA fat nutrient ID is invalid." }
        require(source.carbohydrateNutrientId == UsdaFoundationEvidence.CARBOHYDRATE_NUTRIENT_ID) {
            "USDA carbohydrate nutrient ID is invalid."
        }
        require(source.sourceCaloriesKcal?.isFinite() == true && source.sourceCaloriesKcal.roundToInt() == record.calories) {
            "USDA source calories do not reproduce the runtime value."
        }
        require(source.sourceProteinGrams?.isFinite() == true && source.sourceProteinGrams == record.proteinGrams) {
            "USDA source protein does not reproduce the runtime value."
        }
        require(source.sourceCarbsGrams?.isFinite() == true && source.sourceCarbsGrams == record.carbsGrams) {
            "USDA source carbohydrate does not reproduce the runtime value."
        }
        require(source.sourceFatGrams?.isFinite() == true && source.sourceFatGrams == record.fatGrams) {
            "USDA source fat does not reproduce the runtime value."
        }
    }

    private fun validateDirectUsdaFdcRecord(record: PakistaniFoodRecord, policy: DirectUsdaFdcEvidence.Policy) {
        require(FoodCommercialReleaseEligibilityPolicy.isEligible(record)) {
            "Direct USDA FDC commercial evidence is incomplete."
        }
        require(record.servingSize == "100 g" && record.nutritionBasis == FoodCommercialEvidence.VERIFIED_PER_100G_BASIS) {
            "Direct USDA FDC basis must be verified per 100 g."
        }
        require(record.nutritionDisplayLabel == policy.nutritionDisplayLabel) {
            "Direct USDA FDC display classification is invalid."
        }
        require(record.importedProvenance.size == 1) { "Direct USDA FDC food requires one exact provenance record." }
        val source = record.importedProvenance.single()
        require(source.sourceProvider == DirectUsdaFdcEvidence.SOURCE_PROVIDER) { "Direct USDA provider is incomplete." }
        require(source.dataType == policy.dataType && source.evidenceClass == policy.evidenceClass) {
            "Direct USDA source class is invalid."
        }
        require(source.release == policy.release) { "Direct USDA release is invalid." }
        require(source.importClassification == policy.importClassification) { "Direct USDA import classification is invalid." }
        require(source.provenanceWarning == policy.provenanceWarning) { "Direct USDA provenance warning is invalid." }
        require(source.sourceDescription?.isNotBlank() == true && source.sourcePublicationDate?.isNotBlank() == true) {
            "Direct USDA source identity is incomplete."
        }
        require(source.sourceUrl == DirectUsdaFdcEvidence.SOURCE_URL) { "Direct USDA source URL is incomplete." }
        require(
            source.sourceFileHashes == mapOf(
                policy.archiveFilename to policy.archiveSha256,
                "selection-scoped-records" to DirectUsdaFdcEvidence.SELECTION_EVIDENCE_SHA256
            )
        ) { "Direct USDA source hashes are invalid." }
        require(source.retrievalDate == DirectUsdaFdcEvidence.RETRIEVAL_DATE) { "Direct USDA retrieval date is invalid." }
        require(source.foodState?.isNotBlank() == true && source.transformationHistory?.isNotBlank() == true) {
            "Direct USDA state/transformation evidence is incomplete."
        }
        require(source.fitDesiReviewDisposition == DirectUsdaFdcEvidence.REVIEW_DISPOSITION) {
            "Direct USDA review disposition is incomplete."
        }
        require(source.repositoryLicense == DirectUsdaFdcEvidence.LICENCE) { "Direct USDA public-domain status is incomplete." }
        require(source.licenceStatus == record.licenceStatus && source.fitDesiReviewStatus == record.fitDesiReviewStatus) {
            "Direct USDA commercial/review evidence does not match the record."
        }
        require(source.nutritionBasis == record.nutritionBasis) { "Direct USDA nutrition basis does not match the record." }
        require(source.originalSourceId.isNotBlank() && record.id == "fd-food-usda-${source.originalSourceId}") {
            "Direct USDA FDC identity does not match the FitDesi ID."
        }
        require(source.calorieNutrientId == DirectUsdaFdcEvidence.CALORIE_NUTRIENT_ID) { "Direct USDA calorie nutrient ID is invalid." }
        require(source.proteinNutrientId == DirectUsdaFdcEvidence.PROTEIN_NUTRIENT_ID) { "Direct USDA protein nutrient ID is invalid." }
        require(source.carbohydrateNutrientId == DirectUsdaFdcEvidence.CARBOHYDRATE_NUTRIENT_ID) { "Direct USDA carbohydrate nutrient ID is invalid." }
        require(source.fatNutrientId == DirectUsdaFdcEvidence.FAT_NUTRIENT_ID) { "Direct USDA fat nutrient ID is invalid." }
        val expectedRecordSha256 = DirectUsdaFdcEvidence.sourceRecordSha256ByFdcId[source.originalSourceId]
        require(expectedRecordSha256 != null && source.sourceRecordSha256 == expectedRecordSha256) {
            "Direct USDA source record identity or fingerprint is invalid."
        }
        require(source.sourceCaloriesKcal?.isFinite() == true && source.sourceCaloriesKcal.roundToInt() == record.calories) {
            "Direct USDA source calories do not reproduce the runtime value."
        }
        require(source.sourceProteinGrams?.isFinite() == true && source.sourceProteinGrams == record.proteinGrams) {
            "Direct USDA source protein does not reproduce the runtime value."
        }
        require(source.sourceCarbsGrams?.isFinite() == true && source.sourceCarbsGrams == record.carbsGrams) {
            "Direct USDA source carbohydrate does not reproduce the runtime value."
        }
        require(source.sourceFatGrams?.isFinite() == true && source.sourceFatGrams == record.fatGrams) {
            "Direct USDA source fat does not reproduce the runtime value."
        }

        val requiredDerivations = setOf(
            DirectUsdaFdcEvidence.CALORIE_NUTRIENT_ID,
            DirectUsdaFdcEvidence.PROTEIN_NUTRIENT_ID,
            DirectUsdaFdcEvidence.CARBOHYDRATE_NUTRIENT_ID,
            DirectUsdaFdcEvidence.FAT_NUTRIENT_ID
        )
        require(source.nutrientDerivationCodes.keys == requiredDerivations) {
            "Direct USDA nutrient derivation evidence is incomplete."
        }
        if (policy.evidenceClass == "BRANDED_LABEL") {
            require(record.id == "fd-food-usda-1886335") { "Unexpected Branded food identity." }
            require(source.brandOwner == "Multicom Publishing Incorporated" && source.brandName == "DIYA") {
                "Branded manufacturer identity is incomplete."
            }
            require(source.ingredientStatement == "SPLIT MOONG BEANS (WITHOUT SKIN)") {
                "Branded ingredient declaration is incomplete."
            }
            require(source.sourceServingSize == 112.0 && source.sourceServingUnit == "g") {
                "Branded serving evidence is incomplete."
            }
            require(source.nutrientDerivationCodes.values.all { it == "LCCS" }) {
                "Branded nutrients must remain manufacturer-label-serving-derived."
            }
        } else {
            require(source.brandOwner == null && source.brandName == null && source.ingredientStatement == null) {
                "Non-Branded USDA evidence must not claim manufacturer identity."
            }
        }
    }
}

object FoodCatalogueSearch {
    val TRACKER_CATEGORIES = listOf(
        "Breads", "Grains & Rice", "Lentils & Legumes", "Vegetable Dishes",
        "Protein & Main Dishes", "Snacks & Street Food", "Drinks", "Desserts",
        "Dairy & Sides", "Breakfast", "Fats & Oils"
    )

    fun matches(name: String, aliases: List<String>, category: String, query: String): Boolean {
        val needle = normalize(query)
        if (needle.isBlank()) return true
        return sequenceOf(name, category).plus(aliases.asSequence())
            .map(::normalize)
            .any { searchable -> needle in searchable || searchable in needle }
    }

    fun mapLegacyCategory(value: String): String = when (value) {
        "Grains" -> "Grains & Rice"
        "Main Dishes", "Seafood" -> "Protein & Main Dishes"
        "Legumes" -> "Lentils & Legumes"
        "Vegetables" -> "Vegetable Dishes"
        "Snacks" -> "Snacks & Street Food"
        "Beverages" -> "Drinks"
        "Dairy" -> "Dairy & Sides"
        else -> value
    }

    fun normalize(value: String): String = java.text.Normalizer
        .normalize(value, java.text.Normalizer.Form.NFKD)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\bdaal\\b"), "dal")
        .replace(Regex("\\bchanay\\b"), "chana")
        .replace(Regex("\\bcholay\\b"), "chole")
        .replace(Regex("\\bbhallay\\b"), "bhalla")
        .replace(Regex("\\bjaman\\b"), "jamun")
        .replace(Regex("\\bkhorma\\b"), "khurma")
        .replace(Regex("\\bparantha\\b"), "paratha")
        .replace(Regex("\\bchappati\\b"), "chapati")
        .replace(Regex("\\bqeema\\b|\\bkheema\\b"), "keema")
        .replace(Regex("\\bmattar\\b"), "matar")
        .replace(Regex("\\bpakoray\\b"), "pakora")
        .replace(Regex("\\brasmalai\\b"), "ras malai")
        .replace(Regex("\\bgolgappa\\b"), "gol gappa")
        .replace(Regex("\\s+"), " ")
        .trim()
}

// Layer B — FitDesi South Asian discovery: cultural food identity metadata ONLY.
// This model intentionally has no calorie, macronutrient, serving-nutrition,
// provenance, or loggable fields, so a discovery record cannot be converted into
// the nutrition/logging model without an explicit future verified-promotion process.
@Serializable
data class SouthAsianFoodDiscoveryCatalogue(
    val schemaVersion: Int,
    val discoveryCatalogueId: String,
    val generatedAt: String,
    val recordCount: Int,
    val nutritionStatus: String,
    val records: List<SouthAsianFoodDiscoveryRecord>
)

@Serializable
data class SouthAsianFoodDiscoveryRecord(
    val id: String,
    val name: String,
    val category: String,
    val cuisine: String,
    val nutritionStatus: String
)

object SouthAsianFoodDiscovery {
    const val ID_PREFIX = "fd-discovery-"
    const val NUTRITION_VERIFICATION_IN_PROGRESS = "NUTRITION_VERIFICATION_IN_PROGRESS"
}

object SouthAsianFoodDiscoveryParser {
    // Strict: any unexpected key (e.g. calories/protein/importedProvenance) fails the parse,
    // so nutrition can never be smuggled into a discovery record.
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(value: String): SouthAsianFoodDiscoveryCatalogue {
        val catalogue = json.decodeFromString<SouthAsianFoodDiscoveryCatalogue>(value)
        require(catalogue.schemaVersion == 1) { "Unsupported South Asian discovery schema." }
        require(catalogue.recordCount == catalogue.records.size) { "Discovery catalogue count mismatch." }
        require(catalogue.nutritionStatus == SouthAsianFoodDiscovery.NUTRITION_VERIFICATION_IN_PROGRESS) {
            "Discovery catalogue status is invalid."
        }
        require(catalogue.records.map(SouthAsianFoodDiscoveryRecord::id).distinct().size == catalogue.records.size) {
            "Discovery catalogue contains duplicate IDs."
        }
        catalogue.records.forEach(::validateRecord)
        return catalogue
    }

    private fun validateRecord(record: SouthAsianFoodDiscoveryRecord) {
        require(record.id.startsWith(SouthAsianFoodDiscovery.ID_PREFIX) && record.id.isNotBlank()) {
            "Discovery IDs must be non-blank and use the fd-discovery- prefix."
        }
        require(record.name.isNotBlank()) { "Discovery name is required." }
        require(record.category.isNotBlank()) { "Discovery category is required." }
        require(record.cuisine.isNotBlank()) { "Discovery cuisine is required." }
        require(record.nutritionStatus == SouthAsianFoodDiscovery.NUTRITION_VERIFICATION_IN_PROGRESS) {
            "Discovery records must remain nutrition-unverified."
        }
    }
}
