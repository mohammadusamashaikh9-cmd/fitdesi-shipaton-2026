package com.example.ui

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.AiCoachIntent
import com.example.ai.AiCoachResponse
import com.example.ai.AiCoachResponseMetadata
import com.example.ai.AiRepository
import com.example.ai.AiRuntimeConfig
import com.example.ai.AiRuntimeMode
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedDietPlanAlternative
import com.example.ai.GeneratedDietPlanDay
import com.example.ai.GeneratedDietPlanMeal
import com.example.ai.GeneratedPlanSource
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.GeneratedWorkoutPlanDay
import com.example.ai.GeneratedWorkoutPlanExercise
import com.example.ai.PlanValidationStatus
import com.example.ai.hasSavableGeneratedPlan
import com.example.ai.conversation.AiCoachMessage
import com.example.ai.conversation.AiCoachMessageRole
import com.example.security.AiSafetyPolicy
import com.example.ui.components.FitDesiBrandLockup
import com.example.ui.components.FitDesiPrimaryButton
import com.example.ui.components.FitDesiSecondaryButton
import com.example.ui.theme.FitDesiDimensions
import com.example.ui.theme.FitDesiMotion
import com.example.ui.theme.FitDesiSpacing
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.fitDesiColors
import com.example.viewmodel.CoachPlanSaveState
import com.example.viewmodel.AiCoachUiState
import com.example.viewmodel.CoachRemoteAction
import com.example.viewmodel.TrainerViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import java.text.DateFormat
import java.util.Date

private data class CoachSuggestion(val label: String, val prompt: String)

private const val COACH_ENTRY_DURATION_MS = 400
private const val COACH_THINKING_HALF_LOOP_MS = 800

private val coachSuggestions = listOf(
    CoachSuggestion("Build muscle", "How can I build muscle safely?"),
    CoachSuggestion("Fat loss", "Give me a sustainable fat loss approach."),
    CoachSuggestion("Pakistani diet", "Suggest a balanced Pakistani diet approach."),
    CoachSuggestion("Workout plan", "How should I structure a beginner workout plan?"),
    CoachSuggestion("Yoga & mobility", "Show me beginner yoga and mobility options."),
    CoachSuggestion("Protein target", "How should I think about my protein target?")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCoachScreen(
    onBack: () -> Unit,
    viewModel: TrainerViewModel,
    onViewSavedDietPlan: (String) -> Unit,
    modifier: Modifier = Modifier,
    repository: AiRepository = AiRepository()
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedRepositoryParameter = repository
    val runtimeMode = remember { AiRuntimeConfig.mode }
    val focusManager = LocalFocusManager.current
    val uiState by viewModel.aiCoachUiState.collectAsStateWithLifecycle()
    var historyVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        if (historyVisible) historyVisible = false else onBack()
    }

    fun submitQuestion() {
        focusManager.clearFocus()
        viewModel.submitAiCoachQuestion(repository)
    }

    AiCoachChatContent(
        state = uiState,
        runtimeMode = runtimeMode,
        onBack = onBack,
        onQuestionChange = viewModel::updateAiCoachQuestion,
        onSubmit = ::submitQuestion,
        onRetry = { viewModel.retryAiCoachQuestion(repository) },
        onHistory = { historyVisible = true },
        onNewChat = viewModel::startNewCoachChat,
        onSaveMessage = viewModel::saveCoachMessagePlan,
        onGrantConsent = viewModel::grantRemoteCoachConsent,
        onKeepLocal = viewModel::keepUsingLocalCoach,
        onViewSavedDietPlan = onViewSavedDietPlan,
        modifier = modifier
    )

    if (historyVisible) {
        CoachHistorySheet(
            state = uiState,
            onDismiss = { historyVisible = false },
            onSelect = { conversationId ->
                viewModel.selectCoachConversation(conversationId)
                historyVisible = false
            },
            onDelete = viewModel::deleteCoachConversation,
            onClear = viewModel::clearCoachHistory
        )
    }
}

