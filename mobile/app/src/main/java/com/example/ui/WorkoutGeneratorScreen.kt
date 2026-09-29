package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiRepository
import com.example.ai.AiWorkoutRequest
import com.example.ai.GeneratedRoutine
import com.example.ai.GeneralWarmupPrescription
import com.example.ai.OfflineAiWorkoutGenerator
import com.example.ai.validateGeneratorStep
import com.example.exercise.CanonicalRoutineExerciseSelector
import com.example.exercise.ExerciseRepositoryProvider
import com.example.exercise.programming.ExerciseProgrammingRepositoryProvider
import com.example.subscription.SubscriptionCapability
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.ui.theme.fitDesiColors
import com.example.viewmodel.TrainerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private val StringSetStateSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) }
)

internal fun restorablePlannerStage(stage: String): String = when (stage) {
    "WIZARD", "GENERATING", "RESULTS" -> "WIZARD"
    else -> "SELECTION"
}

private val PlannerStageStateSaver = listSaver<MutableState<String>, String>(
    save = { listOf(restorablePlannerStage(it.value)) },
    restore = { mutableStateOf(restorablePlannerStage(it.firstOrNull().orEmpty())) }
)

internal fun plannerSelectionAfterFrequencyChange(
    selectedDays: Set<String>,
    requestedDays: Int
): Set<String> = if (selectedDays.size > requestedDays) emptySet() else selectedDays

internal fun togglePlannerDaySelection(
    selectedDays: Set<String>,
    day: String,
    requestedDays: Int
): Set<String> = when {
    day in selectedDays -> selectedDays - day
    selectedDays.size < requestedDays -> selectedDays + day
    else -> selectedDays
}

internal fun hasExactPlannerDaySelection(
    selectedDays: Set<String>,
    requestedDays: Int
): Boolean = requestedDays > 0 && selectedDays.size == requestedDays

internal fun usesExpandedPlannerStepLabels(fontScale: Float): Boolean = fontScale > 1f

internal fun plannerSteps(generatorType: String): List<Int> =
    if (generatorType == "Single Workout") listOf(1, 2, 3, 5, 6) else (1..6).toList()

internal fun plannerStepLabels(generatorType: String): List<String> {
    val labelsByStep = listOf("Info", "Level", "Goal", "Days", "Split", "Equipment")
    return plannerSteps(generatorType).map { step -> labelsByStep[step - 1] }
}

internal fun nextPlannerStep(currentStep: Int, generatorType: String): Int? {
    val steps = plannerSteps(generatorType)
    val currentIndex = steps.indexOf(currentStep)
    return if (currentIndex in 0 until steps.lastIndex) steps[currentIndex + 1] else null
}

internal fun previousPlannerStep(currentStep: Int, generatorType: String): Int? {
    val steps = plannerSteps(generatorType)
    val currentIndex = steps.indexOf(currentStep)
    return if (currentIndex > 0) steps[currentIndex - 1] else null
}

internal fun plannerWeekday(calendarDayOfWeek: Int): String = when (calendarDayOfWeek) {
    Calendar.MONDAY -> "Mon"
    Calendar.TUESDAY -> "Tue"
    Calendar.WEDNESDAY -> "Wed"
    Calendar.THURSDAY -> "Thu"
    Calendar.FRIDAY -> "Fri"
    Calendar.SATURDAY -> "Sat"
    Calendar.SUNDAY -> "Sun"
    else -> error("Unsupported calendar day: $calendarDayOfWeek")
}

