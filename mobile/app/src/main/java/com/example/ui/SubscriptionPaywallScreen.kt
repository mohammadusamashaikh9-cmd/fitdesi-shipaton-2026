package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.subscription.SubscriptionBillingPeriod
import com.example.subscription.SubscriptionTier
import com.example.boost.BoostAccessState
import com.example.boost.BoostAdState
import com.example.boost.FITDESI_BOOST_DURATION_MINUTES
import com.example.ui.theme.FitDesiOrange
import com.example.ui.theme.fitDesiColors
import com.example.viewmodel.PaywallCustomerInfoStatus
import com.example.viewmodel.PaywallOfferingState
import com.example.viewmodel.PaywallOperationFailure
import com.example.viewmodel.PaywallOperationState
import com.example.viewmodel.PaywallPurchaseOption
import com.example.viewmodel.PaywallUnavailableReason
import com.example.viewmodel.SubscriptionPaywallUiState

internal fun SubscriptionPaywallUiState.profileMembershipValue(): String = when {
    hasAuthoritativeCustomerInfo -> currentTier.displayName()
    customerInfoStatus == PaywallCustomerInfoStatus.LOADING -> "Checking membership"
    else -> SubscriptionTier.BASIC.displayName()
}

internal fun SubscriptionPaywallUiState.shouldShowBasicHomeUpgradeEntry(): Boolean =
    hasAuthoritativeCustomerInfo &&
        customerInfoStatus == PaywallCustomerInfoStatus.READY &&
        currentTier == SubscriptionTier.BASIC

