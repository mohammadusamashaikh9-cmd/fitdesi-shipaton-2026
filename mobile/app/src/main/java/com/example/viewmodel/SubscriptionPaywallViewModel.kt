package com.example.viewmodel

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FitDesiApplication
import com.example.boost.BoostAccessState
import com.example.boost.BoostAdController
import com.example.boost.BoostAdState
import com.example.boost.BoostRepository
import com.example.boost.DisabledBoostAdController
import com.example.boost.DisabledBoostRepository
import com.example.subscription.OfferingLoadResult
import com.example.subscription.PurchaseFailureReason
import com.example.subscription.PurchaseResult
import com.example.subscription.RestoreFailureReason
import com.example.subscription.RestoreResult
import com.example.subscription.SubscriptionBillingPeriod
import com.example.subscription.SubscriptionOperationUnavailableReason
import com.example.subscription.SubscriptionPackage
import com.example.subscription.SubscriptionPurchaseCoordinator
import com.example.subscription.SubscriptionRepository
import com.example.subscription.CommercialOperationBlockReason
import com.example.subscription.SubscriptionState
import com.example.subscription.SubscriptionStatus
import com.example.subscription.SubscriptionTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PaywallPurchaseOption(
    val packageIdentifier: String,
    val billingPeriod: SubscriptionBillingPeriod,
    val localizedPrice: String
)

enum class PaywallCustomerInfoStatus {
    DISABLED,
    LOADING,
    READY,
    ERROR
}

sealed interface PaywallOfferingState {
    data object NotLoaded : PaywallOfferingState
    data object Loading : PaywallOfferingState
    data class Ready(val options: List<PaywallPurchaseOption>) : PaywallOfferingState
    data object Invalid : PaywallOfferingState
    data class Unavailable(val reason: PaywallUnavailableReason) : PaywallOfferingState
}

enum class PaywallOperationFailure(val message: String) {
    INVALID_SELECTION("Choose an available FitDesi Plus plan and try again."),
    PURCHASE_NETWORK("We couldn't complete your purchase. Check your connection and try again."),
    PURCHASE_STORE_UNAVAILABLE("Purchases are temporarily unavailable. Please try again later."),
    PURCHASE_NOT_COMPLETED("We couldn't complete your purchase. Please try again."),
    ENTITLEMENT_NOT_ACTIVATED("Your purchase completed, but Plus is not active yet. Try restoring purchases."),
    RESTORE_NETWORK("We couldn't restore purchases. Check your connection and try again."),
    RESTORE_STORE_UNAVAILABLE("The store is temporarily unavailable. Please try restoring later."),
    RESTORE_NOT_COMPLETED("We couldn't restore purchases. Please try again.")
}

enum class PaywallUnavailableReason(val message: String) {
    SUBSCRIPTIONS_UNAVAILABLE("Subscriptions are not available in this version of FitDesi."),
    OFFERING_UNAVAILABLE("FitDesi Plus plans are temporarily unavailable. Please try again."),
    PACKAGE_UNAVAILABLE("That FitDesi Plus plan is temporarily unavailable. Please choose another plan or try again.")
}

sealed interface PaywallOperationState {
    data object Idle : PaywallOperationState
    data class Purchasing(val packageIdentifier: String) : PaywallOperationState
    data object Restoring : PaywallOperationState
    data class PurchaseSucceeded(val tier: SubscriptionTier) : PaywallOperationState
    data class RestoreSucceeded(val tier: SubscriptionTier) : PaywallOperationState
    data object PurchaseCancelled : PaywallOperationState
    data object NothingActive : PaywallOperationState
    data object AccountRequired : PaywallOperationState
    data object VerificationRequired : PaywallOperationState
    data object PreparingAccount : PaywallOperationState
    data object IdentityUnavailable : PaywallOperationState
    data class Failed(val reason: PaywallOperationFailure) : PaywallOperationState
    data class Unavailable(val reason: PaywallUnavailableReason) : PaywallOperationState
}

data class SubscriptionPaywallUiState(
    val currentTier: SubscriptionTier,
    val customerInfoStatus: PaywallCustomerInfoStatus,
    val hasAuthoritativeCustomerInfo: Boolean,
    val offering: PaywallOfferingState = PaywallOfferingState.NotLoaded,
    val operation: PaywallOperationState = PaywallOperationState.Idle,
    val selectedPackageIdentifier: String? = null,
    val boostAccess: BoostAccessState = BoostAccessState.Unknown,
    val boostAdState: BoostAdState = BoostAdState.Disabled,
    val boostPrivacyOptionsRequired: Boolean = false
)

