package com.example.boost

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BoostAdUnavailableReason {
    ADS_DISABLED,
    CONSENT_UNAVAILABLE,
    AD_UNAVAILABLE,
    VERIFICATION_FAILED
}

sealed interface BoostAdState {
    data object Disabled : BoostAdState
    data object NeedsPreparation : BoostAdState
    data object GatheringConsent : BoostAdState
    data object Loading : BoostAdState
    data object Ready : BoostAdState
    data object Showing : BoostAdState
    data object Verifying : BoostAdState
    data class Unavailable(val reason: BoostAdUnavailableReason) : BoostAdState
}

interface BoostAdController {
    val state: StateFlow<BoostAdState>
    val privacyOptionsRequired: StateFlow<Boolean>

    fun prepare(hostActivity: Activity)

    fun show(hostActivity: Activity)

    fun showPrivacyOptions(hostActivity: Activity)
}

class DisabledBoostAdController : BoostAdController {
    private val stableState = MutableStateFlow<BoostAdState>(BoostAdState.Disabled)

    override val state: StateFlow<BoostAdState> = stableState.asStateFlow()
    private val stablePrivacyOptionsRequired = MutableStateFlow(false)
    override val privacyOptionsRequired: StateFlow<Boolean> =
        stablePrivacyOptionsRequired.asStateFlow()

    override fun prepare(hostActivity: Activity) = Unit

    override fun show(hostActivity: Activity) = Unit

    override fun showPrivacyOptions(hostActivity: Activity) = Unit
}
