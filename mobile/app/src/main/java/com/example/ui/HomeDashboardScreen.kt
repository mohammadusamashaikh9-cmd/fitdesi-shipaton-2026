package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.data.WorkoutLog
import com.example.ui.components.FitDesiBrandLockup
import com.example.ui.components.FitDesiPrimaryButton
import com.example.ui.components.FitDesiSecondaryButton
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiInk
import com.example.ui.theme.FitDesiMotion
import com.example.ui.theme.FitDesiOrange
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.FitDesiTextDark
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.fitDesiColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HOME_CARD_STAGGER_MS = 50
private const val HOME_ENTRANCE_MS = FitDesiMotion.fast + HOME_CARD_STAGGER_MS
private const val HYDRATION_PROGRESS_MS = 400
private const val HYDRATION_COUNT_MS = 180

@Composable
fun HomeScreen(
    profileName: String,
    todayConsumed: Int,
    dailyGoal: Int,
    todayProtein: Float,
    todayCarbs: Float,
    todayFat: Float,
    proteinGoal: Float,
    carbsGoal: Float,
    fatGoal: Float,
    showMacroTargetProgress: Boolean,
    workoutLogs: List<WorkoutLog>,
    activePlanJson: String,
    waterGlasses: Int,
    onWaterIntakeChange: (Int) -> Unit,
    onNavigateToCalories: () -> Unit,
    onSetMacroTargets: () -> Unit,
    onNavigateToExercises: () -> Unit,
    onOpenAiCoach: () -> Unit,
    showBasicPlusEntry: Boolean,
    onExplorePlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val todayWorkouts = workoutLogs.filter { log ->
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(log.timestamp)) == todayKey
    }
    val workoutPlanPresentation = remember(activePlanJson, todayWorkouts) {
        homeWorkoutPlanPresentation(activePlanJson, todayWorkouts)
    }
    val workoutFacts = workoutPlanPresentation.workoutFacts
    val inspectionMode = LocalInspectionMode.current
    var hasEntered by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasEntered = true
    }
    val sectionsVisible = inspectionMode || hasEntered

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(
            start = FitDesiDimensions.compactContentPadding,
            top = FitDesiSpacing.small,
            end = FitDesiDimensions.compactContentPadding,
            bottom = 144.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
    ) {
        item(key = "home_header") {
            HomeEntrance(visible = sectionsVisible, delayMillis = 0) {
                HomeCompactHeader(profileName = profileName)
            }
        }

        if (showBasicPlusEntry) {
            item(key = "basic_plus_entry") {
                HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS) {
                    HomeBasicPlusEntry(onExplorePlus = onExplorePlus)
                }
            }
        }

        item(key = "metric_deck") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS) {
                HomeMetricDeck(
                    workoutFacts = workoutFacts,
                )
            }
        }

        item(key = "current_workout_plan") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 2) {
                HomeCurrentWorkoutPlan(
                    presentation = workoutPlanPresentation,
                    onOpenWorkout = onNavigateToExercises,
                )
            }
        }

        item(key = "nutrition") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 3) {
                HomeNutritionOverview(
                    todayConsumed = todayConsumed,
                    dailyGoal = dailyGoal,
                    todayProtein = todayProtein,
                    todayCarbs = todayCarbs,
                    todayFat = todayFat,
                    proteinGoal = proteinGoal,
                    carbsGoal = carbsGoal,
                    fatGoal = fatGoal,
                    showMacroTargetProgress = showMacroTargetProgress,
                    onSetMacroTargets = onSetMacroTargets,
                )
            }
        }

        item(key = "hydration") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 4) {
                HomeHydrationCard(
                    waterGlasses = waterGlasses,
                    onWaterIntakeChange = onWaterIntakeChange,
                )
            }
        }

        item(key = "workout_activity") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 5) {
                HomeWorkoutAndActivity(
                    todayWorkouts = todayWorkouts,
                    workoutFacts = workoutFacts,
                    onTrackWorkout = onNavigateToExercises,
                )
            }
        }

        item(key = "ai_coach") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 6) {
                HomeAiCoachCard(onOpenAiCoach = onOpenAiCoach)
            }
        }

        item(key = "quick_actions") {
            HomeEntrance(visible = sectionsVisible, delayMillis = HOME_CARD_STAGGER_MS * 7) {
                HomeQuickActions(
                    onNavigateToCalories = onNavigateToCalories,
                    onNavigateToExercises = onNavigateToExercises,
                )
            }
        }
    }
}

