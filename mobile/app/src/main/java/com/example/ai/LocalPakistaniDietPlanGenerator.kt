package com.example.ai

import com.example.ai.knowledge.ProjectFoodRecord
import com.example.ai.knowledge.FoodDietaryClassification
import com.example.domain.DietaryPreference
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LocalDietPlanInput(
    val goal: String?,
    val calorieTarget: Int?,
    val proteinTargetGrams: Double?,
    val carbsTargetGrams: Double?,
    val fatTargetGrams: Double?,
    val dietaryPreference: String?,
    val requestedMealCount: Int,
    val limitations: Set<String>,
    val dislikedFoods: Set<String> = emptySet(),
    val foods: List<ProjectFoodRecord>
)

internal data class LocalDietPlan(
    val targetSummary: String,
    val meals: List<LocalDietMeal>,
    val hydrationReminder: String,
    val variationDisclaimer: String,
    val fallbackUsed: Boolean,
    val validationStatus: PlanValidationStatus,
    val estimatedTotalCalories: Int?,
    val calorieDifference: Int?,
    val validationFailures: List<String>
)

internal data class LocalDietMeal(
    val label: String,
    val primary: ProjectFoodRecord,
    val alternatives: List<LocalDietAlternative>,
    val accompaniments: List<LocalDietPortion>,
    val portionMultiplier: Double,
    val portionDescription: String,
    val estimatedCalories: Int?,
    val estimatedProteinGrams: Double?,
    val estimatedCarbsGrams: Double?,
    val estimatedFatGrams: Double?
)

internal data class LocalDietPortion(
    val food: ProjectFoodRecord,
    val portionMultiplier: Double,
    val portionDescription: String,
    val nutrition: LocalDietNutrition
)

internal data class LocalDietAlternative(
    val food: ProjectFoodRecord,
    val portionMultiplier: Double,
    val portionDescription: String,
    val estimatedCalories: Int?,
    val estimatedProteinGrams: Double?,
    val estimatedCarbsGrams: Double?,
    val estimatedFatGrams: Double?,
    val dietaryCompatibilityStatus: String
)

private data class DietValidationResult(
    val status: PlanValidationStatus,
    val failures: List<String>
)

