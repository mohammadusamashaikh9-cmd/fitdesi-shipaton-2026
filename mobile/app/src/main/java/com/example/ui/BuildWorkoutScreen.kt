package com.example.ui

import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine

import android.app.Application
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.fitdesi.data.Exercise
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.viewmodel.ExerciseUiState
import com.example.viewmodel.ExerciseViewModel
import com.example.viewmodel.TrainerViewModel
import com.google.gson.Gson

data class SelectedExerciseConfig(
    val exercise: Exercise,
    val sets: Int = 3,
    val reps: String = "12"
)

internal const val BUILD_WORKOUT_EXERCISE_LOAD_ERROR =
    "Exercise library could not be loaded. Try again."

internal sealed interface BuildWorkoutCatalogueContent {
    data object Loading : BuildWorkoutCatalogueContent
    data object EmptyCatalogue : BuildWorkoutCatalogueContent
    data object NoMatches : BuildWorkoutCatalogueContent
    data class Ready(val exercises: List<Exercise>) : BuildWorkoutCatalogueContent
    data class Error(
        val message: String = BUILD_WORKOUT_EXERCISE_LOAD_ERROR,
        val retryAvailable: Boolean = true
    ) : BuildWorkoutCatalogueContent
}

internal fun filterBuildWorkoutExercises(
    exercises: List<Exercise>,
    query: String,
    category: String
): List<Exercise> {
    val hasQuery = query.isNotBlank()
    return exercises.filter { exercise ->
        val queryMatches = !hasQuery ||
            exercise.name.contains(query, ignoreCase = true) ||
            exercise.target.orEmpty().contains(query, ignoreCase = true) ||
            exercise.bodyPart.orEmpty().contains(query, ignoreCase = true)
        val categoryMatches = category.equals("All", ignoreCase = true) ||
            exercise.bodyPart.orEmpty().equals(category, ignoreCase = true) ||
            exercise.category.orEmpty().equals(category, ignoreCase = true)
        queryMatches && categoryMatches
    }
}

internal fun buildWorkoutCatalogueContent(
    uiState: ExerciseUiState,
    query: String,
    category: String
): BuildWorkoutCatalogueContent = when (uiState) {
    ExerciseUiState.Loading -> BuildWorkoutCatalogueContent.Loading
    is ExerciseUiState.Error -> BuildWorkoutCatalogueContent.Error()
    is ExerciseUiState.Success -> {
        if (uiState.exercises.isEmpty()) {
            BuildWorkoutCatalogueContent.EmptyCatalogue
        } else {
            val filtered = filterBuildWorkoutExercises(uiState.exercises, query, category)
            if (filtered.isEmpty()) {
                BuildWorkoutCatalogueContent.NoMatches
            } else {
                BuildWorkoutCatalogueContent.Ready(filtered)
            }
        }
    }
}

internal fun MutableList<SelectedExerciseConfig>.toggleBuildWorkoutExercise(exercise: Exercise) {
    if (any { it.exercise.id == exercise.id }) {
        removeAll { it.exercise.id == exercise.id }
    } else {
        add(SelectedExerciseConfig(exercise = exercise))
    }
}

internal fun MutableList<SelectedExerciseConfig>.updateBuildWorkoutExercise(
    exerciseId: String,
    update: (SelectedExerciseConfig) -> SelectedExerciseConfig
) {
    val index = indexOfFirst { it.exercise.id == exerciseId }
    if (index >= 0) this[index] = update(this[index])
}

