package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runtime contract for the PUBLIC-SAFE verified nutrition projection.
 *
 * The historical 165-record engineering catalogue (74 legacy + 52 review-required + 39 verified)
 * and its dfd9d6... checksum remain governed by tools/knowledge/validate/validate-food-catalogue.mjs
 * and the Node stage tests. This suite asserts only what the shipping runtime loads: the 39
 * commercially verified records plus the separate South Asian discovery layer.
 */
@RunWith(RobolectricTestRunner::class)
class FoodCatalogueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val foods by lazy { FoodRepository(context).getPakistaniFoods() }
    private val records by lazy { FoodRepository(context).getPakistaniFoodRecords() }

    @Test
    fun `public safe nutrition catalogue contains only verified commercially eligible records`() {
        assertEquals(39, foods.size)
        assertEquals(39, foods.map { it.id }.distinct().size)
        assertTrue(foods.all { it.isLoggable })
        assertTrue(foods.all { it.loggingEligibility.status == FoodLoggingStatus.VERIFIED })
        assertTrue(foods.all { it.loggingEligibility.canLog })
        assertTrue(foods.all { it.loggingEligibility.commercialReleaseEligible })
        assertTrue(records.all(FoodCommercialReleaseEligibilityPolicy::isEligible))
        assertTrue(records.all { it.licenceStatus == "VERIFIED_COMMERCIAL" })
        assertTrue(records.all { it.fitDesiReviewStatus == "FITDESI_NUTRITION_VERIFIED" })
        assertTrue(records.all { it.nutritionBasis == "VERIFIED_PER_100G" })
        assertTrue(records.all { it.servingSize == "100 g" })
        assertTrue(records.all { it.id == "fd-food-usda-${it.importedProvenance.single().originalSourceId}" })
    }

    @Test
    fun `no unresolved legacy review-required unknown or Nourish records remain at runtime`() {
        assertEquals(0, foods.count { it.loggingEligibility.status == FoodLoggingStatus.LEGACY_ESTIMATE })
        assertEquals(0, foods.count { it.loggingEligibility.status == FoodLoggingStatus.REVIEW_REQUIRED })
        assertEquals(0, foods.count { it.loggingEligibility.status == FoodLoggingStatus.MORE_DATA_NEEDED })
        assertEquals(0, records.count { it.runtimeSource == "IMPORTED_REVIEW_REQUIRED" })
        assertEquals(0, records.count { it.licenceStatus == "UNKNOWN" })
        assertEquals(0, records.count { it.fitDesiReviewStatus == "NEEDS_FITDESI_NUTRITION_REVIEW" })
        assertEquals(0, records.count { it.nutritionBasis == "EXISTING_FITDESI_SERVING_ESTIMATE" })
        assertTrue(records.all { it.id.startsWith("fd-food-usda-") })
        val provenance = records.flatMap { it.importedProvenance }
        assertTrue(
            "No unresolved Nourish provenance may remain",
            provenance.none {
                it.sourceRepository.contains("Nourish", ignoreCase = true) ||
                    it.sourceFile.contains("Nourish", ignoreCase = true)
            }
        )
    }

    @Test
    fun `direct FDC source classes are explicitly verified and commercially eligible`() {
        val expectedCounts = mapOf(
            "USDA_FOUNDATION_VERIFIED" to 15,
            "USDA_FNDDS_VERIFIED" to 11,
            "USDA_SR_LEGACY_VERIFIED" to 12,
            "USDA_BRANDED_LABEL_VERIFIED" to 1
        )

        assertEquals(39, records.size)
        expectedCounts.forEach { (runtimeSource, expectedCount) ->
            val sourceRecords = records.filter { it.runtimeSource == runtimeSource }
            assertEquals(runtimeSource, expectedCount, sourceRecords.size)
            assertTrue(runtimeSource, sourceRecords.all(FoodCommercialReleaseEligibilityPolicy::isEligible))
            assertTrue(runtimeSource, sourceRecords.all { FoodLoggingEligibilityPolicy.evaluate(it).status == FoodLoggingStatus.VERIFIED })
        }
    }

    @Test
    fun `qualified USDA Foundation records are verified loggable and commercially eligible`() {
        val verifiedFoods = foods.filter { it.runtimeSource == "USDA_FOUNDATION_VERIFIED" }
        val verifiedRecords = records.filter { it.runtimeSource == "USDA_FOUNDATION_VERIFIED" }

        assertEquals(15, verifiedFoods.size)
        assertTrue(verifiedFoods.all { it.isLoggable })
        assertTrue(verifiedFoods.all { it.loggingEligibility.status.name == "VERIFIED" })
        assertTrue(verifiedRecords.all(FoodCommercialReleaseEligibilityPolicy::isEligible))
        assertTrue(verifiedRecords.all { it.nutritionBasis == "VERIFIED_PER_100G" })
        assertTrue(verifiedRecords.all { it.servingSize == "100 g" })
    }

    @Test
    fun `representative USDA records are searchable through the existing search contract`() {
        assertTrue(foods.any {
            it.id == "fd-food-usda-2346396" &&
                FoodCatalogueSearch.matches(it.name, it.aliases, it.category, "rolled oats")
        })
        assertTrue(foods.any {
            it.id == "fd-food-usda-2644282" &&
                FoodCatalogueSearch.matches(it.name, it.aliases, it.category, "garbanzo beans")
        })
    }

    @Test
    fun `valid numeric values never override non-loggable record flag`() {
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(
            foodRecord(
                isLoggable = false,
                calories = 460,
                proteinGrams = 18.0,
                carbsGrams = 54.0,
                fatGrams = 19.0,
                nutritionBasis = "VERIFIED_PER_SERVING",
                fitDesiReviewStatus = "FITDESI_NUTRITION_VERIFIED",
                licenceStatus = "VERIFIED_COMMERCIAL"
            )
        )

        assertFalse(eligibility.canLog)
        assertEquals("REVIEW_REQUIRED", eligibility.status.name)
        assertFalse(eligibility.commercialReleaseEligible)
    }

    @Test
    fun `explicitly evidenced synthetic record is verified and commercially eligible`() {
        val record = foodRecord()
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(record)

        assertEquals("VERIFIED", eligibility.status.name)
        assertTrue(eligibility.canLog)
        assertTrue(eligibility.commercialReleaseEligible)
        assertTrue(FoodCommercialReleaseEligibilityPolicy.isEligible(record))
    }

    @Test
    fun `commercial eligibility fails when any required evidence condition is missing`() {
        val verified = foodRecord()
        val incompleteRecords = listOf(
            verified.copy(licenceStatus = "UNKNOWN"),
            verified.copy(fitDesiReviewStatus = "EXISTING_FITDESI_LOGGABLE"),
            verified.copy(nutritionBasis = "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G"),
            verified.copy(calories = 0),
            verified.copy(proteinGrams = -0.1),
            verified.copy(isLoggable = false)
        )

        incompleteRecords.forEach { record ->
            val eligibility = FoodLoggingEligibilityPolicy.evaluate(record)
            assertFalse("Expected ${record.id} to fail the commercial gate", eligibility.commercialReleaseEligible)
            assertFalse("Expected ${record.id} to fail the separate commercial policy", FoodCommercialReleaseEligibilityPolicy.isEligible(record))
            assertFalse("Expected ${record.id} not to be VERIFIED", eligibility.status.name == "VERIFIED")
        }
    }

    @Test
    fun `non-loggable flag takes precedence over incomplete numeric data`() {
        val eligibility = FoodLoggingEligibilityPolicy.evaluate(
            foodRecord(isLoggable = false, calories = 0, proteinGrams = -0.1)
        )

        assertEquals("REVIEW_REQUIRED", eligibility.status.name)
        assertFalse(eligibility.canLog)
        assertFalse(eligibility.commercialReleaseEligible)
    }

    @Test
    fun `unknown USDA source token fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "\"runtimeSource\": \"USDA_FNDDS_VERIFIED\"",
            "\"runtimeSource\": \"USDA_UNKNOWN_VERIFIED\""
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `FNDDS record with wrong data type fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "\"dataType\": \"Survey (FNDDS)\"",
            "\"dataType\": \"Foundation Foods\""
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `SR Legacy record with wrong evidence class fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "\"evidenceClass\": \"SR_LEGACY\"",
            "\"evidenceClass\": \"FNDDS\""
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `Branded label record with missing brand identity fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "\"brandName\": \"DIYA\"",
            "\"brandName\": \"\""
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `direct FDC selection evidence hash drift fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "874e5da49762103fe8821828296477e1f3ad178bc4b0f0d21871c3878e962138",
            "${"0".repeat(64)}"
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `unselected direct FDC identity fails closed`() {
        val malformed = catalogueText()
            .replaceFirst("\"id\": \"fd-food-usda-2708614\"", "\"id\": \"fd-food-usda-999999999\"")
            .replaceFirst("\"originalSourceId\": \"2708614\"", "\"originalSourceId\": \"999999999\"")

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `direct FDC source record fingerprint drift fails closed`() {
        val malformed = catalogueText().replaceFirst(
            "5c60268a0955d54739674557efd6ab732a805c8f4680b10ab6cfdb3f455ad7e1",
            "${"0".repeat(64)}"
        )

        assertThrows(IllegalArgumentException::class.java) { FoodCatalogueParser.parse(malformed) }
    }

    @Test
    fun `verified USDA record with incomplete provenance is rejected by the parser`() {
        val source = catalogueText()
        val marker = "\"sourceProvider\": \"USDA FoodData Central\""
        assertTrue(source.contains(marker))
        val incomplete = source.replaceFirst(marker, "\"sourceProvider\": \"\"")

        assertThrows(IllegalArgumentException::class.java) {
            FoodCatalogueParser.parse(incomplete)
        }
    }

    @Test
    fun `projected record count metadata is internally consistent`() {
        val catalogue = FoodCatalogueParser.parse(catalogueText())
        assertEquals(39, catalogue.recordCount)
        assertEquals(catalogue.records.size, catalogue.recordCount)
        assertEquals(39, catalogue.reviewedLoggableCount)
        assertEquals(0, catalogue.reviewRequiredCount)
        assertNotNull(catalogue.catalogueId)
    }

    @Test
    fun `diet and coach food source is verified only and excludes every discovery record`() {
        // getPakistaniFoodRecords() is the sole nutrition source mapped into ProjectFoodRecord
        // for the Coach and the deterministic diet generator. Discovery records live in a
        // separate model/loader and can never enter calorie, macro, meal, or portion math.
        assertEquals(39, records.size)
        assertTrue(records.all { it.isLoggable })
        assertTrue(records.none { it.id.startsWith(SouthAsianFoodDiscovery.ID_PREFIX) })

        val discovery = FoodRepository(context).getSouthAsianFoodDiscoveryRecords()
        assertEquals(20, discovery.size)
        assertTrue(discovery.all { it.id.startsWith(SouthAsianFoodDiscovery.ID_PREFIX) })
        assertTrue(discovery.none { it.id.startsWith("fd-food-usda-") })
    }

    private fun foodRecord(
        isLoggable: Boolean = true,
        calories: Int = 460,
        proteinGrams: Double = 18.0,
        carbsGrams: Double = 54.0,
        fatGrams: Double = 19.0,
        nutritionBasis: String = "VERIFIED_PER_SERVING",
        fitDesiReviewStatus: String = "FITDESI_NUTRITION_VERIFIED",
        licenceStatus: String = "VERIFIED_COMMERCIAL"
    ) = PakistaniFoodRecord(
        id = "synthetic-${isLoggable}-${calories}-${proteinGrams}-${nutritionBasis}",
        name = "Synthetic verified food",
        aliases = emptyList(),
        category = "Protein & Main Dishes",
        originalCategory = "Main Dishes",
        servingSize = "1 verified serving",
        calories = calories,
        proteinGrams = proteinGrams,
        carbsGrams = carbsGrams,
        fatGrams = fatGrams,
        nutritionBasis = nutritionBasis,
        nutritionDisplayLabel = "Verified serving nutrition",
        fitDesiReviewStatus = fitDesiReviewStatus,
        isLoggable = isLoggable,
        runtimeSource = "SYNTHETIC_TEST",
        dietaryClassification = "MEAT",
        licenceStatus = licenceStatus,
        importedProvenance = emptyList()
    )

    private fun catalogueText(): String =
        context.assets.open("pakistani_food_catalogue_public.json").bufferedReader().use { it.readText() }
}
