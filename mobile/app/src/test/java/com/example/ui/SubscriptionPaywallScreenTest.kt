package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.subscription.SubscriptionBillingPeriod
import com.example.subscription.SubscriptionTier
import com.example.boost.BoostAccessState
import com.example.boost.BoostAdState
import com.example.boost.BoostAdUnavailableReason
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.viewmodel.PaywallCustomerInfoStatus
import com.example.viewmodel.PaywallOfferingState
import com.example.viewmodel.PaywallOperationFailure
import com.example.viewmodel.PaywallOperationState
import com.example.viewmodel.PaywallPurchaseOption
import com.example.viewmodel.PaywallUnavailableReason
import com.example.viewmodel.SubscriptionPaywallUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionPaywallScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `profile membership value is authoritative and otherwise fails Basic-safe`() {
        assertEquals("Basic", state(tier = SubscriptionTier.BASIC).profileMembershipValue())
        assertEquals("Plus", state(tier = SubscriptionTier.PLUS).profileMembershipValue())
        assertEquals("Pro", state(tier = SubscriptionTier.PRO).profileMembershipValue())
        assertEquals(
            "Checking membership",
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.LOADING,
                hasAuthoritativeCustomerInfo = false
            ).profileMembershipValue()
        )
        assertEquals(
            "Basic",
            state(
                tier = SubscriptionTier.PRO,
                customerInfoStatus = PaywallCustomerInfoStatus.ERROR,
                hasAuthoritativeCustomerInfo = false
            ).profileMembershipValue()
        )
        assertEquals(
            "Basic",
            state(
                tier = SubscriptionTier.PLUS,
                customerInfoStatus = PaywallCustomerInfoStatus.DISABLED,
                hasAuthoritativeCustomerInfo = false
            ).profileMembershipValue()
        )
    }

    @Test
    fun `Home Plus entry requires authoritative Ready Basic membership`() {
        assertTrue(state().shouldShowBasicHomeUpgradeEntry())

        listOf(
            state(tier = SubscriptionTier.PLUS),
            state(tier = SubscriptionTier.PRO),
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.LOADING,
                hasAuthoritativeCustomerInfo = false
            ),
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.DISABLED,
                hasAuthoritativeCustomerInfo = false
            ),
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.ERROR,
                hasAuthoritativeCustomerInfo = false
            ),
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.READY,
                hasAuthoritativeCustomerInfo = false
            )
        ).forEach { uiState ->
            assertFalse(uiState.shouldShowBasicHomeUpgradeEntry())
        }
    }

    @Test
    fun `compact Home Plus entry opens membership once`() {
        var opens = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                HomeBasicPlusEntry(onExplorePlus = { opens++ })
            }
        }

        composeTestRule.onNodeWithTag("home_basic_plus_entry")
            .assertIsDisplayed()
            .assertTextContains("FitDesi Basic")
            .assertTextContains("Explore FitDesi Plus")
            .performClick()

        assertEquals(1, opens)
    }

    @Test
    fun `Profile membership card shows current value and opens it`() {
        var opens = 0
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                ProfileMembershipCard(
                    membershipValue = "Plus",
                    onPlanAndMembershipClick = { opens++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_plan_and_membership")
            .assertExists()
            .assertTextContains("Plan & membership")
            .assertTextContains("Plus")
            .performClick()

        assertEquals(1, opens)
    }

    @Test
    fun `membership entry shows default hero copy`() {
        render(state(), entryPoint = PaywallEntryPoint.MEMBERSHIP)

        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText("Train smarter with FitDesi Plus").assertExists()
        composeTestRule.onNodeWithText(
            "Build personalized workouts, remove routine limits, and understand your progress beyond the basics."
        ).assertExists()
    }

    @Test
    fun `AI workout generator entry shows contextual headline`() {
        render(state(), entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR)
        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText("Build your next workout with FitDesi Plus").assertExists()
    }

    @Test
    fun `routine limit entry shows contextual headline`() {
        render(state(), entryPoint = PaywallEntryPoint.ROUTINE_LIMIT)
        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText("Keep building without routine limits").assertExists()
    }

    @Test
    fun `macro targets entry shows contextual headline`() {
        render(state(), entryPoint = PaywallEntryPoint.MACRO_TARGETS)
        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText(
            "Turn your calorie target into a complete nutrition plan"
        ).assertExists()
    }

    @Test
    fun `full history entry shows contextual headline`() {
        render(state(), entryPoint = PaywallEntryPoint.FULL_HISTORY)
        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText("See your full training journey").assertExists()
    }

    @Test
    fun `advanced analytics entry shows contextual headline`() {
        render(state(), entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS)
        composeTestRule.onNodeWithTag("paywall_hero").assertExists()
        composeTestRule.onNodeWithText("Understand what is changing").assertExists()
    }

    @Test
    fun `AI workout generator leads with personalized workouts`() {
        assertFirstBenefit(
            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR,
            expectedBenefitTag = "paywall_benefit_personalized"
        )
    }

    @Test
    fun `routine limit leads with unlimited authored routines`() {
        assertFirstBenefit(
            entryPoint = PaywallEntryPoint.ROUTINE_LIMIT,
            expectedBenefitTag = "paywall_benefit_unlimited"
        )
    }

    @Test
    fun `macro targets leads with macro targets and progress`() {
        assertFirstBenefit(
            entryPoint = PaywallEntryPoint.MACRO_TARGETS,
            expectedBenefitTag = "paywall_benefit_macros"
        )
    }

    @Test
    fun `full history leads with the full journey`() {
        assertFirstBenefit(
            entryPoint = PaywallEntryPoint.FULL_HISTORY,
            expectedBenefitTag = "paywall_benefit_journey"
        )
    }

    @Test
    fun `advanced analytics leads with deeper progress insights`() {
        assertFirstBenefit(
            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS,
            expectedBenefitTag = "paywall_benefit_journey"
        )
    }

    @Test
    fun `Basic Generator context exposes deliberate secondary Boost action`() {
        var boostRequests = 0
        render(
            state(boostAdState = BoostAdState.Ready),
            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR,
            onRequestBoost = { boostRequests++ }
        )

        composeTestRule.onNodeWithTag("paywall_primary_cta").assertExists()
        composeTestRule.onNodeWithTag("paywall_boost_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("paywall_boost_copy")
            .assertTextContains("If you choose, watch one rewarded ad", substring = true)
            .assertTextContains("RevenueCat must verify it", substring = true)
            .assertTextContains("30 minutes", substring = true)
            .assertTextContains("does not create Plus ownership", substring = true)
            .assertTextContains(
                "Workout Planner and Advanced Analytics stay available",
                substring = true
            )
            .assertTextContains("full history", substring = true)
            .assertTextContains("macro targets", substring = true)
            .assertTextContains("unlimited authored routines", substring = true)
        composeTestRule.onAllNodesWithTag("paywall_boost_action").assertCountEquals(1)
        composeTestRule.onNodeWithTag("paywall_boost_return").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_boost_action")
            .assertTextContains("Watch rewarded ad for 30 minutes")
            .performClick()

        assertEquals(1, boostRequests)
    }

    @Test
    fun `Boost is absent outside approved contexts`() {
        render(
            state(boostAdState = BoostAdState.Ready),
            entryPoint = PaywallEntryPoint.FULL_HISTORY
        )
        composeTestRule.onNodeWithTag("paywall_boost_card").assertDoesNotExist()
    }

    @Test
    fun `Boost is absent for permanent Plus`() {
        render(
            state(tier = SubscriptionTier.PLUS, boostAdState = BoostAdState.Ready),
            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS
        )
        composeTestRule.onNodeWithTag("paywall_boost_card").assertDoesNotExist()
    }

    @Test
    fun `Boost is absent for permanent Pro`() {
        render(
            state(tier = SubscriptionTier.PRO, boostAdState = BoostAdState.Ready),
            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR
        )
        composeTestRule.onNodeWithTag("paywall_boost_card").assertDoesNotExist()
    }

    @Test
    fun `active Boost requires explicit return before retrying blocked action`() {
        var closes = 0
        render(
            state(
                boostAccess = BoostAccessState.Active(expiresAtEpochMillis = 2_000L),
                boostAdState = BoostAdState.NeedsPreparation
            ),
            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS,
            onBack = { closes++ }
        )

        composeTestRule.onNodeWithTag("paywall_boost_action").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_boost_copy")
            .assertTextContains("temporary Boost", substring = true)
            .assertTextContains("only Advanced Analytics access", substring = true)
            .assertTextContains("does not create Plus ownership", substring = true)
            .assertTextContains(
                "Workout Planner and Advanced Analytics stay available",
                substring = true
            )
            .assertTextContains("full history", substring = true)
            .assertTextContains("macro targets", substring = true)
            .assertTextContains("unlimited authored routines", substring = true)
        composeTestRule.onAllNodesWithTag("paywall_boost_return").assertCountEquals(1)
        composeTestRule.onNodeWithTag("paywall_boost_return")
            .performScrollTo()
            .assertTextContains("Return and try again")
            .performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `disabled release-style controller exposes no Boost action`() {
        render(
            state(boostAdState = BoostAdState.Disabled),
            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR
        )

        composeTestRule.onNodeWithTag("paywall_boost_card").assertDoesNotExist()
    }

    @Test
    fun `required UMP privacy options remain deliberate and contextual`() {
        var privacyRequests = 0
        render(
            state(
                boostAdState = BoostAdState.Ready,
                boostPrivacyOptionsRequired = true
            ),
            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS,
            onShowBoostPrivacyOptions = { privacyRequests++ }
        )

        composeTestRule.onNodeWithTag("paywall_boost_privacy_options")
            .performScrollTo()
            .assertTextContains("Privacy choices")
            .performClick()
        assertEquals(1, privacyRequests)
    }

    @Test
    fun `Boost loading state cannot launch an ad`() {
        render(
            state(boostAdState = BoostAdState.Loading),
            entryPoint = PaywallEntryPoint.AI_WORKOUT_GENERATOR
        )

        composeTestRule.onNodeWithTag("paywall_boost_action")
            .performScrollTo()
            .assertTextEquals("Loading rewarded ad…")
            .assertIsNotEnabled()
    }

    @Test
    fun `Boost unavailability exposes only an explicit retry`() {
        var requests = 0
        render(
            state(
                boostAdState = BoostAdState.Unavailable(
                    BoostAdUnavailableReason.AD_UNAVAILABLE
                )
            ),
            entryPoint = PaywallEntryPoint.ADVANCED_ANALYTICS,
            onRequestBoost = { requests++ }
        )

        composeTestRule.onNodeWithTag("paywall_boost_action")
            .performScrollTo()
            .assertTextContains("Try Boost again")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `hero uses compact truthful membership badge`() {
        render(
            state(
                customerInfoStatus = PaywallCustomerInfoStatus.LOADING,
                hasAuthoritativeCustomerInfo = false
            )
        )
        composeTestRule.onNodeWithTag("paywall_current_membership").assertExists()
        composeTestRule.onNodeWithText("Checking membership").assertExists()
    }

    @Test
    fun `authoritative Basic uses the compact Basic plan badge`() {
        render(state())
        composeTestRule.onNodeWithTag("paywall_current_membership").assertExists()
        composeTestRule.onNodeWithText("Current plan · Basic").assertExists()
    }

    @Test
    fun `three primary value cards render`() {
        render(state())
        composeTestRule.onNodeWithTag("paywall_benefit_personalized")
            .assertTextContains("Workouts built around you")
        composeTestRule.onNodeWithTag("paywall_benefit_unlimited")
            .assertTextContains("Train without limits")
        composeTestRule.onNodeWithTag("paywall_benefit_journey")
            .assertTextContains("See the full journey")
    }

    @Test
    fun `comparison presents seven truthful Basic and Plus rows`() {
        render(state())

        assertComparisonRow(1, "FitDesi Coach (local/offline)", "Included", "Included")
        assertComparisonRow(2, "Tracking, catalogues & core tools", "Included", "Included")
        assertComparisonRow(3, "Saved authored routines", "Up to 2", "Unlimited")
        assertComparisonRow(4, "Workout Planner", "Not included", "Included")
        assertComparisonRow(5, "Detailed workout history", "30 days", "Full history")
        assertComparisonRow(6, "Macro targets & progress", "Not included", "Included")
        assertComparisonRow(7, "Progress insights", "Basic", "Advanced analytics")

        composeTestRule.onAllNodesWithTag(
            "paywall_comparison_row",
            useUnmergedTree = true
        ).assertCountEquals(7)
        composeTestRule.onNodeWithText(
            "remote AI Coach",
            substring = true,
            ignoreCase = true
        ).assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "online AI Coach",
            substring = true,
            ignoreCase = true
        ).assertDoesNotExist()
    }

    @Test
    fun `Ready offering renders localized prices verbatim with Annual recommended`() {
        render(
            state(
                offering = readyOffering(),
                selectedPackageIdentifier = annualIdentifier
            )
        )
        composeTestRule.onNodeWithTag("paywall_plan_selector").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("paywall_annual_option")
            .assertTextContains("Annual Plus")
            .assertTextContains("Recommended")
            .assertTextContains("€98,76 / year")
            .assertIsSelected()
        composeTestRule.onNodeWithTag("paywall_monthly_option")
            .assertTextContains("Monthly Plus")
            .assertTextContains("PKR 1,234.56 / month")
            .assertIsNotSelected()
        composeTestRule.onNodeWithText("Free trial", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("discount", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping Monthly requests exact Monthly selection`() {
        val selected = mutableListOf<String>()
        render(
            state(offering = readyOffering(), selectedPackageIdentifier = annualIdentifier),
            onSelectPackage = selected::add
        )
        composeTestRule.onNodeWithTag("paywall_monthly_option").performScrollTo().performClick()
        assertEquals(listOf(monthlyIdentifier), selected)
    }

    @Test
    fun `tapping Annual requests exact Annual selection`() {
        val selected = mutableListOf<String>()
        render(
            state(offering = readyOffering(), selectedPackageIdentifier = monthlyIdentifier),
            onSelectPackage = selected::add
        )
        composeTestRule.onNodeWithTag("paywall_annual_option").performScrollTo().performClick()
        assertEquals(listOf(annualIdentifier), selected)
    }

    @Test
    fun `Annual selection exposes one CTA and routes Annual identifier`() {
        val purchased = mutableListOf<String>()
        render(
            state(offering = readyOffering(), selectedPackageIdentifier = annualIdentifier),
            onPurchase = purchased::add
        )
        composeTestRule.onAllNodesWithTag("paywall_primary_cta").assertCountEquals(1)
        composeTestRule.onNodeWithTag("paywall_primary_cta")
            .assertTextContains("Continue with Annual")
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(annualIdentifier), purchased)
    }

    @Test
    fun `Monthly selection exposes one CTA and routes Monthly identifier`() {
        val purchased = mutableListOf<String>()
        render(
            state(offering = readyOffering(), selectedPackageIdentifier = monthlyIdentifier),
            onPurchase = purchased::add
        )
        composeTestRule.onAllNodesWithTag("paywall_primary_cta").assertCountEquals(1)
        composeTestRule.onNodeWithTag("paywall_primary_cta")
            .assertTextContains("Continue with Monthly")
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(monthlyIdentifier), purchased)
    }

    @Test
    fun `Ready offering without a valid selection disables CTA`() {
        render(state(offering = readyOffering(), selectedPackageIdentifier = null))
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
    }

    @Test
    fun `Loading disables purchase while restore stays available`() {
        render(state(offering = PaywallOfferingState.Loading))
        composeTestRule.onNodeWithTag("paywall_plan_selector").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("paywall_offering_loading").performScrollTo().assertExists()
    }

    @Test
    fun `NotLoaded has no actionable purchase and restore stays available`() {
        render(state(offering = PaywallOfferingState.NotLoaded))
        composeTestRule.onNodeWithTag("paywall_offering_not_loaded").performScrollTo().assertExists()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `Invalid offering has retry and cannot purchase`() {
        var retries = 0
        render(state(offering = PaywallOfferingState.Invalid), onRetryOffering = { retries++ })
        composeTestRule.onNodeWithTag("paywall_plan_selector").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_retry_offering").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `Unavailable offering has retry and restore independent of offering readiness`() {
        var retries = 0
        var restores = 0
        render(
            state(
                offering = PaywallOfferingState.Unavailable(
                    PaywallUnavailableReason.OFFERING_UNAVAILABLE
                )
            ),
            onRestore = { restores++ },
            onRetryOffering = { retries++ }
        )
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_retry_offering").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(1, restores)
    }

    @Test
    fun `busy operation disables purchase selection CTA and restore`() {
        render(
            state(
                offering = readyOffering(),
                operation = PaywallOperationState.Purchasing(annualIdentifier),
                selectedPackageIdentifier = annualIdentifier
            )
        )
        composeTestRule.onNodeWithTag("paywall_annual_option").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_monthly_option").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_operation_progress").performScrollTo().assertExists()
    }

    @Test
    fun `restore operation disables all other commercial actions`() {
        render(
            state(
                offering = readyOffering(),
                operation = PaywallOperationState.Restoring,
                selectedPackageIdentifier = annualIdentifier
            )
        )
        composeTestRule.onNodeWithTag("paywall_annual_option").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_monthly_option").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `restoring feedback is visible beside Restore`() {
        render(state(operation = PaywallOperationState.Restoring))

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Checking for active purchases…")
    }

    @Test
    fun `Plus restore success is visible beside Restore`() {
        render(
            state(
                tier = SubscriptionTier.PLUS,
                operation = PaywallOperationState.RestoreSucceeded(SubscriptionTier.PLUS)
            )
        )

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Plus membership restored and active.")
    }

    @Test
    fun `nothing active feedback is visible beside Restore`() {
        render(state(operation = PaywallOperationState.NothingActive))

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("No active purchases found.")
    }

    @Test
    fun `restore network failure is visible beside Restore`() {
        render(
            state(
                operation = PaywallOperationState.Failed(
                    PaywallOperationFailure.RESTORE_NETWORK
                )
            )
        )

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains(PaywallOperationFailure.RESTORE_NETWORK.message)
    }

    @Test
    fun `restore unavailable feedback is visible beside Restore when applicable`() {
        render(
            state(
                operation = PaywallOperationState.Unavailable(
                    PaywallUnavailableReason.SUBSCRIPTIONS_UNAVAILABLE
                )
            )
        )

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains(PaywallUnavailableReason.SUBSCRIPTIONS_UNAVAILABLE.message)
    }

    @Test
    fun `purchase cancellation is not copied into restore-local feedback`() {
        render(state(operation = PaywallOperationState.PurchaseCancelled))

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Restore eligible purchases anytime.")
        composeTestRule.onNodeWithTag("paywall_operation_neutral")
            .assertTextContains("Purchase cancelled. No changes were made.")
    }

    @Test
    fun `purchase failure is not copied into restore-local feedback`() {
        render(
            state(
                operation = PaywallOperationState.Failed(
                    PaywallOperationFailure.PURCHASE_NETWORK
                )
            )
        )

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Restore eligible purchases anytime.")
        composeTestRule.onNodeWithTag("paywall_operation_error")
            .assertTextContains(PaywallOperationFailure.PURCHASE_NETWORK.message)
    }

    @Test
    fun `idle restore helper copy remains beside Restore`() {
        render(state(operation = PaywallOperationState.Idle))

        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Restore eligible purchases anytime.")
    }

    @Test
    fun `authoritative Plus shows active state no selector and Continue closes`() {
        var closes = 0
        render(
            state(tier = SubscriptionTier.PLUS, offering = readyOffering()),
            onBack = { closes++ }
        )
        composeTestRule.onNodeWithTag("paywall_paid_active")
            .assertTextContains("FitDesi Plus active")
        composeTestRule.onNodeWithTag("paywall_plan_selector").assertDoesNotExist()
        composeTestRule.onNodeWithText("Annual Plus", substring = false).assertDoesNotExist()
        composeTestRule.onNodeWithText("Monthly Plus", substring = false).assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_primary_cta")
            .assertTextContains("Continue")
            .performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `authoritative Pro shows active state without purchase or duration inference`() {
        render(state(tier = SubscriptionTier.PRO, offering = readyOffering()))
        composeTestRule.onNodeWithTag("paywall_paid_active")
            .assertTextContains("FitDesi Pro active")
        composeTestRule.onNodeWithTag("paywall_plan_selector").assertDoesNotExist()
        composeTestRule.onNodeWithText("Annual Plus", substring = false).assertDoesNotExist()
        composeTestRule.onNodeWithText("Monthly Plus", substring = false).assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertTextContains("Continue")
        composeTestRule.onNodeWithTag("paywall_pro_teaser")
            .performScrollTo()
            .assertTextContains("Active")
        composeTestRule.onNodeWithText("Coming soon", substring = false).assertDoesNotExist()
    }

    @Test
    fun `Pro teaser is Coming soon without price or purchase action`() {
        render(state())
        composeTestRule.onNodeWithTag("paywall_pro_teaser")
            .performScrollTo()
            .assertTextContains("FitDesi Pro")
            .assertTextContains("Coming soon")
            .assertTextContains("Deeper adaptive coaching and long-term training intelligence.")
        composeTestRule.onNodeWithTag("paywall_pro_price").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_purchase_pro").assertDoesNotExist()
    }

    @Test
    fun `cancelled feedback is neutral`() {
        render(state(operation = PaywallOperationState.PurchaseCancelled))
        composeTestRule.onNodeWithTag("paywall_operation_neutral")
            .assertTextContains("Purchase cancelled. No changes were made.")
        composeTestRule.onNodeWithTag("paywall_operation_error").assertDoesNotExist()
    }

    @Test
    fun `nothing active feedback is neutral`() {
        render(state(operation = PaywallOperationState.NothingActive))
        composeTestRule.onNodeWithTag("paywall_operation_neutral")
            .assertTextContains("No active purchases found.")
        composeTestRule.onNodeWithTag("paywall_operation_error").assertDoesNotExist()
    }

    @Test
    fun `typed failure shows stable FitDesi copy and remains restorable`() {
        render(
            state(
                operation = PaywallOperationState.Failed(
                    PaywallOperationFailure.PURCHASE_NETWORK
                )
            )
        )
        composeTestRule.onNodeWithTag("paywall_operation_error")
            .assertTextContains(PaywallOperationFailure.PURCHASE_NETWORK.message)
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `purchase success shows positive feedback and Continue remains user controlled`() {
        var closes = 0
        render(
            state(
                tier = SubscriptionTier.PLUS,
                operation = PaywallOperationState.PurchaseSucceeded(SubscriptionTier.PLUS)
            ),
            onBack = { closes++ }
        )
        composeTestRule.onNodeWithTag("paywall_operation_success")
            .performScrollTo()
            .assertTextContains("Plus membership is now active.")
        composeTestRule.onNodeWithTag("paywall_restore_status")
            .performScrollTo()
            .assertTextContains("Restore eligible purchases anytime.")
        assertEquals(0, closes)
        composeTestRule.onNodeWithTag("paywall_primary_cta").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `restore success becomes paid presentation before close`() {
        render(
            state(
                hasAuthoritativeCustomerInfo = false,
                operation = PaywallOperationState.RestoreSucceeded(SubscriptionTier.PRO),
                offering = readyOffering(),
                selectedPackageIdentifier = annualIdentifier
            )
        )
        composeTestRule.onNodeWithTag("paywall_operation_success")
            .performScrollTo()
            .assertTextContains("Pro membership restored and active.")
        composeTestRule.onNodeWithTag("paywall_paid_active")
            .assertTextContains("FitDesi Pro active")
        composeTestRule.onNodeWithTag("paywall_plan_selector").assertDoesNotExist()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertTextContains("Continue")
    }

    @Test
    fun `top app bar and primary CTA meet minimum touch sizes`() {
        render(
            state(offering = readyOffering(), selectedPackageIdentifier = annualIdentifier)
        )
        val backBounds = composeTestRule.onNodeWithContentDescription("Close paywall")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.width >= 48f)
        assertTrue(backBounds.height >= 48f)
        val ctaBounds = composeTestRule.onNodeWithTag("paywall_primary_cta")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(ctaBounds.height >= 52f)
    }

    @Test
    fun `small width and larger text keep content scrollable and footer reachable`() {
        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.6f)
            ) {
                MyPersonalTrainerTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        SubscriptionPaywallScreen(
                            context = PaywallContext(PaywallEntryPoint.MEMBERSHIP),
                            uiState = state(
                                offering = readyOffering(),
                                selectedPackageIdentifier = annualIdentifier
                            ),
                            onBack = {},
                            onSelectPackage = {},
                            onPurchase = {},
                            onRestore = {},
                            onRetryOffering = {}
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("paywall_pro_teaser").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("paywall_primary_cta").assertIsDisplayed()
        composeTestRule.onNodeWithTag("paywall_restore").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `paywall session opens and closes once despite recomposition`() {
        var visible by mutableStateOf(true)
        var recompositionTick by mutableIntStateOf(0)
        var opens = 0
        var closes = 0
        composeTestRule.setContent {
            if (visible) {
                SubscriptionPaywallSession(
                    onOpened = { opens++ },
                    onClosed = { closes++ }
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(1.dp)
                            .testTag("paywall_session_tick_$recompositionTick")
                    )
                }
            }
        }
        composeTestRule.runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, closes)
            recompositionTick++
        }
        composeTestRule.runOnIdle {
            assertEquals(1, opens)
            assertEquals(0, closes)
            visible = false
        }
        composeTestRule.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, closes)
            visible = true
        }
        composeTestRule.runOnIdle {
            assertEquals(2, opens)
            assertEquals(1, closes)
        }
    }

    private fun assertFirstBenefit(
        entryPoint: PaywallEntryPoint,
        expectedBenefitTag: String
    ) {
        render(state(), entryPoint = entryPoint)
        composeTestRule.onNode(
            hasTestTag("paywall_benefit_position_1") and
                hasAnyDescendant(hasTestTag(expectedBenefitTag))
        ).assertExists()
    }

    private fun assertComparisonRow(
        rowNumber: Int,
        feature: String,
        basic: String,
        plus: String
    ) {
        composeTestRule.onNodeWithTag(
            "paywall_comparison_${rowNumber}_feature",
            useUnmergedTree = true
        ).assertTextEquals(feature)
        composeTestRule.onNodeWithTag(
            "paywall_comparison_${rowNumber}_basic",
            useUnmergedTree = true
        ).assertTextEquals(basic)
        composeTestRule.onNodeWithTag(
            "paywall_comparison_${rowNumber}_plus",
            useUnmergedTree = true
        ).assertTextEquals(plus)
    }

    private fun render(
        uiState: SubscriptionPaywallUiState,
        entryPoint: PaywallEntryPoint = PaywallEntryPoint.MEMBERSHIP,
        onBack: () -> Unit = {},
        onSelectPackage: (String) -> Unit = {},
        onPurchase: (String) -> Unit = {},
        onRestore: () -> Unit = {},
        onRetryOffering: () -> Unit = {},
        onRequestBoost: () -> Unit = {},
        onShowBoostPrivacyOptions: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MyPersonalTrainerTheme {
                SubscriptionPaywallScreen(
                    context = PaywallContext(entryPoint),
                    uiState = uiState,
                    onBack = onBack,
                    onSelectPackage = onSelectPackage,
                    onPurchase = onPurchase,
                    onRestore = onRestore,
                    onRetryOffering = onRetryOffering,
                    onRequestBoost = onRequestBoost,
                    onShowBoostPrivacyOptions = onShowBoostPrivacyOptions
                )
            }
        }
    }

    private fun state(
        tier: SubscriptionTier = SubscriptionTier.BASIC,
        customerInfoStatus: PaywallCustomerInfoStatus = PaywallCustomerInfoStatus.READY,
        hasAuthoritativeCustomerInfo: Boolean = true,
        offering: PaywallOfferingState = PaywallOfferingState.NotLoaded,
        operation: PaywallOperationState = PaywallOperationState.Idle,
        selectedPackageIdentifier: String? = null,
        boostAccess: BoostAccessState = BoostAccessState.Unknown,
        boostAdState: BoostAdState = BoostAdState.Disabled,
        boostPrivacyOptionsRequired: Boolean = false
    ) = SubscriptionPaywallUiState(
        currentTier = tier,
        customerInfoStatus = customerInfoStatus,
        hasAuthoritativeCustomerInfo = hasAuthoritativeCustomerInfo,
        offering = offering,
        operation = operation,
        selectedPackageIdentifier = selectedPackageIdentifier,
        boostAccess = boostAccess,
        boostAdState = boostAdState,
        boostPrivacyOptionsRequired = boostPrivacyOptionsRequired
    )

    private fun readyOffering() = PaywallOfferingState.Ready(
        options = listOf(
            PaywallPurchaseOption(
                packageIdentifier = monthlyIdentifier,
                billingPeriod = SubscriptionBillingPeriod.MONTHLY,
                localizedPrice = "PKR 1,234.56"
            ),
            PaywallPurchaseOption(
                packageIdentifier = annualIdentifier,
                billingPeriod = SubscriptionBillingPeriod.ANNUAL,
                localizedPrice = "€98,76"
            )
        )
    )

    private companion object {
        const val monthlyIdentifier = "plus_monthly_fixture"
        const val annualIdentifier = "plus_annual_fixture"
    }
}
