package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SouthAsianFoodDiscoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository by lazy { FoodRepository(context) }
    private val records by lazy { repository.getSouthAsianFoodDiscoveryRecords() }
    private val discoveryFoods by lazy { repository.getSouthAsianFoods() }

    private fun discoveryText(): String =
        context.assets.open("south_asian_food_discovery.json").bufferedReader().use { it.readText() }

    @Test
    fun `discovery dataset contains exactly twenty FitDesi-authored identities`() {
        assertEquals(20, records.size)
        assertEquals(20, discoveryFoods.size)
    }

    @Test
    fun `discovery ids are nonblank unique prefixed and never reuse legacy numeric ids`() {
        val ids = records.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.startsWith(SouthAsianFoodDiscovery.ID_PREFIX) })
        assertTrue("Discovery IDs must not reuse legacy canonical numeric ids", ids.none { it.toIntOrNull() != null })
    }

    @Test
    fun `discovery names are nonblank and unique`() {
        val names = records.map { it.name }
        assertTrue(names.all { it.isNotBlank() })
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `every discovery record stays in nutrition-verification-in-progress status`() {
        assertTrue(records.all { it.nutritionStatus == SouthAsianFoodDiscovery.NUTRITION_VERIFICATION_IN_PROGRESS })
        assertTrue(discoveryFoods.all { it.nutritionStatus == SouthAsianFoodDiscovery.NUTRITION_VERIFICATION_IN_PROGRESS })
    }

    @Test
    fun `discovery asset carries no nutrition serving or provenance keys`() {
        val raw = discoveryText()
        listOf(
            "calories", "caloriesPerServing", "protein", "proteinGrams",
            "carbs", "carbsGrams", "fat", "fatGrams", "nutritionBasis",
            "importedProvenance", "servingSize", "isLoggable"
        ).forEach { key ->
            assertFalse("Discovery asset must not contain \"$key\"", raw.contains("\"$key\""))
        }
    }

    @Test
    fun `discovery search surfaces cultural foods by name without touching the nutrition layer`() {
        val biryani = discoveryFoods.filter {
            FoodCatalogueSearch.matches(it.name, emptyList(), it.category, "biryani")
        }
        assertTrue(biryani.any { it.name == "Chicken Biryani" })
        // A culturally similar query must not pull an unrelated verified USDA record into discovery.
        assertTrue(records.none { it.id.startsWith("fd-food-usda-") })
    }

    @Test
    fun `parser rejects a smuggled nutrition key fail closed`() {
        val tampered = discoveryText().replaceFirst(
            "\"cuisine\": \"South Asian\"",
            "\"cuisine\": \"South Asian\", \"calories\": 100"
        )
        assertThrows(IllegalArgumentException::class.java) {
            SouthAsianFoodDiscoveryParser.parse(tampered)
        }
    }

    @Test
    fun `parser rejects a non discovery id prefix fail closed`() {
        val tampered = discoveryText().replaceFirst(
            "\"id\": \"fd-discovery-roti-whole-wheat\"",
            "\"id\": \"fd-food-usda-roti\""
        )
        assertThrows(IllegalArgumentException::class.java) {
            SouthAsianFoodDiscoveryParser.parse(tampered)
        }
    }

    @Test
    fun `parser rejects a verified nutrition status fail closed`() {
        val tampered = discoveryText().replaceFirst(
            "\"nutritionStatus\": \"NUTRITION_VERIFICATION_IN_PROGRESS\"",
            "\"nutritionStatus\": \"FITDESI_NUTRITION_VERIFIED\""
        )
        assertThrows(IllegalArgumentException::class.java) {
            SouthAsianFoodDiscoveryParser.parse(tampered)
        }
    }

    @Test
    fun `parser rejects a record count mismatch fail closed`() {
        val tampered = discoveryText().replaceFirst("\"recordCount\": 20", "\"recordCount\": 19")
        assertThrows(IllegalArgumentException::class.java) {
            SouthAsianFoodDiscoveryParser.parse(tampered)
        }
    }
}
