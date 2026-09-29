package com.example.viewmodel

import com.example.boost.BoostAccessState
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAccessPolicyTest {
    @Test
    fun `authoritative Plus and Pro receive full history and macro target capabilities`() {
        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            val access = progressAccessFor(
                SubscriptionState(
                    tier = tier,
                    status = SubscriptionStatus.READY,
                    hasAuthoritativeCustomerInfo = true
                )
            )

            assertTrue(access.canViewFullHistory)
            assertTrue(access.canShowMacroTargets)
            assertTrue(access.canViewAdvancedAnalytics)
        }
    }

    @Test
    fun `paid-looking non-authoritative states fail Basic-safe`() {
        SubscriptionStatus.entries.forEach { status ->
            listOf(false, true).forEach customerInfoLoop@{ hasCustomerInfo ->
                if (status == SubscriptionStatus.READY && hasCustomerInfo) return@customerInfoLoop
                val access = progressAccessFor(
                    SubscriptionState(
                        tier = SubscriptionTier.PRO,
                        status = status,
                        hasAuthoritativeCustomerInfo = hasCustomerInfo
                    )
                )

                assertFalse("$status authoritative=$hasCustomerInfo", access.canViewFullHistory)
                assertFalse("$status authoritative=$hasCustomerInfo", access.canShowMacroTargets)
                assertFalse("$status authoritative=$hasCustomerInfo", access.canViewAdvancedAnalytics)
            }
        }
    }

    @Test
    fun `authoritative Basic remains recent-history only`() {
        val access = progressAccessFor(
            SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.READY,
                hasAuthoritativeCustomerInfo = true
            )
        )

        assertFalse(access.canViewFullHistory)
        assertFalse(access.canShowMacroTargets)
        assertFalse(access.canViewAdvancedAnalytics)
    }

    @Test
    fun `active Boost permits advanced analytics without full history or macro targets`() {
        val access = progressAccessFor(
            subscriptionState = SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.READY,
                hasAuthoritativeCustomerInfo = true
            ),
            boostAccessState = BoostAccessState.Active(expiresAtEpochMillis = 2_000L),
            nowEpochMillis = 1_000L
        )

        assertTrue(access.canViewAdvancedAnalytics)
        assertFalse(access.canViewFullHistory)
        assertFalse(access.canShowMacroTargets)
    }
}
