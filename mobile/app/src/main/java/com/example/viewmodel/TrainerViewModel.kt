package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FitDesiApplication
import com.example.data.AppDatabase
import com.example.data.CalorieLog
import com.example.data.ExerciseEntity
import com.example.data.TrainerRepository
import com.example.data.WorkoutLog
import com.example.data.WorkoutMuscleLoadEntity
import com.example.data.api.ExerciseDBExercise
import com.example.data.ProfileRepository
import com.example.data.FoodRepository
import com.example.data.SavedPlanRepository
import com.example.data.SavedPlanResult
import com.example.data.SavedRoutine
import com.example.data.SavedRoutineOrigin
import com.example.data.SavedRoutineRepository
import com.example.data.SavedRoutineResult
import com.example.data.UserProfile
import com.example.ai.AiCoachRequest
import com.example.ai.AiCoachResponse
import com.example.ai.AiCoachExecutionState
import com.example.ai.AiRepository
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedRoutine
import com.example.ai.GeneratedWorkoutPlan
import com.example.ai.hasSavableGeneratedPlan
import com.example.ai.toGeneratedRoutine
import com.example.ai.knowledge.LocalCoachContextStore
import com.example.ai.knowledge.KnowledgePackLoader
import com.example.ai.knowledge.KnowledgePackRegistry
import com.example.ai.knowledge.ProjectFoodRecord
import com.example.ai.knowledge.FoodDietaryClassification
import com.example.ai.knowledge.toLocalCoachContext
import com.example.security.AiSafetyPolicy
import com.example.security.BuildWeekRuntimeConfig
import com.example.security.SafeLog
import com.example.subscription.SubscriptionCapability
import com.example.subscription.EffectiveCapabilityPolicy
import com.example.subscription.SubscriptionPolicy
import com.example.subscription.SubscriptionState
import com.example.subscription.hasAuthoritativeCapability
import com.example.domain.CompletedExerciseSetCount
import com.example.domain.calculateMuscleWorkload
import com.example.exercise.ExerciseId
import com.example.exercise.ExerciseRepositoryProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncStatus {
    ONLINE, // Green dot: "Online – Latest exercises loaded"
    CACHED, // Yellow dot: "Using cached exercises"
    OFFLINE // Red dot: "Offline – Using essential exercises"
}

enum class WorkoutSaveResult {
    SAVED,
    DUPLICATE,
    ERROR
}

enum class NutritionTargetSaveResult {
    SAVED,
    REQUIRES_PLUS,
    INVALID_TARGETS,
    ERROR
}

enum class CoachPlanSaveState {
    NONE,
    SAVING,
    SAVED,
    ALREADY_SAVED,
    ERROR
}

internal fun GeneratedWorkoutPlan.isMateriallyEquivalentTo(other: GeneratedWorkoutPlan): Boolean =
    copy(createdAt = 0L) == other.copy(createdAt = 0L)

internal fun GeneratedRoutine.isMateriallyEquivalentTo(other: GeneratedRoutine): Boolean =
    copy(createdAt = 0L) == other.copy(createdAt = 0L)

internal fun SavedRoutine.isHistoricalWrapperForEquivalentRoutine(other: GeneratedRoutine): Boolean =
    routineId != routine.planId &&
        routine.copy(planId = "", createdAt = 0L) == other.copy(planId = "", createdAt = 0L)

internal fun normalizedRoutineContentIsEquivalent(
    first: GeneratedRoutine,
    second: GeneratedRoutine,
    stableRoutineId: (GeneratedRoutine) -> String
): Boolean {
    val firstId = stableRoutineId(first)
    val secondId = stableRoutineId(second)
    if (firstId != secondId) return false
    return first.copy(planId = firstId, createdAt = 0L) ==
        second.copy(planId = secondId, createdAt = 0L)
}

internal fun SubscriptionState.hasUnlimitedAuthoredRoutineCapability(): Boolean =
    hasAuthoritativeCapability(SubscriptionCapability.UNLIMITED_ROUTINES)

internal suspend fun saveNutritionTargetsWithCapability(
    calories: Int,
    carbsGrams: Float,
    proteinGrams: Float,
    fatGrams: Float,
    currentProfile: UserProfile,
    hasMacroTargetsCapability: () -> Boolean,
    persistProfile: suspend (UserProfile) -> Unit
): NutritionTargetSaveResult {
    if (calories <= 0 || carbsGrams <= 0f || proteinGrams <= 0f || fatGrams <= 0f) {
        return NutritionTargetSaveResult.INVALID_TARGETS
    }
    if (!runCatching(hasMacroTargetsCapability).getOrDefault(false)) {
        return NutritionTargetSaveResult.REQUIRES_PLUS
    }
    val updatedProfile = currentProfile.copy(
        customCalorieGoal = calories,
        customCarbGoalGrams = carbsGrams,
        customProteinGoalGrams = proteinGrams,
        customFatGoalGrams = fatGrams
    )
    return try {
        persistProfile(updatedProfile)
        NutritionTargetSaveResult.SAVED
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NutritionTargetSaveResult.ERROR
    }
}

