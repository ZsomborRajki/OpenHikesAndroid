package app.openhikes.core.common

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the effective [LaunchConfig] for this process so debug launch Intent overrides merged in
 * the launcher activity propagate consistently to DataStore, map networking, and ViewModels.
 */
@Singleton
class LaunchConfigHolder @Inject constructor(initialConfig: LaunchConfig) {
    private val state = MutableStateFlow(initialConfig)

    val current: StateFlow<LaunchConfig> = state.asStateFlow()

    fun mergeWith(transform: (LaunchConfig) -> LaunchConfig) {
        state.update(transform)
    }
}
