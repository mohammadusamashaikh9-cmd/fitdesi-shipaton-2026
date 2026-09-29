package com.example.subscription

import com.example.boost.BoostAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveCapabilityPolicyTest {
    private val now = 10_000L
    private val authoritativeBasic = SubscriptionState(
        tier = SubscriptionTier.BASIC,
        status = SubscriptionStatus.READY,
        hasAuthoritativeCustomerInfo = true
    )

    @Test
    fun `Basic without Boost remains locked for Boost capabilities`() {
        assertFalse(has(BoostAccessState.Inactive, SubscriptionCapability.AI_WORKOUT_GENERATION))
        assertFalse(has(BoostAccessState.Inactive, SubscriptionCapability.ADVANCED_ANALYTICS))
    }

    @Test
    fun `authoritative unexpired Boost unlocks only approved capabilities`() {
        val boost = BoostAccessState.Active(now + 30L * 60L * 1_000L)

        assertTrue(has(boost, SubscriptionCapability.AI_WORKOUT_GENERATION))
        assertTrue(has(boost, SubscriptionCapability.ADVANCED_ANALYTICS))
        assertFalse(has(boost, SubscriptionCapability.FULL_WORKOUT_HISTORY))
        assertFalse(has(boost, SubscriptionCapability.UNLIMITED_ROUTINES))
        assertFalse(has(boost, SubscriptionCapability.MACRO_TARGETS))
        assertFalse(has(boost, SubscriptionCapability.REMOTE_AI_COACH))
        assertEquals(SubscriptionTier.BASIC, authoritativeBasic.tier)
    }

    @Test
    fun `unknown and expired Boost fail closed`() {
        assertFalse(has(BoostAccessState.Unknown, SubscriptionCapability.AI_WORKOUT_GENERATION))
        assertFalse(
            has(
                BoostAccessState.Active(expiresAtEpochMillis = now),
                SubscriptionCapability.ADVANCED_ANALYTICS
            )
        )
    }

    @Test
    fun `stale CustomerInfo fails closed even if a prior active Boost state is observed`() {
        val staleBasic = authoritativeBasic.copy(status = SubscriptionStatus.ERROR)

        assertFalse(
            EffectiveCapabilityPolicy.hasCapability(
                staleBasic,
                BoostAccessState.Active(now + 5_000L),
                SubscriptionCapability.AI_WORKOUT_GENERATION,
                now
            )
        )
    }

    @Test
    fun `permanent Plus capability remains independent of Boost`() {
        val plus = authoritativeBasic.copy(tier = SubscriptionTier.PLUS)

        assertTrue(
            EffectiveCapabilityPolicy.hasCapability(
                plus,
                BoostAccessState.Unknown,
                SubscriptionCapability.AI_WORKOUT_GENERATION,
                now
            )
        )
        assertEquals(SubscriptionTier.PLUS, plus.tier)
    }

    private fun has(boost: BoostAccessState, capability: SubscriptionCapability): Boolean =
        EffectiveCapabilityPolicy.hasCapability(
            authoritativeBasic,
            boost,
            capability,
            now
        )
}