internal suspend fun saveRoutineAndCoordinateActivePlan(
    routine: GeneratedRoutine,
    origin: SavedRoutineOrigin,
    saveRoutine: suspend (GeneratedRoutine, SavedRoutineOrigin) -> SavedRoutineResult,
    stableRoutineId: (GeneratedRoutine) -> String,
    getRoutine: suspend (String) -> SavedRoutine?,
    setActiveRoutine: suspend (GeneratedRoutine) -> Unit,
    refreshSavedRoutines: suspend () -> Unit
): SavedRoutineResult {
    val result = saveRoutine(routine, origin)
    if (result != SavedRoutineResult.SAVED && result != SavedRoutineResult.ALREADY_SAVED) {
        return result
    }
    val storedRoutine = getRoutine(stableRoutineId(routine)) ?: return SavedRoutineResult.ERROR
    return runCatching {
        setActiveRoutine(storedRoutine.routine)
        refreshSavedRoutines()
    }.fold(
        onSuccess = { result },
        onFailure = { SavedRoutineResult.ERROR }
    )
}

internal suspend fun saveCoachWorkoutPlan(
    incomingPlan: GeneratedWorkoutPlan,
    savedPlanRepository: SavedPlanRepository,
    savedRoutineRepository: SavedRoutineRepository,
    refreshSavedRoutines: suspend () -> Boolean,
    setActiveRoutine: suspend (GeneratedRoutine) -> Unit
): SavedPlanResult {
    try {
        if (!incomingPlan.isValid()) return SavedPlanResult.INVALID_PLAN

        val incomingRoutine = incomingPlan.toGeneratedRoutine()
        val existingPlan = savedPlanRepository.getWorkoutPlan(incomingPlan.planId)
        val existingRoutines = savedRoutineRepository.loadRoutines()
            .getOrElse { return SavedPlanResult.ERROR }
        val existingRoutine = existingRoutines.firstOrNull { it.routineId == incomingPlan.planId }
        val historicalRoutinesWithIncomingPlanId = existingRoutines.filter { savedRoutine ->
            savedRoutine.routineId != incomingPlan.planId && savedRoutine.routine.planId == incomingPlan.planId
        }
        val hasDivergentHistoricalPlanIdCollision = historicalRoutinesWithIncomingPlanId.any { savedRoutine ->
            !savedRoutine.isHistoricalWrapperForEquivalentRoutine(incomingRoutine)
        }
        if (hasDivergentHistoricalPlanIdCollision) {
            return SavedPlanResult.ERROR
        }
        val historicalEquivalentRoutine = historicalRoutinesWithIncomingPlanId.firstOrNull()
            ?: existingRoutines.firstOrNull { savedRoutine ->
                savedRoutine.isHistoricalWrapperForEquivalentRoutine(incomingRoutine)
            }

        if (existingPlan != null && !existingPlan.isMateriallyEquivalentTo(incomingPlan)) {
            return SavedPlanResult.ERROR
        }
        if (existingRoutine != null &&
            !existingRoutine.routine.isMateriallyEquivalentTo(incomingRoutine)
        ) {
            return SavedPlanResult.ERROR
        }

        var createdRequiredEntry = false
        if (existingPlan == null) {
            when (savedPlanRepository.saveWorkoutPlan(incomingPlan)) {
                SavedPlanResult.SAVED -> createdRequiredEntry = true
                SavedPlanResult.ALREADY_SAVED -> Unit
                SavedPlanResult.INVALID_PLAN -> return SavedPlanResult.INVALID_PLAN
                SavedPlanResult.ERROR -> return SavedPlanResult.ERROR
            }
        }

        val storedPlan = savedPlanRepository.getWorkoutPlan(incomingPlan.planId)
            ?: return SavedPlanResult.ERROR
        if (!storedPlan.isMateriallyEquivalentTo(incomingPlan)) return SavedPlanResult.ERROR

        val authoritativeRoutine = storedPlan.toGeneratedRoutine()
        if (existingRoutine != null &&
            !existingRoutine.routine.isMateriallyEquivalentTo(authoritativeRoutine)
        ) {
            return SavedPlanResult.ERROR
        }
        if (historicalEquivalentRoutine != null &&
            !historicalEquivalentRoutine.isHistoricalWrapperForEquivalentRoutine(authoritativeRoutine)
        ) {
            return SavedPlanResult.ERROR
        }
        if (existingRoutine == null && historicalEquivalentRoutine == null) {
            when (savedRoutineRepository.saveRoutine(
                authoritativeRoutine,
                SavedRoutineOrigin.AI_WORKOUT_GENERATOR
            )) {
                SavedRoutineResult.SAVED -> createdRequiredEntry = true
                SavedRoutineResult.ALREADY_SAVED -> Unit
                SavedRoutineResult.LIMIT_REACHED,
                SavedRoutineResult.INVALID_ROUTINE,
                SavedRoutineResult.ERROR -> return SavedPlanResult.ERROR
            }
        }

        val storedRoutineId = existingRoutine?.routineId ?: historicalEquivalentRoutine?.routineId
            ?: incomingPlan.planId
        val storedRoutine = savedRoutineRepository.getRoutine(storedRoutineId)
            ?: return SavedPlanResult.ERROR
        val storedRoutineIsEquivalent = if (storedRoutine.routineId == incomingPlan.planId) {
            storedRoutine.routine.isMateriallyEquivalentTo(authoritativeRoutine)
        } else {
            storedRoutine.isHistoricalWrapperForEquivalentRoutine(authoritativeRoutine)
        }
        if (!storedRoutineIsEquivalent) {
            return SavedPlanResult.ERROR
        }
        if (!refreshSavedRoutines()) {
            return SavedPlanResult.ERROR
        }
        setActiveRoutine(storedRoutine.routine)
        return if (createdRequiredEntry) SavedPlanResult.SAVED else SavedPlanResult.ALREADY_SAVED
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return SavedPlanResult.ERROR
    }
}