internal fun normalizedPlannerRequest(
    request: AiWorkoutRequest,
    calendarDayOfWeek: Int
): AiWorkoutRequest = if (request.generatorType == "Single Workout") {
    request.copy(
        daysCount = 1,
        selectedDays = listOf(plannerWeekday(calendarDayOfWeek))
    )
} else {
    request
}
internal fun handleGeneratorNextAction(
    currentStep: Int,
    input: AiWorkoutRequest,
    hasAiWorkoutGenerationCapability: () -> Boolean,
    onValidationMessage: (String?) -> Unit,
    onAdvance: () -> Unit,
    onRequiresPlus: () -> Unit,
    onGenerate: (AiWorkoutRequest) -> Unit
) {
    val validationMessage = validateGeneratorStep(currentStep, input)
    onValidationMessage(validationMessage)
    if (validationMessage != null) return
    if (currentStep < 6) {
        onAdvance()
        return
    }
    if (!runCatching(hasAiWorkoutGenerationCapability).getOrDefault(false)) {
        onRequiresPlus()
        return
    }
    onGenerate(input)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutGeneratorScreen(
    viewModel: TrainerViewModel,
    onBack: () -> Unit,
    onAiWorkoutGenerationRequiresPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val aiRepository = remember(context.applicationContext) {
        val canonicalRepository = ExerciseRepositoryProvider.getRepository(context.applicationContext)
        AiRepository(
            localWorkout = OfflineAiWorkoutGenerator(
                canonicalSelector = CanonicalRoutineExerciseSelector(canonicalRepository),
                programmingCatalogue = ExerciseProgrammingRepositoryProvider.getRepository(
                    context.applicationContext
                )
            )
        )
    }


    // Navigation and workflow stages: "SELECTION" -> "WIZARD" -> "GENERATING" -> "RESULTS"
    var currentStage by rememberSaveable(saver = PlannerStageStateSaver) {
        mutableStateOf("SELECTION")
    }
    var selectedGeneratorType by rememberSaveable { mutableStateOf("Weekly Routine") } // "Weekly Routine" or "Single Workout"

    // WIZARD STEPS: 1 to 6
    var currentStep by rememberSaveable { mutableStateOf(1) }

    // STEP 1: INFO STATE
    var gender by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf(0f) }

    // STEP 2: EXPERIENCE STATE
    var experienceLevel by rememberSaveable { mutableStateOf("") }

    // STEP 3: FITNESS GOAL
    var fitnessGoal by rememberSaveable { mutableStateOf("") }

    // STEP 4: TRAINING DAYS
    var daysPerWeek by rememberSaveable { mutableStateOf(0) }
    var selectedDays by rememberSaveable(saver = StringSetStateSaver) { mutableStateOf(emptySet()) }

    // STEP 5: SPLIT STATE
    var trainingSplit by rememberSaveable { mutableStateOf("") }
    var enforceRecovery by rememberSaveable { mutableStateOf(true) }

    // STEP 6: EQUIPMENT STATE
    var selectedEquipmentPreset by rememberSaveable { mutableStateOf("") } // "Full Gym", "Home Gym", "Bodyweight"
    var selectedEquipment by rememberSaveable(saver = StringSetStateSaver) { mutableStateOf(emptySet()) }

    // Expandable accordion states for step 6
    var freeWeightsExpanded by rememberSaveable { mutableStateOf(true) }
    var machinesExpanded by rememberSaveable { mutableStateOf(true) }
    var bodyweightExpanded by rememberSaveable { mutableStateOf(true) }

    // GENERATED RESULT
    var generatedRoutineResult by remember { mutableStateOf<GeneratedRoutine?>(null) }
    var isSavingGeneratedRoutine by remember { mutableStateOf(false) }
    var generationErrorMessage by remember { mutableStateOf<String?>(null) }
    var wizardValidationMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(profile) {
        if (gender.isBlank() && profile.gender in setOf("Male", "Female")) gender = profile.gender
        if (age <= 0f && profile.age in 15..80) age = profile.age.toFloat()
        if (experienceLevel.isBlank() && profile.workoutExperience in setOf("Beginner", "Intermediate", "Advanced")) {
            experienceLevel = profile.workoutExperience
        }
        if (fitnessGoal.isBlank()) {
            fitnessGoal = when (profile.goal) {
                "Gain Muscle" -> profile.goal
                "Build Strength" -> "Get Stronger"
                "Lose Fat" -> "Lose Body Fat"
                else -> ""
            }
        }
        if (daysPerWeek == 0 && profile.workoutDays in 2..7) daysPerWeek = profile.workoutDays
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val cardBgColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onBackground
    val textSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryOrange = MaterialTheme.colorScheme.primary
    val buttonBgColor = MaterialTheme.colorScheme.surfaceVariant

    // Handle equipment preset updates
    LaunchedEffect(selectedEquipmentPreset) {
        when (selectedEquipmentPreset) {
            "Full Gym" -> {
                selectedEquipment = setOf("Barbell", "Kettlebells", "Dumbbells", "Cables", "Machine", "Pull-up Bar", "Resistance Bands", "Bodyweight")
            }
            "Home Gym" -> {
                selectedEquipment = setOf("Kettlebells", "Dumbbells", "Pull-up Bar", "Resistance Bands", "Bodyweight")
            }
            "Bodyweight" -> {
                selectedEquipment = setOf("Bodyweight")
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Personalized Workout Planner",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = textColor,
                        letterSpacing = 0.5.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (currentStage) {
                            "RESULTS" -> {
                                currentStage = "SELECTION"
                            }
                            "WIZARD" -> {
                                val previousStep = previousPlannerStep(currentStep, selectedGeneratorType)
                                if (previousStep != null) {
                                    currentStep = previousStep
                                } else {
                                    currentStage = "SELECTION"
                                }
                            }
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go Back",
                            tint = textColor
                        )
                    }
                },
                actions = {
                    if (currentStage == "WIZARD") {
                        IconButton(
                            onClick = { currentStage = "SELECTION" },
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(cardBgColor)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Close planner",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentStage) {
                "SELECTION" -> {
                    SelectionStage(
                        cardBgColor = cardBgColor,
                        cardBorderColor = cardBorderColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        primaryOrange = primaryOrange,
                        statusText = "Built from your goals, training experience, available days and equipment. Uses FitDesi's deterministic workout-planning logic.",
                        onSelect = { type ->
                            selectedGeneratorType = type
                            currentStep = 1
                            currentStage = "WIZARD"
                        }
                    )
                }

                "WIZARD" -> {
                    WizardStage(
                        step = currentStep,
                        generatorType = selectedGeneratorType,
                        gender = gender,
                        onGenderChange = { gender = it },
                        age = age,
                        onAgeChange = { age = it },
                        experienceLevel = experienceLevel,
                        onExperienceChange = { experienceLevel = it },
                        fitnessGoal = fitnessGoal,
                        onGoalChange = { fitnessGoal = it },
                        daysPerWeek = daysPerWeek,
                        onDaysPerWeekChange = { requestedDays ->
                            selectedDays = plannerSelectionAfterFrequencyChange(
                                selectedDays = selectedDays,
                                requestedDays = requestedDays
                            )
                            daysPerWeek = requestedDays
                        },
                        selectedDays = selectedDays,
                        onSelectedDaysChange = { selectedDays = it },
                        trainingSplit = trainingSplit,
                        onSplitChange = { trainingSplit = it },
                        enforceRecovery = enforceRecovery,
                        onEnforceRecoveryChange = { enforceRecovery = it },
                        selectedEquipmentPreset = selectedEquipmentPreset,
                        onEquipmentPresetChange = { selectedEquipmentPreset = it },
                        selectedEquipment = selectedEquipment,
                        onEquipmentChange = { selectedEquipment = it },
                        freeWeightsExpanded = freeWeightsExpanded,
                        onFreeWeightsToggle = { freeWeightsExpanded = !freeWeightsExpanded },
                        machinesExpanded = machinesExpanded,
                        onMachinesToggle = { machinesExpanded = !machinesExpanded },
                        bodyweightExpanded = bodyweightExpanded,
                        onBodyweightToggle = { bodyweightExpanded = !bodyweightExpanded },
                        cardBgColor = cardBgColor,
                        cardBorderColor = cardBorderColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        primaryOrange = primaryOrange,
                        buttonBgColor = buttonBgColor,
                        validationMessage = wizardValidationMessage,
                        onNext = {
                            val input = normalizedPlannerRequest(
                                request = AiWorkoutRequest(
                                    generatorType = selectedGeneratorType,
                                    gender = gender,
                                    age = age.toInt(),
                                    level = experienceLevel,
                                    goal = fitnessGoal,
                                    daysCount = daysPerWeek,
                                    selectedDays = selectedDays.toList(),
                                    split = trainingSplit,
                                    enforceRecovery = enforceRecovery,
                                    equipment = selectedEquipment.toList()
                                ),
                                calendarDayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                            )
                            handleGeneratorNextAction(
                                currentStep = currentStep,
                                input = input,
                                hasAiWorkoutGenerationCapability = {
                                    viewModel.hasSubscriptionCapability(
                                        SubscriptionCapability.AI_WORKOUT_GENERATION
                                    )
                                },
                                onValidationMessage = { wizardValidationMessage = it },
                                onAdvance = {
                                    nextPlannerStep(currentStep, selectedGeneratorType)?.let { nextStep ->
                                        currentStep = nextStep
                                    }
                                },
                                onRequiresPlus = onAiWorkoutGenerationRequiresPlus,
                                onGenerate = { validatedInput ->
                                    // Safe mode uses the deterministic local agent pipeline.
                                    currentStage = "GENERATING"
                                    generationErrorMessage = null
                                    coroutineScope.launch {
                                        val result = aiRepository.generateWorkout(validatedInput)
                                        result.fold(
                                            onSuccess = { routine ->
                                                generatedRoutineResult = routine
                                                currentStage = "RESULTS"
                                            },
                                            onFailure = { throwable ->
                                                generationErrorMessage = throwable.message
                                                    ?: "An unexpected error occurred while creating the plan."
                                                currentStage = "RESULTS"
                                            }
                                        )
                                    }
                                }
                            )
                        },
                        onBack = {
                            wizardValidationMessage = null
                            val previousStep = previousPlannerStep(currentStep, selectedGeneratorType)
                            if (previousStep != null) {
                                currentStep = previousStep
                            } else {
                                currentStage = "SELECTION"
                            }
                        }
                    )
                }

                "GENERATING" -> {
                    GeneratingStage(
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        primaryOrange = primaryOrange
                    )
                }

                "RESULTS" -> {
                    ResultStage(
                        routine = generatedRoutineResult,
                        errorMessage = generationErrorMessage,
                        viewModel = viewModel,
                        cardBgColor = cardBgColor,
                        cardBorderColor = cardBorderColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        primaryOrange = primaryOrange,
                        onRetry = {
                            currentStage = "SELECTION"
                        },
                        onSave = {
                            generatedRoutineResult?.let { routine ->
                                if (!isSavingGeneratedRoutine) {
                                    isSavingGeneratedRoutine = true
                                    val gson = Gson()
                                    val json = gson.toJson(routine)
                                    viewModel.saveGeneratedWorkoutPlan(json) { result ->
                                        isSavingGeneratedRoutine = false
                                        when (result) {
                                            com.example.data.SavedRoutineResult.SAVED,
                                            com.example.data.SavedRoutineResult.ALREADY_SAVED -> {
                                                Toast.makeText(context, "Workout program saved to My Routines", Toast.LENGTH_LONG).show()
                                                onBack()
                                            }
                                            com.example.data.SavedRoutineResult.INVALID_ROUTINE -> {
                                                Toast.makeText(context, "This workout plan is incomplete and could not be saved.", Toast.LENGTH_LONG).show()
                                            }
                                            com.example.data.SavedRoutineResult.LIMIT_REACHED,
                                            com.example.data.SavedRoutineResult.ERROR -> {
                                                Toast.makeText(context, "Workout plan could not be saved. Please try again.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// STAGE 1: GENERATOR SELECTION SCREEN
// ==========================================
@Composable
fun SelectionStage(
    cardBgColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    primaryOrange: Color,
    statusText: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = statusText,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            color = textSecondaryColor
        )

        // High fidelity routine card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(18.dp))
                .border(BorderStroke(1.5.dp, primaryOrange.copy(alpha = 0.5f)), RoundedCornerShape(18.dp))
                .background(cardBgColor, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(primaryOrange.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "COMPLETE PROGRAM",
                            fontFamily = InterFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryOrange
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFFB300), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Text(
                                text = "RECOMMENDED",
                                fontFamily = InterFontFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Weekly Routine",
                    fontFamily = OswaldFontFamily,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Build a structured multi-day program tailored to your goals. Ideal for consistent, long-term progress.",
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    color = textSecondaryColor,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BulletPointItem(icon = Icons.Default.CalendarToday, text = "2-7 days per week", textColor = textColor)
                    BulletPointItem(icon = Icons.Default.FitnessCenter, text = "Balanced muscle coverage", textColor = textColor)
                    BulletPointItem(icon = Icons.Default.TrendingUp, text = "Progressive overload built-in", textColor = textColor)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { /* How To Info dialog or message */ }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(16.dp))
                            Text("How To", fontFamily = InterFontFamily, fontSize = 13.sp, color = primaryOrange, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = { onSelect("Weekly Routine") },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryOrange),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Get Started", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Single Workout card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(18.dp))
                .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(18.dp))
                .background(cardBgColor, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.fitDesiColors.info.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "QUICK & FOCUSED",
                        fontFamily = InterFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.fitDesiColors.info
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Single Workout",
                    fontFamily = OswaldFontFamily,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Create a targeted workout for today from your goals, experience and equipment.",
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    color = textSecondaryColor,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BulletPointItem(icon = Icons.Default.FlashOn, text = "Quick setup", textColor = textColor)
                    BulletPointItem(icon = Icons.Default.FilterCenterFocus, text = "Choose your training split", textColor = textColor)
                    BulletPointItem(icon = Icons.Default.DirectionsRun, text = "Start immediately", textColor = textColor)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { /* How To Info dialog or message */ }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.fitDesiColors.info, modifier = Modifier.size(16.dp))
                            Text("How To", fontFamily = InterFontFamily, fontSize = 13.sp, color = MaterialTheme.fitDesiColors.info, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = { onSelect("Single Workout") },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryOrange),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Get Started", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun BulletPointItem(icon: ImageVector, text: String, textColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            fontFamily = InterFontFamily,
            fontSize = 13.sp,
            color = textColor.copy(alpha = 0.85f)
        )
    }
}

// ==========================================
// STAGE 2: MULTI-STEP WIZARD
// ==========================================
@Composable
fun WizardStage(
    step: Int,
    generatorType: String,
    gender: String,
    onGenderChange: (String) -> Unit,
    age: Float,
    onAgeChange: (Float) -> Unit,
    experienceLevel: String,
    onExperienceChange: (String) -> Unit,
    fitnessGoal: String,
    onGoalChange: (String) -> Unit,
    daysPerWeek: Int,
    onDaysPerWeekChange: (Int) -> Unit,
    selectedDays: Set<String>,
    onSelectedDaysChange: (Set<String>) -> Unit,
    trainingSplit: String,
    onSplitChange: (String) -> Unit,
    enforceRecovery: Boolean,
    onEnforceRecoveryChange: (Boolean) -> Unit,
    selectedEquipmentPreset: String,
    onEquipmentPresetChange: (String) -> Unit,
    selectedEquipment: Set<String>,
    onEquipmentChange: (Set<String>) -> Unit,
    freeWeightsExpanded: Boolean,
    onFreeWeightsToggle: () -> Unit,
    machinesExpanded: Boolean,
    onMachinesToggle: () -> Unit,
    bodyweightExpanded: Boolean,
    onBodyweightToggle: () -> Unit,
    cardBgColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    primaryOrange: Color,
    buttonBgColor: Color,
    validationMessage: String?,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // STEP PROGRESS DOTS INDICATOR
            val visibleSteps = plannerSteps(generatorType)
            val stepNames = plannerStepLabels(generatorType)
            val allStepIcons = listOf(
                Icons.Default.Person,
                Icons.Default.BarChart,
                Icons.Default.Flag,
                Icons.Default.Event,
                Icons.Default.ViewAgenda,
                Icons.Default.FitnessCenter
            )
            val stepIcons = visibleSteps.map { stepNumber -> allStepIcons[stepNumber - 1] }
            val useExpandedLabels = usesExpandedPlannerStepLabels(LocalDensity.current.fontScale)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    stepNames.forEachIndexed { index, name ->
                        val stepNumber = visibleSteps[index]
                        val isCompleted = stepNumber < step
                        val isActive = stepNumber == step

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isCompleted -> Color(0xFF4CAF50)
                                            isActive -> primaryOrange
                                            else -> buttonBgColor
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCompleted) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "$name step complete",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = stepIcons[index],
                                        contentDescription = "$name step ${index + 1} of ${stepNames.size}",
                                        tint = if (isActive) Color.White else textSecondaryColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (!useExpandedLabels) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = name,
                                    fontFamily = InterFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isActive) primaryOrange else textSecondaryColor,
                                    maxLines = 1,
                                )
                            }
                        }

                        if (index < stepNames.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .weight(0.2f)
                                    .padding(top = 16.dp)
                                    .height(2.dp)
                                    .background(if (stepNumber < step) Color(0xFF4CAF50) else cardBorderColor)
                            )
                        }
                    }
                }

                if (useExpandedLabels) {
                    Spacer(modifier = Modifier.height(8.dp))
                    stepNames.chunked(2).forEachIndexed { rowIndex, rowNames ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowNames.forEachIndexed { columnIndex, name ->
                                val index = rowIndex * 2 + columnIndex
                                val isActive = visibleSteps[index] == step
                                Text(
                                    text = "${index + 1}. $name",
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontFamily = InterFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isActive) primaryOrange else textSecondaryColor,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Divider(color = cardBorderColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

            // STEP CONTENT VIEWS
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (step) {
                    1 -> {
                        Step1Personalize(
                            gender = gender,
                            onGenderChange = onGenderChange,
                            age = age,
                            onAgeChange = onAgeChange,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange
                        )
                    }
                    2 -> {
                        Step2Experience(
                            experienceLevel = experienceLevel,
                            onExperienceChange = onExperienceChange,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange
                        )
                    }
                    3 -> {
                        Step3Goal(
                            fitnessGoal = fitnessGoal,
                            onGoalChange = onGoalChange,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange
                        )
                    }
                    4 -> {
                        Step4Days(
                            daysPerWeek = daysPerWeek,
                            onDaysPerWeekChange = onDaysPerWeekChange,
                            selectedDays = selectedDays,
                            onSelectedDaysChange = onSelectedDaysChange,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange,
                            buttonBgColor = buttonBgColor
                        )
                    }
                    5 -> {
                        Step5Split(
                            trainingSplit = trainingSplit,
                            onSplitChange = onSplitChange,
                            enforceRecovery = enforceRecovery,
                            onEnforceRecoveryChange = onEnforceRecoveryChange,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange
                        )
                    }
                    6 -> {
                        Step6Equipment(
                            selectedEquipmentPreset = selectedEquipmentPreset,
                            onEquipmentPresetChange = onEquipmentPresetChange,
                            selectedEquipment = selectedEquipment,
                            onEquipmentChange = onEquipmentChange,
                            freeWeightsExpanded = freeWeightsExpanded,
                            onFreeWeightsToggle = onFreeWeightsToggle,
                            machinesExpanded = machinesExpanded,
                            onMachinesToggle = onMachinesToggle,
                            bodyweightExpanded = bodyweightExpanded,
                            onBodyweightToggle = onBodyweightToggle,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            primaryOrange = primaryOrange
                        )
                    }
                }
            }
        }

        // FOOTER ACTIONS
        if (validationMessage != null) {
            Text(
                text = validationMessage,
                color = MaterialTheme.colorScheme.error,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, cardBorderColor),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Go Back", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
                enabled = step != 4 || hasExactPlannerDaySelection(selectedDays, daysPerWeek),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryOrange)
            ) {
                val actionLabel = when {
                    step != 6 -> "Next"
                    generatorType == "Single Workout" -> "Create workout"
                    else -> "Create plan"
                }
                Text(actionLabel, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = if (step == 6) Icons.Default.AutoAwesome else Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ==========================================
// STEP 1 CONTENT: PERSONALIZE
// ==========================================
@Composable
fun Step1Personalize(
    gender: String,
    onGenderChange: (String) -> Unit,
    age: Float,
    onAgeChange: (Float) -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column {
            Text(
                text = "Personalize",
                fontFamily = OswaldFontFamily,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "Help us tailor a routine to your goals.",
                fontFamily = InterFontFamily,
                fontSize = 14.sp,
                color = textSecondaryColor
            )
        }

        Text(
            text = "Select Gender",
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = textColor
        )

        if (gender.isBlank() || age !in 15f..80f) {
            Text(
                text = "Profile details are not set. Choose your gender and age to continue; no values will be assumed.",
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = primaryOrange
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Male Card
            val isMaleSelected = gender == "Male"
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(90.dp)
                    .border(
                        BorderStroke(
                            width = if (isMaleSelected) 2.dp else 1.dp,
                            color = if (isMaleSelected) primaryOrange else cardBorderColor
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onGenderChange("Male") },
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Male,
                        contentDescription = "Male",
                        tint = if (isMaleSelected) primaryOrange else textSecondaryColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Male",
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
            }

            // Female Card
            val isFemaleSelected = gender == "Female"
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(90.dp)
                    .border(
                        BorderStroke(
                            width = if (isFemaleSelected) 2.dp else 1.dp,
                            color = if (isFemaleSelected) primaryOrange else cardBorderColor
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onGenderChange("Female") },
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Female,
                        contentDescription = "Female",
                        tint = if (isFemaleSelected) primaryOrange else textSecondaryColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Female",
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Age",
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = textColor
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(12.dp))
                .background(cardBgColor)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (age in 15f..80f) age.toInt().toString() else "Not set",
                        fontFamily = OswaldFontFamily,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryOrange
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "years old",
                        fontFamily = InterFontFamily,
                        fontSize = 15.sp,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = age.coerceIn(15f, 80f),
                    onValueChange = onAgeChange,
                    valueRange = 15f..80f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = primaryOrange,
                        inactiveTrackColor = cardBorderColor,
                        thumbColor = primaryOrange
                    )
                )
            }
        }
    }
}

// ==========================================
// STEP 2 CONTENT: EXPERIENCE
// ==========================================
@Composable
fun Step2Experience(
    experienceLevel: String,
    onExperienceChange: (String) -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "What's your fitness experience?",
                fontFamily = OswaldFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "YOUR FITNESS LEVEL?",
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 1.sp
            )
        }

        val levels = listOf(
            Triple("Novice", "New to strength training with less than 3 months experience", Icons.Default.ArrowDownward),
            Triple("Beginner", "3-12 months of consistent training experience", Icons.Default.DirectionsWalk),
            Triple("Intermediate", "1-3 years of regular training with good form", Icons.Default.DirectionsRun),
            Triple("Advanced", "3+ years of dedicated training and advanced techniques", Icons.Default.Whatshot)
        )

        levels.forEach { (title, description, icon) ->
            val isSelected = experienceLevel == title
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primaryOrange else cardBorderColor
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onExperienceChange(title) },
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) primaryOrange.copy(alpha = 0.15f) else cardBorderColor.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) primaryOrange else textSecondaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Text(
                            text = description,
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = textSecondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    RadioButton(
                        selected = isSelected,
                        onClick = { onExperienceChange(title) },
                        colors = RadioButtonDefaults.colors(selectedColor = primaryOrange)
                    )
                }
            }
        }
    }
}

