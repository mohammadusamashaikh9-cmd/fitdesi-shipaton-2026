package com.example.subscription

import com.example.identity.AuthSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface RevenueCatIdentityTarget {
    data object Anonymous : RevenueCatIdentityTarget
    data class Identified(val appUserId: String) : RevenueCatIdentityTarget
    data object Invalid : RevenueCatIdentityTarget
}

sealed interface RevenueCatIdentityState {
    val generation: Long

    data class Disabled(
        override val generation: Long = 0L
    ) : RevenueCatIdentityState

    data class Reconciling(
        override val generation: Long
    ) : RevenueCatIdentityState

    data class AnonymousReady(
        override val generation: Long
    ) : RevenueCatIdentityState

    data class IdentifiedReady(
        override val generation: Long,
        val appUserId: String
    ) : RevenueCatIdentityState

    data class Error(
        override val generation: Long
    ) : RevenueCatIdentityState
}

internal data class RevenueCatOperationIdentity(
    val generation: Long,
    val appUserId: String
)

internal sealed interface RevenueCatOperationGate {
    data object AccountRequired : RevenueCatOperationGate
    data object VerificationRequired : RevenueCatOperationGate
    data object PreparingAccount : RevenueCatOperationGate
    data object IdentityUnavailable : RevenueCatOperationGate
    data class Allowed(
        val identity: RevenueCatOperationIdentity
    ) : RevenueCatOperationGate
}

internal fun revenueCatOperationGate(
    session: AuthSessionState,
    identityState: RevenueCatIdentityState
): RevenueCatOperationGate {
    when (session) {
        AuthSessionState.Initializing -> return RevenueCatOperationGate.PreparingAccount
        AuthSessionState.Guest -> return RevenueCatOperationGate.AccountRequired
        is AuthSessionState.Authenticated -> if (!session.emailVerified) {
            return RevenueCatOperationGate.VerificationRequired
        }
    }

    val target = revenueCatIdentityTarget(session) as? RevenueCatIdentityTarget.Identified
        ?: return RevenueCatOperationGate.IdentityUnavailable
    return when (identityState) {
        is RevenueCatIdentityState.Reconciling -> RevenueCatOperationGate.PreparingAccount
        is RevenueCatIdentityState.IdentifiedReady -> if (
            identityState.appUserId == target.appUserId
        ) {
            RevenueCatOperationGate.Allowed(
                RevenueCatOperationIdentity(
                    generation = identityState.generation,
                    appUserId = identityState.appUserId
                )
            )
        } else {
            RevenueCatOperationGate.IdentityUnavailable
        }
        is RevenueCatIdentityState.Disabled,
        is RevenueCatIdentityState.AnonymousReady,
        is RevenueCatIdentityState.Error -> RevenueCatOperationGate.IdentityUnavailable
    }
}

internal fun isCommercialIdentityReady(
    session: AuthSessionState,
    identityState: RevenueCatIdentityState
): Boolean = revenueCatOperationGate(session, identityState) is RevenueCatOperationGate.Allowed

interface RevenueCatIdentityCoordinator {
    val state: StateFlow<RevenueCatIdentityState>

    fun reconcile(session: AuthSessionState)

    fun retry()

    fun invalidateBeforeAuthExit()
}

internal interface RevenueCatOperationIdentityOwner {
    fun operationIdentity(
        session: AuthSessionState
    ): RevenueCatOperationIdentity?

    fun acceptOperationCustomerInfo(
        identity: RevenueCatOperationIdentity,
        snapshot: CustomerInfoSnapshot
    ): SubscriptionTier?
}

internal interface RevenueCatCommercialIdentityCoordinator :
    RevenueCatIdentityCoordinator,
    RevenueCatOperationIdentityOwner

internal interface RevenueCatIdentityClient : CustomerInfoProvider {
    val appUserId: String
    val isAnonymous: Boolean

    fun logIn(
        appUserId: String,
        callback: (Result<CustomerInfoSnapshot>) -> Unit
    )

    fun logOut(callback: (Result<CustomerInfoSnapshot>) -> Unit)
}

