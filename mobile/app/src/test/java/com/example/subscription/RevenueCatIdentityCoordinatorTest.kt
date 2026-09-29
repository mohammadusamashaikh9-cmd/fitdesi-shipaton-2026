package com.example.subscription

import com.example.identity.AuthSessionState
import com.example.boost.BoostAccessState
import com.example.boost.CustomerInfoBoostRepository
import com.example.boost.FITDESI_BOOST_ENTITLEMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueCatIdentityCoordinatorTest {
    @Test
    fun `guest and unverified sessions target anonymous RevenueCat ownership`() {
        assertEquals(
            RevenueCatIdentityTarget.Anonymous,
            revenueCatIdentityTarget(AuthSessionState.Guest)
        )
        assertEquals(
            RevenueCatIdentityTarget.Anonymous,
            revenueCatIdentityTarget(
                AuthSessionState.Authenticated(
                    uid = "firebaseUidA",
                    email = "person@example.com",
                    emailVerified = false
                )
            )
        )
    }

    @Test
    fun `verified Firebase accounts map exactly to non PII RevenueCat IDs`() {
        assertEquals(
            RevenueCatIdentityTarget.Identified("fd_firebaseUidA"),
            revenueCatIdentityTarget(
                AuthSessionState.Authenticated(
                    uid = "firebaseUidA",
                    email = "first@example.com",
                    emailVerified = true
                )
            )
        )
        assertEquals(
            RevenueCatIdentityTarget.Identified("fd_firebaseUidB"),
            revenueCatIdentityTarget(
                AuthSessionState.Authenticated(
                    uid = "firebaseUidB",
                    email = "same@example.com",
                    emailVerified = true
                )
            )
        )
    }

    @Test
    fun `unsafe or overlength verified Firebase UID fails closed`() {
        val unsafe = revenueCatIdentityTarget(
            AuthSessionState.Authenticated(
                uid = "uid/with whitespace",
                email = "person@example.com",
                emailVerified = true
            )
        )
        val overlength = revenueCatIdentityTarget(
            AuthSessionState.Authenticated(
                uid = "a".repeat(98),
                email = "person@example.com",
                emailVerified = true
            )
        )

        assertTrue(unsafe is RevenueCatIdentityTarget.Invalid)
        assertTrue(overlength is RevenueCatIdentityTarget.Invalid)
    }

    @Test
    fun `guest startup verifies anonymous SDK CustomerInfo before becoming ready`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )
        val boost = CustomerInfoBoostRepository(
            fixture.store,
            nowMillis = { 1_000L },
            timerScope = null
        )

        fixture.coordinator.reconcile(AuthSessionState.Guest)

        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.Reconciling)
        assertEquals(1, fixture.client.customerInfoRequests.size)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
        fixture.client.completeCustomerInfo(
            index = 0,
            result = Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.AnonymousReady)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)
        assertFalse(
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .allowsPaidSubscriptions
        )
    }

    @Test
    fun `guest startup logs out cached identified SDK before accepting anonymous state`() {
        val fixture = identityFixture(appUserId = "fd_oldAccount", anonymous = false)

        fixture.coordinator.reconcile(AuthSessionState.Guest)

        assertEquals(1, fixture.client.logoutCallbacks.size)
        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.Reconciling)
        fixture.client.completeLogout(
            index = 0,
            result = Result.success(snapshot(setOf("pro")))
        )

        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.AnonymousReady)
        assertFalse(
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .allowsPaidSubscriptions
        )
    }

    @Test
    fun `verified startup identifies account before publishing paid CustomerInfo`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val session = verifiedSession("firebaseUidA")
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )

        fixture.coordinator.reconcile(session)

        assertEquals(listOf("fd_firebaseUidA"), fixture.client.loginAppUserIds)
        assertEquals(CustomerInfoState.Loading, fixture.store.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        fixture.client.completeLogin(
            index = 0,
            result = Result.success(snapshot(setOf("plus")))
        )

        assertEquals(
            RevenueCatIdentityState.IdentifiedReady(
                generation = 1L,
                appUserId = "fd_firebaseUidA"
            ),
            fixture.coordinator.state.value
        )
        assertEquals(
            setOf("plus"),
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .snapshot.activeEntitlementIdentifiers
        )
        assertEquals(SubscriptionTier.PLUS, subscription.state.value.tier)
    }

    @Test
    fun `account switch invalidates A and logs directly into B without anonymous hop`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        fixture.coordinator.reconcile(verifiedSession("firebaseUidA"))
        fixture.client.completeLogin(0, Result.success(snapshot(setOf("plus"))))

        fixture.coordinator.reconcile(verifiedSession("firebaseUidB"))

        assertEquals(CustomerInfoState.Loading, fixture.store.state.value)
        assertEquals(
            listOf("fd_firebaseUidA", "fd_firebaseUidB"),
            fixture.client.loginAppUserIds
        )
        assertTrue(fixture.client.logoutCallbacks.isEmpty())

        fixture.client.repeatLoginCallback(0, Result.success(snapshot(setOf("plus"))))
        assertEquals(CustomerInfoState.Loading, fixture.store.state.value)
        fixture.client.completeLogin(1, Result.success(snapshot(setOf("pro"))))

        assertEquals(
            RevenueCatIdentityState.IdentifiedReady(2L, "fd_firebaseUidB"),
            fixture.coordinator.state.value
        )
        assertEquals(
            setOf("pro"),
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .snapshot.activeEntitlementIdentifiers
        )
    }

    @Test
    fun `account to guest accepts current anonymous Boost but never anonymous paid tier`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )
        val boost = CustomerInfoBoostRepository(
            fixture.store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        fixture.coordinator.reconcile(verifiedSession("firebaseUidA"))
        fixture.client.completeLogin(
            0,
            Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        fixture.coordinator.reconcile(AuthSessionState.Guest)

        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)

        fixture.client.completeLogout(
            index = 0,
            result = Result.success(snapshot(setOf("pro", FITDESI_BOOST_ENTITLEMENT)))
        )

        assertEquals(RevenueCatIdentityState.AnonymousReady(2L), fixture.coordinator.state.value)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)
        assertFalse(
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .allowsPaidSubscriptions
        )
    }

    @Test
    fun `account to guest to same account restores authoritative paid and Boost state`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )
        val boost = CustomerInfoBoostRepository(
            fixture.store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        val accountA = verifiedSession("firebaseUidA")

        fixture.coordinator.reconcile(accountA)
        fixture.client.completeLogin(
            index = 0,
            result = Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        assertEquals(SubscriptionTier.PLUS, subscription.state.value.tier)
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)

        fixture.coordinator.reconcile(AuthSessionState.Guest)

        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
        fixture.client.completeLogout(
            index = 0,
            result = Result.success(snapshot(emptySet()))
        )
        assertEquals(RevenueCatIdentityState.AnonymousReady(2L), fixture.coordinator.state.value)

        fixture.coordinator.reconcile(accountA)

        assertEquals(RevenueCatIdentityState.Reconciling(3L), fixture.coordinator.state.value)
        assertEquals(
            listOf("fd_firebaseUidA", "fd_firebaseUidA"),
            fixture.client.loginAppUserIds
        )
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
        fixture.client.completeLogin(
            index = 1,
            result = Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        assertEquals("fd_firebaseUidA", fixture.client.appUserId)
        assertFalse(fixture.client.isAnonymous)
        assertEquals(
            RevenueCatIdentityState.IdentifiedReady(3L, "fd_firebaseUidA"),
            fixture.coordinator.state.value
        )
        assertEquals(SubscriptionTier.PLUS, subscription.state.value.tier)
        assertEquals(BoostAccessState.Active(31_000L), boost.state.value)
        assertEquals(
            setOf("plus", FITDESI_BOOST_ENTITLEMENT),
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .snapshot.activeEntitlementIdentifiers
        )
    }

    @Test
    fun `account to guest to B serializes SDK mutations and publishes only B`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )
        val boost = CustomerInfoBoostRepository(
            fixture.store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        fixture.coordinator.reconcile(verifiedSession("firebaseUidA"))
        fixture.client.completeLogin(
            0,
            Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        fixture.coordinator.reconcile(AuthSessionState.Guest)
        fixture.coordinator.reconcile(verifiedSession("firebaseUidB"))

        assertEquals(RevenueCatIdentityState.Reconciling(3L), fixture.coordinator.state.value)
        assertEquals(listOf("fd_firebaseUidA"), fixture.client.loginAppUserIds)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)

        fixture.client.completeLogout(
            index = 0,
            result = Result.success(snapshot(setOf("pro", FITDESI_BOOST_ENTITLEMENT)))
        )

        assertEquals(CustomerInfoState.Loading, fixture.store.state.value)
        assertEquals(
            listOf("fd_firebaseUidA", "fd_firebaseUidB"),
            fixture.client.loginAppUserIds
        )
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)

        fixture.client.completeLogin(1, Result.success(snapshot(setOf("plus"))))

        assertEquals("fd_firebaseUidB", fixture.client.appUserId)
        assertFalse(fixture.client.isAnonymous)
        assertEquals(
            RevenueCatIdentityState.IdentifiedReady(3L, "fd_firebaseUidB"),
            fixture.coordinator.state.value
        )
        assertEquals(SubscriptionTier.PLUS, subscription.state.value.tier)
        assertEquals(BoostAccessState.Inactive, boost.state.value)
        assertEquals(
            setOf("plus"),
            (fixture.store.state.value as CustomerInfoState.Authoritative)
                .snapshot.activeEntitlementIdentifiers
        )
    }

    @Test
    fun `account to guest logout failure remains Basic and Boost Unknown`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)
        val subscription = RevenueCatSubscriptionRepository(
            client = fixture.client,
            customerInfoStore = fixture.store
        )
        val boost = CustomerInfoBoostRepository(
            fixture.store,
            nowMillis = { 1_000L },
            timerScope = null
        )
        fixture.coordinator.reconcile(verifiedSession("firebaseUidA"))
        fixture.client.completeLogin(
            0,
            Result.success(snapshot(setOf("plus", FITDESI_BOOST_ENTITLEMENT)))
        )

        fixture.coordinator.reconcile(AuthSessionState.Guest)

        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
        fixture.client.completeLogout(
            index = 0,
            result = Result.failure(IllegalStateException("offline"))
        )

        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.Error)
        assertEquals(SubscriptionTier.BASIC, subscription.state.value.tier)
        assertEquals(BoostAccessState.Unknown, boost.state.value)
    }

    @Test
    fun `invalid verified identity fails closed without calling RevenueCat`() {
        val fixture = identityFixture(appUserId = "anonymous-id", anonymous = true)

        fixture.coordinator.reconcile(
            AuthSessionState.Authenticated(
                uid = "unsafe/uid",
                email = "person@example.com",
                emailVerified = true
            )
        )

        assertTrue(fixture.coordinator.state.value is RevenueCatIdentityState.Error)
        assertEquals(CustomerInfoState.Error, fixture.store.state.value)
        assertTrue(fixture.client.loginAppUserIds.isEmpty())
        assertTrue(fixture.client.logoutCallbacks.isEmpty())
        assertTrue(fixture.client.customerInfoRequests.isEmpty())
    }

    private fun identityFixture(
        appUserId: String,
        anonymous: Boolean
    ): IdentityFixture {
        val client = FakeRevenueCatIdentityClient(appUserId, anonymous)
        val store = RevenueCatCustomerInfoStore(client)
        return IdentityFixture(
            client = client,
            store = store,
            coordinator = DefaultRevenueCatIdentityCoordinator(client, store)
        )
    }

    private data class IdentityFixture(
        val client: FakeRevenueCatIdentityClient,
        val store: RevenueCatCustomerInfoStore,
        val coordinator: DefaultRevenueCatIdentityCoordinator
    )

    private class FakeRevenueCatIdentityClient(
        initialAppUserId: String,
        initialAnonymous: Boolean
    ) : RevenueCatIdentityClient, RevenueCatClient {
        override var appUserId: String = initialAppUserId
            private set
        override var isAnonymous: Boolean = initialAnonymous
            private set

        val loginAppUserIds = mutableListOf<String>()
        val logoutCallbacks = mutableListOf<(Result<CustomerInfoSnapshot>) -> Unit>()
        val customerInfoRequests = mutableListOf<(Result<CustomerInfoSnapshot>) -> Unit>()
        private val loginCallbacks = mutableListOf<(Result<CustomerInfoSnapshot>) -> Unit>()

        override fun logIn(
            appUserId: String,
            callback: (Result<CustomerInfoSnapshot>) -> Unit
        ) {
            loginAppUserIds += appUserId
            loginCallbacks += callback
        }

        override fun logOut(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
            logoutCallbacks += callback
        }

        override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) = Unit

        override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
            customerInfoRequests += callback
        }

        override fun getCurrentOffering(
            callback: (Result<List<StorePackageReference>>) -> Unit
        ) = Unit

        fun completeLogin(index: Int, result: Result<CustomerInfoSnapshot>) {
            if (result.isSuccess) {
                appUserId = loginAppUserIds[index]
                isAnonymous = false
            }
            loginCallbacks[index](result)
        }

        fun repeatLoginCallback(index: Int, result: Result<CustomerInfoSnapshot>) {
            loginCallbacks[index](result)
        }

        fun completeLogout(index: Int, result: Result<CustomerInfoSnapshot>) {
            if (result.isSuccess) {
                appUserId = "fresh-anonymous-$index"
                isAnonymous = true
            }
            logoutCallbacks[index](result)
        }

        fun completeCustomerInfo(index: Int, result: Result<CustomerInfoSnapshot>) {
            customerInfoRequests[index](result)
        }
    }

    private companion object {
        fun verifiedSession(uid: String) = AuthSessionState.Authenticated(
            uid = uid,
            email = "person@example.com",
            emailVerified = true
        )

        fun snapshot(entitlements: Set<String>) = CustomerInfoSnapshot(
            activeEntitlementIdentifiers = entitlements,
            entitlementExpirationsEpochMillis = if (
                FITDESI_BOOST_ENTITLEMENT in entitlements
            ) {
                mapOf(FITDESI_BOOST_ENTITLEMENT to 31_000L)
            } else {
                emptyMap()
            }
        )
    }
}
