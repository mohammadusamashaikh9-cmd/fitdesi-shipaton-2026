package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.viewmodel.TrainerViewModel
import com.example.viewmodel.ProgressViewModel
import com.example.viewmodel.ProgressUiState

import com.example.ai.GeneratedRoutine
import com.example.data.WorkoutLog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiSpacing
import com.google.gson.Gson

internal fun restorableWorkoutRoute(route: String): String = when (route) {
    "generator", "progress", "manage_history", "my_routines" -> route
    else -> "dashboard"
}

private val WorkoutRouteStateSaver = listSaver<MutableState<String>, String>(
    save = { listOf(restorableWorkoutRoute(it.value)) },
    restore = { mutableStateOf(restorableWorkoutRoute(it.firstOrNull().orEmpty())) }
)

internal fun activePlanAfterRemovingDay(
    routine: GeneratedRoutine,
    dayIndex: Int
): GeneratedRoutine? {
    if (dayIndex !in routine.days.indices) return routine
    val remainingDays = routine.days.filterIndexed { index, _ -> index != dayIndex }
    return if (remainingDays.isEmpty()) null else routine.copy(days = remainingDays)
}

// --- DATA REPRESENTING DASHBOARD GRID ITEM ---
data class GridItem(
    val id: Int,
    val title: String,
    val icon: ImageVector,
    val isNew: Boolean = false,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDashboardScreen(
    viewModel: TrainerViewModel? = null,
    progressViewModel: ProgressViewModel? = null,
    modifier: Modifier = Modifier,
    onNavigateToDetail: (String) -> Unit = {},
    rootNavigationRequest: Int = 0,
    onTrainingModeChanged: (Boolean) -> Unit = {},
    onRoutineLimitReached: () -> Unit = {},
    onAiWorkoutGenerationRequiresPlus: () -> Unit = {},
    onFullHistoryRequiresPlus: () -> Unit = {},
    onAdvancedAnalyticsRequiresPlus: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var activeScreen by rememberSaveable(saver = WorkoutRouteStateSaver) {
        mutableStateOf("dashboard")
    }
    var routineToTrack by remember { mutableStateOf<GeneratedRoutine?>(null) }
    var routineSourceId by remember { mutableStateOf<String?>(null) }
    var routineDayIndex by remember { mutableStateOf<Int?>(null) }
    var selectionRoutine by remember { mutableStateOf<GeneratedRoutine?>(null) }
    var selectionRoutineSourceId by remember { mutableStateOf<String?>(null) }
    var manualStartRequested by remember { mutableStateOf(false) }
    var progressInitialSectionName by rememberSaveable {
        mutableStateOf(ProgressHubSection.TRAINING.name)
    }
    val progressInitialSection = runCatching {
        ProgressHubSection.valueOf(progressInitialSectionName)
    }.getOrDefault(ProgressHubSection.TRAINING)
    val progressState by if (progressViewModel != null) {
        progressViewModel.uiState.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<ProgressUiState?>(null) }
    }
    ProgressDateRefreshEffect(
        onRefresh = { progressViewModel?.refreshCurrentDate() }
    )

    fun openManualTracker() {
        routineToTrack = null
        routineSourceId = null
        routineDayIndex = null
        manualStartRequested = true
        activeScreen = "track_workout"
    }

    fun openDaySelector(routine: GeneratedRoutine, sourceRoutineId: String?) {
        selectionRoutine = routine
        selectionRoutineSourceId = sourceRoutineId
        routineToTrack = null
        routineSourceId = null
        routineDayIndex = null
        manualStartRequested = false
        activeScreen = "select_workout_day"
    }

    LaunchedEffect(activeScreen) {
        onTrainingModeChanged(activeScreen == "track_workout")
    }
    DisposableEffect(Unit) {
        onDispose { onTrainingModeChanged(false) }
    }

    var handledRootNavigationRequest by rememberSaveable {
        mutableIntStateOf(rootNavigationRequest)
    }
    LaunchedEffect(rootNavigationRequest) {
        if (rootNavigationRequest != handledRootNavigationRequest) {
            handledRootNavigationRequest = rootNavigationRequest
            activeScreen = "dashboard"
            routineToTrack = null
            routineSourceId = null
            routineDayIndex = null
            selectionRoutine = null
            selectionRoutineSourceId = null
            manualStartRequested = false
        }
    }
    BackHandler(enabled = activeScreen != "dashboard") {
        activeScreen = if (activeScreen == "manage_history") "progress" else "dashboard"
        routineToTrack = null
        routineSourceId = null
        routineDayIndex = null
        selectionRoutine = null
        selectionRoutineSourceId = null
        manualStartRequested = false
    }

    if (activeScreen == "progress" && progressViewModel != null) {
        ProgressHubScreen(
            viewModel = progressViewModel,
            initialSection = progressInitialSection,
            onBack = { activeScreen = "dashboard" },
            onFullHistoryRequired = onFullHistoryRequiresPlus,
            onAdvancedAnalyticsRequired = onAdvancedAnalyticsRequiresPlus,
            onManageWorkoutHistory = { activeScreen = "manage_history" },
            modifier = modifier
        )
        return
    } else if (activeScreen == "manage_history") {
        WorkoutHistoryScreen(
            viewModel = viewModel,
            onBack = { activeScreen = "progress" },
            canShowDetailedHistory = { timestamp ->
                progressViewModel?.canShowWorkoutManagementDetail(timestamp) == true
            },
            modifier = modifier
        )
        return
    } else if (activeScreen == "my_routines") {
        MyRoutinesScreen(
            viewModel = viewModel,
            onBack = { activeScreen = "dashboard" },
            onChooseWorkoutDay = { routine ->
                openDaySelector(routine.routine, routine.routineId)
            },
            modifier = modifier
        )
        return
    } else if (activeScreen == "generator" && viewModel != null) {
        WorkoutGeneratorScreen(
            viewModel = viewModel,
            onBack = { activeScreen = "dashboard" },
            onAiWorkoutGenerationRequiresPlus = onAiWorkoutGenerationRequiresPlus,
            modifier = modifier
        )
        return
    } else if (activeScreen == "select_workout_day") {
        val routine = selectionRoutine
        if (routine != null) {
            val trackerPreferences = remember(context) {
                context.getSharedPreferences("workout_tracker_prefs", android.content.Context.MODE_PRIVATE)
            }
            val restoredDraft = remember(routine, selectionRoutineSourceId) {
                readActiveWorkoutDraft(trackerPreferences)
            }
            WorkoutDaySelectionScreen(
                routine = routine,
                sourceRoutineId = selectionRoutineSourceId,
                hasDraft = restoredDraft.hasDraft,
                restoredContext = restoredDraft.context,
                onBack = {
                    selectionRoutine = null
                    selectionRoutineSourceId = null
                    activeScreen = "dashboard"
                },
                onExecuteSelectedDay = { selectedDayIndex ->
                    routineToTrack = routine
                    routineSourceId = selectionRoutineSourceId
                    routineDayIndex = selectedDayIndex
                    manualStartRequested = false
                    activeScreen = "track_workout"
                },
                modifier = modifier
            )
        }
        return
    } else if (activeScreen == "track_workout") {
        TrackWorkoutScreen(
            viewModel = viewModel,
            onBack = {
                routineToTrack = null
                routineSourceId = null
                routineDayIndex = null
                selectionRoutine = null
                selectionRoutineSourceId = null
                manualStartRequested = false
                activeScreen = "dashboard"
            },
            initialRoutine = routineToTrack,
            initialRoutineSourceId = routineSourceId,
            initialSelectedDayIndex = routineDayIndex,
            initialManualStartRequested = manualStartRequested,
            modifier = modifier
        )
        return
    } else if (activeScreen == "build_routine") {
        BuildRoutineScreen(
            onBackToWorkout = { activeScreen = "dashboard" },
            onSaveRoutine = { routine, onResult ->
                if (viewModel == null) {
                    onResult(com.example.data.SavedRoutineResult.ERROR)
                } else {
                    viewModel.saveBuildRoutine(routine, onResult)
                }
            },
            onRoutineLimitReached = onRoutineLimitReached,
            modifier = modifier
        )
        return
    } else if (activeScreen == "build_workout" && viewModel != null) {
        BuildWorkoutScreen(
            viewModel = viewModel,
            onBack = { activeScreen = "dashboard" },
            onRoutineLimitReached = onRoutineLimitReached,
            modifier = modifier
        )
        return
    }
    
    // The active plan stays separate from the persisted routine library.

    
    val profileState by if (viewModel != null) {
        viewModel.userProfile.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(com.example.data.UserProfile()) }
    }

    val savedRoutines by if (viewModel != null) {
        viewModel.savedRoutines.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }




    val savedRoutine = remember(profileState.generatedWorkoutPlan) {
        parseActiveWorkoutPlan(profileState.generatedWorkoutPlan)
    }
    val currentPlanSourceId = remember(savedRoutine, savedRoutines) {
        savedRoutine?.let { activePlan ->
            savedRoutines.firstOrNull { saved ->
                saved.routineId == activePlan.planId && saved.routine == activePlan
            }?.routineId
        }
    }

    if (activeScreen == "manage_current_plan" && savedRoutine != null) {
        CurrentPlanManagementScreen(
            routine = savedRoutine,
            onBack = { activeScreen = "dashboard" },
            onClearPlan = {
                viewModel?.setActiveWorkoutPlan("")
                activeScreen = "dashboard"
            },
            onRemoveDay = { dayIndex ->
                val updatedRoutine = activePlanAfterRemovingDay(savedRoutine, dayIndex)
                if (updatedRoutine == null) {
                    viewModel?.setActiveWorkoutPlan("")
                    activeScreen = "dashboard"
                } else {
                    viewModel?.setActiveWorkoutPlan(Gson().toJson(updatedRoutine))
                }
            },
            modifier = modifier
        )
        return
    }



    // Presentation follows the app-level Material theme selected in Profile.
    val backgroundColor = MaterialTheme.colorScheme.background
    val cardBackgroundColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Workout",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("workout_dashboard_container"),
            contentPadding = PaddingValues(
                start = FitDesiSpacing.medium,
                end = FitDesiSpacing.medium,
                bottom = FitDesiSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
        ) {
            item {
                Text(
                    text = "Plan, train and review your training",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceSecondaryColor
                )
            }

            savedRoutine?.let { currentPlan ->
                item(key = "current_plan_continuity") {
                    WorkoutCurrentPlanContinuityCard(
                        routine = currentPlan,
                        onChooseWorkoutDay = {
                            openDaySelector(currentPlan, currentPlanSourceId)
                        },
                        onManageCurrentPlan = { activeScreen = "manage_current_plan" }
                    )
                }
            }

            item {
                WorkoutPrimaryTrackAction(
                    prominent = savedRoutine == null,
                    onTrackWorkout = ::openManualTracker
                )
            }

            item {
                WorkoutActionGroup(
                    title = "PLAN & CREATE",
                    actions = listOf(
                        WorkoutRootAction(
                            title = "Workout Planner",
                            detail = "Personalized from your goals, experience and schedule",
                            icon = Icons.Default.CalendarMonth,
                            onClick = { activeScreen = "generator" }
                        ),
                        WorkoutRootAction(
                            title = "Build Routine",
                            detail = "Name and configure a routine",
                            icon = Icons.Default.Layers,
                            onClick = { activeScreen = "build_routine" }
                        ),
                        WorkoutRootAction(
                            title = "Create Workout",
                            detail = "Choose and group exercises yourself",
                            icon = Icons.Default.FitnessCenter,
                            onClick = { activeScreen = "build_workout" }
                        ),
                        WorkoutRootAction(
                            title = "My Routines",
                            detail = when {
                                viewModel == null -> "Routine library unavailable"
                                savedRoutines.isEmpty() -> "No saved routines"
                                else -> savedRoutines.size.toString() + " saved routines"
                            },
                            icon = Icons.Default.EventNote,
                            onClick = { activeScreen = "my_routines" }
                        )
                    )
                )
            }

            item {
                WorkoutActionGroup(
                    title = "REVIEW",
                    actions = listOf(
                        WorkoutRootAction(
                            title = "Progress",
                            detail = "Training trends and overview",
                            icon = Icons.Default.TrendingUp,
                            onClick = {
                                progressInitialSectionName = ProgressHubSection.TRAINING.name
                                activeScreen = "progress"
                            }
                        ),
                        WorkoutRootAction(
                            title = "Workout history",
                            detail = "Review completed sessions",
                            icon = Icons.Default.History,
                            onClick = {
                                progressInitialSectionName = ProgressHubSection.JOURNAL.name
                                activeScreen = "progress"
                            }
                        )
                    )
                )
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                ) {
                    Text(
                        text = "LATEST ACTIVITY",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        letterSpacing = 1.sp,
                        modifier = Modifier.semantics { heading() }
                    )
                    LatestWorkoutCard(
                        latestWorkout = progressState?.latestRecentWorkout,
                        onTrackWorkout = ::openManualTracker
                    )
                }
            }
        }
    }
}
private data class WorkoutRootAction(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun WorkoutPrimaryTrackAction(
    prominent: Boolean,
    onTrackWorkout: () -> Unit
) {
    if (prominent) {
        Button(
            onClick = onTrackWorkout,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FitDesiDimensions.primaryControlHeight)
                .testTag("workout_primary_track_action")
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(FitDesiSpacing.extraSmall))
            Text("Track a workout", fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onTrackWorkout,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FitDesiDimensions.primaryControlHeight)
                .testTag("workout_primary_track_action")
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(FitDesiSpacing.extraSmall))
            Text("Track a workout", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorkoutActionGroup(
    title: String,
    actions: List<WorkoutRootAction>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.semantics { heading() }
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                actions.forEachIndexed { index, action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = action.onClick)
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .padding(
                                horizontal = FitDesiSpacing.medium,
                                vertical = FitDesiSpacing.small
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = action.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = action.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Open " + action.title,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index < actions.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCurrentPlanContinuityCard(
    routine: GeneratedRoutine,
    onChooseWorkoutDay: () -> Unit,
    onManageCurrentPlan: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workout_current_plan_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Text(
                text = "CURRENT PLAN",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = routine.name.ifBlank { "Current workout plan" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val details = buildList {
                if (routine.frequency.isNotBlank()) add(routine.frequency)
                if (routine.days.isNotEmpty()) {
                    add(
                        routine.days.size.toString() + " " +
                            if (routine.days.size == 1) "plan day" else "plan days"
                    )
                }
            }.joinToString(" • ")
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onChooseWorkoutDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workout_choose_current_plan_day"),
                enabled = routine.days.isNotEmpty()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text("Choose workout day")
            }
            TextButton(
                onClick = onManageCurrentPlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workout_manage_current_plan")
            ) {
                Text("Manage current plan")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrentPlanManagementScreen(
    routine: GeneratedRoutine,
    onBack: () -> Unit,
    onClearPlan: () -> Unit,
    onRemoveDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val expandedDays = remember { mutableStateMapOf<Int, Boolean>() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Manage current plan", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Workout")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = FitDesiSpacing.medium,
                end = FitDesiSpacing.medium,
                bottom = FitDesiSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            item {
                Text(routine.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (routine.description.isNotBlank()) {
                    Text(
                        routine.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onClearPlan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FitDesiSpacing.small),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                    Text("Clear current plan")
                }
            }
            items(routine.days.size, key = { it }) { dayIndex ->
                val day = routine.days[dayIndex]
                val isExpanded = expandedDays[dayIndex] == true
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(FitDesiSpacing.medium)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedDays[dayIndex] = !isExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    day.dayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    day.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse day" else "Expand day"
                            )
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(FitDesiSpacing.small))
                            RoutineDayDetails(day)
                        }
                        TextButton(
                            onClick = { onRemoveDay(dayIndex) },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Remove day")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutHistoryScreen(
    viewModel: TrainerViewModel?,
    onBack: () -> Unit,
    canShowDetailedHistory: (Long) -> Boolean,
    modifier: Modifier = Modifier
) {
    val workoutLogs by if (viewModel != null) {
        viewModel.workoutLogs.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<WorkoutLog>()) }
    }
    val sessions = remember(workoutLogs) { workoutLogs.sortedByDescending { it.timestamp } }
    val backgroundColor = MaterialTheme.colorScheme.background

    Scaffold(
        modifier = modifier,
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Manage workout history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (sessions.isEmpty()) "No saved sessions yet" else "${sessions.size} saved sessions",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Progress")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("workout_history_screen"),
            contentPadding = PaddingValues(
                start = FitDesiSpacing.medium,
                end = FitDesiSpacing.medium,
                bottom = FitDesiSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            if (sessions.isEmpty()) {
                item {
                    WorkoutCollectionEmptyState(
                        showingHistory = true,
                        onBuildRoutine = {}
                    )
                }
            } else {
                items(sessions, key = { it.id }) { workout ->
                    WorkoutHistoryRow(
                        workout = workout,
                        showDetailedHistory = canShowDetailedHistory(workout.timestamp),
                        onRemove = { viewModel?.deleteWorkoutLog(workout) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorkoutHistoryRow(
    workout: WorkoutLog,
    showDetailedHistory: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completedDate = remember(workout.timestamp) {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(workout.timestamp))
    }
    if (showDetailedHistory) {
        val sessionDetails = buildList {
            add(completedDate)
            if (workout.completedSets > 0) add("${workout.completedSets} completed sets")
            if (workout.liftingVolumeKg > 0) add("${workout.liftingVolumeKg.roundToInt()} kg volume")
        }.joinToString(" • ")
        PopulatedWorkoutItem(
            title = workout.exerciseName,
            duration = workout.formattedRecordedDuration(),
            exercisesCount = sessionDetails,
            difficulty = workout.category,
            containerColor = MaterialTheme.colorScheme.surface,
            onSurfaceColor = MaterialTheme.colorScheme.onSurface,
            onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
            onRemove = onRemove
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(FitDesiSpacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
                    Text("Workout record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        completedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete workout record",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutCollectionEmptyState(
    showingHistory: Boolean,
    onBuildRoutine: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Icon(
                imageVector = if (showingHistory) Icons.Default.History else Icons.Default.EventNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (showingHistory) "No workout history yet" else "No saved routine yet",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (showingHistory) {
                        "Finish a tracked workout to build your history."
                    } else {
                        "Build a routine when you are ready to plan your training."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!showingHistory) {
                TextButton(onClick = onBuildRoutine) { Text("Build") }
            }
        }
    }
}

@Composable
private fun LatestWorkoutCard(
    latestWorkout: WorkoutLog?,
    onTrackWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            if (latestWorkout == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("No recent workout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Complete a tracked session to see activity from the last 30 days here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val completedDate = remember(latestWorkout.timestamp) {
                    java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
                        .format(java.util.Date(latestWorkout.timestamp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Most recent workout", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            latestWorkout.exerciseName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            completedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                ) {
                    WorkoutDatum(latestWorkout.formattedRecordedDuration(), "Duration", Modifier.weight(1f))
                    if (latestWorkout.completedSets > 0) {
                        WorkoutDatum("${latestWorkout.completedSets}", "Sets", Modifier.weight(1f))
                    }
                    if (latestWorkout.liftingVolumeKg > 0.0) {
                        WorkoutDatum("${latestWorkout.liftingVolumeKg.roundToInt()} kg", "Volume", Modifier.weight(1f))
                    }
                }
            }
            OutlinedButton(
                onClick = onTrackWorkout,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FitDesiDimensions.minimumTouchTarget)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text("Track workout")
            }
        }
    }
}

@Composable
private fun WorkoutSecondaryActionCard(
    title: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WorkoutListAction(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                .padding(horizontal = FitDesiSpacing.medium, vertical = FitDesiSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Open $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkoutActivitySnapshot(
    sessionCount: Int,
    latestWorkout: WorkoutLog?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text("Your activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "$sessionCount saved sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if ((latestWorkout?.liftingVolumeKg ?: 0.0) > 0.0) {
                WorkoutDatum(
                    "${latestWorkout!!.liftingVolumeKg.roundToInt()} kg",
                    "Last volume"
                )
            }
        }
    }
}

@Composable
private fun WorkoutDatum(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WorkoutStartCard(
    onTrackWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Track your workout",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Log real sets, reps, weight and rest as you train.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Button(
                onClick = onTrackWorkout,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = FitDesiDimensions.primaryControlHeight)
                    .testTag("workout_primary_track_action"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text("Track Workout", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- SUB-COMPONENT: INDIVIDUAL DASHBOARD GRID BLOCK (M3 standard corner structures) ---
@Composable
fun DashboardGridBlock(
    item: GridItem,
    backgroundColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("dashboard_box_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top row inside the block holding the customized layout vector Icon and accent badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )

                    // Accent badge logic for "Workout Generator" or generic custom markings
                    if (item.isNew) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NEW",
                                fontFamily = InterFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // Core labels block with strict contrast settings
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.title,
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = onSurfaceColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = item.description,
                        fontFamily = InterFontFamily,
                        fontSize = 10.sp,
                        color = onSurfaceSecondaryColor,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// --- SUB-COMPONENT: LOW-IMPACT EMPTY STATE COMPONENT ---
@Composable
fun LowImpactEmptyState(
    onSurfaceSecondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val vectorShapesColor = MaterialTheme.colorScheme.surfaceVariant
    val pulseAccentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp)
            .testTag("empty_state_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Centered minimalist utility vector shapes drawn on Custom Canvas
        Box(
            modifier = Modifier
                .size(100.dp)
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Pulse accent circle block representing visual dynamic depth
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(pulseAccentColor)
            )

            Canvas(
                modifier = Modifier.size(54.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Draw standard geometric layouts matching custom minimalist blueprints
                val path = Path().apply {
                    // Modern abstract dumbbell shape vector representation
                    moveTo(canvasWidth * 0.15f, canvasHeight * 0.5f)
                    lineTo(canvasWidth * 0.85f, canvasHeight * 0.5f)
                }
                drawPath(
                    path = path,
                    color = vectorShapesColor,
                    style = Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )

                // Left plate block outline
                drawRect(
                    color = vectorShapesColor,
                    topLeft = Offset(canvasWidth * 0.15f, canvasHeight * 0.25f),
                    size = androidx.compose.ui.geometry.Size(canvasWidth * 0.12f, canvasHeight * 0.5f),
                    style = Fill
                )

                // Right plate block outline
                drawRect(
                    color = vectorShapesColor,
                    topLeft = Offset(canvasWidth * 0.73f, canvasHeight * 0.25f),
                    size = androidx.compose.ui.geometry.Size(canvasWidth * 0.12f, canvasHeight * 0.5f),
                    style = Fill
                )

                // Outer decorative concentric layout frame lines
                drawCircle(
                    color = vectorShapesColor.copy(alpha = 0.4f),
                    radius = canvasWidth * 0.45f,
                    center = Offset(canvasWidth / 2, canvasHeight / 2),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Requested exact text specification layout
        Text(
            text = "You haven't created any workout or routine.",
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = onSurfaceSecondaryColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// --- SUB-COMPONENT: SWIPE TO DISMISS WRAPPER ---
@Composable
fun SwipeToDismissWorkoutWrapper(
    key: String,
    onRemove: () -> Unit,
    cardBgColor: Color,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val swipeOffset = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Limits in pixels
    val limitPx = with(density) { -80.dp.toPx() }
    val deleteThresholdPx = with(density) { -180.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag("swipe_wrapper_$key")
    ) {
        // Background layer: Red delete button area
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0xFFE53935))
                .clickable {
                    coroutineScope.launch {
                        swipeOffset.animateTo(-1000f, tween(300))
                        onRemove()
                    }
                }
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Item",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "DELETE",
                    color = Color.White,
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // Foreground layer: The workout item card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (swipeOffset.value < deleteThresholdPx) {
                                    swipeOffset.animateTo(-1000f, tween(200))
                                    onRemove()
                                } else if (swipeOffset.value < limitPx / 2) {
                                    swipeOffset.animateTo(limitPx, spring())
                                } else {
                                    swipeOffset.animateTo(0f, spring())
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val target = (swipeOffset.value + dragAmount).coerceIn(-250f * density.density, 0f)
                                swipeOffset.snapTo(target)
                            }
                        }
                    )
                }
                .background(cardBgColor)
        ) {
            content()
        }
    }
}

// --- SUB-COMPONENT: SAVED WORKOUT HISTORY ITEM ---
@Composable
fun PopulatedWorkoutItem(
    title: String,
    duration: String,
    exercisesCount: String,
    difficulty: String,
    containerColor: Color,
    onSurfaceColor: Color,
    onSurfaceSecondaryColor: Color,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null
) {
    val difficultyAccent = if (difficulty == "Advanced") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = onSurfaceColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = duration,
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        color = onSurfaceSecondaryColor
                    )
                    Text(
                        text = "•",
                        fontSize = 11.sp,
                        color = onSurfaceSecondaryColor
                    )
                    Text(
                        text = exercisesCount,
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        color = onSurfaceSecondaryColor
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Small difficulty tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(difficultyAccent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = difficulty.uppercase(),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = difficultyAccent
                    )
                }

                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Workout",
                            tint = Color.Red.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// --- SCREEN PREVIEWS ---
@Preview(name = "Workout dashboard compact dark", widthDp = 320, heightDp = 800, showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun WorkoutDashboardScreenDarkPreview() {
    MaterialTheme {
        WorkoutDashboardScreen()
    }
}

@Preview(name = "Workout dashboard compact light", widthDp = 360, heightDp = 800, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun WorkoutDashboardScreenLightPreview() {
    MaterialTheme {
        // Force state parameters internally for view check
        Box(modifier = Modifier.background(Color.White)) {
            WorkoutDashboardScreen()
        }
    }
}
