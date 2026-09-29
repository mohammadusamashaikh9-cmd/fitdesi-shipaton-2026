package com.example.subscription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionStatus {
    DISABLED,
    LOADING,
    READY,
    ERROR
}

data class SubscriptionState(
    val tier: SubscriptionTier,
    val status: SubscriptionStatus,
    val hasAuthoritativeCustomerInfo: Boolean
)

sealed interface OfferingLoadResult {
    data class Ready(val packages: List<SubscriptionPackage>) : OfferingLoadResult
    data class Invalid(val packageIdentifiers: Set<String>) : OfferingLoadResult
    data class Unavailable(
        val reason: SubscriptionOperationUnavailableReason
    ) : OfferingLoadResult
}

interface SubscriptionRepository {
    val state: StateFlow<SubscriptionState>

    fun refreshCustomerInfo()

    fun loadCurrentOffering(callback: (OfferingLoadResult) -> Unit)
}

internal interface RevenueCatClient : CustomerInfoProvider {
    fun getCurrentOffering(callback: (Result<List<StorePackageReference>>) -> Unit)
}

class DisabledSubscriptionRepository : SubscriptionRepository {
    private val stableState = MutableStateFlow(
        SubscriptionState(
            tier = SubscriptionTier.BASIC,
            status = SubscriptionStatus.DISABLED,
            hasAuthoritativeCustomerInfo = false
        )
    )

    override val state: StateFlow<SubscriptionState> = stableState.asStateFlow()

    override fun refreshCustomerInfo() = Unit

    override fun loadCurrentOffering(callback: (OfferingLoadResult) -> Unit) {
        callback(
            OfferingLoadResult.Unavailable(
                SubscriptionOperationUnavailableReason.REVENUECAT_DISABLED
            )
        )
    }
}

internal class RevenueCatSubscriptionRepository(
    private val client: RevenueCatClient,
    private val customerInfoStore: MutableCustomerInfoStore = RevenueCatCustomerInfoStore(client)
) : SubscriptionRepository {
    private val mutableState = MutableStateFlow(
        SubscriptionState(
            tier = SubscriptionTier.BASIC,
            status = SubscriptionStatus.LOADING,
            hasAuthoritativeCustomerInfo = false
        )
    )

    override val state: StateFlow<SubscriptionState> = mutableState.asStateFlow()

    init {
        customerInfoStore.observe(::applyCustomerInfoState)
    }

    override fun refreshCustomerInfo() {
        customerInfoStore.refresh()
    }

    override fun loadCurrentOffering(callback: (OfferingLoadResult) -> Unit) {
        client.getCurrentOffering { result ->
            result.fold(
                onSuccess = { references ->
                    callback(
                        when (val validation = OfferingValidator.validate(references)) {
                            is OfferingValidationResult.Valid ->
                                OfferingLoadResult.Ready(validation.packages)
                            is OfferingValidationResult.Invalid ->
                                OfferingLoadResult.Invalid(
                                    validation.missingOrMismatchedPackageIdentifiers
                                )
                        }
                    )
                },
                onFailure = {
                    callback(
                        OfferingLoadResult.Unavailable(
                            SubscriptionOperationUnavailableReason.OFFERING_UNAVAILABLE
                        )
                    )
                }
            )
        }
    }

    private fun applyCustomerInfoState(customerInfoState: CustomerInfoState) {
        mutableState.value = when (customerInfoState) {
            CustomerInfoState.Disabled -> SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.DISABLED,
                hasAuthoritativeCustomerInfo = false
            )
            CustomerInfoState.Loading -> SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.LOADING,
                hasAuthoritativeCustomerInfo = false
            )
            CustomerInfoState.Error -> SubscriptionState(
                tier = SubscriptionTier.BASIC,
                status = SubscriptionStatus.ERROR,
                hasAuthoritativeCustomerInfo = false
            )
            is CustomerInfoState.Authoritative -> SubscriptionState(
                tier = customerInfoState.subscriptionTier(),
                status = SubscriptionStatus.READY,
                hasAuthoritativeCustomerInfo = true
            )
            is CustomerInfoState.Stale -> SubscriptionState(
                tier = customerInfoState.subscriptionTier(),
                status = SubscriptionStatus.ERROR,
                hasAuthoritativeCustomerInfo = true
            )
        }
    }
}

private fun CustomerInfoState.Authoritative.subscriptionTier(): SubscriptionTier =
    if (allowsPaidSubscriptions) {
        SubscriptionTierResolver.resolve(snapshot.activeEntitlementIdentifiers)
    } else {
        SubscriptionTier.BASIC
    }

private fun CustomerInfoState.Stale.subscriptionTier(): SubscriptionTier =
    if (allowsPaidSubscriptions) {
        SubscriptionTierResolver.resolve(snapshot.activeEntitlementIdentifiers)
    } else {
        SubscriptionTier.BASIC
    }