internal class LocalPakistaniDietPlanGenerator {
    fun generate(input: LocalDietPlanInput): LocalDietPlan {
        val mealCount = input.requestedMealCount.coerceIn(2, 6)
        val safeCalories = input.calorieTarget?.coerceIn(MIN_CALORIES, MAX_CALORIES)
        val preference = DietaryPreference.from(input.dietaryPreference)
        val validFoods = input.foods.asSequence()
            .filter(::isUsable)
            .filter { preference.allows(it.dietaryClassification) }
            .filter { avoidsLimitations(it, input.limitations) }
            .filter { avoidsDislikes(it, input.dislikedFoods) }
            .distinctBy(ProjectFoodRecord::id)
            .toList()
        if (validFoods.isEmpty()) {
            return LocalDietPlan(
                targetSummary = "No compatible reviewed Pakistani foods were available; nutrition remains incomplete.",
                meals = emptyList(),
                hydrationReminder = "Drink water regularly unless a clinician has given you a fluid restriction.",
                variationDisclaimer = STANDARD_VARIATION,
                fallbackUsed = true,
                validationStatus = if (preference == DietaryPreference.MISSING) {
                    PlanValidationStatus.NEEDS_PROFILE_INPUT
                } else {
                    PlanValidationStatus.NEEDS_ADJUSTMENT
                },
                estimatedTotalCalories = null,
                calorieDifference = safeCalories,
                validationFailures = listOf("No compatible reviewed foods are available for this profile.")
            )
        }

        val stageLabels = mealLabels(mealCount)
        val mealShares = mealShares(mealCount)
        val used = linkedSetOf<String>()
        val usedCategories = linkedSetOf<String>()
        val meals = stageLabels.mapIndexed { index, label ->
            val mealBudget = safeCalories?.let { target -> target * mealShares[index] }
            val candidates = candidatesFor(
                label = label,
                foods = validFoods,
                goal = input.goal,
                mealBudget = mealBudget,
                proteinBudget = input.proteinTargetGrams?.times(mealShares[index]),
                carbsBudget = input.carbsTargetGrams?.times(mealShares[index]),
                fatBudget = input.fatTargetGrams?.times(mealShares[index])
            ).take(MAX_CANDIDATES_PER_STAGE)
            val primary = candidates.firstOrNull { it.id !in used && it.category !in usedCategories }
                ?: candidates.firstOrNull { it.id !in used }
                ?: validFoods.first { it.id !in used || used.size >= validFoods.size }
            used += primary.id
            usedCategories += primary.category
            val portionMultiplier = portionMultiplier(primary, mealBudget)
            val primaryNutrition = estimatedNutrition(primary, portionMultiplier)
            val remainingBudget = mealBudget?.minus(primaryNutrition?.calories ?: 0)?.coerceAtLeast(0.0)
            val accompaniment = if (remainingBudget != null && remainingBudget >= MIN_ACCOMPANIMENT_CALORIES) {
                validFoods.asSequence()
                    .filter {
                        it.id != primary.id && it.id !in used &&
                            it.category != primary.category &&
                            it.category.lowercase() !in ENERGY_DENSE_CATEGORIES
                    }
                    .map { food -> food to portionMultiplier(food, remainingBudget) }
                    .mapNotNull { (food, portion) ->
                        estimatedNutrition(food, portion)?.let { nutrition ->
                            LocalDietPortion(
                                food = food,
                                portionMultiplier = portion,
                                portionDescription = practicalServingDescription(food, portion),
                                nutrition = nutrition
                            )
                        }
                    }
                    .minByOrNull { abs(it.nutrition.calories - remainingBudget) }
            } else {
                null
            }
            accompaniment?.let {
                used += it.food.id
                usedCategories += it.food.category
            }
            val accompaniments = listOfNotNull(accompaniment)
            val nutrition = primaryNutrition?.plus(accompaniments.map(LocalDietPortion::nutrition))
            val alternatives = candidates.filter { candidate ->
                candidate.id != primary.id && accompaniments.none { it.food.id == candidate.id }
            }.take(MAX_ALTERNATIVE_CANDIDATES).map { alternative ->
                val alternativePortion = portionMultiplier(alternative, mealBudget)
                val alternativeNutrition = estimatedNutrition(alternative, alternativePortion)
                LocalDietAlternative(
                    food = alternative,
                    portionMultiplier = alternativePortion,
                    portionDescription = practicalServingDescription(alternative, alternativePortion),
                    estimatedCalories = alternativeNutrition?.calories,
                    estimatedProteinGrams = alternativeNutrition?.protein,
                    estimatedCarbsGrams = alternativeNutrition?.carbs,
                    estimatedFatGrams = alternativeNutrition?.fat,
                    dietaryCompatibilityStatus = "COMPATIBLE"
                )
            }.filter { alternative ->
                val alternativeCalories = alternative.estimatedCalories
                alternativeCalories != null && nutrition != null &&
                    abs(alternativeCalories - nutrition.calories) <= nutrition.calories * ALTERNATIVE_CALORIE_TOLERANCE
            }.take(2)
            LocalDietMeal(
                label = label,
                primary = primary,
                alternatives = alternatives,
                accompaniments = accompaniments,
                portionMultiplier = portionMultiplier,
                portionDescription = practicalServingDescription(primary, portionMultiplier),
                estimatedCalories = nutrition?.calories,
                estimatedProteinGrams = nutrition?.protein,
                estimatedCarbsGrams = nutrition?.carbs,
                estimatedFatGrams = nutrition?.fat
            )
        }
        val completeNutrition = meals.all { it.estimatedCalories != null }
        val estimatedCalories = meals.mapNotNull(LocalDietMeal::estimatedCalories).sum()
        val estimatedProtein = meals.mapNotNull(LocalDietMeal::estimatedProteinGrams).sum()
        val estimatedCarbs = meals.mapNotNull(LocalDietMeal::estimatedCarbsGrams).sum()
        val estimatedFat = meals.mapNotNull(LocalDietMeal::estimatedFatGrams).sum()
        val macroSummary = listOfNotNull(
            input.proteinTargetGrams?.let { "protein ${it.roundToInt()} g" },
            input.carbsTargetGrams?.let { "carbohydrate ${it.roundToInt()} g" },
            input.fatTargetGrams?.let { "fat ${it.roundToInt()} g" }
        ).joinToString()
        val validation = validatePlan(input, meals, safeCalories, preference)
        val validationStatus = validation.status
        val calorieDifference = safeCalories?.let { it - estimatedCalories }
        val targetSummary = buildString {
            append("Goal: ${input.goal?.takeIf(String::isNotBlank) ?: "general balanced eating"}. ")
            if (safeCalories != null) append("Existing target: about $safeCalories kcal/day. ")
            if (macroSummary.isNotBlank()) append("Existing macro targets: $macroSummary. ")
            if (completeNutrition) {
                append(
                    "Portion-adjusted listed meal estimate: about $estimatedCalories kcal/day, " +
                        "protein ${estimatedProtein.roundToInt()} g, carbohydrate ${estimatedCarbs.roundToInt()} g, " +
                        "fat ${estimatedFat.roundToInt()} g."
                )
                if (validationStatus == PlanValidationStatus.NEEDS_ADJUSTMENT && calorieDifference != null) {
                    append(" This needs adjustment by about ${kotlin.math.abs(calorieDifference)} kcal " +
                        if (calorieDifference > 0) "to reach the target." else "to stay near the target.")
                }
                if (validation.failures.isNotEmpty()) {
                    append(" Checks needing attention: ${validation.failures.joinToString()}.")
                }
            } else {
                append("Daily nutrition total is incomplete because at least one local record lacks nutrition values.")
            }
        }
        return LocalDietPlan(
            targetSummary = targetSummary,
            meals = meals,
            hydrationReminder =
                "Drink water across the day and around training unless a qualified clinician has prescribed a fluid restriction.",
            variationDisclaimer = STANDARD_VARIATION,
            fallbackUsed = false,
            validationStatus = validationStatus,
            estimatedTotalCalories = estimatedCalories.takeIf { completeNutrition },
            calorieDifference = calorieDifference,
            validationFailures = validation.failures
        )
    }