@Composable
internal fun SubscriptionPaywallSession(
    onOpened: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentOnOpened by rememberUpdatedState(onOpened)
    val currentOnClosed by rememberUpdatedState(onClosed)

    DisposableEffect(Unit) {
        currentOnOpened()
        onDispose { currentOnClosed() }
    }

    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionPaywallScreen(
    context: PaywallContext,
    uiState: SubscriptionPaywallUiState,
    onBack: () -> Unit,
    onSelectPackage: (String) -> Unit,
    onPurchase: (String) -> Unit,
    onRestore: () -> Unit,
    onRetryOffering: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onRetryIdentity: () -> Unit = {},
    onRequestBoost: () -> Unit = {},
    onShowBoostPrivacyOptions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isBusy = uiState.operation is PaywallOperationState.Purchasing ||
        uiState.operation is PaywallOperationState.Restoring
    val paidTier = uiState.confirmedPaidTier()
    val isPaidMember = paidTier != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FITDESI PLUS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = FitDesiOrange
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("paywall_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close paywall",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            PaywallPurchaseFooter(
                uiState = uiState,
                isPaidMember = isPaidMember,
                isBusy = isBusy,
                onBack = onBack,
                onPurchase = onPurchase
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .testTag("subscription_paywall")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            PaywallHero(context = context, uiState = uiState, paidTier = paidTier)
            PaywallOperationFeedback(
                operation = uiState.operation,
                onOpenAccount = onOpenAccount,
                onRetryIdentity = onRetryIdentity
            )
            PrimaryBenefitSection(context.entryPoint)
            if (!isPaidMember) {
                PlusPlanSection(
                    offering = uiState.offering,
                    selectedPackageIdentifier = uiState.selectedPackageIdentifier,
                    enabled = !isBusy,
                    onSelectPackage = onSelectPackage,
                    onRetryOffering = onRetryOffering
                )
            }
            BasicPlusComparison()
            ContextualBoostSection(
                context = context,
                uiState = uiState,
                isPaidMember = isPaidMember,
                onBack = onBack,
                onRequestBoost = onRequestBoost,
                onShowPrivacyOptions = onShowBoostPrivacyOptions
            )
            ProTeaser(isActive = paidTier == SubscriptionTier.PRO)
            RestorePurchasesSection(
                enabled = !isBusy,
                operation = uiState.operation,
                onRestore = onRestore
            )
            Text(
                text = "Basic remains useful for local workout and food tracking, even when subscriptions are unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitDesiColors.mutedContent,
                modifier = Modifier.testTag("paywall_basic_fallback")
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ContextualBoostSection(
    context: PaywallContext,
    uiState: SubscriptionPaywallUiState,
    isPaidMember: Boolean,
    onBack: () -> Unit,
    onRequestBoost: () -> Unit,
    onShowPrivacyOptions: () -> Unit
) {
    val capability = context.boostCapability() ?: return
    val authoritativeBasic = uiState.hasAuthoritativeCustomerInfo &&
        uiState.customerInfoStatus == PaywallCustomerInfoStatus.READY &&
        uiState.currentTier == SubscriptionTier.BASIC
    val activeBoost = uiState.boostAccess as? BoostAccessState.Active
    if (
        isPaidMember ||
        !authoritativeBasic ||
        (activeBoost == null && uiState.boostAdState == BoostAdState.Disabled)
    ) {
        return
    }
    val featureName = when (capability) {
        com.example.subscription.SubscriptionCapability.AI_WORKOUT_GENERATION ->
            "Workout Planner"
        com.example.subscription.SubscriptionCapability.ADVANCED_ANALYTICS ->
            "Advanced Analytics"
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("paywall_boost_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(1.dp, FitDesiOrange.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("FITDESI BOOST", color = FitDesiOrange, fontWeight = FontWeight.Bold)
            Text("Temporary $featureName access", style = MaterialTheme.typography.titleMedium)
            Text(
                if (activeBoost != null) {
                    "Your temporary Boost grants only $featureName access and does not create Plus ownership. " +
                        "With an active FitDesi Plus membership, Workout Planner and Advanced Analytics " +
                        "stay available, alongside full history, macro targets, and unlimited authored routines."
                } else {
                    "Boost is optional and temporary. If you choose, watch one rewarded ad. " +
                        "RevenueCat must verify it before $featureName is available for " +
                        "$FITDESI_BOOST_DURATION_MINUTES minutes. Boost grants only this contextual access " +
                        "and does not create Plus ownership. With an active FitDesi Plus membership, " +
                        "Workout Planner and Advanced Analytics stay available, alongside full history, " +
                        "macro targets, and unlimited authored routines."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.fitDesiColors.mutedContent,
                modifier = Modifier.testTag("paywall_boost_copy")
            )
            if (activeBoost != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("paywall_boost_return")
                ) {
                    Text("Return and try again")
                }
            } else {
                val buttonState = boostButtonState(uiState.boostAdState)
                OutlinedButton(
                    onClick = onRequestBoost,
                    enabled = buttonState.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("paywall_boost_action")
                ) {
                    if (buttonState.showProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = FitDesiOrange
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(buttonState.label)
                }
                if (uiState.boostPrivacyOptionsRequired) {
                    TextButton(
                        onClick = onShowPrivacyOptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("paywall_boost_privacy_options")
                    ) {
                        Text("Privacy choices")
                    }
                }
            }
        }
    }
}

private data class BoostButtonState(
    val label: String,
    val enabled: Boolean,
    val showProgress: Boolean = false
)

private fun boostButtonState(state: BoostAdState): BoostButtonState = when (state) {
    BoostAdState.NeedsPreparation -> BoostButtonState("Check Boost availability", true)
    BoostAdState.GatheringConsent -> BoostButtonState("Checking consent…", false, true)
    BoostAdState.Loading -> BoostButtonState("Loading rewarded ad…", false, true)
    BoostAdState.Ready -> BoostButtonState(
        "Watch rewarded ad for $FITDESI_BOOST_DURATION_MINUTES minutes",
        true
    )
    BoostAdState.Showing -> BoostButtonState("Rewarded ad in progress…", false, true)
    BoostAdState.Verifying -> BoostButtonState("Verifying Boost…", false, true)
    is BoostAdState.Unavailable -> BoostButtonState("Try Boost again", true)
    BoostAdState.Disabled -> BoostButtonState("Boost unavailable", false)
}

private data class PaywallHeroContent(
    val headline: String,
    val supportingCopy: String
)

private fun PaywallEntryPoint.heroContent(): PaywallHeroContent = when (this) {
    PaywallEntryPoint.MEMBERSHIP -> PaywallHeroContent(
        headline = "Train smarter with FitDesi Plus",
        supportingCopy = "Build personalized workouts, remove routine limits, and understand your progress beyond the basics."
    )
    PaywallEntryPoint.AI_WORKOUT_GENERATOR -> PaywallHeroContent(
        headline = "Build your next workout with FitDesi Plus",
        supportingCopy = "Generate focused, personalized workouts around your goals and training needs."
    )
    PaywallEntryPoint.ROUTINE_LIMIT -> PaywallHeroContent(
        headline = "Keep building without routine limits",
        supportingCopy = "Save unlimited authored routines as your training evolves."
    )
    PaywallEntryPoint.MACRO_TARGETS -> PaywallHeroContent(
        headline = "Turn your calorie target into a complete nutrition plan",
        supportingCopy = "Set macro targets and follow protein, carbohydrate, and fat progress."
    )
    PaywallEntryPoint.FULL_HISTORY -> PaywallHeroContent(
        headline = "See your full training journey",
        supportingCopy = "Review your complete workout history beyond the most recent 30 days."
    )
    PaywallEntryPoint.ADVANCED_ANALYTICS -> PaywallHeroContent(
        headline = "Understand what is changing",
        supportingCopy = "Use deeper progress and trend insights to understand your training."
    )
}

@Composable
private fun PaywallHero(
    context: PaywallContext,
    uiState: SubscriptionPaywallUiState,
    paidTier: SubscriptionTier?
) {
    val copy = context.entryPoint.heroContent()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paywall_hero"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, FitDesiOrange.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CurrentMembershipPill(uiState = uiState, paidTier = paidTier)
            Text(
                text = copy.headline,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = copy.supportingCopy,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.fitDesiColors.mutedContent
            )
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(4.dp)
                    .background(FitDesiOrange, CircleShape)
            )
        }
    }
}

@Composable
private fun CurrentMembershipPill(
    uiState: SubscriptionPaywallUiState,
    paidTier: SubscriptionTier?
) {
    val label = when {
        paidTier == SubscriptionTier.PLUS -> "FitDesi Plus active"
        paidTier == SubscriptionTier.PRO -> "FitDesi Pro active"
        uiState.hasAuthoritativeCustomerInfo -> "Current plan · Basic"
        uiState.customerInfoStatus == PaywallCustomerInfoStatus.LOADING -> "Checking membership"
        else -> "Current plan · Basic"
    }
    val isPaid = paidTier != null

    Surface(
        modifier = Modifier
            .testTag("paywall_current_membership"),
        shape = CircleShape,
        color = if (isPaid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.fitDesiColors.elevatedSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPaid) FitDesiOrange else MaterialTheme.fitDesiColors.border
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .semantics(mergeDescendants = true) {}
                .then(if (isPaid) Modifier.testTag("paywall_paid_active") else Modifier),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (isPaid) FitDesiOrange else MaterialTheme.fitDesiColors.mutedContent, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isPaid) FitDesiOrange else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private data class PrimaryBenefit(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val testTag: String
)

private val personalizedWorkoutsBenefit = PrimaryBenefit(
    title = "Workouts built around you",
    body = "Generate focused workouts and structured routines around your goals.",
    icon = Icons.Default.FitnessCenter,
    testTag = "paywall_benefit_personalized"
)

private val unlimitedRoutinesBenefit = PrimaryBenefit(
    title = "Train without limits",
    body = "Save unlimited authored routines as your training evolves.",
    icon = Icons.Default.AllInclusive,
    testTag = "paywall_benefit_unlimited"
)

private val fullJourneyBenefit = PrimaryBenefit(
    title = "See the full journey",
    body = "Unlock complete workout history and deeper progress insights.",
    icon = Icons.Default.Timeline,
    testTag = "paywall_benefit_journey"
)

private val macroTargetsBenefit = PrimaryBenefit(
    title = "Stay on top of your macros",
    body = "Set macro targets and follow protein, carbohydrate, and fat progress.",
    icon = Icons.Default.Star,
    testTag = "paywall_benefit_macros"
)

private fun PaywallEntryPoint.primaryBenefits(): List<PrimaryBenefit> = when (this) {
    PaywallEntryPoint.MEMBERSHIP -> listOf(
        personalizedWorkoutsBenefit,
        unlimitedRoutinesBenefit,
        fullJourneyBenefit
    )
    PaywallEntryPoint.AI_WORKOUT_GENERATOR -> listOf(
        personalizedWorkoutsBenefit,
        fullJourneyBenefit,
        unlimitedRoutinesBenefit
    )
    PaywallEntryPoint.ROUTINE_LIMIT -> listOf(
        unlimitedRoutinesBenefit,
        personalizedWorkoutsBenefit,
        fullJourneyBenefit
    )
    PaywallEntryPoint.MACRO_TARGETS -> listOf(
        macroTargetsBenefit,
        fullJourneyBenefit,
        personalizedWorkoutsBenefit
    )
    PaywallEntryPoint.FULL_HISTORY,
    PaywallEntryPoint.ADVANCED_ANALYTICS -> listOf(
        fullJourneyBenefit,
        personalizedWorkoutsBenefit,
        unlimitedRoutinesBenefit
    )
}

@Composable
private fun PrimaryBenefitSection(entryPoint: PaywallEntryPoint) {
    val benefits = entryPoint.primaryBenefits()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(
            eyebrow = "BUILT FOR PROGRESS",
            title = "More intention in every session"
        )
        benefits.forEachIndexed { index, benefit ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paywall_benefit_position_${index + 1}")
            ) {
                PrimaryBenefitCard(benefit)
            }
        }
    }
}

@Composable
private fun PrimaryBenefitCard(benefit: PrimaryBenefit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(benefit.testTag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = benefit.icon,
                        contentDescription = null,
                        tint = FitDesiOrange,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = benefit.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = benefit.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.fitDesiColors.mutedContent
                )
            }
        }
    }
}

