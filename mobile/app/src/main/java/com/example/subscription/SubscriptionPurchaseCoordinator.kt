package com.example.subscription

import android.app.Activity
import com.example.identity.AuthSessionState
import java.util.concurrent.atomic.AtomicBoolean

object Stage10BPurchasePolicy {
    private val purchasablePackageIdentifiers = setOf(
        "plus_monthly",
        "plus_annual"
    )

    fun isPurchasable(packageIdentifier: String): Boolean =
        packageIdentifier in purchasablePackageIdentifiers
}

enum class SubscriptionOperationUnavailableReason {
    REVENUECAT_DISABLED,
    OFFERING_UNAVAILABLE,
    PACKAGE_UNAVAILABLE
}

enum class RestoreFailureReason {
    NETWORK,
    STORE_UNAVAILABLE,
    RESTORE_FAILED,
    UNKNOWN
}

enum class PurchaseFailureReason {
    NETWORK,
    STORE_UNAVAILABLE,
    INVALID_PACKAGE,
    PURCHASE_FAILED,
    ENTITLEMENT_NOT_ACTIVATED,
    UNKNOWN
}

enum class CommercialOperationBlockReason {
    ACCOUNT_REQUIRED,
    VERIFICATION_REQUIRED,
    PREPARING_ACCOUNT,
    IDENTITY_UNAVAILABLE
}

sealed interface PurchaseResult {
    data class EntitlementActivated(val tier: SubscriptionTier) : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val reason: PurchaseFailureReason) : PurchaseResult
    data class Unavailable(
        val reason: SubscriptionOperationUnavailableReason
    ) : PurchaseResult
    data class Blocked(
        val reason: CommercialOperationBlockReason
    ) : PurchaseResult
}

internal sealed interface RevenueCatPurchaseClientResult {
    data class CustomerInfo(
        val snapshot: CustomerInfoSnapshot
    ) : RevenueCatPurchaseClientResult

    data object Cancelled : RevenueCatPurchaseClientResult

    data class Failed(
        val reason: PurchaseFailureReason
    ) : RevenueCatPurchaseClientResult

    data class Unavailable(
        val reason: SubscriptionOperationUnavailableReason
    ) : RevenueCatPurchaseClientResult
}

internal sealed interface RevenueCatRestoreClientResult {
    data class CustomerInfo(
        val snapshot: CustomerInfoSnapshot
    ) : RevenueCatRestoreClientResult

    data class Failed(
        val reason: RestoreFailureReason
    ) : RevenueCatRestoreClientResult
}

internal interface RevenueCatPurchaseClient {
    fun purchase(
        hostActivity: Activity,
        packageIdentifier: String,
        callback: (RevenueCatPurchaseClientResult) -> Unit
    )

    fun restorePurchases(callback: (RevenueCatRestoreClientResult) -> Unit)
}

