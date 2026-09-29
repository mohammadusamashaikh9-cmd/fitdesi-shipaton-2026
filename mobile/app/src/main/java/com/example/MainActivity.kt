package com.example

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.ui.PersonalTrainerApp
import com.example.ui.theme.MyPersonalTrainerTheme
import com.example.ui.theme.LocalThemeMode
import com.example.viewmodel.AccountViewModel
import com.example.viewmodel.SubscriptionPaywallViewModel
import com.example.viewmodel.TrainerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSplashExit()
        enableEdgeToEdge()
        setContent {
            val fitDesiApplication = application as FitDesiApplication
            val viewModel: TrainerViewModel = composeViewModel()
            val subscriptionPaywallViewModel: SubscriptionPaywallViewModel = composeViewModel()
            val accountViewModel: AccountViewModel = composeViewModel(
                factory = AccountViewModel.factory(
                    repository = fitDesiApplication.authRepository,
                    identityCoordinator = fitDesiApplication.revenueCatIdentityCoordinator
                )
            )
            val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
            val revenueCatIdentityState by
                fitDesiApplication.revenueCatIdentityCoordinator.state.collectAsStateWithLifecycle()
            val currentTheme = userProfile.theme

            CompositionLocalProvider(LocalThemeMode provides currentTheme) {
                MyPersonalTrainerTheme(theme = LocalThemeMode.current) {
                    PersonalTrainerApp(
                        viewModel = viewModel,
                        aiRepository = fitDesiApplication.aiRepository,
                        accountViewModel = accountViewModel,
                        subscriptionPaywallViewModel = subscriptionPaywallViewModel,
                        onPurchaseSubscription = { packageIdentifier ->
                            subscriptionPaywallViewModel.purchase(
                                hostActivity = this@MainActivity,
                                packageIdentifier = packageIdentifier
                            )
                        },
                        onRequestBoost = {
                            subscriptionPaywallViewModel.requestBoost(this@MainActivity)
                        },
                        revenueCatIdentityState = revenueCatIdentityState,
                        onRetryRevenueCatIdentity =
                            fitDesiApplication.revenueCatIdentityCoordinator::retry,
                        onShowBoostPrivacyOptions = {
                            subscriptionPaywallViewModel.showBoostPrivacyOptions(this@MainActivity)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    private fun configureSplashExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        splashScreen.setOnExitAnimationListener { splashView ->
            if (!ValueAnimator.areAnimatorsEnabled()) {
                splashView.remove()
            } else {
                splashView.animate()
                    .alpha(0f)
                    .scaleX(1.025f)
                    .scaleY(1.025f)
                    .setDuration(550L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f))
                    .withEndAction(splashView::remove)
                    .start()
            }
        }
    }
}