    private fun candidatesFor(
        label: String,
        foods: List<ProjectFoodRecord>,
        goal: String?,
        mealBudget: Double?,
        proteinBudget: Double?,
        carbsBudget: Double?,
        fatBudget: Double?
    ): List<ProjectFoodRecord> {
        val terms = when (label) {
            "Breakfast" -> listOf("egg", "paratha", "roti", "yogurt", "dahi", "milk", "oat")
            "Lunch" -> listOf("chicken", "daal", "chana", "curry", "rice", "roti", "sabzi")
            "Dinner" -> listOf("fish", "chicken", "daal", "curry", "karahi", "sabzi", "roti")
            else -> listOf("fruit", "yogurt", "dahi", "milk", "chana", "egg", "nuts")
        }
        return foods.sortedWith(
            compareByDescending<ProjectFoodRecord> { food ->
                foodScore(food, terms, goal, mealBudget, proteinBudget, carbsBudget, fatBudget)
            }.thenBy(ProjectFoodRecord::id)
        )
    }

    private fun foodScore(
        food: ProjectFoodRecord,
        mealTerms: List<String>,
        goal: String?,
        mealBudget: Double?,
        proteinBudget: Double?,
        carbsBudget: Double?,
        fatBudget: Double?
    ): Double {
        val name = food.name.lowercase()
        val category = food.category.lowercase()
        var score = mealTerms.count { it in name || it in category } * 80.0
        if (!hasCompleteNutrition(food)) return score - 100.0
        val proteinDensity = food.proteinGrams / food.calories.coerceAtLeast(1) * 100.0
        val normalizedGoal = goal.orEmpty().lowercase()
        when {
            "muscle" in normalizedGoal || "gain" in normalizedGoal -> {
                score += food.proteinGrams * 2.0 + proteinDensity
                if (food.proteinGrams < MIN_PROTEIN_MEAL_GRAMS) score -= 25.0
            }
            "fat" in normalizedGoal || "lose" in normalizedGoal -> {
                score += proteinDensity * 2.0
                if (category in LEANER_CATEGORIES) score += 20.0
                if (category in ENERGY_DENSE_CATEGORIES || food.calories > HIGH_ENERGY_SERVING) score -= 45.0
            }
            else -> score += proteinDensity
        }
        if (mealBudget != null) {
            val serving = portionMultiplier(food, mealBudget)
            score -= abs(food.calories * serving - mealBudget) / 10.0
            if (food.calories > mealBudget * 1.35) score -= 50.0
            proteinBudget?.let { score -= abs(food.proteinGrams * serving - it) * 2.0 }
            carbsBudget?.let { score -= abs(food.carbsGrams * serving - it) * 0.5 }
            fatBudget?.let { score -= abs(food.fatGrams * serving - it) }
        }
        return score
    }

    private fun matchesPreference(food: ProjectFoodRecord, preference: String?): Boolean =
        DietaryPreference.from(preference).allows(food.dietaryClassification)

