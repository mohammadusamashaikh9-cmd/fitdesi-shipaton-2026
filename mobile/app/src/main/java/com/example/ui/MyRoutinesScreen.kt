package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.GeneratedExercise
import com.example.data.SavedRoutine
import com.example.data.SavedRoutineOrigin
import com.example.ui.theme.FitDesiSpacing
import com.example.viewmodel.TrainerViewModel

/** Library destination for locally built and generated routines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyRoutinesScreen(
    viewModel: TrainerViewModel?,
    onBack: () -> Unit,
    onChooseWorkoutDay: (SavedRoutine) -> Unit,
    modifier: Modifier = Modifier
) {
    val routines by if (viewModel != null) {
        viewModel.savedRoutines.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<SavedRoutine>()) }
    }
    var selectedRoutine by remember { mutableStateOf<SavedRoutine?>(null) }
    var routinePendingDeletion by remember { mutableStateOf<SavedRoutine?>(null) }
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background

    BackHandler(enabled = selectedRoutine != null) { selectedRoutine = null }

    Scaffold(
        modifier = modifier,
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My Routines", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (routines.isEmpty()) "No saved routines yet" else "${routines.size} saved routines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedRoutine == null) onBack() else selectedRoutine = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Workout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        val selected = selectedRoutine
        if (selected != null) {
            RoutineDetail(
                savedRoutine = selected,
                onUseAsCurrent = { viewModel?.selectSavedRoutine(selected) },
                onChooseWorkoutDay = {
                    viewModel?.selectSavedRoutine(selected)
                    onChooseWorkoutDay(selected)
                },
                onDelete = { routinePendingDeletion = selected },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
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
                    Text(
                        text = "SAVED ROUTINES",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                if (routines.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(FitDesiSpacing.medium),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
                            ) {
                                Icon(Icons.Default.EventNote, contentDescription = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("No saved routines", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Build a routine or save a generated workout to keep it here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(routines, key = SavedRoutine::routineId) { savedRoutine ->
                        RoutineListItem(
                            savedRoutine = savedRoutine,
                            onOpen = { selectedRoutine = savedRoutine },
                            onDelete = { routinePendingDeletion = savedRoutine }
                        )
                    }
                }
            }
        }
    }

    routinePendingDeletion?.let { target ->
        RoutineDeleteConfirmationDialog(
            routineName = target.routine.name,
            onDismiss = { routinePendingDeletion = null },
            onConfirm = {
                routinePendingDeletion = null
                val currentViewModel = viewModel
                if (currentViewModel == null) {
                    Toast.makeText(context, "Routine could not be deleted", Toast.LENGTH_SHORT).show()
                } else {
                    currentViewModel.deleteSavedRoutine(target.routineId) { deleted ->
                        if (deleted && selectedRoutine?.routineId == target.routineId) {
                            selectedRoutine = null
                        }
                        val message = if (deleted) "Routine deleted" else "Routine could not be deleted"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun RoutineListItem(
    savedRoutine: SavedRoutine,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.EventNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = savedRoutine.routine.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${savedRoutine.routine.days.size} sessions • ${routineOriginLabel(savedRoutine.origin)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${savedRoutine.routine.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Open ${savedRoutine.routine.name}")
        }
    }
}

@Composable
private fun RoutineDetail(
    savedRoutine: SavedRoutine,
    onUseAsCurrent: () -> Unit,
    onChooseWorkoutDay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FitDesiSpacing.medium,
            end = FitDesiSpacing.medium,
            bottom = FitDesiSpacing.extraLarge
        ),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
    ) {
        item {
            Text(savedRoutine.routine.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${savedRoutine.routine.frequency} • ${routineOriginLabel(savedRoutine.origin)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
                OutlinedButton(onClick = onUseAsCurrent, modifier = Modifier.weight(1f)) {
                    Text("Use as current")
                }
                ElevatedButton(onClick = onChooseWorkoutDay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Choose workout day")
                }
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FitDesiSpacing.small),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Delete routine")
            }
        }
        items(savedRoutine.routine.days, key = { it.dayName }) { day ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(FitDesiSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(day.dayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(day.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    RoutineDayDetails(day)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
internal fun RoutineDayDetails(day: com.example.ai.GeneratedDay) {
    val presentation = structuredWorkoutDayPresentation(day)
    if (presentation.isLegacyDisplay) {
        day.exercises.forEach { exercise ->
            Text(
                text = "${exercise.name} • ${exercise.sets} × ${exercise.reps}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        presentation.sections.forEach { section ->
            Text(
                text = section.phase.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { heading() }
            )
            section.generalWarmup?.let { warmup ->
                Text(warmup.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                formatStructuredDuration(warmup.durationSeconds)?.let { duration ->
                    Text(
                        "$duration • ${warmupIntensityLabel(warmup.intensityCue)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                warmup.equipment?.takeIf(String::isNotBlank)?.let { equipment ->
                    Text(
                        "Equipment: $equipment",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            section.exercises.forEach { exercise ->
                RoutineStructuredExercise(
                    exercise = exercise,
                    showRampUpSets = section.phase == StructuredWorkoutPhase.MAIN_WORKOUT
                )
            }
        }
    }
}

@Composable
internal fun RoutineDeleteConfirmationDialog(
    routineName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $routineName?") },
        text = { Text("This removes only this saved routine. This action cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun RoutineStructuredExercise(
    exercise: GeneratedExercise,
    showRampUpSets: Boolean
) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = "${exercise.name} • ${exercise.sets} × ${exercise.reps}",
            style = MaterialTheme.typography.bodySmall
        )
        formatActivityPrescription(exercise.activityPrescription)?.let { prescription ->
            Text(
                text = prescription,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (exercise.instructions.isNotBlank()) {
            Text(
                text = exercise.instructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showRampUpSets && exercise.rampUpSets.isNotEmpty()) {
            Text(
                text = "Ramp-up sets",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            exercise.rampUpSets.forEach { rampUpSet ->
                formatRampUpSetRow(rampUpSet)?.let { row ->
                    Text(row, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        displayedSafetyNote(exercise)?.let { note ->
            Text(
                text = "Safety: $note",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun routineOriginLabel(origin: SavedRoutineOrigin): String = when (origin) {
    SavedRoutineOrigin.BUILD_ROUTINE -> "Build Routine"
    SavedRoutineOrigin.AI_WORKOUT_GENERATOR -> "Generated plan"
    SavedRoutineOrigin.CUSTOM_WORKOUT -> "Custom workout"
    SavedRoutineOrigin.LEGACY_ACTIVE -> "Saved routine"
}