class SubscriptionPaywallViewModel internal constructor(
    application: Application,
    private val subscriptionRepository: SubscriptionRepository,
    private val purchaseCoordinator: SubscriptionPurchaseCoordinator,
    private val boostRepository: BoostRepository = DisabledBoostRepository(),
    private val boostAdController: BoostAdController = DisabledBoostAdController()
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        subscriptionRepository = (application as FitDesiApplication).subscriptionRepository,
        purchaseCoordinator = application.subscriptionPurchaseCoordinator,
        boostRepository = application.boostRepository,
        boostAdController = application.boostAdController
    )

    private val stateLock = Any()
    private var isPaywallVisible = false
    private var nextOfferingRequestId = 0L
    private var activeOfferingRequestId: Long? = null
    private var nextOperationId = 0L
    private var activeOperationId: Long? = null

    private val _uiState = MutableStateFlow(
        subscriptionRepository.state.value.toPaywallUiState().copy(
            boostAccess = boostRepository.state.value,
            boostAdState = boostAdController.state.value,
            boostPrivacyOptionsRequired = boostAdController.privacyOptionsRequired.value
        )
    )
    val uiState: StateFlow<SubscriptionPaywallUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            subscriptionRepository.state.collect { subscriptionState ->
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(
                        currentTier = subscriptionState.tier,
                        customerInfoStatus = subscriptionState.status.toPaywallStatus(),
                        hasAuthoritativeCustomerInfo =
                            subscriptionState.hasAuthoritativeCustomerInfo
                    )
                }
            }
        }
        viewModelScope.launch {
            boostRepository.state.collect { boostAccess ->
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(boostAccess = boostAccess)
                }
            }
        }
        viewModelScope.launch {
            boostAdController.state.collect { boostAdState ->
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(boostAdState = boostAdState)
                }
            }
        }
        viewModelScope.launch {
            boostAdController.privacyOptionsRequired.collect { required ->
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(boostPrivacyOptionsRequired = required)
                }
            }
        }
    }

    fun onPaywallOpened() {
        synchronized(stateLock) {
            if (isPaywallVisible) return
            isPaywallVisible = true
            if (activeOperationId == null) {
                _uiState.value = _uiState.value.copy(
                    operation = PaywallOperationState.Idle,
                    selectedPackageIdentifier = null
                )
            } else {
                _uiState.value = _uiState.value.copy(selectedPackageIdentifier = null)
            }
        }

        subscriptionRepository.refreshCustomerInfo()
        boostRepository.reevaluate()
        requestOffering()
    }

    fun requestBoost(hostActivity: Activity) {
        when (boostAdController.state.value) {
            BoostAdState.Ready -> boostAdController.show(hostActivity)
            BoostAdState.Disabled,
            BoostAdState.GatheringConsent,
            BoostAdState.Loading,
            BoostAdState.Showing,
            BoostAdState.Verifying -> Unit
            BoostAdState.NeedsPreparation,
            is BoostAdState.Unavailable -> boostAdController.prepare(hostActivity)
        }
    }

    fun showBoostPrivacyOptions(hostActivity: Activity) {
        boostAdController.showPrivacyOptions(hostActivity)
    }

    fun onPaywallClosed() {
        synchronized(stateLock) {
            if (!isPaywallVisible) return
            isPaywallVisible = false
            activeOfferingRequestId = null
            _uiState.value = _uiState.value.copy(selectedPackageIdentifier = null)
        }
    }

    fun retryOffering() {
        requestOffering()
    }

    fun selectPackage(packageIdentifier: String) {
        synchronized(stateLock) {
            val readyOffering = _uiState.value.offering as? PaywallOfferingState.Ready
                ?: return
            if (readyOffering.options.none { it.packageIdentifier == packageIdentifier }) return

            _uiState.value = _uiState.value.copy(
                selectedPackageIdentifier = packageIdentifier
            )
        }
    }

    fun purchase(hostActivity: Activity, packageIdentifier: String) {
        val operationId = synchronized(stateLock) {
            if (activeOperationId != null) return

            val readyOffering = _uiState.value.offering as? PaywallOfferingState.Ready
            val isCurrentPlusOption = readyOffering
                ?.options
                ?.any { option -> option.packageIdentifier == packageIdentifier }
                ?: false
            if (!isCurrentPlusOption) {
                _uiState.value = _uiState.value.copy(
                    operation = PaywallOperationState.Failed(
                        PaywallOperationFailure.INVALID_SELECTION
                    )
                )
                return
            }

            nextOperationId += 1
            nextOperationId.also { id ->
                activeOperationId = id
                _uiState.value = _uiState.value.copy(
                    operation = PaywallOperationState.Purchasing(packageIdentifier)
                )
            }
        }

        try {
            purchaseCoordinator.purchase(hostActivity, packageIdentifier) { result ->
                completePurchase(operationId, result)
            }
        } catch (_: RuntimeException) {
            completePurchase(
                operationId,
                PurchaseResult.Failed(PurchaseFailureReason.UNKNOWN)
            )
        }
    }

    fun restorePurchases() {
        val operationId = synchronized(stateLock) {
            if (activeOperationId != null) return

            nextOperationId += 1
            nextOperationId.also { id ->
                activeOperationId = id
                _uiState.value = _uiState.value.copy(
                    operation = PaywallOperationState.Restoring
                )
            }
        }

        try {
            purchaseCoordinator.restorePurchases { result ->
                completeRestore(operationId, result)
            }
        } catch (_: RuntimeException) {
            completeRestore(
                operationId,
                RestoreResult.Failed(RestoreFailureReason.UNKNOWN)
            )
        }
    }

    private fun requestOffering() {
        val requestId = synchronized(stateLock) {
            if (activeOfferingRequestId != null) return

            nextOfferingRequestId += 1
            nextOfferingRequestId.also { id ->
                activeOfferingRequestId = id
                _uiState.value = _uiState.value.copy(
                    offering = PaywallOfferingState.Loading,
                    selectedPackageIdentifier = null
                )
            }
        }

        try {
            subscriptionRepository.loadCurrentOffering { result ->
                completeOfferingRequest(requestId, result)
            }
        } catch (_: RuntimeException) {
            completeOfferingRequest(
                requestId,
                OfferingLoadResult.Unavailable(
                    SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE
                )
            )
        }
    }

    private fun completeOfferingRequest(
        requestId: Long,
        result: OfferingLoadResult
    ) {
        synchronized(stateLock) {
            if (activeOfferingRequestId != requestId) return
            activeOfferingRequestId = null
            val offering = result.toPaywallOfferingState()
            _uiState.value = _uiState.value.copy(
                offering = offering,
                selectedPackageIdentifier = offering.defaultAnnualPackageIdentifier()
            )
        }
    }

    private fun completePurchase(operationId: Long, result: PurchaseResult) {
        synchronized(stateLock) {
            if (activeOperationId != operationId) return
            activeOperationId = null
            _uiState.value = _uiState.value.copy(
                operation = when (result) {
                    is PurchaseResult.EntitlementActivated ->
                        PaywallOperationState.PurchaseSucceeded(result.tier)
                    PurchaseResult.Cancelled -> PaywallOperationState.PurchaseCancelled
                    is PurchaseResult.Failed ->
                        PaywallOperationState.Failed(result.reason.toPaywallFailure())
                    is PurchaseResult.Unavailable ->
                        PaywallOperationState.Unavailable(result.reason.toPaywallReason())
                    is PurchaseResult.Blocked -> result.reason.toPaywallOperationState()
                }
            )
        }
    }

    private fun completeRestore(operationId: Long, result: RestoreResult) {
        synchronized(stateLock) {
            if (activeOperationId != operationId) return
            activeOperationId = null
            _uiState.value = _uiState.value.copy(
                operation = when (result) {
                    is RestoreResult.Restored ->
                        PaywallOperationState.RestoreSucceeded(result.tier)
                    RestoreResult.NothingActive -> PaywallOperationState.NothingActive
                    is RestoreResult.Failed ->
                        PaywallOperationState.Failed(result.reason.toPaywallFailure())
                    is RestoreResult.Unavailable ->
                        PaywallOperationState.Unavailable(result.reason.toPaywallReason())
                    is RestoreResult.Blocked -> result.reason.toPaywallOperationState()
                }
            )
        }
    }
}