    private fun validatePlan(
        input: LocalDietPlanInput,
        meals: List<LocalDietMeal>,
        calorieTarget: Int?,
        preference: DietaryPreference
    ): DietValidationResult {
        val missingProfile = buildList {
            if (input.goal.isNullOrBlank()) add("goal is not set")
            if (preference == DietaryPreference.MISSING) add("diet preference is not set")
            if (calorieTarget == null) add("calorie target is not available")
            if (input.proteinTargetGrams == null) add("protein target is not available")
            if (input.carbsTargetGrams == null) add("carbohydrate target is not available")
            if (input.fatTargetGrams == null) add("fat target is not available")
        }
        if (missingProfile.isNotEmpty()) {
            return DietValidationResult(PlanValidationStatus.NEEDS_PROFILE_INPUT, missingProfile)
        }
        val failures = mutableListOf<String>()
        if (meals.size != input.requestedMealCount.coerceIn(2, 6)) failures += "meal count does not match the profile"
        val allFoods = meals.flatMap { meal ->
            listOf(meal.primary) +
                meal.accompaniments.map(LocalDietPortion::food) +
                meal.alternatives.map(LocalDietAlternative::food)
        }
        if (allFoods.any { !it.isNutritionReviewed }) failures += "an unreviewed nutrition record was selected"
        if (allFoods.any { !preference.allows(it.dietaryClassification) }) failures += "diet preference is not satisfied"
        if (allFoods.any { !avoidsLimitations(it, input.limitations) }) failures += "an allergy or limitation is not satisfied"
        if (meals.any { it.estimatedCalories == null }) failures += "meal nutrition is incomplete"
        val calories = meals.mapNotNull(LocalDietMeal::estimatedCalories).sum().toDouble()
        val protein = meals.mapNotNull(LocalDietMeal::estimatedProteinGrams).sum()
        val carbs = meals.mapNotNull(LocalDietMeal::estimatedCarbsGrams).sum()
        val fat = meals.mapNotNull(LocalDietMeal::estimatedFatGrams).sum()
        failures += validateDietNutritionTargets(
            actualCalories = calories,
            targetCalories = calorieTarget!!.toDouble(),
            actualProtein = protein,
            targetProtein = input.proteinTargetGrams!!,
            actualCarbs = carbs,
            targetCarbs = input.carbsTargetGrams!!,
            actualFat = fat,
            targetFat = input.fatTargetGrams!!
        )
        if (meals.any { it.portionMultiplier > practicalPortionCap(it.primary) }) {
            failures += "a serving exceeds the practical cap"
        }
        if (meals.flatMap(LocalDietMeal::accompaniments).any {
                it.portionMultiplier > practicalPortionCap(it.food)
            }
        ) {
            failures += "an accompaniment serving exceeds the practical cap"
        }
        if (meals.flatMap(LocalDietMeal::alternatives).any {
                it.portionMultiplier > practicalPortionCap(it.food)
            }
        ) {
            failures += "an alternative serving exceeds the practical cap"
        }
        return DietValidationResult(
            if (failures.isEmpty()) PlanValidationStatus.VALIDATED else PlanValidationStatus.NEEDS_ADJUSTMENT,
            failures
        )
    }

    private fun avoidsLimitations(food: ProjectFoodRecord, limitations: Set<String>): Boolean {
        val allergyText = limitations.joinToString(" ").lowercase()
        if (allergyText.isBlank()) return true
        return allergyText.split(Regex("[^a-z]+"))
            .filter { it.length >= 3 && it !in IGNORED_LIMITATION_WORDS }
            .none { it in food.name.lowercase() }
    }

    private fun avoidsDislikes(food: ProjectFoodRecord, dislikedFoods: Set<String>): Boolean =
        dislikedFoods.none { disliked ->
            val normalized = disliked.trim().lowercase()
            normalized.isNotBlank() && normalized in food.name.lowercase()
        }

    private fun isUsable(food: ProjectFoodRecord): Boolean =
        food.id.isNotBlank() && food.name.isNotBlank() && food.servingSize.isNotBlank() &&
            food.isNutritionReviewed

    private fun hasCompleteNutrition(food: ProjectFoodRecord): Boolean =
        food.calories >= 0 && food.proteinGrams >= 0 &&
            food.carbsGrams >= 0 && food.fatGrams >= 0

