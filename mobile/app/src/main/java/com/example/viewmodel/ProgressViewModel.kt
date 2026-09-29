package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FitDesiApplication
import com.example.boost.BoostAccessState
import com.example.data.AppDatabase
import com.example.data.CalorieLog
import com.example.data.ProfileRepository
import com.example.data.TrainerRepository
import com.example.data.UserProfile
import com.example.data.WorkoutLog
import com.example.domain.AdvancedAnalyticsRange
import com.example.domain.AdvancedProgressAnalytics
import com.example.domain.HistoryDateAccess
import com.example.domain.LocalCalendarDate
import com.example.domain.MuscleWorkloadSummary
import com.example.domain.PersistedMuscleLoad
import com.example.domain.ProgressAnalyticsV2
import com.example.domain.ProgressDatePolicy
import com.example.domain.ProgressDatePreset
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionPolicy
import com.example.domain.ProgressDateSelectionResult
import com.example.domain.ProgressJournalDay
import com.example.domain.ProgressNutritionTotals
import com.example.domain.ProgressOverview
import com.example.domain.analyticsQueryWindows
import com.example.domain.buildProgressJournalDays
import com.example.domain.calculateCurrentWorkoutStreakFromTimestamps
import com.example.domain.calculateProgressAnalyticsV2
import com.example.domain.calculateProgressNutritionTotals
import com.example.domain.summarizePersistedMuscleWorkload
import com.example.subscription.SubscriptionCapability
import com.example.subscription.EffectiveCapabilityPolicy
import com.example.subscription.SubscriptionState
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProgressOverviewRange(val calendarDays: Int) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30)
}

enum class OlderHistoryAction {
    SHOWN,
    REQUIRES_FULL_HISTORY
}

enum class AdvancedAnalyticsAction {
    SHOWN,
    REQUIRES_ADVANCED_ANALYTICS
}

data class ProgressAccess(
    val canViewFullHistory: Boolean = false,
    val canShowMacroTargets: Boolean = false,
    val canViewAdvancedAnalytics: Boolean = false
)

data class ProgressTargetPresentation(
    val calorieTarget: Int? = null,
    val proteinTargetGrams: Float? = null,
    val carbsTargetGrams: Float? = null,
    val fatTargetGrams: Float? = null
)

data class ProgressUiState(
    val isLoading: Boolean = true,
    val today: LocalCalendarDate,
    val dateSelection: ProgressDateSelection = ProgressDateSelection.Range(today, today),
    val analytics: ProgressAnalyticsV2? = null,
    val exactWorkoutStreak: Int = 0,
    val currentWeekActivity: List<Boolean> = List(7) { false },
    val workoutsThisWeek: Int = 0,
    val hasWorkoutToday: Boolean = false,
    val todayNutrition: ProgressNutritionTotals = ProgressNutritionTotals(),
    val latestRecentWorkout: WorkoutLog? = null,
    val journalDays: List<ProgressJournalDay> = emptyList(),
    val selectedJournalDate: LocalCalendarDate = today,
    val targets: ProgressTargetPresentation = ProgressTargetPresentation(),
    val canViewFullHistory: Boolean = false,
    val canShowMacroTargets: Boolean = false,
    val canViewAdvancedAnalytics: Boolean = false,
    val muscleWorkload: MuscleWorkloadSummary? = null,
    // Transitional source compatibility only; the product UI uses dateSelection.
    val overviewRange: ProgressOverviewRange = ProgressOverviewRange.THIRTY_DAYS,
    val overview: ProgressOverview = ProgressOverview(rangeDays = 30),
    val journalWindowStart: LocalCalendarDate = today,
    val journalWindowEnd: LocalCalendarDate = today,
    val advancedRange: AdvancedAnalyticsRange = AdvancedAnalyticsRange.THIRTY_DAYS,
    val isAdvancedAnalyticsLoading: Boolean = false,
    val advancedAnalytics: AdvancedProgressAnalytics? = null
)

