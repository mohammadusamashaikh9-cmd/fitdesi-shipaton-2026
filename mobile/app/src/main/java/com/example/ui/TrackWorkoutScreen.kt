package com.example.ui

import android.widget.Toast
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.security.SafeLog
import com.example.viewmodel.TrainerViewModel
import com.example.viewmodel.ExerciseUiState
import com.example.viewmodel.ExerciseViewModel
import com.example.viewmodel.WorkoutSaveResult
import com.example.fitdesi.data.Exercise
import com.example.ai.GeneratedRoutine
import com.example.ai.ActivityPrescriptionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiMotion
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.components.FitDesiEmptyState
import com.example.ui.components.FitDesiErrorState
import com.example.ui.components.FitDesiLoadingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal const val TRACK_WORKOUT_EXERCISE_LOAD_ERROR =
    "Exercise library could not be loaded. Try again."

internal sealed interface TrackWorkoutCatalogueContent {
    data object Loading : TrackWorkoutCatalogueContent
    data object EmptyCatalogue : TrackWorkoutCatalogueContent
    data object NoMatches : TrackWorkoutCatalogueContent
    data class Ready(val exercises: List<Exercise>) : TrackWorkoutCatalogueContent
    data class Error(
        val message: String = TRACK_WORKOUT_EXERCISE_LOAD_ERROR,
        val retryAvailable: Boolean = true
    ) : TrackWorkoutCatalogueContent
}

internal data class TrackWorkoutSelectionResult(
    val exercise: TrackedExercise,
    val totalSeconds: Int,
    val isTimerPaused: Boolean
)

internal fun filterTrackWorkoutExercises(
    exercises: List<Exercise>,
    query: String
): List<Exercise> =
    if (query.isBlank()) exercises
    else exercises.filter { it.name.contains(query, ignoreCase = true) }

internal fun trackWorkoutCatalogueContent(
    uiState: ExerciseUiState,
    query: String
): TrackWorkoutCatalogueContent = when (uiState) {
    ExerciseUiState.Loading -> TrackWorkoutCatalogueContent.Loading
    is ExerciseUiState.Error -> TrackWorkoutCatalogueContent.Error()
    is ExerciseUiState.Success -> {
        if (uiState.exercises.isEmpty()) {
            TrackWorkoutCatalogueContent.EmptyCatalogue
        } else {
            val filtered = filterTrackWorkoutExercises(uiState.exercises, query)
            if (filtered.isEmpty()) {
                TrackWorkoutCatalogueContent.NoMatches
            } else {
                TrackWorkoutCatalogueContent.Ready(filtered)
            }
        }
    }
}

internal fun selectTrackWorkoutExercise(
    exercise: Exercise,
    hasTrackedExercises: Boolean,
    totalSeconds: Int,
    isTimerPaused: Boolean
): TrackWorkoutSelectionResult = TrackWorkoutSelectionResult(
    exercise = exercise.toTrackedExercise(),
    totalSeconds = if (hasTrackedExercises) totalSeconds else 0,
    isTimerPaused = if (hasTrackedExercises) isTimerPaused else false
)

internal fun canAttemptWorkoutSave(
    isSavingWorkout: Boolean,
    hasSavedWorkout: Boolean
): Boolean = !isSavingWorkout && !hasSavedWorkout

internal fun shouldUseStackedSetCards(screenWidthDp: Int, fontScale: Float): Boolean =
    screenWidthDp < 400 || fontScale > 1.2f

internal data class WorkoutCompletionSummary(
    val title: String,
    val completedAt: Long,
    val durationSeconds: Int,
    val exerciseNames: List<String>,
    val completedSets: Int,
    val volumeKg: Double,
    val calories: Int
)

internal data class RestoredActiveWorkoutDraft(
    val exercises: List<TrackedExercise>,
    val context: ActiveWorkoutContext?
) {
    val hasDraft: Boolean
        get() = exercises.isNotEmpty()
}

