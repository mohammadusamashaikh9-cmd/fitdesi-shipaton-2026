package com.example.subscription

import com.example.identity.AuthSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class RevenueCatOperationGateTest {
    @Test
    fun `guest requires account before purchase or restore`() {
        assertEquals(
            RevenueCatOperationGate.AccountRequired,
            revenueCatOperationGate(
                session = AuthSessionState.Guest,
                identityState = RevenueCatIdentityState.AnonymousReady(3L)
            )
        )
    }

    @Test
    fun `unverified account requires verification`() {
        assertEquals(
            RevenueCatOperationGate.VerificationRequired,
            revenueCatOperationGate(
                session = AuthSessionState.Authenticated(
                    uid = "firebaseUidA",
                    email = "person@example.com",
                    emailVerified = false
                ),
                identityState = RevenueCatIdentityState.AnonymousReady(3L)
            )
        )
    }

    @Test
    fun `verified account waits while RevenueCat is reconciling`() {
        assertEquals(
            RevenueCatOperationGate.PreparingAccount,
            revenueCatOperationGate(
                session = verifiedSession("firebaseUidA"),
                identityState = RevenueCatIdentityState.Reconciling(4L)
            )
        )
    }

    @Test
    fun `verified account fails closed when RevenueCat identity is unresolved`() {
        assertEquals(
            RevenueCatOperationGate.IdentityUnavailable,
            revenueCatOperationGate(
                session = verifiedSession("firebaseUidA"),
                identityState = RevenueCatIdentityState.Error(4L)
            )
        )
    }

    @Test
    fun `only matching identified account generation is allowed`() {
        assertEquals(
            RevenueCatOperationGate.IdentityUnavailable,
            revenueCatOperationGate(
                session = verifiedSession("firebaseUidA"),
                identityState = RevenueCatIdentityState.IdentifiedReady(
                    generation = 7L,
                    appUserId = "fd_firebaseUidB"
                )
            )
        )
        assertEquals(
            RevenueCatOperationGate.Allowed(
                RevenueCatOperationIdentity(7L, "fd_firebaseUidA")
            ),
            revenueCatOperationGate(
                session = verifiedSession("firebaseUidA"),
                identityState = RevenueCatIdentityState.IdentifiedReady(
                    generation = 7L,
                    appUserId = "fd_firebaseUidA"
                )
            )
        )
    }

    private companion object {
        fun verifiedSession(uid: String) = AuthSessionState.Authenticated(
            uid = uid,
            email = "person@example.com",
            emailVerified = true
        )
    }
}
