package com.example.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRepositoryTest {
    @Test
    fun disabledRepositoryIsStableBasic() {
        val repository = DisabledSubscriptionRepository()
        var offeringResult: OfferingLoadResult? = null

        assertEquals(
            SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.DISABLED,
                hasAuthoritativeCustomerInfo = false
            ),
            repository.state.value
        )

        repository.refreshCustomerInfo()
        repository.loadCurrentOffering { offeringResult = it }

        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertEquals(SubscriptionStatus.DISABLED, repository.state.value.status)
        assertEquals(
            OfferingLoadResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            ),
            offeringResult
        )
    }

    @Test
    fun customerInfoPlusUpdatesTierFromActiveEntitlements() {
        val client = FakeRevenueCatClient()
        val store = RevenueCatCustomerInfoStore(client)
        val repository = RevenueCatSubscriptionRepository(
            client = client,
            customerInfoStore = store
        )

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = snapshot(setOf("plus")),
            allowsPaidSubscriptions = true
        )

        assertEquals(SubscriptionTier.PLUS, repository.state.value.tier)
        assertEquals(SubscriptionStatus.READY, repository.state.value.status)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun customerInfoProWinsOverPlus() {
        val client = FakeRevenueCatClient()
        val store = RevenueCatCustomerInfoStore(client)
        val repository = RevenueCatSubscriptionRepository(
            client = client,
            customerInfoStore = store
        )

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = snapshot(setOf("plus", "pro")),
            allowsPaidSubscriptions = true
        )

        assertEquals(SubscriptionTier.PRO, repository.state.value.tier)
    }

    @Test
    fun customerInfoProIsPreservedWhenLaterRefreshFails() {
        val client = FakeRevenueCatClient()
        val store = RevenueCatCustomerInfoStore(client)
        val repository = RevenueCatSubscriptionRepository(
            client = client,
            customerInfoStore = store
        )

        store.beginIdentityTransition(generation = 1L)
        store.completeIdentityTransition(
            generation = 1L,
            snapshot = snapshot(setOf("pro")),
            allowsPaidSubscriptions = true
        )

        assertEquals(SubscriptionTier.PRO, repository.state.value.tier)
        assertEquals(SubscriptionStatus.READY, repository.state.value.status)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)

        repository.refreshCustomerInfo()
        client.completeCustomerInfo(Result.failure(IllegalStateException("offline")))

        assertEquals(SubscriptionTier.PRO, repository.state.value.tier)
        assertEquals(SubscriptionStatus.ERROR, repository.state.value.status)
        assertTrue(repository.state.value.hasAuthoritativeCustomerInfo)
    }

    @Test
    fun `pre reconciliation refresh cannot establish commercial authority`() {
        val client = FakeRevenueCatClient()
        val store = RevenueCatCustomerInfoStore(client)
        val repository = RevenueCatSubscriptionRepository(
            client = client,
            customerInfoStore = store
        )

        repository.refreshCustomerInfo()

        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
        assertEquals(SubscriptionStatus.LOADING, repository.state.value.status)
        assertFalse(repository.state.value.hasAuthoritativeCustomerInfo)
        assertEquals(0, client.customerInfoRequestCount)
    }

    @Test
    fun offeringLoadUsesCurrentOfferingAndHardValidation() {
        val client = FakeRevenueCatClient()
        val repository = RevenueCatSubscriptionRepository(client)
        var result: OfferingLoadResult? = null

        repository.loadCurrentOffering { result = it }
        client.completeOffering(
            Result.success(
                listOf(
                    StorePackageReference("plus_monthly", "fitdesi_plus_monthly", "Rs 850.00"),
                    StorePackageReference("plus_annual", "fitdesi_plus_annual", "Rs 7,100.00"),
                    StorePackageReference("pro_monthly", "fitdesi_pro_monthly", "Rs 1,950.00"),
                    StorePackageReference("pro_annual", "fitdesi_pro_annual", "Rs 17,100.00")
                )
            )
        )

        assertTrue(result is OfferingLoadResult.Ready)
    }

    @Test
    fun offeringFailureIsUnavailableAndDoesNotGrantPaidState() {
        val client = FakeRevenueCatClient()
        val repository = RevenueCatSubscriptionRepository(client)
        var result: OfferingLoadResult? = null

        repository.loadCurrentOffering { result = it }
        client.completeOffering(Result.failure(IllegalStateException("offline")))

        assertEquals(
            OfferingLoadResult.Unavailable(
                SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE
            ),
            result
        )
        assertEquals(SubscriptionTier.BASIC, repository.state.value.tier)
    }

    private class FakeRevenueCatClient : RevenueCatClient {
        private var customerInfoListener: ((CustomerInfoSnapshot) -> Unit)? = null
        private var customerInfoCallback: ((Result<CustomerInfoSnapshot>) -> Unit)? = null
        private var offeringCallback: ((Result<List<StorePackageReference>>) -> Unit)? = null
        var customerInfoRequestCount: Int = 0
            private set

        override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) {
            customerInfoListener = listener
        }

        override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
            customerInfoRequestCount += 1
            customerInfoCallback = callback
        }

        override fun getCurrentOffering(callback: (Result<List<StorePackageReference>>) -> Unit) {
            offeringCallback = callback
        }

        fun completeCustomerInfo(result: Result<CustomerInfoSnapshot>) {
            customerInfoCallback?.invoke(result)
                ?: customerInfoListener?.let { listener ->
                    result.getOrNull()?.let(listener)
                }
        }

        fun completeOffering(result: Result<List<StorePackageReference>>) {
            offeringCallback?.invoke(result)
        }
    }

    private companion object {
        fun snapshot(entitlements: Set<String>) = CustomerInfoSnapshot(
            activeEntitlementIdentifiers = entitlements,
            entitlementExpirationsEpochMillis = emptyMap()
        )
    }
}