private fun SubscriptionState.toPaywallUiState(): SubscriptionPaywallUiState =
    SubscriptionPaywallUiState(
        currentTier = tier,
        customerInfoStatus = status.toPaywallStatus(),
        hasAuthoritativeCustomerInfo = hasAuthoritativeCustomerInfo
    )

private fun SubscriptionStatus.toPaywallStatus(): PaywallCustomerInfoStatus = when (this) {
    SubscriptionStatus.DISABLED -> PaywallCustomerInfoStatus.DISABLED
    SubscriptionStatus.LOADING -> PaywallCustomerInfoStatus.LOADING
    SubscriptionStatus.READY -> PaywallCustomerInfoStatus.READY
    SubscriptionStatus.ERROR -> PaywallCustomerInfoStatus.ERROR
}

private fun OfferingLoadResult.toPaywallOfferingState(): PaywallOfferingState = when (this) {
    is OfferingLoadResult.Ready -> packages.toReadyPaywallOffering()
    is OfferingLoadResult.Invalid -> PaywallOfferingState.Invalid
    is OfferingLoadResult.Unavailable -> PaywallOfferingState.Unavailable(reason.toPaywallReason())
}

private fun List<SubscriptionPackage>.toReadyPaywallOffering(): PaywallOfferingState {
    val monthly = filter { packageOption ->
        packageOption.packageIdentifier == PLUS_MONTHLY_PACKAGE_IDENTIFIER &&
            packageOption.tier == SubscriptionTier.PLUS &&
            packageOption.billingPeriod == SubscriptionBillingPeriod.MONTHLY
    }
    val annual = filter { packageOption ->
        packageOption.packageIdentifier == PLUS_ANNUAL_PACKAGE_IDENTIFIER &&
            packageOption.tier == SubscriptionTier.PLUS &&
            packageOption.billingPeriod == SubscriptionBillingPeriod.ANNUAL
    }
    if (monthly.size != 1 || annual.size != 1) {
        return PaywallOfferingState.Invalid
    }

    return PaywallOfferingState.Ready(
        options = listOf(monthly.single(), annual.single()).map { packageOption ->
            PaywallPurchaseOption(
                packageIdentifier = packageOption.packageIdentifier,
                billingPeriod = packageOption.billingPeriod,
                localizedPrice = packageOption.localizedPrice
            )
        }
    )
}