@Composable
internal fun HomeBasicPlusEntry(
    onExplorePlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onExplorePlus,
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_basic_plus_entry"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(
            FitDesiDimensions.cardBorderWidth,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = FitDesiSpacing.medium,
                vertical = FitDesiSpacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro),
            ) {
                Text(
                    text = "FitDesi Basic",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                Text(
                    text = "Explore FitDesi Plus",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HomeEntrance(
    visible: Boolean,
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = HOME_ENTRANCE_MS,
                delayMillis = delayMillis,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = HOME_ENTRANCE_MS,
                delayMillis = delayMillis,
            ),
            initialOffsetY = { fullHeight -> fullHeight.coerceAtMost(24) },
        ),
        exit = ExitTransition.None,
    ) {
        content()
    }
}

@Composable
private fun HomeCompactHeader(profileName: String) {
    val cleanName = profileName.trim()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = FitDesiInk,
        contentColor = FitDesiTextDark,
        border = BorderStroke(
            width = FitDesiDimensions.cardBorderWidth,
            color = FitDesiOrange.copy(alpha = 0.42f),
        ),
    ) {
        Box {
            HomeGeometricPattern(Modifier.fillMaxSize())
            Column(
                modifier = Modifier.padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            ) {
                FitDesiBrandLockup(
                    markWidth = 34.dp,
                    contentColor = FitDesiTextDark,
                )
                Text(
                    text = if (cleanName.isBlank()) "Welcome to FitDesi" else "Hello, $cleanName",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = FitDesiTextDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (cleanName.isBlank()) {
                        "Complete your profile to personalize today’s dashboard."
                    } else {
                        "Your nutrition, hydration, and training — grounded in your real logs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FitDesiTextDark.copy(alpha = 0.76f),
                )
            }
        }
    }
}