internal fun Job?.isCoachPlanSaveInFlight(): Boolean = this?.isCompleted == false

internal fun createCoachPlanSaveJobIfIdle(
    currentJob: Job?,
    scope: CoroutineScope,
    block: suspend CoroutineScope.() -> Unit
): Job? = if (currentJob.isCoachPlanSaveInFlight()) {
    null
} else {
    scope.launch(start = CoroutineStart.LAZY, block = block)
}

internal fun applyCoachSaveResultIfCurrent(
    current: AiCoachUiState,
    capturedResponse: AiCoachResponse,
    result: SavedPlanResult
): AiCoachUiState {
    if (current.response !== capturedResponse) return current
    return current.copy(
        saveState = when (result) {
            SavedPlanResult.SAVED -> CoachPlanSaveState.SAVED
            SavedPlanResult.ALREADY_SAVED -> CoachPlanSaveState.ALREADY_SAVED
            SavedPlanResult.INVALID_PLAN,
            SavedPlanResult.ERROR -> CoachPlanSaveState.ERROR
        }
    )
}

internal suspend fun deleteSavedRoutineAndCoordinateActivePlan(
    routineId: String,
    deletedRoutine: GeneratedRoutine,
    deletedRoutineStableId: String = routineId,
    activePlanJson: String,
    stableRoutineId: (GeneratedRoutine) -> String,
    deleteRoutine: suspend (String) -> Boolean,
    clearActivePlan: suspend () -> Unit,
    refreshSavedRoutines: suspend () -> Unit
): Boolean {
    val deleted = runCatching { deleteRoutine(routineId) }.getOrDefault(false)
    if (!deleted) return false

    val activeRoutine = activePlanJson
        .takeIf(String::isNotBlank)
        ?.let { json ->
            runCatching { Gson().fromJson(json, GeneratedRoutine::class.java) }.getOrNull()
        }
    val activePlanMatchesDeletedRoutine = activeRoutine?.let { active ->
        stableRoutineId(active) == deletedRoutineStableId &&
            normalizedRoutineContentIsEquivalent(active, deletedRoutine, stableRoutineId)
    } == true
    val activePlanUpdated = if (activePlanMatchesDeletedRoutine) {
        runCatching { clearActivePlan() }.isSuccess
    } else {
        true
    }
    val listRefreshed = runCatching { refreshSavedRoutines() }.isSuccess
    return activePlanUpdated && listRefreshed
}

data class AiCoachUiState(
    val question: String = "",
    val response: AiCoachResponse? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val saveState: CoachPlanSaveState = CoachPlanSaveState.NONE,
    val executionState: AiCoachExecutionState? = null,
    val backendFailureState: AiCoachExecutionState? = null,
    val retryAfterSeconds: Long? = null,
    val backendNotice: String? = null,
    val activeConversationId: String? = null,
    val conversationHistory: List<AiCoachConversationSummary> = emptyList(),
    val messages: List<com.example.ai.conversation.AiCoachMessage> = emptyList(),
    val messageSaveStates: Map<String, CoachPlanSaveState> = emptyMap(),
    val remoteAction: CoachRemoteAction? = null,
    val isConsentUpdating: Boolean = false,
    val historyError: String? = null
)

internal fun AiCoachUiState.withUpdatedCoachQuestion(
    question: String,
    currentSaveJob: Job?
): AiCoachUiState = copy(
    question = question,
    errorMessage = null,
    saveState = if (
        currentSaveJob.isCoachPlanSaveInFlight() &&
        saveState == CoachPlanSaveState.SAVING
    ) {
        CoachPlanSaveState.SAVING
    } else {
        CoachPlanSaveState.NONE
    },
    backendNotice = null
)

sealed interface SavedDietPlansUiState {
    data object Loading : SavedDietPlansUiState
    data class Success(val plans: List<GeneratedDietPlan>) : SavedDietPlansUiState
    data class Error(val message: String) : SavedDietPlansUiState
}