private data class ComparisonRow(
    val feature: String,
    val basic: String,
    val plus: String
)

private const val COMPARISON_FEATURE_COLUMN_WEIGHT = 1.25f
private const val COMPARISON_BASIC_COLUMN_WEIGHT = 0.8f
private const val COMPARISON_PLUS_COLUMN_WEIGHT = 0.95f
private val comparisonColumnSpacing = 10.dp

@Composable
private fun BasicPlusComparison() {
    val rows = listOf(
        ComparisonRow("FitDesi Coach (local/offline)", "Included", "Included"),
        ComparisonRow("Tracking, catalogues & core tools", "Included", "Included"),
        ComparisonRow("Saved authored routines", "Up to 2", "Unlimited"),
        ComparisonRow("Workout Planner", "Not included", "Included"),
        ComparisonRow("Detailed workout history", "30 days", "Full history"),
        ComparisonRow("Macro targets & progress", "Not included", "Included"),
        ComparisonRow("Progress insights", "Basic", "Advanced analytics")
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(eyebrow = "CHOOSE YOUR LEVEL", title = "Basic vs Plus")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .testTag("paywall_basic_plus_comparison"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ComparisonHeader()
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.fitDesiColors.border.copy(alpha = 0.75f)
                        )
                    }
                    ComparisonFeatureRow(row = row, rowNumber = index + 1)
                }
            }
        }
    }
}

