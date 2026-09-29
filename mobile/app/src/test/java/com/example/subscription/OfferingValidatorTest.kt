package com.example.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferingValidatorTest {
    private val exactPackages = listOf(
        StorePackageReference("plus_monthly", "fitdesi_plus_monthly", "Rs 850.00"),
        StorePackageReference("plus_annual", "fitdesi_plus_annual", "Rs 7,100.00"),
        StorePackageReference("pro_monthly", "fitdesi_pro_monthly", "Rs 1,950.00"),
        StorePackageReference("pro_annual", "fitdesi_pro_annual", "Rs 17,100.00")
    )

    @Test
    fun exactFourMappingsAreValidAndAssignedToExpectedTiers() {
        val result = OfferingValidator.validate(exactPackages)

        assertTrue(result is OfferingValidationResult.Valid)
        val packages = (result as OfferingValidationResult.Valid).packages
        assertEquals(4, packages.size)
        assertEquals(
            SubscriptionPackage(
                packageIdentifier = "plus_monthly",
                productIdentifier = "fitdesi_plus_monthly",
                tier = SubscriptionTier.PLUS,
                billingPeriod = SubscriptionBillingPeriod.MONTHLY,
                localizedPrice = "Rs 850.00"
            ),
            packages.first { it.packageIdentifier == "plus_monthly" }
        )
        assertEquals(
            SubscriptionPackage(
                packageIdentifier = "pro_annual",
                productIdentifier = "fitdesi_pro_annual",
                tier = SubscriptionTier.PRO,
                billingPeriod = SubscriptionBillingPeriod.ANNUAL,
                localizedPrice = "Rs 17,100.00"
            ),
            packages.first { it.packageIdentifier == "pro_annual" }
        )
    }

    @Test
    fun everyRequiredPackageFailsClosedWhenMissing() {
        exactPackages.forEach { omitted ->
            val result = OfferingValidator.validate(exactPackages - omitted)

            assertEquals(
                setOf(omitted.packageIdentifier),
                (result as OfferingValidationResult.Invalid).missingOrMismatchedPackageIdentifiers
            )
        }
    }

    @Test
    fun wrongProductIdFailsClosed() {
        val result = OfferingValidator.validate(
            exactPackages.map { reference ->
                if (reference.packageIdentifier == "plus_annual") {
                    reference.copy(productIdentifier = "wrong_product")
                } else {
                    reference
                }
            }
        )

        assertEquals(
            setOf("plus_annual"),
            (result as OfferingValidationResult.Invalid).missingOrMismatchedPackageIdentifiers
        )
    }

    @Test
    fun extraPackagesAreIgnored() {
        val result = OfferingValidator.validate(
            exactPackages + StorePackageReference("intro_offer", "fitdesi_intro", "Rs 100.00")
        )

        assertTrue(result is OfferingValidationResult.Valid)
        assertEquals(4, (result as OfferingValidationResult.Valid).packages.size)
    }

    @Test
    fun legacyMonthlyAndYearlyMappingsAreRejected() {
        val result = OfferingValidator.validate(
            listOf(
                StorePackageReference("monthly", "monthly", "Rs 100.00"),
                StorePackageReference("yearly", "yearly", "Rs 1,000.00")
            )
        )

        assertEquals(
            setOf("plus_monthly", "plus_annual", "pro_monthly", "pro_annual"),
            (result as OfferingValidationResult.Invalid).missingOrMismatchedPackageIdentifiers
        )
    }
}
