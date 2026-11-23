package app.openhikes.core.location

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates one-shot auto-start requests when entering the recording screen from map controls
 * vs. opening an already running or recovered session.
 */
@Singleton
class RecordingEntryCoordinator @Inject constructor() {
    private val pendingAutoStartState = MutableStateFlow(false)

    val pendingAutoStart: StateFlow<Boolean> = pendingAutoStartState.asStateFlow()

    fun requestAutoStart() {
        pendingAutoStartState.value = true
    }

    fun consumeAutoStart(): Boolean {
        val current = pendingAutoStartState.value
        if (current) {
            pendingAutoStartState.value = false
        }
        return current
    }
}
