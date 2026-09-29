package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CalorieLog
import com.example.domain.JournalDayKind
import com.example.domain.LocalCalendarDate
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressJournalDay
import com.example.domain.progressCaloriesForEntry
import com.example.ui.theme.FitDesiSpacing
import com.example.viewmodel.ProgressUiState
import java.text.DateFormatSymbols
import kotlin.math.roundToInt

@Composable
fun FitnessJournalScreen(
    state: ProgressUiState,
    onDateSelected: (LocalCalendarDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedDay = state.journalDays.firstOrNull { it.date == state.selectedJournalDate }
        ?: state.journalDays.firstOrNull()
    val showDateChips = state.dateSelection is ProgressDateSelection.Range &&
        state.analytics?.currentWindow?.calendarDays?.let { it <= 31 } == true
    LazyColumn(
        modifier = modifier.testTag("fitness_journal"),
        contentPadding = PaddingValues(FitDesiSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        item {
            Text("JOURNAL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                if (state.journalDays.isEmpty()) "No recorded days in this period" else "${state.journalDays.count { it.kind != JournalDayKind.NEITHER }} recorded days",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Workout and meal records stay grouped by local date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDateChips && state.journalDays.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                    items(state.journalDays, key = { it.date.toString() }) { day ->
                        FilterChip(
                            selected = day.date == selectedDay?.date,
                            onClick = { onDateSelected(day.date) },
                            label = {
                                Column {
                                    Text(formatJournalChipDate(day.date), fontWeight = FontWeight.SemiBold)
                                    Text(journalDayMarker(day.kind), style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.testTag("journal_date_${day.date.year}_${day.date.month}_${day.date.dayOfMonth}")
                        )
                    }
                }
            }
        }
        if (selectedDay != null && showDateChips) {
            item {
                AnimatedContent(
                    targetState = selectedDay,
                    transitionSpec = {
                        (slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 6 } + fadeOut(tween(220)))
                    },
                    label = "journal-day"
                ) { day -> JournalDayCard(day, state, initiallyExpanded = true) }
            }
        } else if (state.journalDays.isEmpty()) {
            item { ProgressEmptyCard("Nothing was logged in this period. Choose another period when you’re ready.") }
        } else {
            var previousMonth: Pair<Int, Int>? = null
            state.journalDays.forEach { day ->
                val month = day.date.year to day.date.month
                if (month != previousMonth) {
                    item(key = "month-${month.first}-${month.second}") {
                        Text(
                            "${DateFormatSymbols.getInstance().months[month.second - 1]} ${month.first}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    previousMonth = month
                }
                item(key = "journal-${day.date}") {
                    JournalDayCard(day, state, initiallyExpanded = false)
                }
            }
        }
    }
}

@Composable
private fun JournalDayCard(day: ProgressJournalDay, state: ProgressUiState, initiallyExpanded: Boolean) {
    var expanded by remember(day.date) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
            .clickable { expanded = !expanded }
            .testTag("journal_daily_summary"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(FitDesiSpacing.medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatJournalDate(day.date), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${journalDayMarker(day.kind)}  •  ${day.workoutEntries.size} workouts  •  ${day.nutritionTotals.calories} kcal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse day" else "Expand day")
            }
            if (expanded) {
                JournalDailySummary(day, state)
                Text("TRAINING", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (day.workoutEntries.isEmpty()) {
                    Text("No workout was logged on this date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    day.workoutEntries.forEach { workout ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(workout.exerciseName, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    workout.category.takeIf(String::isNotBlank),
                                    formatProgressDuration(if (workout.durationSeconds > 0) workout.durationSeconds.toLong() else workout.durationMinutes * 60L),
                                    workout.completedSets.takeIf { it > 0 }?.let { "$it sets" },
                                    workout.liftingVolumeKg.takeIf { it > 0.0 }?.let { "${it.roundToInt()} kg" }
                                ).joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text("MEALS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (day.mealGroups.isEmpty()) {
                    Text("No food was logged on this date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    day.mealGroups.forEach { group ->
                        Text(group.mealType, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        group.entries.forEach { entry -> JournalNutritionEntry(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalDailySummary(day: ProgressJournalDay, state: ProgressUiState) {
    val isToday = day.date == state.today
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${day.nutritionTotals.calories} kcal consumed")
        Text(
            "Protein ${day.nutritionTotals.loggedProteinGrams.roundToInt()} g  •  " +
                "Carbs ${day.nutritionTotals.loggedCarbsGrams.roundToInt()} g  •  " +
                "Fat ${day.nutritionTotals.loggedFatGrams.roundToInt()} g",
            style = MaterialTheme.typography.bodySmall
        )
        if (isToday) state.targets.calorieTarget?.let { Text("Current goal reference $it kcal", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun JournalNutritionEntry(entry: CalorieLog) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(entry.description, fontWeight = FontWeight.SemiBold)
        Text(
            "${entry.servings} serving(s)  •  ${progressCaloriesForEntry(entry)} kcal  •  " +
                "P ${(entry.proteinGrams * entry.servings).roundToInt()} g  " +
                "C ${(entry.carbsGrams * entry.servings).roundToInt()} g  " +
                "F ${(entry.fatGrams * entry.servings).roundToInt()} g",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun journalDayMarker(kind: JournalDayKind): String = when (kind) {
    JournalDayKind.TRAINING_ONLY -> "Training"
    JournalDayKind.NUTRITION_ONLY -> "Nutrition"
    JournalDayKind.BOTH -> "Training + nutrition"
    JournalDayKind.NEITHER -> "No logs"
}

private fun formatJournalChipDate(date: LocalCalendarDate): String =
    "${DateFormatSymbols.getInstance().shortMonths[date.month - 1]} ${date.dayOfMonth}"

private fun formatJournalDate(date: LocalCalendarDate): String =
    "${DateFormatSymbols.getInstance().months[date.month - 1]} ${date.dayOfMonth}, ${date.year}"
