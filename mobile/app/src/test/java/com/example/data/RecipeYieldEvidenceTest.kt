package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeYieldEvidenceTest {
    @Test
    fun `Node generated synthetic khichdi projection parses through the Android recipe gate`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource(
            "stage9b-synthetic-khichdi-android-catalogue.json"
        )).readText()
        val record = parseRecord(fixture)
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(record)

        assertEquals("fd-food-recipe-test-only-plain-rice-lentil-khichdi", record.id)
        assertEquals("VERIFIED_RECIPE_YIELD", record.nutritionBasis)
        assertEquals("FITDESI_RECIPE_YIELD_VERIFIED", record.runtimeSource)
        assertEquals(FoodLoggingStatus.VERIFIED, eligibility.status)
        assertTrue(eligibility.canLog)
        assertTrue(eligibility.commercialReleaseEligible)
    }

    @Test
    fun `complete Stage 9A-1 shaped recipe evidence parses and qualifies`() {
        val record = parseRecord(recipeCatalogueJson())
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(record)

        assertEquals("fd-food-recipe-synthetic-stew", record.id)
        assertEquals(70, record.calories)
        assertEquals(7.26, record.proteinGrams, 0.0)
        assertEquals(14.37, record.carbsGrams, 0.0)
        assertEquals(1.7, record.fatGrams, 0.0)
        assertEquals(FoodLoggingStatus.VERIFIED, eligibility.status)
        assertTrue(eligibility.canLog)
        assertTrue(eligibility.commercialReleaseEligible)
    }

    @Test
    fun `recipe basis token without recipe evidence fails closed`() {
        assertRecipeRejected(recipeCatalogueJson(recipeEvidence = null))

        val tokenOnly = basicRecord(
            id = "fd-food-recipe-token-only",
            nutritionBasis = "VERIFIED_RECIPE_YIELD",
            isLoggable = true,
            reviewStatus = "FITDESI_NUTRITION_VERIFIED",
            licenceStatus = "VERIFIED_COMMERCIAL"
        )
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(tokenOnly)
        assertEquals(FoodLoggingStatus.MORE_DATA_NEEDED, eligibility.status)
        assertFalse(eligibility.canLog)
        assertFalse(eligibility.commercialReleaseEligible)
        assertFalse(FoodCommercialReleaseEligibilityPolicy.isEligible(tokenOnly))
    }

    @Test
    fun `unsupported recipe evidence and calculation versions fail closed`() {
        listOf(
            recipeEvidenceJson(evidenceSchemaVersion = 2),
            recipeEvidenceJson(recipeSchemaVersion = 2),
            recipeEvidenceJson(calculationVersion = "FITDESI_RECIPE_YIELD_V2")
        ).forEach { evidence -> assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence)) }
    }

    @Test
    fun `recipe evidence identity must be present and match the owning record`() {
        listOf("", "fd-food-recipe-different").forEach { recipeId ->
            assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(recipeId = recipeId)))
        }
    }

    @Test
    fun `blank and duplicate nutritive ingredient IDs fail closed`() {
        val blank = ingredientJson(foodId = "")
        val duplicate = "${ingredientJson()},${ingredientJson(grams = "49.5")}"
        val duplicateProcess = "${ingredientJson()},${processWaterJson()},${processWaterJson()}"

        assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(ingredients = blank)))
        assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(ingredients = duplicate)))
        assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(ingredients = duplicateProcess)))
    }

    @Test
    fun `invalid ingredient quantities and fingerprints fail closed`() {
        listOf("", "0", "-1", "NaN").forEach { grams ->
            val evidence = recipeEvidenceJson(ingredients = ingredientJson(grams = grams))
            assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence))
        }
        listOf("", "sha256:not-a-checksum", "a".repeat(64)).forEach { fingerprint ->
            val evidence = recipeEvidenceJson(ingredients = ingredientJson(fingerprint = fingerprint))
            assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence))
        }
    }

    @Test
    fun `invalid final yield fails closed`() {
        listOf("", "0", "-1", "Infinity").forEach { yieldGrams ->
            val evidence = recipeEvidenceJson(finalYieldGrams = yieldGrams)
            assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence))
        }
    }

    @Test
    fun `missing negative and non-finite recipe nutrition fails closed`() {
        assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(nutrition = null)))
        assertRecipeRejected(
            recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(nutrition = nutritionJson(proteinGrams = "-0.01")))
        )
        assertRecipeRejected(
            recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(nutrition = nutritionJson(proteinGrams = "1e9999")))
        )
    }

    @Test
    fun `packaged nutrition must match recipe publication values`() {
        listOf(
            recipeCatalogueJson(calories = 71),
            recipeCatalogueJson(proteinGrams = "7.27"),
            recipeCatalogueJson(carbsGrams = "14.38"),
            recipeCatalogueJson(fatGrams = "1.71")
        ).forEach(::assertRecipeRejected)
    }

    @Test
    fun `publication values must follow the Stage 9A-1 rounding boundary`() {
        assertRecipeRejected(
            recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(nutrition = nutritionJson(energyKcal = "70.4")))
        )
        assertRecipeRejected(
            recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(nutrition = nutritionJson(proteinGrams = "7.261")))
        )
    }

    @Test
    fun `missing or malformed evidence hash fails closed`() {
        listOf("", "sha256:${"b".repeat(64)}", "b".repeat(63), "B".repeat(64)).forEach { hash ->
            assertRecipeRejected(recipeCatalogueJson(recipeEvidence = recipeEvidenceJson(evidenceHash = hash)))
        }
    }

    @Test
    fun `missing authorship source or yield evidence fails closed`() {
        listOf(
            recipeEvidenceJson(includeAuthorship = false),
            recipeEvidenceJson(includeReferences = false),
            recipeEvidenceJson(includeYieldEvidence = false)
        ).forEach { evidence -> assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence)) }
    }

    @Test
    fun `unapproved review and insufficient derived commercial evidence fail closed`() {
        listOf(
            recipeEvidenceJson(reviewDisposition = "DRAFT"),
            recipeEvidenceJson(commercialEligible = false),
            recipeEvidenceJson(derivedCommercialEvidence = listOf("APPROVED_RECIPE_REVIEW"))
        ).forEach { evidence -> assertRecipeRejected(recipeCatalogueJson(recipeEvidence = evidence)) }
    }

    @Test
    fun `verified per 100g legacy and review-required policies remain unchanged`() {
        val verifiedPer100g = basicRecord(
            id = "synthetic-usda",
            nutritionBasis = "VERIFIED_PER_100G",
            isLoggable = true,
            reviewStatus = "FITDESI_NUTRITION_VERIFIED",
            licenceStatus = "VERIFIED_COMMERCIAL"
        )
        val legacy = basicRecord(
            id = "synthetic-legacy",
            nutritionBasis = "EXISTING_FITDESI_SERVING_ESTIMATE",
            isLoggable = true,
            reviewStatus = "EXISTING_FITDESI_LOGGABLE",
            licenceStatus = "UNKNOWN"
        )
        val reviewRequired = basicRecord(
            id = "synthetic-review",
            nutritionBasis = "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G",
            isLoggable = false,
            reviewStatus = "NEEDS_FITDESI_NUTRITION_REVIEW",
            licenceStatus = "NOT_VERIFIED"
        )

        assertEquals(FoodLoggingStatus.VERIFIED, FoodLoggingEligibilityPolicy.evaluate(verifiedPer100g).status)
        assertEquals(FoodLoggingStatus.LEGACY_ESTIMATE, FoodLoggingEligibilityPolicy.evaluate(legacy).status)
        assertEquals(FoodLoggingStatus.REVIEW_REQUIRED, FoodLoggingEligibilityPolicy.evaluate(reviewRequired).status)
    }

    private fun parseRecord(json: String): PakistaniFoodRecord = FoodCatalogueParser.parse(json).records.single()

    private fun assertRecipeRejected(json: String) {
        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(json) }
    }

    private fun recipeCatalogueJson(
        calories: Int = 70,
        proteinGrams: String = "7.26",
        carbsGrams: String = "14.37",
        fatGrams: String = "1.7",
        recipeEvidence: String? = recipeEvidenceJson()
    ): String {
        val evidenceField = recipeEvidence?.let { ",\"recipeEvidence\":$it" }.orEmpty()
        return """
            {
              "schemaVersion":1,
              "catalogueId":"synthetic-recipe-catalogue",
              "generatedAt":"2026-08-29",
              "recordCount":1,
              "reviewedLoggableCount":1,
              "reviewRequiredCount":0,
              "sourceCandidateCount":0,
              "categoryTotals":{"Protein & Main Dishes":1},
              "ambiguousAliases":{},
              "records":[{
                "id":"fd-food-recipe-synthetic-stew",
                "name":"Synthetic Verified Stew",
                "aliases":["synthetic stew"],
                "category":"Protein & Main Dishes",
                "originalCategory":"FitDesi-authored recipe",
                "servingSize":"100 g",
                "calories":$calories,
                "proteinGrams":$proteinGrams,
                "carbsGrams":$carbsGrams,
                "fatGrams":$fatGrams,
                "nutritionBasis":"VERIFIED_RECIPE_YIELD",
                "nutritionDisplayLabel":"FitDesi verified recipe yield • per 100 g",
                "fitDesiReviewStatus":"FITDESI_NUTRITION_VERIFIED",
                "isLoggable":true,
                "runtimeSource":"FITDESI_RECIPE_YIELD_VERIFIED",
                "dietaryClassification":"VEGAN",
                "licenceStatus":"VERIFIED_COMMERCIAL",
                "importedProvenance":[]$evidenceField
              }]
            }
        """.trimIndent()
    }

    private fun recipeEvidenceJson(
        evidenceSchemaVersion: Int = 1,
        recipeId: String = "fd-food-recipe-synthetic-stew",
        recipeSchemaVersion: Int = 1,
        calculationVersion: String = "FITDESI_RECIPE_YIELD_V1",
        ingredients: String = "${ingredientJson()},${processWaterJson()}",
        finalYieldGrams: String = "250",
        includeYieldEvidence: Boolean = true,
        nutrition: String? = nutritionJson(),
        evidenceHash: String = "b".repeat(64),
        includeAuthorship: Boolean = true,
        includeReferences: Boolean = true,
        reviewDisposition: String = "APPROVED",
        commercialEligible: Boolean = true,
        derivedCommercialEvidence: List<String> = REQUIRED_COMMERCIAL_EVIDENCE
    ): String {
        val yieldEvidence = if (includeYieldEvidence) {
            ",\"evidence\":{\"method\":\"WEIGHED_FINAL_YIELD\",\"referenceIdentifier\":\"synthetic:yield:250g\"}"
        } else {
            ""
        }
        val nutritionField = nutrition?.let { ",\"nutrition\":$it" }.orEmpty()
        val authorshipField = if (includeAuthorship) {
            "\"authorship\":{\"authorName\":\"FitDesi synthetic authors\",\"specificationType\":\"FITDESI_AUTHORED_RECIPE\",\"sourceIdentifier\":\"fitdesi:synthetic-recipe-v1\"}"
        } else {
            ""
        }
        val referencesField = if (includeReferences) {
            "${if (includeAuthorship) "," else ""}\"references\":[{\"sourceName\":\"Synthetic recipe reference\",\"sourceIdentifier\":\"synthetic:reference:1\",\"licenseIdentifier\":\"CC0-1.0\",\"usageClassification\":\"REFERENCE_ONLY\"}]"
        } else {
            ""
        }
        val derived = derivedCommercialEvidence.joinToString(",") { "\"$it\"" }
        return """
            {
              "evidenceSchemaVersion":$evidenceSchemaVersion,
              "recipe":{"id":"$recipeId","schemaVersion":$recipeSchemaVersion,"recipeVersion":1,"calculationVersion":"$calculationVersion"},
              "ingredients":[$ingredients],
              "yield":{"finalYieldGrams":"$finalYieldGrams"$yieldEvidence}$nutritionField,
              "roundingPolicy":{"mode":"ROUND_HALF_UP_NON_NEGATIVE","boundary":"FINAL_PUBLICATION_ONLY","energyDecimalPlaces":0,"macroDecimalPlaces":2},
              "provenance":{$authorshipField$referencesField},
              "review":{"disposition":"$reviewDisposition","reviewer":"FitDesi synthetic reviewer","reviewedAt":"2026-08-29"},
              "commercialEligibility":{"eligible":$commercialEligible,"derivedFrom":[$derived]},
              "evidenceSha256":"$evidenceHash"
            }
        """.trimIndent()
    }

    private fun ingredientJson(
        foodId: String = "fd-food-usda-synthetic-a",
        grams: String = "150.5",
        fingerprint: String = "sha256:${"a".repeat(64)}"
    ): String = """
        {"type":"NUTRITIVE","foodId":"$foodId","grams":"$grams","evidenceFingerprint":"$fingerprint"}
    """.trimIndent()

    private fun processWaterJson(): String = """
        {"type":"PROCESS","processId":"water","processType":"WATER","grams":"100"}
    """.trimIndent()

    private fun nutritionJson(
        energyKcal: String = "70",
        proteinGrams: String = "7.26",
        carbsGrams: String = "14.37",
        fatGrams: String = "1.7"
    ): String = """
        {
          "rawTotalsExact":{"energyKcal":"176.12625","proteinGrams":"18.14875","carbsGrams":"35.92625","fatGrams":"4.2525"},
          "publishedPer100g":{"energyKcal":$energyKcal,"proteinGrams":$proteinGrams,"carbsGrams":$carbsGrams,"fatGrams":$fatGrams}
        }
    """.trimIndent()

    private fun basicRecord(
        id: String,
        nutritionBasis: String,
        isLoggable: Boolean,
        reviewStatus: String,
        licenceStatus: String
    ) = PakistaniFoodRecord(
        id = id,
        name = "Synthetic food",
        aliases = emptyList(),
        category = "Protein & Main Dishes",
        originalCategory = "Synthetic",
        servingSize = "100 g",
        calories = 100,
        proteinGrams = 10.0,
        carbsGrams = 20.0,
        fatGrams = 5.0,
        nutritionBasis = nutritionBasis,
        nutritionDisplayLabel = "Synthetic",
        fitDesiReviewStatus = reviewStatus,
        isLoggable = isLoggable,
        runtimeSource = "SYNTHETIC_TEST",
        dietaryClassification = "VEGAN",
        licenceStatus = licenceStatus,
        importedProvenance = emptyList()
    )

    private companion object {
        val REQUIRED_COMMERCIAL_EVIDENCE = listOf(
            "VERIFIED_PER_100G_INGREDIENTS",
            "VERIFIED_COMMERCIAL_INGREDIENT_EVIDENCE",
            "EXPLICIT_GRAM_QUANTITIES",
            "EXPLICIT_YIELD_EVIDENCE",
            "APPROVED_RECIPE_REVIEW"
        )
    }
}