@Composable
private fun ComparisonHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.fitDesiColors.elevatedSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(comparisonColumnSpacing)
    ) {
        Text(
            text = "FEATURE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.fitDesiColors.mutedContent,
            modifier = Modifier.weight(COMPARISON_FEATURE_COLUMN_WEIGHT)
        )
        Text(
            text = "BASIC",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.fitDesiColors.mutedContent,
            modifier = Modifier.weight(COMPARISON_BASIC_COLUMN_WEIGHT)
        )
        Text(
            text = "PLUS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = FitDesiOrange,
            modifier = Modifier.weight(COMPARISON_PLUS_COLUMN_WEIGHT)
        )
    }
}

@Composable
private fun ComparisonFeatureRow(row: ComparisonRow, rowNumber: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .testTag("paywall_comparison_row"),
        horizontalArrangement = Arrangement.spacedBy(comparisonColumnSpacing),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = row.feature,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(COMPARISON_FEATURE_COLUMN_WEIGHT)
                .testTag("paywall_comparison_${rowNumber}_feature")
        )
        Text(
            text = row.basic,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(COMPARISON_BASIC_COLUMN_WEIGHT)
                .testTag("paywall_comparison_${rowNumber}_basic")
        )
        Text(
            text = row.plus,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(COMPARISON_PLUS_COLUMN_WEIGHT)
                .testTag("paywall_comparison_${rowNumber}_plus")
        )
    }
}

