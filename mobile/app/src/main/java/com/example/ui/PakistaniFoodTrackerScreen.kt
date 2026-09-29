package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.CalorieLog
import com.example.data.FoodCatalogueSearch
import com.example.data.FoodLoggingEligibility
import com.example.data.FoodLoggingStatus
import com.example.data.FoodRepository
import com.example.ui.components.FitDesiEmptyState
import com.example.ui.components.FitDesiErrorState
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiMotion
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.NumbersTextStyle
import com.example.ui.theme.fitDesiColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface NutritionCatalogueState {
    data class Content(
        val foods: List<FoodItem>,
        val discoveryFoods: List<DiscoveryFoodItem>
    ) : NutritionCatalogueState
    data object Error : NutritionCatalogueState
}

data class FoodItem(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val category: String,
    val caloriesPerServing: Int,
    val proteinGrams: Float,
    val carbsGrams: Float,
    val fatGrams: Float,
    val servingUnit: String = "serving",
    val nutritionDisplayLabel: String,
    val reviewStatus: String,
    val isLoggable: Boolean,
    val runtimeSource: String,
    val loggingEligibility: FoodLoggingEligibility
)

// Layer B discovery result. Deliberately carries NO nutrition, serving, or logging
// fields so the UI cannot render a kcal value or expose a log action for it.
data class DiscoveryFoodItem(
    val id: String,
    val name: String,
    val category: String,
    val cuisine: String,
    val nutritionStatus: String
)

internal const val DISCOVERY_STATUS_LABEL = "Nutrition verification in progress"

internal enum class FoodLoggingVisualTreatment {
    SUCCESS,
    WARNING,
    DANGER
}

internal data class FoodLoggingPresentation(
    val badgeLabel: String,
    val nutritionSummary: String,
    val detailNote: String,
    val logActionLabel: String?,
    val unavailableTitle: String?,
    val visualTreatment: FoodLoggingVisualTreatment
)

internal fun foodLoggingPresentation(food: FoodItem): FoodLoggingPresentation =
    when (food.loggingEligibility.status) {
        FoodLoggingStatus.VERIFIED -> FoodLoggingPresentation(
            badgeLabel = "Verified",
            nutritionSummary = "${food.caloriesPerServing} kcal • ${food.servingUnit}",
            detailNote = "Verified nutrition • ${food.servingUnit}",
            logActionLabel = "Log food",
            unavailableTitle = null,
            visualTreatment = FoodLoggingVisualTreatment.SUCCESS
        )
        FoodLoggingStatus.LEGACY_ESTIMATE -> FoodLoggingPresentation(
            badgeLabel = "FitDesi estimate",
            nutritionSummary = "${food.caloriesPerServing} kcal • ${food.servingUnit}",
            detailNote = "FitDesi estimate • nutrition source review pending",
            logActionLabel = "Log FitDesi estimate",
            unavailableTitle = null,
            visualTreatment = FoodLoggingVisualTreatment.WARNING
        )
        FoodLoggingStatus.REVIEW_REQUIRED -> FoodLoggingPresentation(
            badgeLabel = "Review pending",
            nutritionSummary = "${food.caloriesPerServing} kcal • reference nutrition",
            detailNote = "Reference nutrition • serving review pending",
            logActionLabel = null,
            unavailableTitle = "Nutrition review pending",
            visualTreatment = FoodLoggingVisualTreatment.WARNING
        )
        FoodLoggingStatus.MORE_DATA_NEEDED -> FoodLoggingPresentation(
            badgeLabel = "More data needed",
            nutritionSummary = "Nutrition details incomplete",
            detailNote = "More data needed",
            logActionLabel = null,
            unavailableTitle = "Logging unavailable",
            visualTreatment = FoodLoggingVisualTreatment.DANGER
        )
    }

internal data class NutritionTotals(
    val calories: Int,
    val proteinGrams: Float,
    val carbsGrams: Float,
    val fatGrams: Float
)

internal fun calculateNutritionTotals(logs: List<CalorieLog>): NutritionTotals = NutritionTotals(
    calories = logs.sumOf { (it.amount * it.servings).roundToInt() },
    proteinGrams = logs.sumOf { (it.proteinGrams * it.servings).toDouble() }.toFloat(),
    carbsGrams = logs.sumOf { (it.carbsGrams * it.servings).toDouble() }.toFloat(),
    fatGrams = logs.sumOf { (it.fatGrams * it.servings).toDouble() }.toFloat()
)