@Composable
private fun HomeGeometricPattern(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val interval = size.width / 3f
        repeat(5) { index ->
            val startX = (index - 1) * interval
            drawLine(
                color = FitDesiOrange.copy(alpha = 0.1f),
                start = Offset(startX, 0f),
                end = Offset(startX + size.height, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        drawCircle(
            color = FitDesiOrange.copy(alpha = 0.07f),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.92f, size.height * 0.08f),
        )
    }
}

@Composable
private fun HomeMetricDeck(
    workoutFacts: HomeWorkoutFacts,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "TODAY", title = "At a glance")
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stack = maxWidth < 270.dp || LocalDensity.current.fontScale >= 1.3f
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeMetricTile("Sessions", "${workoutFacts.sessionCount}", "logged", Icons.Default.CheckCircle, FitDesiOrange)
                    HomeMetricTile("Duration", formatActivityDuration(workoutFacts.durationSeconds), "tracked", Icons.Default.Timer, MaterialTheme.fitDesiColors.success)
                    HomeMetricTile("Sets", "${workoutFacts.completedSets}", "completed", Icons.Default.FitnessCenter, MaterialTheme.fitDesiColors.info)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeMetricTile(
                        label = "Sessions",
                        value = "${workoutFacts.sessionCount}",
                        unit = "logged",
                        icon = Icons.Default.CheckCircle,
                        accent = FitDesiOrange,
                        modifier = Modifier.weight(1f),
                    )
                    HomeMetricTile(
                        label = "Duration",
                        value = formatActivityDuration(workoutFacts.durationSeconds),
                        unit = "tracked",
                        icon = Icons.Default.Timer,
                        accent = MaterialTheme.fitDesiColors.success,
                        modifier = Modifier.weight(1f),
                    )
                    HomeMetricTile(
                        label = "Sets",
                        value = "${workoutFacts.completedSets}",
                        unit = "completed",
                        icon = Icons.Default.FitnessCenter,
                        accent = MaterialTheme.fitDesiColors.info,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCurrentWorkoutPlan(
    presentation: HomeWorkoutPlanPresentation,
    onOpenWorkout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "WORKOUT", title = "What should I do now?")
        HomeSectionSurface {
            Column(
                modifier = Modifier.padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            ) {
                when (presentation) {
                    is HomeWorkoutPlanPresentation.NoActivePlan -> {
                        Text(
                            text = "No current workout plan selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Open Workout to choose an existing routine or build one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is HomeWorkoutPlanPresentation.ActivePlan -> {
                        Text(
                            text = presentation.planName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val planDetails = buildList {
                            if (presentation.frequency.isNotBlank()) add(presentation.frequency)
                            if (presentation.dayCount > 0) {
                                add("${presentation.dayCount} ${if (presentation.dayCount == 1) "plan day" else "plan days"}")
                            }
                        }.joinToString(" • ")
                        Text(
                            text = planDetails.ifBlank { "Current plan details are available in Workout." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (presentation.workoutFacts.sessionCount > 0) {
                    Text(
                        text = "Today: ${presentation.workoutFacts.sessionCount} ${if (presentation.workoutFacts.sessionCount == 1) "session" else "sessions"} logged • " +
                            "${formatActivityDuration(presentation.workoutFacts.durationSeconds)} • " +
                            "${presentation.workoutFacts.completedSets} completed sets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                FitDesiPrimaryButton(
                    text = if (presentation is HomeWorkoutPlanPresentation.ActivePlan) "View current plan" else "Open Workout",
                    onClick = onOpenWorkout,
                    modifier = Modifier.fillMaxWidth().testTag("home_current_plan_action"),
                    leadingIcon = {
                        Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeMetricTile(
    label: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 92.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (LocalDensity.current.fontScale > 1f) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(FitDesiSpacing.micro))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeNutritionOverview(
    todayConsumed: Int,
    dailyGoal: Int,
    todayProtein: Float,
    todayCarbs: Float,
    todayFat: Float,
    proteinGoal: Float,
    carbsGoal: Float,
    fatGoal: Float,
    showMacroTargetProgress: Boolean,
    onSetMacroTargets: () -> Unit,
) {
    val presentation = nutritionProgressPresentation(
        consumedCalories = todayConsumed,
        calorieTarget = dailyGoal,
        consumedProteinGrams = todayProtein,
        consumedCarbsGrams = todayCarbs,
        consumedFatGrams = todayFat,
        proteinTargetGrams = proteinGoal,
        carbsTargetGrams = carbsGoal,
        fatTargetGrams = fatGoal,
        showMacroTargetProgress = showMacroTargetProgress
    )
    val animatedCalorieProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = presentation.calories.progress ?: 0f,
        animationSpec = tween(FitDesiMotion.emphasis),
        label = "home_calorie_progress",
    )
    val allMacroTargetsAvailable = listOf(
        presentation.protein,
        presentation.carbs,
        presentation.fat
    ).all { it.targetGrams != null }

    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "NUTRITION", title = "Calories & macros")
        HomeSectionSurface {
            Column(
                modifier = Modifier.padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val stack = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
                    if (stack) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                        ) {
                            HomeCalorieRing(todayConsumed, dailyGoal, animatedCalorieProgress)
                            HomeCalorieMessage(todayConsumed, dailyGoal)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
                        ) {
                            HomeCalorieRing(todayConsumed, dailyGoal, animatedCalorieProgress)
                            Box(modifier = Modifier.weight(1f)) {
                                HomeCalorieMessage(todayConsumed, dailyGoal)
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.fitDesiColors.border)

                HomeMacroDeck(
                    presentation = presentation,
                )

                if (allMacroTargetsAvailable) {
                    Text(
                        text = "Progress uses your saved or profile-calculated targets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                        Text(
                            text = if (presentation.canShowMacroTargets) {
                                "Your consumed grams are shown. Add targets to unlock percentage progress."
                            } else {
                                "Consumed grams are shown. Macro targets and progress are available with Plus."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FitDesiSecondaryButton(
                            text = "Set macro targets",
                            onClick = onSetMacroTargets,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("set_macro_targets"),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCalorieRing(
    todayConsumed: Int,
    dailyGoal: Int,
    progress: Float,
) {
    Box(
        modifier = Modifier.size(128.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            color = FitDesiOrange,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 10.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = todayConsumed.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (dailyGoal > 0) "of $dailyGoal kcal" else "kcal consumed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeCalorieMessage(todayConsumed: Int, dailyGoal: Int) {
    val message = when {
        dailyGoal <= 0 -> "Set a calorie goal in Profile to see daily progress."
        todayConsumed <= dailyGoal -> "${dailyGoal - todayConsumed} kcal remaining today"
        else -> "${todayConsumed - dailyGoal} kcal above today’s goal"
    }
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
        Text(
            text = "Daily energy",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeMacroDeck(
    presentation: NutritionProgressPresentation,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stack = maxWidth < 270.dp || LocalDensity.current.fontScale >= 1.3f
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                HomeMacroCard("Protein", presentation.protein, presentation.canShowMacroTargets, FitDesiOrange, Modifier.fillMaxWidth())
                HomeMacroCard("Carbs", presentation.carbs, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.info, Modifier.fillMaxWidth())
                HomeMacroCard("Fat", presentation.fat, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.warning, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                HomeMacroCard("Protein", presentation.protein, presentation.canShowMacroTargets, FitDesiOrange, Modifier.weight(1f))
                HomeMacroCard("Carbs", presentation.carbs, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.info, Modifier.weight(1f))
                HomeMacroCard("Fat", presentation.fat, presentation.canShowMacroTargets, MaterialTheme.fitDesiColors.warning, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeMacroCard(
    label: String,
    presentation: MacroProgressPresentation,
    canShowMacroTargets: Boolean,
    accent: Color,
    modifier: Modifier,
) {
    val targetAvailable = presentation.targetGrams != null
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = presentation.progress ?: 0f,
        animationSpec = tween(FitDesiMotion.emphasis),
        label = "${label.lowercase()}_progress",
    )
    Surface(
        modifier = modifier.heightIn(min = 112.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "${presentation.consumedGrams.roundToInt()} g",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (targetAvailable) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                    Text(
                        text = "${presentation.targetGrams?.roundToInt()} g target",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = if (canShowMacroTargets) "Target not set" else "Consumed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeHydrationCard(
    waterGlasses: Int,
    onWaterIntakeChange: (Int) -> Unit,
) {
    val quickTrackerReference = 8
    val waterProgress = waterGlasses.toFloat() / quickTrackerReference
    val animatedProgress by animateFloatAsState(
        targetValue = waterProgress.coerceIn(0f, 1f),
        animationSpec = tween(HYDRATION_PROGRESS_MS),
        label = "home_hydration_progress",
    )
    val referenceReached = waterGlasses >= quickTrackerReference

    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "HYDRATION", title = "Water check-in")
        HomeSectionSurface(modifier = Modifier.testTag("hydration_tracker_card")) {
            Column {
                HomeHydrationSummary(
                    waterGlasses = waterGlasses,
                    modifier = Modifier.padding(FitDesiSpacing.medium),
                )
                HomeHydrationInteractionArea(
                    waterGlasses = waterGlasses,
                    quickTrackerReference = quickTrackerReference,
                    animatedProgress = animatedProgress,
                    referenceReached = referenceReached,
                    onWaterIntakeChange = onWaterIntakeChange,
                )
            }
        }
    }
}

@Composable
private fun HomeHydrationSummary(
    waterGlasses: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.fitDesiColors.info.copy(alpha = 0.16f),
            contentColor = MaterialTheme.fitDesiColors.info,
        ) {
            Icon(
                imageVector = Icons.Default.WaterDrop,
                contentDescription = null,
                modifier = Modifier.padding(FitDesiSpacing.small).size(24.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro),
        ) {
            Text(
                text = "TODAY'S HYDRATION",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.fitDesiColors.info,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
            ) {
                AnimatedContent(
                    targetState = waterGlasses,
                    transitionSpec = {
                        fadeIn(tween(HYDRATION_COUNT_MS)) togetherWith
                            fadeOut(tween(HYDRATION_COUNT_MS))
                    },
                    label = "hydration_glass_count",
                ) { count ->
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "glasses today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = FitDesiSpacing.extraSmall),
                )
            }
        }
    }
}

@Composable
private fun HomeHydrationInteractionArea(
    waterGlasses: Int,
    quickTrackerReference: Int,
    animatedProgress: Float,
    referenceReached: Boolean,
    onWaterIntakeChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 32.dp,
            topEnd = 12.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp,
        ),
        color = MaterialTheme.fitDesiColors.info.copy(alpha = 0.24f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
        ) {
            Text(
                text = "$quickTrackerReference-glass quick tracker",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FitDesiDimensions.progressHeight),
                color = MaterialTheme.fitDesiColors.info,
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            )

            Crossfade(
                targetState = referenceReached,
                animationSpec = tween(HOME_ENTRANCE_MS),
                label = "hydration_tracker_completion",
            ) { reached ->
                Text(
                    text = if (reached) {
                        "Quick tracker complete · $waterGlasses glasses logged today"
                    } else {
                        "${(quickTrackerReference - waterGlasses).coerceAtLeast(0)} remaining on the quick tracker"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HomeHydrationSegments(
                waterGlasses = waterGlasses,
                quickTrackerReference = quickTrackerReference,
                onWaterIntakeChange = onWaterIntakeChange,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackControls = maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.3f
                if (stackControls) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = "Tap a marker or adjust one glass at a time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Start),
                        )
                        HomeHydrationControls(waterGlasses, onWaterIntakeChange)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Tap a marker or adjust one glass at a time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(FitDesiSpacing.small))
                        HomeHydrationControls(waterGlasses, onWaterIntakeChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHydrationSegments(
    waterGlasses: Int,
    quickTrackerReference: Int,
    onWaterIntakeChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
        (1..quickTrackerReference).chunked(4).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { glass ->
                    HomeWaterTarget(
                        glass = glass,
                        selected = waterGlasses >= glass,
                        onClick = {
                            val newValue = if (waterGlasses == glass) glass - 1 else glass
                            onWaterIntakeChange(newValue)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHydrationControls(
    waterGlasses: Int,
    onWaterIntakeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
    ) {
        FilledTonalIconButton(
            onClick = { onWaterIntakeChange(waterGlasses - 1) },
            enabled = waterGlasses > 0,
            modifier = Modifier
                .size(FitDesiDimensions.minimumTouchTarget)
                .testTag("water_minus_button"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.fitDesiColors.info,
            ),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Remove one glass")
        }
        HomeHydrationPlusButton(
            enabled = waterGlasses < 24,
            onClick = { onWaterIntakeChange(waterGlasses + 1) },
        )
    }
}

@Composable
private fun HomeHydrationPlusButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(FitDesiMotion.fast),
        label = "hydration_plus_scale",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            !enabled -> 0.dp
            pressed -> 2.dp
            else -> 8.dp
        },
        animationSpec = tween(FitDesiMotion.fast),
        label = "hydration_plus_elevation",
    )

    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(64.dp)
            .shadow(elevation, CircleShape)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .testTag("water_plus_button"),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.fitDesiColors.info,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add one glass",
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun HomeWaterTarget(
    glass: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.fitDesiColors.info
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(FitDesiMotion.fast),
        label = "water_target_container_$glass",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.fitDesiColors.info else MaterialTheme.fitDesiColors.border,
        animationSpec = tween(FitDesiMotion.fast),
        label = "water_target_border_$glass",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(FitDesiMotion.fast),
        label = "water_target_content_$glass",
    )

    Box(
        modifier = modifier
            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.Button)
            .semantics { contentDescription = "Glass $glass of 8" }
            .testTag("water_glass_$glass"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(FitDesiDimensions.cardBorderWidth, borderColor),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = glass.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HomeWorkoutAndActivity(
    todayWorkouts: List<WorkoutLog>,
    workoutFacts: HomeWorkoutFacts,
    onTrackWorkout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "TRAINING", title = "Today’s activity")
        HomeActivityStrip(
            sessions = workoutFacts.sessionCount,
            duration = formatActivityDuration(workoutFacts.durationSeconds),
            completedSets = workoutFacts.completedSets,
        )
        AnimatedContent(
            targetState = todayWorkouts.isEmpty(),
            transitionSpec = {
                fadeIn(tween(HOME_ENTRANCE_MS)) togetherWith fadeOut(tween(HOME_ENTRANCE_MS))
            },
            label = "home_workout_content",
        ) { empty ->
            if (empty) {
                HomeWorkoutEmptyState(onTrackWorkout = onTrackWorkout)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    todayWorkouts.take(2).forEach { log ->
                        HomeWorkoutRow(log = log)
                    }
                    if (todayWorkouts.size > 2) {
                        Text(
                            text = "+${todayWorkouts.size - 2} more completed today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActivityStrip(
    sessions: Int,
    duration: String,
    completedSets: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(FitDesiSpacing.small)) {
            val stack = maxWidth < 270.dp || LocalDensity.current.fontScale >= 1.3f
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeActivityMetric("Sessions", sessions.toString(), Modifier.fillMaxWidth())
                    HomeActivityMetric("Duration", duration, Modifier.fillMaxWidth())
                    HomeActivityMetric("Completed sets", completedSets.toString(), Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeActivityMetric("Sessions", sessions.toString(), Modifier.weight(1f))
                    HomeActivityMetric("Duration", duration, Modifier.weight(1f))
                    HomeActivityMetric("Completed sets", completedSets.toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeActivityMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
    }
}

@Composable
private fun HomeWorkoutEmptyState(onTrackWorkout: () -> Unit) {
    HomeSectionSurface {
        BoxWithConstraints(modifier = Modifier.padding(FitDesiSpacing.medium)) {
            val stack = maxWidth < 270.dp || LocalDensity.current.fontScale >= 1.3f
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                    HomeWorkoutEmptyMessage()
                    FitDesiSecondaryButton(
                        text = "Track now",
                        onClick = onTrackWorkout,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        HomeWorkoutEmptyMessage()
                    }
                    FitDesiSecondaryButton(text = "Track", onClick = onTrackWorkout)
                }
            }
        }
    }
}

@Composable
private fun HomeWorkoutEmptyMessage() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
    ) {
        Surface(
            shape = CircleShape,
            color = FitDesiOrange.copy(alpha = 0.14f),
            contentColor = FitDesiOrange,
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.padding(FitDesiSpacing.small).size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "No workout logged yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Complete a real session to populate today’s activity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeWorkoutRow(log: WorkoutLog) {
    HomeSectionSurface {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.fitDesiColors.success.copy(alpha = 0.16f),
                contentColor = MaterialTheme.fitDesiColors.success,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed workout",
                    modifier = Modifier.padding(FitDesiSpacing.small).size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro),
            ) {
                Text(
                    text = log.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = homeWorkoutRowDetails(log),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeAiCoachCard(onOpenAiCoach: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(
            FitDesiDimensions.cardBorderWidth,
            FitDesiOrange.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
        ) {
            Surface(
                shape = CircleShape,
                color = FitDesiOrange,
                contentColor = FitDesiInk,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.padding(FitDesiSpacing.small).size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
            ) {
                Text(
                    text = "FitDesi AI Coach",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Offline coaching grounded in your local FitDesi knowledge and profile context.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
                FitDesiPrimaryButton(
                    text = "Ask AI Coach",
                    onClick = onOpenAiCoach,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeQuickActions(
    onNavigateToCalories: () -> Unit,
    onNavigateToExercises: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        HomeSectionHeading(eyebrow = "QUICK ACTIONS", title = "Keep moving")
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stack = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeMealButton(onClick = onNavigateToCalories, modifier = Modifier.fillMaxWidth())
                    HomeWorkoutButton(onClick = onNavigateToExercises, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    HomeMealButton(onClick = onNavigateToCalories, modifier = Modifier.weight(1f))
                    HomeWorkoutButton(onClick = onNavigateToExercises, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeMealButton(onClick: () -> Unit, modifier: Modifier) {
    FitDesiPrimaryButton(
        text = "Log meal",
        onClick = onClick,
        modifier = modifier.testTag("shortcut_calories"),
        leadingIcon = {
            Icon(Icons.Default.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
        },
    )
}

@Composable
private fun HomeWorkoutButton(onClick: () -> Unit, modifier: Modifier) {
    FitDesiSecondaryButton(
        text = "Track now",
        onClick = onClick,
        modifier = modifier.testTag("shortcut_exercises"),
        leadingIcon = {
            Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(18.dp))
        },
    )
}

@Composable
private fun HomeSectionHeading(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = FitDesiOrange,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HomeSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = FitDesiDimensions.cardBorderWidth,
            color = MaterialTheme.fitDesiColors.border,
        ),
        content = content,
    )
}

private fun formatActivityDuration(totalSeconds: Int): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        safeSeconds > 0 -> "${safeSeconds}s"
        else -> "0m"
    }
}

@Preview(name = "Home V2 - 320dp Empty Light", showBackground = true, widthDp = 320, heightDp = 1600)
@Composable
private fun HomeV2EmptyLightPreview() {
    MyPersonalTrainerTheme(theme = "Light") {
        HomeScreen(
            profileName = "",
            todayConsumed = 0,
            dailyGoal = 0,
            todayProtein = 18f,
            todayCarbs = 42f,
            todayFat = 12f,
            proteinGoal = 0f,
            carbsGoal = 0f,
            fatGoal = 0f,
            showMacroTargetProgress = false,
            workoutLogs = emptyList(),
            activePlanJson = "",
            waterGlasses = 0,
            onWaterIntakeChange = {},
            onNavigateToCalories = {},
            onSetMacroTargets = {},
            onNavigateToExercises = {},
            onOpenAiCoach = {},
            showBasicPlusEntry = false,
            onExplorePlus = {},
        )
    }
}

@Preview(name = "Home V2 - 360dp Dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 360, heightDp = 1600)
@Composable
private fun HomeV2DarkPreview() {
    MyPersonalTrainerTheme(theme = "Dark") {
        HomeScreen(
            profileName = "Preview user",
            todayConsumed = 1460,
            dailyGoal = 2400,
            todayProtein = 72f,
            todayCarbs = 180f,
            todayFat = 48f,
            proteinGoal = 110f,
            carbsGoal = 320f,
            fatGoal = 70f,
            showMacroTargetProgress = true,
            workoutLogs = listOf(
                WorkoutLog(
                    id = 1,
                    sessionId = "preview-session",
                    exerciseName = "Upper Body Strength",
                    category = "Strength",
                    durationMinutes = 42,
                    caloriesBurned = 0,
                    completedSets = 12,
                ),
            ),
            activePlanJson = """{"name":"Preview strength plan","description":"","splitType":"Full Body","frequency":"3 days per week","days":[]}""",
            waterGlasses = 5,
            onWaterIntakeChange = {},
            onNavigateToCalories = {},
            onSetMacroTargets = {},
            onNavigateToExercises = {},
            onOpenAiCoach = {},
            showBasicPlusEntry = true,
            onExplorePlus = {},
        )
    }
}

@Preview(name = "Home V2 - 320dp Large Text", showBackground = true, widthDp = 320, heightDp = 1800, fontScale = 1.5f)
@Composable
private fun HomeV2LargeTextPreview() {
    MyPersonalTrainerTheme(theme = "Light") {
        HomeScreen(
            profileName = "A user with a longer profile name",
            todayConsumed = 860,
            dailyGoal = 0,
            todayProtein = 34f,
            todayCarbs = 112f,
            todayFat = 24f,
            proteinGoal = 0f,
            carbsGoal = 0f,
            fatGoal = 0f,
            showMacroTargetProgress = false,
            workoutLogs = emptyList(),
            activePlanJson = "",
            waterGlasses = 8,
            onWaterIntakeChange = {},
            onNavigateToCalories = {},
            onSetMacroTargets = {},
            onNavigateToExercises = {},
            onOpenAiCoach = {},
            showBasicPlusEntry = true,
            onExplorePlus = {},
        )
    }
}
