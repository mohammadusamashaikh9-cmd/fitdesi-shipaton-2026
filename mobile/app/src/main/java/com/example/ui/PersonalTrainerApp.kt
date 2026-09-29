package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.AiRepository
import com.example.subscription.RevenueCatIdentityState
import com.example.subscription.isCommercialIdentityReady
import com.example.ui.theme.InterFontFamily
import com.example.viewmodel.AccountViewModel
import com.example.viewmodel.SubscriptionPaywallViewModel
import com.example.viewmodel.TrainerViewModel
import com.example.viewmodel.ProgressViewModel
import com.example.viewmodel.profileAccountValue

enum class TrainerTab {
    HOME, WORKOUT, AI_COACH, TOOLS, PROFILE
}

internal sealed interface RootDetailDestination {
    data object CalorieTracker : RootDetailDestination
    data class Account(
        val returnPaywallContext: PaywallContext? = null
    ) : RootDetailDestination

    data class Paywall(
        val context: PaywallContext
    ) : RootDetailDestination
}

internal data class RootNavigationState(
    val selectedTab: TrainerTab = TrainerTab.HOME,
    val detailDestination: RootDetailDestination? = null
) {
    fun navigateToRoot(destination: TrainerTab): RootNavigationState = copy(
        selectedTab = destination,
        detailDestination = null
    )

    fun openDetail(
        destination: RootDetailDestination,
        ownerTab: TrainerTab = selectedTab
    ): RootNavigationState = copy(
        selectedTab = ownerTab,
        detailDestination = destination
    )

    fun closeDetail(): RootNavigationState = when (val current = detailDestination) {
        is RootDetailDestination.Account -> current.returnPaywallContext?.let { context ->
            copy(detailDestination = RootDetailDestination.Paywall(context))
        } ?: copy(detailDestination = null)
        else -> copy(detailDestination = null)
    }

    fun openAccountFromPaywall(context: PaywallContext): RootNavigationState = copy(
        detailDestination = RootDetailDestination.Account(returnPaywallContext = context)
    )

    fun returnToPaywallIfCommercialReady(isReady: Boolean): RootNavigationState {
        if (!isReady) return this
        val account = detailDestination as? RootDetailDestination.Account ?: return this
        val context = account.returnPaywallContext ?: return this
        return copy(detailDestination = RootDetailDestination.Paywall(context))
    }
}

internal fun shouldShowRootBottomNavigation(
    isTrainingMode: Boolean,
    detailDestination: RootDetailDestination? = null
): Boolean = !isTrainingMode &&
    detailDestination !is RootDetailDestination.Paywall &&
    detailDestination !is RootDetailDestination.Account

internal fun shouldRenderSelectedRootContent(
    detailDestination: RootDetailDestination?
): Boolean = detailDestination !is RootDetailDestination.CalorieTracker &&
    detailDestination !is RootDetailDestination.Account

internal fun shouldResetWorkoutRootOnSelection(
    currentState: RootNavigationState,
    destination: TrainerTab
): Boolean = destination == TrainerTab.WORKOUT &&
    currentState.selectedTab == destination &&
    currentState.detailDestination == null

