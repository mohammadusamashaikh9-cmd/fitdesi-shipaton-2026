package com.example.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionTierResolverTest {
    @Test
    fun noActiveEntitlements_resolvesBasic() {
        assertEquals(SubscriptionTier.BASIC, SubscriptionTierResolver.resolve(emptySet()))
    }

    @Test
    fun plusEntitlement_resolvesPlus() {
        assertEquals(SubscriptionTier.PLUS, SubscriptionTierResolver.resolve(setOf("plus")))
    }

    @Test
    fun proEntitlement_resolvesPro() {
        assertEquals(SubscriptionTier.PRO, SubscriptionTierResolver.resolve(setOf("pro")))
    }

    @Test
    fun plusAndProEntitlements_resolvePro() {
        assertEquals(
            SubscriptionTier.PRO,
            SubscriptionTierResolver.resolve(setOf("plus", "pro"))
        )
    }

    @Test
    fun unknownEntitlementsAreIgnored() {
        assertEquals(
            SubscriptionTier.BASIC,
            SubscriptionTierResolver.resolve(setOf("FitDesi AI Plus", "unknown"))
        )
    }
}
