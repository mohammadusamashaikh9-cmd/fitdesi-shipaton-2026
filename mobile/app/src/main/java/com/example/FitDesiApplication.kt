package com.example

import android.app.Application
import com.example.ai.AiRepository
import com.example.ai.backend.AiBackendClient
import com.example.ai.backend.AuthenticatedCoachClient
import com.example.ai.backend.RemoteAiConsentRepository
import com.example.ai.conversation.AiCoachConversationRepository
import com.example.boost.BoostAdController
import com.example.boost.BoostRepository
import com.example.boost.CustomerInfoBoostRepository
import com.example.boost.createBoostAdController
import com.example.identity.AuthRepository
import com.example.identity.createFirebaseAuthRepository
import com.example.subscription.CustomerInfoStore
import com.example.subscription.DefaultRevenueCatIdentityCoordinator
import com.example.subscription.DisabledSubscriptionRepository
import com.example.subscription.DisabledSubscriptionPurchaseCoordinator
import com.example.subscription.DisabledCustomerInfoStore
import com.example.subscription.DisabledRevenueCatIdentityCoordinator
import com.example.subscription.RevenueCatConfiguration
import com.example.subscription.RevenueCatConfigurationDecision
import com.example.subscription.RevenueCatIdentityCoordinator
import com.example.subscription.RevenueCatSdkClient
import com.example.subscription.RevenueCatSubscriptionPurchaseCoordinator
import com.example.subscription.RevenueCatSubscriptionRepository
import com.example.subscription.SubscriptionPurchaseCoordinator
import com.example.subscription.SubscriptionRepository
import com.google.firebase.FirebaseApp
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FitDesiApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val authRepository: AuthRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val appContext = applicationContext
        if (FirebaseApp.getApps(appContext).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            checkNotNull(FirebaseApp.initializeApp(appContext)) {
                "Firebase configuration is unavailable."
            }
        }
        createFirebaseAuthRepository()
    }
    val aiRepository: AiRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AiRepository(
            backendService = AiBackendClient.service,
            coachClient = AuthenticatedCoachClient(authRepository, AiBackendClient.service)
        )
    }
    val remoteAiConsentRepository: RemoteAiConsentRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        RemoteAiConsentRepository(authRepository, AiBackendClient.service)
    }
    val aiCoachConversationRepository: AiCoachConversationRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        AiCoachConversationRepository(applicationContext)
    }
    lateinit var subscriptionRepository: SubscriptionRepository
        private set
    lateinit var subscriptionPurchaseCoordinator: SubscriptionPurchaseCoordinator
        private set
    lateinit var customerInfoStore: CustomerInfoStore
        private set
    lateinit var revenueCatIdentityCoordinator: RevenueCatIdentityCoordinator
        private set
    lateinit var boostRepository: BoostRepository
        private set
    lateinit var boostAdController: BoostAdController
        private set

    override fun onCreate() {
        super.onCreate()

        val subscriptionServices = createSubscriptionServices()
        customerInfoStore = subscriptionServices.customerInfoStore
        subscriptionRepository = subscriptionServices.repository
        subscriptionPurchaseCoordinator = subscriptionServices.purchaseCoordinator
        revenueCatIdentityCoordinator = subscriptionServices.identityCoordinator
        boostRepository = CustomerInfoBoostRepository(customerInfoStore)
        boostAdController = createBoostAdController(
            applicationContext = this,
            customerInfoStore = customerInfoStore,
            revenueCatReady = subscriptionServices.revenueCatReady
        )
        revenueCatIdentityCoordinator.reconcile(authRepository.session.value)
        applicationScope.launch {
            authRepository.session.collect(revenueCatIdentityCoordinator::reconcile)
        }
    }

    private fun createSubscriptionServices(): SubscriptionServices {
        val decision = RevenueCatConfiguration.resolve(
            enabled = BuildConfig.FITDESI_REVENUECAT_ENABLED,
            publicKey = BuildConfig.FITDESI_REVENUECAT_PUBLIC_KEY,
            releaseBuild = !BuildConfig.DEBUG
        )
        if (decision !is RevenueCatConfigurationDecision.Ready) {
            return disabledSubscriptionServices()
        }

        return try {
            if (!Purchases.isConfigured) {
                Purchases.configure(
                    revenueCatPurchasesConfiguration(this, decision.publicKey)
                )
            }
            val client = RevenueCatSdkClient(Purchases.sharedInstance)
            val customerInfoStore = com.example.subscription.RevenueCatCustomerInfoStore(client)
            val repository = RevenueCatSubscriptionRepository(client, customerInfoStore)
            val identityCoordinator = DefaultRevenueCatIdentityCoordinator(
                client = client,
                customerInfoStore = customerInfoStore
            )
            SubscriptionServices(
                customerInfoStore = customerInfoStore,
                repository = repository,
                identityCoordinator = identityCoordinator,
                purchaseCoordinator = RevenueCatSubscriptionPurchaseCoordinator(
                    client = client,
                    identityCoordinator = identityCoordinator,
                    authSessionProvider = { authRepository.session.value }
                ),
                revenueCatReady = true
            )
        } catch (_: RuntimeException) {
            disabledSubscriptionServices()
        }
    }

    private fun disabledSubscriptionServices(): SubscriptionServices {
        val customerInfoStore = DisabledCustomerInfoStore()
        val identityCoordinator = DisabledRevenueCatIdentityCoordinator()
        return SubscriptionServices(
            customerInfoStore = customerInfoStore,
            repository = DisabledSubscriptionRepository(),
            purchaseCoordinator = DisabledSubscriptionPurchaseCoordinator(),
            identityCoordinator = identityCoordinator,
            revenueCatReady = false
        )
    }

    private data class SubscriptionServices(
        val customerInfoStore: CustomerInfoStore,
        val repository: SubscriptionRepository,
        val purchaseCoordinator: SubscriptionPurchaseCoordinator,
        val identityCoordinator: RevenueCatIdentityCoordinator,
        val revenueCatReady: Boolean
    )
}

internal fun revenueCatPurchasesConfiguration(
    application: Application,
    publicKey: String
): PurchasesConfiguration = PurchasesConfiguration.Builder(application, publicKey).build()