@Composable
private fun PlusPlanSection(
    offering: PaywallOfferingState,
    selectedPackageIdentifier: String?,
    enabled: Boolean,
    onSelectPackage: (String) -> Unit,
    onRetryOffering: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(eyebrow = "FITDESI PLUS", title = "Choose your plan")
        when (offering) {
            PaywallOfferingState.NotLoaded -> OfferingLoadingMessage(
                message = "Plans will load when this membership screen opens.",
                showProgress = false
            )
            PaywallOfferingState.Loading -> OfferingLoadingMessage(
                message = "Checking available Plus plans…",
                showProgress = true
            )
            is PaywallOfferingState.Ready -> {
                val annual = offering.options.firstOrNull {
                    it.billingPeriod == SubscriptionBillingPeriod.ANNUAL
                }
                val monthly = offering.options.firstOrNull {
                    it.billingPeriod == SubscriptionBillingPeriod.MONTHLY
                }
                if (annual != null && monthly != null) {
                    Column(
                        modifier = Modifier.testTag("paywall_plan_selector"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PaywallPlanOption(
                            option = annual,
                            selected = annual.packageIdentifier == selectedPackageIdentifier,
                            recommended = true,
                            enabled = enabled,
                            onSelectPackage = onSelectPackage
                        )
                        PaywallPlanOption(
                            option = monthly,
                            selected = monthly.packageIdentifier == selectedPackageIdentifier,
                            recommended = false,
                            enabled = enabled,
                            onSelectPackage = onSelectPackage
                        )
                    }
                }
            }
            PaywallOfferingState.Invalid -> OfferingUnavailableCard(
                message = "The available Plus plans could not be verified. Basic remains available.",
                onRetryOffering = onRetryOffering
            )
            is PaywallOfferingState.Unavailable -> OfferingUnavailableCard(
                message = "${offering.reason.message} Basic remains available.",
                onRetryOffering = onRetryOffering
            )
        }
    }
}