    private fun portionMultiplier(
        food: ProjectFoodRecord,
        mealBudget: Double?
    ): Double {
        if (mealBudget == null || !hasCompleteNutrition(food) || food.calories == 0) return 1.0
        val desired = (mealBudget / food.calories).coerceIn(MIN_PORTION, practicalPortionCap(food))
        return PORTION_STEPS.minBy { abs(it - desired) }.coerceAtMost(practicalPortionCap(food))
    }

    private fun practicalPortionCap(food: ProjectFoodRecord): Double {
        val serving = food.servingSize.lowercase()
        return when {
            "cup" in serving || "glass" in serving -> 1.5
            "plate" in serving -> 1.5
            "piece" in serving || "roti" in serving || "naan" in serving -> 2.0
            "bowl" in serving -> 2.0
            else -> 2.0
        }
    }

    private fun estimatedNutrition(
        food: ProjectFoodRecord,
        portionMultiplier: Double
    ): LocalDietNutrition? {
        if (!hasCompleteNutrition(food)) return null
        return LocalDietNutrition(
            calories = (food.calories * portionMultiplier).roundToInt(),
            protein = food.proteinGrams * portionMultiplier,
            carbs = food.carbsGrams * portionMultiplier,
            fat = food.fatGrams * portionMultiplier
        )
    }

    private fun mealLabels(count: Int): List<String> = when (count) {
        2 -> listOf("Breakfast", "Dinner")
        3 -> listOf("Breakfast", "Lunch", "Dinner")
        4 -> listOf("Breakfast", "Lunch", "Snack", "Dinner")
        5 -> listOf("Breakfast", "Morning Snack", "Lunch", "Afternoon Snack", "Dinner")
        else -> listOf("Breakfast", "Morning Snack", "Lunch", "Afternoon Snack", "Dinner", "Evening Snack")
    }

    private fun mealShares(count: Int): List<Double> = when (count) {
        2 -> listOf(0.4, 0.6)
        3 -> listOf(0.25, 0.35, 0.4)
        4 -> listOf(0.25, 0.3, 0.15, 0.3)
        5 -> listOf(0.22, 0.1, 0.28, 0.1, 0.3)
        else -> listOf(0.2, 0.08, 0.26, 0.08, 0.28, 0.1)
    }

    companion object {
        private const val MIN_CALORIES = 1200
        private const val MAX_CALORIES = 6000
        private const val MAX_CANDIDATES_PER_STAGE = 10
        private const val MIN_PORTION = 0.5
        private const val MIN_ACCOMPANIMENT_CALORIES = 100.0
        private const val MAX_ALTERNATIVE_CANDIDATES = 6
        private const val ALTERNATIVE_CALORIE_TOLERANCE = 0.25
        private val PORTION_STEPS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        private const val MIN_PROTEIN_MEAL_GRAMS = 12.0
        private const val HIGH_ENERGY_SERVING = 500
        private val LEANER_CATEGORIES =
            setOf("vegetables", "legumes", "dairy", "seafood", "breakfast")
        private val ENERGY_DENSE_CATEGORIES =
            setOf("desserts", "fats & oils")
        private val IGNORED_LIMITATION_WORDS =
            setOf("food", "allergy", "allergies", "avoid", "and", "the", "with")
        private const val STANDARD_VARIATION =
            "Calories, macros, and portion-adjusted totals are estimates from the existing local food records. Missing values remain incomplete. Home recipes, oil, ingredients, and serving sizes vary. This is general nutrition education, not medical nutrition treatment."
    }
}

internal data class LocalDietNutrition(
        val calories: Int,
        val protein: Double,
        val carbs: Double,
        val fat: Double
    ) {
    fun plus(others: List<LocalDietNutrition>): LocalDietNutrition = others.fold(this) { total, item ->
        LocalDietNutrition(
            calories = total.calories + item.calories,
            protein = total.protein + item.protein,
            carbs = total.carbs + item.carbs,
            fat = total.fat + item.fat
        )
    }
}

internal fun validateDietNutritionTargets(
    actualCalories: Double,
    targetCalories: Double,
    actualProtein: Double,
    targetProtein: Double,
    actualCarbs: Double,
    targetCarbs: Double,
    actualFat: Double,
    targetFat: Double
): List<String> = buildList {
    fun check(label: String, actual: Double, target: Double, minimumRatio: Double, maximumRatio: Double) {
        if (actual !in (target * minimumRatio)..(target * maximumRatio)) {
            add("$label ${actual.roundToInt()} vs target ${target.roundToInt()}")
        }
    }
    check("calories", actualCalories, targetCalories, 0.90, 1.10)
    check("protein", actualProtein, targetProtein, 0.90, 1.30)
    check("carbohydrate", actualCarbs, targetCarbs, 0.80, 1.20)
    check("fat", actualFat, targetFat, 0.80, 1.20)
}

