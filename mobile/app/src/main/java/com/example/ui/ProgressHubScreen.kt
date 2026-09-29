package com.example.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.LocalCalendarDate
import com.example.domain.ProgressDatePreset
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionResult
import com.example.viewmodel.OlderHistoryAction
import com.example.viewmodel.ProgressUiState
import com.example.viewmodel.ProgressViewModel

enum class ProgressHubSection(val label: String) {
    TRAINING("Training"),
    NUTRITION("Nutrition"),
    COMBINED("Combined"),
    JOURNAL("Journal")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressHubScreen(
    viewModel: ProgressViewModel,
    initialSection: ProgressHubSection,
    onBack: () -> Unit,
    onFullHistoryRequired: () -> Unit,
    onAdvancedAnalyticsRequired: () -> Unit,
    onManageWorkoutHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProgressHubContent(
        state = state,
        initialSection = initialSection,
        onBack = onBack,
        onFullHistoryRequired = onFullHistoryRequired,
        onAdvancedAnalyticsRequired = onAdvancedAnalyticsRequired,
        onManageWorkoutHistory = onManageWorkoutHistory,
        onDateSelected = { date ->
            if (viewModel.selectJournalDate(date) == OlderHistoryAction.REQUIRES_FULL_HISTORY) {
                onFullHistoryRequired()
            }
        },
        onShift = viewModel::shiftDateSelection,
        onPreset = viewModel::requestDatePreset,
        modifier = modifier
    )
}

@Composable
internal fun ProgressDateRefreshEffect(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnRefresh()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = currentOnRefresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        currentOnRefresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressHubContent(
    state: ProgressUiState,
    initialSection: ProgressHubSection,
    onBack: () -> Unit,
    onFullHistoryRequired: () -> Unit,
    onAdvancedAnalyticsRequired: () -> Unit,
    onManageWorkoutHistory: () -> Unit,
    onDateSelected: (LocalCalendarDate) -> Unit,
    onShift: (Int) -> ProgressDateSelectionResult,
    onPreset: (ProgressDatePreset, ProgressDateSelection.Range?) -> ProgressDateSelectionResult,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember(initialSection) { mutableStateOf(initialSection) }
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("progress_hub_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Progress", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Workout")
                    }
                },
                actions = {
                    if (selectedSection == ProgressHubSection.JOURNAL) {
                        IconButton(
                            onClick = onManageWorkoutHistory,
                            modifier = Modifier.testTag("progress_manage_workout_history")
                        ) {
                            Icon(Icons.Default.History, contentDescription = "Manage workout history")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ProgressMomentumHeader(state)
            ScrollableTabRow(
                selectedTabIndex = selectedSection.ordinal,
                modifier = Modifier.fillMaxWidth().testTag("progress_section_tabs"),
                edgePadding = 0.dp
            ) {
                ProgressHubSection.entries.forEach { section ->
                    Tab(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        text = { Text(section.label) },
                        modifier = Modifier.testTag("progress_tab_${section.name.lowercase()}")
                    )
                }
            }
            ProgressDateNavigator(
                selection = state.dateSelection,
                today = state.today,
                canViewFullHistory = state.canViewFullHistory,
                onShift = onShift,
                onPreset = onPreset,
                onFullHistoryRequired = onFullHistoryRequired
            )
            AnimatedContent(
                modifier = Modifier.weight(1f),
                targetState = selectedSection,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "progress-section"
            ) { section ->
                when (section) {
                    ProgressHubSection.TRAINING -> ProgressTrainingScreen(
                        state = state,
                        onAdvancedAnalyticsRequired = onAdvancedAnalyticsRequired,
                        modifier = Modifier.fillMaxSize()
                    )
                    ProgressHubSection.NUTRITION -> ProgressNutritionScreen(state, Modifier.fillMaxSize())
                    ProgressHubSection.COMBINED -> ProgressCombinedScreen(state, Modifier.fillMaxSize())
                    ProgressHubSection.JOURNAL -> FitnessJournalScreen(
                        state = state,
                        onDateSelected = onDateSelected,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
