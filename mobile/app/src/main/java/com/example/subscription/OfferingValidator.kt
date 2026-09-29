package com.example.subscription

data class StorePackageReference(
    val packageIdentifier: String,
    val productIdentifier: String,
    val localizedPrice: String
)

enum class SubscriptionBillingPeriod {
    MONTHLY,
    ANNUAL
}

data class SubscriptionPackage(
    val packageIdentifier: String,
    val productIdentifier: String,
    val tier: SubscriptionTier,
    val billingPeriod: SubscriptionBillingPeriod,
    val localizedPrice: String
)

sealed interface OfferingValidationResult {
    data class Valid(val packages: List<SubscriptionPackage>) : OfferingValidationResult

    data class Invalid(
        val missingOrMismatchedPackageIdentifiers: Set<String>
    ) : OfferingValidationResult
}

object OfferingValidator {
    private data class RequiredPackage(
        val productIdentifier: String,
        val tier: SubscriptionTier,
        val billingPeriod: SubscriptionBillingPeriod
    )

    private val requiredPackages = linkedMapOf(
        "plus_monthly" to RequiredPackage(
            "fitdesi_plus_monthly",
            SubscriptionTier.PLUS,
            SubscriptionBillingPeriod.MONTHLY
        ),
        "plus_annual" to RequiredPackage(
            "fitdesi_plus_annual",
            SubscriptionTier.PLUS,
            SubscriptionBillingPeriod.ANNUAL
        ),
        "pro_monthly" to RequiredPackage(
            "fitdesi_pro_monthly",
            SubscriptionTier.PRO,
            SubscriptionBillingPeriod.MONTHLY
        ),
        "pro_annual" to RequiredPackage(
            "fitdesi_pro_annual",
            SubscriptionTier.PRO,
            SubscriptionBillingPeriod.ANNUAL
        )
    )

    fun validate(packages: Collection<StorePackageReference>): OfferingValidationResult {
        val packagesByIdentifier = packages.groupBy(StorePackageReference::packageIdentifier)
        val invalidIdentifiers = requiredPackages.mapNotNullTo(linkedSetOf()) {
                (packageIdentifier, required) ->
            val matches = packagesByIdentifier[packageIdentifier]
            packageIdentifier.takeUnless {
                matches?.size == 1 && matches.single().productIdentifier == required.productIdentifier
            }
        }
        if (invalidIdentifiers.isNotEmpty()) {
            return OfferingValidationResult.Invalid(invalidIdentifiers)
        }

        return OfferingValidationResult.Valid(
            requiredPackages.map { (packageIdentifier, required) ->
                val reference = packagesByIdentifier.getValue(packageIdentifier).single()
                SubscriptionPackage(
                    packageIdentifier = packageIdentifier,
                    productIdentifier = required.productIdentifier,
                    tier = required.tier,
                    billingPeriod = required.billingPeriod,
                    localizedPrice = reference.localizedPrice
                )
            }
        )
    }
}
