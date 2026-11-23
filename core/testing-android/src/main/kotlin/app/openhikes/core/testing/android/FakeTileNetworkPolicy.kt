package app.openhikes.core.testing.android

import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.network.TileNetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [TileNetworkPolicy] fake for testing online, metered, Data Saver, Battery Saver,
 * and thermal conditions without OS broadcasts.
 */
class FakeTileNetworkPolicy(
    initialConditions: TileNetworkConditions = TileNetworkConditions(),
    private var simulatesOffline: Boolean = false
) : TileNetworkPolicy {
    private val conditionsFlow = MutableStateFlow(initialConditions)
    private val interactiveFlow = MutableStateFlow(
        TileNetworkPolicyEvaluator.evaluate(
            TileNetworkPurpose.INTERACTIVE,
            initialConditions,
            simulatesOffline
        ).isAllowed
    )

    override val conditions: StateFlow<TileNetworkConditions> = conditionsFlow.asStateFlow()
    override val allowsInteractiveRequests: StateFlow<Boolean> = interactiveFlow.asStateFlow()

    fun setConditions(conditions: TileNetworkConditions, offlineOverride: Boolean = simulatesOffline) {
        simulatesOffline = offlineOverride
        conditionsFlow.value = conditions
        interactiveFlow.value = TileNetworkPolicyEvaluator.evaluate(
            TileNetworkPurpose.INTERACTIVE,
            conditions,
            simulatesOffline
        ).isAllowed
    }

    override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
        TileNetworkPolicyEvaluator.evaluate(purpose, conditionsFlow.value, simulatesOffline)
}
