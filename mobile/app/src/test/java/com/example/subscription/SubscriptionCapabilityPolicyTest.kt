package com.example.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCapabilityPolicyTest {
    @Test
    fun plusExplicitlyInheritsBasicAndProExplicitlyInheritsBoth() {
        assertTrue(SubscriptionPolicy.inherits(SubscriptionTier.PLUS, SubscriptionTier.BASIC))
        assertTrue(SubscriptionPolicy.inherits(SubscriptionTier.PRO, SubscriptionTier.PLUS))
        assertTrue(SubscriptionPolicy.inherits(SubscriptionTier.PRO, SubscriptionTier.BASIC))
        assertFalse(SubscriptionPolicy.inherits(SubscriptionTier.BASIC, SubscriptionTier.PLUS))
        assertFalse(SubscriptionPolicy.inherits(SubscriptionTier.PLUS, SubscriptionTier.PRO))
    }

    @Test
    fun basicHasNoPaidCapabilities() {
        SubscriptionCapability.entries.forEach { capability ->
            assertFalse(SubscriptionPolicy.hasCapability(SubscriptionTier.BASIC, capability))
        }
    }

    @Test
    fun plusHasTheApprovedPlusCapabilities() {
        val expected = setOf(
            SubscriptionCapability.MACRO_TARGETS,
            SubscriptionCapability.UNLIMITED_ROUTINES,
            SubscriptionCapability.FULL_WORKOUT_HISTORY,
            SubscriptionCapability.ADVANCED_ANALYTICS,
            SubscriptionCapability.AI_WORKOUT_GENERATION,
            SubscriptionCapability.AI_PROGRESS_SUMMARIES,
            SubscriptionCapability.ADAPTIVE_RECOMMENDATIONS
        )

        assertEquals(expected, SubscriptionPolicy.capabilitiesFor(SubscriptionTier.PLUS))
        assertFalse(
            SubscriptionPolicy.hasCapability(
                SubscriptionTier.PLUS,
                SubscriptionCapability.REMOTE_AI_COACH
            )
        )
    }

    @Test
    fun proContainsEveryPlusCapabilityAndAddsProOnlyCapabilities() {
        val plus = SubscriptionPolicy.capabilitiesFor(SubscriptionTier.PLUS)
        val pro = SubscriptionPolicy.capabilitiesFor(SubscriptionTier.PRO)

        assertTrue(pro.containsAll(plus))
        assertTrue(pro.contains(SubscriptionCapability.REMOTE_AI_COACH))
        assertTrue(pro.contains(SubscriptionCapability.MULTI_WEEK_ADAPTATION))
        assertTrue(pro.contains(SubscriptionCapability.LONGITUDINAL_REASONING))
    }

    @Test
    fun basicCommercialLimitsArePolicyOnlyConstants() {
        assertEquals(2, SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT)
        assertEquals(30, SubscriptionPolicy.BASIC_DETAILED_HISTORY_DAYS)
    }
}
