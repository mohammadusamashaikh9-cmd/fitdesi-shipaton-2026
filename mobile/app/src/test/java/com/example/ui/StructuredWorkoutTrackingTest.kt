package com.example.ui

import com.example.ai.ActivityPrescription
import com.example.ai.ActivityPrescriptionMode
import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.ai.GeneralWarmupPrescription
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.ai.WarmupIntensity
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredWorkoutTrackingTest {
    @Test
    fun `structured conversion is deterministic`() {
        val routine = structuredRoutine()

        assertEquals(routine.toTrackedExercises(0), routine.toTrackedExercises(0))
    }

    @Test
    fun `structured day maps activities and main work in phase order`() {
        val tracked = structuredRoutine().toTrackedExercises(0)

        assertEquals(
            listOf(
                TrackedWorkoutPhase.GENERAL_WARMUP,
                TrackedWorkoutPhase.PREPARATION,
                TrackedWorkoutPhase.PREPARATION,
                TrackedWorkoutPhase.MAIN_WORK,
                TrackedWorkoutPhase.MAIN_WORK,
                TrackedWorkoutPhase.COOLDOWN
            ),
            tracked.map(TrackedExercise::phase)
        )

        val generalWarmup = tracked.first()
        assertEquals("0257", generalWarmup.exerciseId)
        assertEquals(TrackedItemType.ACTIVITY, generalWarmup.trackingType)
        assertEquals(ActivityPrescriptionMode.DURATION_SECONDS, generalWarmup.activityPrescription?.mode)
        assertEquals(180, generalWarmup.activityPrescription?.durationSeconds)
        assertTrue(generalWarmup.sets.isEmpty())

        val preparation = tracked.single { it.exerciseId == "0643" }
        assertEquals("0643", preparation.exerciseId)
        assertEquals(8, preparation.activityPrescription?.repetitions)
        assertTrue(preparation.activityPrescription?.perSide == true)
        assertTrue(preparation.sets.isEmpty())
        assertEquals("Use a comfortable range.", preparation.instructions)

        val freeTextPreparation = tracked.single { it.exerciseId == "prep-free-text" }
        assertEquals(ActivityPrescriptionMode.FREE_TEXT, freeTextPreparation.activityPrescription?.mode)
        assertEquals("Move smoothly for one controlled round", freeTextPreparation.activityPrescription?.freeText)

        val cooldown = tracked.single { it.phase == TrackedWorkoutPhase.COOLDOWN }
        assertEquals("1576", cooldown.exerciseId)
        assertEquals(45, cooldown.activityPrescription?.durationSeconds)
        assertTrue(cooldown.sets.isEmpty())
    }

    @Test
    fun `main IDs targets actuals and prescribed rest remain exact`() {
        val mains = structuredRoutine().toTrackedExercises(0)
            .filter { it.phase == TrackedWorkoutPhase.MAIN_WORK }

        assertEquals(listOf("fd-exercise-chair-squat", "0286"), mains.map(TrackedExercise::exerciseId))

        val chairSquat = mains.first()
        assertEquals(3, chairSquat.targetSets)
        assertEquals(1, chairSquat.sets.count(TrackedSet::isRampUp))
        assertEquals(3, chairSquat.sets.count { !it.isRampUp })
        assertTrue(chairSquat.sets.all { it.weight.isEmpty() && it.reps.isEmpty() })
        assertTrue(chairSquat.sets.filterNot(TrackedSet::isRampUp).all { it.targetReps == "8-12" })
        assertTrue(chairSquat.sets.filterNot(TrackedSet::isRampUp).all { it.prescribedRestSeconds == 90 })
        assertEquals("Protect the knees.", chairSquat.safetyNote)

        val rampUp = chairSquat.sets.single(TrackedSet::isRampUp)
        assertEquals("8", rampUp.targetReps)
        assertEquals(RampUpLoadCue.VERY_LIGHT, rampUp.rampUpLoadCue)
        assertEquals(45, rampUp.prescribedRestSeconds)
        assertEquals("", rampUp.weight)
        assertEquals("", rampUp.reps)
        assertFalse(rampUp.isDone)
    }

    @Test
    fun `0286 defensively creates zero ramp up tracking rows`() {
        val excluded = structuredRoutine().toTrackedExercises(0).single { it.exerciseId == "0286" }

        assertTrue(excluded.sets.none(TrackedSet::isRampUp))
        assertEquals(2, excluded.sets.size)
    }

    @Test
    fun `opaque canonical IDs remain unchanged`() {
        val ids = structuredRoutine().toTrackedExercises(0).map(TrackedExercise::exerciseId)

        assertEquals(
            listOf("0257", "0643", "prep-free-text", "fd-exercise-chair-squat", "0286", "1576"),
            ids
        )
        assertEquals("0643", ids[1])
        assertEquals("1576", ids.last())
    }

    @Test
    fun `rest selection uses prescriptions and legacy fallback only for strength sets`() {
        val tracked = structuredRoutine().toTrackedExercises(0)
        val main = tracked.single { it.exerciseId == "fd-exercise-chair-squat" }
        val rampUp = main.sets.single(TrackedSet::isRampUp)
        val working = main.sets.first { !it.isRampUp }
        val activity = tracked.first()

        assertEquals(45, automaticRestSeconds(main, rampUp))
        assertEquals(90, automaticRestSeconds(main, working))
        assertNull(automaticRestSeconds(activity, TrackedSet(previous = "—", weight = "", reps = "")))
        assertEquals(TRACK_WORKOUT_REST_SECONDS, resolveStrengthRestSeconds(null))
        assertEquals(TRACK_WORKOUT_REST_SECONDS, resolveStrengthRestSeconds(0))
    }

    @Test
    fun `working metrics exclude activities and ramp ups and allow bodyweight completion`() {
        val activity = trackedActivity(
            id = "prep",
            phase = TrackedWorkoutPhase.PREPARATION,
            isDone = true
        )
        val main = trackedMain(
            id = "0257",
            sets = listOf(
                TrackedSet(
                    id = "ramp",
                    previous = "—",
                    weight = "100",
                    reps = "5",
                    isDone = true,
                    targetReps = "5",
                    prescribedRestSeconds = 45,
                    isRampUp = true
                ),
                TrackedSet(
                    id = "bodyweight",
                    previous = "—",
                    weight = "",
                    reps = "12",
                    isDone = true,
                    targetReps = "8-12",
                    prescribedRestSeconds = 90
                ),
                TrackedSet(
                    id = "loaded",
                    previous = "—",
                    weight = "20",
                    reps = "10",
                    isDone = true,
                    targetReps = "8-12",
                    prescribedRestSeconds = 90
                )
            )
        )

        val metrics = calculateWorkoutSessionMetrics(
            exercises = listOf(activity, main),
            weightsAreKg = true,
            sessionDurationSeconds = 321
        )

        assertEquals(1, metrics.completedPreparationActivities)
        assertEquals(1, metrics.completedRampUpSets)
        assertEquals(2, metrics.completedSets)
        assertEquals(2, metrics.totalSets)
        assertEquals(1, metrics.completedExerciseCount)
        assertEquals(200.0, metrics.volumeKg, 0.001)
        assertEquals(321, metrics.sessionDurationSeconds)
    }

    @Test
    fun `historical draft normalization preserves actual values and completion`() {
        val historicalJson = """
            [
              {
                "id":"legacy-row",
                "name":"Legacy Exercise",
                "category":"Strength",
                "sets":[
                  {
                    "id":"legacy-set",
                    "previous":"40 kg",
                    "weight":"42.5",
                    "reps":"8",
                    "isDone":true
                  }
                ]
              }
            ]
        """.trimIndent()

        val restored = Gson()
            .fromJson(historicalJson, Array<TrackedExercise>::class.java)
            .toList()
            .normalizeTrackedExercises()
            .single()

        assertEquals("", restored.exerciseId)
        assertEquals(TrackedWorkoutPhase.MAIN_WORK, restored.phase)
        assertEquals(TrackedItemType.STRENGTH, restored.trackingType)
        assertEquals("42.5", restored.sets.single().weight)
        assertEquals("8", restored.sets.single().reps)
        assertTrue(restored.sets.single().isDone)
        assertFalse(restored.sets.single().isRampUp)
        assertEquals(TRACK_WORKOUT_REST_SECONDS, restored.sets.single().prescribedRestSeconds)
    }

    @Test
    fun `additional working set preserves target but starts actuals blank`() {
        val source = trackedMain(
            id = "0257",
            sets = listOf(
                TrackedSet(
                    id = "performed",
                    previous = "—",
                    weight = "30",
                    reps = "10",
                    isDone = true,
                    targetReps = "8-12",
                    prescribedRestSeconds = 90
                )
            )
        )

        val added = source.withAdditionalBlankWorkingSet(previous = "30 kg × 10").sets.last()

        assertEquals("30 kg × 10", added.previous)
        assertEquals("8-12", added.targetReps)
        assertEquals(90, added.prescribedRestSeconds)
        assertEquals("", added.weight)
        assertEquals("", added.reps)
        assertFalse(added.isDone)
        assertFalse(added.isRampUp)
    }

    @Test
    fun `no completed phase or set has no progress and cannot persist`() {
        assertAssessment(
            exercises = listOf(
                trackedActivity("warmup", TrackedWorkoutPhase.GENERAL_WARMUP, isDone = false),
                trackedMain(
                    id = "0643",
                    sets = listOf(incompleteWorkingSet("work"))
                )
            ),
            isPlanComplete = false,
            hasAnyProgress = false,
            hasCompletedMainWorkingSet = false,
            canPersistWithCurrentWorkoutLog = false
        )
    }

    @Test
    fun `general warmup only is progress but cannot persist`() {
        assertActivityOnlyProgress(TrackedWorkoutPhase.GENERAL_WARMUP)
    }

    @Test
    fun `preparation only is progress but cannot persist`() {
        assertActivityOnlyProgress(TrackedWorkoutPhase.PREPARATION)
    }

    @Test
    fun `ramp up only is progress but cannot persist`() {
        assertAssessment(
            exercises = listOf(
                trackedMain(
                    id = "0643",
                    sets = listOf(
                        completedRampUpSet("ramp"),
                        incompleteWorkingSet("work")
                    )
                )
            ),
            isPlanComplete = false,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = false,
            canPersistWithCurrentWorkoutLog = false
        )
    }

    @Test
    fun `cooldown only is progress but cannot persist`() {
        assertActivityOnlyProgress(TrackedWorkoutPhase.COOLDOWN)
    }

    @Test
    fun `completed weighted main working set is persistable main work`() {
        assertAssessment(
            exercises = listOf(
                trackedMain(
                    id = "0257",
                    sets = listOf(completedWorkingSet("weighted", weight = "40"))
                )
            ),
            isPlanComplete = true,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = true,
            canPersistWithCurrentWorkoutLog = true
        )
    }

    @Test
    fun `completed bodyweight main working set is persistable without external weight`() {
        assertAssessment(
            exercises = listOf(
                trackedMain(
                    id = "fd-exercise-chair-squat",
                    sets = listOf(completedWorkingSet("bodyweight", weight = ""))
                )
            ),
            isPlanComplete = true,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = true,
            canPersistWithCurrentWorkoutLog = true
        )
    }

    @Test
    fun `incomplete session with a completed main set can persist`() {
        assertAssessment(
            exercises = listOf(
                trackedMain(
                    id = "1576",
                    sets = listOf(
                        completedWorkingSet("complete", weight = "20"),
                        incompleteWorkingSet("incomplete")
                    )
                )
            ),
            isPlanComplete = false,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = true,
            canPersistWithCurrentWorkoutLog = true
        )
    }

    @Test
    fun `fully completed structured session is complete and persistable`() {
        assertAssessment(
            exercises = listOf(
                trackedActivity("warmup", TrackedWorkoutPhase.GENERAL_WARMUP, isDone = true),
                trackedActivity("prep", TrackedWorkoutPhase.PREPARATION, isDone = true),
                trackedMain(
                    id = "0643",
                    sets = listOf(
                        completedRampUpSet("ramp"),
                        completedWorkingSet("work", weight = "")
                    )
                ),
                trackedActivity("cooldown", TrackedWorkoutPhase.COOLDOWN, isDone = true)
            ),
            isPlanComplete = true,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = true,
            canPersistWithCurrentWorkoutLog = true
        )
    }

    @Test
    fun `selected day zero context preserves source plan and exact day identity`() {
        val context = requireNotNull(structuredRoutine()
            .copy(planId = "plan-0257", name = "Morning strength")
            .toActiveWorkoutContext(selectedDayIndex = 0, sourceRoutineId = "saved-routine-0643"))

        assertEquals("saved-routine-0643", context.sourceRoutineId)
        assertEquals("plan-0257", context.generatedPlanId)
        assertEquals("Morning strength", context.routineName)
        assertEquals(0, context.dayIndex)
        assertEquals("Day 1", context.dayName)
        assertEquals("Full body", context.dayTitle)
        assertEquals(
            context,
            Gson().fromJson(Gson().toJson(context), ActiveWorkoutContext::class.java)
        )
    }

    @Test
    fun `selected non-first day maps only its exact phases IDs and context`() {
        val routine = multiDayRoutine()

        val tracked = routine.toTrackedExercises(1)
        val context = requireNotNull(
            routine.toActiveWorkoutContext(selectedDayIndex = 1, sourceRoutineId = "saved-routine-0643")
        )

        assertEquals(
            listOf("day-two-general", "day-two-prep", "0257", "day-two-cooldown"),
            tracked.map(TrackedExercise::exerciseId)
        )
        assertEquals(
            listOf(
                TrackedWorkoutPhase.GENERAL_WARMUP,
                TrackedWorkoutPhase.PREPARATION,
                TrackedWorkoutPhase.MAIN_WORK,
                TrackedWorkoutPhase.COOLDOWN
            ),
            tracked.map(TrackedExercise::phase)
        )
        assertEquals(1, context.dayIndex)
        assertEquals("Day 2", context.dayName)
        assertEquals("Exact selected day", context.dayTitle)
        assertEquals("saved-routine-0643", context.sourceRoutineId)
        assertEquals("multi-plan-1576", context.generatedPlanId)
    }

    @Test
    fun `invalid selected day never falls back to first day`() {
        val routine = multiDayRoutine()

        assertTrue(routine.toTrackedExercises(-1).isEmpty())
        assertTrue(routine.toTrackedExercises(2).isEmpty())
        assertNull(routine.toActiveWorkoutContext(selectedDayIndex = -1))
        assertNull(routine.toActiveWorkoutContext(selectedDayIndex = 2))
        assertNull(routine.toActiveWorkoutDraftSeed(selectedDayIndex = 2))
    }

    @Test
    fun `AI Coach and library selected day contexts preserve their available plan identities`() {
        val routine = multiDayRoutine()
        val coachOnly = requireNotNull(routine.toActiveWorkoutContext(selectedDayIndex = 1))
        val libraryBacked = requireNotNull(
            routine.toActiveWorkoutContext(selectedDayIndex = 1, sourceRoutineId = "saved-routine-0257")
        )

        assertNull(coachOnly.sourceRoutineId)
        assertEquals("multi-plan-1576", coachOnly.generatedPlanId)
        assertEquals("saved-routine-0257", libraryBacked.sourceRoutineId)
        assertEquals("multi-plan-1576", libraryBacked.generatedPlanId)
    }

    @Test
    fun `draft relationship requires exact plan and selected day snapshot`() {
        val selected = requireNotNull(
            multiDayRoutine().toActiveWorkoutContext(
                selectedDayIndex = 1,
                sourceRoutineId = "saved-fd-exercise-chair-squat"
            )
        )

        assertEquals(
            ActiveDraftRelationship.EXACT_PLAN_DAY_MATCH,
            activeDraftRelationship(hasDraft = true, restoredContext = selected, selectedContext = selected.copy())
        )
        assertEquals(
            ActiveDraftRelationship.MANUAL_OR_HISTORICAL_DRAFT,
            activeDraftRelationship(hasDraft = true, restoredContext = null, selectedContext = selected)
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(
                hasDraft = true,
                restoredContext = selected,
                selectedContext = selected.copy(sourceRoutineId = "different-routine")
            )
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(
                hasDraft = true,
                restoredContext = selected,
                selectedContext = selected.copy(dayIndex = 0, dayName = "Day 1", dayTitle = "Full body")
            )
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(
                hasDraft = true,
                restoredContext = selected,
                selectedContext = selected.copy(dayTitle = "Mutated selected day")
            )
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(
                hasDraft = true,
                restoredContext = selected,
                selectedContext = selected.copy(generatedPlanId = "changed-plan")
            )
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(
                hasDraft = true,
                restoredContext = selected.copy(generatedPlanId = "old-plan"),
                selectedContext = selected
            )
        )
        assertEquals(
            ActiveDraftRelationship.NONE,
            activeDraftRelationship(hasDraft = false, restoredContext = null, selectedContext = selected)
        )
    }

    @Test
    fun `draft with no provable plan identity is never an automatic structured match`() {
        val selected = ActiveWorkoutContext(
            generatedPlanId = "plan-0257",
            routineName = "Selected",
            dayIndex = 0,
            dayName = "Day 1",
            dayTitle = "Strength"
        )
        val historical = selected.copy(generatedPlanId = null)
        val unidentifiableSelection = selected.copy(generatedPlanId = null)

        assertEquals(
            ActiveDraftRelationship.MANUAL_OR_HISTORICAL_DRAFT,
            activeDraftRelationship(true, historical, selected)
        )
        assertEquals(
            ActiveDraftRelationship.OTHER_STRUCTURED_DRAFT,
            activeDraftRelationship(true, selected, unidentifiableSelection)
        )
    }

    @Test
    fun `explicit selected-routine replacement seeds only selected routine data`() {
        val replacement = requireNotNull(structuredRoutine()
            .copy(name = "Replacement routine", planId = "replacement-0286")
            .toActiveWorkoutDraftSeed(selectedDayIndex = 0, sourceRoutineId = "saved-replacement-0257"))

        assertEquals("Replacement routine", replacement.context.routineName)
        assertEquals("saved-replacement-0257", replacement.context.sourceRoutineId)
        assertEquals("replacement-0286", replacement.context.generatedPlanId)
        assertEquals(
            listOf("0257", "0643", "prep-free-text", "fd-exercise-chair-squat", "0286", "1576"),
            replacement.exercises.map(TrackedExercise::exerciseId)
        )
    }

    @Test
    fun `explicit replacement creates fresh session timers and exact selected day state`() {
        val replacement = requireNotNull(
            multiDayRoutine().freshActiveWorkoutState(
                selectedDayIndex = 1,
                sourceRoutineId = "saved-replacement-0643",
                freshSessionId = "fresh-session-1576"
            )
        )

        assertEquals("fresh-session-1576", replacement.sessionId)
        assertEquals(0, replacement.totalSeconds)
        assertFalse(replacement.isWorkoutTimerPaused)
        assertFalse(replacement.restTimer.isActive)
        assertNull(replacement.activityTimer.activeExerciseId)
        assertTrue(replacement.activityTimer.isPaused)
        assertEquals(1, replacement.seed.context.dayIndex)
        assertEquals("Day 2", replacement.seed.context.dayName)
        assertEquals(listOf("day-two-general", "day-two-prep", "0257", "day-two-cooldown"),
            replacement.seed.exercises.map(TrackedExercise::exerciseId))
    }

    @Test
    fun `persisted rest uses deadline pause resume skip and expiry semantics`() {
        val started = startPersistedRestTimer(durationSeconds = 90, nowEpochMillis = 1_000L)
        assertEquals(90, remainingRestSeconds(started, nowEpochMillis = 1_000L))

        val paused = pausePersistedRestTimer(started, nowEpochMillis = 31_000L)
        assertTrue(paused.isPaused)
        assertEquals(60, remainingRestSeconds(paused, nowEpochMillis = 70_000L))

        val resumed = resumePersistedRestTimer(paused, nowEpochMillis = 70_000L)
        assertFalse(resumed.isPaused)
        assertEquals(60, remainingRestSeconds(resumed, nowEpochMillis = 70_000L))
        assertEquals(0, remainingRestSeconds(resumed, nowEpochMillis = 130_000L))
        assertFalse(normalizePersistedRestTimer(resumed, nowEpochMillis = 130_000L).isActive)
        assertFalse(skipPersistedRestTimer().isActive)
    }

    @Test
    fun `activity timer starts pauses and switches one activity deterministically`() {
        val started = toggleActivityTimer(ActivityTimerState(), "0257")
        assertEquals("0257", started.activeExerciseId)
        assertFalse(started.isPaused)

        val paused = toggleActivityTimer(started, "0257")
        assertEquals("0257", paused.activeExerciseId)
        assertTrue(paused.isPaused)

        val switched = toggleActivityTimer(paused, "fd-exercise-chair-squat")
        assertEquals("fd-exercise-chair-squat", switched.activeExerciseId)
        assertFalse(switched.isPaused)
        assertTrue(clearActivityTimer().isPaused)
        assertEquals(null, clearActivityTimer().activeExerciseId)
    }

    @Test
    fun `finish decision saves only a fully complete plan`() {
        assertEquals(
            WorkoutFinishDecision.SAVE_COMPLETED_WORKOUT,
            workoutFinishDecision(
                SessionCompletionAssessment(
                    isPlanComplete = true,
                    hasAnyProgress = true,
                    hasCompletedMainWorkingSet = true,
                    canPersistWithCurrentWorkoutLog = true
                )
            )
        )
        assertEquals(
            WorkoutFinishDecision.SHOW_INCOMPLETE_OPTIONS,
            workoutFinishDecision(
                SessionCompletionAssessment(
                    isPlanComplete = false,
                    hasAnyProgress = true,
                    hasCompletedMainWorkingSet = true,
                    canPersistWithCurrentWorkoutLog = true
                )
            )
        )
        assertEquals(
            WorkoutFinishDecision.SHOW_INCOMPLETE_OPTIONS,
            workoutFinishDecision(
                SessionCompletionAssessment(
                    isPlanComplete = false,
                    hasAnyProgress = true,
                    hasCompletedMainWorkingSet = false,
                    canPersistWithCurrentWorkoutLog = false
                )
            )
        )
        assertEquals(
            WorkoutFinishDecision.SHOW_NO_PROGRESS_OPTIONS,
            workoutFinishDecision(
                SessionCompletionAssessment(
                    isPlanComplete = false,
                    hasAnyProgress = false,
                    hasCompletedMainWorkingSet = false,
                    canPersistWithCurrentWorkoutLog = false
                )
            )
        )
    }

    @Test
    fun `manual workout finish is based on truthful main work not routine identity`() {
        val historicalManualExercise = TrackedExercise(
            id = "historical-manual-row",
            name = "Manual Bodyweight Exercise",
            category = "Strength",
            sets = listOf(completedWorkingSet("manual-complete", weight = "")),
            exerciseId = null,
            phase = null,
            trackingType = null
        )
        val completedManual = listOf(historicalManualExercise).normalizeTrackedExercises()
        val partialManual = listOf(
            historicalManualExercise.copy(
                sets = listOf(
                    completedWorkingSet("manual-complete", weight = ""),
                    incompleteWorkingSet("manual-incomplete")
                )
            )
        ).normalizeTrackedExercises()
        val noWorkManual = listOf(
            historicalManualExercise.copy(
                sets = listOf(incompleteWorkingSet("manual-empty"))
            )
        ).normalizeTrackedExercises()

        assertEquals(
            WorkoutFinishDecision.SAVE_COMPLETED_WORKOUT,
            workoutFinishDecision(assessWorkoutCompletion(completedManual))
        )
        assertEquals(
            WorkoutFinishDecision.SHOW_INCOMPLETE_OPTIONS,
            workoutFinishDecision(assessWorkoutCompletion(partialManual))
        )
        assertEquals(
            WorkoutFinishDecision.SHOW_NO_PROGRESS_OPTIONS,
            workoutFinishDecision(assessWorkoutCompletion(noWorkManual))
        )
        assertEquals("", completedManual.single().exerciseId)
        assertEquals(TrackedWorkoutPhase.MAIN_WORK, completedManual.single().phase)
        assertEquals(TrackedItemType.STRENGTH, completedManual.single().trackingType)
    }

    private fun structuredRoutine(): GeneratedRoutine = GeneratedRoutine(
        name = "Structured tracking fixture",
        description = "Test",
        splitType = "Full body",
        frequency = "1 day",
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Full body",
                description = "Test day",
                generalWarmup = GeneralWarmupPrescription(
                    label = "General Warm-up",
                    durationSeconds = 180,
                    intensityCue = WarmupIntensity.EASY,
                    canonicalExerciseId = "0257"
                ),
                warmupExercises = listOf(
                    activityExercise(
                        id = "0643",
                        name = "Preparation",
                        reps = "8 per side",
                        prescription = ActivityPrescription(
                            mode = ActivityPrescriptionMode.REPETITIONS,
                            repetitions = 8,
                            perSide = true
                        )
                    ),
                    activityExercise(
                        id = "prep-free-text",
                        name = "Movement Preparation",
                        reps = "One controlled round",
                        prescription = ActivityPrescription(
                            mode = ActivityPrescriptionMode.FREE_TEXT,
                            freeText = "Move smoothly for one controlled round"
                        )
                    )
                ),
                exercises = listOf(
                    GeneratedExercise(
                        name = "Chair Squat",
                        sets = 3,
                        reps = "8-12",
                        targetMuscle = "Legs",
                        instructions = "Move with control.",
                        restSeconds = 90,
                        exerciseId = "fd-exercise-chair-squat",
                        safetyNote = "Protect the knees.",
                        rampUpSets = listOf(
                            RampUpSetPrescription(
                                ordinal = 1,
                                loadCue = RampUpLoadCue.VERY_LIGHT,
                                repetitions = 8,
                                restSeconds = 45
                            )
                        )
                    ),
                    GeneratedExercise(
                        name = "Excluded Ramp Exercise",
                        sets = 2,
                        reps = "6",
                        targetMuscle = "Shoulders",
                        instructions = "Alternate sides.",
                        restSeconds = 75,
                        exerciseId = "0286",
                        rampUpSets = listOf(
                            RampUpSetPrescription(
                                ordinal = 1,
                                loadCue = RampUpLoadCue.LIGHT,
                                repetitions = 5,
                                restSeconds = 60
                            )
                        )
                    )
                ),
                cooldownExercises = listOf(
                    activityExercise(
                        id = "1576",
                        name = "Cooldown",
                        reps = "45 sec",
                        prescription = ActivityPrescription(
                            mode = ActivityPrescriptionMode.DURATION_SECONDS,
                            durationSeconds = 45
                        )
                    )
                )
            )
        )
    )

    private fun multiDayRoutine(): GeneratedRoutine = structuredRoutine().copy(
        planId = "multi-plan-1576",
        days = structuredRoutine().days + GeneratedDay(
            dayName = "Day 2",
            title = "Exact selected day",
            description = "Non-first day",
            focus = "Upper body",
            generalWarmup = GeneralWarmupPrescription(
                label = "Day two general",
                durationSeconds = 120,
                intensityCue = WarmupIntensity.EASY,
                canonicalExerciseId = "day-two-general"
            ),
            warmupExercises = listOf(
                activityExercise(
                    id = "day-two-prep",
                    name = "Day two preparation",
                    reps = "6 per side",
                    prescription = ActivityPrescription(
                        mode = ActivityPrescriptionMode.REPETITIONS,
                        repetitions = 6,
                        perSide = true
                    )
                )
            ),
            exercises = listOf(
                GeneratedExercise(
                    name = "Exact Day Two Exercise",
                    sets = 2,
                    reps = "8",
                    targetMuscle = "Back",
                    instructions = "Track only this selected day.",
                    restSeconds = 75,
                    exerciseId = "0257"
                )
            ),
            cooldownExercises = listOf(
                activityExercise(
                    id = "day-two-cooldown",
                    name = "Day two cooldown",
                    reps = "30 sec",
                    prescription = ActivityPrescription(
                        mode = ActivityPrescriptionMode.DURATION_SECONDS,
                        durationSeconds = 30
                    )
                )
            )
        )
    )

    private fun activityExercise(
        id: String,
        name: String,
        reps: String,
        prescription: ActivityPrescription
    ) = GeneratedExercise(
        name = name,
        sets = 1,
        reps = reps,
        targetMuscle = "Mobility",
        instructions = "Use a comfortable range.",
        exerciseId = id,
        activityPrescription = prescription
    )

    private fun trackedActivity(
        id: String,
        phase: TrackedWorkoutPhase,
        isDone: Boolean
    ) = TrackedExercise(
        id = "tracked-$id",
        name = id,
        category = phase.name,
        sets = emptyList(),
        exerciseId = id,
        phase = phase,
        trackingType = TrackedItemType.ACTIVITY,
        activityPrescription = ActivityPrescription(
            mode = ActivityPrescriptionMode.REPETITIONS,
            repetitions = 8
        ),
        isActivityDone = isDone
    )

    @Test
    fun `workload input includes only completed main working sets and preserves opaque ids`() {
        val counts = completedMainWorkingSetCounts(
            listOf(
                trackedMain(
                    id = "0257",
                    sets = listOf(
                        completedWorkingSet("working", "10"),
                        completedRampUpSet("ramp"),
                        incompleteWorkingSet("incomplete")
                    )
                ),
                trackedActivity("0643", TrackedWorkoutPhase.PREPARATION, isDone = true),
                trackedActivity("1512", TrackedWorkoutPhase.COOLDOWN, isDone = true)
            )
        )

        assertEquals(1, counts.size)
        assertEquals("0257", counts.single().exerciseId)
        assertEquals(1, counts.single().completedMainWorkingSets)
    }

    private fun trackedMain(id: String, sets: List<TrackedSet>) = TrackedExercise(
        id = "tracked-$id",
        name = id,
        category = "Strength",
        sets = sets,
        exerciseId = id,
        phase = TrackedWorkoutPhase.MAIN_WORK,
        trackingType = TrackedItemType.STRENGTH,
        targetSets = sets.count { !it.isRampUp }
    )

    private fun completedWorkingSet(id: String, weight: String) = TrackedSet(
        id = id,
        previous = "—",
        weight = weight,
        reps = "10",
        isDone = true
    )

    private fun incompleteWorkingSet(id: String) = TrackedSet(
        id = id,
        previous = "—",
        weight = "",
        reps = "",
        isDone = false
    )

    private fun completedRampUpSet(id: String) = TrackedSet(
        id = id,
        previous = "—",
        weight = "",
        reps = "8",
        isDone = true,
        isRampUp = true
    )

    private fun assertActivityOnlyProgress(phase: TrackedWorkoutPhase) {
        assertAssessment(
            exercises = listOf(
                trackedActivity("activity", phase, isDone = true),
                trackedMain(
                    id = "0643",
                    sets = listOf(incompleteWorkingSet("work"))
                )
            ),
            isPlanComplete = false,
            hasAnyProgress = true,
            hasCompletedMainWorkingSet = false,
            canPersistWithCurrentWorkoutLog = false
        )
    }

    private fun assertAssessment(
        exercises: List<TrackedExercise>,
        isPlanComplete: Boolean,
        hasAnyProgress: Boolean,
        hasCompletedMainWorkingSet: Boolean,
        canPersistWithCurrentWorkoutLog: Boolean
    ) {
        val assessment = assessWorkoutCompletion(exercises)

        assertEquals(isPlanComplete, assessment.isPlanComplete)
        assertEquals(hasAnyProgress, assessment.hasAnyProgress)
        assertEquals(hasCompletedMainWorkingSet, assessment.hasCompletedMainWorkingSet)
        assertEquals(canPersistWithCurrentWorkoutLog, assessment.canPersistWithCurrentWorkoutLog)
    }
}
