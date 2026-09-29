package com.example.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.fitdesi.data.Exercise
import com.example.fitdesi.data.Instructions
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.ExerciseViewModel
import com.example.viewmodel.ExerciseUiState
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackWorkoutCatalogueTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trackerPreferences
        get() = context.getSharedPreferences("workout_tracker_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearTrackerPreferencesBeforeTest() {
        trackerPreferences.edit().clear().commit()
    }

    @After
    fun clearTrackerPreferencesAfterTest() {
        trackerPreferences.edit().clear().commit()
    }

    @Test
    fun `constrained workout viewport keeps actual inputs reachable while editing`() {
        val tracked = TrackedExercise(
            id = "tracked-ime",
            name = "Viewport Row",
            category = "Strength",
            sets = listOf(
                TrackedSet(
                    id = "set-ime",
                    previous = "—",
                    weight = "",
                    reps = ""
                )
            ),
            exerciseId = "0257",
            phase = TrackedWorkoutPhase.MAIN_WORK,
            trackingType = TrackedItemType.STRENGTH,
            targetSets = 1,
            targetText = "8-12 reps"
        )
        trackerPreferences.edit()
            .putString("tracked_exercises", Gson().toJson(listOf(tracked)))
            .putBoolean("is_timer_paused", true)
            .commit()

        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                Box(Modifier.size(width = 360.dp, height = 420.dp)) {
                    TrackWorkoutScreen(
                        viewModel = null,
                        onBack = {},
                        exerciseViewModel = ExerciseViewModel(
                            ApplicationProvider.getApplicationContext<Application>()
                        )
                    )
                }
            }
        }

        val workoutList = composeTestRule.onNode(hasScrollToKeyAction())
        workoutList.performScrollToKey("tracked-ime")

        val weightInput = composeTestRule.onNodeWithTag("actual_weight_set-ime")
        weightInput
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        weightInput.performTextInput("12.5")
        weightInput.assertTextContains("12.5")
        composeTestRule.onNodeWithTag("tracked_set_context_set-ime").assertIsDisplayed()

        workoutList.performScrollToKey("tracked-ime")
        val repsInput = composeTestRule.onNodeWithTag("actual_reps_set-ime")
        repsInput
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        repsInput.performTextInput("8")
        repsInput.assertTextContains("8")
        composeTestRule.onNodeWithTag("tracked_set_context_set-ime").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compact_set_done_set-ime").assertTextContains("Mark set done")
    }

    @Test
    fun `loading empty catalogue and search no-match remain distinct`() {
        assertEquals(
            TrackWorkoutCatalogueContent.Loading,
            trackWorkoutCatalogueContent(ExerciseUiState.Loading, "")
        )
        assertEquals(
            TrackWorkoutCatalogueContent.EmptyCatalogue,
            trackWorkoutCatalogueContent(ExerciseUiState.Success(emptyList()), "")
        )
        assertEquals(
            TrackWorkoutCatalogueContent.NoMatches,
            trackWorkoutCatalogueContent(ExerciseUiState.Success(canonicalExamples), "not-present")
        )
    }

    @Test
    fun `repository error is safe and exposes retry`() {
        val content = trackWorkoutCatalogueContent(
            ExerciseUiState.Error("raw repository detail must not be shown"),
            ""
        ) as TrackWorkoutCatalogueContent.Error

        assertEquals(TRACK_WORKOUT_EXERCISE_LOAD_ERROR, content.message)
        assertTrue(content.retryAvailable)
        assertFalse(content.message.contains("raw repository detail"))
    }

    @Test
    fun `ready state uses the full canonical list without copying or changing IDs`() {
        val canonical534 = canonicalExamples + (1..530).map { index ->
            exercise(
                id = "test-canonical-$index",
                name = "Test Canonical Exercise $index"
            )
        }

        val content = trackWorkoutCatalogueContent(
            ExerciseUiState.Success(canonical534),
            ""
        ) as TrackWorkoutCatalogueContent.Ready

        assertSame(canonical534, content.exercises)
        assertEquals(534, content.exercises.size)
        assertEquals("0001", content.exercises.single { it.id == "0001" }.id)
        assertEquals(
            "fd-exercise-chair-squat",
            content.exercises.single { it.id == "fd-exercise-chair-squat" }.id
        )
        assertEquals(
            listOf("0088", "1371"),
            content.exercises
                .filter { it.name == "Barbell Seated Calf Raise" }
                .map(Exercise::id)
        )
    }

    @Test
    fun `search remains name-only and case-insensitive`() {
        assertEquals(
            listOf("fd-exercise-chair-squat"),
            filterTrackWorkoutExercises(canonicalExamples, "cHaIr SqUaT").map(Exercise::id)
        )
        assertTrue(filterTrackWorkoutExercises(canonicalExamples, "quadriceps").isEmpty())
    }

    @Test
    fun `one selection creates the existing tracked format with one blank set`() {
        val source = canonicalExamples.first { it.id == "0001" }
        val selection = selectTrackWorkoutExercise(
            exercise = source,
            hasTrackedExercises = false,
            totalSeconds = 47,
            isTimerPaused = true
        )

        assertEquals(source.name, selection.exercise.name)
        assertEquals(source.category, selection.exercise.category)
        assertNotEquals(source.id, selection.exercise.id)
        assertEquals(source.id, selection.exercise.exerciseId)
        assertEquals(TrackedWorkoutPhase.MAIN_WORK, selection.exercise.phase)
        assertEquals(TrackedItemType.STRENGTH, selection.exercise.trackingType)
        assertTrue(selection.exercise.id.isNotBlank())
        assertEquals(1, selection.exercise.sets.size)
        with(selection.exercise.sets.single()) {
            assertEquals("—", previous)
            assertEquals("", weight)
            assertEquals("", reps)
            assertFalse(isDone)
            assertTrue(id.isNotBlank())
        }
    }

    @Test
    fun `first selection starts from zero while later selection preserves timer state`() {
        val source = canonicalExamples.first()

        val first = selectTrackWorkoutExercise(
            exercise = source,
            hasTrackedExercises = false,
            totalSeconds = 47,
            isTimerPaused = true
        )
        assertEquals(0, first.totalSeconds)
        assertFalse(first.isTimerPaused)

        val later = selectTrackWorkoutExercise(
            exercise = source,
            hasTrackedExercises = true,
            totalSeconds = 47,
            isTimerPaused = false
        )
        assertEquals(47, later.totalSeconds)
        assertFalse(later.isTimerPaused)
    }

    @Test
    fun `existing active draft JSON remains readable`() {
        val legacyJson = """
            [
              {
                "id":"tracked-legacy",
                "name":"Legacy Row",
                "category":"Back",
                "sets":[
                  {
                    "id":"set-legacy",
                    "previous":"40 kg",
                    "weight":"42.5",
                    "reps":"8",
                    "isDone":true
                  }
                ]
              }
            ]
        """.trimIndent()

        val restored = Gson().fromJson(legacyJson, Array<TrackedExercise>::class.java).single()

        assertEquals("tracked-legacy", restored.id)
        assertEquals("Legacy Row", restored.name)
        assertEquals("Back", restored.category)
        assertEquals("set-legacy", restored.sets.single().id)
        assertEquals("42.5", restored.sets.single().weight)
        assertEquals("8", restored.sets.single().reps)
        assertTrue(restored.sets.single().isDone)
    }

    @Test
    fun `new canonical selection keeps legacy fields and adds canonical tracking fields`() {
        val tracked = canonicalExamples.first().toTrackedExercise()
        val json = JsonParser.parseString(Gson().toJson(tracked)).asJsonObject
        val setJson = json.getAsJsonArray("sets").single().asJsonObject

        assertTrue(json.keySet().containsAll(setOf("id", "name", "category", "sets")))
        assertTrue(setJson.keySet().containsAll(setOf("id", "previous", "weight", "reps", "isDone")))
        assertEquals("0001", json.get("exerciseId").asString)
    }

    @Test
    fun `saved routine preserves empty and canonical exercise IDs with blank actual reps`() {
        val routine = GeneratedRoutine(
            name = "Compatibility routine",
            description = "Test",
            splitType = "Full body",
            frequency = "1 day",
            days = listOf(
                GeneratedDay(
                    dayName = "Day 1",
                    title = "Strength",
                    description = "Test day",
                    exercises = listOf(
                        generatedExercise(name = "Legacy Exercise", exerciseId = ""),
                        generatedExercise(
                            name = "Chair Squat",
                            exerciseId = "fd-exercise-chair-squat"
                        )
                    )
                )
            )
        )

        val tracked = routine.toTrackedExercises(0)

        assertEquals(listOf("Legacy Exercise", "Chair Squat"), tracked.map(TrackedExercise::name))
        assertEquals(listOf("Strength", "Strength"), tracked.map(TrackedExercise::category))
        assertEquals(listOf("", "fd-exercise-chair-squat"), tracked.map(TrackedExercise::exerciseId))
        assertTrue(tracked.all { it.sets.size == 3 })
        assertTrue(tracked.all { exercise -> exercise.sets.all { set -> set.targetReps == "10" } })
        assertTrue(tracked.all { exercise -> exercise.sets.all { set -> set.reps.isEmpty() } })
    }

    @Test
    fun `matching selected plan day preserves draft session actuals and timers`() {
        val routine = selectedDayRoutine(planId = "matching-plan-0257")
        val selectedContext = requireNotNull(
            routine.toActiveWorkoutContext(selectedDayIndex = 1, sourceRoutineId = "saved-0643")
        )
        val tracked = TrackedExercise(
            id = "preserved-row",
            name = "Preserved actuals",
            category = "Upper body",
            sets = listOf(
                TrackedSet(
                    id = "preserved-set",
                    previous = "—",
                    weight = "32.5",
                    reps = "9",
                    isDone = true,
                    targetReps = "8"
                )
            ),
            exerciseId = "1576",
            phase = TrackedWorkoutPhase.MAIN_WORK,
            trackingType = TrackedItemType.STRENGTH,
            targetSets = 1
        )
        trackerPreferences.edit()
            .putString("tracked_exercises", Gson().toJson(listOf(tracked)))
            .putString("active_workout_context", Gson().toJson(selectedContext))
            .putString("active_session_id", "preserved-session")
            .putInt("total_seconds", 47)
            .putBoolean("is_timer_paused", true)
            .putString(
                "rest_timer_state",
                Gson().toJson(PersistedRestTimerState(pausedRemainingSeconds = 31, isPaused = true))
            )
            .putString("active_activity_id", "preserved-row")
            .putBoolean("activity_timer_paused", true)
            .commit()

        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                TrackWorkoutScreen(
                    viewModel = null,
                    onBack = {},
                    initialRoutine = routine,
                    initialRoutineSourceId = "saved-0643",
                    initialSelectedDayIndex = 1,
                    exerciseViewModel = ExerciseViewModel(
                        ApplicationProvider.getApplicationContext<Application>()
                    )
                )
            }
        }
        composeTestRule.waitForIdle()

        val restoredType = object : com.google.gson.reflect.TypeToken<List<TrackedExercise>>() {}.type
        val persisted = Gson().fromJson<List<TrackedExercise>>(
            trackerPreferences.getString("tracked_exercises", "[]"),
            restoredType
        )
        assertEquals("preserved-session", trackerPreferences.getString("active_session_id", null))
        assertEquals(47, trackerPreferences.getInt("total_seconds", -1))
        assertEquals("32.5", persisted.single().sets.single().weight)
        assertEquals("9", persisted.single().sets.single().reps)
        assertTrue(persisted.single().sets.single().isDone)
        assertEquals(31, Gson().fromJson(
            trackerPreferences.getString("rest_timer_state", null),
            PersistedRestTimerState::class.java
        ).pausedRemainingSeconds)
        assertEquals("preserved-row", trackerPreferences.getString("active_activity_id", null))
        composeTestRule.onNodeWithTag("draft_conflict_discard").assertDoesNotExist()
    }

    @Test
    fun `explicit conflict replacement resets stale draft and starts exact selected day`() {
        val routine = selectedDayRoutine(planId = "replacement-plan-1576")
        val oldContext = ActiveWorkoutContext(
            sourceRoutineId = "old-saved-routine",
            generatedPlanId = "old-plan",
            routineName = "Old workout",
            dayIndex = 0,
            dayName = "Old day",
            dayTitle = "Old title"
        )
        trackerPreferences.edit()
            .putString(
                "tracked_exercises",
                Gson().toJson(listOf(generatedExercise("Old row", "0257").let {
                    TrackedExercise(
                        id = "old-row",
                        name = it.name,
                        category = "Old",
                        sets = listOf(TrackedSet(previous = "—", weight = "99", reps = "3", isDone = true)),
                        exerciseId = it.exerciseId,
                        phase = TrackedWorkoutPhase.MAIN_WORK,
                        trackingType = TrackedItemType.STRENGTH
                    )
                }))
            )
            .putString("active_workout_context", Gson().toJson(oldContext))
            .putString("active_session_id", "stale-session")
            .putInt("total_seconds", 900)
            .putString("rest_timer_state", Gson().toJson(PersistedRestTimerState(pausedRemainingSeconds = 45, isPaused = true)))
            .putString("active_activity_id", "old-row")
            .putBoolean("activity_timer_paused", false)
            .commit()

        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                TrackWorkoutScreen(
                    viewModel = null,
                    onBack = {},
                    initialRoutine = routine,
                    initialRoutineSourceId = null,
                    initialSelectedDayIndex = 1,
                    exerciseViewModel = ExerciseViewModel(
                        ApplicationProvider.getApplicationContext<Application>()
                    )
                )
            }
        }

        composeTestRule.onNodeWithTag("draft_conflict_discard").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            trackerPreferences.getString("active_session_id", null) != "stale-session"
        }

        val restored = readActiveWorkoutDraft(trackerPreferences)
        assertEquals(listOf("1576"), restored.exercises.map(TrackedExercise::exerciseId))
        assertEquals(1, restored.context?.dayIndex)
        assertEquals("Day 2", restored.context?.dayName)
        assertEquals("Upper strength", restored.context?.dayTitle)
        assertEquals("replacement-plan-1576", restored.context?.generatedPlanId)
        assertEquals(0, trackerPreferences.getInt("total_seconds", -1))
        assertFalse(trackerPreferences.contains("rest_timer_state"))
        assertFalse(trackerPreferences.contains("active_activity_id"))
        assertTrue(trackerPreferences.getBoolean("activity_timer_paused", false))
    }

    @Test
    fun `manual start with no draft opens an empty tracker without seeding an active plan`() {
        renderManualStart()

        composeTestRule.onNodeWithTag("manual_draft_conflict_dialog").assertDoesNotExist()
        val workoutList = composeTestRule.onNode(hasScrollToKeyAction())
        workoutList.performScrollToKey("track_workout_empty_state")
        composeTestRule
            .onNodeWithTag("track_workout_empty_state")
            .assertIsDisplayed()
        val restored = readActiveWorkoutDraft(trackerPreferences)
        assertTrue(restored.exercises.isEmpty())
        assertEquals(null, restored.context)
    }

    @Test
    fun `dashboard generic Track Workout forwards explicit manual start intent`() {
        persistStructuredDraftForManualConflict()
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                WorkoutDashboardScreen(viewModel = null)
            }
        }

        composeTestRule.onNodeWithTag("workout_primary_track_action").performClick()

        composeTestRule.onNodeWithTag("manual_draft_conflict_dialog").assertIsDisplayed()
        assertPersistedManualConflictDraft()
    }

    @Test
    fun `manual start with structured draft requires a decision and resume preserves all draft state`() {
        persistStructuredDraftForManualConflict()

        renderManualStart()

        composeTestRule.onNodeWithTag("manual_draft_conflict_dialog").assertIsDisplayed()
        assertPersistedManualConflictDraft()
        composeTestRule.onNodeWithTag("manual_draft_conflict_resume").performClick()
        composeTestRule.onNodeWithTag("manual_draft_conflict_dialog").assertDoesNotExist()
        val workoutList = composeTestRule.onNode(hasScrollToKeyAction())
        workoutList.performScrollToKey("manual-conflict-row")
        composeTestRule
            .onNodeWithTag("tracked_exercise_Manual conflict row")
            .assertIsDisplayed()
        assertPersistedManualConflictDraft()
    }

    @Test
    fun `discard structured draft and start manual clears draft identity session and timers`() {
        persistStructuredDraftForManualConflict()

        renderManualStart()
        composeTestRule.onNodeWithTag("manual_draft_conflict_discard").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            trackerPreferences.getString("active_session_id", null) != "manual-conflict-session"
        }

        val workoutList = composeTestRule.onNode(hasScrollToKeyAction())
        workoutList.performScrollToKey("track_workout_empty_state")
        composeTestRule
            .onNodeWithTag("track_workout_empty_state")
            .assertIsDisplayed()
        val restored = readActiveWorkoutDraft(trackerPreferences)
        assertTrue(restored.exercises.isEmpty())
        assertEquals(null, restored.context)
        assertNotEquals("manual-conflict-session", trackerPreferences.getString("active_session_id", null))
        assertEquals(0, trackerPreferences.getInt("total_seconds", -1))
        assertTrue(trackerPreferences.getBoolean("is_timer_paused", false))
        assertFalse(trackerPreferences.contains("rest_timer_state"))
        assertFalse(trackerPreferences.contains("active_activity_id"))
        assertTrue(trackerPreferences.getBoolean("activity_timer_paused", false))
        assertTrue(trackerPreferences.getBoolean("is_kg_selected", false))
    }

    @Test
    fun `cancel manual start conflict preserves draft and returns through Workout callback`() {
        persistStructuredDraftForManualConflict()
        var backCalls = 0

        renderManualStart(onBack = { backCalls++ })
        composeTestRule.onNodeWithTag("manual_draft_conflict_cancel").performClick()

        assertEquals(1, backCalls)
        assertPersistedManualConflictDraft()
    }

    private fun renderManualStart(onBack: () -> Unit = {}) {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                TrackWorkoutScreen(
                    viewModel = null,
                    onBack = onBack,
                    initialManualStartRequested = true,
                    exerciseViewModel = ExerciseViewModel(
                        ApplicationProvider.getApplicationContext<Application>()
                    )
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun persistStructuredDraftForManualConflict() {
        val context = ActiveWorkoutContext(
            sourceRoutineId = "saved-manual-conflict",
            generatedPlanId = "plan-manual-conflict",
            routineName = "Structured draft",
            dayIndex = 1,
            dayName = "Day 2",
            dayTitle = "Upper strength"
        )
        val tracked = TrackedExercise(
            id = "manual-conflict-row",
            name = "Manual conflict row",
            category = "Upper body",
            sets = listOf(
                TrackedSet(
                    id = "manual-conflict-set",
                    previous = "—",
                    weight = "22.5",
                    reps = "8",
                    isDone = true,
                    targetReps = "8-10"
                )
            ),
            exerciseId = "0643",
            phase = TrackedWorkoutPhase.MAIN_WORK,
            trackingType = TrackedItemType.STRENGTH,
            targetSets = 1
        )
        trackerPreferences.edit()
            .putString("tracked_exercises", Gson().toJson(listOf(tracked)))
            .putString("active_workout_context", Gson().toJson(context))
            .putString("active_session_id", "manual-conflict-session")
            .putInt("total_seconds", 73)
            .putBoolean("is_timer_paused", true)
            .putBoolean("is_kg_selected", true)
            .putString(
                "rest_timer_state",
                Gson().toJson(PersistedRestTimerState(pausedRemainingSeconds = 29, isPaused = true))
            )
            .putString("active_activity_id", "manual-conflict-row")
            .putBoolean("activity_timer_paused", true)
            .commit()
    }

    private fun assertPersistedManualConflictDraft() {
        val restored = readActiveWorkoutDraft(trackerPreferences)
        assertEquals(listOf("0643"), restored.exercises.map(TrackedExercise::exerciseId))
        assertEquals("22.5", restored.exercises.single().sets.single().weight)
        assertEquals("8", restored.exercises.single().sets.single().reps)
        assertTrue(restored.exercises.single().sets.single().isDone)
        assertEquals("saved-manual-conflict", restored.context?.sourceRoutineId)
        assertEquals("plan-manual-conflict", restored.context?.generatedPlanId)
        assertEquals(1, restored.context?.dayIndex)
        assertEquals("Day 2", restored.context?.dayName)
        assertEquals("Upper strength", restored.context?.dayTitle)
        assertEquals("manual-conflict-session", trackerPreferences.getString("active_session_id", null))
        assertEquals(73, trackerPreferences.getInt("total_seconds", -1))
        assertTrue(trackerPreferences.getBoolean("is_timer_paused", false))
        assertEquals(
            29,
            Gson().fromJson(
                trackerPreferences.getString("rest_timer_state", null),
                PersistedRestTimerState::class.java
            ).pausedRemainingSeconds
        )
        assertEquals("manual-conflict-row", trackerPreferences.getString("active_activity_id", null))
        assertTrue(trackerPreferences.getBoolean("activity_timer_paused", false))
        assertTrue(trackerPreferences.getBoolean("is_kg_selected", false))
    }

    private fun generatedExercise(name: String, exerciseId: String) = GeneratedExercise(
        name = name,
        sets = 3,
        reps = "10",
        targetMuscle = "General",
        instructions = "Use controlled technique.",
        exerciseId = exerciseId
    )

    private fun selectedDayRoutine(planId: String) = GeneratedRoutine(
        name = "Selected-day tracker fixture",
        description = "Test",
        splitType = "Upper lower",
        frequency = "2 days",
        planId = planId,
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Lower strength",
                description = "First day",
                exercises = listOf(generatedExercise("Day one exercise", "0643"))
            ),
            GeneratedDay(
                dayName = "Day 2",
                title = "Upper strength",
                description = "Selected day",
                exercises = listOf(generatedExercise("Day two exercise", "1576"))
            )
        )
    )

    companion object {
        private val canonicalExamples = listOf(
            exercise(id = "0001", name = "Leading Zero Press", category = "chest"),
            exercise(
                id = "fd-exercise-chair-squat",
                name = "Chair Squat",
                category = "strength",
                bodyPart = "upper legs",
                target = "quadriceps"
            ),
            exercise(id = "0088", name = "Barbell Seated Calf Raise"),
            exercise(id = "1371", name = "Barbell Seated Calf Raise")
        )

        private fun exercise(
            id: String,
            name: String,
            category: String = "strength",
            bodyPart: String = "lower legs",
            target: String = "calves"
        ) = Exercise(
            id = id,
            name = name,
            category = category,
            bodyPart = bodyPart,
            equipment = "body weight",
            instructions = Instructions(en = "Test instructions"),
            target = target
        )
    }
}