// ==========================================
// STEP 3 CONTENT: GOALS
// ==========================================
@Composable
fun Step3Goal(
    fitnessGoal: String,
    onGoalChange: (String) -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "What do you want to achieve?",
                fontFamily = OswaldFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "YOUR FITNESS GOAL?",
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 1.sp
            )
        }

        val goals = listOf(
            Triple("Gain Muscle", "Increase muscle mass and definition", Icons.Default.FitnessCenter),
            Triple("Get Stronger", "Build power and increase lifting capacity", Icons.Default.Bolt),
            Triple("Lose Body Fat", "Burn calories and reduce body fat", Icons.Default.Whatshot)
        )

        goals.forEach { (title, description, icon) ->
            val isSelected = fitnessGoal == title
            val activeColor = when (title) {
                "Gain Muscle" -> Color(0xFF9C27B0)
                "Get Stronger" -> Color(0xFF00B0FF)
                else -> Color(0xFFFF6D00)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) activeColor else cardBorderColor
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onGoalChange(title) },
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) activeColor.copy(alpha = 0.15f) else cardBorderColor.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) activeColor else textSecondaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Text(
                            text = description,
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = textSecondaryColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .border(BorderStroke(2.dp, if (isSelected) activeColor else cardBorderColor), CircleShape)
                            .background(if (isSelected) activeColor else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// STEP 4 CONTENT: DAYS
// ==========================================
@Composable
fun Step4Days(
    daysPerWeek: Int,
    onDaysPerWeekChange: (Int) -> Unit,
    selectedDays: Set<String>,
    onSelectedDaysChange: (Set<String>) -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color,
    buttonBgColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "How many days?",
                fontFamily = OswaldFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "Days per week",
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                color = textSecondaryColor
            )
        }

        // Days per week picker cards grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val daysOptions = listOf(2, 3, 4, 5, 6, 7)
            daysOptions.take(3).forEach { d ->
                val isSelected = daysPerWeek == d
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(65.dp)
                        .border(
                            BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) primaryOrange else cardBorderColor
                            ),
                            RoundedCornerShape(10.dp)
                        )
                        .background(cardBgColor)
                        .clickable { onDaysPerWeekChange(d) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.toString(), fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor)
                            if (d in 3..5) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(10.dp))
                            }
                        }
                        Text("days", fontFamily = InterFontFamily, fontSize = 10.sp, color = textSecondaryColor)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val daysOptions = listOf(2, 3, 4, 5, 6, 7)
            daysOptions.drop(3).forEach { d ->
                val isSelected = daysPerWeek == d
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(65.dp)
                        .border(
                            BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) primaryOrange else cardBorderColor
                            ),
                            RoundedCornerShape(10.dp)
                        )
                        .background(cardBgColor)
                        .clickable { onDaysPerWeekChange(d) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.toString(), fontFamily = OswaldFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor)
                            if (d in 3..5) {
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(10.dp))
                            }
                        }
                        Text("days", fontFamily = InterFontFamily, fontSize = 10.sp, color = textSecondaryColor)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Which days work best?",
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = textColor
        )

        val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        Text(
            text = selectedDays.size.toString() + " of " + daysPerWeek + " selected",
            fontFamily = InterFontFamily,
            style = MaterialTheme.typography.labelMedium,
            color = textSecondaryColor,
            modifier = Modifier.testTag("planner_day_selection_count")
        )

        // 2 column pill grid
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until weekdays.size step 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val first = weekdays[i]
                    val isFirstSelected = selectedDays.contains(first)
                    val isFirstEnabled = isFirstSelected || selectedDays.size < daysPerWeek
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .border(
                                BorderStroke(
                                    width = if (isFirstSelected) 2.dp else 1.dp,
                                    color = if (isFirstSelected) primaryOrange else cardBorderColor
                                ),
                                RoundedCornerShape(12.dp)
                            )
                            .background(if (isFirstSelected) primaryOrange.copy(alpha = 0.1f) else cardBgColor)
                            .alpha(if (isFirstEnabled) 1f else 0.45f)
                            .clickable(enabled = isFirstEnabled) {
                                onSelectedDaysChange(
                                    togglePlannerDaySelection(selectedDays, first, daysPerWeek)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isFirstSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(16.dp))
                            }
                            Text(first, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textColor)
                        }
                    }

                    if (i + 1 < weekdays.size) {
                        val second = weekdays[i + 1]
                        val isSecondSelected = selectedDays.contains(second)
                        val isSecondEnabled = isSecondSelected || selectedDays.size < daysPerWeek
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .border(
                                    BorderStroke(
                                        width = if (isSecondSelected) 2.dp else 1.dp,
                                        color = if (isSecondSelected) primaryOrange else cardBorderColor
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .background(if (isSecondSelected) primaryOrange.copy(alpha = 0.1f) else cardBgColor)
                                .alpha(if (isSecondEnabled) 1f else 0.45f)
                                .clickable(enabled = isSecondEnabled) {
                                    onSelectedDaysChange(
                                        togglePlannerDaySelection(selectedDays, second, daysPerWeek)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isSecondSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(16.dp))
                                }
                                Text(second, fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textColor)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ==========================================
// STEP 5 CONTENT: SPLIT
// ==========================================
@Composable
fun Step5Split(
    trainingSplit: String,
    onSplitChange: (String) -> Unit,
    enforceRecovery: Boolean,
    onEnforceRecoveryChange: (Boolean) -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "Choose Training Split",
                fontFamily = OswaldFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "Select your program structure",
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                color = textSecondaryColor
            )
        }

        val splits = listOf(
            Triple("Upper / Lower", "Alternate between upper and lower body days", listOf("Upper", "Lower")),
            Triple("Full Body", "Train all major muscles in single sessions", listOf("All Muscles")),
            Triple("Push / Pull / Legs", "Focus on upper pushes, pulls, and lower legs", listOf("Push", "Pull", "Legs")),
            Triple("I don't know", "Not sure? We will choose the best structure for you.", listOf("Recommended for beginners"))
        )

        splits.forEach { (title, description, badges) ->
            val isSelected = trainingSplit == title
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primaryOrange else cardBorderColor
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onSplitChange(title) },
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )

                        RadioButton(
                            selected = isSelected,
                            onClick = { onSplitChange(title) },
                            colors = RadioButtonDefaults.colors(selectedColor = primaryOrange)
                        )
                    }

                    Text(
                        text = description,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { b ->
                            Box(
                                modifier = Modifier
                                    .background(primaryOrange.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = b,
                                    fontFamily = InterFontFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryOrange
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val recoveryPresentation = recoverySpacingPreferencePresentation()
        // Recovery preference card switch
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = cardBgColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recoveryPresentation.title,
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = textColor
                    )
                    Text(
                        text = recoveryPresentation.supportingText,
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        color = textSecondaryColor
                    )
                }

                Switch(
                    checked = enforceRecovery,
                    onCheckedChange = onEnforceRecoveryChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryOrange
                    )
                )
            }
        }
    }
}