internal class RevenueCatSubscriptionPurchaseCoordinator(
    private val client: RevenueCatPurchaseClient,
    private val identityCoordinator: RevenueCatCommercialIdentityCoordinator,
    private val authSessionProvider: () -> AuthSessionState
) : SubscriptionPurchaseCoordinator {
    override fun purchase(
        hostActivity: Activity,
        packageIdentifier: String,
        callback: (PurchaseResult) -> Unit
    ) {
        if (!Stage10BPurchasePolicy.isPurchasable(packageIdentifier)) {
            callback(PurchaseResult.Failed(PurchaseFailureReason.INVALID_PACKAGE))
            return
        }
        val identity = allowedIdentityOrBlock { blocked -> callback(PurchaseResult.Blocked(blocked)) }
            ?: return
        val active = AtomicBoolean(true)
        client.purchase(hostActivity, packageIdentifier) { result ->
            if (!active.compareAndSet(true, false)) return@purchase
            if (!identityIsCurrent(identity)) {
                callback(PurchaseResult.Blocked(CommercialOperationBlockReason.IDENTITY_UNAVAILABLE))
                return@purchase
            }
            when (result) {
                is RevenueCatPurchaseClientResult.CustomerInfo -> {
                    val tier = identityCoordinator.acceptOperationCustomerInfo(
                        identity = identity,
                        snapshot = result.snapshot
                    )
                    if (tier == null) {
                        callback(
                            PurchaseResult.Blocked(
                                CommercialOperationBlockReason.IDENTITY_UNAVAILABLE
                            )
                        )
                        return@purchase
                    }
                    callback(
                        if (tier == SubscriptionTier.BASIC) {
                            PurchaseResult.Failed(
                                PurchaseFailureReason.ENTITLEMENT_NOT_ACTIVATED
                            )
                        } else {
                            PurchaseResult.EntitlementActivated(tier)
                        }
                    )
                }
                RevenueCatPurchaseClientResult.Cancelled -> callback(PurchaseResult.Cancelled)
                is RevenueCatPurchaseClientResult.Failed -> {
                    callback(PurchaseResult.Failed(result.reason))
                }
                is RevenueCatPurchaseClientResult.Unavailable -> {
                    callback(PurchaseResult.Unavailable(result.reason))
                }
            }
        }
    }

    override fun restorePurchases(callback: (RestoreResult) -> Unit) {
        val identity = allowedIdentityOrBlock { blocked -> callback(RestoreResult.Blocked(blocked)) }
            ?: return
        val active = AtomicBoolean(true)
        client.restorePurchases { result ->
            if (!active.compareAndSet(true, false)) return@restorePurchases
            if (!identityIsCurrent(identity)) {
                callback(RestoreResult.Blocked(CommercialOperationBlockReason.IDENTITY_UNAVAILABLE))
                return@restorePurchases
            }
            when (result) {
                is RevenueCatRestoreClientResult.CustomerInfo -> {
                    val tier = identityCoordinator.acceptOperationCustomerInfo(
                        identity = identity,
                        snapshot = result.snapshot
                    )
                    if (tier == null) {
                        callback(
                            RestoreResult.Blocked(
                                CommercialOperationBlockReason.IDENTITY_UNAVAILABLE
                            )
                        )
                        return@restorePurchases
                    }
                    callback(
                        if (tier == SubscriptionTier.BASIC) {
                            RestoreResult.NothingActive
                        } else {
                            RestoreResult.Restored(tier)
                        }
                    )
                }
                is RevenueCatRestoreClientResult.Failed -> {
                    callback(RestoreResult.Failed(result.reason))
                }
            }
        }
    }

    private fun allowedIdentityOrBlock(
        onBlocked: (CommercialOperationBlockReason) -> Unit
    ): RevenueCatOperationIdentity? {
        val session = authSessionProvider()
        val gate = revenueCatOperationGate(session, identityCoordinator.state.value)
        if (gate !is RevenueCatOperationGate.Allowed) {
            onBlocked(gate.toBlockReason())
            return null
        }
        return identityCoordinator.operationIdentity(session) ?: run {
            onBlocked(CommercialOperationBlockReason.IDENTITY_UNAVAILABLE)
            null
        }
    }

    private fun identityIsCurrent(identity: RevenueCatOperationIdentity): Boolean =
        identityCoordinator.operationIdentity(authSessionProvider()) == identity
}

sealed interface RestoreResult {
    data class Restored(val tier: SubscriptionTier) : RestoreResult
    data object NothingActive : RestoreResult
    data class Failed(val reason: RestoreFailureReason) : RestoreResult
    data class Unavailable(
        val reason: SubscriptionOperationUnavailableReason
    ) : RestoreResult
    data class Blocked(
        val reason: CommercialOperationBlockReason
    ) : RestoreResult
}

private fun RevenueCatOperationGate.toBlockReason(): CommercialOperationBlockReason = when (this) {
    RevenueCatOperationGate.AccountRequired -> CommercialOperationBlockReason.ACCOUNT_REQUIRED
    RevenueCatOperationGate.VerificationRequired ->
        CommercialOperationBlockReason.VERIFICATION_REQUIRED
    RevenueCatOperationGate.PreparingAccount ->
        CommercialOperationBlockReason.PREPARING_ACCOUNT
    RevenueCatOperationGate.IdentityUnavailable ->
        CommercialOperationBlockReason.IDENTITY_UNAVAILABLE
    is RevenueCatOperationGate.Allowed -> error("Allowed identity is not blocked.")
}

interface SubscriptionPurchaseCoordinator {
    fun purchase(
        hostActivity: Activity,
        packageIdentifier: String,
        callback: (PurchaseResult) -> Unit
    )

    fun restorePurchases(callback: (RestoreResult) -> Unit)
}

class DisabledSubscriptionPurchaseCoordinator : SubscriptionPurchaseCoordinator {
    override fun purchase(
        hostActivity: Activity,
        packageIdentifier: String,
        callback: (PurchaseResult) -> Unit
    ) {
        callback(
            PurchaseResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            )
        )
    }

    override fun restorePurchases(callback: (RestoreResult) -> Unit) {
        callback(
            RestoreResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            )
        )
    }
}
