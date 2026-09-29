package com.example.subscription

enum class SubscriptionTier {
    BASIC,
    PLUS,
    PRO
}

enum class SubscriptionCapability {
    MACRO_TARGETS,
    UNLIMITED_ROUTINES,
    FULL_WORKOUT_HISTORY,
    ADVANCED_ANALYTICS,
    AI_WORKOUT_GENERATION,
    REMOTE_AI_COACH,
    AI_PROGRESS_SUMMARIES,
    ADAPTIVE_RECOMMENDATIONS,
    MULTI_WEEK_ADAPTATION,
    LONGITUDINAL_REASONING
}

object SubscriptionTierResolver {
    private const val PLUS_ENTITLEMENT = "plus"
    private const val PRO_ENTITLEMENT = "pro"

    fun resolve(activeEntitlementIdentifiers: Set<String>): SubscriptionTier = when {
        PRO_ENTITLEMENT in activeEntitlementIdentifiers -> SubscriptionTier.PRO
        PLUS_ENTITLEMENT in activeEntitlementIdentifiers -> SubscriptionTier.PLUS
        else -> SubscriptionTier.BASIC
    }
}

object SubscriptionPolicy {
    const val BASIC_AUTHORED_ROUTINE_LIMIT = 2
    const val BASIC_DETAILED_HISTORY_DAYS = 30

    private val plusCapabilities = setOf(
        SubscriptionCapability.MACRO_TARGETS,
        SubscriptionCapability.UNLIMITED_ROUTINES,
        SubscriptionCapability.FULL_WORKOUT_HISTORY,
        SubscriptionCapability.ADVANCED_ANALYTICS,
        SubscriptionCapability.AI_WORKOUT_GENERATION,
        SubscriptionCapability.AI_PROGRESS_SUMMARIES,
        SubscriptionCapability.ADAPTIVE_RECOMMENDATIONS
    )

    private val proCapabilities = plusCapabilities + setOf(
        SubscriptionCapability.REMOTE_AI_COACH,
        SubscriptionCapability.MULTI_WEEK_ADAPTATION,
        SubscriptionCapability.LONGITUDINAL_REASONING
    )

    fun capabilitiesFor(tier: SubscriptionTier): Set<SubscriptionCapability> = when (tier) {
        SubscriptionTier.BASIC -> emptySet()
        SubscriptionTier.PLUS -> plusCapabilities
        SubscriptionTier.PRO -> proCapabilities
    }

    fun hasCapability(tier: SubscriptionTier, capability: SubscriptionCapability): Boolean =
        capability in capabilitiesFor(tier)

    fun inherits(tier: SubscriptionTier, inheritedTier: SubscriptionTier): Boolean = when (tier) {
        SubscriptionTier.BASIC -> inheritedTier == SubscriptionTier.BASIC
        SubscriptionTier.PLUS -> inheritedTier == SubscriptionTier.BASIC ||
            inheritedTier == SubscriptionTier.PLUS
        SubscriptionTier.PRO -> true
    }
}

fun SubscriptionState.hasAuthoritativeCapability(
    capability: SubscriptionCapability
): Boolean =
    status == SubscriptionStatus.READY &&
        hasAuthoritativeCustomerInfo &&
        SubscriptionPolicy.hasCapability(tier, capability)
