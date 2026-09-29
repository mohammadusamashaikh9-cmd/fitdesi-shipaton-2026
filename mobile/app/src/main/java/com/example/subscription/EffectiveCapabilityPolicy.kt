package com.example.subscription

import com.example.boost.BoostAccessState

object EffectiveCapabilityPolicy {
    private val boostApprovedCapabilities = setOf(
        SubscriptionCapability.AI_WORKOUT_GENERATION,
        SubscriptionCapability.ADVANCED_ANALYTICS
    )

    fun hasCapability(
        subscriptionState: SubscriptionState,
        boostAccessState: BoostAccessState,
        capability: SubscriptionCapability,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (subscriptionState.hasAuthoritativeCapability(capability)) return true
        if (capability !in boostApprovedCapabilities) return false
        if (
            subscriptionState.status != SubscriptionStatus.READY ||
            !subscriptionState.hasAuthoritativeCustomerInfo
        ) {
            return false
        }
        val activeBoost = boostAccessState as? BoostAccessState.Active ?: return false
        return activeBoost.expiresAtEpochMillis > nowEpochMillis
    }
}
