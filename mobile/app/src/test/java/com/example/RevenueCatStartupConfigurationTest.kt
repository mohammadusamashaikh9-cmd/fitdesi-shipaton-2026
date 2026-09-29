package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.revenuecat.purchases.PurchasesConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RevenueCatStartupConfigurationTest {
    @Test
    fun `startup configuration preserves SDK owned anonymous identity`() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val configuration = Class.forName("com.example.FitDesiApplicationKt")
            .getDeclaredMethod(
                "revenueCatPurchasesConfiguration",
                Application::class.java,
                String::class.java
            )
            .invoke(null, application, "test_public_sdk_key") as PurchasesConfiguration

        assertEquals("test_public_sdk_key", configuration.apiKey)
        assertNull(configuration.appUserID)
    }
}