internal fun calculateServingTotals(food: FoodItem, quantity: Float): NutritionTotals {
    val boundedQuantity = quantity.takeIf { it.isFinite() && it > 0f } ?: 0f
    return NutritionTotals(
        calories = (food.caloriesPerServing * boundedQuantity).roundToInt(),
        proteinGrams = food.proteinGrams * boundedQuantity,
        carbsGrams = food.carbsGrams * boundedQuantity,
        fatGrams = food.fatGrams * boundedQuantity
    )
}

internal fun filterPakistaniFoods(
    foods: List<FoodItem>,
    query: String,
    category: String
): List<FoodItem> = foods.filter { food ->
    val matchesSearch = FoodCatalogueSearch.matches(food.name, food.aliases, food.category, query)
    val matchesCategory = category == "All" || food.category.equals(category, ignoreCase = true)
    matchesSearch && matchesCategory
}

// Discovery layer has no aliases; search over name + category only. Kept as a
// separate typed list so a discovery result can never merge into a loggable FoodItem.
internal fun filterDiscoveryFoods(
    discoveryFoods: List<DiscoveryFoodItem>,
    query: String,
    category: String
): List<DiscoveryFoodItem> = discoveryFoods.filter { food ->
    val matchesSearch = FoodCatalogueSearch.matches(food.name, emptyList(), food.category, query)
    val matchesCategory = category == "All" || food.category.equals(category, ignoreCase = true)
    matchesSearch && matchesCategory
}

internal fun foodCategoryOptions(
    verifiedFoods: List<FoodItem>,
    discoveryFoods: List<DiscoveryFoodItem>
): List<String> {
    val presentCategories = (verifiedFoods.map(FoodItem::category) + discoveryFoods.map(DiscoveryFoodItem::category))
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
    val trackerCategories = FoodCatalogueSearch.TRACKER_CATEGORIES.filter { trackerCategory ->
        presentCategories.any { it.equals(trackerCategory, ignoreCase = true) }
    }
    val trackerCategoryKeys = trackerCategories.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    val discoveryOnlyCategories = presentCategories
        .filterNot { it.lowercase(Locale.ROOT) in trackerCategoryKeys }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    return listOf("All") + trackerCategories + discoveryOnlyCategories
}

internal fun isSupportedMealType(value: String): Boolean =
    value in listOf("Breakfast", "Lunch", "Dinner", "Snack")

internal fun foodResultCountLabel(count: Int, filtering: Boolean): String =
    if (filtering) "$count matching foods" else "$count foods"

internal fun browseAllFoodsLabel(count: Int): String = "Browse all $count foods"

internal fun nutritionCompositionLabel(verifiedCount: Int, discoveryCount: Int): String =
    buildString {
        append("$verifiedCount verified nutrition")
        if (discoveryCount > 0) {
            append(" • $discoveryCount South Asian foods (nutrition verification in progress)")
        }
    }

@Composable
fun CalorieTrackerScreen(
    todayConsumed: Int,
    dailyGoal: Int,
    calorieLogs: List<CalorieLog>,
    proteinGoal: Float,
    carbsGoal: Float,
    fatGoal: Float,
    showMacroTargetProgress: Boolean,
    onAddCalorie: (Int, String, String, Float, Float, Float, Float) -> Unit,
    onDeleteCalorie: (CalorieLog) -> Unit,
    onUpdateGoal: (Int) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    val catalogueState = remember(context, reloadKey) {
        runCatching {
            val repository = FoodRepository(context)
            NutritionCatalogueState.Content(
                foods = repository.getPakistaniFoods(),
                discoveryFoods = repository.getSouthAsianFoods()
            )
        }.getOrElse { NutritionCatalogueState.Error }
    }
    NutritionTrackerLayout(
        catalogueState = catalogueState,
        todayConsumed = todayConsumed,
        dailyGoal = dailyGoal,
        calorieLogs = calorieLogs,
        proteinGoal = proteinGoal,
        carbsGoal = carbsGoal,
        fatGoal = fatGoal,
        showMacroTargetProgress = showMacroTargetProgress,
        onAddCalorie = onAddCalorie,
        onDeleteCalorie = onDeleteCalorie,
        onUpdateGoal = onUpdateGoal,
        onBack = onBack,
        onRetryCatalogue = { reloadKey += 1 },
        modifier = modifier
    )
}

