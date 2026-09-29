package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.CalorieLog
import com.example.data.TrainerRepository
import com.example.data.UserProfile
import com.example.data.WorkoutLog
import com.example.boost.BoostAccessState
import com.example.domain.LocalCalendarDate
import com.example.domain.AdvancedAnalyticsRange
import com.example.domain.ProgressDatePolicy
import com.example.domain.ProgressDateSelection
import com.example.domain.ProgressDateSelectionResult
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import java.util.TimeZone
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProgressViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val zone = TimeZone.getTimeZone("UTC")
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        database?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `Basic stays recent paid access requires retry and downgrade preserves rows`() = runTest(dispatcher) {
        val createdDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        database = createdDatabase
        val dao = createdDatabase.trainerDao()
        val today = LocalCalendarDate(2026, 9, 4)
        val oldestBasic = LocalCalendarDate(2026, 8, 6)
        val olderDate = LocalCalendarDate(2026, 6, 1)
        dao.insertCalorieLog(
            CalorieLog(
                amount = 250,
                mealType = "Dinner",
                description = "Older persisted meal",
                timestamp = midday(olderDate)
            )
        )
        val olderWorkout = WorkoutLog(
            sessionId = "older-persisted-session",
            exerciseName = "Older persisted workout",
            category = "Strength",
            durationMinutes = 20,
            caloriesBurned = 0,
            timestamp = midday(olderDate)
        )
        dao.insertWorkoutLog(olderWorkout)
        val recentWorkout = WorkoutLog(
            sessionId = "recent-session",
            exerciseName = "Recent persisted workout",
            category = "Mobility",
            durationMinutes = 15,
            caloriesBurned = 0,
            timestamp = midday(today)
        )
        dao.insertWorkoutLog(recentWorkout)
        val subscription = MutableStateFlow(authoritativeState(SubscriptionTier.BASIC))
        val viewModel = ProgressViewModel(
            application = application,
            repository = TrainerRepository(dao),
            profileFlow = MutableStateFlow(
                UserProfile(
                    customCalorieGoal = 2_000,
                    customProteinGoalGrams = 150f,
                    customCarbGoalGrams = 220f,
                    customFatGoalGrams = 70f
                )
            ),
            subscriptionState = subscription,
            boostAccessState = MutableStateFlow(BoostAccessState.Inactive),
            computationDispatcher = dispatcher,
            timeZoneProvider = { zone },
            nowMillis = { midday(today) }
        )

        val basic = viewModel.uiState.first { !it.isLoading }
        assertEquals(oldestBasic, basic.journalWindowStart)
        assertEquals(30, basic.journalDays.size)
        assertFalse(basic.journalDays.any { it.date == olderDate })
        assertEquals("Recent persisted workout", basic.latestRecentWorkout?.exerciseName)
        assertTrue(basic.hasWorkoutToday)
        assertFalse(viewModel.canShowWorkoutManagementDetail(olderWorkout.timestamp))
        assertTrue(viewModel.canShowWorkoutManagementDetail(recentWorkout.timestamp))
        assertEquals(OlderHistoryAction.REQUIRES_FULL_HISTORY, viewModel.requestOlderJournalWindow())
        assertEquals(
            ProgressDateSelectionResult.RequiresFullHistory,
            viewModel.requestDateSelection(ProgressDateSelection.Range(olderDate, today))
        )
        assertFalse(basic.canViewAdvancedAnalytics)
        assertEquals(null, basic.analytics?.previousPeriodComparison)

        subscription.value = authoritativeState(SubscriptionTier.PLUS)
        val paidWithoutReplay = viewModel.uiState.first { it.canViewFullHistory }
        assertEquals(oldestBasic, paidWithoutReplay.journalWindowStart)
        assertTrue(viewModel.canShowWorkoutManagementDetail(olderWorkout.timestamp))
        val longRange = ProgressDateSelection.Range(olderDate, today)
        assertTrue(viewModel.requestDateSelection(longRange) is ProgressDateSelectionResult.Allowed)
        val paidLongRange = viewModel.uiState.first { it.dateSelection == longRange }
        assertEquals(listOf(today, olderDate), paidLongRange.journalDays.map { it.date })
        assertTrue(
            viewModel.requestDateSelection(ProgressDateSelection.Range(olderDate, olderDate)) is
                ProgressDateSelectionResult.Allowed
        )
        val paidOlder = viewModel.uiState.first { it.dateSelection == ProgressDateSelection.Range(olderDate, olderDate) }
        assertEquals(olderDate, paidOlder.selectedJournalDate)
        assertEquals(1, paidOlder.analytics!!.workoutTotals.sessionCount)
        assertEquals("Recent persisted workout", paidOlder.latestRecentWorkout?.exerciseName)
        assertEquals(1, paidOlder.journalDays.single { it.date == olderDate }.workoutEntries.size)
        assertEquals(1, paidOlder.journalDays.single { it.date == olderDate }.mealGroups.single().entries.size)
        assertTrue(viewModel.requestDateSelection(ProgressDateSelection.AllHistory) is ProgressDateSelectionResult.Allowed)
        val paidAll = viewModel.uiState.first {
            it.dateSelection is ProgressDateSelection.AllHistory && it.targets.proteinTargetGrams != null
        }
        assertEquals(2, paidAll.analytics!!.workoutTotals.sessionCount)
        assertEquals(listOf(today, olderDate), paidAll.journalDays.map { it.date })

        val transitionAwaiter = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiState.first { it.isLoading && !it.canViewFullHistory }
        }
        subscription.value = authoritativeState(SubscriptionTier.BASIC)
        val safeTransition = transitionAwaiter.await()
        assertEquals(ProgressDateSelection.Range(oldestBasic, today), safeTransition.dateSelection)
        assertEquals(null, safeTransition.analytics)
        assertTrue(safeTransition.journalDays.isEmpty())
        assertEquals(null, safeTransition.targets.proteinTargetGrams)
        assertEquals(null, safeTransition.targets.carbsTargetGrams)
        assertEquals(null, safeTransition.targets.fatTargetGrams)
        assertFalse(safeTransition.canShowMacroTargets)
        assertFalse(safeTransition.canViewAdvancedAnalytics)
        assertEquals(null, safeTransition.muscleWorkload)
        val downgraded = viewModel.uiState.first {
            !it.isLoading &&
                !it.canViewFullHistory &&
                !it.canViewAdvancedAnalytics &&
                it.journalWindowStart == oldestBasic
        }
        assertFalse(downgraded.canViewAdvancedAnalytics)
        assertEquals(ProgressDateSelection.Range(oldestBasic, today), downgraded.dateSelection)
        assertEquals(null, downgraded.analytics?.previousPeriodComparison)
        assertEquals(null, downgraded.muscleWorkload)
        assertFalse(downgraded.journalDays.any { it.date == olderDate })
        assertFalse(viewModel.canShowWorkoutManagementDetail(olderWorkout.timestamp))
        assertEquals(1, dao.getAllCalorieLogsList().size)
        assertEquals(2, dao.getAllWorkoutLogsList().size)
    }

    @Test
    fun `refresh recomputes query date after midnight and timezone changes`() = runTest(dispatcher) {
        val createdDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        database = createdDatabase
        val subscription = MutableStateFlow(authoritativeState(SubscriptionTier.BASIC))
        var currentZone = TimeZone.getTimeZone("UTC")
        var currentTime = ProgressDatePolicy.startOfLocalDateEpochMillis(
            LocalCalendarDate(2026, 9, 4),
            currentZone
        ) + 60L * 60L * 1_000L
        val viewModel = ProgressViewModel(
            application = application,
            repository = TrainerRepository(createdDatabase.trainerDao()),
            profileFlow = MutableStateFlow(UserProfile()),
            subscriptionState = subscription,
            boostAccessState = MutableStateFlow(BoostAccessState.Inactive),
            computationDispatcher = dispatcher,
            timeZoneProvider = { currentZone },
            nowMillis = { currentTime }
        )

        assertEquals(LocalCalendarDate(2026, 9, 4), viewModel.uiState.first { !it.isLoading }.today)

        currentTime += 24L * 60L * 60L * 1_000L
        viewModel.refreshCurrentDate()
        val afterMidnight = viewModel.uiState.first {
            it.today == LocalCalendarDate(2026, 9, 5) &&
                it.journalWindowEnd == LocalCalendarDate(2026, 9, 5)
        }
        assertEquals(LocalCalendarDate(2026, 9, 5), afterMidnight.journalWindowEnd)

        currentZone = TimeZone.getTimeZone("America/Los_Angeles")
        viewModel.refreshCurrentDate()
        val afterTimeZoneChange = viewModel.uiState.first {
            it.today == LocalCalendarDate(2026, 9, 4) &&
                it.journalWindowEnd == LocalCalendarDate(2026, 9, 4)
        }
        assertEquals(LocalCalendarDate(2026, 9, 4), afterTimeZoneChange.journalWindowEnd)
    }

    @Test
    fun `Boost activation permits advanced analytics only after deliberate retry`() = runTest(dispatcher) {
        val createdDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        database = createdDatabase
        val today = LocalCalendarDate(2026, 9, 5)
        val boost = MutableStateFlow<BoostAccessState>(BoostAccessState.Inactive)
        val viewModel = ProgressViewModel(
            application = application,
            repository = TrainerRepository(createdDatabase.trainerDao()),
            profileFlow = MutableStateFlow(UserProfile()),
            subscriptionState = MutableStateFlow(authoritativeState(SubscriptionTier.BASIC)),
            boostAccessState = boost,
            computationDispatcher = dispatcher,
            timeZoneProvider = { zone },
            nowMillis = { midday(today) }
        )

        val beforeBoost = viewModel.uiState.first { !it.isLoading }
        val unchangedSelection = beforeBoost.dateSelection
        assertEquals(
            AdvancedAnalyticsAction.REQUIRES_ADVANCED_ANALYTICS,
            viewModel.requestAdvancedRange(AdvancedAnalyticsRange.SEVEN_DAYS)
        )

        val activeAwaiter = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiState.first { it.canViewAdvancedAnalytics }
        }
        boost.value = BoostAccessState.Active(midday(today) + 30L * 60L * 1_000L)
        val activeWithoutReplay = activeAwaiter.await()

        assertEquals(unchangedSelection, activeWithoutReplay.dateSelection)
        assertFalse(activeWithoutReplay.canViewFullHistory)
        assertFalse(activeWithoutReplay.canShowMacroTargets)
        assertEquals(AdvancedAnalyticsAction.SHOWN, viewModel.requestAdvancedRange(AdvancedAnalyticsRange.SEVEN_DAYS))
        val sevenDaySelection = ProgressDateSelection.Range(
            ProgressDatePolicy.minusCalendarDays(today, 6, zone),
            today
        )
        val deliberatelyRequested = viewModel.uiState.first {
            it.dateSelection == sevenDaySelection
        }
        assertTrue(deliberatelyRequested.canViewAdvancedAnalytics)
        assertFalse(deliberatelyRequested.canViewFullHistory)
    }

    private fun authoritativeState(tier: SubscriptionTier) = SubscriptionState(
        tier = tier,
        status = SubscriptionStatus.READY,
        hasAuthoritativeCustomerInfo = true
    )

    private fun midday(date: LocalCalendarDate): Long =
        ProgressDatePolicy.startOfLocalDateEpochMillis(date, zone) + 12L * 60L * 60L * 1_000L
}