internal fun progressAccessFor(
    subscriptionState: SubscriptionState,
    boostAccessState: BoostAccessState,
    nowEpochMillis: Long
): ProgressAccess = ProgressAccess(
    canViewFullHistory = EffectiveCapabilityPolicy.hasCapability(
        subscriptionState,
        boostAccessState,
        SubscriptionCapability.FULL_WORKOUT_HISTORY,
        nowEpochMillis
    ),
    canShowMacroTargets = EffectiveCapabilityPolicy.hasCapability(
        subscriptionState,
        boostAccessState,
        SubscriptionCapability.MACRO_TARGETS,
        nowEpochMillis
    ),
    canViewAdvancedAnalytics = EffectiveCapabilityPolicy.hasCapability(
        subscriptionState,
        boostAccessState,
        SubscriptionCapability.ADVANCED_ANALYTICS,
        nowEpochMillis
    )
)

internal fun progressAccessFor(state: SubscriptionState): ProgressAccess =
    progressAccessFor(state, BoostAccessState.Unknown, System.currentTimeMillis())

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel internal constructor(
    application: Application,
    private val repository: TrainerRepository,
    profileFlow: Flow<UserProfile>,
    subscriptionState: StateFlow<SubscriptionState>,
    boostAccessState: StateFlow<BoostAccessState>,
    private val computationDispatcher: CoroutineDispatcher,
    private val timeZoneProvider: () -> TimeZone,
    private val nowMillis: () -> Long
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        repository = TrainerRepository(AppDatabase.getDatabase(application).trainerDao()),
        profileFlow = ProfileRepository(application).userProfileFlow,
        subscriptionState = (application as FitDesiApplication).subscriptionRepository.state,
        boostAccessState = application.boostRepository.state,
        computationDispatcher = Dispatchers.Default,
        timeZoneProvider = TimeZone::getDefault,
        nowMillis = System::currentTimeMillis
    )

    private val dateContext = MutableStateFlow(currentDateContext())
    private val requestedSelection = MutableStateFlow<ProgressDateSelection>(
        ProgressDateSelectionPolicy.recentThirtyDays(dateContext.value.today, dateContext.value.timeZone)
    )
    private val selectedJournalDate = MutableStateFlow(dateContext.value.today)

    private val access = combine(subscriptionState, boostAccessState) { subscription, boost ->
        progressAccessFor(subscription, boost, nowMillis())
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            progressAccessFor(subscriptionState.value, boostAccessState.value, nowMillis())
        )

    private val targetPresentation = combine(profileFlow, access) { profile, currentAccess ->
        ProgressTargetPresentation(
            calorieTarget = profile.calculateDailyCalories().takeIf { it > 0 },
            proteinTargetGrams = profile.calculateProteinGrams().takeIf {
                currentAccess.canShowMacroTargets && it > 0f
            },
            carbsTargetGrams = profile.calculateCarbGrams().takeIf {
                currentAccess.canShowMacroTargets && it > 0f
            },
            fatTargetGrams = profile.calculateFatGrams().takeIf {
                currentAccess.canShowMacroTargets && it > 0f
            }
        )
    }

    private val analyticsSnapshot = combine(dateContext, requestedSelection, access) { context, selection, currentAccess ->
        AnalyticsRequest(
            context = context,
            selection = ProgressDateSelectionPolicy.sanitize(
                selection,
                context.today,
                currentAccess.canViewFullHistory,
                context.timeZone
            ),
            access = currentAccess
        )
    }.flatMapLatest { request ->
        analyticsDataFlows(request).mapLatest { data ->
            withContext(computationDispatcher) { buildSnapshot(request, data) as ProgressSnapshot? }
        }.onStart { emit(null) }
    }

    private val streakSnapshot = combine(
        dateContext,
        repository.workoutTimestampSignal
    ) { context, _ -> context }
        .mapLatest { context ->
            val timestamps = repository.getAllWorkoutTimestampsDescending()
            withContext(computationDispatcher) { buildStreakSnapshot(timestamps, context) }
        }

    private val stateInputs = combine(
        analyticsSnapshot,
        targetPresentation,
        streakSnapshot,
        selectedJournalDate,
        access
    ) { snapshot, targets, streak, selectedDate, currentAccess ->
        StateInputs(snapshot, targets, streak, selectedDate, currentAccess)
    }

    val uiState: StateFlow<ProgressUiState> = stateInputs.map { inputs ->
        val currentAccess = inputs.currentAccess
        val safeTargets = inputs.targets.forAccess(currentAccess)
        val snapshot = inputs.snapshot
        if (snapshot == null || snapshot.request.access != currentAccess) {
            return@map initialState(dateContext.value, currentAccess, safeTargets)
        }
        val safeSelectedDate = inputs.selectedDate.takeIf { selected ->
            snapshot.journalDays.any { it.date == selected }
        } ?: snapshot.journalDays.firstOrNull()?.date ?: snapshot.request.context.today
        val window = snapshot.analytics.currentWindow
        val overview = ProgressOverview(
            workoutTotals = snapshot.analytics.workoutTotals.copy(recentWindowStreak = inputs.streak.currentStreak),
            nutritionTotals = calculateProgressNutritionTotals(snapshot.currentCalories),
            nutritionLoggedDays = snapshot.analytics.nutritionSummary.loggedDays,
            rangeDays = window.calendarDays
        )
        ProgressUiState(
            isLoading = false,
            today = snapshot.request.context.today,
            dateSelection = snapshot.request.selection,
            analytics = snapshot.analytics,
            exactWorkoutStreak = inputs.streak.currentStreak,
            currentWeekActivity = inputs.streak.currentWeekActivity,
            workoutsThisWeek = inputs.streak.workoutsThisWeek,
            hasWorkoutToday = inputs.streak.hasWorkoutToday,
            todayNutrition = snapshot.todayNutrition,
            latestRecentWorkout = snapshot.latestRecentWorkout,
            journalDays = snapshot.journalDays,
            selectedJournalDate = safeSelectedDate,
            targets = safeTargets,
            canViewFullHistory = currentAccess.canViewFullHistory,
            canShowMacroTargets = currentAccess.canShowMacroTargets,
            canViewAdvancedAnalytics = currentAccess.canViewAdvancedAnalytics,
            muscleWorkload = snapshot.muscleWorkload.takeIf { currentAccess.canViewAdvancedAnalytics },
            overviewRange = if (window.calendarDays <= 7) ProgressOverviewRange.SEVEN_DAYS else ProgressOverviewRange.THIRTY_DAYS,
            overview = overview,
            journalWindowStart = window.startDate,
            journalWindowEnd = window.endDateInclusive,
            advancedRange = when {
                snapshot.request.selection is ProgressDateSelection.AllHistory -> AdvancedAnalyticsRange.ALL
                window.calendarDays <= 7 -> AdvancedAnalyticsRange.SEVEN_DAYS
                window.calendarDays > 30 -> AdvancedAnalyticsRange.NINETY_DAYS
                else -> AdvancedAnalyticsRange.THIRTY_DAYS
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialState(dateContext.value, access.value)
    )

    init {
        viewModelScope.launch {
            access.collect { currentAccess ->
                val context = dateContext.value
                val sanitized = ProgressDateSelectionPolicy.sanitize(
                    requestedSelection.value,
                    context.today,
                    currentAccess.canViewFullHistory,
                    context.timeZone
                )
                if (sanitized != requestedSelection.value) {
                    requestedSelection.value = sanitized
                    selectedJournalDate.value = context.today
                }
            }
        }
    }

    fun requestDateSelection(selection: ProgressDateSelection): ProgressDateSelectionResult {
        val context = dateContext.value
        val result = ProgressDateSelectionPolicy.validate(
            selection,
            context.today,
            access.value.canViewFullHistory,
            context.timeZone
        )
        if (result is ProgressDateSelectionResult.Allowed) {
            requestedSelection.value = result.selection
            selectedJournalDate.value = when (val allowed = result.selection) {
                ProgressDateSelection.AllHistory -> context.today
                is ProgressDateSelection.Range -> allowed.endDateInclusive
            }
        }
        return result
    }

    fun requestDatePreset(
        preset: ProgressDatePreset,
        customRange: ProgressDateSelection.Range? = null
    ): ProgressDateSelectionResult {
        val context = dateContext.value
        val result = ProgressDateSelectionPolicy.preset(
            preset,
            context.today,
            access.value.canViewFullHistory,
            context.timeZone,
            customRange
        )
        if (result is ProgressDateSelectionResult.Allowed) requestDateSelection(result.selection)
        return result
    }

    fun shiftDateSelection(direction: Int): ProgressDateSelectionResult {
        val context = dateContext.value
        val result = ProgressDateSelectionPolicy.shift(
            requestedSelection.value,
            direction,
            context.today,
            access.value.canViewFullHistory,
            context.timeZone
        )
        if (result is ProgressDateSelectionResult.Allowed) requestDateSelection(result.selection)
        return result
    }

    fun selectJournalDate(date: LocalCalendarDate): OlderHistoryAction {
        val context = dateContext.value
        return if (
            ProgressDatePolicy.historyAccess(
                date,
                context.today,
                access.value.canViewFullHistory,
                context.timeZone
            ) == HistoryDateAccess.ALLOWED
        ) {
            selectedJournalDate.value = date
            OlderHistoryAction.SHOWN
        } else {
            OlderHistoryAction.REQUIRES_FULL_HISTORY
        }
    }

    internal fun canShowWorkoutManagementDetail(timestamp: Long): Boolean {
        val context = dateContext.value
        return ProgressDatePolicy.historyAccess(
            ProgressDatePolicy.localDateAt(timestamp, context.timeZone),
            context.today,
            access.value.canViewFullHistory,
            context.timeZone
        ) == HistoryDateAccess.ALLOWED
    }

    // Compatibility actions delegate to the shared selection; no fixed chips remain in product UI.
    fun setOverviewRange(range: ProgressOverviewRange) {
        val context = dateContext.value
        requestDateSelection(
            ProgressDateSelection.Range(
                ProgressDatePolicy.minusCalendarDays(context.today, range.calendarDays - 1, context.timeZone),
                context.today
            )
        )
    }

    fun requestAdvancedRange(range: AdvancedAnalyticsRange): AdvancedAnalyticsAction {
        if (!access.value.canViewAdvancedAnalytics) return AdvancedAnalyticsAction.REQUIRES_ADVANCED_ANALYTICS
        val context = dateContext.value
        requestDateSelection(
            range.calendarDays?.let { days ->
                ProgressDateSelection.Range(
                    ProgressDatePolicy.minusCalendarDays(context.today, days - 1, context.timeZone),
                    context.today
                )
            } ?: ProgressDateSelection.AllHistory
        )
        return AdvancedAnalyticsAction.SHOWN
    }

    fun requestOlderJournalWindow(): OlderHistoryAction = when (shiftDateSelection(-1)) {
        ProgressDateSelectionResult.RequiresFullHistory -> OlderHistoryAction.REQUIRES_FULL_HISTORY
        else -> OlderHistoryAction.SHOWN
    }

    fun requestNewerJournalWindow() {
        shiftDateSelection(1)
    }

    fun refreshCurrentDate() {
        val previous = dateContext.value
        val current = currentDateContext()
        val timeZoneChanged = previous.timeZone.id != current.timeZone.id ||
            !previous.timeZone.hasSameRules(current.timeZone)
        if (previous.today != current.today || timeZoneChanged) {
            val wasRollingRecent = requestedSelection.value ==
                ProgressDateSelectionPolicy.recentThirtyDays(previous.today, previous.timeZone)
            dateContext.value = current
            requestedSelection.value = if (wasRollingRecent) {
                ProgressDateSelectionPolicy.recentThirtyDays(current.today, current.timeZone)
            } else {
                ProgressDateSelectionPolicy.sanitize(
                    requestedSelection.value,
                    current.today,
                    access.value.canViewFullHistory,
                    current.timeZone
                )
            }
            if (previous.today != current.today) selectedJournalDate.value = current.today
        }
    }

    private fun analyticsDataFlows(request: AnalyticsRequest): Flow<AnalyticsData> {
        val selectedData = when (val selection = request.selection) {
            ProgressDateSelection.AllHistory -> combine(
                repository.allWorkoutLogs,
                repository.allCalorieLogs,
                if (request.access.canViewAdvancedAnalytics) repository.allWorkoutMuscleLoads
                else flowOf(emptyList<com.example.data.WorkoutMuscleLoadWithTimestamp>())
            ) { workouts, calories, muscleLoads -> AnalyticsData(workouts, calories, muscleLoads) }
            is ProgressDateSelection.Range -> {
                val windows = analyticsQueryWindows(selection, request.context.today, request.context.timeZone)
                val queryStart = if (request.access.canViewAdvancedAnalytics) {
                    windows.minOf { it.startDate }
                } else {
                    minOf(selection.startDate, windows.last().startDate)
                }
                val epoch = ProgressDatePolicy.inclusiveDateRange(
                    queryStart,
                    request.context.today,
                    request.context.timeZone
                )
                combine(
                    repository.getWorkoutLogsInRange(epoch.startInclusive, epoch.endExclusive),
                    repository.getCalorieLogsInRange(epoch.startInclusive, epoch.endExclusive),
                    if (request.access.canViewAdvancedAnalytics) {
                        val selectedEpoch = ProgressDateSelectionPolicy.epochRange(selection, request.context.timeZone)
                        repository.getWorkoutMuscleLoadsInRange(selectedEpoch.startInclusive, selectedEpoch.endExclusive)
                    } else {
                        flowOf(emptyList<com.example.data.WorkoutMuscleLoadWithTimestamp>())
                    }
                ) { workouts, calories, muscleLoads -> AnalyticsData(workouts, calories, muscleLoads) }
            }
        }
        val todayEpoch = ProgressDatePolicy.inclusiveDateRange(
            request.context.today,
            request.context.today,
            request.context.timeZone
        )
        val recentWorkoutEpoch = ProgressDateSelectionPolicy.epochRange(
            ProgressDateSelectionPolicy.recentThirtyDays(request.context.today, request.context.timeZone),
            request.context.timeZone
        )
        return combine(
            selectedData,
            repository.getCalorieLogsInRange(todayEpoch.startInclusive, todayEpoch.endExclusive),
            repository.getWorkoutLogsInRange(recentWorkoutEpoch.startInclusive, recentWorkoutEpoch.endExclusive)
        ) { data, todayCalories, recentWorkouts ->
            data.copy(todayCalories = todayCalories, recentWorkouts = recentWorkouts)
        }
    }

    private fun buildSnapshot(request: AnalyticsRequest, data: AnalyticsData): ProgressSnapshot {
        val analytics = calculateProgressAnalyticsV2(
            request.selection,
            request.context.today,
            request.context.timeZone,
            data.workouts,
            data.calories
        ).let { calculated ->
            if (request.access.canViewAdvancedAnalytics) calculated else calculated.copy(
                previousPeriodComparison = null,
                trainingRestComparison = null
            )
        }
        val window = analytics.currentWindow
        val currentWorkouts = data.workouts.filter {
            ProgressDatePolicy.localDateAt(it.timestamp, request.context.timeZone) in window.startDate..window.endDateInclusive
        }
        val currentCalories = data.calories.filter {
            ProgressDatePolicy.localDateAt(it.timestamp, request.context.timeZone) in window.startDate..window.endDateInclusive
        }
        val persistedLoads = data.muscleLoads.map {
            PersistedMuscleLoad(
                it.sessionId,
                it.muscleGroup,
                it.primarySetCredits,
                it.secondarySetCredits,
                it.completedSets
            )
        }
        return ProgressSnapshot(
            request = request,
            analytics = analytics,
            todayNutrition = calculateProgressNutritionTotals(data.todayCalories),
            latestRecentWorkout = data.recentWorkouts.maxByOrNull(WorkoutLog::timestamp),
            currentCalories = currentCalories,
            journalDays = buildJournalDays(
                window.startDate,
                window.endDateInclusive,
                currentWorkouts,
                currentCalories,
                request.context,
                includeEmptyDates = request.selection is ProgressDateSelection.Range && window.calendarDays <= 31
            ),
            muscleWorkload = if (request.access.canViewAdvancedAnalytics) {
                summarizePersistedMuscleWorkload(persistedLoads, currentWorkouts.size)
            } else null
        )
    }

    private fun buildJournalDays(
        start: LocalCalendarDate,
        end: LocalCalendarDate,
        workouts: List<WorkoutLog>,
        calories: List<CalorieLog>,
        context: ProgressDateContext,
        includeEmptyDates: Boolean
    ): List<ProgressJournalDay> {
        if (includeEmptyDates) {
            return buildProgressJournalDays(start, end, workouts, calories, context.today, context.timeZone)
        }
        val recordedDates = (workouts.map { ProgressDatePolicy.localDateAt(it.timestamp, context.timeZone) } +
            calories.map { ProgressDatePolicy.localDateAt(it.timestamp, context.timeZone) })
            .distinct()
            .sortedDescending()
        return recordedDates.flatMap { date ->
            buildProgressJournalDays(date, date, workouts, calories, context.today, context.timeZone)
        }
    }

    private fun buildStreakSnapshot(timestamps: List<Long>, context: ProgressDateContext): StreakSnapshot {
        val activeDates = timestamps.asSequence()
            .map { ProgressDatePolicy.localDateAt(it, context.timeZone) }
            .filter { it <= context.today }
            .toSet()
        val start = currentWeekMonday(context.today, context.timeZone)
        return StreakSnapshot(
            currentStreak = calculateCurrentWorkoutStreakFromTimestamps(timestamps, context.today, context.timeZone),
            hasWorkoutToday = context.today in activeDates,
            currentWeekActivity = (0..6).map { offset ->
                val date = ProgressDatePolicy.plusCalendarDays(start, offset, context.timeZone)
                date <= context.today && date in activeDates
            },
            workoutsThisWeek = timestamps.count {
                ProgressDatePolicy.localDateAt(it, context.timeZone) in start..context.today
            }
        )
    }

    private fun currentWeekMonday(date: LocalCalendarDate, timeZone: TimeZone): LocalCalendarDate {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = ProgressDatePolicy.startOfLocalDateEpochMillis(date, timeZone)
        }
        return ProgressDatePolicy.minusCalendarDays(date, (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7, timeZone)
    }

    private fun currentDateContext(): ProgressDateContext {
        val zone = timeZoneProvider()
        return ProgressDateContext(ProgressDatePolicy.today(nowMillis(), zone), zone)
    }

    private fun initialState(
        context: ProgressDateContext,
        currentAccess: ProgressAccess = ProgressAccess(),
        targets: ProgressTargetPresentation = ProgressTargetPresentation()
    ): ProgressUiState {
        val selection = ProgressDateSelectionPolicy.recentThirtyDays(context.today, context.timeZone)
        return ProgressUiState(
            today = context.today,
            dateSelection = selection,
            targets = targets.forAccess(currentAccess),
            canViewFullHistory = currentAccess.canViewFullHistory,
            canShowMacroTargets = currentAccess.canShowMacroTargets,
            canViewAdvancedAnalytics = currentAccess.canViewAdvancedAnalytics,
            journalWindowStart = selection.startDate,
            journalWindowEnd = selection.endDateInclusive
        )
    }

    private fun ProgressTargetPresentation.forAccess(currentAccess: ProgressAccess): ProgressTargetPresentation =
        if (currentAccess.canShowMacroTargets) this else copy(
            proteinTargetGrams = null,
            carbsTargetGrams = null,
            fatTargetGrams = null
        )

    private data class ProgressDateContext(val today: LocalCalendarDate, val timeZone: TimeZone)
    private data class AnalyticsRequest(
        val context: ProgressDateContext,
        val selection: ProgressDateSelection,
        val access: ProgressAccess
    )
    private data class AnalyticsData(
        val workouts: List<WorkoutLog>,
        val calories: List<CalorieLog>,
        val muscleLoads: List<com.example.data.WorkoutMuscleLoadWithTimestamp>,
        val todayCalories: List<CalorieLog> = emptyList(),
        val recentWorkouts: List<WorkoutLog> = emptyList()
    )
    private data class ProgressSnapshot(
        val request: AnalyticsRequest,
        val analytics: ProgressAnalyticsV2,
        val todayNutrition: ProgressNutritionTotals,
        val latestRecentWorkout: WorkoutLog?,
        val currentCalories: List<CalorieLog>,
        val journalDays: List<ProgressJournalDay>,
        val muscleWorkload: MuscleWorkloadSummary?
    )
    private data class StreakSnapshot(
        val currentStreak: Int,
        val hasWorkoutToday: Boolean,
        val currentWeekActivity: List<Boolean>,
        val workoutsThisWeek: Int
    )
    private data class StateInputs(
        val snapshot: ProgressSnapshot?,
        val targets: ProgressTargetPresentation,
        val streak: StreakSnapshot,
        val selectedDate: LocalCalendarDate,
        val currentAccess: ProgressAccess
    )

}