@Composable
private fun NutritionTrackerLayout(
    catalogueState: NutritionCatalogueState,
    todayConsumed: Int,
    dailyGoal: Int,
    calorieLogs: List<CalorieLog>,
    proteinGoal: Float,
    carbsGoal: Float,
    fatGoal: Float,
    showMacroTargetProgress: Boolean,
    onAddCalorie: (Int, String, String, Float, Float, Float, Float) -> Unit,
    onDeleteCalorie: (CalorieLog) -> Unit,
    onUpdateGoal: (Int) -> Unit,
    onBack: (() -> Unit)?,
    onRetryCatalogue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var expandedFoodId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllFoods by rememberSaveable { mutableStateOf(false) }
    var foodToLog by remember { mutableStateOf<FoodItem?>(null) }
    var showManualLog by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var loggedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val todayLogs = remember(calorieLogs) {
        calorieLogs.filter(::isTodayNutritionLog)
    }
    val totals = remember(todayLogs) { calculateNutritionTotals(todayLogs) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("calorie_tracker_screen")
    ) {
        NutritionTopBar(onBack)
        when (catalogueState) {
            NutritionCatalogueState.Error -> FitDesiErrorState(
                title = "Food library unavailable",
                message = "The local food catalogue could not be loaded.",
                actionLabel = "Try again",
                onAction = onRetryCatalogue,
                modifier = Modifier.fillMaxSize()
            )
            is NutritionCatalogueState.Content -> {
                val foods = catalogueState.foods
                val discoveryFoods = catalogueState.discoveryFoods
                val categories = remember(foods, discoveryFoods) {
                    foodCategoryOptions(foods, discoveryFoods)
                }
                val filteredFoods = remember(foods, searchQuery, selectedCategory) {
                    filterPakistaniFoods(foods, searchQuery, selectedCategory)
                }
                val filteredDiscovery = remember(discoveryFoods, searchQuery, selectedCategory) {
                    filterDiscoveryFoods(discoveryFoods, searchQuery, selectedCategory)
                }
                val visibleFoods = if (!showAllFoods && searchQuery.isBlank() && selectedCategory == "All") {
                    filteredFoods.take(FEATURED_FOOD_LIMIT)
                } else {
                    filteredFoods
                }
                val verifiedCount = foods.count { it.loggingEligibility.status == FoodLoggingStatus.VERIFIED }
                val discoveryCount = discoveryFoods.size
                val totalFoodConcepts = foods.size + discoveryFoods.size

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                ) {
                    item {
                        NutritionOverviewCard(
                            consumed = totals.calories.takeIf { it > 0 } ?: todayConsumed,
                            dailyGoal = dailyGoal,
                            protein = totals.proteinGrams,
                            carbs = totals.carbsGrams,
                            fat = totals.fatGrams,
                            proteinGoal = proteinGoal,
                            carbsGoal = carbsGoal,
                            fatGoal = fatGoal,
                            showMacroTargetProgress = showMacroTargetProgress,
                            onEditGoal = { showGoalDialog = true },
                            modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                        )
                    }
                    item {
                        AnimatedVisibility(
                            visible = loggedMessage != null,
                            enter = fadeIn(tween(FitDesiMotion.standard)),
                            exit = fadeOut(tween(FitDesiMotion.fast))
                        ) {
                            NutritionSuccessBanner(
                                message = loggedMessage.orEmpty(),
                                onDismiss = { loggedMessage = null },
                                modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                            )
                        }
                    }
                    item {
                        NutritionDiscoveryCard(
                            modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                        )
                    }
                    item {
                        NutritionSearchAndFilters(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                showAllFoods = true
                            },
                            selectedCategory = selectedCategory,
                            onCategorySelected = {
                                selectedCategory = it
                                showAllFoods = true
                                expandedFoodId = null
                            },
                            categories = categories
                        )
                    }
                    item {
                        ResultSummary(
                            total = totalFoodConcepts,
                            filtered = filteredFoods.size + filteredDiscovery.size,
                            verified = verifiedCount,
                            discovery = discoveryCount,
                            filtering = searchQuery.isNotBlank() || selectedCategory != "All",
                            modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                        )
                    }
                    if (filteredFoods.isEmpty() && filteredDiscovery.isEmpty()) {
                        item {
                            FitDesiEmptyState(
                                title = "No foods found",
                                message = "Try another name, Roman Urdu spelling, or category.",
                                actionLabel = "Clear filters",
                                onAction = {
                                    searchQuery = ""
                                    selectedCategory = "All"
                                    showAllFoods = false
                                }
                            )
                        }
                    } else {
                        items(visibleFoods, key = FoodItem::id) { food ->
                            NutritionFoodCard(
                                food = food,
                                expanded = expandedFoodId == food.id,
                                onToggle = {
                                    expandedFoodId = if (expandedFoodId == food.id) null else food.id
                                },
                                onLog = {
                                    if (food.loggingEligibility.canLog) foodToLog = food
                                },
                                modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                            )
                        }
                        if (!showAllFoods && filteredFoods.size > visibleFoods.size) {
                            item {
                                OutlinedButton(
                                    onClick = { showAllFoods = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = FitDesiSpacing.medium)
                                        .defaultMinSize(minHeight = FitDesiDimensions.minimumTouchTarget)
                                        .testTag("show_all_foods")
                                ) {
                                    Text(browseAllFoodsLabel(foods.size))
                                }
                            }
                        }
                        if (filteredDiscovery.isNotEmpty()) {
                            item {
                                SouthAsianDiscoveryHeader(
                                    count = filteredDiscovery.size,
                                    modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                                )
                            }
                            items(filteredDiscovery, key = DiscoveryFoodItem::id) { discoveryFood ->
                                SouthAsianDiscoveryFoodCard(
                                    food = discoveryFood,
                                    modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                                )
                            }
                        }
                    }
                    item {
                        LoggedMealsHeader(
                            count = todayLogs.size,
                            onManualLog = { showManualLog = true },
                            modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                        )
                    }
                    if (todayLogs.isEmpty()) {
                        item {
                            FitDesiEmptyState(
                                title = "No meals logged today",
                                message = "Expand an eligible food to log it, or add a manual calorie entry.",
                                actionLabel = "Log manually",
                                onAction = { showManualLog = true }
                            )
                        }
                    } else {
                        items(todayLogs, key = CalorieLog::id) { log ->
                            LoggedMealCard(
                                log = log,
                                onDelete = { onDeleteCalorie(log) },
                                modifier = Modifier.padding(horizontal = FitDesiSpacing.medium)
                            )
                        }
                    }
                }
            }
        }
    }

    foodToLog
        ?.takeIf { food ->
            food.loggingEligibility.canLog && foodLoggingPresentation(food).logActionLabel != null
        }
        ?.let { food ->
            NutritionLogFoodDialog(
                food = food,
                onDismiss = { foodToLog = null },
                onLog = { quantity, mealType ->
                    onAddCalorie(
                        food.caloriesPerServing,
                        mealType,
                        "${food.name} (${formatQuantity(quantity)}x)",
                        food.proteinGrams,
                        food.carbsGrams,
                        food.fatGrams,
                        quantity
                    )
                    loggedMessage = "${food.name} added to today's meals."
                    foodToLog = null
                }
            )
        }
    if (showManualLog) {
        NutritionManualLogDialog(
            onDismiss = { showManualLog = false },
            onAdd = { calories, meal, description ->
                onAddCalorie(calories, meal, description, 0f, 0f, 0f, 1f)
                loggedMessage = "$description added to today's meals."
                showManualLog = false
            }
        )
    }
    if (showGoalDialog) {
        NutritionGoalDialog(
            currentGoal = dailyGoal,
            onDismiss = { showGoalDialog = false },
            onSave = {
                onUpdateGoal(it)
                showGoalDialog = false
            }
        )
    }
}