private fun DietaryPreference.allows(classification: FoodDietaryClassification): Boolean = when (this) {
        DietaryPreference.EGGITARIAN -> classification in setOf(
            FoodDietaryClassification.VEGAN,
            FoodDietaryClassification.VEGETARIAN,
            FoodDietaryClassification.EGG
        )
        DietaryPreference.VEGETARIAN -> classification in setOf(
            FoodDietaryClassification.VEGAN,
            FoodDietaryClassification.VEGETARIAN
        )
        DietaryPreference.VEGAN -> classification == FoodDietaryClassification.VEGAN
        DietaryPreference.MISSING -> false
        DietaryPreference.KETO -> classification != FoodDietaryClassification.UNKNOWN
        DietaryPreference.NON_VEGETARIAN, DietaryPreference.UNRESTRICTED -> true
    }

internal fun LocalDietPlan.asMultilineText(): String = buildString {
    appendLine(targetSummary)
    meals.forEach { meal ->
        appendLine()
        append("${meal.label}: ${meal.primary.name} - ${meal.portionDescription}; ")
        if (meal.estimatedCalories != null) {
            appendLine(
                "~${meal.estimatedCalories} kcal, P ${meal.estimatedProteinGrams!!.roundToInt()} g, " +
                    "C ${meal.estimatedCarbsGrams!!.roundToInt()} g, " +
                    "F ${meal.estimatedFatGrams!!.roundToInt()} g"
            )
        } else {
            appendLine("nutrition estimate incomplete for this record")
        }
        meal.accompaniments.forEach { accompaniment ->
            appendLine(
                "  with ${accompaniment.food.name} - ${accompaniment.portionDescription}; " +
                    "~${accompaniment.nutrition.calories} kcal, " +
                    "P ${accompaniment.nutrition.protein.roundToInt()} g, " +
                    "C ${accompaniment.nutrition.carbs.roundToInt()} g, " +
                    "F ${accompaniment.nutrition.fat.roundToInt()} g"
            )
        }
        if (meal.alternatives.isNotEmpty()) {
            appendLine(
                "Alternatives: ${meal.alternatives.joinToString { alternative ->
                    "${alternative.food.name} (${alternative.portionDescription}, " +
                        "~${alternative.estimatedCalories ?: "?"} kcal, " +
                        "P ${alternative.estimatedProteinGrams?.roundToInt() ?: "?"} g, " +
                        "C ${alternative.estimatedCarbsGrams?.roundToInt() ?: "?"} g, " +
                        "F ${alternative.estimatedFatGrams?.roundToInt() ?: "?"} g)"
                }}"
            )
        }
    }
    appendLine()
    appendLine(hydrationReminder)
    appendLine(variationDisclaimer)
}.trim()

internal fun practicalServingDescription(food: ProjectFoodRecord, multiplier: Double): String {
    val serving = food.servingSize.trim()
    val unitMatch = Regex("(?i)^\\s*1(?:\\.0)?\\s+([a-z]+)(?:\\s+([a-z]+))?").find(serving)
    val firstUnitToken = unitMatch?.groupValues?.getOrNull(1)?.lowercase()
    val secondUnitToken = unitMatch?.groupValues?.getOrNull(2)?.lowercase()?.takeIf(String::isNotBlank)
    val unit = if (firstUnitToken in SIZE_WORDS) secondUnitToken else firstUnitToken
    val grams = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*g").find(serving)
        ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    val quantityText = when (multiplier) {
        0.5 -> "1/2"
        0.75 -> "3/4"
        1.0 -> "1"
        1.25 -> "1 1/4"
        1.5 -> "1 1/2"
        2.0 -> "2"
        else -> String.format(java.util.Locale.US, "%.1f", multiplier)
    }
    val unitText = when {
        unit == null -> if (multiplier == 1.0) "serving" else "servings"
        multiplier == 1.0 -> unit
        unit.endsWith("s") -> unit
        unit == "piece" -> "pieces"
        unit == "glass" -> "glasses"
        else -> "${unit}s"
    }
    val gramText = grams?.let { ", ${(it * multiplier).roundToInt()} g total" }.orEmpty()
    return "$quantityText $unitText$gramText"
}

private val SIZE_WORDS = setOf("small", "medium", "large", "regular")