@Composable
internal fun AiCoachChatContent(
    state: AiCoachUiState,
    runtimeMode: AiRuntimeMode,
    onBack: () -> Unit,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onSaveMessage: (String) -> Unit,
    onGrantConsent: () -> Unit,
    onKeepLocal: () -> Unit,
    onViewSavedDietPlan: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lastMessage = state.messages.lastOrNull()
    LaunchedEffect(lastMessage?.messageId, state.isLoading) {
        val itemCount = listState.layoutInfo.totalItemsCount
        val nearBottom = !listState.canScrollForward ||
            listState.firstVisibleItemIndex >= (itemCount - 4).coerceAtLeast(0)
        if (itemCount > 0 && (lastMessage?.role == AiCoachMessageRole.USER || nearBottom)) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        CoachChatHeader(
            runtimeMode = runtimeMode,
            onBack = onBack,
            onHistory = onHistory,
            onNewChat = onNewChat,
            actionsEnabled = !state.isLoading
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ai_coach_timeline"),
            contentPadding = PaddingValues(
                start = FitDesiSpacing.medium,
                top = FitDesiSpacing.medium,
                end = FitDesiSpacing.medium,
                bottom = FitDesiSpacing.section
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
        ) {
            if (state.messages.isEmpty()) {
                item(key = "empty") {
                    CoachChatEmptyState(
                        enabled = !state.isLoading,
                        onQuestionChange = onQuestionChange
                    )
                }
            } else {
                items(state.messages, key = AiCoachMessage::messageId) { message ->
                    when (message.role) {
                        AiCoachMessageRole.USER -> CoachUserMessage(message)
                        AiCoachMessageRole.COACH -> CoachAssistantMessage(
                            message = message,
                            saveState = state.messageSaveStates[message.messageId]
                                ?: CoachPlanSaveState.NONE,
                            onSave = { onSaveMessage(message.messageId) },
                            onViewSavedDietPlan = onViewSavedDietPlan
                        )
                    }
                }
            }
            if (state.isLoading) {
                item(key = "loading") { CoachLoadingTurn() }
            }
            state.backendNotice?.takeIf(String::isNotBlank)?.let { notice ->
                item(key = "notice-${notice.hashCode()}") { CoachNotice(notice) }
            }
            state.remoteAction?.let { action ->
                item(key = "remote-action-${action.name}") {
                    CoachRemoteActionCard(
                        action = action,
                        isUpdating = state.isConsentUpdating,
                        onGrantConsent = onGrantConsent,
                        onKeepLocal = onKeepLocal
                    )
                }
            }
            state.errorMessage?.let { error ->
                item(key = "error-${error.hashCode()}") {
                    CoachErrorState(message = error, onRetry = onRetry)
                }
            }
            state.historyError?.let { error ->
                item(key = "history-error") {
                    CoachNotice(error)
                }
            }
        }
        CoachComposer(
            question = state.question,
            isLoading = state.isLoading,
            hasCompletedResponse = state.messages.any { it.role == AiCoachMessageRole.COACH },
            onQuestionChange = onQuestionChange,
            onSubmit = onSubmit
        )
    }
}

@Composable
private fun CoachChatHeader(
    runtimeMode: AiRuntimeMode,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    actionsEnabled: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large.copy(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(0.dp)
        ),
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FitDesiSpacing.extraSmall, vertical = FitDesiSpacing.micro),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(FitDesiDimensions.minimumTouchTarget)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "FitDesi AI Coach",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    coachTrustLine(runtimeMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onHistory,
                enabled = actionsEnabled,
                modifier = Modifier
                    .size(FitDesiDimensions.minimumTouchTarget)
                    .testTag("ai_coach_history")
            ) {
                Icon(Icons.Default.History, contentDescription = "Conversation history")
            }
            IconButton(
                onClick = onNewChat,
                enabled = actionsEnabled,
                modifier = Modifier
                    .size(FitDesiDimensions.minimumTouchTarget)
                    .testTag("ai_coach_new_chat")
            ) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoachChatEmptyState(
    enabled: Boolean,
    onQuestionChange: (String) -> Unit
) {
    val motionEnabled = systemAnimationsEnabled()
    var entryComplete by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(motionEnabled) {
        entryComplete = true
    }
    val lockupAlpha by animateFloatAsState(
        targetValue = if (entryComplete) 1f else 0f,
        animationSpec = tween(COACH_ENTRY_DURATION_MS, easing = FastOutSlowInEasing),
        label = "Coach brand entry alpha"
    )
    val lockupScale by animateFloatAsState(
        targetValue = if (entryComplete) 1f else 0.96f,
        animationSpec = tween(COACH_ENTRY_DURATION_MS, easing = FastOutSlowInEasing),
        label = "Coach brand entry scale"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FitDesiSpacing.section)
            .testTag("ai_coach_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.medium)
    ) {
        FitDesiBrandLockup(
            markWidth = 34.dp,
            modifier = Modifier.graphicsLayer {
                alpha = lockupAlpha
                scaleX = lockupScale
                scaleY = lockupScale
            }
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
        ) {
            Text(
                "How can I help with your fitness today?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "Workouts, nutrition, progress and practical South Asian food guidance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
        ) {
            coachSuggestions.take(4).forEach { suggestion ->
                FilterChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onQuestionChange(suggestion.prompt) },
                    label = { Text(suggestion.label) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = FitDesiDimensions.minimumTouchTarget)
                        .testTag("ai_suggestion_${suggestion.label.lowercase().replace(' ', '_')}")
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "General fitness and nutrition education only — not medical care.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CoachUserMessage(message: AiCoachMessage) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .testTag("coach_user_${message.messageId}")
        ) {
            Column(
                modifier = Modifier.padding(FitDesiSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)
            ) {
                Text("You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CoachAssistantMessage(
    message: AiCoachMessage,
    saveState: CoachPlanSaveState,
    onSave: () -> Unit,
    onViewSavedDietPlan: (String) -> Unit
) {
    val response = message.response
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("coach_message_${message.messageId}"),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = null,
                    modifier = Modifier.padding(FitDesiSpacing.extraSmall).size(16.dp)
                )
            }
            Text("FitDesi Coach", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (response == null) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
        } else {
            CoachTimelineResponse(
                response = response,
                saveState = saveState,
                onSave = onSave,
                onViewSavedDietPlan = onViewSavedDietPlan
            )
        }
    }
}

@Composable
private fun CoachTimelineResponse(
    response: AiCoachResponse,
    saveState: CoachPlanSaveState,
    onSave: () -> Unit,
    onViewSavedDietPlan: (String) -> Unit
) {
    val isMedical = response.metadata.intent == AiCoachIntent.MEDICAL_ESCALATION
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
        CoachRichText(response.summary, style = MaterialTheme.typography.bodyLarge)
        if (isMedical) {
            CoachMedicalEscalation(response.recommendedAction)
        } else if (response.recommendedAction.isNotBlank()) {
            CoachActionSection(response.recommendedAction)
        }
        if (response.workoutNote.isNotBlank() || response.workoutPlan != null) {
            CoachWorkoutSection(response)
        }
        if (response.nutritionNote.isNotBlank() || response.dietPlan != null) {
            CoachNutritionSection(response)
        }
        CoachSafetySection(response.safetyDisclaimer, urgent = isMedical)
        if (response.hasSavableGeneratedPlan()) {
            CoachPlanActions(
                response = response,
                saveState = saveState,
                onSave = onSave,
                onViewSavedDietPlan = onViewSavedDietPlan
            )
        }
    }
}

@Composable
private fun CoachLoadingTurn() {
    val motionEnabled = systemAnimationsEnabled()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_coach_loading")
            .semantics {
                stateDescription = "Preparing Coach response"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
    ) {
        CoachThinkingMark(motionEnabled = motionEnabled)
        Text("Preparing your response…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CoachThinkingMark(motionEnabled: Boolean) {
    if (!motionEnabled) {
        CoachThinkingMarkImage()
        return
    }

    val transition = rememberInfiniteTransition(label = "Coach thinking")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(COACH_THINKING_HALF_LOOP_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Coach thinking scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(COACH_THINKING_HALF_LOOP_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Coach thinking alpha"
    )
    CoachThinkingMarkImage(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        }
    )
}

@Composable
private fun CoachThinkingMarkImage(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_fitdesi_mark),
        contentDescription = null,
        modifier = modifier.size(28.dp)
    )
}

@Composable
private fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }
    }
}

