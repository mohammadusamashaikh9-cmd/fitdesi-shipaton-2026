package com.example.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNavigationContractTest {
    @Test
    fun rootTabsContainTheFiveApprovedDestinationsInOrder() {
        assertEquals(
            listOf("HOME", "WORKOUT", "AI_COACH", "TOOLS", "PROFILE"),
            TrainerTab.values().map { it.name }
        )
    }

    @Test
    fun aiCoachIsRenderedByTheRootScaffoldWithoutACompetingOverlayFlag() {
        val source = personalTrainerAppSource()

        assertTrue(source.contains("TrainerTab.AI_COACH -> AiCoachScreen("))
        assertTrue(source.contains("onOpenAiCoach = { navigateToRoot(TrainerTab.AI_COACH) }"))
        assertFalse(source.contains("var showAiCoach"))
        assertFalse(source.contains("TrainerTab.AI_COACH -> Unit"))
    }

    @Test
    fun profileMembershipOpensTypedPaywallAndBackReturnsToProfile() {
        val profileRoot = RootNavigationState(selectedTab = TrainerTab.PROFILE)
        val membershipPaywall = RootDetailDestination.Paywall(
            PaywallContext(PaywallEntryPoint.MEMBERSHIP)
        )

        val paywall = profileRoot.openDetail(
            destination = membershipPaywall,
            ownerTab = TrainerTab.PROFILE
        )

        assertEquals(TrainerTab.PROFILE, paywall.selectedTab)
        assertEquals(membershipPaywall, paywall.detailDestination)
        assertTrue(paywall.detailDestination is RootDetailDestination.Paywall)

        val returned = paywall.closeDetail()
        assertEquals(TrainerTab.PROFILE, returned.selectedTab)
        assertEquals(null, returned.detailDestination)
    }

    @Test
    fun `Profile account entry opens focused typed detail and Back returns to Profile`() {
        val profileRoot = RootNavigationState(selectedTab = TrainerTab.PROFILE)
        val account = profileRoot.openDetail(
            destination = RootDetailDestination.Account(),
            ownerTab = TrainerTab.PROFILE
        )

        assertEquals(TrainerTab.PROFILE, account.selectedTab)
        assertEquals(RootDetailDestination.Account(), account.detailDestination)
        assertFalse(
            shouldShowRootBottomNavigation(
                isTrainingMode = false,
                detailDestination = account.detailDestination
            )
        )
        assertFalse(shouldRenderSelectedRootContent(account.detailDestination))
        assertEquals(profileRoot, account.closeDetail())
    }

    @Test
    fun `Paywall account entry retains context and returns only when commercial identity is ready`() {
        val context = PaywallContext(PaywallEntryPoint.ADVANCED_ANALYTICS)
        val paywall = RootNavigationState(selectedTab = TrainerTab.WORKOUT).openDetail(
            destination = RootDetailDestination.Paywall(context),
            ownerTab = TrainerTab.WORKOUT
        )

        val account = paywall.openAccountFromPaywall(context)

        assertEquals(TrainerTab.WORKOUT, account.selectedTab)
        assertEquals(RootDetailDestination.Account(context), account.detailDestination)
        assertEquals(account, account.returnToPaywallIfCommercialReady(isReady = false))
        assertEquals(paywall, account.returnToPaywallIfCommercialReady(isReady = true))
        assertEquals(paywall, account.closeDetail())
    }

    @Test
    fun `Paywall account recovery stays explicit and never stores replay state`() {
        val app = personalTrainerAppSource()
        val paywall = source("SubscriptionPaywallScreen.kt")

        assertTrue(app.contains("onOpenAccount = {"))
        assertTrue(app.contains("openAccountFromPaywall(paywallDestination.context)"))
        assertTrue(app.contains("returnToPaywallIfCommercialReady"))
        assertTrue(paywall.contains("PaywallOperationState.AccountRequired"))
        assertTrue(paywall.contains("PaywallOperationState.VerificationRequired"))
        assertTrue(paywall.contains("onOpenAccount"))
        assertTrue(paywall.contains("onRetryIdentity"))
        assertFalse(app.contains("pendingPurchase"))
        assertFalse(app.contains("pendingRestore"))
        assertFalse(app.contains("pendingPackage"))
    }

    @Test
    fun `Home membership entry uses authoritative state and the existing overlay path`() {
        val source = personalTrainerAppSource()

        assertTrue(
            source.contains(
                "showBasicPlusEntry = subscriptionPaywallUiState.shouldShowBasicHomeUpgradeEntry()"
            )
        )
        assertTrue(source.contains("ownerTab = TrainerTab.HOME"))
        assertTrue(source.contains("entryPoint = PaywallEntryPoint.MEMBERSHIP"))
    }

    @Test
    fun `Profile keeps one membership control before lower preference content`() {
        val profile = source("ProfileScreen.kt")
        val membershipCard = profile.indexOf("ProfileMembershipCard(")
        val preferences = profile.indexOf("text = \"PREFERENCES\"")

        assertTrue(membershipCard >= 0)
        assertTrue(membershipCard < preferences)
        assertEquals(2, Regex("ProfileMembershipCard\\(").findAll(profile).count())
    }

    @Test
    fun `active workout and tool surfaces expose no local theme truth or controls`() {
        val workout = source("WorkoutDashboardScreen.kt")
        val generator = source("WorkoutGeneratorScreen.kt")
        val tools = source("ToolsScreen.kt")

        listOf(
            workout,
            generator,
            tools,
            source("MyRoutinesScreen.kt"),
            source("WorkoutDaySelectionScreen.kt"),
            source("TrackWorkoutScreen.kt"),
            source("BuildWorkoutScreen.kt")
        ).forEach { activeSurface ->
            assertFalse(activeSurface.contains("var isDarkTheme"))
            assertFalse(activeSurface.contains("isDarkTheme: Boolean"))
            assertFalse(activeSurface.contains("Icons.Default.LightMode"))
            assertFalse(activeSurface.contains("Icons.Default.DarkMode"))
        }
        assertFalse(workout.contains("Switch workout screen to"))
        assertFalse(generator.contains("Toggle Contrast Palette"))
        assertFalse(tools.contains("Toggle Contrast Mode"))
    }

    @Test
    fun `Paywall V2 uses active theme roles instead of dark-only tokens`() {
        val paywall = source("SubscriptionPaywallScreen.kt")

        listOf(
            "FitDesiInk",
            "FitDesiSurfaceDark",
            "FitDesiElevatedDark",
            "FitDesiBorderDark",
            "FitDesiTextDark",
            "FitDesiMutedDark",
            "FitDesiOrangeSoftDark"
        ).forEach { darkOnlyToken ->
            assertFalse(paywall.contains(darkOnlyToken))
        }
        assertTrue(paywall.contains("MaterialTheme.colorScheme"))
        assertTrue(paywall.contains("MaterialTheme.fitDesiColors"))
    }

    @Test
    fun calorieDetailKeepsItsRootAndBottomNavigationBehavior() {
        val homeRoot = RootNavigationState(selectedTab = TrainerTab.HOME)
        val calorieDetail = homeRoot.openDetail(RootDetailDestination.CalorieTracker)

        assertEquals(TrainerTab.HOME, calorieDetail.selectedTab)
        assertTrue(
            shouldShowRootBottomNavigation(
                isTrainingMode = false,
                detailDestination = calorieDetail.detailDestination
            )
        )
        assertFalse(shouldRenderSelectedRootContent(calorieDetail.detailDestination))
        assertEquals(homeRoot, calorieDetail.closeDetail())
    }

    @Test
    fun paywallDetailHidesRootBottomNavigationWithoutChangingSelectedTab() {
        val profileRoot = RootNavigationState(selectedTab = TrainerTab.PROFILE)
        val paywall = RootNavigationState(selectedTab = TrainerTab.PROFILE).openDetail(
            RootDetailDestination.Paywall(PaywallContext(PaywallEntryPoint.MEMBERSHIP))
        )

        assertFalse(
            shouldShowRootBottomNavigation(
                isTrainingMode = false,
                detailDestination = paywall.detailDestination
            )
        )
        assertEquals(TrainerTab.PROFILE, paywall.selectedTab)
        assertTrue(shouldRenderSelectedRootContent(paywall.detailDestination))
        assertEquals(profileRoot, paywall.closeDetail())
    }

    @Test
    fun contextualPaywallRepresentsOnlyApprovedTypedEntryPoints() {
        assertEquals(
            listOf(
                "MEMBERSHIP",
                "AI_WORKOUT_GENERATOR",
                "ROUTINE_LIMIT",
                "MACRO_TARGETS",
                "FULL_HISTORY",
                "ADVANCED_ANALYTICS"
            ),
            PaywallEntryPoint.values().map { it.name }
        )

        val destinations = PaywallEntryPoint.values().map { entryPoint ->
            RootDetailDestination.Paywall(PaywallContext(entryPoint))
        }

        assertEquals(PaywallEntryPoint.values().toList(), destinations.map { it.context.entryPoint })
    }

    @Test
    fun navigationHelpersDistinguishPaywallOverlayFromCalorieReplacement() {
        val paywall = RootDetailDestination.Paywall(
            PaywallContext(PaywallEntryPoint.AI_WORKOUT_GENERATOR)
        )

        assertTrue(shouldRenderSelectedRootContent(null))
        assertTrue(shouldRenderSelectedRootContent(paywall))
        assertFalse(shouldRenderSelectedRootContent(RootDetailDestination.CalorieTracker))
        assertFalse(
            shouldShowRootBottomNavigation(
                isTrainingMode = false,
                detailDestination = paywall
            )
        )
        assertTrue(
            shouldShowRootBottomNavigation(
                isTrainingMode = false,
                detailDestination = RootDetailDestination.CalorieTracker
            )
        )
    }

    @Test
    fun profileCallbackUsesMembershipContextAndOverlayCompositionContract() {
        val source = personalTrainerAppSource()

        assertTrue(source.contains("entryPoint = PaywallEntryPoint.MEMBERSHIP"))
        assertTrue(source.contains("shouldRenderSelectedRootContent"))
        assertTrue(source.contains("PaywallPointerBarrier"))
        assertTrue(source.contains("BackHandler(onBack = closePaywall)"))
        assertTrue(source.contains("onBack = closePaywall"))
    }

    @Test
    fun routineLimitUsesTheTypedWorkoutOverlayAndRetainsTheBuilderRoot() {
        val workoutRoot = RootNavigationState(selectedTab = TrainerTab.WORKOUT)
        val paywall = workoutRoot.openDetail(
            destination = RootDetailDestination.Paywall(
                PaywallContext(PaywallEntryPoint.ROUTINE_LIMIT)
            ),
            ownerTab = TrainerTab.WORKOUT
        )

        assertEquals(TrainerTab.WORKOUT, paywall.selectedTab)
        assertTrue(shouldRenderSelectedRootContent(paywall.detailDestination))
        assertEquals(workoutRoot, paywall.closeDetail())
    }

    @Test
    fun `authored builders route only limit results to the routine limit paywall`() {
        val app = personalTrainerAppSource()
        val dashboard = source("WorkoutDashboardScreen.kt")
        val buildRoutine = source("BuildRoutineScreen.kt")
        val buildWorkout = source("BuildWorkoutScreen.kt")

        assertTrue(app.contains("onRoutineLimitReached = {"))
        assertTrue(app.contains("entryPoint = PaywallEntryPoint.ROUTINE_LIMIT"))
        assertTrue(dashboard.contains("onRoutineLimitReached: () -> Unit = {}"))
        assertEquals(2, Regex("onRoutineLimitReached = onRoutineLimitReached").findAll(dashboard).count())
        assertFalse(dashboard.contains("PaywallEntryPoint"))
        assertTrue(buildRoutine.contains("SavedRoutineResult.LIMIT_REACHED -> AuthoredRoutineSaveAction.OPEN_ROUTINE_LIMIT_PAYWALL"))
        assertTrue(buildWorkout.contains("viewModel.saveCustomWorkoutPlan(json) { result ->"))
        assertFalse(app.contains("pendingRoutineSave"))
        assertFalse(dashboard.contains("pendingRoutineSave"))
    }

    @Test
    fun `routine producers retain their explicit origins and Generator has only generic limit failure`() {
        val trainer = viewModelSource("TrainerViewModel.kt")
        val generator = source("WorkoutGeneratorScreen.kt")

        assertTrue(trainer.contains("saveRoutineAndSetActive(routine, SavedRoutineOrigin.BUILD_ROUTINE)"))
        assertTrue(trainer.contains("saveRoutineAndSetActive(routine, SavedRoutineOrigin.CUSTOM_WORKOUT)"))
        assertTrue(trainer.contains("saveRoutineAndSetActive(routine, SavedRoutineOrigin.AI_WORKOUT_GENERATOR)"))
        assertTrue(generator.contains("viewModel.saveGeneratedWorkoutPlan(json) { result ->"))
        assertTrue(generator.contains("SavedRoutineResult.LIMIT_REACHED,"))
        assertTrue(generator.contains("SavedRoutineResult.ERROR ->"))
        assertTrue(generator.contains("Workout plan could not be saved. Please try again."))
        assertFalse(generator.contains("PaywallEntryPoint.ROUTINE_LIMIT"))
        assertTrue(generator.contains("SubscriptionCapability.AI_WORKOUT_GENERATION"))
    }

    @Test
    fun `Generator and macro paid actions use typed owner routing with retained roots`() {
        val app = personalTrainerAppSource()
        val dashboard = source("WorkoutDashboardScreen.kt")
        val generator = source("WorkoutGeneratorScreen.kt")
        val tools = source("ToolsScreen.kt")

        assertTrue(app.contains("entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR"))
        assertTrue(app.contains("entryPoint = PaywallEntryPoint.MACRO_TARGETS"))
        assertTrue(dashboard.contains("onAiWorkoutGenerationRequiresPlus = onAiWorkoutGenerationRequiresPlus"))
        assertTrue(generator.contains("onAiWorkoutGenerationRequiresPlus: () -> Unit"))
        assertTrue(tools.contains("onMacroTargetsRequirePlus: () -> Unit = {}"))
        assertTrue(tools.contains("NutritionTargetSaveResult.REQUIRES_PLUS -> onMacroTargetsRequirePlus()"))
        assertTrue(tools.contains("NutritionTargetSaveResult.SAVED -> selectedToolKey = null"))
        assertFalse(generator.contains("PaywallEntryPoint.ROUTINE_LIMIT"))
        assertFalse(app.contains("pendingGeneratorAction"))
        assertFalse(app.contains("pendingMacroTargetSave"))

        listOf(
            TrainerTab.WORKOUT to PaywallEntryPoint.AI_WORKOUT_GENERATOR,
            TrainerTab.TOOLS to PaywallEntryPoint.MACRO_TARGETS
        ).forEach { (tab, entryPoint) ->
            val root = RootNavigationState(selectedTab = tab)
            val paywall = root.openDetail(
                RootDetailDestination.Paywall(PaywallContext(entryPoint)),
                ownerTab = tab
            )
            assertTrue(shouldRenderSelectedRootContent(paywall.detailDestination))
            assertEquals(root, paywall.closeDetail())
        }
    }

    @Test
    fun `Progress full history action uses the typed Workout overlay without replay state`() {
        val app = personalTrainerAppSource()
        val dashboard = source("WorkoutDashboardScreen.kt")
        val progress = source("ProgressHubScreen.kt")
        val navigator = source("ProgressDateNavigator.kt")

        assertTrue(app.contains("onFullHistoryRequiresPlus = {"))
        assertTrue(app.contains("entryPoint = PaywallEntryPoint.FULL_HISTORY"))
        assertTrue(dashboard.contains("onFullHistoryRequiresPlus: () -> Unit = {}"))
        assertTrue(dashboard.contains("onFullHistoryRequired = onFullHistoryRequiresPlus"))
        assertTrue(progress.contains("onFullHistoryRequired = onFullHistoryRequired"))
        assertTrue(navigator.contains("handlePresetResult(onPreset(preset, null))"))
        assertTrue(Regex("showOptions = false\\s+onFullHistoryRequired\\(\\)").containsMatchIn(navigator))
        assertTrue(Regex("showCustomPicker = false\\s+onFullHistoryRequired\\(\\)").containsMatchIn(navigator))
        assertFalse(app.contains("pendingFullHistory"))
        assertFalse(dashboard.contains("pendingFullHistory"))
    }

    @Test
    fun `V2 advanced Training uses the typed Workout overlay only after a deliberate request`() {
        val app = personalTrainerAppSource()
        val dashboard = source("WorkoutDashboardScreen.kt")
        val progress = source("ProgressHubScreen.kt")
        val progressSections = source("ProgressV2Sections.kt")

        assertTrue(app.contains("onAdvancedAnalyticsRequiresPlus = {"))
        assertTrue(app.contains("entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS"))
        assertTrue(dashboard.contains("onAdvancedAnalyticsRequiresPlus: () -> Unit = {}"))
        assertTrue(dashboard.contains("onAdvancedAnalyticsRequired = onAdvancedAnalyticsRequiresPlus"))
        assertTrue(progress.contains("onAdvancedAnalyticsRequired = onAdvancedAnalyticsRequired"))
        assertTrue(progressSections.contains("onClick = onAdvancedAnalyticsRequired"))
        assertFalse(progressSections.contains("adherence", ignoreCase = true))
        assertFalse(progressSections.contains("targetWorkoutDays"))
        assertFalse(app.contains("pendingAdvancedAnalytics"))
        assertFalse(dashboard.contains("pendingAdvancedAnalytics"))
    }

    @Test
    fun `Workout history and Progress actions route to the intended Progress subsections`() {
        val dashboard = source("WorkoutDashboardScreen.kt")

        assertTrue(dashboard.contains("progressInitialSectionName = ProgressHubSection.JOURNAL.name"))
        assertTrue(dashboard.contains("progressInitialSectionName = ProgressHubSection.TRAINING.name"))
        assertTrue(dashboard.contains("activeScreen = \"progress\""))
        assertTrue(dashboard.contains("initialSection = progressInitialSection"))
        assertFalse(dashboard.contains("WorkoutAnalyticsScreen("))
        assertFalse(dashboard.contains("activeScreen = \"analytics\""))
        assertFalse(dashboard.contains("activeScreen == \"analytics\""))
    }

    @Test
    fun `Progress refreshes its local date on entry resume and date context broadcasts`() {
        val progress = source("ProgressHubScreen.kt")
        val dashboard = source("WorkoutDashboardScreen.kt")
        val viewModel = viewModelSource("ProgressViewModel.kt")

        assertTrue(dashboard.contains("ProgressDateRefreshEffect("))
        assertTrue(dashboard.contains("onRefresh = { progressViewModel?.refreshCurrentDate() }"))
        assertFalse(progress.contains("ProgressDateRefreshEffect(viewModel::refreshCurrentDate)"))
        assertEquals(1, Regex("ProgressDateRefreshEffect\\(").findAll(dashboard).count())
        assertEquals(1, Regex("ProgressDateRefreshEffect\\(").findAll(progress).count())
        assertTrue(progress.contains("currentOnRefresh()"))
        assertTrue(progress.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(progress.contains("Intent.ACTION_DATE_CHANGED"))
        assertTrue(progress.contains("Intent.ACTION_TIME_CHANGED"))
        assertTrue(progress.contains("Intent.ACTION_TIMEZONE_CHANGED"))
        assertTrue(viewModel.contains("timeZoneProvider = TimeZone::getDefault"))
        assertTrue(viewModel.contains("fun refreshCurrentDate()"))
    }

    @Test
    fun `dashboard and management use Progress history access boundaries`() {
        val dashboard = source("WorkoutDashboardScreen.kt")
        val progress = source("ProgressHubScreen.kt")
        val viewModel = viewModelSource("ProgressViewModel.kt")

        assertTrue(dashboard.contains("latestWorkout = progressState?.latestRecentWorkout"))
        assertFalse(dashboard.contains("latestWorkout = workoutLogs.maxByOrNull { it.timestamp }"))
        assertTrue(dashboard.contains("canShowWorkoutManagementDetail"))
        assertTrue(progress.contains("ProgressMomentumHeader(state)"))
        assertTrue(viewModel.contains("exactWorkoutStreak"))
        assertFalse(progress.contains("ProgressMetricCard(\"Current streak\""))
    }

    @Test
    fun `workload classification is background bounded and blank identities are isolated`() {
        val trainer = viewModelSource("TrainerViewModel.kt")
        val workloadStart = trainer.indexOf("val workload = runCatching")
        val backgroundStart = trainer.indexOf("withContext(Dispatchers.Default)", workloadStart)
        val catalogueLoad = trainer.indexOf("catalogue.load()", backgroundStart)
        val calculation = trainer.indexOf("calculateMuscleWorkload(completedExerciseSets, canonicalExercises)", catalogueLoad)

        assertTrue(workloadStart >= 0)
        assertTrue(backgroundStart in workloadStart until catalogueLoad)
        assertTrue(catalogueLoad in backgroundStart until calculation)
        assertTrue(trainer.contains("it.exerciseId?.takeIf(String::isNotBlank)"))
        assertTrue(trainer.contains("workload?.credits.orEmpty()"))
    }

    @Test
    fun `macro Finish keeps only one target save in flight`() {
        val tools = source("ToolsScreen.kt")
        val guardDeclaration = "var isSavingMacroTargets by remember { mutableStateOf(false) }"
        val duplicateReturn = "if (isSavingMacroTargets) return@onFinish"
        val markInFlight = "isSavingMacroTargets = true"
        val clearInFlight = "isSavingMacroTargets = false"
        val saveCall = "viewModel.saveNutritionTargets("

        assertTrue(tools.contains(guardDeclaration))
        assertTrue(tools.contains(duplicateReturn))
        assertEquals(1, Regex(Regex.escape(saveCall)).findAll(tools).count())
        assertTrue(tools.indexOf(duplicateReturn) < tools.indexOf(markInFlight))
        assertTrue(tools.indexOf(markInFlight) < tools.indexOf(saveCall))
        assertTrue(tools.indexOf(saveCall) < tools.indexOf(clearInFlight))
        assertTrue(
            tools.indexOf(clearInFlight) <
                tools.indexOf("NutritionTargetSaveResult.REQUIRES_PLUS -> onMacroTargetsRequirePlus()")
        )
    }

    @Test
    fun workoutNavigationRequiresExplicitDaySelectionForPlansAndKeepsManualTrackingSeparate() {
        val dashboard = source("WorkoutDashboardScreen.kt")
        val routines = source("MyRoutinesScreen.kt")
        val tracker = source("TrackWorkoutScreen.kt")

        assertTrue(dashboard.contains("WorkoutDaySelectionScreen("))
        assertTrue(dashboard.contains("initialSelectedDayIndex = routineDayIndex"))
        assertTrue(dashboard.contains("sourceRoutineId = selectionRoutineSourceId"))
        assertTrue(dashboard.contains("Text(\"Choose workout day\")"))
        assertTrue(routines.contains("Text(\"Choose workout day\")"))
        assertFalse(routines.contains("Start first session"))
        assertFalse(tracker.contains("routine.days.firstOrNull()"))
        assertTrue(tracker.contains("selectedDayIndex = workoutContext.dayIndex"))
        assertTrue(dashboard.contains("routineDayIndex = null"))
    }

    private fun personalTrainerAppSource(): String {
        val relativePath = "src/main/java/com/example/ui/PersonalTrainerApp.kt"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("mobile/app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate PersonalTrainerApp.kt for the focused navigation contract test")
    }

    private fun source(fileName: String): String {
        val relativePath = "src/main/java/com/example/ui/$fileName"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("mobile/app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $fileName for the focused navigation contract test")
    }

    private fun viewModelSource(fileName: String): String {
        val relativePath = "src/main/java/com/example/viewmodel/$fileName"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("mobile/app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $fileName for the focused ViewModel contract test")
    }
}
