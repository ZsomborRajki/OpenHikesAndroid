package app.openhikes.core.testing

import app.openhikes.core.common.OpenHikesDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/**
 * Deterministic [OpenHikesDispatchers] routing all lanes through a single [TestDispatcher].
 */
class TestDispatchers(val testDispatcher: TestDispatcher = StandardTestDispatcher()) : OpenHikesDispatchers {
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
}
