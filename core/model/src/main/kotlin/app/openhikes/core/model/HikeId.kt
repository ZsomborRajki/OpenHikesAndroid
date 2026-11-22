package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identifier for a saved or imported hike.
 */
@Serializable
@JvmInline
value class HikeId(val value: String) {
    init {
        require(value.isNotBlank()) { "HikeId value must not be blank" }
    }
}