internal class DefaultRevenueCatIdentityCoordinator(
    private val client: RevenueCatIdentityClient,
    private val customerInfoStore: MutableCustomerInfoStore
) : RevenueCatCommercialIdentityCoordinator {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<RevenueCatIdentityState>(
        RevenueCatIdentityState.Reconciling(
            generation = 0L
        )
    )
    override val state: StateFlow<RevenueCatIdentityState> = mutableState.asStateFlow()

    private var currentGeneration = 0L
    private var desiredTarget: RevenueCatIdentityTarget? = null
    private var nextMutationId = 0L
    private var inFlightMutation: IdentityMutation? = null

    override fun reconcile(session: AuthSessionState) {
        val target = revenueCatIdentityTarget(session)
        synchronized(stateLock) {
            val current = mutableState.value
            if (target == desiredTarget &&
                (current is RevenueCatIdentityState.Reconciling ||
                    current is RevenueCatIdentityState.AnonymousReady ||
                    current is RevenueCatIdentityState.IdentifiedReady)
            ) {
                return
            }
            beginReconciliationLocked(target)
        }
    }

    override fun retry() {
        synchronized(stateLock) {
            val target = desiredTarget ?: return
            beginReconciliationLocked(target)
        }
    }

    override fun invalidateBeforeAuthExit() {
        synchronized(stateLock) {
            currentGeneration += 1
            desiredTarget = null
            customerInfoStore.beginIdentityTransition(currentGeneration)
            mutableState.value = RevenueCatIdentityState.Reconciling(currentGeneration)
        }
    }

    override fun operationIdentity(
        session: AuthSessionState
    ): RevenueCatOperationIdentity? = synchronized(stateLock) {
        val target = revenueCatIdentityTarget(session) as? RevenueCatIdentityTarget.Identified
            ?: return@synchronized null
        val ready = mutableState.value as? RevenueCatIdentityState.IdentifiedReady
            ?: return@synchronized null
        if (ready.appUserId != target.appUserId ||
            desiredTarget != target ||
            client.isAnonymous ||
            client.appUserId != target.appUserId
        ) {
            return@synchronized null
        }
        RevenueCatOperationIdentity(
            generation = ready.generation,
            appUserId = ready.appUserId
        )
    }

    override fun acceptOperationCustomerInfo(
        identity: RevenueCatOperationIdentity,
        snapshot: CustomerInfoSnapshot
    ): SubscriptionTier? = synchronized(stateLock) {
        val ready = mutableState.value as? RevenueCatIdentityState.IdentifiedReady
            ?: return@synchronized null
        if (ready.generation != identity.generation ||
            ready.appUserId != identity.appUserId ||
            desiredTarget != RevenueCatIdentityTarget.Identified(identity.appUserId) ||
            client.isAnonymous ||
            client.appUserId != identity.appUserId ||
            !customerInfoStore.acceptAuthoritative(identity.generation, snapshot)
        ) {
            return@synchronized null
        }
        SubscriptionTierResolver.resolve(snapshot.activeEntitlementIdentifiers)
    }

    private fun beginReconciliationLocked(target: RevenueCatIdentityTarget) {
        currentGeneration += 1
        desiredTarget = target
        val generation = currentGeneration
        customerInfoStore.beginIdentityTransition(generation)
        mutableState.value = RevenueCatIdentityState.Reconciling(generation)

        driveLatestReconciliationLocked()
    }

    private fun driveLatestReconciliationLocked() {
        if (inFlightMutation != null) return
        val target = desiredTarget ?: return
        val generation = currentGeneration

        when (target) {
            RevenueCatIdentityTarget.Invalid -> failLocked(generation)
            RevenueCatIdentityTarget.Anonymous -> if (client.isAnonymous) {
                requestCurrentCustomerInfo(generation, target)
            } else {
                launchMutationLocked(generation, target) { callback ->
                    client.logOut(callback)
                }
            }
            is RevenueCatIdentityTarget.Identified -> if (
                !client.isAnonymous && client.appUserId == target.appUserId
            ) {
                requestCurrentCustomerInfo(generation, target)
            } else {
                launchMutationLocked(generation, target) { callback ->
                    client.logIn(target.appUserId, callback)
                }
            }
        }
    }

    private fun launchMutationLocked(
        generation: Long,
        target: RevenueCatIdentityTarget,
        launch: ((Result<CustomerInfoSnapshot>) -> Unit) -> Unit
    ) {
        val mutation = IdentityMutation(
            id = ++nextMutationId,
            generation = generation,
            target = target
        )
        inFlightMutation = mutation
        launch { result -> completeMutation(mutation, result) }
    }

    private fun completeMutation(
        mutation: IdentityMutation,
        result: Result<CustomerInfoSnapshot>
    ) {
        synchronized(stateLock) {
            if (inFlightMutation != mutation) return
            inFlightMutation = null
            if (mutation.generation == currentGeneration && mutation.target == desiredTarget) {
                completeLocked(mutation.generation, mutation.target, result)
            } else {
                driveLatestReconciliationLocked()
            }
        }
    }

    private fun requestCurrentCustomerInfo(
        generation: Long,
        target: RevenueCatIdentityTarget
    ) {
        client.getCustomerInfo { result ->
            complete(generation, target, result)
        }
    }

    private fun complete(
        generation: Long,
        target: RevenueCatIdentityTarget,
        result: Result<CustomerInfoSnapshot>
    ) {
        synchronized(stateLock) {
            if (generation != currentGeneration || target != desiredTarget) return
            completeLocked(generation, target, result)
        }
    }

    private fun completeLocked(
        generation: Long,
        target: RevenueCatIdentityTarget,
        result: Result<CustomerInfoSnapshot>
    ) {
        val snapshot = result.getOrNull()
        if (snapshot == null || !clientMatches(target)) {
            failLocked(generation)
            return
        }
        val allowsPaidSubscriptions = target is RevenueCatIdentityTarget.Identified
        if (!customerInfoStore.completeIdentityTransition(
                generation = generation,
                snapshot = snapshot,
                allowsPaidSubscriptions = allowsPaidSubscriptions
            )
        ) {
            return
        }
        mutableState.value = when (target) {
            RevenueCatIdentityTarget.Anonymous ->
                RevenueCatIdentityState.AnonymousReady(generation)
            is RevenueCatIdentityTarget.Identified ->
                RevenueCatIdentityState.IdentifiedReady(generation, target.appUserId)
            RevenueCatIdentityTarget.Invalid -> RevenueCatIdentityState.Error(generation)
        }
    }

    private fun clientMatches(target: RevenueCatIdentityTarget): Boolean = when (target) {
        RevenueCatIdentityTarget.Anonymous -> client.isAnonymous
        is RevenueCatIdentityTarget.Identified ->
            !client.isAnonymous && client.appUserId == target.appUserId
        RevenueCatIdentityTarget.Invalid -> false
    }

    private fun failLocked(generation: Long) {
        customerInfoStore.failIdentityTransition(generation)
        mutableState.value = RevenueCatIdentityState.Error(generation)
    }

    private data class IdentityMutation(
        val id: Long,
        val generation: Long,
        val target: RevenueCatIdentityTarget
    )
}

