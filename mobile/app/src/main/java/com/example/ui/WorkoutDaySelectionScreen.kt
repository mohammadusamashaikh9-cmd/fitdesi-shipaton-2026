package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.GeneratedRoutine
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutDaySelectionScreen(
    routine: GeneratedRoutine,
    sourceRoutineId: String?,
    hasDraft: Boolean,
    restoredContext: ActiveWorkoutContext?,
    onBack: () -> Unit,
    onExecuteSelectedDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDayIndex by remember(routine, sourceRoutineId) { mutableStateOf<Int?>(null) }
    val selectedContext = selectedDayIndex?.let { index ->
        routine.toActiveWorkoutContext(index, sourceRoutineId)
    }
    val relationship = selectedContext?.let { context ->
        activeDraftRelationship(hasDraft, restoredContext, context)
    }
    val backgroundColor = MaterialTheme.colorScheme.background

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = { Text("Choose workout day", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Workout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor)
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    selectedDayIndex?.let(onExecuteSelectedDay)
                },
                enabled = selectedContext != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FitDesiSpacing.medium)
                    .height(FitDesiDimensions.minimumTouchTarget)
                    .testTag("workout_day_execute")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text(
                    if (relationship == ActiveDraftRelationship.EXACT_PLAN_DAY_MATCH) {
                        "Continue selected day"
                    } else {
                        "Start selected day"
                    }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .selectableGroup(),
            contentPadding = PaddingValues(
                start = FitDesiSpacing.medium,
                end = FitDesiSpacing.medium,
                top = FitDesiSpacing.small,
                bottom = FitDesiSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            item {
                Text(
                    text = routine.name.ifBlank { "Current workout plan" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Select the exact plan day you want to track. No day is selected automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (routine.days.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("workout_day_empty"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = "This plan has no workout days and cannot be started.",
                            modifier = Modifier.padding(FitDesiSpacing.medium),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                itemsIndexed(routine.days, key = { index, _ -> index }) { index, day ->
                    val selected = selectedDayIndex == index
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { selectedDayIndex = index },
                                role = Role.RadioButton
                            )
                            .testTag("workout_day_option_$index"),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(FitDesiSpacing.medium),
                            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = day.dayName.ifBlank { "Day ${index + 1}" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = day.title.ifBlank { "Workout day" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                day.focus
                                    .takeIf(String::isNotBlank)
                                    ?.let { focus ->
                                        Text(
                                            text = "Focus: $focus",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}
