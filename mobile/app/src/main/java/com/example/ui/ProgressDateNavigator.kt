package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import com.example.domain.LocalCalendarDate
import com.example.domain.ProgressDatePolicy
import com.example.domain.ProgressDatePreset
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionPolicy
import com.example.domain.ProgressDateSelectionResult
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressDateNavigator(
    selection: ProgressDateSelection,
    today: LocalCalendarDate,
    canViewFullHistory: Boolean,
    onShift: (Int) -> ProgressDateSelectionResult,
    onPreset: (ProgressDatePreset, ProgressDateSelection.Range?) -> ProgressDateSelectionResult,
    onFullHistoryRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOptions by remember { mutableStateOf(false) }
    var showCustomPicker by remember { mutableStateOf(false) }
    fun handlePresetResult(result: ProgressDateSelectionResult) {
        when (result) {
            is ProgressDateSelectionResult.Allowed -> showOptions = false
            ProgressDateSelectionResult.RequiresFullHistory -> {
                showOptions = false
                onFullHistoryRequired()
            }
            ProgressDateSelectionResult.Future,
            is ProgressDateSelectionResult.Invalid -> Unit
        }
    }
    val range = selection as? ProgressDateSelection.Range
    val nextEnabled = range?.let { current ->
        val timeZone = TimeZone.getDefault()
        val days = ProgressDateSelectionPolicy.inclusiveDayCount(current, timeZone)
        ProgressDatePolicy.plusCalendarDays(current.endDateInclusive, days, timeZone) <= today
    } == true
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = {
                if (onShift(-1) == ProgressDateSelectionResult.RequiresFullHistory) onFullHistoryRequired()
            },
            enabled = range != null,
            modifier = Modifier.testTag("progress_period_previous")
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous period")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable { showOptions = true }
                .testTag("progress_period_picker"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                progressSelectionLabel(selection),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Icon(Icons.Default.CalendarMonth, contentDescription = "Choose progress dates", modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(
            onClick = { onShift(1) },
            enabled = nextEnabled,
            modifier = Modifier.testTag("progress_period_next")
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next period")
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            ProgressPeriodPickerContent(
                canViewFullHistory = canViewFullHistory,
                onPresetSelected = { preset -> handlePresetResult(onPreset(preset, null)) },
                onChooseDates = {
                    showOptions = false
                    showCustomPicker = true
                },
                onFullHistoryRequired = {
                    showOptions = false
                    onFullHistoryRequired()
                }
            )
        }
    }

    if (showCustomPicker) {
        ProgressCustomDateSheet(
            selection = range,
            today = today,
            canViewFullHistory = canViewFullHistory,
            onDismiss = { showCustomPicker = false },
            onConfirm = { custom ->
                when (onPreset(ProgressDatePreset.CUSTOM, custom)) {
                    ProgressDateSelectionResult.RequiresFullHistory -> {
                        showCustomPicker = false
                        onFullHistoryRequired()
                    }
                    is ProgressDateSelectionResult.Allowed -> showCustomPicker = false
                    else -> Unit
                }
            }
        )
    }
}

@Composable
internal fun ProgressPeriodPickerContent(
    canViewFullHistory: Boolean,
    onPresetSelected: (ProgressDatePreset) -> Unit,
    onChooseDates: () -> Unit,
    onFullHistoryRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("progress_period_picker_content")) {
        Text(
            "Choose progress period",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        listOf(
            ProgressDatePreset.RECENT_30_DAYS to "Recent 30 days",
            ProgressDatePreset.THIS_WEEK to "This week",
            ProgressDatePreset.THIS_MONTH to "This month"
        ).forEach { (preset, label) ->
            ProgressPresetRow(label) { onPresetSelected(preset) }
        }
        ProgressPresetRow("Choose dates", onClick = onChooseDates)
        ProgressPresetRow(
            label = "All history",
            locked = !canViewFullHistory,
            testTag = "progress_all_history"
        ) {
            if (canViewFullHistory) onPresetSelected(ProgressDatePreset.ALL_HISTORY)
            else onFullHistoryRequired()
        }
        HorizontalDivider()
        Text(
            if (canViewFullHistory) "Choose any saved history period." else "Basic includes today and the previous 29 local dates.",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProgressPresetRow(
    label: String,
    locked: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            if (locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Plus", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "$label requires Plus",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressCustomDateSheet(
    selection: ProgressDateSelection.Range?,
    today: LocalCalendarDate,
    canViewFullHistory: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ProgressDateSelection.Range) -> Unit
) {
    val oldest = if (canViewFullHistory) null else ProgressDatePolicy.basicOldestAllowedDate(today, TimeZone.getDefault())
    val minimumMillis = oldest?.toPickerMillis()
    val maximumMillis = today.toPickerMillis()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = selection?.startDate?.toPickerMillis(),
        initialSelectedEndDateMillis = selection?.endDateInclusive?.toPickerMillis(),
        selectableDates = remember(minimumMillis, maximumMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= maximumMillis && (minimumMillis == null || utcTimeMillis >= minimumMillis)
            }
        }
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            DateRangePicker(
                state = state,
                title = { Text("Choose dates", modifier = Modifier.padding(16.dp)) },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        val start = state.selectedStartDateMillis?.fromPickerMillis()
                        val end = state.selectedEndDateMillis?.fromPickerMillis()
                        if (start != null && end != null) onConfirm(ProgressDateSelection.Range(start, end))
                    },
                    enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
                ) { Text("Apply") }
            }
        }
    }
}

internal fun progressSelectionLabel(selection: ProgressDateSelection): String = when (selection) {
    ProgressDateSelection.AllHistory -> "All history"
    is ProgressDateSelection.Range -> {
        val months = DateFormatSymbols.getInstance().shortMonths
        val start = "${months[selection.startDate.month - 1]} ${selection.startDate.dayOfMonth}"
        val end = "${months[selection.endDateInclusive.month - 1]} ${selection.endDateInclusive.dayOfMonth}, ${selection.endDateInclusive.year}"
        "$start – $end"
    }
}

private fun LocalCalendarDate.toPickerMillis(): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(year, month - 1, dayOfMonth)
}.timeInMillis

private fun Long.fromPickerMillis(): LocalCalendarDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    timeInMillis = this@fromPickerMillis
}.let { LocalCalendarDate(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
