package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedRoutine
import com.example.data.SavedRoutineResult
import com.example.exercise.CanonicalRoutineCatalogueResult
import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.CanonicalRoutineSelectionException
import com.example.exercise.CanonicalRoutineSelectionRequest
import com.example.exercise.CanonicalRoutineSelectionResult
import com.example.exercise.Exercise
import com.example.exercise.ExerciseRepositoryProvider
import com.example.exercise.canonicalRoutineTargetSnapshot
import com.example.exercise.programming.DeterministicStructuredWorkoutComposer
import com.example.exercise.programming.ExerciseProgrammingCatalogue
import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingRepositoryProvider
import com.example.exercise.programming.ExerciseProgrammingState
import com.example.exercise.programming.StructuredWorkoutCompositionRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROUTINE_STEP_COUNT = 6
private const val FULL_GYM_EQUIPMENT_OPTION = "Full gym"
private const val PROGRAMMING_DEGRADED_MESSAGE =
    "Enhanced workout programming is temporarily unavailable. Generate to use the basic main-exercise workout."

internal sealed interface RoutineGenerationReadiness {
    data object Blocked : RoutineGenerationReadiness
    data class Structured(
        val records: List<ExerciseProgrammingMetadata>
    ) : RoutineGenerationReadiness
    data class Degraded(val message: String) : RoutineGenerationReadiness
}

internal val RoutineGenerationReadiness.canGenerate: Boolean
    get() = this !is RoutineGenerationReadiness.Blocked

internal val RoutineGenerationReadiness.programmingRecords: List<ExerciseProgrammingMetadata>
    get() = (this as? RoutineGenerationReadiness.Structured)?.records.orEmpty()

internal val RoutineGenerationReadiness.degradedMessage: String?
    get() = (this as? RoutineGenerationReadiness.Degraded)?.message

internal fun routineGenerationReadiness(
    canonicalReady: Boolean,
    programmingState: ExerciseProgrammingState
): RoutineGenerationReadiness {
    if (!canonicalReady) return RoutineGenerationReadiness.Blocked
    return when (programmingState) {
        ExerciseProgrammingState.NotLoaded,
        ExerciseProgrammingState.Loading -> RoutineGenerationReadiness.Blocked
        is ExerciseProgrammingState.Ready -> if (programmingState.records.isEmpty()) {
            RoutineGenerationReadiness.Degraded(PROGRAMMING_DEGRADED_MESSAGE)
        } else {
            RoutineGenerationReadiness.Structured(programmingState.records)
        }
        is ExerciseProgrammingState.Error -> RoutineGenerationReadiness.Degraded(PROGRAMMING_DEGRADED_MESSAGE)
    }
}

internal val ROUTINE_EQUIPMENT_OPTIONS = listOf(
    "Bodyweight",
    "Dumbbells",
    "Barbell and rack",
    "Resistance bands",
    FULL_GYM_EQUIPMENT_OPTION
)

internal fun toggleRoutineEquipment(
    selected: Set<String>,
    option: String
): Set<String> {
    if (option !in ROUTINE_EQUIPMENT_OPTIONS) return selected.toSet()
    if (option == FULL_GYM_EQUIPMENT_OPTION) {
        return if (option in selected) emptySet() else setOf(option)
    }

    val individualSelections = selected - FULL_GYM_EQUIPMENT_OPTION
    return if (option in individualSelections) {
        individualSelections - option
    } else {
        individualSelections + option
    }
}

internal fun routineEquipmentSummary(selected: Set<String>): String =
    ROUTINE_EQUIPMENT_OPTIONS.filter(selected::contains).joinToString(", ")

internal fun hasRoutineEquipmentSelection(selected: Set<String>): Boolean =
    selected.any(ROUTINE_EQUIPMENT_OPTIONS::contains)

internal data class RoutineBuilderInput(
    val name: String,
    val notes: String,
    val sessionMinutes: Int?,
    val level: String?,
    val goal: String?,
    val daysPerWeek: Int?,
    val split: String?,
    val equipment: Set<String>
)

internal data class RoutineBackDestination(
    val step: Int,
    val showResult: Boolean,
    val exitBuilder: Boolean
)