internal fun SelectedExerciseConfig.toGeneratedExercise(): GeneratedExercise = GeneratedExercise(
    name = exercise.name,
    sets = sets,
    reps = reps,
    targetMuscle = exercise.target ?: exercise.bodyPart ?: "General",
    instructions = exercise.instructions?.en.orEmpty(),
    exerciseId = exercise.id
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildWorkoutScreen(
    viewModel: TrainerViewModel,
    onBack: () -> Unit,
    onRoutineLimitReached: () -> Unit,
    modifier: Modifier = Modifier,
    exerciseViewModel: ExerciseViewModel = composeViewModel()
) {
    val context = LocalContext.current

    // Fields for customized workout plan setup
    var workoutName by remember { mutableStateOf("My Custom Strength Workout") }
    var workoutDescription by remember { mutableStateOf("Custom workout created manually.") }

    // List of chosen/selected exercises
    val selectedExercises = remember { mutableStateListOf<SelectedExerciseConfig>() }
    var isSavingWorkout by remember { mutableStateOf(false) }

    // Exercise search & filter state
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    // Categories list for chips
    val categories = remember {
        listOf("All", "chest", "back", "shoulders", "upper arms", "lower legs", "cardio")
    }

    val exerciseUiState by exerciseViewModel.uiState.collectAsStateWithLifecycle()
    val catalogueContent = remember(exerciseUiState, searchQuery, selectedCategory) {
        buildWorkoutCatalogueContent(exerciseUiState, searchQuery, selectedCategory)
    }

    // Theme values
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
                    Column {
                        Text(
                            text = "CREATE WORKOUT",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            color = onSurfaceColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Hand-pick exercises and specify sets/reps",
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = onSurfaceSecondaryColor
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("build_workout_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to dashboard",
                            tint = onSurfaceColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor)
            )
        },
        bottomBar = {
            // Sticky Save Action bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = cardBackgroundColor,
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${selectedExercises.size} Selected",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                        Text(
                            text = "Customize details below",
                            fontFamily = InterFontFamily,
                            fontSize = 11.sp,
                            color = onSurfaceSecondaryColor
                        )
                    }

                    Button(
                        onClick = {
                            if (isSavingWorkout) return@Button
                            if (workoutName.isBlank()) {
                                Toast.makeText(context, "Please enter a workout name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (selectedExercises.isEmpty()) {
                                Toast.makeText(context, "Select at least one exercise to save!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            // Convert chosen configuration to GeneratedRoutine
                            val routine = GeneratedRoutine(
                                name = workoutName,
                                description = workoutDescription,
                                splitType = "Custom Plan",
                                frequency = "1-Day Protocol",
                                days = listOf(
                                    GeneratedDay(
                                        dayName = "Workout Plan",
                                        title = workoutName,
                                        description = workoutDescription,
                                        exercises = selectedExercises.map(SelectedExerciseConfig::toGeneratedExercise)
                                    )
                                )
                            )

                            isSavingWorkout = true
                            val json = Gson().toJson(routine)
                            viewModel.saveCustomWorkoutPlan(json) { result ->
                                isSavingWorkout = false
                                when (result.toAuthoredRoutineSaveAction()) {
                                    AuthoredRoutineSaveAction.COMPLETE -> {
                                        Toast.makeText(context, "Workout Plan Created & Saved! 🎉", Toast.LENGTH_LONG).show()
                                        onBack()
                                    }
                                    AuthoredRoutineSaveAction.OPEN_ROUTINE_LIMIT_PAYWALL -> {
                                        onRoutineLimitReached()
                                    }
                                    AuthoredRoutineSaveAction.SHOW_INVALID_ERROR -> {
                                        Toast.makeText(context, "This workout plan is incomplete and could not be saved.", Toast.LENGTH_LONG).show()
                                    }
                                    AuthoredRoutineSaveAction.SHOW_STORAGE_ERROR -> {
                                        Toast.makeText(context, "Workout plan could not be saved. Please try again.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSavingWorkout,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("save_workout_plan_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SAVE PLAN",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Workout Metadata Setup
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Workout Details",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = accentColor
                        )

                        OutlinedTextField(
                            value = workoutName,
                            onValueChange = { workoutName = it },
                            label = { Text("Workout Name", fontFamily = InterFontFamily) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("workout_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = cardBorderColor
                            )
                        )

                        OutlinedTextField(
                            value = workoutDescription,
                            onValueChange = { workoutDescription = it },
                            label = { Text("Short Description / Objective", fontFamily = InterFontFamily) },
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("workout_desc_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = cardBorderColor
                            )
                        )
                    }
                }
            }

            // Section 2: Selected Exercises List
            item {
                Text(
                    text = "SELECTED EXERCISES (${selectedExercises.size})",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = onSurfaceColor,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (selectedExercises.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, cardBorderColor.copy(alpha = 0.5f)), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = onSurfaceSecondaryColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No exercises selected yet",
                                fontFamily = OswaldFontFamily,
                                fontSize = 14.sp,
                                color = onSurfaceColor
                            )
                            Text(
                                text = "Search & select exercises from the list below",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = onSurfaceSecondaryColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(selectedExercises) { config ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(12.dp))
                            .testTag("selected_item_${config.exercise.id}"),
                        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = config.exercise.name.uppercase(),
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = onSurfaceColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Target: ${config.exercise.target?.uppercase() ?: "GENERAL"}",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Configuration row: Sets stepper and Reps slider/text
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Sets configuration
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Sets:",
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                            color = onSurfaceSecondaryColor
                                        )
                                        IconButton(
                                            onClick = {
                                                if (config.sets > 1) {
                                                    selectedExercises.updateBuildWorkoutExercise(config.exercise.id) {
                                                        it.copy(sets = it.sets - 1)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, "Less sets", modifier = Modifier.size(14.dp))
                                        }
                                        Text(
                                            text = "${config.sets}",
                                            fontFamily = OswaldFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = onSurfaceColor
                                        )
                                        IconButton(
                                            onClick = {
                                                if (config.sets < 12) {
                                                    selectedExercises.updateBuildWorkoutExercise(config.exercise.id) {
                                                        it.copy(sets = it.sets + 1)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Add, "More sets", modifier = Modifier.size(14.dp))
                                        }
                                    }

                                    // Reps input/selection
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Reps:",
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                            color = onSurfaceSecondaryColor
                                        )

                                        // Quick simple toggle or increments for reps
                                        IconButton(
                                            onClick = {
                                                val currentRepsInt = config.reps.toIntOrNull() ?: 12
                                                if (currentRepsInt > 1) {
                                                    selectedExercises.updateBuildWorkoutExercise(config.exercise.id) {
                                                        it.copy(reps = "${currentRepsInt - 2}")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, "Less reps", modifier = Modifier.size(14.dp))
                                        }

                                        Text(
                                            text = config.reps,
                                            fontFamily = OswaldFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = onSurfaceColor
                                        )

                                        IconButton(
                                            onClick = {
                                                val currentRepsInt = config.reps.toIntOrNull() ?: 12
                                                if (currentRepsInt < 50) {
                                                    selectedExercises.updateBuildWorkoutExercise(config.exercise.id) {
                                                        it.copy(reps = "${currentRepsInt + 2}")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Add, "More reps", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }

                            // Delete button
                            IconButton(
                                onClick = {
                                    selectedExercises.remove(config)
                                },
                                modifier = Modifier.testTag("delete_selected_${config.exercise.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove exercise",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: Add Exercise Options Selector
            item {
                Text(
                    text = "CHOOSE EXERCISE OPTIONS",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = onSurfaceColor,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // Search Bar & Filter chips
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search exercises...", fontFamily = InterFontFamily) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("exercise_search_bar"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = cardBorderColor
                        ),
                        singleLine = true
                    )

                    // Horizontal chips row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categories) { category ->
                            val isSelected = selectedCategory == category
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategory = category },
                                label = {
                                    Text(
                                        text = category.uppercase(),
                                        fontFamily = OswaldFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentColor,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Exercise catalogue state and options list
            when (val content = catalogueContent) {
                BuildWorkoutCatalogueContent.Loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                            .testTag("exercise_catalogue_loading"),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = accentColor)
                    }
                }

                BuildWorkoutCatalogueContent.EmptyCatalogue -> item {
                    CatalogueMessage(
                        text = "The exercise catalogue is empty.",
                        testTag = "exercise_catalogue_empty",
                        color = onSurfaceSecondaryColor
                    )
                }

                BuildWorkoutCatalogueContent.NoMatches -> item {
                    CatalogueMessage(
                        text = "No matching exercises found.",
                        testTag = "exercise_catalogue_no_matches",
                        color = onSurfaceSecondaryColor
                    )
                }

                is BuildWorkoutCatalogueContent.Error -> item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                            .testTag("exercise_catalogue_error"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = content.message,
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        if (content.retryAvailable) {
                            Button(
                                onClick = exerciseViewModel::loadOfflineExercises,
                                modifier = Modifier.testTag("exercise_catalogue_retry")
                            ) {
                                Text("RETRY", fontFamily = OswaldFontFamily)
                            }
                        }
                    }
                }

                is BuildWorkoutCatalogueContent.Ready -> items(
                    items = content.exercises,
                    key = { it.id }
                ) { exercise ->
                    val isAlreadySelected = selectedExercises.any { it.exercise.id == exercise.id }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, cardBorderColor), RoundedCornerShape(12.dp))
                            .testTag("option_exercise_${exercise.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = cardBackgroundColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = exercise.name.uppercase(),
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = onSurfaceColor
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Part: ${exercise.bodyPart?.uppercase() ?: "BODY"}",
                                        fontFamily = InterFontFamily,
                                        fontSize = 10.sp,
                                        color = accentColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(3.dp)
                                            .clip(CircleShape)
                                            .background(onSurfaceSecondaryColor.copy(alpha = 0.5f))
                                    )
                                    Text(
                                        text = "Equip: ${exercise.equipment?.uppercase() ?: "NONE"}",
                                        fontFamily = InterFontFamily,
                                        fontSize = 10.sp,
                                        color = onSurfaceSecondaryColor
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Add / Remove action button
                            if (isAlreadySelected) {
                                FilledIconButton(
                                    onClick = {
                                        selectedExercises.toggleBuildWorkoutExercise(exercise)
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = accentColor.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("add_remove_${exercise.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                OutlinedIconButton(
                                    onClick = {
                                        selectedExercises.toggleBuildWorkoutExercise(exercise)
                                    },
                                    border = BorderStroke(1.dp, accentColor),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("add_remove_${exercise.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Select",
                                        tint = accentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Extra space at bottom to prevent save button overlap
            item {
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun CatalogueMessage(
    text: String,
    testTag: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = InterFontFamily,
            fontSize = 13.sp,
            color = color,
            textAlign = TextAlign.Center
        )
    }
}
