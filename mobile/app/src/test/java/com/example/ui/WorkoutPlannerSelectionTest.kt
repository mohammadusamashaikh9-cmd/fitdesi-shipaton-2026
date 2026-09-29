package com.example.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlannerSelectionTest {
    @Test
    fun twoDayPlanCannotSelectAThirdWeekday() {
        var selected = emptySet<String>()
        selected = togglePlannerDaySelection(selected, "Mon", requestedDays = 2)
        assertFalse(hasExactPlannerDaySelection(selected, requestedDays = 2))

        selected = togglePlannerDaySelection(selected, "Wed", requestedDays = 2)
        assertTrue(hasExactPlannerDaySelection(selected, requestedDays = 2))

        assertEquals(
            setOf("Mon", "Wed"),
            togglePlannerDaySelection(selected, "Fri", requestedDays = 2)
        )
    }

    @Test
    fun threeDayPlanEnablesContinuationOnlyAtExactCount() {
        val twoDays = setOf("Mon", "Wed")
        val threeDays = togglePlannerDaySelection(twoDays, "Fri", requestedDays = 3)

        assertFalse(hasExactPlannerDaySelection(twoDays, requestedDays = 3))
        assertTrue(hasExactPlannerDaySelection(threeDays, requestedDays = 3))
        assertEquals(
            threeDays,
            togglePlannerDaySelection(threeDays, "Sun", requestedDays = 3)
        )
    }

    @Test
    fun higherFrequencyPlanRemainsCappedAtRequestedCount() {
        val requestedDays = 6
        val selected = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            .fold(emptySet<String>()) { days, day ->
                togglePlannerDaySelection(days, day, requestedDays)
            }

        assertTrue(hasExactPlannerDaySelection(selected, requestedDays))
        assertEquals(
            selected,
            togglePlannerDaySelection(selected, "Sun", requestedDays)
        )
    }

    @Test
    fun deselectingAtTheLimitMakesAnotherWeekdaySelectable() {
        val selected = setOf("Mon", "Wed")
        val afterDeselect = togglePlannerDaySelection(selected, "Wed", requestedDays = 2)
        val replacement = togglePlannerDaySelection(afterDeselect, "Fri", requestedDays = 2)

        assertEquals(setOf("Mon"), afterDeselect)
        assertEquals(setOf("Mon", "Fri"), replacement)
    }

    @Test
    fun frequencyIncreaseRetainsCompatibleDaysAndIncompatibleReductionClearsAll() {
        val selected = setOf("Mon", "Wed", "Fri")

        assertEquals(
            selected,
            plannerSelectionAfterFrequencyChange(selected, requestedDays = 5)
        )
        assertEquals(
            emptySet<String>(),
            plannerSelectionAfterFrequencyChange(selected, requestedDays = 2)
        )
    }

    @Test
    fun crossTabNavigationPreservesWorkoutWhileDeliberateWorkoutReselectResetsIt() {
        val workout = RootNavigationState(selectedTab = TrainerTab.WORKOUT)

        assertFalse(
            shouldResetWorkoutRootOnSelection(
                currentState = workout,
                destination = TrainerTab.TOOLS
            )
        )
        assertTrue(
            shouldResetWorkoutRootOnSelection(
                currentState = workout,
                destination = TrainerTab.WORKOUT
            )
        )
        assertFalse(
            shouldResetWorkoutRootOnSelection(
                currentState = workout.openDetail(
                    RootDetailDestination.Paywall(
                        PaywallContext(PaywallEntryPoint.AI_WORKOUT_GENERATOR)
                    )
                ),
                destination = TrainerTab.WORKOUT
            )
        )
    }

    @Test
    fun rootTabRestorationKeepsOnlyRoutesWithCompleteSaveableContext() {
        assertEquals("generator", restorableWorkoutRoute("generator"))
        assertEquals("progress", restorableWorkoutRoute("progress"))
        assertEquals("manage_history", restorableWorkoutRoute("manage_history"))
        assertEquals("my_routines", restorableWorkoutRoute("my_routines"))
        assertEquals("dashboard", restorableWorkoutRoute("select_workout_day"))
        assertEquals("dashboard", restorableWorkoutRoute("track_workout"))
        assertEquals("dashboard", restorableWorkoutRoute("build_routine"))
        assertEquals("dashboard", restorableWorkoutRoute("build_workout"))
        assertEquals("dashboard", restorableWorkoutRoute("manage_current_plan"))
    }

    @Test
    fun plannerRestorationReturnsGenerationAndResultsToSafeWizardState() {
        assertEquals("SELECTION", restorablePlannerStage("SELECTION"))
        assertEquals("WIZARD", restorablePlannerStage("WIZARD"))
        assertEquals("WIZARD", restorablePlannerStage("GENERATING"))
        assertEquals("WIZARD", restorablePlannerStage("RESULTS"))
        assertEquals("SELECTION", restorablePlannerStage("unexpected"))
    }

    @Test
    fun removingActivePlanDaysPreservesRemainingPlanAndClearsTheLastDay() {
        val first = com.example.ai.GeneratedDay("Monday", "Upper", "", emptyList())
        val second = com.example.ai.GeneratedDay("Friday", "Lower", "", emptyList())
        val routine = com.example.ai.GeneratedRoutine("Plan", "", "", "2 days", listOf(first, second))

        assertEquals(listOf(second), activePlanAfterRemovingDay(routine, 0)?.days)
        assertEquals(routine, activePlanAfterRemovingDay(routine, 8))
        assertEquals(null, activePlanAfterRemovingDay(routine.copy(days = listOf(first)), 0))
    }
    @Test
    fun weeklyRoutineRetainsEveryWizardStep() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), plannerSteps("Weekly Routine"))
        assertEquals(2, nextPlannerStep(1, "Weekly Routine"))
        assertEquals(3, nextPlannerStep(2, "Weekly Routine"))
        assertEquals(4, nextPlannerStep(3, "Weekly Routine"))
        assertEquals(5, nextPlannerStep(4, "Weekly Routine"))
        assertEquals(6, nextPlannerStep(5, "Weekly Routine"))
        assertEquals(null, nextPlannerStep(6, "Weekly Routine"))
        val weeklyRequest = plannerRequest(generatorType = "Weekly Routine")
        assertEquals(weeklyRequest, normalizedPlannerRequest(weeklyRequest, java.util.Calendar.SUNDAY))
    }

    @Test
    fun singleWorkoutSkipsWeeklyDayScheduling() {
        assertEquals(listOf(1, 2, 3, 5, 6), plannerSteps("Single Workout"))
        assertEquals(2, nextPlannerStep(1, "Single Workout"))
        assertEquals(3, nextPlannerStep(2, "Single Workout"))
        assertEquals(5, nextPlannerStep(3, "Single Workout"))
        assertEquals(6, nextPlannerStep(5, "Single Workout"))
    }

    @Test
    fun singleWorkoutBackNavigationSkipsWeeklyDayScheduling() {
        assertEquals(5, previousPlannerStep(6, "Single Workout"))
        assertEquals(3, previousPlannerStep(5, "Single Workout"))
        assertEquals(2, previousPlannerStep(3, "Single Workout"))
        assertEquals(1, previousPlannerStep(2, "Single Workout"))
        assertEquals(null, previousPlannerStep(1, "Single Workout"))
    }

    @Test
    fun singleWorkoutRequestUsesExactlyOneCurrentWeekdayAndPreservesOtherInputs() {
        val request = plannerRequest(generatorType = "Single Workout")
        val normalized = normalizedPlannerRequest(request, java.util.Calendar.THURSDAY)

        assertEquals(1, normalized.daysCount)
        assertEquals(listOf("Thu"), normalized.selectedDays)
        assertEquals(request.gender, normalized.gender)
        assertEquals(request.age, normalized.age)
        assertEquals(request.level, normalized.level)
        assertEquals(request.goal, normalized.goal)
        assertEquals(request.split, normalized.split)
        assertEquals(request.enforceRecovery, normalized.enforceRecovery)
        assertEquals(request.equipment, normalized.equipment)
    }

    @Test
    fun weekdayMappingCoversAllSevenCalendarDays() {
        assertEquals("Mon", plannerWeekday(java.util.Calendar.MONDAY))
        assertEquals("Tue", plannerWeekday(java.util.Calendar.TUESDAY))
        assertEquals("Wed", plannerWeekday(java.util.Calendar.WEDNESDAY))
        assertEquals("Thu", plannerWeekday(java.util.Calendar.THURSDAY))
        assertEquals("Fri", plannerWeekday(java.util.Calendar.FRIDAY))
        assertEquals("Sat", plannerWeekday(java.util.Calendar.SATURDAY))
        assertEquals("Sun", plannerWeekday(java.util.Calendar.SUNDAY))
    }

    @Test
    fun stepperLabelsExcludeDaysOnlyForSingleWorkout() {
        assertEquals(
            listOf("Info", "Level", "Goal", "Split", "Equipment"),
            plannerStepLabels("Single Workout")
        )
        assertEquals(
            listOf("Info", "Level", "Goal", "Days", "Split", "Equipment"),
            plannerStepLabels("Weekly Routine")
        )
    }
    @Test
    fun increasedFontScaleUsesExpandedPlannerStepLabels() {
        assertFalse(usesExpandedPlannerStepLabels(fontScale = 1f))
        assertTrue(usesExpandedPlannerStepLabels(fontScale = 1.01f))
        assertTrue(usesExpandedPlannerStepLabels(fontScale = 1.6f))
    }
    @Test
    fun plannerUiIsTruthfulExactCountGatedAndSaveableAcrossRootTabs() {
        val app = source("PersonalTrainerApp.kt")
        val dashboard = source("WorkoutDashboardScreen.kt")
        val planner = source("WorkoutGeneratorScreen.kt")
        val paywall = source("SubscriptionPaywallScreen.kt")
        val createWorkout = source("BuildWorkoutScreen.kt")

        assertTrue(app.contains("rememberSaveableStateHolder()"))
        assertTrue(app.contains("SaveableStateProvider(currentTab.name)"))
        assertTrue(dashboard.contains("var activeScreen by rememberSaveable"))
        assertTrue(planner.contains("var currentStage by rememberSaveable"))
        assertTrue(planner.contains("enabled = step != 4 || hasExactPlannerDaySelection"))
        assertTrue(planner.contains("Personalized Workout Planner"))
        assertTrue(planner.contains("Built from your goals, training experience, available days and equipment."))
        assertTrue(planner.contains("Uses FitDesi's deterministic workout-planning logic."))
        assertTrue(planner.contains("\"Quick setup\""))
        assertTrue(planner.contains("\"Choose your training split\""))
        assertFalse(planner.contains("\"Ready in 30 seconds\""))
        assertFalse(planner.contains("\"Focus on specific muscles\""))
        assertTrue(planner.contains("generatorType == \"Single Workout\" -> \"Create workout\""))
        assertTrue(planner.contains("else -> \"Create plan\""))
        assertTrue(planner.contains("step != 6 -> \"Next\""))
        assertTrue(planner.contains("stepNames.chunked(2)"))
        assertTrue(planner.contains("usesExpandedPlannerStepLabels(LocalDensity.current.fontScale)"))
        assertTrue(planner.contains("generatorType = selectedGeneratorType"))
        assertTrue(planner.contains("val input = normalizedPlannerRequest("))
        assertTrue(planner.contains("2-7 days per week"))
        assertTrue(planner.contains("Help us tailor a routine to your goals."))
        assertFalse(planner.contains("3-6 days per week"))
        assertFalse(planner.contains("perfect routine", ignoreCase = true))
        assertTrue(dashboard.contains("title = \"Workout Planner\""))
        assertTrue(dashboard.contains("title = \"Create Workout\""))
        assertTrue(createWorkout.contains("text = \"CREATE WORKOUT\""))
        assertTrue(createWorkout.contains("Custom workout created manually."))
        assertFalse(createWorkout.contains("Personalized routine created on the planner."))
        assertTrue(dashboard.contains("Text(\"Manage current plan\")"))
        assertTrue(dashboard.contains("Text(\"Clear current plan\")"))
        assertTrue(dashboard.contains("Text(\"Remove day\")"))
        assertFalse(planner.contains("\"AI Workout Generator\""))
        assertFalse(dashboard.contains("\"AI Workout Generator\""))
        assertFalse(paywall.contains("\"AI Workout Generator"))
    }

    private fun plannerRequest(generatorType: String) = com.example.ai.AiWorkoutRequest(
        generatorType = generatorType,
        gender = "Female",
        age = 31,
        level = "Intermediate",
        goal = "Get Stronger",
        daysCount = 4,
        selectedDays = listOf("Mon", "Tue", "Thu", "Sat"),
        split = "Full Body",
        enforceRecovery = true,
        equipment = listOf("Dumbbells", "Bodyweight")
    )
    private fun source(fileName: String): String {
        val relativePath = "src/main/java/com/example/ui/" + fileName
        val candidates = listOf(
            File(relativePath),
            File("app/" + relativePath),
            File("mobile/app/" + relativePath)
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate " + fileName)
    }
}