internal fun previousRoutineDestination(
    currentStep: Int,
    showingResult: Boolean
): RoutineBackDestination = when {
    showingResult -> RoutineBackDestination(ROUTINE_STEP_COUNT, showResult = false, exitBuilder = false)
    currentStep > 1 -> RoutineBackDestination(currentStep - 1, showResult = false, exitBuilder = false)
    else -> RoutineBackDestination(1, showResult = false, exitBuilder = true)
}

internal fun createLocalRoutine(
    input: RoutineBuilderInput,
    canonicalExercises: List<Exercise>,
    selector: CanonicalRoutineExerciseSelector,
    programmingRecords: List<ExerciseProgrammingMetadata> = emptyList(),
    composer: DeterministicStructuredWorkoutComposer = DeterministicStructuredWorkoutComposer()
): GeneratedRoutine {
    val level = requireNotNull(input.level)
    val goal = requireNotNull(input.goal)
    val daysPerWeek = requireNotNull(input.daysPerWeek)
    val split = requireNotNull(input.split)
    val equipment = input.equipment
    val sessionMinutes = requireNotNull(input.sessionMinutes)
    require(input.name.isNotBlank())
    require(daysPerWeek in 2..6)
    require(hasRoutineEquipmentSelection(equipment))

    val focusCycle = when (split) {
        "Full Body" -> listOf("Full Body")
        "Upper / Lower" -> listOf("Upper Body", "Lower Body")
        "Push / Pull / Legs" -> listOf("Push", "Pull", "Legs")
        else -> listOf("Chest & Triceps", "Back & Biceps", "Legs", "Shoulders & Core", "Full Body", "Conditioning")
    }
    val baseSets = when (goal) {
        "Strength" -> 4
        "Muscle Gain" -> 3
        "Fat Loss" -> 3
        "Endurance" -> 3
        else -> 3
    }
    val sets = when (level) {
        "Beginner" -> (baseSets - 1).coerceAtLeast(2)
        "Advanced" -> (baseSets + 1).coerceAtMost(5)
        else -> baseSets
    }
    val reps = when (goal) {
        "Strength" -> "4-6"
        "Muscle Gain" -> "8-12"
        "Fat Loss" -> "12-15"
        "Endurance" -> "15-20"
        else -> "8-12"
    }

    val description = buildString {
        append("$level $goal routine using ${routineEquipmentSummary(equipment)}, planned for about $sessionMinutes minutes per session.")
        if (input.notes.isNotBlank()) append(" Notes: ${input.notes.trim().take(160)}")
        append(" General training guidance only; use controlled form and stop if you feel pain, dizziness, or unusual symptoms.")
    }

    return GeneratedRoutine(
        name = input.name.trim().take(50),
        description = description,
        splitType = split,
        frequency = "$daysPerWeek days/week",
        days = List(daysPerWeek) { index ->
            val focus = focusCycle[index % focusCycle.size]
            val selection = selector.selectDayResult(
                canonicalExercises,
                CanonicalRoutineSelectionRequest(
                    equipment = equipment,
                    goal = goal,
                    experienceLevel = level,
                    split = split,
                    dayFocus = focus
                ),
                programmingRecords
            )
            val selectedExercises = when (selection) {
                is CanonicalRoutineSelectionResult.Supported -> selection.exercises
                is CanonicalRoutineSelectionResult.Unsupported -> throw CanonicalRoutineSelectionException(selection.message)
                is CanonicalRoutineSelectionResult.CoverageFailure -> throw CanonicalRoutineSelectionException(selection.message)
            }
            val mainOnlyDay = GeneratedDay(
                dayName = "Day ${index + 1}",
                title = focus,
                description = "$focus session for a $goal goal.",
                exercises = selectedExercises.map { exercise ->
                    GeneratedExercise(
                        name = exercise.name,
                        sets = sets,
                        reps = reps,
                        targetMuscle = exercise.canonicalRoutineTargetSnapshot(),
                        instructions = exercise.instructions,
                        exerciseId = exercise.id.value
                    )
                }
            )
            val composition = composer.compose(
                StructuredWorkoutCompositionRequest(
                    dayFocus = focus,
                    goal = goal,
                    experienceLevel = level,
                    availableEquipment = equipment,
                    mainExercises = mainOnlyDay.exercises,
                    canonicalExercises = canonicalExercises,
                    programmingRecords = programmingRecords
                )
            )
            mainOnlyDay.copy(
                exercises = composition.mainExercises,
                generalWarmup = composition.generalWarmup,
                warmupExercises = composition.preparationExercises,
                cooldownExercises = composition.cooldownExercises
            )
        },
        planId = "build-routine-${UUID.randomUUID()}",
        goal = goal,
        experienceLevel = level,
        createdAt = System.currentTimeMillis(),
        sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE
    )
}

