package com.example.subscription

import com.revenuecat.purchases.PurchasesErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class RevenueCatFailureMappingTest {
    @Test
    fun purchaseErrorsMapToStableFitDesiReasons() {
        assertEquals(
            PurchaseFailureReason.NETWORK,
            PurchasesErrorCode.NetworkError.toPurchaseFailureReason()
        )
        assertEquals(
            PurchaseFailureReason.STORE_UNAVAILABLE,
            PurchasesErrorCode.StoreProblemError.toPurchaseFailureReason()
        )
        assertEquals(
            PurchaseFailureReason.PURCHASE_FAILED,
            PurchasesErrorCode.PurchaseInvalidError.toPurchaseFailureReason()
        )
        assertEquals(
            PurchaseFailureReason.UNKNOWN,
            PurchasesErrorCode.UnknownBackendError.toPurchaseFailureReason()
        )
    }

    @Test
    fun restoreErrorsMapToStableFitDesiReasons() {
        assertEquals(
            RestoreFailureReason.NETWORK,
            PurchasesErrorCode.NetworkError.toRestoreFailureReason()
        )
        assertEquals(
            RestoreFailureReason.STORE_UNAVAILABLE,
            PurchasesErrorCode.StoreProblemError.toRestoreFailureReason()
        )
        assertEquals(
            RestoreFailureReason.RESTORE_FAILED,
            PurchasesErrorCode.CustomerInfoError.toRestoreFailureReason()
        )
        assertEquals(
            RestoreFailureReason.UNKNOWN,
            PurchasesErrorCode.UnknownBackendError.toRestoreFailureReason()
        )
    }
}
