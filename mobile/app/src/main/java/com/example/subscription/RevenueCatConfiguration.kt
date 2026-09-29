package com.example.subscription

sealed interface RevenueCatConfigurationDecision {
    data object Disabled : RevenueCatConfigurationDecision
    data object MissingPublicKey : RevenueCatConfigurationDecision
    data object SecretKeyRejected : RevenueCatConfigurationDecision
    data class Ready(val publicKey: String) : RevenueCatConfigurationDecision
}

object RevenueCatConfiguration {
    fun resolve(
        enabled: Boolean,
        publicKey: String,
        releaseBuild: Boolean = false
    ): RevenueCatConfigurationDecision {
        if (releaseBuild || !enabled) {
            return RevenueCatConfigurationDecision.Disabled
        }

        val normalizedKey = publicKey.trim()
        if (normalizedKey.isEmpty()) {
            return RevenueCatConfigurationDecision.MissingPublicKey
        }
        if (normalizedKey.startsWith("sk_", ignoreCase = true)) {
            return RevenueCatConfigurationDecision.SecretKeyRejected
        }

        return RevenueCatConfigurationDecision.Ready(normalizedKey)
    }
}