internal suspend fun loadProgrammingState(
    catalogue: ExerciseProgrammingCatalogue
): ExerciseProgrammingState = try {
    catalogue.load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ExerciseProgrammingState.Error(PROGRAMMING_DEGRADED_MESSAGE)
}

internal suspend fun loadProgrammingStateAfterCanonical(
    canonicalResult: CanonicalRoutineCatalogueResult,
    programmingCatalogue: ExerciseProgrammingCatalogue
): ExerciseProgrammingState = if (canonicalResult is CanonicalRoutineCatalogueResult.Ready) {
    loadProgrammingState(programmingCatalogue)
} else {
    ExerciseProgrammingState.NotLoaded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildRoutineScreen(
    onBackToWorkout: () -> Unit,
    onSaveRoutine: (GeneratedRoutine, (SavedRoutineResult) -> Unit) -> Unit,
    onRoutineLimitReached: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selector = remember(context.applicationContext) {
        CanonicalRoutineExerciseSelector(
            ExerciseRepositoryProvider.getRepository(context.applicationContext)
        )
    }
    val programmingRepository = remember(context.applicationContext) {
        ExerciseProgrammingRepositoryProvider.getRepository(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var currentStep by remember { mutableIntStateOf(1) }
    var routineName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var sessionMinutes by remember { mutableStateOf<Int?>(null) }
    var level by remember { mutableStateOf<String?>(null) }
    var goal by remember { mutableStateOf<String?>(null) }
    var daysPerWeek by remember { mutableStateOf<Int?>(null) }
    var split by remember { mutableStateOf<String?>(null) }
    var equipment by remember { mutableStateOf<Set<String>>(emptySet()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var generatedRoutine by remember { mutableStateOf<GeneratedRoutine?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var isSavingRoutine by remember { mutableStateOf(false) }
    var catalogueResult by remember(selector) {
        mutableStateOf<CanonicalRoutineCatalogueResult>(CanonicalRoutineCatalogueResult.Loading)
    }
    var programmingState by remember(programmingRepository) {
        mutableStateOf<ExerciseProgrammingState>(ExerciseProgrammingState.NotLoaded)
    }

    fun retryCatalogueLoad() {
        coroutineScope.launch {
            programmingState = ExerciseProgrammingState.NotLoaded
            catalogueResult = CanonicalRoutineCatalogueResult.Loading
            val loadedCatalogue = selector.loadCatalogue()
            programmingState = if (loadedCatalogue is CanonicalRoutineCatalogueResult.Ready) {
                ExerciseProgrammingState.Loading
            } else {
                ExerciseProgrammingState.NotLoaded
            }
            catalogueResult = loadedCatalogue
            programmingState = loadProgrammingStateAfterCanonical(loadedCatalogue, programmingRepository)
        }
    }

    LaunchedEffect(selector, programmingRepository) {
        programmingState = ExerciseProgrammingState.NotLoaded
        val loadedCatalogue = selector.loadCatalogue()
        programmingState = if (loadedCatalogue is CanonicalRoutineCatalogueResult.Ready) {
            ExerciseProgrammingState.Loading
        } else {
            ExerciseProgrammingState.NotLoaded
        }
        catalogueResult = loadedCatalogue
        programmingState = loadProgrammingStateAfterCanonical(loadedCatalogue, programmingRepository)
    }

    fun input() = RoutineBuilderInput(
        name = routineName,
        notes = notes,
        sessionMinutes = sessionMinutes,
        level = level,
        goal = goal,
        daysPerWeek = daysPerWeek,
        split = split,
        equipment = equipment
    )

    fun navigateBack() {
        if (isGenerating) return
        val destination = previousRoutineDestination(currentStep, generatedRoutine != null)
        if (destination.exitBuilder) {
            onBackToWorkout()
        } else {
            currentStep = destination.step
            generatedRoutine = if (destination.showResult) generatedRoutine else null
            errorMessage = null
        }
    }

    BackHandler { navigateBack() }

    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary
    val accentBlue = Color(0xFF29B6F6)
    val generationReadiness = routineGenerationReadiness(
        canonicalReady = catalogueResult is CanonicalRoutineCatalogueResult.Ready,
        programmingState = programmingState
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BUILD ROUTINE", fontFamily = OswaldFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text("Local offline training planner", fontFamily = InterFontFamily, fontSize = 11.sp, color = secondaryColor)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navigateBack() },
                        enabled = !isGenerating,
                        modifier = Modifier.testTag("build_routine_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor)
            )
        }
    ) { innerPadding ->
        if (generatedRoutine != null) {
            RoutineResultContent(
                routine = generatedRoutine!!,
                input = input(),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                onSurfaceColor = onSurfaceColor,
                secondaryColor = secondaryColor,
                accentColor = accentColor,
                accentBlue = accentBlue,
                onEdit = {
                    generatedRoutine = null
                    currentStep = ROUTINE_STEP_COUNT
                },
                onSave = {
                    if (!isSavingRoutine) {
                        isSavingRoutine = true
                        onSaveRoutine(generatedRoutine!!) { result ->
                            isSavingRoutine = false
                            when (result.toAuthoredRoutineSaveAction()) {
                                AuthoredRoutineSaveAction.COMPLETE -> {
                                    Toast.makeText(context, "Routine saved locally", Toast.LENGTH_SHORT).show()
                                    onBackToWorkout()
                                }
                                AuthoredRoutineSaveAction.OPEN_ROUTINE_LIMIT_PAYWALL -> {
                                    onRoutineLimitReached()
                                }
                                AuthoredRoutineSaveAction.SHOW_INVALID_ERROR -> {
                                    errorMessage = "This routine needs a name and at least one complete exercise before saving."
                                }
                                AuthoredRoutineSaveAction.SHOW_STORAGE_ERROR -> {
                                    errorMessage = "Routine could not be saved. Please try again."
                                }
                            }
                        }
                    }
                },
                isSaving = isSavingRoutine,
                saveErrorMessage = errorMessage,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("build_routine_step_$currentStep"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RoutineProgress(currentStep, accentColor, accentBlue, secondaryColor)
                Text(
                    text = routineStepTitle(currentStep),
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 23.sp,
                    color = onSurfaceColor
                )
                Text(
                    text = routineStepDescription(currentStep),
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = secondaryColor
                )

                when (currentStep) {
                    1 -> RoutineInfoStep(
                        name = routineName,
                        notes = notes,
                        sessionMinutes = sessionMinutes,
                        onNameChange = { routineName = it.take(50); errorMessage = null },
                        onNotesChange = { notes = it.take(160); errorMessage = null },
                        onSessionSelected = { sessionMinutes = it; errorMessage = null },
                        surfaceColor = surfaceColor,
                        borderColor = borderColor,
                        accentColor = accentColor
                    )
                    2 -> RoutineChoiceList(listOf("Beginner", "Intermediate", "Advanced"), level, { level = it; errorMessage = null }, surfaceColor, borderColor, onSurfaceColor, secondaryColor, accentColor)
                    3 -> RoutineChoiceList(listOf("Strength", "Muscle Gain", "Fat Loss", "Endurance", "General Fitness"), goal, { goal = it; errorMessage = null }, surfaceColor, borderColor, onSurfaceColor, secondaryColor, accentColor)
                    4 -> RoutineChoiceList((2..6).map { "$it days per week" }, daysPerWeek?.let { "$it days per week" }, { daysPerWeek = it.substringBefore(" ").toInt(); errorMessage = null }, surfaceColor, borderColor, onSurfaceColor, secondaryColor, accentColor)
                    5 -> RoutineChoiceList(listOf("Full Body", "Upper / Lower", "Push / Pull / Legs", "Body Part Split"), split, { split = it; errorMessage = null }, surfaceColor, borderColor, onSurfaceColor, secondaryColor, accentColor)
                    6 -> {
                        RoutineEquipmentChoiceList(
                            options = ROUTINE_EQUIPMENT_OPTIONS,
                            selected = equipment,
                            onToggled = {
                                equipment = toggleRoutineEquipment(equipment, it)
                                errorMessage = null
                            },
                            surfaceColor = surfaceColor,
                            borderColor = borderColor,
                            onSurfaceColor = onSurfaceColor,
                            secondaryColor = secondaryColor,
                            accentColor = accentColor
                        )
                        when (val result = catalogueResult) {
                            CanonicalRoutineCatalogueResult.Loading -> Row(
                                modifier = Modifier.fillMaxWidth().testTag("routine_catalogue_loading"),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("Loading exercise library…", fontFamily = InterFontFamily, fontSize = 12.sp, color = secondaryColor)
                            }
                            is CanonicalRoutineCatalogueResult.Error -> Row(
                                modifier = Modifier.fillMaxWidth().testTag("routine_catalogue_error"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(result.message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontFamily = InterFontFamily, fontSize = 12.sp)
                                TextButton(onClick = ::retryCatalogueLoad, modifier = Modifier.testTag("routine_catalogue_retry")) {
                                    Text("RETRY", fontWeight = FontWeight.Bold)
                                }
                            }
                            is CanonicalRoutineCatalogueResult.Ready -> Unit
                        }
                        if (catalogueResult is CanonicalRoutineCatalogueResult.Ready) {
                            when (programmingState) {
                                ExerciseProgrammingState.NotLoaded,
                                ExerciseProgrammingState.Loading -> Row(
                                    modifier = Modifier.fillMaxWidth().testTag("routine_programming_loading"),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Text(
                                        "Loading enhanced workout programming…",
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        color = secondaryColor
                                    )
                                }
                                is ExerciseProgrammingState.Error,
                                is ExerciseProgrammingState.Ready -> Unit
                            }
                            generationReadiness.degradedMessage?.let { degradedMessage ->
                                Text(
                                    text = degradedMessage,
                                    color = secondaryColor,
                                    fontFamily = InterFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.testTag("routine_programming_degraded")
                                )
                            }
                        }
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontFamily = InterFontFamily, fontSize = 12.sp, modifier = Modifier.testTag("build_routine_error"))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { navigateBack() },
                        enabled = !isGenerating,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (currentStep == 1) "CANCEL" else "BACK", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            if (!isGenerating) {
                                val inputSnapshot = input().copy(equipment = equipment.toSet())
                                errorMessage = validateRoutineStep(currentStep, inputSnapshot)
                                if (errorMessage == null) {
                                    if (currentStep < ROUTINE_STEP_COUNT) {
                                        currentStep += 1
                                    } else {
                                        val ready = catalogueResult as? CanonicalRoutineCatalogueResult.Ready
                                        if (ready != null) {
                                            val catalogueSnapshot = ready.exercises.toList()
                                            val programmingSnapshot = generationReadiness.programmingRecords.toList()
                                            isGenerating = true
                                            coroutineScope.launch {
                                                try {
                                                    generatedRoutine = withContext(Dispatchers.Default) {
                                                        createLocalRoutine(
                                                            inputSnapshot,
                                                            catalogueSnapshot,
                                                            selector,
                                                            programmingSnapshot
                                                        )
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (selection: CanonicalRoutineSelectionException) {
                                                    errorMessage = selection.message
                                                } catch (_: Exception) {
                                                    errorMessage =
                                                        "A complete routine could not be built from the exercise library. Try another equipment choice."
                                                } finally {
                                                    isGenerating = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isGenerating &&
                            (currentStep != ROUTINE_STEP_COUNT || generationReadiness.canGenerate),
                        modifier = Modifier.weight(1.5f).height(52.dp).testTag(if (currentStep == ROUTINE_STEP_COUNT) "generate_routine_button" else "routine_next_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentStep == ROUTINE_STEP_COUNT) accentBlue else accentColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isGenerating && currentStep == ROUTINE_STEP_COUNT) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("GENERATING\u2026", fontWeight = FontWeight.Bold, color = Color.White)
                        } else {
                            Text(
                                if (currentStep == ROUTINE_STEP_COUNT) "GENERATE" else "CONTINUE",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

internal fun validateRoutineStep(step: Int, input: RoutineBuilderInput): String? = when (step) {
    1 -> when {
        input.name.isBlank() -> "Enter a routine name."
        input.sessionMinutes == null -> "Choose an approximate session length."
        else -> null
    }
    2 -> if (input.level == null) "Choose your experience level." else null
    3 -> if (input.goal == null) "Choose a training goal." else null
    4 -> if (input.daysPerWeek == null) "Choose training days per week." else null
    5 -> if (input.split == null) "Choose a routine split." else null
    else -> if (!hasRoutineEquipmentSelection(input.equipment)) "Choose available equipment." else null
}

private fun routineStepTitle(step: Int): String = when (step) {
    1 -> "Routine information"
    2 -> "Experience level"
    3 -> "Training goal"
    4 -> "Days per week"
    5 -> "Routine split"
    else -> "Available equipment"
}

private fun routineStepDescription(step: Int): String = when (step) {
    1 -> "Name the plan and choose a realistic session length. No profile values are assumed."
    2 -> "Choose the level that best matches your current training experience."
    3 -> "Select one primary goal so the local plan can set sensible sets and rep ranges."
    4 -> "Pick a weekly frequency you can recover from and follow consistently."
    5 -> "Choose how muscle groups should be organized across your training days."
    else -> "The routine uses only the equipment you select."
}

@Composable
private fun RoutineProgress(currentStep: Int, accentColor: Color, accentBlue: Color, secondaryColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(ROUTINE_STEP_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                index + 1 < currentStep -> accentColor
                                index + 1 == currentStep -> accentBlue
                                else -> secondaryColor.copy(alpha = 0.18f)
                            }
                        )
                )
            }
        }
        Text("STEP $currentStep OF $ROUTINE_STEP_COUNT", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = secondaryColor, letterSpacing = 1.sp)
    }
}

@Composable
private fun RoutineInfoStep(
    name: String,
    notes: String,
    sessionMinutes: Int?,
    onNameChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSessionSelected: (Int) -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    accentColor: Color
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth().testTag("routine_name_input"),
        label = { Text("Routine name") },
        placeholder = { Text("Example: My strength routine") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentColor, unfocusedBorderColor = borderColor, focusedContainerColor = surfaceColor, unfocusedContainerColor = surfaceColor)
    )
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Notes (optional)") },
        placeholder = { Text("Schedule or movement preferences") },
        minLines = 2,
        maxLines = 3,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentColor, unfocusedBorderColor = borderColor, focusedContainerColor = surfaceColor, unfocusedContainerColor = surfaceColor)
    )
    Text("SESSION LENGTH", fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = accentColor)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 45, 60).forEach { minutes ->
            FilterChip(
                selected = sessionMinutes == minutes,
                onClick = { onSessionSelected(minutes) },
                label = { Text("$minutes min", fontWeight = FontWeight.Bold) },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = Color.White)
            )
        }
    }
}

@Composable
private fun RoutineChoiceList(
    options: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    secondaryColor: Color,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            val isSelected = selected == option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(surfaceColor)
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) accentColor else borderColor, RoundedCornerShape(12.dp))
                    .clickable { onSelected(option) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("routine_option_${option.lowercase().replace(' ', '_').replace('/', '_')}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(option, fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isSelected) accentColor else onSurfaceColor)
                    Text(routineOptionHint(option), fontFamily = InterFontFamily, fontSize = 11.sp, color = secondaryColor)
                }
                RadioButton(selected = isSelected, onClick = { onSelected(option) }, colors = RadioButtonDefaults.colors(selectedColor = accentColor))
            }
        }
    }
}

@Composable
private fun RoutineEquipmentChoiceList(
    options: List<String>,
    selected: Set<String>,
    onToggled: (String) -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    secondaryColor: Color,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            val isSelected = option in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(surfaceColor)
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) accentColor else borderColor, RoundedCornerShape(12.dp))
                    .clickable { onToggled(option) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("routine_equipment_${option.lowercase().replace(' ', '_')}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(option, fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isSelected) accentColor else onSurfaceColor)
                    Text(routineEquipmentHint(option), fontFamily = InterFontFamily, fontSize = 11.sp, color = secondaryColor)
                }
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggled(option) },
                    colors = CheckboxDefaults.colors(checkedColor = accentColor)
                )
            }
        }
    }
}

private fun routineEquipmentHint(option: String): String = when (option) {
    "Bodyweight" -> "Exercises that need no external load"
    "Dumbbells" -> "Includes dumbbell and bodyweight exercises"
    "Barbell and rack" -> "Includes barbell and bodyweight exercises"
    "Resistance bands" -> "Includes band and bodyweight exercises"
    else -> "Uses the complete gym-equipment catalogue"
}

private fun routineOptionHint(option: String): String = when (option) {
    "Beginner" -> "New or returning to structured training"
    "Intermediate" -> "Comfortable with common movements and progression"
    "Advanced" -> "Experienced with consistent training and recovery"
    "Strength" -> "Lower rep ranges with longer recovery"
    "Muscle Gain" -> "Moderate reps and balanced weekly volume"
    "Fat Loss" -> "Consistent full-session work with moderate reps"
    "Endurance" -> "Higher rep ranges and controlled pacing"
    "General Fitness" -> "Balanced strength, movement, and consistency"
    "Full Body" -> "Train major movement patterns each day"
    "Upper / Lower" -> "Alternate upper- and lower-body sessions"
    "Push / Pull / Legs" -> "Organize sessions by movement pattern"
    "Body Part Split" -> "Rotate focused muscle-group sessions"
    else -> "Uses this selection when building the local plan"
}

@Composable
private fun RoutineResultContent(
    routine: GeneratedRoutine,
    input: RoutineBuilderInput,
    surfaceColor: Color,
    borderColor: Color,
    onSurfaceColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    accentBlue: Color,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    saveErrorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).testTag("build_routine_result"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = accentColor)
            }
            Column {
                Text("ROUTINE READY", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = accentBlue, letterSpacing = 1.sp)
                Text(routine.name, fontFamily = OswaldFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, color = onSurfaceColor)
            }
        }

        if (saveErrorMessage != null) {
            Text(
                text = saveErrorMessage,
                color = MaterialTheme.colorScheme.error,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.testTag("build_routine_save_error")
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = surfaceColor), border = BorderStroke(1.dp, borderColor), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutineSummaryRow("Level", input.level.orEmpty(), onSurfaceColor, secondaryColor)
                RoutineSummaryRow("Goal", input.goal.orEmpty(), onSurfaceColor, secondaryColor)
                RoutineSummaryRow("Schedule", routine.frequency, onSurfaceColor, secondaryColor)
                RoutineSummaryRow("Split", routine.splitType, onSurfaceColor, secondaryColor)
                RoutineSummaryRow("Equipment", routineEquipmentSummary(input.equipment), onSurfaceColor, secondaryColor)
                RoutineSummaryRow("Session", "${input.sessionMinutes} minutes", onSurfaceColor, secondaryColor)
            }
        }

        routine.days.forEach { day ->
            Card(colors = CardDefaults.cardColors(containerColor = surfaceColor), border = BorderStroke(1.dp, borderColor), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.dayName.uppercase(), fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = accentBlue, letterSpacing = 1.sp)
                    Text(day.title, fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = onSurfaceColor)
                    day.exercises.forEach { exercise ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(exercise.name, modifier = Modifier.weight(1f), fontFamily = InterFontFamily, fontSize = 12.sp, color = onSurfaceColor)
                            Text("${exercise.sets} × ${exercise.reps}", fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accentColor)
                        }
                    }
                }
            }
        }

        Text(
            "General training guidance only. Stop if you feel pain, dizziness, faintness, chest pain, or unusual symptoms, and seek qualified care when appropriate.",
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = secondaryColor
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("EDIT", fontWeight = FontWeight.Bold)
            }
            Button(onClick = onSave, enabled = !isSaving, modifier = Modifier.weight(1.5f).height(52.dp).testTag("save_generated_routine"), colors = ButtonDefaults.buttonColors(containerColor = accentColor), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(if (isSaving) "SAVING" else "SAVE & FINISH", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoutineSummaryRow(label: String, value: String, onSurfaceColor: Color, secondaryColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFontFamily, fontSize = 12.sp, color = secondaryColor)
        Text(value, fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = onSurfaceColor)
    }
}
internal enum class AuthoredRoutineSaveAction {
    COMPLETE,
    OPEN_ROUTINE_LIMIT_PAYWALL,
    SHOW_INVALID_ERROR,
    SHOW_STORAGE_ERROR
}

internal fun SavedRoutineResult.toAuthoredRoutineSaveAction(): AuthoredRoutineSaveAction = when (this) {
    SavedRoutineResult.SAVED,
    SavedRoutineResult.ALREADY_SAVED -> AuthoredRoutineSaveAction.COMPLETE
    SavedRoutineResult.LIMIT_REACHED -> AuthoredRoutineSaveAction.OPEN_ROUTINE_LIMIT_PAYWALL
    SavedRoutineResult.INVALID_ROUTINE -> AuthoredRoutineSaveAction.SHOW_INVALID_ERROR
    SavedRoutineResult.ERROR -> AuthoredRoutineSaveAction.SHOW_STORAGE_ERROR
}
