package com.example.boost

import com.example.subscription.CustomerInfoSnapshot
import com.example.subscription.CustomerInfoState
import com.example.subscription.CustomerInfoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class BoostRepositoryTest {
    private var now = 1_000L

    @Test
    fun `active Boost is reconstructed from authoritative CustomerInfo with expiration`() {
        val expiration = now + 30L * 60L * 1_000L
        val store = FakeCustomerInfoStore(
            CustomerInfoState.Authoritative(snapshot(setOf(FITDESI_BOOST_ENTITLEMENT), expiration))
        )

        val repository = CustomerInfoBoostRepository(store, nowMillis = { now }, timerScope = null)

        assertEquals(BoostAccessState.Active(expiration), repository.state.value)
    }

    @Test
    fun `authoritative CustomerInfo without Boost is inactive`() {
        val repository = CustomerInfoBoostRepository(
            FakeCustomerInfoStore(CustomerInfoState.Authoritative(snapshot(emptySet(), null))),
            nowMillis = { now },
            timerScope = null
        )

        assertEquals(BoostAccessState.Inactive, repository.state.value)
    }

    @Test
    fun `stale unknown and missing expiration fail closed`() {
        val activeSnapshot = snapshot(setOf(FITDESI_BOOST_ENTITLEMENT), now + 5_000L)
        assertEquals(
            BoostAccessState.Unknown,
            deriveBoostAccess(CustomerInfoState.Stale(activeSnapshot), now)
        )
        assertEquals(BoostAccessState.Unknown, deriveBoostAccess(CustomerInfoState.Error, now))
        assertEquals(
            BoostAccessState.Unknown,
            deriveBoostAccess(
                CustomerInfoState.Authoritative(
                    CustomerInfoSnapshot(setOf(FITDESI_BOOST_ENTITLEMENT), emptyMap())
                ),
                now
            )
        )
    }

    @Test
    fun `expiration reevaluation revokes Boost without creating local entitlement`() {
        val expiration = now + 1_000L
        val store = FakeCustomerInfoStore(
            CustomerInfoState.Authoritative(snapshot(setOf(FITDESI_BOOST_ENTITLEMENT), expiration))
        )
        val repository = CustomerInfoBoostRepository(store, nowMillis = { now }, timerScope = null)
        assertEquals(BoostAccessState.Active(expiration), repository.state.value)

        now = expiration
        repository.reevaluate()

        assertEquals(BoostAccessState.Inactive, repository.state.value)
        assertEquals(setOf(FITDESI_BOOST_ENTITLEMENT), store.snapshot().activeEntitlementIdentifiers)
    }

    @Test
    fun `refresh delegates to shared CustomerInfo store`() {
        val store = FakeCustomerInfoStore(CustomerInfoState.Loading)
        val repository = CustomerInfoBoostRepository(store, nowMillis = { now }, timerScope = null)

        repository.refreshCustomerInfo()

        assertEquals(1, store.refreshCalls)
        assertEquals(BoostAccessState.Unknown, repository.state.value)
    }

    private fun snapshot(entitlements: Set<String>, expiration: Long?) = CustomerInfoSnapshot(
        activeEntitlementIdentifiers = entitlements,
        entitlementExpirationsEpochMillis = if (expiration == null) {
            emptyMap()
        } else {
            mapOf(FITDESI_BOOST_ENTITLEMENT to expiration)
        }
    )

    private class FakeCustomerInfoStore(initial: CustomerInfoState) : CustomerInfoStore {
        private val mutableState = MutableStateFlow(initial)
        private val observers = mutableListOf<(CustomerInfoState) -> Unit>()
        override val state: StateFlow<CustomerInfoState> = mutableState
        var refreshCalls = 0

        override fun refresh() {
            refreshCalls += 1
        }

        override fun observe(observer: (CustomerInfoState) -> Unit) {
            observers += observer
            observer(mutableState.value)
        }

        fun snapshot(): CustomerInfoSnapshot =
            (mutableState.value as CustomerInfoState.Authoritative).snapshot
    }
}