@Composable
fun PersonalTrainerApp(
    viewModel: TrainerViewModel,
    aiRepository: AiRepository = AiRepository(),
    accountViewModel: AccountViewModel,
    subscriptionPaywallViewModel: SubscriptionPaywallViewModel,
    onPurchaseSubscription: (String) -> Unit,
    onRequestBoost: () -> Unit,
    revenueCatIdentityState: RevenueCatIdentityState,
    onRetryRevenueCatIdentity: () -> Unit,
    onShowBoostPrivacyOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var navigationState by remember { mutableStateOf(RootNavigationState()) }
    var workoutRootRequest by remember { mutableIntStateOf(0) }
    var requestedToolsDestination by remember { mutableStateOf<String?>(null) }
    var requestedSavedDietPlanId by remember { mutableStateOf<String?>(null) }
    var isWorkoutTrainingMode by remember { mutableStateOf(false) }
    val rootTabStateHolder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val progressViewModel: ProgressViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    val navigateToRoot: (TrainerTab) -> Unit = { destination ->
        val shouldResetWorkoutRoot = shouldResetWorkoutRootOnSelection(navigationState, destination)

        navigationState = navigationState.navigateToRoot(destination)
        requestedToolsDestination = null
        requestedSavedDietPlanId = null
        isWorkoutTrainingMode = false
        if (shouldResetWorkoutRoot) {
            workoutRootRequest += 1
        }
    }

    BackHandler(
        enabled = navigationState.detailDestination == RootDetailDestination.CalorieTracker ||
            navigationState.detailDestination is RootDetailDestination.Account
    ) {
        navigationState = navigationState.closeDetail()
    }

    val calorieLogs by viewModel.calorieLogs.collectAsStateWithLifecycle()
    val workoutLogs by viewModel.workoutLogs.collectAsStateWithLifecycle()
    val dailyGoal by viewModel.dailyCalorieGoal.collectAsStateWithLifecycle()
    val todayConsumed by viewModel.todayCaloriesConsumed.collectAsStateWithLifecycle()
    val todayProtein by viewModel.todayProteinGrams.collectAsStateWithLifecycle()
    val todayCarbs by viewModel.todayCarbsGrams.collectAsStateWithLifecycle()
    val todayFat by viewModel.todayFatGrams.collectAsStateWithLifecycle()
    val proteinGoal by viewModel.proteinGoal.collectAsStateWithLifecycle()
    val carbsGoal by viewModel.carbsGoal.collectAsStateWithLifecycle()
    val fatGoal by viewModel.fatGoal.collectAsStateWithLifecycle()
    val waterIntake by viewModel.waterIntake.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val canPresentMacroTargets by viewModel.canPresentMacroTargets.collectAsStateWithLifecycle(
        initialValue = false
    )
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()
    val subscriptionPaywallUiState by subscriptionPaywallViewModel.uiState.collectAsStateWithLifecycle()
    val currentTab = navigationState.selectedTab

    LaunchedEffect(accountUiState.session, revenueCatIdentityState) {
        val returned = navigationState.returnToPaywallIfCommercialReady(
            isReady = isCommercialIdentityReady(
                session = accountUiState.session,
                identityState = revenueCatIdentityState
            )
        )
        if (returned != navigationState) {
            navigationState = returned
        }
    }

    val isHome = currentTab == TrainerTab.HOME
    val isWorkout = currentTab == TrainerTab.WORKOUT
    val isCoach = currentTab == TrainerTab.AI_COACH
    val isTools = currentTab == TrainerTab.TOOLS
    val isProfile = currentTab == TrainerTab.PROFILE
    val homeScale by animateFloatAsState(if (isHome) 1.08f else 1f, label = "home_scale")
    val workoutScale by animateFloatAsState(if (isWorkout) 1.08f else 1f, label = "workout_scale")
    val coachScale by animateFloatAsState(if (isCoach) 1.08f else 1f, label = "coach_scale")
    val toolsScale by animateFloatAsState(if (isTools) 1.08f else 1f, label = "tools_scale")
    val profileScale by animateFloatAsState(if (isProfile) 1.08f else 1f, label = "profile_scale")

    Scaffold(
        bottomBar = {
            if (
                shouldShowRootBottomNavigation(
                    isTrainingMode = isWorkoutTrainingMode,
                    detailDestination = navigationState.detailDestination
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp
                ) {
                    Column {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                        NavigationBar(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0.dp),
                            modifier = Modifier.fillMaxWidth().height(76.dp).navigationBarsPadding()
                        ) {
                            FitDesiRootTab("Home", isHome, Icons.Filled.Home, Icons.Outlined.Home, homeScale, "tab_home") {
                                navigateToRoot(TrainerTab.HOME)
                            }
                            FitDesiRootTab("Workout", isWorkout, Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter, workoutScale, "tab_workout") {
                                navigateToRoot(TrainerTab.WORKOUT)
                            }
                            FitDesiRootTab("AI Coach", isCoach, Icons.Filled.Psychology, Icons.Outlined.Psychology, coachScale, "tab_ai_coach") {
                                navigateToRoot(TrainerTab.AI_COACH)
                            }
                            FitDesiRootTab("Tools", isTools, Icons.Filled.GridView, Icons.Outlined.GridView, toolsScale, "tab_tools") {
                                navigateToRoot(TrainerTab.TOOLS)
                            }
                            FitDesiRootTab("Profile", isProfile, Icons.Filled.Person, Icons.Outlined.Person, profileScale, "tab_profile") {
                                navigateToRoot(TrainerTab.PROFILE)
                            }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            ) {
                when (navigationState.detailDestination) {
                    RootDetailDestination.CalorieTracker -> CalorieTrackerScreen(
                        todayConsumed = todayConsumed,
                        dailyGoal = dailyGoal,
                        calorieLogs = calorieLogs,
                        proteinGoal = proteinGoal,
                        carbsGoal = carbsGoal,
                        fatGoal = fatGoal,
                        showMacroTargetProgress = canPresentMacroTargets,
                        onAddCalorie = { amount, mealType, desc, protein, carbs, fat, servings ->
                            viewModel.addCalorieLog(amount, mealType, desc, protein, carbs, fat, servings)
                            Toast.makeText(context, "Meal logged successfully!", Toast.LENGTH_SHORT).show()
                        },
                        onDeleteCalorie = viewModel::deleteCalorieLog,
                        onUpdateGoal = { goal ->
                            viewModel.setDailyCalorieGoal(goal)
                            Toast.makeText(context, "Daily goal updated!", Toast.LENGTH_SHORT).show()
                        },
                        onBack = { navigationState = navigationState.closeDetail() }
                    )
                    is RootDetailDestination.Account -> AccountScreen(
                        uiState = accountUiState,
                        onBack = { navigationState = navigationState.closeDetail() },
                        onSignIn = accountViewModel::signIn,
                        onCreateAccount = accountViewModel::createAccount,
                        onSendPasswordReset = accountViewModel::sendPasswordReset,
                        onResendVerification = accountViewModel::resendVerification,
                        onRefreshVerification = accountViewModel::refreshCurrentUser,
                        onSignOut = accountViewModel::signOut,
                        onDeleteAccount = accountViewModel::deleteAccount,
                        onClearTransientState = accountViewModel::clearTransientState
                    )
                    else -> {
                        rootTabStateHolder.SaveableStateProvider(currentTab.name) {
                            when (currentTab) {
                        TrainerTab.HOME -> HomeScreen(
                            profileName = userProfile.name,
                            todayConsumed = todayConsumed,
                            dailyGoal = dailyGoal,
                            todayProtein = todayProtein,
                            todayCarbs = todayCarbs,
                            todayFat = todayFat,
                            proteinGoal = proteinGoal,
                            carbsGoal = carbsGoal,
                            fatGoal = fatGoal,
                            showMacroTargetProgress = canPresentMacroTargets,
                            workoutLogs = workoutLogs,
                            activePlanJson = userProfile.generatedWorkoutPlan,
                            waterGlasses = waterIntake,
                            onWaterIntakeChange = viewModel::saveWaterIntake,
                            onNavigateToCalories = {
                                navigationState = navigationState.openDetail(
                                    RootDetailDestination.CalorieTracker
                                )
                            },
                            onSetMacroTargets = {
                                navigateToRoot(TrainerTab.TOOLS)
                                requestedToolsDestination = "macro"
                            },
                            onNavigateToExercises = { navigateToRoot(TrainerTab.WORKOUT) },
                            onOpenAiCoach = { navigateToRoot(TrainerTab.AI_COACH) },
                            showBasicPlusEntry = subscriptionPaywallUiState.shouldShowBasicHomeUpgradeEntry(),
                            onExplorePlus = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.MEMBERSHIP
                                        )
                                    ),
                                    ownerTab = TrainerTab.HOME
                                )
                            }
                        )
                        TrainerTab.WORKOUT -> WorkoutDashboardScreen(
                            viewModel = viewModel,
                            progressViewModel = progressViewModel,
                            rootNavigationRequest = workoutRootRequest,
                            onTrainingModeChanged = { isWorkoutTrainingMode = it },
                            onRoutineLimitReached = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.ROUTINE_LIMIT
                                        )
                                    ),
                                    ownerTab = TrainerTab.WORKOUT
                                )
                            },
                            onAiWorkoutGenerationRequiresPlus = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR
                                        )
                                    ),
                                    ownerTab = TrainerTab.WORKOUT
                                )
                            },
                            onFullHistoryRequiresPlus = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.FULL_HISTORY
                                        )
                                    ),
                                    ownerTab = TrainerTab.WORKOUT
                                )
                            },
                            onAdvancedAnalyticsRequiresPlus = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS
                                        )
                                    ),
                                    ownerTab = TrainerTab.WORKOUT
                                )
                            }
                        )
                        TrainerTab.AI_COACH -> AiCoachScreen(
                            onBack = { navigateToRoot(TrainerTab.HOME) },
                            viewModel = viewModel,
                            repository = aiRepository,
                            onViewSavedDietPlan = { planId ->
                                navigateToRoot(TrainerTab.TOOLS)
                                requestedToolsDestination = "saved_diet_plans"
                                requestedSavedDietPlanId = planId
                            }
                        )
                        TrainerTab.TOOLS -> ToolsScreen(
                            viewModel = viewModel,
                            onNavigateToCalories = {
                                navigationState = navigationState.openDetail(
                                    RootDetailDestination.CalorieTracker
                                )
                            },
                            initialToolKey = requestedToolsDestination,
                            initialSavedDietPlanId = requestedSavedDietPlanId,
                            onInitialToolHandled = {
                                requestedToolsDestination = null
                                requestedSavedDietPlanId = null
                            },
                            onMacroTargetsRequirePlus = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.MACRO_TARGETS
                                        )
                                    ),
                                    ownerTab = TrainerTab.TOOLS
                                )
                            }
                        )
                        TrainerTab.PROFILE -> ProfileScreen(
                            viewModel = viewModel,
                            membershipValue = subscriptionPaywallUiState.profileMembershipValue(),
                            onPlanAndMembershipClick = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Paywall(
                                        context = PaywallContext(
                                            entryPoint = PaywallEntryPoint.MEMBERSHIP
                                        )
                                    ),
                                    ownerTab = TrainerTab.PROFILE
                                )
                            },
                            accountValue = accountUiState.session.profileAccountValue(),
                            onAccountClick = {
                                navigationState = navigationState.openDetail(
                                    destination = RootDetailDestination.Account(),
                                    ownerTab = TrainerTab.PROFILE
                                )
                            }
                        )
                            }
                        }
                    }
                }
            }

            val paywallDestination =
                navigationState.detailDestination as? RootDetailDestination.Paywall
            if (paywallDestination != null) {
                val closePaywall = { navigationState = navigationState.closeDetail() }

                BackHandler(onBack = closePaywall)
                Box(modifier = Modifier.fillMaxSize()) {
                    PaywallPointerBarrier()
                    SubscriptionPaywallSession(
                        onOpened = subscriptionPaywallViewModel::onPaywallOpened,
                        onClosed = subscriptionPaywallViewModel::onPaywallClosed
                    ) {
                        SubscriptionPaywallScreen(
                            context = paywallDestination.context,
                            uiState = subscriptionPaywallUiState,
                            onBack = closePaywall,
                            onSelectPackage = subscriptionPaywallViewModel::selectPackage,
                            onPurchase = onPurchaseSubscription,
                            onRestore = subscriptionPaywallViewModel::restorePurchases,
                            onRetryOffering = subscriptionPaywallViewModel::retryOffering,
                            onOpenAccount = {
                                navigationState = navigationState
                                    .openAccountFromPaywall(paywallDestination.context)
                            },
                            onRetryIdentity = onRetryRevenueCatIdentity,
                            onRequestBoost = onRequestBoost,
                            onShowBoostPrivacyOptions = onShowBoostPrivacyOptions
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaywallPointerBarrier() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { change -> change.consume() }
                    }
                }
            }
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FitDesiRootTab(
    label: String,
    selected: Boolean,
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    scale: Float,
    tag: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier.size(width = 34.dp, height = 29.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(width = 18.dp, height = 2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
                Icon(
                    imageVector = if (selected) selectedIcon else unselectedIcon,
                    contentDescription = "$label tab",
                    modifier = Modifier.size(24.dp).scale(scale)
                )
            }
        },
        label = {
            androidx.compose.material3.Text(
                text = label,
                fontFamily = InterFontFamily,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = androidx.compose.ui.graphics.Color.Transparent
        ),
        modifier = Modifier.testTag(tag)
    )
}