sealed interface ExercisesUiState {
    object Loading : ExercisesUiState
    data class Success(val exercises: List<ExerciseDBExercise>, val error: String? = null) : ExercisesUiState
    data class Error(val message: String) : ExercisesUiState
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// Extension Mapper Helpers
fun ExerciseEntity.toExerciseDBExercise(): ExerciseDBExercise {
    val setupList = howToSetup?.split("\n")?.filter { it.isNotEmpty() }
    val performList = howToPerform?.split("\n")?.filter { it.isNotEmpty() }
    val techniqueList = properTechnique?.split("\n")?.filter { it.isNotEmpty() }
    val avoidList = thingsToAvoid?.split("\n")?.filter { it.isNotEmpty() }
    
    val guide = if (setupList != null || performList != null || techniqueList != null || avoidList != null) {
        com.example.data.api.DetailedGuide(
            howToSetup = setupList,
            howToPerform = performList,
            properTechnique = techniqueList,
            thingsToAvoid = avoidList
        )
    } else {
        null
    }

    return ExerciseDBExercise(
        id = id.toString(),
        name = name,
        category = category,
        bodyPart = category,
        muscles = muscles.split(", ").map { it.trim() }.filter { it.isNotEmpty() },
        muscleGroups = muscles.split(", ").map { it.trim() }.filter { it.isNotEmpty() },
        target = muscles.split(", ").firstOrNull()?.trim(),
        equipment = equipment,
        difficulty = difficulty,
        instructions = instructions.split(". ").map { it.trim() }.filter { it.isNotEmpty() },
        steps = instructions.split(". ").map { it.trim() }.filter { it.isNotEmpty() },
        videoUrl = videoUrl,
        video = videoUrl,
        gifUrl = thumbnail,
        thumbnail = thumbnail,
        image = thumbnail,
        grip = grip,
        mechanic = mechanic,
        force = force,
        detailedGuide = guide
    )
}

fun ExerciseDBExercise.toEntity(): ExerciseEntity {
    return ExerciseEntity(
        id = getSafeId(),
        name = getSafeName(),
        category = getSafeCategory(),
        muscles = getSafeMuscles().joinToString(", "),
        equipment = getSafeEquipment(),
        difficulty = getSafeDifficulty(),
        instructions = getSafeInstructions().joinToString(". "),
        videoUrl = getSafeVideoUrl(),
        thumbnail = getSafeThumbnail(),
        grip = grip,
        mechanic = mechanic,
        force = force,
        howToSetup = detailedGuide?.howToSetup?.joinToString("\n"),
        howToPerform = detailedGuide?.howToPerform?.joinToString("\n"),
        properTechnique = detailedGuide?.properTechnique?.joinToString("\n"),
        thingsToAvoid = detailedGuide?.thingsToAvoid?.joinToString("\n")
    )
}

class TrainerViewModel(application: Application) : AndroidViewModel(application) {
    private val fitDesiApplication = application as FitDesiApplication
    private val repository: TrainerRepository
    private val profileRepository = ProfileRepository(application)
    private val savedPlanRepository = SavedPlanRepository(application)
    private val subscriptionRepository = fitDesiApplication.subscriptionRepository
    private val boostRepository = fitDesiApplication.boostRepository
    private val savedRoutineRepository = SavedRoutineRepository(application) {
        subscriptionRepository.state.value.hasUnlimitedAuthoredRoutineCapability()
    }
    private val gson = Gson()
    private val coachChatController = AiCoachChatController(
        scope = viewModelScope,
        conversationRepository = fitDesiApplication.aiCoachConversationRepository,
        authSession = fitDesiApplication.authRepository.session,
        responder = CoachQuestionResponder(fitDesiApplication.aiRepository::askCoachWithStatus),
        consentActions = object : CoachConsentActions {
            override suspend fun fetch() = fitDesiApplication.remoteAiConsentRepository.fetch()
            override suspend fun grantStandard(noticeVersion: String) =
                fitDesiApplication.remoteAiConsentRepository.grantStandard(noticeVersion)
            override suspend fun declineStandard() =
                fitDesiApplication.remoteAiConsentRepository.declineStandard()
        },
        savePlan = ::saveCoachResponsePlan
    )

    internal fun hasSubscriptionCapability(capability: SubscriptionCapability): Boolean =
        EffectiveCapabilityPolicy.hasCapability(
            subscriptionState = subscriptionRepository.state.value,
            boostAccessState = boostRepository.state.value,
            capability = capability
        )

    internal val canPresentMacroTargets: Flow<Boolean> = subscriptionRepository.state.map { state ->
        state.hasAuthoritativeCapability(SubscriptionCapability.MACRO_TARGETS)
    }

    val userProfile: StateFlow<UserProfile> = profileRepository.userProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UserProfile()
        )