@Composable
private fun CoachRemoteActionCard(
    action: CoachRemoteAction,
    isUpdating: Boolean,
    onGrantConsent: () -> Unit,
    onKeepLocal: () -> Unit
) {
    val (title, body) = when (action) {
        CoachRemoteAction.SIGN_IN -> "Sign in required" to
            "Sign in from Profile, then deliberately send your message again. Local Coach remains available."
        CoachRemoteAction.VERIFY_EMAIL -> "Verify your email" to
            "Verify your email, refresh your account, then send again."
        CoachRemoteAction.CONSENT_REQUIRED -> "Choose remote processing" to
            "Remote AI Coach can send this question and limited relevant fitness or nutrition context through FitDesi's backend to a server-selected AI provider."
        CoachRemoteAction.CONSENT_NOTICE_CHANGED -> "Remote notice changed" to
            "Review the current remote-processing notice before using Remote Coach again."
        CoachRemoteAction.QUOTA_UNAVAILABLE -> "Remote Coach is unavailable" to
            "Remote usage is currently unavailable. Keep using local guidance."
        CoachRemoteAction.REQUEST_IN_PROGRESS -> "A request is already running" to
            "Wait for the current Coach response before sending another message."
        CoachRemoteAction.REMOTE_UNAVAILABLE -> "Using local guidance" to
            "Remote Coach is unavailable right now. Your local FitDesi guidance remains available."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("coach_remote_action"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action == CoachRemoteAction.CONSENT_REQUIRED ||
                action == CoachRemoteAction.CONSENT_NOTICE_CHANGED
            ) {
                FitDesiPrimaryButton(
                    text = if (isUpdating) "Updating…" else "Use Remote Coach",
                    enabled = !isUpdating,
                    onClick = onGrantConsent,
                    modifier = Modifier.fillMaxWidth().testTag("coach_grant_remote_consent")
                )
                FitDesiSecondaryButton(
                    text = "Keep Using Local Coach",
                    enabled = !isUpdating,
                    onClick = onKeepLocal,
                    modifier = Modifier.fillMaxWidth().testTag("coach_keep_local")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoachHistorySheet(
    state: AiCoachUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("coach_history_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Coach history",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                TextButton(onClick = onClear, enabled = state.conversationHistory.isNotEmpty()) {
                    Text("Clear all")
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                contentPadding = PaddingValues(bottom = FitDesiSpacing.section)
            ) {
                items(state.conversationHistory, key = { it.conversationId }) { summary ->
                    Surface(
                        onClick = { onSelect(summary.conversationId) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (summary.isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("coach_history_${summary.conversationId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(FitDesiSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(summary.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        if (summary.isCurrent) append("Current • ")
                                        append(formatConversationTime(summary.updatedAt))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDelete(summary.conversationId) },
                                modifier = Modifier.size(FitDesiDimensions.minimumTouchTarget)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${summary.title}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatConversationTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun CoachErrorState(message: String, onRetry: () -> Unit) {
    Crossfade(targetState = message, animationSpec = tween(200), label = "Coach error") { currentMessage ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_coach_error")
                .semantics {
                    stateDescription = "Error"
                    liveRegion = LiveRegionMode.Polite
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(FitDesiSpacing.content),
                verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "Guidance could not be prepared",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Text(currentMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                FitDesiPrimaryButton(text = "Retry", onClick = onRetry)
                Text(
                    "You can also edit your question below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun CoachNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CoachMedicalEscalation(recommendedAction: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Assertive
                stateDescription = "Urgent safety guidance"
            }
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.content),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
            Text(
                "Urgent safety guidance",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            CoachRichText(recommendedAction, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun CoachActionSection(body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    "YOUR NEXT ACTION",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = FitDesiSpacing.medium, vertical = FitDesiSpacing.extraSmall)
                )
            }
            Box(modifier = Modifier.padding(start = FitDesiSpacing.medium, end = FitDesiSpacing.medium, bottom = FitDesiSpacing.medium)) {
                CoachRichText(body)
            }
        }
    }
}

@Composable
private fun CoachWorkoutSection(response: AiCoachResponse) {
    val plan = response.workoutPlan
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            CoachSectionHeading(Icons.Default.FitnessCenter, "WORKOUT", plan?.title ?: "Workout guidance")
            CoachRichText(response.workoutNote)
            plan?.let {
                Text(
                    "${it.numberOfDays} sessions • ${it.goal} • ${it.experienceLevel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                it.days.forEachIndexed { index, day ->
                    WorkoutDayCard(day = day, dayNumber = index + 1)
                }
                CoachSupportingNote("Progression", it.progressionGuidance)
                CoachSupportingNote("Recovery", it.recoveryGuidance)
                CoachSupportingNote("Plan safety", it.safetyNote)
            }
        }
    }
}

@Composable
private fun WorkoutDayCard(day: GeneratedWorkoutPlanDay, dayNumber: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)
        ) {
            Text("DAY $dayNumber", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(day.dayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(day.focus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            day.exercises.forEach { exercise ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f), MaterialTheme.shapes.small)
                        .padding(horizontal = FitDesiSpacing.small, vertical = FitDesiSpacing.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(exercise.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${exercise.sets} sets • ${exercise.repsOrDuration} • ${exercise.restSeconds}s rest",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachNutritionSection(response: AiCoachResponse) {
    val plan = response.dietPlan
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            CoachSectionHeading(Icons.Default.LocalDining, "NUTRITION", plan?.title ?: "Nutrition guidance")
            CoachRichText(response.nutritionNote)
            plan?.let {
                DietTargetSummary(it)
                it.days.forEach { day -> DietDayCard(day) }
                if (it.hydrationReminder.isNotBlank()) CoachSupportingNote("Hydration", it.hydrationReminder)
                CoachSupportingNote("Plan note", it.disclaimer)
            }
        }
    }
}

@Composable
private fun DietTargetSummary(plan: GeneratedDietPlan) {
    val targets = buildList {
        plan.calorieTarget?.let { add("$it kcal") }
        plan.proteinTargetGrams?.let { add("${formatNumber(it)} g protein") }
        plan.carbsTargetGrams?.let { add("${formatNumber(it)} g carbs") }
        plan.fatTargetGrams?.let { add("${formatNumber(it)} g fat") }
    }
    if (targets.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Text(
                targets.joinToString(" • "),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(FitDesiSpacing.small)
            )
        }
    }
}

@Composable
private fun DietDayCard(day: GeneratedDietPlanDay) {
    Surface(
        color = MaterialTheme.fitDesiColors.elevatedSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Text(day.dayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            day.meals.forEach { meal -> DietMealRow(meal) }
        }
    }
}

@Composable
private fun DietMealRow(meal: GeneratedDietPlanMeal) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f), MaterialTheme.shapes.small)
            .padding(FitDesiSpacing.small),
        verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)
    ) {
        Text(meal.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(meal.foodName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            meal.portionDescription.ifBlank { meal.storedServing },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NutritionLine(
            calories = meal.estimatedCalories,
            protein = meal.estimatedProteinGrams,
            carbs = meal.estimatedCarbsGrams,
            fat = meal.estimatedFatGrams
        )
        meal.additionalFoods.forEach { addition ->
            Text(
                "+ ${addition.foodName} — ${addition.portionDescription}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (meal.alternatives.isNotEmpty()) {
            Text("Alternatives", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            meal.alternatives.forEach { alternative ->
                Text(
                    "• ${alternative.foodName}${alternative.portionDescription.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NutritionLine(
                    calories = alternative.estimatedCalories,
                    protein = alternative.estimatedProteinGrams,
                    carbs = alternative.estimatedCarbsGrams,
                    fat = alternative.estimatedFatGrams
                )
                if (alternative.dietaryCompatibilityStatus != "NOT_RECORDED") {
                    Text(
                        alternative.dietaryCompatibilityStatus.replace('_', ' ').lowercase()
                            .replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NutritionLine(calories: Int?, protein: Double?, carbs: Double?, fat: Double?) {
    val values = buildList {
        calories?.let { add("$it kcal") }
        protein?.let { add("P ${formatNumber(it)} g") }
        carbs?.let { add("C ${formatNumber(it)} g") }
        fat?.let { add("F ${formatNumber(it)} g") }
    }
    if (values.isNotEmpty()) {
        Text(values.joinToString(" • "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoachSectionHeading(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    eyebrow: String,
    title: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(FitDesiSpacing.extraSmall).size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
private fun CoachSupportingNote(title: String, body: String) {
    if (body.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(FitDesiSpacing.small), verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            CoachRichText(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CoachSafetySection(body: String, urgent: Boolean) {
    val container = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
                Text("Safety boundary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                CoachRichText(body, color = content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun coachPlanSaveErrorMessage(hasWorkoutPlan: Boolean): String =
    if (hasWorkoutPlan) {
        "The workout could not be fully saved. Any completed save steps were kept; try again to finish."
    } else {
        "The plan could not be saved. Try again."
    }

internal fun coachPlanSaveSuccessMessage(
    hasWorkoutPlan: Boolean,
    state: CoachPlanSaveState
): String = when {
    hasWorkoutPlan && state == CoachPlanSaveState.SAVED ->
        "Saved to My Routines and set as current"
    hasWorkoutPlan -> "Already in My Routines and set as current"
    state == CoachPlanSaveState.SAVED -> "Plan saved"
    else -> "Already saved"
}

@Composable
private fun CoachPlanActions(
    response: AiCoachResponse,
    saveState: CoachPlanSaveState,
    onSave: () -> Unit,
    onViewSavedDietPlan: (String) -> Unit
) {
    val dietPlan = response.dietPlan
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, MaterialTheme.fitDesiColors.border),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "Plan save status"
            }
    ) {
        Column(
            modifier = Modifier.padding(FitDesiSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.small)
        ) {
            Text("PLAN ACTIONS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            AnimatedContent(
                targetState = saveState,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "Plan save state"
            ) { state ->
                when (state) {
                    CoachPlanSaveState.NONE,
                    CoachPlanSaveState.ERROR -> {
                        Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall)) {
                            FitDesiPrimaryButton(
                                text = if (state == CoachPlanSaveState.ERROR) "Try save again" else if (response.workoutPlan != null) "Save workout plan" else "Save diet plan",
                                onClick = onSave,
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(if (response.workoutPlan != null) "save_workout_plan" else "save_diet_plan")
                            )
                            if (state == CoachPlanSaveState.ERROR) {
                                Text(
                                    coachPlanSaveErrorMessage(hasWorkoutPlan = response.workoutPlan != null),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("coach_plan_save_error")
                                )
                            }
                        }
                    }
                    CoachPlanSaveState.SAVING -> Row(
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = "Saving plan" },
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Saving plan…", style = MaterialTheme.typography.bodyMedium)
                    }
                    CoachPlanSaveState.SAVED,
                    CoachPlanSaveState.ALREADY_SAVED -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.fitDesiColors.success)
                        Text(
                            coachPlanSaveSuccessMessage(
                                hasWorkoutPlan = response.workoutPlan != null,
                                state = state
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (dietPlan != null && saveState in setOf(CoachPlanSaveState.SAVED, CoachPlanSaveState.ALREADY_SAVED)) {
                FitDesiSecondaryButton(
                    text = "Open saved diet plan",
                    onClick = { onViewSavedDietPlan(dietPlan.planId) },
                    modifier = Modifier.fillMaxWidth().testTag("view_saved_diet_plan")
                )
            }
        }
    }
}

@Composable
private fun CoachRichText(
    body: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    val lines = body.lines()
    Column(verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)) {
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                Spacer(Modifier.height(FitDesiSpacing.micro))
            } else if (line.startsWith("- ") || line.startsWith("• ")) {
                Row(horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall), verticalAlignment = Alignment.Top) {
                    Text("•", style = style, color = color)
                    Text(line.drop(2), style = style, color = color, modifier = Modifier.weight(1f))
                }
            } else {
                Text(line, style = style, color = color)
            }
        }
    }
}

@Composable
private fun CoachComposer(
    question: String,
    isLoading: Boolean,
    hasCompletedResponse: Boolean,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val canSend = question.trim().isNotEmpty() &&
        question.length <= AiSafetyPolicy.MAX_INPUT_CHARACTERS &&
        !isLoading
    val sendColor by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(FitDesiMotion.fast),
        label = "Coach send color"
    )
    val composerBorder by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.fitDesiColors.border,
        animationSpec = tween(FitDesiMotion.fast),
        label = "Coach composer border"
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp)
        ),
        shadowElevation = 8.dp,
        border = BorderStroke(FitDesiDimensions.cardBorderWidth, composerBorder),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.padding(
                start = FitDesiSpacing.medium,
                top = FitDesiSpacing.extraSmall,
                end = FitDesiSpacing.medium,
                bottom = FitDesiSpacing.extraSmall
            ),
            verticalArrangement = Arrangement.spacedBy(FitDesiSpacing.micro)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FitDesiSpacing.extraSmall),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { value ->
                        if (value.length <= AiSafetyPolicy.MAX_INPUT_CHARACTERS) onQuestionChange(value)
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .testTag("ai_coach_input"),
                    placeholder = { Text("Message FitDesi Coach") },
                    minLines = 1,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                FilledIconButton(
                    onClick = onSubmit,
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sendColor,
                        contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = sendColor,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .size(FitDesiDimensions.primaryControlHeight)
                        .testTag("ai_coach_submit")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (isLoading) "Send unavailable while loading" else "Send question")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        isLoading -> "Wait for the current response"
                        hasCompletedResponse -> "Continue this conversation"
                        else -> "Ask about workouts, nutrition or progress"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(FitDesiSpacing.extraSmall))
                Text(
                    "${question.length}/${AiSafetyPolicy.MAX_INPUT_CHARACTERS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun coachTrustLine(runtimeMode: AiRuntimeMode): String = when (runtimeMode) {
    AiRuntimeMode.LOCAL -> "Offline Coach • local FitDesi knowledge"
    AiRuntimeMode.BACKEND_MOCK -> "FitDesi Coach • test response"
    AiRuntimeMode.BACKEND_REMOTE -> "FitDesi Coach • service response"
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) {
    value.toInt().toString()
} else {
    String.format(Locale.ROOT, "%.1f", value)
}

// Preview fixtures are presentation-only and never enter production state.
private fun previewResponse(
    summary: String = "A consistent plan built around progressive training, practical meals, and recovery will support this goal.",
    action: String = "Train four times this week and add one reviewed protein source to each main meal.",
    nutrition: String = "Use familiar Pakistani foods, keep portions practical, and adjust from your real targets.",
    workout: String = "Use controlled repetitions and progress only when technique remains steady.",
    safety: String = "General fitness guidance only. Stop and seek professional help for severe pain or medical concerns.",
    intent: AiCoachIntent = AiCoachIntent.GENERAL_COACHING,
    workoutPlan: GeneratedWorkoutPlan? = null,
    dietPlan: GeneratedDietPlan? = null
) = AiCoachResponse(
    summary = summary,
    recommendedAction = action,
    nutritionNote = nutrition,
    workoutNote = workout,
    safetyDisclaimer = safety,
    metadata = AiCoachResponseMetadata(
        sourceType = "LOCAL_KNOWLEDGE",
        profileContextUsed = true,
        intent = intent,
        validationStatus = if (workoutPlan != null || dietPlan != null) "VALIDATED" else null
    ),
    workoutPlan = workoutPlan,
    dietPlan = dietPlan
)

private fun previewWorkoutPlan() = GeneratedWorkoutPlan(
    planId = "preview-workout",
    title = "Balanced four-day strength plan",
    goal = "Build muscle",
    experienceLevel = "Intermediate",
    days = listOf(
        GeneratedWorkoutPlanDay(
            "Day 1", "Upper body",
            listOf(
                GeneratedWorkoutPlanExercise("preview-push", "Dumbbell press", "horizontal_push", 3, "8–12 reps", 90),
                GeneratedWorkoutPlanExercise("preview-row", "Dumbbell row", "horizontal_pull", 3, "8–12 reps", 90)
            )
        ),
        GeneratedWorkoutPlanDay(
            "Day 2", "Lower body",
            listOf(
                GeneratedWorkoutPlanExercise("preview-squat", "Goblet squat", "squat", 4, "8–10 reps", 120),
                GeneratedWorkoutPlanExercise("preview-hinge", "Romanian deadlift", "hinge", 3, "8–12 reps", 120)
            )
        )
    ),
    progressionGuidance = "Add a repetition before increasing load.",
    recoveryGuidance = "Keep recovery days between demanding sessions.",
    safetyNote = "Use controlled technique and stop for sharp pain.",
    createdAt = 1L,
    sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
    profileContextUsed = true
)

private fun previewDietPlan() = GeneratedDietPlan(
    planId = "preview-diet",
    title = "Practical Pakistani meal plan",
    goal = "Build muscle",
    calorieTarget = 2464,
    proteinTargetGrams = 100.0,
    carbsTargetGrams = 350.0,
    fatTargetGrams = 70.0,
    days = listOf(
        GeneratedDietPlanDay(
            "Today",
            listOf(
                GeneratedDietPlanMeal(
                    label = "Breakfast",
                    foodRecordId = "preview-egg",
                    foodName = "Egg and roti breakfast",
                    storedServing = "1 plate",
                    portionMultiplier = 1.0,
                    estimatedCalories = 520,
                    estimatedProteinGrams = 24.0,
                    estimatedCarbsGrams = 58.0,
                    estimatedFatGrams = 20.0,
                    alternatives = listOf(
                        GeneratedDietPlanAlternative(
                            foodRecordId = "preview-dal",
                            foodName = "Daal with roti",
                            portionDescription = "1 bowl and 2 rotis",
                            estimatedCalories = 500,
                            estimatedProteinGrams = 22.0,
                            estimatedCarbsGrams = 72.0,
                            estimatedFatGrams = 13.0,
                            dietaryCompatibilityStatus = "COMPATIBLE"
                        )
                    ),
                    portionDescription = "2 eggs and 2 rotis"
                ),
                GeneratedDietPlanMeal(
                    label = "Lunch",
                    foodRecordId = "preview-chana",
                    foodName = "Chana with rice",
                    storedServing = "1 plate",
                    portionMultiplier = 1.0,
                    estimatedCalories = 610,
                    estimatedProteinGrams = 25.0,
                    estimatedCarbsGrams = 95.0,
                    estimatedFatGrams = 14.0,
                    alternatives = emptyList(),
                    portionDescription = "1 bowl chana and 1 cup rice"
                )
            )
        )
    ),
    hydrationReminder = "Drink water regularly through the day.",
    disclaimer = "Nutrition values are estimates and can vary by recipe.",
    createdAt = 1L,
    sourceType = GeneratedPlanSource.LOCAL_KNOWLEDGE,
    profileContextUsed = true,
    validationStatus = PlanValidationStatus.VALIDATED
)

@Composable
private fun CoachPreview(
    theme: String,
    state: AiCoachUiState
) {
    MyPersonalTrainerTheme(theme = theme) {
        AiCoachChatContent(
            state = state,
            runtimeMode = AiRuntimeMode.LOCAL,
            onBack = {},
            onQuestionChange = {},
            onSubmit = {},
            onRetry = {},
            onHistory = {},
            onNewChat = {},
            onSaveMessage = {},
            onGrantConsent = {},
            onKeepLocal = {},
            onViewSavedDietPlan = {}
        )
    }
}

private fun previewChatState(response: AiCoachResponse? = null, loading: Boolean = false): AiCoachUiState {
    val userMessage = AiCoachMessage(
        messageId = "preview-user",
        role = AiCoachMessageRole.USER,
        timestamp = 1L,
        text = "Build me a practical fitness plan."
    )
    val messages = if (response == null) {
        listOf(userMessage)
    } else {
        listOf(
            userMessage,
            AiCoachMessage(
                messageId = "preview-coach",
                role = AiCoachMessageRole.COACH,
                timestamp = 2L,
                text = response.summary,
                response = response
            )
        )
    }
    return AiCoachUiState(
        activeConversationId = "preview-conversation",
        messages = messages,
        isLoading = loading,
        messageSaveStates = response?.let { mapOf("preview-coach" to CoachPlanSaveState.NONE) }.orEmpty()
    )
}

@Preview(name = "Chat V2 empty light", showBackground = true, widthDp = 320, heightDp = 720)
@Composable private fun CoachEmptyLightPreview() = CoachPreview(
    theme = "Light",
    state = AiCoachUiState(activeConversationId = "preview-empty")
)

@Preview(name = "Chat V2 conversation dark", showBackground = true, backgroundColor = 0xFF0E0F11, widthDp = 360, heightDp = 900)
@Composable private fun CoachConversationDarkPreview() = CoachPreview(
    theme = "Dark",
    state = previewChatState(previewResponse())
)

@Preview(name = "Chat V2 loading", showBackground = true, widthDp = 360, heightDp = 780)
@Composable private fun CoachLoadingPreview() = CoachPreview(
    theme = "Dark",
    state = previewChatState(loading = true)
)

@Preview(name = "Chat V2 workout message", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable private fun CoachWorkoutPlanPreview() = CoachPreview(
    theme = "Dark",
    state = previewChatState(previewResponse(workoutPlan = previewWorkoutPlan()))
)

@Preview(name = "Chat V2 diet message", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable private fun CoachDietPlanPreview() = CoachPreview(
    theme = "Light",
    state = previewChatState(previewResponse(dietPlan = previewDietPlan())).copy(
        messageSaveStates = mapOf("preview-coach" to CoachPlanSaveState.SAVED)
    )
)
