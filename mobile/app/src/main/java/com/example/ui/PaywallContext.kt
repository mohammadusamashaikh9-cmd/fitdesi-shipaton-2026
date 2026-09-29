package com.example.ui

import com.example.subscription.SubscriptionCapability

internal enum class PaywallEntryPoint {
    MEMBERSHIP,
    AI_WORKOUT_GENERATOR,
    ROUTINE_LIMIT,
    MACRO_TARGETS,
    FULL_HISTORY,
    ADVANCED_ANALYTICS
}

internal data class PaywallContext(
    val entryPoint: PaywallEntryPoint
)

internal fun PaywallContext.boostCapability(): SubscriptionCapability? = when (entryPoint) {
    PaywallEntryPoint.AI_WORKOUT_GENERATOR -> SubscriptionCapability.AI_WORKOUT_GENERATION
    PaywallEntryPoint.ADVANCED_ANALYTICS -> SubscriptionCapability.ADVANCED_ANALYTICS
    else -> null
}