private fun PaywallOfferingState.defaultAnnualPackageIdentifier(): String? =
    (this as? PaywallOfferingState.Ready)
        ?.options
        ?.singleOrNull { option ->
            option.billingPeriod == SubscriptionBillingPeriod.ANNUAL
        }
        ?.packageIdentifier

private fun PurchaseFailureReason.toPaywallFailure(): PaywallOperationFailure = when (this) {
    PurchaseFailureReason.NETWORK -> PaywallOperationFailure.PURCHASE_NETWORK
    PurchaseFailureReason.STORE_UNAVAILABLE ->
        PaywallOperationFailure.PURCHASE_STORE_UNAVAILABLE
    PurchaseFailureReason.INVALID_PACKAGE -> PaywallOperationFailure.INVALID_SELECTION
    PurchaseFailureReason.ENTITLEMENT_NOT_ACTIVATED ->
        PaywallOperationFailure.ENTITLEMENT_NOT_ACTIVATED
    PurchaseFailureReason.PURCHASE_FAILED,
    PurchaseFailureReason.UNKNOWN -> PaywallOperationFailure.PURCHASE_NOT_COMPLETED
}

private fun RestoreFailureReason.toPaywallFailure(): PaywallOperationFailure = when (this) {
    RestoreFailureReason.NETWORK -> PaywallOperationFailure.RESTORE_NETWORK
    RestoreFailureReason.STORE_UNAVAILABLE -> PaywallOperationFailure.RESTORE_STORE_UNAVAILABLE
    RestoreFailureReason.RESTORE_FAILED,
    RestoreFailureReason.UNKNOWN -> PaywallOperationFailure.RESTORE_NOT_COMPLETED
}

private fun SubscriptionOperationUnavailableReason.toPaywallReason(): PaywallUnavailableReason =
    when (this) {
        SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED ->
            PaywallUnavailableReason.SUBSCRIPTIONS_UNAVAILABLE
        SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE ->
            PaywallUnavailableReason.OFFERING_UNAVAILABLE
        SubscriptionOperationUnavailableReason.PACKAGE_UNAVAILABLE ->
            PaywallUnavailableReason.PACKAGE_UNAVAILABLE
    }

private fun CommercialOperationBlockReason.toPaywallOperationState(): PaywallOperationState =
    when (this) {
        CommercialOperationBlockReason.ACCOUNT_REQUIRED ->
            PaywallOperationState.AccountRequired
        CommercialOperationBlockReason.VERIFICATION_REQUIRED ->
            PaywallOperationState.VerificationRequired
        CommercialOperationBlockReason.PREPARING_ACCOUNT ->
            PaywallOperationState.PreparingAccount
        CommercialOperationBlockReason.IDENTITY_UNAVAILABLE ->
            PaywallOperationState.IdentityUnavailable
    }

private const val PLUS_MONTHLY_PACKAGE_IDENTIFIER = "plus_monthly"
private const val PLUS_ANNUAL_PACKAGE_IDENTIFIER = "plus_annual"
