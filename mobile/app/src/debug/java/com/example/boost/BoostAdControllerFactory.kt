package com.example.boost

import android.app.Activity
import android.content.Context
import com.example.BuildConfig
import com.example.subscription.CustomerInfoStore
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.revenuecat.purchases.ExperimentalPreviewRevenueCatPurchasesAPI
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.admob.enableRewardVerification
import com.revenuecat.purchases.admob.loadAndTrackRewardedAd
import com.revenuecat.purchases.admob.setTrackingFullScreenContentCallback
import com.revenuecat.purchases.admob.show
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun createBoostAdController(
    applicationContext: Context,
    customerInfoStore: CustomerInfoStore,
    revenueCatReady: Boolean
): BoostAdController {
    if (
        !BuildConfig.FITDESI_BOOST_ADS_ENABLED ||
        BuildConfig.FITDESI_BOOST_REWARDED_AD_UNIT_ID.isBlank() ||
        !revenueCatReady ||
        !Purchases.isConfigured
    ) {
        return DisabledBoostAdController()
    }
    return RevenueCatAdMobBoostAdController(
        applicationContext = applicationContext.applicationContext,
        customerInfoStore = customerInfoStore,
        purchases = Purchases.sharedInstance,
        rewardedAdUnitId = BuildConfig.FITDESI_BOOST_REWARDED_AD_UNIT_ID
    )
}

@OptIn(ExperimentalPreviewRevenueCatPurchasesAPI::class)
private class RevenueCatAdMobBoostAdController(
    private val applicationContext: Context,
    private val customerInfoStore: CustomerInfoStore,
    private val purchases: Purchases,
    private val rewardedAdUnitId: String
) : BoostAdController {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(applicationContext)
    private val mutableState = MutableStateFlow<BoostAdState>(BoostAdState.NeedsPreparation)
    private val mutablePrivacyOptionsRequired = MutableStateFlow(false)
    private var rewardedAd: RewardedAd? = null
    private var mobileAdsInitialized = false

    override val state: StateFlow<BoostAdState> = mutableState.asStateFlow()
    override val privacyOptionsRequired: StateFlow<Boolean> =
        mutablePrivacyOptionsRequired.asStateFlow()

    override fun prepare(hostActivity: Activity) {
        if (
            mutableState.value == BoostAdState.GatheringConsent ||
            mutableState.value == BoostAdState.Loading ||
            mutableState.value == BoostAdState.Ready ||
            mutableState.value == BoostAdState.Showing ||
            mutableState.value == BoostAdState.Verifying
        ) {
            return
        }
        mutableState.value = BoostAdState.GatheringConsent
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            hostActivity,
            parameters,
            {
                updatePrivacyOptionsRequirement()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(hostActivity) { formError ->
                    updatePrivacyOptionsRequirement()
                    if (formError != null && !consentInformation.canRequestAds()) {
                        mutableState.value = BoostAdState.Unavailable(
                            BoostAdUnavailableReason.CONSENT_UNAVAILABLE
                        )
                    } else {
                        initializeAndLoadIfAllowed()
                    }
                }
            },
            {
                updatePrivacyOptionsRequirement()
                if (consentInformation.canRequestAds()) {
                    initializeAndLoadIfAllowed()
                } else {
                    mutableState.value = BoostAdState.Unavailable(
                        BoostAdUnavailableReason.CONSENT_UNAVAILABLE
                    )
                }
            }
        )
    }

    override fun show(hostActivity: Activity) {
        val ad = rewardedAd
        if (mutableState.value != BoostAdState.Ready || ad == null) return
        rewardedAd = null
        mutableState.value = BoostAdState.Showing
        ad.show(
            activity = hostActivity,
            rewardVerificationStarted = {
                mutableState.value = BoostAdState.Verifying
            },
            rewardVerificationCompleted = { result ->
                val verified = result.verifiedReward != null || result.moreRewards.isNotEmpty()
                if (verified) {
                    customerInfoStore.refresh()
                    mutableState.value = BoostAdState.NeedsPreparation
                } else {
                    mutableState.value = BoostAdState.Unavailable(
                        BoostAdUnavailableReason.VERIFICATION_FAILED
                    )
                }
            }
        )
    }

    override fun showPrivacyOptions(hostActivity: Activity) {
        if (!mutablePrivacyOptionsRequired.value) return
        UserMessagingPlatform.showPrivacyOptionsForm(hostActivity) {
            updatePrivacyOptionsRequirement()
            if (!consentInformation.canRequestAds()) {
                rewardedAd = null
                mutableState.value = BoostAdState.Unavailable(
                    BoostAdUnavailableReason.CONSENT_UNAVAILABLE
                )
            }
        }
    }

    private fun updatePrivacyOptionsRequirement() {
        mutablePrivacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun initializeAndLoadIfAllowed() {
        if (!consentInformation.canRequestAds()) {
            mutableState.value = BoostAdState.Unavailable(
                BoostAdUnavailableReason.CONSENT_UNAVAILABLE
            )
            return
        }
        if (mobileAdsInitialized) {
            loadRewardedAd()
            return
        }
        mutableState.value = BoostAdState.Loading
        MobileAds.initialize(applicationContext) {
            mobileAdsInitialized = true
            loadRewardedAd()
        }
    }

    private fun loadRewardedAd() {
        if (!consentInformation.canRequestAds()) {
            mutableState.value = BoostAdState.Unavailable(
                BoostAdUnavailableReason.CONSENT_UNAVAILABLE
            )
            return
        }
        mutableState.value = BoostAdState.Loading
        purchases.adTracker.loadAndTrackRewardedAd(
            context = applicationContext,
            adUnitId = rewardedAdUnitId,
            adRequest = AdRequest.Builder().build(),
            placement = "fitdesi_boost_contextual",
            loadCallback = object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    ad.enableRewardVerification()
                    ad.setTrackingFullScreenContentCallback(
                        object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                if (mutableState.value == BoostAdState.Showing) {
                                    mutableState.value = BoostAdState.Unavailable(
                                        BoostAdUnavailableReason.VERIFICATION_FAILED
                                    )
                                }
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                rewardedAd = null
                                mutableState.value = BoostAdState.Unavailable(
                                    BoostAdUnavailableReason.AD_UNAVAILABLE
                                )
                            }
                        }
                    )
                    rewardedAd = ad
                    mutableState.value = BoostAdState.Ready
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    mutableState.value = BoostAdState.Unavailable(
                        BoostAdUnavailableReason.AD_UNAVAILABLE
                    )
                }
            }
        )
    }
}
