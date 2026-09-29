package com.example.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueCatConfigurationTest {
    @Test
    fun disabledConfigurationDoesNotUseSuppliedKey() {
        assertEquals(
            RevenueCatConfigurationDecision.Disabled,
            RevenueCatConfiguration.resolve(enabled = false, publicKey = "test_public_key")
        )
    }

    @Test
    fun missingOrBlankKeyFailsClosed() {
        assertEquals(
            RevenueCatConfigurationDecision.MissingPublicKey,
            RevenueCatConfiguration.resolve(enabled = true, publicKey = "")
        )
        assertEquals(
            RevenueCatConfigurationDecision.MissingPublicKey,
            RevenueCatConfiguration.resolve(enabled = true, publicKey = "   ")
        )
    }

    @Test
    fun secretLookingKeyIsRejectedCaseInsensitively() {
        assertEquals(
            RevenueCatConfigurationDecision.SecretKeyRejected,
            RevenueCatConfiguration.resolve(enabled = true, publicKey = "sk_secret")
        )
        assertEquals(
            RevenueCatConfigurationDecision.SecretKeyRejected,
            RevenueCatConfiguration.resolve(enabled = true, publicKey = " SK_OTHER ")
        )
    }

    @Test
    fun enabledPublicKeyIsAcceptedWithoutLoggingOrPersistence() {
        val result = RevenueCatConfiguration.resolve(enabled = true, publicKey = " test_public_key ")

        assertTrue(result is RevenueCatConfigurationDecision.Ready)
        assertEquals("test_public_key", (result as RevenueCatConfigurationDecision.Ready).publicKey)
    }

    @Test
    fun releaseBuildIsHardDisabledEvenWhenInputsTryToEnableIt() {
        assertEquals(
            RevenueCatConfigurationDecision.Disabled,
            RevenueCatConfiguration.resolve(
                enabled = true,
                publicKey = "test_public_key",
                releaseBuild = true
            )
        )
    }
}