@Composable
private fun PaywallPlanOption(
    option: PaywallPurchaseOption,
    selected: Boolean,
    recommended: Boolean,
    enabled: Boolean,
    onSelectPackage: (String) -> Unit
) {
    val isAnnual = option.billingPeriod == SubscriptionBillingPeriod.ANNUAL
    val planName = if (isAnnual) "Annual" else "Monthly"
    val period = if (isAnnual) "year" else "month"
    val tag = if (isAnnual) "paywall_annual_option" else "paywall_monthly_option"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelectPackage(option.packageIdentifier) }
            )
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.fitDesiColors.elevatedSurface,
            disabledContainerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.fitDesiColors.elevatedSurface.copy(alpha = 0.7f)
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) FitDesiOrange else MaterialTheme.fitDesiColors.border
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) FitDesiOrange else Color.Transparent,
                border = BorderStroke(2.dp, if (selected) FitDesiOrange else MaterialTheme.fitDesiColors.mutedContent),
                modifier = Modifier.size(24.dp)
            ) {
                if (selected) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$planName Plus",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (recommended) {
                        RecommendedBadge()
                    }
                }
                Text(
                    text = "${option.localizedPrice} / $period",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) FitDesiOrange else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun RecommendedBadge() {
    Surface(shape = CircleShape, color = FitDesiOrange) {
        Text(
            text = "Recommended",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun OfferingLoadingMessage(message: String, showProgress: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (showProgress) "paywall_offering_loading" else "paywall_offering_not_loaded"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.fitDesiColors.elevatedSurface,
        border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = FitDesiOrange
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.fitDesiColors.mutedContent,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OfferingUnavailableCard(message: String, onRetryOffering: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.fitDesiColors.mutedContent
            )
            TextButton(
                onClick = onRetryOffering,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("paywall_retry_offering")
            ) {
                Text("Try again", color = FitDesiOrange)
            }
        }
    }
}

@Composable
private fun ProTeaser(isActive: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paywall_pro_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.fitDesiColors.elevatedSurface),
        border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("paywall_pro_teaser"),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = FitDesiOrange,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FitDesi Pro",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (isActive) "Active" else "Coming soon",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = FitDesiOrange
                    )
                }
                Text(
                    text = "Deeper adaptive coaching and long-term training intelligence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.fitDesiColors.mutedContent
                )
            }
        }
    }
}

@Composable
private fun RestorePurchasesSection(
    enabled: Boolean,
    operation: PaywallOperationState,
    onRestore: () -> Unit
) {
    val status = operation.restoreLocalStatus()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(
            onClick = onRestore,
            enabled = enabled,
            border = BorderStroke(1.dp, MaterialTheme.fitDesiColors.border),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.fitDesiColors.disabledContent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("paywall_restore")
        ) {
            Text("Restore purchases")
        }
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodySmall,
            color = when (status.tone) {
                RestoreStatusTone.PROGRESS,
                RestoreStatusTone.SUCCESS -> MaterialTheme.fitDesiColors.success
                RestoreStatusTone.ERROR -> MaterialTheme.colorScheme.error
                RestoreStatusTone.NEUTRAL -> MaterialTheme.fitDesiColors.mutedContent
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("paywall_restore_status")
        )
    }
}

private data class RestoreLocalStatus(
    val message: String,
    val tone: RestoreStatusTone
)

private enum class RestoreStatusTone {
    PROGRESS,
    SUCCESS,
    ERROR,
    NEUTRAL
}

private fun PaywallOperationState.restoreLocalStatus(): RestoreLocalStatus = when (this) {
    PaywallOperationState.Restoring -> RestoreLocalStatus(
        message = "Checking for active purchases…",
        tone = RestoreStatusTone.PROGRESS
    )
    is PaywallOperationState.RestoreSucceeded -> RestoreLocalStatus(
        message = "${tier.displayName()} membership restored and active.",
        tone = RestoreStatusTone.SUCCESS
    )
    PaywallOperationState.NothingActive -> RestoreLocalStatus(
        message = "No active purchases found.",
        tone = RestoreStatusTone.NEUTRAL
    )
    is PaywallOperationState.Failed -> when (reason) {
        PaywallOperationFailure.RESTORE_NETWORK,
        PaywallOperationFailure.RESTORE_STORE_UNAVAILABLE,
        PaywallOperationFailure.RESTORE_NOT_COMPLETED -> RestoreLocalStatus(
            message = reason.message,
            tone = RestoreStatusTone.ERROR
        )
        else -> defaultRestoreLocalStatus()
    }
    is PaywallOperationState.Unavailable -> if (
        reason == PaywallUnavailableReason.SUBSCRIPTIONS_UNAVAILABLE
    ) {
        RestoreLocalStatus(
            message = reason.message,
            tone = RestoreStatusTone.NEUTRAL
        )
    } else {
        defaultRestoreLocalStatus()
    }
    else -> defaultRestoreLocalStatus()
}