    val waterIntake: StateFlow<Int> = profileRepository.waterIntakeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0
        )

    val favoriteExercises: StateFlow<List<String>> = profileRepository.favoriteExercisesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trainingNotes: StateFlow<List<String>> = profileRepository.trainingNotesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveFavoriteExercises(exercises: List<String>) {
        viewModelScope.launch { profileRepository.saveFavoriteExercises(exercises) }
    }

    fun saveTrainingNotes(notes: List<String>) {
        viewModelScope.launch { profileRepository.saveTrainingNotes(notes) }
    }

    fun saveWaterIntake(glasses: Int) {
        viewModelScope.launch {
            profileRepository.saveWaterIntake(glasses)
        }
    }

    val proteinGoal: StateFlow<Float> = userProfile
        .map { it.calculateProteinGrams() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    val carbsGoal: StateFlow<Float> = userProfile
        .map { it.calculateCarbGrams() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    val fatGoal: StateFlow<Float> = userProfile
        .map { it.calculateFatGrams() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    private val _exercisesState = MutableStateFlow<ExercisesUiState>(ExercisesUiState.Loading)
    val exercisesState: StateFlow<ExercisesUiState> = _exercisesState

    private val _syncStatus = MutableStateFlow(SyncStatus.OFFLINE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    val chatMessagesState = MutableStateFlow<List<ChatMessage>>(emptyList())
    val aiCoachUiState: StateFlow<AiCoachUiState> = coachChatController.state
    private val _savedDietPlansState = MutableStateFlow<SavedDietPlansUiState>(SavedDietPlansUiState.Loading)
    val savedDietPlansState: StateFlow<SavedDietPlansUiState> = _savedDietPlansState
    private val _savedRoutines = MutableStateFlow<List<SavedRoutine>>(emptyList())
    val savedRoutines: StateFlow<List<SavedRoutine>> = _savedRoutines
    private var coachPlanSaveJob: Job? = null

    fun addChatMessage(message: ChatMessage) {
        val boundedMessage = message.copy(text = AiSafetyPolicy.boundedMessageText(message.text))
        chatMessagesState.value = AiSafetyPolicy.boundedHistory(chatMessagesState.value + boundedMessage)
    }

    fun updateAiCoachQuestion(question: String) {
        coachChatController.updateDraft(question)
    }

    @Suppress("UNUSED_PARAMETER")
    fun submitAiCoachQuestion(aiRepository: AiRepository) {
        coachChatController.send()
    }

    @Suppress("UNUSED_PARAMETER")
    fun retryAiCoachQuestion(aiRepository: AiRepository) {
        coachChatController.retryLast()
    }

    fun saveCurrentCoachPlan() {
        val messageId = aiCoachUiState.value.messages
            .lastOrNull { it.response?.hasSavableGeneratedPlan() == true }
            ?.messageId
            ?: return
        coachChatController.saveMessagePlan(messageId)
    }

    fun startNewCoachChat() = coachChatController.newChat()

    fun selectCoachConversation(conversationId: String) =
        coachChatController.selectConversation(conversationId)

    fun deleteCoachConversation(conversationId: String) =
        coachChatController.deleteConversation(conversationId)

    fun clearCoachHistory() = coachChatController.clearHistory()

    fun saveCoachMessagePlan(messageId: String) =
        coachChatController.saveMessagePlan(messageId)

    fun grantRemoteCoachConsent() = coachChatController.grantRemoteConsent()

    fun keepUsingLocalCoach() = coachChatController.keepUsingLocalCoach()

    private suspend fun saveCoachResponsePlan(response: AiCoachResponse): SavedPlanResult {
        if (!response.hasSavableGeneratedPlan()) return SavedPlanResult.INVALID_PLAN
        val workoutPlan = response.workoutPlan
        val dietPlan = response.dietPlan
        if ((workoutPlan == null) == (dietPlan == null)) return SavedPlanResult.INVALID_PLAN
        return try {
            when {
                workoutPlan != null -> saveCoachWorkoutPlan(
                    incomingPlan = workoutPlan,
                    savedPlanRepository = savedPlanRepository,
                    savedRoutineRepository = savedRoutineRepository,
                    refreshSavedRoutines = ::refreshSavedRoutines,
                    setActiveRoutine = { storedRoutine ->
                        profileRepository.saveGeneratedWorkoutPlan(gson.toJson(storedRoutine))
                    }
                )
                dietPlan != null -> savedPlanRepository.saveDietPlan(dietPlan).also { result ->
                    if (result == SavedPlanResult.SAVED || result == SavedPlanResult.ALREADY_SAVED) {
                        refreshSavedDietPlans()
                    }
                }
                else -> SavedPlanResult.INVALID_PLAN
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SavedPlanResult.ERROR
        }
    }

    fun deleteSavedDietPlan(planId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = savedPlanRepository.deleteDietPlan(planId)
            if (deleted) {
                refreshSavedDietPlans()
            }
            onResult(deleted)
        }
    }

    fun retryLoadSavedDietPlans() {
        viewModelScope.launch { refreshSavedDietPlans(showLoading = true) }
    }

    private suspend fun refreshSavedDietPlans(showLoading: Boolean = false) {
        if (showLoading) _savedDietPlansState.value = SavedDietPlansUiState.Loading
        _savedDietPlansState.value = savedPlanRepository.loadDietPlans().fold(
            onSuccess = { SavedDietPlansUiState.Success(it) },
            onFailure = {
                SavedDietPlansUiState.Error(
                    "Saved diet plans could not be loaded. Your food logs were not affected."
                )
            }
        )
    }

    val dailyCalorieGoal = MutableStateFlow(0)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = TrainerRepository(database.trainerDao())
        fetchExercises()
        viewModelScope.launch { refreshSavedDietPlans(showLoading = true) }
        viewModelScope.launch { refreshSavedRoutines() }

        KnowledgePackRegistry.configure(KnowledgePackLoader.fromAssets(application))
        KnowledgePackRegistry.installLegacyFoods(
            FoodRepository(application).getPakistaniFoodRecords().map { record ->
                ProjectFoodRecord(
                    id = record.id,
                    name = record.name,
                    category = record.category,
                    servingSize = record.servingSize,
                    calories = record.calories,
                    proteinGrams = record.proteinGrams,
                    carbsGrams = record.carbsGrams,
                    fatGrams = record.fatGrams,
                    aliases = record.aliases,
                    nutritionBasis = record.nutritionBasis,
                    reviewStatus = record.fitDesiReviewStatus,
                    isNutritionReviewed = record.isLoggable,
                    runtimeSource = record.runtimeSource,
                    dietaryClassification = runCatching {
                        FoodDietaryClassification.valueOf(record.dietaryClassification)
                    }.getOrDefault(FoodDietaryClassification.UNKNOWN)
                )
            }
        )

        // Sync daily calorie goal with profile calorie calculations
        viewModelScope.launch {
            userProfile.collect { profile ->
                dailyCalorieGoal.value = profile.calculateDailyCalories()
            }
        }

        // Perform bidirectional startup sync with Firestore
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            com.example.data.FirestoreSyncManager.performStartupSync(context, repository)
        }
    }

    fun saveProfile(profile: UserProfile) {
        viewModelScope.launch {
            profileRepository.saveProfile(profile)
        }
    }

    fun saveNutritionTargets(
        calories: Int,
        carbsGrams: Float,
        proteinGrams: Float,
        fatGrams: Float,
        onResult: (NutritionTargetSaveResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(
                saveNutritionTargetsWithCapability(
                    calories = calories,
                    carbsGrams = carbsGrams,
                    proteinGrams = proteinGrams,
                    fatGrams = fatGrams,
                    currentProfile = userProfile.value,
                    hasMacroTargetsCapability = {
                        hasSubscriptionCapability(SubscriptionCapability.MACRO_TARGETS)
                    },
                    persistProfile = profileRepository::saveProfile
                )
            )
        }
    }

    fun saveBuildRoutine(
        routine: GeneratedRoutine,
        onResult: (SavedRoutineResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(saveRoutineAndSetActive(routine, SavedRoutineOrigin.BUILD_ROUTINE))
        }
    }

    /** Compatibility entry point used by the offline generator. */
    fun saveGeneratedWorkoutPlan(
        planJson: String,
        onResult: (SavedRoutineResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (planJson.isBlank()) {
                profileRepository.saveGeneratedWorkoutPlan("")
                onResult(SavedRoutineResult.SAVED)
                return@launch
            }
            val routine = runCatching { gson.fromJson(planJson, GeneratedRoutine::class.java) }.getOrNull()
            if (routine == null) {
                onResult(SavedRoutineResult.INVALID_ROUTINE)
                return@launch
            }
            onResult(saveRoutineAndSetActive(routine, SavedRoutineOrigin.AI_WORKOUT_GENERATOR))
        }
    }

    fun saveCustomWorkoutPlan(
        planJson: String,
        onResult: (SavedRoutineResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val routine = runCatching { gson.fromJson(planJson, GeneratedRoutine::class.java) }.getOrNull()
            if (planJson.isBlank() || routine == null) {
                onResult(SavedRoutineResult.INVALID_ROUTINE)
                return@launch
            }
            onResult(saveRoutineAndSetActive(routine, SavedRoutineOrigin.CUSTOM_WORKOUT))
        }
    }

    /** Updates only the active-plan slot; it never changes the saved routine library. */
    fun setActiveWorkoutPlan(planJson: String) {
        viewModelScope.launch { profileRepository.saveGeneratedWorkoutPlan(planJson) }
    }

    fun selectSavedRoutine(routine: SavedRoutine) {
        viewModelScope.launch {
            profileRepository.saveGeneratedWorkoutPlan(gson.toJson(routine.routine))
        }
    }

    fun deleteSavedRoutine(routineId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val routineToDelete = savedRoutineRepository.getRoutine(routineId)
            if (routineToDelete == null) {
                onResult(false)
                return@launch
            }
            val activePlanJson = runCatching {
                profileRepository.userProfileFlow.first().generatedWorkoutPlan
            }.getOrElse {
                onResult(false)
                return@launch
            }
            val deleted = deleteSavedRoutineAndCoordinateActivePlan(
                routineId = routineId,
                deletedRoutine = routineToDelete.routine,
                deletedRoutineStableId = savedRoutineRepository.stableRoutineId(routineToDelete.routine),
                activePlanJson = activePlanJson,
                stableRoutineId = savedRoutineRepository::stableRoutineId,
                deleteRoutine = savedRoutineRepository::deleteRoutine,
                clearActivePlan = { profileRepository.saveGeneratedWorkoutPlan("") },
                refreshSavedRoutines = { refreshSavedRoutines() }
            )
            onResult(deleted)
        }
    }

    private suspend fun saveRoutineAndSetActive(
        routine: GeneratedRoutine,
        origin: SavedRoutineOrigin
    ): SavedRoutineResult = saveRoutineAndCoordinateActivePlan(
        routine = routine,
        origin = origin,
        saveRoutine = savedRoutineRepository::saveRoutine,
        stableRoutineId = savedRoutineRepository::stableRoutineId,
        getRoutine = savedRoutineRepository::getRoutine,
        setActiveRoutine = { storedRoutine ->
            profileRepository.saveGeneratedWorkoutPlan(gson.toJson(storedRoutine))
        },
        refreshSavedRoutines = {
            refreshSavedRoutines()
            Unit
        }
    )

    private suspend fun refreshSavedRoutines(): Boolean = savedRoutineRepository.loadRoutines().fold(
        onSuccess = { routines ->
            _savedRoutines.value = routines
            true
        },
        onFailure = { false }
    )

    fun saveTheme(theme: String) {
        viewModelScope.launch {
            profileRepository.saveTheme(theme)
        }
    }

    fun clearProfile() {
        viewModelScope.launch {
            profileRepository.clearProfile()
        }
    }

    fun isOnline(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun fetchExercises() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _isSyncing.value = true
                _exercisesState.value = ExercisesUiState.Loading
            }

            val context = getApplication<Application>().applicationContext
            val online = isOnline(context)

            if (online && BuildWeekRuntimeConfig.exerciseProxyEnabled) {
                try {
                    SafeLog.debug("ExerciseAPI", "Fetching exercises from API")
                    val remoteExercises = repository.getExerciseDBExercises()
                    SafeLog.debug("ExerciseAPI", "Exercise update completed")
                    if (remoteExercises.isNotEmpty()) {
                        repository.insertAllExercises(remoteExercises.map { it.toEntity() })
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _syncStatus.value = SyncStatus.ONLINE
                            _exercisesState.value = ExercisesUiState.Success(remoteExercises)
                            android.widget.Toast.makeText(context, "${remoteExercises.size} exercises loaded from API", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            fallbackToCacheOrLocal("API Error: No exercises returned")
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    SafeLog.error("ExerciseAPI", "Remote exercise update failed", e)
                    val msg = "Online exercise updates are unavailable. Using offline exercises."
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        fallbackToCacheOrLocal(msg)
                    }
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _isSyncing.value = false
                    }
                }
            } else {
                SafeLog.debug("ExerciseAPI", "Remote exercise updates unavailable; using offline data")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    fallbackToCacheOrLocal("Online exercise updates unavailable")
                    _isSyncing.value = false
                }
            }
        }
    }

    private suspend fun fallbackToCacheOrLocal(errorMsg: String? = null) {
        val finalErrorMsg = if (errorMsg != null) "$errorMsg - Using offline data (API unavailable)" else "Using offline data (API unavailable)"
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.getAllCachedExercises()
        }
        if (cached.isNotEmpty()) {
            val mapped = cached.map { it.toExerciseDBExercise() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _syncStatus.value = SyncStatus.CACHED
                _exercisesState.value = ExercisesUiState.Success(mapped, finalErrorMsg)
            }
        } else {
            val context = getApplication<Application>().applicationContext
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.data.api.LocalExerciseProvider.getFallbackExercises(context)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _syncStatus.value = SyncStatus.OFFLINE
                _exercisesState.value = ExercisesUiState.Success(local, finalErrorMsg)
            }
        }
    }

    val calorieLogs: StateFlow<List<CalorieLog>> = repository.allCalorieLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val workoutLogs: StateFlow<List<WorkoutLog>> = repository.allWorkoutLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            combine(userProfile, workoutLogs) { profile, logs ->
                val recentLogs = logs.sortedByDescending(WorkoutLog::timestamp).take(3)
                val recentEquipment = recentLogs.mapNotNull { log ->
                    val exercise = log.exerciseName.lowercase()
                    when {
                        "barbell" in exercise -> "Barbell"
                        "dumbbell" in exercise -> "Dumbbells"
                        "kettlebell" in exercise -> "Kettlebells"
                        "cable" in exercise -> "Cables"
                        "machine" in exercise -> "Machine"
                        "band" in exercise -> "Resistance Bands"
                        "pull-up" in exercise || "pull up" in exercise -> "Pull-up Bar"
                        "bodyweight" in exercise || "push-up" in exercise ||
                            "push up" in exercise || "plank" in exercise -> "Bodyweight"
                        else -> null
                    }
                }.toSet()
                val profileEquipment = when (profile.workoutType.trim().lowercase()) {
                    "gym", "both" -> setOf(
                        "Bodyweight", "Dumbbells", "Barbell", "Bench", "Cables", "Machine",
                        "Leverage Machine", "Pull-up Bar", "Dip Station", "Resistance Bands", "Kettlebells"
                    )
                    "home" -> setOf("Bodyweight")
                    else -> emptySet()
                }
                val equipment = profileEquipment + recentEquipment
                val recentSummary = recentLogs.joinToString(" | ") { log ->
                    buildString {
                        append(log.exerciseName.take(80))
                        append(": ${log.completedSets} completed sets")
                        if (log.liftingVolumeKg > 0.0) {
                            append(", ${log.liftingVolumeKg.toInt()} kg training volume")
                        }
                        val seconds = log.durationSeconds.takeIf { it > 0 }
                            ?: log.durationMinutes.coerceAtLeast(0) * 60
                        if (seconds > 0) append(", ${seconds}s duration")
                    }
                }.takeIf(String::isNotBlank)

                profile.toLocalCoachContext(equipment, recentSummary)
            }.collect { LocalCoachContextStore.update(it) }
        }
    }

    // Expose daily consumed calories calculated from today's logs
    val todayCaloriesConsumed: StateFlow<Int> = calorieLogs
        .map { logs -> 
            logs.filter { isToday(it.timestamp) }.sumOf { (it.amount * it.servings).toInt() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Expose daily burned calories calculated from today's logged workouts
    val todayCaloriesBurned: StateFlow<Int> = workoutLogs
        .map { logs -> 
            logs.filter { isToday(it.timestamp) }.sumOf { it.caloriesBurned }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val todayProteinGrams: StateFlow<Float> = calorieLogs
        .map { logs -> 
            logs.filter { isToday(it.timestamp) }.sumOf { (it.proteinGrams * it.servings).toDouble() }.toFloat()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    val todayCarbsGrams: StateFlow<Float> = calorieLogs
        .map { logs -> 
            logs.filter { isToday(it.timestamp) }.sumOf { (it.carbsGrams * it.servings).toDouble() }.toFloat()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    val todayFatGrams: StateFlow<Float> = calorieLogs
        .map { logs -> 
            logs.filter { isToday(it.timestamp) }.sumOf { (it.fatGrams * it.servings).toDouble() }.toFloat()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    fun setDailyCalorieGoal(goal: Int) {
        dailyCalorieGoal.value = goal
    }

    fun addCalorieLog(
        amount: Int,
        mealType: String,
        description: String,
        proteinGrams: Float = 0f,
        carbsGrams: Float = 0f,
        fatGrams: Float = 0f,
        servings: Float = 1.0f
    ) {
        viewModelScope.launch {
            val log = CalorieLog(
                amount = amount,
                mealType = mealType,
                description = description,
                proteinGrams = proteinGrams,
                carbsGrams = carbsGrams,
                fatGrams = fatGrams,
                servings = servings
            )
            repository.insertCalorieLog(log)
            val context = getApplication<Application>().applicationContext
            com.example.data.FirestoreSyncManager.syncCalorieLog(context, log)
        }
    }

    fun deleteCalorieLog(log: CalorieLog) {
        viewModelScope.launch {
            repository.deleteCalorieLog(log)
            val context = getApplication<Application>().applicationContext
            com.example.data.FirestoreSyncManager.deleteCalorieLog(context, log)
        }
    }

    fun logWorkout(
        sessionId: String,
        exerciseName: String,
        category: String,
        durationMinutes: Int,
        durationSeconds: Int,
        calories: Int,
        completedSets: Int,
        liftingVolumeKg: Double,
        completedExerciseSets: List<CompletedExerciseSetCount> = emptyList(),
        onResult: (WorkoutSaveResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val log = WorkoutLog(
                sessionId = sessionId,
                exerciseName = exerciseName,
                category = category,
                durationMinutes = durationMinutes,
                durationSeconds = durationSeconds,
                caloriesBurned = calories,
                completedSets = completedSets,
                liftingVolumeKg = liftingVolumeKg
            )
            try {
                val workload = runCatching {
                    withContext(Dispatchers.Default) {
                        val catalogue = ExerciseRepositoryProvider.getRepository(
                            getApplication<Application>().applicationContext
                        )
                        catalogue.load()
                        val canonicalExercises = completedExerciseSets
                            .mapNotNull { it.exerciseId?.takeIf(String::isNotBlank) }
                            .distinct()
                            .mapNotNull { id -> catalogue.getById(ExerciseId(id))?.let { id to it } }
                            .toMap()
                        calculateMuscleWorkload(completedExerciseSets, canonicalExercises)
                    }
                }.onFailure { error ->
                    SafeLog.error("WorkoutPersistence", "Muscle classification unavailable; saving workout without workload", error)
                }.getOrNull()
                val muscleRows = workload?.credits.orEmpty().map { credit ->
                    WorkoutMuscleLoadEntity(
                        sessionId = sessionId,
                        muscleGroup = credit.muscleGroup.name,
                        primarySetCredits = credit.primarySetCredits,
                        secondarySetCredits = credit.secondarySetCredits
                    )
                }
                val insertedId = repository.insertWorkoutWithMuscleLoads(log, muscleRows)
                if (insertedId == -1L) {
                    onResult(WorkoutSaveResult.DUPLICATE)
                    return@launch
                }
                val context = getApplication<Application>().applicationContext
                com.example.data.FirestoreSyncManager.syncWorkoutLog(context, log.copy(id = insertedId.toInt()))
                onResult(WorkoutSaveResult.SAVED)
            } catch (error: Exception) {
                SafeLog.error("WorkoutPersistence", "Completed workout save failed", error)
                onResult(WorkoutSaveResult.ERROR)
            }
        }
    }

    fun deleteWorkoutLog(log: WorkoutLog) {
        viewModelScope.launch {
            repository.deleteWorkoutLog(log)
            val context = getApplication<Application>().applicationContext
            com.example.data.FirestoreSyncManager.deleteWorkoutLog(context, log)
        }
    }

    fun deleteAllWorkoutLogs() {
        viewModelScope.launch {
            repository.deleteAllWorkoutLogs()
            val context = getApplication<Application>().applicationContext
            com.example.data.FirestoreSyncManager.deleteAllWorkoutLogs(context)
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(Date(timestamp)) == sdf.format(Date())
    }
}
