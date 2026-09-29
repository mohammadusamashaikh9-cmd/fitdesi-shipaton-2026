package com.example.subscription

import com.example.boost.BoostAccessState
import com.example.boost.CustomerInfoBoostRepository
import com.example.boost.FITDESI_BOOST_ENTITLEMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerInfoStoreTest {
    @Test
    fun `store starts loading without authoritative CustomerInfo`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )

        assertEquals(CustomerInfoState.Loading, store.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertFalse(subscription.state.value.hasAuthoritativeCustomerInfo)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
        assertEquals(0, provider.listenerCount)
    }

    @Test
    fun `cached CustomerInfo and refresh before first transition cannot publish authority`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        val cached = CustomerInfoSnapshot(
            activeEntitlementIdentifiers = setOf(
                "plus",
                "pro",
                FITDESI_BOOST_ENTITLEMENT
            ),
            entitlementExpirationsEpochMillis = mapOf(
                FITDESI_BOOST_ENTITLEMENT to 31_000L
            )
        )

        provider.emit(cached)
        store.refresh()
        val completedGenerationZero = store.completeIdentityTransition(
            generation = 0L,
            snapshot = cached,
            allowsPaidSubscriptions = true
        )

        assertFalse(completedGenerationZero)
        assertEquals(0, provider.refreshRequestCount)
        assertEquals(CustomerInfoState.Loading, store.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertFalse(subscription.state.value.hasAuthoritativeCustomerInfo)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
    }

    @Test
    fun `first completed identity transition supplies subscription and Boost truth`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val expiration = 31_000L
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf("plus", FITDESI_BOOST_ENTITLEMENT),
                entitlementExpirationsEpochMillis = mapOf(FITDESI_BOOST_ENTITLEMENT to expiration)
            ),
            allowsPaidSubscriptions = true
        )

        assertEquals(SubscriptionTier.PLUS, subscription.state.value.tier)
        assertTrue(subscription.state.value.hasAuthoritativeCustomerInfo)
        assertEquals(BoostAccessState.Active(expiration), boost.state.value)
    }

    @Test
    fun `refresh failure marks prior snapshot stale and Boost fails closed`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf(FITDESI_BOOST_ENTITLEMENT),
                entitlementExpirationsEpochMillis = mapOf(FITDESI_BOOST_ENTITLEMENT to 31_000L)
            ),
            allowsPaidSubscriptions = true
        )
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)

        store.refresh()
        provider.completeRefresh(Result.failure(IllegalStateException("offline")))

        assertTrue(store.state.value is CustomerInfoState.Stale)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
    }

    @Test
    fun `identity transition clears paid and Boost presentation before reconciliation`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf("plus", FITDESI_BOOST_ENTITLEMENT),
                entitlementExpirationsEpochMillis = mapOf(
                    FITDESI_BOOST_ENTITLEMENT to 31_000L
                )
            ),
            allowsPaidSubscriptions = true
        )

        store.beginIdentityTransition(generation = 2L)

        assertEquals(CustomerInfoState.Loading, store.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
    }

    @Test
    fun `old generation listener and refresh callbacks cannot republish stale state`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf("plus"),
                entitlementExpirationsEpochMillis = emptyMap()
            ),
            allowsPaidSubscriptions = true
        )
        store.refresh()
        store.beginIdentityTransition(generation = 2L)
        provider.emitFromListener(
            index = 0,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf("plus"),
                entitlementExpirationsEpochMillis = emptyMap()
            )
        )
        provider.completeRefresh(
            index = 0,
            result = Result.success(
                CustomerInfoSnapshot(
                    activeEntitlementIdentifiers = setOf("pro"),
                    entitlementExpirationsEpochMillis = emptyMap()
                )
            )
        )

        assertEquals(CustomerInfoState.Loading, store.state.value)
    }

    @Test
    fun `anonymous CustomerInfo can authorize Boost without activating paid subscription`() {
        val provider = FakeCustomerInfoProvider()
        val store = RevenueCatCustomerInfoStore(provider)
        val subscription = RevenueCatSubscriptionRepository(
            client = FakeRevenueCatClient(provider),
            customerInfoStore = store
        )
        val boost = CustomerInfoBoostRepository(
            store,
            nowMillis = { 1_000L },
            timerScope = null
        )

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = CustomerInfoSnapshot(
                activeEntitlementIdentifiers = setOf(
                    "pro",
                    FITDESI_BOOST_ENTITLEMENT
                ),
                entitlementExpirationsEpochMillis = mapOf(
                    FITDESI_BOOST_ENTITLEMENT to 31_000L
                )
            ),
            allowsPaidSubscriptions = false
        )

        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertTrue(subscription.state.value.hasAuthoritativeCustomerInfo)
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)
    }

    private class FakeRevenueCatClient(
        private val customerInfoProvider: FakeCustomerInfoProvider
    ) : RevenueCatClient {
        override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) {
            customerInfoProvider.setCustomerInfoListener(listener)
        }

        override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
            customerInfoProvider.getCustomerInfo(callback)
        }

        override fun getCurrentOffering(
            callback: (Result<List<StorePackageReference>>) -> Unit
        ) = Unit
    }

    private class FakeCustomerInfoProvider : CustomerInfoProvider {
        private val listeners = mutableListOf<(CustomerInfoSnapshot) -> Unit>()
        private val refreshCallbacks = mutableListOf<(Result<CustomerInfoSnapshot>) -> Unit>()

        val listenerCount: Int
            get() = listeners.size
        val refreshRequestCount: Int
            get() = refreshCallbacks.size

        override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) {
            listeners += listener
        }

        override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
            refreshCallbacks += callback
        }

        fun emit(snapshot: CustomerInfoSnapshot) {
            listeners.lastOrNull()?.invoke(snapshot)
        }

        fun emitFromListener(index: Int, snapshot: CustomerInfoSnapshot) {
            listeners[index](snapshot)
        }

        fun completeRefresh(result: Result<CustomerInfoSnapshot>) {
            refreshCallbacks.lastOrNull()?.invoke(result)
        }

        fun completeRefresh(index: Int, result: Result<CustomerInfoSnapshot>) {
            refreshCallbacks[index](result)
        }
    }
}