// ==========================================
// STEP 6 CONTENT: EQUIPMENT
// ==========================================
@Composable
fun Step6Equipment(
    selectedEquipmentPreset: String,
    onEquipmentPresetChange: (String) -> Unit,
    selectedEquipment: Set<String>,
    onEquipmentChange: (Set<String>) -> Unit,
    freeWeightsExpanded: Boolean,
    onFreeWeightsToggle: () -> Unit,
    machinesExpanded: Boolean,
    onMachinesToggle: () -> Unit,
    bodyweightExpanded: Boolean,
    onBodyweightToggle: () -> Unit,
    textColor: Color,
    textSecondaryColor: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    primaryOrange: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "Equipment Access",
                fontFamily = OswaldFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "Select the equipment you have available",
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                color = textSecondaryColor
            )
        }

        // Equipment presets quick filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf("Full Gym", "Home Gym", "Bodyweight")
            presets.forEach { p ->
                val isSelected = selectedEquipmentPreset == p
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(
                            BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) primaryOrange else cardBorderColor
                            ),
                            RoundedCornerShape(12.dp)
                        )
                        .background(if (isSelected) primaryOrange.copy(alpha = 0.05f) else cardBgColor)
                        .clickable { onEquipmentPresetChange(p) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = textColor)
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Info box message
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.fitDesiColors.success.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.fitDesiColors.success, modifier = Modifier.size(18.dp))
                Text(
                    text = "All equipment categories will be used for maximum exercise variety.",
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = MaterialTheme.fitDesiColors.success,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Expandable categories list
        // Category 1: Free Weights
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFreeWeightsToggle() }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Free Weights",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColor
                    )
                    Icon(
                        imageVector = if (freeWeightsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = textSecondaryColor
                    )
                }

                if (freeWeightsExpanded) {
                    val items = listOf("Barbell", "Kettlebells", "Dumbbells")
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        items.forEach { item ->
                            val isChecked = selectedEquipment.contains(item)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) onEquipmentChange(selectedEquipment - item)
                                        else onEquipmentChange(selectedEquipment + item)
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(16.dp))
                                    Text(item, fontFamily = InterFontFamily, fontSize = 14.sp, color = textColor)
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (isChecked) onEquipmentChange(selectedEquipment - item)
                                        else onEquipmentChange(selectedEquipment + item)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryOrange)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Category 2: Machines
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMachinesToggle() }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Machines",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColor
                    )
                    Icon(
                        imageVector = if (machinesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = textSecondaryColor
                    )
                }

                if (machinesExpanded) {
                    val items = listOf("Cables", "Machine")
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        items.forEach { item ->
                            val isChecked = selectedEquipment.contains(item)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) onEquipmentChange(selectedEquipment - item)
                                        else onEquipmentChange(selectedEquipment + item)
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.SettingsInputComponent, contentDescription = null, tint = primaryOrange, modifier = Modifier.size(16.dp))
                                    Text(item, fontFamily = InterFontFamily, fontSize = 14.sp, color = textColor)
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (isChecked) onEquipmentChange(selectedEquipment - item)
                                        else onEquipmentChange(selectedEquipment + item)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryOrange)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Category 3: Bodyweight & Accessories
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val bodyweightItemsList = listOf("Bodyweight", "Resistance Bands")
                val selectedCount = bodyweightItemsList.count { selectedEquipment.contains(it) }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBodyweightToggle() }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bodyweight & Accessories",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                        if (selectedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .background(primaryOrange.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$selectedCount selected",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryOrange
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = if (bodyweightExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = textSecondaryColor
                    )
                }

                if (bodyweightExpanded) {
                    val items = listOf(
                        "Bodyweight" to "Bodyweight",
                        "Band" to "Resistance Bands"
                    )
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        items.forEach { (label, targetName) ->
                            val isChecked = selectedEquipment.contains(targetName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) onEquipmentChange(selectedEquipment - targetName)
                                        else onEquipmentChange(selectedEquipment + targetName)
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = if (label == "Bodyweight") Icons.Default.AccessibilityNew else Icons.Default.LinearScale,
                                        contentDescription = null,
                                        tint = primaryOrange,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(label, fontFamily = InterFontFamily, fontSize = 14.sp, color = textColor)
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (isChecked) onEquipmentChange(selectedEquipment - targetName)
                                        else onEquipmentChange(selectedEquipment + targetName)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = primaryOrange)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// STAGE 3: DETERMINISTIC PLAN CREATION
// ==========================================
@Composable
fun GeneratingStage(
    textColor: Color,
    textSecondaryColor: Color,
    primaryOrange: Color
) {
    var loadingTipIndex by remember { mutableStateOf(0) }
    val loadingTips = listOf(
        "Analyzing your fitness profile...",
        "Structuring your selected day split...",
        "Selecting targeted muscle exercises...",
        "Balancing rep ranges and progressive overload...",
        "Reviewing equipment accessibility restrictions..."
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L)
            loadingTipIndex = (loadingTipIndex + 1) % loadingTips.size
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            val infiniteTransition = rememberInfiniteTransition()
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(primaryOrange.copy(alpha = 0.15f * scale))
            )

            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(primaryOrange),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Creating plan",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Creating your plan",
            fontFamily = OswaldFontFamily,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Animating text tips
        AnimatedContent(
            targetState = loadingTips[loadingTipIndex],
            transitionSpec = {
                fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
            }
        ) { text ->
            Text(
                text = text,
                fontFamily = InterFontFamily,
                fontSize = 14.sp,
                color = textSecondaryColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

// ==========================================
// STAGE 4: RESULTS PREVIEW
// ==========================================
@Composable
fun ResultStage(
    routine: GeneratedRoutine?,
    errorMessage: String?,
    viewModel: TrainerViewModel,
    cardBgColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    primaryOrange: Color,
    onRetry: () -> Unit,
    onSave: () -> Unit
) {
    if (errorMessage != null || routine == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = Color.Red,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Plan creation failed",
                fontFamily = OswaldFontFamily,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage ?: "Plan creation is temporarily unavailable. Please try again later.",
                fontFamily = InterFontFamily,
                fontSize = 13.sp,
                color = textSecondaryColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = primaryOrange),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Try again", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    var selectedDayIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Overview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBgColor, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = routine.name,
                            fontFamily = OswaldFontFamily,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Box(
                            modifier = Modifier
                                .background(primaryOrange.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = routine.splitType,
                                fontFamily = InterFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryOrange
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = routine.description,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        lineHeight = 16.sp
                    )

                    if (routine.explanation.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Why this plan: ${routine.explanation}",
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = textColor
                        )
                    }

                    if (routine.safetyNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Safety: ${routine.safetyNote}",
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = textSecondaryColor,
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Frequency: ${routine.frequency}",
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable selector for days
            ScrollableTabRow(
                selectedTabIndex = selectedDayIndex,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                divider = {},
                indicator = { tabPositions ->
                    if (tabPositions.isNotEmpty()) {
                        TabRowDefaults.SecondaryIndicator(
                            color = primaryOrange,
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedDayIndex])
                        )
                    }
                }
            ) {
                routine.days.forEachIndexed { index, day ->
                    Tab(
                        selected = selectedDayIndex == index,
                        onClick = { selectedDayIndex = index },
                        text = {
                            Text(
                                text = day.dayName,
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        },
                        selectedContentColor = primaryOrange,
                        unselectedContentColor = textSecondaryColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Exercises List for current day
            val currentDay = routine.days.getOrNull(selectedDayIndex)
            if (currentDay != null) {
                val presentation = structuredWorkoutDayPresentation(currentDay)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        Column {
                            Text(
                                text = currentDay.title,
                                fontFamily = OswaldFontFamily,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                            Text(
                                text = currentDay.description,
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = textSecondaryColor
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    presentation.sections.forEach { section ->
                        if (!presentation.isLegacyDisplay) {
                            item { WorkoutResultPhaseHeading(section.phase.label, primaryOrange) }
                        }
                        section.generalWarmup?.let { warmup ->
                            item {
                                WorkoutResultGeneralWarmupCard(
                                    warmup = warmup,
                                    cardBgColor = cardBgColor,
                                    cardBorderColor = cardBorderColor,
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor,
                                    primaryOrange = primaryOrange
                                )
                            }
                        }
                        items(section.exercises) { ex ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = cardBgColor)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp)
                                ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ex.name,
                                        fontFamily = OswaldFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = textColor,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Box(
                                        modifier = Modifier
                                            .background(primaryOrange.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = buildString {
                                                append("${ex.sets} sets x ${ex.reps}")
                                                if (ex.restSeconds > 0) append(" • ${ex.restSeconds}s rest")
                                            },
                                            fontFamily = InterFontFamily,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryOrange
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Target: ${ex.targetMuscle}",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryOrange
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = ex.instructions,
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    color = textSecondaryColor,
                                    lineHeight = 14.sp
                                )
                                formatActivityPrescription(ex.activityPrescription)?.let { prescription ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = prescription,
                                        fontFamily = InterFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textColor
                                    )
                                }
                                if (section.phase == StructuredWorkoutPhase.MAIN_WORKOUT && ex.rampUpSets.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Ramp-up sets",
                                        fontFamily = InterFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryOrange
                                    )
                                    ex.rampUpSets.forEach { rampUpSet ->
                                        formatRampUpSetRow(rampUpSet)?.let { row ->
                                            Text(row, fontFamily = InterFontFamily, fontSize = 10.sp, color = textSecondaryColor)
                                        }
                                    }
                                }
                                displayedSafetyNote(ex)?.let { note ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Safety: $note",
                                        fontFamily = InterFontFamily,
                                        fontSize = 10.sp,
                                        color = textSecondaryColor,
                                        lineHeight = 13.sp
                                    )
                                }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Save Button Footer
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryOrange),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save Program to My Workouts", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun WorkoutResultPhaseHeading(label: String, primaryOrange: Color) {
    Text(
        text = label,
        fontFamily = OswaldFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = primaryOrange,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun WorkoutResultGeneralWarmupCard(
    warmup: GeneralWarmupPrescription,
    cardBgColor: Color,
    cardBorderColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    primaryOrange: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = warmup.label,
                fontFamily = OswaldFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            formatStructuredDuration(warmup.durationSeconds)?.let { duration ->
                Text(
                    text = "$duration • ${warmupIntensityLabel(warmup.intensityCue)}",
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = primaryOrange
                )
            }
            warmup.equipment?.takeIf(String::isNotBlank)?.let { equipment ->
                Text(
                    text = "Equipment: $equipment",
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = textSecondaryColor
                )
            }
        }
    }
}
