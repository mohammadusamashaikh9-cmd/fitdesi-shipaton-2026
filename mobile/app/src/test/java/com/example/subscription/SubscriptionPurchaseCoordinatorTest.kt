package com.example.subscription

import android.app.Activity
import com.example.identity.AuthSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionPurchaseCoordinatorTest {
    @Test
    fun stage10BPurchaseAllowlistPermitsOnlyPlusPackages() {
        assertTrue(Stage10BPurchasePolicy.isPurchasable("plus_monthly"))
        assertTrue(Stage10BPurchasePolicy.isPurchasable("plus_annual"))
        assertFalse(Stage10BPurchasePolicy.isPurchasable("pro_monthly"))
        assertFalse(Stage10BPurchasePolicy.isPurchasable("pro_annual"))
        assertFalse(Stage10BPurchasePolicy.isPurchasable("unknown"))
    }

    @Test
    fun disabledCoordinatorRestoreIsUnavailable() {
        val coordinator = DisabledSubscriptionPurchaseCoordinator()
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }

        assertEquals(
            RestoreResult.Unavailable(SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED),
            result
        )
    }

    @Test
    fun disabledCoordinatorPurchaseIsUnavailable() {
        val coordinator = DisabledSubscriptionPurchaseCoordinator()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }

        assertEquals(
            PurchaseResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            ),
            result
        )
    }

    @Test
    fun plusPurchaseAppliesAuthoritativeCustomerInfoToRepositoryState() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }
        purchaseClient.completePurchase(
            RevenueCatPurchaseClientResult.CustomerInfo(snapshot(setOf("plus")))
        )

        assertEquals("plus_monthly", purchaseClient.requestedPackageIdentifier)
        assertEquals(PurchaseResult.EntitlementActivated(SubscriptionTier.PLUS), result)
        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun purchaseWithoutPaidEntitlementFailsClosedToBasic() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_annual") { result = it }
        purchaseClient.completePurchase(
            RevenueCatPurchaseClientResult.CustomerInfo(snapshot(emptySet()))
        )

        assertEquals(
            PurchaseResult.Failed(PurchaseFailureReason.ENTITLEMENT_NOT_ACTIVATED),
            result
        )
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun purchaseCancellationPreservesAuthoritativeTier() {
        val commercial = CommercialFixture(setOf("plus"))
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }
        purchaseClient.completePurchase(RevenueCatPurchaseClientResult.Cancelled)

        assertEquals(PurchaseResult.Cancelled, result)
        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun purchaseFailureReturnsTypedReasonAndPreservesAuthoritativeTier() {
        val commercial = CommercialFixture(setOf("plus"))
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_annual") { result = it }
        purchaseClient.completePurchase(
            RevenueCatPurchaseClientResult.Failed(PurchaseFailureReason.NETWORK)
        )

        assertEquals(PurchaseResult.Failed(PurchaseFailureReason.NETWORK), result)
        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun unavailablePurchasePackageReturnsTypedUnavailableAndPreservesTier() {
        val commercial = CommercialFixture(setOf("plus"))
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }
        purchaseClient.completePurchase(
            RevenueCatPurchaseClientResult.Unavailable(
                SubscriptionOperationUnavailableReason.PACKAGE_UNAVAILABLE
            )
        )

        assertEquals(
            PurchaseResult.Unavailable(
                SubscriptionOperationUnavailableReason.PACKAGE_UNAVAILABLE
            ),
            result
        )
        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
    }

    @Test
    fun proPurchaseIsRejectedBeforeRevenueCatExecution() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "pro_monthly") { result = it }

        assertEquals(
            PurchaseResult.Failed(PurchaseFailureReason.INVALID_PACKAGE),
            result
        )
        assertEquals(null, purchaseClient.requestedPackageIdentifier)
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
    }

    @Test
    fun restorePlusAppliesAuthoritativeCustomerInfoToRepositoryState() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }
        purchaseClient.completeRestore(
            RevenueCatRestoreClientResult.CustomerInfo(snapshot(setOf("plus")))
        )

        assertEquals(RestoreResult.Restored(SubscriptionTier.PLUS), result)
        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun restoreWithoutPaidEntitlementDowngradesToBasicAndReturnsNothingActive() {
        val commercial = CommercialFixture(setOf("plus"))
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }
        purchaseClient.completeRestore(
            RevenueCatRestoreClientResult.CustomerInfo(snapshot(emptySet()))
        )

        assertEquals(RestoreResult.NothingActive, result)
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun restoreFailureReturnsTypedReasonAndPreservesAuthoritativeTier() {
        val commercial = CommercialFixture(setOf("pro"))
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = createCoordinator(
            client = purchaseClient,
            commercial = commercial
        )
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }
        purchaseClient.completeRestore(
            RevenueCatRestoreClientResult.Failed(RestoreFailureReason.NETWORK)
        )

        assertEquals(RestoreResult.Failed(RestoreFailureReason.NETWORK), result)
        assertEquals(SubscriptionTier.PRO, repository.state.value.tier)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun `guest purchase is blocked before RevenueCat execution`() {
        val commercial = CommercialFixture()
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = RevenueCatSubscriptionPurchaseCoordinator(
            client = purchaseClient,
            identityCoordinator = commercial.identity,
            authSessionProvider = { AuthSessionState.Guest }
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }

        assertEquals(
            PurchaseResult.Blocked(CommercialOperationBlockReason.ACCOUNT_REQUIRED),
            result
        )
        assertEquals(null, purchaseClient.requestedPackageIdentifier)
    }

    @Test
    fun `unverified restore is blocked before RevenueCat execution`() {
        val commercial = CommercialFixture()
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val coordinator = RevenueCatSubscriptionPurchaseCoordinator(
            client = purchaseClient,
            identityCoordinator = commercial.identity,
            authSessionProvider = {
                AuthSessionState.Authenticated(
                    uid = "firebaseUidA",
                    email = "person@example.com",
                    emailVerified = false
                )
            }
        )
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }

        assertEquals(
            RestoreResult.Blocked(CommercialOperationBlockReason.VERIFICATION_REQUIRED),
            result
        )
        assertEquals(0, purchaseClient.restoreRequestCount)
    }

    @Test
    fun `purchase callback from stale identity generation is ignored`() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val identity = commercial.identity
        val coordinator = RevenueCatSubscriptionPurchaseCoordinator(
            client = purchaseClient,
            identityCoordinator = identity,
            authSessionProvider = { verifiedSession("firebaseUidA") }
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var result: PurchaseResult? = null

        coordinator.purchase(activity, "plus_monthly") { result = it }
        identity.transitionTo("fd_firebaseUidB", generation = 2L)
        purchaseClient.completePurchase(
            RevenueCatPurchaseClientResult.CustomerInfo(snapshot(setOf("plus")))
        )

        assertEquals(
            PurchaseResult.Blocked(CommercialOperationBlockReason.IDENTITY_UNAVAILABLE),
            result
        )
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertFalse(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun `restore callback from stale identity generation is ignored`() {
        val commercial = CommercialFixture()
        val repository = commercial.repository
        val purchaseClient = FakeRevenueCatPurchaseClient()
        val identity = commercial.identity
        val coordinator = RevenueCatSubscriptionPurchaseCoordinator(
            client = purchaseClient,
            identityCoordinator = identity,
            authSessionProvider = { verifiedSession("firebaseUidA") }
        )
        var result: RestoreResult? = null

        coordinator.restorePurchases { result = it }
        identity.transitionTo("fd_firebaseUidB", generation = 2L)
        purchaseClient.completeRestore(
            RevenueCatRestoreClientResult.CustomerInfo(snapshot(setOf("pro")))
        )

        assertEquals(
            RestoreResult.Blocked(CommercialOperationBlockReason.IDENTITY_UNAVAILABLE),
            result
        )
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertFalse(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    private class FakeRevenueCatClient : RevenueCatClient {
        override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) = Unit

        override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) = Unit

        override fun getCurrentOffering(
            callback: (Result<List<StorePackageReference>>) -> Unit
        ) = Unit
    }

    private class FakeRevenueCatPurchaseClient : RevenueCatPurchaseClient {
        var requestedPackageIdentifier: String? = null
            private set

        private var purchaseCallback: ((RevenueCatPurchaseClientResult) -> Unit)? = null
        private var restoreCallback: ((RevenueCatRestoreClientResult) -> Unit)? = null
        var restoreRequestCount: Int = 0
            private set

        override fun purchase(
            hostActivity: Activity,
            packageIdentifier: String,
            callback: (RevenueCatPurchaseClientResult) -> Unit
        ) {
            requestedPackageIdentifier = packageIdentifier
            purchaseCallback = callback
        }

        fun completePurchase(result: RevenueCatPurchaseClientResult) {
            purchaseCallback?.invoke(result)
        }

        override fun restorePurchases(callback: (RevenueCatRestoreClientResult) -> Unit) {
            restoreRequestCount += 1
            restoreCallback = callback
        }

        fun completeRestore(result: RevenueCatRestoreClientResult) {
            restoreCallback?.invoke(result)
        }
    }

    private class FakeCommercialIdentityCoordinator(
        private val customerInfoStore: MutableCustomerInfoStore
    ) : RevenueCatCommercialIdentityCoordinator {
        private val mutableState = MutableStateFlow<RevenueCatIdentityState>(
            RevenueCatIdentityState.IdentifiedReady(1L, "fd_firebaseUidA")
        )
        override val state: StateFlow<RevenueCatIdentityState> = mutableState

        private var currentIdentity = RevenueCatOperationIdentity(1L, "fd_firebaseUidA")

        override fun reconcile(session: AuthSessionState) = Unit

        override fun retry() = Unit

        override fun invalidateBeforeAuthExit() = Unit

        override fun operationIdentity(
            session: AuthSessionState
        ): RevenueCatOperationIdentity? = when (val gate = revenueCatOperationGate(
            session = session,
            identityState = mutableState.value
        )) {
            is RevenueCatOperationGate.Allowed -> gate.identity
            else -> null
        }

        override fun acceptOperationCustomerInfo(
            identity: RevenueCatOperationIdentity,
            snapshot: CustomerInfoSnapshot
        ): SubscriptionTier? {
            if (identity != currentIdentity) return null
            if (!customerInfoStore.acceptAuthoritative(identity.generation, snapshot)) return null
            return SubscriptionTierResolver.resolve(snapshot.activeEntitlementIdentifiers)
        }

        fun transitionTo(appUserId: String, generation: Long) {
            customerInfoStore.beginIdentityTransition(generation)
            currentIdentity = RevenueCatOperationIdentity(generation, appUserId)
            mutableState.value = RevenueCatIdentityState.IdentifiedReady(
                generation = generation,
                appUserId = appUserId
            )
        }
    }

    private class CommercialFixture(
        initialEntitlements: Set<String> = emptySet()
    ) {
        private val client = FakeRevenueCatClient()
        private val customerInfoStore = RevenueCatCustomerInfoStore(client)
        val repository = RevenueCatSubscriptionRepository(client, customerInfoStore)
        val identity = FakeCommercialIdentityCoordinator(customerInfoStore)

        init {
            customerInfoStore.beginIdentityTransition(generation = 1L)
            check(
                customerInfoStore.completeIdentityTransition(
                    generation = 1L,
                    snapshot = snapshot(initialEntitlements),
                    allowsPaidSubscriptions = true
                )
            )
        }
    }

    private companion object {
        fun createCoordinator(
            client: RevenueCatPurchaseClient,
            commercial: CommercialFixture
        ): RevenueCatSubscriptionPurchaseCoordinator =
            RevenueCatSubscriptionPurchaseCoordinator(
                client = client,
                identityCoordinator = commercial.identity,
                authSessionProvider = { verifiedSession("firebaseUidA") }
            )

        fun verifiedSession(uid: String) = AuthSessionState.Authenticated(
            uid = uid,
            email = "person@example.com",
            emailVerified = true
        )

        fun snapshot(entitlements: Set<String>) = CustomerInfoSnapshot(
            activeEntitlementIdentifiers = entitlements,
            entitlementExpirationsEpochMillis = emptyMap()
        )
    }
}
