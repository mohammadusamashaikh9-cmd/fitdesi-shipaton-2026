package com.example.viewmodel

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.boost.BoostAccessState
import com.example.boost.BoostAdController
import com.example.boost.BoostAdState
import com.example.boost.BoostRepository
import com.example.subscription.CommercialOperationBlockReason
import com.example.subscription.OfferingLoadResult
import com.example.subscription.PurchaseFailureReason
import com.example.subscription.PurchaseResult
import com.example.subscription.RestoreFailureReason
import com.example.subscription.RestoreResult
import com.example.subscription.SubscriptionBillingPeriod
import com.example.subscription.SubscriptionOperationUnavailableReason
import com.example.subscription.SubscriptionPackage
import com.example.subscription.SubscriptionPurchaseCoordinator
import com.example.subscription.SubscriptionRepository
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SubscriptionPaywallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `offering does not load before paywall opened`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.selectPackage("plus_monthly")

        assertEquals(0, repository.offeringLoadCalls)
        assertEquals(PaywallOfferingState.NotLoaded, viewModel.uiState.value.offering)
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
    }

    @Test
    fun `paywall opened refreshes CustomerInfo and loads offering only once`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val viewModel = viewModel(repository)

            viewModel.onPaywallOpened()
            viewModel.onPaywallOpened()

            assertEquals(1, repository.refreshCalls)
            assertEquals(1, repository.offeringLoadCalls)
            assertEquals(PaywallOfferingState.Loading, viewModel.uiState.value.offering)
            assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
        }

    @Test
    fun `paywall opened clears stale operation messaging`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)

        viewModel.restorePurchases()
        coordinator.completeRestore(RestoreResult.NothingActive)
        assertEquals(PaywallOperationState.NothingActive, viewModel.uiState.value.operation)

        viewModel.onPaywallOpened()

        assertEquals(PaywallOperationState.Idle, viewModel.uiState.value.operation)
    }

    @Test
    fun `close then reopen refreshes CustomerInfo and reloads current offering`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val viewModel = openedViewModel(repository)
            repository.completeOffering(OfferingLoadResult.Ready(validPackages))
            viewModel.selectPackage("plus_monthly")

            viewModel.onPaywallClosed()
            assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
            viewModel.onPaywallOpened()

            assertEquals(2, repository.refreshCalls)
            assertEquals(2, repository.offeringLoadCalls)
            assertEquals(PaywallOfferingState.Loading, viewModel.uiState.value.offering)
            assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)

            repository.completeOffering(OfferingLoadResult.Ready(validPackages))
            assertEquals("plus_annual", viewModel.uiState.value.selectedPackageIdentifier)
        }

    @Test
    fun `close then reopen clears completed cancelled and error feedback when idle`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val coordinator = FakePurchaseCoordinator()
            val viewModel = readyViewModel(repository, coordinator)

            viewModel.purchase(activity(), "plus_monthly")
            coordinator.completePurchase(PurchaseResult.Cancelled)
            assertEquals(
                PaywallOperationState.PurchaseCancelled,
                viewModel.uiState.value.operation
            )
            viewModel.onPaywallClosed()
            viewModel.onPaywallOpened()
            assertEquals(PaywallOperationState.Idle, viewModel.uiState.value.operation)
            repository.completeOffering(OfferingLoadResult.Ready(validPackages))

            viewModel.purchase(activity(), "plus_annual")
            coordinator.completePurchase(PurchaseResult.Failed(PurchaseFailureReason.NETWORK))
            assertEquals(
                PaywallOperationState.Failed(PaywallOperationFailure.PURCHASE_NETWORK),
                viewModel.uiState.value.operation
            )
            viewModel.onPaywallClosed()
            viewModel.onPaywallOpened()
            assertEquals(PaywallOperationState.Idle, viewModel.uiState.value.operation)
            repository.completeOffering(OfferingLoadResult.Ready(validPackages))

            viewModel.restorePurchases()
            repository.publishTier(SubscriptionTier.PLUS)
            coordinator.completeRestore(RestoreResult.Restored(SubscriptionTier.PLUS))
            assertEquals(
                PaywallOperationState.RestoreSucceeded(SubscriptionTier.PLUS),
                viewModel.uiState.value.operation
            )
            viewModel.onPaywallClosed()
            viewModel.onPaywallOpened()
            assertEquals(PaywallOperationState.Idle, viewModel.uiState.value.operation)
        }

    @Test
    fun `close then reopen preserves in-flight commercial operation and late result`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val coordinator = FakePurchaseCoordinator()
            val viewModel = readyViewModel(repository, coordinator)

            viewModel.purchase(activity(), "plus_monthly")
            viewModel.onPaywallClosed()
            viewModel.onPaywallOpened()

            assertEquals(
                PaywallOperationState.Purchasing("plus_monthly"),
                viewModel.uiState.value.operation
            )
            assertEquals(1, coordinator.purchaseCalls)
            assertEquals(2, repository.refreshCalls)
            assertEquals(2, repository.offeringLoadCalls)

            repository.publishTier(SubscriptionTier.PLUS)
            coordinator.completePurchase(
                PurchaseResult.EntitlementActivated(SubscriptionTier.PLUS)
            )
            advanceUntilIdle()

            assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
            assertEquals(
                PaywallOperationState.PurchaseSucceeded(SubscriptionTier.PLUS),
                viewModel.uiState.value.operation
            )
        }

    @Test
    fun `ready offering maps exact Plus monthly and annual options`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)

        repository.completeOffering(OfferingLoadResult.Ready(validPackages))

        assertEquals(
            listOf(
                PaywallPurchaseOption(
                    packageIdentifier = "plus_monthly",
                    billingPeriod = SubscriptionBillingPeriod.MONTHLY,
                    localizedPrice = PLUS_MONTHLY_PRICE
                ),
                PaywallPurchaseOption(
                    packageIdentifier = "plus_annual",
                    billingPeriod = SubscriptionBillingPeriod.ANNUAL,
                    localizedPrice = PLUS_ANNUAL_PRICE
                )
            ),
            readyOptions(viewModel)
        )
    }

    @Test
    fun `validated ready offering defaults to Annual option from current options`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val viewModel = openedViewModel(repository)

            repository.completeOffering(OfferingLoadResult.Ready(validPackages))

            val annualOption = readyOptions(viewModel).single {
                it.billingPeriod == SubscriptionBillingPeriod.ANNUAL
            }
            assertEquals("plus_annual", annualOption.packageIdentifier)
            assertEquals(
                annualOption.packageIdentifier,
                viewModel.uiState.value.selectedPackageIdentifier
            )
        }

    @Test
    fun `user can select Monthly and ordinary updates keep that session selection`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val viewModel = openedViewModel(repository)
            repository.completeOffering(OfferingLoadResult.Ready(validPackages))

            viewModel.selectPackage("plus_monthly")
            repository.publish(
                SubscriptionState(
                    tier = SubscriptionTier.BASIC,
                    status = SubscriptionStatus.LOADING,
                    hasAuthoritativeCustomerInfo = false
                )
            )
            viewModel.onPaywallOpened()
            advanceUntilIdle()

            assertEquals("plus_monthly", viewModel.uiState.value.selectedPackageIdentifier)
            assertEquals(1, repository.offeringLoadCalls)
        }

    @Test
    fun `unknown package selection is rejected without replacing current selection`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository()
            val viewModel = openedViewModel(repository)
            repository.completeOffering(OfferingLoadResult.Ready(validPackages))

            viewModel.selectPackage("stale_package")

            assertEquals("plus_annual", viewModel.uiState.value.selectedPackageIdentifier)
        }

    @Test
    fun `localized Plus prices are preserved verbatim`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)

        repository.completeOffering(OfferingLoadResult.Ready(validPackages))

        assertEquals(
            listOf(PLUS_MONTHLY_PRICE, PLUS_ANNUAL_PRICE),
            readyOptions(viewModel).map(PaywallPurchaseOption::localizedPrice)
        )
    }

    @Test
    fun `Pro prices never enter paywall UI state`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)

        repository.completeOffering(OfferingLoadResult.Ready(validPackages))

        val ready = viewModel.uiState.value.offering as PaywallOfferingState.Ready
        assertFalse(ready.options.any { it.packageIdentifier.startsWith("pro_") })
        assertFalse(ready.options.any { it.localizedPrice == PRO_PRICE_SENTINEL })
        assertFalse(viewModel.uiState.value.toString().contains(PRO_PRICE_SENTINEL))
    }

    @Test
    fun `invalid offering clears stale purchase options`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)
        repository.completeOffering(OfferingLoadResult.Ready(validPackages))
        assertTrue(viewModel.uiState.value.offering is PaywallOfferingState.Ready)
        viewModel.selectPackage("plus_monthly")

        viewModel.retryOffering()
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
        repository.completeOffering(OfferingLoadResult.Invalid(setOf("plus_annual")))

        assertEquals(PaywallOfferingState.Invalid, viewModel.uiState.value.offering)
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
        viewModel.selectPackage("plus_monthly")
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
    }

    @Test
    fun `unavailable offering clears options and stays Basic safe`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)
        repository.completeOffering(OfferingLoadResult.Ready(validPackages))
        viewModel.selectPackage("plus_monthly")

        viewModel.retryOffering()
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
        repository.completeOffering(
            OfferingLoadResult.Unavailable(
                SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE
            )
        )

        assertEquals(
            PaywallOfferingState.Unavailable(PaywallUnavailableReason.OFFERING_UNAVAILABLE),
            viewModel.uiState.value.offering
        )
        assertEquals(SubscriptionTier.BASIC, viewModel.uiState.value.currentTier)
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)
    }

    @Test
    fun `retry explicitly requests a new offering`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val viewModel = openedViewModel(repository)
        repository.completeOffering(OfferingLoadResult.Invalid(setOf("plus_monthly")))

        viewModel.retryOffering()

        assertEquals(2, repository.offeringLoadCalls)
        assertEquals(PaywallOfferingState.Loading, viewModel.uiState.value.offering)
        assertEquals(null, viewModel.uiState.value.selectedPackageIdentifier)

        repository.completeOffering(OfferingLoadResult.Ready(validPackages))
        assertEquals("plus_annual", viewModel.uiState.value.selectedPackageIdentifier)
    }

    @Test
    fun `purchase accepts only a currently loaded Plus option`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)
        val activity = activity()

        viewModel.purchase(activity, "plus_monthly")
        assertEquals(0, coordinator.purchaseCalls)
        assertEquals(
            PaywallOperationState.Failed(PaywallOperationFailure.INVALID_SELECTION),
            viewModel.uiState.value.operation
        )

        viewModel.onPaywallOpened()
        repository.completeOffering(OfferingLoadResult.Ready(validPackages))
        viewModel.purchase(activity, "pro_monthly")
        assertEquals(0, coordinator.purchaseCalls)

        viewModel.purchase(activity, "plus_monthly")
        assertEquals(1, coordinator.purchaseCalls)
        assertEquals("plus_monthly", coordinator.lastPackageIdentifier)
        assertEquals(
            PaywallOperationState.Purchasing("plus_monthly"),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `busy purchase blocks purchase and restore actions`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)
        val activity = activity()

        viewModel.purchase(activity, "plus_monthly")
        viewModel.purchase(activity, "plus_annual")
        viewModel.restorePurchases()

        assertEquals(1, coordinator.purchaseCalls)
        assertEquals(0, coordinator.restoreCalls)
        assertEquals(
            PaywallOperationState.Purchasing("plus_monthly"),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `busy restore blocks restore and purchase actions`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.restorePurchases()
        viewModel.restorePurchases()
        viewModel.purchase(activity(), "plus_monthly")

        assertEquals(1, coordinator.restoreCalls)
        assertEquals(0, coordinator.purchaseCalls)
        assertEquals(PaywallOperationState.Restoring, viewModel.uiState.value.operation)
    }

    @Test
    fun `Plus purchase success reflects authoritative Plus state`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.purchase(activity(), "plus_monthly")
        repository.publishTier(SubscriptionTier.PLUS)
        coordinator.completePurchase(PurchaseResult.EntitlementActivated(SubscriptionTier.PLUS))
        advanceUntilIdle()

        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
        assertTrue(viewModel.uiState.value.hasAuthoritativeCustomerInfo)
        assertEquals(
            PaywallOperationState.PurchaseSucceeded(SubscriptionTier.PLUS),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `Pro purchase result remains representable`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.purchase(activity(), "plus_annual")
        repository.publishTier(SubscriptionTier.PRO)
        coordinator.completePurchase(PurchaseResult.EntitlementActivated(SubscriptionTier.PRO))
        advanceUntilIdle()

        assertEquals(SubscriptionTier.PRO, viewModel.uiState.value.currentTier)
        assertEquals(
            PaywallOperationState.PurchaseSucceeded(SubscriptionTier.PRO),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `purchase cancellation is neutral`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PLUS)
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.purchase(activity(), "plus_monthly")
        coordinator.completePurchase(PurchaseResult.Cancelled)

        assertEquals(PaywallOperationState.PurchaseCancelled, viewModel.uiState.value.operation)
        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
    }

    @Test
    fun `purchase typed failure maps to stable FitDesi reason`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PLUS)
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.purchase(activity(), "plus_annual")
        coordinator.completePurchase(PurchaseResult.Failed(PurchaseFailureReason.NETWORK))

        assertEquals(
            PaywallOperationState.Failed(PaywallOperationFailure.PURCHASE_NETWORK),
            viewModel.uiState.value.operation
        )
        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
    }

    @Test
    fun `purchase unavailable maps to stable FitDesi state`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PLUS)
        val coordinator = FakePurchaseCoordinator()
        val viewModel = readyViewModel(repository, coordinator)

        viewModel.purchase(activity(), "plus_monthly")
        coordinator.completePurchase(
            PurchaseResult.Unavailable(
                SubscriptionOperationUnavailableReason.PACKAGE_UNAVAILABLE
            )
        )

        assertEquals(
            PaywallOperationState.Unavailable(PaywallUnavailableReason.PACKAGE_UNAVAILABLE),
            viewModel.uiState.value.operation
        )
        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
    }

    @Test
    fun `restore Plus succeeds without offering readiness`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)

        viewModel.restorePurchases()
        repository.publishTier(SubscriptionTier.PLUS)
        coordinator.completeRestore(RestoreResult.Restored(SubscriptionTier.PLUS))
        advanceUntilIdle()

        assertEquals(0, repository.offeringLoadCalls)
        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
        assertEquals(
            PaywallOperationState.RestoreSucceeded(SubscriptionTier.PLUS),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `restore Pro succeeds`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)

        viewModel.restorePurchases()
        repository.publishTier(SubscriptionTier.PRO)
        coordinator.completeRestore(RestoreResult.Restored(SubscriptionTier.PRO))
        advanceUntilIdle()

        assertEquals(SubscriptionTier.PRO, viewModel.uiState.value.currentTier)
        assertEquals(
            PaywallOperationState.RestoreSucceeded(SubscriptionTier.PRO),
            viewModel.uiState.value.operation
        )
    }

    @Test
    fun `restore NothingActive is neutral and follows CustomerInfo truth`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PLUS)
            val coordinator = FakePurchaseCoordinator()
            val viewModel = viewModel(repository, coordinator)

            viewModel.restorePurchases()
            repository.publishTier(SubscriptionTier.BASIC)
            coordinator.completeRestore(RestoreResult.NothingActive)
            advanceUntilIdle()

            assertEquals(PaywallOperationState.NothingActive, viewModel.uiState.value.operation)
            assertEquals(SubscriptionTier.BASIC, viewModel.uiState.value.currentTier)
        }

    @Test
    fun `restore failure preserves authoritative tier`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PRO)
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)

        viewModel.restorePurchases()
        coordinator.completeRestore(RestoreResult.Failed(RestoreFailureReason.NETWORK))

        assertEquals(
            PaywallOperationState.Failed(PaywallOperationFailure.RESTORE_NETWORK),
            viewModel.uiState.value.operation
        )
        assertEquals(SubscriptionTier.PRO, viewModel.uiState.value.currentTier)
    }

    @Test
    fun `restore unavailable preserves authoritative tier`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PLUS)
        val coordinator = FakePurchaseCoordinator()
        val viewModel = viewModel(repository, coordinator)

        viewModel.restorePurchases()
        coordinator.completeRestore(
            RestoreResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            )
        )

        assertEquals(
            PaywallOperationState.Unavailable(PaywallUnavailableReason.SUBSCRIPTIONS_UNAVAILABLE),
            viewModel.uiState.value.operation
        )
        assertEquals(SubscriptionTier.PLUS, viewModel.uiState.value.currentTier)
    }

    @Test
    fun `commercial identity blocks map to account-safe paywall states`() = runTest(dispatcher) {
        val expectedStates = listOf(
            CommercialOperationBlockReason.ACCOUNT_REQUIRED to
                PaywallOperationState.AccountRequired,
            CommercialOperationBlockReason.VERIFICATION_REQUIRED to
                PaywallOperationState.VerificationRequired,
            CommercialOperationBlockReason.PREPARING_ACCOUNT to
                PaywallOperationState.PreparingAccount,
            CommercialOperationBlockReason.IDENTITY_UNAVAILABLE to
                PaywallOperationState.IdentityUnavailable
        )

        expectedStates.forEach { (reason, expected) ->
            val repository = FakeSubscriptionRepository()
            val coordinator = FakePurchaseCoordinator()
            val viewModel = viewModel(repository, coordinator)

            viewModel.restorePurchases()
            coordinator.completeRestore(RestoreResult.Blocked(reason))

            assertEquals(expected, viewModel.uiState.value.operation)
        }
    }

    @Test
    fun `authoritative paid state survives later offering and CustomerInfo errors`() =
        runTest(dispatcher) {
            val repository = FakeSubscriptionRepository(initialTier = SubscriptionTier.PRO)
            val viewModel = openedViewModel(repository)

            repository.completeOffering(
                OfferingLoadResult.Unavailable(
                    SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE
                )
            )
            repository.publish(
                SubscriptionState(
                    tier = SubscriptionTier.PRO,
                    status = SubscriptionStatus.ERROR,
                    hasAuthoritativeCustomerInfo = true
                )
            )
            advanceUntilIdle()

            assertEquals(SubscriptionTier.PRO, viewModel.uiState.value.currentTier)
            assertTrue(viewModel.uiState.value.hasAuthoritativeCustomerInfo)
            assertEquals(PaywallCustomerInfoStatus.ERROR, viewModel.uiState.value.customerInfoStatus)
            assertEquals(
                PaywallOfferingState.Unavailable(PaywallUnavailableReason.OFFERING_UNAVAILABLE),
                viewModel.uiState.value.offering
            )
        }

    @Test
    fun `Boost preparation and showing each require a deliberate request`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository()
        val boostRepository = FakeBoostRepository()
        val boostController = FakeBoostAdController()
        val viewModel = SubscriptionPaywallViewModel(
            application = application,
            subscriptionRepository = repository,
            purchaseCoordinator = FakePurchaseCoordinator(),
            boostRepository = boostRepository,
            boostAdController = boostController
        )

        viewModel.requestBoost(activity())
        assertEquals(1, boostController.prepareCalls)
        assertEquals(0, boostController.showCalls)
        assertEquals(BoostAccessState.Unknown, boostRepository.state.value)

        boostController.publish(BoostAdState.Ready)
        viewModel.requestBoost(activity())
        assertEquals(1, boostController.prepareCalls)
        assertEquals(1, boostController.showCalls)
        assertEquals(BoostAccessState.Unknown, boostRepository.state.value)
    }

    private fun viewModel(
        repository: FakeSubscriptionRepository,
        coordinator: FakePurchaseCoordinator = FakePurchaseCoordinator()
    ): SubscriptionPaywallViewModel = SubscriptionPaywallViewModel(
        application = application,
        subscriptionRepository = repository,
        purchaseCoordinator = coordinator
    )

    private fun openedViewModel(
        repository: FakeSubscriptionRepository,
        coordinator: FakePurchaseCoordinator = FakePurchaseCoordinator()
    ): SubscriptionPaywallViewModel = viewModel(repository, coordinator).also {
        it.onPaywallOpened()
    }

    private fun readyViewModel(
        repository: FakeSubscriptionRepository,
        coordinator: FakePurchaseCoordinator
    ): SubscriptionPaywallViewModel = openedViewModel(repository, coordinator).also {
        repository.completeOffering(OfferingLoadResult.Ready(validPackages))
    }

    private fun readyOptions(viewModel: SubscriptionPaywallViewModel): List<PaywallPurchaseOption> =
        (viewModel.uiState.value.offering as PaywallOfferingState.Ready).options

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private class FakeSubscriptionRepository(
        initialTier: SubscriptionTier = SubscriptionTier.BASIC
    ) : SubscriptionRepository {
        private val mutableState = MutableStateFlow(
            SubscriptionState(
                tier = initialTier,
                status = SubscriptionStatus.READY,
                hasAuthoritativeCustomerInfo = true
            )
        )
        override val state: StateFlow<SubscriptionState> = mutableState

        var refreshCalls = 0
            private set
        var offeringLoadCalls = 0
            private set
        private val offeringCallbacks = mutableListOf<(OfferingLoadResult) -> Unit>()

        override fun refreshCustomerInfo() {
            refreshCalls += 1
        }

        override fun loadCurrentOffering(callback: (OfferingLoadResult) -> Unit) {
            offeringLoadCalls += 1
            offeringCallbacks += callback
        }

        fun completeOffering(result: OfferingLoadResult) {
            offeringCallbacks.removeAt(0).invoke(result)
        }

        fun publishTier(tier: SubscriptionTier) {
            publish(
                SubscriptionState(
                    tier = tier,
                    status = SubscriptionStatus.READY,
                    hasAuthoritativeCustomerInfo = true
                )
            )
        }

        fun publish(state: SubscriptionState) {
            mutableState.value = state
        }
    }

    private class FakePurchaseCoordinator : SubscriptionPurchaseCoordinator {
        var purchaseCalls = 0
            private set
        var restoreCalls = 0
            private set
        var lastPackageIdentifier: String? = null
            private set
        private var purchaseCallback: ((PurchaseResult) -> Unit)? = null
        private var restoreCallback: ((RestoreResult) -> Unit)? = null

        override fun purchase(
            hostActivity: Activity,
            packageIdentifier: String,
            callback: (PurchaseResult) -> Unit
        ) {
            purchaseCalls += 1
            lastPackageIdentifier = packageIdentifier
            purchaseCallback = callback
        }

        override fun restorePurchases(callback: (RestoreResult) -> Unit) {
            restoreCalls += 1
            restoreCallback = callback
        }

        fun completePurchase(result: PurchaseResult) {
            val callback = checkNotNull(purchaseCallback)
            purchaseCallback = null
            callback(result)
        }

        fun completeRestore(result: RestoreResult) {
            val callback = checkNotNull(restoreCallback)
            restoreCallback = null
            callback(result)
        }
    }

    private class FakeBoostRepository : BoostRepository {
        private val mutableState = MutableStateFlow<BoostAccessState>(BoostAccessState.Unknown)
        override val state: StateFlow<BoostAccessState> = mutableState
        var refreshCalls = 0
        var reevaluateCalls = 0

        override fun refreshCustomerInfo() {
            refreshCalls += 1
        }

        override fun reevaluate() {
            reevaluateCalls += 1
        }
    }

    private class FakeBoostAdController : BoostAdController {
        private val mutableState = MutableStateFlow<BoostAdState>(BoostAdState.NeedsPreparation)
        override val state: StateFlow<BoostAdState> = mutableState
        override val privacyOptionsRequired: StateFlow<Boolean> = MutableStateFlow(false)
        var prepareCalls = 0
        var showCalls = 0

        override fun prepare(hostActivity: Activity) {
            prepareCalls += 1
        }

        override fun show(hostActivity: Activity) {
            showCalls += 1
        }

        override fun showPrivacyOptions(hostActivity: Activity) = Unit

        fun publish(state: BoostAdState) {
            mutableState.value = state
        }
    }

    companion object {
        private const val PLUS_MONTHLY_PRICE = "Rs\u00a01,249.00"
        private const val PLUS_ANNUAL_PRICE = "PKR 9,999/year"
        private const val PRO_PRICE_SENTINEL = "PRO-PRICE-MUST-NOT-LEAK"

        private val validPackages = listOf(
            SubscriptionPackage(
                packageIdentifier = "plus_monthly",
                productIdentifier = "fitdesi_plus_monthly",
                tier = SubscriptionTier.PLUS,
                billingPeriod = SubscriptionBillingPeriod.MONTHLY,
                localizedPrice = PLUS_MONTHLY_PRICE
            ),
            SubscriptionPackage(
                packageIdentifier = "plus_annual",
                productIdentifier = "fitdesi_plus_annual",
                tier = SubscriptionTier.PLUS,
                billingPeriod = SubscriptionBillingPeriod.ANNUAL,
                localizedPrice = PLUS_ANNUAL_PRICE
            ),
            SubscriptionPackage(
                packageIdentifier = "pro_monthly",
                productIdentifier = "fitdesi_pro_monthly",
                tier = SubscriptionTier.PRO,
                billingPeriod = SubscriptionBillingPeriod.MONTHLY,
                localizedPrice = PRO_PRICE_SENTINEL
            ),
            SubscriptionPackage(
                packageIdentifier = "pro_annual",
                productIdentifier = "fitdesi_pro_annual",
                tier = SubscriptionTier.PRO,
                billingPeriod = SubscriptionBillingPeriod.ANNUAL,
                localizedPrice = PRO_PRICE_SENTINEL
            )
        )
    }
}
