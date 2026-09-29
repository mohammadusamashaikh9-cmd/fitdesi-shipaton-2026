package com.example.boost

import android.content.Context
import com.example.subscription.CustomerInfoStore

internal fun createBoostAdController(
    applicationContext: Context,
    customerInfoStore: CustomerInfoStore,
    revenueCatReady: Boolean
): BoostAdController = DisabledBoostAdController()