internal class DisabledRevenueCatIdentityCoordinator :
    RevenueCatCommercialIdentityCoordinator {
    private val stableState = MutableStateFlow<RevenueCatIdentityState>(
        RevenueCatIdentityState.Disabled()
    )
    override val state: StateFlow<RevenueCatIdentityState> = stableState.asStateFlow()

    override fun reconcile(session: AuthSessionState) = Unit

    override fun retry() = Unit

    override fun invalidateBeforeAuthExit() = Unit

    override fun operationIdentity(
        session: AuthSessionState
    ): RevenueCatOperationIdentity? = null

    override fun acceptOperationCustomerInfo(
        identity: RevenueCatOperationIdentity,
        snapshot: CustomerInfoSnapshot
    ): SubscriptionTier? = null
}

internal fun revenueCatIdentityTarget(session: AuthSessionState): RevenueCatIdentityTarget =
    when (session) {
        AuthSessionState.Initializing,
        AuthSessionState.Guest -> RevenueCatIdentityTarget.Anonymous
        is AuthSessionState.Authenticated -> if (!session.emailVerified) {
            RevenueCatIdentityTarget.Anonymous
        } else {
            val uid = session.uid
            if (uid.length in 1..MAX_FIREBASE_UID_LENGTH_FOR_REVENUECAT &&
                uid.all(::isRevenueCatSafeUidCharacter)
            ) {
                RevenueCatIdentityTarget.Identified("$REVENUECAT_FIREBASE_PREFIX$uid")
            } else {
                RevenueCatIdentityTarget.Invalid
            }
        }
    }

private fun isRevenueCatSafeUidCharacter(character: Char): Boolean =
    character in 'a'..'z' ||
        character in 'A'..'Z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'

private const val REVENUECAT_FIREBASE_PREFIX = "fd_"
private const val MAX_REVENUECAT_APP_USER_ID_LENGTH = 100
private const val MAX_FIREBASE_UID_LENGTH_FOR_REVENUECAT =
    MAX_REVENUECAT_APP_USER_ID_LENGTH - REVENUECAT_FIREBASE_PREFIX.length