@Composable
private fun NutritionTopBar(onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FitDesiSpacing.extraSmall, vertical = FitDesiSpacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to previous screen")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Pakistani Food Tracker",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "Local food nutrition and daily meal log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NutritionOverviewCard(
    consumed: Int,
    dailyGoal: Int,
    protein: Float,
    carbs: Float,
    fat: Float,
    proteinGoal: Float,
    carbsGoal: Float,
    fatGoal: Float,
    showMacroTargetProgress: Boolean,
    onEditGoal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presentation = nutritionProgressPresentation(
        consumedCalories = consumed,
        calorieTarget = dailyGoal,
        consumedProteinGrams = protein,
        consumedCarbsGrams = carbs,
        consumedFatGrams = fat,
        proteinTargetGrams = proteinGoal,
        carbsTargetGrams = carbsGoal,
        fatTargetGrams = fatGoal,
        showMacroTargetProgress = showMacroTargetProgress
    )
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = presentation.calories.progress ?: 0f,
        animationSpec = tween(FitDesiMotion.emphasis),
        label = "nutrition calorie progress"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "$consumed kcal",
                        style = MaterialTheme.typography.headlineLarge.merge(NumbersTextStyle),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            dailyGoal <= 0 -> "Daily target not set"
                            consumed <= dailyGoal -> "${dailyGoal - consumed} kcal remaining"
                            else -> "${consumed - dailyGoal} kcal above target"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEditGoal, modifier = Modifier.testTag("edit_calorie_goal")) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit daily calorie target")
                }
            }
            if (dailyGoal > 0) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FitDesiDimensions.progressHeight)
                        .clip(CircleShape),
                    color = if (consumed > dailyGoal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            NutritionMacroGrid(
                presentation = presentation
            )
        }
    }
}

