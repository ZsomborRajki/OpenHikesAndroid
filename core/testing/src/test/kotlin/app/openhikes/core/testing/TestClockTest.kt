package app.openhikes.core.testing

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test

class TestClockTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun advancesAndResetsInstantDeterministically() {
        val clock = TestClock()
        assertThat(clock.now()).isEqualTo(TestClock.DEFAULT_START_INSTANT)

        clock.advanceBy(Duration.ofMinutes(15))
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-06-15T08:15:00Z"))
        assertThat(clock.monotonicMillis()).isEqualTo(TestClock.DEFAULT_START_INSTANT.toEpochMilli() + 900_000L)

        val updated = Instant.parse("2026-06-15T10:00:00Z")
        clock.set(updated)
        assertThat(clock.now()).isEqualTo(updated)
        // Wall-clock jump must not disturb monotonic elapsed time.
        assertThat(clock.monotonicMillis()).isEqualTo(TestClock.DEFAULT_START_INSTANT.toEpochMilli() + 900_000L)
        clock.advanceMonotonicBy(Duration.ofSeconds(30))
        assertThat(clock.monotonicMillis()).isEqualTo(TestClock.DEFAULT_START_INSTANT.toEpochMilli() + 930_000L)
    }

    @Test
    fun testDispatchersBindAllLanesToTestDispatcher() = runTest(mainDispatcherRule.testDispatcher) {
        val dispatchers = TestDispatchers(mainDispatcherRule.testDispatcher)
        val result = withContext(dispatchers.io) {
            withContext(Dispatchers.Main) {
                "deterministic"
            }
        }
        assertThat(result).isEqualTo("deterministic")
    }
}
