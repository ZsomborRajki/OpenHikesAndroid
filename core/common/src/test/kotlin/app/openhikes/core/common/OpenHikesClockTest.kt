package app.openhikes.core.common

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OpenHikesClockTest {
    @Test
    fun systemClockReturnsNonEpochInstant() {
        val clock = SystemOpenHikesClock()
        assertThat(clock.now()).isGreaterThan(Instant.EPOCH)
    }

    @Test
    fun defaultDispatchersExposeStandardInstancesAndTurbineCollectsFlows() = runTest {
        val dispatchers = DefaultOpenHikesDispatchers()
        assertThat(dispatchers.io).isNotNull()
        assertThat(dispatchers.default).isNotNull()
        assertThat(dispatchers.main).isNotNull()

        flowOf("trail-ready").test {
            assertThat(awaitItem()).isEqualTo("trail-ready")
            awaitComplete()
        }
    }
}