@Composable
private fun NutritionMacroGrid(
    presentation: NutritionProgressPresentation
) {
    BoxWithConstraints {
        val stack = maxWidth < 330.dp || LocalDensity.current.fontScale >= 1.3f
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                MacroSummary("Protein", presentation.protein, presentation.canShowMacroTargets, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                MacroSummary("Carbs", presentation.carbs, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.info, Modifier.fillMaxWidth())
                MacroSummary("Fat", presentation.fat, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.warning, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                MacroSummary("Protein", presentation.protein, presentation.canShowMacroTargets, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                MacroSummary("Carbs", presentation.carbs, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.info, Modifier.weight(1f))
                MacroSummary("Fat", presentation.fat, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.warning, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MacroSummary(
    label: String,
    presentation: MacroProgressPresentation,
    canShowMacroTargets: Boolean,
    color: Color,
    modifier: Modifier
) {
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = presentation.progress ?: 0f,
        animationSpec = tween(FitDesiMotion.emphasis),
        label = "$label nutrition progress"
    )
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.padding(FitDesiSpacing.small)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatMacro(presentation.consumedGrams)} g", style = MaterialTheme.typography.titleMedium, color = color)
            Text(
                when (val target = presentation.targetGrams) {
                    null -> if (canShowMacroTargets) "Target not set" else "Consumed"
                    else -> "of ${formatMacro(target)} g"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (presentation.targetGrams != null) {
                Spacer(Modifier.height(FitDesiSpacing.micro))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = color,
                    trackColor = color.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun NutritionDiscoveryCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
        ) {
            FoodCategoryIllustration(
                category = "Pakistani foods",
                modifier = Modifier.size(76.dp),
                compact = true
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Explore local favourites", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Search biryani, roti, daal, chana, karahi, breakfast and more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun NutritionSearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    categories: List<String>
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search foods or Roman Urdu names") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear food search")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FitDesiSpacing.medium)
                .testTag("food_search_bar")
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
        ) {
            items(categories, key = { it }) { category ->
                val isSelected = selectedCategory == category
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    animationSpec = tween(FitDesiMotion.fast),
                    label = "nutrition category"
                )
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelected(category) },
                    label = {
                        Text(
                            text = categoryPresentationLabel(category),
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = containerColor,
                        selectedContainerColor = containerColor,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = FitDesiDimensions.minimumTouchTarget)
                        .semantics {
                            selected = isSelected
                            role = Role.Tab
                        }
                )
            }
        }
    }
}

@Composable
private fun ResultSummary(
    total: Int,
    filtered: Int,
    verified: Int,
    discovery: Int,
    filtering: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = if (filtering) filtered else total,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "food result count"
        ) { count ->
            Text(
                text = foodResultCountLabel(count, filtering),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
        }
        Text(
            text = nutritionCompositionLabel(verified, discovery),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NutritionFoodCard(
    food: FoodItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val eligibility = food.loggingEligibility
    val presentation = foodLoggingPresentation(food)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
            .clickable(onClickLabel = if (expanded) "Collapse food details" else "Expand food details") { onToggle() }
            .testTag("food_item_${food.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(FitDesiSpacing.medium)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = food.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(FitDesiSpacing.micro))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)
                    ) {
                        CompactBadge(categoryPresentationLabel(food.category), MaterialTheme.colorScheme.primaryContainer)
                        FoodStatusBadge(presentation)
                    }
                    Spacer(Modifier.height(FitDesiSpacing.extraSmall))
                    Text(
                        text = presentation.nutritionSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse ${food.name}" else "Expand ${food.name}"
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = FitDesiSpacing.small))
                FoodCategoryIllustration(
                    category = food.category,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp)
                )
                Spacer(Modifier.height(FitDesiSpacing.small))
                Text(
                    text = presentation.detailNote,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(FitDesiSpacing.small))
                NutrientGrid(calculateServingTotals(food, 1f))
                Spacer(Modifier.height(FitDesiSpacing.small))
                if (eligibility.canLog && presentation.logActionLabel != null) {
                    Button(
                        onClick = onLog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = FitDesiDimensions.primaryControlHeight)
                            .testTag("quick_log_${food.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                        Text(presentation.logActionLabel)
                    }
                } else {
                    Surface(
                        color = when (presentation.visualTreatment) {
                            FoodLoggingVisualTreatment.SUCCESS -> MaterialTheme.fitDesiColors.success.copy(alpha = 0.18f)
                            FoodLoggingVisualTreatment.WARNING -> MaterialTheme.fitDesiColors.warning.copy(alpha = 0.18f)
                            FoodLoggingVisualTreatment.DANGER -> MaterialTheme.colorScheme.errorContainer
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "${presentation.unavailableTitle}. ${eligibility.explanation}"
                            }
                    ) {
                        Column(modifier = Modifier.padding(FitDesiSpacing.small)) {
                            Text(presentation.unavailableTitle.orEmpty(), style = MaterialTheme.typography.labelLarge)
                            Text(eligibility.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SouthAsianDiscoveryHeader(count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "South Asian foods",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "$count cultural foods • nutrition verification in progress",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SouthAsianDiscoveryFoodCard(food: DiscoveryFoodItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discovery_item_${food.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(FitDesiSpacing.medium)) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(FitDesiSpacing.micro))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)
            ) {
                CompactBadge(categoryPresentationLabel(food.category), MaterialTheme.colorScheme.primaryContainer)
                CompactBadge(food.cuisine, MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(Modifier.height(FitDesiSpacing.extraSmall))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovery_status_${food.id}")
                    .semantics { contentDescription = DISCOVERY_STATUS_LABEL }
            ) {
                Row(
                    modifier = Modifier.padding(FitDesiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = DISCOVERY_STATUS_LABEL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FoodStatusBadge(presentation: FoodLoggingPresentation) {
    val color = when (presentation.visualTreatment) {
        FoodLoggingVisualTreatment.SUCCESS -> MaterialTheme.fitDesiColors.success
        FoodLoggingVisualTreatment.WARNING -> MaterialTheme.fitDesiColors.warning
        FoodLoggingVisualTreatment.DANGER -> MaterialTheme.fitDesiColors.danger
    }
    CompactBadge(presentation.badgeLabel, color.copy(alpha = 0.18f), color)
}

@Composable
private fun CompactBadge(label: String, background: Color, foreground: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FoodCategoryIllustration(category: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val icon: ImageVector = when {
        "drink" in category.lowercase() -> Icons.Default.LocalCafe
        "vegetable" in category.lowercase() || "lentil" in category.lowercase() -> Icons.Default.Spa
        "rice" in category.lowercase() || "grain" in category.lowercase() -> Icons.Default.RiceBowl
        else -> Icons.Default.Restaurant
    }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "$category category illustration" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 54.dp else 82.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (compact) 28.dp else 40.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(FitDesiSpacing.small)
                .size(if (compact) 10.dp else 16.dp)
                .background(MaterialTheme.fitDesiColors.success, CircleShape)
        )
    }
}

@Composable
private fun NutrientGrid(totals: NutritionTotals) {
    BoxWithConstraints {
        val values = listOf(
            "Calories" to "${totals.calories} kcal",
            "Protein" to "${formatMacro(totals.proteinGrams)} g",
            "Carbs" to "${formatMacro(totals.carbsGrams)} g",
            "Fat" to "${formatMacro(totals.fatGrams)} g"
        )
        val columns = if (maxWidth >= 480.dp && LocalDensity.current.fontScale < 1.3f) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
            values.chunked(columns).forEach { rowValues ->
                Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    rowValues.forEach { (label, value) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(modifier = Modifier.padding(FitDesiSpacing.small)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    repeat(columns - rowValues.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LoggedMealsHeader(count: Int, onManualLog: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Today's logged meals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("$count entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onManualLog, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Log manually")
        }
    }
}

@Composable
private fun LoggedMealCard(log: CalorieLog, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val totals = calculateNutritionTotals(listOf(log))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Icon(
                    Icons.Default.Restaurant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp).size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(log.description, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${log.mealType} • ${formatQuantity(log.servings)} serving reference",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "P ${formatMacro(totals.proteinGrams)} g • C ${formatMacro(totals.carbsGrams)} g • F ${formatMacro(totals.fatGrams)} g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${totals.calories} kcal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_meal_${log.id}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete ${log.description} from today's meals",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun NutritionSuccessBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.fitDesiColors.success.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NutritionLogFoodDialog(
    food: FoodItem,
    onDismiss: () -> Unit,
    onLog: (Float, String) -> Unit
) {
    var quantityText by rememberSaveable { mutableStateOf("1") }
    var selectedMeal by rememberSaveable { mutableStateOf("Breakfast") }
    val quantity = quantityText.toFloatOrNull()
    val validQuantity = quantity != null && quantity.isFinite() && quantity > 0f && quantity <= MAX_LOG_QUANTITY
    val totals = calculateServingTotals(food, quantity ?: 0f)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .imePadding(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
            ) {
                Text("Log ${food.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    foodLoggingPresentation(food).detailNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    supportingText = { Text("Use 0.5–$MAX_LOG_QUANTITY reference portions") },
                    isError = quantityText.isNotBlank() && !validQuantity,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                ) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { preset ->
                        AssistChip(onClick = { quantityText = formatQuantity(preset) }, label = { Text("${formatQuantity(preset)}×") })
                    }
                }
                AnimatedContent(targetState = totals, label = "nutrition log totals") { currentTotals ->
                    NutrientGrid(currentTotals)
                }
                Text("Meal", style = MaterialTheme.typography.labelLarge)
                MealTypeSelector(selectedMeal = selectedMeal, onSelected = { selectedMeal = it })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                    Button(
                        onClick = { onLog(quantity ?: 0f, selectedMeal) },
                        enabled = food.loggingEligibility.canLog && validQuantity,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) { Text("Log food") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealTypeSelector(selectedMeal: String, onSelected: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
    ) {
        SUPPORTED_MEALS.forEach { meal ->
            FilterChip(
                selected = selectedMeal == meal,
                onClick = { onSelected(meal) },
                label = { Text(meal) },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { selected = selectedMeal == meal }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NutritionManualLogDialog(onDismiss: () -> Unit, onAdd: (Int, String, String) -> Unit) {
    var caloriesText by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedMeal by rememberSaveable { mutableStateOf("Breakfast") }
    val calories = caloriesText.toIntOrNull()
    val valid = calories != null && calories > 0 && description.isNotBlank()
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).imePadding(), shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
            ) {
                Text("Manual calorie entry", style = MaterialTheme.typography.titleLarge)
                Text("Use values you already know. Macros remain zero when they are not provided.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Meal description") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it },
                    label = { Text("Calories") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                MealTypeSelector(selectedMeal, onSelected = { selectedMeal = it })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onAdd(calories ?: 0, selectedMeal, description.trim()) }, enabled = valid) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun NutritionGoalDialog(currentGoal: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var goalText by rememberSaveable(currentGoal) { mutableStateOf(currentGoal.takeIf { it > 0 }?.toString().orEmpty()) }
    val goal = goalText.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily calorie target") },
        text = {
            OutlinedTextField(
                value = goalText,
                onValueChange = { goalText = it },
                label = { Text("Calories") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(goal ?: 0) }, enabled = goal != null && goal > 0) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun isTodayNutritionLog(log: CalorieLog): Boolean {
    val formatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return formatter.format(Date(log.timestamp)) == formatter.format(Date())
}

private fun categoryPresentationLabel(category: String): String = when (category) {
    "Protein & Main Dishes" -> "Protein mains"
    "Snacks & Street Food" -> "Street food"
    "Lentils & Legumes" -> "Lentils"
    "Vegetable Dishes" -> "Vegetables"
    "Grains & Rice" -> "Grains & rice"
    "Dairy & Sides" -> "Dairy & sides"
    else -> category
}

private fun formatMacro(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.05f) value.roundToInt().toString() else "%.1f".format(Locale.US, value)

private fun formatQuantity(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) value.roundToInt().toString() else "%.1f".format(Locale.US, value)

private const val FEATURED_FOOD_LIMIT = 6
private const val MAX_LOG_QUANTITY = 10
private val SUPPORTED_MEALS = listOf("Breakfast", "Lunch", "Dinner", "Snack")

private fun previewFood(
    id: String,
    name: String,
    status: FoodLoggingStatus,
    calories: Int
) = FoodItem(
    id = id,
    name = name,
    aliases = emptyList(),
    category = if ("Biryani" in name) "Grains & Rice" else "Lentils & Legumes",
    caloriesPerServing = calories,
    proteinGrams = 12f,
    carbsGrams = 38f,
    fatGrams = 8f,
    servingUnit = if (status == FoodLoggingStatus.REVIEW_REQUIRED) "Serving basis under review" else "1 bowl",
    nutritionDisplayLabel = "Preview nutrition reference",
    reviewStatus = when (status) {
        FoodLoggingStatus.VERIFIED -> "FITDESI_NUTRITION_VERIFIED"
        FoodLoggingStatus.LEGACY_ESTIMATE -> "EXISTING_FITDESI_LOGGABLE"
        FoodLoggingStatus.REVIEW_REQUIRED,
        FoodLoggingStatus.MORE_DATA_NEEDED -> "NEEDS_FITDESI_NUTRITION_REVIEW"
    },
    isLoggable = status == FoodLoggingStatus.VERIFIED || status == FoodLoggingStatus.LEGACY_ESTIMATE,
    runtimeSource = "PREVIEW_ONLY",
    loggingEligibility = FoodLoggingEligibility(
        status = status,
        canLog = status == FoodLoggingStatus.VERIFIED || status == FoodLoggingStatus.LEGACY_ESTIMATE,
        commercialReleaseEligible = status == FoodLoggingStatus.VERIFIED,
        explanation = if (status == FoodLoggingStatus.MORE_DATA_NEEDED) "A positive calorie value is required before this food can be logged." else "Preview"
    )
)

@Preview(name = "Nutrition V1 Light", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun NutritionV1LightPreview() {
    MyPersonalTrainerTheme(theme = "Light") {
        NutritionTrackerLayout(
            catalogueState = NutritionCatalogueState.Content(
                foods = listOf(
                    previewFood("preview-1", "Chicken Biryani", FoodLoggingStatus.VERIFIED, 420),
                    previewFood("preview-2", "Pindi Chana", FoodLoggingStatus.LEGACY_ESTIMATE, 260),
                    previewFood("preview-3", "Nutrition pending", FoodLoggingStatus.REVIEW_REQUIRED, 260)
                ),
                discoveryFoods = listOf(
                    DiscoveryFoodItem(
                        id = "fd-discovery-chicken-biryani",
                        name = "Chicken Biryani",
                        category = "Rice & Meals",
                        cuisine = "South Asian",
                        nutritionStatus = "NUTRITION_VERIFICATION_IN_PROGRESS"
                    )
                )
            ),
            todayConsumed = 680,
            dailyGoal = 2200,
            calorieLogs = emptyList(),
            proteinGoal = 100f,
            carbsGoal = 300f,
            fatGoal = 70f,
            showMacroTargetProgress = true,
            onAddCalorie = { _, _, _, _, _, _, _ -> },
            onDeleteCalorie = {},
            onUpdateGoal = {},
            onBack = {},
            onRetryCatalogue = {}
        )
    }
}

@Preview(name = "Nutrition V1 Dark 320", widthDp = 320, heightDp = 900, showBackground = true)
@Composable
private fun NutritionV1DarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") {
        NutritionTrackerLayout(
            catalogueState = NutritionCatalogueState.Content(
                foods = listOf(previewFood("preview-1", "Chicken Biryani", FoodLoggingStatus.LEGACY_ESTIMATE, 420)),
                discoveryFoods = emptyList()
            ),
            todayConsumed = 0,
            dailyGoal = 0,
            calorieLogs = emptyList(),
            proteinGoal = 0f,
            carbsGoal = 0f,
            fatGoal = 0f,
            showMacroTargetProgress = false,
            onAddCalorie = { _, _, _, _, _, _, _ -> },
            onDeleteCalorie = {},
            onUpdateGoal = {},
            onBack = {},
            onRetryCatalogue = {}
        )
    }
}

@Preview(name = "Nutrition V1 Error Large Text", widthDp = 360, heightDp = 720, fontScale = 1.5f, showBackground = true)
@Composable
private fun NutritionV1ErrorPreview() {
    MyPersonalTrainerTheme(theme = "Light") {
        NutritionTrackerLayout(
            catalogueState = NutritionCatalogueState.Error,
            todayConsumed = 0,
            dailyGoal = 0,
            calorieLogs = emptyList(),
            proteinGoal = 0f,
            carbsGoal = 0f,
            fatGoal = 0f,
            showMacroTargetProgress = false,
            onAddCalorie = { _, _, _, _, _, _, _ -> },
            onDeleteCalorie = {},
            onUpdateGoal = {},
            onBack = {},
            onRetryCatalogue = {}
        )
    }
}
