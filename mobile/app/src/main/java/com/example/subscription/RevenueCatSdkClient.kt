package com.example.subscription

import android.app.Activity
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.logInWith
import com.revenuecat.purchases.logOutWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith

internal class RevenueCatSdkClient(
    private val purchases: Purchases
) : RevenueCatClient, RevenueCatPurchaseClient, RevenueCatIdentityClient {
    private var currentPackagesByIdentifier: Map<String, Package> = emptyMap()

    override val appUserId: String
        get() = purchases.appUserID

    override val isAnonymous: Boolean
        get() = purchases.isAnonymous

    override fun logIn(
        appUserId: String,
        callback: (Result<CustomerInfoSnapshot>) -> Unit
    ) {
        purchases.logInWith(
            appUserID = appUserId,
            onError = { callback(Result.failure(RevenueCatOperationFailure())) },
            onSuccess = { customerInfo, _ ->
                callback(Result.success(customerInfo.toFitDesiSnapshot()))
            }
        )
    }

    override fun logOut(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
        purchases.logOutWith(
            onError = { callback(Result.failure(RevenueCatOperationFailure())) },
            onSuccess = { customerInfo ->
                callback(Result.success(customerInfo.toFitDesiSnapshot()))
            }
        )
    }

    override fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit) {
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
            listener(customerInfo.toFitDesiSnapshot())
        }
    }

    override fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit) {
        purchases.getCustomerInfoWith(
            onError = { callback(Result.failure(RevenueCatOperationFailure())) },
            onSuccess = { customerInfo ->
                callback(Result.success(customerInfo.toFitDesiSnapshot()))
            }
        )
    }

    override fun getCurrentOffering(callback: (Result<List<StorePackageReference>>) -> Unit) {
        purchases.getOfferingsWith(
            onError = {
                currentPackagesByIdentifier = emptyMap()
                callback(Result.failure(RevenueCatOperationFailure()))
            },
            onSuccess = { offerings ->
                val currentOffering = offerings.current
                if (currentOffering == null) {
                    currentPackagesByIdentifier = emptyMap()
                    callback(Result.failure(RevenueCatOperationFailure()))
                } else {
                    val availablePackages = currentOffering.availablePackages
                    val references = availablePackages.map { packageToMap ->
                        StorePackageReference(
                            packageIdentifier = packageToMap.identifier,
                            productIdentifier = packageToMap.product.id,
                            localizedPrice = packageToMap.product.price.formatted
                        )
                    }
                    currentPackagesByIdentifier = when (
                        val validation = OfferingValidator.validate(references)
                    ) {
                        is OfferingValidationResult.Valid -> {
                            val validatedIdentifiers = validation.packages
                                .mapTo(linkedSetOf(), SubscriptionPackage::packageIdentifier)
                            availablePackages
                                .filter { it.identifier in validatedIdentifiers }
                                .associateBy { it.identifier }
                        }
                        is OfferingValidationResult.Invalid -> emptyMap()
                    }
                    callback(
                        Result.success(references)
                    )
                }
            }
        )
    }

    override fun purchase(
        hostActivity: Activity,
        packageIdentifier: String,
        callback: (RevenueCatPurchaseClientResult) -> Unit
    ) {
        val packageToPurchase = currentPackagesByIdentifier[packageIdentifier]
        if (packageToPurchase == null) {
            callback(
                RevenueCatPurchaseClientResult.Unavailable(
                    SubscriptionOperationUnavailableReason.PACKAGE_UNAVAILABLE
                )
            )
            return
        }

        purchases.purchaseWith(
            PurchaseParams.Builder(hostActivity, packageToPurchase).build(),
            onError = { error, userCancelled ->
                callback(
                    if (userCancelled) {
                        RevenueCatPurchaseClientResult.Cancelled
                    } else {
                        RevenueCatPurchaseClientResult.Failed(
                            error.code.toPurchaseFailureReason()
                        )
                    }
                )
            },
            onSuccess = { _, customerInfo ->
                callback(
                    RevenueCatPurchaseClientResult.CustomerInfo(
                        customerInfo.toFitDesiSnapshot()
                    )
                )
            }
        )
    }

    override fun restorePurchases(callback: (RevenueCatRestoreClientResult) -> Unit) {
        purchases.restorePurchasesWith(
            onError = { error ->
                callback(
                    RevenueCatRestoreClientResult.Failed(
                        error.code.toRestoreFailureReason()
                    )
                )
            },
            onSuccess = { customerInfo ->
                callback(
                    RevenueCatRestoreClientResult.CustomerInfo(
                        customerInfo.toFitDesiSnapshot()
                    )
                )
            }
        )
    }

    private fun CustomerInfo.toFitDesiSnapshot(): CustomerInfoSnapshot {
        val active = entitlements.active
        return CustomerInfoSnapshot(
            activeEntitlementIdentifiers = active.keys,
            entitlementExpirationsEpochMillis = active.mapValues { (_, entitlement) ->
                entitlement.expirationDate?.time
            }
        )
    }

    private class RevenueCatOperationFailure : RuntimeException()
}

internal fun PurchasesErrorCode.toPurchaseFailureReason(): PurchaseFailureReason = when (this) {
    PurchasesErrorCode.NetworkError -> PurchaseFailureReason.NETWORK
    PurchasesErrorCode.StoreProblemError,
    PurchasesErrorCode.ProductNotAvailableForPurchaseError,
    PurchasesErrorCode.PurchaseNotAllowedError,
    PurchasesErrorCode.ConfigurationError,
    PurchasesErrorCode.UnsupportedError -> PurchaseFailureReason.STORE_UNAVAILABLE
    PurchasesErrorCode.PurchaseInvalidError,
    PurchasesErrorCode.ProductAlreadyPurchasedError,
    PurchasesErrorCode.ReceiptAlreadyInUseError,
    PurchasesErrorCode.InvalidReceiptError,
    PurchasesErrorCode.MissingReceiptFileError,
    PurchasesErrorCode.PaymentPendingError,
    PurchasesErrorCode.TestStoreSimulatedPurchaseError -> PurchaseFailureReason.PURCHASE_FAILED
    else -> PurchaseFailureReason.UNKNOWN
}

internal fun PurchasesErrorCode.toRestoreFailureReason(): RestoreFailureReason = when (this) {
    PurchasesErrorCode.NetworkError -> RestoreFailureReason.NETWORK
    PurchasesErrorCode.StoreProblemError,
    PurchasesErrorCode.ConfigurationError,
    PurchasesErrorCode.UnsupportedError -> RestoreFailureReason.STORE_UNAVAILABLE
    PurchasesErrorCode.CustomerInfoError,
    PurchasesErrorCode.UnexpectedBackendResponseError,
    PurchasesErrorCode.InvalidReceiptError,
    PurchasesErrorCode.MissingReceiptFileError,
    PurchasesErrorCode.SignatureVerificationError -> RestoreFailureReason.RESTORE_FAILED
    else -> RestoreFailureReason.UNKNOWN
}