private fun defaultRestoreLocalStatus() = RestoreLocalStatus(
    message = "Restore eligible purchases anytime.",
    tone = RestoreStatusTone.NEUTRAL
)

@Composable
private fun PaywallPurchaseFooter(
    uiState: SubscriptionPaywallUiState,
    isPaidMember: Boolean,
    isBusy: Boolean,
    onBack: () -> Unit,
    onPurchase: (String) -> Unit
) {
    val readyOffering = uiState.offering as? PaywallOfferingState.Ready
    val selectedOption = readyOffering?.options?.firstOrNull {
        it.packageIdentifier == uiState.selectedPackageIdentifier
    }
    val selectedPlan = selectedOption?.planName()
    val canPurchase = !isPaidMember && !isBusy && selectedOption != null
    val actionLabel = when {
        isPaidMember -> "Continue"
        isBusy -> "Please wait…"
        selectedPlan != null -> "Continue with $selectedPlan"
        else -> "Choose a plan to continue"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paywall_purchase_footer")
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isPaidMember && selectedOption != null) {
                Text(
                    text = "${selectedOption.planName()} · ${selectedOption.localizedPrice} / ${selectedOption.periodName()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.fitDesiColors.mutedContent
                )
            }
            Button(
                onClick = {
                    if (isPaidMember) {
                        onBack()
                    } else {
                        selectedOption?.packageIdentifier?.let(onPurchase)
                    }
                },
                enabled = isPaidMember || canPurchase,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FitDesiOrange,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.fitDesiColors.disabledContent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("paywall_primary_cta")
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PaywallOperationFeedback(
    operation: PaywallOperationState,
    onOpenAccount: () -> Unit,
    onRetryIdentity: () -> Unit
) {
    when (operation) {
        PaywallOperationState.Idle -> Unit
        is PaywallOperationState.Purchasing -> OperationMessage(
            message = "Completing your purchase…",
            tone = OperationMessageTone.PROGRESS
        )
        PaywallOperationState.Restoring -> OperationMessage(
            message = "Checking for active purchases…",
            tone = OperationMessageTone.PROGRESS
        )
        is PaywallOperationState.PurchaseSucceeded -> OperationMessage(
            message = "${operation.tier.displayName()} membership is now active.",
            tone = OperationMessageTone.SUCCESS
        )
        is PaywallOperationState.RestoreSucceeded -> OperationMessage(
            message = "${operation.tier.displayName()} membership restored and active.",
            tone = OperationMessageTone.SUCCESS
        )
        PaywallOperationState.PurchaseCancelled -> OperationMessage(
            message = "Purchase cancelled. No changes were made.",
            tone = OperationMessageTone.NEUTRAL
        )
        PaywallOperationState.NothingActive -> OperationMessage(
            message = "No active purchases found.",
            tone = OperationMessageTone.NEUTRAL
        )
        PaywallOperationState.AccountRequired -> OperationMessageWithAction(
            message = "Sign in or create a verified account to purchase or restore.",
            actionLabel = "Open Account & security",
            onAction = onOpenAccount
        )
        PaywallOperationState.VerificationRequired -> OperationMessageWithAction(
            message = "Verify your email before purchasing or restoring.",
            actionLabel = "Open Account & security",
            onAction = onOpenAccount
        )
        PaywallOperationState.PreparingAccount -> OperationMessage(
            message = "Preparing your account for purchases…",
            tone = OperationMessageTone.PROGRESS
        )
        PaywallOperationState.IdentityUnavailable -> OperationMessageWithAction(
            message = "Your purchase account is unavailable. Try preparing it again.",
            actionLabel = "Retry account preparation",
            onAction = onRetryIdentity,
            tone = OperationMessageTone.ERROR
        )
        is PaywallOperationState.Failed -> OperationMessage(
            message = operation.reason.message,
            tone = OperationMessageTone.ERROR
        )
        is PaywallOperationState.Unavailable -> OperationMessage(
            message = operation.reason.message,
            tone = OperationMessageTone.NEUTRAL
        )
    }
}

@Composable
private fun OperationMessageWithAction(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    tone: OperationMessageTone = OperationMessageTone.NEUTRAL
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OperationMessage(message = message, tone = tone)
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("paywall_account_action")
        ) {
            Text(actionLabel)
        }
    }
}

