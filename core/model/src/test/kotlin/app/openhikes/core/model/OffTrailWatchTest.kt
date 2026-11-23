package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OffTrailWatchTest {
    @Test
    fun reportsOnceAfterDwellAndRearmsOnlyAfterRejoining() {
        val watch = OffTrailWatch()
        val t0 = 1_000_000L

        // Unmatched observation changes nothing.
        val (w0, fired0) = watch.observe(null, t0)
        assertThat(fired0).isFalse()
        assertThat(w0.isOffTrail).isFalse()

        // First fix past 150 m starts dwell clock without reporting yet.
        val (w1, fired1) = w0.observe(180.0, t0)
        assertThat(fired1).isFalse()
        assertThat(w1.isOffTrail).isTrue()

        // Before 120 s dwell -> still false.
        val (w2, fired2) = w1.observe(190.0, t0 + 90_000L)
        assertThat(fired2).isFalse()

        // At 120 s dwell -> fires once.
        val (w3, fired3) = w2.observe(200.0, t0 + 120_000L)
        assertThat(fired3).isTrue()

        // Staying off-trail does not repeat.
        val (w4, fired4) = w3.observe(220.0, t0 + 300_000L)
        assertThat(fired4).isFalse()

        // Hysteresis band (75..150 m) neither reports nor re-arms.
        val (w5, fired5) = w4.observe(110.0, t0 + 360_000L)
        assertThat(fired5).isFalse()
        assertThat(w5.isOffTrail).isTrue()

        // Rejoining <= 75 m re-arms for a future departure.
        val (w6, fired6) = w5.observe(40.0, t0 + 400_000L)
        assertThat(fired6).isFalse()
        assertThat(w6.isOffTrail).isFalse()

        val (w7, _) = w6.observe(180.0, t0 + 500_000L)
        val (_, fired8) = w7.observe(180.0, t0 + 620_000L)
        assertThat(fired8).isTrue()
    }

    @Test
    fun pausedMovementWatchTriggersOnDistanceOrBurstAndCapsAtThree() {
        val watch = PausedMovementWatch()
        val t0 = 1_000_000L

        val (w1, f1) = watch.observe(100.0, t0)
        assertThat(f1).isFalse()

        // 260 m burst within 60 s triggers pace rule.
        val (w2, f2) = w1.observe(360.0, t0 + 60_000L)
        assertThat(f2).isTrue()
        val (w3, owed) = w2.spendReminder(t0 + 60_000L)
        assertThat(owed).isWithin(0.01).of(360.0)
        assertThat(w3.remindersSent).isEqualTo(1)

        // Quiet period blocks immediate re-trigger even past 500 m.
        val (w4, f4) = w3.observe(1_000.0, t0 + 120_000L)
        assertThat(f4).isFalse()

        // After 15 min quiet floor -> second reminder fires.
        val (w5, f5) = w4.observe(1_000.0, t0 + 60_000L + OffTrailPolicy.PAUSE_QUIET_FOR_MILLIS)
        assertThat(f5).isTrue()
        val (w6, _) = w5.spendReminder(t0 + 60_000L + OffTrailPolicy.PAUSE_QUIET_FOR_MILLIS)
        assertThat(w6.remindersSent).isEqualTo(2)
    }
}
