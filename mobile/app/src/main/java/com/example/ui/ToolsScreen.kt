package com.example.ui

import androidx.activity.compose.BackHandler
import com.example.domain.CalorieEngine
import com.example.domain.CalorieSex
import com.example.domain.LifestyleActivityLevel
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.OswaldFontFamily
import com.example.domain.MacroCalculation
import com.example.domain.MacroRatio
import com.example.domain.OneRepMaxCalculation
import com.example.domain.WeightUnit
import com.example.domain.calculateMacros
import com.example.domain.calculateOneRepMax
import com.example.domain.convertWeight
import com.example.domain.standardMacroRatios
import com.example.viewmodel.NutritionTargetSaveResult
import com.example.viewmodel.TrainerViewModel
import com.example.viewmodel.SavedDietPlansUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// --- DATA STRUCTURE FOR UTILITY TOOL ---
data class UtilityTool(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val key: String,
    val category: String = "Fitness utilities",
    val featured: Boolean = false
)

internal fun pakistaniFoodTrackerTool() = UtilityTool(
    id = "7",
    name = "Pakistani Food Calorie Tracker",
    description = "Search and log local and global foods",
    icon = Icons.Default.Restaurant,
    accentColor = Color(0xFFFF6D00),
    key = "pakistani_food",
    category = "Nutrition",
    featured = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    viewModel: TrainerViewModel? = null,
    onNavigateBack: () -> Unit = {},
    onNavigateToCalories: () -> Unit = {},
    initialToolKey: String? = null,
    initialSavedDietPlanId: String? = null,
    onInitialToolHandled: () -> Unit = {},
    onMacroTargetsRequirePlus: () -> Unit = {}
) {
    var selectedToolKey by rememberSaveable { mutableStateOf<String?>(null) }
    var isSavingMacroTargets by remember { mutableStateOf(false) }
    var selectedDietPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    val favoriteExercises by if (viewModel != null) {
        viewModel.favoriteExercises.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val trainingNotes by if (viewModel != null) {
        viewModel.trainingNotes.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val savedDietPlansState by if (viewModel != null) {
        viewModel.savedDietPlansState.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<SavedDietPlansUiState>(SavedDietPlansUiState.Success(emptyList())) }
    }

    LaunchedEffect(initialToolKey) {
        if (initialToolKey != null) {
            selectedToolKey = initialToolKey
            selectedDietPlanId = initialSavedDietPlanId
            onInitialToolHandled()
        }
    }

    BackHandler(enabled = selectedToolKey != null) {
        if (selectedToolKey == "saved_diet_plans" && selectedDietPlanId != null) {
            selectedDietPlanId = null
        } else {
            selectedToolKey = null
        }
    }

    // Color definitions come from the app-level Material theme.
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryAccent = MaterialTheme.colorScheme.primary

    // Stacked row utilities list specification
    val toolsList = remember {
        listOf(
            UtilityTool(
                id = "1",
                name = "Favorites",
                description = "Track your top lifts and target configurations",
                icon = Icons.Default.Favorite,
                accentColor = Color(0xFFFF1744),
                key = "favorites",
                category = "Planning"
            ),
            UtilityTool(
                id = "2",
                name = "Notes",
                description = "Quick training logs, bench settings, and reminders",
                icon = Icons.Default.Description,
                accentColor = Color(0xFF29B6F6),
                key = "notes",
                category = "Planning"
            ),
            UtilityTool(
                id = "3",
                name = "Rest Timer / Stopwatch",
                description = "Interactive timer for standard active rest intervals",
                icon = Icons.Default.Timer,
                accentColor = Color(0xFF00E676),
                key = "timer",
                category = "Fitness utilities"
            ),
            UtilityTool(
                id = "4",
                name = "Calorie Calculator",
                description = "Determine BMR, TDEE, and daily physical burn ratios",
                icon = Icons.Default.Calculate, // Fallback to custom/standard
                accentColor = Color(0xFFFF9100),
                key = "calorie",
                category = "Training calculations"
            ),
            UtilityTool(
                id = "5",
                name = "Macro Calculator",
                description = "Customize protein, carbohydrate, and fat split targeting",
                icon = Icons.Default.PieChart,
                accentColor = Color(0xFFFF9100),
                key = "macro",
                category = "Nutrition",
                featured = true
            ),
            UtilityTool(
                id = "6",
                name = "One Rep Max Calculator",
                description = "Estimate maximum lifting capacity using weight and reps",
                icon = Icons.Default.FitnessCenter,
                accentColor = Color(0xFFFF9100),
                key = "1rm",
                category = "Training calculations"
            ),
            pakistaniFoodTrackerTool(),
            UtilityTool(
                id = "8",
                name = "Saved Diet Plans",
                description = "View and manage locally saved Pakistani meal plans",
                icon = Icons.Default.MenuBook,
                accentColor = Color(0xFF29B6F6),
                key = "saved_diet_plans",
                category = "Nutrition"
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selectedToolKey != null) {
                        IconButton(
                            onClick = {
                                if (selectedToolKey == "saved_diet_plans" &&
                                    selectedDietPlanId != null
                                ) {
                                    selectedDietPlanId = null
                                } else {
                                    selectedToolKey = null
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(surfaceColor)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go back",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (selectedToolKey == null) "Tools" else {
                                toolsList.firstOrNull { it.key == selectedToolKey }?.name?.uppercase() ?: "TOOL"
                            },
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (selectedToolKey == null) 30.sp else 22.sp,
                            lineHeight = if (selectedToolKey == null) 34.sp else 26.sp,
                            color = onSurfaceColor,
                            letterSpacing = 1.1.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedToolKey == null) "Everyday nutrition, planning, and training utilities" else "FitDesi utility",
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = onSurfaceSecondaryColor,
                            maxLines = 2
                        )
                    }
                }

            }

            Spacer(modifier = Modifier.height(4.dp))

            // Screen content area
            AnimatedContent(
                targetState = selectedToolKey,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "tools_navigation"
            ) { targetKey ->
                when (targetKey) {
                    null -> {
                        ToolsDirectory(
                            tools = toolsList,
                            surfaceColor = surfaceColor,
                            borderColor = cardBorderColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            onToolClick = { tool ->
                                if (tool.key == "pakistani_food") onNavigateToCalories()
                                else selectedToolKey = tool.key
                            }
                        )
                    }
                    "favorites" -> {
                        FavoritesToolView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            favoritesList = favoriteExercises,
                            onFavoritesChange = { viewModel?.saveFavoriteExercises(it) }
                        )
                    }
                    "notes" -> {
                        NotesToolView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            accentColor = Color(0xFF29B6F6),
                            notesList = trainingNotes,
                            onNotesChange = { viewModel?.saveTrainingNotes(it) }
                        )
                    }
                    "timer" -> {
                        StopwatchTimerView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            accentColor = Color(0xFF00E676)
                        )
                    }
                    "calorie" -> {
                        BmrCalorieToolView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            accentColor = Color(0xFFFF9100)
                        )
                    }
                    "macro" -> {
                        MacroToolView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            accentColor = Color(0xFFFF9100),
                            onFindDailyNeeds = { selectedToolKey = "calorie" },
                            onFinish = onFinish@{ result ->
                                if (viewModel == null) {
                                    selectedToolKey = null
                                } else {
                                    if (isSavingMacroTargets) return@onFinish
                                    isSavingMacroTargets = true
                                    viewModel.saveNutritionTargets(
                                        calories = result.calories,
                                        carbsGrams = result.dailyCarbsGrams.toFloat(),
                                        proteinGrams = result.dailyProteinGrams.toFloat(),
                                        fatGrams = result.dailyFatGrams.toFloat()
                                    ) { saveResult ->
                                        isSavingMacroTargets = false
                                        when (saveResult) {
                                            NutritionTargetSaveResult.SAVED -> selectedToolKey = null
                                            NutritionTargetSaveResult.REQUIRES_PLUS -> onMacroTargetsRequirePlus()
                                            NutritionTargetSaveResult.INVALID_TARGETS,
                                            NutritionTargetSaveResult.ERROR -> Unit
                                        }
                                    }
                                }
                            }
                        )
                    }
                    "1rm" -> {
                        OneRepMaxToolView(
                            surfaceColor = surfaceColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            accentColor = Color(0xFFFF9100)
                        )
                    }
                    "saved_diet_plans" -> {
                        SavedDietPlansToolView(
                            state = savedDietPlansState,
                            selectedPlanId = selectedDietPlanId,
                            onSelectPlan = { selectedDietPlanId = it },
                            onBackToList = { selectedDietPlanId = null },
                            onRetry = { viewModel?.retryLoadSavedDietPlans() },
                            onDeletePlan = { planId, onResult ->
                                viewModel?.deleteSavedDietPlan(planId, onResult)
                                    ?: onResult(false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsDirectory(
    tools: List<UtilityTool>,
    surfaceColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    onToolClick: (UtilityTool) -> Unit
) {
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val categoryOrder = listOf("Nutrition", "Training calculations", "Planning", "Fitness utilities")
    val featuredTools = tools.filter { it.featured }

    AnimatedVisibility(
        visible = contentVisible,
        enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(200)),
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("tools_list_stack"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Featured tools",
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = onSurfaceColor
                )
            }
            items(featuredTools, key = { "featured_${it.id}" }) { tool ->
                FeaturedToolCard(
                    tool = tool,
                    borderColor = borderColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                    emphasized = tool.key == "pakistani_food",
                    onClick = { onToolClick(tool) }
                )
            }
            categoryOrder.forEach { category ->
                val categoryTools = tools.filter { it.category == category && !it.featured }
                if (categoryTools.isNotEmpty()) {
                    item(key = "heading_$category") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = category.uppercase(),
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 0.8.sp,
                                color = onSurfaceSecondaryColor,
                                maxLines = 1
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = borderColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                    items(categoryTools, key = { it.id }) { tool ->
                        ToolRowItem(
                            tool = tool,
                            surfaceColor = surfaceColor,
                            borderColor = borderColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            onClick = { onToolClick(tool) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedToolCard(
    tool: UtilityTool,
    borderColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    val titleColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else onSurfaceColor
    val detailColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    } else {
        onSurfaceSecondaryColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp)
            .clickable(onClick = onClick)
            .testTag("featured_tool_${tool.key}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else borderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(5.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(tool.accentColor)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "FEATURED TOOL",
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = tool.accentColor
                    )
                    Text(
                        text = tool.name,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = tool.description,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = detailColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(tool.accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = "Open ${tool.name}",
                        tint = tool.accentColor,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }
        }
    }
}

// --- SUB-COMPONENT: UNIFORM LIST-ITEM ROW (MINIMUM 48DP HEIGHT) ---
@Composable
fun ToolRowItem(
    tool: UtilityTool,
    surfaceColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Soft container color calculation for left-hand icon (10%-15% opacity)
    val softIconContainerBg = tool.accentColor.copy(alpha = 0.12f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp) // Generous sizing exceeding 48dp touch target mandate
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("tool_row_${tool.key}"),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // A. LEFT-HAND ACCENT: Small, soft-colored icon container housing a clean theme vector
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(softIconContainerBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.name,
                    tint = tool.accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // B. TEXT CORE STACK: Name in semi-bold font over secondary descriptive line
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = tool.name,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = onSurfaceColor,
                    maxLines = if (tool.key == "pakistani_food") 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tool.description,
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = onSurfaceSecondaryColor,
                    maxLines = if (tool.key == "pakistani_food") 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // C. RIGHT-HAND ELEMENT: Navigation indicator arrow right-aligned to suggest transition
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Navigate to ${tool.name}",
                tint = onSurfaceSecondaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// =========================================================================
// UTILITY VIEW 1: FAVORITES SUB-PANE
// =========================================================================
@Composable
fun FavoritesToolView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    favoritesList: List<String>,
    onFavoritesChange: (List<String>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("favorites_tool_view"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "FAVORITE LIFTS",
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFFFF1744),
            letterSpacing = 1.sp
        )

        if (favoritesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No favorites added yet. Tap form cards to bookmark.",
                    fontFamily = InterFontFamily,
                    color = onSurfaceSecondaryColor,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(favoritesList) { title ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(surfaceColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = title,
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = onSurfaceColor
                            )
                        }

                        IconButton(
                            onClick = { onFavoritesChange(favoritesList.filter { it != title }) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Remove Favorite",
                                tint = Color(0xFFFF1744),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// UTILITY VIEW 2: NOTES SUB-PANE
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesToolView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color,
    notesList: List<String>,
    onNotesChange: (List<String>) -> Unit
) {
    var noteInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("notes_tool_view"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "TRAINING REMINDERS",
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = accentColor,
            letterSpacing = 1.sp
        )

        // Add Note Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                placeholder = {
                    Text(
                        "Add a training cue or cue-note...",
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        color = onSurfaceSecondaryColor
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor,
                    focusedTextColor = onSurfaceColor,
                    unfocusedTextColor = onSurfaceColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (noteInput.trim().isNotEmpty()) {
                        onNotesChange(listOf(noteInput.trim()) + notesList)
                        noteInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(52.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(notesList) { noteText ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(surfaceColor)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = noteText,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        color = onSurfaceColor,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick = { onNotesChange(notesList.filter { it != noteText }) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete cue",
                            tint = onSurfaceSecondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// UTILITY VIEW 3: ACTIVE STOPWATCH / REST TIMER
// =========================================================================
@Composable
fun StopwatchTimerView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color
) {
    var timeInSeconds by remember { mutableStateOf(90) } // Default rest interval: 90 seconds
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (timeInSeconds > 0) {
                delay(1000L)
                timeInSeconds--
            }
            isRunning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("stopwatch_timer_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SET REST INTERVAL",
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = accentColor,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.weight(0.5f))

        // Large Premium Digital Clock Container
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(surfaceColor)
                .border(BorderStroke(2.dp, if (isRunning) accentColor else onSurfaceSecondaryColor.copy(alpha = 0.3f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val mins = timeInSeconds / 60
                val secs = timeInSeconds % 60
                val timeStr = String.format("%02d:%02d", mins, secs)

                Text(
                    text = timeStr,
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 44.sp,
                    color = onSurfaceColor
                )

                Text(
                    text = if (isRunning) "RESTING..." else "RECOVERY",
                    fontFamily = InterFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) accentColor else onSurfaceSecondaryColor,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Preset Quick Interval Selectors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(45, 90, 120, 180).forEach { seconds ->
                val isSel = timeInSeconds == seconds
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) accentColor.copy(alpha = 0.2f) else surfaceColor)
                        .border(BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent), RoundedCornerShape(8.dp))
                        .clickable {
                            isRunning = false
                            timeInSeconds = seconds
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${seconds}s",
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (isSel) accentColor else onSurfaceColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Action controllers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Reset Button
            Button(
                onClick = {
                    isRunning = false
                    timeInSeconds = 90
                },
                colors = ButtonDefaults.buttonColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text("RESET", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = onSurfaceColor)
            }

            // Start/Pause Button
            Button(
                onClick = { isRunning = !isRunning },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color(0xFFFF1744) else accentColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(52.dp)
            ) {
                Text(
                    text = if (isRunning) "PAUSE TIMER" else "START TIMER",
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// =========================================================================
// UTILITY VIEW 4: BMR / CALORIE CALCULATOR
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BmrCalorieToolView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color
) {
    var gender by remember { mutableStateOf("Male") }
    var ageStr by remember { mutableStateOf("") }
    var heightStr by remember { mutableStateOf("") }
    var weightStr by remember { mutableStateOf("") }
    var targetBmr by remember { mutableStateOf<Int?>(null) }
    var targetTdee by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("bmr_calorie_tool"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "BMR & ENERGY ESTIMATOR",
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = accentColor,
            letterSpacing = 1.sp
        )

        // Inputs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Male", "Female").forEach { currentGender ->
                val isSelected = gender == currentGender
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) accentColor else surfaceColor)
                        .clickable { gender = currentGender }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentGender,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else onSurfaceColor
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = ageStr,
                onValueChange = {
                    ageStr = it.filter { c -> c.isDigit() }
                    errorMessage = null
                },
                label = { Text("Age (yrs)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedContainerColor = surfaceColor,
                    focusedContainerColor = surfaceColor
                )
            )

            OutlinedTextField(
                value = heightStr,
                onValueChange = {
                    heightStr = it.filter { c -> c.isDigit() }
                    errorMessage = null
                },
                label = { Text("Height (cm)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedContainerColor = surfaceColor,
                    focusedContainerColor = surfaceColor
                )
            )

            OutlinedTextField(
                value = weightStr,
                onValueChange = {
                    weightStr = it.filter { c -> c.isDigit() }
                    errorMessage = null
                },
                label = { Text("Weight (kg)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedContainerColor = surfaceColor,
                    focusedContainerColor = surfaceColor
                )
            )
        }

        Button(
            onClick = {
                val age = ageStr.toIntOrNull() ?: 0
                val height = heightStr.toDoubleOrNull() ?: 0.0
                val weight = weightStr.toDoubleOrNull() ?: 0.0
                val base = CalorieSex.from(gender)?.let { sex ->
                    CalorieEngine.estimateBmr(age, sex, height, weight)
                }
                errorMessage = if (base == null) {
                    "Enter a valid age, height, and weight."
                } else {
                    null
                }
                if (errorMessage == null) {
                    targetBmr = base!!.roundToInt()
                    targetTdee = (base * LifestyleActivityLevel.MODERATE.multiplier).roundToInt()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("CALCULATE EXPENDITURE", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
        }

        CalculatorError(errorMessage)

        targetBmr?.let { bmr ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(surfaceColor)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ESTIMATED METABOLIC RESULTS",
                    fontFamily = OswaldFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceSecondaryColor,
                    letterSpacing = 0.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("BMR", fontFamily = InterFontFamily, fontSize = 11.sp, color = onSurfaceSecondaryColor)
                        Text("$bmr kcal", fontFamily = OswaldFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accentColor)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TDEE (Moderate)", fontFamily = InterFontFamily, fontSize = 11.sp, color = onSurfaceSecondaryColor)
                        Text("${targetTdee} kcal", fontFamily = OswaldFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accentColor)
                    }
                }
            }
        }
    }
}

// =========================================================================
// UTILITY VIEW 5: MACRO CALCULATOR SUB-PANE
// =========================================================================
@Composable
fun MacroToolView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color,
    onFindDailyNeeds: () -> Unit,
    onFinish: (MacroCalculation) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var selectedRatioId by remember { mutableStateOf<String?>(null) }
    var customCarbs by remember { mutableStateOf("") }
    var customProtein by remember { mutableStateOf("") }
    var customFat by remember { mutableStateOf("") }
    var caloriesInput by remember { mutableStateOf("") }
    var mealsInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var calculation by remember { mutableStateOf<MacroCalculation?>(null) }
    val scrollState = rememberScrollState()

    fun selectedRatio(): MacroRatio? {
        return if (selectedRatioId == "custom") {
            MacroRatio(
                id = "custom",
                name = "Custom",
                carbPercent = customCarbs.toIntOrNull() ?: -1,
                proteinPercent = customProtein.toIntOrNull() ?: -1,
                fatPercent = customFat.toIntOrNull() ?: -1
            ).takeIf { it.isValid }
        } else {
            standardMacroRatios.firstOrNull { it.id == selectedRatioId }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState)
            .testTag("macro_tool_view"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CalculatorStepHeader(
            currentStep = step,
            title = when (step) {
                1 -> "SELECT MACRO RATIO"
                2 -> "ENTER DAILY DETAILS"
                else -> "YOUR MACRO TARGETS"
            },
            accentColor = accentColor,
            onSurfaceSecondaryColor = onSurfaceSecondaryColor
        )

        when (step) {
            1 -> {
                Text(
                    text = "Choose the percentage of calories assigned to carbs, protein, and fat.",
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    color = onSurfaceSecondaryColor
                )

                standardMacroRatios.forEach { ratio ->
                    MacroRatioOption(
                        ratio = ratio,
                        selected = selectedRatioId == ratio.id,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                        accentColor = accentColor,
                        onClick = {
                            selectedRatioId = ratio.id
                            errorMessage = null
                        }
                    )
                }

                MacroRatioOption(
                    ratio = MacroRatio("custom", "Custom ratio", 0, 0, 100),
                    selected = selectedRatioId == "custom",
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                    accentColor = accentColor,
                    summaryOverride = "Enter your own split",
                    onClick = {
                        selectedRatioId = "custom"
                        errorMessage = null
                    }
                )

                if (selectedRatioId == "custom") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MacroPercentField("Carbs %", customCarbs, { customCarbs = it }, accentColor, surfaceColor, Modifier.weight(1f))
                        MacroPercentField("Protein %", customProtein, { customProtein = it }, accentColor, surfaceColor, Modifier.weight(1f))
                        MacroPercentField("Fat %", customFat, { customFat = it }, accentColor, surfaceColor, Modifier.weight(1f))
                    }
                    Text(
                        text = "Total: ${(customCarbs.toIntOrNull() ?: 0) + (customProtein.toIntOrNull() ?: 0) + (customFat.toIntOrNull() ?: 0)}%",
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        color = onSurfaceSecondaryColor
                    )
                }

                CalculatorError(errorMessage)
                Button(
                    onClick = {
                        if (selectedRatio() == null) {
                            errorMessage = if (selectedRatioId == "custom") {
                                "Custom percentages must be positive and total 100%."
                            } else {
                                "Select a macro ratio to continue."
                            }
                        } else {
                            errorMessage = null
                            step = 2
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("macro_continue_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("CONTINUE", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            2 -> {
                val ratio = selectedRatio()
                Text(
                    text = ratio?.let { "${it.summary} ${it.name}" } ?: "Ratio not selected",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = accentColor
                )

                OutlinedTextField(
                    value = caloriesInput,
                    onValueChange = {
                        caloriesInput = it.filter(Char::isDigit)
                        errorMessage = null
                    },
                    label = { Text("Daily calories") },
                    placeholder = { Text("Enter your calorie target") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("macro_calories_input"),
                    colors = calculatorFieldColors(accentColor, surfaceColor)
                )

                OutlinedTextField(
                    value = mealsInput,
                    onValueChange = {
                        mealsInput = it.filter(Char::isDigit)
                        errorMessage = null
                    },
                    label = { Text("Meals per day") },
                    placeholder = { Text("At least 1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("macro_meals_input"),
                    colors = calculatorFieldColors(accentColor, surfaceColor)
                )

                TextButton(onClick = onFindDailyNeeds, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Calculate, contentDescription = null, tint = accentColor)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Find my daily needs", color = accentColor, fontWeight = FontWeight.Bold)
                }

                CalculatorError(errorMessage)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            errorMessage = null
                            step = 1
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("BACK")
                    }
                    Button(
                        onClick = {
                            val calories = caloriesInput.toIntOrNull() ?: 0
                            val meals = mealsInput.toIntOrNull() ?: 0
                            val activeRatio = selectedRatio()
                            errorMessage = when {
                                activeRatio == null -> "Select a valid macro ratio."
                                calories <= 0 -> "Daily calories must be greater than zero."
                                meals < 1 -> "Meals per day must be at least 1."
                                else -> null
                            }
                            if (errorMessage == null && activeRatio != null) {
                                calculation = calculateMacros(calories, meals, activeRatio)
                                step = 3
                            }
                        },
                        modifier = Modifier.weight(1.4f).height(52.dp).testTag("macro_calculate_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("CALCULATE", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            else -> calculation?.let { result ->
                MacroResultsCard(
                    result = result,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                    accentColor = accentColor
                )

                Text(
                    text = "Finish saves these values as your local calorie and nutrition targets.",
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = onSurfaceSecondaryColor
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            calculation = null
                            step = 2
                        },
                        modifier = Modifier.weight(1f).height(52.dp).testTag("macro_recalculate_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("RECALCULATE")
                    }
                    Button(
                        onClick = { onFinish(result) },
                        modifier = Modifier.weight(1f).height(52.dp).testTag("macro_finish_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("FINISH", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun CalculatorStepHeader(
    currentStep: Int,
    title: String,
    accentColor: Color,
    onSurfaceSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (index < currentStep) accentColor else onSurfaceSecondaryColor.copy(alpha = 0.2f))
                )
            }
        }
        Text(
            text = "STEP $currentStep OF 3",
            fontFamily = InterFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceSecondaryColor,
            letterSpacing = 1.sp
        )
        Text(
            text = title,
            fontFamily = OswaldFontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
            color = accentColor,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun MacroRatioOption(
    ratio: MacroRatio,
    selected: Boolean,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color,
    summaryOverride: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accentColor else onSurfaceSecondaryColor.copy(alpha = 0.18f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summaryOverride ?: ratio.summary,
                fontFamily = OswaldFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (selected) accentColor else onSurfaceColor
            )
            Text(
                text = ratio.name,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = onSurfaceSecondaryColor
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
        )
    }
}

@Composable
private fun MacroPercentField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    surfaceColor: Color,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = calculatorFieldColors(accentColor, surfaceColor)
    )
}

@Composable
private fun MacroResultsCard(
    result: MacroCalculation,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("macro_results_card"),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "${result.ratio.summary} ${result.ratio.name}",
                fontFamily = OswaldFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor
            )
            Text(
                text = "${result.calories} kcal across ${result.mealsPerDay} meals",
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = onSurfaceSecondaryColor
            )

            Text("PER MEAL", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = onSurfaceSecondaryColor, letterSpacing = 1.sp)
            MacroMetricRow(
                carbs = result.carbsPerMealGrams,
                protein = result.proteinPerMealGrams,
                fat = result.fatPerMealGrams,
                onSurfaceColor = onSurfaceColor,
                onSurfaceSecondaryColor = onSurfaceSecondaryColor
            )

            HorizontalDivider(color = onSurfaceSecondaryColor.copy(alpha = 0.15f))
            Text("DAILY TOTAL", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = onSurfaceSecondaryColor, letterSpacing = 1.sp)
            MacroMetricRow(
                carbs = result.dailyCarbsGrams,
                protein = result.dailyProteinGrams,
                fat = result.dailyFatGrams,
                onSurfaceColor = onSurfaceColor,
                onSurfaceSecondaryColor = onSurfaceSecondaryColor
            )

            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accentColor.copy(alpha = 0.08f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("TIPS FOR SUCCESS", fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = accentColor)
                listOf(
                    "Build meals around minimally processed foods and a consistent protein source.",
                    "Use these numbers as planning targets, not medical advice or strict guarantees.",
                    "Review energy, recovery, and progress before making gradual adjustments."
                ).forEach { tip ->
                    Text("• $tip", fontFamily = InterFontFamily, fontSize = 11.sp, color = onSurfaceSecondaryColor, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun MacroMetricRow(
    carbs: Double,
    protein: Double,
    fat: Double,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacroMetric("CARBS", carbs, Color(0xFF29B6F6), onSurfaceColor, onSurfaceSecondaryColor, Modifier.weight(1f))
        MacroMetric("PROTEIN", protein, Color(0xFFFF7043), onSurfaceColor, onSurfaceSecondaryColor, Modifier.weight(1f))
        MacroMetric("FAT", fat, Color(0xFFFFCA28), onSurfaceColor, onSurfaceSecondaryColor, Modifier.weight(1f))
    }
}

@Composable
private fun MacroMetric(
    label: String,
    value: Double,
    color: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.1f)).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontFamily = InterFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = onSurfaceSecondaryColor)
        Text("${formatCalculatorNumber(value)}g", fontFamily = OswaldFontFamily, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = onSurfaceColor)
    }
}

@Composable
private fun CalculatorError(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun calculatorFieldColors(accentColor: Color, surfaceColor: Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accentColor,
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        focusedContainerColor = surfaceColor,
        unfocusedContainerColor = surfaceColor,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
    )

private fun formatCalculatorNumber(value: Double): String =
    java.text.DecimalFormat("#,##0.#").format(value)

@Composable
fun MacroBarItem(
    label: String,
    percentage: Int,
    color: Color,
    valueGrams: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label ($percentage%)",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.Gray
            )

            Text(
                text = valueGrams,
                fontFamily = OswaldFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = color
            )
        }

        // Percentage slider line representation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percentage / 100f)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// =========================================================================
// UTILITY VIEW 6: ONE REP MAX CALCULATOR SUB-PANE
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneRepMaxToolView(
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color
) {
    var selectedUnit by remember { mutableStateOf(WeightUnit.KG) }
    var weightStr by remember { mutableStateOf("") }
    var repsStr by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var calculation by remember { mutableStateOf<OneRepMaxCalculation?>(null) }
    val scrollState = rememberScrollState()

    fun changeUnit(newUnit: WeightUnit) {
        if (newUnit == selectedUnit) return
        weightStr.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { currentWeight ->
            weightStr = formatCalculatorNumber(convertWeight(currentWeight, selectedUnit, newUnit))
        }
        selectedUnit = newUnit
        calculation = null
        errorMessage = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState)
            .testTag("one_rep_max_tool"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (calculation == null) {
            Text(
                text = "LOCAL EPLEY ESTIMATE",
                fontFamily = OswaldFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = accentColor,
                letterSpacing = 1.sp
            )
            Text(
                text = "Enter a completed lift. This estimate is for training guidance and is not saved as progress.",
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = onSurfaceSecondaryColor,
                lineHeight = 17.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(surfaceColor).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WeightUnit.entries.forEach { unit ->
                    val selected = selectedUnit == unit
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) accentColor else Color.Transparent)
                            .clickable { changeUnit(unit) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = unit.label,
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else onSurfaceColor
                        )
                    }
                }
            }

            OutlinedTextField(
                value = weightStr,
                onValueChange = {
                    weightStr = it.filter { char -> char.isDigit() || char == '.' }
                    errorMessage = null
                },
                label = { Text("Weight lifted (${selectedUnit.label})") },
                placeholder = { Text("Enter completed lift") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("one_rep_max_weight_input"),
                colors = calculatorFieldColors(accentColor, surfaceColor)
            )

            OutlinedTextField(
                value = repsStr,
                onValueChange = {
                    repsStr = it.filter(Char::isDigit)
                    errorMessage = null
                },
                label = { Text("Number of reps") },
                placeholder = { Text("At least 1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("one_rep_max_reps_input"),
                colors = calculatorFieldColors(accentColor, surfaceColor)
            )

            CalculatorError(errorMessage)
            Button(
                onClick = {
                    val weight = weightStr.toDoubleOrNull() ?: 0.0
                    val reps = repsStr.toIntOrNull() ?: 0
                    errorMessage = when {
                        !weight.isFinite() || weight <= 0.0 -> "Weight lifted must be greater than zero."
                        reps < 1 -> "Number of reps must be at least 1."
                        else -> null
                    }
                    if (errorMessage == null) {
                        calculation = runCatching {
                            calculateOneRepMax(weight, reps, selectedUnit)
                        }.getOrElse {
                            errorMessage = "Enter a weight and rep count within a reasonable range."
                            null
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("one_rep_max_calculate_button")
            ) {
                Text("CALCULATE 1RM", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
            }
        } else {
            OneRepMaxResultsCard(
                result = calculation!!,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                accentColor = accentColor
            )
            OutlinedButton(
                onClick = { calculation = null },
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("one_rep_max_recalculate_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("EDIT & RECALCULATE", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun OneRepMaxResultsCard(
    result: OneRepMaxCalculation,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("one_rep_max_results"),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ESTIMATED 1RM", fontFamily = InterFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = onSurfaceSecondaryColor, letterSpacing = 1.2.sp)
            Text(
                text = "${result.estimatedOneRepMax.roundToInt()} ${result.unit.label}",
                fontFamily = OswaldFontFamily,
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor
            )
            Text(
                text = "Epley estimate from ${result.reps} completed rep${if (result.reps == 1) "" else "s"}",
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                color = onSurfaceSecondaryColor
            )

            if (result.isHighRepEstimate) {
                Text(
                    text = "Estimate is less accurate for high reps.",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)).padding(10.dp),
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(color = onSurfaceSecondaryColor.copy(alpha = 0.15f))
            Text("TRAINING PERCENTAGES", modifier = Modifier.fillMaxWidth(), fontFamily = OswaldFontFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)

            result.trainingLoads.chunked(2).forEach { rowLoads ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowLoads.forEach { load ->
                        TrainingLoadCard(
                            percent = load.percent,
                            focus = load.focus,
                            weight = load.weight,
                            unit = result.unit,
                            accentColor = accentColor,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceSecondaryColor = onSurfaceSecondaryColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainingLoadCard(
    percent: Int,
    focus: String,
    weight: Double,
    unit: WeightUnit,
    accentColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$percent%", fontFamily = OswaldFontFamily, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
        Text(focus.uppercase(), fontFamily = InterFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = onSurfaceSecondaryColor)
        Text("${formatCalculatorNumber(weight)} ${unit.label}", fontFamily = InterFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
    }
}

// --- SCREEN PREVIEWS ---
@Preview(name = "Tools Dark 360", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 800)
@Composable
fun ToolsScreenDarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") {
        ToolsScreen()
    }
}

@Preview(name = "Tools Light 320", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 320, heightDp = 800)
@Composable
fun ToolsScreenLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") {
        ToolsScreen()
    }
}