internal fun readActiveWorkoutDraft(sharedPrefs: SharedPreferences): RestoredActiveWorkoutDraft {
    val exercises = sharedPrefs.getString("tracked_exercises", null)
        ?.takeIf(String::isNotBlank)
        ?.let { savedJson ->
            runCatching {
                val type = object : TypeToken<List<TrackedExercise>>() {}.type
                Gson().fromJson<List<TrackedExercise>>(savedJson, type)
                    .orEmpty()
                    .normalizeTrackedExercises()
            }.getOrNull()
        }
        .orEmpty()
    val context = sharedPrefs.getString("active_workout_context", null)
        ?.takeIf(String::isNotBlank)
        ?.let { json ->
            runCatching { Gson().fromJson(json, ActiveWorkoutContext::class.java) }.getOrNull()
        }
    return RestoredActiveWorkoutDraft(exercises = exercises, context = context)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackWorkoutScreen(
    viewModel: TrainerViewModel?,
    onBack: () -> Unit,
    initialRoutine: GeneratedRoutine? = null,
    initialRoutineSourceId: String? = null,
    initialSelectedDayIndex: Int? = null,
    initialManualStartRequested: Boolean = false,
    modifier: Modifier = Modifier,
    exerciseViewModel: ExerciseViewModel = composeViewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val exerciseUiState by exerciseViewModel.uiState.collectAsStateWithLifecycle()

    val backgroundColor = MaterialTheme.colorScheme.background
    val cardBgColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryBlue = Color(0xFF1565C0)
    val successColor = Color(0xFF00C853)
    val errorColor = Color(0xFFD50000)

    val sharedPrefs = remember {
        context.getSharedPreferences("workout_tracker_prefs", android.content.Context.MODE_PRIVATE)
    }

    val restoredDraft = remember(sharedPrefs) { readActiveWorkoutDraft(sharedPrefs) }
    val restoredTrackedExercises = restoredDraft.exercises
    val restoredWorkoutContext = restoredDraft.context
    val restoredRestTimer = remember(sharedPrefs) {
        val state = sharedPrefs.getString("rest_timer_state", null)
            ?.takeIf(String::isNotBlank)
            ?.let { json -> runCatching { Gson().fromJson(json, PersistedRestTimerState::class.java) }.getOrNull() }
        normalizePersistedRestTimer(state, System.currentTimeMillis())
    }

    // Remove the exact squat/press draft that older production builds seeded, once.
    val legacyDemoCleanupComplete = remember(sharedPrefs) {
        sharedPrefs.getBoolean("legacy_demo_cleanup_complete", false)
    }
    val restoredLegacyDemoDraft = remember(restoredTrackedExercises, legacyDemoCleanupComplete) {
        val squat = restoredTrackedExercises.firstOrNull { it.name == "Barbell Squat" }
        val press = restoredTrackedExercises.firstOrNull { it.name == "Barbell Overhead Press" }
        !legacyDemoCleanupComplete &&
            restoredTrackedExercises.size == 2 &&
            squat != null &&
            press != null &&
            squat.sets.size == 3 &&
            press.sets.size == 3 &&
            squat.sets.all { it.previous.contains("125") || it.previous.contains("56.7") } &&
            press.sets.all { it.previous.contains("40") || it.previous.contains("18.1") }
    }

    LaunchedEffect(Unit) {
        val editor = sharedPrefs.edit().putBoolean("legacy_demo_cleanup_complete", true)
        if (restoredLegacyDemoDraft) {
            editor.remove("tracked_exercises")
                .remove("total_seconds")
                .remove("active_session_id")
        }
        editor.apply()
    }

    // Dynamic ticking timer state loaded from SharedPreferences.
    var totalSeconds by remember {
        mutableStateOf(if (restoredLegacyDemoDraft) 0 else sharedPrefs.getInt("total_seconds", 0))
    }
    var isTimerPaused by remember {
        mutableStateOf(
            if (restoredLegacyDemoDraft) true
            else sharedPrefs.getBoolean("is_timer_paused", restoredTrackedExercises.isEmpty())
        )
    }
    var isKgSelected by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_kg_selected", false))
    }

    fun convertWeightString(input: String, toKg: Boolean): String {
        if (input.isBlank() || input == "—") return "—"
        val numberRegex = """(\d+(?:\.\d+)?)""".toRegex()
        val match = numberRegex.find(input) ?: return input
        val numericValue = match.value.toDoubleOrNull() ?: return input
        
        val converted = if (toKg) {
            if (input.lowercase().contains("lbs")) {
                val value = numericValue / 2.20462
                String.format("%.1f kg", value)
            } else {
                String.format("%.1f kg", numericValue)
            }
        } else {
            if (input.lowercase().contains("kg")) {
                val value = numericValue * 2.20462
                String.format("%.1f lbs", value)
            } else {
                String.format("%.1f lbs", numericValue)
            }
        }
        return converted.replace(".0 kg", " kg").replace(".0 lbs", " lbs")
    }

    // Active workout draft only; completed sessions are persisted in Room.
    val trackedExercises = remember {
        mutableStateListOf<TrackedExercise>().apply {
            if (!restoredLegacyDemoDraft) addAll(restoredTrackedExercises)
        }
    }

    var activeWorkoutContext by remember { mutableStateOf(restoredWorkoutContext) }

    var activeSessionId by remember {
        mutableStateOf(
            if (restoredLegacyDemoDraft) UUID.randomUUID().toString()
            else sharedPrefs.getString("active_session_id", null) ?: UUID.randomUUID().toString()
        )
    }
    var isSavingWorkout by remember { mutableStateOf(false) }
    var hasSavedWorkout by remember { mutableStateOf(false) }
    var workoutSaveError by remember { mutableStateOf<String?>(null) }

    var pendingRoutineConflict by remember { mutableStateOf<GeneratedRoutine?>(null) }
    var pendingRoutineContext by remember { mutableStateOf<ActiveWorkoutContext?>(null) }
    var showManualDraftConflict by remember(initialManualStartRequested, restoredDraft.hasDraft) {
        mutableStateOf(initialManualStartRequested && restoredDraft.hasDraft)
    }

    // Auto-save changes reactively on state updates
    val stateKey = trackedExercises.map { exercise ->
        listOf(
            exercise.id,
            exercise.actualActivityReps.orEmpty(),
            exercise.actualElapsedSeconds.toString(),
            exercise.isActivityDone.toString()
        ) to exercise.sets.map { set ->
            Triple(set.weight, set.reps, set.isDone)
        }
    }
    LaunchedEffect(stateKey, totalSeconds, isKgSelected, isTimerPaused, activeSessionId, activeWorkoutContext) {
        try {
            val gson = Gson()
            val json = gson.toJson(trackedExercises.toList())
            sharedPrefs.edit()
                .putString("tracked_exercises", json)
                .putInt("total_seconds", totalSeconds)
                .putBoolean("is_kg_selected", isKgSelected)
                .putBoolean("is_timer_paused", isTimerPaused)
                .putString("active_session_id", activeSessionId)
                .apply {
                    if (activeWorkoutContext == null) remove("active_workout_context")
                    else putString("active_workout_context", gson.toJson(activeWorkoutContext))
                }
                .apply()
            
            // Sync weight preference settings with Firestore
            com.example.data.FirestoreSyncManager.saveWeightPreference(context, isKgSelected)
        } catch (e: Exception) {
            SafeLog.error("WorkoutPersistence", "Workout state persistence failed", e)
        }
    }

    LaunchedEffect(isTimerPaused) {
        while (!isTimerPaused) {
            delay(1000)
            totalSeconds++
        }
    }

    // Timer formatted string: HH:MM:SS
    val timerString = remember(totalSeconds) {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Modal / Overlay Dialog states
    var showCancelDialog by remember { mutableStateOf(false) }
    var showAddExercisesDialog by remember { mutableStateOf(false) }
    var showVictoryDialog by remember { mutableStateOf(false) }
    var completionSummary by remember { mutableStateOf<WorkoutCompletionSummary?>(null) }
    val expandedExerciseIds = remember { mutableStateMapOf<String, Boolean>() }
    var entranceVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entranceVisible = true }

    // One deadline-based rest state survives backgrounding and process restoration.
    var persistedRestTimer by remember { mutableStateOf(restoredRestTimer) }
    var restClockEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val restTimeRemaining = remainingRestSeconds(persistedRestTimer, restClockEpochMillis)
        .takeIf { persistedRestTimer.isActive && it > 0 }
    val isRestPaused = persistedRestTimer.isPaused
    var restAnnouncement by remember { mutableStateOf("") }
    var activeActivityId by remember {
        mutableStateOf(sharedPrefs.getString("active_activity_id", null))
    }
    var isActivityTimerPaused by remember {
        mutableStateOf(sharedPrefs.getBoolean("activity_timer_paused", true))
    }
    var showResetWorkoutConfirm by remember { mutableStateOf(false) }

    fun clearActiveWorkout() {
        val freshSessionId = UUID.randomUUID().toString()
        trackedExercises.clear()
        totalSeconds = 0
        isTimerPaused = true
        isSavingWorkout = false
        hasSavedWorkout = false
        workoutSaveError = null
        activeSessionId = freshSessionId
        activeWorkoutContext = null
        persistedRestTimer = skipPersistedRestTimer()
        restClockEpochMillis = System.currentTimeMillis()
        restAnnouncement = ""
        activeActivityId = null
        isActivityTimerPaused = true
        sharedPrefs.edit()
            .remove("tracked_exercises")
            .remove("active_workout_context")
            .remove("rest_timer_state")
            .remove("active_activity_id")
            .remove("activity_timer_paused")
            .putInt("total_seconds", 0)
            .putBoolean("is_timer_paused", true)
            .putString("active_session_id", freshSessionId)
            .apply()
    }

    fun startSelectedRoutine(routine: GeneratedRoutine, workoutContext: ActiveWorkoutContext): Boolean {
        val freshState = routine.freshActiveWorkoutState(
            selectedDayIndex = workoutContext.dayIndex,
            sourceRoutineId = workoutContext.sourceRoutineId,
            freshSessionId = UUID.randomUUID().toString()
        ) ?: return false
        trackedExercises.clear()
        trackedExercises.addAll(freshState.seed.exercises)
        activeWorkoutContext = freshState.seed.context
        totalSeconds = freshState.totalSeconds
        isTimerPaused = freshState.isWorkoutTimerPaused
        activeSessionId = freshState.sessionId
        hasSavedWorkout = false
        workoutSaveError = null
        persistedRestTimer = freshState.restTimer
        restClockEpochMillis = System.currentTimeMillis()
        restAnnouncement = ""
        activeActivityId = freshState.activityTimer.activeExerciseId
        isActivityTimerPaused = freshState.activityTimer.isPaused
        val gson = Gson()
        sharedPrefs.edit()
            .putString("tracked_exercises", gson.toJson(freshState.seed.exercises))
            .putString("active_workout_context", gson.toJson(freshState.seed.context))
            .putInt("total_seconds", freshState.totalSeconds)
            .putBoolean("is_timer_paused", freshState.isWorkoutTimerPaused)
            .putString("active_session_id", freshState.sessionId)
            .remove("rest_timer_state")
            .remove("active_activity_id")
            .putBoolean("activity_timer_paused", true)
            .apply()
        return true
    }

    LaunchedEffect(initialRoutine, initialRoutineSourceId, initialSelectedDayIndex) {
        val routine = initialRoutine ?: return@LaunchedEffect
        val selectedDayIndex = initialSelectedDayIndex ?: return@LaunchedEffect
        val selectedContext = routine.toActiveWorkoutContext(
            selectedDayIndex = selectedDayIndex,
            sourceRoutineId = initialRoutineSourceId
        ) ?: return@LaunchedEffect
        when (
            activeDraftRelationship(
                hasDraft = trackedExercises.isNotEmpty(),
                restoredContext = activeWorkoutContext,
                selectedContext = selectedContext
            )
        ) {
            ActiveDraftRelationship.NONE -> startSelectedRoutine(routine, selectedContext)
            ActiveDraftRelationship.EXACT_PLAN_DAY_MATCH -> Unit
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            ActiveDraftRelationship.MANUAL_OR_HISTORICAL_DRAFT -> {
                pendingRoutineConflict = routine
                pendingRoutineContext = selectedContext
            }
        }
    }

    LaunchedEffect(persistedRestTimer) {
        while (persistedRestTimer.isActive && !persistedRestTimer.isPaused) {
            delay(1_000)
            restClockEpochMillis = System.currentTimeMillis()
            if (remainingRestSeconds(persistedRestTimer, restClockEpochMillis) <= 0) {
                persistedRestTimer = skipPersistedRestTimer()
                restAnnouncement = "Rest complete"
            }
        }
    }

    LaunchedEffect(activeActivityId, isActivityTimerPaused) {
        while (activeActivityId != null && !isActivityTimerPaused) {
            delay(1_000)
            val index = trackedExercises.indexOfFirst { it.id == activeActivityId }
            if (index < 0 || trackedExercises[index].isActivityDone) {
                activeActivityId = null
                isActivityTimerPaused = true
            } else {
                val activity = trackedExercises[index]
                trackedExercises[index] = activity.copy(
                    actualElapsedSeconds = activity.actualElapsedSeconds + 1
                )
            }
        }
    }

    LaunchedEffect(activeActivityId, isActivityTimerPaused) {
        sharedPrefs.edit()
            .apply {
                if (activeActivityId == null) remove("active_activity_id")
                else putString("active_activity_id", activeActivityId)
            }
            .putBoolean("activity_timer_paused", isActivityTimerPaused)
            .apply()
    }

    LaunchedEffect(persistedRestTimer) {
        val gson = Gson()
        if (persistedRestTimer.isActive) {
            sharedPrefs.edit().putString("rest_timer_state", gson.toJson(persistedRestTimer)).apply()
        } else {
            sharedPrefs.edit().remove("rest_timer_state").apply()
        }
    }

    val sessionMetrics = calculateWorkoutSessionMetrics(
        exercises = trackedExercises,
        weightsAreKg = isKgSelected,
        sessionDurationSeconds = totalSeconds
    )
    val currentTrackedItemId = trackedExercises.firstOrNull { tracked ->
        !assessWorkoutCompletion(listOf(tracked)).isPlanComplete
    }?.id

    var showIncompleteWorkoutDialog by remember { mutableStateOf(false) }
    var showNoProgressDialog by remember { mutableStateOf(false) }

    fun saveCompletedWorkout() {
        val completed = trackedExercises
            .filter {
                it.phase == TrackedWorkoutPhase.MAIN_WORK &&
                    it.trackingType == TrackedItemType.STRENGTH
            }
            .flatMap { exercise ->
            exercise.sets
                .filter(::isCompletedWorkingSet)
                .map { set -> exercise to set }
        }

        if (viewModel == null) {
            workoutSaveError = "Workout storage is unavailable. Reopen the app and try again."
        } else if (canAttemptWorkoutSave(isSavingWorkout, hasSavedWorkout)) {
            isSavingWorkout = true
            workoutSaveError = null
            isTimerPaused = true

            val exerciseNames = completed.map { it.first.name }.distinct()
            val categories = completed.map { it.first.category }.distinct()
            val completedSummary = WorkoutCompletionSummary(
                title = exerciseNames.joinToString(", "),
                completedAt = System.currentTimeMillis(),
                durationSeconds = totalSeconds,
                exerciseNames = exerciseNames,
                completedSets = sessionMetrics.completedSets,
                volumeKg = sessionMetrics.volumeKg,
                calories = 0
            )

            viewModel.logWorkout(
                sessionId = activeSessionId,
                exerciseName = exerciseNames.joinToString(", "),
                category = categories.joinToString(", "),
                durationMinutes = totalSeconds / 60,
                durationSeconds = totalSeconds,
                calories = 0,
                completedSets = sessionMetrics.completedSets,
                liftingVolumeKg = sessionMetrics.volumeKg,
                completedExerciseSets = completedMainWorkingSetCounts(trackedExercises)
            ) { result ->
                isSavingWorkout = false
                when (result) {
                    WorkoutSaveResult.SAVED -> {
                        hasSavedWorkout = true
                        completionSummary = completedSummary
                        showVictoryDialog = true
                    }
                    WorkoutSaveResult.DUPLICATE -> {
                        hasSavedWorkout = true
                        completionSummary = completedSummary
                        showVictoryDialog = true
                        Toast.makeText(context, "Workout already saved", Toast.LENGTH_SHORT).show()
                    }
                    WorkoutSaveResult.ERROR -> {
                        isTimerPaused = false
                        workoutSaveError = "Workout could not be saved. Your draft is safe; try again."
                    }
                }
            }
        }
    }

    fun attemptFinishWorkout() {
        focusManager.clearFocus()
        when (workoutFinishDecision(assessWorkoutCompletion(trackedExercises))) {
            WorkoutFinishDecision.SAVE_COMPLETED_WORKOUT -> saveCompletedWorkout()
            WorkoutFinishDecision.SHOW_INCOMPLETE_OPTIONS -> showIncompleteWorkoutDialog = true
            WorkoutFinishDecision.SHOW_NO_PROGRESS_OPTIONS -> showNoProgressDialog = true
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = FitDesiSpacing.medium,
                        vertical = FitDesiSpacing.small
                    ),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(FitDesiDimensions.minimumTouchTarget)
                                .testTag("workout_back_btn")
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active workout",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 0.5.sp
                            )
                            AnimatedContent(
                                targetState = timerString,
                                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                                label = "sessionTimer"
                            ) { value ->
                                Text(
                                    text = value,
                                    fontFamily = InterFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            activeWorkoutContext?.let { workoutContext ->
                                Text(
                                    text = listOf(workoutContext.routineName, workoutContext.dayName, workoutContext.dayTitle)
                                        .filter(String::isNotBlank)
                                        .joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { showResetWorkoutConfirm = true },
                            modifier = Modifier
                                .size(FitDesiDimensions.minimumTouchTarget)
                                .testTag("workout_reset_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset workout"
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                isTimerPaused = !isTimerPaused
                                Toast.makeText(
                                    context,
                                    if (isTimerPaused) "Timer paused" else "Timer resumed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier
                                .size(FitDesiDimensions.minimumTouchTarget)
                                .semantics {
                                    stateDescription = if (isTimerPaused) "Paused" else "Running"
                                }
                        ) {
                            AnimatedContent(
                                targetState = isTimerPaused,
                                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                                label = "pauseResumeState"
                            ) { paused ->
                                Icon(
                                    imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (paused) "Resume workout timer" else "Pause workout timer",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (restTimeRemaining == null) {
                                    persistedRestTimer = startPersistedRestTimer(
                                        TRACK_WORKOUT_REST_SECONDS,
                                        System.currentTimeMillis()
                                    )
                                    restClockEpochMillis = System.currentTimeMillis()
                                    restAnnouncement = "Rest started"
                                } else {
                                    persistedRestTimer = skipPersistedRestTimer()
                                    restAnnouncement = "Rest skipped"
                                }
                            },
                            modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (restTimeRemaining == null) "Rest" else "${restTimeRemaining}s")
                        }
                        Button(
                            onClick = ::attemptFinishWorkout,
                            enabled = canAttemptWorkoutSave(isSavingWorkout, hasSavedWorkout),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                .testTag("workout_finish_btn")
                        ) {
                            AnimatedContent(
                                targetState = isSavingWorkout,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                                label = "finishState"
                            ) { saving ->
                                Text(if (saving) "Saving…" else "Finish workout")
                            }
                        }
                    }
                    AnimatedVisibility(visible = restTimeRemaining != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { stateDescription = "Rest timer active" },
                            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Rest ${restTimeRemaining ?: 0}s",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    persistedRestTimer = if (isRestPaused) {
                                        restAnnouncement = "Rest resumed"
                                        resumePersistedRestTimer(persistedRestTimer, now)
                                    } else {
                                        restAnnouncement = "Rest paused"
                                        pausePersistedRestTimer(persistedRestTimer, now)
                                    }
                                    restClockEpochMillis = now
                                },
                                modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            ) {
                                Text(if (isRestPaused) "Resume" else "Pause")
                            }
                            TextButton(
                                onClick = {
                                    persistedRestTimer = skipPersistedRestTimer()
                                    restAnnouncement = "Rest skipped"
                                },
                                modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            ) {
                                Text("Skip")
                            }
                        }
                    }
                    if (restAnnouncement.isNotBlank()) {
                        Text(
                            text = restAnnouncement,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    workoutSaveError?.let { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = ::attemptFinishWorkout,
                                modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .background(backgroundColor)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = FitDesiSpacing.medium,
                    vertical = FitDesiSpacing.small
                ),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
            ) {
            item(key = "session_metrics") {
                AnimatedVisibility(
                    visible = entranceVisible,
                    enter = fadeIn(tween(200)) +
                        androidx.compose.animation.slideInVertically(tween(200)) { it / 12 }
                ) {
                    ActiveWorkoutMetricsCard(
                        metrics = sessionMetrics
                    )
                }
            }
            item(key = "weight_unit") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WEIGHT UNIT",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = onSurfaceSecondaryColor,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, cardBorderColor, RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isKgSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable {
                                    if (!isKgSelected) {
                                        isKgSelected = true
                                        // Convert existing values in trackedExercises
                                        for (i in trackedExercises.indices) {
                                            val ex = trackedExercises[i]
                                            val updatedSets = ex.sets.map { set ->
                                                // Convert previous
                                                val newPrevious = convertWeightString(set.previous, toKg = true)
                                                // Convert entered weight if present
                                                val newWeight = if (set.weight.isNotBlank()) {
                                                    val wDouble = set.weight.toDoubleOrNull()
                                                    if (wDouble != null) {
                                                        String.format("%.1f", wDouble / 2.20462).replace(".0", "")
                                                    } else set.weight
                                                } else ""
                                                set.copy(previous = newPrevious, weight = newWeight)
                                            }
                                            trackedExercises[i] = ex.copy(sets = updatedSets)
                                        }
                                    }
                                }
                                .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                .semantics { selected = isKgSelected }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "KGS",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isKgSelected) Color.White else onSurfaceSecondaryColor
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isKgSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable {
                                    if (isKgSelected) {
                                        isKgSelected = false
                                        // Convert existing values in trackedExercises
                                        for (i in trackedExercises.indices) {
                                            val ex = trackedExercises[i]
                                            val updatedSets = ex.sets.map { set ->
                                                // Convert previous
                                                val newPrevious = convertWeightString(set.previous, toKg = false)
                                                // Convert entered weight if present
                                                val newWeight = if (set.weight.isNotBlank()) {
                                                    val wDouble = set.weight.toDoubleOrNull()
                                                    if (wDouble != null) {
                                                        String.format("%.1f", wDouble * 2.20462).replace(".0", "")
                                                    } else set.weight
                                                } else ""
                                                set.copy(previous = newPrevious, weight = newWeight)
                                            }
                                            trackedExercises[i] = ex.copy(sets = updatedSets)
                                        }
                                    }
                                }
                                .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                .semantics { selected = !isKgSelected }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "LBS",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (!isKgSelected) Color.White else onSurfaceSecondaryColor
                            )
                        }
                    }
                }
            }
                if (trackedExercises.isEmpty()) {
                    item(key = "track_workout_empty_state") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp))
                                .testTag("track_workout_empty_state"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FitnessCenter,
                                    contentDescription = null,
                                    tint = primaryBlue,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "No exercises added yet",
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = onSurfaceColor
                                )
                                Text(
                                    text = "Add an exercise to start tracking a real workout.",
                                    fontFamily = InterFontFamily,
                                    fontSize = 13.sp,
                                    color = onSurfaceSecondaryColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = trackedExercises,
                    key = { _, exercise -> exercise.id }
                ) { exIdx, exercise ->
                    val isExpanded = expandedExerciseIds[exercise.id]
                        ?: (exercise.id == currentTrackedItemId)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(tween(200))
                            .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp))
                            .testTag("tracked_exercise_${exercise.name}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            // Header Row for Exercise
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            expandedExerciseIds[exercise.id] = !isExpanded
                                        }
                                        .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = "Reorder",
                                        tint = onSurfaceSecondaryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = exercise.phase?.displayLabel.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = exercise.name,
                                            fontFamily = OswaldFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = onSurfaceColor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (activeWorkoutContext == null) {
                                        IconButton(
                                            onClick = { trackedExercises.removeAt(exIdx) },
                                            modifier = Modifier.size(FitDesiDimensions.minimumTouchTarget)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove ${exercise.name}",
                                                tint = errorColor.copy(alpha = 0.7f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            expandedExerciseIds[exercise.id] = !isExpanded
                                        },
                                        modifier = Modifier.size(FitDesiDimensions.minimumTouchTarget)
                                    ) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (isExpanded) "Collapse ${exercise.name}" else "Expand ${exercise.name}",
                                            tint = onSurfaceSecondaryColor
                                        )
                                    }
                                }
                            }

                            if (
                                isExpanded &&
                                exercise.trackingType == TrackedItemType.ACTIVITY
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = exercise.phase?.displayLabel.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                exercise.targetText
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { target ->
                                        Text(
                                            text = "Target: $target",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = onSurfaceSecondaryColor
                                        )
                                    }
                                if (exercise.activityPrescription?.mode == ActivityPrescriptionMode.REPETITIONS) {
                                    OutlinedTextField(
                                        value = exercise.actualActivityReps.orEmpty(),
                                        onValueChange = { value ->
                                            if (value.all(Char::isDigit)) {
                                                trackedExercises[exIdx] = exercise.copy(actualActivityReps = value)
                                            }
                                        },
                                        label = { Text("Actual reps") },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("actual_activity_reps_${exercise.id}")
                                            .semantics {
                                                stateDescription = if (exercise.actualActivityReps.isNullOrBlank()) {
                                                    "Actual reps, empty"
                                                } else {
                                                    "Actual reps, ${exercise.actualActivityReps}"
                                                }
                                            }
                                    )
                                }
                                if (exercise.activityPrescription?.mode == ActivityPrescriptionMode.DURATION_SECONDS) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                                    ) {
                                        Text(
                                            text = "Elapsed ${formatSessionDuration(exercise.actualElapsedSeconds)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilledTonalButton(
                                        onClick = {
                                                val updated = toggleActivityTimer(
                                                    ActivityTimerState(activeActivityId, isActivityTimerPaused),
                                                    exercise.id
                                                )
                                                activeActivityId = updated.activeExerciseId
                                                isActivityTimerPaused = updated.isPaused
                                            },
                                            modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                        ) {
                                            Icon(
                                                imageVector = if (activeActivityId == exercise.id && !isActivityTimerPaused) {
                                                    Icons.Default.Pause
                                                } else {
                                                    Icons.Default.PlayArrow
                                                },
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (activeActivityId == exercise.id && !isActivityTimerPaused) "Pause" else "Start")
                                        }
                                    }
                                }
                                exercise.instructions
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { instructions ->
                                        Text(
                                            text = instructions,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = onSurfaceSecondaryColor
                                        )
                                    }
                                exercise.safetyNote
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { safety ->
                                        Text(
                                            text = "Safety: $safety",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        val newDone = !exercise.isActivityDone
                                        trackedExercises[exIdx] = exercise.copy(
                                            isActivityDone = newDone
                                        )
                                        if (newDone && activeActivityId == exercise.id) {
                                            activeActivityId = null
                                            isActivityTimerPaused = true
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                        .semantics {
                                            role = Role.Checkbox
                                            selected = exercise.isActivityDone
                                            stateDescription = if (exercise.isActivityDone) {
                                                "Completed"
                                            } else {
                                                "Not completed"
                                            }
                                        }
                                ) {
                                    Icon(
                                        imageVector = if (exercise.isActivityDone) {
                                            Icons.Default.CheckCircle
                                        } else {
                                            Icons.Default.RadioButtonUnchecked
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (exercise.isActivityDone) "Completed" else "Mark complete")
                                }
                            } else if (isExpanded) {
                            Spacer(modifier = Modifier.height(10.dp))

                            val workingSets = exercise.sets.filterNot(TrackedSet::isRampUp)
                            val workingTarget = workingSets.firstOrNull()?.targetReps.orEmpty()
                            val workingRest = workingSets.firstOrNull()?.let {
                                automaticRestSeconds(exercise, it)
                            }
                            Text(
                                text = buildString {
                                    append("Target: ${exercise.targetSets} × ")
                                    append(workingTarget.ifBlank { "actual reps" })
                                    workingRest?.let { append(" • Rest: ${it}s") }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = onSurfaceSecondaryColor
                            )

                            val useStackedSetCards = shouldUseStackedSetCards(
                                screenWidthDp = LocalConfiguration.current.screenWidthDp,
                                fontScale = LocalDensity.current.fontScale
                            )
                            if (useStackedSetCards) {
                                exercise.sets.forEachIndexed { setIdx, set ->
                                    val typeOrdinal = exercise.sets
                                        .take(setIdx + 1)
                                        .count { it.isRampUp == set.isRampUp }
                                    CompactTrackedSetCard(
                                        set = set,
                                        ordinal = typeOrdinal,
                                        weightUnit = if (isKgSelected) "kg" else "lbs",
                                        onWeightChange = { value ->
                                            val updatedSets = exercise.sets.toMutableList().apply {
                                                this[setIdx] = set.copy(weight = value)
                                            }
                                            trackedExercises[exIdx] = exercise.copy(sets = updatedSets)
                                        },
                                        onRepsChange = { value ->
                                            val updatedSets = exercise.sets.toMutableList().apply {
                                                this[setIdx] = set.copy(reps = value)
                                            }
                                            trackedExercises[exIdx] = exercise.copy(sets = updatedSets)
                                        },
                                        onToggleDone = {
                                            if (set.reps.toIntOrNull()?.let { it > 0 } != true) {
                                                Toast.makeText(context, "Enter actual reps first!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val newDone = !set.isDone
                                                val updatedSets = exercise.sets.toMutableList().apply {
                                                    this[setIdx] = set.copy(isDone = newDone)
                                                }
                                                trackedExercises[exIdx] = exercise.copy(sets = updatedSets)
                                                if (newDone) {
                                                    automaticRestSeconds(exercise, set)?.let { seconds ->
                                                        val now = System.currentTimeMillis()
                                                        persistedRestTimer = startPersistedRestTimer(seconds, now)
                                                        restClockEpochMillis = now
                                                        restAnnouncement = "Rest started"
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                            // Set Table Headers
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SET", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = onSurfaceSecondaryColor, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                                Text("LAST", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = onSurfaceSecondaryColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text(if (isKgSelected) "ACTUAL KG" else "ACTUAL LBS", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = onSurfaceSecondaryColor, modifier = Modifier.weight(1.1f), textAlign = TextAlign.Center)
                                Text("ACTUAL REPS", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = onSurfaceSecondaryColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.width(48.dp)) // Done space
                            }

                            // Sets Rows
                            exercise.sets.forEachIndexed { setIdx, set ->
                                val typeOrdinal = exercise.sets
                                    .take(setIdx + 1)
                                    .count { it.isRampUp == set.isRampUp }
                                Text(
                                    text = if (set.isRampUp) {
                                        buildString {
                                            append("Ramp-up $typeOrdinal • Target: ${set.targetReps.orEmpty()} reps")
                                            set.rampUpLoadCue?.let { cue ->
                                                append(" • ${cue.name.lowercase().replace('_', ' ')}")
                                            }
                                            append(" • Rest: ${resolveStrengthRestSeconds(set.prescribedRestSeconds)}s")
                                        }
                                    } else {
                                        "Working set $typeOrdinal • Target: ${set.targetReps.orEmpty().ifBlank { "not specified" }}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (set.isRampUp) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        onSurfaceSecondaryColor
                                    }
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .testTag("tracked_set_context_${set.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Set Number
                                    Box(
                                        modifier = Modifier
                                            .width(36.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (set.isRampUp) "W$typeOrdinal" else "$typeOrdinal",
                                            fontFamily = InterFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = onSurfaceColor
                                        )
                                    }

                                    // Previous
                                    Text(
                                        text = set.previous,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        color = onSurfaceSecondaryColor,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center
                                    )

                                    // Weight input (BasicTextField styled as beautiful card input)
                                    Box(
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, if (set.isDone) successColor.copy(alpha = 0.5f) else cardBorderColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        BasicTextField(
                                            value = set.weight,
                                            onValueChange = { newVal ->
                                                val updatedSets = exercise.sets.toMutableList().apply {
                                                    this[setIdx] = set.copy(weight = newVal)
                                                }
                                                trackedExercises[exIdx] = exercise.copy(sets = updatedSets)
                                            },
                                            textStyle = TextStyle(
                                                fontFamily = InterFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = onSurfaceColor,
                                                textAlign = TextAlign.Center
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Decimal,
                                                imeAction = ImeAction.Next
                                            ),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                                            maxLines = 1,
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("actual_weight_${set.id}")
                                                .semantics {
                                                    stateDescription = if (set.weight.isBlank()) "Actual weight, empty" else "Actual weight, ${set.weight}"
                                                }
                                        )
                                        if (set.weight.isEmpty()) {
                                            Text(
                                                text = "—",
                                                fontFamily = InterFontFamily,
                                                fontSize = 12.sp,
                                                color = onSurfaceSecondaryColor,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    // Reps input
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, if (set.isDone) successColor.copy(alpha = 0.5f) else cardBorderColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        BasicTextField(
                                            value = set.reps,
                                            onValueChange = { newVal ->
                                                val updatedSets = exercise.sets.toMutableList().apply {
                                                    this[setIdx] = set.copy(reps = newVal)
                                                }
                                                trackedExercises[exIdx] = exercise.copy(sets = updatedSets)
                                            },
                                            textStyle = TextStyle(
                                                fontFamily = InterFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = onSurfaceColor,
                                                textAlign = TextAlign.Center
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Next
                                            ),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                                            maxLines = 1,
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("actual_reps_${set.id}")
                                                .semantics {
                                                    stateDescription = if (set.reps.isBlank()) "Actual reps, empty" else "Actual reps, ${set.reps}"
                                                }
                                        )
                                        if (set.reps.isEmpty()) {
                                            Text(
                                                text = "—",
                                                fontFamily = InterFontFamily,
                                                fontSize = 12.sp,
                                                color = onSurfaceSecondaryColor,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    // Accessible completion control for this real set.
                                    val isSetFilled = set.reps.toIntOrNull()?.let { it > 0 } == true
                                    val doneBtnColor by animateColorAsState(
                                        targetValue = if (set.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        label = "done_color_anim"
                                    )

                                    Box(
                                        modifier = Modifier
                                            .width(48.dp)
                                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(doneBtnColor)
                                            .clickable {
                                                if (!isSetFilled) {
                                                    Toast.makeText(context, "Enter actual reps first!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val newDone = !set.isDone
                                                    val updatedSets = exercise.sets.toMutableList().apply {
                                                        this[setIdx] = set.copy(isDone = newDone)
                                                    }
                                                    trackedExercises[exIdx] = exercise.copy(sets = updatedSets)

                                                    if (newDone) {
                                                        automaticRestSeconds(exercise, set)?.let { seconds ->
                                                            val now = System.currentTimeMillis()
                                                            persistedRestTimer = startPersistedRestTimer(seconds, now)
                                                            restClockEpochMillis = now
                                                            restAnnouncement = "Rest started"
                                                        }
                                                    }
                                                }
                                            }
                                            .semantics {
                                                role = Role.Checkbox
                                                selected = set.isDone
                                                stateDescription = if (set.isDone) "Completed" else "Not completed"
                                            }
                                            .testTag("done_btn_${exercise.name}_$setIdx"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AnimatedContent(
                                            targetState = set.isDone,
                                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                                            label = "setCompletion"
                                        ) { completed ->
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = if (completed) "Set ${setIdx + 1} completed" else "Mark set ${setIdx + 1} complete",
                                                tint = if (completed) Color.White else onSurfaceSecondaryColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Add Set Button
                            Button(
                                onClick = {
                                    val unitSuffix = if (isKgSelected) " kg" else " lbs"
                                    val lastPerformed = exercise.sets.lastOrNull {
                                        !it.isRampUp && it.isDone && it.reps.isNotBlank()
                                    }
                                    val previousStr = lastPerformed?.let { previous ->
                                        buildString {
                                            append(previous.weight.takeIf(String::isNotBlank)?.plus(unitSuffix) ?: "Bodyweight")
                                            append(" × ${previous.reps}")
                                        }
                                    } ?: "—"
                                    trackedExercises[exIdx] = exercise.withAdditionalBlankWorkingSet(
                                        previous = previousStr
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = onSurfaceColor
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                                    .border(1.dp, cardBorderColor, RoundedCornerShape(8.dp))
                                    .testTag("add_set_btn_${exercise.name}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("Add set", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            }
                        }
                    }
                }
            // Session-level controls remain clear of the scrolling set editor.
            item(key = "session_controls") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                // Cancel Button
                OutlinedButton(
                    onClick = { showCancelDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("workout_cancel_btn")
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Add Exercises Button
                Button(
                    onClick = { showAddExercisesDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                        .testTag("workout_add_exercise_btn")
                ) {
                    Text(
                        text = "Add Exercises",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }
        }
        }
    }
    }

    if (showManualDraftConflict) {
        AlertDialog(
            onDismissRequest = {
                showManualDraftConflict = false
                onBack()
            },
            modifier = Modifier.testTag("manual_draft_conflict_dialog"),
            title = { Text("Existing workout draft", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A workout is already in progress. Resume it, or explicitly discard it before starting an empty manual workout."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                ) {
                    Button(
                        onClick = { showManualDraftConflict = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("manual_draft_conflict_resume")
                    ) {
                        Text("Resume existing workout")
                    }
                    TextButton(
                        onClick = {
                            clearActiveWorkout()
                            showManualDraftConflict = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("manual_draft_conflict_discard")
                    ) {
                        Text(
                            "Discard existing draft and start manual workout",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(
                        onClick = {
                            showManualDraftConflict = false
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("manual_draft_conflict_cancel")
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = {}
        )
    }

    if (pendingRoutineConflict != null && pendingRoutineContext != null) {
        AlertDialog(
            onDismissRequest = {
                pendingRoutineConflict = null
                pendingRoutineContext = null
                onBack()
            },
            title = { Text("Active workout in progress", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Another workout draft is already saved. Resume it, or explicitly discard it and start the selected plan day."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                ) {
                    Button(
                        onClick = {
                            val routine = pendingRoutineConflict
                            val workoutContext = pendingRoutineContext
                            if (routine != null && workoutContext != null) {
                                startSelectedRoutine(routine, workoutContext)
                                pendingRoutineConflict = null
                                pendingRoutineContext = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("draft_conflict_discard")
                    ) {
                        Text(
                            "Discard existing draft and start ${pendingRoutineContext?.dayName?.ifBlank { pendingRoutineContext?.dayTitle.orEmpty() }.orEmpty()}"
                        )
                    }
                    TextButton(
                        onClick = {
                            pendingRoutineConflict = null
                            pendingRoutineContext = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("draft_conflict_resume")
                    ) {
                        Text("Resume existing workout")
                    }
                    TextButton(
                        onClick = {
                            pendingRoutineConflict = null
                            pendingRoutineContext = null
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                            .testTag("draft_conflict_cancel")
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = {}
        )
    }

    if (showIncompleteWorkoutDialog) {
        AlertDialog(
            onDismissRequest = { showIncompleteWorkoutDialog = false },
            title = { Text("Workout incomplete", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You completed ${sessionMetrics.completedSets} of ${sessionMetrics.totalSets} main working sets. " +
                        "Leaving now will keep the active draft and will not create a completed workout history entry."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                ) {
                    Button(
                        onClick = { showIncompleteWorkoutDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                    ) { Text("Continue workout") }
                    OutlinedButton(
                        onClick = {
                            showIncompleteWorkoutDialog = false
                            isTimerPaused = true
                            isActivityTimerPaused = true
                            val gson = Gson()
                            sharedPrefs.edit()
                                .putString("tracked_exercises", gson.toJson(trackedExercises.toList()))
                                .putInt("total_seconds", totalSeconds)
                                .putBoolean("is_timer_paused", true)
                                .putString("active_session_id", activeSessionId)
                                .putBoolean("activity_timer_paused", true)
                                .apply {
                                    if (activeWorkoutContext == null) remove("active_workout_context")
                                    else putString("active_workout_context", gson.toJson(activeWorkoutContext))
                                    if (persistedRestTimer.isActive) {
                                        putString("rest_timer_state", gson.toJson(persistedRestTimer))
                                    } else {
                                        remove("rest_timer_state")
                                    }
                                }
                                .apply()
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                    ) { Text("Save draft & exit") }
                    TextButton(
                        onClick = {
                            showIncompleteWorkoutDialog = false
                            clearActiveWorkout()
                            persistedRestTimer = skipPersistedRestTimer()
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                    ) { Text("Discard session", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {}
        )
    }

    if (showNoProgressDialog) {
        AlertDialog(
            onDismissRequest = { showNoProgressDialog = false },
            title = { Text("No workout progress yet", fontWeight = FontWeight.Bold) },
            text = { Text("Nothing will be saved to workout history.") },
            confirmButton = {
                TextButton(
                    onClick = { showNoProgressDialog = false },
                    modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                ) { Text("Continue workout") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNoProgressDialog = false
                        clearActiveWorkout()
                        persistedRestTimer = skipPersistedRestTimer()
                        onBack()
                    },
                    modifier = Modifier.heightIn(min = FitDesiDimensions.minimumTouchTarget)
                ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    // --- DIALOG 1: CANCEL ALERT ---
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Workout?", fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to discard your current workout session? Your stats won't be saved.", fontFamily = InterFontFamily) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        clearActiveWorkout()
                        onBack()
                    }
                ) {
                    Text("DISCARD", color = Color.Red, fontWeight = FontWeight.Bold, fontFamily = InterFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("KEEP TRAINING", fontFamily = InterFontFamily)
                }
            }
        )
    }

    // --- DIALOG 2: ADD EXERCISES DIALOG ---
    if (showAddExercisesDialog) {
        var searchQuery by remember { mutableStateOf("") }
        val catalogueContent = remember(exerciseUiState, searchQuery) {
            trackWorkoutCatalogueContent(exerciseUiState, searchQuery)
        }

        Dialog(
            onDismissRequest = { showAddExercisesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.8f)
                    .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Add Exercises",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search exercises...", fontFamily = InterFontFamily, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Exercise List scrollable
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (val content = catalogueContent) {
                            TrackWorkoutCatalogueContent.Loading -> item {
                                FitDesiLoadingState(
                                    message = "Loading exercise library…",
                                    modifier = Modifier.testTag("track_exercise_catalogue_loading")
                                )
                            }

                            TrackWorkoutCatalogueContent.EmptyCatalogue -> item {
                                FitDesiEmptyState(
                                    title = "No exercises available",
                                    message = "The local exercise catalogue is empty.",
                                    modifier = Modifier.testTag("track_exercise_catalogue_empty")
                                )
                            }

                            TrackWorkoutCatalogueContent.NoMatches -> item {
                                FitDesiEmptyState(
                                    title = "No matches",
                                    message = "Try a different exercise name.",
                                    modifier = Modifier.testTag("track_exercise_catalogue_no_matches")
                                )
                            }

                            is TrackWorkoutCatalogueContent.Error -> item {
                                FitDesiErrorState(
                                    title = "Exercise library unavailable",
                                    message = content.message,
                                    actionLabel = if (content.retryAvailable) "Retry" else null,
                                    onAction = if (content.retryAvailable) exerciseViewModel::loadOfflineExercises else null,
                                    modifier = Modifier.testTag("track_exercise_catalogue_error")
                                )
                            }

                            is TrackWorkoutCatalogueContent.Ready -> {
                                // This list is local exercise data, not live AI analysis.
                                if (searchQuery.isBlank()) {
                                    item {
                                        Text(
                                            text = "LOCAL EXERCISE LIBRARY",
                                            fontFamily = OswaldFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = onSurfaceSecondaryColor,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                // Exercise list
                                items(
                                    items = content.exercises,
                                    key = { it.id }
                                ) { exercise ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                // Add to active session tracked list
                                                val selection = selectTrackWorkoutExercise(
                                                    exercise = exercise,
                                                    hasTrackedExercises = trackedExercises.isNotEmpty(),
                                                    totalSeconds = totalSeconds,
                                                    isTimerPaused = isTimerPaused
                                                )
                                                totalSeconds = selection.totalSeconds
                                                isTimerPaused = selection.isTimerPaused
                                                trackedExercises.add(selection.exercise)
                                                Toast.makeText(context, "${exercise.name} added!", Toast.LENGTH_SHORT).show()
                                                showAddExercisesDialog = false
                                            }
                                            .testTag("track_exercise_option_${exercise.id}")
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = exercise.name,
                                                fontFamily = OswaldFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = onSurfaceColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = (exercise.category ?: exercise.bodyPart ?: "Other") +
                                                    " • " + (exercise.equipment ?: "Bodyweight"),
                                                fontFamily = InterFontFamily,
                                                fontSize = 11.sp,
                                                color = onSurfaceSecondaryColor
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.AddCircle,
                                            contentDescription = "Add",
                                            tint = primaryBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showAddExercisesDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showVictoryDialog && completionSummary != null) {
        WorkoutCompletionDialog(
            summary = completionSummary!!,
            onDone = {
                showVictoryDialog = false
                clearActiveWorkout()
                onBack()
            }
        )
    }

    if (showResetWorkoutConfirm) {
        AlertDialog(
            onDismissRequest = { showResetWorkoutConfirm = false },
            title = {
                Text(
                    text = "Reset Active Workout?",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to reset your active workout session? This will clear all entered weight/reps and reset the timer.",
                    fontFamily = InterFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWorkoutConfirm = false
                        clearActiveWorkout()
                        persistedRestTimer = skipPersistedRestTimer()
                        activeActivityId = null
                        isActivityTimerPaused = true
                        Toast.makeText(context, "Workout session has been reset!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(
                        text = "RESET WORKOUT",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetWorkoutConfirm = false }
                ) {
                    Text(
                        text = "CANCEL",
                        fontFamily = InterFontFamily
                    )
                }
            }
        )
    }
}

@Composable
private fun CompactTrackedSetCard(
    set: TrackedSet,
    ordinal: Int,
    weightUnit: String,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onToggleDone: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FitDesiSpacing.small)
            .testTag("tracked_set_context_${set.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Text(
                text = buildString {
                    append(if (set.isRampUp) "RAMP-UP / WARM-UP SET $ordinal" else "WORKING SET $ordinal")
                    append(" • Target: ${set.targetReps.orEmpty().ifBlank { "not specified" }} reps")
                    set.rampUpLoadCue?.let { cue ->
                        append(" • ${cue.name.lowercase().replace('_', ' ')}")
                    }
                    append(" • Rest: ${resolveStrengthRestSeconds(set.prescribedRestSeconds)}s")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (set.isRampUp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            if (set.previous.isNotBlank() && set.previous != "—") {
                Text(
                    text = "Last set: ${set.previous}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = set.weight,
                onValueChange = onWeightChange,
                label = { Text("Actual weight ($weightUnit)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("actual_weight_${set.id}")
                    .semantics {
                        stateDescription = if (set.weight.isBlank()) "Actual weight, empty" else "Actual weight, ${set.weight}"
                    }
            )
            OutlinedTextField(
                value = set.reps,
                onValueChange = onRepsChange,
                label = { Text("Actual reps") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("actual_reps_${set.id}")
                    .semantics {
                        stateDescription = if (set.reps.isBlank()) "Actual reps, empty" else "Actual reps, ${set.reps}"
                    }
            )
            FilledTonalButton(
                onClick = onToggleDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                    .semantics {
                        role = Role.Checkbox
                        selected = set.isDone
                        stateDescription = if (set.isDone) "Completed" else "Not completed"
                    }
                    .testTag("compact_set_done_${set.id}")
            ) {
                Icon(
                    imageVector = if (set.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null
                )
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text(if (set.isDone) "Done" else "Mark set done")
            }
        }
    }
}

@Composable
private fun ActiveWorkoutMetricsCard(
    metrics: WorkoutSessionMetrics,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = metrics.progress.coerceIn(0f, 1f),
        animationSpec = tween(FitDesiMotion.emphasis),
        label = "workoutProgress"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${metrics.completedSets}/${metrics.totalSets} sets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .semantics {
                        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                            current = metrics.progress,
                            range = 0f..1f
                        )
                }
            )
            if (metrics.totalGeneralWarmupActivities > 0) {
                PhaseProgressRow(
                    label = "Warm-up",
                    value = if (metrics.generalWarmupCompleted) "Complete" else "Not complete"
                )
            }
            if (metrics.totalPreparationActivities > 0) {
                PhaseProgressRow(
                    label = "Preparation",
                    value = "${metrics.completedPreparationActivities}/${metrics.totalPreparationActivities}"
                )
            }
            PhaseProgressRow(
                label = "Main work",
                value = "${metrics.completedSets}/${metrics.totalSets} sets"
            )
            if (metrics.totalRampUpSets > 0) {
                PhaseProgressRow(
                    label = "Ramp-up",
                    value = "${metrics.completedRampUpSets}/${metrics.totalRampUpSets}"
                )
            }
            if (metrics.totalCooldownActivities > 0) {
                PhaseProgressRow(
                    label = "Cooldown",
                    value = "${metrics.completedCooldownActivities}/${metrics.totalCooldownActivities}"
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SessionMetric("Exercises", "${metrics.completedExerciseCount}/${metrics.exerciseCount}", Modifier.weight(1f))
                SessionMetric("Completed sets", metrics.completedSets.toString(), Modifier.weight(1f))
                SessionMetric("Volume", "${metrics.volumeKg.roundToInt()} kg", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhaseProgressRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun WorkoutCompletionDialog(
    summary: WorkoutCompletionSummary,
    onDone: () -> Unit
) {
    val completedDate = remember(summary.completedAt) {
        SimpleDateFormat("EEE, d MMM - h:mm a", Locale.getDefault()).format(Date(summary.completedAt))
    }
    var entered by remember(summary.completedAt) { mutableStateOf(false) }
    LaunchedEffect(summary.completedAt) { entered = true }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Workout complete",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = "Saved in workout history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDone,
                        modifier = Modifier.size(FitDesiDimensions.minimumTouchTarget)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close workout summary")
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(tween(200)) { it / 10 }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(FitDesiSpacing.large),
                                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Workout saved",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                    Text(
                                        text = "Session saved",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = summary.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = completedDate,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                            CompletionMetricTile(
                                label = "Duration",
                                value = formatSessionDuration(summary.durationSeconds),
                                icon = Icons.Default.Timer,
                                modifier = Modifier.weight(1f)
                            )
                            CompletionMetricTile(
                                label = "Volume",
                                value = "${summary.volumeKg.roundToInt()} kg",
                                icon = Icons.Default.FitnessCenter,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                            CompletionMetricTile(
                                label = "Exercises",
                                value = summary.exerciseNames.size.toString(),
                                icon = Icons.Default.List,
                                modifier = Modifier.weight(1f)
                            )
                            CompletionMetricTile(
                                label = "Completed sets",
                                value = summary.completedSets.toString(),
                                icon = Icons.Default.CheckCircle,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (summary.calories > 0) {
                            CompletionMetricTile(
                                label = "Calories",
                                value = "${summary.calories} kcal",
                                icon = Icons.Default.LocalFireDepartment,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            text = "Exercise breakdown",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }
                        )
                        summary.exerciseNames.forEachIndexed { index, exercise ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(FitDesiSpacing.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = exercise,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(Icons.Default.Check, contentDescription = "Completed", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FitDesiDimensions.minimumTouchTarget)
                        .testTag("victory_close_btn")
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun CompletionMetricTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

internal fun formatSessionDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

@Preview(name = "Workout completion compact", widthDp = 320, heightDp = 800, showBackground = true)
@Composable
private fun WorkoutCompletionCompactPreview() {
    MaterialTheme {
        WorkoutCompletionDialog(
            summary = WorkoutCompletionSummary(
                title = "Dumbbell press, Romanian deadlift, seated row",
                completedAt = 0L,
                durationSeconds = 2640,
                exerciseNames = listOf("Dumbbell press", "Romanian deadlift", "Seated row"),
                completedSets = 9,
                volumeKg = 3840.0,
                calories = 0
            ),
            onDone = {}
        )
    }
}

@Preview(name = "Workout completion large text", widthDp = 360, heightDp = 800, fontScale = 1.5f, showBackground = true)
@Composable
private fun WorkoutCompletionLargeTextPreview() {
    MaterialTheme {
        WorkoutCompletionDialog(
            summary = WorkoutCompletionSummary(
                title = "Dumbbell press, Romanian deadlift, seated row",
                completedAt = 0L,
                durationSeconds = 2640,
                exerciseNames = listOf("Dumbbell press", "Romanian deadlift", "Seated row"),
                completedSets = 9,
                volumeKg = 3840.0,
                calories = 0
            ),
            onDone = {}
        )
    }
}
