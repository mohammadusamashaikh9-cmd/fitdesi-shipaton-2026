package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FoodLoggingEligibilityPolicy
import com.example.data.FoodRepository
import com.example.data.PakistaniFoodRecord
import com.example.ui.theme.FitDesiSansFontFamily
import com.example.ui.theme.Typography as FitDesiTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
class NutritionTrackerLogicTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository by lazy { FoodRepository(context) }
    private val foods by lazy { repository.getPakistaniFoods() }
    private val discoveryFoods by lazy { repository.getSouthAsianFoods() }

    @Test
    fun `biryani search resolves to the South Asian discovery layer not a fabricated verified record`() {
        val verifiedResults = filterPakistaniFoods(foods, query = "biryani", category = "All")
        val discoveryResults = filterDiscoveryFoods(discoveryFoods, query = "biryani", category = "All")

        assertTrue("biryani must surface as a discovery identity", discoveryResults.any { it.name == "Chicken Biryani" })
        // The verified nutrition layer must not invent a biryani record with copied nutrition.
        assertTrue(verifiedResults.none { it.name.contains("Biryani", ignoreCase = true) })
    }

    @Test
    fun `a verified nutrition query still returns a loggable verified record`() {
        val results = filterPakistaniFoods(foods, query = "rolled oats", category = "All")

        assertTrue(results.any { it.id == "fd-food-usda-2346396" && it.loggingEligibility.canLog })
    }

    @Test
    fun `category filtering keeps only the selected category`() {
        val results = filterPakistaniFoods(foods, query = "", category = "Grains & Rice")

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.category == "Grains & Rice" })
    }

    @Test
    fun `discovery category filtering keeps only the selected category`() {
        val results = filterDiscoveryFoods(discoveryFoods, query = "", category = "Breads")

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.category == "Breads" })
    }

    @Test
    fun `category options include verified and discovery categories once in deterministic order`() {
        val options = foodCategoryOptions(foods, discoveryFoods)

        assertEquals(
            listOf(
                "All",
                "Breads",
                "Grains & Rice",
                "Lentils & Legumes",
                "Vegetable Dishes",
                "Protein & Main Dishes",
                "Snacks & Street Food",
                "Drinks",
                "Desserts",
                "Dairy & Sides",
                "Breakfast",
                "Fats & Oils",
                "Curries & Main Dishes",
                "Kebabs",
                "Rice & Meals"
            ),
            options
        )
        assertEquals(options.size, options.distinct().size)
        assertEquals(1, options.count { it == "All" })
    }

    @Test
    fun `Kebabs category returns only nutritionless discovery records`() {
        val verifiedResults = filterPakistaniFoods(foods, query = "", category = "Kebabs")
        val discoveryResults = filterDiscoveryFoods(discoveryFoods, query = "", category = "Kebabs")

        assertTrue(verifiedResults.isEmpty())
        assertEquals(listOf("Chapli Kabab", "Shami Kabab"), discoveryResults.map { it.name }.sorted())
        assertTrue(discoveryResults.all { it.nutritionStatus == "NUTRITION_VERIFICATION_IN_PROGRESS" })
    }

    @Test
    fun `unmatched query produces an honest empty result across both layers`() {
        assertTrue(filterPakistaniFoods(foods, "not-a-real-food-xyz", "All").isEmpty())
        assertTrue(filterDiscoveryFoods(discoveryFoods, "not-a-real-food-xyz", "All").isEmpty())
    }

    @Test
    fun `catalogue count copy describes the dual layer food set accurately`() {
        assertEquals("59 foods", foodResultCountLabel(count = 59, filtering = false))
        assertEquals("12 matching foods", foodResultCountLabel(count = 12, filtering = true))
        assertEquals("Browse all 39 foods", browseAllFoodsLabel(count = 39))
    }

    @Test
    fun `nutrition composition copy never claims every visible food is verified`() {
        val label = nutritionCompositionLabel(verifiedCount = 39, discoveryCount = 20)

        assertEquals("39 verified nutrition • 20 South Asian foods (nutrition verification in progress)", label)
        assertFalse(label.contains("59 verified"))
    }

    @Test
    fun `discovery status label is the manager approved verification copy`() {
        assertEquals("Nutrition verification in progress", DISCOVERY_STATUS_LABEL)
        assertTrue(discoveryFoods.all { it.nutritionStatus == "NUTRITION_VERIFICATION_IN_PROGRESS" })
    }

    @Test
    fun `serving calculation uses only stored values`() {
        val food = foods.first { it.caloriesPerServing > 0 }
        val totals = calculateServingTotals(food, 1.5f)

        assertEquals((food.caloriesPerServing * 1.5f).roundToInt(), totals.calories)
        assertEquals(food.proteinGrams * 1.5f, totals.proteinGrams, 0.001f)
        assertEquals(food.carbsGrams * 1.5f, totals.carbsGrams, 0.001f)
        assertEquals(food.fatGrams * 1.5f, totals.fatGrams, 0.001f)
    }

    @Test
    fun `meal selection accepts only supported tracker meals`() {
        listOf("Breakfast", "Lunch", "Dinner", "Snack").forEach {
            assertTrue(isSupportedMealType(it))
        }
        assertFalse(isSupportedMealType("Brunch"))
    }

    @Test
    fun `nutrition typography uses the shared offline FitDesi family`() {
        assertEquals(FitDesiSansFontFamily, FitDesiTypography.bodyLarge.fontFamily)
        assertEquals(FitDesiSansFontFamily, FitDesiTypography.titleLarge.fontFamily)
    }

    @Test
    fun `legacy estimate presentation uses truthful estimate copy and keeps an action`() {
        val food = foodItemFor(legacyEstimateRecord())
        val presentation = foodLoggingPresentation(food)

        assertEquals("FitDesi estimate", presentation.badgeLabel)
        assertEquals("${food.caloriesPerServing} kcal • ${food.servingUnit}", presentation.nutritionSummary)
        assertEquals("FitDesi estimate • nutrition source review pending", presentation.detailNote)
        assertEquals("Log FitDesi estimate", presentation.logActionLabel)
        assertFalse(presentation.badgeLabel.contains("Reviewed", ignoreCase = true))
    }

    @Test
    fun `review-required presentation has calm pending copy and no logging action`() {
        val food = foodItemFor(reviewRequiredRecord())
        val presentation = foodLoggingPresentation(food)

        assertEquals("Review pending", presentation.badgeLabel)
        assertEquals("Reference nutrition • serving review pending", presentation.detailNote)
        assertEquals(null, presentation.logActionLabel)
        assertEquals("Nutrition review pending", presentation.unavailableTitle)
        assertEquals(FoodLoggingVisualTreatment.WARNING, presentation.visualTreatment)
    }

    @Test
    fun `verified presentation contract exists`() {
        val food = foodItemFor(verifiedRecord())
        val presentation = foodLoggingPresentation(food)

        assertEquals("Verified", presentation.badgeLabel)
        assertEquals("Verified nutrition • 1 verified serving", presentation.detailNote)
        assertEquals("Log food", presentation.logActionLabel)
    }

    @Test
    fun `imported USDA record uses verified presentation and gram scaling`() {
        val food = foods.single { it.id == "fd-food-usda-2512381" }
        val presentation = foodLoggingPresentation(food)
        val fiftyGrams = calculateServingTotals(food, 0.5f)

        assertEquals("Verified", presentation.badgeLabel)
        assertEquals("Verified nutrition • 100 g", presentation.detailNote)
        assertEquals("Log food", presentation.logActionLabel)
        assertTrue(food.loggingEligibility.commercialReleaseEligible)
        assertEquals((food.caloriesPerServing * 0.5f).roundToInt(), fiftyGrams.calories)
        assertEquals(food.proteinGrams * 0.5f, fiftyGrams.proteinGrams, 0.001f)
        assertEquals(food.carbsGrams * 0.5f, fiftyGrams.carbsGrams, 0.001f)
        assertEquals(food.fatGrams * 0.5f, fiftyGrams.fatGrams, 0.001f)
    }

    private fun foodItemFor(record: PakistaniFoodRecord) = FoodItem(
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

    private fun verifiedRecord() = baseRecord(
        id = "synthetic-verified-presentation",
        servingSize = "1 verified serving",
        nutritionBasis = "VERIFIED_PER_SERVING",
        fitDesiReviewStatus = "FITDESI_NUTRITION_VERIFIED",
        isLoggable = true,
        licenceStatus = "VERIFIED_COMMERCIAL"
    )

    private fun legacyEstimateRecord() = baseRecord(
        id = "synthetic-legacy-presentation",
        servingSize = "1 bowl",
        nutritionBasis = "EXISTING_FITDESI_SERVING_ESTIMATE",
        fitDesiReviewStatus = "EXISTING_FITDESI_LOGGABLE",
        isLoggable = true,
        licenceStatus = "UNKNOWN"
    )

    private fun reviewRequiredRecord() = baseRecord(
        id = "synthetic-review-presentation",
        servingSize = "Serving basis under review",
        nutritionBasis = "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G",
        fitDesiReviewStatus = "NEEDS_FITDESI_NUTRITION_REVIEW",
        isLoggable = false,
        licenceStatus = "UNKNOWN"
    )

    private fun baseRecord(
        id: String,
        servingSize: String,
        nutritionBasis: String,
        fitDesiReviewStatus: String,
        isLoggable: Boolean,
        licenceStatus: String
    ) = PakistaniFoodRecord(
        id = id,
        name = "Synthetic presentation food",
        aliases = emptyList(),
        category = "Protein & Main Dishes",
        originalCategory = "Main Dishes",
        servingSize = servingSize,
        calories = 260,
        proteinGrams = 12.0,
        carbsGrams = 30.0,
        fatGrams = 9.0,
        nutritionBasis = nutritionBasis,
        nutritionDisplayLabel = "Synthetic nutrition reference",
        fitDesiReviewStatus = fitDesiReviewStatus,
        isLoggable = isLoggable,
        runtimeSource = "SYNTHETIC_TEST",
        dietaryClassification = "MEAT",
        licenceStatus = licenceStatus,
        importedProvenance = emptyList()
    )
}
