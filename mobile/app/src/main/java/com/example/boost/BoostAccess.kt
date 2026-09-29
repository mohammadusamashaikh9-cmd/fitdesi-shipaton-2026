package com.example.boost

import com.example.subscription.CustomerInfoState
import com.example.subscription.CustomerInfoStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val FITDESI_BOOST_ENTITLEMENT = "fitdesi_boost"
const val FITDESI_BOOST_DURATION_MINUTES = 30

sealed interface BoostAccessState {
    data object Unknown : BoostAccessState
    data object Inactive : BoostAccessState
    data class Active(val expiresAtEpochMillis: Long) : BoostAccessState
}

interface BoostRepository {
    val state: StateFlow<BoostAccessState>

    fun refreshCustomerInfo()

    fun reevaluate()
}

class DisabledBoostRepository : BoostRepository {
    private val stableState = MutableStateFlow<BoostAccessState>(BoostAccessState.Unknown)

    override val state: StateFlow<BoostAccessState> = stableState.asStateFlow()

    override fun refreshCustomerInfo() = Unit

    override fun reevaluate() = Unit
}

internal class CustomerInfoBoostRepository(
    private val customerInfoStore: CustomerInfoStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timerScope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BoostRepository {
    private val mutableState = MutableStateFlow<BoostAccessState>(BoostAccessState.Unknown)
    private val timerGeneration = AtomicLong(0L)
    private var expirationJob: Job? = null

    override val state: StateFlow<BoostAccessState> = mutableState.asStateFlow()

    init {
        customerInfoStore.observe { reevaluate() }
    }

    override fun refreshCustomerInfo() {
        customerInfoStore.refresh()
    }

    override fun reevaluate() {
        expirationJob?.cancel()
        val generation = timerGeneration.incrementAndGet()
        val next = deriveBoostAccess(customerInfoStore.state.value, nowMillis())
        mutableState.value = next
        if (next is BoostAccessState.Active) {
            val remainingMillis = (next.expiresAtEpochMillis - nowMillis()).coerceAtLeast(1L)
            expirationJob = timerScope?.launch {
                delay(remainingMillis)
                if (timerGeneration.get() == generation) reevaluate()
            }
        }
    }
}

internal fun deriveBoostAccess(
    customerInfoState: CustomerInfoState,
    nowEpochMillis: Long
): BoostAccessState {
    val snapshot = (customerInfoState as? CustomerInfoState.Authoritative)?.snapshot
        ?: return BoostAccessState.Unknown
    if (FITDESI_BOOST_ENTITLEMENT !in snapshot.activeEntitlementIdentifiers) {
        return BoostAccessState.Inactive
    }
    val expiration = snapshot.entitlementExpirationsEpochMillis[FITDESI_BOOST_ENTITLEMENT]
        ?: return BoostAccessState.Unknown
    return if (expiration > nowEpochMillis) {
        BoostAccessState.Active(expiration)
    } else {
        BoostAccessState.Inactive
    }
}