private enum class OperationMessageTone {
    PROGRESS,
    SUCCESS,
    NEUTRAL,
    ERROR
}

@Composable
private fun OperationMessage(message: String, tone: OperationMessageTone) {
    val containerColor = when (tone) {
        OperationMessageTone.SUCCESS -> MaterialTheme.fitDesiColors.success.copy(alpha = 0.14f)
        OperationMessageTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        OperationMessageTone.PROGRESS,
        OperationMessageTone.NEUTRAL -> MaterialTheme.fitDesiColors.elevatedSurface
    }
    val contentColor = when (tone) {
        OperationMessageTone.SUCCESS -> MaterialTheme.fitDesiColors.success
        OperationMessageTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        OperationMessageTone.PROGRESS,
        OperationMessageTone.NEUTRAL -> MaterialTheme.fitDesiColors.mutedContent
    }
    val tag = when (tone) {
        OperationMessageTone.PROGRESS -> "paywall_operation_progress"
        OperationMessageTone.SUCCESS -> "paywall_operation_success"
        OperationMessageTone.NEUTRAL -> "paywall_operation_neutral"
        OperationMessageTone.ERROR -> "paywall_operation_error"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(
            1.dp,
            if (tone == OperationMessageTone.SUCCESS) MaterialTheme.fitDesiColors.success else MaterialTheme.fitDesiColors.border
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tone == OperationMessageTone.PROGRESS) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = FitDesiOrange
                )
            } else if (tone == OperationMessageTone.SUCCESS) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.fitDesiColors.success,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SectionHeading(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = FitDesiOrange
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}

private fun PaywallPurchaseOption.planName(): String = when (billingPeriod) {
    SubscriptionBillingPeriod.ANNUAL -> "Annual"
    SubscriptionBillingPeriod.MONTHLY -> "Monthly"
}

private fun PaywallPurchaseOption.periodName(): String = when (billingPeriod) {
    SubscriptionBillingPeriod.ANNUAL -> "year"
    SubscriptionBillingPeriod.MONTHLY -> "month"
}

private fun SubscriptionPaywallUiState.confirmedPaidTier(): SubscriptionTier? {
    val successfulTier = when (val result = operation) {
        is PaywallOperationState.PurchaseSucceeded -> result.tier
        is PaywallOperationState.RestoreSucceeded -> result.tier
        else -> null
    }
    if (successfulTier == SubscriptionTier.PLUS || successfulTier == SubscriptionTier.PRO) {
        return successfulTier
    }
    return currentTier.takeIf {
        hasAuthoritativeCustomerInfo && it != SubscriptionTier.BASIC
    }
}

private fun SubscriptionTier.displayName(): String = when (this) {
    SubscriptionTier.BASIC -> "Basic"
    SubscriptionTier.PLUS -> "Plus"
    SubscriptionTier.PRO -> "Pro"
}
