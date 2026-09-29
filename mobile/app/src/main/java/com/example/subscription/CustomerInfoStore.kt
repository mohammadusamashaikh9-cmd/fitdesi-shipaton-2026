package com.example.subscription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CustomerInfoSnapshot(
    val activeEntitlementIdentifiers: Set<String>,
    val entitlementExpirationsEpochMillis: Map<String, Long?>
)

sealed interface CustomerInfoState {
    data object Disabled : CustomerInfoState
    data object Loading : CustomerInfoState
    data object Error : CustomerInfoState
    data class Authoritative(
        val snapshot: CustomerInfoSnapshot,
        val allowsPaidSubscriptions: Boolean = true
    ) : CustomerInfoState
    data class Stale(
        val snapshot: CustomerInfoSnapshot,
        val allowsPaidSubscriptions: Boolean = true
    ) : CustomerInfoState
}

interface CustomerInfoStore {
    val state: StateFlow<CustomerInfoState>

    fun refresh()

    fun observe(observer: (CustomerInfoState) -> Unit)
}

internal interface MutableCustomerInfoStore : CustomerInfoStore {
    fun beginIdentityTransition(generation: Long)

    fun completeIdentityTransition(
        generation: Long,
        snapshot: CustomerInfoSnapshot,
        allowsPaidSubscriptions: Boolean
    ): Boolean

    fun failIdentityTransition(generation: Long): Boolean

    fun acceptAuthoritative(
        generation: Long,
        snapshot: CustomerInfoSnapshot
    ): Boolean
}

internal interface CustomerInfoProvider {
    fun setCustomerInfoListener(listener: (CustomerInfoSnapshot) -> Unit)

    fun getCustomerInfo(callback: (Result<CustomerInfoSnapshot>) -> Unit)
}

internal class DisabledCustomerInfoStore : MutableCustomerInfoStore {
    private val stableState = MutableStateFlow<CustomerInfoState>(CustomerInfoState.Disabled)

    override val state: StateFlow<CustomerInfoState> = stableState.asStateFlow()

    override fun refresh() = Unit

    override fun observe(observer: (CustomerInfoState) -> Unit) {
        observer(stableState.value)
    }

    override fun beginIdentityTransition(generation: Long) = Unit

    override fun completeIdentityTransition(
        generation: Long,
        snapshot: CustomerInfoSnapshot,
        allowsPaidSubscriptions: Boolean
    ): Boolean = false

    override fun failIdentityTransition(generation: Long): Boolean = false

    override fun acceptAuthoritative(
        generation: Long,
        snapshot: CustomerInfoSnapshot
    ): Boolean = false
}

internal class RevenueCatCustomerInfoStore(
    private val provider: CustomerInfoProvider
) : MutableCustomerInfoStore {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<CustomerInfoState>(CustomerInfoState.Loading)
    private val observers = linkedSetOf<(CustomerInfoState) -> Unit>()
    private var currentGeneration = 0L
    private var acceptsCustomerInfo = false
    private var allowsPaidSubscriptions = false

    override val state: StateFlow<CustomerInfoState> = mutableState.asStateFlow()

    override fun refresh() {
        val generation = synchronized(stateLock) {
            if (!acceptsCustomerInfo) return
            if (mutableState.value is CustomerInfoState.Error) {
                publishLocked(CustomerInfoState.Loading)
            }
            currentGeneration
        }
        provider.getCustomerInfo { result ->
            synchronized(stateLock) {
                if (generation != currentGeneration || !acceptsCustomerInfo) {
                    return@synchronized
                }
                result.fold(
                    onSuccess = { snapshot -> acceptAuthoritativeLocked(snapshot) },
                    onFailure = {
                        val failedState = when (val current = mutableState.value) {
                            is CustomerInfoState.Authoritative -> CustomerInfoState.Stale(
                                snapshot = current.snapshot,
                                allowsPaidSubscriptions = current.allowsPaidSubscriptions
                            )
                            is CustomerInfoState.Stale -> current
                            else -> CustomerInfoState.Error
                        }
                        publishLocked(failedState)
                    }
                )
            }
        }
    }

    override fun observe(observer: (CustomerInfoState) -> Unit) {
        synchronized(stateLock) {
            observers += observer
            observer(mutableState.value)
        }
    }

    override fun beginIdentityTransition(generation: Long) {
        synchronized(stateLock) {
            if (generation <= currentGeneration) return
            currentGeneration = generation
            acceptsCustomerInfo = false
            allowsPaidSubscriptions = false
            publishLocked(CustomerInfoState.Loading)
            registerListener(generation)
        }
    }

    override fun completeIdentityTransition(
        generation: Long,
        snapshot: CustomerInfoSnapshot,
        allowsPaidSubscriptions: Boolean
    ): Boolean = synchronized(stateLock) {
        if (generation <= 0L || generation != currentGeneration) return@synchronized false
        this.allowsPaidSubscriptions = allowsPaidSubscriptions
        acceptsCustomerInfo = true
        acceptAuthoritativeLocked(snapshot)
        true
    }

    override fun failIdentityTransition(generation: Long): Boolean = synchronized(stateLock) {
        if (generation != currentGeneration) return@synchronized false
        acceptsCustomerInfo = false
        allowsPaidSubscriptions = false
        publishLocked(CustomerInfoState.Error)
        true
    }

    override fun acceptAuthoritative(
        generation: Long,
        snapshot: CustomerInfoSnapshot
    ): Boolean = synchronized(stateLock) {
        if (generation != currentGeneration || !acceptsCustomerInfo) {
            return@synchronized false
        }
        acceptAuthoritativeLocked(snapshot)
        true
    }

    private fun registerListener(generation: Long) {
        provider.setCustomerInfoListener { snapshot ->
            acceptAuthoritative(generation, snapshot)
        }
    }

    private fun acceptAuthoritativeLocked(snapshot: CustomerInfoSnapshot) {
        publishLocked(
            CustomerInfoState.Authoritative(
                snapshot = snapshot,
                allowsPaidSubscriptions = allowsPaidSubscriptions
            )
        )
    }

    private fun publishLocked(state: CustomerInfoState) {
        mutableState.value = state
        observers.toList().forEach { observer -> observer(state) }
    }
}
